import AppKit
import SailAPI
import SailLook
import SailSurface
import SailTerminal
import SailTerminalView
import SailTerminalWire
import SailVT
import SailWorkbench

/// One pane: one terminal view drawing one session, made and attached once (W2), on this Mac's host
/// or in a workspace on the runner; only where the session goes and what the banner says differ.
/// The terminal keeps the palette it opened in (a screen's palette is set when it is made).
@MainActor
public final class TerminalPane: NSView {
    public enum Start: Equatable, Sendable {
        case open(folder: String)
        case attach
    }

    /// How much of the terminal background lies over a pane that is not focused (Ghostty's
    /// unfocused split opacity of 0.7).
    public static let dimming = 0.3

    public let id: PaneID
    public let name: String
    public let terminal: TerminalView
    public let host: LocalHost
    public let place: Place
    public let opened = ContinuousClock.now
    public private(set) var session: TerminalSession?
    var onEvent: @MainActor (TerminalPane, SessionEvent) -> Void = { _, _ in }
    /// Asked to show the account, where a runner tab whose sign-in lapsed is signed in again.
    var onSignIn: @MainActor () -> Void = {}
    /// Whether the session was ever attached: a tab refused before then holds none.
    public private(set) var attachedOnce = false
    /// The end under way, nil once it answered.
    private var ending: Task<EndOutcome, Never>?
    /// Whether the last end of this runner session was not confirmed: it may still be running.
    public private(set) var unended = false

    let palette: Palette
    var look: Look {
        didSet { paint() }
    }

    public internal(set) var isDimmed = false {
        didSet { scrim.isHidden = !isDimmed }
    }

    private let start: Start
    private let door: Door
    /// The card saying where the session stands: what a test or the drive reads and presses.
    public let banner: StatusBanner
    private let scrim = Scrim()
    private let latency = NSTextField(labelWithString: "")
    private var latencyTimer: Timer?

    init(
        _ pane: Pane, start: Start, host: LocalHost, runner: (any TicketSource)?, fonts: TerminalFonts, points: Double, look: Look,
        palette: Palette
    ) throws(ShellError) {
        id = pane.id
        name = pane.session
        place = pane.place
        self.start = start
        self.host = host
        if pane.place.onRunner {
            guard let runner else { throw .host("Sign in to open a terminal in a workspace.") }
            door = .runner(runner)
        } else {
            door = .local(host.address)
        }
        self.look = look
        self.palette = palette
        do {
            terminal = try TerminalView(fonts: fonts, points: points)
        } catch {
            throw .resources("the terminal cannot start: \(error)")
        }
        banner = StatusBanner(look: look)
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
            screen = try Screen(size: grid, scrollbackBytes: 10 << 20, palette: palette)
        } catch {
            banner.show(Said.sentence("the terminal screen could not be made: \(error)"))
            return
        }
        let size = TerminalSize(columns: Int32(grid.columns), rows: Int32(grid.rows))
        let (project, workspace) = if case let .workspace(project, workspace) = place { (project, workspace) } else { ("", "") }
        let next = switch start {
        case let .open(folder):
            TerminalSession(
                open: SessionCreate(
                    session: name, command: [], workingDirectory: folder, project: project, room: "", size: size, workspace: workspace
                ),
                door: door, screen: screen
            )
        case .attach:
            TerminalSession(attach: name, write: true, door: door, screen: screen)
        }
        session = next
        banner.show(place.label.map { "Connecting to \($0) on the runner" } ?? "Connecting to this Mac's terminal host")
        terminal.onEvent = { [weak self] event in self?.handle(event) }
        terminal.attach(next)
        next.start()
    }

    /// Ends the session at its host, then this client's part: on this pane's connection when it is
    /// attached to this Mac's host, else on one of its own through the pane's door (a runner's ends
    /// a session only for a connection that holds the write token, and says when it will not),
    /// within `limit` in all, a session the host is still making waited for first
    /// (``TerminalSession/end(within:)``); the task answers what the end came to, nil when nothing
    /// is left to end. An end under way is the one answered, never a second. A local host that
    /// cannot be reached keeps the session, which the next launch opens as a last tab.
    @discardableResult
    func end(within limit: Duration = Door.endLimit) -> Task<EndOutcome, Never>? {
        if let ending { return ending }
        guard let session else { return nil }
        let phase = session.state.phase
        if !place.onRunner, case .attached = phase, (try? session.kill()) != nil {
            detach()
            return nil
        }
        if case .ended = phase, !unended {
            detach()
            return nil
        }
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

    /// Whether this pane holds a live session on the runner, which a quit or a sign-out ends: one
    /// made, or one the runner is still making (a workspace starting), until it ends.
    var holdsRunnerSession: Bool {
        guard place.onRunner, let session else { return false }
        if ending != nil || unended { return true }
        if case .ended = session.state.phase { return false }
        return session.mayBeAtHost
    }

    /// The tab stays while its runner session is ended, saying so.
    func showEnding() {
        banner.show("Ending this terminal on the runner…")
    }

    /// The runner did not confirm the end, said why: the tab stays, offering to end it again, or
    /// to close it anyway, which leaves the session to run on.
    func showUnended(_ why: [String], endAgain: @escaping @MainActor () -> Void, closeAnyway: @escaping @MainActor () -> Void) {
        banner.show(
            EndOutcome.mayStillRun("This terminal", why) + " Close Anyway leaves it there.", button: "End Again", handler: { MainActor.assumeIsolated { endAgain() } }, second: "Close Anyway",
            secondHandler: { MainActor.assumeIsolated { closeAnyway() } }
        )
    }

    /// A new session in the same workspace, on a new ticket, the ended one's screen let go: Open
    /// Again on a runner tab.
    func openAgain() {
        session?.close()
        session = nil
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
    var mayEndAProgram: Bool {
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
            return banner.show(Said.sentence("\(error)"), button: "Try Again") { [weak self] in
                Task { await self?.tryAgain() }
            }
        }
        session?.reconnectNow()
    }

    private func say(_ sentence: String) {
        banner.show(sentence)
    }

    private func handle(_ event: SessionEvent) {
        switch event {
        case .phase(.attached):
            attachedOnce = true
            banner.hide()
        case .phase(.reconnecting) where place.onRunner:
            banner.show("Reconnecting to \(place.label ?? "the runner")…", button: "Reconnect Now") { [weak self] in
                MainActor.assumeIsolated { self?.session?.reconnectNow() }
            }
        case .phase(.reconnecting):
            banner.show("Reconnecting to this Mac's terminal host")
        case let .phase(.parked(reason)) where place.onRunner:
            banner.show(Said.sentence(reason), button: "Reconnect Now") { [weak self] in MainActor.assumeIsolated { self?.session?.reconnectNow() } }
        case let .phase(.parked(reason)):
            banner.show(host.endedBy ?? Said.sentence("this Mac's terminal host does not answer: \(reason)"), button: "Try Again") { [weak self] in
                Task { await self?.tryAgain() }
            }
        case let .phase(.ended(end)) where place.onRunner:
            showEnded(end)
        case let .phase(.ended(end)):
            banner.show(end.said.joined(separator: " "))
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
            banner.show("The runner restarted, which ends every session it held.", button: "Open Again", handler: again)
        case let .ended(reason):
            banner.show(Said.sentence(reason), button: "Open Again", handler: again)
        case let .ticketRefused(refusal) where refusal.code == .known(.notFound) && attachedOnce:
            banner.show("This terminal ended while Sail was away from the runner.", button: "Open Again", handler: again)
        case .signIn:
            banner.show(end.said.joined(separator: " "), button: "Sign In", handler: signIn)
        case let .ticketRefused(refusal) where [.known(.missingBearerToken), .known(.invalidBearerToken)].contains(refusal.code):
            banner.show(end.said.joined(separator: " "), button: "Sign In", handler: signIn)
        default:
            banner.show(end.said.joined(separator: " "))
        }
    }

    private func compose() {
        wantsLayer = true
        layer?.backgroundColor = NSColor(palette.background).cgColor
        terminal.frame = bounds
        terminal.autoresizingMask = [.width, .height]
        addSubview(terminal)
        scrim.frame = bounds
        scrim.autoresizingMask = [.width, .height]
        scrim.wantsLayer = true
        scrim.layer?.backgroundColor = NSColor(palette.background).withAlphaComponent(Self.dimming).cgColor
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
