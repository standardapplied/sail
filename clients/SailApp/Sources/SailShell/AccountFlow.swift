import Foundation
import SailAPI

/// Who is signed in on this Mac, and every action that changes it: a check of the session it holds
/// (at launch and each time the app is activated), Continue, Create Passkey (which signs in at once
/// after `register/finish`), Sign In with Passkey and Sign Out. The welcome, the Account pane and
/// the user menu draw it; nothing else decides it. Only a person's action holds the buttons (#462):
/// a check never does, so a person's action is never swallowed by one; a check's answer that comes
/// during a person's action waits for it to end, and is then shown unless who is signed in was set
/// since the check began.
@MainActor
public final class AccountFlow {
    public private(set) var standing: SignedIn?
    /// Whether a ceremony or a call is under way.
    public var busy: Bool {
        running > 0 || acting > 0
    }

    /// How many ceremonies and calls have ended.
    public private(set) var ended = 0
    /// How many checks of who is signed in are under way: a menu saying the control plane is
    /// reconnecting says so only while one is.
    public private(set) var checks = 0
    /// How many actions a person has started (a reach, an enrollment, a sign-in, a sign-out): what
    /// the drive waits on.
    public private(set) var actions = 0
    public var isActing: Bool {
        acting > 0
    }

    /// What this Mac remembers: the control plane reached and the sign-ins made there.
    public let memory: SignInMemory
    /// The person's action under way, or the last one: what a test awaits.
    package private(set) var underWay: Task<Void, Never>?
    /// Told whenever anything above changes.
    var onChange: @MainActor () -> Void = {}
    /// Who is signed in now, nil for no one, and whether a person's sign-in set it.
    var onStanding: @MainActor (SignedIn?, _ byAPerson: Bool) -> Void = { _, _ in }
    /// A check's answer once shown: who, or the refusal that left no one, with the day an ended
    /// session ended.
    var onChecked: @MainActor (Result<SignedIn?, AccountRefusal>, _ endedOn: Date?) -> Void = { _, _ in }

    private let account: Account
    private let now: @MainActor () -> Date
    private var running = 0
    private var acting = 0
    /// How many times who is signed in was set: a check that began before the latest answers too
    /// late to be shown.
    private var shown = 0
    /// How many times a person signed in here: a call tells of its session's end only while the
    /// sign-in it was asked under stands.
    private var signIns = 0
    /// The sign-in a call found ended while a check was under way, which may have asked before the
    /// end: once that check is shown, who is signed in is asked again while it still stands.
    private var endedDuringACheck: Int?
    /// A check's answer that came while a person's action was under way, taken once it ends.
    private var heldAnswer: (@MainActor () -> Void)?

    init(account: Account, memory: SignInMemory, now: @escaping @MainActor () -> Date = { Date() }) {
        self.account = account
        self.memory = memory
        self.now = now
    }

    /// Whether this Mac holds a session for the control plane it last reached: a launch then waits
    /// for the check, else it shows the welcome at once. One the vault could not read is gone once
    /// read, so it is told as a check's answer.
    func holdsASession() -> Bool {
        guard let plane = memory.plane else { return false }
        do throws(AccountRefusal) {
            return try account.heldUntil(at: plane) != nil
        } catch {
            forgetIfGone(error)
            onChecked(.failure(error), nil)
            return false
        }
    }

    /// What the user menu says: who is signed in at which control plane and whether it answers;
    /// while a check asks and knows no one, that it is checking, never "Not signed in".
    var menuContent: UserMenuContent {
        let lastUsed = memory.plane?.named
        guard let standing else {
            if checks > 0, let lastUsed { return .checking(at: lastUsed) }
            return .signedOut(lastUsed: lastUsed)
        }
        let whoami = try? standing.whoami.get()
        let unreachable: Bool = if case let .failure(refusal) = standing.whoami { refusal.reason == .unreachable } else { false }
        return .signedIn(handle: whoami?.fde ?? standing.fde, address: standing.controlPlane.named, whoami: whoami, unreachable: unreachable, refreshing: checks > 0)
    }

    /// The API as the person signed in, nil when no one is or the vault cannot say. Whichever of its
    /// calls finds the session ended has who is signed in asked (``sessionEnded(_:)``).
    func client() -> SailClient? {
        guard let standing else { return nil }
        let signIn = signIns
        return try? account.client(at: standing.controlPlane) { [weak self] in
            Task { @MainActor in self?.sessionEnded(signIn) }
        }
    }

    /// The check a call that found the session ended asked: what a test awaits.
    package private(set) var ending: Task<Void, Never>?

    /// A call asked under the `signIn`-th sign-in found the session ended: who is signed in is asked
    /// while that sign-in stands, once however many calls found it together. A check already under
    /// way answers first, and is followed by one more only if it leaves that sign-in standing. The
    /// check's answer goes where every check's goes: an ended session to the welcome.
    private func sessionEnded(_ signIn: Int) {
        guard standing != nil, signIn == signIns, ending == nil else { return }
        guard checks == 0 else {
            endedDuringACheck = signIn
            return
        }
        ending = Task {
            await refresh()
            ending = nil
        }
    }

    /// Asks the server who this Mac is signed in as at the control plane it last reached, when it
    /// holds a session there. The check holds no button. An answer that comes during a person's
    /// action waits for it to end, so nothing changes under a passkey sheet.
    public func refresh() async {
        guard !busy, let plane = memory.plane else { return }
        let seen = shown
        checks += 1
        onChange()
        defer {
            checks -= 1
            onChange()
            if let signIn = endedDuringACheck {
                endedDuringACheck = nil
                sessionEnded(signIn)
            }
        }
        _ = await run(byAPerson: false) { () async throws(AccountRefusal) in
            let until: Date?
            do throws(AccountRefusal) {
                until = try self.account.heldUntil(at: plane)
            } catch {
                return self.whenNoOneActs { self.take(.failure(error), seen: seen, until: nil) }
            }
            let answer: Result<SignedIn?, AccountRefusal>
            do throws(AccountRefusal) {
                answer = .success(try await self.account.current(at: plane))
            } catch {
                answer = .failure(error)
            }
            self.whenNoOneActs { self.take(answer, seen: seen, until: until) }
        }
    }

    /// A check's answer, dropped when who is signed in was set since the check began (a sign-in, a
    /// sign-out), which it would undo. An ended session ended on its own end when that had passed,
    /// else today, when the app was told.
    private func take(_ answer: Result<SignedIn?, AccountRefusal>, seen: Int, until: Date?) {
        guard shown == seen else { return }
        var endedOn: Date?
        switch answer {
        case let .success(found):
            learnt(found)
        case let .failure(refusal):
            forgetIfGone(refusal)
            if refusal.reason == .ended { endedOn = min(until ?? now(), now()) }
        }
        onChecked(answer, endedOn)
    }

    private func whenNoOneActs(_ go: @escaping @MainActor () -> Void) {
        guard acting > 0 else { return go() }
        heldAnswer = go
    }

    /// Continue: one unauthenticated health check of the control plane `typed` names; it is
    /// remembered only once it answered. False when another action is under way. Stopped
    /// (``stopReaching()``), it says and remembers nothing.
    @discardableResult
    func reach(_ typed: String, then done: @escaping @MainActor (Result<ControlPlane, AccountRefusal>) -> Void) -> Bool {
        act { () async throws(AccountRefusal) in
            self.reached(try await self.account.reach(typed), then: done)
        } refused: { done(.failure($0)) }
    }

    /// Continue's answer, `plane`, remembered and said unless the check was stopped before it was
    /// taken: an edit handled while the answer came back to the main actor stops it too.
    func reached(_ plane: ControlPlane, then done: @MainActor (Result<ControlPlane, AccountRefusal>) -> Void) {
        guard !Task.isCancelled else { return }
        memory.reached(plane)
        done(.success(plane))
    }

    /// The person changed the address while Continue's check was under way: it stops at once.
    func stopReaching() {
        underWay?.cancel()
    }

    /// Create Passkey: enrolls with `ticket`, says whose passkey was made (`created`), then signs in
    /// with it at once, a second Touch ID.
    @discardableResult
    func enroll(ticket: String, at plane: ControlPlane, created: @escaping @MainActor (String) -> Void, refused: @escaping @MainActor (AccountRefusal) -> Void) -> Bool {
        act { () async throws(AccountRefusal) in
            let enrolled = try await self.account.enroll(ticket: ticket, at: plane)
            created(enrolled.fde)
            try await self.signIn(at: plane)
        } refused: { refused($0) }
    }

    /// Sign In with Passkey.
    @discardableResult
    func signIn(at plane: ControlPlane, refused: @escaping @MainActor (AccountRefusal) -> Void) -> Bool {
        act { () async throws(AccountRefusal) in try await self.signIn(at: plane) } refused: { refused($0) }
    }

    /// Sign In Again, from the Account view: the ceremony at the control plane signed in to, the
    /// view left where it is. False when no one is signed in or another action is under way.
    @discardableResult
    func signInAgain(refused: @escaping @MainActor (AccountRefusal) -> Void) -> Bool {
        guard let plane = standing?.controlPlane else { return false }
        return act { () async throws(AccountRefusal) in try await self.signIn(at: plane, landing: false) } refused: { refused($0) }
    }

    /// Signs out at once, asking nothing: what the sign-in opened on the runner lives on there, for
    /// the same person's next sign-in. The refusal when the vault would not forget it.
    func signOut() -> AccountRefusal? {
        guard let plane = standing?.controlPlane, acting == 0 else { return nil }
        begin()
        defer {
            ended += 1
            end()
        }
        do throws(AccountRefusal) {
            try account.signOut(at: plane)
            show(nil)
            return nil
        } catch {
            return error
        }
    }

    /// Signed in at `plane`: the session kept, the person remembered for the next welcome there;
    /// `landing`, as a person's sign-in, which opens Workspaces.
    private func signIn(at plane: ControlPlane, landing: Bool = true) async throws(AccountRefusal) {
        let signedIn = try await account.signIn(at: plane)
        signIns += 1
        let whoami = try? signedIn.whoami.get()
        let name = [whoami?.displayName, whoami?.name].compactMap { $0 }.first { !$0.isEmpty }
        memory.signedIn(RememberedSignIn(handle: whoami?.fde ?? signedIn.fde, name: name), at: plane)
        show(signedIn, byAPerson: landing)
    }

    /// Starts `work`, a person's action, before the click or Return that started it returns: a
    /// second one starts nothing until it ends.
    private func act(_ work: @escaping @MainActor () async throws(AccountRefusal) -> Void, refused: @escaping @MainActor (AccountRefusal) -> Void) -> Bool {
        guard acting == 0 else { return false }
        begin()
        underWay = Task {
            if let refusal = await run(work), !Task.isCancelled { refused(refusal) }
        }
        return true
    }

    private func begin() {
        acting += 1
        actions += 1
        onChange()
    }

    /// A person's action ends: a check's answer that came meanwhile is taken.
    private func end() {
        acting -= 1
        onChange()
        guard acting == 0, let held = heldAnswer else { return }
        heldAnswer = nil
        held()
    }

    /// Runs `act`, a person's action (``begin()`` already called) or (not `byAPerson`) a check;
    /// its refusal, nil when it ended well.
    private func run(byAPerson person: Bool = true, _ act: @MainActor () async throws(AccountRefusal) -> Void) async -> AccountRefusal? {
        running += 1
        onChange()
        defer {
            running -= 1
            if person { end() }
            ended += 1
            onChange()
        }
        do throws(AccountRefusal) {
            try await act()
            return nil
        } catch {
            forgetIfGone(error)
            return error
        }
    }

    /// No one is signed in when the session ended or could not be read.
    private func forgetIfGone(_ refusal: AccountRefusal) {
        if [.ended, .unreadableSignIn].contains(refusal.reason) { learnt(nil) }
    }

    /// Who a check found signed in, shown: signed in, Workspaces, when shown, asks for its projects
    /// again. A check that finds no one while no one is signed in changes nothing on screen.
    private func learnt(_ signedIn: SignedIn?) {
        if signedIn != nil || standing != nil { show(signedIn) }
    }

    /// Shows who is signed in. The drive shows one of its own for the frames of long words.
    package func show(_ signedIn: SignedIn?, byAPerson: Bool = false) {
        shown += 1
        standing = signedIn
        onStanding(signedIn, byAPerson)
        onChange()
    }
}
