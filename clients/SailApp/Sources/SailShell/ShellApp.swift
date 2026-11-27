import AppKit
import SailAccount
import SailAPI
import SailLook
import SailTerminal
import SailTerminalView
import SailWorkbench

/// "Sail": one window, 1180 by 800 and no smaller than `leastFrame`, laid out as Mast's shell: one
/// band across the window's top in the chrome, the window buttons' home and its drag surface; under
/// it the icon rail on the chrome at the left, and beside the rail the selected pane (Workspaces,
/// or the welcome while no one is signed in; Terminals, the tabs and splits; or the account) as a
/// card with 6 pt corners and a 2 pt margin of chrome. The band is the title bar–toolbar area of a
/// full-size-content window with an empty toolbar in the compact unified style, so AppKit gives it
/// its height and centres the window buttons in it; its row after the buttons is a title bar
/// accessory (`.left`) holding the tab strip, whose empty part is inside the title bar's own view:
/// a drag there moves the window and a double-click does what System Settings says, as AppKit does
/// it. The tabs start `buttonsGap` past where AppKit put the buttons. In full screen, where macOS
/// hides the title bar and the buttons, the toolbar is hidden and the band is drawn in the window's
/// top row, its inset 8 pt. The look follows the appearance while the app runs. It opens on
/// Workspaces at every launch and after every sign-in, and asks the control plane who it is signed
/// in as at launch and each time the app is activated.
@MainActor
public final class ShellApp: NSObject, NSApplicationDelegate, NSWindowDelegate {
    public static let title = "Sail"
    /// Said once a launch when macOS runs the app from the temporary copy it makes of a download.
    static let translocated = "Sail is running from a copy macOS made of the download. Move Sail to Applications and open it from there."
    public let window: NSWindow
    public let rail: Rail
    /// Who is signed in, and every action that changes it.
    public let flow: AccountFlow
    public let account: AccountPane
    /// The signed-out Workspaces card: welcome, enroll, sign in.
    public let welcome: WelcomeView
    /// The card's outline on the chrome, over its edge, taking no clicks; none in light.
    let cardEdge: CardEdge
    /// The menu behind the rail's user button: who is signed in and where, Account, Keys (the
    /// Account view at its keys), Appearance and Sign Out.
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
        flow = AccountFlow(account: Account(authenticator: passkeys { [weak window] in window }, vault: vault, connect: connect), memory: SignInMemory(defaults))
        account = AccountPane(flow: flow, look: look, title: title, mono: mono, labels: labels)
        welcome = WelcomeView(flow: flow, look: look, title: title, mono: labels, version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
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
        land()
        if Bundle.main.bundlePath.contains("/AppTranslocation/") { thisMac.tell(Self.translocated) }
        NSApp.activate(ignoringOtherApps: true)
    }

    /// Where the window opens at every launch: Workspaces, Sail's mark drawing itself while the
    /// session this Mac holds is checked, else the welcome. The check starts here, not at the
    /// app's activation, which a launch may never get.
    func land() {
        if flow.holdsASession(), let host = flow.memory.plane?.named {
            workspaces.waitForSignIn(at: host)
            askWhoIsSignedIn()
        }
        select(.workspaces)
    }

    public func applicationDidBecomeActive(_: Notification) {
        askWhoIsSignedIn()
    }

    /// The check asked last of who is signed in: what a test awaits.
    package private(set) var checking: Task<Void, Never>?

    /// Asks the control plane who is signed in; the flow tells what it learnt, and asks nothing
    /// while a check or a person's action is under way.
    private func askWhoIsSignedIn() {
        checking = Task { await flow.refresh() }
    }

    /// Closing the window keeps the app and every tab, this Mac's and the runner's, as a Mac app
    /// that holds terminals does; the Dock icon brings the window back.
    public func applicationShouldTerminateAfterLastWindowClosed(_: NSApplication) -> Bool {
        false
    }

    public func windowShouldClose(_: NSWindow) -> Bool {
        window.orderOut(nil)
        thisMac.connectFiles(away: true)
        return false
    }

    public func applicationShouldHandleReopen(_: NSApplication, hasVisibleWindows visible: Bool) -> Bool {
        if !visible {
            window.makeKeyAndOrderFront(nil)
            thisMac.connectFiles(away: false)
        }
        return true
    }

    /// Hidden (⌘H), the files are not on screen, as from the Dock; shown again, they are while
    /// the window is up.
    public func applicationDidHide(_: Notification) {
        thisMac.connectFiles(away: true)
    }

    public func applicationDidUnhide(_: Notification) {
        thisMac.connectFiles(away: !window.isVisible)
    }

    /// Answers AppKit's deferred terminate, and asks it again: what the quit question ends in.
    var answerTerminate: @MainActor (Bool) -> Void = { NSApp.reply(toApplicationShouldTerminate: $0) }
    var terminateAgain: @MainActor () -> Void = { NSApp.terminate(nil) }

    /// Quitting asks about each file pane's unsaved text first, Save, Discard or Cancel, one at a
    /// time; a Cancel, a conflict, a refusal or no connection keeps the app open (M52). AppKit's
    /// deferred terminate is answered on every path, or it keeps its run loop in the modal mode.
    public func applicationShouldTerminate(_: NSApplication) -> NSApplication.TerminateReply {
        guard let (tab, pane) = thisMac.unsavedFilePanes.first else { return .terminateNow }
        thisMac.show(tab)
        pane.askToLeave(then: { [weak self] in
            pane.finishClosing()
            self?.answerTerminate(false)
            self?.terminateAgain()
        }, otherwise: { [weak self] in
            self?.answerTerminate(false)
        })
        return .terminateLater
    }

    /// Quitting detaches every terminal, this Mac's and the runner's, and asks nothing: each
    /// session lives on at its host.
    public func applicationWillTerminate(_: Notification) {
        thisMac.detachAll()
    }

    /// Whether Workspaces shows the welcome: no one is signed in and no held session is being
    /// checked.
    var welcoming: Bool {
        flow.standing == nil && !workspaces.asking
    }

    /// Shows `item`'s pane; the account, with no one signed in, is the welcome on Workspaces.
    public func select(_ item: Rail.Item) {
        let item = item == .account && flow.standing == nil ? .workspaces : item
        thisMac.isHidden = item != .terminals
        thisMac.strip.showsTabs = !thisMac.isHidden
        workspaces.isHidden = item != .workspaces || welcoming
        welcome.isHidden = item != .workspaces || !welcoming
        account.isHidden = item != .account
        rail.show(item)
        paintChrome()
        thisMac.connectFiles()
        switch item {
        case .terminals:
            thisMac.shown()
            thisMac.apply()
        case .workspaces:
            window.title = Self.title
            if welcoming { welcome.claimKeys() } else { workspaces.shown() }
        case .account:
            window.title = Self.title
            account.shown()
        }
    }

    /// Opens the user menu over the rail's user button, or closes it when it is open. Opened by
    /// anything but the pointer (the keys, VoiceOver), the keys go back to the button when it closes.
    @objc public func toggleUserMenu(_: Any?) {
        if userMenu.isOpen { return userMenu.close() }
        guard let button = rail.button(.account) else { return }
        rail.menuOpen = true
        userMenu.open(from: button, saying: flow.menuContent, appearance: Appearance.stored(in: defaults), withKeys: !rail.pressedByPointer)
    }

    private func updateMenu() {
        userMenu.update(flow.menuContent, appearance: Appearance.stored(in: defaults))
    }

    /// Where the keys rest in the pane shown: the focused terminal, the welcome's field.
    private var restingResponder: NSResponder? {
        switch rail.selected {
        case .terminals?: thisMac.focused?.terminal
        case .workspaces? where welcoming: welcome.field?.field
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
        case .account: select(.account)
        case .signIn: select(.workspaces)
        case .signOut: account.signOutHere(nil)
        case .keys:
            select(.account)
            account.scrollToKeys()
        }
    }

    /// Who is signed in, as the flow learnt it: Workspaces and the runner's tickets are theirs, and
    /// go with them. Signed out, the welcome shows this Mac's screen; a person's sign-in opens
    /// Workspaces.
    private func standing(_ signedIn: SignedIn?, byAPerson: Bool) {
        workspaces.client = flow.client()
        workspaces.person = WorkspacesModel.Person(signedIn)
        thisMac.standingChanged()
        if signedIn == nil {
            workspaces.signedOut()
            welcome.reset()
        }
        if byAPerson {
            select(.workspaces)
        } else if let shown = rail.selected, shown != .terminals {
            select(shown)
        }
    }

    /// A check found no one, or could not say who: the wait at launch ends.
    private func checked(_ answer: Result<SignedIn?, AccountRefusal>, endedOn: Date?) {
        welcome.checked(answer, endedOn: endedOn)
        guard flow.standing == nil, workspaces.asking else { return }
        workspaces.signedOut()
        if rail.selected == .workspaces { select(.workspaces) }
    }

    /// What a person does on This Mac's page, done in Terminals: a row's Open shows its tab with
    /// that terminal focused, New Shell opens a shell there at once, End Session ends it as Close does.
    private func onThisMac(_ act: LocalProjectView.Act) {
        switch act {
        case let .open(pane):
            thisMac.focus(pane)
            select(.terminals)
        case .newShell: thisMac.newTab(nil)
        case let .end(pane): thisMac.endSession(pane)
        }
    }

    /// Who is signed in now and the API as them, which every runner tab asks at each ticket.
    private func signedIn() -> (handle: String, tickets: any TicketSource)? {
        guard let standing = flow.standing, let client = flow.client() else { return nil }
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
        welcome.look = look
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

    /// A workspace's files are followed only while on screen: not from the Dock, nor once closing.
    public func windowDidMiniaturize(_: Notification) {
        thisMac.connectFiles(away: true)
    }

    public func windowDidDeminiaturize(_: Notification) {
        thisMac.connectFiles(away: false)
    }

    public func windowDidResize(_: Notification) {
        fitTheStrip()
    }

    /// The band's height in full screen, where the band is drawn by the window: the compact unified
    /// title bar–toolbar area's in a window (AppKit sets that one).
    package static let bandHeight = 38.0
    /// The least frame the window takes. AppKit keeps the compact toolbar's row out of `minSize` and
    /// adds it above: with `minSize` 600 a frame asked for at 100 pt is held at 610, the 38 pt band
    /// less a 28 pt title bar. So `minSize` is this less that row, and the drive and the tests read
    /// this alone.
    public static let leastFrame = NSSize(width: 480, height: 610)
    /// The unified toolbar's row: the band less a plain title bar.
    static var toolbarRow: Double {
        bandHeight - Double(NSWindow.frameRect(forContentRect: .zero, styleMask: [.titled]).height)
    }
    /// The band's room between the window buttons and the first tab, wherever AppKit put the buttons.
    package static let buttonsGap = Look.Space.l
    /// The strip's inset in full screen, where macOS hides the buttons.
    static let fullScreenInset = 8.0

    /// Where AppKit put the window's buttons in the band, in window coordinates: the union of their
    /// frames, nil when it shows none.
    package static func buttons(of window: NSWindow) -> NSRect? {
        let frames = [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton].compactMap { kind in
            window.standardWindowButton(kind).flatMap { button in button.isHidden ? nil : button.convert(button.bounds, to: nil) }
        }
        return frames.dropFirst().reduce(frames.first) { $0?.union($1) }
    }
    /// The chrome around the pane's card (Mast's `.cockpit-main` margin).
    static let cardMargin = 2.0

    /// `frame` no larger than `visible` in either direction. AppKit constrains a window's frame to its
    /// screen only vertically (`constrainFrameRect(_:to:)`), so the window's designed 1180 pt is not
    /// left as its frame on a narrower screen.
    static func fitted(_ frame: NSRect, in visible: NSRect) -> NSRect {
        NSRect(origin: frame.origin, size: NSSize(width: min(frame.width, visible.width), height: min(frame.height, visible.height)))
    }

    /// The strip in the title bar's accessory after the window's buttons; in full screen, where the
    /// title bar and its toolbar hide, across the band the window draws at its top, 8 pt in. Its
    /// direction is AppKit's for the title bar (`windowTitlebarLayoutDirection`), the one answer
    /// both where the strip is fitted and which way its tabs run read.
    func placeTheStrip(fullScreen: Bool? = nil) {
        let strip = thisMac.strip
        let full = fullScreen ?? window.styleMask.contains(.fullScreen)
        if let index = window.titlebarAccessoryViewControllers.firstIndex(of: titleBarRow) { window.removeTitlebarAccessoryViewController(at: index) }
        strip.removeFromSuperview()
        NSLayoutConstraint.deactivate(stripInTheWindow)
        strip.userInterfaceLayoutDirection = window.windowTitlebarLayoutDirection
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

    /// The accessory reaches the window's far edge, so all the row beside the buttons is the title
    /// bar's (AppKit sets a `.left` accessory's height, its view's frame its width), its first tab
    /// `buttonsGap` past the buttons, wherever AppKit starts the accessory (9 pt after them on one
    /// Mac, 8 or less on CI's runner). Right to left (the strip's direction, the title bar's) AppKit
    /// puts the buttons at the window's right and the accessory at its left: the strip, whose tabs
    /// then run from its right edge, ends `buttonsGap` before the buttons. A window showing no buttons
    /// leaves the strip as it is.
    private func fitTheStrip() {
        let strip = thisMac.strip
        guard !window.styleMask.contains(.fullScreen), strip.window != nil else { return }
        window.contentView?.superview?.layoutSubtreeIfNeeded()
        guard let buttons = Self.buttons(of: window) else { return }
        let start = Double(strip.convert(NSPoint.zero, to: nil).x)
        let end: Double
        if strip.userInterfaceLayoutDirection == .rightToLeft {
            strip.leading = 0
            end = Double(buttons.minX) - Self.buttonsGap
        } else {
            strip.leading = max(0, Double(buttons.maxX) + Self.buttonsGap - start)
            end = Double(window.frame.width)
        }
        strip.setFrameSize(NSSize(width: max(0, end - start), height: strip.frame.height))
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
        window.toolbarStyle = .unifiedCompact
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
        for side in [thisMac, workspaces, welcome, account] as [NSView] {
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
        flow.onStanding = { [weak self] signedIn, byAPerson in self?.standing(signedIn, byAPerson: byAPerson) }
        flow.onChecked = { [weak self] answer, endedOn in self?.checked(answer, endedOn: endedOn) }
        flow.onChange = { [weak self] in
            self?.account.show()
            self?.updateMenu()
        }
        welcome.onTerminals = { [weak self] in self?.select(.terminals) }
        userMenu.popover.fallback = { [weak self] in self?.restingResponder }
        thisMac.signedIn = { [weak self] in self?.signedIn() }
        thisMac.controlPlane = { [weak self] in self?.workspaces.person.controlPlane ?? "" }
        thisMac.onSignIn = { [weak self] in self?.select(.workspaces) }
        thisMac.startWorkspace = { [weak self] project, name in
            guard let client = self?.flow.client() else { return SignIn.signedOut.lines }
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
        thisMac.onRunnerTabs = { [weak self] project, name in self?.workspaces.recount(project: project, name: name) }
        thisMac.onSessions = { [weak self] in
            guard let self else { return }
            workspaces.sessions = thisMac.terminals
        }
        workspaces.onThisMacShown = { [weak self] in self?.thisMac.restoreOnce() }
        workspaces.local.on = { [weak self] act in self?.onThisMac(act) }
        workspaces.onOpen = { [weak self] project, name in self?.thisMac.openWorkspace(project: project, name: name) }
        workspaces.remembered = { [defaults] person in ModeMemory.stored(in: defaults, for: person) }
        workspaces.memory = workspaces.remembered(workspaces.person)
        workspaces.keep = { [defaults] memory, person in memory.store(in: defaults, for: person) }
        account.useStored = { [weak self] stored, client in await self?.workspaces.useStored(stored, by: client) ?? false }
        workspaces.lastChosen = { [defaults] person in defaults.string(forKey: Self.chosenKey(person)) }
        workspaces.keepChosen = { [defaults] name, person in defaults.set(name, forKey: Self.chosenKey(person)) }
        workspaces.onStart = { [weak self] start, project, name in
            self?.thisMac.startSession(start, project: project, name: name)
        }
        thisMac.onShow = { [weak self] in
            guard let self, rail.selected != .terminals else { return }
            select(.terminals)
        }
        thisMac.onTitle = { [weak self] title in
            guard let self, rail.selected == .terminals else { return }
            window.title = title
        }
    }

    /// Where a person's last project is kept: one person on one control plane, the parts joined by
    /// the unit separator, which neither an address nor a handle holds.
    static func chosenKey(_ person: WorkspacesModel.Person) -> String {
        ["sail.project", person.controlPlane, person.handle].joined(separator: "\u{1F}")
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
            Command.close.item(target: terminals), Command.closeTab.item(target: terminals), Command.closeOtherTabs.item(target: terminals), .separator(),
            item("New File", #selector(FilesPanel.newFile(_:)), "n", [.option, .command]), item("New Folder", #selector(FilesPanel.newFolder(_:)), "N", [.command, .shift]),
            item("Rename", #selector(FilesPanel.renameFile(_:)), FilesPanel.renameKey, []), item("Reveal in Terminal", #selector(FilesPanel.revealInTerminal(_:)), ""),
            item("Delete", #selector(FilesPanel.deleteFiles(_:)), FilesPanel.deleteKey), .separator(),
            item("Save", #selector(FilePane.saveDocument(_:)), "s"),
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
        case .terminals: thisMac.toggleFiles(sender)
        case .workspaces: workspaces.toggleSidebar(sender)
        default: break
        }
    }

    /// What ⌃⌘S says and does in front now: its title, and the true reason when it does nothing.
    var sidebarCommand: (title: String, enabled: Bool, reason: String?) {
        guard window.attachedSheet == nil else { return ("Show Sidebar", false, "A sheet is open.") }
        switch rail.selected {
        case .terminals: return thisMac.filesCommand
        case .workspaces: return welcoming ? ("Show Sidebar", false, "The sidebar lists projects once you sign in.") : workspaces.sidebarCommand
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
