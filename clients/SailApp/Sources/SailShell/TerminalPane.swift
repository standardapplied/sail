import AppKit
import SailLook
import SailSurface
import SailTerminal
import SailTerminalView
import SailTerminalWire
import SailVT

/// "This Mac": one terminal session on this Mac's own host, in the home folder with the login shell,
/// signed in or not. The session's name is remembered; the next launch attaches to it while it
/// lives. ⌥⌘L shows the latency of key to echo on glass.
@MainActor
public final class TerminalPane: NSView {
    static let sessionKey = "terminal.session"

    public let terminal: TerminalView
    public let host: LocalHost
    public private(set) var session: TerminalSession?
    /// Told each time a session attaches.
    public var onAttached: @MainActor (TerminalSession) -> Void = { _ in }
    /// Told when a program names the window.
    public var onTitle: @MainActor (String) -> Void = { _ in }

    private let look: Look
    private let banner: StatusBanner
    private let latency = NSTextField(labelWithString: "")
    private var latencyTimer: Timer?
    private var opened = false

    init(fonts directory: URL, helper: String?, look: Look) throws(ShellError) {
        self.look = look
        do {
            terminal = try TerminalView(fonts: try TerminalFonts(directory: directory))
        } catch {
            throw .resources("the terminal cannot start: \(error)")
        }
        do {
            host = try LocalHost(binary: helper)
        } catch {
            throw .host("this Mac's terminal host has no place to listen: \(error)")
        }
        banner = StatusBanner(look: look)
        super.init(frame: .zero)
        compose()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Opens the session the first time the pane is shown.
    func shown() {
        window?.makeFirstResponder(terminal)
        guard !opened else { return }
        opened = true
        Task { await open(fresh: false) }
    }

    /// Opens the remembered session when it still lives, else a new one.
    public func open(fresh: Bool) async {
        banner.show("Connecting to this Mac's terminal host")
        do {
            try await host.ensureServing()
        } catch {
            banner.show(Self.sentence(error.description), button: "Try Again") { [weak self] in
                Task { await self?.open(fresh: fresh) }
            }
            return
        }
        let remembered = fresh ? nil : UserDefaults.standard.string(forKey: Self.sessionKey)
        let listed = (try? await SessionListing.sessions(at: host.address)) ?? []
        let grid = terminal.metrics.grid(fitting: PixelSize(
            width: Int(Double(terminal.bounds.width) * terminal.metrics.scale),
            height: Int(Double(terminal.bounds.height) * terminal.metrics.scale)
        ))
        let screen: Screen
        do {
            screen = try Screen(size: grid, scrollbackBytes: 10 << 20, palette: look.palette)
        } catch {
            banner.show(Self.sentence("the terminal screen could not be made: \(error)"))
            return
        }
        let next: TerminalSession
        if let remembered, listed.contains(where: { $0.name == remembered && $0.live }) {
            next = TerminalSession(attach: remembered, write: true, door: .local(host.address), screen: screen)
        } else {
            let name = "mac-" + UUID().uuidString.prefix(8).lowercased()
            UserDefaults.standard.set(name, forKey: Self.sessionKey)
            next = TerminalSession(
                open: SessionCreate(
                    session: name, command: [], workingDirectory: NSHomeDirectory(), project: "", room: "",
                    size: TerminalSize(columns: Int32(grid.columns), rows: Int32(grid.rows))
                ),
                door: .local(host.address), screen: screen
            )
        }
        session?.close()
        session = next
        terminal.onEvent = { [weak self] event in self?.handle(event) }
        terminal.attach(next)
        next.start()
    }

    func close() {
        session?.close()
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
            if let session { onAttached(session) }
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
            banner.show(Self.sentence(end.description), button: "Open a New Session") { [weak self] in
                Task { await self?.open(fresh: true) }
            }
        case let .screen(.title(title)):
            onTitle(title)
        default:
            break
        }
    }

    private func compose() {
        wantsLayer = true
        layer?.backgroundColor = NSColor(look.palette.background).cgColor
        terminal.frame = bounds
        terminal.autoresizingMask = [.width, .height]
        addSubview(terminal)
        banner.translatesAutoresizingMaskIntoConstraints = false
        addSubview(banner)
        latency.font = .monospacedDigitSystemFont(ofSize: Look.Step.micro.points, weight: .regular)
        latency.textColor = NSColor(look.textSecondary)
        latency.wantsLayer = true
        latency.drawsBackground = true
        latency.backgroundColor = NSColor(look.surface)
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

    private func updateLatency() {
        let figures = terminal.latency
        let hertz = window?.screen?.maximumFramesPerSecond ?? 0
        func ms(_ value: Double) -> String {
            String(format: "%.1f", value)
        }
        latency.stringValue = """
        key → echo on glass  p50 \(ms(figures.keyToEcho.p50))  p95 \(ms(figures.keyToEcho.p95)) ms  (\(figures.keyToEcho.count) keys)
        estimated, no display time: key → echo  p50 \(ms(figures.keyToEchoEstimated.p50))  p95 \(ms(figures.keyToEchoEstimated.p95)) ms  (\(figures.keyToEchoEstimated.count) keys)
        output → on glass  p50 \(ms(figures.outputToPresented.p50))  p95 \(ms(figures.outputToPresented.p95)) ms
        display \(hertz) Hz · \(terminal.drawableCount) drawables
        """
    }

    /// `text` as a sentence: a capital first, a full stop last.
    static func sentence(_ text: String) -> String {
        let capital = text.prefix(1).uppercased() + text.dropFirst()
        return capital.hasSuffix(".") ? capital : capital + "."
    }
}
