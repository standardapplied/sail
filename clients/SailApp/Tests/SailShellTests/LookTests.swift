import CryptoKit
import Foundation
import CoreText
import SailLook
import SailVT
import Testing

/// S8 and scenario 19: every text-on-surface pair of the look is 4.5:1 or better, dark and light,
/// ink on the accent included; and no source of the app names the system's accent.
@Suite struct LookTests {
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

    @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func everyTextOnEverySurfaceIsFourAndAHalfToOne(_ name: String, _ look: Look) {
        let surfaces = [
            ("canvas", look.canvas), ("surface", look.surface), ("well", look.well), ("hover over canvas", look.fillHover.over(look.canvas)),
            ("hover over surface", look.fillHover.over(look.surface)),
        ]
        let texts: [(String, Tint)] = [
            ("text", look.text), ("textSecondary", look.textSecondary), ("textTertiary", look.textTertiary),
            ("accentText", Tint(Self.hex(look.accentText))), ("danger", Tint(Self.hex(look.danger))),
        ]
        for (surfaceName, surface) in surfaces {
            for (textName, text) in texts {
                let ratio = Self.contrast(text.over(surface), surface)
                #expect(ratio >= 4.5, "\(name) \(textName) on \(surfaceName) is \(String(format: "%.2f", ratio)):1")
            }
        }
        #expect(Self.contrast(look.onAccent, look.accent) >= 4.5, "\(name) onAccent on accent")
    }

    @Test func theAccentIsTerracottaAndItsInkDarkInBothAppearances() {
        for look in [Look.dark, Look.light] {
            #expect(look.accent == RGB(0xFC4926))
            #expect(look.onAccent == RGB(0x0A0E11))
        }
    }

    @Test func noSourceOfTheAppNamesTheSystemAccent() throws {
        let sources = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources")
        var read = 0
        for target in ["SailLook", "SailShell", "SailAccount", "Sail", "SailTerminalView", "SailRenderer"] {
            let directory = sources.appending(path: target)
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path) where name.hasSuffix(".swift") {
                let text = try String(contentsOf: directory.appending(path: name), encoding: .utf8)
                read += 1
                for word in ["controlAccentColor", "accentColor", "AppleAccentColor", "selectedContentBackgroundColor", "keyboardFocusIndicatorColor"] {
                    #expect(!text.contains(word), "\(target)/\(name) names \(word)")
                }
            }
        }
        #expect(read >= 12)
    }

    @Test func theTitleFontIsThePinnedNewsreaderMedium() throws {
        let fonts = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Fonts")
        let pins = [
            "Newsreader16pt-Medium.ttf": "ee2f66ee4c6c313acf211c952211f8c6c0c0d9fa6a17cd0ec0ceba13418b1aed",
            "Newsreader-OFL.txt": "fdfad38143ec470553cae82a1e45320bdd1b9ec70415d37bd0171051d8a4ded8",
        ]
        for (file, pin) in pins {
            let digest = SHA256.hash(data: try Data(contentsOf: fonts.appending(path: file)))
            #expect(digest.map { String(format: "%02x", $0) }.joined() == pin, "\(file) is not the pinned Newsreader file")
        }
        let title = try TitleFont(directory: fonts).font(.title2)
        #expect(CTFontGetSize(title) == 22)
    }

    static func hex(_ rgb: RGB) -> UInt32 {
        UInt32(rgb.red) << 16 | UInt32(rgb.green) << 8 | UInt32(rgb.blue)
    }
}
