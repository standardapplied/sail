import AppKit
import SailSurface
import SailVT

/// Key events as a US keyboard makes them: the virtual key code, the characters with and without
/// the modifiers, and the flags.
enum Keys {
    struct Key {
        let code: UInt16
        let characters: String
        let ignoring: String
        let flags: NSEvent.ModifierFlags
    }

    private static let shifted: [Character: Character] = [
        "!": "1", "@": "2", "#": "3", "$": "4", "%": "5", "^": "6", "&": "7", "*": "8", "(": "9", ")": "0", "_": "-", "+": "=",
        "{": "[", "}": "]", "|": "\\", ":": ";", "\"": "'", "<": ",", ">": ".", "?": "/", "~": "`",
    ]

    private static let punctuation: [Character: SailVT.Key] = [
        "-": .minus, "=": .equal, "[": .bracketLeft, "]": .bracketRight, "\\": .backslash, ";": .semicolon, "'": .quote,
        ",": .comma, ".": .period, "/": .slash, "`": .backquote, " ": .space, "\r": .enter, "\t": .tab,
    ]

    private static let namedKeys: [String: (key: SailVT.Key, text: String)] = [
        "enter": (.enter, "\r"), "escape": (.escape, "\u{1b}"), "tab": (.tab, "\t"), "backspace": (.backspace, "\u{7f}"),
        "arrowUp": (.arrowUp, "\u{F700}"), "arrowDown": (.arrowDown, "\u{F701}"), "arrowLeft": (.arrowLeft, "\u{F702}"),
        "arrowRight": (.arrowRight, "\u{F703}"),
    ]

    /// The key that types `character`, Shift held for a capital or a shifted symbol; a character
    /// no US key types is sent on no key, as the character viewer would.
    static func typing(_ character: Character) -> Key? {
        let text = String(character)
        if let base = shifted[character], let code = Self.code(for: base) {
            return Key(code: code, characters: text, ignoring: String(base), flags: .shift)
        }
        if character.isUppercase, let code = Self.code(for: Character(text.lowercased())) {
            return Key(code: code, characters: text, ignoring: text.lowercased(), flags: .shift)
        }
        return Key(code: Self.code(for: character) ?? 0xFFFF, characters: text, ignoring: text, flags: [])
    }

    /// `name` (a letter, a digit or a named key) with `modifiers` held: a control chord types its
    /// control character, as the keyboard does.
    static func named(_ name: String, modifiers: [String]) -> Key? {
        var flags: NSEvent.ModifierFlags = []
        for modifier in modifiers {
            switch modifier {
            case "control": flags.insert(.control)
            case "option": flags.insert(.option)
            case "shift": flags.insert(.shift)
            case "command": flags.insert(.command)
            default: return nil
            }
        }
        if let entry = namedKeys[name], let code = Typing.code(of: entry.key) {
            return Key(code: code, characters: entry.text, ignoring: entry.text, flags: flags)
        }
        guard name.count == 1, let character = name.first, let code = Self.code(for: character) else { return nil }
        var characters = name
        if flags.contains(.control), let scalar = name.uppercased().unicodeScalars.first, (0x40 ... 0x5F).contains(scalar.value),
           let control = Unicode.Scalar(scalar.value - 0x40)
        {
            characters = String(Character(control))
        }
        return Key(code: code, characters: flags.contains(.command) ? name : characters, ignoring: name, flags: flags)
    }

    private static func code(for character: Character) -> UInt16? {
        if let key = punctuation[character] { return Typing.code(of: key) }
        let name = String(character).lowercased()
        if let scalar = name.unicodeScalars.first, name.unicodeScalars.count == 1 {
            switch scalar.value {
            case 0x61 ... 0x7A: return SailVT.Key.allCases.first { "\($0)" == name }.flatMap(Typing.code(of:))
            case 0x30 ... 0x39: return SailVT.Key.allCases.first { "\($0)" == "digit" + name }.flatMap(Typing.code(of:))
            default: return nil
            }
        }
        return nil
    }
}
