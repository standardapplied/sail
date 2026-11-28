import SailWorkbench
import Testing

/// The one list the strip's count and the Window menu draw: every tab in the strip's order, titled
/// as the strip names it, the selected one marked, ⌘1 to ⌘8 for the first eight and ⌘9 for the
/// last of nine or more.
@Suite(.timeLimit(.minutes(1))) struct TabMenuTests {
    static func keys(_ tabs: Int) -> [String?] {
        TabMenu(WorkbenchTests.bench(tabs: tabs)).entries.map(\.key)
    }

    @Test func theKeysForOneEightNineAndTwelveTabs() {
        #expect(Self.keys(1) == ["1"])
        #expect(Self.keys(8) == ["1", "2", "3", "4", "5", "6", "7", "8"])
        #expect(Self.keys(9) == ["1", "2", "3", "4", "5", "6", "7", "8", "9"])
        #expect(Self.keys(12) == ["1", "2", "3", "4", "5", "6", "7", "8", nil, nil, nil, "9"])
        #expect(TabMenu(Workbench()).entries.isEmpty)
    }

    @Test func everyTabInTheStripsOrderTitledAsTheStripNamesIt() throws {
        var bench = WorkbenchTests.bench(tabs: 3)
        let ids = bench.tabs.map(\.id)
        bench.rename(ids[1], to: "build")
        bench.moveTab(ids[2], to: 0)
        bench.select(ids[0])
        let menu = TabMenu(bench)
        #expect(menu.entries.map(\.id) == [ids[2], ids[0], ids[1]])
        #expect(menu.entries.map(\.title) == [bench.title(of: ids[2]), "This Mac", "build"])
        #expect(menu.entries.map(\.selected) == [false, true, false])
        #expect(menu.entries.map(\.key) == ["1", "2", "3"])
    }

    /// Scenario 6: the second tab renamed and the fifth closed, the list says the new name, holds
    /// eleven and numbers its keys again.
    @Test func aRenameAndACloseAreListedAtOnce() throws {
        var bench = WorkbenchTests.bench(tabs: 12)
        let ids = bench.tabs.map(\.id)
        let before = TabMenu(bench)
        bench.rename(ids[1], to: "deploy")
        let fifth = try #require(bench.tabs[4].layout.leaves.first)
        _ = bench.close(fifth)
        let after = TabMenu(bench)
        #expect(after != before)
        try #require(after.entries.count == 11)
        #expect(!after.entries.contains { $0.id == ids[4] })
        #expect(after.entries[1].title == "deploy")
        #expect(after.entries.map(\.key) == ["1", "2", "3", "4", "5", "6", "7", "8", nil, nil, "9"])
        #expect(after.entries.filter(\.selected).map(\.id) == [bench.selected].compactMap(\.self))
    }
}
