import AppKit
import SailAccount
import SailAPI
import SailLook
import SailTerminalView
import SailWorkbench

/// "Sail": one window, 1180 by 800 and no smaller than 480 by 600, laid out as Mast's shell: one band
/// across the window's top in the chrome, the window buttons' home and its drag surface; under it
/// the icon rail on the chrome at the left, and beside the rail the selected pane ("This Mac", the
/// local terminals in tabs and splits, or the account) as a card with 6 pt corners and a 2 pt
/// margin of chrome. The band is the title bar–toolbar area of a full-size-content window with an
/// empty toolbar in the unified style, so AppKit gives it its height and centres the window buttons
/// in it; its row after the buttons is a title bar accessory (`.left`) holding the tab strip, whose
/// empty part is inside the title bar's own view: a drag there moves the window and a double-click
/// does what System Settings says, as AppKit does it. The tabs start past the buttons' 96 pt
/// inset. In full screen, where macOS hides the title bar and the buttons, the toolbar is hidden
/// and the band is drawn in the window's top row, its inset 8 pt. The look follows the appearance
/// while the app runs. It asks the control plane who it is signed in as at launch and each time
/// the app is activated.
@MainActor
public final class ShellApp: NSObject, NSApplicationDelegate, NSWindowDelegate {
    public static let title = "Sail"
    /// Said once a launch when macOS runs the app from the temporary copy it makes of a download.
    static let translocated = "Sail is running from a copy macOS made of the download. Move Sail to Applications and open it from there."
    static let paneKey = "shell.pane"

    public let window: NSWindow
    public let rail: Rail
    public let account: AccountPane
    public let workspaces: WorkspacesPane
    public let thisMac: WorkbenchView
    public private(set) var look: Look

    private let root = NSView()
    private let content = NSView()
    private var appearanceWatch: NSKeyValueObservation?
    private let titleBarRow = NSTitlebarAccessoryViewController()
    private let band = NSToolbar(identifier: "sh.standardapplied.sail.band")
    private var stripInTheWindow: [NSLayoutConstraint] = []

    /// The app from its bundle: the fonts in `Contents/Resources/Fonts`, the host in
    /// `Contents/Helpers/sail`, the look the appearance names. `passkeys` makes the authenticator,
    /// given the window its sheets show on.
    public convenience init(
        bundle: Bundle, passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator = { PlatformPasskeys(anchor: $0) },
        connect: @escaping @Sendable (ControlPlane) -> any Transport = { URLSessionTransport(base: $0.url) }
    ) throws(ShellError) {
        guard let resources = bundle.resourceURL else { throw .resources("the app's bundle has no resources folder") }
        let helper = bundle.bundleURL.appending(path: "Contents/Helpers/sail").path
        try self.init(
            fonts: resources.appending(path: "Fonts"), helper: FileManager.default.isExecutableFile(atPath: helper) ? helper : nil,
            look: Self.look(of: NSApplication.shared.effectiveAppearance), passkeys: passkeys, connect: connect
        )
    }

    static func look(of appearance: NSAppearance) -> Look {
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .aqua ? .light : .dark
    }

    init(
        fonts: URL, helper: String?, look: Look, passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator,
        connect: @escaping @Sendable (ControlPlane) -> any Transport = { URLSessionTransport(base: $0.url) }
    ) throws(ShellError) {
        self.look = look
        let window = ShellWindow(
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
            account: Account(authenticator: passkeys { [weak window] in window }, vault: KeychainVault(), connect: connect), look: look, title: title,
            mono: mono
        )
        workspaces = WorkspacesPane(look: look, title: title, mono: mono)
        rail = Rail(look: look)
        super.init()
        compose()
    }

    public func applicationDidFinishLaunching(_: Notification) {
        NSApp.mainMenu = menu()
        appearanceWatch = NSApp.observe(\.effectiveAppearance) { [weak self] application, _ in
            MainActor.assumeIsolated { self?.apply(Self.look(of: application.effectiveAppearance)) }
        }
        if let visible = (window.screen ?? NSScreen.main)?.visibleFrame { window.setFrame(Self.fitted(window.frame, in: visible), display: false) }
        window.center()
        window.makeKeyAndOrderFront(nil)
        placeTheStrip()
        select(UserDefaults.standard.string(forKey: Self.paneKey).flatMap { Rail.Item(rawValue: $0) } ?? .account)
        if Bundle.main.bundlePath.contains("/AppTranslocation/") { thisMac.tell(Self.translocated) }
        NSApp.activate(ignoringOtherApps: true)
    }

    public func applicationDidBecomeActive(_: Notification) {
        Task { await account.refresh() }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_: NSApplication) -> Bool {
        true
    }

    /// Quitting with terminals on the runner asks once, then ends them there (``RunnerQuit``).
    public func applicationShouldTerminate(_: NSApplication) -> NSApplication.TerminateReply {
        RunnerQuit.ask(thisMac.runnerPanes, on: window) { NSApp.reply(toApplicationShouldTerminate: $0) }
    }

    /// Quitting detaches this Mac's terminals and asks nothing: each session lives on at the host.
    public func applicationWillTerminate(_: Notification) {
        thisMac.detachAll()
    }

    /// Shows `item`'s pane and remembers it for the next launch.
    public func select(_ item: Rail.Item) {
        thisMac.isHidden = item != .thisMac
        thisMac.strip.showsTabs = !thisMac.isHidden
        workspaces.isHidden = item != .workspaces
        account.isHidden = item != .account
        rail.show(item)
        paintChrome()
        UserDefaults.standard.set(item.rawValue, forKey: Self.paneKey)
        switch item {
        case .thisMac:
            thisMac.shown()
            thisMac.apply()
        case .workspaces:
            window.title = Self.title
            workspaces.shown()
        case .account:
            window.title = Self.title
            window.makeFirstResponder(account.address)
        }
    }

    /// Who is signed in, as the account learnt it: the Workspaces item and the runner's tickets
    /// are theirs, and go with them.
    private func standing(_ signedIn: SignedIn?) {
        let whoami = signedIn.flatMap { try? $0.whoami.get() }
        let client = account.client()
        workspaces.client = client
        workspaces.person = (whoami?.fde ?? signedIn?.fde ?? "", whoami?.role == .known(.admin))
        thisMac.runner = client
        rail.offer(.workspaces, signedIn != nil)
        if signedIn == nil, rail.selected == .workspaces { select(.account) }
    }

    /// Draws the window in `look`: the chrome, the rail and the panes.
    func apply(_ look: Look) {
        self.look = look
        window.backgroundColor = NSColor(look.canvas)
        rail.look = look
        account.look = look
        workspaces.look = look
        thisMac.look = look
        paintChrome()
    }

    public func windowDidEnterFullScreen(_: Notification) {
        placeTheStrip()
    }

    public func windowDidExitFullScreen(_: Notification) {
        placeTheStrip()
    }

    public func windowDidResize(_: Notification) {
        fitTheStrip()
    }

    /// The band's height in full screen, where the band is drawn by the window: the unified title
    /// bar–toolbar area's in a window (Mast's band is 44 px; AppKit sets this one).
    static let bandHeight = 52.0
    /// What the band keeps clear for the window buttons before the tabs (Mast's `.topbar__inset`),
    /// and in full screen, where macOS hides them.
    static let buttonsInset = 96.0
    static let fullScreenInset = 8.0
    /// The chrome around the pane's card (Mast's `.cockpit-main` margin).
    static let cardMargin = 2.0

    /// `frame` no larger than `visible` in either direction. AppKit constrains a window's frame to its
    /// screen only vertically (`constrainFrameRect(_:to:)`), so the window's designed 1180 pt is not
    /// left as its frame on a narrower screen.
    static func fitted(_ frame: NSRect, in visible: NSRect) -> NSRect {
        NSRect(origin: frame.origin, size: NSSize(width: min(frame.width, visible.width), height: min(frame.height, visible.height)))
    }

    /// The strip in the title bar's accessory after the window's buttons; in full screen, where the
    /// title bar and its toolbar hide, across the band the window draws at its top, 8 pt in.
    func placeTheStrip(fullScreen: Bool? = nil) {
        let strip = thisMac.strip
        let full = fullScreen ?? window.styleMask.contains(.fullScreen)
        if let index = window.titlebarAccessoryViewControllers.firstIndex(of: titleBarRow) { window.removeTitlebarAccessoryViewController(at: index) }
        strip.removeFromSuperview()
        NSLayoutConstraint.deactivate(stripInTheWindow)
        band.isVisible = !full
        if full {
            strip.translatesAutoresizingMaskIntoConstraints = false
            strip.leading = Self.fullScreenInset
            root.addSubview(strip)
            stripInTheWindow = [
                strip.leadingAnchor.constraint(equalTo: root.leadingAnchor), strip.trailingAnchor.constraint(equalTo: root.trailingAnchor),
                strip.topAnchor.constraint(equalTo: root.topAnchor), strip.heightAnchor.constraint(equalToConstant: Self.bandHeight),
            ]
            NSLayoutConstraint.activate(stripInTheWindow)
        } else {
            strip.translatesAutoresizingMaskIntoConstraints = true
            strip.setFrameSize(NSSize(width: 0, height: strip.frame.height))
            titleBarRow.view = strip
            window.addTitlebarAccessoryViewController(titleBarRow)
            fitTheStrip()
        }
    }

    /// The accessory reaches the window's trailing edge, so all the row after the buttons is the
    /// title bar's (AppKit sets a `.left` accessory's height, its view's frame its width).
    private func fitTheStrip() {
        let strip = thisMac.strip
        guard !window.styleMask.contains(.fullScreen), strip.superview != nil else { return }
        window.contentView?.superview?.layoutSubtreeIfNeeded()
        let start = strip.window == nil ? Double(window.standardWindowButton(.zoomButton)?.frame.maxX ?? 0) : Double(strip.convert(NSPoint.zero, to: nil).x)
        strip.leading = max(Look.Space.s, Self.buttonsInset - start)
        strip.setFrameSize(NSSize(width: max(0, Double(window.frame.width) - start), height: strip.frame.height))
    }

    /// The band, the rail and the card's margin are one chrome surface, as Mast's `--chrome-bg`.
    private func paintChrome() {
        root.layer?.backgroundColor = NSColor(look.chrome).cgColor
    }

    /// Runs the app, `configure` given it before it launches; a bundle missing its fonts or host
    /// says so in an alert and quits.
    public static func run(
        passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator = { PlatformPasskeys(anchor: $0) },
        connect: @escaping @Sendable (ControlPlane) -> any Transport = { URLSessionTransport(base: $0.url) },
        configure: @MainActor (ShellApp) -> Void = { _ in }
    ) -> Never {
        let application = NSApplication.shared
        application.setActivationPolicy(.regular)
        let shell: ShellApp
        do {
            shell = try ShellApp(bundle: .main, passkeys: passkeys, connect: connect)
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
        band.allowsUserCustomization = false
        band.allowsDisplayModeCustomization = false
        window.toolbar = band
        window.toolbarStyle = .unified
        window.titlebarSeparatorStyle = .none
        content.wantsLayer = true
        content.layer?.cornerRadius = Look.contentRadius
        content.layer?.masksToBounds = true
        root.wantsLayer = true
        paintChrome()
        titleBarRow.layoutAttribute = .left
        for view in [rail, content] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            root.addSubview(view)
        }
        for side in [thisMac, workspaces, account] as [NSView] {
            side.frame = content.bounds
            side.autoresizingMask = [.width, .height]
            side.isHidden = true
            content.addSubview(side)
        }
        thisMac.strip.showsTabs = false
        window.contentView = root
        let belowTheTitleBar = (window.contentLayoutGuide as? NSLayoutGuide)?.topAnchor ?? root.topAnchor
        let underTheRow = content.topAnchor.constraint(equalTo: belowTheTitleBar)
        underTheRow.priority = .defaultHigh
        NSLayoutConstraint.activate([
            rail.leadingAnchor.constraint(equalTo: root.leadingAnchor), rail.topAnchor.constraint(equalTo: content.topAnchor),
            rail.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: rail.trailingAnchor, constant: Self.cardMargin),
            content.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -Self.cardMargin), underTheRow,
            content.topAnchor.constraint(greaterThanOrEqualTo: root.topAnchor, constant: Self.bandHeight),
            content.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -Self.cardMargin),
        ])
        placeTheStrip()
        rail.onSelect = { [weak self] item in self?.select(item) }
        account.onStanding = { [weak self] signedIn in self?.standing(signedIn) }
        workspaces.onOpen = { [weak self] project, name in
            self?.select(.thisMac)
            self?.thisMac.openWorkspace(project: project, name: name)
        }
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
            Command.newTab.item(target: terminals), .separator(),
            Command.splitRight.item(target: terminals), Command.splitDown.item(target: terminals), .separator(),
            Command.close.item(target: terminals), Command.closeTab.item(target: terminals), Command.closeOtherTabs.item(target: terminals),
            item("Close Window", #selector(NSWindow.performClose(_:)), "W", [.command, .shift]),
        ]
        let edit: [NSMenuItem] = [
            item("Cut", #selector(NSText.cut(_:)), "x"), Command.copy.item(target: nil), Command.paste.item(target: nil), Command.selectAll.item(target: nil),
        ]
        let view: [NSMenuItem] = [
            item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "+"), hidden(item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "=")),
            item("Smaller", #selector(TerminalView.decreaseFontSize(_:)), "-"),
            item("Actual Size", #selector(TerminalView.resetFontSize(_:)), "0"), .separator(),
            Command.renameTab.item(target: terminals),
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
            Command.zoomSplit.item(target: terminals),
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

/// The window, whose toolbar is the band and nothing else: it cannot be hidden (that would leave a
/// 28 pt title bar over a card laid out for the band), and offers no display modes.
final class ShellWindow: NSWindow {
    override func toggleToolbarShown(_: Any?) {}

    override func validateMenuItem(_ item: NSMenuItem) -> Bool {
        item.action == #selector(toggleToolbarShown(_:)) ? false : super.validateMenuItem(item)
    }
}

/// A quit with terminals on the runner open: asked once, since the app cannot attach to them again
/// after a relaunch; then each is ended at the runner through its door and the app told to quit,
/// those that could not be ended named first. Cancel leaves everything as it was.
@MainActor
enum RunnerQuit {
    /// What AppKit is answered now; `reply` is told later whether to quit, when the answer is later.
    static func ask(_ panes: [TerminalPane], on window: NSWindow, reply: @escaping @MainActor (Bool) -> Void) -> NSApplication.TerminateReply {
        guard !panes.isEmpty else { return .terminateNow }
        let terminals = panes.count == 1 ? "1 Runner Terminal" : "\(panes.count) Runner Terminals"
        Confirmation.ask(
            "Quit Sail?", detail: "A terminal on the runner ends when Sail quits; this Mac's own keep running.",
            action: "Quit and End \(terminals)", on: window
        ) { quit in
            guard quit else { return reply(false) }
            Task { @MainActor in
                let unended = await end(panes)
                guard !unended.isEmpty else { return reply(true) }
                say(unended, on: window, then: reply)
            }
        }
        return .terminateLater
    }

    /// Ends every pane's session at the runner and waits for each: the tab and what stopped it, for
    /// each that could not be ended.
    static func end(_ panes: [TerminalPane]) async -> [String] {
        var unended: [String] = []
        for pane in panes {
            let label = pane.place.label ?? pane.name
            if let ending = pane.end(), let error = await ending.value { unended.append("\(label): \(TerminalPane.sentence(error.description))") }
        }
        return unended
    }

    /// The runner terminals a quit could not end, each with what stopped it, then the quit: they run
    /// on until the runner ends them.
    private static func say(_ unended: [String], on window: NSWindow, then reply: @escaping @MainActor (Bool) -> Void) {
        let alert = NSAlert()
        alert.messageText = "Some terminals on the runner could not be ended"
        alert.informativeText = (unended + ["They keep running on the runner."]).joined(separator: "\n")
        alert.addButton(withTitle: "Quit")
        alert.beginSheetModal(for: window) { _ in MainActor.assumeIsolated { reply(true) } }
    }
}
