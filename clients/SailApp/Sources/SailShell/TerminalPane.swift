import AppKit
import SailAPI
import SailControls
import SailLook
import SailSurface
import SailTerminal
import SailTerminalView
import SailTerminalWire
import SailVT
import SailWorkbench

/// One pane: one terminal view drawing one session, made and attached once (W2), on this Mac's host
/// or in a workspace on the runner; only where the session goes and what the banner says differ.
/// The terminal takes the look's palette as the look changes, while it runs.
@MainActor
public final class TerminalPane: NSView {
    /// How the pane comes to its session: opening a new one, attaching as the person asked now
    /// (taking the keyboard from their other client), or resuming one without being asked (a
    /// restore, a sign-in coming back), which never takes it from their other live client.
    public enum Start: Equatable, Sendable {
        case open
        case attach
        case resume
    }

    /// How much of the terminal background lies over a pane that is not focused (Ghostty's
    /// unfocused split opacity of 0.7).
    public static let dimming = 0.3
    /// How long an end goes unsaid: a host that answers within it closes the pane as at once, as
    /// Ghostty's close does; one slower says the pane is ending.
    public static let endingSaidAfter = Duration.milliseconds(200)

    public let id: PaneID
    public let name: String
    public let terminal: TerminalView
    public let host: LocalHost
    public let place: Place
    /// The harness the pane's session runs, nil for a shell.
    public let agent: AgentStart?
    public let opened = ContinuousClock.now
    public private(set) var session: TerminalSession?
    var onEvent: @MainActor (TerminalPane, SessionEvent) -> Void = { _, _ in }
    /// Asked to show the account, where a runner tab whose sign-in lapsed is signed in again.
    var onSignIn: @MainActor () -> Void = {}
    /// Asked to close the pane, its session already over: Close on an ended or gone runner tab.
    var onClose: @MainActor (TerminalPane) -> Void = { _ in }
    /// Whether the session was ever attached: a tab refused before then holds none.
    public private(set) var attachedOnce = false
    /// The end under way, nil once it answered.
    private var ending: Task<EndOutcome, Never>?
    /// Waits out ``endingSaidAfter``; a test holds it.
    var nap: @MainActor (Duration) async -> Void = { try? await Task.sleep(for: $0) }
    /// Whether the last end of this session was not confirmed: it may still be running.
    public private(set) var unended = false

    var look: Look {
        didSet { paint() }
    }

    public internal(set) var isDimmed = false {
        didSet { scrim.isHidden = !isDimmed }
    }

    private var start: Start
    private let door: Door
    /// The runner's tickets, which also list a workspace's sessions; nil on this Mac.
    private let tickets: (any TicketSource)?
    /// The person the pane's runner session is reached as, empty when no one is named.
    let person: String
    private var keyboard = Keyboard()
    /// A key typed while the keyboard is elsewhere was refused, and said: once until it moves again.
    private var refusedSaid = false
    /// Says a refused key: the system's alert sound, as a Mac says a key that goes nowhere.
    package var beep: @MainActor () -> Void = { NSSound.beep() }
    /// Whether the keyboard is with another client, so closing this tab only detaches it.
    package var keyboardElsewhere: Bool {
        keyboard.elsewhere != nil
    }

    /// Where the session of this tab, named `tab`, goes on while the keyboard is elsewhere: nil while
    /// it is here or no one holds it.
    package func stillRunning(_ tab: String) -> String? {
        keyboard.stillRunning(tab)
    }
    private let banner: Card
    /// The sentence ``tell(_:)`` last showed.
    private var told: String?
    /// How many sentences were told: each hides only itself, never one told after it.
    private var tellings = 0
    /// How a told sentence waits out its ten seconds: a test's to hold.
    var toldWait: @MainActor (Duration) async -> Void = { try? await Task.sleep(for: $0) }
    private let scrim = Scrim()
    private let latency = NSTextField(labelWithString: "")
    private var latencyTimer: Timer?

    init(
        _ pane: Pane, start: Start, host: LocalHost, runner: (any TicketSource)?, person: String, fonts: TerminalFonts, points: Double,
        look: Look
    ) throws(ShellError) {
        id = pane.id
        name = pane.session
        place = pane.place
        agent = pane.agent
        self.start = start
        self.host = host
        self.person = person
        tickets = pane.place.onRunner ? runner : nil
        if pane.place.onRunner {
            guard let runner else { throw .host("Sign in to open a terminal in a workspace.") }
            door = .runner(runner)
        } else {
            door = .local(host.address)
        }
        self.look = look
        do {
            terminal = try TerminalView(fonts: fonts, points: points)
        } catch {
            throw .resources("the terminal cannot start: \(error)")
        }
        banner = Card(look: look)
        super.init(frame: NSRect(x: 0, y: 0, width: 640, height: 400))
        compose()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Opens or attaches the session at the grid the pane's frame holds; once.
    func begin() {
        guard session == nil else { return }
        let metrics = terminal.metrics
        let grid = metrics.grid(fitting: PixelSize(
            width: Int(Double(bounds.width) * metrics.scale), height: Int(Double(bounds.height) * metrics.scale)
        ))
        let screen: Screen
        do {
            screen = try Screen(size: grid, scrollbackBytes: 10 << 20, palette: look.palette)
        } catch {
            banner.show(Said.sentence("the terminal screen could not be made: \(error)"), tone: .error)
            return
        }
        let size = TerminalSize(columns: Int32(grid.columns), rows: Int32(grid.rows))
        let next = switch start {
        case .open:
            TerminalSession(open: place.sessionCreate(session: name, size: size, agent: agent), door: door, screen: screen)
        case .attach:
            TerminalSession(attach: name, write: true, door: door, screen: screen)
        case .resume:
            TerminalSession(attach: name, write: true, takes: false, door: door, screen: screen)
        }
        session = next
        if let agent, start == .open, let label = place.label {
            banner.show(NewSessionWords.starting(agent, in: label))
        } else {
            banner.show(place.label.map { "Connecting to \($0) on the runner" } ?? "Connecting to this Mac's terminal host")
        }
        terminal.onEvent = { [weak self] event in self?.handle(event) }
        terminal.recolour(look.palette)
        terminal.attach(next)
        next.start()
    }

    /// Ends the session at its host, then this client's part, on a connection of its own through
    /// the pane's door, the host's answer awaited (a runner's ends a session only for a connection
    /// that holds the write token, and says when it will not), within `limit` in all, a session the
    /// host is still making waited for first (``TerminalSession/end(within:)``); the task answers
    /// what the end came to, nil when nothing is left to end. An end under way is the one answered,
    /// never a second. An attached pane's end is no different: a Kill written to its connection and
    /// not answered could be lost, leaving the session running with no pane.
    @discardableResult
    func end(within limit: Duration = Door.endLimit) -> Task<EndOutcome, Never>? {
        if let ending { return ending }
        guard let session else { return nil }
        if case .ended = session.state.phase, !unended {
            detach()
            return nil
        }
        if unended { banner.hide() }
        let task = Task { [weak self] () -> EndOutcome in
            let outcome = await session.end(within: limit)
            self?.detach()
            self?.ending = nil
            if case .unconfirmed = outcome { self?.unended = true } else { self?.unended = false }
            return outcome
        }
        ending = task
        return task
    }

    /// Whether an end is under way.
    var isEnding: Bool {
        ending != nil
    }

    /// The pane stays while its session is ended, saying so once ``endingSaidAfter`` has passed
    /// with the end still under way.
    @discardableResult
    func showEnding() -> Task<Void, Never> {
        Task { [weak self] in
            await self?.nap(Self.endingSaidAfter)
            guard let self, isEnding else { return }
            banner.show(place.onRunner ? "Ending this terminal on the runner…" : "Ending this terminal…")
        }
    }

    /// The host did not confirm the end, said why: the tab stays, offering to end it again, or to
    /// close it anyway, which leaves the session to run on.
    func showUnended(_ why: [String], endAgain: @escaping @MainActor () -> Void, closeAnyway: @escaping @MainActor () -> Void) {
        banner.show(
            EndOutcome.mayStillRun("This terminal", why, onThisMac: !place.onRunner) + " Close Anyway leaves it there.", tone: .warning,
            button: "End Again", handler: { MainActor.assumeIsolated { endAgain() } }, second: "Close Anyway",
            secondHandler: { MainActor.assumeIsolated { closeAnyway() } }
        )
    }

    /// A new session in the same workspace, on a new ticket, the ended one's screen let go: Open
    /// Again on a runner tab, and Open a New One on one that came back ended.
    func openAgain() {
        session?.close()
        session = nil
        start = .open
        begin()
    }

    /// Ends this client's part only; the session lives on at the host.
    func detach() {
        session?.close()
        latencyTimer?.invalidate()
    }

    /// Whether ending the session may end a program the person is running: false only while the
    /// attached cursor is on the shell's prompt, as the shell marked it (OSC 133), or the session is
    /// over. A session not attached cannot say, so it may.
    package var mayEndAProgram: Bool {
        guard let session else { return false }
        switch session.state.phase {
        case .ended: return false
        case .attached: return !session.screen.cursorAtPrompt
        default: return true
        }
    }

    @objc public func toggleLatency(_: Any?) {
        if latencyTimer == nil {
            latency.isHidden = false
            updateLatency()
            latencyTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.updateLatency() }
            }
        } else {
            latencyTimer?.invalidate()
            latencyTimer = nil
            latency.isHidden = true
        }
    }

    /// What the banner says now (nil when hidden) and the buttons it offers: what a test or the
    /// drive reads.
    public var status: (says: String?, offers: [String]) {
        (banner.shown, banner.offers)
    }

    /// Whether the banner's first button answers Return now.
    package var statusReturns: Bool {
        banner.returns
    }

    /// Presses the banner's button titled `title`, as a click does; false when none is so titled.
    public func press(_ title: String) -> Bool {
        banner.press(title)
    }

    /// The latency overlay's lines, every one shown.
    public static let latencyLines = 4

    /// What the latency overlay says now, nil when it is hidden.
    public var latencyShown: String? {
        latency.isHidden ? nil : latency.stringValue
    }

    /// Try Again on a parked pane: the host made to serve, saying what that did (another copy's
    /// terminals closing), then the session reconnected; what stops it is said with Try Again again.
    private func tryAgain() async {
        do {
            try await host.ensureServing { [weak self] sentence in await self?.say(sentence) }
        } catch {
            return banner.show(Said.sentence("\(error)"), tone: .error, button: "Try Again") { [weak self] in
                Task { await self?.tryAgain() }
            }
        }
        session?.reconnectNow()
    }

    private func say(_ sentence: String) {
        banner.show(sentence)
    }

    /// `sentence` in the terminal's own card for ten seconds, as a ⌘-click on a path that is not
    /// there says; false, nothing shown, while the card says something of the session's own.
    func tell(_ sentence: String) -> Bool {
        guard banner.isHidden || banner.shown == told else { return false }
        told = sentence
        tellings += 1
        let telling = tellings, wait = toldWait
        say(sentence)
        Task { [weak self] in
            await wait(.seconds(10))
            guard let self, tellings == telling, banner.shown == sentence else { return }
            banner.hide()
        }
        return true
    }

    private func handle(_ event: SessionEvent) {
        if case .phase(.ended) = event { terminal.inert = true } else if case .phase(.attached) = event { terminal.inert = false }
        switch event {
        case .phase(.attached):
            attachedOnce = true
            banner.hide()
        case let .writerChanged(writer, yours) where place.onRunner && !person.isEmpty:
            showKeyboard(writer, yours: yours)
        case .refused where keyboard.said != nil && !refusedSaid:
            refusedSaid = true
            beep()
        case .phase(.reconnecting) where place.onRunner:
            banner.show("Reconnecting to \(place.label ?? "the runner")…", button: "Reconnect Now") { [weak self] in
                MainActor.assumeIsolated { self?.session?.reconnectNow() }
            }
        case .phase(.reconnecting):
            banner.show("Reconnecting to this Mac's terminal host")
        case let .phase(.parked(reason)) where place.onRunner:
            banner.show(Said.sentence(reason), tone: .warning, button: "Reconnect Now") { [weak self] in MainActor.assumeIsolated { self?.session?.reconnectNow() } }
        case let .phase(.parked(reason)):
            banner.show(host.endedBy ?? Said.sentence("this Mac's terminal host does not answer: \(reason)"), tone: .warning, button: "Try Again") { [weak self] in
                Task { await self?.tryAgain() }
            }
        case let .phase(.ended(end)) where place.onRunner:
            showEnded(end)
        case let .phase(.ended(end)) where end != .closed:
            banner.show(end.said.joined(separator: " "), tone: .warning)
        default:
            break
        }
        onEvent(self, event)
    }

    /// How a runner tab's session ended, in the server's words, offering what can help: Open Again
    /// only where a new ticket can succeed (it ended, the runner restarted, or it ended while Sail
    /// was away from the runner), Sign In where the sign-in lapsed, and nothing else.
    private func showEnded(_ end: SessionEnd) {
        let again: () -> Void = { [weak self] in MainActor.assumeIsolated { self?.openAgain() } }
        let signIn: () -> Void = { [weak self] in MainActor.assumeIsolated { self?.onSignIn() } }
        switch end {
        case .closed:
            return
        case .hostRestarted:
            banner.show("The runner restarted, which ends every session it held.", tone: .warning, button: "Open Again", handler: again)
        case let .ended(reason):
            banner.show(Said.sentence(reason), tone: .warning, button: "Open Again", handler: again)
        case let .ticketRefused(refusal) where refusal.code == .known(.notFound) && (start != .open || attachedOnce):
            Task { await showReturn(refused: end) }
        case let .refused(refusal) where agent != nil:
            banner.show(NewSessionWords.refused(refusal), tone: .warning, button: "Close", handler: { [weak self] in MainActor.assumeIsolated { self.map { $0.onClose($0) } } })
        case .signIn:
            banner.show(end.said.joined(separator: " "), tone: .warning, button: "Sign In", handler: signIn)
        default:
            banner.show(end.said.joined(separator: " "), tone: .warning)
        }
    }

    /// A runner session the runner would not attach, said from its workspace's listing
    /// (``RunnerReturn``): ended in the host's words, or no longer on the runner, offering Open a
    /// New One; its workspace not on this runner, offering Close alone; the terminal host not
    /// answering, offering Try Again. A listing that cannot be had otherwise, or lists it live,
    /// leaves the refusal's own words. Close is always there.
    private func showReturn(refused end: SessionEnd) async {
        guard case let .workspace(project, workspace) = place, let tickets else { return }
        let listing: Result<RunnerSessions, SailError>
        do {
            listing = .success(try await tickets.sessions(in: project, workspace: workspace))
        } catch {
            listing = .failure(error)
        }
        let close: () -> Void = { [weak self] in MainActor.assumeIsolated { self.map { $0.onClose($0) } } }
        guard let said = RunnerReturn.of(name, listing) else {
            return banner.show(end.said.joined(separator: " "), tone: .warning, button: "Close", handler: close)
        }
        switch said.offer {
        case .openANewOne:
            banner.show(said.said, tone: .warning, button: "Open a New One", handler: { [weak self] in MainActor.assumeIsolated { self?.openAgain() } }, second: "Close", secondHandler: close)
        case .tryAgain:
            banner.show(said.said, tone: .warning, button: "Try Again", handler: { [weak self] in MainActor.assumeIsolated { self?.attachAgain() } }, second: "Close", secondHandler: close)
        case .close:
            banner.show(said.said, tone: .warning, button: "Close", handler: close)
        }
    }

    /// The same session attached again, on a new ticket: Try Again once the runner answers.
    func attachAgain() {
        session?.close()
        session = nil
        begin()
    }

    /// Who holds the keyboard now that the host said `writer` does, `yours` when this client: while
    /// it is elsewhere the pane says so and offers to take it back, Return staying with the terminal;
    /// once it is here again, the line goes.
    private func showKeyboard(_ writer: String, yours: Bool) {
        let wasSaid = keyboard.said
        keyboard.writerChanged(to: writer, yours: yours, me: person)
        refusedSaid = false
        sayKeyboard(wasSaid)
        if keyboard.needsOpener { Task { await learnOpener() } }
    }

    private func sayKeyboard(_ wasSaid: String?) {
        if let said = keyboard.said {
            banner.show(said, button: "Take Keyboard", returns: false) { [weak self] in
                MainActor.assumeIsolated { self?.takeKeyboard() }
            }
        } else if let wasSaid, banner.shown == wasSaid {
            banner.hide()
        }
    }

    /// Who opened this session, from its workspace's listing, so a keyboard no one holds is said to
    /// anyone else, whose typing does not take it.
    private func learnOpener() async {
        guard case let .workspace(project, workspace) = place, let tickets else { return }
        let listing = try? await tickets.sessions(in: project, workspace: workspace)
        let wasSaid = keyboard.said
        if let opener = listing?.sessions.first(where: { $0.name == name })?.opener {
            keyboard.opener = opener
        } else {
            keyboard.openerNotLearned()
        }
        sayKeyboard(wasSaid)
    }

    /// Take Keyboard: asked of the host, or, with no connection attached, said why it cannot be yet.
    private func takeKeyboard() {
        guard session?.takeWrite() != true else { return }
        banner.show("This terminal is not connected, so the keyboard cannot be taken yet; it reconnects on its own.", tone: .warning)
    }

    private func compose() {
        wantsLayer = true
        terminal.frame = bounds
        terminal.autoresizingMask = [.width, .height]
        addSubview(terminal)
        scrim.frame = bounds
        scrim.autoresizingMask = [.width, .height]
        scrim.wantsLayer = true
        scrim.isHidden = true
        addSubview(scrim)
        banner.translatesAutoresizingMaskIntoConstraints = false
        addSubview(banner)
        latency.font = .monospacedDigitSystemFont(ofSize: Look.Step.micro.points, weight: .regular)
        latency.wantsLayer = true
        latency.drawsBackground = true
        paint()
        latency.isBezeled = false
        latency.maximumNumberOfLines = Self.latencyLines
        latency.isHidden = true
        latency.translatesAutoresizingMaskIntoConstraints = false
        addSubview(latency)
        NSLayoutConstraint.activate([
            banner.centerXAnchor.constraint(equalTo: centerXAnchor),
            banner.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Look.Space.x3l),
            banner.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: Look.Space.l),
            latency.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.s),
            latency.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -Look.Space.m),
        ])
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.palette.background).cgColor
        scrim.layer?.backgroundColor = NSColor(look.palette.background).withAlphaComponent(Self.dimming).cgColor
        terminal.recolour(look.palette)
        banner.look = look
        latency.textColor = NSColor(look.textSecondary)
        latency.backgroundColor = NSColor(look.surface)
    }

    private func updateLatency() {
        let figures = terminal.latency
        let hertz = window?.screen?.maximumFramesPerSecond ?? 0
        func ms(_ value: Double) -> String {
            String(format: "%.1f", value)
        }
        latency.stringValue = """
        key → echo on glass  p50 \(ms(figures.keyToEcho.p50))  p95 \(ms(figures.keyToEcho.p95)) ms  (\(figures.keyToEcho.count) keys)
        no display time, at GPU completion: key → echo  p50 \(ms(figures.keyToEchoEstimated.p50))  p95 \(ms(figures.keyToEchoEstimated.p95)) ms  (\(figures.keyToEchoEstimated.count) keys)
        output → on glass  p50 \(ms(figures.outputToPresented.p50))  p95 \(ms(figures.outputToPresented.p95)) ms
        display \(hertz) Hz · \(terminal.drawableCount) drawables · shown by \(figures.reportedPresents > 0 ? "the display's time" : "GPU completion")
        """
    }
}

/// The terminal background at 30% over an unfocused pane; the pointer goes through it to the
/// terminal, whose first click focuses it.
final class Scrim: NSView {
    override func hitTest(_: NSPoint) -> NSView? {
        nil
    }
}
