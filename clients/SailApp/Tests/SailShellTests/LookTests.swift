import CryptoKit
import Foundation
import AppKit
import CoreText
import SailLook
@testable import SailShell
import SailTestPasskey
import SailTerminalView
import SailVT
import Testing

/// S8 and scenario 19: every text-on-surface pair of the look is 4.5:1 or better, dark and light;
/// text on the accent fill, white in the light appearance, is large text and 3:1 or better; no
/// source of the app names the system's accent; and the window's chrome is the look's.
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
        let surfaces = [("canvas", look.canvas), ("surface", look.surface), ("selection over canvas", look.fillSelected.over(look.canvas))]
        let texts: [(String, Tint)] = [("text", look.text), ("textSecondary", look.textSecondary), ("accentText", Tint(Self.hex(look.accentText)))]
        for (surfaceName, surface) in surfaces {
            for (textName, text) in texts where !(textName == "accentText" && surfaceName.hasPrefix("selection")) {
                let ratio = Self.contrast(text.over(surface), surface)
                #expect(ratio >= 4.5, "\(name) \(textName) on \(surfaceName) is \(String(format: "%.2f", ratio)):1")
            }
        }
    }

    /// White on terracotta is 3.43:1, so the one pair under 4.5:1 is held to the large-text bound by
    /// name, and the only text drawn on an accent fill, `AccentButton`'s label, is bold (HIG
    /// Accessibility: bold text at any size, 3:1).
    @MainActor @Test func textOnTheAccentIsLargeTextAtThreeToOne() throws {
        #expect(Look.dark.accent == RGB(0xFC4926) && Look.light.accent == RGB(0xFC4926))
        #expect(Look.dark.onAccent == RGB(0x0A0E11) && Look.light.onAccent == RGB(0xFFFFFF))
        let dark = Self.contrast(Look.dark.onAccent, Look.dark.accent), light = Self.contrast(Look.light.onAccent, Look.light.accent)
        #expect(dark >= 4.5, "dark onAccent on accent is \(dark)")
        #expect(light >= 3 && light < 3.5, "light onAccent on accent is \(light)")
        for look in [Look.dark, Look.light] {
            let button = AccentButton("Sign In with Passkey", look: look, target: nil, action: #selector(NSApplication.terminate(_:)))
            let font = try #require(button.attributedTitle.attribute(.font, at: 0, effectiveRange: nil) as? NSFont)
            let ink = button.attributedTitle.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor
            #expect(NSFontManager.shared.traits(of: font).contains(.boldFontMask) || font.pointSize >= 18, "the accent label is not large text")
            #expect(ink == NSColor(look.onAccent))
        }
    }

    /// A: the title bar is transparent over a full-size content view, the platform's buttons in
    /// place; under it the rail is drawn in `chrome` and the strip beside it in the pane's surface
    /// (the layers' own colours; the drive checks the pixels of the running window).
    @MainActor @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theTitleBarShowsTheRailAndThePanesSurface(_ name: String, _ look: Look) throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-look-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let passkey = SoftwarePasskey(file: folder.appending(path: "passkey.json"))
        let shell = try ShellApp(fonts: Self.fonts, helper: nil, look: look) { _ in passkey }
        let window = shell.window
        #expect(window.styleMask.contains(.fullSizeContentView))
        #expect(window.titlebarAppearsTransparent)
        #expect(window.titleVisibility == .hidden)
        for button in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
            #expect(window.standardWindowButton(button).map { !$0.isHidden } == true, "\(name): the window lacks its \(button) button")
        }
        let content = try #require(window.contentView)
        #expect(window.contentLayoutRect.height < content.bounds.height, "\(name): the content view does not reach under the title bar")
        #expect(shell.rail.layer?.backgroundColor == NSColor(look.chrome).cgColor, "\(name): the rail is not chrome")
        for (item, surface) in [(Rail.Item.account, look.canvas), (.thisMac, look.palette.background)] {
            shell.select(item)
            #expect(content.layer?.backgroundColor == NSColor(surface).cgColor, "\(name) \(item): the strip under the title bar is not the pane's surface")
        }
    }

    static let fonts = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "Fonts")

    /// Focus and selection are the look's accent, never the system's: a field being edited is
    /// ringed in the accent (the system's ring is off), text selected in it is on the selection fill,
    /// and its caret is the accent; Return goes to Enroll in the ticket field and to Sign In in the
    /// address field.
    @MainActor @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func focusSelectionAndReturnAreTheLooks(_ name: String, _ look: Look) throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-focus-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let passkey = SoftwarePasskey(file: folder.appending(path: "passkey.json"))
        let shell = try ShellApp(fonts: Self.fonts, helper: nil, look: look) { _ in passkey }
        shell.select(.account)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let pane = shell.account
        let cases: [(NSTextField, NSButton, NSButton)] = [(pane.ticket, pane.enroll, pane.signIn), (pane.address, pane.signIn, pane.enroll)]
        for (field, button, other) in cases {
            #expect(shell.window.makeFirstResponder(field), "\(name): the field took no focus")
            let editor = try #require(field.currentEditor() as? NSTextView)
            #expect(field.focusRingType == .none)
            let ring = try #require(field.layer)
            #expect(ring.borderWidth == 2, "\(name): the field is not ringed")
            #expect(ring.borderColor == NSColor(look.accent).cgColor, "\(name): the ring is not the accent")
            #expect(editor.selectedTextAttributes[.backgroundColor] as? NSColor == NSColor(look.fillSelected))
            #expect(editor.insertionPointColor == NSColor(look.accent))
            #expect(button.keyEquivalent == "\r", "\(name): Return does not go to \(button.title)")
            #expect(other.keyEquivalent.isEmpty, "\(name): Return also goes to \(other.title)")
        }
    }

    static func hex(_ rgb: RGB) -> UInt32 {
        UInt32(rgb.red) << 16 | UInt32(rgb.green) << 8 | UInt32(rgb.blue)
    }
}

extension Tint {
    /// This tint drawn over the opaque `base`.
    func over(_ base: RGB) -> RGB {
        func mix(_ top: UInt8, _ bottom: UInt8) -> UInt8 {
            UInt8((Double(top) * alpha + Double(bottom) * (1 - alpha)).rounded())
        }
        return RGB(red: mix(rgb.red, base.red), green: mix(rgb.green, base.green), blue: mix(rgb.blue, base.blue))
    }
}
