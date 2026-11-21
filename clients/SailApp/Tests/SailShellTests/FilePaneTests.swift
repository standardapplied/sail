import AppKit
import Foundation
import SailFiles
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailTerminalWire
import SailVT
import SailWorkbench
import Testing

/// A workspace's file pane, beside its terminals: typed in once whole, following the file while
/// clean, saying so while unsaved and never replacing typed text by itself, writing nothing without
/// a save; ⌘W closes the file, never a terminal; the keys move to it as between panes.
@MainActor @Suite(.serialized) struct FilePaneTests {
    static let path = FilesPanelTests.root + "/tide.ts"
    /// The windows the tests' workbenches are in, kept while the suite runs.
    static var windows: [NSWindow] = []

    /// A shell with the files panel shown over a door holding `tide.ts` as `text`, the file opened;
    /// the workbench in a window of its own, so the keys have somewhere to be.
    static func opened(_ text: [UInt8], width: Double = 1180) async throws -> (FakeRunnerDoor, ShellApp, WorkbenchView, FilesPanel, FilePane) {
        let door = try FakeRunnerDoor(person: "uday")
        door.setFolder(FilesPanelTests.root, [FileEntry(name: "tide.ts", kind: .known(.file), target: .known(.none), size: Int64(text.count), mtimeNanos: 0, mode: 0o100644)])
        door.setFile(path, text)
        let (shell, bench) = try FilesPanelTests.shell(door, width: width)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        bench.removeFromSuperview()
        window.contentView = bench
        windows.append(window)
        bench.setFrameSize(NSSize(width: width, height: 600))
        bench.toggleFiles(nil)
        let panel = try #require(FilesPanelTests.panel(bench))
        #expect(await RunnerTabTests.until(10) { panel.list.numberOfRows == 1 }, "the tree never listed tide.ts")
        #expect(panel.openFile(FileName(path)))
        let pane = try #require(bench.model.selectedTab.flatMap { bench.tabViews[$0.id]?.filePane })
        return (door, shell, bench, panel, pane)
    }

    @Test func aFileOpensBesideTheTerminalsTypedInOnlyOnceWhole() async throws {
        let (_, _, bench, _, pane) = try await Self.opened(Array("one\ntwo\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.string == "one\ntwo\n" && pane.editor.isEditable }, "shown: \(pane.editor.string)")
        bench.layoutSubtreeIfNeeded()
        let terminal = try #require(bench.focused)
        #expect(pane.frame.minX >= terminal.frame.maxX && pane.frame.width >= WorkbenchView.filePaneLeast, "the pane at \(pane.frame) is not beside the terminal at \(terminal.frame)")
        #expect(pane.file.clean)
        var hash = SHA256()
        hash.update(Array("one\ntwo\n".utf8))
        #expect(pane.file.digest == hash.finalize().map { String(format: "%02x", $0) }.joined(), "the base is not the read's digest: \(pane.file.digest)")
    }

    /// F14: clean, the workspace's change is shown; typed in, the dot is on the title and the row,
    /// a change underneath only says so, and Discard Mine takes the workspace's text.
    @Test func aCleanPaneFollowsAndAnUnsavedOneSaysSo() async throws {
        let (door, _, _, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        door.setFile(Self.path, Array("two\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.string == "two\n" }, "a clean pane did not follow: \(pane.editor.string)")
        pane.editor.setSelectedRange(NSRange(location: 4, length: 0))
        pane.editor.insertText("mine", replacementRange: NSRange(location: 4, length: 0))
        #expect(!pane.file.clean && panel.unsaved == FileName(Self.path), "typing did not mark the pane and its row unsaved")
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.file.note == .changed }, "the change underneath was not said")
        #expect(pane.editor.string == "two\nmine", "an unsaved pane was replaced: \(pane.editor.string)")
        pane.layoutSubtreeIfNeeded()
        let line = try #require(pane.subviews.compactMap { $0 as? NSTextField }.first { $0.stringValue == "Changed on the workspace while you typed." })
        #expect(line.frame.width >= line.attributedStringValue.size().width, "the line's words are cut: \(line.frame.width) pt for \(line.attributedStringValue.size().width)")
        pane.discardPressed()
        #expect(await RunnerTabTests.until(10) { pane.editor.string == "theirs\n" && pane.file.clean && panel.unsaved == nil })
    }

    /// A changed line that wraps is barred along every row it takes, its number on its first.
    @Test func aWrappedChangedLineIsBarredAlongEveryRow() async throws {
        let (door, _, bench, _, pane) = try await Self.opened(Array("short\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        let long = "short\n" + String(repeating: "a long line that wraps ", count: 30) + "\nend\n"
        door.setFile(Self.path, Array(long.utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.string == long })
        bench.layoutSubtreeIfNeeded()
        let rows = pane.lineNumbers.rows
        let first = try #require(rows.first { $0.number == 1 }), wrapped = try #require(rows.first { $0.number == 2 })
        #expect(wrapped.barred && !first.barred && wrapped.height > first.height * 1.5, "the wrapped line's bar spans \(wrapped.height) pt against a row's \(first.height): \(rows)")
    }

    /// The sheet bars each differing line in its gutter and fills none, and says when the
    /// workspace's text was read.
    @Test func theSheetBarsItsLinesAndSaysWhenTheirsWasRead() throws {
        let read = Date(timeIntervalSince1970: 1_700_000_000)
        let sheet = ConflictSheet(name: "tide.ts", theirs: "same\ntheirs\n", theirsRead: read, yours: "same\nmine\n", deleted: false, look: .light, mono: try LabelFont(directory: LookTests.fonts))
        let filled = [sheet.theirsView, sheet.yoursView].contains { view in
            var found = false
            view.textStorage?.enumerateAttribute(.backgroundColor, in: NSRange(location: 0, length: view.textStorage?.length ?? 0)) { value, _, _ in found = found || value != nil }
            return found
        }
        #expect(!filled, "a differing line was filled rather than barred")
        let rulers = [sheet.theirsView, sheet.yoursView].compactMap { $0.enclosingScrollView?.verticalRulerView as? LineNumbers }
        #expect(rulers.count == 2 && rulers.allSatisfy { $0.barred == IndexSet(integer: 2) }, "the differing line is not barred on both sides")
        #expect([sheet.theirsView, sheet.yoursView].allSatisfy { $0.enclosingScrollView is QuietScrollView })
        let time = DateFormatter.localizedString(from: read, dateStyle: .none, timeStyle: .short)
        #expect(Self.words(in: sheet.contentView).contains("Theirs  on the workspace, \(time)"), "\(Self.words(in: sheet.contentView))")
    }

    static func words(in view: NSView?) -> [String] {
        guard let view else { return [] }
        return ((view as? NSTextField).map { [$0.stringValue] } ?? []) + view.subviews.flatMap { words(in: $0) }
    }

    /// The look's tokens as colours, read from every token it holds.
    static func tokens(_ look: Look) -> Set<RGB> {
        func collect(_ value: Any) -> [RGB] {
            if let rgb = value as? RGB { return [rgb] }
            if let tint = value as? Tint { return [tint.rgb] }
            return Mirror(reflecting: value).children.flatMap { collect($0.value) }
        }
        return Set(collect(look))
    }

    static func rgb(_ colour: NSColor?) -> RGB? {
        guard let colour = colour?.usingColorSpace(.sRGB) else { return nil }
        func byte(_ value: CGFloat) -> UInt8 {
            UInt8(max(0, min(255, (Double(value) * 255).rounded())))
        }
        return RGB(red: byte(colour.redComponent), green: byte(colour.greenComponent), blue: byte(colour.blueComponent))
    }

    /// Every colour a shown view paints with: its layer's fill and edge, a text field's and a text
    /// view's ink and fill, with the view that paints it.
    static func colours(in view: NSView) -> [(String, RGB)] {
        guard !view.isHidden else { return [] }
        var found: [(String, RGB)] = []
        let name = "\(type(of: view))"
        if let fill = view.layer?.backgroundColor, let rgb = rgb(NSColor(cgColor: fill)), (NSColor(cgColor: fill)?.alphaComponent ?? 0) > 0 { found.append((name + " fill", rgb)) }
        if let layer = view.layer, layer.borderWidth > 0, let edge = layer.borderColor, let rgb = rgb(NSColor(cgColor: edge)) { found.append((name + " edge", rgb)) }
        if let field = view as? NSTextField, !field.stringValue.isEmpty, let rgb = rgb(field.textColor) { found.append((name + " \"\(field.stringValue.prefix(24))\"", rgb)) }
        if let text = view as? NSTextView {
            if let rgb = rgb(text.textColor) { found.append((name + " ink", rgb)) }
            if text.drawsBackground, let rgb = rgb(text.backgroundColor) { found.append((name + " fill", rgb)) }
        }
        return found + view.subviews.flatMap { colours(in: $0) }
    }

    /// A change of look reaches every surface the workbench shows (the terminals, the tree, the file
    /// pane and its divider, the conflict sheet over them): after light to dark, no view paints a
    /// colour only light has, so a new surface that forgets the look fails here on its own.
    @Test func aChangeOfLookReachesEverySurface() async throws {
        let (door, shell, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine", replacementRange: NSRange(location: 0, length: 0))
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.file.note == .changed })
        #expect(bench.compareFile())
        let window = try #require(bench.window)
        #expect(await RunnerTabTests.until(5) { window.attachedSheet != nil }, "the sheet did not come up")
        shell.apply(.dark)
        bench.layoutSubtreeIfNeeded()
        let lightOnly = Self.tokens(.light).subtracting(Self.tokens(.dark))
        let painted = Self.colours(in: bench) + (window.attachedSheet?.contentView.map(Self.colours) ?? [])
        let stale = painted.filter { lightOnly.contains($0.1) }
        #expect(stale.isEmpty, "light's colours after the switch to dark: \(stale.map { "\($0.0) \($0.1)" })")
        let names = painted.map(\.0)
        let walked = ["FilePane fill", "PaneTextView ink", "Keep one version"].filter { surface in !names.contains { $0.contains(surface) } }
        #expect(walked.isEmpty, "the walk never reached \(walked): \(names)")
        window.attachedSheet.map { window.endSheet($0) }
    }

    /// Right to left the pane's title and line mirror and read from the right; back left to right,
    /// they read from the left again.
    @Test func thePanesTitleMirrorsRightToLeftAndBack() async throws {
        let (_, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        func name() -> NSTextField? {
            pane.subviews.flatMap { [$0] + $0.subviews }.compactMap { $0 as? NSTextField }.first { $0.stringValue == "tide.ts" }
        }
        bench.userInterfaceLayoutDirection = .rightToLeft
        bench.apply()
        bench.layoutSubtreeIfNeeded()
        let right = try #require(name())
        #expect(right.frame.minX > pane.bounds.midX && right.alignment == .right, "right to left, the name at \(right.frame) reads from the left")
        bench.userInterfaceLayoutDirection = .leftToRight
        bench.apply()
        bench.layoutSubtreeIfNeeded()
        let left = try #require(name())
        #expect(left.frame.maxX < pane.bounds.midX && left.alignment == .left, "back left to right, the name at \(left.frame) stayed mirrored")
    }

    /// Every shown surface as laid out: its kind, its frame in `root`, and a text field's alignment,
    /// in the order the views stand.
    static func layout(of view: NSView, in root: NSView) -> [String] {
        guard !view.isHidden, !(view is NSTextView) else { return [] }
        let frame = view.convert(view.bounds, to: root).integral
        let alignment = (view as? NSTextField).map { " aligned \($0.alignment.rawValue)" } ?? ""
        return ["\(type(of: view)) \(frame)\(alignment)"] + view.subviews.flatMap { layout(of: $0, in: root) }
    }

    /// A change of direction and back leaves every surface the workbench shows (the tree, the
    /// terminals, the file pane and its divider) as it was, frame for frame and aligned the same;
    /// right to left, the pane's title and line stand mirrored. No surface can keep the other
    /// direction's layout.
    @Test func aChangeOfDirectionAndBackLeavesEverySurfaceAsItWas() async throws {
        let (_, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        bench.layoutSubtreeIfNeeded()
        let before = Self.layout(of: bench, in: bench)
        let chrome = { pane.subviews.flatMap { [$0] + $0.subviews }.filter { !$0.isHidden && ($0 is NSTextField || $0 is SailButton) && $0.frame.minY < FilePane.titleHeight + FilePane.lineHeight } }
        let leftToRight = chrome().map { ($0, $0.convert($0.bounds, to: pane)) }
        bench.userInterfaceLayoutDirection = .rightToLeft
        bench.apply()
        bench.layoutSubtreeIfNeeded()
        for (view, frame) in leftToRight {
            let mirrored = view.convert(view.bounds, to: pane)
            #expect(abs(Double(mirrored.minX) - (Double(pane.bounds.width) - Double(frame.maxX))) < 1, "right to left, \(type(of: view)) at \(mirrored) does not mirror \(frame)")
        }
        bench.userInterfaceLayoutDirection = .leftToRight
        bench.apply()
        bench.layoutSubtreeIfNeeded()
        let after = Self.layout(of: bench, in: bench)
        let moved = zip(before, after).filter { $0 != $1 }.map { "\($0.0) became \($0.1)" }
        #expect(moved.isEmpty && before.count == after.count, "back left to right, surfaces kept the other direction's layout: \(moved.prefix(8))")
    }

    /// The one line's rule holds for the tree's line as for the pane's: narrow, its words keep their
    /// row and Try Again goes under them; wide, beside them.
    @Test func theTreesLineKeepsItsWordsWholeWithItsActionUnderThem() {
        func button(at width: Double) -> NSRect {
            let line = FilesLineView(look: .light)
            line.show(FilesLine(words: "Can't reach the files.", mark: .bad, action: .tryAgain), starting: false)
            line.frame = NSRect(x: 0, y: 0, width: width, height: line.height(for: width))
            line.layoutSubtreeIfNeeded()
            return line.button.frame
        }
        let narrow = button(at: 236), wide = button(at: 600)
        #expect(narrow.minY >= wide.minY + 12, "narrow, Try Again at \(narrow) is not a row under the words; wide, at \(wide)")
    }

    /// M26: ⌘S writes the pane's text whole against the digest read; the dot goes from the title
    /// and the row, and the pane follows again.
    @Test func aSaveWritesTheTextAndTheDotGoes() async throws {
        let (door, _, _, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("saved ", replacementRange: NSRange(location: 0, length: 0))
        pane.saveDocument(nil)
        #expect(await RunnerTabTests.until(10) { door.file(Self.path) == Array("saved one\n".utf8) && pane.file.clean && panel.unsaved == nil },
                "the door holds \(door.file(Self.path).map { String(decoding: $0, as: UTF8.self) } ?? "nothing"), the pane \(pane.file.clean ? "clean" : "unsaved")")
    }

    /// M27: ⌘S over a newer file writes nothing and brings up the sheet; Keep Mine then writes the
    /// pane's text against the version the sheet showed.
    @Test func aSaveOverANewerFileShowsTheSheetAndKeepMineWrites() async throws {
        let (door, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine ", replacementRange: NSRange(location: 0, length: 0))
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.file.note == .changed })
        pane.saveDocument(nil)
        #expect(await RunnerTabTests.until(10) { bench.sheetOffers == ["Cancel", "Keep Theirs", "Keep Mine"] }, "no sheet after a save over a newer file")
        #expect(door.file(Self.path) == Array("theirs\n".utf8), "the save wrote over the newer file")
        #expect(bench.pressSheet("Keep Mine"))
        #expect(await RunnerTabTests.until(10) { door.file(Self.path) == Array("mine one\n".utf8) && pane.file.clean }, "Keep Mine did not write the pane's text")
    }

    /// M29: a save refused for a full disk says so in the host's words; the dot stays.
    @Test func aSaveRefusedIsSaidAndTheDotStays() async throws {
        let (door, _, _, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
        door.refuseNextSave(.noSpace, saying: "dock's disk is full (12 KB free). Nothing was changed.")
        pane.saveDocument(nil)
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "dock's disk is full (12 KB free). Nothing was changed." }, "said: \(pane.noteWords ?? "nothing")")
        #expect(!pane.file.clean && panel.unsaved == FileName(Self.path) && door.file(Self.path) == Array("one\n".utf8))
        door.removeFile(Self.path)
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "tide.ts was deleted on the workspace while you typed." }, "a refusal's words hid a newer word on the file: \(pane.noteWords ?? "nothing")")
    }

    /// The newest word wins the line, whichever way: a save's refusal after the file's own note
    /// replaces the note, as a newer note replaces a refusal.
    @Test func aSavesRefusalAfterTheFilesNoteReplacesIt() async throws {
        let (door, _, _, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "Changed on the workspace while you typed." })
        door.refuseNextSave(.noSpace, saying: "dock's disk is full (12 KB free). Nothing was changed.")
        pane.saveDocument(nil)
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "dock's disk is full (12 KB free). Nothing was changed." }, "the refusal did not replace the note: \(pane.noteWords ?? "nothing")")
    }

    /// M14a: a file that is not text is one sentence and Copy Path.
    @Test func aFileThatIsNotTextIsASentence() async throws {
        let (_, _, _, _, pane) = try await Self.opened([0x89, 0x50, 0x4E, 0x47, 0, 1, 2, 3])
        #expect(await RunnerTabTests.until(10) { pane.file.state == .notText("tide.ts isn't text: a PNG image, 8 bytes.") })
        #expect(pane.scroll.isHidden)
    }

    /// F13: opening, typing, undoing, discarding, hiding the panel and showing it again, and
    /// following changes write nothing: the door hears only listings, reads, watches and cancels.
    @Test func nothingIsWrittenWithoutASave() async throws {
        let (door, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("typed", replacementRange: NSRange(location: 0, length: 0))
        pane.editor.undoManager?.undo()
        pane.editor.insertText("again", replacementRange: NSRange(location: 0, length: 0))
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.file.note == .changed })
        pane.discardPressed()
        bench.toggleFiles(nil)
        bench.toggleFiles(nil)
        #expect(await RunnerTabTests.until(10) { pane.editor.string == "theirs\n" })
        let written = door.filesReceived.filter { frame in
            switch frame {
            case .filesList, .filesRead, .filesWatch, .filesCancel, .filesStat: false
            default: true
            }
        }
        #expect(written.isEmpty, "frames other than reads reached the door: \(written)")
    }

    /// ⌘W with the keys in the file pane closes the file, never a terminal.
    @Test func commandWInTheFileClosesTheFileNotATerminal() async throws {
        let (_, _, bench, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        let terminals = bench.model.panes.count
        bench.window?.makeFirstResponder(pane.editor)
        bench.closePane(nil)
        #expect(bench.model.selectedTab.flatMap { bench.tabViews[$0.id]?.filePane } == nil && panel.filePane == nil, "the file did not close")
        #expect(bench.model.panes.count == terminals, "a terminal closed")
    }

    /// With nothing in the window holding the keys, ⌘W closes the file if the keys were last in it,
    /// never a terminal; once a terminal took the keys, the file no longer counts as holding them.
    @Test func withNothingHoldingTheKeysCommandWClosesTheFileTheyWereLastIn() async throws {
        let (_, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        let window = try #require(bench.window), terminal = try #require(bench.focused)
        window.makeFirstResponder(terminal.terminal)
        window.makeFirstResponder(pane.editor)
        window.makeFirstResponder(nil)
        #expect(await RunnerTabTests.until(2) { bench.filePaneHoldingKeys === pane }, "the file the keys were last in does not count as holding them")
        window.makeFirstResponder(terminal.terminal)
        window.makeFirstResponder(nil)
        #expect(await RunnerTabTests.until(2) { bench.filePaneHoldingKeys == nil }, "the file still counts after a terminal took the keys")
        window.makeFirstResponder(pane.editor)
        window.makeFirstResponder(nil)
        let terminals = bench.model.panes.count
        #expect(await RunnerTabTests.until(2) { bench.filePaneHoldingKeys === pane })
        bench.closePane(nil)
        #expect(bench.model.selectedTab.flatMap { bench.tabViews[$0.id]?.filePane } == nil && bench.model.panes.count == terminals, "⌘W with the keys last in the file closed a terminal or left the file")
    }

    /// Hiding the files panel leaves the file open, connected; a zoomed terminal hides the pane
    /// until the zoom ends.
    @Test func hidingTheTreeKeepsTheFileAndAZoomHidesIt() async throws {
        let (door, _, bench, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        bench.toggleFiles(nil)
        bench.layoutSubtreeIfNeeded()
        #expect(panel.isHidden && !pane.isHidden && pane.superview != nil, "hiding the tree closed the file")
        #expect(await RunnerTabTests.until(10) { door.filesConnections == 1 }, "the file's connection ended with the tree")
        bench.splitRight(nil)
        #expect(bench.model.selectedTab?.layout.leaves.count == 2, "the terminal did not split")
        bench.zoomSplit(nil)
        bench.layoutSubtreeIfNeeded()
        #expect(bench.model.selectedTab?.zoomed != nil && pane.isHidden, "a zoomed terminal left the pane shown")
        bench.zoomSplit(nil)
        bench.layoutSubtreeIfNeeded()
        #expect(!pane.isHidden)
    }

    /// The keys move from the terminal to the file pane and on to the tree by Select Next Split,
    /// and back by Select Previous Split, as between panes.
    @Test func theKeysMoveToTheFileAsBetweenPanes() async throws {
        let (_, _, bench, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        let terminal = try #require(bench.focused)
        bench.window?.makeFirstResponder(terminal.terminal)
        bench.selectNextSplit(nil)
        #expect(bench.window?.firstResponder === pane.editor, "the keys did not move to the file")
        bench.selectNextSplit(nil)
        #expect((bench.window?.firstResponder as? NSView)?.isDescendant(of: panel) == true, "the keys did not move on to the tree")
        bench.selectPreviousSplit(nil)
        #expect(bench.window?.firstResponder === pane.editor, "the keys did not move back to the file")
        bench.selectPreviousSplit(nil)
        #expect(bench.window?.firstResponder === terminal.terminal, "the keys did not move back to the terminal")
    }

    /// At the least frame the pane takes the terminals' place, and moving the keys to the terminal
    /// puts it behind, its text kept.
    @Test func atTheLeastFrameThePaneTakesTheTerminalsPlaceAndGivesItBack() async throws {
        let (_, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8), width: 700)
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        bench.layoutSubtreeIfNeeded()
        #expect(!pane.isHidden, "the pane did not come forward")
        let terminal = try #require(bench.focused)
        bench.window?.makeFirstResponder(pane.editor)
        bench.selectPreviousSplit(nil)
        bench.layoutSubtreeIfNeeded()
        #expect(pane.isHidden && bench.window?.firstResponder === terminal.terminal && pane.editor.string == "one\n", "the terminal was not given back its place")
        let view = try #require(bench.model.selectedTab.flatMap { bench.tabViews[$0.id] })
        bench.focusFilePane(in: view)
        bench.layoutSubtreeIfNeeded()
        #expect(!pane.isHidden && pane.covering, "Show Terminal is not offered where the pane covers the terminals")
        pane.onShowTerminal()
        bench.layoutSubtreeIfNeeded()
        #expect(pane.isHidden && bench.window?.firstResponder === terminal.terminal, "Show Terminal did not bring the terminal forward")
    }
}
