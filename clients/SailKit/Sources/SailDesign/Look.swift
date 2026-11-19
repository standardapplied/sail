import SailVT

/// A colour token: an sRGB colour and its opacity over whatever it is drawn on.
public struct Tint: Hashable, Sendable {
    public let rgb: RGB
    public let alpha: Double

    public init(_ hex: UInt32, alpha: Double = 1) {
        self.init(RGB(hex), alpha: alpha)
    }

    public init(_ rgb: RGB, alpha: Double = 1) {
        self.rgb = rgb
        self.alpha = alpha
    }
}

/// The Sail look's tokens, dark and light (`design-language.md` 2.1.1, and the controls spec's
/// table). The accent is terracotta always, never the system's. Text on it is `onAccent`: dark ink
/// in the dark appearance, white in the light (the owner's choice), which is 3.43:1 on terracotta,
/// so text on an accent fill is always large text (bold) and counts against 3:1. Status colours are
/// Mast's; terracotta never means a status or an error. The look is calm: terracotta fills only the
/// one primary action in view and marks where a person acts (the caret, the cursor with the keys,
/// focus, a link's underline, the chosen bar); choosing is ink; red (crimson) means wrong or
/// destroys. Its light tint is only under selected text and in the focus halo.
public struct Look: Equatable, Sendable {
    /// The terminal's colours, its selection and idle cursor taken from this look's tokens.
    public private(set) var palette: Palette
    public let canvas: RGB
    public let surface: RGB
    public let surfacePop: RGB
    public let chrome: RGB
    public let text: Tint
    public let textSecondary: Tint
    public private(set) var textTertiary: Tint
    public private(set) var separator: Tint
    public private(set) var separatorStrong: Tint
    public private(set) var gridLine: Tint
    public let accent: RGB
    public let accentHover: RGB
    public let accentPressed: RGB
    public let onAccent: RGB
    public let fillSelected: Tint
    public private(set) var fillSelectedQuiet: Tint
    public let fillHover: Tint
    public let fillPressed: Tint
    /// The band under a list's group head ("YOURS 3").
    public let groupHead: Tint
    /// A project's letter well, the same for every project.
    public let well: Tint
    public private(set) var focusHalo: Tint
    /// Under selected text, in fields and in the terminal: the accent's one tint besides the halo.
    public let textSelection: Tint
    public let error: RGB
    public let errorHover: RGB
    public let errorPressed: RGB
    public let onError: RGB
    public let warning: RGB
    public let success: RGB
    public let info: RGB
    public let ink: RGB
    public let onInk: RGB

    public static let dark = Look(
        palette: .dark, canvas: RGB(0x0A0E11), surface: RGB(0x12171B), surfacePop: RGB(0x161C21), chrome: RGB(0x251717), text: Tint(0xF6F1E9),
        textSecondary: Tint(0xF6F1E9, alpha: 0.78), textTertiary: Tint(0xF6F1E9, alpha: 0.55), separator: Tint(0xF6F1E9, alpha: 0.14),
        separatorStrong: Tint(0xF6F1E9, alpha: 0.24), gridLine: Tint(0xF6F1E9, alpha: 0.07), accent: RGB(0xFC4926), accentHover: RGB(0xFF7350),
        accentPressed: RGB(0xE23E1D), onAccent: RGB(0x0A0E11), fillSelected: Tint(0xF6F1E9, alpha: 0.10),
        fillSelectedQuiet: Tint(0xF6F1E9, alpha: 0.06), fillHover: Tint(0xF6F1E9, alpha: 0.06), fillPressed: Tint(0xF6F1E9, alpha: 0.10),
        groupHead: Tint(0xF6F1E9, alpha: 0.06), well: Tint(0xF6F1E9, alpha: 0.08), focusHalo: Tint(0xFC4926, alpha: 0.55), textSelection: Tint(0xFC4926, alpha: 0.30), error: RGB(0xF2788D), errorHover: RGB(0xD06779),
        errorPressed: RGB(0xB35968), onError: RGB(0x0A0E11),
        warning: RGB(0xD08FA6), success: RGB(0x86B89A), info: RGB(0x7FBFCA), ink: RGB(0xF6F1E9), onInk: RGB(0x0A0E11)
    ).withTerminalInk()

    public static let light = Look(
        palette: .light, canvas: RGB(0xF8FAFC), surface: RGB(0xFDFFFF), surfacePop: RGB(0xFFFFFF), chrome: RGB(0xF7EAE2), text: Tint(0x0E1217),
        textSecondary: Tint(0x383E43), textTertiary: Tint(0x666B70), separator: Tint(0x0E1217, alpha: 0.12), separatorStrong: Tint(0x0E1217, alpha: 0.22),
        gridLine: Tint(0x0E1217, alpha: 0.06), accent: RGB(0xFC4926), accentHover: RGB(0xD73626), accentPressed: RGB(0xB92C1E),
        onAccent: RGB(0xFFFFFF), fillSelected: Tint(0x0E1217, alpha: 0.08), fillSelectedQuiet: Tint(0x0E1217, alpha: 0.05),
        fillHover: Tint(0x0E1217, alpha: 0.05), fillPressed: Tint(0x0E1217, alpha: 0.09), groupHead: Tint(0x0E1217, alpha: 0.05),
        well: Tint(0x0E1217, alpha: 0.06), focusHalo: Tint(0xFC4926, alpha: 0.42),
        textSelection: Tint(0xFC4926, alpha: 0.20), error: RGB(0xB91C3C), errorHover: RGB(0x9F1834), errorPressed: RGB(0x89152C), onError: RGB(0xFFFFFF), warning: RGB(0x9D4260), success: RGB(0x256E4A),
        info: RGB(0x17646F), ink: RGB(0x0E1217), onInk: RGB(0xF8FAFC)
    ).withTerminalInk()

    /// This look with its terminal's selection, `textSelection` over the terminal's background under
    /// its own foreground, and its idle cursor, `textTertiary` over that background: both opaque,
    /// fixed colours, so a change of those tokens reaches the terminal too.
    private func withTerminalInk() -> Look {
        var look = self
        let ground = palette.background
        look.palette.selection = SelectionInk(foreground: palette.foreground, background: ground.blended(toward: textSelection.rgb, by: textSelection.alpha))
        look.palette.idleCursor = ground.blended(toward: textTertiary.rgb, by: textTertiary.alpha)
        return look
    }

    /// This look under Increase Contrast: edges and hairlines about twice as strong, tertiary text
    /// as strong as secondary, a chosen thing at full strength whether or not its list has the keys,
    /// and an opaque focus halo.
    public func contrasted() -> Look {
        var look = self
        let dark = palette.isDark
        look.separator = Tint(separator.rgb, alpha: dark ? 0.32 : 0.30)
        look.separatorStrong = Tint(separatorStrong.rgb, alpha: dark ? 0.50 : 0.45)
        look.gridLine = Tint(gridLine.rgb, alpha: dark ? 0.16 : 0.15)
        look.textTertiary = textSecondary
        look.fillSelectedQuiet = fillSelected
        look.focusHalo = Tint(accent)
        return look
    }

    /// Content corners, drawn at 6 pt; windows, sheets, menus and system controls keep the
    /// platform's own.
    public static let contentRadius = 6.0
    /// Pills, keycaps and options: 4 pt keeps a fill concentric inside a 6 pt frame 2 pt in.
    public static let innerRadius = 4.0
    /// Small buttons.
    public static let smallRadius = 5.0
    /// The room a control keeps around its box for the focus halo, so no bounds of its own clip it.
    public static let haloRoom = 4.0
    /// The edge every bordered control has, transparent or not: its padding is measured inside it,
    /// as the design's `border-box` measures it.
    public static let hairline = 1.0

    /// The 4-point spacing grid (2.3), the steps the shell uses.
    public enum Space {
        public static let s = 8.0
        public static let m = 12.0
        public static let l = 16.0
        public static let xxl = 24.0
        public static let x3l = 32.0
    }

    /// The type steps, in points.
    public enum Step: Double {
        case caption = 10, micro = 11, footnote = 12, callout = 13, body = 14, headline = 15, title3 = 18, title2 = 22, title1 = 26

        public var points: Double {
            rawValue
        }
    }

    /// Letter spacing, in ems: eyebrows and group heads, pills, the sidebar's smaller pills.
    public enum Tracking {
        public static let eyebrow = 0.14
        public static let pill = 0.08
        public static let smallPill = 0.06
    }

    /// Durations, in seconds: hover, press and caret turns; a spinner's turn; a pulse; how long a
    /// skeleton waits before it shows, so a fast answer never flashes one.
    public enum Timing {
        public static let hover = 0.12
        public static let turn = 0.7
        public static let pulse = 1.2
        public static let skeletonDelay = 0.15
    }
}
