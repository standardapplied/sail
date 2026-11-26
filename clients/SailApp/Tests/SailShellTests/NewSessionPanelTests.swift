import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTestPasskey
import SailWorkbench
import Testing

/// A1: a person starts an agent from a workspace row of their own. The row's + and its menu's
/// items, and none of them on someone else's row; ⌥⌘T on the chosen row; the New Session panel
/// drawn from the recorded agents route of the drive's own workspaces: what it lists, in the
/// server's words, where it hangs at every width and right to left, what each key does, what Start
/// starts and what is remembered; every refusal said in place and never a command; every control in
/// reach in the turn it shows, in both looks, with one accent primary and no grey.
///
/// One test at a time: each waits on the one main actor every AppKit test shares for the route's
/// answer, within a bound of the clock, while the sweeps beside it (every scene at every width,
/// both directions) hold that actor in long stretches. Side by side on a loaded runner the bound
/// ran out on waits that take a fraction of it alone (CI; and the owner's Mac on its efficiency
/// cores, red in 6 of 6 runs in parallel and green in 3 of 3 one at a time).
@MainActor @Suite(.serialized, .timeLimit(.minutes(2))) struct NewSessionPanelTests {
    init() {
        ExitWatch.install()
    }

    static let claude = AgentStart(harness: "claude-code", display: "Claude Code", mode: "default")
    static let storedLine = "Stored Claude Code key “default”. Removed the key from the clipboard."

    /// harbor listed for uday (dock and pier his, quay ana's), what starts in each answered as
    /// `agents` recorded, `width` wide in `look`.
    static func listed(
        _ agents: [String], rows: Exchange? = nil, also: [Exchange] = [], width: Double = 1000, look: Look = .light, person: WorkspacesModel.Person = WorkspacesViewTests.uday,
        saying words: String = "dock"
    ) async throws -> (WorkspacesView, KeyWindow, RecordedServer) {
        let listing = try rows.map { $0 } ?? Exchange("workspaces-three")
        let recorded = try agents.map { try Exchange($0) }
        let server = RecordedServer([try Exchange("projects"), listing] + recorded + also)
        let (view, window) = try WorkspacesViewTests.view(server, width: width)
        view.person = person
        view.look = look
        view.project.list.pointer = { nil }
        view.newSession.popover.keysLeft = Notification.Name("sail.tests.keys-left.\(UUID().uuidString)")
        view.shown()
        try #require(await RunnerTabTests.until { !view.busy && view.text.contains(words) }, "nothing listed: \(view.text)")
        view.layoutSubtreeIfNeeded()
        return (view, window, server)
    }

    static func key(_ code: UInt16, _ characters: String, _ modifiers: NSEvent.ModifierFlags = []) throws -> NSEvent {
        try #require(NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: modifiers, timestamp: 0, windowNumber: 0, context: nil,
            characters: characters, charactersIgnoringModifiers: characters, isARepeat: false, keyCode: code
        ))
    }

    static func optionCommandT() throws -> NSEvent {
        try key(17, "t", [.command, .option])
    }

    /// The panel opened by ⌥⌘T on row `name`, chosen with the list's keys, once it shows.
    static func open(_ name: String, in view: WorkspacesView) async throws -> NewSessionPanel {
        view.project.list.choose(name)
        view.window?.makeFirstResponder(view.project.list)
        #expect(view.project.list.onKey?(try optionCommandT()) == true, "⌥⌘T was not the list's")
        try #require(await RunnerTabTests.until { view.newSession.isOpen }, "⌥⌘T on \(name) opened nothing: \(view.text)")
        return view.newSession
    }

    /// A key pressed while the panel holds the window's keys.
    static func press(_ event: NSEvent, in window: NSWindow) throws {
        let keys = try #require(window.firstResponder as? NewSessionKeys, "the panel does not hold the keys: \(String(describing: window.firstResponder))")
        if event.modifierFlags.contains(.command) { _ = keys.performKeyEquivalent(with: event) } else { keys.keyDown(with: event) }
    }

    /// A button pressed through its action, as its click ends: no AppKit click is run, which here,
    /// where the press draws the panel anew and takes the button out of its window, stopped the
    /// test process's main run loop.
    static func press(_ button: NSButton?) throws {
        let button = try #require(button)
        #expect(button.sendAction(button.action, to: button.target), "\(button.title) sent nothing")
    }

    /// Store a Key chosen in `panel`'s Sign in pop-up, as its menu chooses it.
    static func storeKey(_ panel: NewSessionPanel) throws {
        let menu = try #require(panel.view.signIn?.menu, "no Sign in pop-up: \(panel.view.words)")
        let index = try #require(menu.items.firstIndex { $0.title == NewSessionWords.storeKey }, "Sign in offers no Store a Key")
        menu.performActionForItem(at: index)
    }

    static func down() throws -> NSEvent {
        try key(125, String(Character(try #require(UnicodeScalar(UInt16(NSDownArrowFunctionKey))))), [.function, .numericPad])
    }

    static func up() throws -> NSEvent {
        try key(126, String(Character(try #require(UnicodeScalar(UInt16(NSUpArrowFunctionKey))))), [.function, .numericPad])
    }

    static func returnKey() throws -> NSEvent {
        try key(36, "\r")
    }

    static func escape() throws -> NSEvent {
        try key(53, "\u{1b}")
    }

    /// The ⋯ menu of row `name`, as drawn.
    static func menu(_ name: String, in view: WorkspacesView) throws -> NSMenu {
        let list = view.project.list
        list.choose(name)
        let index = try #require(list.entries.firstIndex { if case let .item(item) = $0 { item.id == name } else { false } })
        let row = try #require(list.rowView(atRow: index, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        let more = try #require(WorkspacesViewTests.found(SailButton.self, in: row).first { $0.menu != nil })
        return try #require(more.menu)
    }

    /// The row's actions on it while it is chosen, by title, in order.
    static func onRow(_ name: String, in view: WorkspacesView) throws -> [String] {
        let list = view.project.list
        list.choose(name)
        let index = try #require(list.entries.firstIndex { if case let .item(item) = $0 { item.id == name } else { false } })
        let row = try #require(list.rowView(atRow: index, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        return WorkspacesViewTests.found(SailButton.self, in: row).filter { !$0.isHiddenOrHasHiddenAncestor }.map(\.title)
    }

    // MARK: The row

    /// The person's own row shows + beside ⋯, and its menu New Session ⌥⌘T after Open, then the
    /// harness last started once there is one; someone else's row, an admin's view of it included,
    /// shows neither; ⌘T is no row's.
    @Test func ownRowsOfferANewSessionAndOtherPeoplesNone() async throws {
        let (view, window, _) = try await Self.listed([])
        defer { window.orderOut(nil) }
        let dockActions = try Self.onRow("dock", in: view), quayActions = try Self.onRow("quay", in: view)
        #expect(dockActions == ["Open", NewSessionWords.newSession, "More"], "dock shows \(dockActions)")
        _ = try Self.onRow("dock", in: view)
        let plus = try #require(view.project.plus("dock") as? SailButton), size = plus.intrinsicContentSize
        #expect(near(size.width, size.height) && near(size.height, ButtonSize.small.height), "the + is not the small icon button ⋯ is: \(size)")
        #expect(quayActions == ["Open", "More"], "quay, ana's, shows \(quayActions)")
        let dock = try Self.menu("dock", in: view)
        #expect(dock.items.filter { !$0.isSeparatorItem }.map(\.title) == ["Open", "New Session", "Copy Name", "Stop", "Delete"])
        let item = try #require(dock.items.first { $0.title == "New Session" })
        #expect(item.keyEquivalent == "t" && item.keyEquivalentModifierMask == [.command, .option], "New Session's key is not ⌥⌘T")
        #expect(!dock.items.contains { $0.keyEquivalent == "t" && $0.keyEquivalentModifierMask == .command }, "⌘T is taken from New Tab")
        view.memory = ModeMemory(modes: [:], last: Self.claude)
        let again = try Self.menu("dock", in: view)
        #expect(again.items.filter { !$0.isSeparatorItem }.map(\.title) == ["Open", "New Claude Code Session", "New Session", "Copy Name", "Stop", "Delete"])
        let quay = try Self.menu("quay", in: view)
        #expect(!quay.items.contains { $0.title.hasPrefix("New ") }, "quay's menu offers \(quay.items.map(\.title))")
        #expect(!quay.items.contains { $0.title.contains("…") } && !again.items.contains { $0.title.contains("…") })
    }

    /// The row's quick item, "New Claude Code Session", asks what starts in the workspace first:
    /// the last start starts at once while it still can (a stored login; signing in there when
    /// that is how it last started), else the panel opens at that harness saying why, nothing started.
    static func quickItem(_ golden: String, last: AgentStart, row: String = "dock") async throws -> (view: WorkspacesView, window: KeyWindow, started: [AgentStart?], asked: Int) {
        let (view, window, server) = try await Self.listed([golden])
        var memory = ModeMemory()
        memory.remember(last)
        view.memory = memory
        var started: [AgentStart?] = []
        view.onStart = { start, _, _ in started.append(start) }
        let menu = try Self.menu(row, in: view)
        let index = try #require(menu.items.firstIndex { $0.title == "New \(last.display) Session" })
        menu.performActionForItem(at: index)
        try #require(await RunnerTabTests.until { !view.busy }, "the quick item's ask never ended")
        let asked = await server.requests.filter { $0.route.hasSuffix("/agents") }.count
        return (view, window, started, asked)
    }

    @Test func theQuickItemStartsAStoredLoginsLastStartAtOnce() async throws {
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")
        let quick = try await Self.quickItem("workspace-agents-dock", last: plan)
        defer { quick.window.orderOut(nil) }
        #expect(quick.started == [plan] && quick.asked == 1 && !quick.view.newSession.isOpen, "started \(quick.started), asked \(quick.asked)")
    }

    @Test func theQuickItemSignsInThereAgainWhenThatIsHowItLastStarted() async throws {
        let inside = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan", inside: true)
        let quick = try await Self.quickItem("workspace-agents-dock-no-login", last: inside)
        defer { quick.window.orderOut(nil) }
        #expect(quick.started == [inside] && !quick.view.newSession.isOpen, "started \(quick.started)")
    }

    /// The key it last started on gone, the quick item opens the panel at the harness on its own
    /// sign-in rather than start it another way unasked.
    @Test func theQuickItemOpensThePanelWhenItsKeyIsGone() async throws {
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")
        let quick = try await Self.quickItem("workspace-agents-dock-no-login", last: plan)
        defer { quick.window.orderOut(nil) }
        let panel = quick.view.newSession
        #expect(quick.started.isEmpty && panel.isOpen, "started \(quick.started), the panel \(panel.isOpen ? "open" : "closed")")
        #expect(panel.view.rows.first(where: \.chosen)?.name.stringValue == "Claude Code" && panel.view.signIn?.title == "Your Claude account")
        #expect(panel.view.primary?.title == "Start Claude Code")
    }

    @Test func theQuickItemOpensThePanelAtAHarnessNoLongerInstalled() async throws {
        let codex = AgentStart(harness: "codex", display: "Codex CLI", mode: "full-access")
        let quick = try await Self.quickItem("workspace-agents-dock-not-installed", last: codex)
        defer { quick.window.orderOut(nil) }
        let panel = quick.view.newSession
        #expect(quick.started.isEmpty && panel.isOpen && panel.view.rows.first(where: \.chosen)?.name.stringValue == "Codex CLI")
        #expect(panel.view.words.contains("Codex CLI is not installed in harbor's workspaces; its agent installation lists Claude Code.") && panel.view.primary == nil)
    }

    @Test func theQuickItemOpensThePanelForAModeNoLongerOffered() async throws {
        let ghost = AgentStart(harness: "claude-code", display: "Claude Code", mode: "ghost")
        let quick = try await Self.quickItem("workspace-agents-dock", last: ghost)
        defer { quick.window.orderOut(nil) }
        #expect(quick.started.isEmpty && quick.view.newSession.isOpen && quick.view.newSession.view.modes?.title == "Claude Code's own", "a mode no longer offered started, or the panel did not fall back to the harness's own")
    }

    /// While what starts is asked, the row's + is in flight; a second ⌥⌘T takes the ask over (the
    /// newest press wins), and the panel opens once, for it.
    @Test func thePlusSaysItIsAskingAndTheNewestPressTakesTheAskOver() async throws {
        let transport = Held(RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-agents-dock")]), holding: "agents")
        let (view, window) = try WorkspacesViewTests.view(transport)
        defer { window.orderOut(nil) }
        view.project.list.pointer = { nil }
        view.newSession.popover.keysLeft = Notification.Name("sail.tests.keys-left.\(UUID().uuidString)")
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("quay") })
        view.project.list.choose("dock")
        window.makeFirstResponder(view.project.list)
        #expect(view.project.list.onKey?(try Self.optionCommandT()) == true)
        #expect(await RunnerTabTests.until { (view.project.plus("dock") as? SailButton)?.busyWord == NewSessionWords.asking }, "the + never said it was asking")
        #expect(view.project.list.onKey?(try Self.optionCommandT()) == true)
        transport.release()
        #expect(await RunnerTabTests.until { view.newSession.isOpen })
        #expect(transport.routes.filter { $0.hasSuffix("/agents") }.count == 2, "the second press did not ask again: \(transport.routes)")
        #expect(window.childWindows?.filter { $0 === view.newSession.popover.panel }.count == 1, "the panel opened twice")
        #expect((view.project.plus("dock") as? SailButton)?.busyWord == nil, "the + stayed in flight")
    }

    /// The newest press wins: a quick item pressed on pier while dock's ask is out takes it over,
    /// pier then marked asking; dock's answer, landing after, starts nothing.
    @Test func aQuickItemOnAnotherRowTakesOverTheAsk() async throws {
        let pier = Exchange(method: "GET", path: "/v1/projects/harbor/workspaces/pier/agents", status: 200, body: try Exchange("workspace-agents-dock").body)
        let transport = Held(RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-agents-dock"), pier]), holding: "agents")
        let (view, window) = try WorkspacesViewTests.view(transport)
        defer { window.orderOut(nil) }
        view.project.list.pointer = { nil }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("quay") })
        view.memory = ModeMemory(modes: ["claude-code": "plan"], last: AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan"))
        var started: [String] = []
        view.onStart = { _, _, name in started.append(name) }
        view.project.on(.startLast("dock"))
        view.project.on(.startLast("pier"))
        _ = try Self.onRow("pier", in: view)
        #expect((view.project.plus("pier") as? SailButton)?.busyWord == NewSessionWords.asking, "pier is not marked asking")
        transport.release()
        #expect(await RunnerTabTests.until { !view.busy }, "the asks never ended")
        #expect(started == ["pier"], "the earlier ask's answer acted: started \(started)")
    }

    /// The newest press wins on one row too: + pressed while dock's quick item is asking opens the
    /// panel, and the quick item's answer starts nothing.
    @Test func thePlusDuringAQuickItemsAskOpensThePanelAndStartsNothing() async throws {
        let transport = Held(RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-agents-dock")]), holding: "agents")
        let (view, window) = try WorkspacesViewTests.view(transport)
        defer { window.orderOut(nil) }
        view.project.list.pointer = { nil }
        view.newSession.popover.keysLeft = Notification.Name("sail.tests.keys-left.\(UUID().uuidString)")
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("quay") })
        view.memory = ModeMemory(modes: ["claude-code": "plan"], last: AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan"))
        var started = 0
        view.onStart = { _, _, _ in started += 1 }
        view.project.on(.startLast("dock"))
        view.project.on(.newSession("dock"))
        transport.release()
        #expect(await RunnerTabTests.until { !view.busy && view.newSession.isOpen }, "the + did not open the panel")
        #expect(started == 0, "the quick item's answer started an agent the person did not last ask for")
    }

    /// An answer landing while the section is hidden takes its row's "Asking" away all the same.
    @Test func anAnswerWhileHiddenTakesTheAskingMarkAway() async throws {
        let transport = Held(RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-agents-dock")]), holding: "agents")
        let (view, window) = try WorkspacesViewTests.view(transport)
        defer { window.orderOut(nil) }
        view.project.list.pointer = { nil }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("quay") })
        view.project.on(.newSession("dock"))
        view.isHidden = true
        transport.release()
        #expect(await RunnerTabTests.until { !view.busy })
        view.isHidden = false
        _ = try Self.onRow("dock", in: view)
        #expect((view.project.plus("dock") as? SailButton)?.busyWord == nil && !view.newSession.isOpen, "dock still says it is asking, or a panel opened over a hidden section")
    }

    /// The defaults key joins control plane and handle by a separator neither holds: two people can
    /// never share one.
    @Test func noTwoPeopleShareAKey() {
        let one = WorkspacesModel.Person(handle: "c", admin: false, confirmed: true, controlPlane: "a.b")
        let other = WorkspacesModel.Person(handle: "b.c", admin: false, confirmed: true, controlPlane: "a")
        #expect(ModeMemory.key(for: one) != ModeMemory.key(for: other))
    }

    /// Two control planes on one host, on two ports, are two places: the same handle signed in to
    /// each is a person of each, with keys of their own.
    @Test func twoControlPlanesOnOneHostAreTwoPlaces() throws {
        let unconfirmed: AccountRefusal
        do { _ = try ControlPlane(address: "not an address"); Issue.record("a non-address was taken"); return } catch { unconfirmed = error }
        let signedIn = { (address: String) throws -> SignedIn in
            SignedIn(controlPlane: try ControlPlane(address: address), fde: "uday", until: .distantFuture, whoami: .failure(unconfirmed))
        }
        let one = WorkspacesModel.Person(try signedIn("https://main.acme.dev")), other = WorkspacesModel.Person(try signedIn("https://main.acme.dev:8443"))
        #expect(one.controlPlane == "https://main.acme.dev" && other.controlPlane == "https://main.acme.dev:8443", "the people are at \(one.controlPlane) and \(other.controlPlane)")
        #expect(ModeMemory.key(for: one) != ModeMemory.key(for: other))
    }

    /// What a person started is theirs on that control plane: the next person signing in on this
    /// Mac, or the same handle on another control plane, inherits no quick item and no mode.
    @Test func startsAreRememberedPerPersonAndControlPlane() async throws {
        let defaults = ReattachTests.defaults()
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")
        var udays = ModeMemory()
        udays.remember(plan)
        udays.store(in: defaults, for: WorkspacesViewTests.uday)
        let other = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: "main.acme.dev")
        #expect(ModeMemory.stored(in: defaults, for: WorkspacesViewTests.uday) == udays)
        #expect(ModeMemory.stored(in: defaults, for: WorkspacesViewTests.ana) == ModeMemory() && ModeMemory.stored(in: defaults, for: other) == ModeMemory())
        let (view, window, _) = try await Self.listed([])
        defer { window.orderOut(nil) }
        view.remembered = { ModeMemory.stored(in: defaults, for: $0) }
        view.person = WorkspacesViewTests.ana
        #expect(view.memory == ModeMemory(), "ana inherited uday's starts")
        view.person = WorkspacesViewTests.uday
        #expect(view.memory == udays, "uday's starts did not come back with him")
    }

    // MARK: The panel

    /// The usual case (dock, holding both sign-ins): the title names the workspace; every kind an
    /// equal row in the route's order, then the Shell, each a tile (its monogram, the Shell's
    /// terminal), its name and how it signs in (the stored key, not what Sign in shows); the first
    /// chosen, with its options under its name: Mode at the harness's own default and that mode's
    /// words, then Sign in on its account and that dock holds it; the one primary names it. It
    /// hangs 4 pt under the row, its trailing edge on ⋯'s, 388 pt wide.
    @Test func thePanelListsWhatTheRouteOffersInItsWords() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock-signed-in"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        let shown = panel.view
        #expect(shown.rows.map(\.name.stringValue) == ["Claude Code", "Codex CLI", "Shell"])
        #expect(shown.rows.map(\.line.stringValue) == ["API key “default”", "API key “work”", "A terminal in dock, no agent"])
        #expect(shown.rows.map(\.tile) == [.monogram("Cl"), .monogram("Co"), .icon(.terminal)])
        #expect(shown.rows.allSatisfy { near($0.frame.height, NewSessionView.row) && near($0.frame.width, shown.rows[0].frame.width) }, "the rows are not equal")
        #expect(shown.rows.map(\.chosen) == [true, false, false])
        #expect(shown.words.first == "New session in dock")
        #expect(shown.modes?.title == "Claude Code's own" && shown.signIn?.title == "Your Claude account")
        let order = try ["Shell", "CLAUDE CODE", "Mode", "Claude Code's own", "Starts as Claude Code starts by itself: the project's default mode, else Auto where it is available, else Manual.", "Sign in", "Your Claude account", "Already signed in in dock.", "Start Claude Code"].map { said in
            try #require(shown.words.firstIndex(of: said), "\(said) is not said: \(shown.words)")
        }
        #expect(order == order.sorted(), "the options do not stand under the rows in order: \(shown.words)")
        #expect(!shown.words.contains { $0.contains("asks you to sign in") }, "a workspace holding the sign-in was told to sign in")
        #expect(shown.primary?.isEnabled == true && shown.words.contains(NewSessionWords.hint(.rows, modes: true)) && shown.warned == nil)
        #expect(!shown.words.contains { $0.contains("stopped") }, "a running workspace said it is stopped")
        let row = try #require(view.project.rowFrame("dock").map { window.convertToScreen($0) }), box = panel.popover.frame
        #expect(near(box.width, NewSessionPanel.width) && near(box.maxY, row.minY - NewSessionPanel.gap), "the panel is at \(box), the row at \(row)")
        #expect(near(box.maxX, row.maxX - RowStyle.list.trailing), "its trailing edge \(box.maxX) is not ⋯'s \(row.maxX - RowStyle.list.trailing)")
        #expect(near(box.height, shown.height) && window.childWindows?.contains(panel.popover.panel) == true)
    }

    /// ↓ and ↑ choose and stop at the ends; the chosen harness alone shows its mode; Codex, of one
    /// mode, shows it as words with the server's note; ⌘M opens the mode's menu, its labels the
    /// server's with the chosen one checked; a mode chosen there is what Return starts, on the
    /// harness's own sign-in where nothing else was chosen, with no command, and is remembered for
    /// that harness with the start.
    @Test func theKeysChooseAndStartAndTheChoiceIsRemembered() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        var popped: [SailButton] = []
        panel.popUp = { popped.append($0) }
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.rows.map(\.chosen) == [false, true, false] && panel.view.modes == nil)
        let fixed = try #require(WorkspacesViewTests.found(SailButton.self, in: panel.view).first { $0.title == "Full access" }, "Codex's one mode is not shown")
        #expect(!fixed.isEnabled && fixed.menu == nil, "a harness of one mode offers a menu")
        #expect(panel.view.words.contains { $0.hasPrefix("Sail starts Codex CLI in a workspace with full access") } && panel.view.primary?.title == "Start Codex CLI")
        try Self.press(try Self.key(46, "m", .command), in: window)
        #expect(popped.isEmpty, "⌘M opened a menu for a harness of one mode")
        #expect(panel.view.words.contains(NewSessionWords.hint(.rows, modes: false)) && !panel.view.words.contains(NewSessionWords.hint(.rows, modes: true)), "⌘M is named where it opens nothing")
        for _ in 0 ..< 3 { try Self.press(try Self.down(), in: window) }
        #expect(panel.view.rows.map(\.chosen) == [false, false, true], "↓ went past the Shell")
        #expect(panel.view.words.contains(NewSessionWords.hint(.rows, modes: false)) && !panel.view.words.contains(NewSessionWords.hint(.rows, modes: true)), "⌘M is named on the Shell")
        for _ in 0 ..< 4 { try Self.press(try Self.up(), in: window) }
        #expect(panel.view.rows.map(\.chosen) == [true, false, false], "↑ went past the first")
        try Self.press(try Self.key(46, "m", .command), in: window)
        let modes = try #require(popped.first?.menu, "⌘M opened no mode menu")
        #expect(popped.first === panel.view.modes)
        #expect(modes.items.map(\.title) == ["Claude Code's own", "Manual", "Accept edits", "Plan", "Auto", "Don't ask", "Bypass permissions"])
        #expect(modes.items.filter { $0.state == .on }.map(\.title) == ["Claude Code's own"])
        modes.performActionForItem(at: 3)
        #expect(panel.view.modes?.title == "Plan" && panel.view.words.contains("Researches and proposes changes, and edits nothing until you approve the plan."))
        var started: [(AgentStart?, String, String)] = []
        view.onStart = { started.append(($0, $1, $2)) }
        try Self.press(try Self.returnKey(), in: window)
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan", inside: true)
        #expect(started.count == 1 && started.first?.0 == plan && started.first?.1 == "harbor" && started.first?.2 == "dock")
        #expect(plan.wire.harness == "claude-code" && plan.wire.mode == "plan" && plan.wire.inside)
        #expect(!panel.isOpen && window.firstResponder === view.project.list, "the keys did not go back to the list")
        #expect(view.memory == ModeMemory(modes: ["claude-code": "plan"], ways: ["claude-code": .signIn], last: plan))
        let reopened = try await Self.open("dock", in: view)
        #expect(reopened.view.modes?.title == "Plan", "the mode chosen was not remembered")
    }

    /// The Shell starts a new shell in the workspace, never the row's Open; Escape closes and
    /// nothing starts; so does a click outside; the + again closes it.
    @Test func theShellStartsANewShellAndEscapeStartsNothing() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        var opened: [String] = [], shells: [String] = [], started = 0
        view.onOpen = { opened.append("\($0) \($1)") }
        view.onStart = { start, project, name in
            if start == nil { shells.append("\(project) \(name)") } else { started += 1 }
        }
        _ = try await Self.open("dock", in: view)
        #expect(WorkspacesViewTests.footSays(view.project) == ["New"], "the foot names the list's keys while the panel holds them: \(WorkspacesViewTests.footSays(view.project))")
        try Self.press(try Self.optionCommandT(), in: window)
        #expect(view.newSession.isOpen && shells.isEmpty && started == 0 && opened.isEmpty, "⌥⌘T, not named while the panel is open, did something")
        try Self.press(try Self.escape(), in: window)
        #expect(!view.newSession.isOpen && started == 0 && opened.isEmpty, "Escape left it open or started something")
        #expect(WorkspacesViewTests.footSays(view.project) == ["Open", "New Session", "New"])
        _ = try await Self.open("dock", in: view)
        let outside = try #require(NSEvent.mouseEvent(with: .leftMouseDown, location: NSPoint(x: 5, y: 5), modifierFlags: [], timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1))
        view.newSession.popover.clickedAnywhere(outside)
        #expect(!view.newSession.isOpen && started == 0, "a click outside left it open")
        _ = try await Self.open("dock", in: view)
        let panelWindow = view.newSession.popover.panel
        let onTheShadow = try #require(NSEvent.mouseEvent(with: .leftMouseDown, location: NSPoint(x: 2, y: 2), modifierFlags: [], timestamp: 0, windowNumber: panelWindow.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1))
        view.newSession.popover.clickedAnywhere(onTheShadow)
        #expect(!view.newSession.isOpen, "a click on its shadow, outside the card, left it open")
        let panel = try await Self.open("dock", in: view)
        for _ in 0 ..< 2 { try Self.press(try Self.down(), in: window) }
        #expect(panel.view.primary?.title == "Open Shell" && panel.view.modes == nil && panel.view.signIn == nil)
        #expect(!panel.view.words.contains { $0 == "Mode" || $0 == "Sign in" }, "the Shell shows options: \(panel.view.words)")
        try Self.press(try Self.returnKey(), in: window)
        #expect(shells == ["harbor dock"] && opened.isEmpty && started == 0, "the Shell started \(shells) and opened \(opened)")
        view.project.on(.newSession("dock"))
        #expect(await RunnerTabTests.until { view.newSession.isOpen })
        view.project.on(.newSession("dock"))
        #expect(!view.newSession.isOpen, "the + again did not close it")
        _ = try await Self.open("dock", in: view)
        #expect(try WorkspacesViewTests.commandNReachesNewWorkspace(from: window.firstResponder, in: view.project), "⌘N, named while the panel is open, does not reach New Workspace")
    }

    /// A stopped workspace is a fact under the title; it is not read for its sign-in, so Start signs
    /// in as for a workspace not holding it.
    @Test func aStoppedWorkspaceIsSaidAsAFact() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-pier"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("pier", in: view)
        #expect(panel.view.words.contains("pier is stopped. Starting a session starts it."))
        #expect(panel.view.primary?.title == "Start Claude Code" && panel.view.words.contains("Claude Code asks you to sign in with your Claude account when it starts, once in each workspace."))
    }

    /// No key stored: no refusal, the harness's own sign-in is the way in. Sign in reads its name and
    /// lists it, a separator and Store a Key; the server's sentence says what happens, once; Start
    /// and Sign In starts it with `inside`. No command shows, ever.
    @Test func noKeyStoredTheSignInIsTheWayIn() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock-no-login"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        let words = panel.view.words
        #expect(panel.view.rows.first?.line.stringValue == "Your Claude account")
        let menu = try #require(panel.view.signIn?.menu)
        #expect(menu.items.map { $0.isSeparatorItem ? "-" : $0.title } == ["Your Claude account", "-", "Store a Key"])
        #expect(menu.items.filter { $0.state == .on }.map(\.title) == ["Your Claude account"])
        #expect(panel.view.signIn?.title == "Your Claude account")
        let order = try ["Mode", "Claude Code's own", "Sign in", "Claude Code asks you to sign in with your Claude account when it starts, once in each workspace."].map { said in
            try #require(words.firstIndex(of: said), "\(said) is not said: \(words)")
        }
        #expect(order == order.sorted(), "\(words)")
        #expect(!words.contains { $0.contains("login stored") || $0.contains("Store a") }, "a refusal or a link of the old panel shows: \(words)")
        #expect(panel.view.primary?.title == "Start Claude Code" && !words.contains("Start and Sign In"))
        #expect(!words.contains { $0.contains("agent login") || $0.contains("/login") || $0.contains("sail ") }, "a command shows: \(words)")
        var started: [AgentStart?] = []
        view.onStart = { start, _, _ in started.append(start) }
        try Self.press(try Self.returnKey(), in: window)
        #expect(started.map { [$0?.wire.harness, $0?.wire.mode] } == [["claude-code", "default"]] && started.allSatisfy { $0?.wire.inside == true })
        #expect(view.memory.last == AgentStart(harness: "claude-code", display: "Claude Code", mode: "default", inside: true), "a start that signed in is not remembered as one")
    }

    /// With a key stored, Sign in lists the account first, then the key, a separator and Store a
    /// Key; the account is chosen until the key is. The key chosen: the row says it, the sentence
    /// goes, Start is plain and starts on the key, and the choice is remembered for that harness.
    @Test func theSignInPopUpOffersTheAccountThenTheKeyAndTheChoiceIsRemembered() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        let menu = try #require(panel.view.signIn?.menu)
        #expect(menu.items.map { $0.isSeparatorItem ? "-" : $0.title } == ["Your Claude account", "API key “default”", "-", "Store a Key"])
        #expect(panel.view.signIn?.title == "Your Claude account" && panel.view.primary?.title == "Start Claude Code")
        #expect(panel.view.rows.first?.line.stringValue == "API key “default”", "the row repeats Sign in's choice")
        #expect(panel.view.words.contains("Claude Code asks you to sign in with your Claude account when it starts, once in each workspace."))
        #expect(panel.view.signIn?.accessibilityLabel() == "Sign in" && panel.view.signIn?.accessibilityValue() as? String == "Your Claude account")
        menu.performActionForItem(at: 1)
        #expect(panel.view.signIn?.title == "API key “default”" && panel.view.rows.first?.line.stringValue == "API key “default”")
        #expect(panel.view.primary?.title == "Start Claude Code" && !panel.view.words.contains { $0.contains("asks you to sign in") })
        #expect(panel.view.words.contains("Or your Claude account, chosen here."))
        var started: [AgentStart?] = []
        view.onStart = { start, _, _ in started.append(start) }
        try Self.press(try Self.returnKey(), in: window)
        #expect(started.map { [$0?.wire.harness, $0?.wire.mode] } == [["claude-code", "default"]] && started.allSatisfy { $0?.wire.inside == false })
        let reopened = try await Self.open("dock", in: view)
        #expect(reopened.view.signIn?.title == "API key “default”", "the key chosen was not remembered")
        try Self.press(try Self.down(), in: window)
        #expect(reopened.view.signIn?.title == "Your ChatGPT account", "Codex took Claude Code's choice")
    }

    /// ⇥ reaches Sign in, drawn holding the keys; Space and ↑ ↓ open its menu there; ⇥ again gives
    /// the keys back to the rows, which ↓ then moves. Choosing a row puts Sign in's focus away.
    @Test func tabReachesSignInAndSpaceOrTheArrowsOpenIt() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        var popped: [SailButton] = []
        panel.popUp = { popped.append($0) }
        try Self.press(try Self.key(48, "\t"), in: window)
        #expect(panel.signInFocused && panel.view.signIn?.holdsKeys == true)
        try Self.press(try Self.key(49, " "), in: window)
        try Self.press(try Self.down(), in: window)
        try Self.press(try Self.up(), in: window)
        #expect(popped.count == 3 && popped.allSatisfy { $0 === panel.view.signIn }, "Space and the arrows opened \(popped.count) menus")
        #expect(panel.view.rows.map(\.chosen) == [true, false, false], "an arrow moved the rows while Sign in held the keys")
        try Self.press(try Self.key(48, "\t", .shift), in: window)
        #expect(!panel.signInFocused && panel.view.signIn?.holdsKeys == false)
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.rows.map(\.chosen) == [false, true, false] && popped.count == 3)
        try Self.press(try Self.key(48, "\t"), in: window)
        try Self.press(try Self.down(), in: window)
        try Self.press(try Self.key(48, "\t"), in: window)
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.rows.map(\.chosen) == [false, false, true] && !panel.signInFocused)
        try Self.press(try Self.key(48, "\t"), in: window)
        #expect(!panel.signInFocused, "⇥ focused a Sign in the Shell does not have")
    }

    /// Mode and Sign in stand on one column: their pop-ups start at the same place.
    @Test func modeAndSignInStandOnOneColumn() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        let mode = try #require(panel.view.modes), signIn = try #require(panel.view.signIn)
        #expect(near(mode.frame.minX, signIn.frame.minX), "Mode's pop-up starts at \(mode.frame.minX), Sign in's at \(signIn.frame.minX)")
        #expect(signIn.frame.minY > mode.frame.maxY)
    }

    /// A harness not installed: its row is dimmed and says the server's few words for it; chosen,
    /// the server's sentence and its remedy's words stand in place of its options, and there is no
    /// primary, the keys still named.
    @Test func aHarnessNotInstalledIsSaidInPlace() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock-not-installed"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        #expect(panel.view.rows.map(\.line.stringValue).dropFirst().first == "Not installed in harbor")
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.words.contains("Codex CLI is not installed in harbor's workspaces; its agent installation lists Claude Code."))
        #expect(panel.view.words.contains("An admin adds codex to harbor's agent install list and builds its template again."))
        #expect(panel.view.primary == nil && panel.view.modes == nil && panel.view.signIn == nil, "a harness that does not start offers a Start or options")
        #expect(panel.view.words.contains(NewSessionWords.hint(.rows, modes: false)))
        var started = 0
        view.onStart = { _, _, _ in started += 1 }
        try Self.press(try Self.returnKey(), in: window)
        #expect(started == 0 && panel.isOpen, "Return started a harness not installed")
    }

    /// A mode the server calls risky says its words in the warning tone after the warning mark,
    /// wherever they show: Bypass permissions chosen by ⌘M, Codex's one mode with its note, and a
    /// made-up harness's mode under a name the app has never seen; a mode not flagged says them
    /// quietly, Bypass permissions too once the server stops flagging it.
    @Test func aRiskyModeWarnsInItsOwnTone() async throws {
        let tide = try Exchange("workspace-agents-dock").offering([Exchange.madeUpOffer("Tide", risky: true)])
        let unflagged = try Exchange("workspace-agents-dock").altered(at: ["offered", 0, "modes", 6, "risky"], to: false)
        let (view, window, _) = try await Self.listed([], also: [tide])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        #expect(panel.view.warned == nil)
        try Self.mode("Bypass permissions", in: panel)
        #expect(panel.view.warned == "Skips every permission prompt and safety check.")
        let label = try #require(WorkspacesViewTests.found(NSTextField.self, in: panel.view).first { $0.stringValue == "Skips every permission prompt and safety check." })
        #expect(label.attributedStringValue.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor == NSColor(Tint(view.look.warning)), "the warning is not in the warning tone")
        try Self.mode("Plan", in: panel)
        #expect(panel.view.warned == nil, "a mode not flagged warns")
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.warned?.hasPrefix("Sail starts Codex CLI in a workspace with full access") == true, "Codex's one risky mode says its note quietly")
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.rows.first(where: \.chosen)?.name.stringValue == "Tide" && panel.view.warned == nil)
        try Self.mode("Reckless", in: panel)
        #expect(panel.view.warned == "Runs anything without asking.", "a made-up harness's risky mode does not warn")
        let (calm, calmWindow, _) = try await Self.listed([], also: [unflagged])
        defer { calmWindow.orderOut(nil) }
        let quiet = try await Self.open("dock", in: calm)
        try Self.mode("Bypass permissions", in: quiet)
        #expect(quiet.view.warned == nil, "a mode warned by its name")
    }

    /// `label` chosen in the chosen harness's mode menu, as the menu chooses it.
    static func mode(_ label: String, in panel: NewSessionPanel) throws {
        let menu = try #require(panel.view.modes?.menu, "no mode menu: \(panel.view.words)")
        let index = try #require(menu.items.firstIndex { $0.title == label }, "no mode \(label)")
        menu.performActionForItem(at: index)
    }

    /// Past seven rows typing narrows them: "op" leaves the rows whose names start with it, the
    /// first chosen with its options; ⌫ widens them again; what matches no row is not taken, and
    /// nothing typed reaches the list beneath. Seven rows are never narrowed.
    @Test func pastSevenRowsTypingNarrowsThem() async throws {
        let names = ["Pi", "OpenCode", "Gemini CLI", "Aider", "Goose", "Opal"]
        let eight = try Exchange("workspace-agents-dock").offering(names.map { Exchange.madeUpOffer($0) })
        let (view, window, _) = try await Self.listed([], also: [eight])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        #expect(panel.view.rows.count == 9)
        let tall = panel.view.height
        try Self.press(try Self.key(31, "o"), in: window)
        try Self.press(try Self.key(35, "p"), in: window)
        #expect(panel.view.rows.map(\.name.stringValue) == ["OpenCode", "Opal"] && panel.view.rows.map(\.chosen) == [true, false])
        #expect(panel.view.words.contains("OPENCODE") && panel.view.primary?.title == "Start OpenCode" && panel.view.height < tall)
        try Self.press(try Self.key(7, "x"), in: window)
        #expect(panel.view.rows.map(\.name.stringValue) == ["OpenCode", "Opal"], "what matches no row was taken")
        try Self.press(try Self.key(0, "A", .shift), in: window)
        #expect(panel.view.rows.map(\.name.stringValue) == ["Opal"] && panel.view.rows.first?.chosen == true)
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.rows.map(\.chosen) == [true], "↓ left the narrowed rows")
        for _ in 0 ..< 4 { try Self.press(try Self.key(51, "\u{7f}"), in: window) }
        #expect(panel.view.rows.count == 9 && panel.view.rows.first?.chosen == true)
        #expect(panel.isOpen && WorkspacesViewTests.footSays(view.project) == ["New"], "typing reached the list or closed the panel")
        let five = try Exchange("workspace-agents-dock").offering(names.prefix(4).map { Exchange.madeUpOffer($0) })
        let (seven, sevenWindow, _) = try await Self.listed([], also: [five])
        defer { sevenWindow.orderOut(nil) }
        let few = try await Self.open("dock", in: seven)
        try Self.press(try Self.key(31, "o"), in: sevenWindow)
        #expect(few.view.rows.count == 7 && few.view.rows.first?.chosen == true, "seven rows took typing")
    }

    /// The page's six kinds: equal rows, OpenCode dimmed with the server's few words, its tile in
    /// the quiet ink; the pointer over a row sets its name at full weight, as over the chosen one's,
    /// and leaving it sets it back.
    @Test func sixKindsAreEqualRowsAndThePointerStrengthensOne() async throws {
        let (view, window, _) = try await Self.listed([], also: [try Self.sixKinds()])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        let rows = panel.view.rows
        #expect(rows.map(\.name.stringValue) == ["Claude Code", "Codex CLI", "Pi", "OpenCode", "Gemini CLI", "Shell"])
        #expect(rows.map(\.line.stringValue) == ["API key “default”", "API key “work”", "Pi token “default”", "Not installed in harbor", "Gemini CLI token “default”", "A terminal in dock, no agent"])
        #expect(rows.map(\.tile) == [.monogram("Cl"), .monogram("Co"), .monogram("Pi"), .monogram("Op"), .monogram("Ge"), .icon(.terminal)])
        let font = { (row: NewSessionRow) in row.name.attributedStringValue.attribute(.font, at: 0, effectiveRange: nil) as? NSFont }
        let pi = rows[2], event = try #require(NSEvent.enterExitEvent(with: .mouseEntered, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, eventNumber: 0, trackingNumber: 0, userData: nil))
        #expect(font(pi) != font(rows[0]) && font(rows[0]) == .systemFont(ofSize: Look.Step.callout.points, weight: .semibold), "a row not chosen is at full weight")
        pi.mouseEntered(with: event)
        #expect(pi.hovered && font(pi) == font(rows[0]), "the pointer over Pi left its name at \(String(describing: font(pi)))")
        pi.mouseExited(with: event)
        #expect(!pi.hovered && font(pi) != font(rows[0]))
    }

    /// Someone else's workspace, by ⌥⌘T on its row: no list, the server's sentence and remedy, and
    /// Open a Shell, which starts a new shell there. An owner gone: the sentence, no remedy (it
    /// names a command), and Close.
    @Test func whereNoAgentStartsForThePersonItSaysWhy() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-quay"])
        defer { window.orderOut(nil) }
        var opened: [String] = [], shells: [String] = []
        view.onOpen = { opened.append("\($0) \($1)") }
        view.onStart = { start, project, name in if start == nil { shells.append("\(project) \(name)") } }
        let panel = try await Self.open("quay", in: view)
        #expect(panel.view.rows.isEmpty && panel.view.primary == nil)
        #expect(panel.view.words == ["New session in quay", "quay is ana's workspace, so an agent starts in it only as ana, with ana's login.", "Open a shell in it, or start the agent in a workspace of your own.", "Open a Shell"])
        try Self.press(panel.view.secondary)
        #expect(shells == ["harbor quay"] && opened.isEmpty && !panel.isOpen)
        let (gone, goneWindow, _) = try await Self.listed(["workspace-agents-pier-owner-gone"])
        defer { goneWindow.orderOut(nil) }
        let refused = try await Self.open("pier", in: gone)
        #expect(refused.view.words == ["New session in pier", "pier is ben's workspace, and ben is no longer active here, so no agent starts in it until the box's pass leaves it nobody's.", "Close"])
        try Self.press(refused.view.secondary)
        #expect(!refused.isOpen)
    }

    /// The route refused outright (a member's ⌥⌘T on someone else's row): its sentence, and Close.
    @Test func aRouteRefusedIsItsSentence() async throws {
        let (view, window, _) = try await Self.listed(["refusal-forbidden-not-assignee-agents"], person: WorkspacesViewTests.ana)
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        #expect(panel.view.rows.isEmpty && panel.view.primary == nil)
        #expect(panel.view.words == ["New session in dock", "Workspace 'dock' belongs to 'uday', not you.", "Ask uday to enter it, or have an admin do it.", "Close"], "\(panel.view.words)")
    }

    /// At the least window the panel spans what shows of the page less 8 pt on each side, under
    /// the row: never past the card, even where the section is laid out wider than the card shows.
    @Test func atTheLeastWindowThePanelSpansThePage() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"], width: WorkspacesViewTests.narrowestCard)
        defer { window.orderOut(nil) }
        let card = NSView(frame: try #require(window.contentView).frame)
        window.contentView = card
        view.frame = NSRect(x: 0, y: 0, width: WorkspacesViewTests.narrowestCard + 27, height: card.frame.height)
        card.addSubview(view)
        view.layoutSubtreeIfNeeded()
        #expect(view.project.narrow)
        let panel = try await Self.open("dock", in: view)
        let page = window.convertToScreen(view.project.convert(view.project.visibleRect, to: nil)), box = panel.popover.frame
        let row = try #require(view.project.rowFrame("dock").map { window.convertToScreen($0) })
        #expect(near(box.minX, page.minX + NewSessionPanel.inset) && near(box.maxX, page.maxX - NewSessionPanel.inset), "the panel is \(box) on what shows of the page \(page)")
        let content = window.convertToScreen(try #require(window.contentView).frame)
        #expect(box.minX >= content.minX + NewSessionPanel.inset - 0.5 && box.maxX <= content.maxX - NewSessionPanel.inset + 0.5, "the panel \(box) overhangs the window's content \(content)")
        #expect(near(box.maxY, row.minY - NewSessionPanel.gap))
    }

    /// Right to left the panel hangs from ⋯'s edge at the row's left, and its rows, bar and foot
    /// are mirrored.
    @Test func rightToLeftThePanelIsMirrored() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        for each in [view.project] + WorkspacesViewTests.found(NSView.self, in: view.project) {
            each.userInterfaceLayoutDirection = .rightToLeft
        }
        view.project.list.reloadData()
        let panel = try await Self.open("dock", in: view)
        let row = try #require(view.project.rowFrame("dock").map { window.convertToScreen($0) }), box = panel.popover.frame
        #expect(near(box.minX, row.minX + RowStyle.list.trailing), "the panel is \(box), the row \(row)")
        let first = try #require(panel.view.rows.first), width = panel.view.bounds.width
        let start = NewSessionRow.inset + NewSessionRow.tile + NewSessionRow.gap
        #expect(near(first.name.frame.maxX, first.bounds.width - start) && near(first.line.frame.maxX, first.name.frame.maxX), "the row is not mirrored")
        let primary = try #require(panel.view.primary)
        #expect(primary.frame.midX < width / 2, "the primary is not at the leading edge right to left")
        #expect(panel.view.words.first == "New session in dock")
    }

    /// With no room under the row, the panel hangs over it.
    @Test func withNoRoomUnderItsRowThePanelHangsOverIt() async throws {
        let rows = try WorkspacesReachTests.list((1 ... 30).map { ["name": String(format: "ws-%02d", $0), "instance": String(format: "harbor-ws-%02d", $0), "state": "running", "owner": "uday"] })
        let agents = Exchange(method: "GET", path: "/v1/projects/harbor/workspaces/ws-30/agents", status: 200, body: try Exchange("workspace-agents-dock").body)
        let (view, window, _) = try await Self.listed([], rows: rows, also: [agents], saying: "ws-30")
        defer { window.orderOut(nil) }
        let panel = try await Self.open("ws-30", in: view)
        let row = try #require(view.project.rowFrame("ws-30").map { window.convertToScreen($0) }), box = panel.popover.frame
        #expect(near(box.minY, row.maxY + NewSessionPanel.gap), "the panel is \(box), the row \(row)")
    }

    /// The panel follows its row or closes: the list scrolled, or drawn again with its row gone or
    /// stopped by someone else, it closes, never floating over another row while Start acts on dock.
    @Test func thePanelFollowsItsRowOrCloses() async throws {
        let many = try WorkspacesReachTests.list([["name": "dock", "instance": "harbor-dock", "state": "running", "owner": "uday"]] + (1 ... 30).map { ["name": String(format: "ws-%02d", $0), "instance": String(format: "harbor-ws-%02d", $0), "state": "running", "owner": "uday"] })
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"], rows: many, saying: "ws-30")
        defer { window.orderOut(nil) }
        _ = try await Self.open("dock", in: view)
        let clip = try #require(view.project.listScroll?.contentView)
        clip.scroll(to: NSPoint(x: 0, y: 40))
        view.project.listScroll?.reflectScrolledClipView(clip)
        #expect(!view.newSession.isOpen, "the list scrolled under the panel and it stayed")
        for (name, rows) in [("gone", [["name": "pier", "instance": "harbor-pier", "state": "stopped", "owner": "uday"]]), ("stopped by ana", [["name": "dock", "instance": "harbor-dock", "state": "stopped", "owner": "uday"]])] {
            let (shown, shownWindow, _) = try await Self.listed(["workspace-agents-dock"])
            defer { shownWindow.orderOut(nil) }
            _ = try await Self.open("dock", in: shown)
            shown.client = SailClient(transport: RecordedServer([try Exchange("projects"), try WorkspacesReachTests.list(rows)]), token: "tok")
            await shown.refresh()
            #expect(await RunnerTabTests.until { !shown.newSession.isOpen }, "dock \(name) and the panel stayed")
        }
        let (same, sameWindow, server) = try await Self.listed(["workspace-agents-dock"])
        defer { sameWindow.orderOut(nil) }
        _ = try await Self.open("dock", in: same)
        let before = await server.requests.count
        await same.refresh()
        #expect(await RunnerTabTests.until { !same.busy } && same.newSession.isOpen, "a listing that left dock where it was closed the panel")
        let after = await server.requests.count
        #expect(after > before, "the refresh asked nothing")
    }

    /// A closed panel's keys do nothing, and a list not shown takes no key: a Return after Start,
    /// with the section left, never presses the list's Open.
    @Test func aClosedPanelAndAHiddenListTakeNoKeys() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        var started = 0, opened = 0
        view.onStart = { _, _, _ in started += 1 }
        view.onOpen = { _, _ in opened += 1 }
        _ = try await Self.open("dock", in: view)
        let keys = try #require(window.firstResponder as? NewSessionKeys)
        try Self.press(try Self.escape(), in: window)
        keys.keyDown(with: try Self.returnKey())
        #expect(started == 0 && opened == 0, "a closed panel's keys started an agent or opened dock")
        view.isHidden = true
        view.project.list.keyDown(with: try Self.returnKey())
        #expect(opened == 0, "a hidden list's Return opened dock")
    }

    /// The panel closes when its section goes, another project is chosen, or the window is resized.
    @Test func thePanelGoesWithItsPlace() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        _ = try await Self.open("dock", in: view)
        view.isHidden = true
        #expect(!view.newSession.isOpen, "hiding the section left it open")
        view.isHidden = false
        _ = try await Self.open("dock", in: view)
        window.setContentSize(NSSize(width: 1100, height: 640))
        #expect(!view.newSession.isOpen, "a resize left it open")
    }

    /// What a person's starts remember outlives the app: kept in the defaults and read back by
    /// the next shell into its Workspaces section for whoever it signs in as.
    @Test func theStartsAreKeptBetweenLaunches() throws {
        let defaults = ReattachTests.defaults()
        let kept = ModeMemory(modes: ["claude-code": "plan"], ways: ["claude-code": .key], last: AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan"))
        #expect(ModeMemory.stored(in: defaults, for: WorkspacesViewTests.uday) == ModeMemory())
        kept.store(in: defaults, for: WorkspacesViewTests.uday)
        #expect(ModeMemory.stored(in: defaults, for: WorkspacesViewTests.uday) == kept)
        let shell = try ShellApp(fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in SoftwarePasskey(file: FileManager.default.temporaryDirectory.appending(path: "sail-a1-\(UUID().uuidString).json")) }, defaults: defaults)
        shell.workspaces.person = WorkspacesViewTests.uday
        #expect(shell.workspaces.memory == kept)
        defaults.set(Data("not a memory".utf8), forKey: ModeMemory.key(for: WorkspacesViewTests.uday))
        #expect(ModeMemory.stored(in: defaults, for: WorkspacesViewTests.uday) == ModeMemory(), "an unreadable memory is not read as nothing")
    }

    // MARK: Look, reach and calm

    /// The page's six kinds: dock's two, then Pi, OpenCode not installed, Gemini CLI and the Shell.
    static func sixKinds() throws -> Exchange {
        try Exchange("workspace-agents-dock").offering([Exchange.madeUpOffer("Pi"), Exchange.madeUpOffer("OpenCode", unavailable: "Not installed in harbor"), Exchange.madeUpOffer("Gemini CLI")])
    }

    /// Every scene the panel draws: each harness of dock chosen, a key just stored, dock holding its
    /// sign-ins, no key, not installed, stopped, someone else's, an owner gone, and six kinds.
    static func scenes() throws -> [(name: String, answer: Exchange, row: String, choices: Int)] {
        try [
            ("workspace-agents-dock", "dock", 3), ("workspace-agents-dock-signed-in", "dock", 2), ("workspace-agents-dock-no-login", "dock", 1),
            ("workspace-agents-dock-not-installed", "dock", 2),
            ("workspace-agents-pier", "pier", 1), ("workspace-agents-quay", "quay", 0), ("workspace-agents-pier-owner-gone", "pier", 0),
        ].map { ($0.0, try Exchange($0.0), $0.1, $0.2) } + [("six kinds", try sixKinds(), "dock", 6)]
    }

    /// Each scene at `width`, each of its first `choices` rows chosen in turn, handed to `check`.
    static func eachScene(width: Double, look: Look = .light, rightToLeft: Bool = false, _ check: (String, NewSessionPanel, KeyWindow) throws -> Void) async throws {
        for scene in try scenes() {
            let (view, window, _) = try await listed([], also: [scene.answer], width: width, look: look)
            defer { window.orderOut(nil) }
            if rightToLeft {
                for each in [view.project] + WorkspacesViewTests.found(NSView.self, in: view.project) {
                    each.userInterfaceLayoutDirection = .rightToLeft
                }
            }
            let panel = try await open(scene.row, in: view)
            for at in 0 ..< max(scene.choices, 1) {
                if at > 0 { try press(try down(), in: window) }
                try check("\(scene.name), row \(at + 1) chosen", panel, window)
            }
            if scene.name == "workspace-agents-dock" {
                let agents = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: scene.answer.body))
                var chose = ModeMemory()
                chose.choose(.key, for: "claude-code")
                panel.show(.agents(agents), memory: chose, stored: Self.storedLine, harness: "claude-code", inUse: true)
                try check("\(scene.name), a key just stored", panel, window)
            }
        }
    }

    /// Nothing the panel shows is out of reach at any width the frames draw, the least included,
    /// left to right and right to left: nothing outside its parent, squeezed, overlapping, clipped
    /// or cut without an ellipsis, and every button and row taking a click across its face.
    @Test(arguments: 0 ..< 5)
    func nothingInThePanelIsOutOfReach(_ index: Int) async throws {
        let width = WorkspacesReachTests.widths[index].section
        var faults: [String] = []
        for rightToLeft in [false, true] {
            try await Self.eachScene(width: width, rightToLeft: rightToLeft) { scene, panel, _ in
                let root = try #require(panel.popover.panel.contentView)
                faults += WorkspacesReachTests.outOfReach(in: root).map { "\(scene) at \(width) pt\(rightToLeft ? " right to left" : ""): \($0)" }
                faults += panel.view.rows.compactMap { WorkspacesReachTests.missesAClick($0).map { "\(scene): a row: \($0)" } }
            }
        }
        #expect(faults.isEmpty, "\(faults.count) faults, the first: \(faults.prefix(30).joined(separator: "\n"))")
    }

    /// The person's own rows with the longest name the server allows, each chosen, + beside ⋯, at
    /// every width the frames draw: nothing out of reach.
    @Test(arguments: 0 ..< 5)
    func ownRowsWithTheirPlusAreInReach(_ index: Int) async throws {
        let width = WorkspacesReachTests.widths[index].section
        let rows = try WorkspacesReachTests.list([
            ["name": "dock", "instance": "harbor-dock", "state": "running", "owner": "uday"],
            ["name": WorkspacesReachTests.long, "instance": "harbor-\(WorkspacesReachTests.long)", "state": "stopped", "owner": "uday"],
            ["name": "quay", "instance": "harbor-quay", "state": "running", "owner": "ana"],
        ])
        let (view, window, _) = try await Self.listed([], rows: rows, width: width)
        defer { window.orderOut(nil) }
        view.memory = ModeMemory(modes: [:], last: Self.claude)
        window.makeFirstResponder(view.project.list)
        var faults: [String] = []
        for id in WorkspacesReachTests.ids(view.project.list) {
            view.project.list.choose(id)
            faults += WorkspacesReachTests.outOfReach(in: view).map { "\(id.prefix(12)) chosen at \(width) pt: \($0)" }
        }
        #expect(faults.isEmpty, "\(faults.count) faults, the first: \(faults.prefix(30).joined(separator: "\n"))")
    }

    /// What the panel shows takes a click in the turn it opens and each time it is drawn again,
    /// before any display pass: its rows, the mode's and Sign in's pop-ups and its button.
    @Test func whatThePanelShowsTakesAClickAtOnce() async throws {
        var faults: [String] = []
        try await Self.eachScene(width: 1000) { scene, panel, _ in
            let controls: [NSView] = panel.view.rows + [panel.view.primary, panel.view.secondary, panel.view.modes, panel.view.signIn].compactMap { $0 }
            faults += controls.compactMap { WorkspacesReachTests.missesAClick($0).map { "\(scene): \($0)" } }
        }
        #expect(faults.isEmpty, "\(faults.count) faults: \(faults.joined(separator: "\n"))")
    }

    /// Calm, in both looks: the panel's one accent fill is its primary, none where nothing starts;
    /// its accent lines the chosen row's bar and check and, once a key is stored, the primary's
    /// halo; no tint, no grey; and the page under it draws no more than before it opened.
    @Test(arguments: [Look.light, .dark])
    func thePanelIsCalm(_ look: Look) async throws {
        var faults: [String] = []
        try await Self.eachScene(width: 1000, look: look) { scene, panel, _ in
            let count = CalmTests.Count(marks(in: panel.view))
            let primary = panel.view.primary == nil ? 0 : 1, halo = panel.view.primary?.holdsKeys == true ? 1 : 0
            let lines = (panel.view.rows.contains(where: \.chosen) ? 2 : 0) + halo
            if count.fill != primary || count.line != lines || count.tint != 0 || count.neutral != 0 || count.error != 0 {
                faults.append("\(scene): \(count), wanted \(primary) fills and \(lines) lines")
            }
        }
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"], look: look)
        defer { window.orderOut(nil) }
        let open = { () throws -> SailButton? in
            let index = try #require(view.project.list.entries.firstIndex { if case let .item(item) = $0 { item.id == "dock" } else { false } })
            let row = try #require(view.project.list.rowView(atRow: index, makeIfNecessary: true))
            return WorkspacesViewTests.found(SailButton.self, in: row).first { $0.title == "Open" }
        }
        _ = try await Self.open("dock", in: view)
        view.layoutSubtreeIfNeeded()
        let shown = CalmTests.Count(marks(in: window))
        #expect(shown.fill == 1 && shown.tint == 0 && shown.neutral == 0, "the window with the panel open draws \(shown): one primary on screen")
        #expect(try open()?.kind == .secondary, "the chosen row's Open stays primary under the panel")
        try Self.press(try Self.escape(), in: window)
        #expect(try open()?.kind == .primary, "the chosen row's Open is not primary again once the panel closed")
        #expect(CalmTests.Count(marks(in: window)).fill == 1)
        #expect(faults.isEmpty, "\(faults.joined(separator: "\n"))")
    }

    /// The floating box casts its shadow whole, rounded as its card is: in every look the shadow's
    /// reach fits the panel's room, so the window's edge never cuts it into a square band; each
    /// layer's blur stays inside the window; and no square corner of shadow stands past the card's
    /// rounded one. For the New Session panel and the user menu, which share the popover. The pixels
    /// of both, on screen, are the drive's (`newSessionShadowWhole`).
    @Test(arguments: [Look.light, .dark])
    func theFloatingBoxCastsItsShadowWhole(_ look: Look) async throws {
        for each in [Look.light, .dark, Look.light.contrasted(), Look.dark.contrasted()] {
            #expect(PopShadow.reach(each) <= Popover.room, "the pop shadow reaches \(PopShadow.reach(each)) pt, past the room of \(Popover.room) pt")
        }
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"], look: look)
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        var faults = try Self.shadowCut(panel.popover, card: panel.view, radius: NewSessionView.radius).map { "the New Session panel: \($0)" }
        let shell = try UserMenuTests.shell(look)
        defer { shell.window.orderOut(nil) }
        try UserMenuTests.open(shell, UserMenuTests.uday)
        faults += try Self.shadowCut(shell.userMenu.popover, card: shell.userMenu.view, radius: Look.contentRadius).map { "the user menu: \($0)" }
        shell.userMenu.close()
        #expect(faults.isEmpty, "\(faults.joined(separator: "\n"))")
    }

    /// How `popover`'s shadow is cut or squared: a layer whose blurred, offset path leaves the window,
    /// or a path that holds `card`'s own corner, which its `radius` rounds away.
    static func shadowCut(_ popover: Popover, card: NSView, radius: Double) throws -> [String] {
        let host = try #require(popover.panel.contentView)
        host.layoutSubtreeIfNeeded()
        let shadow = try #require(host.subviews.compactMap { $0 as? PopShadowView }.first)
        #expect(near(shadow.frame, card.frame), "the shadow is cast by \(shadow.frame), not the card's \(card.frame)")
        var faults: [String] = []
        for cast in shadow.cast {
            let path = try #require(cast.shadowPath)
            let reach = 2 * Double(cast.shadowRadius)
            let extent = path.boundingBox.offsetBy(dx: cast.shadowOffset.width, dy: cast.shadowOffset.height).insetBy(dx: -reach, dy: -reach)
            let inHost = host.convert(extent, from: shadow)
            if !host.bounds.insetBy(dx: -0.5, dy: -0.5).contains(inHost) { faults.append("a shadow layer reaches \(inHost), out of the window's \(host.bounds)") }
            let corner = CGPoint(x: path.boundingBox.minX + 0.5, y: path.boundingBox.minY + 0.5)
            if path.contains(corner) { faults.append("a shadow layer's path is square at \(corner); the card's corners are \(radius) pt round") }
        }
        if shadow.cast.isEmpty { faults.append("no shadow is cast") }
        return faults
    }

    /// A change of look while it shows reaches everything in it, at every width.
    @Test func aChangeOfLookReachesEverythingThePanelShows() async throws {
        var kept: [String] = []
        for (width, _) in WorkspacesReachTests.widths {
            try await Self.eachScene(width: width) { scene, panel, window in
                guard let view = window.contentView as? WorkspacesView else { return }
                view.look = .dark
                kept += WorkspacesViewTests.keptLightAnywhere(view).map { "\(scene) at \(width) pt: \($0)" }
                view.look = .light
            }
        }
        #expect(kept.isEmpty, "\(kept.prefix(20).joined(separator: "\n"))")
    }
}
