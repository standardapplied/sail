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

/// How a row is drawn: chosen, the selected fill at full strength while its list has the keys and
/// at half otherwise, with the accent bar; else the hover fill under the pointer. Its actions show
/// while it is hovered or chosen.
public struct RowPaint: Equatable, Sendable {
    public let fill: Tint?
    public let bar: Bool
    public let actions: Bool

    public init(_ state: RowState, look: Look) {
        fill = state.chosen ? (state.emphasized ? look.fillSelected : look.fillSelectedQuiet) : state.hovered ? look.fillHover : nil
        bar = state.chosen
        actions = state.chosen || state.hovered
    }

    public init(fill: Tint?, bar: Bool) {
        self.fill = fill
        self.bar = bar
        actions = false
    }
}

/// A project's tile: its lower-case initial in mono at 11 in its hue, on the hue at 14% with a 22% ring
/// (of the hue's own strength, as CSS mixes a translucent colour with transparent), in 4 pt
/// corners, 20 pt square. The hue comes from the first letter, as the design's table gives it for
/// its nine letters; any other Latin letter takes one of the four hues by its place in the
/// alphabet, and anything else is secondary, so a project keeps its colour everywhere.
public struct TilePaint: Equatable, Sendable {
    public static let side = 20.0, points = 11.0, radius = Look.innerRadius

    public let ink: Tint
    public let fill: Tint
    public let ring: Tint

    public init(_ name: String, look: Look) {
        ink = Self.hue(of: name, look: look)
        fill = Tint(ink.rgb, alpha: ink.alpha * 0.14)
        ring = Tint(ink.rgb, alpha: ink.alpha * 0.22)
    }

    static func hue(of name: String, look: Look) -> Tint {
        let designs: [Character: Tint] = [
            "a": look.textSecondary, "b": Tint(look.success), "d": Tint(look.warning), "h": Tint(look.accentText), "k": Tint(look.info),
            "l": Tint(look.info), "m": Tint(look.success), "q": Tint(look.warning), "t": Tint(look.accentText),
        ]
        guard let first = name.lowercased().first else { return look.textSecondary }
        if let hue = designs[first] { return hue }
        guard let letter = first.asciiValue, (97 ... 122).contains(letter) else { return look.textSecondary }
        return Tint([look.accentText, look.warning, look.info, look.success][Int(letter - 97) % 4])
    }
}
