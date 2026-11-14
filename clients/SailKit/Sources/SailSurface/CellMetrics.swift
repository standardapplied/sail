import SailVT

/// A monospace font's measures at the size it is drawn, in pixels: points times the display's
/// scale. `underlineOffset` is how far below the baseline the underline's centre sits.
public struct FontMetrics: Equatable, Sendable {
    public var advance: Double
    public var ascent: Double
    public var descent: Double
    public var leading: Double
    public var underlineOffset: Double
    public var underlineThickness: Double
    public var xHeight: Double

    public init(
        advance: Double, ascent: Double, descent: Double, leading: Double, underlineOffset: Double, underlineThickness: Double,
        xHeight: Double
    ) {
        self.advance = advance
        self.ascent = ascent
        self.descent = descent
        self.leading = leading
        self.underlineOffset = underlineOffset
        self.underlineThickness = underlineThickness
        self.xHeight = xHeight
    }
}

/// A size in whole pixels.
public struct PixelSize: Hashable, Sendable {
    public var width: Int
    public var height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }
}

/// One cell's box in whole pixels, and where its lines go: everything the surface lays out on.
/// The grid starts after the padding (10 pt across, 8 pt down, as Mast's terminal pads).
public struct CellMetrics: Equatable, Sendable {
    public static let paddingPoints = (x: 10.0, y: 8.0)

    public let width: Int
    public let height: Int
    /// From the cell's top to the baseline.
    public let baseline: Int
    /// The thickness of box-drawing lines, underlines and the bar cursor.
    public let stroke: Int
    public let underlineTop: Int
    public let strikethroughTop: Int
    public let padding: PixelSize
    public let scale: Double

    public init(font: FontMetrics, scale: Double) throws(SurfaceError) {
        let measures = [font.advance, font.ascent, font.descent, font.leading, font.underlineOffset, font.underlineThickness, font.xHeight]
        guard scale.isFinite, scale > 0, measures.allSatisfy({ $0.isFinite }), font.advance >= 1, font.ascent > 0,
              font.descent >= 0, font.leading >= 0, font.ascent + font.descent + font.leading < Double(Screen.largestCellPixels)
        else { throw .invalid("a font measuring \(font) at scale \(scale) has no cell") }
        width = max(1, Int(font.advance.rounded()))
        height = max(1, Int((font.ascent + font.descent + font.leading).rounded()))
        baseline = min(height, max(1, Int(((Double(height) - font.ascent - font.descent) / 2 + font.ascent).rounded())))
        stroke = max(1, Int(font.underlineThickness.rounded()))
        underlineTop = Self.clamped(baseline + Int((font.underlineOffset - Double(stroke) / 2).rounded()), height - stroke)
        strikethroughTop = Self.clamped(baseline - Int((font.xHeight / 2).rounded()) - stroke / 2, height - stroke)
        padding = PixelSize(width: Int((Self.paddingPoints.x * scale).rounded()), height: Int((Self.paddingPoints.y * scale).rounded()))
        self.scale = scale
    }

    /// The most whole cells that fit in a surface of `pixels`, padding included; one at least.
    public func grid(fitting pixels: PixelSize) -> GridSize {
        GridSize(
            columns: max(1, (pixels.width - 2 * padding.width) / width),
            rows: max(1, (pixels.height - 2 * padding.height) / height)
        )
    }

    /// The cell under a point in pixels from the surface's top left, held inside `grid`.
    public func cell(atX x: Double, y: Double, in grid: GridSize) -> CellPoint {
        func index(_ value: Double, _ pad: Int, _ size: Int, _ count: Int) -> Int {
            guard value.isFinite else { return 0 }
            return min(count - 1, max(0, Int(((value - Double(pad)) / Double(size)).rounded(.down))))
        }
        return CellPoint(x: index(x, padding.width, width, grid.columns), y: index(y, padding.height, height, grid.rows))
    }

    /// The top left of `cell`, in pixels from the surface's top left.
    public func origin(of cell: CellPoint) -> (x: Int, y: Int) {
        (padding.width + cell.x * width, padding.height + cell.y * height)
    }

    private static func clamped(_ value: Int, _ largest: Int) -> Int {
        min(max(0, largest), max(0, value))
    }
}

/// The font sizes ⌘+ and ⌘− step through, as Mast's terminal has them; ⌘0 returns to
/// ``standard``.
public enum FontLadder {
    public static let sizes: [Double] = [10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24, 28, 32]
    public static let standard = 15.0

    public static func larger(than size: Double) -> Double {
        sizes.first { $0 > size } ?? sizes[sizes.count - 1]
    }

    public static func smaller(than size: Double) -> Double {
        sizes.last { $0 < size } ?? sizes[0]
    }
}

public enum SurfaceError: Error, Equatable, CustomStringConvertible {
    case invalid(String)

    public var description: String {
        switch self {
        case let .invalid(what): what
        }
    }
}
