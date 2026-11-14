import GhosttyVt

/// Widths as the terminal prints text: grapheme clustering (mode 2027) is on in every screen, so an
/// emoji sequence, a flag or a skin-toned emoji is one cluster.
public enum Graphemes {
    /// The cells the first grapheme cluster of `text` takes: 0, 1 or 2.
    public static func cells(_ text: String) -> Int {
        let points = text.unicodeScalars.map(\.value)
        guard !points.isEmpty else { return 0 }
        var width: UInt8 = 0
        _ = points.withUnsafeBufferPointer { ghostty_unicode_grapheme_width($0.baseAddress, $0.count, &width) }
        return Int(width)
    }
}
