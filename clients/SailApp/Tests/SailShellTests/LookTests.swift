import CryptoKit
import Foundation
import AppKit
import SailControls
import CoreText
import SailControls
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
        let surfaces = [("canvas", look.canvas), ("surface", look.surface)]
        let texts: [(String, Tint)] = [("text", look.text), ("textSecondary", look.textSecondary)]
        for (surfaceName, surface) in surfaces {
            for (textName, text) in texts {
                let ratio = Self.contrast(text.over(surface), surface)
                #expect(ratio >= 4.5, "\(name) \(textName) on \(surfaceName) is \(String(format: "%.2f", ratio)):1")
            }
        }
    }

    /// White on terracotta is 3.43:1, so the one pair under 4.5:1 is held to the large-text bound by
    /// name, and the only text drawn on an accent fill, the primary button's label, is bold (HIG
    /// Accessibility: bold text at any size, 3:1).
    @MainActor @Test func textOnTheAccentIsLargeTextAtThreeToOne() throws {
        #expect(Look.dark.accent == RGB(0xFC4926) && Look.light.accent == RGB(0xFC4926))
        #expect(Look.dark.onAccent == RGB(0x0A0E11) && Look.light.onAccent == RGB(0xFFFFFF))
        let dark = Self.contrast(Look.dark.onAccent, Look.dark.accent), light = Self.contrast(Look.light.onAccent, Look.light.accent)
        #expect(dark >= 4.5, "dark onAccent on accent is \(dark)")
        #expect(light >= 3 && light < 3.5, "light onAccent on accent is \(light)")
        for look in [Look.dark, Look.light] {
            let paint = ButtonPaint(.primary, ButtonState(), look: look)
            #expect(paint.bold, "the accent label is not large text")
            #expect(paint.fill == Tint(look.accent) && paint.label == Tint(look.onAccent))
        }
    }

    /// The Mac's Increase Contrast gives the contrasted look, in either appearance; off, the look itself.
    @MainActor @Test func increaseContrastGivesTheContrastedLook() throws {
        let light = try #require(NSAppearance(named: .aqua)), dark = try #require(NSAppearance(named: .darkAqua))
        #expect(ShellApp.look(of: light, contrast: true) == Look.light.contrasted() && ShellApp.look(of: dark, contrast: true) == Look.dark.contrasted())
        #expect(ShellApp.look(of: light, contrast: false) == .light && ShellApp.look(of: dark, contrast: false) == .dark)
    }

    @Test func noSourceOfTheAppNamesTheSystemAccent() throws {
        let sources = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources")
        var read = 0
        for target in ["SailLook", "SailShell", "SailAccount", "Sail", "SailTerminalView", "SailRenderer", "SailControls"] {
            let directory = sources.appending(path: target)
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path) where name.hasSuffix(".swift") {
                let text = try String(contentsOf: directory.appending(path: name), encoding: .utf8)
                read += 1
                for word in ["controlAccentColor", "accentColor", "AppleAccentColor", "selectedContentBackgroundColor", "keyboardFocusIndicatorColor"] {
                    #expect(!text.contains(word), "\(target)/\(name) names \(word)")
                }
            }
        }
        #expect(read >= 19)
    }

    @Test func theTitleFontIsThePinnedNewsreaderMedium() throws {
        let fonts = Self.fonts
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

    /// AppKit draws a push button answering Return as the window's default, in the system accent:
    /// no button of the window answers Return unless it draws itself in the Sail accent.
    @MainActor @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func noButtonIsTheSystemDrawnDefault(_ name: String, _ look: Look) throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-default-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let passkey = SoftwarePasskey(file: folder.appending(path: "passkey.json"))
        let shell = try ShellApp(fonts: Self.fonts, helper: nil, look: look, passkeys: { _ in passkey }, defaults: ReattachTests.defaults())
        func buttons(_ view: NSView) -> [NSButton] {
            ((view as? NSButton).map { [$0] } ?? []) + view.subviews.flatMap(buttons)
        }
        var walked = 0
        for pane in [shell.account, shell.thisMac, shell.thisMac.strip] as [NSView] {
            for button in buttons(pane) {
                walked += 1
                #expect(button.keyEquivalent != "\r" || button is SailButton, "\(name): \(button.title) is the system-drawn default")
            }
        }
        #expect(walked >= 4)
    }

    /// A: the title bar is transparent over a full-size content view, the platform's buttons in
    /// place; under it the rail is clear over the chrome, one flat fill, and the row beside it in the
    /// account's surface, or for "This Mac" in the tabs' `chrome` (the layers' own colours; the drive
    /// checks the pixels of the running window).
    @MainActor @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theTitleBarShowsTheRailAndThePanesSurface(_ name: String, _ look: Look) throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-look-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let passkey = SoftwarePasskey(file: folder.appending(path: "passkey.json"))
        let shell = try ShellApp(fonts: Self.fonts, helper: nil, look: look, passkeys: { _ in passkey }, defaults: ReattachTests.defaults())
        let window = shell.window
        #expect(window.styleMask.contains(.fullSizeContentView))
        #expect(window.titlebarAppearsTransparent)
        #expect(window.titleVisibility == .hidden)
        for button in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
            #expect(window.standardWindowButton(button).map { !$0.isHidden } == true, "\(name): the window lacks its \(button) button")
        }
        let content = try #require(window.contentView)
        #expect(window.contentLayoutRect.height < content.bounds.height, "\(name): the content view does not reach under the title bar")
        #expect(shell.rail.layer?.backgroundColor == NSColor.clear.cgColor, "\(name): the rail hides the chrome")
        shell.select(.thisMac)
        let chosen = try #require(shell.rail.bar(.thisMac))
        #expect(!chosen.isHidden && chosen.layer?.backgroundColor == NSColor(look.accent).cgColor, "\(name): the rail's chosen item has no accent bar, as a tab's")
        #expect(shell.rail.drawnMarks == [.accentLine], "\(name): the rail's chosen item draws \(shell.rail.drawnMarks), not one accent bar on ink")
        shell.window.contentView?.layoutSubtreeIfNeeded()
        let wash = try #require(shell.rail.chosenWash)
        #expect(near(wash.size.width, Rail.item) && near(wash.size.height, Rail.item) && wash.colour == NSColor(Tint(look.chromeWash)).cgColor, "\(name): the rail's chosen wash is \(wash)")
        #expect(near(wash.button.height - (wash.insets.top + wash.insets.bottom), Rail.item), "\(name): the button's frame \(wash.button) is not its 42 pt alignment rect and insets \(wash.insets)")
        for item in [Rail.Item.account, .thisMac] {
            shell.select(item)
            #expect(content.layer?.backgroundColor == NSColor(look.chrome).cgColor, "\(name) \(item): the band under the title bar is not the chrome")
        }
    }

    static let fonts = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "Fonts")

    /// Focus and selection are the look's accent, never the system's: a field being edited is
    /// ringed in the accent (the system's ring is off), text selected in it is on the selection fill,
    /// and its caret is the accent; Return in the ticket field enrolls and in the address field signs
    /// in, through the field's own action, with no button answering Return.
    @MainActor @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func focusSelectionAndReturnAreTheLooks(_ name: String, _ look: Look) throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-focus-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let passkey = SoftwarePasskey(file: folder.appending(path: "passkey.json"))
        let shell = try ShellApp(fonts: Self.fonts, helper: nil, look: look, passkeys: { _ in passkey }, defaults: ReattachTests.defaults())
        shell.select(.account)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let pane = shell.account
        let cases: [(NSTextField, Selector)] = [
            (pane.ticket, #selector(AccountPane.enrollWithTicket(_:))), (pane.address, #selector(AccountPane.signInWithPasskey(_:))),
        ]
        for (field, action) in cases {
            #expect(shell.window.makeFirstResponder(field), "\(name): the field took no focus")
            let editor = try #require(field.currentEditor() as? NSTextView)
            #expect(field.focusRingType == .none)
            let frame = try #require(field.superview as? FieldFrame, "\(name): the field is not in the shared field style")
            #expect(frame.ring.width == 2, "\(name): the field is not ringed")
            #expect(frame.ring.colour == NSColor(look.accent), "\(name): the ring is not the accent")
            #expect(editor.selectedTextAttributes[.backgroundColor] as? NSColor == NSColor(look.textSelection))
            #expect(editor.insertionPointColor == NSColor(look.accent))
            #expect(field.action == action, "\(name): Return in the field does not send \(action)")
            #expect(field.target === pane)
            #expect(pane.enroll.keyEquivalent.isEmpty, "\(name): Enroll answers Return")
            #expect(pane.signIn.keyEquivalent.isEmpty, "\(name): Sign In answers Return")
        }
        #expect(pane.enroll.controlSize == .large, "\(name): Enroll is not the size of the fields' buttons")
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
