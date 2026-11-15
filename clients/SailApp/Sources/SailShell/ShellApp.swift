import AppKit
import SailAccount
import SailAPI

/// "Sail": one window holding the account. It asks the control plane who it is signed in as at
/// launch and each time the app is activated.
@MainActor
public final class ShellApp: NSObject, NSApplicationDelegate, NSWindowDelegate {
    public static let title = "Sail"

    public let window: NSWindow
    public let account: AccountPane

    public init(passkeys: (@escaping @MainActor @Sendable () -> NSWindow?) -> any PasskeyAuthenticator = { PlatformPasskeys(anchor: $0) }) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1180, height: 800), styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false
        )
        self.window = window
        account = AccountPane(account: Account(authenticator: passkeys { [weak window] in window }, vault: KeychainVault()))
        super.init()
        window.title = Self.title
        window.contentMinSize = NSSize(width: 480, height: 600)
        window.delegate = self
        window.isReleasedWhenClosed = false
        window.contentView = account
    }

    public func applicationDidFinishLaunching(_: Notification) {
        NSApp.mainMenu = menu()
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func applicationDidBecomeActive(_: Notification) {
        Task { await account.refresh() }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_: NSApplication) -> Bool {
        true
    }

    /// Runs the app, `configure` given it before it launches.
    public static func run(configure: @MainActor (ShellApp) -> Void = { _ in }) -> Never {
        let application = NSApplication.shared
        application.setActivationPolicy(.regular)
        let shell = ShellApp()
        configure(shell)
        application.delegate = shell
        application.run()
        exit(0)
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
        func item(_ title: String, _ action: Selector, _ key: String, target: AnyObject? = nil) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            item.target = target
            return item
        }
        submenu(Self.title, [
            item("Sign Out", #selector(AccountPane.signOutHere(_:)), "", target: account), .separator(),
            item("Quit \(Self.title)", #selector(NSApplication.terminate(_:)), "q"),
        ])
        submenu("Edit", [
            item("Cut", #selector(NSText.cut(_:)), "x"), item("Copy", #selector(NSText.copy(_:)), "c"),
            item("Paste", #selector(NSText.paste(_:)), "v"), item("Select All", #selector(NSText.selectAll(_:)), "a"),
        ])
        submenu("Window", [item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"), item("Close", #selector(NSWindow.performClose(_:)), "w")])
        return bar
    }
}
