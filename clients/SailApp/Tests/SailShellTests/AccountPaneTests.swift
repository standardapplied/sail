import AppKit
import Foundation
import SailAPI
import SailLook
@testable import SailShell
import SailTestPasskey
import Synchronization
import Testing

/// A vault holding one session, for any control plane.
struct OneSession: TokenVault {
    let held = PasskeySession(sessionToken: "tok", fde: "uday", expiresAt: Date(timeIntervalSinceNow: 3600))

    func session(for _: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        held
    }

    func keep(_: PasskeySession, for _: ControlPlane) throws(VaultFailure) {}

    func forget(_: ControlPlane) throws(VaultFailure) {}
}

/// A control plane that holds the question of who is signed in until released, then answers it
/// with a 502, and answers everything else with a 502 at once; every route asked, in order.
final class HeldCheck: Transport {
    private let state = Mutex<(routes: [String], held: CheckedContinuation<Void, Never>?, released: Bool)>(([], nil, false))

    var routes: [String] {
        state.withLock { $0.routes }
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        state.withLock { $0.routes.append(request.route) }
        if request.route.contains("whoami") {
            await withCheckedContinuation { (wait: CheckedContinuation<Void, Never>) in
                let go = state.withLock { state -> Bool in
                    if state.released { return true }
                    state.held = wait
                    return false
                }
                if go { wait.resume() }
            }
        }
        throw .unexpected(route: request.route, status: 502)
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
/// person's sign-in is never swallowed by it, and its late answer is never shown over theirs.
@MainActor @Suite struct AccountPaneTests {
    @Test func aSignInStartsWhileACheckOfWhoIsSignedInIsUnderWay() async throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-account-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let defaults = try #require(UserDefaults(suiteName: "sail-account-\(UUID().uuidString)"))
        defaults.set("http://127.0.0.1:7979", forKey: AccountPane.addressKey)
        let transport = HeldCheck()
        let account = Account(authenticator: SoftwarePasskey(file: folder.appending(path: "passkey.json")), vault: OneSession()) { _ in transport }
        let pane = AccountPane(
            account: account, look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts),
            defaults: defaults
        )
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } }, "the check asked nothing: \(transport.routes)")
        #expect(pane.signIn.isEnabled, "the check holds Sign In")

        pane.signIn.performClick(nil)

        #expect(await RunnerTabTests.until { transport.routes.contains { !$0.contains("whoami") } }, "the click started no sign-in: \(transport.routes)")
        #expect(await RunnerTabTests.until { pane.actions == 1 && !pane.lines.isEmpty }, "the sign-in's refusal was never said")
        transport.release()
        await checking.value
        #expect(pane.standing == nil, "the check's late answer was shown over the sign-in")
        #expect(!pane.lines.isEmpty, "the sign-in's refusal is no longer said")
        #expect(!pane.busy)
    }
}
