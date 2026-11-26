import AppKit
import ImageIO
import SailAccount
import SailAPI
import SailControls
import SailShell
import SailTestPasskey
import SailVT
import Security

/// The drive's steps on the account: they act through the welcome's and the Account pane's own
/// fields and buttons, and read what they say. No step's detail holds a ticket or a token.
extension Driver {
    func performAccount(_ step: Step) async -> (Bool, String)? {
        let welcome = shell.welcome, flow = shell.flow
        if let item = step.select {
            guard let chosen = Rail.Item(rawValue: item), let button = shell.rail.button(chosen) else { return (false, "no rail item \(item)") }
            if chosen == .account { return await accountByTheMenu() }
            button.performClick(nil)
            return (shell.rail.selected == chosen, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing")")
        }
        if let shows = step.accountShows {
            return accountShows(shows)
        }
        if let pick = step.accountRow, pick.count == 3 {
            return await accountRow(pick[0], pick[1], pick[2])
        }
        if let words = step.accountSays {
            let said = await waitUntil(step.seconds ?? 10) { self.shell.account.text.contains(words) }
            return (said, "the account says: " + shell.account.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if step.accountAtKeys == true {
            return await accountAtKeys(step)
        }
        if let days = step.sessionEndsIn {
            do {
                try await GoldenDesk.shared.signInEnds(in: days)
                return (true, "")
            } catch {
                return (false, "passkey-login-finish: \(error)")
            }
        }
        if let rightToLeft = step.accountRightToLeft {
            let direction: NSUserInterfaceLayoutDirection = rightToLeft ? .rightToLeft : .leftToRight
            for view in [shell.account] + Self.views(in: shell.account) {
                view.userInterfaceLayoutDirection = direction
                view.needsLayout = true
                view.needsDisplay = true
            }
            shell.account.show()
            shell.account.layoutSubtreeIfNeeded()
            return (true, "the account is laid out \(rightToLeft ? "right to left" : "left to right")")
        }
        if let name = step.accountShot {
            return await accountShot(name)
        }
        if let state = step.welcomeShows {
            return welcomeShows(state)
        }
        if let how = step.menu {
            return await openMenu(how)
        }
        if let title = step.menuClick {
            return await clickInMenu(title, step)
        }
        if let says = step.menuSays {
            let said = await waitUntil(step.seconds ?? 2) { self.menuSaid == says }
            return (said, menuSaid)
        }
        if let address = step.address {
            if welcome.welcome.screen != .address, let chip = welcome.shownChip { chip.change.performClick(nil) }
            welcome.addressField.field.stringValue = address
            return (welcome.welcome.screen == .address, said)
        }
        if let typed = step.typeTicket {
            if welcome.welcome.screen == .back { welcome.toEnroll.performClick(nil) }
            welcome.ticketField.field.stringValue = typed
            return (welcome.welcome.screen == .ticket, said)
        }
        if let word = step.welcomeBusy {
            let busy = await waitUntil(step.seconds ?? 10) { welcome.primary.busyWord == word && flow.isActing }
            return (busy, "the primary says \(welcome.primary.busyWord ?? welcome.primary.title); \(said)")
        }
        if let selected = step.rail {
            return railReads(selected)
        }
        if step.proceed == true {
            let acted = await clicked(welcome.proceed, named: "Continue", step)
            return (acted.ok, (acted.why.map { "\($0); " } ?? "") + said)
        }
        if step.change == true {
            guard let chip = welcome.shownChip else { return (false, "no chip shown: \(said)") }
            let typed = welcome.welcome.typed
            chip.change.performClick(nil)
            let field = welcome.addressField.field, chosen = (field.currentEditor() as? NSTextView)?.selectedRange()
            let selected = shell.window.firstResponder === field.currentEditor() && chosen == NSRange(location: 0, length: (typed as NSString).length)
            return (welcome.welcome.screen == .address && field.stringValue == typed && selected,
                    "the field holds \(field.stringValue), \(selected ? "selected, with the keys" : "not selected with the keys"); \(said)")
        }
        if let answer = step.passkey {
            guard let chosen = SoftwarePasskey.Answer(rawValue: answer) else { return (false, "no passkey answer \(answer)") }
            passkey.answer(chosen)
            return (true, "")
        }
        if let variable = step.enroll {
            guard let ticket = Self.secret(variable) else { return (false, "neither \(variable) nor the file \(variable)_FILE names holds a ticket") }
            if welcome.welcome.screen == .back { welcome.toEnroll.performClick(nil) }
            welcome.ticketField.field.stringValue = ticket
            let acted = await clicked(welcome.create, named: "Create Passkey", step)
            keepTheToken()
            let empty = welcome.ticketField.field.stringValue.isEmpty
            return (acted.ok && empty, (acted.why.map { "\($0); " } ?? "") + "the ticket field is \(empty ? "empty" : "not empty"); \(said)")
        }
        if step.signIn == true {
            let button: NSButton = welcome.welcome.screen == .back ? welcome.signIn : welcome.toSignIn
            let acted = await clicked(button, named: "Sign In with Passkey", step)
            keepTheToken()
            return (acted.ok, (acted.why.map { "\($0); " } ?? "") + said)
        }
        if step.signOut == true {
            let acted = await clicked(shell.account.signOut, named: "Sign Out", step)
            return (acted.ok, (acted.why.map { "\($0); " } ?? "") + said)
        }
        if let lines = step.exactly {
            let found = await waitUntil(step.seconds ?? 10) { !flow.busy && self.shown.split(separator: "\n").map(String.init) == lines }
            return (found, said)
        }
        if let text = step.pane {
            let found = await waitUntil(step.seconds ?? 10) { !flow.busy && self.shown.contains(text) }
            return (found, said)
        }
        if let words = step.sail {
            return run(words.map { $0 == "{credential}" ? passkey.credentialIds.last ?? "" : $0 })
        }
        if step.activate == true {
            let idle = await settled(step) { NotificationCenter.default.post(name: NSApplication.didBecomeActiveNotification, object: NSApp) }
            return (idle, said)
        }
        if let count = step.asked {
            let asked = passkey.asked - askedAtStart
            return (asked == count, "the software passkey was asked \(asked) times")
        }
        if let wanted = step.keychain {
            return keychain(wanted)
        }
        if step.accent == true {
            return accent()
        }
        if step.titleBar == true {
            return await titleBar(step.name ?? "title-bar")
        }
        if step.sweep == true {
            return sweep()
        }
        return nil
    }

    /// The rail's items top to bottom, Workspaces, Terminals and Account, each shown, with
    /// `selected` the one chosen.
    private func railReads(_ selected: String) -> (Bool, String) {
        let buttons = Rail.Item.allCases.compactMap { shell.rail.button($0) }.filter { !$0.isHiddenOrHasHiddenAncestor }
        let order = buttons.sorted { $0.convert($0.bounds, to: nil).maxY > $1.convert($1.bounds, to: nil).maxY }.map { $0.toolTip ?? "" }
        let chosen = shell.rail.selected?.rawValue ?? "nothing"
        return (order == ["Workspaces", "Terminals", "Account"] && chosen == selected, "the rail reads \(order.joined(separator: ", ")), \(chosen) chosen")
    }

    /// A signed build's session token, kept for the sweep.
    private func keepTheToken() {
        guard signed, let plane = shell.flow.memory.plane, let held = try? KeychainVault().session(for: plane) else { return }
        tokens.insert(held.sessionToken)
    }

    /// The welcome in one of its states, as the page's frames draw it (sail.example.com, Mara), for
    /// the frames of each; "none" puts back the screen this Mac's memory chooses.
    private func welcomeShows(_ state: String) -> (Bool, String) {
        guard state != "none" else {
            shell.welcome.reset()
            return (true, shell.welcome.text)
        }
        guard let plane = try? ControlPlane(address: "sail.example.com") else { return (false, "no control plane") }
        let mara = RememberedSignIn(handle: "mara", name: "Mara Quill")
        var welcome = Welcome(plane: nil, person: nil)
        shell.welcome.addressField.field.stringValue = plane.named
        switch state {
        case "welcome": break
        case "connecting": welcome.began(.connecting)
        case "unreachable": welcome.refused(AccountRefusal(.unreachable("Could not connect to the server."), at: plane, step: .reaching))
        case "enroll": welcome.reached(plane, person: nil)
        case "creating", "signing-after-enrolling", "ticket-refused", "cancelled-creating", "cancelled-after-enrolling":
            welcome.reached(plane, person: nil)
            let afterEnrolling = ["signing-after-enrolling", "cancelled-after-enrolling"].contains(state)
            welcome.began(afterEnrolling ? .signingAfterEnrolling(handle: "mara") : .creating)
            let forbidden = Refusal(code: .known(.forbidden), message: "Enrollment ticket is invalid or expired.")
            if state == "ticket-refused" { welcome.refused(AccountRefusal(.refused(forbidden), at: plane, step: .enrolling)) }
            if state.hasPrefix("cancelled-") { welcome.refused(AccountRefusal(.cancelled, signingIn: afterEnrolling, at: plane)) }
        case "back", "signing-in", "cancelled", "ended":
            welcome.reached(plane, person: mara)
            if state == "signing-in" { welcome.began(.signingIn) }
            if state == "cancelled" { welcome.refused(AccountRefusal(.cancelled, signingIn: true, at: plane)) }
            if state == "ended" { welcome.ended(on: Date(timeIntervalSince1970: 1_795_608_000)) }
        default: return (false, "no welcome state named \(state)")
        }
        shell.welcome.show(welcome)
        shell.select(.workspaces)
        return (!shell.welcome.isHiddenOrHasHiddenAncestor, shell.welcome.text.replacingOccurrences(of: "\n", with: " | "))
    }

    private func accountShows(_ shows: String) -> (Bool, String) {
        guard shows != "none" else {
            shell.flow.show(nil)
            return (true, shell.account.text)
        }
        let handle = String(repeating: "k", count: 63)
        guard let plane = try? ControlPlane(address: "https://" + String(repeating: "h", count: 52) + ".acme.dev"), ["long", "long-unconfirmed"].contains(shows) else {
            return (false, "no sign-in named \(shows)")
        }
        let whoami = Whoami(fde: handle, name: handle, displayName: "Uday Chandra-Bhattacharyya of the Standard Applied Mac app team", role: .known(.member), capabilities: [])
        let answer: Swift.Result<Whoami, AccountRefusal> = shows == "long" ? .success(whoami) : .failure(AccountRefusal(.unreachable("The network connection was lost."), at: plane, step: .asking))
        shell.flow.show(SignedIn(controlPlane: plane, fde: handle, until: Date(timeIntervalSinceNow: 30 * 86400), whoami: answer))
        return (true, shell.account.text)
    }

    /// The Account view's list of `group`.
    private func accountList(_ group: String) -> SailList? {
        switch group {
        case "passkeys": shell.account.passkeyList
        case "keys": shell.account.keyList
        default: nil
        }
    }

    /// `action` on the row titled `title` of the Account view's `group`.
    private func accountRow(_ group: String, _ title: String, _ action: String) async -> (Bool, String) {
        guard let list = accountList(group) else { return (false, "no account group \(group)") }
        let found = await waitUntil(10) { list.entries.contains { if case let .item(item) = $0 { item.name == title } else { false } } }
        let item = list.entries.lazy.compactMap { entry -> SailList.Item? in if case let .item(item) = entry, item.name == title { item } else { nil } }.first
        guard found, let item else { return (false, "\(group) has no row \(title): \(shell.account.text.replacingOccurrences(of: "\n", with: " | "))") }
        list.layoutSubtreeIfNeeded()
        if action == "hover" {
            list.point(at: item.id)
            let shown = await waitUntil(2) { list.actionsShown(item.id) }
            return (shown, "\(title) is hovered, its actions \(shown ? "shown" : "not shown")")
        }
        guard let row = list.entries.firstIndex(where: { if case let .item(found) = $0 { found.id == item.id } else { false } }),
              let view = list.rowView(atRow: row, makeIfNecessary: true)
        else { return (false, "\(title) has no row on screen") }
        list.point(at: item.id)
        view.layoutSubtreeIfNeeded()
        let buttons = Self.views(in: view).compactMap { $0 as? SailButton }.filter { !$0.isHiddenOrHasHiddenAncestor }
        if action.hasPrefix("⋯ ") {
            let title = String(action.dropFirst(2))
            guard let more = buttons.first(where: { $0.menu != nil }), let menu = more.menu, let index = menu.items.firstIndex(where: { $0.title == title }) else {
                return (false, "\(item.name)'s ⋯ menu has no \(title)")
            }
            menu.performActionForItem(at: index)
            return (true, "chose \(title) from \(item.name)'s menu")
        }
        guard let button = buttons.first(where: { $0.title == action }) else { return (false, "\(item.name) shows no \(action): \(buttons.map(\.title))") }
        guard await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY)) else { return (false, "\(action) could not be clicked") }
        return (true, "clicked \(action) on \(item.name)")
    }

    /// The Account view shown, scrolled to Keys, as the user menu's Keys leaves it.
    private func accountAtKeys(_ step: Step) async -> (Bool, String) {
        let pane = shell.account
        let there = await waitUntil(step.seconds ?? 5) {
            pane.layoutSubtreeIfNeeded()
            let most = max(Double(pane.scroll.documentView?.frame.height ?? 0) - Double(pane.scroll.contentSize.height), 0)
            let keys = min(max(Double(pane.eyebrows[2].frame.minY) - AccountPane.inset.top, 0), most)
            return self.shell.rail.selected == .account && abs(pane.scrolledTo - keys) < 1
        }
        return (there, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing"), the account scrolled to \(pane.scrolledTo) pt, its keys at \(pane.eyebrows[2].frame.minY) pt")
    }

    /// The window in both looks at 1024 × 800, then at its least frame, then as it was.
    private func accountShot(_ name: String) async -> (Bool, String) {
        let was = shell.window.frame
        var said: [String] = [], ok = true
        for (suffix, size) in [("", NSSize(width: 1024, height: 800)), ("-least", ShellApp.leastFrame)] {
            shell.window.setFrame(NSRect(x: was.minX, y: was.maxY - size.height, width: size.width, height: size.height), display: true)
            shell.account.layoutSubtreeIfNeeded()
            let (taken, detail) = await inBothLooks(name + suffix) { file in await self.capture(.window(self.shell.window.windowNumber), to: file) }
            ok = ok && taken
            said.append(detail)
        }
        shell.window.setFrame(was, display: true)
        return (ok, said.joined(separator: "; "))
    }

    /// What the user menu shows, as one line: closed, and whether its button has the keys; or open,
    /// whom and where it says, what is highlighted, the Appearance chosen.
    var menuSaid: String {
        let menu = shell.userMenu, view = menu.view
        guard menu.isOpen else { return "closed" + (shell.window.firstResponder === shell.rail.button(.account) ? ", the keys on its button" : "") }
        let content: UserMenuContent = view.content
        var place = ""
        if let address = content.address { place = " at " + address + ", " + (content.plane?.said ?? "") }
        var highlighted = "nothing"
        if let index = view.highlighted { highlighted = index == view.items.count ? "Appearance" : view.items[index].item.rawValue }
        return "open: " + content.name + place + "; " + highlighted + " highlighted; " + view.chosenLook.said + " chosen"
    }

    /// The Account pane through the user menu, as a person reaches it: the button, then Account
    /// (Sign In when signed out, which opens Workspaces).
    private func accountByTheMenu() async -> (Bool, String) {
        let (opened, said) = await openMenu("click")
        guard opened else { return (false, said) }
        let item = shell.userMenu.view.items.first { $0.item == .account || $0.item == .signIn }?.item ?? .account
        let (chosen, detail) = await clickInMenu(item.rawValue, Step())
        return (chosen && shell.rail.selected == (item == .signIn ? .workspaces : .account), "\(detail); the rail shows \(shell.rail.selected?.rawValue ?? "nothing")")
    }

    func openMenu(_ how: String) async -> (Bool, String) {
        guard let button = shell.rail.button(.account) else { return (false, "no user button") }
        let wasOpen = shell.userMenu.isOpen
        // A person's first click on a window that is not the key one only brings it forward.
        guard await waitUntil(5, { self.shell.window.isKeyWindow }) else { return (false, "the window never became key: \(menuSaid)") }
        let clickedAt = ContinuousClock.now
        var scene = ""
        switch how {
        case "click":
            let at = NSPoint(x: button.bounds.midX, y: button.bounds.midY)
            scene = "; at the click " + clickScene(button, at: at)
            guard await click(button, at: at) else { return (false, "the click was not made") }
        case "keys":
            guard NSApplication.shared.isFullKeyboardAccessEnabled else { return (false, "keyboard navigation is off, so the button cannot take the keys") }
            guard shell.window.makeFirstResponder(button), let space = Keys.named(" ", modifiers: []) else { return (false, "the button did not take the keys") }
            send(space)
        default: return (false, "no way to open the menu named \(how)")
        }
        let sent = (ContinuousClock.now - clickedAt) / .milliseconds(1)
        let toggled = await waitUntil(2) { self.shell.userMenu.isOpen != wasOpen && self.shell.rail.menuOpen == !wasOpen }
        let took = (ContinuousClock.now - clickedAt) / .milliseconds(1)
        return (toggled, "\(menuSaid); the \(how) returned in \(Self.ms(sent)) ms, the menu \(wasOpen ? "closed" : "open") \(Self.ms(took)) ms after it\(scene)")
    }

    private func clickInMenu(_ title: String, _ step: Step) async -> (Bool, String) {
        let menu = shell.userMenu, view = menu.view
        guard menu.isOpen else { return (false, "the menu is closed") }
        if title == "outside" {
            let rail = shell.rail
            guard await click(rail, at: NSPoint(x: rail.bounds.midX, y: rail.bounds.midY)) else { return (false, "the click was not made") }
            return (await waitUntil(2) { !menu.isOpen }, menuSaid)
        }
        if let appearance = Appearance.allCases.first(where: { $0.said == title }) {
            guard await clickAppearance(appearance) else { return (false, "the click was not made") }
            let taken = await waitUntil(2) { Appearance.stored(in: self.shell.defaults) == appearance && NSApp.appearance?.name == appearance.appearance?.name }
            let drawn = await lookDrawn()
            return (taken && drawn && menu.isOpen, "\(menuSaid); the app's look \(shell.look == .dark ? "dark" : "light")\(drawn ? "" : ", not drawn by every terminal shown")")
        }
        guard let row = view.items.first(where: { $0.item.rawValue == title }) else { return (false, "the menu has no \(title): \(menuSaid)") }
        let began = shell.flow.actions
        let before = (row.downs, row.ups), clickedAt = ContinuousClock.now
        let at = NSPoint(x: row.bounds.midX, y: row.bounds.midY)
        let scene = clickScene(row, at: at)
        guard await click(row, at: at) else { return (false, "the click was not made") }
        let done: Bool
        switch row.item {
        case .account, .keys: done = await waitUntil(2) { !menu.isOpen && self.shell.rail.selected == .account }
        case .signIn: done = await waitUntil(2) { !menu.isOpen && self.shell.rail.selected == .workspaces }
        case .signOut:
            done = await waitUntil(step.seconds ?? 20) { self.shell.flow.actions > began && !self.shell.flow.isActing && self.shell.flow.standing == nil }
        }
        let took = (ContinuousClock.now - clickedAt) / .milliseconds(1)
        let reached = "\(title) took \(row.downs - before.0) mouse-down and \(row.ups - before.1) mouse-up in \(Self.ms(took)) ms; at the click \(scene); "
            + "the app's current event after it \(NSApp.currentEvent.map { "\($0.type.rawValue) in window \($0.windowNumber)" } ?? "none")"
        return (done && !menu.isOpen, "\(menuSaid); \(reached)" + (row.item == .signOut ? "; \(said)" : ""))
    }

    /// Where a click at `point` in `view` lands, as the window server and AppKit see it just before
    /// it is made: the key and main windows, the window under the point on screen and the view that
    /// window's content hits there, the clicked view's window and whether it can become key, and
    /// whether the view takes a first click in a window that is not key.
    func clickScene(_ view: NSView, at point: NSPoint) -> String {
        guard let window = view.window else { return "no window" }
        func named(_ window: NSWindow?) -> String {
            window.map { "\(type(of: $0)) \($0.windowNumber)" } ?? "none"
        }
        let screen = window.convertPoint(toScreen: view.convert(point, to: nil))
        let number = NSWindow.windowNumber(at: screen, belowWindowWithWindowNumber: 0)
        let under = NSApp.window(withWindowNumber: number)
        let hit = under.flatMap { under in under.contentView?.superview?.hitTest(under.convertPoint(fromScreen: screen)) }
        let inWindow = view.convert(point, to: nil)
        let hitThere = window.contentView?.superview?.hitTest(inWindow)
        let first = NSEvent.mouseEvent(
            with: .leftMouseDown, location: view.convert(point, to: nil), modifierFlags: [], timestamp: 0, windowNumber: window.windowNumber, context: nil,
            eventNumber: 0, clickCount: 1, pressure: 1
        )
        return "key \(named(NSApp.keyWindow)), main \(named(NSApp.mainWindow)), under the point \(under.map { named($0) } ?? "window \(number), not the app's") hitting "
            + "\(hit.map { String(describing: type(of: $0)) } ?? "nothing"), the clicked view's window \(named(window)) (can become key \(window.canBecomeKey), key \(window.isKeyWindow)), "
            + "it takes a first click \(view.acceptsFirstMouse(for: first)), the app active \(NSApp.isActive); the clicked view's frame \(view.convert(view.bounds, to: nil)) "
            + "in its window, which hits \(hitThere.map { String(describing: type(of: $0)) } ?? "nothing") at \(inWindow)"
    }

    /// A click on the open user menu's `appearance` segment as a person makes it.
    func clickAppearance(_ appearance: Appearance) async -> Bool {
        let segments = shell.userMenu.view.segments
        guard let index = Appearance.allCases.firstIndex(of: appearance), segments.rects.indices.contains(index) else { return false }
        let rect = segments.rects[index]
        return await click(segments, at: NSPoint(x: rect.midX, y: rect.midY))
    }

    /// What the welcome says while no one is signed in, else the Account pane.
    private var shown: String {
        shell.flow.standing == nil ? shell.welcome.text : shell.account.text
    }

    /// What is shown, as one line.
    private var said: String {
        "it says: " + shown.replacingOccurrences(of: "\n", with: " | ")
    }

    /// Does `act` and waits until the pane has ended what it started.
    /// Clicks `button` as a person would and waits until the action it started has ended (a check
    /// of who is signed in may go on): refused, saying so, when the button was disabled, or the
    /// click started no action within 2 s (a click a check of who is signed in swallowed once
    /// ended in 0.03 s with nothing said, read as the sign-in's end).
    private func clicked(_ button: NSButton, named name: String, _ step: Step) async -> (ok: Bool, why: String?) {
        let pane = shell.flow
        guard button.isEnabled else { return (false, "\(name) was disabled when clicked\(pane.busy ? ", an action under way" : "")") }
        let began = pane.actions
        button.performClick(nil)
        guard await waitUntil(2, { pane.actions > began }) else { return (false, "the click on \(name) started nothing within 2 s") }
        guard await waitUntil(step.seconds ?? 20, { !pane.isActing }) else { return (false, "\(name) was still under way after \(Int(step.seconds ?? 20)) s") }
        return (true, nil)
    }

    private func settled(_ step: Step, _ act: () -> Void) async -> Bool {
        let before = shell.flow.ended
        act()
        return await waitUntil(step.seconds ?? 20) { self.shell.flow.ended > before && !self.shell.flow.busy }
    }

    private func run(_ words: [String]) -> (Bool, String) {
        let sail = Bundle.main.bundleURL.appending(path: "Contents/Helpers/sail")
        let process = Process()
        process.executableURL = sail
        process.arguments = words
        let output = Pipe()
        process.standardOutput = output
        process.standardError = output
        do {
            try process.run()
        } catch {
            return (false, "sail could not be started: \(error)")
        }
        let said = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        process.waitUntilExit()
        let shown = words.prefix(3).joined(separator: " ")
        return (process.terminationStatus == 0, "sail \(shown) … exited \(process.terminationStatus): \(said.suffix(300))")
    }

    /// Scenarios 3, 16 and S2: the item as item 3 says, holding a session 30 days long, or gone; or
    /// an item of the earlier shape put in its place, as a build before this one kept it.
    private func keychain(_ wanted: String) -> (Bool, String) {
        guard let plane = shell.flow.memory.plane else { return (false, "no control plane was reached") }
        if wanted == "oldShape" {
            var item = KeychainVault.item(plane)
            item[kSecValueData as String] = Data(#"{"token":"old-shape-token","fde":"uday","expiresAt":815000000}"#.utf8)
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            _ = SecItemDelete(KeychainVault.item(plane) as CFDictionary)
            let added = SecItemAdd(item as CFDictionary, nil)
            return (added == errSecSuccess, "an item of the earlier shape was kept: \(added)")
        }
        var query = KeychainVault.item(plane)
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if wanted == "gone" { return (status == errSecItemNotFound, "the Keychain answered \(status)") }
        guard status == errSecSuccess, let item = found as? [String: Any] else { return (false, "the Keychain answered \(status)") }
        let group = item[kSecAttrAccessGroup as String] as? String ?? ""
        let session = try? KeychainVault().session(for: plane)
        let left = (session?.expiresAt.timeIntervalSinceNow ?? 0) / 3600
        let checks = [
            "a session for uday ending 30 days from now": session?.fde == "uday" && abs(left - 30 * 24) < 1,
            "service": item[kSecAttrService as String] as? String == KeychainVault.service,
            "account": item[kSecAttrAccount as String] as? String == plane.description,
            "when unlocked, this device only": item[kSecAttrAccessible as String] as? String == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String,
            "not synchronizable": (item[kSecAttrSynchronizable as String] as? Bool ?? false) == false,
            "the app's default access group": group.hasSuffix(".sh.standardapplied.sail") && group.split(separator: ".").first?.count == 10,
        ]
        let failed = checks.filter { !$0.value }.keys.sorted()
        return (failed.isEmpty, failed.isEmpty ? "the item is \(checks.keys.sorted().joined(separator: ", "))" : "the item is not: \(failed.joined(separator: ", "))")
    }

    /// `view` drawn by its layer into an sRGB bitmap at twice its size.
    static func drawn(_ view: NSView) -> CGContext? {
        view.display()
        let scale = 2
        let width = Int(view.bounds.width) * scale, height = Int(view.bounds.height) * scale
        guard width > 0, height > 0, let layer = view.layer, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
              )
        else { return nil }
        context.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
        layer.render(in: context)
        return context
    }

    /// The window's content as a PNG, for the owner to judge the look and for a failed step.
    func windowImage() -> Data? {
        guard let content = shell.window.contentView, let image = Self.drawn(content)?.makeImage() else { return nil }
        return NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])
    }

    /// Scenario 18: the sign-in button drawn into an sRGB bitmap, its commonest colour the fill and
    /// the colour furthest from it the label, each within 2 of 255 of the token (the label `onAccent`:
    /// white in the light appearance, ink in the dark), whatever the system accent is.
    private func accent() -> (Bool, String) {
        guard let context = Self.drawn(shell.welcome.primary), let bytes = context.data else { return (false, "the button could not be drawn") }
        let width = context.width, height = context.height
        let pixels = bytes.bindMemory(to: UInt8.self, capacity: width * height * 4)
        var counts: [UInt32: Int] = [:]
        for index in 0 ..< width * height where pixels[index * 4 + 3] == 255 {
            counts[Self.pixel(pixels, index * 4), default: 0] += 1
        }
        guard let fill = counts.max(by: { $0.value < $1.value })?.key,
              let label = counts.keys.max(by: { Self.distance($0, fill) < Self.distance($1, fill) })
        else { return (false, "the button drew nothing opaque") }
        let wanted = Self.hex(shell.look.onAccent)
        let system = NSColor.controlAccentColor.usingColorSpace(.sRGB).map {
            String(format: "%02X%02X%02X", Int($0.redComponent * 255), Int($0.greenComponent * 255), Int($0.blueComponent * 255))
        } ?? "unknown"
        return (
            Self.near(fill, 0xFC4926) && Self.near(label, wanted),
            String(format: "fill #%06X, label #%06X (wanted #%06X); the system accent is #", fill, label, wanted) + system
        )
    }

    /// A: the window's chrome is the look's, laid out as Mast's shell, in light and then dark. The
    /// title bar is transparent over a full-size content view in the compact unified style with the
    /// platform's buttons shown; the band is the chrome under the buttons, in the middle and at the
    /// end, and the rail under it is too; and the band's geometry holds (``bandGeometry()``). The top of
    /// the window, the band and the card's first rows, is saved in each look as `<name>-<look>.png`
    /// (`-rtl` right to left) beside the window's other shots.
    private func titleBar(_ name: String) async -> (Bool, String) {
        let window = shell.window
        guard window.styleMask.contains(.fullSizeContentView), window.titlebarAppearsTransparent, window.toolbarStyle == .unifiedCompact,
              [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton].allSatisfy({ window.standardWindowButton($0)?.isHidden == false })
        else { return (false, "the window's title bar is the system's, not compact, or a window button is missing") }
        var held = true, said: [String] = []
        let (taken, shots) = await inBothLooks(name) { file in
            let (chrome, chromeSaid) = self.bandIsChrome(), (geometry, geometrySaid) = self.bandGeometry()
            held = held && chrome && geometry
            said.append("\(self.shell.look == .dark ? "dark" : "light"): \(chromeSaid); \(geometrySaid)")
            await self.capture(.window(window.windowNumber), to: file)
            Self.keepTheTop(of: file, points: ShellApp.bandHeight + 48, window: window)
        }
        return (held && taken, said.joined(separator: " | ") + "; " + shots)
    }

    /// The band under the buttons, in its middle and at its end, and the rail under it, wherever
    /// the direction put the buttons and the rail, sampled in the chrome.
    private func bandIsChrome() -> (Bool, String) {
        let window = shell.window
        guard let content = window.contentView, let context = Self.drawn(content), let bytes = context.data else { return (false, "the window drew nothing") }
        let pixels = bytes.bindMemory(to: UInt8.self, capacity: context.width * context.height * 4)
        let band = Double(content.bounds.height - window.contentLayoutRect.maxY)
        func at(_ x: Double, _ y: Double) -> UInt32 {
            Self.pixel(pixels, (Int(y * 2) * context.width + Int(x * 2)) * 4)
        }
        let chrome = Self.hex(shell.look.chrome)
        let buttons = Double(ShellApp.buttons(of: window)?.midX ?? 40), rail = Double(shell.rail.convert(shell.rail.bounds, to: nil).midX)
        let samples = [("under the buttons", at(buttons, band / 2)), ("mid-band", at(Double(content.bounds.width) / 2, band / 2)),
                       ("the band's end", at(Double(content.bounds.width) - 6, band / 2)), ("the rail", at(rail, band + 4))]
        return (
            samples.allSatisfy { Self.near($0.1, chrome) },
            samples.map { "\($0.0) #" + String(format: "%06X", $0.1) }.joined(separator: ", ") + String(format: " (wanted #%06X)", chrome)
        )
    }

    /// The band as AppKit and the shell laid it out, said in window points: its height (AppKit's,
    /// or in full screen the one the window draws) against `bandHeight`; each window button's frame
    /// and its centre against the band's, within 1 pt; the shown tabs' centres, within half a point;
    /// the room from the buttons to the nearest of the strip's tabs and `+`, `buttonsGap` within half a point;
    /// the card's top flush under the band; and no view of the shell over a button.
    func bandGeometry() -> (Bool, String) {
        let window = shell.window
        guard let root = window.contentView, let card = shell.thisMac.superview else { return (false, "the window has no content") }
        root.layoutSubtreeIfNeeded()
        let full = window.styleMask.contains(.fullScreen), height = Double(root.bounds.height)
        let band = full ? Double(shell.thisMac.strip.frame.height) : height - Double(window.contentLayoutRect.maxY)
        let middle = height - band / 2, cardTop = height - Double(card.convert(card.bounds, to: nil).maxY)
        let strip = shell.thisMac.strip
        let controls = ((strip.tabs as [NSView]) + [strip.add]).filter { !$0.isHiddenOrHasHiddenAncestor }.map { $0.convert($0.bounds, to: nil) }
        var misses: [String] = []
        var said = [String(format: "the band %.1f pt", band), String(format: "the card's top at %.1f", cardTop)]
        if abs(band - ShellApp.bandHeight) >= 0.5 { misses.append(String(format: "the band is %.1f pt, not %.0f", band, ShellApp.bandHeight)) }
        if abs(cardTop - band) >= 0.5 { misses.append("the card is not flush under the band") }
        for tab in strip.tabs where !tab.isHiddenOrHasHiddenAncestor {
            let frame = tab.convert(tab.bounds, to: nil)
            if abs(Double(frame.midY) - middle) >= 0.5 { misses.append(String(format: "a tab's centre is %.1f pt off the band's", Double(frame.midY) - middle)) }
        }
        if let tab = strip.tabs.first(where: { !$0.isHiddenOrHasHiddenAncestor }) {
            let frame = tab.convert(tab.bounds, to: nil)
            said.append(String(format: "the first tab x %.1f–%.1f, %.1f pt high, centred %+.1f", frame.minX, frame.maxX, frame.height, Double(frame.midY) - middle))
        }
        if !full, let buttons = ShellApp.buttons(of: window) {
            for (kind, named) in [(NSWindow.ButtonType.closeButton, "close"), (.miniaturizeButton, "minimise"), (.zoomButton, "zoom")] {
                guard let button = window.standardWindowButton(kind) else { continue }
                let frame = button.convert(button.bounds, to: nil)
                said.append(named + String(format: " x %.1f–%.1f y %.1f from the top, centred %+.1f", frame.minX, frame.maxX, height - frame.maxY, Double(frame.midY) - middle))
                if abs(Double(frame.midY) - middle) >= 1 { misses.append("\(named) is not centred in the band") }
                for view in [shell.rail, card, strip] as [NSView] where view.convert(view.bounds, to: nil).intersects(frame) {
                    misses.append("\(type(of: view)) lies over \(named)")
                }
            }
            let onTheRight = buttons.midX > root.bounds.midX
            let gaps = controls.map { onTheRight ? Double(buttons.minX - $0.maxX) : Double($0.minX - buttons.maxX) }
            if let gap = gaps.min() {
                said.append(String(format: "%.1f pt from the buttons to the nearest tab", gap) + (onTheRight ? ", the buttons on the right" : ""))
                if abs(gap - ShellApp.buttonsGap) >= 0.5 {
                    misses.append(String(format: "the nearest tab is %.1f pt from the buttons, not %.0f", gap, ShellApp.buttonsGap))
                }
            }
        }
        return (misses.isEmpty, (misses.isEmpty ? "" : "MISSED " + misses.joined(separator: ", ") + "; ") + said.joined(separator: ", "))
    }

    /// The shot in `file` cut to the window's top `points`.
    private static func keepTheTop(of file: URL, points: Double, window: NSWindow) {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return }
        let scale = Double(image.width) / Double(window.frame.width)
        guard let top = image.cropping(to: CGRect(x: 0, y: 0, width: Double(image.width), height: (points * scale).rounded())),
              let png = NSBitmapImageRep(cgImage: top).representation(using: .png, properties: [:])
        else { return }
        try? png.write(to: file)
    }

    /// The sRGB colour at byte `offset` of an RGBA bitmap, as `0xRRGGBB`.
    private static func pixel(_ pixels: UnsafeMutablePointer<UInt8>, _ offset: Int) -> UInt32 {
        let red = UInt32(pixels[offset]), green = UInt32(pixels[offset + 1]), blue = UInt32(pixels[offset + 2])
        return red << 16 | green << 8 | blue
    }

    private static func channels(_ rgb: UInt32) -> [Int] {
        [Int(rgb >> 16 & 0xFF), Int(rgb >> 8 & 0xFF), Int(rgb & 0xFF)]
    }

    private static func near(_ found: UInt32, _ wanted: UInt32) -> Bool {
        zip(channels(found), channels(wanted)).allSatisfy { pair in abs(pair.0 - pair.1) <= 2 }
    }

    private static func distance(_ one: UInt32, _ other: UInt32) -> Int {
        zip(channels(one), channels(other)).reduce(0) { sum, pair in sum + abs(pair.0 - pair.1) }
    }

    private static func hex(_ rgb: RGB) -> UInt32 {
        let red = UInt32(rgb.red), green = UInt32(rgb.green), blue = UInt32(rgb.blue)
        return red << 16 | green << 8 | blue
    }

    /// Scenario 17: the session token, `sess_` and the enrollment ticket searched for in the
    /// process's own memory of what it shows, every file named for the bundle id under the Library
    /// folders, crash reports of Sail, `$TMPDIR`, the drive's output and its printed lines, the user
    /// defaults as cfprefsd answers them, the last 15 minutes of the system log at every level,
    /// every process's arguments and the pasteboard; and every login value a step pasted into Store
    /// a Login, in all of those but the pasteboard, where the drive itself put it (a step checks
    /// that the app cleared it). Each place found is reported with which kind of secret was in it
    /// (a session token, the session prefix, the ticket, a login's value), never the secret, nor
    /// the prefix's own letters: an earlier run's report that spelled them was found by a later sweep. An image is
    /// searched as its bytes, which a drawn token would not be; the pasteboard is never written by
    /// the drive but for a login's value, so it shows only that the app did not write there.
    private func sweep() -> (Bool, String) {
        let ticket = Self.secret("SAIL_DRIVE_TICKET").map { [Data($0.utf8)] } ?? []
        var needles: [(name: String, bytes: Data)] = tokens.map { (name: "a session token", bytes: Data($0.utf8)) }
        needles.append((name: "the session prefix", bytes: Data("sess_".utf8)))
        needles += ticket.map { (name: "the ticket", bytes: $0) }
        let logins = pastedLogins.map { (name: "a login's value", bytes: Data($0.utf8)) }
        var places: [String] = []
        func search(_ data: Data, at place: String, pasted: Bool = true) {
            let found = Set((needles + (pasted ? logins : [])).filter { data.range(of: $0.bytes) != nil }.map(\.name)).sorted()
            if !found.isEmpty { places.append(found.joined(separator: " and ") + " in " + place) }
        }
        search(Data(shell.account.text.utf8), at: "the account pane")
        search(Data(shell.welcome.text.utf8), at: "the welcome")
        search(Data(shell.workspaces.text.utf8), at: "the workspaces section")
        search(Data(results.map(\.detail).joined(separator: "\n").utf8), at: "the drive's report")
        let library = FileManager.default.homeDirectoryForCurrentUser.appending(path: "Library")
        let named = ["Preferences", "Caches", "HTTPStorages", "Application Support", "Saved Application State"].flatMap {
            Self.files(under: library.appending(path: $0)).filter { $0.path.contains("sh.standardapplied.sail") }
        }
        let crashes = Self.files(under: library.appending(path: "Logs/DiagnosticReports")).filter { $0.lastPathComponent.hasPrefix("Sail") }
        let outputs = Self.files(under: out.deletingLastPathComponent()).filter { $0.lastPathComponent.hasSuffix("-output.txt") }
        let searched = named + crashes + Self.files(under: out) + outputs + Self.files(under: FileManager.default.temporaryDirectory)
        for file in searched {
            if let data = try? Data(contentsOf: file, options: .mappedIfSafe) { search(data, at: file.path) }
        }
        let files = searched.count
        search(Self.output(of: "/usr/bin/log", ["show", "--last", "15m", "--info", "--debug", "--style", "compact"]), at: "log show --last 15m --info --debug")
        search(Self.output(of: "/usr/bin/defaults", ["export", "sh.standardapplied.sail", "-"]), at: "defaults export sh.standardapplied.sail")
        search(Self.output(of: "/bin/ps", ["-axww", "-o", "args"]), at: "ps -axww -o args")
        search(Data((NSPasteboard.general.string(forType: .string) ?? "").utf8), at: "the pasteboard", pasted: false)
        let scope = "\(tokens.count) token(s), the session prefix, \(ticket.count) ticket and \(logins.count) login value(s) in \(files) files, the log, the defaults, the processes and the pasteboard"
        return (places.isEmpty, places.isEmpty ? "found in none of \(scope)" : "found in \(places.joined(separator: ", ")) of \(scope)")
    }

    /// A secret the drive was handed: in its environment as `name`, or in the file `name_FILE`
    /// names (a launch by LaunchServices passes its environment on where it may be seen, so CI
    /// hands the ticket in a file); nil when neither holds one.
    static func secret(_ name: String) -> String? {
        let environment = ProcessInfo.processInfo.environment
        let value = environment[name] ?? environment[name + "_FILE"].flatMap { try? String(contentsOfFile: $0, encoding: .utf8) }
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func files(under folder: URL) -> [URL] {
        guard let walk = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: [.isRegularFileKey]) else { return [] }
        return walk.compactMap { $0 as? URL }.filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
    }

    private static func output(of tool: String, _ words: [String]) -> Data {
        let process = Process()
        process.executableURL = URL(filePath: tool)
        process.arguments = words
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return Data() }
        let data = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return data
    }
}
