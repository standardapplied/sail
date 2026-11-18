#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation
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

    /// A zoomed pane being closed keeps its zoom while the focus moves on, and loses it only as it
    /// goes: one layout, not two.
    @Test func aZoomKeptAsTheFocusMovesOnEndsOnlyWithTheClose() throws {
        let (start, a, b, _, _) = try Self.grid()
        var bench = start
        bench.focus(a)
        bench.toggleZoom()
        let zoomed = bench.frames(in: Self.room, scale: 2)
        bench.focus(b, keepingZoom: true)
        #expect(bench.focusedPane?.id == b && bench.selectedTab?.zoomed == a)
        #expect(bench.frames(in: Self.room, scale: 2) == zoomed, "nothing laid out again yet")
        bench.close(a)
        #expect(bench.selectedTab?.zoomed == nil && bench.focusedPane?.id == b)
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
        future.version = 2
        let strays = Workbench.restore(future, live: ["mac-t1", "mac-t2"], home: "/")
        #expect(strays.tabs.count == 2 && strays.tabs.allSatisfy { $0.name == nil })
        #expect(Workbench.restore(nil, live: ["mac-t1"], home: "/").tabs.count == 1)
        bench.selectTab(at: 0)
        let twice = Arrangement(version: 1, tabs: bench.arrangement.tabs + bench.arrangement.tabs, selected: 3)
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
