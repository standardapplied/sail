import SailVT

/// A colour token: an sRGB colour and its opacity over whatever it is drawn on.
public struct Tint: Hashable, Sendable {
    public let rgb: RGB
    public let alpha: Double

    public init(_ hex: UInt32, alpha: Double = 1) {
        rgb = RGB(hex)
        self.alpha = alpha
    }
}

/// The Sail look's tokens, dark and light (`design-language.md` 2.1.1). The accent is terracotta
/// always, never the system's. Text on it is `onAccent`: dark ink in the dark appearance, white in
/// the light (the owner's choice), which is 3.43:1 on terracotta, so text on an accent fill is
/// always large text (bold) and counts against 3:1.
public struct Look: Equatable, Sendable {
    public let palette: Palette
    public let canvas: RGB
    public let surface: RGB
    public let chrome: RGB
    public let text: Tint
    public let textSecondary: Tint
    public let separator: Tint
    public let separatorStrong: Tint
    public let accent: RGB
    public let accentText: RGB
    public let onAccent: RGB
    public let fillSelected: Tint
    public let fillHover: Tint

    public static let dark = Look(
        palette: .dark, canvas: RGB(0x0A0E11), surface: RGB(0x12171B), chrome: RGB(0x251717), text: Tint(0xF6F1E9),
        textSecondary: Tint(0xF6F1E9, alpha: 0.78), separator: Tint(0xF6F1E9, alpha: 0.14), separatorStrong: Tint(0xF6F1E9, alpha: 0.24), accent: RGB(0xFC4926),
        accentText: RGB(0xFC4926), onAccent: RGB(0x0A0E11), fillSelected: Tint(0xFC4926, alpha: 0.16), fillHover: Tint(0xF6F1E9, alpha: 0.06)
    )

    public static let light = Look(
        palette: .light, canvas: RGB(0xF8FAFC), surface: RGB(0xFDFFFF), chrome: RGB(0xF7EAE2), text: Tint(0x0E1217),
        textSecondary: Tint(0x383E43), separator: Tint(0x0E1217, alpha: 0.12), separatorStrong: Tint(0x0E1217, alpha: 0.22),
        accent: RGB(0xFC4926), accentText: RGB(0xC8321F), onAccent: RGB(0xFFFFFF), fillSelected: Tint(0xFC4926, alpha: 0.16),
        fillHover: Tint(0x0E1217, alpha: 0.05)
    )

    /// Content corners, drawn at 6 pt; windows, sheets, menus and system controls keep the
    /// platform's own.
    public static let contentRadius = 6.0

    /// The 4-point spacing grid (2.3), the steps the shell uses.
    public enum Space {
        public static let s = 8.0
        public static let m = 12.0
        public static let l = 16.0
        public static let xxl = 24.0
        public static let x3l = 32.0
    }

    /// The type steps the shell draws (2.2.2), in points.
    public enum Step: Double {
        case micro = 11, callout = 13, body = 14, title2 = 22

        public var points: Double {
            rawValue
        }
    }
}
