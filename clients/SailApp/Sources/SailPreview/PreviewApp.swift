import AppKit
import SailLook
import SailSurface
import SailTerminal
import SailTerminalView
import SailTerminalWire
import SailVT

/// "Sail Terminal Preview": one window holding one terminal session on this Mac's own host, in the
/// home folder with the login shell. The session's name is remembered; the next launch attaches to
/// it while it lives. ⌥⌘L shows the latency of key to echo on glass.
@MainActor
public final class PreviewApp: NSObject, NSApplicationDelegate, NSWindowDelegate {
    public static let title = "Sail Terminal Preview"
    static let sessionKey = "terminal.session"

    public let window: NSWindow
    public let terminal: TerminalView
    public let host: LocalHost
    public private(set) var session: TerminalSession?
    /// Told each time a session attaches: the drive harness starts on the first.
    public var onAttached: @MainActor (TerminalSession) -> Void = { _ in }

    private let look: Look
    private let banner: StatusBanner
    private let latency = NSTextField(labelWithString: "")
    private var latencyTimer: Timer?

    /// The app from its bundle: the fonts in `Contents/Resources/Fonts`, the host in
    /// `Contents/Helpers/sail`.
    public convenience init(bundle: Bundle) throws(PreviewError) {
        guard let resources = bundle.resourceURL else { throw .resources("the app's bundle has no resources folder") }
        let helper = bundle.bundleURL.appending(path: "Contents/Helpers/sail").path
        try self.init(fonts: resources.appending(path: "Fonts"), helper: FileManager.default.isExecutableFile(atPath: helper) ? helper : nil)
    }

    public init(fonts directory: URL, helper: String?) throws(PreviewError) {
        let dark = NSApplication.shared.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) != .aqua
        look = dark ? .dark : .light
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
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1180, height: 800), styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false
        )
        banner = StatusBanner(look: look)
        super.init()
        compose()
    }

    public func applicationDidFinishLaunching(_: Notification) {
        NSApp.mainMenu = Self.menu(target: self)
        window.center()
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(terminal)
        NSApp.activate(ignoringOtherApps: true)
        Task { await open(fresh: false) }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_: NSApplication) -> Bool {
        true
    }

    public func applicationWillTerminate(_: Notification) {
        session?.close()
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
            let name = "preview-" + UUID().uuidString.prefix(8).lowercased()
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

    @objc func toggleLatency(_: Any?) {
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
            window.title = title.isEmpty ? Self.title : title
        default:
            break
        }
    }

    private func compose() {
        window.title = Self.title
        window.contentMinSize = NSSize(width: 480, height: 600)
        window.delegate = self
        window.isReleasedWhenClosed = false
        window.backgroundColor = NSColor(look.palette.background)
        guard let content = window.contentView else { return }
        terminal.frame = content.bounds
        terminal.autoresizingMask = [.width, .height]
        content.addSubview(terminal)
        banner.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(banner)
        latency.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        latency.textColor = NSColor(look.mutedForeground)
        latency.wantsLayer = true
        latency.drawsBackground = true
        latency.backgroundColor = NSColor(look.surface)
        latency.isBezeled = false
        latency.maximumNumberOfLines = 3
        latency.isHidden = true
        latency.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(latency)
        NSLayoutConstraint.activate([
            banner.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            banner.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -32),
            banner.leadingAnchor.constraint(greaterThanOrEqualTo: content.leadingAnchor, constant: 16),
            latency.topAnchor.constraint(equalTo: content.topAnchor, constant: 8),
            latency.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -12),
        ])
    }

    private func updateLatency() {
        let figures = terminal.latency
        let hertz = window.screen?.maximumFramesPerSecond ?? 0
        func ms(_ value: Double) -> String {
            String(format: "%.1f", value)
        }
        latency.stringValue = """
        key → echo on glass  p50 \(ms(figures.keyToEcho.p50))  p95 \(ms(figures.keyToEcho.p95)) ms  (\(figures.keyToEcho.count) keys)
        output → on glass  p50 \(ms(figures.outputToPresented.p50))  p95 \(ms(figures.outputToPresented.p95)) ms
        display \(hertz) Hz · \(terminal.drawableCount) drawables
        """
        guard figures.keyToEchoEstimated.count > 0 else { return }
        latency.stringValue += """

        estimated, the display gave no time: key → echo  p50 \(ms(figures.keyToEchoEstimated.p50))  p95 \(ms(figures.keyToEchoEstimated.p95)) ms  (\(figures.keyToEchoEstimated.count) keys)
        """
    }

    /// `text` as a sentence: a capital first, a full stop last.
    static func sentence(_ text: String) -> String {
        let capital = text.prefix(1).uppercased() + text.dropFirst()
        return capital.hasSuffix(".") ? capital : capital + "."
    }

    static func menu(target: PreviewApp) -> NSMenu {
        let bar = NSMenu()
        func submenu(_ title: String, _ items: [NSMenuItem]) {
            let menu = NSMenu(title: title)
            for item in items {
                menu.addItem(item)
            }
            let holder = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            holder.submenu = menu
            bar.addItem(holder)
        }
        func item(_ title: String, _ action: Selector, _ key: String, _ modifiers: NSEvent.ModifierFlags = .command) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            item.keyEquivalentModifierMask = modifiers
            return item
        }
        let bigger = item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "=")
        bigger.isHidden = true
        bigger.allowsKeyEquivalentWhenHidden = true
        let latency = item("Show Latency", #selector(PreviewApp.toggleLatency(_:)), "l", [.command, .option])
        latency.target = target
        submenu(title, [item("Quit \(title)", #selector(NSApplication.terminate(_:)), "q")])
        submenu("Edit", [item("Copy", #selector(TerminalView.copy(_:)), "c"), item("Paste", #selector(TerminalView.paste(_:)), "v")])
        submenu("View", [
            item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "+"), bigger,
            item("Smaller", #selector(TerminalView.decreaseFontSize(_:)), "-"),
            item("Actual Size", #selector(TerminalView.resetFontSize(_:)), "0"), .separator(), latency,
        ])
        submenu("Window", [item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"), item("Close", #selector(NSWindow.performClose(_:)), "w")])
        return bar
    }
}
