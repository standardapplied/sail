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

/// Shut until opened: whatever waits on it meanwhile goes on once it is.
final class Gate: Sendable {
    private let state = Mutex<(waiting: CheckedContinuation<Void, Never>?, opened: Bool)>((nil, false))

    var isWaiting: Bool {
        state.withLock { $0.waiting != nil }
    }

    func wait() async {
        await withCheckedContinuation { (wait: CheckedContinuation<Void, Never>) in
            let go = state.withLock { state -> Bool in
                if state.opened { return true }
                state.waiting = wait
                return false
            }
            if go { wait.resume() }
        }
    }

    func open() {
        let waiting = state.withLock { state -> CheckedContinuation<Void, Never>? in
            state.opened = true
            defer { state.waiting = nil }
            return state.waiting
        }
        waiting?.resume()
    }
}

/// A passkey that makes and signs whatever it is asked, its sheet up until `sheet` opens when one
/// is given.
struct Signs: PasskeyAuthenticator {
    var sheet: Gate?

    func register(_: PasskeyRegistrationRequest) async throws(PasskeyFailure) -> PasskeyRegistration {
        await sheet?.wait()
        return PasskeyRegistration(credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.create\"}".utf8), attestationObject: Data([0xA3]))
    }

    func assert(_: PasskeyAssertionRequest) async throws(PasskeyFailure) -> PasskeyAssertion {
        await sheet?.wait()
        return PasskeyAssertion(
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
    private let gate = Gate()
    private let state = Mutex<(routes: [String], seen: Int)>(([], 0))

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
        await gate.wait()
        guard let answer else { return try await server.send(request) }
        return HTTPResponse(status: answer.status, headers: answer.headers, body: answer.body)
    }

    func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        throw .unexpected(route: request.route, status: 502)
    }

    func release() {
        gate.open()
    }
}

/// The account pane: a check of who is signed in (the app becoming active) holds no button, so a
/// person's action is never swallowed by it; an answer that comes during a person's action waits
/// for it to end, and is then shown unless who is signed in was set since the check began, said
/// after what the action said.
@MainActor @Suite struct AccountPaneTests {
    static let ticket = "enr_0123456789abcdef0123456789abcdef"

    static func pane(remembering fde: String, _ transport: Held, sheet: Gate? = nil) throws -> AccountPane {
        let defaults = try #require(UserDefaults(suiteName: "sail-account-\(UUID().uuidString)"))
        defaults.set("http://127.0.0.1:7979", forKey: AccountPane.addressKey)
        let account = Account(authenticator: Signs(sheet: sheet), vault: OneSession(fde: fde)) { _ in transport }
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

    /// The same, the check finding a remembered session the control plane does not confirm: the
    /// refusal is said, then why it is not confirmed, and the rows say it is not yet confirmed.
    @Test func aRefusedSignInDuringACheckThatCannotConfirmSaysBoth() async throws {
        let failing = Exchange(method: "GET", path: "/v1/whoami", status: 502, body: Data("{}".utf8))
        let transport = Held(RecordedServer([]), holding: "whoami", then: failing)
        let pane = try Self.pane(remembering: "uday", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })

        pane.signIn.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the sign-in's refusal was never said")
        let refused = pane.lines
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == "uday")
        #expect(pane.lines.starts(with: refused) && pane.lines.count > refused.count, "why it is not confirmed was not said: \(pane.lines)")
        #expect(pane.text.contains("Not yet confirmed: remembered on this Mac until "), "\(pane.text)")
        #expect(!pane.text.contains("Signed in until"), "\(pane.text)")
    }

    /// An enrollment while a check that will find a session is under way: the check's answer is
    /// shown, and the enrollment's sentence kept, but never "Sign in with it." beside the
    /// signed-in rows.
    @Test func anEnrollmentDuringACheckKeepsItsSentenceAndTheCheckSignedIn() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami")
        let pane = try Self.pane(remembering: "uday", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })
        pane.ticket.stringValue = Self.ticket

        pane.enroll.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment said nothing: \(transport.routes)")
        #expect(pane.lines == ["A passkey for @uday is on this Mac.", "Sign in with it."], "\(pane.lines)")
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == "uday", "the check's answer was dropped though the enrollment set no one")
        #expect(pane.lines == ["A passkey for @uday is on this Mac."], "\(pane.lines)")
    }

    /// A check that answers while the enrollment's passkey sheet is up: the pane does not change
    /// under the sheet; once the enrollment ends the check's answer is shown, never beside "Sign
    /// in with it.".
    @Test func aCheckAnsweringDuringAnEnrollmentWaitsForItToEnd() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami")
        let sheet = Gate()
        let pane = try Self.pane(remembering: "uday", transport, sheet: sheet)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })
        pane.ticket.stringValue = Self.ticket
        pane.enroll.performClick(nil)
        #expect(await RunnerTabTests.until { sheet.isWaiting }, "the passkey sheet never came up: \(transport.routes)")

        transport.release()
        await checking.value

        #expect(pane.standing == nil && pane.isActing, "the pane changed under the passkey sheet")
        sheet.open()
        #expect(await RunnerTabTests.until { !pane.isActing }, "the enrollment never ended")
        #expect(pane.standing?.fde == "uday", "the check's answer was never shown")
        #expect(pane.lines == ["A passkey for @uday is on this Mac."], "\(pane.lines)")
    }

    /// A check of an older session that answers while the sign-in's passkey sheet is up: the pane
    /// does not flip under the sheet, and the sign-in, once it ends, is not undone by it.
    @Test func aCheckAnsweringDuringASignInNeitherFlipsThePaneNorUndoesIt() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-login-start"), try Exchange("passkey-login-finish")])
        let transport = Held(server, holding: "whoami")
        let sheet = Gate()
        let pane = try Self.pane(remembering: "someone-before", transport, sheet: sheet)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })
        pane.signIn.performClick(nil)
        #expect(await RunnerTabTests.until { sheet.isWaiting }, "the passkey sheet never came up: \(transport.routes)")

        transport.release()
        await checking.value

        #expect(pane.standing == nil, "the pane flipped to \(pane.standing?.fde ?? "") under the passkey sheet")
        sheet.open()
        #expect(await RunnerTabTests.until { !pane.isActing }, "the sign-in never ended")
        #expect(pane.standing?.fde == "uday", "the check's late answer undid the sign-in: \(pane.standing?.fde ?? "no one")")
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
    /// check's answer, no one signed in, is shown, and said after the enrollment's sentence.
    @Test func anEnrollmentDuringACheckThatFindsTheSessionEndedShowsNoOneAndSaysBoth() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami", after: 1, then: try Exchange("refusal-invalid-bearer-token"))
        let pane = try Self.pane(remembering: "uday", transport)
        await pane.refresh()
        #expect(pane.standing?.fde == "uday", "the first check found no one: \(pane.lines)")
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.filter { $0.contains("whoami") }.count == 2 })
        pane.ticket.stringValue = Self.ticket

        pane.enroll.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment said nothing: \(transport.routes)")
        let enrolled = pane.lines
        transport.release()
        await checking.value
        #expect(pane.standing == nil, "the session the server ended is still shown")
        #expect(pane.lines.starts(with: enrolled) && pane.lines.contains { $0.hasSuffix("has ended.") }, "\(pane.lines)")
    }

    /// Sign Out signs out at once and asks nothing; the menu item after it does nothing more.
    @Test func signOutSignsOutAtOnceAskingNothing() async throws {
        let transport = Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held")
        let pane = try Self.pane(remembering: "uday", transport)
        await pane.refresh()
        #expect(pane.standing?.fde == "uday")

        pane.signOut.performClick(nil)
        pane.signOutHere(nil)

        #expect(pane.standing == nil && !pane.isActing && pane.signIn.isEnabled)
    }
}
