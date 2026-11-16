import Foundation

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

/// Where a pane's session lives: this Mac's own host, opened in `folder`.
public enum Place: Hashable, Codable, Sendable {
    case thisMac(folder: String)

    /// What a session this Mac's host holds for the app is named after: a live one no pane holds is
    /// the app's still, and comes back as a tab.
    public static let thisMacSessions = "mac-"

    public var folder: String {
        switch self {
        case let .thisMac(folder): folder
        }
    }

    public var newSessionName: String {
        Self.thisMacSessions + UUID().uuidString.prefix(8).lowercased()
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
