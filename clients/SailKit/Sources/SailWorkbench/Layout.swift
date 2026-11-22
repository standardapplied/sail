import Foundation
import SailTerminalWire

/// A pane's identity in its workbench, made once and never given to another pane.
public struct PaneID: Hashable, Comparable, Sendable, CustomStringConvertible {
    public let value: Int

    public init(_ value: Int) {
        self.value = value
    }

    public static func < (one: PaneID, other: PaneID) -> Bool {
        one.value < other.value
    }

    public var description: String {
        "pane \(value)"
    }
}

/// A tab's identity in its workbench, made once and never given to another tab.
public struct TabID: Hashable, Sendable {
    public let value: Int

    public init(_ value: Int) {
        self.value = value
    }
}

/// Where a pane's session lives: this Mac's own host, opened in `folder`, or workspace `name` of
/// `project` on the runner the app is signed in to.
public enum Place: Hashable, Codable, Sendable {
    case thisMac(folder: String)
    case workspace(project: String, name: String)

    /// What a session this Mac's host holds for the app is named after: a live one no pane holds is
    /// the app's still, and comes back as a tab.
    public static let thisMacSessions = "mac-"
    /// What a session the app opens on a runner is named after, never taken for one of this Mac's.
    public static let runnerSessions = "app-"

    /// The folder a session here opens in: none in a workspace, whose shell starts where it starts.
    public var folder: String {
        switch self {
        case let .thisMac(folder): folder
        case .workspace: ""
        }
    }

    /// Whether a session here is on a runner, which every runner-or-local decision asks.
    public var onRunner: Bool {
        if case .workspace = self { true } else { false }
    }

    public var newSessionName: String {
        let prefix = onRunner ? Self.runnerSessions : Self.thisMacSessions
        return prefix + UUID().uuidString.prefix(8).lowercased()
    }

    /// What a new tab here asks its host to make, the one place the app builds it: a login shell
    /// named `session` at `size`, in this Mac's folder; in a workspace, where the runner starts it in
    /// the instance's own directory, so it names no folder.
    public func sessionCreate(session: String, size: TerminalSize) -> SessionCreate {
        switch self {
        case let .thisMac(folder):
            SessionCreate(session: session, command: [], workingDirectory: folder, project: "", room: "", size: size)
        case let .workspace(project, name):
            SessionCreate(session: session, command: [], workingDirectory: "", project: project, room: "", size: size, workspace: name)
        }
    }

    /// What a tab here is called until the person names it, words only: nil on this Mac, where its
    /// program's title names it; in a workspace, where it is ("acme · try").
    public var label: String? {
        switch self {
        case .thisMac: nil
        case let .workspace(project, name): "\(project) · \(name)"
        }
    }
}

/// One terminal: its session, for the pane's life, and what its program last said.
public struct Pane: Hashable, Sendable {
    public let id: PaneID
    public let session: String
    public let place: Place
    /// The program's title (OSC 0 or 2), empty for none.
    public internal(set) var programTitle = ""
    /// The folder the shell last reported (OSC 7), as it was sent, empty for none.
    public internal(set) var reportedFolder = ""
    /// The host the shell's first report named ("" for none), nil before its first report.
    public internal(set) var firstHost: String?

    /// Whether the shell reports a folder on another host than its first report named: the person
    /// went to another machine from it, and its output is that machine's. A first report naming no
    /// host compares nothing.
    public var elsewhere: Bool {
        guard let firstHost, !firstHost.isEmpty else { return false }
        return ReportedFolder.host(reportedFolder) != firstHost
    }
}

/// How a split lays its sides out: side by side (split right) or stacked (split down).
public enum Axis: String, Codable, Sendable {
    case sideBySide, stacked
}

public enum Side: Hashable, Sendable {
    case first, second
}

/// A tab's panes: one pane, or a split of two layouts along an axis, the first side taking
/// `ratio` of the room the divider leaves.
public indirect enum Layout: Hashable, Sendable {
    case pane(PaneID)
    case split(Axis, ratio: Double, Layout, Layout)

    /// The panes in reading order: first side before second, depth first.
    public var leaves: [PaneID] {
        switch self {
        case let .pane(id): [id]
        case let .split(_, _, first, second): first.leaves + second.leaves
        }
    }

    func contains(_ pane: PaneID) -> Bool {
        path(to: pane) != nil
    }

    func path(to pane: PaneID) -> [Side]? {
        switch self {
        case let .pane(id): id == pane ? [] : nil
        case let .split(_, _, first, second):
            first.path(to: pane).map { [.first] + $0 } ?? second.path(to: pane).map { [.second] + $0 }
        }
    }

    func node(at path: [Side]) -> Layout? {
        guard let side = path.first else { return self }
        guard case let .split(_, _, first, second) = self else { return nil }
        return (side == .first ? first : second).node(at: Array(path.dropFirst()))
    }

    func replacing(_ pane: PaneID, with layout: Layout) -> Layout {
        switch self {
        case let .pane(id): id == pane ? layout : self
        case let .split(axis, ratio, first, second):
            .split(axis, ratio: ratio, first.replacing(pane, with: layout), second.replacing(pane, with: layout))
        }
    }

    /// The layout without `pane`, its split's other side taking the split's place; nil when the
    /// pane was all there was.
    func removing(_ pane: PaneID) -> Layout? {
        switch self {
        case let .pane(id): return id == pane ? nil : self
        case let .split(axis, ratio, first, second):
            let (kept, other) = (first.removing(pane), second.removing(pane))
            guard let kept, let other else { return kept ?? other }
            return .split(axis, ratio: ratio, kept, other)
        }
    }

    func settingRatio(_ ratio: Double, at path: [Side]) -> Layout {
        guard case let .split(axis, own, first, second) = self else { return self }
        guard let side = path.first else { return .split(axis, ratio: ratio, first, second) }
        let rest = Array(path.dropFirst())
        return side == .first
            ? .split(axis, ratio: own, first.settingRatio(ratio, at: rest), second)
            : .split(axis, ratio: own, first, second.settingRatio(ratio, at: rest))
    }

    /// How many panes share the room along `axis`: a pane is one, a split along the same axis its
    /// sides' sum, a split across it its larger side.
    func weight(along axis: Axis) -> Double {
        switch self {
        case .pane: 1
        case let .split(own, _, first, second):
            own == axis
                ? first.weight(along: axis) + second.weight(along: axis)
                : max(first.weight(along: axis), second.weight(along: axis))
        }
    }

    /// Every split's first share its first side's weight over both.
    func equalized() -> Layout {
        guard case let .split(axis, _, first, second) = self else { return self }
        let share = first.weight(along: axis) / (first.weight(along: axis) + second.weight(along: axis))
        return .split(axis, ratio: share, first.equalized(), second.equalized())
    }
}
