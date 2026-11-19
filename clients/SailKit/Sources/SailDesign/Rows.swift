import SailVT

/// The two kinds of list row the app draws: the sidebar's, which is the left panel's project row
/// (30 pt, 6 pt in from both sides, rounded, no gap between rows, a 2 × 14 bar at the list's
/// leading edge, its content after a 12 pt disclosure slot with 7 pt gaps), and the main list's
/// (52 pt, edge to edge, a 2 × 24 bar, a hairline between rows). Group heads are 30 pt in both.
public struct RowStyle: Equatable, Sendable {
    public let height: Double
    public let margin: Double
    public let leading: Double
    public let trailing: Double
    public let radius: Double
    public let bar: Double
    public let hairline: Bool

    public static let sidebar = RowStyle(height: 30, margin: 6, leading: 6, trailing: 6, radius: Look.smallRadius, bar: 14, hairline: false)
    public static let list = RowStyle(height: 52, margin: 0, leading: 20, trailing: 12, radius: 0, bar: 24, hairline: true)
    public static let groupHeight = 30.0
    public static let barWidth = 2.0
    public static let disclosure = 12.0, gap = 7.0
}

/// What a row is doing: chosen, its list holding the keys, under the pointer. The keys move the
/// choice itself, so no row is marked apart from it.
public struct RowState: Equatable, Sendable {
    public var chosen = false
    public var emphasized = false
    public var hovered = false

    public init(chosen: Bool = false, emphasized: Bool = false, hovered: Bool = false) {
        self.chosen = chosen
        self.emphasized = emphasized
        self.hovered = hovered
    }
}

/// How a row is drawn: chosen, the selected ink fill at full strength while its list has the keys
/// and quieter otherwise, with the accent bar saying which; else the hover fill under the pointer.
/// Its actions show while it is hovered or chosen.
public struct RowPaint: Equatable, Sendable {
    public let fill: Tint?
    public let bar: Bool
    public let actions: Bool
    /// A 2 pt `info` line runs along its foot: something under way, about as long as it takes.
    public let progress: Bool

    public init(_ state: RowState, look: Look) {
        fill = state.chosen ? (state.emphasized ? look.fillSelected : look.fillSelectedQuiet) : state.hovered ? look.fillHover : nil
        bar = state.chosen
        actions = state.chosen || state.hovered
        progress = false
    }

    private init(fill: Tint?, bar: Bool, actions: Bool, progress: Bool) {
        self.fill = fill
        self.bar = bar
        self.actions = actions
        self.progress = progress
    }

    /// A create row open for a name: drawn chosen, quietly, its field holding the keys.
    public static func naming(_ look: Look) -> Self {
        Self(fill: look.fillSelectedQuiet, bar: true, actions: false, progress: false)
    }

    /// A create row refused: no fill and no bar; its field's edge, mark and sentence say it.
    public static let refused = Self(fill: nil, bar: false, actions: false, progress: false)

    /// A row being created: no fill, its actions (how long, a spinner) shown, the progress line
    /// along its foot.
    public static let creating = Self(fill: nil, bar: false, actions: true, progress: true)
}

/// A project's tile, 20 pt square in 4 pt corners: the same `well` for every project, a
/// hairline ring, and its lower-case initial in mono at 11 in the secondary ink. No project has a
/// hue of its own: the owner found the hues diluted the panel (19 Nov 2026). `mark` flips every
/// tile to the box icon instead of the initial.
public struct TilePaint: Equatable, Sendable {
    public enum Mark: Equatable, Sendable {
        case initial, icon(LineIcon)
    }

    public static let side = 20.0, points = 11.0, radius = Look.innerRadius
    public static let mark = Mark.initial

    public let ink: Tint
    public let fill: Tint
    public let ring: Tint

    public init(look: Look) {
        ink = look.textSecondary
        fill = look.well
        ring = look.separator
    }
}
