import AppKit
import Foundation
import SailSurface
@testable import SailTerminalView
import SailVT
import Testing

@MainActor
@Suite(.serialized, .timeLimit(.minutes(1))) struct ViewTests {
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
    @Test func composingSendsNothingDrawsAtTheCursorAndCommitsOnce() throws {
        let harness = try Harness()
        harness.link.output("$ ")
        harness.tick()
        let before = try #require(try harness.view.snapshot())
        for marked in ["に", "にほ", "にほん"] {
            harness.view.setMarkedText(marked, selectedRange: NSRange(location: marked.utf16.count, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        }
        harness.flush()
        #expect(harness.link.bytes.isEmpty, "composing sent \(harness.link.bytes.count) bytes")
        #expect(harness.view.hasMarkedText())
        harness.tick()
        let composing = try #require(try harness.view.snapshot())
        #expect(composing.difference(from: before, tolerance: 8).pixels > 0, "the marked text is drawn")
        let cell = harness.view.cursorRect
        #expect(cell.origin.x == Double(harness.view.metrics.padding.width + 2 * harness.view.metrics.width) / harness.view.metrics.scale)
        let expected = harness.window.convertToScreen(harness.view.convert(cell, to: nil))
        #expect(harness.view.firstRect(forCharacterRange: NSRange(location: 0, length: 3), actualRange: nil) == expected)
        harness.view.insertText("にほん", replacementRange: NSRange(location: NSNotFound, length: 0))
        harness.flush()
        #expect(harness.link.bytes == Array("にほん".utf8))
        #expect(!harness.view.hasMarkedText())
    }

    /// Scenario 9: a drag across a wrapped line and ⌘C put the text on the pasteboard with the
    /// wrapped rows joined.
    @Test func aDragAcrossAWrappedLineCopiesItJoined() throws {
        let harness = try Harness()
        let metrics = harness.view.metrics
        harness.window.setContentSize(NSSize(
            width: Double(2 * metrics.padding.width + 10 * metrics.width) / metrics.scale,
            height: Double(2 * metrics.padding.height + 4 * metrics.height) / metrics.scale
        ))
        harness.flush()
        #expect(harness.view.grid == GridSize(columns: 10, rows: 4))
        harness.link.output("abcdefghijklmno")
        harness.tick()
        let grid = try #require(harness.view.grid)
        harness.mouse(.leftMouseDown, column: 0, row: 0)
        harness.mouse(.leftMouseDragged, column: 2, row: 1)
        harness.mouse(.leftMouseDragged, column: 4, row: 1)
        harness.mouse(.leftMouseUp, column: 4, row: 1)
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
        harness.window.endSheet(sheet, returnCode: .alertSecondButtonReturn)
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

    /// V1: output resumes a paused display link, and the first refresh after draws it.
    @Test func outputIsDrawnOnTheFirstRefreshAfterIt() async throws {
        let harness = try Harness()
        harness.tick()
        harness.tick()
        #expect(harness.clock.isPaused, "nothing changed, so the link paused")
        harness.link.output("hello")
        await harness.clock.nextResume()
        #expect(!harness.clock.isPaused)
        harness.tick()
        #expect(harness.view.visibleText.contains("hello"))
    }

    /// V3: every start, pause, resume and stop of the display link ran on the main thread, output
    /// from the session's thread included.
    @Test func everyDisplayLinkChangeRanOnTheMainThread() async throws {
        let harness = try Harness()
        for round in 0 ..< 5 {
            harness.tick()
            harness.tick()
            #expect(harness.clock.isPaused)
            let link = harness.link
            Thread.detachNewThread { link.output("round \(round)\r\n") }
            await harness.clock.nextResume()
        }
        harness.window.contentView = nil
        let records = harness.view.pacer.records
        #expect(records.contains { $0.change == .start } && records.contains { $0.change == .stop })
        #expect(records.contains { $0.change == .pause } && records.contains { $0.change == .resume })
        #expect(records.allSatisfy(\.onMainThread))
    }

    @Test func theViewIsATextAreaShowingTheVisibleText() throws {
        let harness = try Harness()
        harness.link.output("visible words")
        harness.tick()
        #expect(harness.view.accessibilityRole() == .textArea)
        #expect((harness.view.accessibilityValue() as? String)?.contains("visible words") == true)
    }

    /// The clipboard is touched only on the person's action: a program's OSC 52 write is refused.
    @Test func aProgramCannotWriteTheClipboard() throws {
        let harness = try Harness()
        harness.pasteboard.clearContents()
        harness.pasteboard.setString("mine", forType: .string)
        harness.link.output("\u{1b}]52;c;\(Data("theirs".utf8).base64EncodedString())\u{7}")
        harness.tick()
        #expect(harness.pasteboard.string(forType: .string) == "mine")
    }

    @Test func aPasteCountsItsLines() {
        #expect(TerminalView.lines(in: "one") == 1)
        #expect(TerminalView.lines(in: "one\n") == 1)
        #expect(TerminalView.lines(in: "one\ntwo\nthree") == 3)
    }
}

/// V2: the app parses and makes no escape sequence; the screen's encoders and libghostty-vt do.
@Suite struct SweepTests {
    @Test func noAppSourceSpellsAnEscapeSequence() throws {
        let sources = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources")
        let targets = ["SailLook", "SailRenderer", "SailTerminalView", "SailPreview", "SailTerminalPreview"]
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
}
