import AppKit
import Foundation
import SailLook
import SailVT
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
        strip.showsTabs = true
        strip.show(Self.items(), selected: TabID(1))
        let selected = try #require(strip.tabs.first), other = try #require(strip.tabs.last)
        #expect(strip.layer?.backgroundColor == NSColor(look.chrome).cgColor, "\(name): the strip is not chrome")
        #expect(selected.layer?.backgroundColor == NSColor(look.fillSelected).cgColor, "\(name): the selected tab is not fillSelected")
        #expect(selected.label.textColor == NSColor(look.text))
        #expect(other.layer?.backgroundColor == NSColor.clear.cgColor)
        #expect(other.label.textColor == NSColor(look.textSecondary))
        #expect(selected.close.isHidden && other.close.isHidden, "\(name): a close button shows without the pointer on its tab")
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

    /// A tab's title is centred in the tab, to the point, at three widths, with the close button
    /// shown (the pointer on the tab) and hidden; a title too long for the tab is cut in the middle,
    /// still centred, clear of the close button and the zoom mark.
    @Test(arguments: [100.0, 180.0, 240.0])
    func aTabsTitleIsCentredInTheTab(_ width: Double) throws {
        let hover = try #require(NSEvent.otherEvent(
            with: .applicationDefined, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, subtype: 0, data1: 0, data2: 0
        ))
        for title in ["api", "linuxuser@lightgrid: ~/a/rather/long/working/directory"] {
            let tab = TabButton(id: TabID(1), look: .dark)
            tab.frame = NSRect(x: 0, y: 0, width: width, height: 20)
            tab.show(TabStrip.Item(id: TabID(1), title: title, zoomed: true), selected: true, look: .dark)
            for hovered in [false, true] {
                if hovered { tab.mouseEntered(with: hover) }
                tab.layoutSubtreeIfNeeded()
                let label = tab.label.alignmentRect(forFrame: tab.label.frame)
                #expect(tab.close.isHidden == !hovered)
                #expect(abs(label.midX - tab.bounds.midX) <= 0.5, "\(title) at \(width) pt, hovered \(hovered): the title's middle is \(label.midX), the tab's \(tab.bounds.midX)")
                #expect(label.minX >= tab.close.frame.maxX - 1 && label.maxX <= tab.bounds.maxX - TabButton.titleInset + 1, "\(title) at \(width) pt overlaps the close button or the zoom mark: \(label)")
            }
            #expect(tab.label.lineBreakMode == .byTruncatingMiddle)
        }
    }

    /// Mast's shell: one band across the window's top in the chrome (sampled under the buttons'
    /// inset, in the middle and at the end), the rail's top edge at the band's bottom, the pane's
    /// card beside it, and no view over a window button; in full screen the band is the window's
    /// own top row, the tabs 8 pt in, the rail under it. Both panes, both looks.
    @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theBandRunsAcrossTheTopAndTheRailStartsUnderIt(_ name: String, _ look: Look) throws {
        let shell = try Self.shell(look)
        let window = shell.window
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        for pane in [Rail.Item.thisMac, .account] {
            shell.select(pane)
            for full in [false, true] {
                shell.placeTheStrip(fullScreen: full)
                window.layoutIfNeeded()
                let root = try #require(window.contentView)
                let band = full ? ShellApp.bandHeight : Double(root.bounds.height - window.contentLayoutRect.maxY)
                let context = try #require(Self.drawn(root))
                let pixels = try #require(context.data).bindMemory(to: UInt8.self, capacity: context.width * context.height * 4)
                func isChrome(_ x: Double, _ y: Double) -> Bool {
                    let at = (Int(y * 2) * context.width + Int(x * 2)) * 4
                    let found = [pixels[at], pixels[at + 1], pixels[at + 2]], wanted = [look.chrome.red, look.chrome.green, look.chrome.blue]
                    return zip(found, wanted).allSatisfy { abs(Int($0) - Int($1)) <= 2 }
                }
                for x in [40.0, Double(root.bounds.width) / 2, Double(root.bounds.width) - 6] {
                    #expect(isChrome(x, band / 2), "\(name) \(pane) full \(full): the band at x \(x) is not the chrome")
                }
                #expect(isChrome(Rail.width / 2, band + 4), "\(name) \(pane) full \(full): the rail under the band is not the chrome")
                let railTop = Double(root.bounds.height - shell.rail.frame.maxY)
                #expect(abs(railTop - band) < 0.5, "\(name) \(pane) full \(full): the rail starts at \(railTop), the band ends at \(band)")
                #expect(band >= 44, "\(name): the band is \(band) pt")
                guard !full else {
                    #expect(shell.thisMac.strip.leading == ShellApp.fullScreenInset)
                    continue
                }
                let views: [NSView] = [shell.rail, shell.thisMac, shell.account, shell.thisMac.strip]
                for kind in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
                    let button = try #require(window.standardWindowButton(kind))
                    let frame = button.convert(button.bounds, to: nil)
                    #expect(abs(Double(frame.midY) - (Double(root.bounds.height) - band / 2)) < 1.5, "\(name): \(kind) is not centred in the band")
                    for view in views where view.window != nil && !view.isHiddenOrHasHiddenAncestor {
                        #expect(!view.convert(view.bounds, to: nil).intersects(frame), "\(name) \(pane): \(type(of: view)) lies over \(kind)")
                    }
                }
                let strip = shell.thisMac.strip
                strip.show(Self.items(), selected: TabID(1))
                strip.layoutSubtreeIfNeeded()
                if pane == .thisMac {
                    let firstTab = try #require(strip.tabs.first)
                    let tabStart = Double(firstTab.convert(firstTab.bounds, to: nil).minX)
                    #expect(tabStart >= ShellApp.buttonsInset - 0.5 && tabStart <= ShellApp.buttonsInset + Look.Space.s, "\(name): the first tab starts at \(tabStart), not past the buttons' 96 pt inset")
                }
                let card = try #require(shell.thisMac.superview)
                #expect(card.layer.map { Double($0.cornerRadius) } == Look.contentRadius && card.layer?.masksToBounds == true, "\(name): the pane is not a card with 6 pt corners")
                #expect(abs(Double(card.frame.minX - shell.rail.frame.maxX) - ShellApp.cardMargin) < 0.5 && abs(Double(root.bounds.maxX - card.frame.maxX) - ShellApp.cardMargin) < 0.5
                    && abs(Double(card.frame.minY) - ShellApp.cardMargin) < 0.5, "\(name): the card's chrome margin is not 2 pt: \(card.frame)")
                let row = strip.convert(strip.bounds, to: nil)
                #expect(abs(Double(row.height) - band) < 1 && abs(Double(row.maxX) - Double(root.bounds.width)) < 1,
                        "\(name): the strip \(row) does not fill the band after the buttons (\(band) pt)")
            }
        }
    }

    static func drawn(_ view: NSView) -> CGContext? {
        view.display()
        let width = Int(view.bounds.width) * 2, height = Int(view.bounds.height) * 2
        guard width > 0, height > 0, let layer = view.layer, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.scaleBy(x: 2, y: 2)
        layer.render(in: context)
        return context
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

    /// P1: each command a context menu offers is a menu bar item with the same action and target,
    /// so both are one action validated alike; a context menu's item shows no key (Apple's
    /// guidelines), and with nothing to act on the workbench's commands are left out, not dimmed.
    @Test func everyContextMenuCommandIsTheMenuBarsOwn() throws {
        let shell = try Self.shell(.dark)
        func items(_ menu: NSMenu) -> [NSMenuItem] {
            menu.items.flatMap { [$0] + ($0.submenu.map(items) ?? []) }
        }
        let bar = items(shell.menu())
        let workbench = shell.thisMac
        let offered: [(Command, AnyObject?)] = [
            (.copy, nil), (.paste, nil), (.selectAll, nil), (.splitRight, workbench), (.splitDown, workbench), (.zoomSplit, workbench),
            (.close, workbench), (.renameTab, workbench), (.closeTab, workbench), (.closeOtherTabs, workbench), (.newTab, workbench),
        ]
        for (command, target) in offered {
            let context = command.item(target: target, keyed: false)
            #expect(context.keyEquivalent.isEmpty, "\(command.title) shows a key in a context menu")
            #expect(bar.contains { $0.action == context.action && $0.target === context.target && $0.title == command.title }, "\(command.title) is not the menu bar's own")
        }
        let pane = workbench.paneMenu().items.map { $0.isSeparatorItem ? "-" : $0.title }
        #expect(pane == ["Copy", "Paste", "Select All"], "with no terminal, only the edit commands, dimmed by the responder chain: \(pane)")
        #expect(workbench.tabMenu(for: TabID(1)).items.map(\.title) == ["New Tab"])
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
