import Foundation

/// A drive script: steps run in order once the preview's session attached. Each step is one
/// object naming one action; `name` labels it in the report and its saved image.
struct Script: Decodable {
    let steps: [Step]
}

struct Step: Decodable {
    var name: String?
    /// Text typed one key at a time, through the window's `sendEvent` and the input context.
    var type: String?
    /// A key pressed with `modifiers` ("control", "option", "shift", "command"): a letter, a digit,
    /// or "enter", "escape", "tab", "backspace", "arrowUp", "arrowDown", "arrowLeft", "arrowRight".
    var press: String?
    var modifiers: [String]?
    /// Marked texts set in order through the input client, then `commit` inserted.
    var compose: [String]?
    var commit: String?
    /// A click at a cell `[column, row]`, `clicks` times, with Shift when `shift`.
    var click: [Int]?
    var clicks: Int?
    var shift: Bool?
    /// A drag from one cell to another, `[[column, row], [column, row]]`.
    var drag: [[Int]]?
    /// Wheel lines, up negative.
    var scroll: Int?
    /// The window's content size in points, `[width, height]`.
    var resize: [Double]?
    /// "bigger", "smaller" or "reset".
    var font: String?
    /// ⌘C, then the pasteboard must hold exactly `pasteboard` when given.
    var copy: Bool?
    var pasteboard: String?
    /// `paste` put on the pasteboard, then ⌘V; `confirm` answers a confirmation sheet.
    var paste: String?
    var confirm: Bool?
    /// The drawable count from now on, 2 or 3.
    var drawables: Int?
    /// Waits until a visible line, trimmed, equals `line` (and the line above it equals `after`,
    /// when given), the top line equals `top`, a line is the grid's column count (`gridColumns`),
    /// the screen contains `text`, or the banner contains `banner`, within `seconds` (10 by
    /// default).
    var line: String?
    var after: String?
    var top: String?
    var gridColumns: Bool?
    var text: String?
    var banner: String?
    var seconds: Double?
    /// The visible text stays what it was when the step began while `seconds` pass and the
    /// screen takes output.
    var still: Bool?
    /// No gap between frames that drew output while more waited was longer than this many ms.
    var gapUnder: Double?
    /// Types `measure` keys, one each `interval` ms (50 by default), and records the latency.
    var measure: Int?
    var interval: Int?
    /// Measures the renderer's CPU side in this release build: a full 200x60 redraw's p95 must be
    /// 4 ms or less.
    var bench: Bool?
    /// The window's title must become this.
    var title: String?
    /// ⌥⌘L, then the latency overlay must show its figures.
    var latencyOverlay: Bool?
    /// Starts the latency from nothing.
    var resetLatency: Bool?
    /// Kills the host the preview started, as a crash would.
    var killHost: Bool?
    /// Waits until the session is attached.
    var attached: Bool?
    /// Opens a new session, as the banner's button does.
    var newSession: Bool?
    /// The view's font size must be this many points.
    var fontPoints: Double?
}
