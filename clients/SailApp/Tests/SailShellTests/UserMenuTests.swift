import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
import SailVT
@testable import SailShell
import Synchronization
import Testing

/// The user menu behind the rail's user button (the calm spec's PR 2, in Paper and ink): where it
/// sits at every window size and in either direction, what it says, how the keys and the pointer
/// work it, what each item does, Appearance remembered and taken, what VoiceOver hears, and that it
/// adds no accent and no grey to the window it opens over. No test here runs a real event loop (a
/// posted event, a button's own mouse tracking): with no `NSApp.run` under a test, that stopped the
/// main run loop and so ended the suite's process; real clicks are the drive's. No test here holds
/// what is the whole process's, so it passes beside any other suite in one process: each shell's
/// menu is told its window gave up the keys by a notification of the test's own, not the real key
/// window, and its Appearance is set through a recorder, not `NSApp.appearance`.
@MainActor @Suite struct UserMenuTests {
    init() {
        ExitWatch.install()
    }

    static let uday = UserMenuContent.signedIn(
        handle: "uday", address: "main.acme.dev", whoami: Whoami(fde: "uday", displayName: "Uday Chandra", role: .known(.admin), capabilities: []),
        unreachable: false, refreshing: false
    )
    static let long = UserMenuContent.signedIn(
        handle: String(repeating: "k", count: 63), address: String(repeating: "h", count: 52) + ".acme.dev", whoami: nil, unreachable: true, refreshing: false
    )
    /// The window's sizes: the least, and widths and heights from narrow to wide.
    static let sizes: [NSSize] = [ShellApp.leastFrame, NSSize(width: 720, height: 624), NSSize(width: 1024, height: 768), NSSize(width: 1180, height: 800), NSSize(width: 1440, height: 900)]

    static func shell(_ look: Look = .light) throws -> ShellApp {
        let shell = try WorkbenchLookTests.shell(look)
        shell.window.orderFront(nil)
        own(shell)
        return shell
    }

    /// What each test's shell set as the app's appearance, in place of the process's own.
    static var appearances: [ObjectIdentifier: NSAppearance.Name?] = [:]

    /// `shell` told it gave up the keys by a notification of its own, and its Appearance recorded.
    static func own(_ shell: ShellApp) {
        shell.userMenu.popover.keysLeft = Notification.Name("sail.tests.keys-left.\(UUID().uuidString)")
        let id = ObjectIdentifier(shell)
        shell.setAppearance = { Self.appearances[id] = .some($0?.name) }
    }

    /// The appearance `shell` last set, nil for the Mac's own (or none set).
    static func appearance(of shell: ShellApp) -> NSAppearance.Name? {
        appearances[ObjectIdentifier(shell)] ?? nil
    }

    static func open(_ shell: ShellApp, _ content: UserMenuContent = uday, withKeys: Bool = false) throws {
        let button = try #require(shell.rail.button(.account))
        shell.rail.menuOpen = true
        shell.userMenu.open(from: button, saying: content, appearance: Appearance.stored(in: shell.defaults), withKeys: withKeys)
    }

    static func key(_ code: UInt16, _ characters: String) throws -> NSEvent {
        try #require(NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, characters: characters,
            charactersIgnoringModifiers: characters, isARepeat: false, keyCode: code
        ))
    }

    static let down: UInt16 = 125, up: UInt16 = 126, left: UInt16 = 123, right: UInt16 = 124, enter: UInt16 = 36, escape: UInt16 = 53

    static func press(_ code: UInt16, in shell: ShellApp) throws {
        shell.window.sendEvent(try key(code, code == enter ? "\r" : code == escape ? "\u{1b}" : " "))
    }

    static func highlighted(_ view: UserMenuView) -> String {
        guard let index = view.highlighted else { return "nothing" }
        return index == view.items.count ? "Appearance" : view.items[index].item.rawValue
    }

    /// At every window size and in either direction the menu is 260 pt wide, its leading edge on the
    /// button's, its foot 8 pt above the button's top, the shadow's room about it; a child of the
    /// window while open, gone when closed. Left to right, where the button is where the app puts it,
    /// the menu is inside the window; right to left only the button is turned here, still at the
    /// window's left, so the kit-rtl drive's frame shows it inside a right-to-left window.
    @Test(arguments: [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft])
    func theMenuSitsOnItsButtonAtEverySize(_ direction: NSUserInterfaceLayoutDirection) throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        let button = try #require(shell.rail.button(.account))
        button.userInterfaceLayoutDirection = direction
        for size in Self.sizes {
            shell.window.setFrame(NSRect(origin: shell.window.frame.origin, size: size), display: true)
            shell.window.layoutIfNeeded()
            for content in [Self.uday, .signedOut(lastUsed: nil), Self.long] {
                try Self.open(shell, content)
                let box = shell.userMenu.box, menu = shell.userMenu.view
                let square = shell.window.convertToScreen(button.convert(button.alignmentRect(forFrame: button.bounds), to: nil))
                let said = "\(size.width)×\(size.height), \(content.name.prefix(12)): the menu at \(box), its button at \(square), the window \(shell.window.frame)"
                #expect(near(box.width, UserMenu.width) && near(box.height, menu.fittingSize.height), "\(said)")
                let leading = direction == .rightToLeft ? near(box.maxX, square.maxX) : near(box.minX, square.minX)
                #expect(leading && near(box.minY, square.maxY + UserMenu.gap), "\(said)")
                if direction == .leftToRight { #expect(shell.window.frame.insetBy(dx: -0.5, dy: -0.5).contains(box), "\(said): out of the window") }
                #expect(near(shell.userMenu.popover.panel.frame.width, box.width + 2 * Popover.room) && shell.window.childWindows?.contains(shell.userMenu.popover.panel) == true, "\(said)")
                #expect(near(menu.frame.width, UserMenu.width) && menu.userInterfaceLayoutDirection == direction, "\(said): the box is laid out \(menu.frame)")
                // Each opening makes its rows anew; a click must find them laid out at once, the
                // first opening (which draws the panel at once) or any later one.
                let unlaid = menu.items.filter { !near($0.frame.height, UserMenuView.row) }.map(\.item.rawValue)
                #expect(unlaid.isEmpty, "\(said): \(unlaid) not laid out as the menu opens")
                shell.userMenu.close()
                #expect(!shell.userMenu.popover.panel.isVisible && shell.window.childWindows?.contains(shell.userMenu.popover.panel) != true, "\(said): still shown closed")
            }
        }
    }

    /// The mockup's measures: identity 14 pt in with a 32 pt disc and the name in 13 semibold over
    /// `@handle · Role` in mono 11; the control plane's line with its state square and word; 28 pt
    /// items 6 pt in; the 36 pt Appearance row; Sign Out in a section of its own. Signed out, only
    /// "Not signed in", Sign In and Appearance.
    @Test(arguments: [Look.light, .dark])
    func theMenuSaysWhoWhereAndWhat(_ look: Look) throws {
        let shell = try Self.shell(look)
        defer { shell.window.orderOut(nil) }
        try Self.open(shell)
        let view = shell.userMenu.view
        let labels = WorkspacesViewTests.found(NSTextField.self, in: view).filter { !$0.isHiddenOrHasHiddenAncestor }.map(\.stringValue)
        #expect(labels == ["Uday Chandra", "@uday · Admin", "main.acme.dev", "Connected", "Appearance"], "the menu says \(labels)")
        #expect(view.items.map(\.item) == [.account, .keys, .signOut] && view.items.allSatisfy { near($0.frame.height, UserMenuView.row) && near($0.frame.minX, 6) })
        #expect(near(view.avatarDisc.width, 32) && near(view.avatarDisc.minX, 16) && view.planeInk == Tint(look.success))
        for words in ["Connected", "Reconnecting", "Can’t reach"] {
            let label = try #require(WorkspacesViewTests.found(NSTextField.self, in: view).first { $0.stringValue == "Connected" || $0.stringValue == "Reconnecting" || $0.stringValue == "Can’t reach" })
            let plane: UserMenuContent.Plane = words == "Connected" ? .connected : words == "Reconnecting" ? .reconnecting : .unreachable
            shell.userMenu.update(.signedIn(handle: "uday", address: "main.acme.dev", whoami: nil, unreachable: plane != .connected, refreshing: plane == .reconnecting), appearance: .auto)
            view.layoutSubtreeIfNeeded()
            let needs = ceil(Double(label.attributedStringValue.size().width)) + 4
            #expect(label.stringValue == words && needs <= Double(label.frame.width) + 0.5, "\(words) needs \(needs) pt and has \(label.frame.width): cut")
        }
        shell.userMenu.update(Self.uday, appearance: .auto)
        view.layoutSubtreeIfNeeded()
        let signOut = try #require(view.items.last), logins = try #require(view.items.first { $0.item == .keys })
        #expect(signOut.frame.minY - logins.frame.maxY > UserMenuView.appearanceRow, "Sign Out is not in a section apart: \(signOut.frame) after \(logins.frame)")
        shell.userMenu.update(.signedOut(lastUsed: nil), appearance: .auto)
        view.layoutSubtreeIfNeeded()
        let out = WorkspacesViewTests.found(NSTextField.self, in: view).filter { !$0.isHiddenOrHasHiddenAncestor }.map(\.stringValue)
        #expect(out == ["Not signed in", "no control plane yet", "Appearance"] && view.items.map(\.item) == [.signIn], "signed out, the menu says \(out)")
        #expect(view.content.disc == .person && near(view.avatarDisc.width, 32), "signed out, no person's disc")
        shell.userMenu.update(Self.long, appearance: .auto)
        #expect(view.planeInk == Tint(look.error), "out of reach, the square is not the error's")
    }

    /// Opened by the keys the first item is highlighted, by the pointer none (as a Mac menu); ↓, ↑
    /// and Tab walk the items that can be chosen and Appearance, round, and Tab never leaves the
    /// menu; ← and → choose the Appearance on it; Return chooses; Escape closes and the keys go back
    /// to the button when the keys opened it, else where they were.
    @Test func theKeysWorkTheMenu() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        let view = shell.userMenu.view, button = try #require(shell.rail.button(.account))
        let had = Popover.holder(of: shell.window.firstResponder)
        try Self.open(shell, withKeys: true)
        var walked = [Self.highlighted(view)]
        for code in [Self.down, Self.down, Self.down, Self.up] {
            try Self.press(code, in: shell)
            walked.append(Self.highlighted(view))
        }
        #expect(walked == ["Account", "Appearance", "Sign Out", "Account", "Sign Out"], "the keys walk \(walked), not top to bottom")
        shell.window.sendEvent(try Self.key(48, "\t"))
        #expect(Self.highlighted(view) == "Account" && shell.userMenu.isOpen && shell.window.firstResponder is MenuKeys, "Tab left the menu, or moved to \(Self.highlighted(view))")
        try Self.press(Self.down, in: shell)
        try Self.press(Self.left, in: shell)
        #expect(view.chosenLook == .dark && Appearance.stored(in: shell.defaults) == .dark && Self.appearance(of: shell) == .darkAqua && shell.userMenu.isOpen)
        try Self.press(Self.right, in: shell)
        try Self.press(Self.right, in: shell)
        #expect(view.chosenLook == .auto && Self.appearance(of: shell) == nil, "→ twice from Dark is \(view.chosenLook)")
        try Self.press(Self.escape, in: shell)
        // The rail's button takes the keys on this Mac with Keyboard Navigation off as well (CI's
        // runner gave them to it); where it refuses them, they go back where they were.
        let back: NSResponder? = button.acceptsFirstResponder ? button : had
        #expect(!shell.userMenu.isOpen && !shell.rail.menuOpen && Popover.holder(of: shell.window.firstResponder) === back,
                "Escape left the keys on \(String(describing: shell.window.firstResponder)), not \(String(describing: back))")
        try Self.open(shell, withKeys: true)
        try Self.press(Self.enter, in: shell)
        #expect(!shell.userMenu.isOpen && shell.rail.selected == .account, "Return on Account showed \(shell.rail.selected?.rawValue ?? "nothing")")
        let field = shell.account.address
        shell.window.makeFirstResponder(field)
        let before = Popover.holder(of: shell.window.firstResponder)
        try Self.open(shell)
        #expect(Self.highlighted(view) == "nothing", "opened with the pointer, \(Self.highlighted(view)) is highlighted")
        try Self.press(Self.escape, in: shell)
        #expect(Popover.holder(of: shell.window.firstResponder) === before && before === field, "the keys went to \(String(describing: shell.window.firstResponder))")
    }

    /// The button opens the menu and, again, closes it; a click outside it closes it, as does the
    /// window giving up the keys; Keys, which cannot be chosen, says why when clicked or pointed
    /// at and leaves the menu open; Account shows the Account pane; an Appearance leaves it open.
    @Test func thePointerWorksTheMenu() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        let button = try #require(shell.rail.button(.account)), view = shell.userMenu.view
        button.performClick(nil)
        #expect(shell.userMenu.isOpen && shell.rail.menuOpen, "the button did not open the menu")
        button.performClick(nil)
        #expect(!shell.userMenu.isOpen && !shell.rail.menuOpen, "the button again did not close it")
        try Self.open(shell)
        let rail = shell.rail, at = rail.convert(NSPoint(x: rail.bounds.midX, y: rail.bounds.midY), to: nil)
        let outside = try #require(NSEvent.mouseEvent(
            with: .leftMouseDown, location: at, modifierFlags: [], timestamp: 0, windowNumber: shell.window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1
        ))
        shell.userMenu.popover.clickedAnywhere(outside)
        #expect(!shell.userMenu.isOpen, "a click outside left it open")
        try Self.open(shell)
        NotificationCenter.default.post(name: shell.userMenu.popover.keysLeft, object: shell.window)
        #expect(!shell.userMenu.isOpen, "the window giving up the keys left it open")
        try Self.open(shell)
        let logins = try #require(view.items.first { $0.item == .keys })
        #expect(view.tip.text == "Keys come in a later version of Sail." && view.tip.anchor === logins && !view.tip.isShown, "the Keys tooltip says \(view.tip.text)")
        logins.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: logins.bounds.midX, y: logins.bounds.midY), in: logins))
        #expect(shell.userMenu.isOpen && view.tip.isShown && logins.accessibilityHelp() == UserMenuContent.Item.keys.why, "Keys did not say why")
        #expect(logins.drawn.fill == nil && logins.drawn.ink == shell.look.textTertiary, "Keys is drawn as one that can be chosen")
        let account = try #require(view.items.first { $0.item == .account })
        account.mouseEntered(with: try Self.mouse(.mouseMoved, at: .zero, in: account))
        #expect(!view.tip.isShown && account.highlighted && account.drawn.fill == shell.look.menuHighlight.fill, "pointing at Account did not highlight it")
        let dark = view.segments.rects[1]
        view.segments.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: dark.midX, y: dark.midY), in: view.segments))
        #expect(shell.userMenu.isOpen && Appearance.stored(in: shell.defaults) == .dark && Self.appearance(of: shell) == .darkAqua)
        account.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: account.bounds.midX, y: account.bounds.midY), in: account))
        #expect(!shell.userMenu.isOpen && shell.rail.selected == .account, "Account showed \(shell.rail.selected?.rawValue ?? "nothing")")
        shell.choose(.auto)
    }

    /// Who is signed in learnt while an item is pressed (the drive's step at launch): the item stays
    /// until it is let go, the click chooses it, and the menu then says what was learnt; a press
    /// let go outside it chooses nothing and the menu says it at once.
    @Test func anItemIsNeverTakenFromUnderAPress() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        let view = shell.userMenu.view
        try Self.open(shell, .signedOut(lastUsed: nil))
        var signIn = try #require(view.items.first)
        signIn.mouseDown(with: try Self.mouse(.leftMouseDown, at: NSPoint(x: signIn.bounds.midX, y: signIn.bounds.midY), in: signIn))
        shell.userMenu.update(Self.uday, appearance: .auto)
        #expect(view.items.map(\.item) == [.signIn] && view.items.first === signIn, "the pressed Sign In was taken: \(view.items.map(\.item))")
        signIn.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: signIn.bounds.midX, y: signIn.bounds.midY), in: signIn))
        #expect(!shell.userMenu.isOpen && shell.rail.selected == .account, "the click on Sign In chose nothing")
        try Self.open(shell, .signedOut(lastUsed: nil))
        signIn = try #require(view.items.first)
        signIn.mouseDown(with: try Self.mouse(.leftMouseDown, at: NSPoint(x: signIn.bounds.midX, y: signIn.bounds.midY), in: signIn))
        shell.userMenu.update(Self.uday, appearance: .auto)
        signIn.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: -10, y: -10), in: signIn))
        #expect(shell.userMenu.isOpen && view.items.map(\.item) == [.account, .keys, .signOut], "let go outside, the menu says \(view.items.map(\.item))")
        #expect(near(shell.userMenu.box.height, view.fittingSize.height) && view.items.allSatisfy { near($0.frame.height, UserMenuView.row) }, "the box was not sized again")
        shell.userMenu.close()
    }

    static func mouse(_ type: NSEvent.EventType, at point: NSPoint, in view: NSView) throws -> NSEvent {
        try #require(NSEvent.mouseEvent(
            with: type, location: view.convert(point, to: nil), modifierFlags: [], timestamp: 0, windowNumber: view.window?.windowNumber ?? 0, context: nil,
            eventNumber: 0, clickCount: 1, pressure: 0
        ))
    }

    /// Appearance is remembered and taken: Light and Dark set the app's own, Auto leaves the Mac's
    /// (the app follows it as it changes); the one kept is taken at the next launch, Auto when none.
    @Test func appearanceIsRememberedAndTaken() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        #expect(Appearance.stored(in: shell.defaults) == .auto, "a first launch is not Auto")
        var taken: NSAppearance.Name?? = .none
        for (appearance, name) in [(Appearance.dark, NSAppearance.Name.darkAqua), (.light, .aqua)] {
            shell.choose(appearance)
            #expect(Self.appearance(of: shell) == name && Appearance.stored(in: shell.defaults) == appearance)
            ShellApp.takeRememberedAppearance(from: shell.defaults) { taken = .some($0?.name) }
            #expect(taken == .some(name), "\(appearance) was not taken at launch")
        }
        shell.choose(.auto)
        ShellApp.takeRememberedAppearance(from: shell.defaults) { taken = .some($0?.name) }
        #expect(taken == .some(nil) && Self.appearance(of: shell) == nil && Appearance.stored(in: shell.defaults) == .auto, "Auto set an appearance of the app's own")
    }

    /// VoiceOver hears a menu named Account, its items as menu items by their titles (Keys
    /// dimmed, saying why), and Appearance as a group of three choices, the chosen one on; pressing
    /// Account by VoiceOver chooses it.
    /// The window giving up the keys closes the menu, by the window's own notice: the one test whose
    /// shell keeps the real name (no other test's window can close a menu it does not hold open).
    @Test func theWindowGivingUpTheKeysClosesTheMenu() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        #expect(shell.userMenu.popover.keysLeft == NSWindow.didResignKeyNotification, "the menu listens for \(shell.userMenu.popover.keysLeft.rawValue)")
        try Self.open(shell)
        NotificationCenter.default.post(name: NSWindow.didResignKeyNotification, object: shell.window)
        #expect(!shell.userMenu.isOpen && !shell.rail.menuOpen, "the window gave up the keys and the menu is still open")
    }

    /// N1: the key catcher is VoiceOver's menu only while the menu is open; closed, it is no element
    /// and has no children, so VoiceOver finds no closed "Account" menu in the window.
    @Test func theClosedMenuIsNoElement() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        try Self.open(shell)
        let keys = try #require(shell.window.firstResponder as? MenuKeys)
        #expect(keys.isAccessibilityElement() && (keys.accessibilityChildren()?.count ?? 0) > 0, "open, the menu is no element")
        shell.userMenu.close()
        #expect(!keys.isAccessibilityElement() && (keys.accessibilityChildren() ?? []).isEmpty, "closed, the menu is still an element with children")
    }

    @Test func voiceOverHearsAMenu() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        try Self.open(shell)
        let view = shell.userMenu.view
        #expect(view.accessibilityRole() == .menu && view.accessibilityLabel() == "Account")
        #expect(view.items.allSatisfy { $0.accessibilityRole() == .menuItem && $0.accessibilityLabel() == $0.item.rawValue && $0.isAccessibilityEnabled() == $0.item.enabled })
        let segments = view.segments
        #expect(segments.accessibilityRole() == .radioGroup && segments.accessibilityLabel() == "Appearance")
        let said = segments.buttons.map { "\($0.accessibilityLabel() ?? "?")=\($0.accessibilityValue() as? Int ?? -1)" }
        #expect(said == ["Light=0", "Dark=0", "Auto=1"], "Appearance says \(said)")
        let logins = try #require(view.items.first { $0.item == .keys })
        #expect(!logins.isAccessibilityEnabled() && logins.accessibilityHelp() == "Keys come in a later version of Sail.")
        let button = try #require(shell.rail.button(.account))
        #expect(button.accessibilityRole() == .menuButton && button.accessibilityHelp()?.contains("Opens a menu") == true, "the rail's button does not say it opens a menu")
        let keys = try #require(shell.window.firstResponder as? MenuKeys)
        #expect(keys.isAccessibilityElement() && keys.accessibilityRole() == .menu && keys.accessibilityLabel() == "Account")
        #expect((keys.accessibilityChildren() as? [NSView])?.count == view.items.count + 1, "the menu's children are not its items and Appearance")
        #expect(view.items.allSatisfy { !$0.isAccessibilitySelected() } && keys.accessibilityFocusedUIElement as? MenuKeys === keys, "opened by the pointer, something is selected")
        try Self.press(Self.down, in: shell)
        let account = try #require(view.items.first { $0.item == .account })
        #expect(account.isAccessibilitySelected() && keys.accessibilityFocusedUIElement as? MenuRow === account, "the highlight is not the selected, focused element")
        try Self.press(Self.down, in: shell)
        #expect(!account.isAccessibilitySelected() && segments.isAccessibilitySelected() && keys.accessibilityFocusedUIElement as? MenuSegments === segments)
        #expect(account.accessibilityPerformPress() && !shell.userMenu.isOpen && shell.rail.selected == .account)
    }

    /// The pointer and the keys are told apart by how the button was pressed, not by the app's last
    /// event: a press with no click (the keys, VoiceOver) opens it with the first item highlighted;
    /// a click, nothing (the drive's real clicks show it: `m-open-by-a-click`, and kit's keys).
    /// VoiceOver's press and Show Menu on the menu-button-role button open our menu and show no
    /// other window.
    @Test func howTheButtonWasPressedDecides() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        let button = try #require(shell.rail.button(.account)), view = shell.userMenu.view
        #expect(!shell.rail.pressedByPointer, "no click is under way, yet the rail says the pointer pressed")
        let shown = Set(NSApp.windows.filter(\.isVisible).map(\.windowNumber))
        #expect(button.accessibilityPerformPress() && shell.userMenu.isOpen && Self.highlighted(view) != "nothing", "VoiceOver's press highlighted nothing")
        let opened = Set(NSApp.windows.filter(\.isVisible).map(\.windowNumber)).subtracting(shown)
        #expect(opened == [shell.userMenu.popover.panel.windowNumber], "VoiceOver's press showed windows \(opened), not only the menu's \(shell.userMenu.popover.panel.windowNumber)")
        shell.userMenu.close()
        #expect(button.accessibilityPerformShowMenu() && shell.userMenu.isOpen && Self.highlighted(view) != "nothing", "VoiceOver's Show Menu did not open the menu")
        shell.userMenu.close()
    }

    /// M1: the control plane's state is true as a check ends and as one starts, with the menu open:
    /// "Checking…" while who is signed in is not yet known, "Can’t reach" once the check found it out
    /// of reach (not "Reconnecting", still counted under way when it answered), "Reconnecting" while
    /// a check asks again, then its answer.
    @Test func theControlPlanesStateIsTrueAsChecksStartAndEnd() async throws {
        let defaults = ReattachTests.defaults()
        defaults.set("http://127.0.0.1:7979", forKey: AccountPane.addressKey)
        let transport = OutOfReach()
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in Signs(sheet: nil) }, connect: { _ in transport },
            vault: OneSession(fde: "uday"), defaults: defaults
        )
        Self.own(shell)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let view = shell.userMenu.view
        try Self.open(shell, shell.account.menuContent)
        #expect(view.content.name == "Not signed in" && view.content.detail == "127.0.0.1:7979")
        let said = { "the menu \(shell.userMenu.isOpen ? "open" : "closed (its window gave up the keys)") says \(view.content.name), \(view.content.plane.map { "\($0)" } ?? "no state"); the account \(shell.account.standing == nil ? "knows no one" : "knows uday"), \(shell.account.checks) checks" }
        let check = Task { await shell.account.refresh() }
        #expect(await RunnerTabTests.until { transport.asked == 1 }, "the check asked nothing")
        #expect(shell.userMenu.isOpen && view.content.name == "Checking…" && view.content.items == [.account], "during the first check \(said())")
        transport.answer()
        await check.value
        #expect(shell.userMenu.isOpen && view.content.name == "uday" && view.content.plane == .unreachable, "the check ended: \(said())")
        #expect(view.content == shell.account.menuContent, "the menu and the account disagree: \(view.content) against \(shell.account.menuContent)")
        let again = Task { await shell.account.refresh() }
        #expect(await RunnerTabTests.until { transport.asked == 2 }, "the check asked nothing again: \(transport.asked) asked")
        #expect(shell.userMenu.isOpen && view.content.plane == .reconnecting, "asking again: \(said())")
        #expect(view.content == shell.account.menuContent, "the menu and the account disagree asking again")
        transport.answer()
        await again.value
        #expect(shell.userMenu.isOpen && view.content.plane == .unreachable, "asked again: \(said())")
        shell.userMenu.close()
    }

    /// Minor 1: the keys go back to a field mid-edit with its selection as it was, not all of it
    /// selected; and to the shown pane's own, never the menu's catcher, when the view that held them
    /// has left the window (a tab closed, a rename ended).
    @Test func theKeysGoBackWhereTheyCan() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        shell.select(.account)
        let field = shell.account.address
        field.stringValue = "sail.example.com"
        shell.window.makeFirstResponder(field)
        let editor = try #require(field.currentEditor())
        editor.selectedRange = NSRange(location: 4, length: 0)
        try Self.open(shell)
        try Self.press(Self.escape, in: shell)
        let back = try #require(field.currentEditor(), "the field did not get the keys back")
        #expect(back.selectedRange == NSRange(location: 4, length: 0), "the field's selection came back as \(back.selectedRange)")
        let gone = NSTextField(string: "a name being renamed")
        shell.window.contentView?.addSubview(gone)
        shell.window.makeFirstResponder(gone)
        try Self.open(shell)
        gone.removeFromSuperview()
        try Self.press(Self.escape, in: shell)
        let now = Popover.holder(of: shell.window.firstResponder)
        #expect(!(shell.window.firstResponder is MenuKeys) && now === field, "the keys went to \(String(describing: shell.window.firstResponder)), not the Account pane's field")
    }

    /// Minor 2: a press let go after the menu closed (Escape while the mouse was held) chooses
    /// nothing: Sign Out acts at once.
    @Test func aReleaseAfterTheMenuClosedChoosesNothing() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        try Self.open(shell)
        let view = shell.userMenu.view, selected = shell.rail.selected
        let account = try #require(view.items.first { $0.item == .account })
        let middle = NSPoint(x: account.bounds.midX, y: account.bounds.midY)
        account.mouseDown(with: try Self.mouse(.leftMouseDown, at: middle, in: account))
        try Self.press(Self.escape, in: shell)
        account.mouseUp(with: try Self.mouse(.leftMouseUp, at: middle, in: account))
        #expect(!shell.userMenu.isOpen && shell.rail.selected == selected, "the release chose Account after the menu closed")
    }

    /// The rail's user button is drawn open while its menu is: a 1 pt outline in the secondary ink
    /// on its own 42 pt square, the icon in ink; closed, no outline.
    @Test(arguments: [Look.light, .dark])
    func theButtonIsDrawnOpen(_ look: Look) throws {
        let shell = try Self.shell(look)
        defer { shell.window.orderOut(nil) }
        try Self.open(shell)
        let wash = try #require(shell.rail.wash(.account))
        #expect(wash.layer?.borderWidth == 1 && wash.layer?.borderColor == NSColor(look.textSecondary).cgColor && near(wash.bounds.width, Rail.item))
        shell.userMenu.close()
        #expect(wash.layer?.borderWidth == 0 && !shell.rail.menuOpen)
    }

    /// Open over the window, the menu adds no accent and no grey: its marks are the avatar and the
    /// chosen Appearance turned over to ink, 2 opened by the pointer, and the highlighted item too, 3,
    /// by the keys; the window's count is the same as before plus those. Its Appearance track is an
    /// outline: a fill there, even in the paper's tone, would be counted grey.
    @Test(arguments: [Look.light, .dark])
    func theMenuIsCalm(_ look: Look) throws {
        let shell = try Self.shell(look)
        defer { shell.window.orderOut(nil) }
        shell.window.displayIfNeeded()
        let before = CalmTests.Count(marks(in: shell.window))
        for (withKeys, turned) in [(false, 2), (true, 3)] {
            try Self.open(shell, withKeys: withKeys)
            let menu = CalmTests.Count(marks(in: shell.userMenu.view)), whole = CalmTests.Count(marks(in: shell.window))
            let said = "\(withKeys ? "by the keys" : "by the pointer"): the menu \(menu); the window \(whole), before \(before)"
            #expect(menu.accent == 0 && menu.neutral == 0 && menu.error == 0 && menu.inverse == turned, "\(said)")
            #expect(whole.accent == before.accent && whole.neutral == before.neutral && whole.inverse == before.inverse + turned, "\(said)")
            shell.userMenu.close()
        }
        #expect(shell.userMenu.view.segments.trackFill == nil && look.marks(fill: Tint(look.canvas), area: 100, inControl: true) == [.neutralFill(100)])
    }

    /// The turned-over marks are drawn in ink and paper, read from the menu's own pixels at 2x: the
    /// highlighted item's middle is `inverse`, its title `onInverse`; the chosen segment's middle too.
    @Test(arguments: [Look.light, .dark])
    func theTurnedOverMarksAreInkByPixels(_ look: Look) throws {
        let shell = try Self.shell(look)
        defer { shell.window.orderOut(nil) }
        try Self.open(shell, withKeys: true)
        let view = shell.userMenu.view
        view.displayIfNeeded()
        let image = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: image)
        let scale = Double(image.pixelsWide) / Double(view.bounds.width)
        func channels(_ drawn: NSColor?) -> [Double] {
            guard let drawn = drawn?.usingColorSpace(.sRGB) else { return [] }
            return [drawn.redComponent, drawn.greenComponent, drawn.blueComponent].map { Double($0) * 255 }
        }
        func colour(_ point: NSPoint) -> [Double] {
            channels(image.colorAt(x: Int(point.x * scale), y: Int(point.y * scale)))
        }
        // The window's colour space is the screen's, so a token is read back as it is drawn there:
        // a swatch of it filled into a bitmap of the same view, read the same way.
        func near(_ drawn: [Double], _ rgb: RGB) -> Bool {
            guard let swatch = view.bitmapImageRepForCachingDisplay(in: NSRect(x: 0, y: 0, width: 4, height: 4)), let context = NSGraphicsContext(bitmapImageRep: swatch) else { return false }
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = context
            NSColor(Tint(rgb)).setFill()
            NSRect(x: 0, y: 0, width: 4, height: 4).fill()
            NSGraphicsContext.restoreGraphicsState()
            let reference = channels(swatch.colorAt(x: 1, y: 1))
            return drawn.count == 3 && reference.count == 3 && zip(drawn, reference).allSatisfy { abs($0 - $1) <= 3 }
        }
        let account = try #require(view.items.first)
        let corner = view.convert(NSPoint(x: account.bounds.maxX - 4, y: account.bounds.midY), from: account)
        #expect(near(colour(corner), look.inverse), "the highlighted Account is \(colour(corner)) at \(corner)")
        let chosen = view.segments.rects[2]
        let middle = view.convert(NSPoint(x: chosen.minX + 2, y: chosen.midY), from: view.segments)
        #expect(near(colour(middle), look.inverse), "the chosen Auto is \(colour(middle))")
        let paper = NSPoint(x: 3, y: account.frame.midY)
        #expect(near(colour(paper), look.surfacePop), "the menu's ground is \(colour(paper))")
    }

    /// Nothing the menu shows is out of reach, with the longest handle and control plane, signed
    /// out, and in either direction: no label cut without an ellipsis, none over another.
    @Test(arguments: [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft])
    func nothingInTheMenuIsOutOfReach(_ direction: NSUserInterfaceLayoutDirection) throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        try #require(shell.rail.button(.account)).userInterfaceLayoutDirection = direction
        var faults: [String] = []
        for content in [Self.uday, Self.long, .signedOut(lastUsed: nil)] {
            try Self.open(shell, content, withKeys: true)
            faults += WorkspacesReachTests.outOfReach(in: shell.userMenu.view).map { "\(content.name.prefix(12)): \($0)" }
            shell.userMenu.close()
        }
        #expect(faults.isEmpty, "\(faults.count) faults: \(faults.joined(separator: "\n"))")
    }

    /// Right to left the menu is mirrored: the avatar and the state square at its right, the name
    /// set right, the segments' Light at their right, ← choosing the next Appearance.
    @Test func rightToLeftTheMenuIsMirrored() throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        try #require(shell.rail.button(.account)).userInterfaceLayoutDirection = .rightToLeft
        try Self.open(shell, withKeys: true)
        let view = shell.userMenu.view, width = Double(view.bounds.width)
        #expect(near(view.avatarDisc.maxX, width - 16) && near(view.planeSquare?.maxX ?? 0, width - 16), "the disc is at \(view.avatarDisc)")
        let rects = view.segments.rects
        try #require(rects.count == 3, "the segments are \(rects)")
        #expect(rects[0].minX > rects[1].minX && rects[1].minX > rects[2].minX, "the segments run \(rects)")
        try Self.press(Self.down, in: shell)
        try Self.press(Self.left, in: shell)
        #expect(view.chosenLook == .auto, "← at Auto, the last, moved to \(view.chosenLook)")
        try Self.press(Self.right, in: shell)
        #expect(view.chosenLook == .dark, "→ right to left did not go back to Dark: \(view.chosenLook)")
        shell.choose(.auto)
    }
}

/// A control plane that does not answer: each request waits until answered, then fails as the
/// network does, counting the checks of who is signed in asked (not the lists a sign-in starts).
final class OutOfReach: Transport, Sendable {
    private let state = Mutex<(asked: Int, gates: [Gate])>((0, []))

    var asked: Int {
        state.withLock { $0.asked }
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        let gate = Gate()
        state.withLock {
            if request.route.contains("whoami") { $0.asked += 1 }
            $0.gates.append(gate)
        }
        await gate.wait()
        throw .unreachable("The network connection was lost.")
    }

    func open(_: HTTPRequest) async throws(SailError) -> HTTPStream {
        throw .unreachable("The network connection was lost.")
    }

    /// Every request waiting is answered: out of reach.
    func answer() {
        state.withLock { $0.gates }.forEach { $0.open() }
    }
}
