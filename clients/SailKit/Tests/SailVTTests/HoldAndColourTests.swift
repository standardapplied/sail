@testable import SailVT
import Synchronization
import Testing

/// Render holds (synchronized output, mode 2026) and colour changes, as a renderer meets them.
@Suite struct HoldAndColourTests {
    /// A clock a test moves by hand.
    final class Hands: Sendable {
        private let instant = Mutex(ContinuousClock.now)

        var now: ContinuousClock.Instant {
            instant.withLock { $0 }
        }

        func advance(_ duration: Duration) {
            instant.withLock { $0 += duration }
        }
    }

    @Test func aFrameDuringAHoldIsTheLastFinishedOneAndTheHoldsEndShowsTheNew() throws {
        let screen = try screen()
        _ = screen.feed(Array("old".utf8))
        #expect(try screen.frame().rows[0].text == "old")
        _ = screen.feed(Array("\u{1b}[?2026h\r\u{1b}[2Kne".utf8))
        #expect(try screen.frame().rows[0].text == "old")
        #expect(try screen.text() == "old")
        _ = screen.feed(Array("w\u{1b}[?2026l".utf8))
        let after = try screen.frame()
        #expect(after.rows[0].text == "new")
        #expect(after.changed != .nothing)
    }

    @Test func whatCameBeforeTheHoldInTheSameWriteIsInTheHeldFrame() throws {
        let screen = try screen()
        _ = try screen.frame()
        _ = screen.feed(Array("done\u{1b}[?2026h\r\u{1b}[2Khalf".utf8))
        #expect(try screen.frame().rows[0].text == "done")
    }

    @Test func aHoldLongerThanTheLimitIsEndedAndTheScreenGoesOn() throws {
        let hands = Hands()
        let screen = try Screen(size: GridSize(columns: 20, rows: 3), scrollbackBytes: 0, palette: .dark, now: { hands.now })
        _ = screen.feed(Array("old\u{1b}[?2026h\r\u{1b}[2Kstuck".utf8))
        #expect(try screen.frame().rows[0].text == "old")
        hands.advance(Screen.holdLimit - .milliseconds(1))
        #expect(try screen.frame().rows[0].text == "old")
        hands.advance(.milliseconds(1))
        #expect(try screen.frame().rows[0].text == "stuck")
        _ = screen.feed(Array("\u{1b}[?2026h".utf8))
        #expect(try screen.frame().rows[0].text == "stuck")
    }

    @Test func aResizeOrAReplayEndsAHold() throws {
        let screen = try screen()
        _ = screen.feed(Array("old\u{1b}[?2026h\r\u{1b}[2Knew".utf8))
        try screen.resize(GridSize(columns: 22, rows: 3))
        #expect(try screen.frame().rows[0].text == "new")
        _ = screen.feed(Array("\u{1b}[?2026h\r\u{1b}[2Kheld".utf8))
        screen.beginReplay()
        _ = screen.feed(Array("replayed".utf8))
        #expect(try screen.frame().rows[0].text == "replayed")
    }

    @Test(arguments: [
        ("\u{1b}]11;rgb:ff/00/00\u{1b}\\", RGB(0xFF0000), Palette.dark.foreground),
        ("\u{1b}]10;rgb:ff/ff/00\u{1b}\\", Palette.dark.background, RGB(0xFFFF00)),
    ])
    func aDefaultColourChangeRedrawsEveryCell(_ change: String, _ background: RGB, _ foreground: RGB) throws {
        let screen = try screen()
        _ = screen.feed(Array("R ".utf8))
        _ = try screen.frame()
        _ = screen.feed(Array(change.utf8))
        let frame = try screen.frame()
        #expect(frame.changed == .all)
        #expect(frame.colors.background == background && frame.colors.foreground == foreground)
        #expect(frame.rows.allSatisfy { $0.cells.allSatisfy { $0.background == background && $0.foreground == foreground } })
        _ = screen.feed(Array("\u{1b}]110\u{07}\u{1b}]111\u{07}".utf8))
        let reset = try screen.frame()
        #expect(reset.changed == .all)
        #expect(reset.rows[0].cells[1].background == Palette.dark.background)
        #expect(reset.rows[0].cells[0].foreground == Palette.dark.foreground)
    }

    @Test func aPaletteChangeRedrawsTheCellsInThatColour() throws {
        let screen = try screen()
        _ = screen.feed(Array("\u{1b}[31mr".utf8))
        _ = try screen.frame()
        _ = screen.feed(Array("\u{1b}]4;1;rgb:00/00/ff\u{07}".utf8))
        let frame = try screen.frame()
        #expect(frame.changed == .all)
        #expect(frame.rows[0].cells[0].foreground == RGB(0x0000FF))
    }

    /// T5: a replay starts from the palette's colours, whatever a program set before it.
    @Test func aReplayStartsFromThePalettesColours() throws {
        let screen = try screen()
        _ = screen.feed(Array("\u{1b}]10;rgb:12/34/56\u{07}\u{1b}]11;rgb:65/43/21\u{07}\u{1b}]12;rgb:01/02/03\u{07}".utf8))
        #expect(try screen.frame().colors.foreground == RGB(0x123456))
        screen.beginReplay()
        let colors = try screen.frame().colors
        #expect(colors == DefaultColors(foreground: Palette.dark.foreground, background: Palette.dark.background, cursor: Palette.dark.cursor))
    }

    @Test func aResizeAnswersAnInBandSizeReportAtOnce() throws {
        let screen = try screen()
        _ = screen.feed(Array("\u{1b}[?2048h".utf8))
        #expect(try screen.resize(GridSize(columns: 30, rows: 6)).reply == Array("\u{1b}[48;6;30;0;0t".utf8))
        #expect(try screen.setCellPixels(width: 10, height: 20).reply == Array("\u{1b}[48;6;30;120;300t".utf8))
        #expect(throws: ScreenError.self) { try screen.setCellPixels(width: Screen.largestCellPixels + 1, height: 1) }
    }

    @Test func blinkOverlineAndTheUnderlinesOwnColourAreDrawn() throws {
        let screen = try screen()
        _ = screen.feed(Array("\u{1b}[5mb\u{1b}[0;53mo\u{1b}[0;4;58:2::255:0:0mu\u{1b}[0;4;58;5;2mp".utf8))
        let cells = try screen.frame().rows[0].cells
        #expect(cells[0].attributes == .blink)
        #expect(cells[1].attributes == .overline)
        #expect(cells[2].underline == .single && cells[2].underlineColor == RGB(0xFF0000))
        #expect(cells[3].underlineColor == Palette.dark.ansi[2])
        #expect(cells[4].underlineColor == nil)
    }

    private func screen() throws -> Screen {
        try Screen(size: GridSize(columns: 20, rows: 3), scrollbackBytes: 0, palette: .dark)
    }
}

extension ScreenFrame.Row {
    var text: String {
        Screen.text(of: [self])
    }
}
