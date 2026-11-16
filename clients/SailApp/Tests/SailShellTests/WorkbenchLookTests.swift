import AppKit
import Foundation
import SailLook
@testable import SailShell
import SailTerminalView
import SailTestPasskey
import SailWorkbench
import Testing

/// The workbench's controls in the Sail look, never the system's accent (the runner's accent is
/// graphite, so each colour is asserted, not only seen); every command in the menu with the
/// table's key (W8); no pane's view added anywhere but its tab's view (W2, scenario 33).
@MainActor @Suite struct WorkbenchLookTests {
    static func shell(_ look: Look) throws -> ShellApp {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-tabs-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let passkey = SoftwarePasskey(file: folder.appending(path: "passkey.json"))
        return try ShellApp(fonts: LookTests.fonts, helper: nil, look: look) { _ in passkey }
    }

    static func items() -> [TabStrip.Item] {
        [TabStrip.Item(id: TabID(1), title: "api", zoomed: false), TabStrip.Item(id: TabID(2), title: "This Mac", zoomed: true)]
    }

    @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theStripsTabsAreTheLooks(_ name: String, _ look: Look) throws {
        let shell = try Self.shell(look)
        let strip = shell.thisMac.strip
        strip.show(Self.items(), selected: TabID(1))
        let selected = try #require(strip.tabs.first), other = try #require(strip.tabs.last)
        #expect(strip.layer?.backgroundColor == NSColor(look.chrome).cgColor, "\(name): the strip is not chrome")
        #expect(selected.layer?.backgroundColor == NSColor(look.fillSelected).cgColor, "\(name): the selected tab is not fillSelected")
        #expect(selected.label.textColor == NSColor(look.text))
        #expect(other.layer?.backgroundColor == NSColor.clear.cgColor)
        #expect(other.label.textColor == NSColor(look.textSecondary))
        #expect(!selected.close.isHidden && other.close.isHidden, "\(name): the close button shows on the selected tab only")
        other.mouseEntered(with: try #require(NSEvent.otherEvent(
            with: .applicationDefined, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, subtype: 0, data1: 0, data2: 0
        )))
        #expect(other.layer?.backgroundColor == NSColor(look.fillHover).cgColor, "\(name): hover is not fillHover")
        #expect(!other.close.isHidden)
        for button in [selected.close, other.close, strip.add] {
            #expect(!button.isBordered, "\(name): \(button.toolTip ?? "") draws the system's bezel")
            #expect(button.focusRingType == .none, "\(name): \(button.toolTip ?? "") draws the system's focus ring")
            #expect(button.contentTintColor == NSColor(look.textSecondary), "\(name): \(button.toolTip ?? "") is not textSecondary")
            #expect(button.keyEquivalent.isEmpty)
            #expect(button.layer?.borderWidth == 0, "\(name): \(button.toolTip ?? "") is ringed unfocused")
            #expect(button.becomeFirstResponder())
            #expect(button.layer?.borderWidth == 2, "\(name): \(button.toolTip ?? "") shows no keyboard focus")
            #expect(button.layer?.borderColor == NSColor(look.accent).cgColor, "\(name): \(button.toolTip ?? "")'s focus is not the accent")
            #expect(button.resignFirstResponder())
            #expect(button.layer?.borderWidth == 0)
        }
        #expect(selected.layer.map { Double($0.cornerRadius) } == Look.contentRadius)
        #expect(selected.label.font == NSFont.systemFont(ofSize: 13))
        #expect(selected.toolTip == "api")
        #expect(!selected.mouseDownCanMoveWindow && strip.mouseDownCanMoveWindow, "\(name): a tab moves the window, or the strip's empty part does not")
        #expect(strip.accessibilityRole() == .tabGroup)
        #expect(selected.accessibilityRole() == .radioButton)
        #expect(selected.accessibilitySubrole() == TabButton.tabButton)
        var pressed: TabID?
        strip.onSelect = { pressed = $0 }
        #expect(other.accessibilityPerformPress())
        #expect(pressed == TabID(2), "VoiceOver's press selects no tab")
    }

    /// The rename field: no system focus ring, ringed in the accent, its selection `fillSelected`
    /// and its caret the accent.
    @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theRenameFieldIsTheLooks(_ name: String, _ look: Look) throws {
        let shell = try Self.shell(look)
        shell.select(.thisMac)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let strip = shell.thisMac.strip
        strip.show(Self.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        let tab = try #require(strip.tabs.first)
        tab.rename()
        let field = try #require(tab.field)
        let editor = try #require(field.currentEditor() as? NSTextView)
        #expect(field.focusRingType == .none)
        #expect(field.layer?.borderWidth == 2, "\(name): the field is not ringed")
        #expect(field.layer?.borderColor == NSColor(look.accent).cgColor, "\(name): the ring is not the accent")
        #expect(editor.selectedTextAttributes[.backgroundColor] as? NSColor == NSColor(look.fillSelected))
        #expect(editor.insertionPointColor == NSColor(look.accent))
    }

    @Test func theScrimIsTheTerminalBackgroundAt30PercentAndTakesNoClick() throws {
        let shell = try Self.shell(.dark)
        var bench = Workbench()
        let id = bench.newTab(session: "mac-scrim", place: .thisMac(folder: "/"))
        let pane = try TerminalPane(
            try #require(bench.panes[id]), start: .attach, host: shell.thisMac.host, fonts: TerminalFonts(directory: LookTests.fonts), points: 15,
            look: .dark, palette: Look.dark.palette
        )
        let scrim = try #require(pane.subviews.first { $0 is Scrim })
        #expect(scrim.layer?.backgroundColor == NSColor(Look.dark.palette.background).withAlphaComponent(0.3).cgColor)
        #expect(!pane.isDimmed && scrim.isHidden)
        pane.isDimmed = true
        #expect(!scrim.isHidden)
        #expect(scrim.hitTest(NSPoint(x: 10, y: 10)) == nil)
    }

    /// W8: every command is a menu item with the table's key and modifiers.
    @Test func everyCommandHasItsMenuItemAndTheTablesKey() throws {
        let shell = try Self.shell(.dark)
        func items(_ menu: NSMenu) -> [NSMenuItem] {
            menu.items.flatMap { [$0] + ($0.submenu.map(items) ?? []) }
        }
        let all = items(shell.menu())
        func arrow(_ key: Int) -> String {
            UnicodeScalar(UInt16(key)).map { String(Character($0)) } ?? ""
        }
        let command: NSEvent.ModifierFlags = .command
        var table: [(String, String, NSEvent.ModifierFlags)] = [
            ("New Tab", "t", command), ("Split Right", "d", command), ("Split Down", "D", [.command, .shift]), ("Close", "w", command),
            ("Close Tab", "w", [.command, .option]), ("Close Window", "W", [.command, .shift]), ("Cut", "x", command), ("Copy", "c", command),
            ("Paste", "v", command), ("Select All", "a", command), ("Bigger", "+", command), ("Smaller", "-", command), ("Actual Size", "0", command),
            ("Rename Tab", "", command), ("Show Latency", "l", [.command, .option]), ("Minimize", "m", command),
            ("Zoom Split", "\r", [.command, .shift]), ("Select Previous Split", "[", command), ("Select Next Split", "]", command),
            ("Select Split Above", arrow(NSUpArrowFunctionKey), [.command, .option]), ("Select Split Below", arrow(NSDownArrowFunctionKey), [.command, .option]),
            ("Select Split Left", arrow(NSLeftArrowFunctionKey), [.command, .option]), ("Select Split Right", arrow(NSRightArrowFunctionKey), [.command, .option]),
            ("Equalize Splits", "=", [.command, .control]), ("Move Divider Up", arrow(NSUpArrowFunctionKey), [.command, .control]),
            ("Move Divider Down", arrow(NSDownArrowFunctionKey), [.command, .control]), ("Move Divider Left", arrow(NSLeftArrowFunctionKey), [.command, .control]),
            ("Move Divider Right", arrow(NSRightArrowFunctionKey), [.command, .control]), ("Show Previous Tab", "{", [.command, .shift]),
            ("Show Next Tab", "}", [.command, .shift]), ("Select Last Tab", "9", command),
        ]
        for number in 1 ... 8 {
            table.append(("Select Tab \(number)", "\(number)", command))
        }
        for (title, key, modifiers) in table {
            let found = all.filter { $0.title == title }
            #expect(found.contains { $0.keyEquivalent == key && $0.keyEquivalentModifierMask == modifiers }, "\(title) is not in the menu with its key")
        }
        #expect(!all.contains { $0.title.hasSuffix("…") || $0.title.hasSuffix("...") }, "a menu item ends in an ellipsis")
        #expect(!all.contains { $0.title == "Close" && $0.keyEquivalent == "w" && $0.action == #selector(NSWindow.performClose(_:)) }, "⌘W still closes the window")
    }

    /// The workbench takes its own keys before the menu bar: each finds its menu item, shifted
    /// keys by what the keyboard sends (`charactersIgnoringModifiers` "D", "{"), arrows by their
    /// function-key characters; a key of no workbench item finds none.
    @Test func eachShortcutFindsItsMenuItem() throws {
        let shell = try Self.shell(.dark)
        let menu = shell.menu()
        func key(_ characters: String, _ flags: NSEvent.ModifierFlags) throws -> NSEvent {
            try #require(NSEvent.keyEvent(
                with: .keyDown, location: .zero, modifierFlags: flags, timestamp: 0, windowNumber: 0, context: nil, characters: characters,
                charactersIgnoringModifiers: characters, isARepeat: false, keyCode: 0
            ))
        }
        let left = String(Character(try #require(UnicodeScalar(UInt16(NSLeftArrowFunctionKey)))))
        let cases: [(NSEvent, Selector?)] = [
            (try key("d", .command), #selector(WorkbenchView.splitRight(_:))),
            (try key("D", [.command, .shift]), #selector(WorkbenchView.splitDown(_:))),
            (try key(left, [.command, .option, .function, .numericPad]), #selector(WorkbenchView.selectSplit(_:))),
            (try key("\r", [.command, .shift]), #selector(WorkbenchView.zoomSplit(_:))),
            (try key("{", [.command, .shift]), #selector(WorkbenchView.showPreviousTab(_:))),
            (try key("9", .command), #selector(WorkbenchView.selectTab(_:))),
            (try key("c", .command), nil),
            (try key("d", [.command, .option]), nil),
        ]
        for (event, action) in cases {
            let found = WorkbenchView.shortcut(for: event, in: menu, target: shell.thisMac)
            #expect(found?.action == action, "\(event.charactersIgnoringModifiers ?? "") found \(found?.title ?? "nothing")")
        }
    }

    /// A shortcut the workbench takes is validated as the menu would: with no tab, ⌘D does nothing
    /// and is taken (no program gets it); a key no workbench item owns is left to the view and the
    /// menu (⌘K to the program, ⌘C to Edit).
    @Test func aDisabledShortcutDoesNothingAndAnUnownedKeyPassesOn() throws {
        let shell = try Self.shell(.dark)
        NSApp.mainMenu = shell.menu()
        shell.select(.thisMac)
        func key(_ characters: String, _ flags: NSEvent.ModifierFlags) throws -> NSEvent {
            try #require(NSEvent.keyEvent(
                with: .keyDown, location: .zero, modifierFlags: flags, timestamp: 0, windowNumber: 0, context: nil, characters: characters,
                charactersIgnoringModifiers: characters, isARepeat: false, keyCode: 0
            ))
        }
        #expect(shell.thisMac.model.tabs.isEmpty)
        #expect(shell.thisMac.performKeyEquivalent(with: try key("d", .command)), "a disabled ⌘D was passed on")
        #expect(shell.thisMac.model.tabs.isEmpty, "a disabled ⌘D acted")
        #expect(!shell.thisMac.performKeyEquivalent(with: try key("k", .command)), "⌘K, no item's, was taken")
        #expect(!shell.thisMac.performKeyEquivalent(with: try key("c", .command)), "⌘C, Edit's, was taken")
    }

    /// W2 and scenario 33: the workbench adds a pane's view in one place, `TabView.addPane`,
    /// called once, when the pane is made; nothing else it adds is a pane.
    @Test func aPanesViewIsAddedOnlyToItsTabsView() throws {
        let file = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources/SailShell/WorkbenchView.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        let added = source.matches(of: /addSubview\((\w+)/).map { String($0.output.1) }
        #expect(Set(added) == ["room", "empty", "banner", "pane", "divider"], "\(added)")
        #expect(added.filter { $0 == "pane" }.count == 1)
        #expect(source.matches(of: /\.addPane\(/).count == 1, "a pane's view is added again somewhere")
        #expect(source.contains("func addPane(_ pane: TerminalPane) {\n        addSubview(pane, positioned: .below, relativeTo: dividers.first)"))
    }
}
