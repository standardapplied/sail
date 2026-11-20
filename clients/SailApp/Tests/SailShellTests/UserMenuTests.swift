import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
import SailVT
@testable import SailShell
import Testing

/// The user menu behind the rail's user button (the calm spec's PR 2, in Paper and ink): where it
/// sits at every window size and in either direction, what it says, how the keys and the pointer
/// work it, what each item does, Appearance remembered and taken, what VoiceOver hears, and that it
/// adds no accent and no grey to the window it opens over.
@MainActor @Suite struct UserMenuTests {
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
        return shell
    }

    static func open(_ shell: ShellApp, _ content: UserMenuContent = uday, withKeys: Bool = false) throws {
        let button = try #require(shell.rail.button(.account))
        shell.rail.menuOpen = true
        shell.userMenu.open(from: button, saying: content, appearance: Appearance.stored(in: shell.defaults), withKeys: withKeys)
        shell.userMenu.view.layoutSubtreeIfNeeded()
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
    /// button's, its foot 8 pt above the button's top, inside the window, the shadow's room about
    /// it; a child of the window while open, gone when closed.
    @Test(arguments: [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft])
    func theMenuSitsOnItsButtonAtEverySize(_ direction: NSUserInterfaceLayoutDirection) throws {
        let shell = try Self.shell()
        defer { shell.window.orderOut(nil) }
        let button = try #require(shell.rail.button(.account))
        button.userInterfaceLayoutDirection = direction
        for size in Self.sizes {
            shell.window.setFrame(NSRect(origin: shell.window.frame.origin, size: size), display: true)
            shell.window.layoutIfNeeded()
            for content in [Self.uday, .signedOut, Self.long] {
                try Self.open(shell, content)
                let box = shell.userMenu.box, menu = shell.userMenu.view
                let square = shell.window.convertToScreen(button.convert(button.alignmentRect(forFrame: button.bounds), to: nil))
                let said = "\(size.width)×\(size.height), \(content.name.prefix(12)): the menu at \(box), its button at \(square), the window \(shell.window.frame)"
                #expect(near(box.width, UserMenu.width) && near(box.height, menu.fittingSize.height), "\(said)")
                let leading = direction == .rightToLeft ? near(box.maxX, square.maxX) : near(box.minX, square.minX)
                #expect(leading && near(box.minY, square.maxY + UserMenu.gap), "\(said)")
                #expect(shell.window.frame.insetBy(dx: -0.5, dy: -0.5).contains(box), "\(said): out of the window")
                #expect(near(shell.userMenu.panel.frame.width, box.width + 2 * UserMenu.room) && shell.window.childWindows?.contains(shell.userMenu.panel) == true, "\(said)")
                #expect(near(menu.frame.width, UserMenu.width) && menu.userInterfaceLayoutDirection == direction, "\(said): the box is laid out \(menu.frame)")
                shell.userMenu.close()
                #expect(!shell.userMenu.panel.isVisible && shell.window.childWindows?.contains(shell.userMenu.panel) != true, "\(said): still shown closed")
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
        let labels = WorkspacesViewTests.found(NSTextField.self, in: view).filter { !$0.isHidden }.map(\.stringValue)
        #expect(labels == ["Uday Chandra", "@uday · Admin", "main.acme.dev", "Connected", "Appearance"], "the menu says \(labels)")
        #expect(view.items.map(\.item) == [.account, .logins, .signOut] && view.items.allSatisfy { near($0.frame.height, UserMenuView.row) && near($0.frame.minX, 6) })
        #expect(near(view.avatarDisc.width, 32) && near(view.avatarDisc.minX, 16) && view.planeInk == Tint(look.success))
        let signOut = try #require(view.items.last), logins = try #require(view.items.first { $0.item == .logins })
        #expect(signOut.frame.minY - logins.frame.maxY > UserMenuView.appearanceRow, "Sign Out is not in a section apart: \(signOut.frame) after \(logins.frame)")
        shell.userMenu.update(.signedOut, appearance: .auto)
        view.layoutSubtreeIfNeeded()
        let out = WorkspacesViewTests.found(NSTextField.self, in: view).filter { !$0.isHidden }.map(\.stringValue)
        #expect(out == ["Not signed in", "Appearance"] && view.items.map(\.item) == [.signIn], "signed out, the menu says \(out)")
        shell.userMenu.update(Self.long, appearance: .auto)
        #expect(view.planeInk == Tint(look.error), "out of reach, the square is not the error's")
    }

    /// Opened, the first item is highlighted (M1); ↓ and ↑ walk the items that can be chosen and
    /// Appearance, round; ← and → choose the Appearance on it; Return chooses; Escape closes and the
    /// keys go back to the button when the keys opened it, else where they were.
    @Test func theKeysWorkTheMenu() throws {
        let shell = try Self.shell()
        defer {
            shell.window.orderOut(nil)
            NSApp.appearance = nil
        }
        let view = shell.userMenu.view, button = try #require(shell.rail.button(.account))
        let had = shell.window.firstResponder
        try Self.open(shell, withKeys: true)
        var walked = [Self.highlighted(view)]
        for code in [Self.down, Self.down, Self.down, Self.up] {
            try Self.press(code, in: shell)
            walked.append(Self.highlighted(view))
        }
        #expect(walked == ["Account", "Appearance", "Sign Out", "Account", "Sign Out"], "the keys walk \(walked)")
        try Self.press(Self.up, in: shell)
        try Self.press(Self.left, in: shell)
        #expect(view.chosenLook == .dark && Appearance.stored(in: shell.defaults) == .dark && NSApp.appearance?.name == .darkAqua && shell.userMenu.isOpen)
        try Self.press(Self.right, in: shell)
        try Self.press(Self.right, in: shell)
        #expect(view.chosenLook == .auto && NSApp.appearance == nil, "→ twice from Dark is \(view.chosenLook)")
        try Self.press(Self.escape, in: shell)
        // The button takes the keys only with Keyboard Navigation on; off, they go back where they were.
        let back: NSResponder? = NSApp.isFullKeyboardAccessEnabled ? button : had
        #expect(!shell.userMenu.isOpen && !shell.rail.menuOpen && shell.window.firstResponder === back, "Escape left the keys on \(String(describing: shell.window.firstResponder))")
        try Self.open(shell, withKeys: true)
        try Self.press(Self.enter, in: shell)
        #expect(!shell.userMenu.isOpen && shell.rail.selected == .account, "Return on Account showed \(shell.rail.selected?.rawValue ?? "nothing")")
        let field = shell.account.address
        shell.window.makeFirstResponder(field)
        let before = shell.window.firstResponder
        try Self.open(shell)
        #expect(Self.highlighted(view) == "Account", "opened with the pointer, \(Self.highlighted(view)) is highlighted")
        try Self.press(Self.escape, in: shell)
        #expect(shell.window.firstResponder === before, "the keys went to \(String(describing: shell.window.firstResponder))")
    }

    /// The button opens the menu and, again, closes it; a click outside it closes it, as does the
    /// window giving up the keys; Logins, which cannot be chosen, says why when clicked or pointed
    /// at and leaves the menu open; Account shows the Account pane; an Appearance leaves it open.
    @Test func thePointerWorksTheMenu() throws {
        let shell = try Self.shell()
        defer {
            shell.window.orderOut(nil)
            NSApp.appearance = nil
        }
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
        NSApp.sendEvent(outside)
        #expect(!shell.userMenu.isOpen, "a click outside left it open")
        try Self.open(shell)
        NotificationCenter.default.post(name: NSWindow.didResignKeyNotification, object: shell.window)
        #expect(!shell.userMenu.isOpen, "the window giving up the keys left it open")
        try Self.open(shell)
        let logins = try #require(view.items.first { $0.item == .logins })
        logins.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: logins.bounds.midX, y: logins.bounds.midY), in: logins))
        #expect(shell.userMenu.isOpen && view.tip.isShown && logins.accessibilityHelp() == UserMenuContent.Item.logins.why, "Logins did not say why")
        #expect(logins.drawn.fill == nil && logins.drawn.ink == shell.look.textTertiary, "Logins is drawn as one that can be chosen")
        let account = try #require(view.items.first { $0.item == .account })
        account.mouseEntered(with: try Self.mouse(.mouseMoved, at: .zero, in: account))
        #expect(!view.tip.isShown && account.highlighted && account.drawn.fill == shell.look.menuHighlight.fill, "pointing at Account did not highlight it")
        let dark = view.segments.rects[1]
        view.segments.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: dark.midX, y: dark.midY), in: view.segments))
        #expect(shell.userMenu.isOpen && Appearance.stored(in: shell.defaults) == .dark && NSApp.appearance?.name == .darkAqua)
        account.mouseUp(with: try Self.mouse(.leftMouseUp, at: NSPoint(x: account.bounds.midX, y: account.bounds.midY), in: account))
        #expect(!shell.userMenu.isOpen && shell.rail.selected == .account, "Account showed \(shell.rail.selected?.rawValue ?? "nothing")")
        shell.choose(.auto)
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
        defer {
            shell.window.orderOut(nil)
            NSApp.appearance = nil
        }
        #expect(Appearance.stored(in: shell.defaults) == .auto, "a first launch is not Auto")
        for (appearance, name) in [(Appearance.dark, NSAppearance.Name.darkAqua), (.light, .aqua)] {
            shell.choose(appearance)
            #expect(NSApp.appearance?.name == name && Appearance.stored(in: shell.defaults) == appearance)
            NSApp.appearance = nil
            ShellApp.takeRememberedAppearance(from: shell.defaults)
            #expect(NSApp.appearance?.name == name, "\(appearance) was not taken at launch")
        }
        shell.choose(.auto)
        ShellApp.takeRememberedAppearance(from: shell.defaults)
        #expect(NSApp.appearance == nil && Appearance.stored(in: shell.defaults) == .auto, "Auto set an appearance of the app's own")
    }

    /// VoiceOver hears a menu named Account, its items as menu items by their titles (Logins
    /// dimmed, saying why), and Appearance as a group of three choices, the chosen one on; pressing
    /// Account by VoiceOver chooses it.
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
        let account = try #require(view.items.first { $0.item == .account })
        #expect(account.accessibilityPerformPress() && !shell.userMenu.isOpen && shell.rail.selected == .account)
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

    /// Open over the window, the menu adds no accent and no grey: its marks are the avatar, the
    /// highlighted item and the chosen Appearance turned over to ink, 3, however it was opened, and
    /// the window's count is the same as before plus those.
    @Test(arguments: [Look.light, .dark])
    func theMenuIsCalm(_ look: Look) throws {
        let shell = try Self.shell(look)
        defer { shell.window.orderOut(nil) }
        shell.window.displayIfNeeded()
        let before = CalmTests.Count(marks(in: shell.window))
        for (withKeys, turned) in [(false, 3), (true, 3)] {
            try Self.open(shell, withKeys: withKeys)
            let menu = CalmTests.Count(marks(in: shell.userMenu.view)), whole = CalmTests.Count(marks(in: shell.window))
            let said = "\(withKeys ? "by the keys" : "by the pointer"): the menu \(menu); the window \(whole), before \(before)"
            #expect(menu.accent == 0 && menu.neutral == 0 && menu.error == 0 && menu.inverse == turned, "\(said)")
            #expect(whole.accent == before.accent && whole.neutral == before.neutral && whole.inverse == before.inverse + turned, "\(said)")
            shell.userMenu.close()
        }
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
        func colour(_ point: NSPoint) -> [Double] {
            guard let drawn = image.colorAt(x: Int(point.x * scale), y: Int(point.y * scale))?.usingColorSpace(.sRGB) else { return [] }
            return [drawn.redComponent, drawn.greenComponent, drawn.blueComponent].map { Double($0) * 255 }
        }
        func near(_ drawn: [Double], _ rgb: RGB) -> Bool {
            drawn.count == 3 && zip(drawn, [rgb.red, rgb.green, rgb.blue].map(Double.init)).allSatisfy { abs($0 - $1) <= 3 }
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
        for content in [Self.uday, Self.long, .signedOut] {
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
        defer {
            shell.window.orderOut(nil)
            NSApp.appearance = nil
        }
        try #require(shell.rail.button(.account)).userInterfaceLayoutDirection = .rightToLeft
        try Self.open(shell, withKeys: true)
        let view = shell.userMenu.view, width = Double(view.bounds.width)
        #expect(near(view.avatarDisc.maxX, width - 16) && near(view.planeSquare?.maxX ?? 0, width - 16), "the disc is at \(view.avatarDisc)")
        let rects = view.segments.rects
        #expect(rects[0].minX > rects[1].minX && rects[1].minX > rects[2].minX, "the segments run \(rects)")
        try Self.press(Self.down, in: shell)
        try Self.press(Self.left, in: shell)
        #expect(view.chosenLook == .auto, "← at Auto, the last, moved to \(view.chosenLook)")
        try Self.press(Self.right, in: shell)
        #expect(view.chosenLook == .dark, "→ right to left did not go back to Dark: \(view.chosenLook)")
        shell.choose(.auto)
    }
}
