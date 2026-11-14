import Foundation
import GhosttyVt
import SailVT
import Testing

@Suite struct InputTests {
    @Test func everyKeyIsTheLibrarysOwnKey() throws {
        let manifest = try JSONSerialization.jsonObject(with: Data(String(cString: ghostty_type_json()).utf8)) as? [String: Any]
        let types = manifest?["types"] as? [String: Any]
        let values = try #require((types?["GhosttyKey"] as? [String: Any])?["values"] as? [String: Int])
        let named = Dictionary(uniqueKeysWithValues: values.filter { $0.key != "MAX_VALUE" }.map { name, value in
            (name.lowercased().split(separator: "_").enumerated().map { $0 == 0 ? String($1) : $1.capitalized }.joined(), value)
        })
        #expect(Key.allCases.count == named.count)
        for key in Key.allCases {
            #expect(named["\(key)"] == Int(key.rawValue), "\(key)")
        }
    }

    @Test func modifiersAreTheLibrarysModifiers() {
        let pairs: [(Modifiers, Int32)] = [
            (.shift, GHOSTTY_MODS_SHIFT), (.control, GHOSTTY_MODS_CTRL), (.alt, GHOSTTY_MODS_ALT), (.super, GHOSTTY_MODS_SUPER),
            (.capsLock, GHOSTTY_MODS_CAPS_LOCK), (.numLock, GHOSTTY_MODS_NUM_LOCK), (.rightShift, GHOSTTY_MODS_SHIFT_SIDE),
            (.rightControl, GHOSTTY_MODS_CTRL_SIDE), (.rightAlt, GHOSTTY_MODS_ALT_SIDE), (.rightSuper, GHOSTTY_MODS_SUPER_SIDE),
        ]
        for (modifier, ghostty) in pairs {
            #expect(Int32(modifier.rawValue) == ghostty)
        }
    }

    /// Scenario 13: with Kitty flags pushed (disambiguate and event types), Ctrl-C, Alt-x and an
    /// arrow's release are Kitty's encodings; after the pop, the legacy ones.
    @Test func keysAreEncodedInTheProtocolTheProgramPushed() throws {
        let screen = try screen()
        screen.setOptionAsAlt(.both)
        let control = KeyInput(key: .c, modifiers: .control, text: "c", unshifted: "c")
        let alt = KeyInput(key: .x, modifiers: .alt, text: "x", unshifted: "x")
        let arrowUp = KeyInput(key: .arrowUp, action: .release)
        #expect(screen.encode(control) == [0x03])
        _ = screen.feed(Array("\u{1b}[>3u".utf8))
        #expect(screen.encode(control) == Array("\u{1b}[99;5u".utf8))
        #expect(screen.encode(alt) == Array("\u{1b}[120;3u".utf8))
        #expect(screen.encode(arrowUp) == Array("\u{1b}[1;1:3A".utf8))
        _ = screen.feed(Array("\u{1b}[<u".utf8))
        #expect(screen.encode(control) == [0x03])
        #expect(screen.encode(alt) == Array("\u{1b}x".utf8))
        #expect(screen.encode(arrowUp).isEmpty)
    }

    @Test func cursorKeysFollowTheApplicationMode() throws {
        let screen = try screen()
        let up = KeyInput(key: .arrowUp)
        #expect(screen.encode(up) == Array("\u{1b}[A".utf8))
        _ = screen.feed(Array("\u{1b}[?1h".utf8))
        #expect(screen.encode(up) == Array("\u{1b}OA".utf8))
    }

    /// libghostty-vt reads option-as-alt on macOS alone; elsewhere alt always prefixes ESC.
    @Test func optionTypesAsAltOnlyWhenTheAppSaysSo() throws {
        let screen = try screen()
        let option = KeyInput(key: .a, modifiers: .alt, text: "å", unshifted: "a")
        #if os(macOS)
        #expect(screen.encode(option) == Array("å".utf8))
        screen.setOptionAsAlt(.both)
        #expect(screen.encode(option) == Array("\u{1b}a".utf8))
        #else
        #expect(screen.encode(option) == Array("\u{1b}å".utf8))
        screen.setOptionAsAlt(.both)
        #expect(screen.encode(option) == Array("\u{1b}å".utf8))
        #endif
    }

    @Test func textIsSentAsItsUTF8() throws {
        #expect(try screen().encode(KeyInput(key: .e, text: "é", unshifted: "e")) == Array("é".utf8))
    }

    /// Scenario 14.
    @Test func aMultiLinePasteIntoAProgramThatDidNotAskNeedsConfirmation() throws {
        let screen = try screen()
        #expect(screen.paste("one\ntwo\nthree") == .needsConfirmation)
        #expect(screen.paste("one\ntwo\nthree", confirmed: true) == .send(Array("one\rtwo\rthree".utf8)))
        #expect(screen.paste("one line") == .send(Array("one line".utf8)))
        #expect(screen.paste("") == .nothingToPaste)
    }

    /// Scenario 15 (the screen's half; the 64 KiB split is the session's).
    @Test func aPasteIntoAProgramThatAskedIsBracketed() throws {
        let screen = try screen()
        _ = screen.feed(Array("\u{1b}[?2004h".utf8))
        #expect(screen.paste("one\ntwo\nthree") == .send(Array("\u{1b}[200~one\ntwo\nthree\u{1b}[201~".utf8)))
        #expect(screen.paste("x\u{1b}[201~rm -rf") == .needsConfirmation)
    }

    @Test func aPasteDuringAReplayIsStillSent() throws {
        let screen = try screen()
        screen.beginReplay()
        #expect(screen.paste("typed") == .send(Array("typed".utf8)))
        #expect(screen.feed(Array("\u{1b}[6n".utf8)).reply.isEmpty)
    }

    private func screen() throws -> Screen {
        try Screen(size: GridSize(columns: 80, rows: 24), scrollbackBytes: 1 << 16, palette: .dark)
    }
}
