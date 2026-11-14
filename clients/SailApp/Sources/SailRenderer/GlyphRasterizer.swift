import CoreGraphics
import CoreText
import Foundation
import SailLook
import SailSurface

/// Draws one grapheme cluster with Core Text into a slot one or two cells wide, on the cell's
/// baseline: `CTLine` shapes the cluster and falls back through the system's cascade for what
/// JetBrains Mono lacks. Text is antialiased in gray, never smoothed, so its weight is the font's
/// own. A cluster whose font has colour glyphs (emoji) is drawn in colour, scaled to fit.
final class GlyphRasterizer {
    let faces: TerminalFaces
    private var colourFonts: [String: Bool] = [:]

    init(faces: TerminalFaces) {
        self.faces = faces
    }

    var metrics: CellMetrics {
        faces.metrics
    }

    /// Whether Core Text draws `grapheme` from a font with colour glyphs.
    func isColour(_ grapheme: String, _ style: FontStyle) -> Bool {
        if let known = colourFonts[grapheme] { return known }
        let used = CTFontCreateForString(faces.font(style), grapheme as CFString, CFRange(location: 0, length: grapheme.utf16.count))
        let colour = CTFontGetSymbolicTraits(used).contains(.traitColorGlyphs)
        colourFonts[grapheme] = colour
        return colour
    }

    /// Coverage, one byte a pixel, rows from the top.
    func coverage(_ grapheme: String, _ style: FontStyle, cells: Int) -> [UInt8] {
        let width = metrics.width * cells
        let height = metrics.height
        var pixels = [UInt8](repeating: 0, count: width * height)
        pixels.withUnsafeMutableBytes { raw in
            guard let context = CGContext(
                data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return }
            context.setFillColor(gray: 1, alpha: 1)
            draw(grapheme, style, in: context, width: width, fit: false)
        }
        return pixels
    }

    /// Premultiplied BGRA, four bytes a pixel, rows from the top.
    func colour(_ grapheme: String, _ style: FontStyle, cells: Int) -> [UInt8] {
        let width = metrics.width * cells
        let height = metrics.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBytes { raw in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB), let context = CGContext(
                data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
            ) else { return }
            context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
            draw(grapheme, style, in: context, width: width, fit: true)
        }
        return pixels
    }

    private func draw(_ grapheme: String, _ style: FontStyle, in context: CGContext, width: Int, fit: Bool) {
        context.setAllowsAntialiasing(true)
        context.setShouldAntialias(true)
        context.setAllowsFontSmoothing(false)
        context.setShouldSmoothFonts(false)
        let text = NSAttributedString(string: grapheme, attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): faces.font(style),
            NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String): true,
        ])
        let line = CTLineCreateWithAttributedString(text as CFAttributedString)
        var ascent: CGFloat = 0
        var descent: CGFloat = 0
        var leading: CGFloat = 0
        let advance = CTLineGetTypographicBounds(line, &ascent, &descent, &leading)
        let height = Double(metrics.height)
        let tall = ascent + descent
        let shrink = fit && advance > 0 && tall > 0 ? min(1, Double(width) / advance, height / tall) : 1
        let x = max(0, (Double(width) - advance * shrink) / 2)
        if shrink < 1 {
            context.translateBy(x: x, y: (height - tall * shrink) / 2 + descent * shrink)
            context.scaleBy(x: shrink, y: shrink)
            context.textPosition = .zero
        } else {
            context.textPosition = CGPoint(x: x, y: height - Double(metrics.baseline))
        }
        CTLineDraw(line, context)
    }
}
