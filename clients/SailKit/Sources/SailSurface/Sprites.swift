import Foundation
import SailVT

/// What the surface draws itself, at cell size, instead of asking a font: box drawing and block
/// elements (a font's glyphs do not meet at cell edges once the cell is not the font's own line
/// height), the underline styles, the strike and over lines, and the cursor.
public enum Sprite: Hashable, Sendable {
    /// A character U+2500 through U+259F.
    case box(UInt32)
    case underline(Underline)
    case strikethrough
    case overline
    case cursor(CursorShape)

    /// The sprite that draws `grapheme` in place of a font, nil when a font draws it.
    public static func drawing(_ grapheme: String) -> Sprite? {
        let scalars = grapheme.unicodeScalars
        guard scalars.count == 1, let scalar = scalars.first, BoxDrawing.covers(scalar.value) else { return nil }
        return .box(scalar.value)
    }
}

public enum CursorShape: Hashable, Sendable {
    case block, hollow, bar, underline
}

/// Coverage, 0 to 255, of a box of pixels, row by row from the top.
public struct AlphaBitmap: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public var pixels: [UInt8]

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
        pixels = [UInt8](repeating: 0, count: width * height)
    }

    public subscript(x: Int, y: Int) -> UInt8 {
        pixels[y * width + x]
    }

    /// Covers the pixels `x0..<x1` by `y0..<y1`, clipped to the box.
    mutating func fill(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, _ alpha: UInt8 = 255) {
        for y in max(0, y0) ..< min(height, max(y1, 0)) {
            for x in max(0, x0) ..< min(width, max(x1, 0)) {
                pixels[y * width + x] = max(pixels[y * width + x], alpha)
            }
        }
    }

    /// Covers each pixel by the share of its 4x4 sample points that `inside` holds.
    mutating func shade(_ inside: (Double, Double) -> Bool) {
        for y in 0 ..< height {
            for x in 0 ..< width {
                var hits = 0
                for sy in 0 ..< 4 {
                    for sx in 0 ..< 4 where inside(Double(x) + (Double(sx) + 0.5) / 4, Double(y) + (Double(sy) + 0.5) / 4) {
                        hits += 1
                    }
                }
                let alpha = UInt8((hits * 255 + 8) / 16)
                pixels[y * width + x] = max(pixels[y * width + x], alpha)
            }
        }
    }
}

extension Sprite {
    /// This sprite in a box `cells` wide of `metrics`' cells.
    public func render(_ metrics: CellMetrics, cells: Int = 1) -> AlphaBitmap {
        let width = metrics.width * max(1, cells)
        var bitmap = AlphaBitmap(width: width, height: metrics.height)
        let stroke = metrics.stroke
        switch self {
        case let .box(scalar):
            BoxDrawing.draw(scalar, into: &bitmap, stroke: stroke)
        case .strikethrough:
            bitmap.fill(0, metrics.strikethroughTop, width, metrics.strikethroughTop + stroke)
        case .overline:
            bitmap.fill(0, 0, width, stroke)
        case let .underline(style):
            Self.underline(style, into: &bitmap, top: metrics.underlineTop, stroke: stroke, period: metrics.width)
        case .cursor(.block):
            bitmap.fill(0, 0, width, metrics.height)
        case .cursor(.hollow):
            bitmap.fill(0, 0, width, stroke)
            bitmap.fill(0, metrics.height - stroke, width, metrics.height)
            bitmap.fill(0, 0, stroke, metrics.height)
            bitmap.fill(width - stroke, 0, width, metrics.height)
        case .cursor(.bar):
            bitmap.fill(0, 0, max(stroke, Int((metrics.scale * 1.5).rounded())), metrics.height)
        case .cursor(.underline):
            bitmap.fill(0, metrics.height - 2 * stroke, width, metrics.height)
        }
        return bitmap
    }

    private static func underline(_ style: Underline, into bitmap: inout AlphaBitmap, top: Int, stroke: Int, period: Int) {
        let width = bitmap.width
        let room = bitmap.height - top
        switch style {
        case .none:
            break
        case .single:
            bitmap.fill(0, top, width, top + stroke)
        case .double:
            let lower = min(bitmap.height - stroke, top + 2 * stroke)
            let upper = max(0, lower - 2 * stroke)
            bitmap.fill(0, upper, width, upper + stroke)
            bitmap.fill(0, lower, width, lower + stroke)
        case .dotted:
            for x in stride(from: 0, to: width, by: 2 * stroke) {
                bitmap.fill(x, top, x + stroke, top + stroke)
            }
        case .dashed:
            let dash = max(1, width / 4)
            bitmap.fill(dash / 2, top, dash / 2 + dash, top + stroke)
            bitmap.fill(width - dash / 2 - dash, top, width - dash / 2, top + stroke)
        case .curly:
            let amplitude = max(1, Double(min(room - stroke, 2 * stroke)) / 2)
            let centre = Double(top) + amplitude
            bitmap.shade { x, y in
                abs(y - (centre - amplitude * cos(2 * Double.pi * x / Double(period)))) <= Double(stroke) / 2 + 0.25
            }
        }
    }
}
