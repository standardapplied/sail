import AppKit
import SailControls
import SailFiles
import SailLook
import SailTerminal
import SailTerminalWire
import SailWorkbench

/// A workspace tab's files panel (``FilesPanel``): 236 pt at the tab's leading edge, beside its
/// terminals, or over them in a tab too narrow to give the terminals their least room beside it.
/// ⌃⌘S shows and hides it, remembered per workspace on this Mac. A tab of this Mac has none: files
/// are for workspaces.
extension WorkbenchView {
    static let filesShownKey = "files.shown"
    /// The least the terminals keep beside the panel; under it the panel floats over them.
    static let filesBeside = 320.0

    /// The workspace the selected tab's terminals are in; nil for one of this Mac's.
    var selectedWorkspace: (project: String, name: String)? {
        model.selectedTab.flatMap(workspace(of:))
    }

    func workspace(of tab: Tab) -> (project: String, name: String)? {
        guard case let .workspace(project, name) = model.panes[tab.focused]?.place else { return nil }
        return (project, name)
    }

    private var filesShown: Set<String> {
        get { Set(defaults.stringArray(forKey: Self.filesShownKey) ?? []) }
        set { defaults.set(newValue.sorted(), forKey: Self.filesShownKey) }
    }

    private static func key(_ project: String, _ name: String) -> String {
        "\(project)/\(name)"
    }

    /// Whether the selected tab's files panel is shown.
    var filesPanelShown: Bool {
        guard let (project, name) = selectedWorkspace else { return false }
        return filesShown.contains(Self.key(project, name))
    }

    /// ⌃⌘S in a workspace's tab: its files panel shown or put away, remembered for that workspace.
    @objc func toggleFiles(_: Any?) {
        guard let tab = model.selectedTab, let (project, name) = workspace(of: tab), let view = tabViews[tab.id] else { return }
        let key = Self.key(project, name)
        if filesShown.contains(key) { filesShown.remove(key) } else { filesShown.insert(key) }
        apply()
        if filesPanelShown, let panel = view.files { window?.makeFirstResponder(panel.list) }
    }

    /// Whether the selected tab's files panel holds the keys: drawing the workbench again leaves them
    /// there, so a change in the terminals never takes them from the tree.
    var filesHoldTheKeys: Bool {
        guard let tab = model.selectedTab, let panel = tabViews[tab.id]?.files, !panel.isHidden,
              let responder = window?.firstResponder as? NSView else { return false }
        return responder.isDescendant(of: panel)
    }

    /// Escape puts away a panel floating over the terminals.
    func dismissFloatingFiles() {
        guard let tab = model.selectedTab, let view = tabViews[tab.id], view.files?.floating == true else { return }
        toggleFiles(nil)
        if let focused { window?.makeFirstResponder(focused.terminal) }
    }

    /// What View ▸ ⌃⌘S says and does in this section now: its title, and why it does nothing when
    /// it does nothing.
    var filesCommand: (title: String, enabled: Bool, reason: String?) {
        guard model.selectedTab != nil else { return ("Show Files", false, "Open a workspace's terminal to see its files.") }
        guard selectedWorkspace != nil else { return ("Show Files", false, "Files are for workspaces; this tab is this Mac's.") }
        return (filesPanelShown ? "Hide Files" : "Show Files", true, nil)
    }

    /// Makes each workspace tab's files panel the first time it is to show: outside any layout
    /// pass, so a pass only places it.
    func prepareFiles() {
        for tab in model.tabs {
            guard let (project, name) = workspace(of: tab), filesShown.contains(Self.key(project, name)), let view = tabViews[tab.id], view.files == nil else { continue }
            makeFiles(project: project, name: name, in: view)
        }
    }

    /// Connects the selected tab's files panel when shown and ends every other's connection, before
    /// the workbench is laid out, so the ticket's round trip runs while the terminals are laid out
    /// narrower beside it.
    /// Only files on screen are followed: the section shown, the window up and not in the Dock.
    /// `away` says the window went (closed or to the Dock, true) or came back (false), and holds.
    func connectFiles(away: Bool? = nil) {
        if let away { filesAway = away }
        let onScreen = !filesAway && !isHidden && window?.isMiniaturized != true
        for tab in model.tabs {
            guard let panel = tabViews[tab.id]?.files else { continue }
            let wanted = tabViews[tab.id]?.filePane != nil || panel.lookingUp || workspace(of: tab).map { filesShown.contains(Self.key($0.project, $0.name)) } == true
            let shown = onScreen && tab.id == model.selected && wanted
            if shown { panel.open() } else { panel.close() }
        }
    }

    /// Places `tab`'s files panel in `view` and answers the room left to its terminals: frames only,
    /// as it runs inside the workbench's layout. Beside them the tab view's bounds start at the
    /// panel's far edge, so the terminals' frames, the dividers and a drag on them stay in the
    /// workbench's own coordinates.
    func placeFiles(of tab: Tab, in view: TabView, trailing: Double = 0) -> CGSize {
        let size = view.bounds.size, rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        let room = CGSize(width: max(Double(size.width) - trailing, 0), height: Double(size.height))
        guard let (project, name) = workspace(of: tab), filesShown.contains(Self.key(project, name)), let panel = view.files else {
            if let panel = view.files, !panel.isHidden {
                panel.isHidden = true
                panel.shadowView.isHidden = true
            }
            let shift = rightToLeft ? -trailing : 0
            if Double(view.bounds.origin.x) != shift { view.setBoundsOrigin(NSPoint(x: shift, y: 0)) }
            return room
        }
        let width = FilesPanel.width, floating = Double(size.width) < width + Self.filesBeside
        panel.floating = floating
        if panel.isHidden { panel.isHidden = false }
        if panel.shadowView.isHidden == floating { panel.shadowView.isHidden = !floating }
        let shift = rightToLeft ? -trailing : floating ? 0 : -width
        if Double(view.bounds.origin.x) != shift { view.setBoundsOrigin(NSPoint(x: shift, y: 0)) }
        let x = rightToLeft ? Double(size.width) - trailing - width : shift
        let frame = NSRect(x: x, y: 0, width: width, height: Double(size.height))
        if panel.frame != frame { panel.frame = frame }
        if panel.shadowView.frame != frame { panel.shadowView.frame = frame }
        return floating ? room : CGSize(width: max(Double(room.width) - width, 0), height: Double(size.height))
    }

    /// How wide the files panel stands beside the terminals in `tab`: none when hidden or floating.
    func filesBesideWidth(of tab: Tab, in view: TabView) -> Double {
        guard let (project, name) = workspace(of: tab), filesShown.contains(Self.key(project, name)), view.files != nil,
              Double(view.bounds.width) >= FilesPanel.width + Self.filesBeside else { return 0 }
        return FilesPanel.width
    }

    @discardableResult
    func makeFiles(project: String, name: String, in view: TabView) -> FilesPanel {
        let reached = signedIn()
        let tickets = reached.map { SignedInTickets(for: $0.handle, current: signedIn) }
        let panel = FilesPanel(project: project, workspace: name, tickets: tickets, look: look, mono: labels, titleFont: titles)
        panel.onStart = { [weak self] project, name in await self?.startWorkspace(project, name) }
        panel.pasteInTerminal = { [weak self, weak view] in
            guard let self, let view, let tab = model.tabs.first(where: { self.tabViews[$0.id] === view }), let terminal = panes[tab.focused]?.terminal,
                  terminal.link != nil else { return nil }
            return { [weak self, weak terminal] text in
                guard let terminal else { return }
                terminal.paste(text: text)
                self?.window?.makeFirstResponder(terminal)
            }
        }
        panel.onDismiss = { [weak self] in self?.dismissFloatingFiles() }
        panel.onOpenFile = { [weak self, weak view] path, entry in
            if let view { self?.openFile(path, entry, in: view) }
        }
        panel.isHidden = true
        view.addSubview(panel.shadowView)
        view.addSubview(panel)
        view.files = panel
        return panel
    }
}

/// What the drive reads of the selected tab's files panel: its views, its line and rows, and when
/// it last heard a listing and a change and drew a row, on the main thread's media clock.
package struct FilesShown {
    package let list: SailList
    package let scroll: NSScrollView
    package let button: NSButton
    package let words: String?
    package let rows: [FileTree.Row]
    package let new: Set<String>
    package let listingHeard: (path: String, at: Double)?
    package let changeHeard: [String: Double]
    package let openedAt: Double?
    package let admittedAt: Double?
    package let lastDrawn: (at: Double, path: String, new: Bool)
    /// When each entry was first drawn marked new.
    package let newDrawn: [String: Double]
    /// Tree rows drawn and their names built, in all.
    package let draws: Int
    package let namesBuilt: Int
    /// The main thread's own CPU in tree rows' draws, in all and the most one took, in ns.
    package let drawCPU: (total: UInt64, most: UInt64)
}

extension WorkbenchView {
    /// Whether the tree rows count their drawing, and the lists their making, for the drive: off in
    /// the app.
    package static var filesMeasuring: Bool {
        get { TreeRowCell.measuring }
        set {
            TreeRowCell.measuring = newValue
            SailList.measuring = newValue
            PanePhases.measuring = newValue
        }
    }

    package var filesOnScreen: FilesShown? {
        guard let tab = model.selectedTab, let panel = tabViews[tab.id]?.files, !panel.isHidden else { return nil }
        return FilesShown(
            list: panel.list, scroll: panel.scroll, button: panel.line.button, words: panel.shownLine?.words, rows: panel.tree.rows, new: Set(panel.shownNew.map(\.key)),
            listingHeard: panel.listingHeard, changeHeard: panel.changeHeard, openedAt: panel.openedAt, admittedAt: panel.admittedAt, lastDrawn: TreeRowCell.lastDrawn, newDrawn: TreeRowCell.newDrawn,
            draws: TreeRowCell.draws, namesBuilt: TreeRowCell.namesBuilt, drawCPU: TreeRowCell.drawCPU
        )
    }
}

/// What the drive does in the selected tab's files panel as a person would: a row's action as its
/// menu item runs it, a name typed and ⏎, rows dropped as a drag drops them, the delete question
/// answered; and what it reads back.
extension WorkbenchView {
    private var shownFiles: FilesPanel? {
        model.selectedTab.flatMap { tabViews[$0.id]?.files }.flatMap { $0.isHidden ? nil : $0 }
    }

    /// The row whose path under the root is `name`, else the first row named so: a name moved out
    /// of a folder is found where it is now, not where its folder's listing still has it.
    private func filesPath(named name: String) -> FileName? {
        let rows = shownFiles?.tree.rows.filter { $0.kind != .note } ?? [], exact = FileName(FileTree.root.shown + "/" + name)
        return rows.first { $0.path == exact }?.path ?? rows.first { $0.name == name }?.path
    }

    /// Row `name`'s action `title`, run as its menu item runs it; false with no such row or action,
    /// or one that cannot be done now.
    package func filesAct(_ title: String, on name: String) -> Bool {
        guard let panel = shownFiles, let path = filesPath(named: name), let action = panel.rowActions(path).first(where: { $0.title == title }), action.enabled() else {
            return false
        }
        action.run()
        return true
    }

    /// `name` typed into the open name field, and ⏎; false with no field open.
    package func filesName(_ name: String) -> Bool {
        guard let field = shownFiles?.nameRow else { return false }
        field.field.field.stringValue = name
        field.onCommit()
        return true
    }

    /// The open name field's sentence ("" with none said), nil with no field open.
    package var filesNameSays: String? {
        shownFiles?.naming.map { $0.refusal ?? "" }
    }

    /// Rows `names` dropped into folder `into` ("" the root), as a drag within the tree drops them.
    package func filesDrop(_ names: [String], into: String) -> Bool {
        guard let panel = shownFiles else { return false }
        let paths = names.compactMap(filesPath(named:))
        guard paths.count == names.count, into.isEmpty || filesPath(named: into) != nil else { return false }
        panel.drop(paths.map(\.key), into: into.isEmpty ? "" : filesPath(named: into)?.key ?? "")
        return true
    }

    /// The pointer held over row `name`, the row drawn as under it; "" gives the pointer back.
    package func filesHover(_ name: String) -> Bool {
        guard let panel = shownFiles else { return false }
        let path = filesPath(named: name)
        guard name.isEmpty || path != nil else { return false }
        panel.list.point(at: path?.key)
        return true
    }

    /// Whether row `name`'s ⋯ is shown whole.
    package func filesActionsShown(_ name: String) -> Bool {
        guard let panel = shownFiles, let path = filesPath(named: name) else { return false }
        return panel.list.actionsShown(path.key)
    }

    /// Row `name`'s context menu, as a right-click asks it, and where it pops up.
    package func filesMenu(_ name: String) -> (menu: NSMenu, row: NSView)? {
        guard let panel = shownFiles, let path = filesPath(named: name), let index = panel.tree.rows.firstIndex(where: { $0.path == path }),
              let row = panel.list.rowView(atRow: index, makeIfNecessary: true), let event = NSEvent.mouseEvent(
                  with: .rightMouseDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: window?.windowNumber ?? 0, context: nil, eventNumber: 0,
                  clickCount: 1, pressure: 1
              ), let menu = row.menu(for: event) else { return nil }
        return (menu, row)
    }

    /// The delete question's title while it asks.
    package var filesQuestion: String? {
        shownFiles?.question?.title.stringValue
    }

    /// The delete question answered: Delete, or Cancel.
    package func filesAnswer(_ delete: Bool) -> Bool {
        guard let question = shownFiles?.question else { return false }
        (delete ? question.confirm : question.cancel).performClick(nil)
        return true
    }
}
