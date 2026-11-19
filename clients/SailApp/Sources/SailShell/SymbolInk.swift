import AppKit
import SailLook

/// SF Symbols coloured once per look. Each symbol, at its size and weight, in each colour it is
/// drawn in, is made once as a non-template image coloured by its symbol configuration and kept. A
/// change of look then hands a button an image already made, instead of Core Animation tinting a
/// template copy of it again (`CATintedImage`) and converting its pixels at every switch.
@MainActor
package enum SymbolInk {
    private struct Key: Hashable {
        let name: String
        let points: Double
        let weight: CGFloat
        let colour: Tint
        let accessibility: String
    }

    private static var made: [Key: NSImage] = [:]
    /// How many symbol images were made: each a resolution of the symbol at its size, weight and
    /// colour, which a change of look between looks already shown never needs.
    package private(set) static var resolutions = 0

    /// How many distinct symbol images are kept: what one switch to a look not yet shown may make.
    package static var kept: Int {
        made.count
    }

    static func image(_ name: String, points: Double, weight: NSFont.Weight, colour: Tint, accessibility: String) -> NSImage {
        let key = Key(name: name, points: points, weight: weight.rawValue, colour: colour, accessibility: accessibility)
        if let image = made[key] { return image }
        resolutions += 1
        let symbol = NSImage(systemSymbolName: name, accessibilityDescription: accessibility) ?? NSImage()
        let configuration = NSImage.SymbolConfiguration(pointSize: points, weight: weight)
            .applying(NSImage.SymbolConfiguration(paletteColors: [NSColor(colour)]))
        let image = symbol.withSymbolConfiguration(configuration) ?? symbol
        image.isTemplate = false
        made[key] = image
        return image
    }
}
