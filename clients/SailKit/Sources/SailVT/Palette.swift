/// The terminal's colours: Mast's, dark and light, verbatim (`design-language.md` section 1.2.4,
/// from Mast's `ansi.ts`). Slots 16 through 255 are libghostty-vt's standard ones.
public struct Palette: Equatable, Sendable {
    public var foreground: RGB
    public var background: RGB
    public var cursor: RGB
    /// Black, red, green, yellow, blue, magenta, cyan and white, then their bright variants.
    public var ansi: [RGB]
    public var isDark: Bool

    /// How far a dim colour moves toward the background.
    public static let dimming = 0.45

    public static let dark = Palette(
        foreground: RGB(0xF6F1E9), background: RGB(0x0A0E11), cursor: RGB(0xFC4926),
        ansi: [
            0x12171B, 0xE07B6F, 0x86B89A, 0xD2A24C, 0x93B3D7, 0xD08FA6, 0x7FBFCA, 0xC9C4BC,
            0x4A5560, 0xF0968A, 0xA3D1B3, 0xE6BA69, 0xAEC8E8, 0xE3A8BC, 0x9CD4DE, 0xF6F1E9,
        ].map(RGB.init),
        isDark: true
    )

    /// In light mode a bright colour is a stronger one, not a lighter one.
    public static let light = Palette(
        foreground: RGB(0x0E1217), background: RGB(0xF8FAFC), cursor: RGB(0xFC4926),
        ansi: [
            0x0E1217, 0xB42318, 0x256E4A, 0x8A5800, 0x31608F, 0x9D4260, 0x17646F, 0x6D7277,
            0x383E43, 0x8F1C12, 0x1D5A3C, 0x6E4600, 0x274D73, 0x7E344D, 0x125058, 0x0E1217,
        ].map(RGB.init),
        isDark: false
    )
}
