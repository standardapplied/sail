import SailTerminal
import SailVT

/// What a terminal view draws from and types into: a ``TerminalSession``, or in tests a fake that
/// encodes through a real screen. Input calls may block while the host is slow to read, so the
/// view makes them on a queue of its own, never on the main thread.
public protocol TerminalLink: AnyObject, Sendable {
    var screen: Screen { get }
    /// When the screen took each output; read by one view, once, for the link's life.
    var screenChanges: AsyncStream<ContinuousClock.Instant> { get }
    var events: AsyncStream<SessionEvent> { get }
    func send(_ key: KeyInput)
    func paste(_ text: String, confirmed: Bool) -> PasteOutcome
    func send(_ mouse: MouseInput)
    func scroll(lines: Int, x: Double, y: Double)
    func setFocused(_ focused: Bool)
    func resize(_ size: GridSize) throws(ScreenError)
    func setCellPixels(width: Int, height: Int) throws(ScreenError)
}

extension TerminalSession: TerminalLink {}
