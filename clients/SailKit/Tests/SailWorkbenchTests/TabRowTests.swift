import SailWorkbench
import Testing

/// The strip's arithmetic: equal widths from 240 down to 120 pt, and past that only the tabs that
/// fit at 120.
@Suite struct TabRowTests {
    static func row(_ tabs: Int, in room: Double) -> TabRow {
        TabRow(tabs: tabs, room: room, narrowest: 120, widest: 240, gap: 4)
    }

    @Test func fewTabsAreWidestAndManyShareTheRoom() {
        #expect(Self.row(3, in: 800).width == 240 && Self.row(3, in: 800).shown == 3)
        #expect(Self.row(5, in: 800).width == (800 - 16) / 5.0 && Self.row(5, in: 800).shown == 5)
    }

    @Test func tabsPastWhatFitsAtTheNarrowestAreLeftOut() {
        let ten = Self.row(10, in: 800)
        #expect(ten.shown == 6)
        #expect(ten.width == (800 - 20) / 6.0)
        #expect(ten.width >= 120)
        #expect(Self.row(1, in: 50).shown == 1 && Self.row(1, in: 50).width == 120)
        #expect(Self.row(0, in: 800).shown == 0)
    }
}
