import SailVT

extension KeyInput {
    /// The key a person presses to type `character`: Enter, Tab, Backspace and Escape by name, a
    /// control character as Ctrl and its letter, anything else as the key that carries it.
    static func typing(_ character: Character) -> KeyInput {
        switch character {
        case "\r", "\n": return KeyInput(key: .enter)
        case "\t": return KeyInput(key: .tab)
        case "\u{7f}": return KeyInput(key: .backspace)
        case "\u{1b}": return KeyInput(key: .escape)
        default: break
        }
        if let ascii = character.asciiValue, (1 ... 26).contains(ascii) {
            let letter = Character(Unicode.Scalar(ascii + 0x60))
            return KeyInput(key: key(for: letter), modifiers: .control, text: String(letter), unshifted: letter.unicodeScalars.first)
        }
        let lower = Character(character.lowercased())
        let shifted = character.isUppercase
        return KeyInput(
            key: key(for: lower), modifiers: shifted ? .shift : [], consumedModifiers: shifted ? .shift : [],
            text: String(character), unshifted: lower.unicodeScalars.first
        )
    }

    private static func key(for character: Character) -> Key {
        guard let ascii = character.asciiValue else { return .unidentified }
        switch ascii {
        case 0x61 ... 0x7A: return Key(rawValue: Key.a.rawValue + Int32(ascii - 0x61)) ?? .unidentified
        case 0x30: return .digit0
        case 0x31 ... 0x39: return Key(rawValue: Key.digit1.rawValue + Int32(ascii - 0x31)) ?? .unidentified
        case 0x20: return .space
        default: return .unidentified
        }
    }
}
