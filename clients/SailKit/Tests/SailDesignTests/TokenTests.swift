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
            "accentText": hex(look.accentText), "onAccent": hex(look.onAccent), "fillSelected": said(look.fillSelected),
            "fillSelectedQuiet": said(look.fillSelectedQuiet), "fillHover": said(look.fillHover), "fillPressed": said(look.fillPressed),
            "focusHalo": said(look.focusHalo), "error": hex(look.error), "errorHover": hex(look.errorHover), "errorPressed": hex(look.errorPressed),
            "onError": hex(look.onError), "warning": hex(look.warning), "success": hex(look.success), "info": hex(look.info),
            "ink": hex(look.ink), "onInk": hex(look.onInk),
        ]
    }

    static let light: [String: String] = [
        "canvas": "F8FAFC", "surface": "FDFFFF", "surfacePop": "FFFFFF", "chrome": "F7EAE2", "text": "0E1217", "textSecondary": "383E43",
        "textTertiary": "666B70", "separator": "0E1217@0.12", "separatorStrong": "0E1217@0.22", "gridLine": "0E1217@0.06", "accent": "FC4926",
        "accentHover": "D73626", "accentPressed": "B92C1E", "accentText": "C8321F", "onAccent": "FFFFFF", "fillSelected": "FC4926@0.12",
        "fillSelectedQuiet": "FC4926@0.06", "fillHover": "0E1217@0.05", "fillPressed": "0E1217@0.09", "focusHalo": "FC4926@0.42", "error": "B42318",
        "errorHover": "9B1E15", "errorPressed": "851A12", "onError": "FFFFFF", "warning": "9D4260", "success": "256E4A", "info": "17646F",
        "ink": "0E1217", "onInk": "F8FAFC",
    ]

    static let dark: [String: String] = [
        "canvas": "0A0E11", "surface": "12171B", "surfacePop": "161C21", "chrome": "251717", "text": "F6F1E9", "textSecondary": "F6F1E9@0.78",
        "textTertiary": "F6F1E9@0.55", "separator": "F6F1E9@0.14", "separatorStrong": "F6F1E9@0.24", "gridLine": "F6F1E9@0.07", "accent": "FC4926",
        "accentHover": "FF7350", "accentPressed": "E23E1D", "accentText": "FF7350", "onAccent": "0A0E11", "fillSelected": "FC4926@0.16",
        "fillSelectedQuiet": "FC4926@0.08", "fillHover": "F6F1E9@0.06", "fillPressed": "F6F1E9@0.10", "focusHalo": "FC4926@0.55", "error": "E07B6F",
        "errorHover": "C16A5F", "errorPressed": "A65B52", "onError": "0A0E11", "warning": "D08FA6", "success": "86B89A", "info": "7FBFCA",
        "ink": "F6F1E9", "onInk": "0A0E11",
    ]

    @Test(arguments: [("light", Look.light, TokenTests.light), ("dark", Look.dark, TokenTests.dark)])
    func everyTokenIsTheTables(_ name: String, _ look: Look, _ wanted: [String: String]) {
        let found = Self.table(look)
        #expect(Set(found.keys) == Set(wanted.keys))
        for (token, value) in wanted {
            #expect(found[token] == value, "\(name) \(token) is \(found[token] ?? "missing"), not \(value)")
        }
        #expect(look.palette == (name == "dark" ? Palette.dark : Palette.light))
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
            ("text", look.text), ("textSecondary", look.textSecondary), ("textTertiary", look.textTertiary), ("accentText", Tint(look.accentText)),
            ("error", Tint(look.error)),
        ]
        for (groundName, ground) in grounds {
            for (textName, text) in texts {
                let ratio = Self.contrast(Self.over(text, ground), ground)
                #expect(ratio >= 4.5, "\(name) \(textName) on \(groundName) is \(String(format: "%.2f", ratio)):1")
            }
        }
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
