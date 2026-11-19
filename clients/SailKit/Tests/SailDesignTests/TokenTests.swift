import Foundation
import SailDesign
import SailVT
import Testing

/// The look's tokens are the design's table, value for value, in both looks and under Increase
/// Contrast; every text pair a control draws is AA (4.5:1), and bold text on a filled button 3:1.
@Suite struct TokenTests {
    static func hex(_ rgb: RGB) -> String {
        String(format: "%02X%02X%02X", rgb.red, rgb.green, rgb.blue)
    }

    static func said(_ tint: Tint) -> String {
        tint.alpha == 1 ? hex(tint.rgb) : hex(tint.rgb) + String(format: "@%.2f", tint.alpha)
    }

    static func table(_ look: Look) -> [String: String] {
        [
            "canvas": hex(look.canvas), "surface": hex(look.surface), "surfacePop": hex(look.surfacePop), "chrome": hex(look.chrome),
            "text": said(look.text), "textSecondary": said(look.textSecondary), "textTertiary": said(look.textTertiary),
            "separator": said(look.separator), "separatorStrong": said(look.separatorStrong), "gridLine": said(look.gridLine),
            "accent": hex(look.accent), "accentHover": hex(look.accentHover), "accentPressed": hex(look.accentPressed),
            "onAccent": hex(look.onAccent), "fillSelected": said(look.fillSelected), "textSelection": said(look.textSelection),
            "fillSelectedQuiet": said(look.fillSelectedQuiet), "fillHover": said(look.fillHover), "fillPressed": said(look.fillPressed),
            "focusHalo": said(look.focusHalo), "error": hex(look.error), "errorHover": hex(look.errorHover), "errorPressed": hex(look.errorPressed),
            "onError": hex(look.onError), "warning": hex(look.warning), "success": hex(look.success), "info": hex(look.info),
            "ink": hex(look.ink), "onInk": hex(look.onInk),
        ]
    }

    static let light: [String: String] = [
        "canvas": "F8FAFC", "surface": "FDFFFF", "surfacePop": "FFFFFF", "chrome": "F7EAE2", "text": "0E1217", "textSecondary": "383E43",
        "textTertiary": "666B70", "separator": "0E1217@0.12", "separatorStrong": "0E1217@0.22", "gridLine": "0E1217@0.06", "accent": "FC4926",
        "accentHover": "D73626", "accentPressed": "B92C1E", "onAccent": "FFFFFF", "fillSelected": "0E1217@0.08", "textSelection": "FC4926@0.20",
        "fillSelectedQuiet": "0E1217@0.05", "fillHover": "0E1217@0.05", "fillPressed": "0E1217@0.09", "focusHalo": "FC4926@0.42", "error": "B91C3C",
        "errorHover": "9F1834", "errorPressed": "89152C", "onError": "FFFFFF", "warning": "9D4260", "success": "256E4A", "info": "17646F",
        "ink": "0E1217", "onInk": "F8FAFC",
    ]

    static let dark: [String: String] = [
        "canvas": "0A0E11", "surface": "12171B", "surfacePop": "161C21", "chrome": "251717", "text": "F6F1E9", "textSecondary": "F6F1E9@0.78",
        "textTertiary": "F6F1E9@0.55", "separator": "F6F1E9@0.14", "separatorStrong": "F6F1E9@0.24", "gridLine": "F6F1E9@0.07", "accent": "FC4926",
        "accentHover": "FF7350", "accentPressed": "E23E1D", "onAccent": "0A0E11", "fillSelected": "F6F1E9@0.10", "textSelection": "FC4926@0.30",
        "fillSelectedQuiet": "F6F1E9@0.06", "fillHover": "F6F1E9@0.06", "fillPressed": "F6F1E9@0.10", "focusHalo": "FC4926@0.55", "error": "F2788D",
        "errorHover": "D06779", "errorPressed": "B35968", "onError": "0A0E11", "warning": "D08FA6", "success": "86B89A", "info": "7FBFCA",
        "ink": "F6F1E9", "onInk": "0A0E11",
    ]

    @Test(arguments: [("light", Look.light, TokenTests.light), ("dark", Look.dark, TokenTests.dark)])
    func everyTokenIsTheTables(_ name: String, _ look: Look, _ wanted: [String: String]) {
        let found = Self.table(look)
        #expect(Set(found.keys) == Set(wanted.keys))
        for (token, value) in wanted {
            #expect(found[token] == value, "\(name) \(token) is \(found[token] ?? "missing"), not \(value)")
        }
        var mast = look.palette
        mast.selection = nil
        mast.idleCursor = nil
        #expect(mast == (name == "dark" ? Palette.dark : Palette.light), "\(name): the terminal's colours are Mast's")
    }

    /// The calm rule in the tokens: choosing is ink; the accent's only tints are the focus halo and
    /// selected text; errors are crimson, at least 20 degrees of hue from the accent.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark), ("light contrasted", Look.light.contrasted()), ("dark contrasted", Look.dark.contrasted())])
    func choosingIsInkAndRedIsApartFromTheAccent(_ name: String, _ look: Look) {
        for (token, tint) in [("fillSelected", look.fillSelected), ("fillSelectedQuiet", look.fillSelectedQuiet), ("fillHover", look.fillHover), ("fillPressed", look.fillPressed)] {
            #expect(tint.rgb == look.ink, "\(name) \(token) is \(Self.said(tint)), not ink")
        }
        let accentTints = [look.textSelection, look.focusHalo].filter { $0.rgb == look.accent }
        #expect(accentTints.count == 2 && look.textSelection.alpha < 1)
        let apart = abs(Self.hue(look.error) - Self.hue(look.accent))
        #expect(min(apart, 360 - apart) >= 20, "\(name) error is \(String(format: "%.1f", min(apart, 360 - apart)))° from the accent")
    }

    /// The terminal's selection and idle cursor are fixed, opaque colours from the look: selected
    /// text keeps 4.5:1 on any program's colours, as it is drawn in its own.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func theTerminalsSelectionIsFixedAndReadable(_ name: String, _ look: Look) throws {
        let selection = try #require(look.palette.selection)
        #expect(selection.background == Self.over(look.textSelection, look.palette.background) && selection.foreground == look.palette.foreground)
        #expect(Self.contrast(selection.foreground, selection.background) >= 4.5, "\(name) selected text is \(Self.contrast(selection.foreground, selection.background)):1")
        #expect(look.palette.idleCursor == Self.over(look.textTertiary, look.palette.background))
    }

    static func hue(_ rgb: RGB) -> Double {
        let (r, g, b) = (Double(rgb.red) / 255, Double(rgb.green) / 255, Double(rgb.blue) / 255)
        let high = max(r, g, b), low = min(r, g, b), span = high - low
        guard span > 0 else { return 0 }
        let sector = high == r ? (g - b) / span : high == g ? (b - r) / span + 2 : (r - g) / span + 4
        return (sector * 60 + 360).truncatingRemainder(dividingBy: 360)
    }

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func increaseContrastStrengthensEdgesTertiaryTextTheQuietChoiceAndTheHalo(_ name: String, _ look: Look) {
        let strong = look.contrasted()
        let dark = name == "dark"
        let changed = [
            "separator": dark ? "F6F1E9@0.32" : "0E1217@0.30", "separatorStrong": dark ? "F6F1E9@0.50" : "0E1217@0.45",
            "gridLine": dark ? "F6F1E9@0.16" : "0E1217@0.15", "textTertiary": Self.said(look.textSecondary),
            "fillSelectedQuiet": Self.said(look.fillSelected), "focusHalo": "FC4926",
        ]
        let before = Self.table(look), after = Self.table(strong)
        for (token, value) in after {
            #expect(value == changed[token] ?? before[token], "\(name) contrasted \(token) is \(value)")
        }
    }

    static func luminance(_ rgb: RGB) -> Double {
        func channel(_ value: UInt8) -> Double {
            let linear = Double(value) / 255
            return linear <= 0.04045 ? linear / 12.92 : pow((linear + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(rgb.red) + 0.7152 * channel(rgb.green) + 0.0722 * channel(rgb.blue)
    }

    static func contrast(_ one: RGB, _ other: RGB) -> Double {
        let (light, dark) = (max(luminance(one), luminance(other)), min(luminance(one), luminance(other)))
        return (light + 0.05) / (dark + 0.05)
    }

    static func over(_ tint: Tint, _ base: RGB) -> RGB {
        tint.rgb.blended(toward: base, by: 1 - tint.alpha)
    }

    /// Text on every ground a control puts it on: the canvas, the surface, a floating panel, a
    /// chosen row and a hovered one (each over the surface), and a pill or a field on a chosen row.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark), ("light contrasted", Look.light.contrasted()), ("dark contrasted", Look.dark.contrasted())])
    func everyTextOnEveryGroundIsFourAndAHalfToOne(_ name: String, _ look: Look) {
        let grounds: [(String, RGB)] = [
            ("canvas", look.canvas), ("surface", look.surface), ("surfacePop", look.surfacePop),
            ("a chosen row", Self.over(look.fillSelected, look.surface)), ("a hovered row", Self.over(look.fillHover, look.surface)),
            ("a quiet chosen row", Self.over(look.fillSelectedQuiet, look.surface)),
        ]
        let texts: [(String, Tint)] = [
            ("text", look.text), ("textSecondary", look.textSecondary), ("textTertiary", look.textTertiary), ("error", Tint(look.error)),
        ]
        for (groundName, ground) in grounds {
            for (textName, text) in texts {
                let ratio = Self.contrast(Self.over(text, ground), ground)
                #expect(ratio >= 4.5, "\(name) \(textName) on \(groundName) is \(String(format: "%.2f", ratio)):1")
            }
        }
        let selected = Self.over(look.textSelection, look.surface)
        #expect(Self.contrast(Self.over(look.text, selected), selected) >= 4.5, "\(name) selected text, drawn in the text ink")
        let pressed = Self.over(look.fillPressed, look.canvas)
        #expect(Self.contrast(Self.over(look.text, pressed), pressed) >= 4.5, "\(name) a pressed button's label")
    }

    /// A filled button's label is bold, so 3:1 holds it at rest, hovered and pressed; the tooltip's
    /// ink chip is 4.5:1.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func labelsOnFillsAreThreeToOneAndTheTooltipFourAndAHalf(_ name: String, _ look: Look) {
        for fill in [look.accent, look.accentHover, look.accentPressed] {
            #expect(Self.contrast(look.onAccent, fill) >= 3, "\(name) onAccent on \(Self.hex(fill))")
        }
        for fill in [look.error, look.errorHover, look.errorPressed] {
            #expect(Self.contrast(look.onError, fill) >= 3, "\(name) onError on \(Self.hex(fill))")
        }
        #expect(Self.contrast(look.onInk, look.ink) >= 4.5)
    }
}
