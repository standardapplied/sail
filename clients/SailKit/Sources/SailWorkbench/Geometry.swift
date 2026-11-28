#if canImport(CoreGraphics)
import CoreGraphics
#endif
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

public struct Divider: Equatable, Sendable {
    public let path: [Side]
    public let axis: Axis
    public let line: CGRect
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

    /// Whether every pane of the layout is at least `minimum` in `size`, edges on `scale`'s pixels;
    /// against `before`, a pane already under the minimum there need only be no smaller, so a move
    /// toward fitting always fits.
    func fits(in size: CGSize, minimum: CGSize, scale: Double, against before: Layout? = nil) -> Bool {
        let rect = CGRect(origin: .zero, size: size)
        let was = before?.frames(in: rect, scale: scale).panes ?? [:]
        return frames(in: rect, scale: scale).panes.allSatisfy { pane, frame in
            let floor = was[pane].map { CGSize(width: min(minimum.width, $0.width), height: min(minimum.height, $0.height)) } ?? minimum
            return frame.width >= floor.width - 1e-9 && frame.height >= floor.height - 1e-9
        }
    }

    /// Every split's ratio clamped so each pane keeps at least `sliver` along its axis where the
    /// room allows, else equal shares: no stored ratio lays a pane out at nothing.
    func withSlivers(in rect: CGRect, sliver: Double) -> Layout {
        guard case let .split(axis, ratio, first, second) = self else { return self }
        let room = max(0, Self.extent(of: rect, along: axis) - Self.dividerWidth)
        let (one, two) = (first.weight(along: axis), second.weight(along: axis))
        let share = (one + two) * sliver <= room ? min(max(ratio, one * sliver / room), 1 - two * sliver / room) : one / (one + two)
        let lead = room * share
        let (near, far) = axis == .sideBySide
            ? (CGRect(x: rect.minX, y: rect.minY, width: lead, height: rect.height),
               CGRect(x: rect.minX + lead + Self.dividerWidth, y: rect.minY, width: room - lead, height: rect.height))
            : (CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: lead),
               CGRect(x: rect.minX, y: rect.minY + lead + Self.dividerWidth, width: rect.width, height: room - lead))
        return .split(axis, ratio: share, first.withSlivers(in: near, sliver: sliver), second.withSlivers(in: far, sliver: sliver))
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

/// How a strip of `width` shares its room: equal tabs from ``widest`` to ``narrowest`` and `+` and
/// a stretch to drag the window by always kept; past that, tabs at ``narrowest`` run from `first`,
/// the count kept at the strip's end, and an edge with tabs past it keeps ``fade`` for the part of
/// the next tab it shows under its fade.
public struct TabRow: Equatable, Sendable {
    public static let narrowest = 124.0
    public static let widest = 240.0
    public static let gap = 4.0
    public static let addSide = 24.0
    public static let stretch = 40.0
    /// The count's room at the strip's end, its box and the space after it: three digits fit.
    public static let countSide = 48.0
    public static let fade = 60.0

    public let tabs: Int
    public let width: Double
    /// The first tab shown whole, and how many are.
    public let first: Int
    public let shown: Int
    /// The room the tabs are drawn in, from the strip's leading edge: the `+` follows it.
    public let room: Double
    /// Where the first tab shown whole starts in that room: the room left before it, for the tab
    /// before it under the fade.
    public let inset: Double
    private let strip: Double

    /// The row of `tabs` in a strip `strip` wide, run from `first` as far as the strip lets.
    public init(tabs: Int, width strip: Double, first wanted: Int) {
        self.tabs = tabs
        self.strip = strip
        let free = strip - Self.addSide - Self.gap - Self.stretch
        guard Self.fitting(free) < tabs else {
            shown = tabs
            first = 0
            inset = 0
            width = tabs == 0 ? Self.widest : min(Self.widest, max(Self.narrowest, (free - Self.gap * Double(tabs - 1)) / Double(tabs)))
            room = tabs == 0 ? 0 : Double(tabs) * width + Self.gap * Double(tabs - 1)
            return
        }
        width = Self.narrowest
        room = free - Self.countSide - Self.gap
        let edge = Self.fitting(room - Self.fade), middle = Self.fitting(room - 2 * Self.fade)
        if wanted <= 0 {
            (first, shown, inset) = (0, edge, 0)
        } else if wanted >= tabs - edge {
            (first, shown) = (tabs - edge, edge)
            inset = room - Self.run(edge)
        } else {
            (first, shown) = (wanted, middle)
            inset = ((room - Self.run(middle)) / 2).rounded(.down)
        }
    }

    /// How many tabs at ``narrowest`` fit in `room`, one at the least.
    private static func fitting(_ room: Double) -> Int {
        max(1, Int((room + gap) / (narrowest + gap)))
    }

    /// The width of `count` tabs at ``narrowest`` side by side.
    private static func run(_ count: Int) -> Double {
        Double(count) * narrowest + gap * Double(count - 1)
    }

    /// Whether some tab is not shown whole: the strip scrolls and the count shows.
    public var scrolls: Bool {
        shown < tabs
    }

    /// Whether tabs lie past the leading edge, and past the trailing one: those edges fade.
    public var fadesBefore: Bool {
        first > 0
    }

    public var fadesAfter: Bool {
        first + shown < tabs
    }

    /// Where `+` starts, from the strip's leading edge.
    public var add: Double {
        tabs == 0 ? 0 : room + Self.gap
    }

    public func shows(_ index: Int) -> Bool {
        (first ..< first + shown).contains(index)
    }

    /// Where tab `index` starts in ``room``, nil when no part of it is in the room.
    public func start(of index: Int) -> Double? {
        let start = inset + Double(index - first) * (width + Self.gap)
        return start + width > 0 && start < room ? start : nil
    }

    /// The same row moved the least so tab `index` is shown whole.
    public func revealing(_ index: Int?) -> TabRow {
        guard let index, scrolls, !shows(index) else { return self }
        let middle = Self.fitting(room - 2 * Self.fade)
        return TabRow(tabs: tabs, width: strip, first: index < first ? index : index - middle + 1)
    }
}
