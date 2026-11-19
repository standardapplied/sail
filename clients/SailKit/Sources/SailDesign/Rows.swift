import SailVT

/// The two kinds of list row the app draws: the sidebar's, which is the left panel's project row
/// (30 pt, 6 pt in from both sides, rounded, no gap between rows, a 2 × 14 bar at the list's
/// leading edge, its content after a 12 pt disclosure slot with 7 pt gaps), and the main list's
/// (52 pt, edge to edge, a 2 × 24 bar, a hairline between rows). A group head is the mono eyebrow
/// alone: 34 pt in the main list, under a hairline, and 30 pt in the sidebar.
public struct RowStyle: Equatable, Sendable {
    public let height: Double
    public let head: Double
    public let margin: Double
    public let leading: Double
    public let trailing: Double
    public let radius: Double
    public let bar: Double
    public let hairline: Bool

    public static let sidebar = RowStyle(height: 30, head: 30, margin: 6, leading: 6, trailing: 6, radius: Look.smallRadius, bar: 14, hairline: false)
    public static let list = RowStyle(height: 52, head: 34, margin: 0, leading: 20, trailing: 12, radius: 0, bar: 24, hairline: true)
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

/// How a row is drawn, on paper: no row is ever filled. A chosen row has the accent bar and its
/// name at full weight; one under the pointer has its name at full weight too. Its actions show
/// while it is hovered or chosen.
public struct RowPaint: Equatable, Sendable {
    public let bar: Bool
    /// Its name at full weight (semibold).
    public let strong: Bool
    public let actions: Bool
    /// A 2 pt `info` line runs along its foot: something under way, about as long as it takes.
    public let progress: Bool

    public init(_ state: RowState) {
        bar = state.chosen
        strong = state.chosen || state.hovered
        actions = state.chosen || state.hovered
        progress = false
    }

    private init(bar: Bool, strong: Bool, actions: Bool, progress: Bool) {
        self.bar = bar
        self.strong = strong
        self.actions = actions
        self.progress = progress
    }

    /// A create row open for a name: drawn chosen, its field holding the keys.
    public static let naming = Self(bar: true, strong: false, actions: false, progress: false)

    /// A create row refused: no bar; its field's edge, mark and sentence say it.
    public static let refused = Self(bar: false, strong: false, actions: false, progress: false)

    /// A row being created: its actions (how long, a spinner) shown, the progress line along its foot.
    public static let creating = Self(bar: false, strong: false, actions: true, progress: true)
}

/// A project's tile, 20 pt square in 4 pt corners: the look's `well` for every project, an outline
/// and a quiet letter, and `wellChosen` for the chosen project's. No project has a hue of its own
/// (the owner, 19 Nov 2026). `mark` flips every tile to the box icon instead of the initial.
public struct TilePaint: Equatable, Sendable {
    public enum Mark: Equatable, Sendable {
        case initial, icon(LineIcon)
    }

    public static let side = 20.0, points = 11.0, radius = Look.innerRadius
    public static let mark = Mark.initial

    public let ink: Tint
    public let fill: Tint?
    public let ring: Tint?

    public init(look: Look, chosen: Bool = false) {
        let mark = chosen ? look.wellChosen : look.well
        ink = mark.ink
        fill = mark.fill
        ring = mark.edge
    }
}
