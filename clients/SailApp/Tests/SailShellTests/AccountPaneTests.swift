import AppKit
import Foundation
import SailAPI
import SailControls
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

/// Shut until opened: whatever waits on it meanwhile goes on once it is, or once the task waiting
/// is cancelled, by a test's limit or by the code under test.
final class Gate: Sendable {
    private let state = Mutex<(waiting: CheckedContinuation<Void, Never>?, opened: Bool)>((nil, false))

    var isWaiting: Bool {
        state.withLock { $0.waiting != nil }
    }

    func wait() async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { (wait: CheckedContinuation<Void, Never>) in
                let go = state.withLock { state -> Bool in
                    if state.opened { return true }
                    state.waiting = wait
                    return false
                }
                if go { wait.resume() }
            }
        } onCancel: {
            open()
        }
    }

    /// Returns once opened, or once `action` ended without opening it: a test waiting on what an
    /// action should do fails at once, rather than hangs, when the action ends without doing it.
    func wait(during action: Task<Void, Never>?) async {
        Task { [self] in
            await action?.value
            open()
        }
        await wait()
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
/// is given; `asked` opens as it is asked; `failing`, it ends so instead; `failingSignIn`, only its
/// sign-ins do.
struct Signs: PasskeyAuthenticator {
    var sheet: Gate?
    var asked: Gate?
    var failing: PasskeyFailure?
    var failingSignIn: PasskeyFailure?

    func register(_: PasskeyRegistrationRequest) async throws(PasskeyFailure) -> PasskeyRegistration {
        asked?.open()
        await sheet?.wait()
        if let failing { throw failing }
        return PasskeyRegistration(credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.create\"}".utf8), attestationObject: Data([0xA3]))
    }

    func assert(_: PasskeyAssertionRequest) async throws(PasskeyFailure) -> PasskeyAssertion {
        asked?.open()
        await sheet?.wait()
        if let failing = failing ?? failingSignIn { throw failing }
        return PasskeyAssertion(
            credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.get\"}".utf8), authenticatorData: Data([5]),
            signature: Data([7]), userHandle: Data("uday".utf8)
        )
    }
}

/// The recorded server, but the request whose route holds `holding`, after `skipped` such, is held
/// until released, then answered by the server, or by `answer` when given; every route asked, in
/// order. A row's sessions listing, asked beside each list of workspaces, is never the one held or
/// counted: what a test holds is a person's action or a list, and a listing that matched took the
/// place of the request a test meant, silently: one test held nothing it meant to and failed, and
/// another passed while holding nothing at all. ``arrived(during:)`` returns once the held request came.
final class Held: Transport {
    private let server: RecordedServer
    private let holding: String
    private let skipped: Int
    private let answer: Exchange?
    private let gate = Gate()
    private let arrival = Gate()
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
            guard request.route.contains(holding), !request.route.hasSuffix("/sessions") else { return false }
            state.seen += 1
            return state.seen == skipped + 1
        }
        guard held else { return try await server.send(request) }
        arrival.open()
        await gate.wait()
        guard let answer else { return try await server.send(request) }
        return HTTPResponse(status: answer.status, headers: answer.headers, body: answer.body)
    }

    func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        throw .unexpected(route: request.route, status: 502)
    }

    /// Returns once the held request came, or once `action`, which should send it, ended without.
    func arrived(during action: Task<Void, Never>?) async {
        await arrival.wait(during: action)
    }

    func release() {
        gate.open()
    }
}

/// The exchanges a sign-in makes and the `whoami` after it.
func signInExchanges() throws -> [Exchange] {
    [try Exchange("passkey-login-start"), try Exchange("passkey-login-finish"), try Exchange("whoami")]
}

/// The flow of who is signed in (#462): a check (the app becoming active) holds no button, so a
/// person's action is never swallowed by it; an answer that comes during a person's action waits
/// for it to end, and is then shown unless who is signed in was set since the check began.
@MainActor @Suite(.timeLimit(.minutes(1))) struct AccountFlowTests {
    init() {
        ExitWatch.install()
    }

    static let plane = try! ControlPlane(address: "http://127.0.0.1:7979")

    /// A flow at the drive's control plane, reached on this Mac, holding a session as `fde`.
    static func flow(
        remembering fde: String, _ transport: any Transport, sheet: Gate? = nil, asked: Gate? = nil, defaults: UserDefaults = ReattachTests.defaults(),
        now: @escaping @MainActor () -> Date = { Date() }
    ) -> AccountFlow {
        SignInMemory(defaults).reached(plane)
        let account = Account(authenticator: Signs(sheet: sheet, asked: asked), vault: OneSession(fde: fde)) { _ in transport }
        return AccountFlow(account: account, memory: SignInMemory(defaults), now: now)
    }

    /// A person's sign-in, its refusal when it was refused.
    static func signIn(_ flow: AccountFlow) async -> AccountRefusal? {
        var refused: AccountRefusal?
        flow.signIn(at: plane) { refused = $0 }
        await flow.underWay?.value
        return refused
    }

    @Test func aRefusedSignInDuringACheckLeavesTheCheckSignedIn() async throws {
        let transport = Held(RecordedServer([try Exchange("whoami")]), holding: "whoami")
        let flow = Self.flow(remembering: "uday", transport)
        let checking = Task { await flow.refresh() }
        await transport.arrived(during: checking)
        #expect(!flow.isActing, "the check holds the buttons")

        let refused = await Self.signIn(flow)

        #expect(refused?.reason == .unreachable && transport.routes.contains { $0.contains("login") }, "the press started no sign-in: \(transport.routes)")
        transport.release()
        await checking.value
        #expect(flow.standing?.fde == "uday", "the check's answer was dropped though the refusal set no one")
        #expect(!flow.busy)
    }

    /// A check that answers while a person's passkey sheet is up changes nothing under it; once the
    /// sign-in ends, the check's late answer, the older session, does not undo it.
    @Test func aCheckAnsweringDuringASignInNeitherFlipsNorUndoesIt() async throws {
        let transport = Held(RecordedServer([try Exchange("whoami")] + (try signInExchanges())), holding: "whoami")
        let sheet = Gate(), asked = Gate()
        let flow = Self.flow(remembering: "someone-before", transport, sheet: sheet, asked: asked)
        let checking = Task { await flow.refresh() }
        await transport.arrived(during: checking)
        flow.signIn(at: Self.plane) { _ in }
        await asked.wait(during: flow.underWay)

        transport.release()
        await checking.value

        #expect(flow.standing == nil && flow.isActing, "the flow changed under the passkey sheet")
        sheet.open()
        await flow.underWay?.value
        #expect(flow.standing?.fde == "uday", "the check's late answer undid the sign-in: \(flow.standing?.fde ?? "no one")")
    }

    /// Enrolling and the sign-in after it are one person's action: a check that answers while the
    /// enrollment's sheet is up waits, and the sign-in it ends in is not undone by it.
    @Test func aCheckDuringAnEnrollmentWaitsAndTheSignInAfterItStands() async throws {
        let exchanges = [try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")] + (try signInExchanges())
        let transport = Held(RecordedServer(exchanges), holding: "whoami")
        let sheet = Gate(), asked = Gate()
        let flow = Self.flow(remembering: "someone-before", transport, sheet: sheet, asked: asked)
        let checking = Task { await flow.refresh() }
        await transport.arrived(during: checking)
        var created: String?
        flow.enroll(ticket: AccountPaneTests.ticket, at: Self.plane, created: { created = $0 }, refused: { _ in })
        await asked.wait(during: flow.underWay)
        transport.release()
        await checking.value
        #expect(flow.standing == nil && flow.isActing && created == nil, "the flow changed under the passkey sheet")
        sheet.open()
        await flow.underWay?.value
        #expect(created == "uday" && flow.standing?.fde == "uday", "enrolling did not sign in, or the check undid it: \(flow.standing?.fde ?? "no one")")
    }

    /// Enrolling signs in at once: two ceremonies, the session kept, the person remembered.
    @Test func enrollingSignsIn() async throws {
        let server = RecordedServer([try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")] + (try signInExchanges()))
        let defaults = ReattachTests.defaults()
        let flow = Self.flow(remembering: "uday", server, defaults: defaults)
        var signedInByAPerson = false
        flow.onStanding = { signedIn, byAPerson in signedInByAPerson = signedIn != nil && byAPerson }
        flow.enroll(ticket: AccountPaneTests.ticket, at: Self.plane, created: { _ in }, refused: { _ in })
        await flow.underWay?.value
        #expect(await server.requests.map(\.path) == ["/v1/auth/register/start", "/v1/auth/register/finish", "/v1/auth/login/start", "/v1/auth/login/finish", "/v1/whoami"])
        #expect(flow.standing?.fde == "uday" && signedInByAPerson)
        #expect(SignInMemory(defaults).signIn(at: Self.plane) == RememberedSignIn(handle: "uday", name: "Uday"))
    }

    /// Continue remembers the control plane only once it answered Sail's health.
    @Test func theAddressIsRememberedOnlyOnceItAnswered() async throws {
        let transport = Held(RecordedServer([try Exchange("health")]), holding: "health")
        let defaults = ReattachTests.defaults()
        let flow = AccountFlow(account: Account(authenticator: Signs(), vault: OneSession(fde: "uday"), reachWithin: .seconds(Patience.answer)) { _ in transport }, memory: SignInMemory(defaults))
        var reached: ControlPlane?
        flow.reach("sail.example.com") { reached = try? $0.get() }
        await transport.arrived(during: flow.underWay)
        #expect(SignInMemory(defaults).plane == nil, "the address was remembered before it answered")
        transport.release()
        await flow.underWay?.value
        #expect(reached?.description == "https://sail.example.com" && SignInMemory(defaults).plane == reached)
        let refused = AccountFlow(account: Account(authenticator: Signs(), vault: OneSession(fde: "uday")) { _ in RecordedServer([]) }, memory: SignInMemory(ReattachTests.defaults()))
        refused.reach("sail.example.com") { _ in }
        await refused.underWay?.value
        #expect(refused.memory.plane == nil, "an address that did not answer was remembered")
    }

    /// Continue's answer in hand as the address is edited (the edit handled while the answer comes
    /// back to the main actor, after the health call's own check of the stop) is neither remembered
    /// nor said: the action taking it is already stopped when it is taken.
    @Test func anAnswerTakenAfterTheStopIsNeitherRememberedNorSaid() async throws {
        let defaults = ReattachTests.defaults()
        let flow = AccountFlow(account: Account(authenticator: Signs(), vault: OneSession(fde: "uday")) { _ in RecordedServer([]) }, memory: SignInMemory(defaults))
        let late = Task { () -> Bool in
            var said = false
            flow.reached(Self.plane) { _ in said = true }
            return said
        }
        late.cancel()
        let said = await late.value
        #expect(SignInMemory(defaults).plane == nil && !said, "an answer taken after the stop was \(said ? "said" : "remembered")")
        var taken: ControlPlane?
        flow.reached(Self.plane) { taken = try? $0.get() }
        #expect(SignInMemory(defaults).plane == Self.plane && taken == Self.plane, "an answer taken with no stop was dropped")
    }

    /// A sign-in kept in a shape the vault cannot read is gone once read: the check says so, and a
    /// launch that finds it says so too, rather than reading it away unsaid.
    @Test func aSignInTheVaultCannotReadIsSaidByTheReadThatFindsIt() async throws {
        let defaults = ReattachTests.defaults()
        SignInMemory(defaults).reached(Self.plane)
        for launch in [false, true] {
            let flow = AccountFlow(account: Account(authenticator: Signs(), vault: UnreadableOnce()) { _ in RecordedServer([]) }, memory: SignInMemory(defaults))
            var told: AccountRefusal.Reason?
            flow.onChecked = { answer, _ in if case let .failure(refusal) = answer { told = refusal.reason } }
            if launch {
                #expect(!flow.holdsASession())
            } else {
                await flow.refresh()
            }
            #expect(told == .unreadableSignIn, "\(launch ? "the launch" : "the check") said \(String(describing: told))")
        }
    }

    /// A check that finds the session ended says so with the day it ended: the session's own end
    /// when that had passed, else the day the app was told; no one is signed in after it.
    @Test(arguments: [-86_400.0, 86_400.0])
    func anEndedSessionEndedOnItsEndOrToday(_ offset: Double) async throws {
        let now = Date(timeIntervalSince1970: 1_795_608_000)
        let vault = MemoryVaultOf(PasskeySession(sessionToken: "tok", fde: "uday", expiresAt: now.addingTimeInterval(offset)))
        let defaults = ReattachTests.defaults()
        SignInMemory(defaults).reached(Self.plane)
        let server = RecordedServer([try Exchange("refusal-invalid-bearer-token")])
        let flow = AccountFlow(account: Account(authenticator: Signs(), vault: vault) { _ in server }, memory: SignInMemory(defaults), now: { now })
        var told: (Result<SignedIn?, AccountRefusal>, Date?)?
        flow.onChecked = { told = ($0, $1) }
        await flow.refresh()
        #expect(flow.standing == nil && told?.1 == min(now, now.addingTimeInterval(offset)), "told \(String(describing: told))")
    }
}

/// A vault whose one item it cannot read: the first read removes it and says so, as the Keychain's
/// vault does an item of an older shape; later reads find none.
final class UnreadableOnce: TokenVault, Sendable {
    private let read = Mutex(false)

    func session(for _: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        guard !read.withLock({ $0 }) else { return nil }
        read.withLock { $0 = true }
        throw VaultFailure(status: "the item is not a sign-in Sail kept", unreadable: true)
    }

    func keep(_: PasskeySession, for _: ControlPlane) throws(VaultFailure) {}

    func forget(_: ControlPlane) throws(VaultFailure) {}
}

/// A vault holding one session for any control plane, until it is forgotten; none at first unless given.
final class MemoryVaultOf: TokenVault, Sendable {
    private let held: Mutex<PasskeySession?>

    init(_ session: PasskeySession? = nil) {
        held = Mutex(session)
    }

    func session(for _: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        held.withLock { $0 }
    }

    func keep(_ session: PasskeySession, for _: ControlPlane) throws(VaultFailure) {
        held.withLock { $0 = session }
    }

    func forget(_: ControlPlane) throws(VaultFailure) {
        held.withLock { $0 = nil }
    }
}

/// The Account pane, signed in: the menu's language, live as checks start and end.
@MainActor @Suite(.timeLimit(.minutes(1))) struct AccountPaneTests {
    init() {
        ExitWatch.install()
    }

    static let ticket = "enr_0123456789abcdef0123456789abcdef"

    /// The pane over a flow at the drive's control plane holding a session as `fde`, drawn as the
    /// shell draws it at each change.
    static func pane(remembering fde: String, _ transport: any Transport) throws -> AccountPane {
        let flow = AccountFlowTests.flow(remembering: fde, transport)
        let pane = AccountPane(
            flow: flow, look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts),
            labels: try LabelFont(directory: LookTests.fonts)
        )
        flow.onChange = { [weak pane] in pane?.show() }
        return pane
    }

    /// The pane in a window `width` wide, laid out.
    static func shown(_ pane: AccountPane, width: Double = 900, look: Look = .light) -> NSWindow {
        pane.look = look
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 700), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = pane
        pane.layoutSubtreeIfNeeded()
        return window
    }

    static func eyebrows(_ pane: AccountPane) -> [String] {
        WorkspacesViewTests.found(NSTextField.self, in: pane).filter { !$0.isHiddenOrHasHiddenAncestor && pane.eyebrows.contains($0) }.map(\.stringValue)
    }

    /// Someone signed in, for a shell's tests that need the Account pane shown.
    static let someone = SignedIn(
        controlPlane: AccountFlowTests.plane, fde: "uday", until: Date(timeIntervalSinceNow: 3600),
        whoami: .success(Whoami(fde: "uday", name: "uday", displayName: "Uday", role: .known(.admin), capabilities: []))
    )

    /// Signed in, the pane speaks the menu's language: the person's name as the display heading,
    /// mono eyebrows over the facts (SIGNED IN AS, CONTROL PLANE), `@handle · Role`, the control
    /// plane as the menu's line (its address, state square and word), the session's end in quiet
    /// text, and Sign Out the secondary button.
    @Test(arguments: [Look.light, .dark])
    func signedInItIsTheMenusLanguage(_ look: Look) async throws {
        let pane = try Self.pane(remembering: "uday", Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held"))
        let window = Self.shown(pane, look: look)
        defer { window.orderOut(nil) }
        await pane.flow.refresh()
        pane.layoutSubtreeIfNeeded()
        #expect(Self.eyebrows(pane) == ["SIGNED IN AS", "CONTROL PLANE"], "the eyebrows are \(Self.eyebrows(pane))")
        #expect(pane.eyebrows.allSatisfy { $0.textColor == NSColor(look.textSecondary) && $0.stringValue == $0.stringValue.uppercased() })
        #expect(pane.handle.stringValue == "@uday · Admin" && pane.planeRow.said == "127.0.0.1:7979" && pane.planeRow.plane == .connected)
        #expect(pane.until.stringValue.hasPrefix("Signed in until ") && pane.until.textColor == NSColor(look.textTertiary))
        #expect(pane.signOut.kind == .secondary && !pane.signOut.isHiddenOrHasHiddenAncestor)
        let lines = pane.text.split(separator: "\n").map(String.init)
        #expect(Array(lines.prefix(3)) == ["Uday", "@uday · Admin", "127.0.0.1:7979 · Connected"] && lines.count == 4, "the pane says \(lines)")
    }

    /// Why `whoami` could not confirm the session is said under the control plane's line, its
    /// sentence crimson after the error's mark.
    @Test(arguments: [Look.light, .dark])
    func aSessionNotConfirmedSaysWhyUnderTheLine(_ look: Look) async throws {
        let failing = Held(RecordedServer([]), holding: "whoami", then: Exchange(method: "GET", path: "/v1/whoami", status: 502, body: Data("{}".utf8)))
        let pane = try Self.pane(remembering: "uday", failing)
        let window = Self.shown(pane, look: look)
        defer { window.orderOut(nil) }
        failing.release()
        await pane.flow.refresh()
        pane.layoutSubtreeIfNeeded()
        let said = pane.said.attributedStringValue
        try #require(said.length > 0, "nothing is said under the line: \(pane.text)")
        #expect(said.containsAttachments && !pane.said.isHidden && pane.until.stringValue.hasPrefix("Not yet confirmed"), "\(pane.text)")
        #expect((said.attribute(.foregroundColor, at: said.length - 1, effectiveRange: nil) as? NSColor) != nil)
        let line = pane.convert(pane.planeRow.bounds, from: pane.planeRow), under = pane.convert(pane.said.bounds, from: pane.said)
        #expect(under.maxY <= line.minY + 0.5, "the refusal stands at \(under), not under the line \(line)")
    }

    /// The control plane's line is live as the menu's is: out of reach once a check found it so,
    /// reconnecting while one asks again, then its answer.
    @Test func theControlPlanesLineIsLive() async throws {
        let transport = OutOfReach()
        let pane = try Self.pane(remembering: "uday", transport)
        let window = Self.shown(pane)
        defer { window.orderOut(nil) }
        let check = Task { await pane.flow.refresh() }
        await transport.arrived(1, during: check)
        transport.answer()
        await check.value
        #expect(pane.planeRow.plane == .unreachable && pane.planeRow.said == "127.0.0.1:7979", "after the check the line says \(String(describing: pane.planeRow.plane))")
        let again = Task { await pane.flow.refresh() }
        await transport.arrived(2, during: again)
        #expect(pane.planeRow.plane == .reconnecting, "asking again the line says \(String(describing: pane.planeRow.plane))")
        transport.answer()
        await again.value
        #expect(pane.planeRow.plane == .unreachable)
    }

    /// A recorded `whoami` answering for `fde`, named `name`.
    static func whoami(_ fde: String, name: String) -> Exchange {
        let body = #"{"capabilities": ["read"], "display_name": "\#(name)", "email": "e@example.com", "fde": "\#(fde)", "name": "\#(fde)", "role": "member", "schema_version": 1}"#
        return Exchange(method: "GET", path: "/v1/whoami", status: 200, body: Data(body.utf8))
    }

    static let longHandle = String(repeating: "k", count: 63)
    static let longName = "Uday Chandra-Bhattacharyya of the Standard Applied Mac app team"

    /// M1: the pane and the menu say one thing, from one source: the heading, `@handle · Role` and
    /// the control plane's line are the menu content's name, detail, address and state, confirmed,
    /// during a check and out of reach.
    @Test func thePaneAndTheMenuCannotDisagree() async throws {
        func agree(_ pane: AccountPane, _ when: String) {
            let content = pane.flow.menuContent
            #expect(pane.heading.stringValue == content.name && pane.handle.stringValue == (content.detail ?? ""), "\(when): \(pane.heading.stringValue) / \(pane.handle.stringValue) against \(content)")
            #expect(pane.planeRow.said == (content.address ?? "") && pane.planeRow.plane == content.plane, "\(when): the line says \(pane.planeRow.said), \(String(describing: pane.planeRow.plane)) against \(content)")
        }
        let confirmed = try Self.pane(remembering: "uday", Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held"))
        await confirmed.flow.refresh()
        agree(confirmed, "confirmed")
        let transport = OutOfReach()
        let away = try Self.pane(remembering: "ana", transport)
        let check = Task { await away.flow.refresh() }
        await transport.arrived(1, during: check)
        transport.answer()
        await check.value
        agree(away, "out of reach")
        let again = Task { await away.flow.refresh() }
        await transport.arrived(2, during: again)
        agree(away, "asking again")
        transport.answer()
        await again.value
    }

    /// M2: a long name and a long handle are cut at their end and said in full on hover; the session's
    /// end, confirmed or not, wraps.
    @Test func longWordsAreCutAndSaidInFull() async throws {
        let pane = try Self.pane(remembering: Self.longHandle, Held(RecordedServer([Self.whoami(Self.longHandle, name: Self.longName)]), holding: "nothing is held"))
        let window = Self.shown(pane, width: Double(ShellApp.leastFrame.width) - Rail.width - 4)
        defer { window.orderOut(nil) }
        await pane.flow.refresh()
        pane.layoutSubtreeIfNeeded()
        for (label, tip) in [(pane.heading, pane.headingTip), (pane.handle, pane.handleTip)] {
            let style = label.attributedStringValue.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle
            #expect(style?.lineBreakMode == .byTruncatingTail && tip.shows && tip.text == label.stringValue, "\(label.stringValue.prefix(12))… is not cut at its end and said in full")
        }
        #expect(pane.until.maximumNumberOfLines != 1, "the session's end does not wrap")
    }

    /// Nothing the pane shows is out of reach, signed in, at every window size from the least frame
    /// up, with the longest handle and a long name, confirmed and not yet confirmed.
    @Test func nothingInThePaneIsOutOfReach() async throws {
        var faults: [String] = []
        for size in UserMenuTests.sizes {
            let width = Double(size.width) - Rail.width - 4
            let signedIn = try Self.pane(remembering: "uday", Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held"))
            let other = Self.shown(signedIn, width: width)
            await signedIn.flow.refresh()
            faults += WorkspacesReachTests.outOfReach(in: signedIn).map { "\(width) pt, signed in: \($0)" }
            other.orderOut(nil)
            let long = try Self.pane(remembering: Self.longHandle, Held(RecordedServer([Self.whoami(Self.longHandle, name: Self.longName)]), holding: "nothing is held"))
            let longWindow = Self.shown(long, width: width)
            await long.flow.refresh()
            faults += WorkspacesReachTests.outOfReach(in: long).map { "\(width) pt, long and confirmed: \($0)" }
            longWindow.orderOut(nil)
            let failing = Held(RecordedServer([]), holding: "whoami", then: Exchange(method: "GET", path: "/v1/whoami", status: 502, body: Data("{}".utf8)))
            let unconfirmed = try Self.pane(remembering: Self.longHandle, failing)
            let unconfirmedWindow = Self.shown(unconfirmed, width: width)
            failing.release()
            await unconfirmed.flow.refresh()
            unconfirmed.layoutSubtreeIfNeeded()
            #expect(unconfirmed.until.stringValue.hasPrefix("Not yet confirmed"), "\(unconfirmed.until.stringValue)")
            faults += WorkspacesReachTests.outOfReach(in: unconfirmed).map { "\(width) pt, long and not yet confirmed: \($0)" }
            unconfirmedWindow.orderOut(nil)
        }
        #expect(faults.isEmpty, "\(faults.count) faults: \(faults.prefix(20).joined(separator: "\n"))")
    }

    /// Sign Out signs out at once and asks nothing, as one action of the flow's; the menu item
    /// after it does nothing more.
    @Test func signOutSignsOutAtOnceAskingNothing() async throws {
        let pane = try Self.pane(remembering: "uday", Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held"))
        await pane.flow.refresh()
        #expect(pane.flow.standing?.fde == "uday")
        let before = pane.flow.actions

        pane.signOut.performClick(nil)
        pane.signOutHere(nil)

        #expect(pane.flow.actions == before + 1, "one action, the second asking nothing")
        #expect(pane.flow.standing == nil && !pane.flow.isActing && pane.text.isEmpty)
    }
}
