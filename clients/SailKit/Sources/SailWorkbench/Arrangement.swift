import Foundation

/// What a workbench keeps between launches: its arrangement, never its sessions. The tabs in
/// order, each tab's tree with its ratios, every pane's session name and place, each tab's name and
/// focused session, and the selected tab. Zoom, titles and folders are not kept.
public struct Arrangement: Codable, Equatable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    public var tabs: [Tab]
    public var selected: Int?

    public init(version: Int = currentVersion, tabs: [Tab], selected: Int?) {
        self.version = version
        self.tabs = tabs
        self.selected = selected
    }

    public struct Tab: Codable, Equatable, Sendable {
        public var name: String?
        public var focused: String
        public var layout: Node
    }

    public indirect enum Node: Codable, Equatable, Sendable {
        case pane(session: String, place: Place)
        case split(axis: Axis, ratio: Double, first: Node, second: Node)
    }
}

extension Workbench {
    public var arrangement: Arrangement {
        Arrangement(
            version: Arrangement.currentVersion,
            tabs: tabs.map { .init(name: $0.name, focused: panes[$0.focused]?.session ?? "", layout: node($0.layout)) },
            selected: tabs.firstIndex { $0.id == selected }
        )
    }

    private func node(_ layout: Layout) -> Arrangement.Node {
        switch layout {
        case let .pane(id):
            let pane = panes[id]
            return .pane(session: pane?.session ?? "", place: pane?.place ?? .thisMac(folder: ""))
        case let .split(axis, ratio, first, second):
            return .split(axis: axis, ratio: ratio, first: node(first), second: node(second))
        }
    }

    /// The kept `arrangement` holding only sessions `live` names (W6); a live `mac-*` session no
    /// pane holds opens as a last tab in `home`. An unknown version drops all, a ratio not strictly
    /// inside (0, 1) is halved, a session held twice keeps its first holder.
    public static func restore(_ arrangement: Arrangement?, live: [String], home: String) -> Workbench {
        var bench = Workbench()
        var unheld = Set(live)
        let stored = arrangement.flatMap { $0.version == Arrangement.currentVersion ? $0 : nil }
        var selected: TabID?
        for (index, tab) in (stored?.tabs ?? []).enumerated() {
            guard let layout = bench.adopt(tab.layout, from: &unheld) else { continue }
            let leaves = layout.leaves
            let focused = leaves.first { bench.panes[$0]?.session == tab.focused } ?? leaves[0]
            let id = bench.adoptTab(layout, focused: focused)
            bench.rename(id, to: tab.name ?? "")
            if index <= stored?.selected ?? -1 { selected = id }
        }
        for session in live where session.hasPrefix(Place.thisMacSessions) && unheld.remove(session) != nil {
            bench.newTab(session: session, place: .thisMac(folder: home))
        }
        if let selected { bench.select(selected) } else { bench.selectTab(at: 0) }
        return bench
    }
}

/// Where a shell says its folder is (OSC 7), as a path on this Mac: a `file://` URI, percent
/// decoded, or a `kitty-shell-cwd://` URI (Ghostty's zsh integration sends it, its path raw), whose
/// host is empty, `localhost` or one of this Mac's names; or an absolute path. Anything else, a
/// relative path, another host or nothing, names no folder here.
public enum ReportedFolder {
    public static func path(_ reported: String, hostNames: Set<String>) -> String? {
        if reported.hasPrefix("/") { return reported }
        for (scheme, decoded) in [("file://", true), ("kitty-shell-cwd://", false)] where reported.hasPrefix(scheme) {
            let rest = reported.dropFirst(scheme.count)
            guard let slash = rest.firstIndex(of: "/") else { return nil }
            let host = rest[..<slash].lowercased()
            guard host.isEmpty || host == "localhost" || hostNames.contains(where: { $0.lowercased() == host }) else { return nil }
            let path = String(rest[slash...])
            return decoded ? path.removingPercentEncoding : path
        }
        return nil
    }
}

/// When a session that ended closes its pane: its program exited (the host says `exited(N)`),
/// unless with a non-zero code within ``quickFailure`` of the pane opening, so a shell that cannot
/// start is said rather than gone. Any other end keeps the pane and its banner.
public enum Ending {
    public static let quickFailure = Duration.seconds(2)

    public static func closesPane(reason: String, lived: Duration) -> Bool {
        guard reason.hasPrefix("exited("), reason.hasSuffix(")"), let code = Int(reason.dropFirst(7).dropLast()) else { return false }
        return code == 0 || lived >= quickFailure
    }
}
