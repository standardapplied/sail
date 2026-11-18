import AppKit
@_exported import SailDesign

extension NSColor {
    /// A token with its opacity.
    public convenience init(_ tint: Tint) {
        self.init(srgbRed: Double(tint.rgb.red) / 255, green: Double(tint.rgb.green) / 255, blue: Double(tint.rgb.blue) / 255, alpha: tint.alpha)
    }
}
