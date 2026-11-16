import Foundation

/// One tab: its panes' layout, the person's name for it, the focused pane, the panes in the order
/// they were last focused (each once, the focused one last), and the pane zoomed to fill it.
public struct Tab: Hashable, Sendable {
    public let id: TabID
    public internal(set) var layout: Layout
    public internal(set) var name: String?
    public internal(set) var focused: PaneID
    public internal(set) var history: [PaneID]
    public internal(set) var zoomed: PaneID?
}

/// The terminals of a window as a value: tabs in order, the selected one, and every pane. Each
/// command is an operation returning what it opened or closed; the app opens and ends those
/// sessions and draws the value, nothing more. After every operation the value is whole: each
/// split has a ratio strictly between 0 and 1, no tab is empty, each tab's focused and zoomed panes
/// are in its tree, the selected tab exists or there is none, and pane ids and session names are
/// unique.
public struct Workbench: Hashable, Sendable {
    /// What a tab is called when nobody named it and its program said nothing.
    public static let untitled = "This Mac"

    public private(set) var tabs: [Tab] = []
    public private(set) var selected: TabID?
    public private(set) var panes: [PaneID: Pane] = [:]
    private var made = 0

    public init() {}

    public var selectedTab: Tab? {
        selectedIndex.map { tabs[$0] }
    }

    public var focusedPane: Pane? {
        selectedTab.flatMap { panes[$0.focused] }
    }

    public func tab(holding pane: PaneID) -> Tab? {
        index(holding: pane).map { tabs[$0] }
    }

    private var selectedIndex: Int? {
        tabs.firstIndex { $0.id == selected }
    }

    private func index(holding pane: PaneID) -> Int? {
        tabs.firstIndex { $0.layout.contains(pane) }
    }

    private mutating func mint() -> Int {
        made += 1
        return made
    }

    private mutating func makePane(session: String, place: Place) -> PaneID {
        precondition(!panes.values.contains { $0.session == session }, "the session \(session) has a pane already, and a session has one pane")
        let id = PaneID(mint())
        panes[id] = Pane(id: id, session: session, place: place)
        return id
    }

    private mutating func focus(_ pane: PaneID, at index: Int) {
        tabs[index].focused = pane
        tabs[index].history.removeAll { $0 == pane }
        tabs[index].history.append(pane)
    }

    /// The kept `node` with a new pane for each session `unheld` still names, taken from it; a
    /// split losing a side collapses to the other, and a ratio not strictly inside (0, 1) is halved.
    mutating func adopt(_ node: Arrangement.Node, from unheld: inout Set<String>) -> Layout? {
        switch node {
        case let .pane(session, place):
            guard unheld.remove(session) != nil else { return nil }
            return .pane(makePane(session: session, place: place))
        case let .split(axis, ratio, first, second):
            let (one, other) = (adopt(first, from: &unheld), adopt(second, from: &unheld))
            guard let one, let other else { return one ?? other }
            return .split(axis, ratio: ratio > 0 && ratio < 1 ? ratio : 0.5, one, other)
        }
    }

    /// A last tab holding `layout`, `focused` its focused pane, not selected.
    mutating func adoptTab(_ layout: Layout, focused: PaneID) -> TabID {
        let id = TabID(mint())
        tabs.append(Tab(id: id, layout: layout, name: nil, focused: focused, history: layout.leaves.filter { $0 != focused } + [focused], zoomed: nil))
        return id
    }

    /// A last tab of one pane on `session`, selected.
    @discardableResult
    public mutating func newTab(session: String, place: Place) -> PaneID {
        let pane = makePane(session: session, place: place)
        let tab = Tab(id: TabID(mint()), layout: .pane(pane), name: nil, focused: pane, history: [pane], zoomed: nil)
        tabs.append(tab)
        selected = tab.id
        return pane
    }

    /// Halves the focused pane of the selected tab along `axis`, the new pane on `session` second
    /// and focused; nil, and nothing changed, when either half would be under `minimum`.
    public mutating func split(_ axis: Axis, session: String, place: Place, in size: CGSize, minimum: CGSize, scale: Double) -> PaneID? {
        guard let index = selectedIndex else { return nil }
        let tab = tabs[index]
        guard let frame = tab.layout.frames(in: CGRect(origin: .zero, size: size), scale: scale).panes[tab.focused] else { return nil }
        let least = Layout.extent(of: minimum, along: axis)
        guard 2 * least + Layout.dividerWidth <= Layout.extent(of: frame, along: axis) else { return nil }
        let pane = makePane(session: session, place: place)
        tabs[index].layout = tab.layout.replacing(tab.focused, with: .split(axis, ratio: 0.5, .pane(tab.focused), .pane(pane)))
        tabs[index].zoomed = nil
        focus(pane, at: index)
        return pane
    }

    /// Closes `pane`: its split's other side takes the split's room and the most recently focused
    /// pane left takes focus; a tab left empty goes, and the tab before it, or else the first, is
    /// selected. The pane closed, nil when there was none.
    @discardableResult
    public mutating func close(_ pane: PaneID) -> Pane? {
        guard let index = index(holding: pane), let closed = panes.removeValue(forKey: pane) else { return nil }
        guard let rest = tabs[index].layout.removing(pane) else {
            removeTab(at: index)
            return closed
        }
        tabs[index].layout = rest
        tabs[index].history.removeAll { $0 == pane }
        tabs[index].zoomed = nil
        if tabs[index].focused == pane, let last = tabs[index].history.last { focus(last, at: index) }
        return closed
    }

    /// Closes the tab and every pane in it, as ``close(_:)`` closes its last.
    @discardableResult
    public mutating func closeTab(_ tab: TabID) -> [Pane] {
        guard let index = tabs.firstIndex(where: { $0.id == tab }) else { return [] }
        let closed = tabs[index].layout.leaves.compactMap { panes.removeValue(forKey: $0) }
        removeTab(at: index)
        return closed
    }

    private mutating func removeTab(at index: Int) {
        let wasSelected = tabs[index].id == selected
        tabs.remove(at: index)
        if wasSelected { selected = tabs.isEmpty ? nil : tabs[max(0, index - 1)].id }
    }

    /// `pane` replaced in its place by a new pane on `session`, as the person asked once its own
    /// session ended: a new pane, so a new view, never the old view on another session.
    public mutating func reopen(_ pane: PaneID, session: String) -> PaneID? {
        guard let index = index(holding: pane), let old = panes.removeValue(forKey: pane) else { return nil }
        let new = makePane(session: session, place: old.place)
        tabs[index].layout = tabs[index].layout.replacing(pane, with: .pane(new))
        tabs[index].history = tabs[index].history.map { $0 == pane ? new : $0 }
        if tabs[index].focused == pane { tabs[index].focused = new }
        if tabs[index].zoomed == pane { tabs[index].zoomed = new }
        return new
    }

    /// Focuses `pane` and selects its tab, as a click on it does; focusing another pane ends a zoom.
    public mutating func focus(_ pane: PaneID) {
        guard let index = index(holding: pane) else { return }
        selected = tabs[index].id
        if tabs[index].focused != pane { tabs[index].zoomed = nil }
        focus(pane, at: index)
    }

    /// Focuses the pane nearest past the focused one's edge toward `direction`, ending a zoom; at
    /// an edge nothing changes and false is answered.
    @discardableResult
    public mutating func moveFocus(_ direction: Direction, in size: CGSize, scale: Double) -> Bool {
        guard let index = selectedIndex else { return false }
        let tab = tabs[index]
        let frames = tab.layout.frames(in: CGRect(origin: .zero, size: size), scale: scale).panes
        guard let from = frames[tab.focused], let to = Layout.neighbour(of: from, toward: direction, among: frames, history: tab.history)
        else { return false }
        tabs[index].zoomed = nil
        focus(to, at: index)
        return true
    }

    /// Focuses the next pane in reading order, or the previous, wrapping, ending a zoom.
    public mutating func focusNeighbour(next: Bool) {
        guard let index = selectedIndex else { return }
        let leaves = tabs[index].layout.leaves
        guard leaves.count > 1, let at = leaves.firstIndex(of: tabs[index].focused) else { return }
        tabs[index].zoomed = nil
        focus(leaves[(at + (next ? 1 : leaves.count - 1)) % leaves.count], at: index)
    }

    /// Moves the divider of the focused pane's nearest split along `direction`'s axis by `points`
    /// that way, stopping where a pane would go under `minimum`; false when there is none to move.
    @discardableResult
    public mutating func moveDivider(_ direction: Direction, by points: Double, in size: CGSize, minimum: CGSize, scale: Double) -> Bool {
        guard let tab = selectedTab, let path = tab.layout.path(to: tab.focused) else { return false }
        let dividers = tab.layout.frames(in: CGRect(origin: .zero, size: size), scale: scale).dividers
        for depth in stride(from: path.count - 1, through: 0, by: -1) {
            guard let divider = dividers.first(where: { $0.path == Array(path.prefix(depth)) }), divider.axis == direction.axis else { continue }
            let position = divider.axis == .sideBySide ? divider.line.minX : divider.line.minY
            return drag(divider.path, to: position + (direction.towardStart ? -points : points), in: size, minimum: minimum, scale: scale)
        }
        return false
    }

    /// Puts the divider of the split at `path` in the selected tab at `position` along its axis, in
    /// points from the tab's top left, stopping where a pane would go under `minimum`; false when
    /// nothing moved.
    @discardableResult
    public mutating func drag(_ path: [Side], to position: Double, in size: CGSize, minimum: CGSize, scale: Double) -> Bool {
        guard let index = selectedIndex, case let .split(axis, ratio, first, second)? = tabs[index].layout.node(at: path),
              let divider = tabs[index].layout.frames(in: CGRect(origin: .zero, size: size), scale: scale).dividers.first(where: { $0.path == path })
        else { return false }
        let room = Layout.extent(of: divider.split, along: axis) - Layout.dividerWidth
        let start = axis == .sideBySide ? divider.split.minX : divider.split.minY
        let least = first.minimum(along: axis, pane: minimum) / room
        let most = 1 - second.minimum(along: axis, pane: minimum) / room
        guard room > 0, least <= most else { return false }
        let wanted = min(max((position - start) / room, least), most)
        guard wanted != ratio else { return false }
        tabs[index].layout = tabs[index].layout.settingRatio(wanted, at: path)
        return true
    }

    /// Gives the selected tab's panes equal shares, by weight (see ``Layout/weight(along:)``).
    public mutating func equalize() {
        guard let index = selectedIndex else { return }
        tabs[index].layout = tabs[index].layout.equalized()
    }

    /// Zooms the focused pane to fill its tab, or ends the zoom; a tab of one pane has none.
    public mutating func toggleZoom() {
        guard let index = selectedIndex, tabs[index].layout.leaves.count > 1 else { return }
        tabs[index].zoomed = tabs[index].zoomed == nil ? tabs[index].focused : nil
    }

    public mutating func select(_ tab: TabID) {
        guard tabs.contains(where: { $0.id == tab }) else { return }
        selected = tab
    }

    /// Selects the tab at `index` from 0, when there is one.
    public mutating func selectTab(at index: Int) {
        guard tabs.indices.contains(index) else { return }
        selected = tabs[index].id
    }

    public mutating func selectLastTab() {
        selected = tabs.last?.id ?? selected
    }

    /// Selects the next tab or the previous, wrapping.
    public mutating func selectNeighbourTab(next: Bool) {
        guard let index = selectedIndex else { return }
        selected = tabs[(index + (next ? 1 : tabs.count - 1)) % tabs.count].id
    }

    /// Moves `tab` to `index` in the order, or the nearest end.
    public mutating func moveTab(_ tab: TabID, to index: Int) {
        guard let from = tabs.firstIndex(where: { $0.id == tab }) else { return }
        let moved = tabs.remove(at: from)
        tabs.insert(moved, at: min(max(0, index), tabs.count))
    }

    /// Names `tab` `name`, which stands over its program's title until cleared: an empty name, or
    /// one of spaces only, clears it.
    public mutating func rename(_ tab: TabID, to name: String) {
        guard let index = tabs.firstIndex(where: { $0.id == tab }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        tabs[index].name = trimmed.isEmpty ? nil : trimmed
    }

    public mutating func setTitle(_ title: String, of pane: PaneID) {
        panes[pane]?.programTitle = title
    }

    public mutating func setFolder(_ reported: String, of pane: PaneID) {
        panes[pane]?.reportedFolder = reported
    }

    /// The person's name for the tab, else its focused pane's program title, else ``untitled``.
    public func title(of tab: TabID) -> String {
        guard let tab = tabs.first(where: { $0.id == tab }) else { return Self.untitled }
        let program = panes[tab.focused]?.programTitle ?? ""
        return tab.name ?? (program.isEmpty ? Self.untitled : program)
    }

    /// Where a new tab or split opens: the selected tab's focused pane's reported folder when it is
    /// on this Mac (see ``ReportedFolder``) and `isFolder` says it is a folder, else the folder that
    /// pane opened in; nil with no tab.
    public func folderForNewPane(hostNames: Set<String>, isFolder: (String) -> Bool) -> String? {
        guard let pane = focusedPane else { return nil }
        return ReportedFolder.path(pane.reportedFolder, hostNames: hostNames).flatMap { isFolder($0) ? $0 : nil } ?? pane.place.folder
    }

    /// The panes and dividers of `tab` (the selected one unless named) in `size`, rounded to
    /// `scale`'s pixels; a zoomed pane alone, filling the tab.
    public func frames(of tab: TabID? = nil, in size: CGSize, scale: Double) -> Frames {
        guard let tab = tabs.first(where: { $0.id == tab ?? selected }) else { return Frames() }
        let room = CGRect(origin: .zero, size: size)
        if let zoomed = tab.zoomed { return Frames(panes: [zoomed: room], dividers: []) }
        return tab.layout.frames(in: room, scale: scale)
    }
}
