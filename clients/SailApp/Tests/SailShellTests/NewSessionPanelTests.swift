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
@MainActor @Suite struct NewSessionPanelTests {
    init() {
        ExitWatch.install()
    }

    static let claude = AgentStart(harness: "claude-code", display: "Claude Code", mode: "default")

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
        #expect(await RunnerTabTests.until { !view.busy && view.text.contains(words) }, "nothing listed: \(view.text)")
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
        #expect(await RunnerTabTests.until { view.newSession.isOpen }, "⌥⌘T on \(name) opened nothing: \(view.text)")
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

    /// The harness last started starts again from the menu, in its mode, without the panel and
    /// without asking what starts.
    @Test func theHarnessStartedLastStartsAgainWithoutThePanel() async throws {
        let (view, window, server) = try await Self.listed([])
        defer { window.orderOut(nil) }
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")
        view.memory = ModeMemory(modes: ["claude-code": "plan"], last: plan)
        var started: [(AgentStart, String, String)] = []
        view.onStart = { started.append(($0, $1, $2)) }
        let menu = try Self.menu("dock", in: view)
        let index = try #require(menu.items.firstIndex { $0.title == "New Claude Code Session" })
        menu.performActionForItem(at: index)
        #expect(started.count == 1 && started.first?.0 == plan && started.first?.1 == "harbor" && started.first?.2 == "dock")
        let asked = await server.requests
        #expect(!view.newSession.isOpen && !asked.contains { $0.route.hasSuffix("/agents") }, "the quick start asked or showed the panel")
    }

    // MARK: The panel

    /// The usual case (dock, signed in): the title names the workspace; one row per harness offered
    /// in the route's order with its login as the server names its kind, then the Shell; the
    /// first chosen, with Mode at the harness's own default and that mode's words; Start the one
    /// primary. It hangs 4 pt under the row, its trailing edge on ⋯'s, 372 pt wide.
    @Test func thePanelListsWhatTheRouteOffersInItsWords() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        let shown = panel.view
        #expect(shown.rows.map(\.name.stringValue) == ["Claude Code", "Codex CLI", "Shell"])
        #expect(shown.rows.map(\.line.stringValue) == ["login “default” · API key", "login “work” · API key", "a terminal, no agent"])
        #expect(shown.rows.map(\.chosen) == [true, false, false])
        #expect(shown.words.first == "New session in dock")
        #expect(shown.modes?.title == "Claude Code's own")
        #expect(shown.words.contains("Starts as Claude Code starts by itself: the project's default mode, else Auto where it is available, else Manual."))
        #expect(shown.primary?.title == "Start" && shown.primary?.isEnabled == true && shown.words.contains(NewSessionWords.hint))
        #expect(!shown.words.contains { $0.contains("stopped") }, "a running workspace said it is stopped")
        let row = try #require(view.project.rowFrame("dock").map { window.convertToScreen($0) }), box = panel.popover.frame
        #expect(near(box.width, NewSessionPanel.width) && near(box.maxY, row.minY - NewSessionPanel.gap), "the panel is at \(box), the row at \(row)")
        #expect(near(box.maxX, row.maxX - RowStyle.list.trailing), "its trailing edge \(box.maxX) is not ⋯'s \(row.maxX - RowStyle.list.trailing)")
        #expect(near(box.height, shown.height) && window.childWindows?.contains(panel.popover.panel) == true)
    }

    /// ↓ and ↑ choose and stop at the ends; the chosen harness alone shows its mode; Codex, of one
    /// mode, shows it as words with the server's note; ⌘M opens the mode's menu, its labels the
    /// server's with the chosen one checked; a mode chosen there is what Return starts, with no
    /// command, and is remembered for that harness with the start.
    @Test func theKeysChooseAndStartAndTheChoiceIsRemembered() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        var popped: [SailButton] = []
        panel.popUp = { popped.append($0) }
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.rows.map(\.chosen) == [false, true, false] && panel.view.modes == nil)
        #expect(panel.view.words.contains("Full access") && panel.view.words.contains { $0.hasPrefix("Sail starts Codex CLI in a workspace with full access") })
        try Self.press(try Self.key(46, "m", .command), in: window)
        #expect(popped.isEmpty, "⌘M opened a menu for a harness of one mode")
        for _ in 0 ..< 3 { try Self.press(try Self.down(), in: window) }
        #expect(panel.view.rows.map(\.chosen) == [false, false, true], "↓ went past the Shell")
        for _ in 0 ..< 4 { try Self.press(try Self.up(), in: window) }
        #expect(panel.view.rows.map(\.chosen) == [true, false, false], "↑ went past the first")
        try Self.press(try Self.key(46, "m", .command), in: window)
        let modes = try #require(popped.first?.menu, "⌘M opened no mode menu")
        #expect(popped.first === panel.view.modes)
        #expect(modes.items.map(\.title) == ["Claude Code's own", "Manual", "Accept edits", "Plan", "Auto", "Don't ask", "Bypass permissions"])
        #expect(modes.items.filter { $0.state == .on }.map(\.title) == ["Claude Code's own"])
        modes.performActionForItem(at: 3)
        #expect(panel.view.modes?.title == "Plan" && panel.view.words.contains("Researches and proposes changes, and edits nothing until you approve the plan."))
        var started: [(AgentStart, String, String)] = []
        view.onStart = { started.append(($0, $1, $2)) }
        try Self.press(try Self.returnKey(), in: window)
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")
        #expect(started.count == 1 && started.first?.0 == plan && started.first?.1 == "harbor" && started.first?.2 == "dock")
        #expect(plan.wire.harness == "claude-code" && plan.wire.mode == "plan" && !plan.wire.inside)
        #expect(!panel.isOpen && window.firstResponder === view.project.list, "the keys did not go back to the list")
        #expect(view.memory == ModeMemory(modes: ["claude-code": "plan"], last: plan))
        let reopened = try await Self.open("dock", in: view)
        #expect(reopened.view.modes?.title == "Plan", "the mode chosen was not remembered")
    }

    /// The Shell is the row's Open; Escape closes and nothing starts; so does a click outside; the
    /// + again closes it.
    @Test func theShellOpensTheRowAndEscapeStartsNothing() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock"])
        defer { window.orderOut(nil) }
        var opened: [String] = [], started = 0
        view.onOpen = { opened.append("\($0) \($1)") }
        view.onStart = { _, _, _ in started += 1 }
        _ = try await Self.open("dock", in: view)
        try Self.press(try Self.escape(), in: window)
        #expect(!view.newSession.isOpen && started == 0 && opened.isEmpty, "Escape left it open or started something")
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
        #expect(panel.view.primary?.title == "Start" && panel.view.modes == nil)
        try Self.press(try Self.returnKey(), in: window)
        #expect(opened == ["harbor dock"] && started == 0, "the Shell opened \(opened)")
        view.project.on(.newSession("dock"))
        #expect(await RunnerTabTests.until { view.newSession.isOpen })
        view.project.on(.newSession("dock"))
        #expect(!view.newSession.isOpen, "the + again did not close it")
    }

    /// A stopped workspace is a fact under the title; Start is unchanged.
    @Test func aStoppedWorkspaceIsSaidAsAFact() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-pier"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("pier", in: view)
        #expect(panel.view.words.contains("pier is stopped. Starting a session starts it."))
        #expect(panel.view.primary?.title == "Start" && panel.view.primary?.isEnabled == true)
    }

    /// No login stored, the harness signing in inside: the server's sentence, Store a Claude Code
    /// Login with the kinds beside it, the sign-in-inside line, and Start and Sign In There, which
    /// starts it with `inside`. Store a Login says how to make the kind the server names first, in
    /// place. No command shows, ever.
    @Test func noLoginStoredOffersBothActions() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock-no-login"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        let words = panel.view.words
        #expect(panel.view.rows.first?.line.stringValue == "no login stored")
        #expect(words.contains("No Claude Code login stored for uday, so a Claude Code session cannot start in dock."))
        #expect(words.contains("Store a Claude Code Login") && words.contains("API key or long-lived token"))
        #expect(words.contains("Or start it and sign in inside Claude Code: that login stays in dock only."))
        #expect(panel.view.primary?.title == "Start and Sign In There" && panel.view.primary?.isEnabled == true && panel.view.modes == nil)
        try Self.press(panel.view.storeLogin)
        #expect(panel.view.words.contains("Create an API key in the Claude Console.") && !panel.view.words.contains("API key or long-lived token"))
        for said in [words, panel.view.words] {
            #expect(!said.contains { $0.contains("agent login") || $0.contains("setup-token") || $0.contains("sail ") }, "a command shows: \(said)")
        }
        var started: [AgentStart] = []
        view.onStart = { start, _, _ in started.append(start) }
        try Self.press(try Self.returnKey(), in: window)
        #expect(started.map { [$0.wire.harness, $0.wire.mode] } == [["claude-code", "default"]] && started.allSatisfy(\.wire.inside))
        #expect(view.memory.last == Self.claude, "a start that signed in there is remembered as one")
    }

    /// A harness not installed: its row says so in the quiet ink; chosen, the server's sentence and
    /// its remedy's words, and Start is off.
    @Test func aHarnessNotInstalledIsSaidInPlace() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-dock-not-installed"])
        defer { window.orderOut(nil) }
        let panel = try await Self.open("dock", in: view)
        #expect(panel.view.rows.map(\.line.stringValue)[1] == "not installed here")
        try Self.press(try Self.down(), in: window)
        #expect(panel.view.words.contains("Codex CLI is not installed in harbor's workspaces; its agent installation lists Claude Code."))
        #expect(panel.view.words.contains("An admin adds codex to harbor's agent install list and builds its template again."))
        #expect(panel.view.primary?.title == "Start" && panel.view.primary?.isEnabled == false)
        var started = 0
        view.onStart = { _, _, _ in started += 1 }
        try Self.press(try Self.returnKey(), in: window)
        #expect(started == 0 && panel.isOpen, "Return started a harness not installed")
    }

    /// Someone else's workspace, by ⌥⌘T on its row: no list, the server's sentence and remedy, and
    /// Open a Shell, its row's Open. An owner gone: the sentence, no remedy (it names a command),
    /// and Close.
    @Test func whereNoAgentStartsForThePersonItSaysWhy() async throws {
        let (view, window, _) = try await Self.listed(["workspace-agents-quay"])
        defer { window.orderOut(nil) }
        var opened: [String] = []
        view.onOpen = { opened.append("\($0) \($1)") }
        let panel = try await Self.open("quay", in: view)
        #expect(panel.view.rows.isEmpty && panel.view.primary == nil)
        #expect(panel.view.words == ["New session in quay", "quay is ana's workspace, so an agent starts in it only as ana, with ana's login.", "Open a shell in it, or start the agent in a workspace of your own.", "Open a Shell"])
        try Self.press(panel.view.secondary)
        #expect(opened == ["harbor quay"] && !panel.isOpen)
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
    /// the row: never past the card, which there holds the section wider than itself (the drive's
    /// least frame: 27 pt).
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
        #expect(near(first.name.frame.maxX, first.bounds.width - 10) && first.line.frame.minX < first.name.frame.minX, "the row is not mirrored")
        let start = try #require(panel.view.primary)
        #expect(start.frame.midX < width / 2, "Start is not at the leading edge right to left")
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

    /// What the person's starts remember outlives the app: kept in the defaults and read back by
    /// the next shell into its Workspaces section.
    @Test func theStartsAreKeptBetweenLaunches() throws {
        let defaults = ReattachTests.defaults()
        #expect(ModeMemory.stored(in: defaults) == ModeMemory())
        let kept = ModeMemory(modes: ["claude-code": "plan"], last: AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan"))
        kept.store(in: defaults)
        #expect(ModeMemory.stored(in: defaults) == kept)
        let shell = try ShellApp(fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in SoftwarePasskey(file: FileManager.default.temporaryDirectory.appending(path: "sail-a1-\(UUID().uuidString).json")) }, defaults: defaults)
        #expect(shell.workspaces.memory == kept)
        defaults.set(Data("not a memory".utf8), forKey: ModeMemory.key)
        #expect(ModeMemory.stored(in: defaults) == ModeMemory(), "an unreadable memory is not read as nothing")
    }

    // MARK: Look, reach and calm

    /// Every scene the panel draws: each harness of dock chosen, no login, not installed, stopped,
    /// someone else's and an owner gone.
    static let scenes: [(golden: String, row: String, choices: Int)] = [
        ("workspace-agents-dock", "dock", 3), ("workspace-agents-dock-no-login", "dock", 1), ("workspace-agents-dock-not-installed", "dock", 2),
        ("workspace-agents-pier", "pier", 1), ("workspace-agents-quay", "quay", 0), ("workspace-agents-pier-owner-gone", "pier", 0),
    ]

    /// Each scene at `width`, each of its first `choices` rows chosen in turn, handed to `check`.
    static func eachScene(width: Double, look: Look = .light, rightToLeft: Bool = false, _ check: (String, NewSessionPanel, KeyWindow) throws -> Void) async throws {
        for scene in scenes {
            let (view, window, _) = try await listed([scene.golden], width: width, look: look)
            defer { window.orderOut(nil) }
            if rightToLeft {
                for each in [view.project] + WorkspacesViewTests.found(NSView.self, in: view.project) {
                    each.userInterfaceLayoutDirection = .rightToLeft
                }
            }
            let panel = try await open(scene.row, in: view)
            for at in 0 ..< max(scene.choices, 1) {
                if at > 0 { try press(try down(), in: window) }
                try check("\(scene.golden), row \(at + 1) chosen", panel, window)
            }
            if scene.golden == "workspace-agents-dock-no-login" {
                try Self.press(panel.view.storeLogin)
                try check("\(scene.golden), how to store one", panel, window)
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
    /// before any display pass: its rows, the mode's pop-up, Store a Login and its button.
    @Test func whatThePanelShowsTakesAClickAtOnce() async throws {
        var faults: [String] = []
        try await Self.eachScene(width: 1000) { scene, panel, _ in
            let controls: [NSView] = panel.view.rows + [panel.view.primary, panel.view.secondary, panel.view.modes, panel.view.storeLogin].compactMap { $0 }
            faults += controls.compactMap { WorkspacesReachTests.missesAClick($0).map { "\(scene): \($0)" } }
        }
        #expect(faults.isEmpty, "\(faults.count) faults: \(faults.joined(separator: "\n"))")
    }

    /// Calm, in both looks: the panel's one accent fill is its primary (Start, on or off), none
    /// where nothing starts; its accent lines the chosen row's bar and Store a Login's underline;
    /// no tint, no grey; and the page under it draws no more than before it opened.
    @Test(arguments: [Look.light, .dark])
    func thePanelIsCalm(_ look: Look) async throws {
        var faults: [String] = []
        try await Self.eachScene(width: 1000, look: look) { scene, panel, _ in
            let count = CalmTests.Count(marks(in: panel.view))
            let primary = panel.view.primary == nil ? 0 : 1, lines = (panel.view.rows.contains(where: \.chosen) ? 1 : 0) + (panel.view.storeLogin == nil ? 0 : 1)
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
