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
        #expect(!pane.unsaved)
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
        #expect(pane.unsaved && panel.unsaved == FileName(Self.path), "typing did not mark the pane and its row unsaved")
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "Changed on the workspace while you typed." }, "the change underneath was not said")
        #expect(pane.editor.string == "two\nmine", "an unsaved pane was replaced: \(pane.editor.string)")
        pane.layoutSubtreeIfNeeded()
        let line = try #require(pane.subviews.compactMap { $0 as? NSTextField }.first { $0.stringValue == "Changed on the workspace while you typed." })
        #expect(line.frame.width >= line.attributedStringValue.size().width, "the line's words are cut: \(line.frame.width) pt for \(line.attributedStringValue.size().width)")
        pane.discardPressed()
        #expect(await RunnerTabTests.until(10) { pane.editor.string == "theirs\n" && !pane.unsaved && panel.unsaved == nil })
    }

    /// A newline typed near the top renumbers every line below it.
    @Test func aTypedNewlineRenumbersTheLinesBelow() async throws {
        let (_, _, bench, _, pane) = try await Self.opened(Array("one\ntwo\nthree\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        bench.layoutSubtreeIfNeeded()
        #expect(pane.lineNumbers.rows.map(\.number).prefix(3) == [1, 2, 3])
        pane.editor.insertText("\n", replacementRange: NSRange(location: 0, length: 0))
        bench.layoutSubtreeIfNeeded()
        let rows = pane.lineNumbers.rows
        #expect(rows.map(\.number).prefix(4) == [1, 2, 3, 4], "after a newline at the top the lines read \(rows.map(\.number))")
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
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "Changed on the workspace while you typed." })
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
        #expect(await RunnerTabTests.until(10) { door.file(Self.path) == Array("saved one\n".utf8) && !pane.unsaved && panel.unsaved == nil },
                "the door holds \(door.file(Self.path).map { String(decoding: $0, as: UTF8.self) } ?? "nothing"), the pane \(!pane.unsaved ? "clean" : "unsaved")")
    }

    /// M27: ⌘S over a newer file writes nothing and brings up the sheet; Keep Mine then writes the
    /// pane's text against the version the sheet showed.
    @Test func aSaveOverANewerFileShowsTheSheetAndKeepMineWrites() async throws {
        let (door, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine ", replacementRange: NSRange(location: 0, length: 0))
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "Changed on the workspace while you typed." })
        pane.saveDocument(nil)
        #expect(await RunnerTabTests.until(10) { bench.sheetOffers == ["Cancel", "Keep Theirs", "Keep Mine"] }, "no sheet after a save over a newer file")
        #expect(door.file(Self.path) == Array("theirs\n".utf8), "the save wrote over the newer file")
        #expect(bench.pressSheet("Keep Mine"))
        #expect(await RunnerTabTests.until(10) { door.file(Self.path) == Array("mine one\n".utf8) && !pane.unsaved }, "Keep Mine did not write the pane's text")
    }

    /// M29: a save refused for a full disk says so in the host's words; the dot stays.
    @Test func aSaveRefusedIsSaidAndTheDotStays() async throws {
        let (door, _, _, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
        door.refuseNextSave(.noSpace, saying: "dock's disk is full (12 KB free). Nothing was changed.")
        pane.saveDocument(nil)
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "dock's disk is full (12 KB free). Nothing was changed." }, "said: \(pane.noteWords ?? "nothing")")
        #expect(pane.unsaved && panel.unsaved == FileName(Self.path) && door.file(Self.path) == Array("one\n".utf8))
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

    /// Every frame the pane sends from now on, as it sends it.
    static func watchSends(_ pane: FilePane) -> () -> [Frame] {
        var sent: [Frame] = []
        let send = pane.send
        pane.send = { frames in
            sent += frames
            send(frames)
        }
        return { sent }
    }

    static func writes(_ frames: [Frame]) -> [Int32] {
        frames.compactMap { if case let .filesWrite(id, _, _, _, _) = $0 { id } else { nil } }
    }

    /// A 4 MB text, as lines.
    static let large = Array(String(repeating: "export const tide = \"" + String(repeating: "~", count: 90) + "\";\n", count: 40_000).utf8)

    /// B1: ⌘S while the view still takes the file a slice a turn refuses in words and writes
    /// nothing; the workspace's file stays whole, and the pane is typed in once the view holds it.
    @Test func aSaveWhileTheFileLoadsRefusesAndWritesNothing() async throws {
        let (door, _, _, _, pane) = try await Self.opened(Self.large)
        let sent = Self.watchSends(pane)
        #expect(await RunnerTabTests.until(20) { !pane.editor.string.isEmpty && !pane.editor.isEditable }, "the file was never seen loading")
        let shown = pane.editor.textStorage?.length ?? 0
        pane.saveDocument(nil)
        #expect(Self.writes(sent()).isEmpty && pane.noteWords == "tide.ts is still loading. Nothing was saved.", "⌘S with \(shown) units in the view: \(pane.noteWords ?? "nothing said")")
        #expect(await RunnerTabTests.until(30) { pane.editor.isEditable }, "the file was never whole")
        #expect(door.file(Self.path) == Self.large && pane.editor.string.utf8.count == Self.large.count && !pane.unsaved)
    }

    /// B2: a change on the workspace while the view still takes the file is read once it holds it
    /// all: the view never shows the old text over the new base.
    @Test func aChangeWhileTheFileLoadsIsReadOnceTheViewHoldsIt() async throws {
        let (door, _, _, _, pane) = try await Self.opened(Self.large)
        #expect(await RunnerTabTests.until(20) { !pane.editor.string.isEmpty && !pane.editor.isEditable }, "the file was never seen loading")
        door.setFile(Self.path, Array("short\n".utf8))
        #expect(await RunnerTabTests.until(30) { pane.editor.string == "short\n" && pane.editor.isEditable && !pane.unsaved },
                "the view holds \(pane.editor.textStorage?.length ?? 0) units, the file 6 bytes; clean \(!pane.unsaved)")
    }

    /// The one check every write passes, proven by breaking the slicing: a view that lost a slice is
    /// never typed in, says so, and its save writes nothing.
    @Test func aViewThatLosesASliceIsNeverTypedInOrSaved() async throws {
        let text = Array(String(repeating: "a line of tide.ts\n", count: 8000).utf8)
        let (door, _, _, _, pane) = try await Self.opened(text)
        pane.losesSlice = 2
        let sent = Self.watchSends(pane)
        let words = OpenFile.notShownWhole("tide.ts")
        #expect(await RunnerTabTests.until(20) { pane.noteWords == words }, "said: \(pane.noteWords ?? "nothing")")
        #expect(!pane.editor.isEditable)
        pane.saveDocument(nil)
        #expect(Self.writes(sent()).isEmpty && pane.noteWords == words && door.file(Self.path) == text)
    }

    /// M1: ⌘S while a save is under way starts no second upload; once the first is answered the
    /// text then held is saved against the new base, and nothing is said as changed on the workspace.
    @Test func aSecondSaveIsSavedOnceTheFirstIsAnswered() async throws {
        let (door, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        let sent = Self.watchSends(pane)
        pane.editor.insertText("x", replacementRange: NSRange(location: 4, length: 0))
        pane.saveDocument(nil)
        pane.editor.insertText("y", replacementRange: NSRange(location: 5, length: 0))
        pane.saveDocument(nil)
        #expect(Self.writes(sent()).count == 1, "a second upload began while the first was under way")
        #expect(await RunnerTabTests.until(10) { door.file(Self.path) == Array("one\nxy".utf8) && !pane.unsaved }, "the door holds \(door.file(Self.path).map { String(decoding: $0, as: UTF8.self) } ?? "nothing")")
        #expect(Self.writes(sent()).count == 2 && pane.noteWords == nil && bench.sheetOffers.isEmpty)
    }

    /// M1: an upload given up is cancelled on the wire, so the host lets go of the file at once: by
    /// a close, and by Keep Mine replacing a save under way.
    @Test func anUploadGivenUpIsCancelledOnTheWire() async throws {
        let (_, _, _, _, closing) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { closing.editor.isEditable })
        let closed = Self.watchSends(closing)
        closing.editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
        closing.saveDocument(nil)
        let id = try #require(Self.writes(closed()).first)
        closing.finishClosing()
        #expect(closed().contains(.putCancel(id: id)), "the close sent \(closed().suffix(3))")
        let (door, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine ", replacementRange: NSRange(location: 0, length: 0))
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.theirs != nil })
        let sent = Self.watchSends(pane)
        pane.saveDocument(nil)
        let first = try #require(Self.writes(sent()).first)
        pane.keepMine(against: try #require(pane.theirs))
        let cancel = try #require(sent().firstIndex(of: .putCancel(id: first)), "Keep Mine did not cancel the save under way")
        #expect(Self.writes(Array(sent()[cancel...])).count == 1)
        #expect(await RunnerTabTests.until(10) { door.file(Self.path) == Array("mine one\n".utf8) && !pane.unsaved } && bench.sheetOffers.isEmpty)
    }

    /// M2: a file that grows past 20 MB while open keeps the person's text, says it can no longer
    /// be shown or saved here, and ⌘S says the same and writes nothing.
    @Test func aFileGrownPastTheLimitKeepsTypedTextAndSaysSo() async throws {
        let (door, _, _, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine ", replacementRange: NSRange(location: 0, length: 0))
        let sent = Self.watchSends(pane)
        door.setFile(Self.path, [UInt8](repeating: 0x61, count: Int(OpenFile.limit) + 10))
        let words = OpenFile.tooLargeNow("tide.ts")
        #expect(await RunnerTabTests.until(30) { pane.noteWords == words }, "said: \(pane.noteWords ?? "nothing")")
        #expect(pane.editor.string == "mine one\n" && pane.unsaved)
        pane.saveDocument(nil)
        #expect(Self.writes(sent()).isEmpty && pane.noteWords == words)
    }

    /// How a quit question about unsaved text ends.
    enum QuitEnd: String, CaseIterable {
        case cancel, discard, saved, conflict, refused, notConnected
    }

    /// M3: quitting with unsaved text answers AppKit's deferred terminate on every path: no on
    /// Cancel and on a save that does not land; no then a terminate asked again on Discard and once
    /// saved.
    @Test(arguments: QuitEnd.allCases)
    func aQuitQuestionAnswersTheDeferredTerminate(_ end: QuitEnd) async throws {
        let (door, shell, _, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        var replies: [Bool] = [], again = 0
        shell.answerTerminate = { replies.append($0) }
        shell.terminateAgain = { again += 1 }
        pane.editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
        switch end {
        case .conflict:
            door.setFile(Self.path, Array("theirs\n".utf8))
            #expect(await RunnerTabTests.until(10) { pane.noteWords == "Changed on the workspace while you typed." })
        case .refused: door.refuseNextSave(.noSpace, saying: "dock's disk is full. Nothing was changed.")
        case .notConnected:
            pane.disconnected()
        default: break
        }
        #expect(shell.applicationShouldTerminate(NSApp) == .terminateLater)
        let window = try #require(pane.window)
        #expect(await RunnerTabTests.until(5) { window.attachedSheet != nil }, "no question was asked")
        let sheet = try #require(window.attachedSheet)
        let answer: NSApplication.ModalResponse = switch end {
        case .cancel: .alertThirdButtonReturn
        case .discard: .alertSecondButtonReturn
        default: .alertFirstButtonReturn
        }
        window.endSheet(sheet, returnCode: answer)
        let leaves = end == .discard || end == .saved
        #expect(await RunnerTabTests.until(10) { replies == [false] && again == (leaves ? 1 : 0) }, "\(end): replied \(replies), asked again \(again) times")
        window.attachedSheet.map { window.endSheet($0) }
    }

    /// The dot goes when a save's answer is the text the view holds, though the keys typed since
    /// were decided by length alone.
    @Test func theDotGoesWhenASavesAnswerIsTheTextShown() async throws {
        let (door, _, _, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("x", replacementRange: NSRange(location: 4, length: 0))
        pane.saveDocument(nil)
        pane.editor.insertText("y", replacementRange: NSRange(location: 5, length: 0))
        pane.editor.setSelectedRange(NSRange(location: 6, length: 0))
        pane.editor.deleteBackward(nil)
        #expect(await RunnerTabTests.until(10) { door.file(Self.path) == Array("one\nx".utf8) && !pane.unsaved && panel.unsaved == nil }, "the dot stayed over the text saved")
    }

    /// "Updated just now" is not said beside unsaved text, and goes after its three seconds.
    @Test func updatedJustNowIsSaidOnlyOfACleanPaneAndOnlyForThreeSeconds() async throws {
        let (door, _, _, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        door.setFile(Self.path, Array("two\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.string == "two\n" && pane.statusWords == "Updated just now" })
        pane.editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
        #expect(pane.statusWords.isEmpty, "\"Updated just now\" stood beside unsaved text")
        pane.editor.undoManager?.undo()
        #expect(await RunnerTabTests.until(5) { !pane.unsaved && pane.statusWords.isEmpty }, "the status stayed past its three seconds: \(pane.statusWords)")
    }

    /// Keep Theirs and Discard Mine are the person's choice: ⌘Z brings their text back, unsaved.
    @Test func discardMineCanBeUndone() async throws {
        let (door, _, _, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine ", replacementRange: NSRange(location: 0, length: 0))
        door.setFile(Self.path, Array("theirs\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "Changed on the workspace while you typed." })
        pane.discardPressed()
        #expect(pane.editor.string == "theirs\n" && !pane.unsaved)
        pane.editor.undoManager?.undo()
        #expect(await RunnerTabTests.until(5) { pane.editor.string == "mine one\n" && pane.unsaved }, "⌘Z after Discard Mine: \(pane.editor.string.debugDescription), \(!pane.unsaved ? "clean" : "unsaved")")
        pane.editor.undoManager?.redo()
        #expect(await RunnerTabTests.until(5) { pane.editor.string == "theirs\n" && !pane.unsaved }, "⌘⇧Z: \(pane.editor.string.debugDescription)")
    }

    /// The text view's own undo reaches the model as a key does: typing undone is clean again, the
    /// dot gone.
    @Test func anUndoneKeyIsCleanAgain() async throws {
        let (_, _, _, panel, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
        #expect(pane.unsaved && panel.unsaved != nil)
        pane.editor.undoManager?.undo()
        #expect(await RunnerTabTests.until(5) { pane.editor.string == "one\n" && !pane.unsaved && panel.unsaved == nil }, "after ⌘Z: \(pane.editor.string.debugDescription), \(!pane.unsaved ? "clean" : "unsaved")")
    }

    /// N1: an undo or redo the text view tells no delegate of is decided before any answer enters:
    /// redone typing met in the same turn by the workspace's newer text is unsaved, and only says
    /// the file changed, never replaced.
    @Test func aRedoMetByAnAnswerInTheSameTurnIsNeverReplaced() async throws {
        let (_, _, _, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        let sent = Self.watchSends(pane)
        pane.editor.insertText("mine ", replacementRange: NSRange(location: 0, length: 0))
        pane.editor.undoManager?.undo()
        #expect(await RunnerTabTests.until(5) { pane.editor.string == "one\n" && !pane.unsaved })
        _ = pane.takes(.filesChanged(paths: [FileName(Self.path)]))
        let id = try #require(sent().compactMap { if case let .filesRead(id, _, _, _) = $0 { id } else { nil } }.last, "no follow read was asked")
        pane.editor.undoManager?.redo()
        let theirs = Array("theirs\n".utf8)
        var hash = SHA256()
        hash.update(theirs)
        _ = pane.takes(.filesData(id: id, version: "v9", bytes: theirs, eof: true, whole: true, digest: hash.finalize().map { String(format: "%02x", $0) }.joined()))
        #expect(pane.editor.string == "mine one\n" && pane.unsaved && pane.noteWords == "Changed on the workspace while you typed.", "the redone typing became \(pane.editor.string.debugDescription), \(!pane.unsaved ? "clean" : "unsaved")")
    }

    /// Closing the tab reads the pane's text as unsaved through its one door: a redo the text view
    /// told no delegate of, then the tab closed in the same turn, asks first.
    @Test func closingATabAfterAnUnreportedRedoAsks() async throws {
        let (_, _, bench, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine ", replacementRange: NSRange(location: 0, length: 0))
        pane.editor.undoManager?.undo()
        #expect(await RunnerTabTests.until(5) { pane.editor.string == "one\n" && !pane.unsaved })
        let window = try #require(pane.window)
        pane.editor.undoManager?.redo()
        bench.closeTab(nil)
        #expect(window.attachedSheet != nil, "the tab closed over redone, unsaved text without asking")
        #expect(bench.model.selectedTab.flatMap { bench.tabViews[$0.id]?.filePane } === pane)
        window.attachedSheet.map { window.endSheet($0, returnCode: .alertThirdButtonReturn) }
    }

    /// M17, M18: a ⌘-click on a compiler's line in the workspace's terminal is looked for on the
    /// workspace and opened in the tab's file pane at its line, the caret there and the line barred
    /// in the accent, the files panel never shown; a path that is not there is said in the
    /// terminal's own card, and nothing opens.
    @Test func aCommandClickOnATerminalPathOpensTheFileAtItsLine() async throws {
        let door = try FakeRunnerDoor(person: "uday")
        door.hold("uday-dock-1")
        door.setFolder(FilesPanelTests.root, [FileEntry(name: "src", kind: .known(.folder), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o040755)])
        let text = (1 ... 60).map { "let line\($0) = \($0)" }.joined(separator: "\n") + "\n"
        door.setFile(FilesPanelTests.root + "/src/App.java", Array(text.utf8))
        let (_, bench) = try FilesPanelTests.shell(door)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1180, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        bench.removeFromSuperview()
        window.contentView = bench
        Self.windows.append(window)
        let terminal = try #require(bench.focused)
        #expect(await RunnerTabTests.until(10) { terminal.status.says == nil }, "the terminal never attached: \(terminal.status.says ?? "")")
        bench.openTerminalPath(in: "src/App.java:42: error: ';' expected", at: 3, from: terminal)
        let pane = { bench.model.selectedTab.flatMap { bench.tabViews[$0.id]?.filePane } }
        #expect(await RunnerTabTests.until(10) { pane()?.editor.isEditable == true }, "the file never opened")
        let opened = try #require(pane())
        bench.layoutSubtreeIfNeeded()
        let line42 = (text as NSString).range(of: "let line42 =").location
        #expect(opened.editor.selectedRange().location == line42, "the caret is at \(opened.editor.selectedRange().location), line 42 at \(line42)")
        #expect(opened.lineNumbers.rows.contains { $0.number == 42 && $0.at }, "line 42 does not carry the bar: \(opened.lineNumbers.rows.filter(\.at))")
        #expect(opened.caretLine == 42 && opened.atLine == 42)
        opened.editor.setSelectedRange(NSRange(location: (text as NSString).length, length: 0))
        #expect(opened.caretLine == 61, "the caret at the end is on line \(opened.caretLine)")
        opened.editor.setSelectedRange(NSRange(location: 0, length: 0))
        #expect(opened.caretLine == 1)
        #expect(!bench.filesPanelShown, "the files panel was shown")
        bench.openTerminalPath(in: "src/Gone.java:3: error: cannot find symbol", at: 3, from: terminal)
        #expect(await RunnerTabTests.until(10) { terminal.status.says == "No file at src/Gone.java in dock." }, "said: \(terminal.status.says ?? "nothing")")
        #expect(pane()?.path == FileName(FilesPanelTests.root + "/src/App.java"))
    }

    /// Half of a character (a lone surrogate) is never saved as U+FFFD: the save says where it is
    /// and writes nothing.
    @Test func halfACharacterIsNeverSavedAsAnother() async throws {
        #expect(FilePane.loneSurrogateLine(NSString(characters: [0x61, 0x0A, 0xD83D, 0x61], length: 4)) == 2)
        #expect(FilePane.loneSurrogateLine(NSString(characters: [0x61, 0xD83D, 0xDE00, 0x0A, 0xDE00], length: 5)) == 2)
        #expect(FilePane.loneSurrogateLine(NSString(characters: [0x61, 0xD83D, 0xDE00], length: 3)) == nil)
        let (door, _, _, _, pane) = try await Self.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        let sent = Self.watchSends(pane)
        let storage = try #require(pane.editor.textStorage)
        CFStringInsert(storage.mutableString as CFMutableString, 0, NSString(characters: [0xD83D], length: 1) as CFString)
        try #require(FilePane.loneSurrogateLine(storage.mutableString) == 1, "the storage did not keep half a character")
        pane.saveDocument(nil)
        #expect(Self.writes(sent()).isEmpty && pane.noteWords == "Line 1 holds half of a character, which can't be saved as text. Nothing was saved." && door.file(Self.path) == Array("one\n".utf8))
    }

    /// The sheet's three actions stand level: all outlined, Keep Mine in the error ink, none filled,
    /// and ⏎ chooses none.
    @Test func theSheetsActionsAreAllOutlined() throws {
        let fonts = try LabelFont(directory: LookTests.fonts)
        for deleted in [false, true] {
            let sheet = ConflictSheet(name: "tide.ts", theirs: "theirs\n", theirsRead: Date(), yours: "mine\n", deleted: deleted, look: .light, mono: fonts)
            #expect(sheet.buttons.map(\.kind) == [.secondary, .secondary, .destructiveQuiet] && sheet.buttons.allSatisfy { $0.keyEquivalent.isEmpty && !$0.isDefault })
        }
        let again = ConflictSheet(name: "tide.ts", theirs: "B\n", theirsRead: Date(), yours: "mine\n", deleted: false, again: true, look: .light, mono: fonts)
        #expect(again.headingWords == "tide.ts changed again on the workspace")
    }

    /// M14a: a file that is not text is one sentence and Copy Path.
    @Test func aFileThatIsNotTextIsASentence() async throws {
        let (_, _, _, _, pane) = try await Self.opened([0x89, 0x50, 0x4E, 0x47, 0, 1, 2, 3])
        #expect(await RunnerTabTests.until(10) { pane.sentenceWords == "tide.ts isn't text: a PNG image, 8 bytes." })
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
        #expect(await RunnerTabTests.until(10) { pane.noteWords == "Changed on the workspace while you typed." })
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
