import AppKit
import Foundation
import SailFiles
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminalWire
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
        pane.discardPressed()
        #expect(await RunnerTabTests.until(10) { pane.editor.string == "theirs\n" && pane.file.clean && panel.unsaved == nil })
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
