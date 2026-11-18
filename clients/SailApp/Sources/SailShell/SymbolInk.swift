import AppKit
import SailLook

/// SF Symbols coloured once per look. Each symbol, at its size and weight, in each colour it is
/// drawn in, is made once as a non-template image coloured by its symbol configuration and kept. A
/// change of look then hands a button an image already made, instead of Core Animation tinting a
/// template copy of it again (`CATintedImage`) and converting its pixels at every switch.
@MainActor
enum SymbolInk {
    private struct Key: Hashable {
        let name: String
        let points: Double
        let weight: CGFloat
        let colour: Tint
    }

    private static var made: [Key: NSImage] = [:]

    static func image(_ name: String, points: Double, weight: NSFont.Weight, colour: Tint, accessibility: String) -> NSImage {
        let key = Key(name: name, points: points, weight: weight.rawValue, colour: colour)
        if let image = made[key] { return image }
        let symbol = NSImage(systemSymbolName: name, accessibilityDescription: accessibility) ?? NSImage()
        let configuration = NSImage.SymbolConfiguration(pointSize: points, weight: weight)
            .applying(NSImage.SymbolConfiguration(paletteColors: [NSColor(colour)]))
        let image = symbol.withSymbolConfiguration(configuration) ?? symbol
        image.isTemplate = false
        made[key] = image
        return image
    }
}
