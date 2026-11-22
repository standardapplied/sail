import AppKit
import SailControls
import SailFiles
import SailLook
import SailTerminalWire
import SailWorkbench

/// A workspace tab's file pane (``FilePane``): one a tab, at the terminals' trailing side behind a
/// divider that drags, its width remembered per workspace on this Mac. Where the tab cannot give
/// both sides their least, the pane takes the terminals' place; moving the keys to a terminal puts
/// it back behind, its text kept, and moving them to the pane brings it forward. The keys move
/// between the tree, the terminals and the pane by the commands that move them between panes;
/// ⌘W in the pane closes the file, never a terminal. A zoomed terminal hides the pane until the
/// zoom ends. Hiding the files panel leaves the pane open, and its connection with it.
extension WorkbenchView {
    static let filePaneWidthsKey = "files.paneWidths"
    /// The least the pane keeps; the terminals keep ``filesBeside``.
    static let filePaneLeast = 320.0

    private var filePaneWidths: [String: Double] {
        get { defaults.dictionary(forKey: Self.filePaneWidthsKey) as? [String: Double] ?? [:] }
        set { defaults.set(newValue, forKey: Self.filePaneWidthsKey) }
    }

    /// Opens `path` into the tab's file pane, at `line` when given: an unsaved pane on another file
    /// asks first (M47).
    func openFile(_ path: FileName, _ entry: FileEntry, in view: TabView, line: Int? = nil) {
        guard let panel = view.files else { return }
        if let pane = view.filePane {
            if pane.path == path {
                if let line { pane.go(toLine: line) }
                return focusFilePane(in: view)
            }
            return pane.askToLeave(then: { [weak self, weak view, weak pane] in
                pane?.finishClosing()
                if let view { self?.openFile(path, entry, in: view, line: line) }
            })
        }
        let pane = FilePane(path: path, entry: entry, workspace: panel.workspace, look: look, mono: labels)
        pane.wantsLayer = true
        pane.send = { [weak panel] frames in panel?.send(frames) }
        pane.onWatched = { [weak panel] watched in panel?.watchFile(watched) }
        pane.onUnsaved = { [weak panel] unsaved in panel?.unsaved = unsaved ? path : nil }
        pane.onClose = { [weak self, weak view, weak panel, weak pane] in
            panel?.unsaved = nil
            panel?.watchFile(nil)
            panel?.filePane = nil
            pane?.removeFromSuperview()
            view?.filePane = nil
            view?.fileDivider.isHidden = true
            self?.connectFiles()
            self?.apply()
            if let terminal = self?.focused { self?.window?.makeFirstResponder(terminal.terminal) }
        }
        pane.onCompare = { [weak self, weak pane] theirs, again in
            guard let self, let pane else { return }
            self.compare(pane, theirs: theirs, again: again)
        }
        panel.filePane = pane
        view.addSubview(pane)
        view.filePane = pane
        connectFiles()
        pane.open()
        if let line { pane.go(toLine: line) }
        focusFilePane(in: view)
    }

    /// A ⌘-click on a path in workspace terminal `pane` (its row's `text`, the 1-based character
    /// `column`): looked for on the workspace (``TerminalPath/Lookup``: the folder the terminal last
    /// reported, the root, then at most 16 folders under it) (M17); the first found opened in the
    /// tab's file pane at its line, the file already open only going there; none found, a refusal
    /// or the connection's end, the terminal's own card says so (M18). Nothing is typed into it.
    func openTerminalPath(in text: String, at column: Int, from pane: TerminalPane) {
        guard let found = TerminalPath.find(in: text, at: column), let tab = model.tabs.first(where: { $0.layout.leaves.contains(pane.id) }),
              let view = tabViews[tab.id], let (project, name) = workspace(of: tab) else { return }
        let panel = view.files ?? makeFiles(project: project, name: name, in: view)
        let cwd = model.panes[pane.id].flatMap { Self.reportedPath($0.reportedFolder) }
        panel.lookUp(found.path, cwd: cwd) { [weak self, weak view, weak pane] next in
            guard let self else { return }
            switch next {
            case let .found(path, entry):
                if let view { openFile(path, entry, in: view, line: found.line) }
            case let .said(words):
                if pane?.tell(words) != true { tell(words) }
            case .ask, .nothing:
                break
            }
            connectFiles()
        }
        connectFiles()
    }

    /// The path a workspace's shell reported as its folder (OSC 7): as written when absolute, else
    /// the path of its `file://` URL, whatever host the workspace names.
    static func reportedPath(_ reported: String) -> String? {
        if reported.hasPrefix("/") { return reported }
        guard reported.hasPrefix("file://") else { return nil }
        let rest = reported.dropFirst("file://".count)
        guard let slash = rest.firstIndex(of: "/") else { return nil }
        return String(rest[slash...]).removingPercentEncoding
    }

    /// The keys to the tab's file pane, brought forward where it takes the terminals' place.
    func focusFilePane(in view: TabView) {
        guard let pane = view.filePane else { return }
        view.filePaneInFront = true
        apply()
        window?.makeFirstResponder(pane.editor)
    }

    /// The selected tab's file pane, when it holds the keys, or when nothing in the window does and
    /// the keys were last in it: ⌘W then closes the file, never a terminal.
    var filePaneHoldingKeys: FilePane? {
        guard let tab = model.selectedTab, let pane = tabViews[tab.id]?.filePane, !pane.isHidden else { return nil }
        let holder = window?.firstResponder
        if let view = holder as? NSView { return view.isDescendant(of: pane) ? pane : nil }
        return pane.heldKeysLast ? pane : nil
    }

    /// How much of the tab's width the file pane and its divider take beside the terminals: none
    /// when it has none, is hidden by a zoom, or takes the terminals' place in a narrow tab.
    func filePaneGive(of tab: Tab, in view: TabView, panelWidth: Double) -> Double {
        guard view.filePane != nil, tab.zoomed == nil, let (project, name) = workspace(of: tab) else { return 0 }
        let total = Double(view.bounds.width) - panelWidth
        guard total >= Self.filePaneLeast + Self.filesBeside + DividerLine.width else { return 0 }
        let wanted = filePaneWidths["\(project)/\(name)"] ?? total / 2
        return min(max(wanted, Self.filePaneLeast), total - Self.filesBeside - DividerLine.width) + DividerLine.width
    }

    /// Places the tab's file pane and its divider beside the terminals' `room` (the bounds already
    /// shifted for both sides), or over it where `give` is none; frames only, as in a layout pass.
    func placeFilePane(of tab: Tab, in view: TabView, room: CGSize, give: Double) {
        guard let pane = view.filePane else { return }
        let width = Double(room.width), height = Double(room.height), rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        if pane.userInterfaceLayoutDirection != userInterfaceLayoutDirection { pane.userInterfaceLayoutDirection = userInterfaceLayoutDirection }
        pane.editor.userInterfaceLayoutDirection = .leftToRight
        guard tab.zoomed == nil else {
            pane.isHidden = true
            view.fileDivider.isHidden = true
            return
        }
        pane.covering = give == 0
        pane.onShowTerminal = { [weak self, weak view] in
            guard let self, let view, let tab = self.model.selectedTab else { return }
            self.keysToTerminal(tab.focused, in: view)
        }
        guard give > 0 else {
            pane.isHidden = !view.filePaneInFront
            view.fileDivider.isHidden = true
            let frame = NSRect(x: 0, y: 0, width: width, height: height)
            if pane.frame != frame { pane.frame = frame }
            return
        }
        pane.isHidden = false
        view.fileDivider.isHidden = false
        let paneWidth = give - DividerLine.width
        let paneFrame = NSRect(x: rightToLeft ? -give : width + DividerLine.width, y: 0, width: paneWidth, height: height)
        let dividerFrame = NSRect(x: rightToLeft ? -DividerLine.width : width, y: 0, width: DividerLine.width, height: height)
        if pane.frame != paneFrame { pane.frame = paneFrame }
        if view.fileDivider.frame != dividerFrame { view.fileDivider.frame = dividerFrame }
        view.fileDivider.look = look
        view.fileDivider.onDrag = { [weak self, weak view] delta in
            guard let self, let view, let tab = self.model.tabs.first(where: { self.tabViews[$0.id] === view }), let (project, name) = self.workspace(of: tab) else { return }
            let now = Double(view.filePane?.frame.width ?? 0)
            self.filePaneWidths["\(project)/\(name)"] = now + (rightToLeft ? delta : -delta)
            self.apply()
        }
    }

    /// Next or previous among the tree, the terminals in reading order and the file pane: answers
    /// whether the keys moved to or from the tree or the pane, leaving the terminals' own moves to
    /// the workbench.
    func moveKeysAmongSides(next: Bool) -> Bool {
        guard let tab = model.selectedTab, let view = tabViews[tab.id] else { return false }
        let tree = filesHoldTheKeys, pane = filePaneHoldingKeys != nil
        let leaves = tab.layout.leaves, treeShown = view.files.map { !$0.isHidden } ?? false
        if pane {
            if next, treeShown, let panel = view.files { window?.makeFirstResponder(panel.list) } else { keysToTerminal(next ? leaves.first : tab.focused, in: view) }
            return true
        }
        if tree {
            if next { keysToTerminal(leaves.first, in: view) } else if view.filePane != nil { focusFilePane(in: view) } else { keysToTerminal(leaves.last, in: view) }
            return true
        }
        if next, tab.focused == leaves.last, view.filePane != nil {
            focusFilePane(in: view)
            return true
        }
        if !next, tab.focused == leaves.first, treeShown, let panel = view.files {
            window?.makeFirstResponder(panel.list)
            return true
        }
        return false
    }

    /// A move toward a side: from the pane toward the terminals goes to the focused terminal; from
    /// the tree toward them likewise; from a terminal at the edge, to the side that is there.
    func moveKeysTowardSide(_ direction: Direction, focusMoved: Bool) -> Bool {
        guard let tab = model.selectedTab, let view = tabViews[tab.id], direction == .left || direction == .right else { return false }
        let trailing = (direction == .right) != (userInterfaceLayoutDirection == .rightToLeft)
        if filePaneHoldingKeys != nil {
            guard !trailing else { return true }
            keysToTerminal(tab.focused, in: view)
            return true
        }
        if filesHoldTheKeys {
            guard trailing else { return true }
            keysToTerminal(tab.focused, in: view)
            return true
        }
        guard !focusMoved else { return false }
        if trailing, view.filePane != nil {
            focusFilePane(in: view)
            return true
        }
        if !trailing, let panel = view.files, !panel.isHidden {
            window?.makeFirstResponder(panel.list)
            return true
        }
        return false
    }

    func keysToTerminal(_ id: PaneID?, in view: TabView) {
        view.filePaneInFront = false
        if let id { change { $0.focus(id) } } else { apply() }
        if let terminal = focused { window?.makeFirstResponder(terminal.terminal) }
    }

    /// Before `tab` closes: its file pane's unsaved text asks first, then `close` goes on.
    func askAboutFile(in tab: Tab, then close: @escaping @MainActor () -> Void) {
        guard let pane = tabViews[tab.id]?.filePane else { return close() }
        pane.askToLeave(then: close)
    }

    /// Shows tab `id`, as a question about its file pane is asked over it.
    func show(_ id: TabID) {
        change { $0.select(id) }
    }

    /// The unsaved file panes, each with its tab: what quitting asks about, one at a time.
    var unsavedFilePanes: [(TabID, FilePane)] {
        model.tabs.compactMap { tab in tabViews[tab.id]?.filePane.flatMap { $0.unsaved ? (tab.id, $0) : nil } }
    }

    /// The conflict sheet over the window: `theirs` and the pane's text side by side; Keep Mine,
    /// Keep Theirs, Cancel, with no default (M27, M48). Its choices act on the version it shows,
    /// whatever the workspace read while it was up; nil `theirs` is a deleted file.
    func compare(_ pane: FilePane, theirs: OpenFile.Theirs?, again: Bool) {
        guard let window else { return }
        let sheet = ConflictSheet(name: pane.name, theirs: theirs?.text ?? "", theirsRead: pane.theirsRead, yours: pane.editor.string, deleted: theirs == nil, again: again, look: look, mono: labels)
        sheet.onChoice = { [weak pane, weak window, weak sheet] choice in
            if let sheet { window?.endSheet(sheet) }
            switch choice {
            case .keepMine: if let theirs { pane?.keepMine(against: theirs) }
            case .keepTheirs: if let theirs { pane?.keepTheirs(theirs) }
            case .saveAnyway: pane?.saveAnyway()
            case .discard: pane?.discardPressed()
            case .cancel: break
            }
        }
        window.beginSheet(sheet)
    }
}

/// The 1 pt line between the terminals and the file pane, on a strip as wide as the pointer's hit
/// area, which drags: its colour its layer's, so a change of look paints nothing.
@MainActor
final class DividerLine: NSView {
    static let width = 6.0
    var onDrag: @MainActor (Double) -> Void = { _ in }
    var look = Look.light {
        didSet { line.layer?.backgroundColor = NSColor(look.separator).cgColor }
    }

    private let line = NSView()
    private var last: Double?

    override init(frame: NSRect) {
        super.init(frame: frame)
        line.wantsLayer = true
        line.layer?.backgroundColor = NSColor(look.separator).cgColor
        addSubview(line)
        setAccessibilityElement(true)
        setAccessibilityRole(.splitter)
        setAccessibilityLabel("Divider between the terminals and the file")
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    override func layout() {
        super.layout()
        line.frame = NSRect(x: (Double(bounds.width) - 1) / 2, y: 0, width: 1, height: Double(bounds.height))
    }

    override func resetCursorRects() {
        addCursorRect(bounds, cursor: .resizeLeftRight)
    }

    override func mouseDown(with event: NSEvent) {
        last = Double(event.locationInWindow.x)
    }

    override func mouseDragged(with event: NSEvent) {
        let at = Double(event.locationInWindow.x)
        if let last { onDrag(at - last) }
        last = at
    }

    override func mouseUp(with _: NSEvent) {
        last = nil
    }
}

/// What the drive reads of the selected tab's file pane.
/// Every field costs nothing in the file's size, a test holding it so: the drive reads it while it
/// times a load, and a probe that slows what it measures hides the figures. What would cost the
/// file's size is read only when asked (``theirs``, ``WorkbenchView/fileLines``).
package struct FilePaneShown {
    package let view: NSView
    package let editor: NSTextView
    /// The text, bridged from the view's storage, not copied.
    package let text: String
    package let clean: Bool
    package let editable: Bool
    package let note: String?
    package let sentence: String?
    let readTheirs: @MainActor () -> String?

    /// The workspace's bytes the pane holds for Compare, as text, decoded when asked.
    @MainActor package var theirs: String? {
        readTheirs()
    }
}

extension WorkbenchView {
    package var filePaneShown: FilePaneShown? {
        guard let tab = model.selectedTab, let pane = tabViews[tab.id]?.filePane else { return nil }
        return FilePaneShown(view: pane, editor: pane.editor, text: pane.editor.string, clean: !pane.unsaved, editable: pane.editor.isEditable, note: pane.noteWords, sentence: pane.sentenceWords, readTheirs: { [weak pane] in pane?.theirs?.text })
    }

    /// The line a ⌘-click took the selected tab's file pane to, and the line its caret is on: asked
    /// apart from ``filePaneShown``, which the drive reads while it times a load.
    package var fileLines: (at: Int?, caret: Int)? {
        guard let pane = model.selectedTab.flatMap({ tabViews[$0.id]?.filePane }) else { return nil }
        return (pane.atLine, pane.caretLine)
    }

    /// Opens `path` from the selected tab's tree into its file pane, as ⏎ on its row does.
    package func openFile(at path: String) -> Bool {
        guard let tab = model.selectedTab, let panel = tabViews[tab.id]?.files else { return false }
        return panel.openFile(FileName(path))
    }

    /// The file pane's ×, as pressed: an unsaved pane asks first.
    package func closeFile() -> Bool {
        guard let pane = model.selectedTab.flatMap({ tabViews[$0.id]?.filePane }) else { return false }
        pane.closePressed()
        return true
    }

    /// ⌘S in the selected tab's file pane.
    package func saveFile() -> Bool {
        guard let pane = model.selectedTab.flatMap({ tabViews[$0.id]?.filePane }) else { return false }
        pane.saveDocument(nil)
        return true
    }

    /// The selected tab's terminal pane focused, and its file pane: what the look check watches.
    package var fileAndTerminal: (terminal: TerminalPane, pane: NSView)? {
        guard let terminal = focused, let pane = model.selectedTab.flatMap({ tabViews[$0.id]?.filePane }) else { return nil }
        return (terminal, pane)
    }

    /// Compare, as its button is pressed on the one line.
    package func compareFile() -> Bool {
        guard let pane = model.selectedTab.flatMap({ tabViews[$0.id]?.filePane }), let theirs = pane.theirs else { return false }
        compare(pane, theirs: theirs, again: false)
        return true
    }

    /// The conflict sheet's actions, as its buttons are titled; none while no sheet is up.
    package var sheetOffers: [String] {
        (window?.attachedSheet as? ConflictSheet)?.buttons.map(\.title) ?? []
    }

    /// The conflict sheet's heading, nil while none is up.
    package var sheetHeading: String? {
        (window?.attachedSheet as? ConflictSheet)?.headingWords
    }

    /// The sheet up over the window: a new one is another object.
    package var sheetUp: NSWindow? {
        window?.attachedSheet
    }

    /// Presses the conflict sheet's button titled `title`.
    package func pressSheet(_ title: String) -> Bool {
        guard let button = (window?.attachedSheet as? ConflictSheet)?.buttons.first(where: { $0.title == title }) else { return false }
        button.performClick(nil)
        return true
    }
}
