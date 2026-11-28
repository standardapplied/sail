import Foundation
import SailAPI

/// What a workbench keeps between launches, on this Mac alone: its arrangement, never its
/// sessions. The tabs in order, each tab's tree with its ratios, every pane's form, session name
/// (a new chat's own) and place, a chat's harness and unsent draft, each tab's name and focused
/// session, the selected tab, the last workspace, and the person whose runner panes these are.
/// Zoom, titles, folders and chats' states are not kept.
public struct Arrangement: Codable, Equatable, Sendable {
    public static let currentVersion = 2

    public var version: Int
    public var tabs: [Tab]
    public var selected: Int?
    /// Whose runner panes it holds: they come back for that person's sign-in alone.
    public var runner: String?
    public var lastWorkspace: Place?

    public init(version: Int = currentVersion, tabs: [Tab], selected: Int?, runner: String? = nil, lastWorkspace: Place? = nil) {
        self.version = version
        self.tabs = tabs
        self.selected = selected
        self.runner = runner
        self.lastWorkspace = lastWorkspace
    }

    /// Its tabs on the runner: a tab's panes are all on the runner or none are, as a split takes
    /// its focused pane's place.
    public var runnerTabs: [Tab] {
        tabs.filter(\.layout.onRunner)
    }

    /// The sessions of its runner panes when they are `person`'s, which a restore keeps as live;
    /// none for anyone else, or for no one.
    public func runnerSessions(for person: String?) -> [String] {
        guard let person, person == runner else { return [] }
        return runnerTabs.flatMap(\.layout.sessions)
    }

    /// The last workspace when it is `person`'s; none for anyone else, or for no one.
    public func lastWorkspace(for person: String?) -> Place? {
        guard let person, person == runner else { return nil }
        return lastWorkspace
    }

    public struct Tab: Codable, Equatable, Sendable {
        public var name: String?
        public var focused: String
        public var layout: Node

        public init(name: String?, focused: String, layout: Node) {
            self.name = name
            self.focused = focused
            self.layout = layout
        }
    }

    public indirect enum Node: Codable, Equatable, Sendable {
        case pane(session: String, place: Place, agent: AgentStart? = nil, form: Pane.Form = .terminal, draft: String = "")
        case split(axis: Axis, ratio: Double, first: Node, second: Node)

        /// Its panes' sessions, in order.
        public var sessions: [String] {
            switch self {
            case let .pane(session, _, _, _, _): [session]
            case let .split(_, _, first, second): first.sessions + second.sessions
            }
        }

        var onRunner: Bool {
            switch self {
            case let .pane(_, place, _, _, _): place.onRunner
            case let .split(_, _, first, _): first.onRunner
            }
        }
    }
}

extension Workbench {
    public var arrangement: Arrangement {
        Arrangement(
            version: Arrangement.currentVersion,
            tabs: tabs.map { .init(name: $0.name, focused: panes[$0.focused]?.session ?? "", layout: node($0.layout)) },
            selected: tabs.firstIndex { $0.id == selected },
            lastWorkspace: lastWorkspace
        )
    }

    /// Its arrangement with `held`, the runner tabs kept aside while no one is signed in, after its
    /// own, all of them `runner`'s.
    public func arrangement(runner: String?, holding held: [Arrangement.Tab]) -> Arrangement {
        var kept = arrangement
        kept.tabs += held
        kept.runner = runner
        return kept
    }

    /// Adds the kept `tabs` after the open ones, every pane in them, and answers the panes made:
    /// the runner tabs a sign-in brings back.
    public mutating func adopt(_ tabs: [Arrangement.Tab]) -> [PaneID] {
        var unheld = Set(tabs.flatMap(\.layout.sessions)).subtracting(panes.values.map(\.session))
        var made: [PaneID] = []
        for tab in tabs {
            guard let layout = adopt(tab.layout, from: &unheld) else { continue }
            let leaves = layout.leaves
            let id = adoptTab(layout, focused: leaves.first { panes[$0]?.session == tab.focused } ?? leaves[0])
            rename(id, to: tab.name ?? "")
            made += leaves
        }
        return made
    }

    /// Each live session of `person` in `listing`, workspace `place`'s, that no pane and no tab
    /// of `held` holds, opened as a last tab, oldest first so the newest ends last: a terminal
    /// running its harness, a chat as a chat. Ended rows, another person's and a form this app does
    /// not know are never adopted; the selected tab stays, or with none the first is. A chat a pane
    /// holds takes the listing's state and title. Answers the panes made.
    @discardableResult
    public mutating func adopt(listing: RunnerSessions, in place: Place, for person: String, beside held: [Arrangement.Tab] = []) -> [PaneID] {
        precondition(place.onRunner, "a sessions listing is a workspace's, on the runner")
        for chat in listing.chats {
            guard let id = panes.values.first(where: { $0.form == .chat && $0.session == chat.name })?.id else { continue }
            setChatState(chat.state, of: id)
            panes[id]?.chatTitle = chat.title
        }
        let terminals = listing.live(for: person).map { (name: $0.name, at: $0.createdAt, agent: $0.agent?.start, chat: ChatRow?.none) }
        let chats = listing.chats.filter { $0.live && $0.opener == person }.map { (name: $0.name, at: $0.createdAt, agent: Optional($0.agent.start), chat: Optional($0)) }
        var taken = Set(panes.values.map(\.session) + held.flatMap(\.layout.sessions))
        var made: [PaneID] = []
        for row in (terminals + chats).sorted(by: { ($0.at, $0.name) < ($1.at, $1.name) }) where taken.insert(row.name).inserted {
            let pane = makePane(session: row.name, place: place, agent: row.agent, form: row.chat == nil ? .terminal : .chat)
            panes[pane]?.chatState = row.chat?.state
            panes[pane]?.chatTitle = row.chat?.title
            _ = adoptTab(.pane(pane), focused: pane)
            made.append(pane)
        }
        if selected == nil { selectTab(at: 0) }
        return made
    }

    private func node(_ layout: Layout) -> Arrangement.Node {
        switch layout {
        case let .pane(id):
            let pane = panes[id]
            return .pane(session: pane?.session ?? "", place: pane?.place ?? .thisMac(folder: ""), agent: pane?.agent, form: pane?.form ?? .terminal, draft: pane?.draft ?? "")
        case let .split(axis, ratio, first, second):
            return .split(axis: axis, ratio: ratio, first: node(first), second: node(second))
        }
    }

    /// The kept `arrangement` holding only sessions `live` names (W6), and its last workspace
    /// when it is `person`'s; a live `mac-*` session no pane holds opens as a last tab in `home`.
    /// Another version drops all, a ratio not strictly inside (0, 1) is halved, a session held twice
    /// keeps its first holder.
    public static func restore(_ arrangement: Arrangement?, live: [String], home: String, for person: String? = nil) -> Workbench {
        var bench = Workbench()
        var unheld = Set(live)
        let stored = arrangement.flatMap { $0.version == Arrangement.currentVersion ? $0 : nil }
        bench.lastWorkspace = stored?.lastWorkspace(for: person)
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
    private static let schemes = [("file://", true), ("kitty-shell-cwd://", false)]

    public static func path(_ reported: String, hostNames: Set<String>) -> String? {
        if reported.hasPrefix("/") { return reported }
        for (scheme, decoded) in schemes where reported.hasPrefix(scheme) {
            let rest = reported.dropFirst(scheme.count)
            guard let slash = rest.firstIndex(of: "/") else { return nil }
            let host = rest[..<slash].lowercased()
            guard host.isEmpty || host == "localhost" || hostNames.contains(where: { $0.lowercased() == host }) else { return nil }
            let path = String(rest[slash...])
            return decoded ? path.removingPercentEncoding : path
        }
        return nil
    }

    /// The host a report names, lowercased: "" for a bare path or a URL naming none, nil when it
    /// is neither.
    public static func host(_ reported: String) -> String? {
        if reported.hasPrefix("/") { return "" }
        for (scheme, _) in schemes where reported.hasPrefix(scheme) {
            let rest = reported.dropFirst(scheme.count)
            return String(rest[..<(rest.firstIndex(of: "/") ?? rest.endIndex)]).lowercased()
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
