import SailVT

/// A Mac key event's facts, as `NSEvent` gives them, so the rules that turn it into a
/// ``KeyInput`` are checked without AppKit.
public struct MacKeyEvent: Equatable, Sendable {
    /// The virtual key code (`kVK_…`): the key's place on the keyboard.
    public var keyCode: UInt16
    /// `NSEvent.ModifierFlags.rawValue`, device bits included.
    public var modifierFlags: UInt
    /// `characters`.
    public var characters: String
    /// `characters(byApplyingModifiers:)` without control: what a control chord's key types alone.
    public var charactersWithoutControl: String
    /// `characters(byApplyingModifiers: [])`: the key's own character.
    public var unmodified: String
    public var action: KeyAction

    public init(
        keyCode: UInt16, modifierFlags: UInt = 0, characters: String = "", charactersWithoutControl: String = "", unmodified: String = "",
        action: KeyAction = .press
    ) {
        self.keyCode = keyCode
        self.modifierFlags = modifierFlags
        self.characters = characters
        self.charactersWithoutControl = charactersWithoutControl
        self.unmodified = unmodified
        self.action = action
    }
}

/// How a Mac view types: the key under the person's finger, the modifiers held, and the text the
/// layout or the input method made of it, handed to the screen's key encoder, which alone makes
/// bytes.
public enum Typing {
    /// What one key event sends, given the text the input method committed while handling it and
    /// whether text was being composed before or after it. A key with ⌘ held that reached the view
    /// is a shortcut no menu took, and sends nothing, as in every Mac terminal. While text is composed nothing is sent;
    /// committed text is sent once, as the key's text, which the encoder sends as it is whatever
    /// the key (Return committing にほん sends にほん) (V4).
    public static func inputs(for event: MacKeyEvent, committed: [String], composedBefore: Bool, composingNow: Bool) -> [KeyInput] {
        guard event.modifierFlags & command == 0 else { return [] }
        if !committed.isEmpty {
            return committed.filter { !$0.isEmpty }.map { input(event, text: $0) }
        }
        guard !composedBefore, !composingNow else { return [] }
        return [input(event, text: event.action == .release ? "" : ownText(event))]
    }

    /// A modifier key pressed or released alone (`NSEvent.flagsChanged`): its press while its own
    /// device bit is now set, else its release. The encoder sends it only to a program that asked for
    /// every key (the Kitty protocol's flag 8); nil for a key that is no modifier.
    public static func modifierKey(_ event: MacKeyEvent) -> KeyInput? {
        guard let bit = modifierBits[event.keyCode] else { return nil }
        return KeyInput(key: key(event.keyCode), modifiers: modifiers(event.modifierFlags), action: event.modifierFlags & bit != 0 ? .press : .release)
    }

    private static let modifierBits: [UInt16: UInt] = [
        0x38: 0x02, 0x3C: 0x04, 0x3B: 0x01, 0x3E: 0x2000, 0x3A: 0x20, 0x3D: 0x40, 0x37: 0x08, 0x36: 0x10, 0x39: 1 << 16,
    ]

    /// Text an input method committed outside a key event (the character viewer, a dictation).
    public static func inserted(_ text: String) -> KeyInput? {
        text.isEmpty ? nil : KeyInput(key: .unidentified, text: text)
    }

    static func input(_ event: MacKeyEvent, text: String) -> KeyInput {
        let held = modifiers(event.modifierFlags)
        let consumed: Modifiers = text.isEmpty ? [] : held.subtracting([.control, .rightControl, .super, .rightSuper])
        return KeyInput(
            key: key(event.keyCode), modifiers: held, consumedModifiers: consumed, text: text,
            unshifted: plain(event.unmodified).flatMap(\.unicodeScalars.first), action: event.action
        )
    }

    /// The event's own text, as Ghostty takes it: a control character is replaced by what the key
    /// types without control, so the encoder sees the key; function keys' private-use characters
    /// are no text.
    static func ownText(_ event: MacKeyEvent) -> String {
        guard let scalar = event.characters.unicodeScalars.first, event.characters.unicodeScalars.count == 1 else {
            return plain(event.characters) ?? ""
        }
        if scalar.value < 0x20 {
            return plain(event.charactersWithoutControl) ?? ""
        }
        return plain(event.characters) ?? ""
    }

    private static func plain(_ text: String) -> String? {
        guard !text.isEmpty, !text.unicodeScalars.contains(where: { (0xF700 ... 0xF8FF).contains($0.value) }) else { return nil }
        return text
    }

    private static let command: UInt = 1 << 20

    /// `NSEvent.ModifierFlags` bits, and the device bits that say the right-hand key is held.
    public static func modifiers(_ flags: UInt) -> Modifiers {
        let pairs: [(UInt, Modifiers)] = [
            (1 << 16, .capsLock), (1 << 17, .shift), (1 << 18, .control), (1 << 19, .alt), (1 << 20, .super),
            (0x04, .rightShift), (0x2000, .rightControl), (0x40, .rightAlt), (0x10, .rightSuper),
        ]
        return pairs.reduce(into: Modifiers()) { held, pair in
            if flags & pair.0 != 0 { held.insert(pair.1) }
        }
    }

    /// The key at virtual key code `code`, `.unidentified` for one this table does not name.
    public static func key(_ code: UInt16) -> Key {
        keys[code] ?? .unidentified
    }

    private static let keys: [UInt16: Key] = [
        0x00: .a, 0x01: .s, 0x02: .d, 0x03: .f, 0x04: .h, 0x05: .g, 0x06: .z, 0x07: .x, 0x08: .c, 0x09: .v,
        0x0A: .intlBackslash, 0x0B: .b, 0x0C: .q, 0x0D: .w, 0x0E: .e, 0x0F: .r, 0x10: .y, 0x11: .t, 0x12: .digit1,
        0x13: .digit2, 0x14: .digit3, 0x15: .digit4, 0x16: .digit6, 0x17: .digit5, 0x18: .equal, 0x19: .digit9, 0x1A: .digit7,
        0x1B: .minus, 0x1C: .digit8, 0x1D: .digit0, 0x1E: .bracketRight, 0x1F: .o, 0x20: .u, 0x21: .bracketLeft, 0x22: .i,
        0x23: .p, 0x24: .enter, 0x25: .l, 0x26: .j, 0x27: .quote, 0x28: .k, 0x29: .semicolon, 0x2A: .backslash, 0x2B: .comma,
        0x2C: .slash, 0x2D: .n, 0x2E: .m, 0x2F: .period, 0x30: .tab, 0x31: .space, 0x32: .backquote, 0x33: .backspace,
        0x35: .escape, 0x36: .metaRight, 0x37: .metaLeft, 0x38: .shiftLeft, 0x39: .capsLock, 0x3A: .altLeft, 0x3B: .controlLeft,
        0x3C: .shiftRight, 0x3D: .altRight, 0x3E: .controlRight, 0x3F: .fn, 0x40: .f17, 0x41: .numpadDecimal,
        0x43: .numpadMultiply, 0x45: .numpadAdd, 0x47: .numLock, 0x48: .audioVolumeUp, 0x49: .audioVolumeDown,
        0x4A: .audioVolumeMute, 0x4B: .numpadDivide, 0x4C: .numpadEnter, 0x4E: .numpadSubtract, 0x4F: .f18, 0x50: .f19,
        0x51: .numpadEqual, 0x52: .numpad0, 0x53: .numpad1, 0x54: .numpad2, 0x55: .numpad3, 0x56: .numpad4, 0x57: .numpad5,
        0x58: .numpad6, 0x59: .numpad7, 0x5A: .f20, 0x5B: .numpad8, 0x5C: .numpad9, 0x5D: .intlYen, 0x5E: .intlRo,
        0x5F: .numpadComma, 0x60: .f5, 0x61: .f6, 0x62: .f7, 0x63: .f3, 0x64: .f8, 0x65: .f9, 0x66: .nonConvert, 0x67: .f11,
        0x68: .kanaMode, 0x69: .f13, 0x6A: .f16, 0x6B: .f14, 0x6D: .f10, 0x6E: .contextMenu, 0x6F: .f12, 0x71: .f15,
        0x72: .insert, 0x73: .home, 0x74: .pageUp, 0x75: .delete, 0x76: .f4, 0x77: .end, 0x78: .f2, 0x79: .pageDown,
        0x7A: .f1, 0x7B: .arrowLeft, 0x7C: .arrowRight, 0x7D: .arrowDown, 0x7E: .arrowUp,
    ]

    /// The virtual key code of `key`: what a synthesized event names for it.
    public static func code(of key: Key) -> UInt16? {
        keys.first { $0.value == key }?.key
    }
}

/// The text an input method is composing (marked text), which is drawn but never sent.
public struct Composition: Equatable, Sendable {
    public private(set) var marked = ""

    public init() {}

    public var isComposing: Bool {
        !marked.isEmpty
    }

    public mutating func mark(_ text: String) {
        marked = text
    }

    public mutating func unmark() {
        marked = ""
    }
}
