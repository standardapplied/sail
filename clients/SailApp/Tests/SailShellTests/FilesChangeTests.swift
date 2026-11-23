import AppKit
import Foundation
import SailControls
import SailFiles
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminalWire
import Testing

/// Making, renaming, moving and deleting in the tree (M19-M25, M45, M59): each action one request,
/// its row there only once the workspace lists it, never replacing a name, its refusal said where it
/// was asked; the delete asked once in `error`; Reveal pastes a quoted path and runs nothing.
@MainActor @Suite(.serialized) struct FilesChangeTests {
    static let root = FilesPanelTests.root
    static var windows: [NSWindow] = []

    /// The panel played by hand in a window of its own, so the name field can hold the keys; every
    /// frame it sends kept in `sent`.
    @MainActor final class Played {
        let panel: FilesPanel
        var sent: [Frame] = []

        init(_ entries: [FileEntry]) throws {
            panel = try FilesPanelTests.played(entries)
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: FilesPanel.width, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
            window.contentView = panel
            FilesChangeTests.windows.append(window)
            panel.layoutSubtreeIfNeeded()
            window.makeFirstResponder(panel.list)
            panel.onSent = { [weak self] in self?.sent += $0 }
        }

        /// The changes sent, by id.
        var changes: [(id: Int32, frame: Frame)] {
            sent.compactMap { frame in
                switch frame {
                case let .filesCreate(id, _, _), let .filesMove(id, _, _), let .filesRemove(id, _): (id, frame)
                default: nil
                }
            }
        }

        /// Types `name` in the open field and presses ⏎.
        func type(_ name: String) throws {
            let field = try #require(panel.nameRow)
            field.field.field.stringValue = name
            field.onCommit()
        }

        /// The workspace lists `folder` anew as `entries`, as the watch has it do.
        func listed(_ folder: String, _ entries: [FileEntry]) throws {
            panel.heard(.frame(.filesChanged(paths: [FileName(folder)])))
            let id = try #require(sent.reversed().lazy.compactMap { if case let .filesList(id, path) = $0, path == FileName(folder) { id } else { nil } }.first)
            panel.heard(.frame(.filesListing(FileListing(id: id, version: "v\(id)", entries: entries, more: false, truncated: false))))
        }
    }

    static func file(_ name: String) -> FileEntry {
        FilesPanelTests.file(name)
    }

    static func folder(_ name: String) -> FileEntry {
        FilesPanelTests.folder(name)
    }

    /// M19, F11: New File's name made by one create; nothing is added before the workspace answers,
    /// a second ⏎ meanwhile sends nothing, and the row is chosen once the watch lists it, whichever
    /// comes first, the answer or the listing.
    @Test(arguments: [false, true])
    func aNewFileIsMadeAndChosenByTheWatch(_ listedFirst: Bool) throws {
        let played = try Played([Self.folder("src"), Self.file("README.md")])
        let panel = played.panel
        panel.newFile(nil)
        #expect(panel.list.numberOfRows == 3 && panel.nameRow?.holdsKeys == true, "the name field did not open with the keys")
        #expect(panel.nameRow?.field.field.accessibilityLabel() == "New file name", "VoiceOver names the field holding the keys nothing")
        try played.type("notes.md")
        let made = try #require(played.changes.first)
        #expect(played.changes.count == 1 && made.frame == .filesCreate(id: made.id, path: FileName(Self.root + "/notes.md"), folder: false))
        try played.type("notes.md")
        #expect(played.changes.count == 1, "a second ⏎ while the first was unanswered sent again")
        let ids = panel.list.entries.compactMap { if case let .item(item) = $0 { item.id } else { nil } }
        #expect(panel.tree.rows.map(\.name) == ["src", "README.md"] && ids.count == 3 && !ids.contains(Self.root + "/notes.md"), "a row was added before the workspace answered: \(ids)")
        let listing = [Self.folder("src"), Self.file("notes.md"), Self.file("README.md")]
        if listedFirst { try played.listed(Self.root, listing) }
        panel.heard(.frame(.filesDone(id: made.id)))
        #expect(panel.naming == nil && panel.list.firstResponderIsList, "the field stayed, or the keys did not come back to the tree")
        if !listedFirst {
            #expect(panel.list.chosen != Self.root + "/notes.md", "chosen before the watch listed it")
            try played.listed(Self.root, listing)
        }
        #expect(panel.list.chosen == Self.root + "/notes.md", "the new file is not chosen: \(panel.list.chosen ?? "nothing")")
    }

    /// New Folder from a file's row makes in that file's folder, the field at its top; Esc leaves
    /// nothing made and gives the keys back.
    @Test func newFolderGoesInTheRowsFolderAndEscMakesNothing() throws {
        let played = try Played([Self.folder("src")])
        let panel = played.panel
        panel.toggle(FileName(Self.root + "/src"))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.file("tide.ts")], more: false, truncated: false))))
        panel.list.choose(Self.root + "/src/tide.ts")
        panel.newFolder(nil)
        #expect(panel.naming?.folder == FileName(Self.root + "/src") && panel.list.entries.count == 3, "the field is not in src")
        #expect(panel.nameRow?.field.field.accessibilityLabel() == "New folder name")
        try played.type("lib")
        let made = try #require(played.changes.first?.id)
        #expect(played.changes.map(\.frame) == [.filesCreate(id: made, path: FileName(Self.root + "/src/lib"), folder: true)])
        let second = try Played([Self.file("README.md")])
        second.panel.newFile(nil)
        second.panel.nameRow?.field.field.stringValue = "draft.md"
        second.panel.nameRow?.onCancel()
        #expect(second.changes.isEmpty && second.panel.naming == nil && second.panel.list.numberOfRows == 1 && second.panel.list.firstResponderIsList, "Esc made something or kept the field")
    }

    /// M20, F11: a taken name is said under the field, which stays open with the name chosen; a
    /// rename onto a taken name sends one move and never a remove, and the old name stays.
    @Test func aTakenNameIsSaidUnderTheFieldAndNothingIsReplaced() throws {
        let played = try Played([Self.file("notes.md"), Self.file("README.md")])
        let panel = played.panel
        panel.list.choose(Self.root + "/README.md")
        panel.renameFile(nil)
        #expect(panel.nameRow?.text == "README.md" && panel.nameRow?.field.field.accessibilityLabel() == "New name")
        try played.type("notes.md")
        let move = try #require(played.changes.first)
        #expect(played.changes.map(\.frame) == [.filesMove(id: move.id, from: FileName(Self.root + "/README.md"), to: FileName(Self.root + "/notes.md"))])
        panel.heard(.frame(.filesRefused(id: move.id, kind: .known(.exists), message: "notes.md is already there.")))
        let field = try #require(panel.nameRow)
        #expect(field.refusal == "notes.md is already there." && field.holdsKeys, "the refusal is not under the field holding the keys: \(field.refusal ?? "none")")
        #expect(field.field.field.currentEditor()?.selectedRange == NSRange(location: 0, length: 8), "the refused name is not chosen")
        #expect(panel.tree.rows.map(\.name) == ["notes.md", "README.md"] && !played.sent.contains { if case .filesRemove = $0 { true } else { false } })
        panel.layoutSubtreeIfNeeded()
        let at = try #require(panel.list.entries.firstIndex { if case let .item(item) = $0 { item.id == panel.naming?.id } else { false } })
        #expect(Double(panel.list.rect(ofRow: at).height) >= field.height && field.height > RowStyle.tree.height, "the sentence under the field is cut")
        field.field.field.stringValue = "notes2.md"
        field.onEdit()
        #expect(panel.nameRow?.refusal == nil, "typing again left the refusal")
    }

    /// A name Sail does not make is refused in place before any frame; a rename to its own name
    /// closes the field and sends nothing.
    @Test func aNameNotOneEntrysIsRefusedInPlace() throws {
        let played = try Played([Self.file("README.md")])
        let panel = played.panel
        panel.newFile(nil)
        try played.type("../escape")
        #expect(played.changes.isEmpty && panel.nameRow?.refusal == FileActions.notAName)
        panel.nameRow?.onCancel()
        panel.list.choose(Self.root + "/README.md")
        panel.renameFile(nil)
        try played.type("README.md")
        #expect(played.changes.isEmpty && panel.naming == nil, "a rename to its own name was sent or left open")
    }

    /// M21, M22: rows dragged into a folder move by one move each, a row already there stays; a
    /// folder dropped into itself or its child is refused before any frame, in the line.
    @Test func aDragMovesByOneMoveAndNeverIntoItself() throws {
        let played = try Played([Self.folder("src"), Self.file("a.ts"), Self.file("b.ts")])
        let panel = played.panel
        let src = Self.root + "/src"
        panel.toggle(FileName(src))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.folder("lib")], more: false, truncated: false))))
        #expect(panel.landing(Self.root + "/a.ts") == "" && panel.landing(src + "/lib") == src + "/lib" && panel.landing(nil) == "")
        panel.drop([Self.root + "/a.ts", Self.root + "/b.ts"], into: src)
        let first = try #require(played.changes.first?.id), last = try #require(played.changes.last?.id)
        #expect(played.changes.map(\.frame) == [
            .filesMove(id: first, from: FileName(Self.root + "/a.ts"), to: FileName(src + "/a.ts")),
            .filesMove(id: last, from: FileName(Self.root + "/b.ts"), to: FileName(src + "/b.ts")),
        ])
        panel.drop([src + "/lib"], into: src)
        #expect(played.changes.count == 2, "a row dropped where it is was moved")
        panel.drop([src], into: src + "/lib")
        #expect(played.changes.count == 2 && panel.shownLine?.words == "A folder cannot be moved into itself.", "line: \(panel.shownLine?.words ?? "none")")
        panel.heard(.frame(.filesDone(id: first)))
        #expect(panel.choosing == FileName(src + "/a.ts"))
    }

    /// M23, M24: Delete asks once, its button in `error` and Cancel the default, naming a folder
    /// with what it holds or counting several; Cancel sends nothing; a folder partly removed is said.
    @Test func deleteAsksOnceInErrorAndCancelKeepsAll() async throws {
        let played = try Played([Self.folder("src"), Self.file("a.ts"), Self.file("b.ts")])
        let panel = played.panel
        panel.list.choose(Self.root + "/src")
        panel.deleteFiles(nil)
        let sheet = try #require(panel.question)
        #expect(sheet.title.stringValue == "Delete src and everything in it?" && sheet.body.stringValue == "This cannot be undone.")
        #expect(sheet.confirm.kind == .destructive && sheet.confirm.title == "Delete" && sheet.cancel.isDefault && !sheet.confirm.isDefault, "Return would delete")
        #expect(played.changes.isEmpty, "deleted before the answer")
        sheet.cancel.performClick(nil)
        #expect(await RunnerTabTests.until(5) { panel.question == nil } && played.changes.isEmpty, "Cancel deleted")
        panel.list.selectRowIndexes(IndexSet([1, 2]), byExtendingSelection: false)
        panel.deleteFiles(nil)
        let several = try #require(panel.question)
        #expect(several.title.stringValue == "Delete 2 items?")
        several.confirm.performClick(nil)
        try #require(await RunnerTabTests.until(5) { played.changes.count == 2 })
        let first = played.changes[0].id, second = played.changes[1].id
        #expect(played.changes.map(\.frame) == [.filesRemove(id: first, path: FileName(Self.root + "/a.ts")), .filesRemove(id: second, path: FileName(Self.root + "/b.ts"))])
        panel.heard(.frame(.filesRefused(id: first, kind: .known(.partlyRemoved), message: "Some of a.ts could not be deleted.")))
        #expect(panel.shownLine?.words == "Some of a.ts could not be deleted.")
    }

    /// M25: deleting what holds an open file's unsaved text says so; Cancel keeps all of it.
    @Test func deletingAnUnsavedFileSaysSo() throws {
        let played = try Played([Self.folder("src")])
        let panel = played.panel
        panel.unsaved = FileName(Self.root + "/src/tide.ts")
        panel.list.choose(Self.root + "/src")
        panel.deleteFiles(nil)
        let sheet = try #require(panel.question)
        #expect(sheet.body.stringValue == "This cannot be undone. tide.ts has unsaved changes; they stay in its pane.")
        sheet.cancel.performClick(nil)
        #expect(played.changes.isEmpty && panel.unsaved == FileName(Self.root + "/src/tide.ts"))
    }

    /// M45: Reveal pastes exactly the quoted paths acted on and no newline, and with no terminal in
    /// the tab it is off, in the row's menu and the File menu alike.
    @Test func revealPastesTheQuotedPathAndNothingElse() throws {
        let played = try Played([Self.file("my notes.md"), Self.file("tide.ts")])
        let panel = played.panel
        var pasted: [String] = []
        panel.list.choose(Self.root + "/my notes.md")
        let reveal = NSMenuItem(title: "Reveal in Terminal", action: #selector(FilesPanel.revealInTerminal(_:)), keyEquivalent: "")
        let action = try #require(panel.rowActions(FileName(Self.root + "/my notes.md")).first { $0.title == "Reveal in Terminal" })
        let offered = { panel.list.rowView(atRow: 0, makeIfNecessary: true)?.accessibilityCustomActions()?.map(\.name) ?? [] }
        #expect(!panel.validateMenuItem(reveal) && !action.enabled(), "Reveal is on with no terminal in the tab")
        #expect(offered() == ["New File", "New Folder", "Rename", "Delete"], "VoiceOver offers Reveal with no terminal in the tab: \(offered())")
        panel.pasteInTerminal = { { pasted.append($0) } }
        #expect(panel.validateMenuItem(reveal) && action.enabled() && offered().contains("Reveal in Terminal"))
        panel.revealInTerminal(nil)
        #expect(pasted == ["'/home/dev/workspace/my notes.md'"], "pasted \(pasted)")
        panel.list.selectRowIndexes(IndexSet([0, 1]), byExtendingSelection: false)
        action.run()
        #expect(pasted.last == "'/home/dev/workspace/my notes.md' /home/dev/workspace/tide.ts" && !pasted.joined().contains("\n"), "pasted \(pasted)")
        #expect(played.sent.isEmpty, "Reveal sent something to the workspace")
    }

    /// A folder dragged with a row inside it moves once, the row going with it.
    @Test func aFolderDraggedWithItsChildMovesOnce() throws {
        let played = try Played([Self.folder("lib"), Self.folder("src")])
        let panel = played.panel
        let src = Self.root + "/src"
        panel.toggle(FileName(src))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.file("a.ts")], more: false, truncated: false))))
        panel.drop([src, src + "/a.ts"], into: Self.root + "/lib")
        let move = try #require(played.changes.first?.id)
        #expect(played.changes.map(\.frame) == [.filesMove(id: move, from: FileName(src), to: FileName(Self.root + "/lib/src"))], "\(played.changes.map(\.frame))")
    }

    /// Delete and Reveal of a folder and a row inside it, both chosen, act on the folder once.
    @Test func aFolderChosenWithItsChildIsDeletedAndRevealedOnce() async throws {
        let played = try Played([Self.folder("src")])
        let panel = played.panel
        let src = Self.root + "/src"
        panel.toggle(FileName(src))
        panel.heard(.frame(.filesListing(FileListing(id: 2, version: "s1", entries: [Self.file("a.ts")], more: false, truncated: false))))
        panel.list.selectRowIndexes(IndexSet([0, 1]), byExtendingSelection: false)
        var pasted: [String] = []
        panel.pasteInTerminal = { { pasted.append($0) } }
        panel.revealInTerminal(nil)
        #expect(pasted == [src], "pasted \(pasted)")
        panel.deleteFiles(nil)
        let sheet = try #require(panel.question)
        #expect(sheet.title.stringValue == "Delete src and everything in it?")
        sheet.confirm.performClick(nil)
        try #require(await RunnerTabTests.until(5) { !played.changes.isEmpty })
        #expect(played.changes.map(\.frame) == [.filesRemove(id: played.changes[0].id, path: FileName(src))], "\(played.changes.map(\.frame))")
    }

    /// A move answered while its folder is shut never takes the person's later choice.
    @Test func aDropIntoAShutFolderDoesNotTakeTheChoiceLater() throws {
        let played = try Played([Self.folder("src"), Self.file("a.ts"), Self.file("README.md")])
        let panel = played.panel
        let src = Self.root + "/src"
        panel.drop([Self.root + "/a.ts"], into: src)
        let move = try #require(played.changes.first?.id)
        panel.heard(.frame(.filesDone(id: move)))
        try played.listed(Self.root, [Self.folder("src"), Self.file("README.md")])
        panel.list.choose(Self.root + "/README.md")
        panel.toggle(FileName(src))
        let id = try #require(played.sent.reversed().lazy.compactMap { if case let .filesList(id, path) = $0, path == FileName(src) { id } else { nil } }.first)
        panel.heard(.frame(.filesListing(FileListing(id: id, version: "s1", entries: [Self.file("a.ts")], more: false, truncated: false))))
        #expect(panel.list.chosen == Self.root + "/README.md", "the move took the choice: \(panel.list.chosen ?? "nothing")")
    }

    /// An action's refusal is said ahead of the tree's own standing line, behind only what the
    /// connection says while it is down.
    @Test func aRefusalIsSaidWhileAFolderKeepsChanging() throws {
        let played = try Played([Self.folder("src"), Self.file("a.ts")])
        let panel = played.panel
        let src = FileName(Self.root + "/src")
        panel.toggle(src)
        for _ in 0 ..< 3 {
            let id = try #require(played.sent.reversed().lazy.compactMap { if case let .filesList(id, path) = $0, path == src { id } else { nil } }.first)
            panel.heard(.frame(.filesRefused(id: id, kind: .known(.changed), message: "src changed while it was read; read it again.")))
            panel.heard(.frame(.filesTick))
        }
        #expect(panel.shownLine?.words == "src keeps changing.")
        panel.drop([src.key], into: src.key)
        #expect(played.changes.isEmpty)
        #expect(panel.shownLine?.words == FileActions.intoItself, "line: \(panel.shownLine?.words ?? "none")")
        panel.heard(.phase(.stopped("dock is stopped.")))
        #expect(panel.shownLine?.words == "dock is stopped." && panel.shownLine?.action == .start, "the refusal hid the stopped workspace's Start")
    }

    /// An answer is matched to the field by its request: a rename closed with Esc and typed again
    /// is not refused by the first one's answer.
    @Test func anAnswerFindsItsOwnField() throws {
        let played = try Played([Self.file("notes.md"), Self.file("README.md")])
        let panel = played.panel
        panel.list.choose(Self.root + "/README.md")
        panel.renameFile(nil)
        try played.type("notes.md")
        panel.nameRow?.onCancel()
        panel.renameFile(nil)
        try played.type("notes.md")
        let first = try #require(played.changes.first?.id), second = try #require(played.changes.last?.id)
        #expect(first != second)
        panel.heard(.frame(.filesRefused(id: first, kind: .known(.exists), message: "notes.md is already there.")))
        #expect(panel.nameRow?.refusal == nil && panel.naming?.asked != nil, "the first rename's answer refused the second's field")
        panel.heard(.frame(.filesDone(id: second)))
        #expect(panel.naming == nil, "the second rename's answer did not close its field")
    }

    /// A delete question still asking when the panel closes is withdrawn, deleting nothing.
    @Test func theDeleteQuestionGoesWithThePanel() async throws {
        let played = try Played([Self.file("README.md")])
        let panel = played.panel
        panel.list.choose(Self.root + "/README.md")
        panel.deleteFiles(nil)
        _ = try #require(panel.question)
        panel.close()
        #expect(panel.question == nil && played.changes.isEmpty, "the question outlived its panel, or deleted")
        #expect(await RunnerTabTests.until(5) { panel.window?.attachedSheet == nil }, "the sheet stayed on the window")
    }

    /// The File menu's tree items act only while the tree holds the keys, so ⌘⌫, F2 and the rest
    /// reach a name field, the file pane or a terminal; New works with no row chosen.
    @Test func theFileMenusItemsActOnlyWhileTheTreeHoldsTheKeys() throws {
        let played = try Played([])
        let panel = played.panel
        let item = { (action: Selector) in NSMenuItem(title: "", action: action, keyEquivalent: "") }
        #expect(panel.validateMenuItem(item(#selector(FilesPanel.newFile(_:)))) && !panel.validateMenuItem(item(#selector(FilesPanel.deleteFiles(_:)))))
        panel.newFile(nil)
        #expect(!panel.validateMenuItem(item(#selector(FilesPanel.newFile(_:)))), "a menu item acts while the name field holds the keys")
        panel.nameRow?.onCancel()
        panel.window?.makeFirstResponder(nil)
        #expect(!panel.validateMenuItem(item(#selector(FilesPanel.newFolder(_:)))), "a menu item acts while the tree does not hold the keys")
    }

    /// A change of look or direction draws every row again, and an open name field keeps the keys
    /// through it.
    @Test func aChangeOfLookDrawsTheRowsAgainAndTheFieldKeepsTheKeys() throws {
        let played = try Played([Self.file("README.md")])
        let panel = played.panel
        let fills = panel.list.fills
        panel.look = .dark
        #expect(panel.list.fills > fills, "a change of look left the rows drawn in the old look")
        panel.newFile(nil)
        let filled = panel.list.fills
        panel.userInterfaceLayoutDirection = .rightToLeft
        #expect(panel.list.fills > filled && panel.naming != nil && panel.nameRow?.holdsKeys == true, "the field lost the keys to a change of direction")
    }

    /// M59, F10 against the door: renaming the open file with unsaved text takes the pane with it,
    /// read at the new name, its text kept and still unsaved, its dot on the new row.
    @Test func aRenamedOpenFileKeepsItsUnsavedText() async throws {
        let (door, _, _, panel, pane) = try await FilePaneTests.opened(Array("one\n".utf8))
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        pane.editor.insertText("mine", replacementRange: NSRange(location: 4, length: 0))
        #expect(pane.unsaved)
        panel.list.choose(FilePaneTests.path)
        panel.renameFile(nil)
        panel.nameRow?.field.field.stringValue = "tides.ts"
        panel.nameRow?.onCommit()
        let moved = FileName(Self.root + "/tides.ts")
        #expect(await RunnerTabTests.until(10) { pane.path == moved && panel.list.chosen == moved.key }, "the pane or the choice did not follow: \(pane.path)")
        #expect(await RunnerTabTests.until(10) { door.filesReceived.contains { if case let .filesRead(_, path, _, _) = $0 { path == moved } else { false } } }, "never read at the new name")
        #expect(pane.unsaved && pane.editor.string == "one\nmine" && panel.unsaved == moved && pane.noteWords == nil, "the rename dropped the text, marked it saved, or said it changed")
        door.setFolder(Self.root, [Self.file("tides.ts"), Self.folder("locked")])
        door.refuseFolder(Self.root + "/locked", .notPermitted, saying: "The workspace's user may not open locked.")
        #expect(await RunnerTabTests.until(10) { panel.tree.rows.count == 2 })
        panel.toggle(FileName(Self.root + "/locked"))
        #expect(await RunnerTabTests.until(10) { panel.tree.refusals[FileName(Self.root + "/locked")] != nil }, "with a file open, a folder's refusal never reached the tree")
    }

    /// M59 through a folder: renaming the folder holding the open file takes the pane with it.
    @Test func aRenamedFolderTakesItsOpenFileWithIt() async throws {
        let (_, _, _, panel, pane) = try await FilePaneTests.opened(Array("one\n".utf8), in: "src")
        #expect(await RunnerTabTests.until(10) { pane.editor.isEditable })
        panel.list.choose(Self.root + "/src")
        panel.renameFile(nil)
        panel.nameRow?.field.field.stringValue = "lib"
        panel.nameRow?.onCommit()
        #expect(await RunnerTabTests.until(10) { pane.path == FileName(Self.root + "/lib/tide.ts") }, "the pane did not follow its folder: \(pane.path)")
    }
}

extension SailList {
    var firstResponderIsList: Bool {
        window?.firstResponder === self
    }
}
