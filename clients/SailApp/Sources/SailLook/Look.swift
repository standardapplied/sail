import SailVT

/// The few Mast tokens the terminal view and its preview use, dark and light (`design-language.md`
/// section 2.1); the full set is spec 3's. The accent is terracotta always, never the system's,
/// and text on it is dark in both appearances.
public struct Look: Equatable, Sendable {
    public let palette: Palette
    public let surface: RGB
    public let border: RGB
    public let foreground: RGB
    public let mutedForeground: RGB
    public let accent: RGB
    public let onAccent: RGB

    public static let dark = Look(
        palette: .dark, surface: RGB(0x12171B), border: RGB(0x2B2E2F), foreground: RGB(0xF6F1E9), mutedForeground: RGB(0xC2BFB9),
        accent: RGB(0xFC4926), onAccent: RGB(0x0A0E11)
    )

    public static let light = Look(
        palette: .light, surface: RGB(0xFDFFFF), border: RGB(0xDCDEE1), foreground: RGB(0x0E1217), mutedForeground: RGB(0x383E43),
        accent: RGB(0xFC4926), onAccent: RGB(0x0A0E11)
    )

    /// Content corners, drawn at 6 pt; windows, menus and controls keep the platform's own.
    public static let contentRadius = 6.0
}
