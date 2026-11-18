import AppKit
import Foundation
import SailAPI
import SailKitTestSupport
import SailLook
@testable import SailShell
import Synchronization
import Testing

/// A vault holding one session as `fde`, for any control plane; a session kept is not held.
struct OneSession: TokenVault {
    let fde: String

    func session(for _: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        PasskeySession(sessionToken: "tok-\(fde)", fde: fde, expiresAt: Date(timeIntervalSinceNow: 3600))
    }

    func keep(_: PasskeySession, for _: ControlPlane) throws(VaultFailure) {}

    func forget(_: ControlPlane) throws(VaultFailure) {}
}

/// A passkey that makes and signs whatever it is asked.
struct Signs: PasskeyAuthenticator {
    func register(_: PasskeyRegistrationRequest) async throws(PasskeyFailure) -> PasskeyRegistration {
        PasskeyRegistration(credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.create\"}".utf8), attestationObject: Data([0xA3]))
    }

    func assert(_: PasskeyAssertionRequest) async throws(PasskeyFailure) -> PasskeyAssertion {
        PasskeyAssertion(
            credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.get\"}".utf8), authenticatorData: Data([5]),
            signature: Data([7]), userHandle: Data("uday".utf8)
        )
    }
}

/// The recorded server, but the request whose route holds `holding`, after `skipped` such, is held
/// until released, then answered by the server, or by `answer` when given; every route asked, in
/// order.
final class Held: Transport {
    private let server: RecordedServer
    private let holding: String
    private let skipped: Int
    private let answer: Exchange?
    private let state = Mutex<(routes: [String], held: CheckedContinuation<Void, Never>?, released: Bool, seen: Int)>(([], nil, false, 0))

    init(_ server: RecordedServer, holding: String, after skipped: Int = 0, then answer: Exchange? = nil) {
        self.server = server
        self.holding = holding
        self.skipped = skipped
        self.answer = answer
    }

    var routes: [String] {
        state.withLock { $0.routes }
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        let held = state.withLock { state -> Bool in
            state.routes.append(request.route)
            guard request.route.contains(holding) else { return false }
            state.seen += 1
            return state.seen == skipped + 1
        }
        guard held else { return try await server.send(request) }
        await withCheckedContinuation { (wait: CheckedContinuation<Void, Never>) in
            let go = state.withLock { state -> Bool in
                if state.released { return true }
                state.held = wait
                return false
            }
            if go { wait.resume() }
        }
        guard let answer else { return try await server.send(request) }
        return HTTPResponse(status: answer.status, headers: answer.headers, body: answer.body)
    }

    func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        throw .unexpected(route: request.route, status: 502)
    }

    func release() {
        let waiting = state.withLock { state -> CheckedContinuation<Void, Never>? in
            state.released = true
            defer { state.held = nil }
            return state.held
        }
        waiting?.resume()
    }
}

/// The account pane: a check of who is signed in (the app becoming active) holds no button, so a
/// person's action is never swallowed by it; its late answer is shown unless who is signed in was
/// set since it began, and what the person's action said is kept.
@MainActor @Suite struct AccountPaneTests {
    static func pane(remembering fde: String, _ transport: Held) throws -> AccountPane {
        let defaults = try #require(UserDefaults(suiteName: "sail-account-\(UUID().uuidString)"))
        defaults.set("http://127.0.0.1:7979", forKey: AccountPane.addressKey)
        let account = Account(authenticator: Signs(), vault: OneSession(fde: fde)) { _ in transport }
        return AccountPane(
            account: account, look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts),
            defaults: defaults
        )
    }

    /// A sign-in the server refuses, clicked while a check that will find a session is under
    /// way: the click starts it, its refusal is said, and the check's answer is still shown
    /// (the refusal set no one) with the refusal kept.
    @Test func aRefusedSignInDuringACheckLeavesTheCheckSignedInAndItsRefusalSaid() async throws {
        let transport = Held(RecordedServer([try Exchange("whoami")]), holding: "whoami")
        let pane = try Self.pane(remembering: "uday", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } }, "the check asked nothing: \(transport.routes)")
        #expect(pane.signIn.isEnabled, "the check holds Sign In")

        pane.signIn.performClick(nil)

        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("login") } }, "the click started no sign-in: \(transport.routes)")
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the sign-in's refusal was never said")
        let refused = pane.lines
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == "uday", "the check's answer was dropped though the refusal set no one")
        #expect(pane.lines == refused, "the refusal is no longer said: \(pane.lines)")
        #expect(!pane.busy)
    }

    /// An enrollment while a check that will find a session is under way: its sentence is kept,
    /// and the check's answer shown.
    @Test func anEnrollmentDuringACheckKeepsItsSentenceAndTheCheckSignedIn() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami")
        let pane = try Self.pane(remembering: "uday", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })
        pane.ticket.stringValue = "enr_0123456789abcdef0123456789abcdef"

        pane.enroll.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment said nothing: \(transport.routes)")
        let enrolled = pane.lines
        #expect(enrolled.first?.hasPrefix("A passkey for @") == true, "\(enrolled)")
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == "uday", "the check's answer was dropped though the enrollment set no one")
        #expect(pane.lines == enrolled, "the enrollment's sentence is no longer said: \(pane.lines)")
    }

    /// A sign-in that succeeds while a check of an older session is under way: the check's late
    /// answer, the older session, would undo it, so it is not shown.
    @Test func aSignInDuringACheckIsNotUndoneByTheChecksLateAnswer() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-login-start"), try Exchange("passkey-login-finish")])
        let transport = Held(server, holding: "whoami")
        let pane = try Self.pane(remembering: "someone-before", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })

        pane.signIn.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && pane.standing != nil }, "the sign-in did not sign in: \(pane.lines)")
        let signedIn = pane.standing?.fde
        #expect(signedIn != nil && signedIn != "someone-before")
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == signedIn, "the check's late answer undid the sign-in: \(pane.standing?.fde ?? "no one")")
    }

    /// An enrollment while a check finds the session ended: the enrollment set no one, so the
    /// check's answer, no one signed in, is shown, and the enrollment's sentence kept.
    @Test func anEnrollmentDuringACheckThatFindsTheSessionEndedShowsNoOneAndKeepsItsSentence() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami", after: 1, then: try Exchange("refusal-invalid-bearer-token"))
        let pane = try Self.pane(remembering: "uday", transport)
        await pane.refresh()
        #expect(pane.standing?.fde == "uday", "the first check found no one: \(pane.lines)")
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.filter { $0.contains("whoami") }.count == 2 })
        pane.ticket.stringValue = "enr_0123456789abcdef0123456789abcdef"

        pane.enroll.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment said nothing: \(transport.routes)")
        let enrolled = pane.lines
        transport.release()
        await checking.value
        #expect(pane.standing == nil, "the session the server ended is still shown")
        #expect(pane.lines == enrolled, "the enrollment's sentence is no longer said: \(pane.lines)")
    }
}
