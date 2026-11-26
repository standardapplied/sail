#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation

/// One of This Mac's terminals as its project lists it (``Workbench/localSessions(ended:hostNames:home:)``).
public struct LocalSession: Hashable, Sendable {
    public let pane: PaneID
    public let title: String
    public let folder: String
    /// How its session ended ("exited 3 · 11:02"), nil while it runs.
    public let ended: String?

    public init(pane: PaneID, title: String, folder: String, ended: String?) {
        self.pane = pane
        self.title = title
        self.folder = folder
        self.ended = ended
    }
}

/// One tab; `history` holds its panes in the order last focused, the focused one last.
public struct Tab: Hashable, Sendable {
    public let id: TabID
    public internal(set) var layout: Layout
    public internal(set) var name: String?
    public internal(set) var focused: PaneID
    public internal(set) var history: [PaneID]
    public internal(set) var zoomed: PaneID?
}

/// The terminals of a window as a value: tabs in order, the selected one, and every pane. Each
/// operation returns what it opened or closed and leaves the value whole (the spec's W1).
public struct Workbench: Hashable, Sendable {
    /// What a tab is called when nobody named it and its program said nothing.
    public static let untitled = "This Mac"

    public private(set) var tabs: [Tab] = []
    public private(set) var selected: TabID?
    public private(set) var panes: [PaneID: Pane] = [:]
    private var made = 0
    /// Each unnamed tab's number among the tabs called as it is, given as it opens or its name is
    /// cleared and kept until it is named or closed: closing or moving another renumbers none.
    private var numbers: [TabID: Numbered] = [:]

    private struct Numbered: Hashable, Sendable {
        let label: String
        let number: Int
    }

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

    private mutating func makePane(session: String, place: Place, agent: AgentStart? = nil) -> PaneID {
        precondition(!panes.values.contains { $0.session == session }, "the session \(session) has a pane already, and a session has one pane")
        precondition(agent == nil || place.onRunner, "an agent runs in a workspace on the runner")
        let id = PaneID(mint())
        panes[id] = Pane(id: id, session: session, place: place, agent: agent)
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
        case let .pane(session, place, agent):
            guard unheld.remove(session) != nil else { return nil }
            return .pane(makePane(session: session, place: place, agent: agent))
        case let .split(axis, ratio, first, second):
            let (one, other) = (adopt(first, from: &unheld), adopt(second, from: &unheld))
            guard let one, let other else { return one ?? other }
            return .split(axis, ratio: ratio > 0 && ratio < 1 ? ratio : 0.5, one, other)
        }
    }

    mutating func adoptTab(_ layout: Layout, focused: PaneID) -> TabID {
        let id = TabID(mint())
        tabs.append(Tab(id: id, layout: layout, name: nil, focused: focused, history: layout.leaves.filter { $0 != focused } + [focused], zoomed: nil))
        number(id, focused: focused)
        return id
    }

    @discardableResult
    public mutating func newTab(session: String, place: Place, agent: AgentStart? = nil) -> PaneID {
        let pane = makePane(session: session, place: place, agent: agent)
        let tab = Tab(id: TabID(mint()), layout: .pane(pane), name: nil, focused: pane, history: [pane], zoomed: nil)
        tabs.append(tab)
        number(tab.id, focused: pane)
        selected = tab.id
        return pane
    }

    /// Halves the focused pane of the selected tab along `axis`, the new pane on `session` second
    /// and focused; nil, and nothing changed, when either half would be under `minimum`.
    public mutating func split(_ axis: Axis, session: String, place: Place, in size: CGSize, minimum: CGSize, scale: Double) -> PaneID? {
        guard let index = selectedIndex else { return nil }
        let tab = tabs[index]
        let halved = tab.layout.replacing(tab.focused, with: .split(axis, ratio: 0.5, .pane(tab.focused), .pane(PaneID(0))))
        guard halved.fits(in: size, minimum: minimum, scale: scale) else { return nil }
        let pane = makePane(session: session, place: place)
        tabs[index].layout = tab.layout.replacing(tab.focused, with: .split(axis, ratio: 0.5, .pane(tab.focused), .pane(pane)))
        tabs[index].zoomed = nil
        focus(pane, at: index)
        return pane
    }

    /// Closes `pane`: its sibling takes the split's room, the most recently focused pane the focus;
    /// an emptied tab goes and the tab before it is selected.
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

    @discardableResult
    public mutating func closeTab(_ tab: TabID) -> [Pane] {
        guard let index = tabs.firstIndex(where: { $0.id == tab }) else { return [] }
        let closed = tabs[index].layout.leaves.compactMap { panes.removeValue(forKey: $0) }
        removeTab(at: index)
        return closed
    }

    private mutating func removeTab(at index: Int) {
        let wasSelected = tabs[index].id == selected
        numbers[tabs[index].id] = nil
        tabs.remove(at: index)
        if wasSelected { selected = tabs.isEmpty ? nil : tabs[max(0, index - 1)].id }
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
    /// points from the tab's top left, or as near to it as leaves every pane of the tab at least
    /// `minimum`, or no smaller than it was when it was already under it; false when nothing moved.
    @discardableResult
    public mutating func drag(_ path: [Side], to position: Double, in size: CGSize, minimum: CGSize, scale: Double) -> Bool {
        guard let index = selectedIndex, case let .split(axis, ratio, _, _)? = tabs[index].layout.node(at: path),
              let divider = tabs[index].layout.frames(in: CGRect(origin: .zero, size: size), scale: scale).dividers.first(where: { $0.path == path })
        else { return false }
        let layout = tabs[index].layout
        let room = Layout.extent(of: divider.split, along: axis) - Layout.dividerWidth
        let start = axis == .sideBySide ? divider.split.minX : divider.split.minY
        guard room > 0 else { return false }
        func fits(_ candidate: Double) -> Bool {
            layout.settingRatio(candidate, at: path).fits(in: size, minimum: minimum, scale: scale, against: layout)
        }
        var (held, wanted) = (ratio, min(max((position - start) / room, Self.leastRatio), 1 - Self.leastRatio))
        if !fits(wanted) {
            for _ in 0 ..< 40 {
                let middle = (held + wanted) / 2
                if fits(middle) { held = middle } else { wanted = middle }
            }
            wanted = held
        }
        let moved = layout.settingRatio(wanted, at: path)
        let rect = CGRect(origin: .zero, size: size)
        guard moved.frames(in: rect, scale: scale).panes != layout.frames(in: rect, scale: scale).panes else { return false }
        tabs[index].layout = moved
        return true
    }

    /// Gives the selected tab's panes equal shares, by weight (see ``Layout/weight(along:)``), unless
    /// that would leave a pane under `minimum`.
    public mutating func equalize(in size: CGSize, minimum: CGSize, scale: Double) {
        guard let index = selectedIndex else { return }
        let equal = tabs[index].layout.equalized()
        if equal.fits(in: size, minimum: minimum, scale: scale) { tabs[index].layout = equal }
    }

    /// Each tab too small at `size` for every pane to have `minimum` takes equal shares when they
    /// fit; else it keeps its ratios, each pane given at least a divider's grip of room (a window
    /// made too small). Focus leaves a pane with no room.
    public mutating func fit(in size: CGSize, minimum: CGSize, scale: Double) {
        for index in tabs.indices where !tabs[index].layout.fits(in: size, minimum: minimum, scale: scale) {
            let equal = tabs[index].layout.equalized()
            tabs[index].layout = equal.fits(in: size, minimum: minimum, scale: scale)
                ? equal : tabs[index].layout.withSlivers(in: CGRect(origin: .zero, size: size), sliver: Layout.dividerHitWidth)
            let panes = tabs[index].layout.frames(in: CGRect(origin: .zero, size: size), scale: scale).panes
            let area = { (pane: PaneID) in panes[pane].map { $0.width * $0.height } ?? 0 }
            if area(tabs[index].focused) < 1, let roomiest = tabs[index].layout.leaves.max(by: { area($0) < area($1) }) { focus(roomiest, at: index) }
        }
    }

    static let leastRatio = 1e-6

    /// Zooms the focused pane to fill its tab, or ends the zoom; a tab of one pane has none.
    public mutating func toggleZoom() {
        guard let index = selectedIndex, tabs[index].layout.leaves.count > 1 else { return }
        tabs[index].zoomed = tabs[index].zoomed == nil ? tabs[index].focused : nil
    }

    public mutating func select(_ tab: TabID) {
        guard tabs.contains(where: { $0.id == tab }) else { return }
        selected = tab
    }

    public mutating func selectTab(at index: Int) {
        guard tabs.indices.contains(index) else { return }
        selected = tabs[index].id
    }

    public mutating func selectLastTab() {
        selected = tabs.last?.id ?? selected
    }

    public mutating func selectNeighbourTab(next: Bool) {
        guard let index = selectedIndex else { return }
        selected = tabs[(index + (next ? 1 : tabs.count - 1)) % tabs.count].id
    }

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
        let cleared = tabs[index].name != nil && trimmed.isEmpty
        tabs[index].name = trimmed.isEmpty ? nil : trimmed
        if cleared { number(tab, focused: tabs[index].focused) } else if !trimmed.isEmpty { numbers[tab] = nil }
    }

    public mutating func setTitle(_ title: String, of pane: PaneID) {
        panes[pane]?.programTitle = title
    }

    public mutating func setFolder(_ reported: String, of pane: PaneID) {
        panes[pane]?.reportedFolder = reported
        if panes[pane]?.firstHost == nil { panes[pane]?.firstHost = ReportedFolder.host(reported) ?? "" }
    }

    /// The person's name for the tab, else where its focused pane is when that is a workspace, else
    /// that pane's program title, else ``untitled``; an unnamed tab of a workspace, or untitled, is
    /// numbered as it opens (the second "acme · try 2", "This Mac 2"), so two are told apart, and
    /// keeps that number, under a program's title too, until the person names it: closing or moving
    /// another tab changes no title.
    public func title(of tab: TabID) -> String {
        guard let tab = tabs.first(where: { $0.id == tab }) else { return Self.untitled }
        if let name = tab.name { return name }
        guard let label = label(tab) else { return panes[tab.focused]?.programTitle ?? Self.untitled }
        guard let numbered = numbers[tab.id], numbered.label == label, numbered.number > 1 else { return label }
        return "\(label) \(numbered.number)"
    }

    /// What an unnamed tab is called before its number: its focused pane's workspace, or
    /// ``untitled`` while its program has said no title; nil for a named tab or a program's title.
    private func label(_ tab: Tab) -> String? {
        guard tab.name == nil, let pane = panes[tab.focused] else { return nil }
        return pane.label ?? (pane.programTitle.isEmpty ? Self.untitled : nil)
    }

    /// Numbers `tab` by its `focused` pane's workspace, or as ``untitled``: the least number no
    /// other tab called so holds.
    private mutating func number(_ tab: TabID, focused: PaneID) {
        let label = panes[focused]?.label ?? Self.untitled
        let taken = Set(numbers.values.filter { $0.label == label }.map(\.number))
        var number = 1
        while taken.contains(number) { number += 1 }
        numbers[tab] = Numbered(label: label, number: number)
    }

    /// This Mac's terminals as the This Mac project lists them: a row per pane on this Mac, in the
    /// order of their tabs and, in a tab, reading order, each called by its tab's title, in the
    /// folder its shell last reported on this Mac (``ReportedFolder``) or else opened in, `home` said
    /// as ~, and ended as `ended` says (nil while its session runs).
    public func localSessions(ended: [PaneID: String], hostNames: Set<String>, home: String) -> [LocalSession] {
        tabs.flatMap { tab in
            tab.layout.leaves.compactMap { id -> LocalSession? in
                guard let pane = panes[id], !pane.place.onRunner else { return nil }
                let folder = ReportedFolder.path(pane.reportedFolder, hostNames: hostNames) ?? pane.place.folder
                let short = folder == home ? "~" : folder.hasPrefix(home + "/") ? "~" + folder.dropFirst(home.count) : folder
                return LocalSession(pane: id, title: title(of: tab.id), folder: short, ended: ended[id])
            }
        }
    }

    /// Where a new tab or split opens: the selected tab's focused pane's reported folder when it is
    /// on this Mac (see ``ReportedFolder``) and `isFolder` says it is a folder, else the folder that
    /// pane opened in; nil with no tab, or when that pane is in a workspace, which has no folder here.
    public func folderForNewPane(hostNames: Set<String>, isFolder: (String) -> Bool) -> String? {
        guard let pane = focusedPane, !pane.place.onRunner else { return nil }
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
