import AppKit
import SailLook
import SailSurface
import SailTerminal
import SailTerminalView
import SailTerminalWire
import SailVT
import SailWorkbench

/// One pane: one terminal view drawing one session on this Mac's host, made and attached once (W2).
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
    public let opened = ContinuousClock.now
    public private(set) var session: TerminalSession?
    var onEvent: @MainActor (TerminalPane, SessionEvent) -> Void = { _, _ in }

    let palette: Palette
    var look: Look {
        didSet { paint() }
    }

    public internal(set) var isDimmed = false {
        didSet { scrim.isHidden = !isDimmed }
    }

    private let start: Start
    private let banner: StatusBanner
    private let scrim = Scrim()
    private let latency = NSTextField(labelWithString: "")
    private var latencyTimer: Timer?

    init(_ pane: Pane, start: Start, host: LocalHost, fonts: TerminalFonts, points: Double, look: Look, palette: Palette) throws(ShellError) {
        id = pane.id
        name = pane.session
        self.start = start
        self.host = host
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
            banner.show(Self.sentence("the terminal screen could not be made: \(error)"))
            return
        }
        let size = TerminalSize(columns: Int32(grid.columns), rows: Int32(grid.rows))
        let next = switch start {
        case let .open(folder):
            TerminalSession(
                open: SessionCreate(session: name, command: [], workingDirectory: folder, project: "", room: "", size: size),
                door: .local(host.address), screen: screen
            )
        case .attach:
            TerminalSession(attach: name, write: true, door: .local(host.address), screen: screen)
        }
        session = next
        banner.show("Connecting to this Mac's terminal host")
        terminal.onEvent = { [weak self] event in self?.handle(event) }
        terminal.attach(next)
        next.start()
    }

    /// Ends the session at the host, then this client's part: on this pane's connection when it is
    /// attached, else on one of its own once this pane's is closed. A host that cannot be reached
    /// keeps the session, which the next launch opens as a last tab.
    func end() {
        guard let session else { return }
        let phase = session.state.phase
        if case .attached = phase { try? session.kill() }
        detach()
        switch phase {
        case .attached, .ended: return
        default:
            let (name, address) = (session.state.session, host.address)
            Task.detached { try? await SessionListing.end(name, at: address) }
        }
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

    /// What the banner says now, nil when it is hidden.
    public var status: String? {
        banner.shown
    }

    /// The latency overlay's lines, every one shown.
    public static let latencyLines = 4

    /// What the latency overlay says now, nil when it is hidden.
    public var latencyShown: String? {
        latency.isHidden ? nil : latency.stringValue
    }

    private func handle(_ event: SessionEvent) {
        switch event {
        case .phase(.attached):
            banner.hide()
        case .phase(.reconnecting):
            banner.show("Reconnecting to this Mac's terminal host")
        case let .phase(.parked(reason)):
            banner.show(Self.sentence("this Mac's terminal host does not answer: \(reason)"), button: "Try Again") { [weak self] in
                Task {
                    try? await self?.host.ensureServing()
                    self?.session?.reconnectNow()
                }
            }
        case let .phase(.ended(end)):
            banner.show(Self.sentence(end.description))
        default:
            break
        }
        onEvent(self, event)
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

    /// `text` as a sentence: a capital first, a full stop last.
    static func sentence(_ text: String) -> String {
        let capital = text.prefix(1).uppercased() + text.dropFirst()
        return capital.hasSuffix(".") ? capital : capital + "."
    }
}

/// The terminal background at 30% over an unfocused pane; the pointer goes through it to the
/// terminal, whose first click focuses it.
final class Scrim: NSView {
    override func hitTest(_: NSPoint) -> NSView? {
        nil
    }
}
