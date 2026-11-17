import AppKit
import SailTerminalView
import SailWorkbench

/// A command of the menu bar that a context menu offers too, defined once (P1): both places make
/// their item from it, with the same action and target, so both are validated alike. A context
/// menu's item carries no key, as Apple's guidelines ask ("Show keyboard shortcuts in your app's
/// main menus, not in context menus").
@MainActor
struct Command {
    let title: String
    let action: Selector
    let key: String
    var modifiers: NSEvent.ModifierFlags = .command

    func item(target: AnyObject?, keyed: Bool = true) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: keyed ? key : "")
        item.keyEquivalentModifierMask = keyed ? modifiers : []
        item.target = target
        return item
    }

    static let copy = Command(title: "Copy", action: #selector(NSText.copy(_:)), key: "c")
    static let paste = Command(title: "Paste", action: #selector(NSText.paste(_:)), key: "v")
    static let selectAll = Command(title: "Select All", action: #selector(NSText.selectAll(_:)), key: "a")
    static let newTab = Command(title: "New Tab", action: #selector(WorkbenchView.newTab(_:)), key: "t")
    static let splitRight = Command(title: "Split Right", action: #selector(WorkbenchView.splitRight(_:)), key: "d")
    static let splitDown = Command(title: "Split Down", action: #selector(WorkbenchView.splitDown(_:)), key: "D", modifiers: [.command, .shift])
    static let zoomSplit = Command(title: "Zoom Split", action: #selector(WorkbenchView.zoomSplit(_:)), key: "\r", modifiers: [.command, .shift])
    static let close = Command(title: "Close", action: #selector(WorkbenchView.closePane(_:)), key: "w")
    static let closeTab = Command(title: "Close Tab", action: #selector(WorkbenchView.closeTab(_:)), key: "w", modifiers: [.command, .option])
    static let closeOtherTabs = Command(title: "Close Other Tabs", action: #selector(WorkbenchView.closeOtherTabs(_:)), key: "")
    static let renameTab = Command(title: "Rename Tab", action: #selector(WorkbenchView.renameTab(_:)), key: "")
}

extension WorkbenchView {
    /// A pane's context menu: the edit commands, then the pane's. A command the workbench would not
    /// do now is left out, not dimmed; Copy and Paste stay, dimmed when they do not apply, the
    /// exception Apple's guidelines make ("Hide unavailable menu items, don't dim them").
    func paneMenu() -> NSMenu {
        menu([
            [Command.copy.item(target: nil, keyed: false), Command.paste.item(target: nil, keyed: false), Command.selectAll.item(target: nil, keyed: false)],
            [Command.splitRight, Command.splitDown, Command.zoomSplit].map { $0.item(target: self, keyed: false) },
            [Command.close.item(target: self, keyed: false)],
        ])
    }

    /// A tab's context menu: each item the menu bar's command, acting on that tab without
    /// selecting it (its id rides on the item), as Safari's and Finder's tab menus do.
    func tabMenu(for tab: TabID) -> NSMenu {
        let rename = Command.renameTab.item(target: self, keyed: false)
        rename.title = "Rename"
        let items = [[rename], [Command.closeTab, Command.closeOtherTabs].map { $0.item(target: self, keyed: false) }]
        items.joined().forEach { $0.representedObject = tab }
        return menu(items + [[Command.newTab.item(target: self, keyed: false)]])
    }

    private func menu(_ groups: [[NSMenuItem]]) -> NSMenu {
        let menu = NSMenu()
        for group in groups.map({ $0.filter { $0.target == nil || validateMenuItem($0) } }) where !group.isEmpty {
            if !menu.items.isEmpty { menu.addItem(.separator()) }
            group.forEach(menu.addItem)
        }
        return menu
    }
}
