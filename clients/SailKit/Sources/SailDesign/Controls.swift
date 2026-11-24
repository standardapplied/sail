import SailVT

/// The focus halo around a control's box: a 3 pt ring in `ring`, after a `gapWidth` gap in `gap`
/// (a filled button's: the canvas, so the ring never touches the fill), following its corners.
public struct Halo: Equatable, Sendable {
    public static let width = 3.0

    public let ring: Tint
    public let gap: Tint?
    public let gapWidth: Double

    public init(ring: Tint, gap: Tint?, gapWidth: Double) {
        self.ring = ring
        self.gap = gap
        self.gapWidth = gapWidth
    }

    /// How far past the box the halo reaches.
    public var reach: Double {
        gapWidth + Self.width
    }
}

/// A spinner's two inks: the ring and the turning arc.
public struct SpinnerInk: Equatable, Sendable {
    public let track: Tint
    public let arc: Tint

    public init(track: Tint, arc: Tint) {
        self.track = track
        self.arc = arc
    }

    public static func plain(_ look: Look) -> SpinnerInk {
        SpinnerInk(track: look.separatorStrong, arc: look.text)
    }
}

/// The Sail look's button kinds, as the component sheet draws them.
public enum ButtonKind: CaseIterable, Sendable {
    case primary, secondary, quiet, destructive, destructiveQuiet
}

/// Height, side padding, label size and corners, small to large.
public struct ButtonSize: Equatable, Sendable {
    public let height: Double
    public let padding: Double
    public let label: Double
    public let radius: Double

    public static let small = ButtonSize(height: 26, padding: 10, label: 12, radius: Look.smallRadius)
    public static let regular = ButtonSize(height: 32, padding: 14, label: 13, radius: Look.contentRadius)
    public static let large = ButtonSize(height: 36, padding: 18, label: 14, radius: Look.contentRadius)
    /// A tree row's ⋯: 20 pt inside its 24 pt row, as the mockup's `.act`.
    public static let row = ButtonSize(height: 20, padding: 4, label: 12, radius: 4)

    /// A leading icon or the spinner, and the gaps after them and before a shortcut hint.
    public static let mark = 14.0, markGap = 6.0, hintGap = 8.0

    /// An icon-only button's SF Symbol, and the ⋯ drawn as three dots: their size and their pitch.
    public static let icon = 15.0
    /// The shortcut hint's opacity.
    public static let hintOpacity = 0.7
    /// A pop-up's chevron after its label, in the hint's place.
    public static let chevron = 11.0

    /// The box's width for a label, a leading icon or spinner and a trailing hint of these widths: the
    /// padding inside the 1 pt edge on each side.
    public func width(label: Double, mark: Bool, hint: Double?) -> Double {
        2 * (padding + Look.hairline) + label + (mark ? Self.mark + Self.markGap : 0) + (hint.map { Self.hintGap + $0 } ?? 0)
    }
}

/// What a button is doing.
public struct ButtonState: Equatable, Sendable {
    public var enabled = true
    public var hovered = false
    public var pressed = false
    public var focused = false
    public var busy = false

    public init(enabled: Bool = true, hovered: Bool = false, pressed: Bool = false, focused: Bool = false, busy: Bool = false) {
        self.enabled = enabled
        self.hovered = hovered
        self.pressed = pressed
        self.focused = focused
        self.busy = busy
    }
}

/// How a button of `kind` is drawn in `state`: no hover or press while disabled, no press while in
/// flight; hover on a bordered kind turns its edge to ink (Mast), or to the error colour on a
/// destructive-quiet one, which takes no pressed fill.
public struct ButtonPaint: Equatable, Sendable {
    public let fill: Tint?
    public let edge: Tint?
    public let label: Tint
    public let bold: Bool
    public let opacity: Double
    public let halo: Halo?
    public let spinner: SpinnerInk

    public init(_ kind: ButtonKind, _ state: ButtonState, look: Look) {
        let hovered = state.enabled && (state.hovered || state.pressed && !state.busy)
        let pressed = state.enabled && state.pressed && !state.busy
        func by(_ rest: RGB, _ hover: RGB, _ press: RGB) -> Tint {
            Tint(pressed ? press : hovered ? hover : rest)
        }
        switch kind {
        case .primary:
            (fill, edge, label, bold) = (by(look.accent, look.accentHover, look.accentPressed), nil, Tint(look.onAccent), true)
            spinner = SpinnerInk(track: Tint(look.onAccent, alpha: 0.35), arc: Tint(look.onAccent))
        case .destructive:
            (fill, edge, label, bold) = (by(look.error, look.errorHover, look.errorPressed), nil, Tint(look.onError), true)
            spinner = SpinnerInk(track: Tint(look.onError, alpha: 0.35), arc: Tint(look.onError))
        case .secondary:
            (fill, edge, label, bold) = (pressed ? look.fillPressed : Tint(look.surface), hovered ? look.text : look.separatorStrong, look.text, false)
            spinner = .plain(look)
        case .quiet:
            (fill, edge, label, bold) = (pressed ? look.fillPressed : nil, nil, hovered ? look.text : look.textSecondary, false)
            spinner = .plain(look)
        case .destructiveQuiet:
            (fill, edge, label, bold) = (Tint(look.surface), hovered ? Tint(look.error) : look.separatorStrong, Tint(look.error), false)
            spinner = .plain(look)
        }
        opacity = state.enabled ? 1 : 0.45
        halo = state.focused && state.enabled ? Halo(ring: look.focusHalo, gap: Tint(look.canvas), gapWidth: 1) : nil
    }
}

/// A text field's two sizes: the small one is the sidebar's search, on the canvas.
public struct FieldSize: Equatable, Sendable {
    public let height: Double
    public let inset: Double
    public let text: Double

    public static let regular = FieldSize(height: 32, inset: 10, text: 13)
    public static let small = FieldSize(height: 28, inset: 8, text: 12)
    /// A tree row's name field, in the row's own place: 22 pt in its 24 pt row, the name's 13 pt.
    public static let row = FieldSize(height: 22, inset: 6, text: 13)

    /// The gap between the eyebrow, the box and the line under it.
    public static let gap = 6.0
    /// The error line's mark: its room and the SF Symbol size drawn in it.
    public static let mark = 14.0

    /// Where the text starts: the inset inside the 1 pt edge.
    public var textInset: Double {
        inset + Look.hairline
    }
}

/// What a text field is doing; `failed` is a refusal shown under it.
public struct FieldState: Equatable, Sendable {
    public var enabled = true
    public var hovered = false
    public var editing = false
    public var failed = false

    public init(enabled: Bool = true, hovered: Bool = false, editing: Bool = false, failed: Bool = false) {
        self.enabled = enabled
        self.hovered = hovered
        self.editing = editing
        self.failed = failed
    }
}

/// How a text field is drawn: the edge ink on hover, the accent and the halo while edited, the
/// error colour on a refusal (its halo the error at 30% while edited). Disabled, only the box
/// dims; the eyebrow and the line under it stay readable.
public struct FieldPaint: Equatable, Sendable {
    public let fill: Tint
    public let edge: Tint
    public let halo: Halo?
    public let eyebrow: Tint
    public let boxOpacity: Double

    public init(_ size: FieldSize, _ state: FieldState, look: Look) {
        fill = Tint(size == .small ? look.canvas : look.surface)
        let editing = state.enabled && state.editing
        edge = state.failed ? Tint(look.error) : editing ? Tint(look.accent) : state.enabled && state.hovered ? look.text : look.separatorStrong
        halo = editing ? Halo(ring: state.failed ? Tint(look.error, alpha: 0.30) : look.focusHalo, gap: nil, gapWidth: 0) : nil
        eyebrow = state.failed ? Tint(look.error) : editing ? look.text : look.textTertiary
        boxOpacity = state.enabled ? 1 : 0.45
    }
}

/// The state pills: a workspace's and a template's. The square dot carries the colour; only the
/// error states colour the whole pill; starting, stopping, deleting, creating and building pulse; no
/// template is dashed.
public enum PillState: CaseIterable, Sendable {
    case running, starting, stopping, deleting, creating, stopped, paused, incomplete, noTemplate, building, buildFailed
    /// A workspace listed but never made: hollow, as stopped.
    case notCreated
    /// A workspace whose instance is in Incus's error state.
    case error
    /// A state this app does not know.
    case unknown

    public var label: String {
        switch self {
        case .running: "Running"
        case .starting: "Starting"
        case .stopping: "Stopping"
        case .deleting: "Deleting"
        case .creating: "Creating"
        case .stopped: "Stopped"
        case .paused: "Paused"
        case .incomplete: "Not whole"
        case .noTemplate: "No template"
        case .building: "Building"
        case .buildFailed: "Build failed"
        case .notCreated: "Not created"
        case .error: "Error"
        case .unknown: "Unknown"
        }
    }

    public var pulses: Bool {
        [.starting, .stopping, .deleting, .creating, .building].contains(self)
    }
}

/// The pill's measures: 20 pt (18 in the sidebar), padding before the dot and after the label, the
/// dot, the gap, the label's size and tracking.
public struct PillSize: Equatable, Sendable {
    public let height: Double
    public let leading: Double
    public let trailing: Double
    public let dot: Double
    public let gap: Double
    public let text: Double
    public let tracking: Double

    public static let regular = PillSize(height: 20, leading: 8, trailing: 9, dot: 6, gap: 6, text: 10, tracking: Look.Tracking.pill)
    public static let small = PillSize(height: 18, leading: 6, trailing: 7, dot: 5, gap: 5, text: 9, tracking: Look.Tracking.smallPill)
    /// No template's dashed edge: 3 pt on, 2 off.
    public static let dash = [3.0, 2.0]

    /// The pill's width for a label this wide.
    public func width(label: Double) -> Double {
        leading + dot + gap + label + trailing
    }
}

/// How a pill is drawn: its label and edge inks, a dashed edge, and its dot, filled or hollow.
public struct PillPaint: Equatable, Sendable {
    public let text: Tint
    public let edge: Tint
    public let dashed: Bool
    public let dot: Tint
    public let hollow: Bool

    public init(_ state: PillState, look: Look) {
        let failed = state == .incomplete || state == .buildFailed || state == .error
        text = failed ? Tint(look.error) : look.textSecondary
        edge = failed ? Tint(look.error, alpha: 0.45) : look.separatorStrong
        dashed = state == .noTemplate
        hollow = state == .stopped || state == .notCreated
        dot = switch state {
        case .running: Tint(look.success)
        case .starting, .stopping, .deleting, .creating, .building: Tint(look.info)
        case .paused, .noTemplate: Tint(look.warning)
        case .incomplete, .buildFailed, .error: Tint(look.error)
        case .stopped, .notCreated, .unknown: look.textTertiary
        }
    }
}

/// A key equivalent as a person reads it after a label: the modifiers in the platform's order
/// (⌃⌥⇧⌘), then the key. A letter is capitalised, and an upper-case one also says ⇧, since AppKit
/// presses it with Shift; Return, Enter, Escape, Delete, Tab, Space, the arrows, Home, End, the page
/// keys and the function keys are said by their glyph or name.
public enum Shortcut {
    static let named: [Unicode.Scalar: String] = [
        "\r": "⏎", "\u{3}": "⌤", "\u{1b}": "esc", "\u{7f}": "⌫", "\u{8}": "⌫", "\t": "⇥", "\u{19}": "⇥", " ": "Space",
        "\u{F700}": "↑", "\u{F701}": "↓", "\u{F702}": "←", "\u{F703}": "→", "\u{F728}": "⌦", "\u{F729}": "↖", "\u{F72B}": "↘",
        "\u{F72C}": "⇞", "\u{F72D}": "⇟",
    ]

    /// Nil for a key it cannot name (an empty key, more than one character, another control or
    /// private-use character), so no hint says something untrue.
    public static func said(_ key: String, control: Bool = false, option: Bool = false, shift: Bool = false, command: Bool = false) -> String? {
        guard key.unicodeScalars.count == 1, let scalar = key.unicodeScalars.first else { return nil }
        let upper = key != key.lowercased()
        let modifiers = (control ? "⌃" : "") + (option ? "⌥" : "") + (shift || upper ? "⇧" : "") + (command ? "⌘" : "")
        if let name = named[scalar] { return modifiers + name }
        if (0xF704 ... 0xF726).contains(scalar.value) { return modifiers + "F\(scalar.value - 0xF703)" }
        guard !(0xE000 ... 0xF8FF).contains(scalar.value), scalar.properties.generalCategory != .control else { return nil }
        return modifiers + key.uppercased()
    }
}

/// How long ago something was, as the app says it everywhere ("listed 4 min ago"): seconds under a
/// minute, then minutes, hours and days, whole and rounded down; "just now" under a second, and for
/// a time a clock put ahead.
public enum Ago {
    public static func said(_ seconds: Double) -> String {
        let whole = Int(seconds.rounded(.down))
        switch whole {
        case ..<1: return "just now"
        case ..<60: return "\(whole) s ago"
        case ..<3600: return "\(whole / 60) min ago"
        case ..<86400: return "\(whole / 3600) h ago"
        default: return "\(whole / 86400) d ago"
        }
    }
}

/// Where a line of text sits, as the design's browser sets it. Its ascent and descent are rounded to
/// whole points and the line box's leading split in two, the half above rounded down; so a 10 pt
/// mono label in a 10 pt line has its baseline 8 pt under the line's top, not 10.2.
public enum TextLine {
    /// JetBrains Mono's ascent and descent, in ems (hhea, typo and win agree: 1020 and 300).
    public static let monoAscent = 1.02, monoDescent = 0.30

    /// The baseline of `points` text with this ascent and descent (in ems) in a line box
    /// `lineHeight` tall (nil: the font's own height) whose top is at `top`.
    public static func baseline(top: Double, points: Double, lineHeight: Double? = nil, ascent: Double = monoAscent, descent: Double = monoDescent) -> Double {
        let above = (ascent * points).rounded(), below = (descent * points).rounded()
        return top + above + (((lineHeight ?? above + below) - above - below) / 2).rounded(.down)
    }

    /// A line of `points` text at the font's own height, its ascent and descent rounded.
    public static func height(points: Double, ascent: Double = monoAscent, descent: Double = monoDescent) -> Double {
        (ascent * points).rounded() + (descent * points).rounded()
    }

    /// How far to raise text set in lines `lineHeight` tall (AppKit puts its baseline `descent`
    /// above the line's foot) to stand where the browser puts it, with the font's ascent and
    /// descent in points.
    public static func centring(lineHeight: Double, ascent: Double, descent: Double) -> Double {
        let above = ascent.rounded(), below = descent.rounded()
        return lineHeight - descent - (above + ((lineHeight - above - below) / 2).rounded(.down))
    }

    /// A baseline on the device's pixel grid, as the browser lays text: rounded down to a whole
    /// pixel at `scale` pixels a point.
    public static func snapped(_ baseline: Double, scale: Double) -> Double {
        (baseline * scale).rounded(.down) / scale
    }
}

/// The quiet scroller, after Mast's terminal scrollbar (`components.css` `.term-scrollbar`): a
/// 12 pt overlay track inset 4 pt top and bottom, drawing nothing of its own; a 6 pt thumb in
/// 3 pt corners, 3 pt from the edge, in the look's text at 35 %, and 55 % hovered or dragged.
public enum ScrollerStyle {
    public static let track = 12.0, thumb = 6.0, radius = 3.0, edge = 3.0, inset = 4.0
    public static let rest = 0.35, active = 0.55

    /// The thumb's ink in `look`, hovered or dragged (`active`) or at rest.
    public static func ink(_ look: Look, active: Bool) -> Tint {
        Tint(look.text.rgb, alpha: active ? Self.active : rest)
    }
}
