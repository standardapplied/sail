import AppKit
import SailAccount
import SailAPI
import SailLook
import SailTerminalView
import SailWorkbench

/// "Sail": one window, 1180 by 800 and no smaller than 480 by 600, an icon rail beside the pane it
/// selects: "This Mac" (the local terminals, in tabs and splits) or the account. The window's chrome
/// is drawn from the look: a transparent title bar over a full-size content view, so the rail's tint
/// and the pane's surface run to the top edge under the platform's own window buttons, which keep
/// dragging and double-click to zoom; with "This Mac" shown, its tabs fill the title bar's row after
/// those buttons. The look follows the appearance while the app runs. It asks the control plane who
/// it is signed in as at launch and each time the app is activated.
@MainActor
public final class ShellApp: NSObject, NSApplicationDelegate, NSWindowDelegate {
    public static let title = "Sail"
    static let paneKey = "shell.pane"

    public let window: NSWindow
    public let rail: Rail
    public let account: AccountPane
    public let thisMac: WorkbenchView
    public private(set) var look: Look

    private let root = NSView()
    private let content = NSView()
    private var appearanceWatch: NSKeyValueObservation?
    private var stripStart = NSLayoutConstraint()

    /// The app from its bundle: the fonts in `Contents/Resources/Fonts`, the host in
    /// `Contents/Helpers/sail`, the look the appearance names. `passkeys` makes the authenticator,
    /// given the window its sheets show on.
    public convenience init(
        bundle: Bundle, passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator = { PlatformPasskeys(anchor: $0) }
    ) throws(ShellError) {
        guard let resources = bundle.resourceURL else { throw .resources("the app's bundle has no resources folder") }
        let helper = bundle.bundleURL.appending(path: "Contents/Helpers/sail").path
        try self.init(
            fonts: resources.appending(path: "Fonts"), helper: FileManager.default.isExecutableFile(atPath: helper) ? helper : nil,
            look: Self.look(of: NSApplication.shared.effectiveAppearance), passkeys: passkeys
        )
    }

    static func look(of appearance: NSAppearance) -> Look {
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .aqua ? .light : .dark
    }

    init(
        fonts: URL, helper: String?, look: Look, passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator
    ) throws(ShellError) {
        self.look = look
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1180, height: 800),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView], backing: .buffered, defer: false
        )
        self.window = window
        let title: TitleFont
        let mono: TerminalFonts
        do {
            title = try TitleFont(directory: fonts)
            mono = try TerminalFonts(directory: fonts)
        } catch {
            throw .resources("the app's fonts cannot be read: \(error)")
        }
        thisMac = try WorkbenchView(fonts: fonts, helper: helper, look: look)
        account = AccountPane(
            account: Account(authenticator: passkeys { [weak window] in window }, vault: KeychainVault()), look: look, title: title, mono: mono
        )
        rail = Rail(look: look)
        super.init()
        compose()
    }

    public func applicationDidFinishLaunching(_: Notification) {
        NSApp.mainMenu = menu()
        appearanceWatch = NSApp.observe(\.effectiveAppearance) { [weak self] application, _ in
            MainActor.assumeIsolated { self?.apply(Self.look(of: application.effectiveAppearance)) }
        }
        window.center()
        window.makeKeyAndOrderFront(nil)
        placeTheStrip()
        select(UserDefaults.standard.string(forKey: Self.paneKey).flatMap { Rail.Item(rawValue: $0) } ?? .account)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func applicationDidBecomeActive(_: Notification) {
        Task { await account.refresh() }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_: NSApplication) -> Bool {
        true
    }

    /// Quitting detaches every terminal and asks nothing: each session lives on at the host.
    public func applicationWillTerminate(_: Notification) {
        thisMac.detachAll()
    }

    /// Shows `item`'s pane and remembers it for the next launch.
    public func select(_ item: Rail.Item) {
        thisMac.isHidden = item != .thisMac
        thisMac.strip.isHidden = thisMac.isHidden
        account.isHidden = !thisMac.isHidden
        rail.show(item)
        paintStrip()
        UserDefaults.standard.set(item.rawValue, forKey: Self.paneKey)
        if item == .thisMac {
            thisMac.shown()
            thisMac.apply()
        } else {
            window.title = Self.title
            window.makeFirstResponder(account.address)
        }
    }

    /// Draws the window in `look`: the chrome, the rail and the panes.
    func apply(_ look: Look) {
        self.look = look
        window.backgroundColor = NSColor(look.canvas)
        rail.look = look
        account.look = look
        thisMac.look = look
        paintStrip()
    }

    /// The tabs start a space after the window's zoom button, where the window put it.
    private func placeTheStrip() {
        let zoomEnd = Double(window.standardWindowButton(.zoomButton)?.frame.maxX ?? 0)
        stripStart.constant = max(Rail.width, zoomEnd + Look.Space.s)
    }

    /// The title bar's row beside the rail: the tabs' `chrome` for "This Mac", else the pane's surface.
    private func paintStrip() {
        root.layer?.backgroundColor = NSColor(rail.selected == .thisMac ? look.chrome : look.canvas).cgColor
    }

    /// Runs the app, `configure` given it before it launches; a bundle missing its fonts or host
    /// says so in an alert and quits.
    public static func run(
        passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator = { PlatformPasskeys(anchor: $0) },
        configure: @MainActor (ShellApp) -> Void = { _ in }
    ) -> Never {
        let application = NSApplication.shared
        application.setActivationPolicy(.regular)
        let shell: ShellApp
        do {
            shell = try ShellApp(bundle: .main, passkeys: passkeys)
        } catch {
            let alert = NSAlert()
            alert.messageText = "\(title) cannot start"
            alert.informativeText = TerminalPane.sentence(error.description)
            alert.runModal()
            exit(1)
        }
        configure(shell)
        application.delegate = shell
        application.run()
        exit(0)
    }

    private func compose() {
        window.title = Self.title
        window.contentMinSize = NSSize(width: 480, height: 600)
        window.delegate = self
        window.isReleasedWhenClosed = false
        apply(look)
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        root.wantsLayer = true
        paintStrip()
        let strip = thisMac.strip
        stripStart = strip.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: Rail.width)
        for view in [rail, content, strip] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            root.addSubview(view)
        }
        for side in [thisMac, account] as [NSView] {
            side.frame = content.bounds
            side.autoresizingMask = [.width, .height]
            side.isHidden = true
            content.addSubview(side)
        }
        strip.isHidden = true
        window.contentView = root
        let belowTheTitleBar = (window.contentLayoutGuide as? NSLayoutGuide)?.topAnchor ?? root.topAnchor
        NSLayoutConstraint.activate([
            rail.leadingAnchor.constraint(equalTo: root.leadingAnchor), rail.topAnchor.constraint(equalTo: root.topAnchor),
            rail.bottomAnchor.constraint(equalTo: root.bottomAnchor), content.leadingAnchor.constraint(equalTo: rail.trailingAnchor),
            content.trailingAnchor.constraint(equalTo: root.trailingAnchor), content.topAnchor.constraint(equalTo: belowTheTitleBar),
            content.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            stripStart,
            strip.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -Look.Space.s), strip.topAnchor.constraint(equalTo: root.topAnchor),
            strip.bottomAnchor.constraint(equalTo: belowTheTitleBar),
        ])
        rail.onSelect = { [weak self] item in self?.select(item) }
        thisMac.onShow = { [weak self] in
            guard let self, rail.selected != .thisMac else { return }
            select(.thisMac)
        }
        thisMac.onTitle = { [weak self] title in
            guard let self, rail.selected == .thisMac else { return }
            window.title = title
        }
    }

    func menu() -> NSMenu {
        let bar = NSMenu()
        func submenu(_ title: String, _ items: [NSMenuItem]) -> NSMenuItem {
            let menu = NSMenu(title: title)
            items.forEach(menu.addItem)
            let holder = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            holder.submenu = menu
            return holder
        }
        func item(_ title: String, _ action: Selector, _ key: String, _ modifiers: NSEvent.ModifierFlags = .command, target: AnyObject? = nil) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            item.keyEquivalentModifierMask = modifiers
            item.target = target
            return item
        }
        func hidden(_ item: NSMenuItem) -> NSMenuItem {
            item.isHidden = true
            item.allowsKeyEquivalentWhenHidden = true
            return item
        }
        func arrow(_ key: Int) -> String {
            UnicodeScalar(UInt16(key)).map { String(Character($0)) } ?? ""
        }
        let terminals = thisMac
        let tabs = (1 ... 8).map { number in
            let tab = hidden(item("Select Tab \(number)", #selector(WorkbenchView.selectTab(_:)), "\(number)", target: terminals))
            tab.tag = number
            return tab
        }
        let last = hidden(item("Select Last Tab", #selector(WorkbenchView.selectTab(_:)), "9", target: terminals))
        last.tag = 9
        let app: [NSMenuItem] = [
            item("Sign Out", #selector(AccountPane.signOutHere(_:)), "", target: account), .separator(),
            item("Quit \(Self.title)", #selector(NSApplication.terminate(_:)), "q"),
        ]
        let file: [NSMenuItem] = [
            item("New Tab", #selector(WorkbenchView.newTab(_:)), "t", target: terminals), .separator(),
            item("Split Right", #selector(WorkbenchView.splitRight(_:)), "d", target: terminals),
            item("Split Down", #selector(WorkbenchView.splitDown(_:)), "D", [.command, .shift], target: terminals), .separator(),
            item("Close", #selector(WorkbenchView.closePane(_:)), "w", target: terminals),
            item("Close Tab", #selector(WorkbenchView.closeTab(_:)), "w", [.command, .option], target: terminals),
            item("Close Window", #selector(NSWindow.performClose(_:)), "W", [.command, .shift]),
        ]
        let edit: [NSMenuItem] = [
            item("Cut", #selector(NSText.cut(_:)), "x"), item("Copy", #selector(NSText.copy(_:)), "c"),
            item("Paste", #selector(NSText.paste(_:)), "v"), item("Select All", #selector(NSText.selectAll(_:)), "a"),
        ]
        let view: [NSMenuItem] = [
            item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "+"), hidden(item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "=")),
            item("Smaller", #selector(TerminalView.decreaseFontSize(_:)), "-"),
            item("Actual Size", #selector(TerminalView.resetFontSize(_:)), "0"), .separator(),
            item("Rename Tab", #selector(WorkbenchView.renameTab(_:)), "", target: terminals),
            item("Show Latency", #selector(WorkbenchView.toggleLatency(_:)), "l", [.command, .option], target: terminals),
        ]
        func directed(_ title: String, _ action: Selector, _ direction: Direction, _ modifiers: NSEvent.ModifierFlags) -> NSMenuItem {
            let keys: [Direction: Int] = [.left: NSLeftArrowFunctionKey, .right: NSRightArrowFunctionKey, .up: NSUpArrowFunctionKey, .down: NSDownArrowFunctionKey]
            let made = item(title, action, arrow(keys[direction] ?? 0), modifiers, target: terminals)
            made.tag = Direction.allCases.firstIndex(of: direction) ?? 0
            return made
        }
        let select = #selector(WorkbenchView.selectSplit(_:)), move = #selector(WorkbenchView.moveDivider(_:))
        let selectSplit: [NSMenuItem] = [
            directed("Select Split Above", select, .up, [.command, .option]), directed("Select Split Below", select, .down, [.command, .option]),
            directed("Select Split Left", select, .left, [.command, .option]), directed("Select Split Right", select, .right, [.command, .option]),
        ]
        let resizeSplit: [NSMenuItem] = [
            item("Equalize Splits", #selector(WorkbenchView.equalizeSplits(_:)), "=", [.command, .control], target: terminals), .separator(),
            directed("Move Divider Up", move, .up, [.command, .control]), directed("Move Divider Down", move, .down, [.command, .control]),
            directed("Move Divider Left", move, .left, [.command, .control]), directed("Move Divider Right", move, .right, [.command, .control]),
        ]
        var windows: [NSMenuItem] = [
            item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"), .separator(),
            item("Zoom Split", #selector(WorkbenchView.zoomSplit(_:)), "\r", [.command, .shift], target: terminals),
            item("Select Previous Split", #selector(WorkbenchView.selectPreviousSplit(_:)), "[", target: terminals),
            item("Select Next Split", #selector(WorkbenchView.selectNextSplit(_:)), "]", target: terminals),
            submenu("Select Split", selectSplit), submenu("Resize Split", resizeSplit), .separator(),
            item("Show Previous Tab", #selector(WorkbenchView.showPreviousTab(_:)), "{", [.command, .shift], target: terminals),
            item("Show Next Tab", #selector(WorkbenchView.showNextTab(_:)), "}", [.command, .shift], target: terminals),
        ]
        windows += tabs
        windows.append(last)
        bar.addItem(submenu(Self.title, app))
        bar.addItem(submenu("File", file))
        bar.addItem(submenu("Edit", edit))
        bar.addItem(submenu("View", view))
        bar.addItem(submenu("Window", windows))
        return bar
    }
}

public enum ShellError: Error, Equatable, CustomStringConvertible {
    case host(String)
    case resources(String)

    public var description: String {
        switch self {
        case let .host(what), let .resources(what): what
        }
    }
}
