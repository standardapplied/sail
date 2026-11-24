import AppKit
import Foundation
import SailKitTestSupport
import SailLook
import SailRenderer
import SailSurface
@testable import SailTerminalView
import SailVT
import Synchronization
import Testing

@MainActor
@Suite(.serialized, .timeLimit(.minutes(1))) struct ViewTests {
    init() {
        ExitWatch.install()
    }

    /// Scenario 7: Ctrl-C sends 0x03; a dead key composes (Option-e marks ´, e commits é) and sends
    /// é once; an arrow in a program's cursor mode sends the application arrow.
    @Test func controlADeadKeyAndAnApplicationArrowReachTheSessionAsTheirBytes() throws {
        let harness = try Harness()
        harness.key("\u{3}", code: 0x08, ignoring: "c", flags: .control)
        #expect(harness.link.bytes == [0x03])
        harness.link.forget()
        harness.view.setMarkedText("´", selectedRange: NSRange(location: 1, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        harness.flush()
        #expect(harness.link.bytes.isEmpty)
        harness.view.insertText("é", replacementRange: NSRange(location: NSNotFound, length: 0))
        harness.flush()
        #expect(harness.link.bytes == Array("é".utf8))
        harness.link.forget()
        harness.link.output("\u{1b}[?1h")
        harness.key("\u{F700}", code: 0x7E)
        #expect(harness.link.bytes == Array("\u{1b}OA".utf8))
    }

    /// Scenario 8 and V4: while にほん is marked nothing is sent; it is drawn at the cursor; the
    /// candidate window's rect is the cursor's cell on screen; the commit sends にほん once.
    @Test func composingSendsNothingDrawsAtTheCursorAndCommitsOnce() async throws {
        let harness = try Harness()
        harness.link.output("$ ")
        await harness.tick()
        let before = try #require(try harness.view.snapshot())
        for marked in ["に", "にほ", "にほん"] {
            harness.view.setMarkedText(marked, selectedRange: NSRange(location: marked.utf16.count, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        }
        harness.flush()
        #expect(harness.link.bytes.isEmpty, "composing sent \(harness.link.bytes.count) bytes")
        #expect(harness.view.hasMarkedText())
        await harness.tick()
        let composing = try #require(try harness.view.snapshot())
        let metrics = harness.view.metrics
        let cursor = metrics.origin(of: CellPoint(x: 2, y: 0))
        let atCursor = (x: cursor.x ..< cursor.x + 6 * metrics.width, y: cursor.y ..< cursor.y + metrics.height)
        #expect(Self.differing(composing, before, in: atCursor) > 0, "the marked text is drawn in the cursor's cells")
        let all = (x: 0 ..< composing.width, y: 0 ..< composing.height)
        #expect(Self.differing(composing, before, in: all) == Self.differing(composing, before, in: atCursor), "and nowhere else")
        let cell = harness.view.cursorRect
        #expect(Int((Double(cell.minX) * metrics.scale).rounded()) == metrics.padding.width + 2 * metrics.width, "the cursor is at column 2")
        let expected = harness.window.convertToScreen(harness.view.convert(cell, to: nil))
        #expect(harness.view.firstRect(forCharacterRange: NSRange(location: 0, length: 3), actualRange: nil) == expected)
        harness.view.insertText("にほん", replacementRange: NSRange(location: NSNotFound, length: 0))
        harness.flush()
        #expect(harness.link.bytes == Array("にほん".utf8))
        #expect(!harness.view.hasMarkedText())
    }

    /// A commit the input method makes inside a key event (Return committing a composition, a dead
    /// key's second press) is sent once, as the committed text, and the key itself is not.
    @Test func aCommitInsideAKeyEventIsSentOnceAsItsText() throws {
        let harness = try Harness()
        harness.view.setMarkedText("にほ", selectedRange: NSRange(location: 2, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        harness.key("にほん", code: 0x00, ignoring: "a")
        #expect(harness.link.bytes == Array("にほん".utf8))
        #expect(!harness.view.hasMarkedText())
    }

    /// M1: a ⌘ chord no menu took reaches the view and sends nothing.
    @Test func aCommandChordNoMenuTookSendsNothing() throws {
        let harness = try Harness()
        harness.key("k", code: 0x28, flags: .command)
        harness.key("a", code: 0x00, flags: .command)
        #expect(harness.link.bytes.isEmpty)
    }

    /// A key release goes only to the encoder: it neither returns a scrolled-back view to the
    /// bottom nor sends anything to a program that did not ask for releases; a press does both.
    @Test func aReleaseLeavesTheViewWhereItWasScrolled() throws {
        let harness = try Harness()
        harness.link.output((1 ... 200).map { "line \($0)" }.joined(separator: "\r\n"))
        _ = harness.link.screen.scroll(lines: -50, x: 0, y: 0)
        let scrolled = try harness.link.screen.text()
        for type in [NSEvent.EventType.keyUp] {
            guard let event = NSEvent.keyEvent(
                with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: harness.window.windowNumber, context: nil, characters: "x", charactersIgnoringModifiers: "x", isARepeat: false, keyCode: 0x07
            ) else { continue }
            harness.window.sendEvent(event)
        }
        harness.flush()
        #expect(try harness.link.screen.text() == scrolled)
        #expect(harness.link.bytes.isEmpty)
        harness.key("x", code: 0x07)
        #expect(try harness.link.screen.text().contains("line 200"))
    }

    /// A new font's cell size reaches a program that asked for in-band size reports (mode 2048).
    @Test func aNewCellSizeIsReportedToTheProgram() throws {
        let harness = try Harness()
        harness.link.output("\u{1b}[?2048h")
        harness.link.forget()
        harness.view.increaseFontSize(nil)
        harness.flush()
        #expect(String(decoding: harness.link.bytes, as: UTF8.self).hasPrefix("\u{1b}[48;"))
    }

    /// The pointer moving with no button reaches a program that asked for every motion (1003).
    /// Motion with no button is reported once per cell entered, and only to a program that asked
    /// for every motion.
    @Test func hoverIsReportedOncePerCellAndOnlyWhenAsked() async throws {
        let harness = try Harness()
        await harness.tick()
        let metrics = harness.view.metrics
        func move(_ column: Int, nudge: Double = 0) -> String {
            let before = harness.link.bytes.count
            let origin = metrics.origin(of: CellPoint(x: column, y: 1))
            let point = NSPoint(
                x: (Double(origin.x) + Double(metrics.width) / 2) / metrics.scale + nudge,
                y: (Double(origin.y) + Double(metrics.height) / 2) / metrics.scale
            )
            guard let event = NSEvent.mouseEvent(
                with: .mouseMoved, location: harness.view.convert(point, to: nil), modifierFlags: [],
                timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: harness.window.windowNumber, context: nil, eventNumber: 0,
                clickCount: 0, pressure: 0
            ) else { return "" }
            harness.view.mouseMoved(with: event)
            harness.flush()
            return String(decoding: harness.link.bytes.dropFirst(before), as: UTF8.self)
        }
        #expect(move(2).isEmpty, "no program asked")
        harness.link.output("\u{1b}[?1003h\u{1b}[?1006h")
        #expect(move(4) == "\u{1b}[<35;5;2M", "a new cell")
        #expect(move(4, nudge: 1).isEmpty, "the same cell")
        #expect(move(6) == "\u{1b}[<35;7;2M", "another cell")
        harness.mouse(.leftMouseDown, column: 6, row: 1)
        harness.mouse(.leftMouseDragged, column: 8, row: 1)
        harness.mouse(.leftMouseUp, column: 8, row: 1)
        harness.flush()
        #expect(move(6) == "\u{1b}[<35;7;2M", "the cell hovered before a drag, entered again after it")
    }

    /// W5: a change of the view's size that keeps the grid still tells the session, so the
    /// session's 60 ms hold counts from the view's last change, not the grid's.
    @Test func aSizeChangeThatKeepsTheGridStillRestartsTheSessionsHold() throws {
        let harness = try Harness()
        harness.flush()
        let grid = try #require(harness.view.grid)
        let told = harness.link.resizes.count
        var frame = harness.view.frame
        frame.size.width += 1
        harness.view.frame = frame
        harness.flush()
        #expect(harness.view.grid == grid, "a point more made another grid")
        #expect(harness.link.resizes.count == told + 1)
        #expect(harness.link.resizes.last == grid)
    }

    /// Scenario 21: the first click on a view that is not focused only focuses it: a program that
    /// tracks the mouse gets no report of it, and nothing is selected; the next click is reported.
    @Test func theFirstClickOnAnUnfocusedViewOnlyFocusesIt() throws {
        let harness = try Harness()
        harness.link.output("\u{1b}[?1000h\u{1b}[?1006habc")
        harness.window.makeFirstResponder(nil)
        harness.flush()
        harness.link.forget()
        harness.mouse(.leftMouseDown, column: 1, row: 0)
        harness.mouse(.leftMouseDragged, column: 2, row: 0)
        harness.mouse(.leftMouseUp, column: 2, row: 0)
        harness.flush()
        #expect(harness.window.firstResponder === harness.view)
        #expect(harness.link.bytes.isEmpty, "the focusing click was reported: \(harness.link.bytes)")
        #expect(harness.link.screen.selectedText() == nil)
        harness.link.forget()
        harness.mouse(.leftMouseDown, column: 1, row: 0)
        harness.mouse(.leftMouseUp, column: 1, row: 0)
        harness.flush()
        #expect(String(decoding: harness.link.bytes, as: UTF8.self) == "\u{1b}[<0;2;1M\u{1b}[<0;2;1m")
    }

    /// M3: a view draws a session for its life. Detaching stops its listening to the session's
    /// screen, and the session cannot be attached again; a view that is dropped stops listening too.
    @Test func aSessionIsDrawnByOneViewForLife() throws {
        let harness = try Harness()
        #expect(!TerminalView.canAttach(harness.link))
        #expect(harness.link.listened)
        harness.view.detach()
        #expect(!harness.link.listened)
        #expect(!TerminalView.canAttach(harness.link))
        let other = try FakeLink()
        #expect(TerminalView.canAttach(other))
        try autoreleasepool {
            var dropped: TerminalView? = try TerminalView(fonts: TerminalFonts(directory: Harness.fonts))
            dropped?.attach(other)
            #expect(other.listened)
            dropped = nil
        }
        #expect(!other.listened, "a dropped view still listened to its session's screen")
    }

    /// Attaching a session a view already drew stops the process with the precondition's sentence,
    /// rather than drawing a screen that never changes.
    @Test func attachingASessionAViewDrewStopsTheProcess() async {
        await #expect(processExitsWith: .failure) {
            try await MainActor.run {
                let harness = try Harness()
                let other = try TerminalView(fonts: TerminalFonts(directory: Harness.fonts))
                other.attach(harness.link)
            }
        }
    }

    /// The view finds `src/a.ts:4` as its link, as the app finds a path, wherever the row holds it.
    static func linksPaths(_ view: TerminalView) {
        view.linkIn = { text, column in
            guard let range = text.range(of: "src/a.ts:4") else { return nil }
            let start = text.distance(from: text.startIndex, to: range.lowerBound), link = start ..< start + 10
            return link.contains(column - 1) ? link : nil
        }
    }

    /// ⌘ held over a link underlines exactly its cells, at every font size, in both palettes and
    /// right to left; without ⌘, or over text that is no link, nothing is underlined. The palette
    /// is set before the first frame: one landing later changes the rows under the link, and the
    /// link is looked for again with the keys really held, which in a test are none.
    @Test(arguments: [("light", false), ("dark", false), ("light", true)])
    func commandOverALinkUnderlinesExactlyItsCells(_ palette: String, _ rightToLeft: Bool) async throws {
        for points in FontLadder.sizes {
            let harness = try Harness()
            harness.view.recolour(palette == "dark" ? .dark : .light)
            harness.flush()
            harness.view.userInterfaceLayoutDirection = rightToLeft ? .rightToLeft : .leftToRight
            harness.view.setFontSize(points)
            harness.link.output("see src/a.ts:4 here")
            Self.linksPaths(harness.view)
            await harness.tick()
            let before = try #require(try harness.view.snapshot())
            harness.mouse(.mouseMoved, column: 6, row: 0)
            #expect(harness.view.shownLink == nil, "\(points) pt: underlined without ⌘")
            harness.mouse(.mouseMoved, column: 6, row: 0, flags: .command)
            #expect(harness.view.shownLink == OverlayLink(row: 0, columns: 4 ..< 14), "\(points) pt: \(String(describing: harness.view.shownLink))")
            await harness.tick()
            let linked = try #require(try harness.view.snapshot())
            let metrics = harness.view.metrics, origin = metrics.origin(of: CellPoint(x: 4, y: 0))
            let cells = (x: origin.x ..< origin.x + 10 * metrics.width, y: origin.y ..< origin.y + metrics.height)
            let all = (x: 0 ..< linked.width, y: 0 ..< linked.height)
            let inside = Self.differing(linked, before, in: cells)
            #expect(inside > 0 && Self.differing(linked, before, in: all) == inside, "\(points) pt: the underline is not exactly the link's cells")
            harness.mouse(.mouseMoved, column: 16, row: 0, flags: .command)
            #expect(harness.view.shownLink == nil, "\(points) pt: a word that is no link was underlined")
        }
    }

    /// A path soft-wrapped across two rows is one line: the link sees it whole, and ⌘ over either
    /// row underlines its cells on both.
    @Test func aPathWrappedAcrossRowsIsReadWhole() async throws {
        let harness = try Harness(columns: 20, rows: 4, size: NSSize(width: 200, height: 160))
        let columns = try #require(harness.view.grid?.columns)
        try #require(columns > 10 && columns < 32, "a grid of \(columns) columns wraps the path nowhere")
        harness.link.output("error in src/very/long/name.ts:9 here")
        var seen: [String] = []
        harness.view.linkIn = { text, column in
            seen.append(text)
            guard let range = text.range(of: "src/very/long/name.ts:9") else { return nil }
            let start = text.distance(from: text.startIndex, to: range.lowerBound), link = start ..< start + 23
            return link.contains(column - 1) ? link : nil
        }
        await harness.tick()
        harness.mouse(.mouseMoved, column: 1, row: 1, flags: .command)
        #expect(seen.last?.hasPrefix("error in src/very/long/name.ts:9 here") == true, "the line was not read whole: \(seen.last ?? "")")
        #expect(harness.view.shownLink == OverlayLink(spans: [OverlayLink.Span(row: 0, columns: 9 ..< columns), OverlayLink.Span(row: 1, columns: 0 ..< 32 - columns)]),
                "\(String(describing: harness.view.shownLink))")
    }

    /// A web address is a link of its own, ahead of any the app finds: ⌘ over any row of one
    /// soft-wrapped across rows underlines it on each, and a ⌘-click there opens it whole in the
    /// browser, never the app's own link.
    @Test func aWebAddressWrappedAcrossRowsOpensWhole() async throws {
        let harness = try Harness(columns: 20, rows: 8, size: NSSize(width: 200, height: 320))
        let grid = try #require(harness.view.grid)
        let address = "https://claude.ai/oauth/authorize?code=true&client_id=5c2e7a90&state=f3Kp9xR2mT7v"
        let rows = (address.count + grid.columns - 1) / grid.columns
        try #require(rows > 2 && rows + 2 <= grid.rows, "a grid of \(grid.columns) by \(grid.rows) holds the address on \(rows) rows")
        var opened: [URL] = [], hosts: [String] = []
        harness.view.openURL = { opened.append($0) }
        harness.view.linkIn = { _, _ in 0 ..< 3 }
        harness.view.onLink = { text, _ in hosts.append(text) }
        harness.link.output("Open:\r\n" + address + "\r\n> ")
        await harness.tick()
        harness.mouse(.mouseMoved, column: 0, row: rows, flags: .command)
        let spans = (0 ..< rows).map { row in OverlayLink.Span(row: 1 + row, columns: 0 ..< (row == rows - 1 ? address.count - row * grid.columns : grid.columns)) }
        #expect(harness.view.shownLink == OverlayLink(spans: spans), "\(String(describing: harness.view.shownLink))")
        harness.mouse(.leftMouseDown, column: 0, row: rows, flags: .command)
        harness.mouse(.leftMouseUp, column: 0, row: rows, flags: .command)
        #expect(opened == [try #require(URL(string: address))] && hosts.isEmpty, "opened \(opened), the app's own link \(hosts)")
    }

    /// A program's own link (OSC 8) is its words, opening the address it names: ⌘ over them
    /// underlines exactly them, a ⌘-click opens that address, and the words beside it are none.
    @Test func aProgramsOwnLinkOpensTheAddressItNames() async throws {
        let harness = try Harness()
        var opened: [URL] = []
        harness.view.openURL = { opened.append($0) }
        harness.link.output("\u{1b}]8;;https://chatgpt.com/#settings/Security\u{1b}\\ChatGPT security settings\u{1b}]8;;\u{1b}\\ here")
        await harness.tick()
        harness.mouse(.mouseMoved, column: 9, row: 0, flags: .command)
        #expect(harness.view.shownLink == OverlayLink(row: 0, columns: 0 ..< 25), "\(String(describing: harness.view.shownLink))")
        harness.mouse(.leftMouseDown, column: 9, row: 0, flags: .command)
        harness.mouse(.leftMouseUp, column: 9, row: 0, flags: .command)
        #expect(opened == [try #require(URL(string: "https://chatgpt.com/#settings/Security"))])
        harness.mouse(.mouseMoved, column: 27, row: 0, flags: .command)
        #expect(harness.view.shownLink == nil, "the words after the link were underlined")
    }

    /// R2-5: a line whose start has scrolled above the screen is never read from its tail, nor one
    /// going on below the screen from its head: no link lies in a word the edge cuts, even one
    /// ending short of the edge (a path's closing `.` left out), so ⌘ over it underlines nothing
    /// and a ⌘-click opens nothing.
    @Test func aPathCutByTheScreensEdgeIsNoLink() async throws {
        let harness = try Harness(columns: 20, rows: 4, size: NSSize(width: 200, height: 160))
        let grid = try #require(harness.view.grid)
        let path = "src/very/long/folder/name.ts:9 x", wraps = (path.count + grid.columns - 1) / grid.columns
        try #require(wraps > 1, "a grid of \(grid.columns) columns wraps the path nowhere")
        var opened: [String] = []
        harness.view.linkIn = { text, column in
            let end = text.firstIndex(of: " ").map { text.distance(from: text.startIndex, to: $0) } ?? text.count
            return column - 1 < end - 1 ? 0 ..< end - 1 : nil
        }
        harness.view.onLink = { text, _ in opened.append(text) }
        harness.link.output(String(repeating: "pad\r\n", count: grid.rows) + path + String(repeating: "\r\nz", count: grid.rows))
        _ = harness.link.screen.scroll(lines: -1, x: 0, y: 0)
        await harness.tick()
        try #require(harness.view.lastFrame?.rows.first?.continues == true, "the path's start did not scroll above the screen")
        harness.mouse(.mouseMoved, column: 1, row: 0, flags: .command)
        #expect(harness.view.shownLink == nil, "the tail of a cut path was underlined")
        harness.mouse(.leftMouseDown, column: 1, row: 0, flags: .command)
        harness.mouse(.leftMouseUp, column: 1, row: 0, flags: .command)
        harness.link.screen.scrollToBottom()
        _ = harness.link.screen.scroll(lines: -(grid.rows + wraps - 1), x: 0, y: 0)
        await harness.tick()
        try #require(harness.view.lastFrame?.rows.last?.wrapped == true && harness.view.lastFrame?.rows.first?.continues == false, "the path's head is not on the last row")
        harness.mouse(.mouseMoved, column: 1, row: grid.rows - 1, flags: .command)
        #expect(harness.view.shownLink == nil, "the head of a cut path was underlined")
        harness.mouse(.leftMouseDown, column: 1, row: grid.rows - 1, flags: .command)
        harness.mouse(.leftMouseUp, column: 1, row: grid.rows - 1, flags: .command)
        #expect(opened.isEmpty, "a cut path was opened: \(opened)")
    }

    /// A ⌘-click on a link, in the text drawn, goes to the link alone: nothing is selected and a
    /// program that tracks the mouse gets nothing, its drag and release included; the same click
    /// without ⌘ is the program's, as before.
    @Test func aCommandClickOnALinkGoesToTheLinkAlone() async throws {
        let harness = try Harness()
        harness.link.output("\u{1b}[?1000h\u{1b}[?1006hsee src/a.ts:4 here")
        Self.linksPaths(harness.view)
        var opened: [(String, Int)] = []
        harness.view.onLink = { opened.append(($0, $1)) }
        await harness.tick()
        harness.link.forget()
        harness.mouse(.leftMouseDown, column: 6, row: 0, flags: .command)
        harness.mouse(.leftMouseDragged, column: 8, row: 0, flags: .command)
        harness.mouse(.leftMouseUp, column: 8, row: 0, flags: .command)
        harness.flush()
        #expect(opened.map(\.1) == [7] && opened.first?.0.hasPrefix("see src/a.ts:4 here") == true, "\(opened)")
        #expect(harness.link.bytes.isEmpty, "the program got the ⌘-click: \(harness.link.bytes)")
        #expect(harness.link.screen.selectedText() == nil)
        harness.mouse(.leftMouseDown, column: 6, row: 0)
        harness.mouse(.leftMouseUp, column: 6, row: 0)
        harness.flush()
        #expect(opened.count == 1 && String(decoding: harness.link.bytes, as: UTF8.self) == "\u{1b}[<0;7;1M\u{1b}[<0;7;1m")
    }

    private static func differing(_ one: RenderedImage, _ other: RenderedImage, in area: (x: Range<Int>, y: Range<Int>)) -> Int {
        var count = 0
        for y in area.y where y < one.height {
            for x in area.x where x < one.width {
                let base = (y * one.width + x) * 4
                if one.bgra[base ..< base + 3] != other.bgra[base ..< base + 3] { count += 1 }
            }
        }
        return count
    }

    /// Scenario 9: a drag across a wrapped line and ⌘C put the text on the pasteboard with the
    /// wrapped rows joined.
    @Test func aDragAcrossAWrappedLineCopiesItJoined() async throws {
        let harness = try Harness()
        let metrics = harness.view.metrics
        harness.window.setContentSize(NSSize(
            width: Double(2 * metrics.padding.width + 10 * metrics.width) / metrics.scale,
            height: Double(2 * metrics.padding.height + 4 * metrics.height) / metrics.scale
        ))
        harness.flush()
        #expect(harness.view.grid == GridSize(columns: 10, rows: 4))
        harness.link.output("abcdefghijklmno")
        await harness.tick()
        let grid = try #require(harness.view.grid)
        harness.mouse(.leftMouseDown, column: 0, row: 0)
        harness.mouse(.leftMouseDragged, column: 2, row: 1)
        harness.mouse(.leftMouseDragged, column: 4, row: 1)
        harness.mouse(.leftMouseUp, column: 4, row: 1)
        #expect(harness.link.screen.selectedText() != nil, "the drag selected")
        harness.view.copy(nil)
        let copied = harness.pasteboard.string(forType: .string)
        #expect(copied == "abcdefghijklmno", "copied \(copied ?? "nothing") from a \(grid) grid")
    }

    /// Scenario 10: three lines pasted into a shell without mode 2004 are asked about; Cancel
    /// sends nothing; Paste sends what the paste encoder makes of them.
    @Test func aPasteThatCouldRunCommandsIsAskedAbout() async throws {
        let harness = try Harness()
        let asker = Asker()
        harness.view.confirmPaste = { lines, answer in asker.asked(lines, answer) }
        let text = "one\ntwo\nthree\n"
        harness.pasteboard.clearContents()
        harness.pasteboard.setString(text, forType: .string)
        harness.view.paste(nil)
        let first = await asker.next()
        #expect(first.lines == 3)
        first.answer(false)
        harness.flush()
        #expect(harness.link.bytes.isEmpty)
        harness.view.paste(nil)
        let second = await asker.next()
        second.answer(true)
        harness.flush()
        let expected = try Screen(size: GridSize(columns: 80, rows: 24), scrollbackBytes: 0, palette: .dark).paste(text, confirmed: true)
        #expect(PasteOutcome.send(harness.link.bytes) == expected)
    }

    @Test func theDefaultConfirmationIsASheetOnTheWindow() throws {
        let harness = try Harness()
        harness.view.presentPasteSheet(lines: 3) { _ in }
        let sheet = try #require(harness.window.attachedSheet)
        func texts(_ view: NSView?) -> [String] {
            guard let view else { return [] }
            return ((view as? NSTextField).map { [$0.stringValue] } ?? []) + view.subviews.flatMap(texts)
        }
        #expect(texts(sheet.contentView).contains("Paste 3 lines?"))
        func buttons(_ view: NSView?) -> [NSButton] {
            guard let view else { return [] }
            return ((view as? NSButton).map { [$0] } ?? []) + view.subviews.flatMap(buttons)
        }
        let keys = buttons(sheet.contentView).map { "\($0.title)=\($0.keyEquivalent.unicodeScalars.map(\.value))" }
        let returnKey = buttons(sheet.contentView).filter { $0.keyEquivalent == "\r" }.map(\.title)
        #expect(returnKey.isEmpty, "no button answers Return, so none is drawn as the system-accent default; the buttons' keys: \(keys)")
        harness.window.endSheet(sheet, returnCode: .alertSecondButtonReturn)
    }

    /// Escape cancels the paste sheet.
    @Test func escapeCancelsThePasteSheet() async throws {
        let harness = try Harness()
        let answers = Mutex<[Bool]>([])
        harness.view.presentPasteSheet(lines: 2) { answer in answers.withLock { $0.append(answer) } }
        let sheet = try #require(harness.window.attachedSheet)
        let escape = try #require(NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: sheet.windowNumber,
            context: nil, characters: "\u{1b}", charactersIgnoringModifiers: "\u{1b}", isARepeat: false, keyCode: 0x35
        ))
        NSApp.sendEvent(escape)
        #expect(await harness.until { harness.window.attachedSheet == nil && answers.withLock { $0 } == [false] }, "Escape did not cancel the sheet")
    }

    /// ⌘V while scrolled back returns the view to the bottom and draws it there, the screen's lock
    /// taken only on the input queue.
    @Test func aPasteWhileScrolledBackDrawsTheBottom() async throws {
        let harness = try Harness()
        await harness.drainMain()
        _ = harness.link.screen.feed(Array((1 ... 200).map { "line \($0)" }.joined(separator: "\r\n").utf8))
        await harness.tick()
        _ = harness.link.screen.scroll(lines: -50, x: 0, y: 0)
        harness.view.needFrame()
        await harness.tick()
        await harness.tick()
        #expect(harness.clock.isPaused)
        #expect(!harness.view.visibleText.contains("line 200"))
        harness.pasteboard.clearContents()
        harness.pasteboard.setString("x", forType: .string)
        harness.view.paste(nil)
        harness.flush()
        #expect(await harness.until { harness.view.visibleText.contains("line 200") }, "the paste did not draw the bottom")
        #expect(harness.link.bytes.contains(UInt8(ascii: "x")))
    }

    /// Scenario 13: a narrower window gives the session a narrower grid at once and the screen
    /// reflows; the session's own hold sends the host one size later (SailKit's
    /// `aSizeIsSentOnceItHasHeldForSixtyMilliseconds`).
    @Test func aNarrowerWindowResizesTheSessionAtOnce() throws {
        let harness = try Harness()
        let wide = try #require(harness.view.grid)
        harness.window.setContentSize(NSSize(width: 500, height: 500))
        harness.flush()
        let narrow = try #require(harness.view.grid)
        #expect(narrow.columns < wide.columns)
        #expect(harness.link.resizes.last == narrow)
        #expect(try harness.link.screen.frame().size == narrow)
    }

    /// Scenario 14: ⌘+ three times is 18 pt and ⌘0 is 15 pt, the grid recomputed each time.
    @Test func theFontLadderRecomputesTheGrid() throws {
        let harness = try Harness()
        let start = try #require(harness.view.grid)
        var height = harness.view.metrics.height
        var last = start
        for size in [16.0, 17, 18] {
            harness.view.increaseFontSize(nil)
            harness.flush()
            let now = try #require(harness.view.grid)
            #expect(harness.view.fontPoints == size)
            #expect(harness.view.metrics.height > height, "the cell grew at \(size) pt")
            #expect(now.columns <= last.columns && now.rows <= last.rows, "the grid at \(size) pt fits the bigger cell")
            #expect(harness.link.resizes.last == now)
            height = harness.view.metrics.height
            last = now
        }
        #expect(last.columns < start.columns && last.rows < start.rows)
        harness.view.resetFontSize(nil)
        harness.flush()
        #expect(harness.view.fontPoints == 15)
        #expect(harness.link.resizes.last == harness.view.grid)
        harness.view.decreaseFontSize(nil)
        #expect(harness.view.fontPoints == 14)
    }

    /// Scenario 17: losing focus sends the focus-out report to a program that asked for them, and
    /// the cursor is drawn hollow.
    @Test func losingFocusIsReportedAndHollowsTheCursor() throws {
        let harness = try Harness()
        harness.link.output("\u{1b}[?1004h")
        #expect(harness.view.focused)
        harness.link.forget()
        harness.window.makeFirstResponder(nil)
        harness.flush()
        #expect(harness.link.bytes == Array("\u{1b}[O".utf8))
        #expect(!harness.view.overlay.focused)
        harness.window.makeFirstResponder(harness.view)
        harness.flush()
        #expect(harness.link.bytes == Array("\u{1b}[O\u{1b}[I".utf8))
    }

    /// V1: output resumes a paused display link, and the first refresh after draws it. The yield
    /// lets the view's own pending work (its first layout) run first, so only the output can resume
    /// the link after the pause.
    @Test func outputIsDrawnOnTheFirstRefreshAfterIt() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused, "nothing changed, so the link paused")
        harness.link.output("hello")
        await harness.clock.nextResume()
        #expect(!harness.clock.isPaused)
        #expect(harness.view.visibleText.contains("hello"), "drawn as it arrived, before the next refresh")
    }

    /// Whether the display tells presentation times outlives a latency reset: a step's state after a
    /// reset, before the next present, still lists its frames where the display does tell times.
    @Test func presentsToldWithATimeAreCountedOverTheViewsLife() {
        let marks = ViewMarks()
        marks.committed(frame: 1, read: 1.0, target: 1.016)
        marks.presented(frame: 1, at: 0)
        #expect(marks.presentsToldWithATime == 0, "a present told with no time")
        marks.committed(frame: 2, read: 1.02, target: 1.033)
        marks.presented(frame: 2, at: 1.04)
        marks.reset()
        #expect(marks.presentsToldWithATime == 1 && marks.figures.reportedPresents == 0)
    }

    /// A key whose echo came in the second of two changes one wake carries is put to that change:
    /// the wake marks its newest change too, so the echo is shown by the frame that reads them,
    /// never held for a later, unrelated output.
    @Test func aKeysEchoFoldedIntoAWakeIsPutToTheNewestChange() throws {
        let marks = ViewMarks()
        let queue = DispatchQueue(label: "sh.standardapplied.sail.tests.wake")
        let drew = DispatchSemaphore(value: 0)
        let wake = ScreenWake(on: queue, marks: marks) { drew.signal() }
        queue.suspend()
        wake.changed(at: 10.0)
        marks.keyWritten(event: 10.4, sent: 10.5, interval: marks.keyBegan())
        wake.changed(at: 10.6)
        queue.resume()
        try #require(drew.wait(timeout: .now() + 2) == .success)
        marks.committed(frame: 1, read: 10.7, target: 10.71)
        marks.completed(frame: 1, ok: true)
        let figures = marks.figures
        #expect(figures.wakes == 1 && figures.changes == 2)
        #expect(figures.keyToEchoEstimated.count == 1, "the key's echo was not put to the wake's newest change")
    }

    /// A wake records when the session's thread began to hand it to the queue and when the hand-off
    /// had returned, one stamp each side of the hand-off itself: a late hand-off and a late take
    /// are told apart.
    @Test func aWakeStampsItsHandOffOnBothSides() throws {
        let marks = ViewMarks()
        let queue = DispatchQueue(label: "sh.standardapplied.sail.tests.posted")
        let drew = DispatchSemaphore(value: 0)
        let handedAt = Mutex(0.0)
        let wake = ScreenWake(handingOver: { block in
            handedAt.withLock { $0 = CACurrentMediaTime() }
            queue.async(execute: block)
        }, marks: marks) { drew.signal() }
        queue.suspend()
        wake.changed(at: CACurrentMediaTime() - 0.05)
        queue.resume()
        try #require(drew.wait(timeout: .now() + 2) == .success)
        let figures = marks.figures
        let handing = try #require(figures.longestToToldHanding), handed = try #require(figures.longestToToldHanded)
        let at = handedAt.withLock { $0 }
        #expect(handing <= at && at <= handed, "the stamps \(handing) and \(handed) do not bracket the hand-off at \(at)")
    }

    /// A wake the queue takes before its hand-off's return is stamped keeps that stamp unknown: the
    /// take's time never stands in for it.
    @Test func aWakeTakenBeforeItsHandOffReturnsLeavesThatStampUnknown() throws {
        let marks = ViewMarks()
        let wake = ScreenWake(handingOver: { $0() }, marks: marks) {}
        wake.changed(at: CACurrentMediaTime() - 0.05)
        let figures = marks.figures
        #expect(figures.longestToToldHanding != nil)
        #expect(figures.longestToToldHanded == nil, "the take's time stood in for the hand-off's return")
    }

    /// Changes the main thread has not yet taken make one wake, which carries the oldest of them:
    /// output to shown is measured from the change that waited longest, never the newest.
    @Test func oneWakeCarriesTheOldestChangeItWasPostedFor() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        let link = harness.link
        let before = CACurrentMediaTime()
        let between = Moment()
        onItsOwnThread {
            link.output("first")
            between.mark()
            link.output(" second")
        }
        await harness.drainMain()
        let figures = harness.view.latency
        #expect(figures.wakes == 1 && figures.changes == 2, "\(figures.wakes) wakes for \(figures.changes) changes")
        let oldest = try #require(figures.longestToTold).start
        #expect(oldest >= before - 0.001 && oldest < between.time, "the wake carried a change at \(oldest), not the first")
        await harness.drawn()
    }

    /// A key that changes nothing on screen leaves an idle view idle, so its echo is drawn the
    /// moment it arrives rather than at the next display callback.
    @Test func aKeysEchoIsDrawnAsItArrives() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        let refreshes = harness.view.latency.refresh.count
        harness.key("e", code: 0x0E)
        #expect(await harness.until { harness.view.latency.refresh.count > refreshes }, "the key's hop read the screen")
        await harness.drawn()
        try #require(harness.clock.isPaused, "the key itself asked for no frame")
        harness.link.output("e")
        await harness.clock.nextResume()
        #expect(harness.view.visibleText.contains("e"), "drawn as it arrived, before the next refresh")
    }

    /// D1, D2: a drawable withheld holds no call on the main thread. Every ask runs off it; output
    /// meanwhile is owed, and the frame the returned drawable brings draws all of it, once.
    @Test func aWithheldDrawableNeverHoldsTheMainThread() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        let gate = DispatchSemaphore(value: 0)
        let onMain = Mutex<[Bool]>([])
        harness.view.nextDrawable = { layer in
            onMain.withLock { $0.append(Thread.isMainThread) }
            _ = gate.wait(timeout: .now() + 2)
            return layer.nextDrawable()
        }
        let committed = Self.committed(harness.view)
        let began = ContinuousClock.now
        harness.link.output("withheld")
        harness.view.drawNowIfIdle()
        harness.link.output(" and more")
        harness.view.drawNowIfIdle()
        #expect(ContinuousClock.now - began < .milliseconds(50), "the main thread waited for the drawable")
        #expect(await harness.until { !onMain.withLock { $0 }.isEmpty }, "the drawable was asked for, on the view's own thread")
        let served = ContinuousClock.now
        await harness.drainMain()
        #expect(ContinuousClock.now - served < .milliseconds(500), "the main queue was held while the drawable was withheld (2 s)")
        #expect(Self.committed(harness.view) == committed, "nothing drew without a drawable")
        #expect(onMain.withLock { $0 } == [false], "one ask, off the main thread")
        harness.view.nextDrawable = { $0.nextDrawable() }
        gate.signal()
        await harness.drawn()
        #expect(Self.committed(harness.view) == committed + 1, "the returned drawable drew one frame")
        #expect(harness.view.visibleText.contains("withheld and more"), "with all the output owed meanwhile")
    }

    /// A window off screen asks for no drawable; seen again, it asks and draws what it owes. An ask
    /// that brings none (its second over) asks again at once, with no fixed wait.
    @Test func anOffScreenWindowAsksWhenSeenAndANilAskAsksAgain() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        harness.view.isOnScreen = { _ in false }
        let asks = Mutex(0)
        harness.view.nextDrawable = { layer in
            asks.withLock { $0 += 1; return $0 } < 3 ? nil : layer.nextDrawable()
        }
        harness.link.output("owed")
        harness.view.drawNowIfIdle()
        await harness.drawn()
        #expect(asks.withLock { $0 } == 0, "off screen, nothing asked")
        harness.view.isOnScreen = { _ in true }
        NotificationCenter.default.post(name: NSWindow.didChangeOcclusionStateNotification, object: harness.window)
        while !harness.view.visibleText.contains("owed") {
            try await Task.sleep(for: .milliseconds(1))
        }
        #expect(asks.withLock { $0 } == 3, "two asks brought none and were asked again")
    }

    /// m1: an idle view, or a hidden one, asks for no drawable when the window is seen again, and
    /// an answer of none asks again only while a frame is owed by a view that is shown.
    @Test func onlyAShownViewThatOwesAFrameAsksForADrawable() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        let asks = Mutex(0)
        let gate = DispatchSemaphore(value: 0)
        harness.view.nextDrawable = { _ in
            asks.withLock { $0 += 1 }
            _ = gate.wait(timeout: .now() + 2)
            return nil
        }
        NotificationCenter.default.post(name: NSWindow.didChangeOcclusionStateNotification, object: harness.window)
        #expect(!harness.view.drawingState.contains("; awaiting a drawable"), "an idle view asked when the window was seen")
        harness.view.isOnScreen = { _ in false }
        harness.link.output("owed")
        harness.view.drawNowIfIdle()
        harness.view.isHidden = true
        harness.view.isOnScreen = { _ in true }
        NotificationCenter.default.post(name: NSWindow.didChangeOcclusionStateNotification, object: harness.window)
        #expect(!harness.view.drawingState.contains("; awaiting a drawable"), "a hidden view asked when the window was seen")
        harness.view.isHidden = false
        NotificationCenter.default.post(name: NSWindow.didChangeOcclusionStateNotification, object: harness.window)
        #expect(await harness.until { asks.withLock { $0 } == 1 }, "a shown view that owes a frame asked")
        harness.view.isHidden = true
        gate.signal()
        #expect(await harness.until { !harness.view.drawingState.contains("; awaiting a drawable") })
        #expect(asks.withLock { $0 } == 1, "an answer of none asked again for a hidden view")
        harness.view.isHidden = false
    }

    /// m4: the layer resized on the main thread while an ask is under way (its drawables held, so
    /// the ask may block in the layer): the main thread does not wait, and the frame owed is drawn
    /// at the new size once a drawable of it comes. Whether the ask really blocked inside the layer
    /// on this machine only a Mac with a starved window server can show.
    @Test func aResizeWhileAnAskIsUnderWayDoesNotHoldTheMainThread() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        let holding = Mutex(false)
        harness.view.nextDrawable = { layer in
            let held = (0 ..< layer.maximumDrawableCount).compactMap { _ in layer.nextDrawable() }
            holding.withLock { $0 = true }
            let next = layer.nextDrawable()
            withExtendedLifetime(held) {}
            return next
        }
        harness.link.output("resized")
        harness.view.drawNowIfIdle()
        #expect(await harness.until { holding.withLock { $0 } }, "the ask began")
        let began = ContinuousClock.now
        harness.window.setContentSize(NSSize(width: 640, height: 400))
        harness.window.layoutIfNeeded()
        #expect(ContinuousClock.now - began < .milliseconds(50), "the main thread waited on the layer while an ask was under way")
        harness.view.nextDrawable = { $0.nextDrawable() }
        await harness.drawn()
        #expect(await harness.until { harness.view.visibleText.contains("resized") }, "the owed frame was drawn")
    }

    /// A drawable that came at the layer's old size is let go and another asked for, and the owed
    /// frame is drawn on that one.
    @Test func aDrawableOfAnotherSizeIsLetGoAndAnotherAskedFor() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        let layer = try #require(harness.view.layer as? CAMetalLayer)
        nonisolated(unsafe) let small = CAMetalLayer()
        small.device = layer.device
        small.pixelFormat = layer.pixelFormat
        small.drawableSize = CGSize(width: 8, height: 8)
        let asks = Mutex(0)
        harness.view.nextDrawable = { layer in
            let ask = asks.withLock { asks in
                asks += 1
                return asks
            }
            return ask == 1 ? small.nextDrawable() : layer.nextDrawable()
        }
        let committed = Self.committed(harness.view)
        harness.link.output("refetched")
        harness.view.drawNowIfIdle()
        #expect(await harness.until { asks.withLock { $0 } == 2 }, "the drawable of another size was let go and another asked for")
        await harness.drawn()
        #expect(Self.committed(harness.view) == committed + 1, "one frame, on the second drawable")
        #expect(harness.view.visibleText.contains("refetched"))
    }

    /// A pane closed while its ask is under way: the drawable that comes draws nothing and asks
    /// for no other.
    @Test func aPaneClosedWhileAnAskIsUnderWayDrawsNothingWhenItComes() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        let gate = DispatchSemaphore(value: 0)
        let asks = Mutex(0)
        harness.view.nextDrawable = { layer in
            asks.withLock { $0 += 1 }
            _ = gate.wait(timeout: .now() + 2)
            return layer.nextDrawable()
        }
        let committed = Self.committed(harness.view)
        harness.link.output("closing")
        harness.view.drawNowIfIdle()
        #expect(await harness.until { asks.withLock { $0 } == 1 }, "the ask began")
        harness.view.detach()
        harness.window.contentView = nil
        gate.signal()
        await harness.drawn()
        await harness.drainMain()
        #expect(Self.committed(harness.view) == committed, "a closed pane drew nothing")
        #expect(asks.withLock { $0 } == 1, "and asked for no other drawable")
    }

    /// A display-link tick while an ask is under way asks for no second drawable and draws
    /// nothing; the one that comes draws the owed frame once.
    @Test func aTickWhileAnAskIsUnderWayAsksForNoOther() async throws {
        let harness = try Harness()
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        let gate = DispatchSemaphore(value: 0)
        let asks = Mutex(0)
        harness.view.nextDrawable = { layer in
            asks.withLock { $0 += 1 }
            _ = gate.wait(timeout: .now() + 2)
            return layer.nextDrawable()
        }
        let committed = Self.committed(harness.view)
        harness.link.output("ticked")
        harness.view.drawNowIfIdle()
        #expect(await harness.until { asks.withLock { $0 } == 1 }, "the ask began")
        let wanted = (0 ..< 3).map { _ in harness.view.refresh(target: CACurrentMediaTime() + 1.0 / 60) }
        #expect(wanted == [false, false, false], "a tick with no drawable keeps the link paused")
        #expect(Self.committed(harness.view) == committed, "nothing drew without a drawable")
        gate.signal()
        await harness.drawn()
        #expect(asks.withLock { $0 } == 1, "one ask for every tick")
        #expect(Self.committed(harness.view) == committed + 1, "the drawable that came drew one frame")
        #expect(harness.view.visibleText.contains("ticked"))
    }

    /// P2: a focused view's blinking cursor hides and shows at the system's rate, each half one
    /// frame, drawn without waking the display link; a key typed shows it at once; unfocused, it
    /// shows, steady, and nothing wakes.
    @Test func aFocusedCursorBlinksAKeyShowsItAndUnfocusedItIsSteady() async throws {
        let harness = try Harness(blinks: true)
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        #expect(harness.view.cursorOn)
        let committed = Self.committed(harness.view)
        let fires = harness.clock.resumes
        #expect(await harness.until(2) { !harness.view.cursorOn }, "the cursor did not blink off")
        await harness.drawn()
        #expect(await harness.until { Self.committed(harness.view) == committed + 1 }, "the blink drew one frame")
        #expect(harness.clock.isPaused, "a blink's frame wants no next display refresh")
        #expect(harness.clock.resumes == fires, "a blink woke the display link")
        #expect(!harness.view.overlay.cursorOn)
        harness.key("a", code: 0)
        #expect(harness.view.cursorOn, "a key typed shows the cursor")
        #expect(harness.view.isBlinking)
        harness.window.makeFirstResponder(nil)
        #expect(harness.view.cursorOn)
        #expect(!harness.view.isBlinking, "an unfocused view would still wake to blink")
    }

    /// A program that asks for a steady cursor (DECSCUSR 2) gets one: no blink wakes the view.
    @Test func aSteadyCursorNeverBlinks() async throws {
        let harness = try Harness(blinks: true)
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        #expect(harness.view.isBlinking, "the control: the same view blinks before the program asks for steady")
        harness.link.output("\u{1b}[2 q")
        await harness.drainMain()
        harness.view.needFrame()
        await harness.tick()
        #expect(!harness.view.isBlinking, "a steady cursor would wake the view")
        #expect(harness.view.cursorOn)
    }

    /// Copy is offered only with a selection, Paste only with text to paste, Select All with a
    /// session; and Select All then selects what Copy writes.
    @Test func theEditCommandsAreValidatedByWhatTheyWouldDo() async throws {
        let harness = try Harness()
        harness.link.output("first\r\nsecond")
        await harness.drainMain()
        func valid(_ action: Selector) -> Bool {
            harness.view.validateMenuItem(NSMenuItem(title: "", action: action, keyEquivalent: ""))
        }
        harness.pasteboard.clearContents()
        #expect(!valid(#selector(TerminalView.copy(_:))), "Copy with nothing selected")
        #expect(!valid(#selector(TerminalView.paste(_:))), "Paste with nothing to paste")
        #expect(valid(#selector(TerminalView.selectAll(_:))))
        harness.view.selectAll(nil)
        #expect(valid(#selector(TerminalView.copy(_:))))
        harness.view.copy(nil)
        #expect(harness.pasteboard.string(forType: .string)?.hasPrefix("first\nsecond") == true)
        #expect(valid(#selector(TerminalView.paste(_:))))
    }

    /// The right button goes to a program that tracks the mouse in a focused view, and no menu
    /// opens; with Shift, or in a program that does not track it, the menu opens instead.
    @Test func theRightButtonGoesToAProgramThatTracksTheMouseShiftGivesTheMenu() async throws {
        let harness = try Harness()
        let menu = NSMenu()
        harness.view.contextMenu = { menu }
        func right(_ flags: NSEvent.ModifierFlags) -> NSEvent? {
            NSEvent.mouseEvent(
                with: .rightMouseDown, location: harness.view.convert(NSPoint(x: 20, y: 20), to: nil), modifierFlags: flags,
                timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: harness.window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1
            )
        }
        #expect(harness.view.menu(for: try #require(right([]))) === menu, "no program tracks the mouse: the menu")
        harness.link.output("\u{1b}[?1000h")
        await harness.drainMain()
        #expect(harness.view.menu(for: try #require(right([]))) == nil, "a program tracking the mouse takes the right button")
        #expect(harness.view.menu(for: try #require(right(.shift))) === menu, "Shift gives the menu")
        harness.link.forget()
        harness.view.rightMouseDown(with: try #require(right([])))
        harness.flush()
        #expect(!harness.link.bytes.isEmpty, "the right button's press reached the program")
    }

    /// A program's clipboard write (OSC 52) from a session this client writes to reaches the
    /// pasteboard.
    @Test func aProgramsClipboardWriteReachesThePasteboard() async throws {
        let harness = try Harness()
        harness.pasteboard.clearContents()
        harness.link.output("\u{1b}]52;c;aGVsbG8=\u{07}")
        #expect(await harness.until { harness.pasteboard.string(forType: .string) == "hello" }, "the pasteboard holds \(harness.pasteboard.string(forType: .string) ?? "nothing")")
    }

    static func committed(_ view: TerminalView) -> Int {
        guard let found = view.drawingState.range(of: "committed ") else { return -1 }
        return Int(view.drawingState[found.upperBound...].prefix { $0.isNumber }) ?? -1
    }

    /// V3: every start, pause, resume and stop of the display link ran on the main thread, the
    /// resumes that output from another thread caused included.
    @Test func everyDisplayLinkChangeRanOnTheMainThread() async throws {
        let harness = try Harness()
        for round in 0 ..< 5 {
            harness.view.pacer.pause()
            let link = harness.link
            Thread.detachNewThread { link.output("round \(round)\r\n") }
            await harness.clock.nextResume()
        }
        harness.window.contentView = nil
        let records = harness.view.pacer.records
        #expect(records.contains { $0.change == .start } && records.contains { $0.change == .stop })
        #expect(records.contains { $0.change == .pause } && records.contains { $0.change == .resume })
        #expect(records.allSatisfy { $0.onMainThread })
    }

    @Test func theViewIsATextAreaShowingTheVisibleText() async throws {
        let harness = try Harness()
        harness.link.output("visible words")
        await harness.tick()
        #expect(harness.view.accessibilityRole() == .textArea)
        #expect((harness.view.accessibilityValue() as? String)?.contains("visible words") == true)
    }

    @Test func aPasteCountsItsLines() {
        #expect(TerminalView.lines(in: "one") == 1)
        #expect(TerminalView.lines(in: "one\n") == 1)
        #expect(TerminalView.lines(in: "one\ntwo\nthree") == 3)
        #expect(TerminalView.lines(in: "one\rtwo\rthree") == 3)
        #expect(TerminalView.lines(in: "one\r\ntwo\r\n") == 2)
    }

    /// The look changes while the terminal runs: the next frame is drawn in the other palette with
    /// what it showed kept; a program told of the scheme only once it subscribed (mode 2031).
    @Test func aRunningViewTakesTheOtherPaletteAtOnce() async throws {
        let harness = try Harness()
        harness.link.output("\u{1b}[31mred\u{1b}[0m")
        await harness.drainMain()
        await harness.tick()
        await harness.tick()
        try #require(harness.clock.isPaused)
        #expect(harness.view.lastFrame?.colors.background == Palette.dark.background)
        harness.link.forget()
        harness.view.recolour(.light)
        harness.flush()
        #expect(await harness.until { harness.view.lastFrame?.colors.background == Palette.light.background }, "the other palette was not drawn")
        #expect(harness.view.visibleText.contains("red"))
        #expect(harness.link.bytes.isEmpty, "a program that did not subscribe is told nothing")
        harness.link.output("\u{1b}[?2031h")
        harness.view.recolour(.dark)
        harness.flush()
        #expect(harness.link.bytes == Array("\u{1b}[?997;1n".utf8))
    }
}

/// V2: the app parses and makes no escape sequence; the screen's encoders and libghostty-vt do.
@Suite struct SweepTests {
    init() {
        ExitWatch.install()
    }

    @Test func noAppSourceSpellsAnEscapeSequence() throws {
        let sources = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources")
        let targets = ["SailLook", "SailRenderer", "SailTerminalView", "SailShell", "SailAccount", "Sail"]
        var read = 0
        for target in targets {
            let directory = sources.appending(path: target)
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path) where name.hasSuffix(".swift") {
                let text = try String(contentsOf: directory.appending(path: name), encoding: .utf8)
                read += 1
                for escape in ["\\u{1b}", "\\u{1B}", "\\e[", "\\u{9b}", "\\u{9B}", "\\x1b"] {
                    #expect(!text.contains(escape), "\(target)/\(name) spells \(escape)")
                }
            }
        }
        #expect(read >= 10)
    }

    /// No unit test posts an event to the app's queue: with no `NSApp.run` under a test, a posted
    /// event stopped the main run loop and ended the suite's process; posting is the drive's.
    @Test func noUnitTestPostsAnEventToTheAppsQueue() throws {
        let tests = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        var read = 0
        for target in try FileManager.default.contentsOfDirectory(atPath: tests.path) where target.hasSuffix("Tests") {
            let directory = tests.appending(path: target)
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path) where name.hasSuffix(".swift") {
                let text = try String(contentsOf: directory.appending(path: name), encoding: .utf8)
                read += 1
                #expect(!text.contains("postEvent" + "("), "\(target)/\(name) posts an event to the app's queue: that is the drive's")
            }
        }
        #expect(read >= 20)
    }

    /// No unit test sends a mouse event through the app or a window (`sendEvent`): landing on a
    /// control, it runs AppKit's own mouse tracking, which stopped the main run loop under a test; a
    /// test hands the view its own handler, and real clicks are the drive's.
    @Test func noUnitTestSendsAMouseDownThroughTheApp() throws {
        let tests = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let sent = try NSRegularExpression(pattern: #"\.sendEvent\(([^)]*)\)"#)
        var read = 0
        for target in try FileManager.default.contentsOfDirectory(atPath: tests.path) where target.hasSuffix("Tests") {
            let directory = tests.appending(path: target)
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path) where name.hasSuffix(".swift") {
                let text = try String(contentsOf: directory.appending(path: name), encoding: .utf8)
                read += 1
                for match in sent.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                    guard let range = Range(match.range(at: 1), in: text) else { continue }
                    let argument = String(text[range])
                    let made = Self.declaration(of: argument, in: text, before: range.lowerBound) ?? argument
                    #expect(!made.contains("Mouse") && !made.contains("mouseEvent"), "\(target)/\(name) sends a mouse event through the app: \(argument)")
                }
            }
        }
        #expect(read >= 20)
    }

    /// Nothing a layout pass reaches changes what makes AppKit ask for another pass from inside one,
    /// which crashed the drive (#502): in `layout()`, `updateConstraints()`, `viewDidLayout()`,
    /// `resizeSubviews` and `setFrameSize` of every view of the app's own, no
    /// `preferredMaxLayoutWidth`, intrinsic size, label text or font, or constraint's `constant` or
    /// `isActive` is set. A view whose height follows its width (no intrinsic width of its own) may
    /// invalidate its size when its width changes. Helpers those call are not read here: the exit
    /// watch writes AppKit's layout-loop exception with its stack, in the tests and the drive.
    @Test func noLayoutChangesAnIntrinsicSize() throws {
        let sources = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources")
        var read = 0
        for target in try FileManager.default.contentsOfDirectory(atPath: sources.path) {
            let directory = sources.appending(path: target)
            guard let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { continue }
            for name in names where name.hasSuffix(".swift") {
                let text = try String(contentsOf: directory.appending(path: name), encoding: .utf8)
                read += 1
                let heightForWidth = text.contains("NSSize(width: NSView.noIntrinsicMetric")
                for entry in ["func layout()", "func updateConstraints()", "func viewDidLayout()", "func resizeSubviews(", "func setFrameSize("] {
                    for body in Self.bodies(of: entry, in: text) {
                        for change in Self.anotherPass where body.contains(change) {
                            let followsWidth = entry == "func setFrameSize(" && change == "invalidateIntrinsicContentSize" && heightForWidth && body.contains("newSize.width != frame.width")
                            #expect(followsWidth, "\(target)/\(name): \(entry) changes \(change) inside a layout pass")
                        }
                    }
                }
            }
        }
        #expect(read >= 50)
    }

    /// What, set inside a layout pass, makes AppKit ask for another.
    static let anotherPass = [
        "preferredMaxLayoutWidth", "invalidateIntrinsicContentSize", "needsUpdateConstraints = true", ".constant =", ".isActive =",
        "stringValue =", "attributedStringValue =", ".font =",
    ]

    /// The bodies of every function `signature` in `text`, brace to matching brace.
    static func bodies(of signature: String, in text: String) -> [String] {
        var found: [String] = []
        var rest = text[...]
        while let head = rest.range(of: signature), let open = rest[head.upperBound...].firstIndex(of: "{") {
            var depth = 0, end = open
            for index in rest[open...].indices {
                if rest[index] == "{" { depth += 1 } else if rest[index] == "}" { depth -= 1 }
                if depth == 0 {
                    end = index
                    break
                }
            }
            found.append(String(rest[open ... end]))
            rest = rest[rest.index(after: end)...]
        }
        return found
    }

    /// What makes `name` in `text` for the `sendEvent` at `use`: from its nearest `let` before it.
    static func declaration(of name: String, in text: String, before use: String.Index) -> String? {
        let before = text.startIndex ..< use
        guard let start = [text.range(of: "let \(name) = ", options: .backwards, range: before), text.range(of: "let \(name): ", options: .backwards, range: before)]
            .compactMap(\.self).max(by: { $0.lowerBound < $1.lowerBound })
        else { return nil }
        return String(text[start.lowerBound ..< use])
    }
}

/// Swift's shared pool held at every priority, in a suite of its own run alone: CI runs every suite
/// in a process of its own (`ci/test-suites`); a plain local `swift test` runs suites side by side in
/// one process, and holding the pool there stalls the others for as long as the holders take to
/// start (well under a second).
@MainActor
@Suite(.serialized) struct SharedPoolTests {
    init() {
        ExitWatch.install()
    }

    /// A change of the screen, told on the session's thread, reaches the queue that draws while
    /// Swift's shared pool is held at every priority: nothing between the change and that queue
    /// needs one of its threads. The wake's queue is a private one here, and the test waits for it
    /// on a semaphore, since any wait of Swift concurrency's would need the pool too.
    @Test func aScreenChangeReachesItsQueueWhileTheSharedPoolIsHeld() throws {
        let marks = ViewMarks()
        let drew = DispatchSemaphore(value: 0)
        let wake = ScreenWake(on: DispatchQueue(label: "sh.standardapplied.sail.tests.wake"), marks: marks) { drew.signal() }
        let pool = PoolHold()
        defer { pool.release() }
        try #require(pool.holdsEveryPriority, "the pool is not held at every priority: holders ran \(pool.running)")
        onItsOwnThread { wake.changed(at: CACurrentMediaTime()) }
        let reached = drew.wait(timeout: .now() + 2) == .success
        pool.release()
        #expect(reached, "the change did not reach its queue while the shared pool was held")
        #expect(marks.figures.wakes == 1)
    }
}
