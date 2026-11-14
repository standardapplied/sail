import AppKit
import SailLook
import SailSurface
import SailTerminal
@testable import SailTerminalView
import SailVT
import Synchronization

/// A session stand-in that encodes through a real screen, so the bytes it records are
/// libghostty-vt's, as a session would send them.
final class FakeLink: TerminalLink {
    let screen: Screen
    let screenChanges: AsyncStream<Void>
    let events: AsyncStream<SessionEvent>
    let changesSink: AsyncStream<Void>.Continuation
    private let sent = Mutex<[UInt8]>([])
    private let sizes = Mutex<[GridSize]>([])
    private let focus = Mutex(true)

    init(columns: Int = 80, rows: Int = 24) throws {
        screen = try Screen(size: GridSize(columns: columns, rows: rows), scrollbackBytes: 1 << 20, palette: .dark)
        (screenChanges, changesSink) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        events = AsyncStream { _ in }
    }

    var bytes: [UInt8] {
        sent.withLock { $0 }
    }

    var resizes: [GridSize] {
        sizes.withLock { $0 }
    }

    func forget() {
        sent.withLock { $0 = [] }
    }

    /// Output from the program: fed to the screen, and the screen's tick told.
    func output(_ text: String) {
        _ = screen.feed(Array(text.utf8))
        changesSink.yield()
    }

    func send(_ key: KeyInput) {
        record(screen.encode(key))
    }

    func paste(_ text: String, confirmed: Bool) -> PasteOutcome {
        let outcome = screen.paste(text, confirmed: confirmed)
        if case let .send(bytes) = outcome { record(bytes) }
        return outcome
    }

    func send(_ mouse: MouseInput) {
        record(screen.encode(mouse))
    }

    func scroll(lines: Int, x: Double, y: Double) {
        record(screen.scroll(lines: lines, x: x, y: y))
    }

    func setFocused(_ focused: Bool) {
        let changed = focus.withLock { current in
            defer { current = focused }
            return current != focused
        }
        if changed { record(screen.focus(focused)) }
    }

    func resize(_ size: GridSize) throws(ScreenError) {
        try screen.resize(size)
        sizes.withLock { $0.append(size) }
    }

    private func record(_ bytes: [UInt8]) {
        sent.withLock { $0 += bytes }
    }
}

/// A display link the test ticks by hand.
@MainActor
final class ManualClock: FrameClock {
    var isPaused = true {
        didSet {
            guard !isPaused, let waiting else { return }
            self.waiting = nil
            waiting.resume()
        }
    }

    private var waiting: CheckedContinuation<Void, Never>?

    /// Returns at the next resume after the call.
    func nextResume() async {
        await withCheckedContinuation { waiting = $0 }
    }

    func invalidate() {
        isPaused = true
    }
}

/// A window that is key without the app being active: the test process has no running app.
final class KeyWindow: NSWindow {
    override var isKeyWindow: Bool {
        true
    }
}

/// The view in a key window, its first responder, ticked by hand.
@MainActor
struct Harness {
    let view: TerminalView
    let window: KeyWindow
    let clock: ManualClock
    let link: FakeLink
    let pasteboard: NSPasteboard

    static let fonts = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "Fonts")

    init(columns: Int = 80, rows: Int = 24, size: NSSize = NSSize(width: 800, height: 500)) throws {
        _ = NSApplication.shared
        let view = try TerminalView(fonts: TerminalFonts(directory: Self.fonts))
        let clock = ManualClock()
        view.makeClock = { _ in clock }
        let window = KeyWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        window.contentView = view
        window.orderFrontRegardless()
        window.makeFirstResponder(view)
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("sh.standardapplied.sail.tests.\(UUID().uuidString)"))
        view.pasteboard = pasteboard
        let link = try FakeLink(columns: columns, rows: rows)
        view.attach(link)
        Self.flush(view)
        self.view = view
        self.window = window
        self.clock = clock
        self.link = link
        self.pasteboard = pasteboard
    }

    /// Waits for every input the view queued to have reached the link.
    func flush() {
        Self.flush(view)
    }

    private static func flush(_ view: TerminalView) {
        view.input.sync {}
    }

    /// One display refresh, when the link runs.
    func tick() {
        if !clock.isPaused { view.refresh() }
    }

    func key(_ characters: String, code: UInt16, ignoring: String? = nil, flags: NSEvent.ModifierFlags = []) {
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            guard let event = NSEvent.keyEvent(
                with: type, location: .zero, modifierFlags: flags, timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, characters: characters, charactersIgnoringModifiers: ignoring ?? characters,
                isARepeat: false, keyCode: code
            ) else { continue }
            window.sendEvent(event)
        }
        flush()
    }

    func mouse(_ type: NSEvent.EventType, column: Int, row: Int, clicks: Int = 1) {
        let metrics = view.metrics
        let origin = metrics.origin(of: CellPoint(x: column, y: row))
        let point = NSPoint(
            x: (Double(origin.x) + Double(metrics.width) / 2) / metrics.scale, y: (Double(origin.y) + Double(metrics.height) / 2) / metrics.scale
        )
        guard let event = NSEvent.mouseEvent(
            with: type, location: view.convert(point, to: nil), modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
            windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: clicks, pressure: 1
        ) else { return }
        window.sendEvent(event)
    }
}

/// The paste confirmations the view asks for, handed to the test one at a time.
@MainActor
final class Asker {
    private var waiting: CheckedContinuation<(lines: Int, answer: @MainActor (Bool) -> Void), Never>?

    func asked(_ lines: Int, _ answer: @escaping @MainActor (Bool) -> Void) {
        waiting?.resume(returning: (lines, answer))
        waiting = nil
    }

    /// Returns at the next question asked after the call.
    func next() async -> (lines: Int, answer: @MainActor (Bool) -> Void) {
        await withCheckedContinuation { waiting = $0 }
    }
}
