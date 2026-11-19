#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation
import SailVT

/// A banner's tone: what it tells, in Mast's status colours, never the accent.
public enum BannerTone: CaseIterable, Sendable {
    case info, warning, error

    /// Its 16 pt mark.
    public var icon: LineIcon {
        switch self {
        case .info: .info
        case .warning: .warning
        case .error: .error
        }
    }
}

/// How a banner is drawn: the tone mixed 8% into the surface, a 35% edge in the tone, the mark in
/// the tone; the title in ink and the line under it secondary.
public struct BannerPaint: Equatable, Sendable {
    public let fill: RGB
    public let edge: Tint
    public let mark: Tint

    public init(_ tone: BannerTone, look: Look) {
        let ink = switch tone {
        case .info: look.info
        case .warning: look.warning
        case .error: look.error
        }
        fill = look.surface.blended(toward: ink, by: 0.08)
        edge = Tint(ink, alpha: 0.35)
        mark = Tint(ink)
    }
}

/// An empty state's tile: the surface with an 8 pt grid, its edge solid when the place is only
/// empty and dashed in the strong separator when something is missing.
public struct EmptyTilePaint: Equatable, Sendable {
    public static let size = 56.0, grid = 8.0

    public let fill: Tint
    public let edge: Tint
    public let dashed: Bool
    public let grid: Tint
    public let ink: Tint

    public init(missing: Bool, look: Look) {
        fill = Tint(look.surface)
        edge = missing ? look.separatorStrong : look.separator
        dashed = missing
        grid = look.gridLine
        ink = look.textSecondary
    }
}

/// How a tooltip is drawn and when it shows: the ink chip in 6 pt corners, 5 pt above and below
/// its text, 9 before and 8 after, an 8 pt arrow, 5 pt from its control, a two-layer shadow; the
/// shortcut as a keycap (Medium 10 at 88% on 16% of the chip's text colour, 4 pt corners, 2 by 5 pt
/// padding) 8 pt after the words; shown after 0.6 s, and at once for another control within 1 s of
/// the last tooltip going.
public enum TooltipStyle {
    public struct Shadow: Equatable, Sendable {
        public let y: Double, blur: Double, alpha: Double

        public init(y: Double, blur: Double, alpha: Double) {
            self.y = y
            self.blur = blur
            self.alpha = alpha
        }
    }

    public static let radius = 6.0, vertical = 5.0, leading = 9.0, trailing = 8.0, arrow = 8.0, gap = 5.0
    public static let shadows = [Shadow(y: 1, blur: 2, alpha: 0.12), Shadow(y: 6, blur: 16, alpha: 0.16)]
    public static let delay = 0.6, warm = 1.0, hintGap = 8.0
    public static let keycapFill = 0.16, keycapText = 0.88, keycapRadius = 4.0, keycapPadding = (vertical: 2.0, horizontal: 5.0)
}

/// Where a tooltip shows: under its anchor, centred, its arrow on top; above when the screen has no
/// room below; beside it, the arrow at its leading edge, for a folded sidebar's tile. Always inside
/// the screen's visible frame, the arrow still pointing at the anchor's middle. Screen points, y up.
public struct TooltipPlacement: Equatable, Sendable {
    /// Where the chip stands from its control on screen; its arrow is on the facing edge.
    public enum Side: Sendable {
        case below, above, right, left
    }

    /// The arrow is kept this far from the chip's corners, and the chip this far inside the window.
    public static let arrowInset = 9.0, windowInset = 10.0

    public let frame: CGRect
    public let side: Side
    /// Along the edge the arrow is on, from the chip's left or bottom.
    public let arrow: Double

    /// The chip `chip` big for a control at `anchor`: below it, or above when the screen has no room
    /// below; beside it (to its right, to its left right to left) for a rail item. It stays 10 pt
    /// inside the window, as the design keeps it, and then inside the screen.
    public init(chip: CGSize, anchor: CGRect, window: CGRect, screen: CGRect, beside: Bool, rightToLeft: Bool = false, gap: Double = TooltipStyle.gap) {
        let width = Double(chip.width), height = Double(chip.height)
        let inside = window.insetBy(dx: Self.windowInset, dy: Self.windowInset)
        func clamp(_ value: Double, _ low: Double, _ high: Double) -> Double {
            max(low, min(value, max(low, high)))
        }
        func across(_ value: Double) -> Double {
            clamp(clamp(value, Double(inside.minX), Double(inside.maxX) - width), Double(screen.minX), Double(screen.maxX) - width)
        }
        func up(_ value: Double) -> Double {
            clamp(clamp(value, Double(inside.minY), Double(inside.maxY) - height), Double(screen.minY), Double(screen.maxY) - height)
        }
        if beside {
            let x = rightToLeft ? Double(anchor.minX) - gap - width : Double(anchor.maxX) + gap
            let y = up(Double(anchor.midY) - height / 2)
            frame = CGRect(x: clamp(x, Double(screen.minX), Double(screen.maxX) - width), y: y, width: width, height: height)
            side = rightToLeft ? .left : .right
            arrow = clamp(Double(anchor.midY) - y, Self.arrowInset / 2, height - Self.arrowInset / 2)
            return
        }
        let below = Double(anchor.minY) - gap - height
        side = below >= Double(screen.minY) ? .below : .above
        let y = side == .below ? below : Double(anchor.maxY) + gap
        let x = across(Double(anchor.midX) - width / 2)
        frame = CGRect(x: x, y: y, width: width, height: height)
        arrow = clamp(Double(anchor.midX) - x, Self.arrowInset, width - Self.arrowInset)
    }
}

/// The shadow a layer floating over the content takes (the panel slid over it, popovers, toasts,
/// sheets): a hairline ring and two soft layers, deeper in the dark look. In-flow surfaces stay flat.
public enum PopShadow {
    public struct Layer: Equatable, Sendable {
        public let y: Double, blur: Double, spread: Double, color: Tint

        public init(y: Double, blur: Double, spread: Double = 0, color: Tint) {
            self.y = y
            self.blur = blur
            self.spread = spread
            self.color = color
        }
    }

    public static func layers(_ look: Look) -> [Layer] {
        look.palette.isDark
            ? [Layer(y: 0, blur: 0, spread: 1, color: Tint(0x000000, alpha: 0.5)), Layer(y: 6, blur: 14, color: Tint(0x000000, alpha: 0.35)), Layer(y: 18, blur: 44, color: Tint(0x000000, alpha: 0.5))]
            : [Layer(y: 0, blur: 0, spread: 0.5, color: Tint(0x0E1217, alpha: 0.05)), Layer(y: 4, blur: 10, color: Tint(0x0E1217, alpha: 0.07)), Layer(y: 14, blur: 36, color: Tint(0x0E1217, alpha: 0.14))]
    }
}
