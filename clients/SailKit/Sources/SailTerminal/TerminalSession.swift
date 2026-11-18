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
/// queued between the socket and the screen.
///
/// Input is written on the caller's thread, which waits while the host is slow to read it, at
/// most ``Socket/sendTimeout`` seconds before the connection is given up: a large paste, or any
/// input against a stuck peer, blocks its caller, so an app sends from a thread that may wait, not
/// its main thread.
public final class TerminalSession: Sendable {
    /// The waits before each reconnect after a drop; one more failure in a row and a local door's
    /// session parks.
    public static let retryWaits: [Duration] = [.milliseconds(500), .seconds(1), .seconds(2), .seconds(4), .seconds(8)]
    /// How long a runner's door waits in all before it parks: past ``retryWaits`` it goes on at their
    /// cap, so a network that is gone for well under a minute, as on a train or a moved laptop, is
    /// ridden out.
    public static let runnerPatience = Duration.seconds(60)
    /// How long a ticket never presented is asked again for the next attempt instead of a new one:
    /// well inside its life (``PtyMessage``'s 30 s), so it is still good when presented.
    public static let unpresentedReuse = Duration.seconds(20)
    /// How long a connection must stay attached to count as a success: one lost sooner is a failed
    /// attempt, so a host that attaches and then drops parks the session like one that never answers.
    public static let stableAttachment = Duration.seconds(5)
    /// How long a size must hold before it is sent to the host.
    public static let resizeHold = Duration.milliseconds(60)
    /// The name of the thread that reads the host's frames and feeds the screen, so a sampler finds it.
    public static let readerName = "sh.standardapplied.sail.terminal.reader"
    /// How many events wait for an app that is not reading them; the oldest go first.
    public static let eventBacklog = 256
    /// The largest grid a session takes, from the app or from its host.
    public static let largestGrid = GridSize(columns: 1000, rows: 500)
    /// How many inputs not yet confirmed applied are kept to be accounted for after a drop.
    public static let unconfirmedLimit = 4096
    /// What the host answers input from a connection that does not hold the write token. The host's
    /// refusal frame carries words alone, no kind, so the words are what there is to match; a host
    /// that rewords it makes such a connection answer queries again, which the host refuses.
    static let notWriter = "You do not hold the write token."
    /// The largest input number a host may name applied: anything above leaves no room to number
    /// this client's input after it (2^32 inputs), and is a protocol violation.
    static let largestApplied = Int64.max - (1 << 32)

    public let screen: Screen
    /// What the session tells the app, the newest ``eventBacklog`` kept for a slow reader.
    public let events: AsyncStream<SessionEvent>

    private let door: Door
    private let core: Mutex<Core>
    private let sequence = Mutex<Int64>(0)
    private let latency = Mutex(LatencyRecorder())
    private let tap = Relay<[UInt8]>()
    let uploads = Uploads()
    private let alarm: Alarm
    private let sleep: @Sendable (Duration) async throws -> Void
    private let after: HoldTick.After
    private let timeout: Int
    private let stableAfter: Duration
    private let patience: Duration
    private let eventSink: AsyncStream<SessionEvent>.Continuation
    private let screenListener = Relay<ContinuousClock.Instant>()
    private let reader = Mutex<QualityOfService?>(nil)
    private let clock = ContinuousClock()

    private struct Core {
        var state: SessionState
        var intent: Intent
        /// The connection attached now, nil when none is.
        var connection: Connection?
        /// A connection being admitted, not attached yet; a detach or a close ends it.
        var inFlight: Connection?
        var supervisor: Task<Void, Never>?
        var resizeTimer: Task<Void, Never>?
        /// The size of the app's view of the screen.
        var viewSize: TerminalSize?
        /// The pty's size as this client knows it: the last size the host announced, or this client
        /// sent while writing. The host applies a writer's `Resize` and ignores a reader's, so the
        /// pty has exactly this size, unless the client is a reader that thinks it writes, and a
        /// reader's `Resize` changes nothing.
        var ptySize: TerminalSize?
        var holdTimer: (any Scheduled)?
        var focused = true
        var detaching = false
        var closed = false
        /// Which supervisor is the session's: one left behind by a detach stays out.
        var generation = 0
        /// This client holds the write token as far as it knows: it attached to write, or took the
        /// token since, and the host has not refused its input as a reader's. A reader sends none
        /// of the terminal's own answers.
        var writing = true
        /// A `TakeWrite` was sent and the host's grant, a `WriterChanged`, is still to come.
        var takingWrite = false
        var replaying = false
        /// Replays this session has finished.
        var replays = 0
        /// The numbers of inputs written but not yet named applied by an `Output`.
        var unconfirmed: [Int64] = []
        /// The highest input number a live `Output` named applied, which the writer may only note
        /// sent after the reader took it.
        var appliedThrough: Int64 = -1
        /// The inputs left unconfirmed by a lost connection are accounted for by the next replay.
        var accounting = false
        var numbered = false
        /// Whether the host holds, or may hold, this session.
        var atHost: AtHost = .none
        /// What ``atHost`` was before the `Create` now out: a refused `Create` leaves it so.
        var beforeCreate: AtHost = .none
        /// Who waits for that answer.
        var createWaiters: [CheckedContinuation<Void, Never>] = []
        /// The last ticket asked of a runner's door while it has not been presented.
        var unpresented: (request: TicketRequest, entry: (address: HostAddress, token: String), asked: ContinuousClock.Instant)?
    }

    private enum Outcome: Sendable {
        /// The attempt never attached.
        case failed(TerminalError)
        /// It attached, for as long as given, then the connection was lost.
        case dropped(TerminalError, attachedFor: Duration)
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

    /// As the public ones, waiting with `sleep`, giving each connect and answer `timeout` seconds,
    /// counting a connection a success once attached for `stableAfter`, timing a render hold's
    /// end with `after`, and a runner's door parking once its waits and attempts add up to
    /// `patience` (``runnerPatience`` by default).
    package convenience init(
        open create: SessionCreate, door: Door, screen: Screen, timeout: Int,
        stableAfter: Duration = TerminalSession.stableAttachment, sleep: @escaping @Sendable (Duration) async throws -> Void,
        after: @escaping HoldTick.After = HoldTick.dispatch, patience: Duration = TerminalSession.runnerPatience
    ) {
        self.init(
            intent: .open(create), door: door, screen: screen, timeout: timeout, stableAfter: stableAfter, sleep: sleep, after: after,
            patience: patience
        )
    }

    package convenience init(
        attach session: String, write: Bool, door: Door, screen: Screen, timeout: Int,
        stableAfter: Duration = TerminalSession.stableAttachment, sleep: @escaping @Sendable (Duration) async throws -> Void
    ) {
        self.init(
            intent: .attach(session: session, write: write), door: door, screen: screen, timeout: timeout,
            stableAfter: stableAfter, sleep: sleep
        )
    }

    private init(
        intent: Intent, door: Door, screen: Screen, timeout: Int = 10, stableAfter: Duration = TerminalSession.stableAttachment,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        after: @escaping HoldTick.After = HoldTick.dispatch,
        patience: Duration = TerminalSession.runnerPatience
    ) {
        self.door = door
        self.patience = if case .runner = door { patience } else { .zero }
        self.screen = screen
        self.timeout = timeout
        self.stableAfter = stableAfter
        self.sleep = sleep
        self.after = after
        alarm = Alarm(sleep: sleep)
        var initial = Core(state: SessionState(phase: .connecting, session: intent.session), intent: intent)
        if case .attach = intent { initial.atHost = .maybe }
        if case let .open(create) = intent {
            initial.viewSize = create.size
            initial.ptySize = create.size
        }
        core = Mutex(initial)
        (events, eventSink) = AsyncStream.makeStream(of: SessionEvent.self, bufferingPolicy: .bufferingNewest(Self.eventBacklog))
    }

    /// Tells `listener`, in place of any before it and on the thread that changed the screen, each
    /// time the screen changed, with when it took the output; nil stops new calls, while a call
    /// already begun may still finish. Nothing stands between the change and the call, so it must
    /// return at once: what draws hands the change on.
    /// The quality of service the reader thread of the latest connection runs at; nil before one.
    public var readerQuality: QualityOfService? {
        reader.withLock { $0 }
    }

    public func onScreenChange(_ listener: (@Sendable (ContinuousClock.Instant) -> Void)?) {
        screenListener.set(listener)
    }

    private func screenChanged(at time: ContinuousClock.Instant) {
        screenListener(time)
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

    /// Skips a reconnect's wait, or leaves the park and tries at once. While attached it asks the
    /// connection to prove itself: the pty's size as this client knows it (the size the host last
    /// announced, or this client sent while writing) goes to the host, which changes nothing there
    /// (a writer's pty has that size, `TIOCSWINSZ` to it raises no `SIGWINCH`, and a reader's
    /// `Resize` is ignored), but leaves data the peer must acknowledge, so a connection that died while the machine slept is
    /// found dead within ``Socket/deadAfter`` seconds. The protocol has no frame that does nothing
    /// at all: an empty `Input` would count as typing in a room-bound session. The app calls it on
    /// wake and on a network change.
    public func reconnectNow() {
        alarm.ring()
        let (connection, size) = core.withLock { ($0.connection, $0.ptySize) }
        guard let connection, let size else { return }
        send(.resize(size), on: connection)
    }

    /// Detaches, leaving the session running at its host.
    public func detach() {
        let (connection, inFlight, supervisor) = core.withLock { core -> (Connection?, Connection?, Task<Void, Never>?) in
            core.detaching = true
            defer { core.inFlight = nil }
            guard let connection = core.connection else {
                defer { core.supervisor = nil }
                return (nil, core.inFlight, core.supervisor)
            }
            return (connection, core.inFlight, nil)
        }
        inFlight?.close()
        if let connection {
            send(.detach, on: connection)
        } else {
            supervisor?.cancel()
            setPhase(.detached)
        }
    }

    /// Whether the host holds, or may hold, this session: what an end and a quit's count go by.
    public var atHost: AtHost {
        core.withLock { $0.atHost }
    }

    /// Ends this session at its host on a connection of its own, within `limit` in all. An open
    /// whose `Create` is still unanswered is waited for first, inside the same limit, so a session
    /// the host is still making is ended, never left behind unseen; a session never made is gone
    /// already. This client detaches either way.
    public func end(within limit: Duration = Door.endLimit) async -> EndOutcome {
        let began = clock.now
        let coming = core.withLock { core -> Bool in
            core.detaching = true
            return core.atHost == .coming
        }
        if coming { _ = await Door.within(limit) { await self.createAnswered() } }
        let held = self.atHost
        detach()
        guard held != .none else { return .gone }
        return await door.end(state.session, within: limit - (clock.now - began), of: limit)
    }

    /// Ends this client's part: the connection, or the one being admitted, closes and nothing
    /// reconnects.
    public func close() {
        let (connections, supervisor, timers) = core.withLock { core in
            core.closed = true
            defer {
                core.supervisor = nil
                core.connection = nil
                core.inFlight = nil
            }
            return ([core.connection, core.inFlight].compactMap(\.self), core.supervisor, (core.resizeTimer, core.holdTimer))
        }
        supervisor?.cancel()
        timers.0?.cancel()
        timers.1?.cancel()
        connections.forEach { $0.close() }
        finish(.closed)
    }

    /// Sends what `key` encodes to; dropped and counted when not attached. Blocks while the host is
    /// slow to read (see the type's notes).
    public func send(_ key: KeyInput) {
        let handedIn = clock.now
        sendInput(screen.encode(key), handedIn: handedIn)
    }

    /// Pastes `text` as ``Screen/paste(_:confirmed:)`` encodes it, sending it unless it needs the
    /// person's confirmation first. Blocks until the host has read all of it (see the type's notes).
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

    /// Resizes the screen at once, and tells the host once the size has held for ``resizeHold``;
    /// a grid larger than ``largestGrid`` is refused.
    public func resize(_ size: GridSize) throws(ScreenError) {
        guard Self.fits(size) else { throw .invalid("a terminal of \(size) cells is larger than \(Self.largestGrid)") }
        sendInput(try screen.resize(size).reply, handedIn: nil)
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

    /// The pixel size of one cell, which changes with the font; what that owes the program (an
    /// in-band size report, mode 2048) is sent at once.
    public func setCellPixels(width: Int, height: Int) throws(ScreenError) {
        sendInput(try screen.setCellPixels(width: width, height: height).reply, handedIn: nil)
    }

    /// How many replays the session has finished, each onto a screen cleared for it: a screen read
    /// once the count rose shows the whole replay. For the probe.
    package var replays: Int {
        core.withLock { $0.replays }
    }

    /// Drops the connection as a broken network would; the session reconnects. For the probe.
    package func dropConnection() {
        core.withLock { $0.connection }?.close()
    }

    /// Hands every byte of output to `tap` as it reaches the screen. For the probe's capture of a
    /// fixture, the one place terminal bytes leave the session.
    package func tapOutput(_ tap: @escaping @Sendable ([UInt8]) -> Void) {
        self.tap.set(tap)
    }

    /// Uploads `source` as `name` into the session's own workspace, on the attached connection: the
    /// path the host stored it at, which needs no quoting. One upload goes at a time; a later one
    /// waits its turn. Refused with an ``UploadRefusal`` before a byte is sent when this machine's
    /// own host serves the session (it has no workspace), the file is larger than
    /// ``UploadPacing/largest``, or this client is known to be watching, not writing; refused by the
    /// host for its own reasons. A connection that drops loses the upload: before the end is sent,
    /// nothing was kept (``UploadRefusal/Kind/connectionLost``); after it, whether the host stored
    /// it is not known (``UploadRefusal/Kind/notKnown``). Cancelling the task before the end is sent
    /// cancels the upload, and the host keeps nothing; after it, the host's answer stands. A read of
    /// `source` that fails throws its own error. `progress` hears the bytes the host holds as they
    /// grow.
    public func upload(
        name: String, source: UploadSource, progress: @escaping @Sendable (Int64) -> Void = { _ in }
    ) async throws -> String {
        let refusal = { (kind: UploadRefusal.Kind, limit: Int64) in
            UploadRefusal(kind: .known(kind), limit: limit, file: name, size: source.size)
        }
        if case .local = door { throw refusal(.noContainer, 0) }
        guard source.size <= UploadPacing.largest else { throw refusal(.tooLarge, UploadPacing.largest) }
        try await uploads.enter()
        defer { uploads.leave() }
        let (connection, writing) = core.withLock { ($0.connection, $0.writing) }
        guard let connection else { throw refusal(.connectionLost, 0) }
        guard writing else { throw refusal(.notWriter, 0) }
        let flight = uploads.begin(on: connection)
        defer { uploads.finish(flight) }
        let outcome: UploadFlight.Outcome
        do {
            outcome = try await send(source, as: name, on: flight, progress: progress)
        } catch is TerminalError {
            connection.close()
            flight.dropped(connection)
            outcome = await flight.outcome()
        } catch {
            try? flight.send(.putCancel(id: flight.id))
            throw error
        }
        switch outcome {
        case let .stored(path):
            progress(source.size)
            return path
        case let .refused(kind, limit): throw UploadRefusal(kind: kind, limit: limit, file: name, size: source.size)
        case .ended: throw refusal(.sessionEnded, 0)
        case .cancelled:
            try? flight.send(.putCancel(id: flight.id))
            throw CancellationError()
        }
    }

    /// Sends `source` on `flight`, paced by ``UploadPacing``: the outcome, or the error sending or
    /// reading it.
    private func send(
        _ source: UploadSource, as name: String, on flight: UploadFlight, progress: @Sendable (Int64) -> Void
    ) async throws -> UploadFlight.Outcome {
        try flight.send(.putBegin(id: flight.id, name: name, size: source.size))
        if let early = await flight.wait(until: 0) { return early }
        var digest = SHA256()
        var offset: Int64 = 0
        while offset < source.size {
            let bytes = try source.read(Int(min(Int64(UploadPacing.chunk), source.size - offset)))
            guard !bytes.isEmpty else { throw UploadSourceShrank() }
            digest.update(bytes)
            try flight.send(.putChunk(id: flight.id, offset: offset, bytes: bytes))
            offset += Int64(bytes.count)
            if let early = await flight.wait(until: offset - Int64(UploadPacing.window * UploadPacing.chunk)) { return early }
            progress(flight.acked)
        }
        try flight.send(.putEnd(id: flight.id, sha256: digest.finalize()))
        return await flight.outcome()
    }

    /// Asks the host for the write token, which it grants to any attached client; once granted, the
    /// view's size goes to the host, so the pty takes the new writer's size.
    public func takeWrite() {
        let connection = core.withLock { core -> Connection? in
            if core.connection != nil { core.takingWrite = true }
            return core.connection
        }
        guard let connection else { return }
        send(.takeWrite, on: connection)
    }

    /// Asks this machine's own host to end the session, on the attached connection, and is refused
    /// for a session not attached now. A runner's door is refused before anything is sent: it admits
    /// Kill only from the write token's holder and hangs up on a Kill it refuses, so a runner's
    /// session is ended by ``SessionListing/end(_:through:timeout:)``, which waits for the answer.
    public func kill() throws(TerminalError) {
        if case .runner = door {
            throw .unsupported("a runner's session is ended through its door, on a connection that waits for the answer")
        }
        guard let connection = core.withLock({ $0.connection }) else {
            throw .unsupported("the session is not attached, so there is no connection to ask the host to end it on")
        }
        send(.kill(session: state.session), on: connection)
    }

    private func supervise(_ generation: Int) async {
        var failures = 0
        var waited = Duration.zero
        var first = true
        var retrying = false
        var reason = ""
        while !Task.isCancelled {
            if !first {
                if failures >= Self.retryWaits.count, waited >= patience {
                    setPhase(.parked(reason: reason))
                    await alarm.wait(nil)
                    failures = 0
                    waited = .zero
                } else {
                    let wait = Self.retryWaits[min(failures, Self.retryWaits.count - 1)]
                    setPhase(.reconnecting(attempt: failures + 1))
                    await alarm.wait(wait)
                    waited += wait
                }
                guard !Task.isCancelled else { return }
            }
            retrying = !first
            first = false
            let began = clock.now
            let outcome = await attempt(generation)
            guard core.withLock({ $0.generation == generation }) else { return }
            if retrying { waited += clock.now - began }
            switch outcome {
            case let .failed(error):
                failures += 1
                reason = error.description
            case let .dropped(error, attachedFor):
                failures = attachedFor >= stableAfter || !retrying ? 0 : failures + 1
                if failures == 0 { waited = .zero }
                reason = error.description
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
        if case let .open(create) = intent, !Self.fits(GridSize(columns: Int(create.size.columns), rows: Int(create.size.rows))) {
            return .finished(.refused(HostRefusal(
                message: "a terminal of \(create.size.columns)x\(create.size.rows) cells is larger than \(Self.largestGrid)", remedy: []
            )))
        }
        let entry: (address: HostAddress, token: String)
        do throws(TerminalError) {
            entry = try await ticket(for: intent.ticket)
        } catch let .ticketRefused(refusal) where !Self.passes(refusal) {
            return .finished(.ticketRefused(refusal))
        } catch let .signIn(needed) {
            return .finished(.signIn(needed))
        } catch {
            return .failed(error)
        }
        return await withCheckedContinuation { result in
            let reader = Thread {
                self.reader.withLock { $0 = Thread.current.qualityOfService }
                result.resume(returning: self.connect(to: entry.address, token: entry.token, generation: generation))
            }
            reader.name = Self.readerName
            reader.start()
        }
    }

    /// The entry for `request`: a ticket asked of the door, or the last one asked for it when that
    /// was never presented (its connect failed before the `Hello`) and is under
    /// ``unpresentedReuse`` old, so attempts against a door that cannot be reached do not spend the
    /// person's tickets, which the host bounds.
    private func ticket(for request: TicketRequest) async throws(TerminalError) -> (address: HostAddress, token: String) {
        let kept = core.withLock { core -> (address: HostAddress, token: String)? in
            guard let held = core.unpresented, held.request == request, clock.now - held.asked < Self.unpresentedReuse else { return nil }
            return held.entry
        }
        if let kept { return kept }
        let entry = try await door.entry(for: request)
        if case .runner = door {
            let asked = clock.now
            core.withLock { $0.unpresented = (request, entry, asked) }
        }
        return entry
    }

    /// One connection, from its connect to its end, on its own thread. Before each step that
    /// speaks for the session (the create, the attach) it is checked still the session's: a
    /// detach, a close or a newer attempt since makes it stand down, sending nothing.
    private func connect(to address: HostAddress, token: String, generation: Int) -> Outcome {
        let connection: Connection
        do throws(TerminalError) {
            connection = try Connection.connect(to: address, timeout: timeout)
        } catch {
            return .failed(error)
        }
        if let standDown = current(generation, admitting: connection) {
            connection.close()
            return standDown
        }
        core.withLock { $0.unpresented = nil }
        defer { core.withLock { if $0.inFlight === connection { $0.inFlight = nil } } }
        let bootID: String
        do throws(TerminalError) {
            bootID = try connection.admit(token: token)
        } catch {
            connection.close()
            return Self.outcome(of: error)
        }
        let restarted = core.withLock { core in
            guard let known = core.state.hostBootID else {
                core.state.hostBootID = bootID
                return false
            }
            return known != bootID
        }
        guard !restarted else {
            connection.close()
            return .finished(.hostRestarted)
        }
        let write: Bool
        do throws(TerminalError) {
            if case let .open(create) = core.withLock({ $0.intent }) {
                if let standDown = current(generation, creating: true) {
                    connection.close()
                    return standDown
                }
                do throws(TerminalError) {
                    try connection.request(.create(create))
                } catch {
                    var refused = false
                    if case .refused = error { refused = true }
                    answeredCreate(refused: refused)
                    throw error
                }
                core.withLock { $0.intent = .attach(session: create.session, write: true) }
                answeredCreate(refused: false)
            }
            guard case let .attach(session, wantsWrite) = core.withLock({ $0.intent }) else { return .failed(.closed) }
            write = wantsWrite
            if let standDown = current(generation) {
                connection.close()
                return standDown
            }
            try connection.request(.attach(session: session, write: write))
        } catch {
            connection.close()
            return Self.outcome(of: error)
        }
        connection.stream()
        let standDown: Outcome? = core.withLock { core in
            guard !core.closed else { return .finished(.closed) }
            guard !core.detaching, core.generation == generation else { return .detached }
            core.connection = connection
            core.inFlight = nil
            core.state.paused = false
            core.writing = write
            core.takingWrite = false
            core.accounting = !core.unconfirmed.isEmpty
            core.numbered = false
            return nil
        }
        if let standDown {
            connection.close()
            return standDown
        }
        alarm.clear()
        setPhase(.attached)
        let attached = clock.now
        var outcome = read(connection, since: attached)
        connection.close()
        if case .finished(.ended) = outcome { uploads.over(sessionEnded: true) }
        uploads.dropped(connection)
        core.withLock { core in
            if core.connection === connection { core.connection = nil }
            if case .dropped = outcome, core.detaching { outcome = .detached }
        }
        return outcome
    }

    /// Nil while the attempt of `generation` is still the session's, recording `connection` as the
    /// one being admitted, and, when `creating`, that its `Create` goes out now; else what it
    /// stands down as. Checked and recorded at once, so an end sees either no `Create` or one it
    /// waits for.
    private func current(_ generation: Int, admitting connection: Connection? = nil, creating: Bool = false) -> Outcome? {
        core.withLock { core in
            guard !core.closed else { return .finished(.closed) }
            guard !core.detaching, core.generation == generation else { return .detached }
            if let connection { core.inFlight = connection }
            if creating {
                core.beforeCreate = core.atHost
                core.atHost = .coming
            }
            return nil
        }
    }

    /// Returns once no `Create` is unanswered.
    private func createAnswered() async {
        await withCheckedContinuation { (answered: CheckedContinuation<Void, Never>) in
            let now = core.withLock { core -> Bool in
                guard core.atHost == .coming else { return true }
                core.createWaiters.append(answered)
                return false
            }
            if now { answered.resume() }
        }
    }

    /// The `Create` was answered, or its connection lost: whoever waits for it goes on. Refused, the
    /// host made nothing of it; made, or its answer lost, only the host can say whether it holds it.
    private func answeredCreate(refused: Bool) {
        let waiters = core.withLock { core in
            core.atHost = refused ? core.beforeCreate : .maybe
            defer { core.createWaiters = [] }
            return core.createWaiters
        }
        waiters.forEach { $0.resume() }
    }

    /// What an error before the attach means: the host's refusal, a skewed magic and a broken
    /// protocol end the session; anything else is a failed attempt, to try again.
    private static func outcome(of error: TerminalError) -> Outcome {
        switch error {
        case let .refused(refusal): .finished(.refused(refusal))
        case .skew: .finished(.refused(HostRefusal(message: error.description, remedy: [])))
        case let .protocolViolation(what): .finished(.protocolViolation(what))
        default: .failed(error)
        }
    }

    private func read(_ connection: Connection, since attached: ContinuousClock.Instant) -> Outcome {
        do throws(TerminalError) {
            while let frame = try connection.next() {
                if let outcome = take(frame, from: connection) { return outcome }
            }
            return .dropped(.closed, attachedFor: clock.now - attached)
        } catch let .protocolViolation(what) {
            return .finished(.protocolViolation(what))
        } catch {
            return .dropped(error, attachedFor: clock.now - attached)
        }
    }

    /// Takes one frame of the attached stream, in order; an outcome when it ends the connection.
    private func take(_ frame: Frame, from connection: Connection) -> Outcome? {
        switch frame {
        case let .output(applied, bytes):
            guard (-1 ... Self.largestApplied).contains(applied) else {
                return .finished(.protocolViolation("it named input \(applied) applied, outside -1 through \(Self.largestApplied)"))
            }
            account(applied)
            tap(bytes)
            let effects = Signposts.outputToScreen { screen.feed(bytes) }
            let updated = clock.now
            latency.withLock { $0.screenUpdated(at: updated) }
            sendInput(effects.reply, handedIn: nil)
            let writing = core.withLock { $0.writing }
            effects.events.filter { writing || !$0.writesTheClipboard }.forEach { emit(.screen($0)) }
            screenChanged(at: updated)
            armHoldTick()
        case let .replayBegin(safe):
            screen.beginReplay()
            core.withLock { $0.replaying = true }
            emit(.replay(safe: safe))
        case .replayEnd:
            screen.endReplay()
            let (focused, refit) = core.withLock { core in
                core.replaying = false
                core.replays += 1
                if core.accounting {
                    core.state.possiblyLostInputs += core.unconfirmed.count
                    core.unconfirmed = []
                    core.accounting = false
                }
                let refit = core.writing ? core.viewSize.flatMap { $0 == core.ptySize ? nil : $0 } : nil
                if refit != nil { core.ptySize = refit }
                return (core.focused, refit)
            }
            if !focused {
                sendInput(screen.focus(false), handedIn: nil)
            }
            if let refit {
                send(.resize(refit), on: connection)
            }
            screenChanged(at: clock.now)
        case let .resized(size):
            let grid = GridSize(columns: Int(size.columns), rows: Int(size.rows))
            guard Self.fits(grid), let effects = try? screen.resize(grid) else {
                return .finished(.protocolViolation("it resized the session to \(size.columns)x\(size.rows), beyond \(Self.largestGrid)"))
            }
            core.withLock { $0.ptySize = size }
            sendInput(effects.reply, handedIn: nil)
            emit(.resized(size))
            screenChanged(at: clock.now)
        case let .writerChanged(fde):
            let fit = core.withLock { core -> TerminalSize? in
                core.state.writer = fde
                guard core.takingWrite else {
                    if case let .attach(_, write) = core.intent { core.writing = write }
                    return nil
                }
                core.takingWrite = false
                core.writing = true
                if case let .attach(session, _) = core.intent { core.intent = .attach(session: session, write: true) }
                guard let view = core.viewSize, view != core.ptySize else { return nil }
                core.ptySize = view
                return view
            }
            if let fit {
                send(.resize(fit), on: connection)
            }
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
            if refusal.message == Self.notWriter { core.withLock { $0.writing = false } }
            emit(.refused(refusal))
        case .accepted:
            if core.withLock({ $0.detaching }) { return .detached }
        case .putAck, .putStored, .putRefused:
            uploads.take(frame)
        default:
            return .finished(.protocolViolation("it sent \(frame.name) to an attached client"))
        }
        return nil
    }

    /// What an `Output` naming input `applied` as the host's last says of the input this client
    /// sent. In a replay it says what the host applied before this attach: inputs written into a
    /// lost connection and numbered above it never reached the program, and are counted dropped.
    /// Live, it confirms inputs and marks their echo. The first `Output` after an attach also
    /// moves this client's numbering above the session's, so its marks are its own.
    private func account(_ applied: Int64) {
        let replaying = core.withLock { core in
            if core.replaying, core.accounting {
                core.state.possiblyLostInputs += core.unconfirmed.filter { $0 > applied }.count
                core.unconfirmed = []
                core.accounting = false
            } else if !core.replaying {
                core.unconfirmed.removeAll { $0 <= applied }
                core.appliedThrough = max(core.appliedThrough, applied)
            }
            if !core.numbered {
                core.numbered = true
                sequence.withLock { $0 = max($0, applied) }
            }
            return core.replaying
        }
        if !replaying {
            latency.withLock { $0.echoed(through: applied, at: clock.now) }
        }
    }

    /// Sends input when attached. Input the person handed in (`handedIn` set) is dropped and
    /// counted otherwise, and tracked until the host names it applied; the terminal's own answers
    /// are dropped uncounted, and not sent at all while they are muted.
    private func sendInput(_ bytes: [UInt8], handedIn: ContinuousClock.Instant?) {
        guard !bytes.isEmpty else { return }
        let connection = core.withLock { core -> Connection? in
            guard case .attached = core.state.phase, !core.detaching, let connection = core.connection else {
                if handedIn != nil { core.state.droppedInputs += 1 }
                return nil
            }
            return handedIn == nil && !core.writing ? nil : connection
        }
        guard let connection else { return }
        do throws(TerminalError) {
            let last = try connection.sendInput(bytes) { sequence.withLock { $0 &+= 1; return $0 } }
            guard let handedIn else { return }
            let written = clock.now
            latency.withLock { $0.sent(last, handedIn: handedIn, written: written) }
            core.withLock { core in
                guard last > core.appliedThrough else { return }
                if core.unconfirmed.count == Self.unconfirmedLimit { core.unconfirmed.removeFirst() }
                core.unconfirmed.append(last)
            }
        } catch {
            connection.close()
        }
    }

    private func send(_ frame: Frame, on connection: Connection) {
        do throws(TerminalError) {
            try connection.send(frame)
        } catch {
            connection.close()
        }
    }

    private func sendViewSize() {
        let (connection, size) = core.withLock { core in
            if core.connection != nil, core.writing { core.ptySize = core.viewSize }
            return (core.connection, core.viewSize)
        }
        guard let connection, let size else { return }
        send(.resize(size), on: connection)
    }

    /// When output left the program's render hold open, a screen tick at the hold's limit, on a
    /// timer of its own: the frame then goes on without the program, and a renderer that draws only
    /// on a tick draws it.
    private func armHoldTick() {
        guard let remaining = screen.holdRemaining else { return }
        let next = after(remaining) { [weak self] in
            guard let self else { return }
            screenChanged(at: clock.now)
        }
        let previous = core.withLock { core in
            defer { core.holdTimer = next }
            return core.holdTimer
        }
        previous?.cancel()
    }

    private func setPhase(_ phase: SessionPhase) {
        let changed = core.withLock { core in
            guard !core.closed || phase == .ended(.closed), core.state.phase != phase else { return false }
            if case .ended = core.state.phase { return false }
            core.state.phase = phase
            return true
        }
        guard changed else { return }
        switch phase {
        case .ended(.ended), .ended(.hostRestarted): uploads.over(sessionEnded: true)
        case .ended, .parked, .detached: uploads.over(sessionEnded: false)
        case .connecting, .attached, .reconnecting: break
        }
        emit(.phase(phase))
    }

    private func finish(_ end: SessionEnd) {
        core.withLock { $0.supervisor = nil }
        setPhase(.ended(end))
        eventSink.finish()
    }

    private func emit(_ event: SessionEvent) {
        eventSink.yield(event)
    }

    private static func fits(_ size: GridSize) -> Bool {
        (1 ... largestGrid.columns).contains(size.columns) && (1 ... largestGrid.rows).contains(size.rows)
    }

    /// Whether asking again may get a ticket the API refused: an outage or a limit, not an answer.
    private static func passes(_ refusal: Refusal) -> Bool {
        refusal.code == .known(.terminalUnavailable) || refusal.code == .known(.rateLimited)
    }
}
