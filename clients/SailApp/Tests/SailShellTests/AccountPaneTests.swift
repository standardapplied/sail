import AppKit
import Foundation
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Synchronization
import Testing

/// A vault holding one session as `fde`, for any control plane, ending `lasts` from when it is
/// read; a session kept is not held.
struct OneSession: TokenVault {
    let fde: String
    var lasts: TimeInterval = 3600

    func session(for _: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        PasskeySession(sessionToken: "tok-\(fde)", fde: fde, expiresAt: Date(timeIntervalSinceNow: lasts))
    }

    func keep(_: PasskeySession, for _: ControlPlane) throws(VaultFailure) {}

    func forget(_: ControlPlane) throws(VaultFailure) {}
}

/// Shut until opened: whatever waits on it meanwhile goes on once it is, or once the task waiting
/// is cancelled, as a test's time limit cancels it.
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
        lasts: TimeInterval = 3600, now: @escaping @MainActor () -> Date = { Date() }
    ) -> AccountFlow {
        SignInMemory(defaults).reached(plane)
        let account = Account(authenticator: Signs(sheet: sheet, asked: asked), vault: OneSession(fde: fde, lasts: lasts)) { _ in transport }
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


/// A control plane answering each route as the server recorded it, the answers changed as a test
/// goes; a route held answers once released, or once the task waiting on it is cancelled.
actor Desk: Transport {
    private var answers: [String: HTTPResponse] = [:]
    private var holding: String?
    private let gate = Gate(), arrival = Gate()
    private(set) var asked: [String] = []

    init(_ names: [String]) throws {
        for name in names {
            let exchange = try Exchange(name)
            answers["\(exchange.method) \(exchange.path)"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
        }
    }

    /// From now on the route `name` recorded is answered as it was.
    func answer(_ name: String) throws {
        let exchange = try Exchange(name)
        answers["\(exchange.method) \(exchange.path)"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
    }

    /// From now on `route` is answered with a bare `status`, as a proxy answers.
    func answer(_ route: String, status: Int) {
        answers[route] = HTTPResponse(status: status, headers: [:], body: Data())
    }

    /// From now on `route` cannot be reached.
    func forget(_ route: String) {
        answers[route] = nil
    }

    /// The next request of `route` is held until ``release()``.
    func hold(_ route: String) {
        holding = route
    }

    func release() {
        gate.open()
    }

    /// Returns once the held request came, or once `action`, which should send it, ended without.
    nonisolated func arrived(during action: Task<Void, Never>?) async {
        await arrival.wait(during: action)
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        asked.append(request.route)
        if request.route == holding {
            holding = nil
            arrival.open()
            await gate.wait()
        }
        guard let answer = answers[request.route] else { throw .unreachable("The network connection was lost.") }
        return answer
    }

    func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        throw .unreachable("no golden streams \(request.route)")
    }
}

/// The Account view, signed in: the header the user menu's own words, the four groups in order with
/// no primary button, each list the server's rows, the drawing mark until a group's first answer and
/// its last answer dimmed while its call fails, each removal asking first with Cancel the default
/// and its refusal said under its row, Can't reach a banner in place, Sign In Again under three
/// days left, and the user menu's Keys landing at its keys.
@MainActor @Suite(.timeLimit(.minutes(1))) struct AccountPaneTests {
    init() {
        ExitWatch.install()
    }

    static let ticket = "enr_0123456789abcdef0123456789abcdef"
    static let goldens = ["whoami", "passkeys", "agents", "logins-no-default"]
    static let month: TimeInterval = 30 * 86_400

    /// The pane over a flow at the drive's control plane holding a session as `fde` for `lasts`,
    /// drawn as the shell draws it at each change.
    static func pane(remembering fde: String, _ transport: any Transport, lasts: TimeInterval = 3600) throws -> AccountPane {
        let flow = AccountFlowTests.flow(remembering: fde, transport, lasts: lasts)
        let pane = AccountPane(
            flow: flow, look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts),
            labels: try LabelFont(directory: LookTests.fonts)
        )
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        pane.clock = AccountPage.Clock(calendar: calendar, locale: Locale(identifier: "en_GB"))
        flow.onChange = { [weak pane] in pane?.show() }
        return pane
    }

    /// The pane in a window `width` wide, laid out.
    static func shown(_ pane: AccountPane, width: Double = 900, height: Double = 900, look: Look = .light) -> NSWindow {
        pane.look = look
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: height), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = pane
        pane.layoutSubtreeIfNeeded()
        return window
    }

    /// Signed in as uday at `desk` for a month, the groups' first answers taken.
    static func signedIn(_ desk: Desk, width: Double = 900, look: Look = .light, lasts: TimeInterval = month) async throws -> (AccountPane, NSWindow) {
        let pane = try pane(remembering: "uday", desk, lasts: lasts)
        let window = shown(pane, width: width, look: look)
        await pane.flow.refresh()
        await pane.loading?.value
        pane.layoutSubtreeIfNeeded()
        return (pane, window)
    }

    /// Someone signed in, for a shell's tests that need the Account pane shown.
    static let someone = SignedIn(
        controlPlane: AccountFlowTests.plane, fde: "uday", until: Date(timeIntervalSinceNow: 3600),
        whoami: .success(Whoami(fde: "uday", name: "uday", displayName: "Uday", role: .known(.admin), capabilities: []))
    )

    /// A recorded `whoami` answering for `fde`, named `name`.
    static func whoami(_ fde: String, name: String) -> Exchange {
        let body = #"{"capabilities": ["read"], "display_name": "\#(name)", "email": "e@example.com", "fde": "\#(fde)", "name": "\#(fde)", "role": "member", "schema_version": 1}"#
        return Exchange(method: "GET", path: "/v1/whoami", status: 200, body: Data(body.utf8))
    }

    static func item(_ name: String, in list: SailList) throws -> SailList.Item {
        try #require(list.entries.lazy.compactMap { entry -> SailList.Item? in if case let .item(item) = entry, item.name == name { item } else { nil } }.first, "no row \(name)")
    }

    static func names(_ list: SailList) -> [String] {
        list.entries.compactMap { entry in if case let .item(item) = entry, !item.inert { item.name } else { nil } }
    }

    /// The header says what the user menu says: its name (the disc's initial), handle and role, then
    /// the email.
    @Test func theHeaderSaysTheMenusNameHandleAndRole() async throws {
        let (pane, window) = try await Self.signedIn(try Desk(Self.goldens))
        defer { window.orderOut(nil) }
        let content = pane.flow.menuContent
        #expect(pane.heading.stringValue == content.name && content.name == "Uday" && pane.disc.name == content.name)
        #expect(pane.handle.stringValue == content.handle && content.handle == "@uday")
        #expect(pane.role.said == content.role && content.role == "Admin" && pane.role.plain && !pane.role.isHidden)
        #expect(pane.email.stringValue == "uday@example.com")
        #expect(pane.text.hasPrefix("Uday\n@uday · Admin · uday@example.com\n127.0.0.1:7979 · Connected\nSigned in until "), "\(pane.text)")
    }

    /// Four groups in order, each under its eyebrow, and no primary button anywhere, a row chosen or
    /// not: Sign Out is secondary, with its icon.
    @Test(arguments: [Look.light, .dark])
    func fourGroupsInOrderAndNoPrimary(_ look: Look) async throws {
        let (pane, window) = try await Self.signedIn(try Desk(Self.goldens), look: look)
        defer { window.orderOut(nil) }
        #expect(pane.eyebrows.map(\.stringValue) == ["CONTROL PLANE", "PASSKEYS", "KEYS"] && pane.eyebrows.allSatisfy { $0.textColor == NSColor(look.textSecondary) })
        let tops = [pane.planeFrame, pane.passkeyFrame, pane.keyFrame, pane.signOut].map { Double($0.frame.minY) }
        #expect(tops == tops.sorted() && Set(tops).count == 4, "the groups stand at \(tops)")
        for list in [pane.passkeyList, pane.keyList] {
            for entry in list.entries {
                guard case let .item(item) = entry, !item.inert else { continue }
                list.choose(item.id)
                pane.layoutSubtreeIfNeeded()
                let primary = WorkspacesViewTests.found(SailButton.self, in: pane).filter { $0.kind == .primary }.map(\.title)
                #expect(primary.isEmpty, "\(item.name) chosen, primary: \(primary)")
            }
        }
        #expect(pane.signOut.kind == .secondary && pane.signOut.title == "Sign Out" && pane.signOut.icon == .out)
    }

    /// The groups are the server's rows: a passkey each, This Mac's tagged; a harness each in the
    /// server's order, a further name a row of its own, Add Key shown on the harness with no key.
    @Test func theListsAreTheServersRows() async throws {
        let desk = try Desk(Self.goldens)
        let (pane, window) = try await Self.signedIn(desk)
        defer { window.orderOut(nil) }
        #expect(Self.names(pane.passkeyList) == ["Sail for Mac", "Sail for iPhone"] && Self.names(pane.keyList) == ["Claude Code", "Claude Code", "Codex CLI"])
        #expect(pane.text.contains("Sail for Mac · This Mac · Added 11 November 2026") && pane.text.contains("Claude Code · No key. Sessions sign in with your Claude account."))
        let first = try #require(pane.keyList.rowView(atRow: 0, makeIfNecessary: true)?.view(atColumn: 0) as? NSView)
        let offered = WorkspacesViewTests.found(SailButton.self, in: first).filter { !$0.isHiddenOrHasHiddenAncestor }.map(\.title)
        #expect(offered == ["Add Key"], "the row with no key offers \(offered)")
        try await desk.answer("logins-with-a-default")
        pane.load()
        await pane.loading?.value
        #expect(Self.names(pane.keyList) == ["Claude Code", "Codex CLI"])
        #expect(try Self.item("Codex CLI", in: pane.keyList).actions.map(\.title) == ["Replace", "Remove"])
    }

    /// Until its first answer each list's frame holds the drawing mark; a later call keeps the last
    /// answer on screen while it goes.
    @Test func untilItsFirstAnswerAGroupDrawsTheMark() async throws {
        let desk = try Desk(Self.goldens)
        await desk.hold("GET /v1/passkeys")
        let pane = try Self.pane(remembering: "uday", desk, lasts: Self.month)
        let window = Self.shown(pane)
        defer { window.orderOut(nil) }
        await pane.flow.refresh()
        await desk.arrived(during: pane.loading)
        pane.layoutSubtreeIfNeeded()
        #expect(!pane.passkeyMark.isHidden && !pane.keyMark.isHidden && pane.passkeyList.isHidden && pane.keyList.isHidden, "no mark while the first answer is awaited")
        #expect(pane.passkeyMark.presentation == .drawing(label: nil) && pane.passkeyMark.markSide == 28 && pane.passkeyMark.superview === pane.passkeyFrame)
        await desk.release()
        await pane.loading?.value
        #expect(pane.passkeyMark.isHidden && pane.keyMark.isHidden && Self.names(pane.passkeyList).count == 2)
        await desk.hold("GET /v1/passkeys")
        pane.load()
        await desk.arrived(during: pane.loading)
        #expect(pane.passkeyMark.isHidden && Self.names(pane.passkeyList).count == 2, "a later call took the last answer off the screen")
        await desk.release()
        await pane.loading?.value
    }

    /// A failed call dims only the group it draws, its last answer kept and the failure said under it.
    @Test func aFailedPasskeysCallDimsOnlyThePasskeysGroup() async throws {
        let desk = try Desk(Self.goldens)
        let (pane, window) = try await Self.signedIn(desk)
        defer { window.orderOut(nil) }
        await desk.answer("GET /v1/passkeys", status: 502)
        pane.load()
        await pane.loading?.value
        #expect(pane.passkeyFrame.alphaValue == AccountPane.dimmed && pane.keyFrame.alphaValue == 1 && pane.planeFrame.alphaValue == 1)
        #expect(Self.names(pane.passkeyList).count == 2 && pane.text.contains("The control plane at 127.0.0.1:7979 answered with an error (502)."), "\(pane.text)")
        try await desk.answer("passkeys")
        pane.load()
        await pane.loading?.value
        #expect(pane.passkeyFrame.alphaValue == 1 && pane.passkeySaid.isHidden)
    }

    /// ⋯ ▸ Remove asks first in the library's sheet, Remove destructive and Cancel the default;
    /// cancelled, nothing is sent.
    @Test func removingAPasskeyAsksWithCancelTheDefault() async throws {
        let desk = try Desk(Self.goldens)
        let (pane, window) = try await Self.signedIn(desk)
        defer { window.orderOut(nil) }
        let remove = try #require(try Self.item("Sail for iPhone", in: pane.passkeyList).actions.first { $0.title == "Remove" })
        #expect(remove.destructive && !remove.onRow)
        remove.run()
        let sheet = try #require(pane.question)
        #expect(sheet.title.stringValue == "Remove the passkey “Sail for iPhone”?" && sheet.body.stringValue.hasPrefix("It stops signing you in"))
        #expect(sheet.cancel.isDefault && !sheet.confirm.isDefault && sheet.confirm.kind == .destructive && sheet.confirm.title == "Remove")
        #expect(window.attachedSheet === sheet.window)
        sheet.withdraw()
        await pane.acting?.value
        let asked = await desk.asked
        #expect(pane.question == nil && !asked.contains { $0.hasPrefix("DELETE") })
    }

    /// The server's refusal of a removal is said under its row in its words, the row kept.
    @Test func aRefusedRemovalIsSaidUnderItsRow() async throws {
        let desk = try Desk(Self.goldens + ["refusal-conflict-passkey-current"])
        let (pane, window) = try await Self.signedIn(desk)
        defer { window.orderOut(nil) }
        let mine = try Self.item("Sail for Mac", in: pane.passkeyList)
        try #require(mine.actions.first { $0.title == "Remove" }).run()
        let sheet = try #require(pane.question)
        _ = sheet.confirm.sendAction(sheet.confirm.action, to: sheet.confirm.target)
        await pane.acting?.value
        await pane.loading?.value
        let said = ["You are signed in with this passkey.", "Sign in with another passkey to remove this one."]
        #expect(pane.refusals[mine.id] == said && Self.names(pane.passkeyList) == ["Sail for Mac", "Sail for iPhone"])
        guard pane.passkeyList.entries.count == 3, case let .item(under) = pane.passkeyList.entries[1] else {
            Issue.record("nothing under the row: \(pane.passkeyList.entries.count)")
            return
        }
        #expect(under.inert && under.voice == said.joined(separator: " ") && pane.text.contains(said.joined(separator: "\n")))
        let asked = await desk.asked
        #expect(asked.contains("DELETE /v1/passkeys/\(mine.id)"))
    }

    /// Removing a key asks in the route's words, and is sent to its harness and name.
    @Test func removingAKeyAsksInTheRoutesWords() async throws {
        let desk = try Desk(Self.goldens + ["login-removed"])
        try await desk.answer("logins-with-a-default")
        let (pane, window) = try await Self.signedIn(desk)
        defer { window.orderOut(nil) }
        try #require(try Self.item("Claude Code", in: pane.keyList).actions.first { $0.title == "Remove" }).run()
        let sheet = try #require(pane.question)
        #expect(sheet.title.stringValue == "Remove the Claude Code key?")
        #expect(sheet.body.stringValue == "Sessions already running keep it until they end. New Claude Code sessions sign in with your Claude account, and spec runs need a key again.")
        #expect(sheet.cancel.isDefault && !sheet.confirm.isDefault)
        _ = sheet.confirm.sendAction(sheet.confirm.action, to: sheet.confirm.target)
        await pane.acting?.value
        let asked = await desk.asked
        #expect(asked.contains("DELETE /v1/logins/claude-code/default") && pane.refusals.isEmpty)
    }

    /// Add Key and Replace open the Store a Key sheet built from the harness the server lists,
    /// Replace storing under the key it replaces.
    @Test func addKeyAndReplaceOpenTheStoreAKeySheet() async throws {
        let (pane, window) = try await Self.signedIn(try Desk(Self.goldens))
        defer { window.orderOut(nil) }
        try #require(try Self.item("Claude Code", in: pane.keyList).actions.first { $0.title == "Add Key" }).run()
        let added = try #require(pane.loginSheet)
        #expect(added.model.harness == "claude-code" && added.model.title == "Store a Claude Code key" && added.model.name == "work" && window.attachedSheet === added)
        added.withdraw()
        #expect(pane.loginSheet == nil)
        try #require(try Self.item("Codex CLI", in: pane.keyList).actions.first { $0.title == "Replace" }).run()
        let replaced = try #require(pane.loginSheet)
        #expect(replaced.model.harness == "codex" && replaced.model.name == "work")
        replaced.withdraw()
    }

    /// Can't reach is said in the control plane's group, with Try Again, the pill in the error's
    /// ink; the other groups keep their answers undimmed. Try Again asks again.
    @Test func cantReachIsABannerAndTheGroupsKeepTheirAnswers() async throws {
        let desk = try Desk(Self.goldens)
        let (pane, window) = try await Self.signedIn(desk)
        defer { window.orderOut(nil) }
        await desk.forget("GET /v1/whoami")
        await pane.flow.refresh()
        pane.layoutSubtreeIfNeeded()
        let banner = try #require(pane.banner)
        #expect(banner.tone == .error && banner.action?.title == "Try Again" && banner.superview === pane.planeFrame)
        #expect(pane.planeLines == ["Sail cannot reach 127.0.0.1:7979.", "Check that this Mac is on its network."] && pane.state.said == "Can’t reach" && pane.state.state == .error)
        #expect(pane.passkeyFrame.alphaValue == 1 && pane.keyFrame.alphaValue == 1 && Self.names(pane.passkeyList).count == 2)
        try await desk.answer("whoami")
        pane.tryAgain(nil)
        await pane.acting?.value
        await pane.loading?.value
        #expect(pane.banner == nil && pane.state.said == "Connected")
    }

    /// Under three days left the end is drawn in the warning ink and Sign In Again shows; it runs
    /// the sign-in ceremony and the view stays; with more left, neither.
    @Test func underThreeDaysLeftSignInAgainShows() async throws {
        let desk = try Desk(Self.goldens + ["passkey-login-start", "passkey-login-finish"])
        let (soon, window) = try await Self.signedIn(desk, lasts: 2 * 86_400 + 600)
        defer { window.orderOut(nil) }
        #expect(soon.ending?.soon == true && soon.ending?.said.hasSuffix(" · ends in 2 days") == true && !soon.signInAgain.isHidden)
        let ink = soon.planeRow.detail.attributedStringValue.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor
        #expect(ink == NSColor(Tint(soon.look.warning)) && soon.signInAgain.kind == .secondary)
        let before = soon.flow.standing?.until
        soon.signInAgainNow(nil)
        await soon.acting?.value
        let asked = await desk.asked
        #expect(soon.flow.standing?.until != before && soon.flow.standing != nil && asked.contains("POST /v1/auth/login/finish"))
        let (later, other) = try await Self.signedIn(try Desk(Self.goldens))
        defer { other.orderOut(nil) }
        #expect(later.ending?.soon == false && later.signInAgain.isHidden)
    }

    /// The user menu's Keys shows the Account view scrolled to its keys, also once the keys come.
    @Test func keysInTheMenuLandsAtTheKeys() async throws {
        let desk = try Desk(Self.goldens)
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in Signs() }, connect: { _ in desk }, vault: OneSession(fde: "uday", lasts: Self.month),
            defaults: ReattachTests.defaults()
        )
        shell.window.setFrame(NSRect(origin: .zero, size: ShellApp.leastFrame), display: false)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        UserMenuTests.own(shell)
        shell.flow.show(Self.someone)
        try UserMenuTests.open(shell, shell.flow.menuContent)
        let keys = try #require(shell.userMenu.view.items.first { $0.item == .keys })
        #expect(keys.accessibilityPerformPress())
        await shell.account.loading?.value
        shell.account.layoutSubtreeIfNeeded()
        let pane = shell.account
        let most = max(Double(pane.scroll.documentView?.frame.height ?? 0) - Double(pane.scroll.contentSize.height), 0)
        let target = min(max(Double(pane.eyebrows[2].frame.minY) - AccountPane.inset.top, 0), most)
        #expect(shell.rail.selected == .account && !shell.userMenu.isOpen && target > 0, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing"), the keys at \(target)")
        #expect(abs(pane.scrolledTo - target) < 1, "scrolled to \(pane.scrolledTo), the keys at \(target)")
    }

    /// Sign Out signs out at once and asks nothing, as one action of the flow's; the menu item
    /// after it does nothing more.
    @Test func signOutSignsOutAtOnceAskingNothing() async throws {
        let (pane, window) = try await Self.signedIn(try Desk(Self.goldens))
        defer { window.orderOut(nil) }
        #expect(pane.flow.standing?.fde == "uday")
        let before = pane.flow.actions
        pane.signOutHere(nil)
        pane.signOutHere(nil)
        #expect(pane.flow.actions == before + 1, "one action, the second asking nothing")
        #expect(pane.flow.standing == nil && !pane.flow.isActing && pane.text.isEmpty)
    }

    /// Nothing the view shows is out of reach at every window size from the least frame up, with
    /// the longest handle and a long name, connected and out of reach, a refusal under a row.
    @Test func nothingInTheViewIsOutOfReach() async throws {
        var faults: [String] = []
        for size in UserMenuTests.sizes {
            let width = Double(size.width) - Rail.width - 4
            let (pane, window) = try await Self.signedIn(try Desk(Self.goldens), width: width)
            faults += WorkspacesReachTests.outOfReach(in: pane).map { "\(width) pt: \($0)" }
            window.orderOut(nil)
            let desk = try Desk(Self.goldens.filter { $0 != "whoami" })
            let long = try Self.pane(remembering: String(repeating: "k", count: 63), desk, lasts: Self.month)
            let longWindow = Self.shown(long, width: width)
            await long.flow.refresh()
            await long.loading?.value
            faults += WorkspacesReachTests.outOfReach(in: long).map { "\(width) pt, long and out of reach: \($0)" }
            longWindow.orderOut(nil)
        }
        #expect(faults.isEmpty, "\(faults.count) faults: \(faults.prefix(20).joined(separator: "\n"))")
    }
}
