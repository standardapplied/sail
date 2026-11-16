import SailWorkbench
import Testing

/// The strip's arithmetic at the window's least width (480 pt) and its usual one (1180 pt), with 3,
/// 8 and 30 tabs: equal widths from 240 to 120 pt, the selected tab always in the strip, `+` and an
/// empty stretch always left.
@Suite struct TabRowTests {
    /// The strip in a window `window` wide: after the rail and the window's buttons (78 pt) to 8 pt
    /// short of the edge.
    static func row(_ tabs: Int, selected: Int, window: Double) -> TabRow {
        TabRow(tabs: tabs, selected: selected, width: window - 78 - 8)
    }

    static func leftOver(_ row: TabRow, window: Double) -> Double {
        window - 86 - Double(row.shown) * row.width - Double(row.shown - 1) * TabRow.gap - TabRow.gap - TabRow.addSide
    }

    @Test(arguments: [480.0, 1180.0])
    func everySelectedTabIsShownAndTheEndStaysFree(_ window: Double) {
        for tabs in [3, 8, 30] {
            for selected in 0 ..< tabs {
                let row = Self.row(tabs, selected: selected, window: window)
                #expect(row.shows(selected), "\(tabs) tabs in \(window) pt: tab \(selected) not shown")
                #expect(row.width >= TabRow.narrowest && row.width <= TabRow.widest)
                #expect(row.first >= 0 && row.first + row.shown <= tabs)
                #expect(Self.leftOver(row, window: window) >= TabRow.stretch - 1e-9, "\(tabs) tabs in \(window) pt leave no stretch")
            }
        }
    }

    @Test func whatFitsAtEachWidth() {
        #expect(Self.row(3, selected: 0, window: 1180).shown == 3 && Self.row(3, selected: 0, window: 1180).width == 240)
        #expect(Self.row(8, selected: 0, window: 1180).shown == 8)
        #expect(Self.row(30, selected: 0, window: 1180).shown == 8)
        #expect(Self.row(3, selected: 0, window: 480).shown == 2)
        #expect(Self.row(30, selected: 29, window: 480).first == 28)
        #expect(Self.row(30, selected: 15, window: 1180).first == 12)
        #expect(Self.row(0, selected: 0, window: 480).shown == 0)
    }
}
