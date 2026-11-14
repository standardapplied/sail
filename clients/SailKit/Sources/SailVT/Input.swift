/// A key event as any platform describes it; the screen's key encoder turns it into bytes.
public struct KeyInput: Equatable, Sendable {
    public var key: Key
    public var modifiers: Modifiers
    /// The modifiers the platform used up producing `text` (shift for "A", option for "å").
    public var consumedModifiers: Modifiers
    /// The text the key produces under the current layout, empty for none.
    public var text: String
    /// The key's character with no modifiers, nil when it has none.
    public var unshifted: Unicode.Scalar?
    public var action: KeyAction
    /// An input method is composing; the key is part of that and not text yet.
    public var composing: Bool

    public init(
        key: Key, modifiers: Modifiers = [], consumedModifiers: Modifiers = [], text: String = "",
        unshifted: Unicode.Scalar? = nil, action: KeyAction = .press, composing: Bool = false
    ) {
        self.key = key
        self.modifiers = modifiers
        self.consumedModifiers = consumedModifiers
        self.text = text
        self.unshifted = unshifted
        self.action = action
        self.composing = composing
    }
}

public enum KeyAction: Equatable, Sendable {
    case press, `repeat`, release
}

/// Held modifiers; the `right…` flags say the right-hand key of a pair is the one held.
public struct Modifiers: OptionSet, Hashable, Sendable {
    public let rawValue: UInt16

    public init(rawValue: UInt16) {
        self.rawValue = rawValue
    }

    public static let shift = Modifiers(rawValue: 1 << 0)
    public static let control = Modifiers(rawValue: 1 << 1)
    public static let alt = Modifiers(rawValue: 1 << 2)
    public static let `super` = Modifiers(rawValue: 1 << 3)
    public static let capsLock = Modifiers(rawValue: 1 << 4)
    public static let numLock = Modifiers(rawValue: 1 << 5)
    public static let rightShift = Modifiers(rawValue: 1 << 6)
    public static let rightControl = Modifiers(rawValue: 1 << 7)
    public static let rightAlt = Modifiers(rawValue: 1 << 8)
    public static let rightSuper = Modifiers(rawValue: 1 << 9)
}

/// Which option keys type as alt on a Mac; set after every sync from the terminal, which cannot
/// know it.
public enum OptionAsAlt: Equatable, Sendable {
    case none, both, left, right
}

/// A pointer event at a place on the terminal's surface, in pixels from its top left.
public struct MouseInput: Equatable, Sendable {
    public var action: MouseAction
    /// The button pressed or released; nil for a motion with none held.
    public var button: MouseButton?
    public var x: Double
    public var y: Double
    public var modifiers: Modifiers

    public init(action: MouseAction, button: MouseButton?, x: Double, y: Double, modifiers: Modifiers = []) {
        self.action = action
        self.button = button
        self.x = x
        self.y = y
        self.modifiers = modifiers
    }
}

public enum MouseAction: Equatable, Sendable {
    case press, release, motion
}

public enum MouseButton: Equatable, Sendable {
    case left, right, middle, wheelUp, wheelDown, wheelLeft, wheelRight
}

/// The answer to a paste.
public enum PasteOutcome: Equatable, Sendable {
    /// The bytes to send the program.
    case send([UInt8])
    /// The text could run commands in a program that did not ask for pastes (mode 2004): it is not
    /// sent until the person confirms.
    case needsConfirmation
    case nothingToPaste
}

/// A selection the person makes, from cells of the viewport.
public enum SelectionRequest: Equatable, Sendable {
    case word(at: CellPoint)
    case line(at: CellPoint)
    case range(from: CellPoint, to: CellPoint)
}
