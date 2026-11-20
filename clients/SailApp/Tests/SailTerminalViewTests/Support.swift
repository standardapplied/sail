import AppKit
import QuartzCore
import SailLook
import SailSurface
import SailTerminal
@testable import SailTerminalView
import SailVT
import Synchronization
import Testing

/// A session stand-in that encodes through a real screen, so the bytes it records are
/// libghostty-vt's, as a session would send them.
final class FakeLink: TerminalLink {
    let screen: Screen
    let events: AsyncStream<SessionEvent>
    let eventsSink: AsyncStream<SessionEvent>.Continuation
    private let listener = Relay<ContinuousClock.Instant>()
    private let sent = Mutex<[UInt8]>([])
    private let sizes = Mutex<[GridSize]>([])
    private let focus = Mutex(true)

    init(columns: Int = 80, rows: Int = 24) throws {
        screen = try Screen(size: GridSize(columns: columns, rows: rows), scrollbackBytes: 1 << 20, palette: .dark)
        (events, eventsSink) = AsyncStream.makeStream(of: SessionEvent.self)
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

    /// Whether a view listens for the screen's changes.
    var listened: Bool {
        listener.isSet
    }

    func onScreenChange(_ listener: (@Sendable (ContinuousClock.Instant) -> Void)?) {
        self.listener.set(listener)
    }

    /// Output from the program: fed to the screen, what it asks of the app told as a writer's
    /// session tells it, and the change told on this thread, as a session's reader thread tells it.
    func output(_ text: String) {
        screen.feed(Array(text.utf8)).events.forEach { eventsSink.yield(.screen($0)) }
        listener(.now)
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

    /// Recolours the screen and tells the change, as a writer's session does.
    func setPalette(_ palette: Palette) throws(ScreenError) {
        record(try screen.setPalette(palette).reply)
        listener(.now)
    }

    func setCellPixels(width: Int, height: Int) throws(ScreenError) {
        record(try screen.setCellPixels(width: width, height: height).reply)
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
    /// How many times the view resumed the clock: each a display refresh asked for.
    private(set) var resumes = 0

    var isPaused = true {
        didSet {
            if oldValue, !isPaused { resumes += 1 }
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

    init(columns: Int = 80, rows: Int = 24, size: NSSize = NSSize(width: 800, height: 500), blinks: Bool = false) throws {
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
        view.isOnScreen = { _ in true }
        view.cursorBlinks = blinks
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

    /// Waits until every block already on the main queue has run, the view's hops back from its
    /// input queue among them: the main queue runs its blocks in order.
    func drainMain() async {
        await withCheckedContinuation { continuation in
            DispatchQueue.main.async { continuation.resume() }
        }
    }

    private static func flush(_ view: TerminalView) {
        view.input.sync {}
    }

    /// One display refresh, when the link runs, and the frame drawn on the arrival of the drawable
    /// it asked for off the main thread.
    func tick() async {
        guard !clock.isPaused else { return }
        view.refresh(target: CACurrentMediaTime() + 1.0 / 60)
        await drawn()
    }

    /// Waits, at most `seconds`, until `condition` holds: what the view's hops to the main actor
    /// lead to. Such a hop is a task, which may wait for a thread of the shared pool before it
    /// reaches the main queue, so one turn of the main queue does not wait for it.
    func until(_ seconds: Double = 5, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(1))
        }
        return true
    }

    /// Waits until no drawable the view asked for is still on its way.
    func drawn() async {
        let came = await until { !view.drawingState.contains("; awaiting a drawable") }
        if !came { Issue.record("a drawable the view asked for did not come within 5 s") }
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

    /// A mouse event at a cell, handed to the view as AppKit hands it: a window of a test process,
    /// which is never the active app, takes a first click to activate itself instead.
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
        switch type {
        case .leftMouseDown: view.mouseDown(with: event)
        case .leftMouseDragged: view.mouseDragged(with: event)
        case .leftMouseUp: view.mouseUp(with: event)
        case .rightMouseDown: view.rightMouseDown(with: event)
        case .rightMouseDragged: view.rightMouseDragged(with: event)
        case .rightMouseUp: view.rightMouseUp(with: event)
        case .otherMouseDown: view.otherMouseDown(with: event)
        case .otherMouseDragged: view.otherMouseDragged(with: event)
        case .otherMouseUp: view.otherMouseUp(with: event)
        default: view.mouseMoved(with: event)
        }
    }
}

/// Swift's shared pool held at every priority: at each, twice as many tasks as the machine has
/// cores wait, as a task stuck in a blocking call does, until the test lets them go, never on a
/// timer. The pool runs only some of them; those left waiting at every priority are what shows each
/// priority's share of the pool is full, so a task at any priority waits for a thread.
final class PoolHold: Sendable {
    static let priorities: [TaskPriority] = [.background, .utility, .medium, .userInitiated, .high]

    private let gate: DispatchSemaphore
    private let count: Int
    private let released = Mutex(false)
    /// How many holders were set going at each priority, by its raw value.
    let asked: [UInt8: Int]
    /// How many of them run, by priority's raw value, once the starts stopped coming.
    let running: [UInt8: Int]

    init() {
        let gate = DispatchSemaphore(value: 0), started = DispatchSemaphore(value: 0)
        let each = ProcessInfo.processInfo.activeProcessorCount * 2
        let ran = Tally()
        var asked: [UInt8: Int] = [:]
        for priority in Self.priorities {
            asked[priority.rawValue, default: 0] += each
            for _ in 0 ..< each {
                Task.detached(priority: priority) {
                    ran.add(priority)
                    Self.hold(gate, started: started)
                }
            }
        }
        Self.awaitStartsToStop(started, upTo: each * Self.priorities.count)
        self.gate = gate
        count = each * Self.priorities.count
        self.asked = asked
        running = ran.counts
    }

    /// Whether at every priority some holders never started: the pool runs no more at any of them.
    var holdsEveryPriority: Bool {
        asked.allSatisfy { priority, asked in running[priority, default: 0] < asked }
    }

    func release() {
        let first = released.withLock { released in
            defer { released = true }
            return !released
        }
        if first { for _ in 0 ..< count { gate.signal() } }
    }

    /// How many holders ran, by priority, counted from the holders' own threads.
    private final class Tally: Sendable {
        private let ran = Mutex<[UInt8: Int]>([:])

        var counts: [UInt8: Int] {
            ran.withLock { $0 }
        }

        func add(_ priority: TaskPriority) {
            ran.withLock { $0[priority.rawValue, default: 0] += 1 }
        }
    }

    private static func hold(_ gate: DispatchSemaphore, started: DispatchSemaphore) {
        started.signal()
        gate.wait()
    }

    /// Waits for the first start, at most 5 s, then for the starts to stop: no new one in 50 ms.
    private static func awaitStartsToStop(_ started: DispatchSemaphore, upTo limit: Int) {
        guard started.wait(timeout: .now() + 5) == .success else { return }
        var counted = 1
        while counted < limit, started.wait(timeout: .now() + .milliseconds(50)) == .success {
            counted += 1
        }
    }
}

/// A moment one thread marks and another reads, on the host clock.
final class Moment: Sendable {
    private let marked = Mutex(0.0)

    var time: Double {
        marked.withLock { $0 }
    }

    func mark() {
        let now = CACurrentMediaTime()
        marked.withLock { $0 = now }
    }
}

/// Runs `work` on a thread of its own, as a session's reader thread runs, and returns once it ran.
func onItsOwnThread(_ work: @escaping @Sendable () -> Void) {
    let done = DispatchSemaphore(value: 0)
    Thread {
        work()
        done.signal()
    }.start()
    done.wait()
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
