import AppKit
import SailControls
import SailFiles
import SailLook
import SailTerminal
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
            let shown = onScreen && tab.id == model.selected && workspace(of: tab).map { filesShown.contains(Self.key($0.project, $0.name)) } == true
            if shown { panel.open() } else { panel.close() }
        }
    }

    /// Places `tab`'s files panel in `view` and answers the room left to its terminals: frames only,
    /// as it runs inside the workbench's layout. Beside them the tab view's bounds start at the
    /// panel's far edge, so the terminals' frames, the dividers and a drag on them stay in the
    /// workbench's own coordinates.
    func placeFiles(of tab: Tab, in view: TabView) -> CGSize {
        let size = view.bounds.size
        guard let (project, name) = workspace(of: tab), filesShown.contains(Self.key(project, name)), let panel = view.files else {
            if let panel = view.files, !panel.isHidden {
                panel.isHidden = true
                panel.shadowView.isHidden = true
            }
            if view.bounds.origin != .zero { view.setBoundsOrigin(.zero) }
            return size
        }
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        let width = FilesPanel.width, floating = Double(size.width) < width + Self.filesBeside
        panel.floating = floating
        if panel.isHidden { panel.isHidden = false }
        if panel.shadowView.isHidden == floating { panel.shadowView.isHidden = !floating }
        let shift = floating || rightToLeft ? 0 : -width
        if Double(view.bounds.origin.x) != shift { view.setBoundsOrigin(NSPoint(x: shift, y: 0)) }
        let x = rightToLeft ? Double(size.width) - width : shift
        let frame = NSRect(x: x, y: 0, width: width, height: Double(size.height))
        if panel.frame != frame { panel.frame = frame }
        if panel.shadowView.frame != frame { panel.shadowView.frame = frame }
        return floating ? size : CGSize(width: max(Double(size.width) - width, 0), height: Double(size.height))
    }

    @discardableResult
    private func makeFiles(project: String, name: String, in view: TabView) -> FilesPanel {
        let reached = signedIn()
        let tickets = reached.map { SignedInTickets(for: $0.handle, current: signedIn) }
        let panel = FilesPanel(project: project, workspace: name, tickets: tickets, look: look, mono: labels)
        panel.onStart = { [weak self] project, name in await self?.startWorkspace(project, name) }
        panel.onDismiss = { [weak self] in self?.dismissFloatingFiles() }
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
