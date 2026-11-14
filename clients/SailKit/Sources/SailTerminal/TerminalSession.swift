import Foundation
import SailAPI
import SailTerminalWire
import SailVT
import Synchronization

/// One terminal session on a host, kept attached through drops, its screen in a ``Screen``. The
/// same code serves every host; a ``Door`` says only where each connection goes and what token it
/// presents.
///
/// Each connection is served by a thread of its own: it connects, is admitted, opens or attaches,
/// and then reads the host's frames in order, feeding the screen as they come, so nothing is
/// queued between the socket and the screen. Input is written on the caller's thread.
public final class TerminalSession: Sendable {
    /// The waits before each reconnect after a drop; one more failure in a row and the session parks.
    public static let retryWaits: [Duration] = [.milliseconds(500), .seconds(1), .seconds(2), .seconds(4), .seconds(8)]
    /// How long a size must hold before it is sent to the host.
    public static let resizeHold = Duration.milliseconds(60)
    /// How many events wait for an app that is not reading them; the oldest go first.
    public static let eventBacklog = 256

    public let screen: Screen
    /// What the session tells the app, the newest ``eventBacklog`` kept for a slow reader.
    public let events: AsyncStream<SessionEvent>
    /// A tick each time the screen took output; ticks the app has not read yet are one tick.
    public let screenChanges: AsyncStream<Void>

    private let door: Door
    private let core: Mutex<Core>
    private let sequence = Mutex<Int64>(0)
    private let latency = Mutex(LatencyRecorder())
    private let tap = Mutex<(@Sendable ([UInt8]) -> Void)?>(nil)
    private let alarm: Alarm
    private let sleep: @Sendable (Duration) async throws -> Void
    private let timeout: Int
    private let eventSink: AsyncStream<SessionEvent>.Continuation
    private let screenSink: AsyncStream<Void>.Continuation
    private let clock = ContinuousClock()

    private struct Core {
        var state: SessionState
        var intent: Intent
        var connection: Connection?
        var supervisor: Task<Void, Never>?
        var resizeTimer: Task<Void, Never>?
        var viewSize: TerminalSize?
        var hostSize: TerminalSize?
        var focused = true
        var detaching = false
        var closed = false
        /// Which supervisor is the session's: one left behind by a detach stays out.
        var generation = 0
    }

    private enum Outcome: Sendable {
        /// The attempt never attached.
        case failed(TerminalError)
        /// It attached, then the connection was lost.
        case dropped(TerminalError)
        case detached
        case finished(SessionEnd)
    }

    /// A session to open with `create` through `door`, drawn on `screen`.
    public convenience init(open create: SessionCreate, door: Door, screen: Screen) {
        self.init(intent: .open(create), door: door, screen: screen)
    }

    /// A session that attaches to `session` through `door`, asking for the write token when `write`.
    public convenience init(attach session: String, write: Bool, door: Door, screen: Screen) {
        self.init(intent: .attach(session: session, write: write), door: door, screen: screen)
    }

    /// As the public ones, waiting with `sleep` and giving each connect and answer `timeout` seconds.
    package convenience init(
        open create: SessionCreate, door: Door, screen: Screen, timeout: Int,
        sleep: @escaping @Sendable (Duration) async throws -> Void
    ) {
        self.init(intent: .open(create), door: door, screen: screen, timeout: timeout, sleep: sleep)
    }

    package convenience init(
        attach session: String, write: Bool, door: Door, screen: Screen, timeout: Int,
        sleep: @escaping @Sendable (Duration) async throws -> Void
    ) {
        self.init(intent: .attach(session: session, write: write), door: door, screen: screen, timeout: timeout, sleep: sleep)
    }

    private init(
        intent: Intent, door: Door, screen: Screen, timeout: Int = 10,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.door = door
        self.screen = screen
        self.timeout = timeout
        self.sleep = sleep
        alarm = Alarm(sleep: sleep)
        var initial = Core(state: SessionState(phase: .connecting, session: intent.session), intent: intent)
        if case let .open(create) = intent {
            initial.viewSize = create.size
        }
        core = Mutex(initial)
        (events, eventSink) = AsyncStream.makeStream(of: SessionEvent.self, bufferingPolicy: .bufferingNewest(Self.eventBacklog))
        (screenChanges, screenSink) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
    }

    public var state: SessionState {
        core.withLock { $0.state }
    }

    /// The latency of the keys sent so far, the last ``LatencyRecorder/capacity`` of them.
    public var latencyReport: LatencyReport {
        latency.withLock { $0.report }
    }

    /// Opens or attaches, and keeps the session attached until it ends, is detached or closed.
    public func start() {
        core.withLock { core in
            guard core.supervisor == nil, !core.closed else { return }
            switch core.state.phase {
            case .ended: return
            case .connecting, .attached, .reconnecting, .parked, .detached:
                core.detaching = false
                core.generation += 1
                let generation = core.generation
                core.supervisor = Task { await self.supervise(generation) }
            }
        }
    }

    /// Attaches again after a ``detach()``.
    public func attach() {
        start()
    }

    /// Skips a reconnect's wait, or leaves the park and tries at once; nothing while attached. The
    /// app calls it on wake and on a network change.
    public func reconnectNow() {
        alarm.ring()
    }

    /// Detaches, leaving the session running at its host.
    public func detach() {
        let (connection, supervisor) = core.withLock { core -> (Connection?, Task<Void, Never>?) in
            core.detaching = true
            guard let connection = core.connection else {
                defer { core.supervisor = nil }
                return (nil, core.supervisor)
            }
            return (connection, nil)
        }
        if let connection {
            do throws(TerminalError) {
                try connection.send(.detach)
            } catch {
                connection.close()
            }
        } else {
            supervisor?.cancel()
            setPhase(.detached)
        }
    }

    /// Ends this client's part: the connection closes and nothing reconnects.
    public func close() {
        let (connection, supervisor, timer) = core.withLock { core in
            core.closed = true
            defer {
                core.supervisor = nil
                core.connection = nil
            }
            return (core.connection, core.supervisor, core.resizeTimer)
        }
        supervisor?.cancel()
        timer?.cancel()
        connection?.close()
        finish(.closed)
    }

    /// Sends what `key` encodes to; dropped and counted when not attached.
    public func send(_ key: KeyInput) {
        let handedIn = clock.now
        sendInput(screen.encode(key), handedIn: handedIn)
    }

    /// Pastes `text` as ``Screen/paste(_:confirmed:)`` encodes it, sending it unless it needs the
    /// person's confirmation first.
    @discardableResult
    public func paste(_ text: String, confirmed: Bool = false) -> PasteOutcome {
        let handedIn = clock.now
        let outcome = screen.paste(text, confirmed: confirmed)
        if case let .send(bytes) = outcome {
            sendInput(bytes, handedIn: handedIn)
        }
        return outcome
    }

    public func send(_ mouse: MouseInput) {
        let handedIn = clock.now
        sendInput(screen.encode(mouse), handedIn: handedIn)
    }

    public func scroll(lines: Int, x: Double, y: Double) {
        let handedIn = clock.now
        sendInput(screen.scroll(lines: lines, x: x, y: y), handedIn: handedIn)
    }

    /// Whether the app shows the session focused; the program hears it when it asked to.
    public func setFocused(_ focused: Bool) {
        let changed = core.withLock { core in
            defer { core.focused = focused }
            return core.focused != focused
        }
        if changed {
            sendInput(screen.focus(focused), handedIn: nil)
        }
    }

    /// Resizes the screen at once, and tells the host once the size has held for ``resizeHold``.
    public func resize(_ size: GridSize) throws(ScreenError) {
        try screen.resize(size)
        let wanted = TerminalSize(columns: Int32(size.columns), rows: Int32(size.rows))
        let previous = core.withLock { core in
            defer {
                core.viewSize = wanted
                core.resizeTimer = Task {
                    do {
                        try await self.sleep(Self.resizeHold)
                    } catch {
                        return
                    }
                    self.sendViewSize()
                }
            }
            return core.resizeTimer
        }
        previous?.cancel()
    }

    /// Drops the connection as a broken network would; the session reconnects. For the probe.
    package func dropConnection() {
        core.withLock { $0.connection }?.close()
    }

    /// Hands every byte of output to `tap` as it reaches the screen. For the probe's capture of a
    /// fixture, the one place terminal bytes leave the session.
    package func tapOutput(_ tap: @escaping @Sendable ([UInt8]) -> Void) {
        self.tap.withLock { $0 = tap }
    }

    /// Asks the host for the write token.
    public func takeWrite() {
        sendControl(.takeWrite)
    }

    /// Asks the host to end the session.
    public func kill() {
        sendControl(.kill(session: state.session))
    }

    private func supervise(_ generation: Int) async {
        var failures = 0
        var first = true
        var reason = ""
        while !Task.isCancelled {
            if !first {
                if failures >= Self.retryWaits.count {
                    setPhase(.parked(reason: reason))
                    await alarm.wait(nil)
                    failures = 0
                } else {
                    setPhase(.reconnecting(attempt: failures + 1))
                    await alarm.wait(Self.retryWaits[failures])
                }
                guard !Task.isCancelled else { return }
            }
            first = false
            let outcome = await attempt(generation)
            guard core.withLock({ $0.generation == generation }) else { return }
            switch outcome {
            case let .failed(error):
                failures += 1
                reason = error.description
            case .dropped:
                failures = 0
            case .detached:
                core.withLock { $0.supervisor = nil }
                setPhase(.detached)
                return
            case let .finished(end):
                finish(end)
                return
            }
        }
    }

    private func attempt(_ generation: Int) async -> Outcome {
        let intent = core.withLock { $0.intent }
        let entry: (address: HostAddress, token: String)
        do throws(TerminalError) {
            entry = try await door.entry(for: intent.ticket)
        } catch let .ticketRefused(refusal) where !Self.passes(refusal) {
            return .finished(.refused(refusal.message))
        } catch {
            return .failed(error)
        }
        return await withCheckedContinuation { result in
            Thread {
                result.resume(returning: self.connect(to: entry.address, token: entry.token, intent: intent, generation: generation))
            }.start()
        }
    }

    /// One connection, from its connect to its end, on its own thread.
    private func connect(to address: HostAddress, token: String, intent: Intent, generation: Int) -> Outcome {
        let connection: Connection
        do throws(TerminalError) {
            connection = try Connection.open(to: address, token: token, timeout: timeout)
        } catch let .refused(refusal) {
            return .finished(.refused(refusal.message))
        } catch let .skew(skew) {
            return .finished(.refused(TerminalError.skew(skew).description))
        } catch {
            return .failed(error)
        }
        let restarted = core.withLock { core in
            guard let known = core.state.hostBootID else {
                core.state.hostBootID = connection.hostBootID
                return false
            }
            return known != connection.hostBootID
        }
        guard !restarted else {
            connection.close()
            return .finished(.hostRestarted)
        }
        do throws(TerminalError) {
            if case let .open(create) = intent {
                try connection.request(.create(create))
                core.withLock { $0.intent = .attach(session: create.session, write: true) }
            }
            guard case let .attach(session, write) = core.withLock({ $0.intent }) else { return .failed(.closed) }
            try connection.request(.attach(session: session, write: write))
        } catch let .refused(refusal) {
            connection.close()
            return .finished(.refused(refusal.message))
        } catch {
            connection.close()
            return .failed(error)
        }
        connection.stream()
        let refusal: Outcome? = core.withLock { core in
            guard !core.closed else { return .finished(.closed) }
            guard !core.detaching, core.generation == generation else { return .detached }
            core.connection = connection
            core.state.paused = false
            return nil
        }
        if let refusal {
            connection.close()
            return refusal
        }
        alarm.clear()
        setPhase(.attached)
        let outcome = read(connection)
        connection.close()
        core.withLock { core in
            if core.connection === connection { core.connection = nil }
        }
        return outcome
    }

    private func read(_ connection: Connection) -> Outcome {
        do throws(TerminalError) {
            while let frame = try connection.next() {
                if let outcome = take(frame, from: connection) { return outcome }
            }
            return .dropped(.closed)
        } catch {
            return .dropped(error)
        }
    }

    /// Takes one frame of the attached stream, in order; an outcome when it ends the connection.
    private func take(_ frame: Frame, from connection: Connection) -> Outcome? {
        switch frame {
        case let .output(applied, bytes):
            latency.withLock { $0.echoed(through: applied, at: clock.now) }
            tap.withLock { $0 }?(bytes)
            let effects = screen.feed(bytes)
            latency.withLock { $0.screenUpdated(at: clock.now) }
            sendInput(effects.reply, handedIn: nil)
            effects.events.forEach { emit(.screen($0)) }
            screenSink.yield()
        case .replayBegin:
            screen.beginReplay()
        case .replayEnd:
            screen.endReplay()
            let (focused, refit) = core.withLock { core in
                (core.focused, core.viewSize.flatMap { $0 == core.hostSize ? nil : $0 })
            }
            if !focused {
                sendInput(screen.focus(false), handedIn: nil)
            }
            if let refit {
                try? connection.send(.resize(refit))
            }
            screenSink.yield()
        case let .resized(size):
            guard (try? screen.resize(GridSize(columns: Int(size.columns), rows: Int(size.rows)))) != nil else {
                return .dropped(.protocolViolation("it resized the session to \(size.columns)x\(size.rows)"))
            }
            core.withLock { $0.hostSize = size }
            emit(.resized(size))
            screenSink.yield()
        case let .writerChanged(fde):
            core.withLock { $0.state.writer = fde }
            emit(.writerChanged(fde))
        case .paused:
            core.withLock { $0.state.paused = true }
            emit(.paused)
        case .continued:
            core.withLock { $0.state.paused = false }
            emit(.continued)
        case let .sessionEnded(reason):
            return .finished(.ended(reason))
        case let .refused(refusal):
            emit(.refused(refusal))
        case .accepted:
            if core.withLock({ $0.detaching }) { return .detached }
        default:
            return .dropped(.protocolViolation("it sent \(frame.name) to an attached client"))
        }
        return nil
    }

    /// Sends input when attached. Input the person handed in (`handedIn` set) is dropped and
    /// counted otherwise; the terminal's own replies are just dropped.
    private func sendInput(_ bytes: [UInt8], handedIn: ContinuousClock.Instant?) {
        guard !bytes.isEmpty else { return }
        let connection = core.withLock { core -> Connection? in
            guard case .attached = core.state.phase, !core.detaching, let connection = core.connection else {
                if handedIn != nil { core.state.droppedInputs += 1 }
                return nil
            }
            return connection
        }
        guard let connection else { return }
        do throws(TerminalError) {
            let last = try connection.sendInput(bytes) { sequence.withLock { $0 += 1; return $0 } }
            if let handedIn {
                let written = clock.now
                latency.withLock { $0.sent(last, handedIn: handedIn, written: written) }
            }
        } catch {
            connection.close()
        }
    }

    private func sendControl(_ frame: Frame) {
        guard let connection = core.withLock({ $0.connection }) else { return }
        do throws(TerminalError) {
            try connection.send(frame)
        } catch {
            connection.close()
        }
    }

    private func sendViewSize() {
        let (connection, size) = core.withLock { ($0.connection, $0.viewSize) }
        guard let connection, let size else { return }
        do throws(TerminalError) {
            try connection.send(.resize(size))
        } catch {
            connection.close()
        }
    }

    private func setPhase(_ phase: SessionPhase) {
        let changed = core.withLock { core in
            guard !core.closed || phase == .ended(.closed), core.state.phase != phase else { return false }
            if case .ended = core.state.phase { return false }
            core.state.phase = phase
            return true
        }
        if changed { emit(.phase(phase)) }
    }

    private func finish(_ end: SessionEnd) {
        core.withLock { $0.supervisor = nil }
        setPhase(.ended(end))
        eventSink.finish()
        screenSink.finish()
    }

    private func emit(_ event: SessionEvent) {
        eventSink.yield(event)
    }

    /// Whether asking again may get a ticket the API refused: an outage or a limit, not an answer.
    private static func passes(_ refusal: Refusal) -> Bool {
        refusal.code == .known(.terminalUnavailable) || refusal.code == .known(.rateLimited)
    }
}
