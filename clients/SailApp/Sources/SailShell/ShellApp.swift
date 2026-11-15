import AppKit
import SailAccount
import SailAPI
import SailLook
import SailTerminalView

/// "Sail": one window, 1180 by 800 and no smaller than 480 by 600, an icon rail beside the pane it
/// selects: "This Mac" (the local terminal) or the account. The window's chrome is drawn from the
/// look: a transparent title bar over a full-size content view, so the rail's tint and the pane's
/// surface run to the top edge under the platform's own window buttons, which keep dragging and
/// double-click to zoom. Text being edited is selected and its caret drawn in the look's accent, by a
/// field editor of the window's own. The look follows the appearance while the app runs. It asks the
/// control plane who it is signed in as at launch and each time the app is activated.
@MainActor
public final class ShellApp: NSObject, NSApplicationDelegate, NSWindowDelegate {
    public static let title = "Sail"
    static let paneKey = "shell.pane"

    public let window: NSWindow
    public let rail: Rail
    public let account: AccountPane
    public let thisMac: TerminalPane
    public private(set) var look: Look

    private let root = NSView()
    private let content = NSView()
    private let fieldEditor = NSTextView()
    private var appearanceWatch: NSKeyValueObservation?

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
        thisMac = try TerminalPane(fonts: fonts, helper: helper, look: look)
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
        select(UserDefaults.standard.string(forKey: Self.paneKey).flatMap { Rail.Item(rawValue: $0) } ?? .account)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func applicationDidBecomeActive(_: Notification) {
        Task { await account.refresh() }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_: NSApplication) -> Bool {
        true
    }

    public func applicationWillTerminate(_: Notification) {
        thisMac.close()
    }

    /// Shows `item`'s pane and remembers it for the next launch.
    public func select(_ item: Rail.Item) {
        let pane: NSView = item == .thisMac ? thisMac : account
        content.subviews.filter { $0 !== pane }.forEach { $0.removeFromSuperview() }
        if pane.superview == nil {
            pane.frame = content.bounds
            pane.autoresizingMask = [.width, .height]
            content.addSubview(pane)
        }
        rail.show(item)
        paintStrip()
        UserDefaults.standard.set(item.rawValue, forKey: Self.paneKey)
        window.title = Self.title
        if item == .thisMac {
            thisMac.shown()
        } else {
            window.makeFirstResponder(account.address)
        }
    }

    /// Draws the window in `look`: the chrome, the rail, the panes and the field editor.
    func apply(_ look: Look) {
        self.look = look
        window.backgroundColor = NSColor(look.canvas)
        rail.look = look
        account.look = look
        thisMac.look = look
        paintStrip()
        fieldEditor.selectedTextAttributes = [.backgroundColor: NSColor(look.fillSelected), .foregroundColor: NSColor(look.text)]
        fieldEditor.insertionPointColor = NSColor(look.accent)
    }

    /// The strip under the title bar beside the rail: the selected pane's surface.
    private func paintStrip() {
        root.layer?.backgroundColor = NSColor(rail.selected == .thisMac ? thisMac.palette.background : look.canvas).cgColor
    }

    public func windowWillReturnFieldEditor(_: NSWindow, to _: Any?) -> Any? {
        fieldEditor
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
        fieldEditor.isFieldEditor = true
        apply(look)
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        root.wantsLayer = true
        paintStrip()
        rail.translatesAutoresizingMaskIntoConstraints = false
        content.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(rail)
        root.addSubview(content)
        window.contentView = root
        let belowTheTitleBar = (window.contentLayoutGuide as? NSLayoutGuide)?.topAnchor ?? root.topAnchor
        NSLayoutConstraint.activate([
            rail.leadingAnchor.constraint(equalTo: root.leadingAnchor), rail.topAnchor.constraint(equalTo: root.topAnchor),
            rail.bottomAnchor.constraint(equalTo: root.bottomAnchor), content.leadingAnchor.constraint(equalTo: rail.trailingAnchor),
            content.trailingAnchor.constraint(equalTo: root.trailingAnchor), content.topAnchor.constraint(equalTo: belowTheTitleBar),
            content.bottomAnchor.constraint(equalTo: root.bottomAnchor),
        ])
        rail.onSelect = { [weak self] item in self?.select(item) }
        thisMac.onTitle = { [weak self] title in
            guard let self, rail.selected == .thisMac else { return }
            window.title = title.isEmpty ? Self.title : title
        }
    }

    private func menu() -> NSMenu {
        let bar = NSMenu()
        func submenu(_ title: String, _ items: [NSMenuItem]) {
            let menu = NSMenu(title: title)
            items.forEach(menu.addItem)
            let holder = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            holder.submenu = menu
            bar.addItem(holder)
        }
        func item(_ title: String, _ action: Selector, _ key: String, _ modifiers: NSEvent.ModifierFlags = .command, target: AnyObject? = nil) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            item.keyEquivalentModifierMask = modifiers
            item.target = target
            return item
        }
        let bigger = item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "=")
        bigger.isHidden = true
        bigger.allowsKeyEquivalentWhenHidden = true
        submenu(Self.title, [
            item("Sign Out", #selector(AccountPane.signOutHere(_:)), "", target: account), .separator(),
            item("Quit \(Self.title)", #selector(NSApplication.terminate(_:)), "q"),
        ])
        submenu("Edit", [
            item("Cut", #selector(NSText.cut(_:)), "x"), item("Copy", #selector(NSText.copy(_:)), "c"),
            item("Paste", #selector(NSText.paste(_:)), "v"), item("Select All", #selector(NSText.selectAll(_:)), "a"),
        ])
        submenu("View", [
            item("Bigger", #selector(TerminalView.increaseFontSize(_:)), "+"), bigger,
            item("Smaller", #selector(TerminalView.decreaseFontSize(_:)), "-"),
            item("Actual Size", #selector(TerminalView.resetFontSize(_:)), "0"), .separator(),
            item("Show Latency", #selector(TerminalPane.toggleLatency(_:)), "l", [.command, .option], target: thisMac),
        ])
        submenu("Window", [item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"), item("Close", #selector(NSWindow.performClose(_:)), "w")])
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
