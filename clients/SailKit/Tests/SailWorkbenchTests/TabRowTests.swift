import SailWorkbench
import Testing

/// The strip's arithmetic at the window's least width (480 pt) and its usual one (1180 pt), with 3,
/// 8, 12 and 30 tabs: equal widths from 240 to 124 pt, then tabs at 124 pt run from the first one
/// asked, a step a tab; the selected tab shown once revealed; `+`, an empty stretch and, while
/// scrolling, the count always left; an edge fades only with tabs past it, the next tab under it.
@Suite(.timeLimit(.minutes(1))) struct TabRowTests {
    /// The strip in a window `window` wide: after the rail and the window's buttons (78 pt) to 8 pt
    /// short of the edge.
    static func strip(_ window: Double) -> Double {
        window - 78 - 8
    }

    static func row(_ tabs: Int, first: Int = 0, window: Double) -> TabRow {
        TabRow(tabs: tabs, width: strip(window), first: first)
    }

    /// What the strip leaves after `+`, less the count's room while it scrolls.
    static func leftOver(_ row: TabRow, window: Double) -> Double {
        strip(window) - row.add - TabRow.addSide - (row.scrolls ? TabRow.countSide : 0)
    }

    @Test(arguments: [480.0, 1180.0])
    func everySelectedTabIsShownOnceRevealedAndTheEndStaysFree(_ window: Double) {
        for tabs in [3, 8, 12, 30] {
            for first in [0, tabs / 2, tabs] {
                for selected in 0 ..< tabs {
                    let row = Self.row(tabs, first: first, window: window).revealing(selected)
                    #expect(row.shows(selected), "\(tabs) tabs in \(window) pt from \(first): tab \(selected) not shown")
                    #expect(row.width >= TabRow.narrowest && row.width <= TabRow.widest)
                    #expect(row.first >= 0 && row.first + row.shown <= tabs && row.shown >= 1)
                    #expect(Self.leftOver(row, window: window) >= TabRow.stretch - 1e-9, "\(tabs) tabs in \(window) pt leave no stretch")
                    #expect(row.start(of: row.first) == row.inset && row.inset >= 0)
                    #expect((row.start(of: row.first + row.shown - 1) ?? .infinity) + row.width <= row.room + 1e-9, "the last tab shown runs past the room")
                }
            }
        }
    }

    @Test func whatFitsAtEachWidth() {
        let three = Self.row(3, window: 1180), eight = Self.row(8, window: 1180), twelve = Self.row(12, window: 1180)
        #expect(three.shown == 3 && three.width == 240 && !three.scrolls)
        #expect(eight.shown == 8 && eight.width >= TabRow.narrowest && !eight.scrolls)
        #expect(twelve.width == 124 && twelve.shown == 7 && twelve.scrolls, "twelve tabs in 1180 pt: \(twelve.shown) of \(twelve.width) pt")
        #expect(Self.row(4, window: 480).shown == 1 && Self.row(4, window: 480).scrolls)
        #expect(Self.row(2, window: 480).shown == 2 && !Self.row(2, window: 480).scrolls)
        #expect(Self.row(30, window: 480).revealing(29).first == 29)
        #expect(Self.row(0, window: 480).shown == 0 && Self.row(0, window: 480).add == 0)
        #expect(three.add == three.room + TabRow.gap && twelve.add == twelve.room + TabRow.gap)
        #expect(three.margins.before == 0 && three.margins.after == 0 && Self.row(0, window: 480).margins.after == 0)
    }

    /// A step a tab: from the first tab to the second and third, the tabs keep their width and the
    /// selection is the row's caller's; past the end the row stays at the end, before the start at it.
    @Test func eachStepMovesTheShownTabsByOne() {
        let firsts = (0 ... 8).map { Self.row(12, first: $0, window: 1180).first }
        #expect(firsts == [0, 1, 2, 3, 4, 5, 5, 5, 5], "\(firsts)")
        #expect((0 ... 8).allSatisfy { Self.row(12, first: $0, window: 1180).width == TabRow.narrowest })
        #expect(Self.row(12, first: -3, window: 1180).first == 0)
        #expect(Self.row(12, first: 2, window: 1180).shows(2) && !Self.row(12, first: 2, window: 1180).shows(1))
    }

    @Test func anEdgeFadesOnlyWithTabsPastIt() {
        let start = Self.row(12, window: 1180), middle = Self.row(12, first: 2, window: 1180), end = Self.row(12, first: 99, window: 1180)
        #expect(!start.fadesBefore && start.fadesAfter)
        #expect(middle.fadesBefore && middle.fadesAfter)
        #expect(end.fadesBefore && !end.fadesAfter)
        #expect(!Self.row(3, window: 1180).fadesBefore && !Self.row(3, window: 1180).fadesAfter)
    }

    /// Past a fading edge the next tab shows in the room the edge keeps, under the fade, and the tabs
    /// further on not at all.
    @Test func theNextTabShowsUnderTheFade() throws {
        for row in [Self.row(12, window: 1180), Self.row(12, first: 2, window: 1180), Self.row(12, first: 99, window: 1180)] {
            let last = row.first + row.shown - 1
            let shownEnd = try #require(row.start(of: last)) + row.width
            if row.fadesBefore {
                let before = try #require(row.start(of: row.first - 1), "from \(row.first) the tab before shows nothing")
                #expect(before < 0 && before + row.width <= row.inset - TabRow.gap + 1e-9 && row.inset >= TabRow.fade && row.margins.before == row.inset)
                #expect(row.start(of: row.first - 2) == nil)
            }
            if row.fadesAfter {
                let after = try #require(row.start(of: last + 1), "from \(row.first) the tab after shows nothing")
                #expect(after == shownEnd + TabRow.gap && row.room - shownEnd >= TabRow.fade && row.margins.after == row.room - shownEnd)
                #expect(row.start(of: last + 2) == nil)
            }
        }
    }

    /// Revealing moves the row the least: a tab past the trailing edge comes in last, one past the
    /// leading edge first, one shown leaves the row as it is.
    @Test func revealingMovesTheRowTheLeast() {
        let start = Self.row(12, window: 1180)
        let nine = start.revealing(9)
        #expect(nine.shows(9) && nine.first + nine.shown - 1 == 9, "tab 9 revealed from the start: \(nine.first) + \(nine.shown)")
        #expect(start.revealing(11).first == 5 && start.revealing(3) == start && start.revealing(nil) == start)
        let end = Self.row(12, first: 99, window: 1180)
        #expect(end.revealing(2).first == 2 && end.revealing(0).first == 0)
    }
}
