import Foundation
import SailVT
import Testing

@Suite struct ScreenTests {
    @Test func theLoadedLibraryIsThePinnedOne() throws {
        #expect(LibraryVersion.loaded == "1.0.1")
        try LibraryVersion.check(LibraryVersion.loaded)
    }

    @Test(arguments: ["2.0.0", "1.0.0", "0.9.9", "1.0", "1.0.1-dev", ""])
    func aLibraryOfAnotherMajorOrOlderThan101IsRefusedNamingIt(_ reported: String) {
        #expect(throws: ScreenError.unsupportedLibrary(found: reported, required: "1.0.1")) {
            try LibraryVersion.check(reported)
        }
        #expect("\(ScreenError.unsupportedLibrary(found: reported, required: "1.0.1"))".contains("libghostty-vt \(reported) is loaded"))
    }

    @Test(arguments: ["1.0.1", "1.0.2", "1.3.0"])
    func aLaterLibraryOfMajorOneIsTaken(_ reported: String) throws {
        try LibraryVersion.check(reported)
    }

    @Test func aGridOfNoCellsOrAPaletteOfTheWrongSizeIsRefused() {
        #expect(throws: ScreenError.self) { try Screen(size: GridSize(columns: 0, rows: 24), scrollbackBytes: 0, palette: .dark) }
        #expect(throws: ScreenError.self) { try Screen(size: GridSize(columns: 80, rows: 70000), scrollbackBytes: 0, palette: .dark) }
        var palette = Palette.light
        palette.ansi.removeLast()
        #expect(throws: ScreenError.invalid("a palette names 16 colours, not 15")) {
            try Screen(size: GridSize(columns: 80, rows: 24), scrollbackBytes: 0, palette: palette)
        }
    }

    @Test func aFrameNamesTheRowsThatChangedSinceTheFrameBefore() throws {
        let screen = try screen(columns: 10, rows: 3)
        #expect(try screen.frame().changed == .all)
        #expect(try screen.frame().changed == .nothing)
        _ = screen.feed(Array("\r\n\nx".utf8))
        let frame = try screen.frame()
        guard case let .rows(rows) = frame.changed else {
            Issue.record("expected changed rows, got \(frame.changed)")
            return
        }
        #expect(rows.contains(2))
        #expect(frame.rows[2].cells[0].grapheme == "x")
        #expect(frame.cursor.position == CellPoint(x: 1, y: 2))
        #expect(try screen.frame().changed == .nothing)
        try screen.resize(GridSize(columns: 12, rows: 4))
        let resized = try screen.frame()
        #expect(resized.changed == .all)
        #expect(resized.size == GridSize(columns: 12, rows: 4))
        #expect(resized.rows.count == 4 && resized.rows.allSatisfy { $0.cells.count == 12 })
    }

    @Test func readingTheTextLeavesTheNextFramesChanges() throws {
        let screen = try screen(columns: 10, rows: 3)
        _ = try screen.frame()
        _ = screen.feed(Array("hi".utf8))
        #expect(try screen.text() == "hi")
        #expect(try screen.frame().changed == .rows([0]))
    }

    @Test func aCellsColoursAreTheOnesItIsDrawnIn() throws {
        let screen = try screen(columns: 10, rows: 1)
        _ = screen.feed(Array("\u{1b}[31ma\u{1b}[7mb\u{1b}[0;2;31mc\u{1b}[0md".utf8))
        let cells = try screen.frame().rows[0].cells
        let red = Palette.dark.ansi[1]
        let background = Palette.dark.background
        #expect(cells[0].foreground == red && cells[0].background == background)
        #expect(cells[1].foreground == background && cells[1].background == red)
        #expect(cells[1].attributes == .inverse)
        #expect(cells[2].foreground == red.blended(toward: background, by: 0.45))
        #expect(cells[2].attributes == .dim)
        #expect(cells[3].foreground == Palette.dark.foreground && cells[3].attributes.isEmpty)
    }

    @Test func aDeviceStatusRequestIsAnsweredWithTheCursorPlace() throws {
        let screen = try screen(columns: 20, rows: 5)
        #expect(screen.feed(Array("ab\u{1b}[6n".utf8)).reply == Array("\u{1b}[1;3R".utf8))
    }

    @Test func aReplayIsHistory() throws {
        let screen = try screen(columns: 20, rows: 3)
        _ = screen.feed(Array("before".utf8))
        screen.beginReplay()
        #expect(try screen.text() == "")
        let replayed = screen.feed(Array("\u{1b}[6n\u{07}\u{1b}]52;c;aGk=\u{07}\u{1b}]2;named\u{07}again".utf8))
        #expect(replayed.reply.isEmpty)
        #expect(replayed.events == [.title("named")])
        screen.endReplay()
        let live = screen.feed(Array("\u{07}\u{1b}[6n".utf8))
        #expect(live.events == [.bell])
        #expect(live.reply == Array("\u{1b}[1;6R".utf8))
        #expect(try screen.text() == "again")
    }

    @Test func aClipboardWriteIsReportedUnlessThePolicyDeniesIt() throws {
        let screen = try screen(columns: 20, rows: 1)
        let osc52 = Array("\u{1b}]52;c;aGVsbG8=\u{07}".utf8)
        #expect(screen.feed(osc52).events == [.clipboardWrite("hello")])
        screen.setClipboardWrites(.deny)
        #expect(screen.feed(osc52).events.isEmpty)
    }

    @Test func aClipboardReadIsAnsweredEmpty() throws {
        let screen = try screen(columns: 20, rows: 1)
        #expect(screen.feed(Array("\u{1b}]52;c;?\u{07}".utf8)).reply == Array("\u{1b}]52;c;\u{07}".utf8))
    }

    @Test func aNotificationAndAWorkingDirectoryAreEvents() throws {
        let screen = try screen(columns: 20, rows: 1)
        let events = screen.feed(Array("\u{1b}]777;notify;Build;done\u{07}\u{1b}]7;file://box/srv\u{07}".utf8)).events
        #expect(events == [.notification(title: "Build", body: "done"), .workingDirectory("file://box/srv")])
    }

    @Test func aFocusReportGoesOnlyToAProgramThatAskedForThem() throws {
        let screen = try screen(columns: 20, rows: 1)
        #expect(screen.focus(false).isEmpty)
        _ = screen.feed(Array("\u{1b}[?1004h".utf8))
        #expect(screen.focus(false) == Array("\u{1b}[O".utf8))
        #expect(screen.focus(true) == Array("\u{1b}[I".utf8))
    }

    @Test func aSizeReportAnswersTheGridAndItsPixels() throws {
        let screen = try screen(columns: 30, rows: 4)
        try screen.setCellPixels(width: 9, height: 18)
        #expect(screen.feed(Array("\u{1b}[18t".utf8)).reply == Array("\u{1b}[8;4;30t".utf8))
        #expect(screen.feed(Array("\u{1b}[14t".utf8)).reply == Array("\u{1b}[4;72;270t".utf8))
    }

    @Test func aWordALineAndARangeAreSelected() throws {
        let screen = try screen(columns: 20, rows: 3)
        _ = screen.feed(Array("hello brave world\r\nsecond line".utf8))
        #expect(screen.selectedText() == nil)
        #expect(screen.select(.word(at: CellPoint(x: 7, y: 0))))
        #expect(screen.selectedText() == "brave")
        #expect(try screen.frame().rows[0].cells[6 ... 10].allSatisfy(\.selected))
        #expect(screen.select(.line(at: CellPoint(x: 2, y: 1))))
        #expect(screen.selectedText() == "second line")
        #expect(screen.select(.range(from: CellPoint(x: 12, y: 0), to: CellPoint(x: 5, y: 1))))
        #expect(screen.selectedText() == "world\nsecond")
        screen.clearSelection()
        #expect(screen.selectedText() == nil)
        #expect(!screen.select(.word(at: CellPoint(x: 25, y: 0))))
    }

    /// A selection extended by words or lines takes whole ones at both ends, in either direction.
    @Test func aSelectionExtendsByWholeWordsOrLines() throws {
        let screen = try screen(columns: 20, rows: 3)
        _ = screen.feed(Array("hello brave world\r\nsecond line".utf8))
        let brave = CellPoint(x: 7, y: 0)
        let line = CellPoint(x: 9, y: 1)
        for (from, to) in [(brave, line), (line, brave)] {
            #expect(screen.select(.words(from: from, to: to)))
            #expect(screen.selectedText() == "brave world\nsecond line")
            #expect(screen.select(.lines(from: from, to: to)))
            #expect(screen.selectedText() == "hello brave world\nsecond line")
        }
        #expect(!screen.select(.words(from: brave, to: CellPoint(x: 25, y: 0))))
    }

    @Test func theMouseIsReportedOnlyToAProgramThatTracksIt() throws {
        let screen = try screen(columns: 20, rows: 5)
        try screen.setCellPixels(width: 10, height: 20)
        let press = MouseInput(action: .press, button: .left, x: 35, y: 45)
        #expect(screen.encode(press).isEmpty)
        _ = screen.feed(Array("\u{1b}[?1000h\u{1b}[?1006h".utf8))
        #expect(screen.encode(press) == Array("\u{1b}[<0;4;3M".utf8))
        #expect(screen.encode(MouseInput(action: .release, button: .left, x: 35, y: 45)) == Array("\u{1b}[<0;4;3m".utf8))
    }

    @Test func aScrollIsTheWheelArrowsOrTheViewport() throws {
        let screen = try screen(columns: 20, rows: 3)
        try screen.setCellPixels(width: 10, height: 20)
        _ = screen.feed(Array((1 ... 10).map { "line \($0)" }.joined(separator: "\r\n").utf8))
        #expect(screen.scroll(lines: -2, x: 0, y: 0).isEmpty)
        #expect(try screen.text() == "line 6\nline 7\nline 8")
        screen.scrollToBottom()
        _ = screen.feed(Array("\u{1b}[?1049h\u{1b}[?1007h".utf8))
        #expect(screen.scroll(lines: 2, x: 0, y: 0) == Array("\u{1b}[B\u{1b}[B".utf8))
        _ = screen.feed(Array("\u{1b}[?1000h\u{1b}[?1006h".utf8))
        #expect(screen.scroll(lines: -1, x: 15, y: 25) == Array("\u{1b}[<64;2;2M".utf8))
    }

    private func screen(columns: Int, rows: Int) throws -> Screen {
        try Screen(size: GridSize(columns: columns, rows: rows), scrollbackBytes: 1 << 20, palette: .dark)
    }
}
