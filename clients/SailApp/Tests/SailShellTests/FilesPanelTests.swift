import AppKit
import Foundation
import SailAPI
import SailControls
import SailFiles
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailTerminalWire
import SailWorkbench
import Testing

/// A workspace's files panel: in the workspace's tab at its leading edge (⌃⌘S, one View item that
/// says what it will do in front), a plain tree the workspace's files fill and follow over the
/// runner's door, the one quiet line only when the tree is not the workspace's live truth, and
/// nothing redrawn by a tick that changes nothing.
@MainActor @Suite(.timeLimit(.minutes(1))) struct FilesPanelTests {
    static let root = FileTree.root.shown

    static func file(_ name: String) -> FileEntry {
        FileEntry(name: FileName(name), kind: .known(.file), target: .known(.none), size: 1, mtimeNanos: 0, mode: 0o644)
    }

    static func folder(_ name: String) -> FileEntry {
        FileEntry(name: FileName(name), kind: .known(.folder), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o755)
    }

    static func key(_ code: UInt16) throws -> NSEvent {
        let scalar: UInt16 = switch code {
        case 123: UInt16(NSLeftArrowFunctionKey)
        case 124: UInt16(NSRightArrowFunctionKey)
        case 125: UInt16(NSDownArrowFunctionKey)
        default: 0x20
        }
        let characters = String(Character(try #require(UnicodeScalar(scalar))))
        return try #require(NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: code == 49 ? [] : [.function, .numericPad], timestamp: 0, windowNumber: 0, context: nil,
            characters: characters, charactersIgnoringModifiers: characters, isARepeat: false, keyCode: code
        ))
    }

    /// A shell whose This Mac section holds one tab in workspace dock of harbor, its tickets the
    /// door's, signed in as uday: its terminal never asked for, its files panel's ready to be.
    /// Not shown through the rail, whose first showing restores this Mac's tabs in place of these.
    static func shell(_ door: FakeRunnerDoor, look: Look = .light, width: Double = 1180) throws -> (ShellApp, WorkbenchView) {
        let shell = try WorkbenchLookTests.shell(look)
        let bench = shell.thisMac
        let tickets = DoorTickets(door)
        bench.signedIn = { ("uday", tickets) }
        bench.isHidden = false
        let id = bench.model.newTab(session: "uday-dock-1", place: .workspace(project: "harbor", name: "dock"))
        bench.makeView(try #require(bench.model.panes[id]), start: .attach, points: 15)
        bench.setFrameSize(NSSize(width: width, height: 600))
        bench.apply()
        return (shell, bench)
    }

    static func panel(_ bench: WorkbenchView) -> FilesPanel? {
        bench.model.selectedTab.flatMap { bench.tabViews[$0.id]?.files }
    }

    static func command(_ shell: ShellApp) -> (title: String, enabled: Bool, reason: String?) {
        let item = NSMenuItem(title: "", action: #selector(ShellApp.toggleSidebar(_:)), keyEquivalent: "s")
        let enabled = shell.validateMenuItem(item)
        return (item.title, enabled, item.toolTip)
    }

    /// The one View item says what ⌃⌘S will do in front, and is off, with the true reason, where
    /// it does nothing.
    @Test func theViewItemSaysWhatItWillDoInEachPlace() throws {
        let door = try FakeRunnerDoor(person: "uday")
        let (shell, bench) = try Self.shell(door)
        shell.select(.terminals)
        #expect(Self.command(shell) == ("Show Files", true, nil))
        shell.toggleSidebar(nil)
        #expect(Self.command(shell) == ("Hide Files", true, nil), "the item does not say it will hide the files")
        shell.toggleSidebar(nil)
        #expect(Self.command(shell) == ("Show Files", true, nil))
        _ = bench.model.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        bench.apply()
        #expect(Self.command(shell) == ("Show Files", false, "Files are for workspaces; this tab is this Mac's."))
        shell.select(.workspaces)
        #expect(Self.command(shell) == ("Show Sidebar", false, "The sidebar lists projects once you sign in."), "signed out, the welcome has no sidebar")
        shell.flow.show(AccountPaneTests.someone)
        shell.select(.workspaces)
        let sidebar = Self.command(shell)
        #expect(sidebar.title == (shell.workspaces.panel.isHidden ? "Show Sidebar" : "Hide Sidebar") && sidebar.enabled == shell.workspaces.sidebarCommand.enabled)
        shell.select(.account)
        #expect(Self.command(shell) == ("Show Sidebar", false, "The account has no sidebar."))
    }

    /// The panel stands 236 pt at the tab's leading edge and the terminals take the rest; under
    /// the terminals' least room beside it, at the least window, it floats over them with its shadow.
    @Test(arguments: [480.0, 560, 720, 1180, 1600])
    func thePanelStandsAtTheLeadingEdgeAtEveryWidth(_ width: Double) throws {
        #expect(Double(ShellApp.leastFrame.width) == 480, "the least window moved: the widths here start at it")
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let door = try FakeRunnerDoor(person: "uday")
            let (shell, bench) = try Self.shell(door, width: width)
            bench.userInterfaceLayoutDirection = direction
            bench.toggleFiles(nil)
            let panel = try #require(Self.panel(bench), "no panel at \(width)")
            let tab = try #require(panel.superview)
            let box = tab.convert(panel.frame, to: bench)
            let rtl = direction == .rightToLeft
            #expect(near(Double(box.width), FilesPanel.width) && near(Double(box.height), 600), "\(width) \(direction): \(box)")
            #expect(near(Double(rtl ? box.maxX : box.minX), rtl ? width : 0), "\(width) \(direction): not at the leading edge: \(box)")
            let floating = width < FilesPanel.width + WorkbenchView.filesBeside
            #expect(panel.floating == floating && panel.shadowView.isHidden == !floating, "\(width): floating \(panel.floating)")
            if let pane = bench.focused {
                let terminal = pane.superview?.convert(pane.frame, to: bench) ?? .zero
                let room = floating ? width : width - FilesPanel.width
                #expect(near(Double(terminal.width), room), "\(width) \(direction): the terminals have \(terminal.width), not \(room)")
                if !floating {
                    #expect(near(Double(rtl ? terminal.minX : terminal.maxX), rtl ? 0 : width) && !terminal.intersects(box.insetBy(dx: 1, dy: 1)), "\(width): the terminals under the panel")
                }
            }
            bench.toggleFiles(nil)
            _ = shell
            #expect(panel.isHidden && tab.bounds.origin == .zero, "\(width): put away, the panel left the terminals' room moved")
        }
    }

    /// The tree is the workspace's: listed over the door, folders first, and when an entry appears in
    /// a shown folder it is listed again and the new entry is marked new.
    @Test func theTreeIsTheWorkspacesAndFollowsIt() async throws {
        let door = try FakeRunnerDoor(person: "uday")
        door.setFolder(Self.root, [Self.file("README.md"), Self.folder("src")])
        let (shell, bench) = try Self.shell(door)
        bench.toggleFiles(nil)
        let panel = try #require(Self.panel(bench))
        #expect(await RunnerTabTests.until(10) { panel.list.numberOfRows == 2 }, "rows: \(panel.list.numberOfRows), line: \(panel.shownLine?.words ?? "none")")
        #expect(panel.tree.rows.map(\.name) == ["src", "README.md"])
        let src = Self.root + "/src"
        panel.list.choose(src)
        panel.toggle(FileName(src))
        #expect(await RunnerTabTests.until(10) { panel.list.numberOfRows == 3 }, "src's refusal never came: \(panel.tree.rows.map(\.name))")
        #expect(panel.list.chosen == src, "opening the chosen folder lost the choice: \(panel.list.chosen ?? "nothing")")
        panel.look = .dark
        #expect(panel.list.chosen == src, "a change of look lost the choice")
        panel.toggle(FileName(src))
        #expect(panel.list.chosen == src && panel.list.numberOfRows == 2, "shutting the chosen folder lost the choice")
        door.setFolder(Self.root, [Self.file("README.md"), Self.folder("src"), Self.file("tide.ts")])
        #expect(await RunnerTabTests.until(10) { panel.list.numberOfRows == 3 }, "the new entry never came")
        let cell = try #require(panel.list.view(atColumn: 0, row: 2, makeIfNecessary: true).flatMap(Self.treeCell))
        #expect(cell.row.name == "tide.ts" && cell.new, "the new entry is not marked new")
        #expect(panel.shownLine == nil, "a live tree says \(panel.shownLine?.words ?? "")")
        bench.toggleFiles(nil)
        _ = shell
    }

    /// The tree keeps the keys it holds: a change in the tab's terminals (a title, an attach), which
    /// draws the workbench again, gives them back to the terminal only when the panel goes.
    @Test func theTreeKeepsItsKeysWhenTheTerminalsChange() throws {
        let door = try FakeRunnerDoor(person: "uday")
        let (shell, bench) = try Self.shell(door)
        bench.toggleFiles(nil)
        let panel = try #require(Self.panel(bench))
        #expect(shell.window.firstResponder === panel.list, "⌃⌘S did not give the tree the keys")
        bench.apply()
        #expect(shell.window.firstResponder === panel.list, "drawing the workbench again took the keys from the tree")
        bench.toggleFiles(nil)
        #expect(shell.window.firstResponder === bench.focused?.terminal, "put away, the panel left the keys nowhere")
    }

    /// The connection and its watch end whenever the files are not on screen (the panel put away,
    /// another section shown, the window closed or in the Dock, the tab closed) and come back when
    /// they are.
    @Test func theConnectionLastsOnlyWhileTheFilesAreOnScreen() async throws {
        let door = try FakeRunnerDoor(person: "uday")
        door.setFolder(Self.root, [Self.file("README.md")])
        let (shell, bench) = try Self.shell(door)
        func connected(_ count: Int, _ when: String) async {
            #expect(await RunnerTabTests.until(10) { door.filesConnections == count }, "\(when): \(door.filesConnections) files connections, not \(count)")
        }
        bench.toggleFiles(nil)
        await connected(1, "shown")
        bench.toggleFiles(nil)
        await connected(0, "the panel put away")
        bench.toggleFiles(nil)
        await connected(1, "shown again")
        bench.isHidden = true
        bench.connectFiles()
        await connected(0, "another section shown")
        bench.isHidden = false
        bench.connectFiles()
        await connected(1, "this Mac's section back")
        _ = shell.windowShouldClose(shell.window)
        await connected(0, "the window closed")
        _ = shell.applicationShouldHandleReopen(NSApplication.shared, hasVisibleWindows: false)
        await connected(1, "the window open again")
        shell.windowDidMiniaturize(Notification(name: NSWindow.didMiniaturizeNotification))
        await connected(0, "the window in the Dock")
        shell.windowDidDeminiaturize(Notification(name: NSWindow.didDeminiaturizeNotification))
        await connected(1, "the window back from the Dock")
        shell.applicationDidHide(Notification(name: NSApplication.didHideNotification))
        await connected(0, "the app hidden")
        shell.applicationDidUnhide(Notification(name: NSApplication.didUnhideNotification))
        await connected(1, "the app shown again")
        let tab = try #require(bench.model.selectedTab)
        _ = bench.model.closeTab(tab.id)
        bench.apply()
        await connected(0, "the tab closed")
    }

    /// VoiceOver hears a tree: the list an outline, each row an outline row at its depth, a folder
    /// open or shut.
    @Test func voiceOverHearsATree() throws {
        let panel = try Self.played([Self.folder("src"), Self.file("README.md")])
        panel.toggle(FileName(Self.root + "/src"))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.file("main.ts")], more: false, truncated: false))))
        panel.layoutSubtreeIfNeeded()
        #expect(panel.list.accessibilityRole() == .outline)
        let rows = try (0 ..< 3).map { try #require(panel.list.rowView(atRow: $0, makeIfNecessary: true)) }
        #expect(rows.map { $0.accessibilitySubrole() } == [.outlineRow, .outlineRow, .outlineRow])
        #expect(rows.map { $0.accessibilityDisclosureLevel() } == [0, 1, 0], "\(rows.map { $0.accessibilityDisclosureLevel() })")
        #expect(rows[0].isAccessibilityExpanded() && !rows[2].isAccessibilityExpanded() && rows[0].accessibilityLabel() == "src, folder, expanded")
        panel.toggle(FileName(Self.root + "/src"))
        panel.layoutSubtreeIfNeeded()
        let shut = try #require(panel.list.rowView(atRow: 0, makeIfNecessary: true))
        #expect(!shut.isAccessibilityExpanded() && shut.accessibilityLabel() == "src, folder, collapsed",
                "after an open and a shut, the row view holds a stale item: \(shut.accessibilityLabel() ?? "nothing")")
    }

    /// The line's action is the library's button in every state that has one: no system button, no
    /// tint, at most the one accent fill (Start), no grey; framed and taking a click in the turn it
    /// shows; nothing out of reach in the panel at its width.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func theLinesButtonIsTheLibrarysAndCalm(_ name: String, _ look: Look) throws {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: FilesPanel.width, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        for (state, phase) in [("unreachable", FilesSession.Phase.unreachable("refused")), ("stopped", .stopped("Workspace dock of harbor was stopped by ana.")),
                               ("limited", .limited("You have 16 files connections open; close one to open another."))] {
            let panel = try Self.played([Self.folder("src"), Self.file("a-very-long-name-that-must-be-cut-in-the-panel-at-its-width.txt")], look: look)
            window.contentView = panel
            window.orderFront(nil)
            panel.heard(.phase(phase))
            panel.layoutSubtreeIfNeeded()
            let system = WorkspacesViewTests.found(NSButton.self, in: panel).filter { !($0 is SailButton) && !($0 is LinkButton) && !$0.isHiddenOrHasHiddenAncestor }
            #expect(system.isEmpty && !panel.line.button.isHidden, "\(name) \(state): \(system.map(\.title))")
            let count = CalmTests.Count(marks(in: panel))
            #expect(count.tint == 0 && count.fill <= 1 && count.neutral == 0, "\(name) \(state): \(count)")
            #expect((state == "stopped") == (count.fill == 1), "\(name) \(state): Start is the one accent fill, nothing else is: \(count)")
            #expect(WorkspacesReachTests.missesAClick(panel.line.button) == nil, "\(name) \(state): \(WorkspacesReachTests.missesAClick(panel.line.button) ?? "")")
            let out = WorkspacesReachTests.outOfReach(in: panel)
            #expect(out.isEmpty, "\(name) \(state): \(out)")
        }
    }

    /// Two names that are not UTF-8 and read alike are two rows: one chosen and opened, the other
    /// kept apart, each by its bytes.
    @Test func lookAlikeNamesAreTwoRows() throws {
        func folder(_ bytes: [UInt8]) -> FileEntry {
            FileEntry(name: FileName(bytes), kind: .known(.folder), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o755)
        }
        let panel = try Self.played([folder([0x61, 0xFF]), folder([0x61, 0xFE])])
        let paths = panel.tree.rows.map(\.path)
        try #require(panel.list.numberOfRows == 2 && paths.count == 2, "the look-alikes are not two rows: \(paths)")
        panel.list.choose(paths[1].key)
        #expect(panel.chosenPath == paths[1] && panel.chosenPath != paths[0], "the choice went to the look-alike")
        panel.list.keyDown(with: try Self.key(124))
        #expect(panel.tree.expanded.contains(paths[1]) && !panel.tree.expanded.contains(paths[0]), "→ opened the look-alike")
    }

    static func treeCell(_ view: NSView) -> TreeRowCell? {
        (view as? TreeRowCell) ?? view.subviews.lazy.compactMap(treeCell).first
    }

    /// The panel on its own, its connection played by hand: the tree admitted at time 0 and the root
    /// listed as `entries`.
    static func played(_ entries: [FileEntry], look: Look = .light, now: @escaping @MainActor () -> Double = { 0 }) throws -> FilesPanel {
        let mono = try LabelFont(directory: LookTests.fonts)
        let panel = FilesPanel(project: "harbor", workspace: "dock", tickets: nil, look: look, mono: mono, titleFont: try TitleFont(directory: LookTests.fonts), clock: now)
        panel.frame = NSRect(x: 0, y: 0, width: FilesPanel.width, height: 600)
        panel.heard(.phase(.connected))
        panel.heard(.admitted)
        panel.heard(.frame(.filesListing(FileListing(id: 1, version: "v1", entries: entries, more: false, truncated: false))))
        panel.layoutSubtreeIfNeeded()
        return panel
    }

    static func stats(_ frames: [Frame]) -> [Int32] {
        frames.compactMap { if case let .filesStat(id, _) = $0 { id } else { nil } }
    }

    /// M1, R3-1: a ⌘-click's lookup lives only while the connection is being asked for or is up: a
    /// phase that ends or parks the connection, heard before the click or after it, ends the lookup
    /// in the panel line's own words for the files connection, never the session's internal reason
    /// and never blank, with "Click again once it is back." while it reconnects; it asks nothing
    /// before admission, and nothing when the connection is had again (the person clicks again).
    /// The phases are the values `FilesSession` makes.
    @Test(arguments: [
        ("stopped", FilesSession.Phase.stopped("Workspace dock of harbor was stopped by ana."), "Workspace dock of harbor was stopped by ana."),
        ("stopped unrecorded", .stopped(""), "dock is stopped."),
        ("refused", .refused(["Workspace 'dock' belongs to 'uday', not you.", "Ask uday to enter it."]), "Workspace 'dock' belongs to 'uday', not you. Ask uday to enter it."),
        ("unreachable", .unreachable(TerminalError.timedOut("the admission").description), "Can't reach dock's files."),
        ("gone", .gone("Workspace dock of harbor no longer exists."), "Workspace dock of harbor no longer exists."),
        ("limited", .limited("You have 16 files connections open; close one to open another."), "You have 16 files connections open; close one to open another."),
        ("reconnecting", .reconnecting(TerminalError.closed.description), "Reconnecting to dock's files. Click again once it is back."),
    ] as [(String, FilesSession.Phase, String)])
    func aLookupEndsWithTheConnectionInItsOwnWords(_ name: String, _ phase: FilesSession.Phase, _ words: String) throws {
        for parkedFirst in [false, true] {
            let panel = FilesPanel(project: "harbor", workspace: "dock", tickets: nil, look: .light, mono: try LabelFont(directory: LookTests.fonts), titleFont: try TitleFont(directory: LookTests.fonts))
            var sent: [Frame] = [], said: [TerminalPath.Lookup.Next] = []
            panel.onSent = { sent += $0 }
            if parkedFirst { panel.heard(.phase(phase)) }
            panel.lookUp("src/a.ts", cwd: nil) { said.append($0) }
            #expect(sent.isEmpty, "\(name): asked before the connection was admitted")
            if !parkedFirst {
                #expect(panel.lookingUp)
                panel.heard(.phase(phase))
            }
            #expect(said == [.said(words)] && !panel.lookingUp, "\(name), parked first \(parkedFirst): \(said)")
            panel.heard(.phase(.connected))
            panel.heard(.admitted)
            #expect(Self.stats(sent).isEmpty && said.count == 1, "\(name), parked first \(parkedFirst): the ended lookup was asked when the connection came back")
        }
    }

    /// M1: a lookup whose connection drops mid-way is said in the drop's words and never asked
    /// again: the connection had again asks nothing, and a late answer to its old ask opens
    /// nothing; a close ends a lookup unsaid, and the next click waits for the next admission.
    @Test func aLookupDroppedMidWayIsSaidAndNeverAskedAgain() throws {
        let panel = try Self.played([Self.folder("src")])
        var sent: [Frame] = [], said: [TerminalPath.Lookup.Next] = []
        panel.onSent = { sent += $0 }
        panel.lookUp("src/a.ts", cwd: nil) { said.append($0) }
        let asked = try #require(Self.stats(sent).first, "the lookup asked nothing")
        panel.heard(.phase(.reconnecting(TerminalError.closed.description)))
        #expect(said == [.said("Reconnecting to dock's files. Click again once it is back.")] && !panel.lookingUp)
        sent = []
        panel.heard(.phase(.connected))
        panel.heard(.admitted)
        panel.heard(.frame(.filesEntry(id: asked, entry: Self.file("a.ts"), version: "v1")))
        #expect(Self.stats(sent).isEmpty && said.count == 1, "the dropped lookup was asked again, or its late answer opened a file: \(said)")
        panel.lookUp("src/b.ts", cwd: nil) { said.append($0) }
        #expect(panel.lookingUp)
        panel.close()
        #expect(!panel.lookingUp && said.count == 1, "a close left the lookup, or said something")
        sent = []
        panel.lookUp("src/c.ts", cwd: nil) { said.append($0) }
        #expect(sent.isEmpty && panel.lookingUp, "a click after a close was asked before the connection had again was admitted")
    }

    /// → opens a folder and ← shuts it, Space too; ↓ moves among rows as any list's.
    @Test func theArrowsOpenAndShutAFolder() throws {
        let panel = try Self.played([Self.folder("src"), Self.file("README.md")])
        panel.list.choose(Self.root + "/src")
        panel.list.keyDown(with: try Self.key(124))
        #expect(panel.tree.expanded.contains(FileName(Self.root + "/src")), "→ did not open the folder")
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.file("main.ts")], more: false, truncated: false))))
        #expect(panel.list.numberOfRows == 3)
        panel.list.keyDown(with: try Self.key(124))
        #expect(panel.list.chosen == Self.root + "/src/main.ts", "→ on an open folder did not go into it")
        panel.list.keyDown(with: try Self.key(123))
        #expect(panel.list.chosen == Self.root + "/src", "← did not go to the folder holding the row")
        panel.list.keyDown(with: try Self.key(123))
        #expect(!panel.tree.expanded.contains(FileName(Self.root + "/src")) && panel.list.numberOfRows == 2, "← did not shut the folder")
        panel.list.keyDown(with: try Self.key(49))
        #expect(panel.tree.expanded.contains(FileName(Self.root + "/src")), "Space did not open the folder")
    }

    /// The choice is the panel's, by path, through the real list: rows made again by an expand or a
    /// listing keep it; an entry gone moves it to its nearest folder still shown, and with none, to
    /// the row now in its place.
    @Test func theChoiceIsHeldByPathThroughEveryRebuild() throws {
        let panel = try Self.played([Self.folder("src"), Self.file("a.txt"), Self.file("b.txt")])
        let src = Self.root + "/src", main = src + "/main.ts"
        panel.list.choose(src)
        panel.toggle(FileName(src))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.file("main.ts"), Self.file("tide.ts")], more: false, truncated: false))))
        #expect(panel.list.chosen == src && panel.chosenPath == FileName(src), "the expand's rows lost the choice")
        panel.list.choose(main)
        #expect(panel.chosenPath == FileName(main), "the person's choice was not taken")
        panel.heard(.frame(.filesChanged(paths: [FileName(src)])))
        panel.heard(.frame(.filesListing(FileListing(id: 3, version: "s2", entries: [Self.file("tide.ts")], more: false, truncated: false))))
        #expect(panel.list.chosen == src, "main.ts gone, the choice did not move to its folder: \(panel.list.chosen ?? "nothing")")
        panel.list.choose(Self.root + "/a.txt")
        panel.heard(.frame(.filesChanged(paths: [FileName(Self.root)])))
        panel.heard(.frame(.filesListing(FileListing(id: 4, version: "v2", entries: [Self.folder("src"), Self.file("b.txt")], more: false, truncated: false))))
        #expect(panel.list.chosen == Self.root + "/b.txt", "a.txt gone with no folder shown, the choice did not move to the row in its place: \(panel.list.chosen ?? "nothing")")
    }

    /// A file pressed stays chosen when a listing lands while the button is down: the tree made
    /// again during the press keeps b.txt, which the press leaves chosen, not a.txt put back.
    @Test func aFilePressedStaysChosenWhenAListingLandsDuringThePress() throws {
        let panel = try Self.played([Self.file("a.txt"), Self.file("b.txt"), Self.file("c.txt")])
        let a = Self.root + "/a.txt", b = Self.root + "/b.txt"
        panel.list.choose(a)
        panel.list.pressed(b)
        panel.heard(.frame(.filesChanged(paths: [FileName(Self.root)])))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "v2", entries: [Self.file("a.txt"), Self.file("b.txt"), Self.file("c.txt"), Self.file("d.txt")], more: false, truncated: false))))
        panel.list.letGo()
        #expect(panel.list.chosen == b && panel.chosenPath == FileName(b), "the press left \(panel.list.chosen ?? "nothing") chosen, the panel \(panel.chosenPath.map { "\($0)" } ?? "nothing")")
    }

    /// A file dropped from a choice of several when its row is made again (its unsaved dot coming),
    /// then ⌘-pressed back into the choice, is told: the panel's chosen file is it again.
    @Test func aFileMadeAgainOutOfAChoiceOfSeveralIsToldWhenPressedBack() throws {
        let panel = try Self.played([Self.file("a.txt"), Self.file("b.txt"), Self.file("c.txt")])
        let a = Self.root + "/a.txt", b = Self.root + "/b.txt"
        panel.list.choose(a)
        panel.list.pressed(b, adding: true)
        panel.list.letGo()
        try #require(panel.list.chosenIDs == [a, b] && panel.chosenPath == FileName(b), "chosen \(panel.list.chosenIDs)")
        panel.unsaved = FileName(a)
        try #require(panel.list.chosenIDs == [b], "a.txt's row made again stayed chosen: \(panel.list.chosenIDs)")
        panel.list.pressed(a, adding: true)
        panel.list.letGo()
        #expect(panel.list.chosenIDs == [a, b] && panel.chosenPath == FileName(a), "pressed back, the panel holds \(panel.chosenPath.map { "\($0)" } ?? "nothing")")
    }

    /// Calm: a tick, or a beat with nothing changed, makes no row and draws no line.
    @Test func aTickThatChangesNothingTouchesNoView() throws {
        let panel = try Self.played((0 ..< 40).map { Self.file("berth-\($0).json") })
        let sets = panel.rowSets, line = panel.shownLine
        #expect(sets == 1)
        for _ in 0 ..< 10 {
            panel.heard(.frame(.filesTick))
            panel.refresh()
        }
        #expect(panel.rowSets == sets && panel.shownLine == line, "the rows were set \(panel.rowSets - sets) times for ticks")
    }

    /// Each state is one quiet line at the top, in the design's words, gone when it no longer applies.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachStateIsOneQuietLine(_ name: String, _ look: Look) throws {
        var now = 0.0
        let panel = try Self.played([Self.file("a.txt")], look: look) { now }
        #expect(panel.line.isHidden, "\(name): a live tree has a line")
        now = 2.5
        panel.refresh()
        #expect(panel.shownLine?.words.hasPrefix("Not live since ") == true && panel.scroll.alphaValue < 1, "\(name): \(panel.shownLine?.words ?? "no line")")
        panel.heard(.frame(.filesTick))
        #expect(panel.line.isHidden && panel.scroll.alphaValue == 1, "\(name): the tick did not make it live again")
        panel.heard(.phase(.unreachable("refused")))
        #expect(panel.shownLine?.words == "Can't reach dock's files." && panel.line.button.title == "Try Again" && !panel.line.button.isHidden)
        panel.heard(.phase(.stopped("Workspace dock of harbor was stopped by ana.")))
        #expect(panel.shownLine?.words == "Workspace dock of harbor was stopped by ana." && panel.line.button.title == "Start", "\(name): not the stop's own record with Start")
        panel.layoutSubtreeIfNeeded()
        #expect(panel.line.frame.minY >= FilesLineView.top && panel.scroll.frame.minY >= panel.line.frame.maxY, "\(name): the tree is under the line")
        let empty = try Self.played([], look: look)
        #expect(empty.shownLine?.words == "~/workspace is empty.", "\(name): an empty tree in no window names ⌥⌘N")
    }

    /// An empty workspace's line names ⌥⌘N only while the tree holds the keys (``KeyHolder``), the
    /// one place New File reaches: not while another view holds them, nor in a window that is not
    /// key; said again each time they come and go.
    @Test func theEmptyLineNamesNewFileOnlyWhileTheTreeHoldsTheKeys() throws {
        let panel = try Self.played([])
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: FilesPanel.width + 200, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        let other = NSTextField(frame: NSRect(x: FilesPanel.width + 20, y: 20, width: 160, height: 22))
        window.contentView?.addSubview(panel)
        window.contentView?.addSubview(other)
        let named = "~/workspace is empty. ⌥⌘N makes a file.", plain = "~/workspace is empty."
        let newFile = NSMenuItem(title: "New File", action: #selector(FilesPanel.newFile(_:)), keyEquivalent: "n")
        #expect(panel.shownLine?.words == plain, "⌥⌘N is named before the tree holds the keys")
        try #require(window.makeFirstResponder(panel.list))
        #expect(panel.shownLine?.words == named && panel.validateMenuItem(newFile), "with the tree holding the keys the line says \(panel.shownLine?.words ?? "nothing")")
        try #require(window.makeFirstResponder(other))
        #expect(panel.shownLine?.words == plain && !panel.validateMenuItem(newFile), "with a field holding the keys the line says \(panel.shownLine?.words ?? "nothing")")
        try #require(window.makeFirstResponder(panel.list))
        window.key = false
        #expect(panel.shownLine?.words == plain, "in a window that is not key the line says \(panel.shownLine?.words ?? "nothing")")
        window.key = true
        #expect(panel.shownLine?.words == named, "the window key again, the line says \(panel.shownLine?.words ?? "nothing")")
    }

    /// The rows as the third pass draws them: 24 pt, 12 pt further in each level, the disclosure on
    /// folders only, semibold while chosen, with the bar and no fill.
    /// A press on a row's name chooses it; only a press in the disclosure's own 12 pt slot opens or
    /// shuts its folder.
    @Test func onlyTheDisclosuresSlotOpensAFolder() throws {
        let panel = try Self.played([Self.folder("src")])
        let cell = try #require(panel.list.view(atColumn: 0, row: 0, makeIfNecessary: true).flatMap(Self.treeCell))
        cell.frame = NSRect(x: 0, y: 0, width: 200, height: 24)
        #expect(cell.opens(at: NSPoint(x: 6, y: 12)) && cell.opens(at: NSPoint(x: 6, y: 1)), "the slot does not open the folder")
        #expect(!cell.opens(at: NSPoint(x: Double(TreeRowCell.slot) + 1, y: 12)) && !cell.opens(at: NSPoint(x: 40, y: 12)), "a press past the slot opens the folder")
    }

    /// M6: a refusal under its folder is as tall as its words wrapped, and its words fill it: none
    /// is cut, in either direction.
    @Test func aRefusalInPlaceIsAsTallAsItsWords() throws {
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let panel = try Self.played([Self.folder("build"), Self.file("a.txt")])
            panel.userInterfaceLayoutDirection = direction
            panel.toggle(FileName(Self.root + "/build"))
            panel.heard(.frame(.filesRefused(id: 2, kind: .known(.notPermitted), message: "The workspace's user may not open build.")))
            panel.layoutSubtreeIfNeeded()
            let note = try #require(panel.list.view(atColumn: 0, row: 1, makeIfNecessary: true).flatMap(Self.treeCell))
            let row = Double(panel.list.rect(ofRow: 1).height)
            #expect(note.row.kind == .note && row > RowStyle.tree.height, "\(direction): the refusal's row is \(row) pt, one line's")
            #expect(near(Double(note.intrinsicContentSize.height), row), "\(direction): the words are \(note.intrinsicContentSize.height) pt in a \(row) pt row")
            #expect(note.userInterfaceLayoutDirection == direction, "\(direction): the row is not laid out as its panel")
        }
    }

    @Test func theRowsAreTheMockups() throws {
        let panel = try Self.played([Self.folder("src"), Self.file("README.md")])
        panel.toggle(FileName(Self.root + "/src"))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.file("main.ts")], more: false, truncated: false))))
        panel.layoutSubtreeIfNeeded()
        #expect((0 ..< panel.list.numberOfRows).allSatisfy { near(Double(panel.list.rect(ofRow: $0).height), 24) })
        let cells = try (0 ..< 3).map { try #require(panel.list.view(atColumn: 0, row: $0, makeIfNecessary: true).flatMap(Self.treeCell)) }
        #expect(cells.map(\.row.name) == ["src", "main.ts", "README.md"])
        #expect(near(Double(cells[1].disclosure.minX) - Double(cells[0].disclosure.minX), RowStyle.indent), "a level is not 12 pt further in")
        panel.list.choose(Self.root + "/README.md")
        let chosen = try #require(panel.list.row(Self.root + "/README.md"))
        #expect(chosen.paint.bar, "the chosen row has no bar")
        #expect(cells[2].strong && !cells[0].strong && !cells[1].strong, "only the chosen name is semibold")
    }
}
