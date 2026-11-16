import Foundation

/// Where a tab's panes and dividers go, in points from the tab's top left, every edge on a
/// display pixel.
public struct Frames: Equatable, Sendable {
    public var panes: [PaneID: CGRect] = [:]
    public var dividers: [Divider] = []

    public init(panes: [PaneID: CGRect] = [:], dividers: [Divider] = []) {
        self.panes = panes
        self.dividers = dividers
    }
}

/// The line between a split's sides.
public struct Divider: Equatable, Sendable {
    /// Its split's place in the tree.
    public let path: [Side]
    public let axis: Axis
    /// The line, ``Layout/dividerWidth`` across.
    public let line: CGRect
    /// The split's whole room, both sides and the line.
    public let split: CGRect

    /// Where the pointer takes the divider: ``Layout/dividerHitWidth`` across, centred on the line.
    public var hitArea: CGRect {
        let grow = (Layout.dividerHitWidth - Layout.dividerWidth) / 2
        return axis == .sideBySide ? line.insetBy(dx: -grow, dy: 0) : line.insetBy(dx: 0, dy: -grow)
    }
}

public enum Direction: Sendable, CaseIterable {
    case left, right, up, down

    var axis: Axis {
        self == .left || self == .right ? .sideBySide : .stacked
    }

    var towardStart: Bool {
        self == .left || self == .up
    }
}

extension Layout {
    public static let dividerWidth = 1.0
    public static let dividerHitWidth = 7.0

    /// Every pane's frame and every divider's line in `rect`, edges rounded to `scale`'s pixels.
    func frames(in rect: CGRect, scale: Double) -> Frames {
        var frames = Frames()
        place(in: rect, scale: scale, path: [], into: &frames)
        return frames
    }

    private func place(in rect: CGRect, scale: Double, path: [Side], into frames: inout Frames) {
        switch self {
        case let .pane(id):
            frames.panes[id] = rect
        case let .split(axis, ratio, first, second):
            let room = max(0, Self.extent(of: rect, along: axis) - Self.dividerWidth)
            let lead = min(room, (room * ratio * scale).rounded() / scale)
            let (one, line, other) = axis == .sideBySide
                ? (
                    CGRect(x: rect.minX, y: rect.minY, width: lead, height: rect.height),
                    CGRect(x: rect.minX + lead, y: rect.minY, width: Self.dividerWidth, height: rect.height),
                    CGRect(x: rect.minX + lead + Self.dividerWidth, y: rect.minY, width: room - lead, height: rect.height)
                )
                : (
                    CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: lead),
                    CGRect(x: rect.minX, y: rect.minY + lead, width: rect.width, height: Self.dividerWidth),
                    CGRect(x: rect.minX, y: rect.minY + lead + Self.dividerWidth, width: rect.width, height: room - lead)
                )
            frames.dividers.append(Divider(path: path, axis: axis, line: line, split: rect))
            first.place(in: one, scale: scale, path: path + [.first], into: &frames)
            second.place(in: other, scale: scale, path: path + [.second], into: &frames)
        }
    }

    static func extent(of rect: CGRect, along axis: Axis) -> Double {
        axis == .sideBySide ? rect.width : rect.height
    }

    static func extent(of size: CGSize, along axis: Axis) -> Double {
        axis == .sideBySide ? size.width : size.height
    }

    /// The least room the layout takes along `axis`, each pane at least `minimum`.
    func minimum(along axis: Axis, pane minimum: CGSize) -> Double {
        switch self {
        case .pane: Self.extent(of: minimum, along: axis)
        case let .split(own, _, first, second):
            own == axis
                ? first.minimum(along: axis, pane: minimum) + Self.dividerWidth + second.minimum(along: axis, pane: minimum)
                : max(first.minimum(along: axis, pane: minimum), second.minimum(along: axis, pane: minimum))
        }
    }

    /// The pane nearest past `from`'s edge toward `direction` that overlaps it on the other axis;
    /// of panes as near, the one focused most recently (latest in `history`).
    static func neighbour(of from: CGRect, toward direction: Direction, among frames: [PaneID: CGRect], history: [PaneID]) -> PaneID? {
        func gap(_ other: CGRect) -> Double? {
            let gap = switch direction {
            case .left: from.minX - other.maxX
            case .right: other.minX - from.maxX
            case .up: from.minY - other.maxY
            case .down: other.minY - from.maxY
            }
            let overlap = direction.axis == .sideBySide
                ? min(from.maxY, other.maxY) - max(from.minY, other.minY)
                : min(from.maxX, other.maxX) - max(from.minX, other.minX)
            return gap > -0.5 && overlap > 0.5 ? gap : nil
        }
        let past = frames.compactMap { id, frame in gap(frame).map { (id: id, gap: $0) } }
        guard let nearest = past.map(\.gap).min() else { return nil }
        func recency(_ id: PaneID) -> Int {
            history.lastIndex(of: id) ?? -1
        }
        return past.filter { $0.gap < nearest + 0.5 }.map(\.id).max { (recency($0), $1.value) < (recency($1), $0.value) }
    }
}
