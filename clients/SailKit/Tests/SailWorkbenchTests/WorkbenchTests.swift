#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation
import SailAPI
import SailKitTestSupport
import SailTerminalWire
import SailWorkbench
import Testing

/// Scenarios 1 to 10 of the spec on the workbench value; scenario 11 and W1 are `WholenessTests`.
@Suite struct WorkbenchTests {
    static let room = CGSize(width: 1001, height: 601)
    static let least = CGSize(width: 110, height: 70)
    static let home = Place.thisMac(folder: "/home/u")

    static func bench(tabs: Int = 1) -> Workbench {
        var bench = Workbench()
        for index in 1 ... tabs { bench.newTab(session: "mac-t\(index)", place: home) }
        return bench
    }

    static func split(_ bench: inout Workbench, _ axis: Axis, _ session: String, size: CGSize = room) throws -> PaneID {
        let pane = bench.split(axis, session: session, place: home, in: size, minimum: least, scale: 2)
        return try #require(pane)
    }

    static func frame(_ bench: Workbench, _ pane: PaneID, size: CGSize = room) -> CGRect? {
        bench.frames(in: size, scale: 2).panes[pane]
    }

    @Test func scenario1SplitRightThenDownInTheRightOne() throws {
        var bench = Self.bench()
        let left = try #require(bench.focusedPane?.id)
        let right = try Self.split(&bench, .sideBySide, "mac-right")
        let bottom = try Self.split(&bench, .stacked, "mac-bottom")
        #expect(Self.frame(bench, left) == CGRect(x: 0, y: 0, width: 500, height: 601))
        #expect(Self.frame(bench, right) == CGRect(x: 501, y: 0, width: 500, height: 300))
        #expect(Self.frame(bench, bottom) == CGRect(x: 501, y: 301, width: 500, height: 300))
        #expect(bench.focusedPane?.id == bottom)
        #expect(bench.frames(in: Self.room, scale: 2).dividers.map(\.line) == [
            CGRect(x: 500, y: 0, width: 1, height: 601), CGRect(x: 501, y: 300, width: 500, height: 1),
        ])
    }

    @Test func scenario2TheSiblingTakesTheRoomAndFocusGoesToTheMostRecentlyFocused() throws {
        var bench = Self.bench()
        let left = try #require(bench.focusedPane?.id)
        let top = try Self.split(&bench, .sideBySide, "mac-right")
        let bottom = try Self.split(&bench, .stacked, "mac-bottom")
        bench.focus(top)
        let answer1 = bench.close(top)
        #expect(answer1?.session == "mac-right")
        #expect(Self.frame(bench, bottom) == CGRect(x: 501, y: 0, width: 500, height: 601))
        #expect(Self.frame(bench, left) == CGRect(x: 0, y: 0, width: 500, height: 601))
        #expect(bench.focusedPane?.id == bottom)
        #expect(bench.panes[top] == nil)
    }

    @Test func scenario3ClosingASecondTabsLastPaneSelectsTheFirst() throws {
        var bench = Self.bench(tabs: 2)
        let first = bench.tabs[0].id
        let last = try #require(bench.focusedPane?.id)
        bench.close(last)
        #expect(bench.tabs.map(\.id) == [first])
        #expect(bench.selected == first)
        bench.close(bench.tabs[0].focused)
        #expect(bench.tabs.isEmpty && bench.selected == nil && bench.panes.isEmpty)
    }

    /// A, C on the left (top, bottom), B, D on the right.
    static func grid() throws -> (Workbench, a: PaneID, b: PaneID, c: PaneID, d: PaneID) {
        var bench = bench()
        let a = try #require(bench.focusedPane?.id)
        let b = try split(&bench, .sideBySide, "mac-b")
        bench.focus(a)
        let c = try split(&bench, .stacked, "mac-c")
        bench.focus(b)
        let d = try split(&bench, .stacked, "mac-d")
        return (bench, a, b, c, d)
    }

    @Test func scenario4EachDirectionFromEachPaneOfAGridAndNextWraps() throws {
        let (start, a, b, c, d) = try Self.grid()
        let expected: [PaneID: [Direction: PaneID]] = [
            a: [.right: b, .down: c], b: [.left: a, .down: d], c: [.up: a, .right: d], d: [.up: b, .left: c],
        ]
        for (from, moves) in expected {
            for direction in Direction.allCases {
                var bench = start
                bench.focus(from)
                let moved = bench.moveFocus(direction, in: Self.room, scale: 2)
                #expect(moved == (moves[direction] != nil), "\(from) \(direction)")
                #expect(bench.focusedPane?.id == moves[direction] ?? from, "\(from) \(direction)")
            }
        }
        var bench = start
        #expect(bench.tabs[0].layout.leaves == [a, c, b, d])
        bench.focus(d)
        bench.focusNeighbour(next: true)
        #expect(bench.focusedPane?.id == a)
        bench.focusNeighbour(next: false)
        #expect(bench.focusedPane?.id == d)
    }

    @Test func aTieGoesToTheMostRecentlyFocused() throws {
        var bench = Self.bench()
        let left = try #require(bench.focusedPane?.id)
        let top = try Self.split(&bench, .sideBySide, "mac-top")
        let bottom = try Self.split(&bench, .stacked, "mac-bottom")
        for recent in [top, bottom, top] {
            bench.focus(recent)
            bench.focus(left)
            bench.moveFocus(.right, in: Self.room, scale: 2)
            #expect(bench.focusedPane?.id == recent)
        }
    }

    @Test func scenario5EqualizeGivesSharesByWeight() throws {
        var row = Self.bench()
        let a = try #require(row.focusedPane?.id)
        let b = try Self.split(&row, .sideBySide, "mac-b")
        let c = try Self.split(&row, .sideBySide, "mac-c")
        let size = CGSize(width: 1502, height: 601), small = CGSize(width: 10, height: 10)
        let answer2 = row.drag([], to: 0.8 * 1501, in: size, minimum: small, scale: 2)
        #expect(answer2)
        let inner = try #require(row.frames(in: size, scale: 2).dividers.first { $0.path == [.second] }?.split)
        let answer3 = row.drag([.second], to: inner.minX + 0.3 * (inner.width - 1), in: size, minimum: small, scale: 2)
        #expect(answer3)
        #expect([a, b, c].compactMap { Self.frame(row, $0, size: size)?.width } == [1201, 89.5, 209.5])
        row.equalize(in: size, minimum: small, scale: 2)
        let widths = [a, b, c].compactMap { Self.frame(row, $0, size: size)?.width }
        #expect(widths.count == 3 && (widths.max() ?? 0) - (widths.min() ?? 0) <= 1, "\(widths)")

        var stack = Self.bench()
        let left = try #require(stack.focusedPane?.id)
        let top = try Self.split(&stack, .sideBySide, "mac-top")
        _ = try Self.split(&stack, .stacked, "mac-bottom")
        let answer4 = stack.drag([], to: 800, in: Self.room, minimum: Self.least, scale: 2)
        #expect(answer4)
        stack.equalize(in: Self.room, minimum: Self.least, scale: 2)
        #expect(Self.frame(stack, left)?.width == 500 && Self.frame(stack, top)?.width == 500)
    }

    @Test func scenario6ADividerStopsAtTheMinimumAndASplitTooSmallIsRefused() throws {
        var bench = Self.bench()
        let left = try #require(bench.focusedPane?.id)
        let right = try Self.split(&bench, .sideBySide, "mac-right")
        let answer5 = bench.drag([], to: 10, in: Self.room, minimum: Self.least, scale: 2)
        #expect(answer5)
        #expect(Self.frame(bench, left)?.width == 110)
        let answer6 = bench.drag([], to: 990, in: Self.room, minimum: Self.least, scale: 2)
        #expect(answer6)
        #expect(Self.frame(bench, right)?.width == 110)
        for _ in 0 ..< 3 { bench.moveDivider(.right, by: 10, in: Self.room, minimum: Self.least, scale: 2) }
        #expect(Self.frame(bench, right)?.width == 110)

        var narrow = Self.bench()
        let before = narrow
        let fifteenColumns = CGSize(width: 155, height: 601)
        let answer7 = narrow.split(.sideBySide, session: "mac-x", place: Self.home, in: fifteenColumns, minimum: Self.least, scale: 2)
        #expect(answer7 == nil)
        #expect(narrow == before)
        let answer8 = narrow.split(.stacked, session: "mac-x", place: Self.home, in: fifteenColumns, minimum: Self.least, scale: 2)
        #expect(answer8 != nil)
    }

    @Test func scenario7AZoomEndsWhenToggledOrOnASplitACloseOrADirectionalMove() throws {
        let (start, a, b, _, _) = try Self.grid()
        var bench = start
        bench.focus(a)
        let unzoomed = bench.frames(in: Self.room, scale: 2)
        bench.toggleZoom()
        #expect(bench.selectedTab?.zoomed == a)
        #expect(bench.frames(in: Self.room, scale: 2) == Frames(panes: [a: CGRect(origin: .zero, size: Self.room)], dividers: []))
        bench.toggleZoom()
        #expect(bench.selectedTab?.zoomed == nil)
        #expect(bench.frames(in: Self.room, scale: 2) == unzoomed)

        bench.toggleZoom()
        let answer9 = bench.moveFocus(.right, in: Self.room, scale: 2)
        #expect(answer9)
        #expect(bench.selectedTab?.zoomed == nil && bench.focusedPane?.id == b)
        #expect(bench.frames(in: Self.room, scale: 2) == unzoomed)

        bench.toggleZoom()
        _ = try Self.split(&bench, .sideBySide, "mac-e")
        #expect(bench.selectedTab?.zoomed == nil)

        bench.toggleZoom()
        let focused = try #require(bench.focusedPane?.id)
        bench.close(focused)
        #expect(bench.selectedTab?.zoomed == nil)
        #expect(bench.frames(in: Self.room, scale: 2) == unzoomed)

        var single = Self.bench()
        single.toggleZoom()
        #expect(single.selectedTab?.zoomed == nil)
    }

    @Test func scenario8ANameStandsOverTheProgramsTitleUntilCleared() throws {
        var bench = Self.bench()
        let tab = try #require(bench.selected)
        #expect(bench.title(of: tab) == "This Mac")
        bench.setTitle("vim", of: bench.tabs[0].focused)
        #expect(bench.title(of: tab) == "vim")
        bench.rename(tab, to: "deploy")
        #expect(bench.title(of: tab) == "deploy")
        bench.setTitle("vim again", of: bench.tabs[0].focused)
        #expect(bench.title(of: tab) == "deploy")
        bench.rename(tab, to: "  ")
        #expect(bench.title(of: tab) == "vim again")
    }

    @Test func aTabsTitleIsItsFocusedPanes() throws {
        var bench = Self.bench()
        let first = try #require(bench.focusedPane?.id)
        let second = try Self.split(&bench, .sideBySide, "mac-2")
        bench.setTitle("one", of: first)
        bench.setTitle("two", of: second)
        let tab = try #require(bench.selected)
        #expect(bench.title(of: tab) == "two")
        bench.focus(first)
        #expect(bench.title(of: tab) == "one")
    }

    @Test(arguments: [
        ("file://box.local/Users/u/a%20b", "/Users/u/a b"), ("file:///tmp", "/tmp"), ("kitty-shell-cwd://localhost/opt", "/opt"),
        ("file://BOX.local/tmp", "/tmp"), ("file://elsewhere/srv", "/home/u"), ("kitty-shell-cwd://elsewhere/opt", "/home/u"),
        ("/var/log", "/var/log"), ("rel", "/home/u"), ("", "/home/u"), ("file:///missing", "/home/u"), ("file://box.local", "/home/u"),
    ])
    func scenario9ANewPaneOpensInTheReportedFolderOnlyWhenItIsHere(_ reported: String, _ expected: String) throws {
        var bench = Self.bench()
        bench.setFolder(reported, of: bench.tabs[0].focused)
        let folders: Set = ["/Users/u/a b", "/tmp", "/opt", "/var/log", "/srv", "/home/u"]
        #expect(bench.folderForNewPane(hostNames: ["box.local"], isFolder: folders.contains) == expected)
    }

    /// A pane's shell reporting a folder on a host other than the one it first reported (the person
    /// went to another machine from it) is elsewhere: its output is that machine's. A first report
    /// without a host compares nothing, as before.
    @Test func aPaneWhoseShellReportsAnotherHostIsElsewhere() throws {
        var bench = Self.bench()
        let pane = bench.tabs[0].focused
        #expect(bench.panes[pane]?.elsewhere == false, "a pane with no report is elsewhere")
        bench.setFolder("file://dock/home/dev/workspace/api", of: pane)
        bench.setFolder("file://DOCK/home/dev/workspace", of: pane)
        #expect(bench.panes[pane]?.elsewhere == false, "the same host, cased otherwise, is elsewhere")
        bench.setFolder("file://build-box/srv/app", of: pane)
        #expect(bench.panes[pane]?.elsewhere == true, "another host is not elsewhere")
        bench.setFolder("kitty-shell-cwd://dock/home/dev", of: pane)
        #expect(bench.panes[pane]?.elsewhere == false, "back on the first host is still elsewhere")
        var hostless = Self.bench()
        let other = hostless.tabs[0].focused
        hostless.setFolder("file:///home/dev/workspace", of: other)
        hostless.setFolder("file://build-box/srv/app", of: other)
        #expect(hostless.panes[other]?.elsewhere == false, "a first report without a host compared hosts")
    }

    @Test func noTabNoFolder() {
        #expect(Workbench().folderForNewPane(hostNames: [], isFolder: { _ in true }) == nil)
    }

    @Test func scenario10OnlyLiveSessionsComeBackAndAStrayIsALastTab() throws {
        var bench = Self.bench(tabs: 3)
        bench.selectTab(at: 0)
        _ = try Self.split(&bench, .sideBySide, "mac-dead1")
        bench.selectTab(at: 2)
        _ = try Self.split(&bench, .stacked, "mac-e")
        bench.rename(bench.tabs[2].id, to: "kept")
        let kept = bench.arrangement
        let live = ["mac-t1", "mac-t3", "mac-e", "mac-stray", "other-session"]
        let restored = Workbench.restore(kept, live: live, home: "/home/u")
        let sessions = restored.tabs.map { tab in tab.layout.leaves.compactMap { restored.panes[$0]?.session } }
        #expect(sessions == [["mac-t1"], ["mac-t3", "mac-e"], ["mac-stray"]])
        #expect(restored.tabs[0].layout == .pane(restored.tabs[0].focused))
        #expect(restored.tabs.map(\.name) == [nil, "kept", nil])
        #expect(restored.selectedTab?.id == restored.tabs[1].id)
        #expect(restored.focusedPane?.session == "mac-e")
        #expect(restored.panes.values.first { $0.session == "mac-stray" }?.place == .thisMac(folder: "/home/u"))
    }

    /// A tab in a workspace says where it is, "acme · try", whatever its program titles itself;
    /// the person's name for it wins. Its sessions are named apart from this Mac's, a new tab from
    /// it opens on this Mac at home, and a relaunch, which lists this Mac's live sessions only,
    /// m10: two unnamed tabs in one workspace are told apart; `onRunner` is the one runner-or-local
    /// decision.
    @Test func twoTabsInOneWorkspaceAreToldApart() {
        var bench = Workbench()
        let place = Place.workspace(project: "acme", name: "try")
        _ = bench.newTab(session: "app-1", place: place)
        _ = bench.newTab(session: "app-2", place: place)
        _ = bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        _ = bench.newTab(session: "app-3", place: place)
        #expect(bench.tabs.map { bench.title(of: $0.id) } == ["acme · try", "acme · try 2", Workbench.untitled, "acme · try 3"])
        #expect(place.onRunner && !Place.thisMac(folder: "/").onRunner)
    }

    /// This Mac's untitled shells are told apart as two tabs in one workspace are: "This Mac", then
    /// "This Mac 2"; a shell its program titles shows that title and keeps its number for when the
    /// title goes, and a tab the person named gives its number up.
    @Test func thisMacsUntitledShellsAreToldApart() {
        var bench = Workbench()
        _ = bench.newTab(session: "mac-1", place: Self.home)
        bench.setTitle("vim", of: bench.newTab(session: "mac-2", place: Self.home))
        _ = bench.newTab(session: "app-1", place: .workspace(project: "acme", name: "try"))
        _ = bench.newTab(session: "mac-3", place: Self.home)
        _ = bench.newTab(session: "mac-4", place: Self.home)
        bench.rename(bench.tabs[4].id, to: "api")
        _ = bench.newTab(session: "mac-5", place: Self.home)
        #expect(bench.tabs.map { bench.title(of: $0.id) } == ["This Mac", "vim", "acme · try", "This Mac 3", "api", "This Mac 4"])
        bench.setTitle("", of: bench.tabs[1].focused)
        #expect(bench.title(of: bench.tabs[1].id) == "This Mac 2", "the program's title gone, the tab is not called as it was")
    }

    /// A title once given stays: closing, moving or naming another tab renumbers none; a new tab, or
    /// one whose name is cleared, takes the least number no other holds.
    @Test func aTabsNumberStaysWhatItWasGiven() {
        var bench = Workbench()
        let panes = (1 ... 3).map { bench.newTab(session: "mac-\($0)", place: Self.home) }
        let titles = { bench.tabs.map { bench.title(of: $0.id) } }
        bench.close(panes[1])
        #expect(titles() == ["This Mac", "This Mac 3"], "closing This Mac 2 renumbered the rest")
        bench.moveTab(bench.tabs[1].id, to: 0)
        #expect(titles() == ["This Mac 3", "This Mac"], "moving a tab renumbered it")
        bench.rename(bench.tabs[1].id, to: "api")
        #expect(titles() == ["This Mac 3", "api"], "naming a tab renumbered another")
        _ = bench.newTab(session: "mac-4", place: Self.home)
        bench.rename(bench.tabs[1].id, to: " ")
        #expect(titles() == ["This Mac 3", "This Mac 2", "This Mac"], "the least free number was not taken")
    }

    /// The This Mac project's sessions are this Mac's panes as they are now, a row each in the order
    /// of their tabs and, in a split, reading order: its tab's title, its folder (where its shell
    /// last reported it was on this Mac, else where it opened, the home folder said as ~ and a
    /// folder beside it as it is) and how its session ended. A workspace's pane is none of them, and
    /// a pane closed leaves the list.
    @Test func thisMacsSessionsAreItsPanesAsTheyAreNow() throws {
        var bench = Workbench()
        let first = bench.newTab(session: "mac-1", place: .thisMac(folder: "/home/u/workspace/sail"))
        _ = bench.newTab(session: "app-1", place: .workspace(project: "acme", name: "try"))
        let second = bench.newTab(session: "mac-2", place: Self.home)
        let split = bench.split(.sideBySide, session: "mac-3", place: .thisMac(folder: "/tmp"), in: Self.room, minimum: Self.least, scale: 2)
        let third = try #require(split)
        let sibling = bench.newTab(session: "mac-4", place: .thisMac(folder: "/home/u2/x"))
        bench.setFolder("file://elsewhere/srv", of: first)
        bench.setFolder("file://box.local/home/u/Downloads", of: third)
        let rows = bench.localSessions(ended: [third: "exited 3 · 11:02"], hostNames: ["box.local"], home: "/home/u")
        #expect(rows == [
            LocalSession(pane: first, title: "This Mac", folder: "~/workspace/sail", ended: nil),
            LocalSession(pane: second, title: "This Mac 2", folder: "~", ended: nil),
            LocalSession(pane: third, title: "This Mac 2", folder: "~/Downloads", ended: "exited 3 · 11:02"),
            LocalSession(pane: sibling, title: "This Mac 3", folder: "/home/u2/x", ended: nil),
        ])
        bench.close(second)
        #expect(bench.localSessions(ended: [:], hostNames: ["box.local"], home: "/home/u").map(\.pane) == [first, third, sibling])
    }

    /// brings back none of its panes.
    @Test func aWorkspaceTabSaysWhereItIsAndStaysOutOfThisMacs() throws {
        var bench = Self.bench()
        let place = Place.workspace(project: "acme", name: "try")
        let session = place.newSessionName
        let pane = bench.newTab(session: session, place: place)
        let tab = try #require(bench.selectedTab)
        bench.setTitle("runner@acme: ~", of: pane)
        #expect(bench.title(of: tab.id) == "acme · try")
        #expect(bench.title(of: bench.tabs[0].id) == Workbench.untitled)
        bench.rename(tab.id, to: "deploy")
        #expect(bench.title(of: tab.id) == "deploy")
        #expect(session.hasPrefix(Place.runnerSessions) && !session.hasPrefix(Place.thisMacSessions))
        #expect(place.folder.isEmpty)
        let size = TerminalSize(columns: 80, rows: 24)
        #expect(place.sessionCreate(session: session, size: size)
            == SessionCreate(session: session, command: [], workingDirectory: "", project: "acme", room: "", size: size, workspace: "try"),
            "a workspace tab names no folder: the runner starts it in the instance's own directory")
        #expect(Place.thisMac(folder: "/Users/u/src").sessionCreate(session: "mac-1", size: size)
            == SessionCreate(session: "mac-1", command: [], workingDirectory: "/Users/u/src", project: "", room: "", size: size))
        #expect(bench.folderForNewPane(hostNames: [], isFolder: { _ in true }) == nil)
        let restored = Workbench.restore(bench.arrangement, live: ["mac-t1"], home: "/home/u")
        #expect(restored.panes.values.map(\.session) == ["mac-t1"])
    }

    @Test func nothingLiveRestoresNothingAndAnUnknownVersionIsNotTrusted() throws {
        var bench = Self.bench(tabs: 2)
        #expect(Workbench.restore(bench.arrangement, live: [], home: "/").tabs.isEmpty)
        bench.rename(bench.tabs[0].id, to: "named")
        var future = bench.arrangement
        future.version = Arrangement.currentVersion + 1
        let strays = Workbench.restore(future, live: ["mac-t1", "mac-t2"], home: "/")
        #expect(strays.tabs.count == 2 && strays.tabs.allSatisfy { $0.name == nil })
        #expect(Workbench.restore(nil, live: ["mac-t1"], home: "/").tabs.count == 1)
        bench.selectTab(at: 0)
        let twice = Arrangement(tabs: bench.arrangement.tabs + bench.arrangement.tabs, selected: 3)
        let restored = Workbench.restore(twice, live: ["mac-t1", "mac-t2"], home: "/")
        #expect(restored.tabs.count == 2 && restored.selectedTab?.id == restored.tabs[1].id)
    }

    @Test func aStoredRatioOutsideTheOpenIntervalIsHalved() throws {
        var bench = Self.bench()
        _ = try Self.split(&bench, .sideBySide, "mac-2")
        for ratio in [0, 1, -3, .nan, .infinity] as [Double] {
            var stored = bench.arrangement
            guard case let .split(axis, _, first, second) = stored.tabs[0].layout else { Issue.record("no split"); return }
            stored.tabs[0].layout = .split(axis: axis, ratio: ratio, first: first, second: second)
            let restored = Workbench.restore(stored, live: ["mac-t1", "mac-2"], home: "/")
            guard case let .split(_, kept, _, _) = restored.tabs[0].layout else { Issue.record("no split"); return }
            #expect(kept == 0.5)
        }
    }

    @Test func theArrangementReadsBackAsItself() throws {
        var bench = try Self.grid().0
        bench.rename(bench.tabs[0].id, to: "grid")
        let data = try JSONEncoder().encode(bench.arrangement)
        #expect(try JSONDecoder().decode(Arrangement.self, from: data) == bench.arrangement)
    }

    /// The minimum is the tree's: a drag of an outer divider stops where a nested pane on the same
    /// axis would go under it (the review's sequence), and a key's move does too.
    @Test func noDragOrKeyLeavesANestedPaneUnderTheMinimum() throws {
        var bench = Workbench()
        let size = CGSize(width: 1001, height: 601)
        bench.newTab(session: "mac-a", place: Self.home)
        _ = try Self.split(&bench, .sideBySide, "mac-b", size: size)
        let c = try Self.split(&bench, .sideBySide, "mac-c", size: size)
        bench.drag([.second], to: 2000, in: size, minimum: Self.least, scale: 1)
        bench.drag([], to: 2000, in: size, minimum: Self.least, scale: 1)
        let frames = bench.frames(in: size, scale: 1).panes
        #expect(frames.values.allSatisfy { $0.width >= Self.least.width }, "\(frames.values.map(\.width))")
        #expect(frames[c]?.width == Self.least.width)
        bench.focus(c)
        for _ in 0 ..< 40 { bench.moveDivider(.right, by: 10, in: size, minimum: Self.least, scale: 1) }
        #expect(bench.frames(in: size, scale: 1).panes.values.allSatisfy { $0.width >= Self.least.width })
    }

    /// A key moves the focused pane's nearest divider on its axis, not an outer one.
    @Test func aKeyMovesTheNearestDivider() throws {
        var bench = Self.bench()
        let a = try #require(bench.focusedPane?.id)
        let b = try Self.split(&bench, .sideBySide, "mac-b")
        let c = try Self.split(&bench, .sideBySide, "mac-c")
        let before = bench.frames(in: Self.room, scale: 2).panes
        let moved = bench.moveDivider(.left, by: 10, in: Self.room, minimum: Self.least, scale: 2)
        #expect(moved)
        let after = bench.frames(in: Self.room, scale: 2).panes
        #expect(after[a] == before[a], "the outer divider moved")
        #expect(after[b].map { $0.width } == before[b].map { $0.width - 10 })
        #expect(after[c].map { $0.width } == before[c].map { $0.width + 10 })
    }

    /// A ratio of zero is never stored, even with no minimum.
    @Test func aDragNeverStoresARatioOfZeroOrOne() throws {
        var bench = Self.bench()
        let split = bench.split(.sideBySide, session: "mac-b", place: Self.home, in: Self.room, minimum: .zero, scale: 1)
        _ = try #require(split)
        for position in [-50.0, 5000] {
            bench.drag([], to: position, in: Self.room, minimum: .zero, scale: 1)
            guard case let .split(_, ratio, _, _) = bench.tabs[0].layout else { Issue.record("no split"); return }
            #expect(ratio > 0 && ratio < 1, "ratio \(ratio)")
        }
    }

    /// A tab too small for its panes keeps its ratios (they clip) when nothing fits, and takes equal
    /// shares when those fit (a restored ratio made for a wider window); equalize never squeezes.
    @Test func aTabTooSmallKeepsItsRatiosOrTakesEqualShares() throws {
        var bench = Self.bench()
        _ = try Self.split(&bench, .sideBySide, "mac-b")
        bench.drag([], to: 850, in: Self.room, minimum: Self.least, scale: 2)
        let wide = bench.tabs[0].layout
        bench.fit(in: CGSize(width: 200, height: 601), minimum: Self.least, scale: 2)
        #expect(bench.tabs[0].layout == wide, "a tab nothing fits changed its ratios")
        bench.fit(in: CGSize(width: 400, height: 601), minimum: Self.least, scale: 2)
        guard case let .split(_, ratio, _, _) = bench.tabs[0].layout else { Issue.record("no split"); return }
        #expect(ratio == 0.5)
        var three = Self.bench()
        _ = try Self.split(&three, .sideBySide, "mac-x")
        _ = try Self.split(&three, .sideBySide, "mac-y")
        let small = CGSize(width: 300, height: 601)
        let before = three.tabs[0].layout
        three.equalize(in: small, minimum: Self.least, scale: 2)
        #expect(three.tabs[0].layout == before, "equalize made a pane under the minimum")
    }

    /// A tab of more panes than its room has points for, restored: focus is moved off a pane laid
    /// out with no area. Thirty deep, not hundreds: a test thread's stack is 512 KB on macOS.
    @Test func focusLeavesAPaneWithNoArea() throws {
        var node = Arrangement.Node.pane(session: "mac-0", place: Self.home)
        for index in 1 ..< 30 {
            node = .split(axis: .sideBySide, ratio: 0.5, first: .pane(session: "mac-\(index)", place: Self.home), second: node)
        }
        var kept = Self.bench().arrangement
        kept.tabs[0].layout = node
        kept.tabs[0].focused = "mac-0"
        var bench = Workbench.restore(kept, live: (0 ..< 30).map { "mac-\($0)" }, home: "/")
        let size = CGSize(width: 40, height: 572)
        let deepest = try #require(bench.tabs[0].layout.leaves.last)
        #expect(bench.tabs[0].focused == deepest)
        #expect(bench.frames(in: size, scale: 1).panes[deepest]?.width == 0)
        bench.fit(in: size, minimum: Self.least, scale: 1)
        let width = bench.frames(in: size, scale: 1).panes[bench.tabs[0].focused]?.width ?? 0
        #expect(width >= 1, "focus rests on a pane \(width) pt wide")
    }

    /// Closing the selected tab selects the one before it, not the one after.
    @Test func closingTheSelectedTabSelectsTheOneBefore() {
        var bench = Self.bench(tabs: 3)
        let ids = bench.tabs.map(\.id)
        bench.select(ids[1])
        bench.closeTab(ids[1])
        #expect(bench.selected == ids[0])
        bench.select(ids[0])
        bench.closeTab(ids[0])
        #expect(bench.selected == ids[2])
    }

    @Test(arguments: [
        ("exited(0)", 0.5, true), ("exited(0)", 5.0, true), ("exited(3)", 0.5, false), ("exited(3)", 2.0, true),
        ("exited(-1)", 9.0, true), ("killed", 9.0, false), ("exited()", 9.0, false), ("exited(x)", 9.0, false), ("host gone", 0.1, false),
    ])
    func aProgramThatExitsClosesItsPaneUnlessItFailedAtOnce(_ reason: String, _ lived: Double, _ closes: Bool) {
        #expect(Ending.closesPane(reason: reason, lived: .milliseconds(Int(lived * 1000))) == closes)
    }

    @Test func tabsSelectWrapAndMove() {
        var bench = Self.bench(tabs: 3)
        let ids = bench.tabs.map(\.id)
        bench.selectNeighbourTab(next: true)
        #expect(bench.selected == ids[0])
        bench.selectNeighbourTab(next: false)
        #expect(bench.selected == ids[2])
        bench.selectTab(at: 1)
        #expect(bench.selected == ids[1])
        bench.selectTab(at: 7)
        #expect(bench.selected == ids[1])
        bench.selectLastTab()
        #expect(bench.selected == ids[2])
        bench.moveTab(ids[2], to: 0)
        #expect(bench.tabs.map(\.id) == [ids[2], ids[0], ids[1]])
        bench.selectTab(at: 0)
        #expect(bench.selected == ids[2])
        bench.moveTab(ids[2], to: 99)
        #expect(bench.tabs.map(\.id) == [ids[0], ids[1], ids[2]])
    }
}

/// The chat app's tabs (`sail-app-a-tab-holds-a-chat-or-a-terminal`), scenarios 4 to 12: a tab holds
/// a chat, a new chat or a terminal, ⌘T opens one like the tab in front, live sessions are adopted
/// as last tabs, and a tab says one mark.
@Suite(.timeLimit(.minutes(1))) struct ChatTabTests {
    static let dock = Place.workspace(project: "harbor", name: "dock")
    static let pier = Place.workspace(project: "harbor", name: "pier")
    static let claude = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")
    static let codex = AgentStart(harness: "codex", display: "Codex CLI", mode: "default", inside: true)
    static let room = CGSize(width: 1001, height: 601)
    static let least = CGSize(width: 110, height: 70)

    static func listing(_ golden: String = "workspace-sessions-chats", altered: [(path: [any JSONPathStep], value: Any?)] = []) throws -> RunnerSessions {
        let exchange = try altered.reduce(try Exchange(golden)) { try $0.altered(at: $1.path, to: $1.value) }
        return try Endpoint.sessions(workspace: "dock", project: "harbor").decode(HTTPResponse(status: exchange.status, body: exchange.body))
    }

    static func chatID(_ last: String) -> String {
        "00000000-0000-7000-8000-0000000000\(last)"
    }

    static func sessions(_ bench: Workbench) -> [[String]] {
        bench.tabs.map { $0.layout.leaves.compactMap { bench.panes[$0]?.session } }
    }

    static func likeFront(_ bench: Workbench, chatFirst: Bool = false, agent: AgentStart? = claude) -> Opening {
        bench.likeFront(chatFirst: chatFirst, agent: agent, hostNames: ["mac"], isFolder: { _ in true }, home: "/home/u")
    }

    /// Scenario 4: ⌘T on a chat opens a new chat in its workspace with its harness, mode and way
    /// in, as a last tab, selected; on a new chat too.
    @Test func commandTOnAChatOpensANewChatInItsWorkspace() throws {
        var bench = Workbench()
        bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        bench.adopt(listing: try Self.listing(altered: [(["sessions", 2, "agent", "mode"], "plan")]), in: Self.dock, for: "uday")
        let chat = try #require(bench.panes.values.first { $0.session == Self.chatID("18") })
        bench.focus(chat.id)
        let opening = Self.likeFront(bench)
        #expect(opening == .newChat(Self.dock, agent: Self.claude))
        guard case let .newChat(place, agent) = opening else { return }
        let made = bench.newChat(in: place, agent: agent)
        #expect(bench.tabs.last?.layout == .pane(made) && bench.selectedTab?.id == bench.tabs.last?.id)
        let pane = try #require(bench.panes[made])
        #expect(pane.form == .newChat && pane.place == Self.dock && pane.agent == Self.claude && pane.session.hasPrefix(Pane.newChats))
        #expect(Self.likeFront(bench) == .newChat(Self.dock, agent: Self.claude))
    }

    /// ⌘T on a terminal opens one like it: a workspace's shell a shell there, an agent the same
    /// agent and mode there, a This Mac shell a This Mac shell in its reported folder.
    @Test func commandTOnATerminalOpensOneLikeIt() {
        var bench = Workbench()
        let shell = bench.newTab(session: "app-shell", place: Self.dock)
        #expect(Self.likeFront(bench, chatFirst: true) == .terminal(Self.dock, agent: nil))
        bench.newTab(session: "app-agent", place: Self.pier, agent: Self.codex)
        #expect(Self.likeFront(bench) == .terminal(Self.pier, agent: Self.codex))
        let mac = bench.newTab(session: "mac-1", place: .thisMac(folder: "/home/u"))
        bench.setFolder("file://mac/srv/app", of: mac)
        #expect(Self.likeFront(bench, chatFirst: true) == .terminal(.thisMac(folder: "/srv/app"), agent: nil))
        bench.focus(shell)
        #expect(Self.likeFront(bench) == .terminal(Self.dock, agent: nil))
    }

    /// Scenario 5: with no tab, a person who starts in Chat gets a new chat in the workspace of
    /// the runner pane focused last; anyone else, or with no workspace yet, a This Mac shell.
    @Test func commandTWithNoTabOpensANewChatInTheLastWorkspaceForAChatPerson() {
        var bench = Workbench()
        #expect(Self.likeFront(bench, chatFirst: true) == .terminal(.thisMac(folder: "/home/u"), agent: nil))
        let dock = bench.newTab(session: "app-1", place: Self.dock)
        let pier = bench.newTab(session: "app-2", place: Self.pier)
        bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        #expect(bench.lastWorkspace == Self.pier)
        bench.focus(dock)
        bench.focus(pier)
        bench.closeTab(bench.tabs[2].id)
        bench.closeTab(bench.tabs[0].id)
        bench.closeTab(bench.tabs[0].id)
        #expect(bench.tabs.isEmpty && bench.lastWorkspace == Self.pier)
        #expect(Self.likeFront(bench, chatFirst: true) == .newChat(Self.pier, agent: Self.claude))
        #expect(Self.likeFront(bench, chatFirst: false) == .terminal(.thisMac(folder: "/home/u"), agent: nil))
        #expect(Self.likeFront(bench, chatFirst: true, agent: nil) == .terminal(.thisMac(folder: "/home/u"), agent: nil))
    }

    /// The last workspace is kept with the arrangement and comes back for its person alone.
    @Test func theLastWorkspaceComesBackForItsPersonAlone() {
        var bench = Workbench()
        bench.newTab(session: "app-1", place: Self.pier)
        bench.closeTab(bench.tabs[0].id)
        let kept = bench.arrangement(runner: "uday", holding: [])
        #expect(kept.lastWorkspace == Self.pier)
        #expect(Workbench.restore(kept, live: [], home: "/", for: "uday").lastWorkspace == Self.pier)
        #expect(Workbench.restore(kept, live: [], home: "/", for: "ana").lastWorkspace == nil)
        #expect(Workbench.restore(kept, live: [], home: "/").lastWorkspace == nil)
    }

    /// Scenario 6: a new chat's workspace and harness change until its first message binds it,
    /// once, to the chat it opened; then it holds that chat, in its place for good.
    @Test func aNewChatChangesUntilItIsBoundOnce() throws {
        var bench = Workbench()
        let pane = bench.newChat(in: Self.dock, agent: Self.claude)
        bench.move(newChat: pane, to: Self.pier, agent: Self.codex)
        #expect(bench.panes[pane]?.place == Self.pier && bench.panes[pane]?.agent == Self.codex && bench.lastWorkspace == Self.pier)
        #expect(bench.bind(pane, to: Self.chatID("05")) == nil)
        let bound = try #require(bench.panes[pane])
        #expect(bound.form == .chat && bound.session == Self.chatID("05") && bound.place == Self.pier && bound.agent == Self.codex)
        #expect(Self.likeFront(bench) == .newChat(Self.pier, agent: Self.codex))
    }

    @Test func aBoundChatIsNeverBoundAgain() async {
        await #expect(processExitsWith: .failure) {
            var bench = Workbench()
            let pane = bench.newChat(in: Place.workspace(project: "harbor", name: "dock"), agent: AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan"))
            bench.bind(pane, to: "00000000-0000-7000-8000-000000000005")
            bench.bind(pane, to: "00000000-0000-7000-8000-000000000006")
        }
    }

    @Test func aBoundChatNeverChangesItsPlace() async {
        await #expect(processExitsWith: .failure) {
            var bench = Workbench()
            let start = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")
            let pane = bench.newChat(in: Place.workspace(project: "harbor", name: "dock"), agent: start)
            bench.bind(pane, to: "00000000-0000-7000-8000-000000000005")
            bench.move(newChat: pane, to: Place.workspace(project: "harbor", name: "pier"), agent: start)
        }
    }

    /// A chat a listing adopted before its first message's answer came is the bound pane's alone:
    /// the adopted tab closes, its state the bound pane's.
    @Test func aChatAdoptedBeforeItsBindIsHeldOnce() throws {
        var bench = Workbench()
        let pane = bench.newChat(in: Self.dock, agent: Self.claude)
        let adopted = bench.adopt(listing: try Self.listing("workspace-sessions-no-terminal-door"), in: Self.dock, for: "uday")
        #expect(adopted.count == 5)
        let closed = bench.bind(pane, to: Self.chatID("20"))
        #expect(closed?.session == Self.chatID("20") && bench.panes.values.filter { $0.session == Self.chatID("20") }.map(\.id) == [pane])
        #expect(bench.panes[pane]?.chatState == .known(.working) && bench.tabs.count == 5)
    }

    /// Scenario 7, and the order: each live session of the person no pane holds opens as a last
    /// tab, oldest first; ended ones never; the same listing again adds none, and the selected tab
    /// stays.
    @Test func liveSessionsAreAdoptedAsLastTabsOnce() throws {
        var bench = Workbench()
        bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        let front = bench.selected
        let listing = try Self.listing()
        let made = bench.adopt(listing: listing, in: Self.dock, for: "uday")
        #expect(Self.sessions(bench) == [["mac-1"], ["app-1f2e3d4c"]] + ["21", "20", "19", "18", "17"].map { [Self.chatID($0)] })
        #expect(made.compactMap { bench.panes[$0]?.form } == [.terminal] + Array(repeating: .chat, count: 5))
        #expect(bench.panes[made[0]]?.agent == AgentStart(harness: "claude-code", display: "Claude Code", mode: "default"))
        #expect(bench.selected == front)
        #expect(bench.adopt(listing: listing, in: Self.dock, for: "uday").isEmpty && bench.selected == front && bench.tabs.count == 7)
    }

    /// A chat started on another Mac is one last tab; with no tab before, the first adopted is
    /// selected.
    @Test func aChatFromAnotherMacArrivesAsOneLastTab() throws {
        var bench = Workbench()
        let listing = try Self.listing("workspace-sessions-no-terminal-door")
        bench.adopt(listing: RunnerSessions(sessions: [], chats: Array(listing.chats.dropFirst(2))), in: Self.dock, for: "uday")
        #expect(bench.selectedTab?.id == bench.tabs.first?.id)
        let front = bench.selected
        #expect(bench.adopt(listing: listing, in: Self.dock, for: "uday").compactMap { bench.panes[$0]?.session } == [Self.chatID("17")])
        #expect(bench.tabs.last?.layout.leaves.compactMap { bench.panes[$0]?.session } == [Self.chatID("17")] && bench.selected == front)
    }

    /// Scenario 8, and a held tab: another person's rows (an admin's listing holds them), unknown
    /// forms and sessions a held-aside tab holds are never adopted.
    @Test func anotherPersonsRowsAndHeldSessionsAreNotAdopted() throws {
        var bench = Workbench()
        let theirs = try Self.listing(altered: [(["sessions", 6, "opener"], "ana"), (["sessions", 5, "opener"], "ana"), (["sessions", 4, "form"], "drydock")])
        let made = bench.adopt(listing: theirs, in: Self.dock, for: "uday")
        #expect(made.compactMap { bench.panes[$0]?.session } == ["19", "18", "17"].map(Self.chatID))
        var other = Workbench()
        let held = [Arrangement.Tab(name: nil, focused: Self.chatID("17"), layout: .pane(session: Self.chatID("17"), place: Self.dock, agent: Self.claude, form: .chat))]
        other.adopt(listing: try Self.listing(), in: Self.dock, for: "uday", beside: held)
        #expect(!other.panes.values.contains { $0.session == Self.chatID("17") } && other.tabs.count == 5)
    }

    /// Scenario 9: a turn that ends while its tab is not in front marks an unseen turn until the
    /// tab is selected; one that ends in front of a window that is not key, until it is key.
    @Test func aTurnEndedOutOfSightIsUnseenUntilShown() throws {
        var bench = Workbench()
        bench.adopt(listing: try Self.listing("workspace-sessions-no-terminal-door"), in: Self.dock, for: "uday")
        let working = try #require(bench.panes.values.first { $0.session == Self.chatID("20") })
        let tab = try #require(bench.tab(holding: working.id)).id
        #expect(bench.mark(of: tab) == .working)
        bench.selectTab(at: 0)
        bench.setChatState(.known(.yourTurn), of: working.id)
        #expect(bench.mark(of: tab) == .unseenTurn)
        bench.setChatState(.known(.yourTurn), of: working.id)
        #expect(bench.mark(of: tab) == .unseenTurn)
        bench.select(tab)
        #expect(bench.mark(of: tab) == .none)
        bench.setChatState(.known(.working), of: working.id)
        bench.setWindowKey(false)
        bench.setChatState(.known(.yourTurn), of: working.id)
        #expect(bench.mark(of: tab) == .unseenTurn)
        bench.setWindowKey(true)
        #expect(bench.mark(of: tab) == .none)
        bench.setChatState(.known(.working), of: working.id)
        bench.setChatState(.known(.yourTurn), of: working.id)
        #expect(bench.mark(of: tab) == .none, "a turn ended in front of a key window was taken for unseen")
    }

    /// A first state is no turn's end, and a new state ends an unseen turn.
    @Test func aFirstStateIsNoTurnsEnd() throws {
        var bench = Workbench()
        bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        bench.adopt(listing: try Self.listing("workspace-sessions-no-terminal-door"), in: Self.dock, for: "uday")
        let yours = try #require(bench.panes.values.first { $0.session == Self.chatID("18") })
        #expect(bench.mark(of: try #require(bench.tab(holding: yours.id)).id) == .none)
        let working = try #require(bench.panes.values.first { $0.session == Self.chatID("20") })
        let tab = try #require(bench.tab(holding: working.id)).id
        bench.setChatState(.known(.yourTurn), of: working.id)
        bench.setChatState(.known(.needsYou), of: working.id)
        #expect(bench.mark(of: tab) == .needsYou && bench.panes[working.id]?.unseen == false)
    }

    /// The listing tells a held chat where it stands and its title, an ended turn's included.
    @Test func aListingRefreshesTheChatsItHolds() throws {
        var bench = Workbench()
        bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        bench.adopt(listing: try Self.listing("workspace-sessions-no-terminal-door"), in: Self.dock, for: "uday")
        let tab = try #require(bench.panes.values.first { $0.session == Self.chatID("20") }.flatMap { bench.tab(holding: $0.id) }).id
        let later = try Self.listing("workspace-sessions-no-terminal-door", altered: [(["sessions", 4, "state"], "your_turn"), (["sessions", 4, "title"], "Fix the tide table rounding")])
        #expect(bench.adopt(listing: later, in: Self.dock, for: "uday").isEmpty)
        #expect(bench.mark(of: tab) == .unseenTurn && bench.title(of: tab) == "Fix the tide table rounding")
    }

    /// Scenario 10, and the order of marks: a tab says the most urgent of its chats'; a split of a
    /// chat opens a terminal in the chat's workspace, which says none.
    @Test func aTabSaysTheMostUrgentMarkOfItsChats() throws {
        var bench = Workbench()
        bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        bench.adopt(listing: try Self.listing("workspace-sessions-no-terminal-door"), in: Self.dock, for: "uday")
        let working = try #require(bench.panes.values.first { $0.session == Self.chatID("20") })
        bench.focus(working.id)
        let place = bench.placeForSplit(hostNames: [], isFolder: { _ in true }, home: "/")
        #expect(place == Self.dock)
        let split = bench.split(.sideBySide, session: "app-beside", place: place, in: Self.room, minimum: Self.least, scale: 2)
        let shell = try #require(split)
        #expect(bench.panes[shell]?.form == .terminal && bench.panes[shell]?.agent == nil && bench.panes[shell]?.mark == TabMark.none)
        let tab = try #require(bench.selected)
        #expect(bench.mark(of: tab) == .working)
        #expect(bench.mark(of: bench.tabs[0].id) == .none)
        #expect(TabMark.allCases.sorted(by: >) == [.needsYou, .error, .unseenTurn, .working, .ended, .none])
        let marks = bench.tabs.dropFirst().map { bench.mark(of: $0.id) }
        #expect(marks == [.none, .working, .needsYou, .none, .error], "\(marks)")
        let kept = Arrangement.Node.split(
            axis: .sideBySide, ratio: 0.5,
            first: .pane(session: Self.chatID("30"), place: Self.dock, agent: Self.claude, form: .chat),
            second: .pane(session: Self.chatID("31"), place: Self.dock, agent: Self.claude, form: .chat)
        )
        var two = Workbench.restore(Arrangement(tabs: [.init(name: nil, focused: Self.chatID("30"), layout: kept)], selected: 0), live: [Self.chatID("30"), Self.chatID("31")], home: "/")
        let leaves = try #require(two.tabs.first).layout.leaves
        try #require(leaves.count == 2)
        let (one, other) = (leaves[0], leaves[1])
        two.setChatState(.known(.ended), of: one)
        two.setChatState(.known(.error), of: other)
        #expect(two.mark(of: two.tabs[0].id) == .error)
        two.setChatState(.known(.needsYou), of: one)
        #expect(two.mark(of: two.tabs[0].id) == .needsYou)
    }

    /// Scenario 8 of the spec: a new chat is "New chat", a chat its listed title else "New chat",
    /// a person's name stands over both, and two alike are numbered.
    @Test func aChatTabIsTitledByItsTitleElseNewChat() throws {
        var bench = Workbench()
        let first = bench.newChat(in: Self.dock, agent: Self.claude)
        bench.newChat(in: Self.pier, agent: Self.claude)
        #expect(bench.tabs.map { bench.title(of: $0.id) } == ["New chat", "New chat 2"])
        bench.bind(first, to: Self.chatID("20"))
        #expect(bench.title(of: bench.tabs[0].id) == "New chat")
        bench.adopt(listing: try Self.listing("workspace-sessions-no-terminal-door", altered: [(["sessions", 4, "title"], "Fix the tide table rounding")]), in: Self.dock, for: "uday")
        #expect(bench.title(of: bench.tabs[0].id) == "Fix the tide table rounding")
        bench.rename(bench.tabs[0].id, to: "tides")
        #expect(bench.title(of: bench.tabs[0].id) == "tides")
        #expect(bench.title(of: bench.tabs[2].id) == "New chat 3")
    }

    /// Scenario 11: a chat's and a new chat's drafts and forms are kept; signed out they are held,
    /// and they come back for their own person's sign-in alone.
    @Test func chatPanesAndDraftsComeBackForTheirPersonAlone() throws {
        var bench = Workbench()
        bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        let draft = bench.newChat(in: Self.dock, agent: Self.claude)
        bench.setDraft("Why does PierTest time out", of: draft)
        let chat = bench.newChat(in: Self.pier, agent: Self.codex)
        bench.bind(chat, to: Self.chatID("05"))
        bench.setDraft("and on CI?", of: chat)
        let terminal = bench.newTab(session: "app-1", place: Self.dock)
        bench.setDraft("typed into a shell", of: terminal)
        #expect(bench.panes[terminal]?.draft == "")
        let kept = try JSONDecoder().decode(Arrangement.self, from: JSONEncoder().encode(bench.arrangement(runner: "uday", holding: [])))
        func back(_ restored: Workbench) -> [String: String] {
            Dictionary(uniqueKeysWithValues: restored.panes.values.filter(\.converses).map { ($0.session, $0.draft) })
        }
        let uday = Workbench.restore(kept, live: ["mac-1"] + kept.runnerSessions(for: "uday"), home: "/", for: "uday")
        #expect(back(uday) == [bench.panes[draft]?.session ?? "": "Why does PierTest time out", Self.chatID("05"): "and on CI?"])
        #expect(uday.panes.values.first { $0.session == Self.chatID("05") }?.form == .chat)
        let unbound = try #require(uday.panes.values.first { $0.form == .newChat })
        #expect(unbound.place == Self.dock && unbound.agent == Self.claude)
        #expect(uday.arrangement(runner: "uday", holding: []) == kept)
        let ana = Workbench.restore(kept, live: ["mac-1"] + kept.runnerSessions(for: "ana"), home: "/", for: "ana")
        #expect(Self.sessions(ana) == [["mac-1"]])
        var signedOut = Workbench.restore(kept, live: ["mac-1"], home: "/")
        #expect(Self.sessions(signedOut) == [["mac-1"]])
        let held = kept.runnerTabs
        #expect(held.count == 3)
        #expect(signedOut.adopt(held).count == 3)
        #expect(back(signedOut) == back(uday))
        #expect(signedOut.adopt(held).isEmpty)
    }

    /// Scenario 12: an arrangement of the old version is dropped; its live sessions come back as
    /// tabs from the listing.
    @Test func anOldArrangementIsDroppedAndTheListingBringsItsSessionsBack() throws {
        var bench = Workbench()
        bench.adopt(listing: try Self.listing(), in: Self.dock, for: "uday")
        bench.rename(bench.tabs[0].id, to: "agent")
        var old = bench.arrangement(runner: "uday", holding: [])
        old.version = 1
        #expect(Arrangement.currentVersion == 2)
        var restored = Workbench.restore(old, live: old.runnerSessions(for: "uday"), home: "/", for: "uday")
        #expect(restored.tabs.isEmpty && restored.lastWorkspace == nil)
        restored.adopt(listing: try Self.listing(), in: Self.dock, for: "uday")
        #expect(Self.sessions(restored) == Self.sessions(bench) && restored.tabs.allSatisfy { $0.name == nil })
    }

    /// A kept chat that names no harness, or a chat on this Mac, is not whole and is dropped
    /// rather than stopping the app.
    @Test func aKeptChatThatIsNotWholeIsDropped() {
        let broken = Arrangement(tabs: [
            .init(name: nil, focused: "x", layout: .pane(session: "x", place: Self.dock, form: .chat)),
            .init(name: nil, focused: "y", layout: .pane(session: "y", place: .thisMac(folder: "/"), agent: Self.claude, form: .newChat)),
            .init(name: nil, focused: "z", layout: .pane(session: "z", place: Self.dock, agent: Self.claude, form: .chat)),
        ], selected: 0)
        let restored = Workbench.restore(broken, live: ["x", "y", "z"], home: "/")
        #expect(Self.sessions(restored) == [["z"]])
    }
}
