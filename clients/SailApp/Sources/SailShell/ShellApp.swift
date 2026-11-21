import AppKit
import SailAccount
import SailAPI
import SailLook
import SailTerminal
import SailTerminalView
import SailWorkbench

/// "Sail": one window, 1180 by 800 and no smaller than `leastFrame` (480 by 624), laid out as Mast's shell: one band
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
    /// The card's outline on the chrome, over its edge, taking no clicks; none in light.
    let cardEdge: CardEdge
    /// The menu behind the rail's user button: who is signed in and where, Account, Logins,
    /// Appearance and Sign Out.
    package let userMenu: UserMenu
    /// Where the app keeps what a person chose: the Appearance among it.
    package let defaults: UserDefaults
    public let workspaces: WorkspacesView
    public let thisMac: WorkbenchView
    public private(set) var look: Look

    private let root = NSView()
    private let content = NSView()
    private var appearanceWatch: NSKeyValueObservation?
    private var contrastWatch: (any NSObjectProtocol)?
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
        Self.takeRememberedAppearance(from: .standard)
        try self.init(
            fonts: resources.appending(path: "Fonts"), helper: FileManager.default.isExecutableFile(atPath: helper) ? helper : nil,
            look: Self.look(of: NSApplication.shared.effectiveAppearance), passkeys: passkeys, connect: connect
        )
    }

    /// The look the appearance names, contrasted while the Mac's Increase Contrast is on.
    public static func look(of appearance: NSAppearance, contrast: Bool = NSWorkspace.shared.accessibilityDisplayShouldIncreaseContrast) -> Look {
        let look: Look = appearance.bestMatch(from: [.darkAqua, .aqua]) == .aqua ? .light : .dark
        return contrast ? look.contrasted() : look
    }

    init(
        fonts: URL, helper: String?, look: Look, passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator,
        connect: @escaping @Sendable (ControlPlane) -> any Transport = { URLSessionTransport(base: $0.url) },
        vault: any TokenVault = KeychainVault(), defaults: UserDefaults = .standard
    ) throws(ShellError) {
        self.look = look
        let window = ShellWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1180, height: 800),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView], backing: .buffered, defer: false
        )
        self.window = window
        let title: TitleFont
        let mono: TerminalFonts
        let labels: LabelFont
        do {
            title = try TitleFont(directory: fonts)
            mono = try TerminalFonts(directory: fonts)
            labels = try LabelFont(directory: fonts)
        } catch {
            throw .resources("the app's fonts cannot be read: \(error)")
        }
        thisMac = try WorkbenchView(fonts: fonts, helper: helper, look: look, defaults: defaults)
        account = AccountPane(
            account: Account(authenticator: passkeys { [weak window] in window }, vault: vault, connect: connect), look: look, title: title,
            mono: mono, labels: labels, defaults: defaults
        )
        workspaces = WorkspacesView(look: look, title: title, mono: labels)
        rail = Rail(look: look)
        cardEdge = CardEdge(look: look)
        userMenu = UserMenu(look: look, mono: labels)
        self.defaults = defaults
        super.init()
        compose()
    }

    public func applicationDidFinishLaunching(_: Notification) {
        NSApp.mainMenu = menu()
        appearanceWatch = NSApp.observe(\.effectiveAppearance) { [weak self] application, _ in
            MainActor.assumeIsolated { self?.apply(Self.look(of: application.effectiveAppearance)) }
        }
        contrastWatch = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.apply(Self.look(of: NSApp.effectiveAppearance)) }
        }
        if let visible = (window.screen ?? NSScreen.main)?.visibleFrame { window.setFrame(Self.fitted(window.frame, in: visible), display: false) }
        window.center()
        window.makeKeyAndOrderFront(nil)
        placeTheStrip()
        let remembered = UserDefaults.standard.string(forKey: Self.paneKey).flatMap { Rail.Item(rawValue: $0) } ?? .account
        if remembered == .workspaces { workspaces.waitForSignIn() }
        select(remembered)
        if Bundle.main.bundlePath.contains("/AppTranslocation/") { thisMac.tell(Self.translocated) }
        NSApp.activate(ignoringOtherApps: true)
    }

    public func applicationDidBecomeActive(_: Notification) {
        askWhoIsSignedIn()
    }

    /// Asks the control plane who is signed in, then tells the Workspaces section what it learnt when
    /// no one is: signed out, or the control plane could not be asked, in its words.
    private func askWhoIsSignedIn() {
        Task {
            await account.refresh()
            guard account.standing == nil else { return }
            let lines = account.lines
            if lines.isEmpty { workspaces.signedOut() } else { workspaces.signInUnknown(lines) }
        }
    }

    /// Closing the window keeps the app and every tab, this Mac's and the runner's, as a Mac app
    /// that holds terminals does; the Dock icon brings the window back.
    public func applicationShouldTerminateAfterLastWindowClosed(_: NSApplication) -> Bool {
        false
    }

    public func windowShouldClose(_: NSWindow) -> Bool {
        window.orderOut(nil)
        return false
    }

    public func applicationShouldHandleReopen(_: NSApplication, hasVisibleWindows visible: Bool) -> Bool {
        if !visible { window.makeKeyAndOrderFront(nil) }
        return true
    }

    /// Quitting detaches every terminal, this Mac's and the runner's, and asks nothing: each
    /// session lives on at its host.
    public func applicationWillTerminate(_: Notification) {
        thisMac.detachAll()
    }

    /// Shows `item`'s pane and remembers it for the next launch.
    public func select(_ item: Rail.Item) {
        rail.offer(.workspaces, account.standing != nil || item == .workspaces)
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

    /// Opens the user menu over the rail's user button, or closes it when it is open. Opened by
    /// anything but the pointer (the keys, VoiceOver), the keys go back to the button when it closes.
    @objc public func toggleUserMenu(_: Any?) {
        if userMenu.isOpen { return userMenu.close() }
        guard let button = rail.button(.account) else { return }
        rail.menuOpen = true
        userMenu.open(from: button, saying: account.menuContent, appearance: Appearance.stored(in: defaults), withKeys: !rail.pressedByPointer)
    }

    private func updateMenu() {
        userMenu.update(account.menuContent, appearance: Appearance.stored(in: defaults))
    }

    /// Where the keys rest in the pane shown: This Mac's focused terminal, the Account pane's address.
    private var restingResponder: NSResponder? {
        switch rail.selected {
        case .thisMac?: thisMac.focused?.terminal
        case .account?: account.address
        default: nil
        }
    }

    /// Account (⌘,), from the app's menu or the user menu: the Account pane.
    @objc public func showAccount(_: Any?) {
        userMenu.close()
        select(.account)
    }

    /// The Appearance chosen last, taken before the window shows, so it opens in that look.
    static func takeRememberedAppearance(
        from defaults: UserDefaults, into set: (NSAppearance?) -> Void = { NSApplication.shared.appearance = $0 }
    ) {
        set(Appearance.stored(in: defaults).appearance)
    }

    /// How an Appearance chosen reaches the app: its own appearance, the whole process's; a test
    /// records it instead, so no other test's window changes look under it.
    var setAppearance: @MainActor (NSAppearance?) -> Void = { NSApp.appearance = $0 }

    /// Appearance chosen: kept for the next launch, and the app's from now (Auto, the Mac's, which
    /// the app follows as it changes).
    func choose(_ appearance: Appearance) {
        appearance.store(in: defaults)
        setAppearance(appearance.appearance)
    }

    private func chose(_ item: UserMenuContent.Item) {
        switch item {
        case .account, .signIn: select(.account)
        case .signOut: account.signOutHere(nil)
        case .logins: break
        }
    }

    /// Who is signed in, as the account learnt it: the Workspaces item and the runner's tickets
    /// are theirs, and go with them.
    private func standing(_ signedIn: SignedIn?) {
        let whoami = signedIn.flatMap { try? $0.whoami.get() }
        workspaces.client = account.client()
        workspaces.person = WorkspacesModel.Person(
            handle: whoami?.fde ?? signedIn?.fde ?? "", admin: whoami?.role == .known(.admin), confirmed: whoami != nil,
            controlPlane: signedIn?.controlPlane.url.host() ?? ""
        )
        rail.offer(.workspaces, signedIn != nil || rail.selected == .workspaces)
        thisMac.standingChanged()
        if signedIn == nil {
            workspaces.signedOut()
        } else if rail.selected == .workspaces {
            workspaces.shown()
        }
    }

    /// Who is signed in now and the API as them, which every runner tab asks at each ticket.
    private func signedIn() -> (handle: String, tickets: any TicketSource)? {
        guard let standing = account.standing, let client = account.client() else { return nil }
        return ((try? standing.whoami.get())?.fde ?? standing.fde, client)
    }

    /// Draws the window in `look`: the chrome, the rail and the panes.
    func apply(_ look: Look) {
        self.look = look
        window.backgroundColor = NSColor(look.canvas)
        cardEdge.look = look
        userMenu.look = look
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
    /// The least frame the window takes. AppKit keeps the unified toolbar's row out of `minSize` and
    /// adds it above: a frame asked for at 620 pt with `minSize` 600 was held at 624, the band's 52 pt
    /// less a 28 pt title bar. So `minSize` is this less that row, and the drive and the tests read
    /// this alone.
    public static let leastFrame = NSSize(width: 480, height: 624)
    /// The unified toolbar's row: the band less a plain title bar.
    static var toolbarRow: Double {
        bandHeight - Double(NSWindow.frameRect(forContentRect: .zero, styleMask: [.titled]).height)
    }
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

    /// The band, the rail and the card's margin are one chrome surface: the warm ground the paper card
    /// stands on, outlined in dark.
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
            alert.informativeText = Said.sentence(error.description)
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
        window.minSize = NSSize(width: Self.leastFrame.width, height: Self.leastFrame.height - Self.toolbarRow)
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
        cardEdge.place(around: content, in: root)
        placeTheStrip()
        rail.onSelect = { [weak self] item in
            guard let self else { return }
            if item == .account { toggleUserMenu(nil) } else { select(item) }
        }
        userMenu.onChoose = { [weak self] item in self?.chose(item) }
        userMenu.onAppearance = { [weak self] appearance in self?.choose(appearance) }
        userMenu.onClose = { [weak self] in self?.rail.menuOpen = false }
        account.onStanding = { [weak self] signedIn in
            self?.standing(signedIn)
            self?.updateMenu()
        }
        account.onChecking = { [weak self] in self?.updateMenu() }
        userMenu.fallback = { [weak self] in self?.restingResponder }
        thisMac.signedIn = { [weak self] in self?.signedIn() }
        thisMac.onSignIn = { [weak self] in self?.select(.account) }
        thisMac.startWorkspace = { [weak self] project, name in
            guard let client = self?.account.client() else { return SignIn.signedOut.lines }
            do throws(SailError) {
                _ = try await client.send(.startWorkspace(name, project: project))
                return nil
            } catch let .refused(refusal) {
                return refusal.lines
            } catch {
                return [Said.sentence(error.description)]
            }
        }
        workspaces.onSignIn = { [weak self] in self?.select(.account) }
        workspaces.onAskWhoIsSignedIn = { [weak self] in self?.askWhoIsSignedIn() }
        thisMac.onRunnerTabs = { [weak self] project, name in self?.workspaces.recount(project: project, name: name) }
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
            item("Account", #selector(ShellApp.showAccount(_:)), ",", target: self),
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
            item("Show Latency", #selector(WorkbenchView.toggleLatency(_:)), "l", [.command, .option], target: terminals), .separator(),
            item("Show Sidebar", #selector(ShellApp.toggleSidebar(_:)), "s", [.command, .control], target: self),
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

    /// ⌃⌘S, one View item for what is in front: a workspace's tab shows or hides its files; the
    /// Workspaces section, its projects sidebar.
    @objc func toggleSidebar(_ sender: Any?) {
        switch rail.selected {
        case .thisMac: thisMac.toggleFiles(sender)
        case .workspaces: workspaces.toggleSidebar(sender)
        default: break
        }
    }

    /// What ⌃⌘S says and does in front now: its title, and the true reason when it does nothing.
    var sidebarCommand: (title: String, enabled: Bool, reason: String?) {
        guard window.attachedSheet == nil else { return ("Show Sidebar", false, "A sheet is open.") }
        switch rail.selected {
        case .thisMac: return thisMac.filesCommand
        case .workspaces: return workspaces.sidebarCommand
        default: return ("Show Sidebar", false, "The account has no sidebar.")
        }
    }
}

extension ShellApp: NSMenuItemValidation {
    /// View ▸ ⌃⌘S says what it will do right now, and is off, with its reason, where it does nothing.
    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        guard item.action == #selector(toggleSidebar(_:)) else { return true }
        let command = sidebarCommand
        item.title = command.title
        item.toolTip = command.reason
        return command.enabled
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
