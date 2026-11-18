import AppKit
import ImageIO
import SailControls
import SailLook
import Synchronization

/// The controls' gallery: one sheet of controls in every state, in a window of its own for each
/// look (light, dark, and each under Increase Contrast), with motion reduced so every shot is
/// still. Focus is reached by Tab, text by typing, and Space presses each focused button. Hover and
/// a press are set through the control's own `mouseEntered` and its highlight: the window server,
/// not `sendEvent`, makes a tracking area's enter events, and moving the real pointer needs the
/// Accessibility permission the drive is built not to need; a real click ends its press before a
/// shot can see it. The unit tests check the tracking area is the box. The window is taken
/// as the screen shows it (`screencapture -l`), at rest and once per focused specimen, and cut into
/// `<control>-<state>-<look>.png` beside the whole sheet's `<sheet>-<look>.png`.
extension Driver {
    func performKit(_ step: Step) async -> (Bool, String)? {
        guard let sheet = step.kit else { return nil }
        guard NSApplication.shared.isFullKeyboardAccessEnabled else {
            return (false, "keyboard navigation is off, so no button takes the keys: ci/drive turns it on for the kit run")
        }
        guard let fonts = Bundle.main.resourceURL?.appending(path: "Fonts"), let mono = try? LabelFont(directory: fonts),
              let title = try? TitleFont(directory: fonts)
        else { return (false, "the drive's bundle has no fonts") }
        let motion = Motion.shared, was = motion.reduced
        motion.reduced = true
        defer { motion.reduced = was }
        if sheet == "more-click" { return await moreClick(mono: mono, title: title) }
        var said: [String] = []
        for (name, look) in [("light", Look.light), ("dark", Look.dark), ("light-contrast", Look.light.contrasted()), ("dark-contrast", Look.dark.contrasted())] {
            let kit = Kit(sheet, look: look, mono: mono, title: title)
            guard !kit.specimens.isEmpty else { return (false, "no sheet named \(sheet)") }
            let (ok, detail) = await shoot(kit, look: name)
            said.append("\(name): \(detail)")
            guard ok else { return (false, said.joined(separator: "; ")) }
        }
        return (true, said.joined(separator: "; "))
    }

    private func shoot(_ kit: Kit, look: String) async -> (Bool, String) {
        let window = kit.window
        window.makeKeyAndOrderFront(nil)
        defer { window.orderOut(nil) }
        guard await waitUntil(5, { window.isKeyWindow }) else { return (false, "the gallery's window never became key") }
        for specimen in kit.specimens {
            guard await prepare(specimen, in: window) else { return (false, "\(specimen.name) could not be put in its state") }
        }
        window.makeFirstResponder(nil)
        kit.fit()
        guard await waitUntil(2, { kit.specimens.allSatisfy { $0.settled } }) else { return (false, "a skeleton never showed") }
        var cut = 0
        guard let rest = await take(window, kit: kit, as: "\(kit.sheet)-\(look)") else { return (false, "the window could not be taken") }
        for specimen in kit.specimens where !specimen.focused {
            cut += save(rest, specimen, window: window, as: "\(specimen.name)-\(look)") ? 1 : 0
        }
        var keys: [String] = []
        for specimen in kit.specimens where specimen.focused {
            guard await tab(to: specimen, in: window, skipping: kit.specimens.filter { $0.state.contains("disabled") }) else {
                return (false, "Tab never brought the keys to \(specimen.name), or gave them to a disabled control on the way")
            }
            if let list = specimen.list, let id = specimen.id {
                guard choose(id, by: "arrowDown", in: list) else { return (false, "↓ never brought the choice to \(id) in \(specimen.name); it is on \(list.chosen ?? "nothing")") }
            }
            if specimen.view is SailTextField, let right = Keys.named("arrowRight", modifiers: []) { send(right) }
            guard let shot = await take(window, kit: kit, as: nil) else { return (false, "the window could not be taken") }
            cut += save(shot, specimen, window: window, as: "\(specimen.name)-\(look)") ? 1 : 0
            if specimen.view is SailButton {
                let before = kit.presses.count
                key(" ")
                guard await waitUntil(2, { kit.presses.count == before + 1 }) else { return (false, "Space did not press \(specimen.name)") }
            }
            if let list = specimen.list {
                guard let said = rowKeys(list, presses: kit.presses) else { return (false, "the row keys of \(specimen.name) did not do what they say: \(list.chosen ?? "nothing") chosen") }
                keys.append(said)
            }
        }
        let wanted = kit.specimens.count
        return (cut == wanted, "\(cut) of \(wanted) states cut at \(String(format: "%.0f", window.backingScaleFactor))x" + (keys.isEmpty ? "" : "; " + keys.joined(separator: "; ")))
    }

    /// A real click on a chosen row's ⋯ with its tooltip showing: the down sent through the window to
    /// the button, whose own tracking takes the queued up and sends its action, which opens the menu.
    /// The chip must be gone as the menu opens; the screen is shot while the menu is up, and the
    /// menu window's distance under the button's box is said.
    private func moreClick(mono: LabelFont, title: TitleFont) async -> (Bool, String) {
        let kit = Kit("row", look: .light, mono: mono, title: title)
        let window = kit.window
        window.makeKeyAndOrderFront(nil)
        defer { window.orderOut(nil) }
        guard await waitUntil(5, { window.isKeyWindow }) else { return (false, "the gallery's window never became key") }
        guard let list = kit.specimens.first(where: { $0.name == "row-chosen-quiet" })?.list else { return (false, "the row sheet has no quiet chosen row") }
        list.choose("dock")
        list.layoutSubtreeIfNeeded()
        let buttons = Self.views(in: list).compactMap { $0 as? SailButton }
        guard let more = buttons.first(where: { $0.accessibilityLabel() == "More" && !$0.isHiddenOrHasHiddenAncestor }), let tooltip = more.tooltip, let menu = more.menu
        else { return (false, "dock's row shows no ⋯ with a tooltip and a menu") }
        let hover = NSEvent.enterExitEvent(
            with: .mouseEntered, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 0, trackingNumber: 0,
            userData: nil
        )
        if let hover { tooltip.mouseEntered(with: hover) }
        guard await waitUntil(3, { tooltip.isShown }) else { return (false, "⋯'s tooltip never showed") }
        let watch = MenuWatch(tooltip)
        menu.delegate = watch
        let middle = more.convert(NSPoint(x: more.bounds.midX, y: more.bounds.midY), to: nil)
        func click(_ type: NSEvent.EventType) -> NSEvent? {
            NSEvent.mouseEvent(
                with: type, location: middle, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil,
                eventNumber: 0, clickCount: 1, pressure: type == .leftMouseDown ? 1 : 0
            )
        }
        guard let down = click(.leftMouseDown), let up = click(.leftMouseUp) else { return (false, "no click could be made") }
        let shot = Shot.begin(.menu, to: out.appending(path: "more-click-light.png"))
        let measure = Timer(timeInterval: 1.5, repeats: false) { _ in
            MainActor.assumeIsolated { watch.frame = NSApp.windows.first { $0.isVisible && $0.level.rawValue >= NSWindow.Level.popUpMenu.rawValue }?.frame }
        }
        RunLoop.main.add(measure, forMode: .common)
        menu.perform(#selector(NSMenu.cancelTracking), with: nil, afterDelay: 2.5, inModes: [.common])
        NSApp.postEvent(up, atStart: false)
        window.sendEvent(down)
        await shot?.taken()
        menu.delegate = nil
        guard watch.opened else { return (false, "the click on ⋯ opened no menu") }
        guard !watch.chipShown else { return (false, "⋯'s tooltip was still showing as its menu opened") }
        let box = window.convertToScreen(more.convert(more.alignmentRect(forFrame: more.bounds), to: nil))
        let under = watch.frame.map { String(format: "its window's top %.1f pt under the box, its left %.1f pt from the box's", Double(box.minY - $0.maxY), Double($0.minX - box.minX)) }
        return (shot != nil, "the click opened ⋯'s menu with its tooltip gone; " + (under ?? "AppKit lists no menu window to measure") + "; shot more-click-light.png")
    }

    static func views(in view: NSView) -> [NSView] {
        view.subviews + view.subviews.flatMap { views(in: $0) }
    }

    /// ↓ (or another key) pressed until `id` is chosen, at most once for each row.
    private func choose(_ id: String, by name: String, in list: SailList) -> Bool {
        guard let press = Keys.named(name, modifiers: []) else { return false }
        for _ in 0 ... list.entries.count where list.chosen != id {
            send(press)
        }
        return list.chosen == id
    }

    /// End, Home, type-select and Return on the list holding the keys, each checked by what is
    /// chosen or pressed after it; what was found, or nil when a key did not do its part.
    private func rowKeys(_ list: SailList, presses: KitPresses) -> String? {
        let items = list.entries.compactMap { entry -> SailList.Item? in
            guard case let .item(item) = entry else { return nil }
            return item
        }
        guard let first = items.first, let last = items.last, let end = Keys.named("end", modifiers: []), let home = Keys.named("home", modifiers: []) else { return nil }
        send(end)
        guard list.chosen == last.id else { return nil }
        send(home)
        guard list.chosen == first.id else { return nil }
        let middle = items[items.count / 2]
        if let letter = middle.name.first, items.first(where: { $0.name.first == letter })?.id == middle.id {
            key(letter)
            guard list.chosen == middle.id else { return nil }
        }
        let before = presses.count
        guard let enter = Keys.named("enter", modifiers: []) else { return nil }
        send(enter)
        let pressed = presses.count == before + 1
        guard pressed || !middle.actions.contains(where: { $0.onRow }) else { return nil }
        return "End, Home, type-select (\(middle.name)) and Return (\(pressed ? "pressed" : "nothing to press")) on the list"
    }

    /// Hover, a press, disabled, in flight, a refusal, or text typed into a field.
    private func prepare(_ specimen: Specimen, in window: NSWindow) async -> Bool {
        let hover = NSEvent.enterExitEvent(
            with: .mouseEntered, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 0,
            trackingNumber: 0, userData: nil
        )
        if let list = specimen.list {
            if specimen.state.contains("hover"), let hover { specimen.crop.mouseEntered(with: hover) }
            if specimen.state.contains("chosen") { list.choose(specimen.id) }
            return true
        }
        if specimen.state.contains("hover"), let hover { specimen.view.mouseEntered(with: hover) }
        if let button = specimen.view as? SailButton {
            if specimen.state.contains("pressed") { button.highlight(true) }
            if let word = specimen.busy { button.fly(word) }
            if specimen.state.contains("disabled") { button.isEnabled = false }
            return true
        }
        guard let field = specimen.view as? SailTextField else { return true }
        if let text = specimen.text {
            window.makeFirstResponder(field.field)
            for character in text { key(character) }
            guard field.field.stringValue == text else { return false }
            window.makeFirstResponder(nil)
        }
        if let error = specimen.error { field.error = error }
        if specimen.state.contains("disabled") { field.isEnabled = false }
        return true
    }

    /// Tab pressed until `specimen` holds the keys; false if it never does or a disabled control
    /// takes them on the way.
    private func tab(to specimen: Specimen, in window: NSWindow, skipping disabled: [Specimen]) async -> Bool {
        window.makeFirstResponder(nil)
        for _ in 0 ..< 60 {
            if disabled.contains(where: { $0.holdsTheKeys(window) }) { return false }
            if specimen.holdsTheKeys(window) { return true }
            key("\t")
        }
        return specimen.holdsTheKeys(window)
    }

    /// The window as the screen shows it, once drawn and committed; saved whole under `whole`.
    private func take(_ window: NSWindow, kit: Kit, as whole: String?) async -> CGImage? {
        window.displayIfNeeded()
        let committed = Mutex(false)
        CATransaction.begin()
        CATransaction.setCompletionBlock { committed.withLock { $0 = true } }
        CATransaction.commit()
        _ = await waitUntil(2) { committed.withLock { $0 } }
        let file = out.appending(path: whole.map { "\($0).png" } ?? "focus.png")
        await capture(.window(window.windowNumber), to: file)
        defer { if whole == nil { try? FileManager.default.removeItem(at: file) } }
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }

    /// The specimen's cell cut from `image`, saved as `name`.png.
    private func save(_ image: CGImage, _ specimen: Specimen, window: NSWindow, as name: String) -> Bool {
        let scale = Double(image.width) / Double(window.frame.width)
        let crop = specimen.crop
        let frame = crop.convert(crop.bounds, to: nil)
        let rect = CGRect(
            x: Double(frame.minX) * scale, y: Double(window.frame.height - frame.maxY) * scale, width: Double(frame.width) * scale, height: Double(frame.height) * scale
        )
        guard let cut = image.cropping(to: rect.integral), let png = NSBitmapImageRep(cgImage: cut).representation(using: .png, properties: [:]) else {
            return false
        }
        return (try? png.write(to: out.appending(path: "\(name).png"))) != nil
    }
}

/// A control in one state, the cell around it that is cut out, and what puts it there.
@MainActor
struct Specimen {
    let name: String
    let view: NSView
    let cell: NSView
    var state: [String] = []
    var busy: String?
    var text: String?
    var error: String?

    var list: SailList?
    var row = 0
    var id: String?

    var focused: Bool {
        state.contains("focused")
    }

    /// What is cut out: the row's own view for a list's row, else the cell.
    var crop: NSView {
        guard let list else { return cell }
        list.layoutSubtreeIfNeeded()
        return list.rowView(atRow: row, makeIfNecessary: true) ?? cell
    }

    var settled: Bool {
        (view as? Skeleton).map { $0.alphaValue == 1 } ?? view.subviews.allSatisfy { ($0 as? Skeleton).map { $0.alphaValue == 1 } ?? true }
    }

    func holdsTheKeys(_ window: NSWindow) -> Bool {
        (view as? SailTextField).map { $0.field.currentEditor() != nil } ?? (window.firstResponder === view)
    }
}

/// The counter every gallery button presses.
final class KitPresses: NSObject {
    var count = 0

    @objc func press(_: Any?) {
        count += 1
    }
}

/// One sheet of the gallery in one look: its window, its specimens in reading order.
@MainActor
final class Kit {
    let sheet: String
    let look: Look
    let mono: LabelFont
    let title: TitleFont
    let presses = KitPresses()
    private(set) var specimens: [Specimen] = []
    private var rows: [NSView] = []

    init(_ sheet: String, look: Look, mono: LabelFont, title: TitleFont) {
        self.sheet = sheet
        self.look = look
        self.mono = mono
        self.title = title
        switch sheet {
        case "button": buttons()
        case "field": fields()
        case "pill": pills()
        case "spinner": spinners()
        case "row": listRows()
        case "sidebar": sidebarRows()
        case "tooltip": tooltips()
        case "banner": banners()
        case "empty": empties()
        default: break
        }
    }

    private func buttons() {
        let kinds: [(ButtonKind, String, String)] = [
            (.primary, "primary", "Open"), (.secondary, "secondary", "Start and Open"), (.quiet, "quiet", "Cancel"),
            (.destructive, "destructive", "Delete Workspace"), (.destructiveQuiet, "destructive-quiet", "Stop"),
        ]
        for (kind, name, title) in kinds {
            row("\(name.replacingOccurrences(of: "-", with: ", ")) · rest, hover, pressed, focused, disabled", ["rest", "hover", "pressed", "focused", "disabled"].map { state in
                var specimen = held("button-\(name)-\(state)", button(title, kind))
                specimen.state = [state]
                return specimen
            })
        }
        var opening = held("button-inflight-opening", button("Open", .primary)), starting = held("button-inflight-starting", button("Start and Open", .secondary))
        var creating = held("button-inflight-creating", button("Create", .primary, size: .small))
        opening.busy = "Opening"
        starting.busy = "Starting"
        creating.busy = "Creating"
        row("In flight", [opening, starting, creating])
        let open = button("Open", .primary, size: .small), check = button("Check Again", .secondary)
        open.shortcut("\r", mono: mono)
        check.shortcut("r", modifiers: .command, mono: mono)
        row("Sizes, symbols and hints", [
            held("button-small-create", button("Create", .primary, size: .small)), held("button-symbol-build-template", button("Build Template", .primary, icon: .hammer)),
            held("button-large-sign-in", button("Sign In with Passkey", .primary, size: .large)),
            held("button-symbol-new-workspace", button("New Workspace", .secondary, icon: .plus)),
            held("button-icon-list-again", SailButton(icon: .refresh, label: "List Again", look: look, target: presses, action: #selector(KitPresses.press(_:)))),
            held("button-icon-more", SailButton(icon: .more, label: "More", size: .small, look: look, target: presses, action: #selector(KitPresses.press(_:)))),
            held("button-hint-open", open), held("button-hint-check-again", check),
        ])
    }

    private func button(_ title: String, _ kind: ButtonKind, size: ButtonSize = .regular, icon: LineIcon? = nil) -> SailButton {
        SailButton(title, kind: kind, size: size, icon: icon, look: look, target: presses, action: #selector(KitPresses.press(_:)))
    }

    private func fields() {
        let help = "Lowercase letters, digits and dashes."
        for state in ["rest", "hover", "editing", "error", "disabled"] {
            let field = SailTextField(label: "Name", placeholder: "name of a new workspace", help: help, look: look, mono: mono)
            field.widthAnchor.constraint(equalToConstant: 280).isActive = true
            var specimen = held("field-\(state)", field)
            specimen.state = [state == "editing" ? "focused" : state]
            specimen.text = ["editing": "exp-ui", "error": "dock", "disabled": "harbor"][state]
            specimen.error = state == "error" ? "Use lowercase letters, digits and dashes, starting with a letter or digit." : nil
            row(state, [specimen])
        }
        let secure = SailTextField(label: "Ticket", placeholder: "the ticket an admin gave you", secure: true, look: look, mono: mono)
        let small = SailTextField(label: nil, placeholder: "Find a project", size: .small, look: look, mono: mono)
        secure.widthAnchor.constraint(equalToConstant: 280).isActive = true
        small.widthAnchor.constraint(equalToConstant: 212).isActive = true
        var typed = held("field-secure", secure)
        typed.text = "ticket"
        row("Secure · small", [typed, held("field-small", small)])
    }

    private func pills() {
        let workspace: [PillState] = [.running, .starting, .creating, .stopped, .paused, .incomplete]
        let template: [PillState] = [.noTemplate, .building, .buildFailed]
        row("Workspace states", workspace.map { held("pill-\(Self.slug($0.label))", Pill($0, look: look, mono: mono)) })
        row("Template states · keys", template.map { held("pill-\(Self.slug($0.label))", Pill($0, look: look, mono: mono)) } + [
            held("keycap-command-n", Keycap("⌘N", look: look, mono: mono)), held("keycap-return", Keycap("⏎", look: look, mono: mono)),
            held("initial", Initial("uday", look: look)),
        ])
        row("Sidebar size", template.map { held("pill-small-\(Self.slug($0.label))", Pill($0, size: .small, look: look, mono: mono)) })
    }

    private func spinners() {
        var busy = held("spinner-on-accent", button("Open", .primary, size: .small))
        busy.busy = "Opening"
        row("Spinner · on accent", [held("spinner", Spinner(look: look)), busy])
        let frame = SkeletonFrame(look: look)
        row("Skeleton row", [held("skeleton-row", frame)])
    }

    /// The design's list specimen: a head, pier at rest and under the pointer, and dock chosen with
    /// and without the keys, each list 440 wide.
    private func listRows() {
        let workspace = { (id: String, name: String, state: PillState) in
            SailList.Item(id: id, name: name, voice: "\(name), \(state.label)", content: WorkspaceLine(name, state: state, look: self.look, mono: self.mono), actions: [
                SailList.Action(state == .running ? "Open" : "Start and Open") { self.presses.press(nil) },
                SailList.Action("Copy Name", key: "c", modifiers: .command, onRow: false) {},
            ])
        }
        let (rest, restHeld) = listed([.head("Yours", count: 2), .item(workspace("pier", "pier", .stopped)), .item(workspace("pier-hovered", "pier", .stopped))], .list, width: 440)
        let (keys, keysHeld) = listed([.item(workspace("dock", "dock", .running)), .item(workspace("tide", "tide", .paused)), .item(workspace("quay", "quay", .running))], .list, width: 440)
        let (quiet, quietHeld) = listed([.item(workspace("dock", "dock", .running))], .list, width: 440)
        row("Head · rest · hover", [
            listRow("row-head", rest, restHeld, row: 0), listRow("row-rest", rest, restHeld, row: 1, id: "pier"),
            listRow("row-hover", rest, restHeld, row: 2, id: "pier-hovered", state: ["hover"]),
        ], showing: [restHeld])
        row("Chosen, the list holding the keys", [listRow("row-chosen", keys, keysHeld, row: 0, id: "dock", state: ["focused"])], showing: [keysHeld])
        row("Chosen, the keys elsewhere", [listRow("row-chosen-quiet", quiet, quietHeld, row: 0, id: "dock", state: ["chosen"])], showing: [quietHeld])
    }

    /// The left panel's project rows, 235 wide (its 236 less the hairline), and the design's tiles.
    private func sidebarRows() {
        let project = { (name: String, template: PillState?) in
            SailList.Item(id: name, name: name, voice: name, content: SidebarRowCell(name, template: template, look: self.look, mono: self.mono))
        }
        let projects: [(String, PillState?)] = [("atlas", nil), ("keel", nil), ("harbor", nil), ("demo", .noTemplate), ("quarry", .building), ("beacon", .buildFailed)]
        let (states, statesHeld) = listed(projects.map { .item(project($0.0, $0.1)) }, .sidebar, width: 235)
        row("Rest · hover · chosen, the keys elsewhere · readiness", [
            listRow("sidebar-rest", states, statesHeld, row: 0, id: "atlas"), listRow("sidebar-hover", states, statesHeld, row: 1, id: "keel", state: ["hover"]),
            listRow("sidebar-chosen-quiet", states, statesHeld, row: 2, id: "harbor", state: ["chosen"]),
            listRow("sidebar-no-template", states, statesHeld, row: 3, id: "demo"), listRow("sidebar-building", states, statesHeld, row: 4, id: "quarry"),
            listRow("sidebar-build-failed", states, statesHeld, row: 5, id: "beacon"),
        ], showing: [statesHeld])
        let (keys, keysHeld) = listed(["harbor", "lighthouse", "marina"].map { .item(project($0, nil)) }, .sidebar, width: 235)
        row("Chosen, the list holding the keys", [listRow("sidebar-chosen", keys, keysHeld, row: 0, id: "harbor", state: ["focused"])], showing: [keysHeld])
        let names = ["harbor", "demo", "lighthouse", "marina", "atlas", "keel", "quarry", "beacon", "tidewater", "cove", "7seas"]
        row("Tiles", names.map { held("tile-\($0)", Tile($0, look: look, mono: mono)) })
    }

    /// The approved tooltip chips, each 5 pt from its own control: below an icon-only button, and
    /// beside a rail item (to its left right to left). Each cut is the chip's.
    private func tooltips() {
        let below: [(String, String?, LineIcon)] = [("New Session in dock", "⌘T", .plus), ("List Again", "⌘R", .refresh), ("More", nil, .more)]
        var chips: [Specimen] = [], views: [NSView] = []
        for (words, hint, icon) in below {
            let chip = held("tooltip-\(Self.slug(words))", TooltipChip(words, hint: hint, side: .below, look: look, mono: mono))
            views.append(Self.hung(chip.cell, from: held("", SailButton(icon: icon, label: words, size: .small, look: look, target: nil, action: nil)).cell, beside: false))
            chips.append(chip)
        }
        let rightToLeft = NSApp.userInterfaceLayoutDirection == .rightToLeft
        let beside = held("tooltip-workspaces", TooltipChip("Workspaces", hint: "⌘1", side: rightToLeft ? .left : .right, look: look, mono: mono))
        views.append(Self.hung(beside.cell, from: held("", SailButton(icon: .box, label: "Workspaces", look: look, target: nil, action: nil)).cell, beside: true))
        row("Below an icon-only control · beside a rail item", chips + [beside], showing: views, spacing: 40)
    }

    /// `chip` 5 pt from `control`'s box (each cell keeps 4 pt of room around its view): under it,
    /// or beside it in reading order.
    private static func hung(_ chip: NSView, from control: NSView, beside: Bool) -> NSView {
        let pair = NSStackView(views: [control, chip])
        pair.orientation = beside ? .horizontal : .vertical
        pair.alignment = beside ? .centerY : .centerX
        pair.spacing = TooltipStyle.gap - 2 * Look.haloRoom
        return pair
    }

    private func banners() {
        let tones: [(BannerTone, String, String, String)] = [
            (.info, "harbor’s template is older than its definition", "New workspaces are created from the older one until it is built again.", "Build Template"),
            (.warning, "This runner is creating another workspace", "That takes about a minute.", "Try Again"),
            (.error, "Couldn’t list harbor’s workspaces", "The connection to main.acme.dev was lost.", "Try Again"),
        ]
        for (tone, title, line, action) in tones {
            let banner = Banner(tone, title: title, line: line, action: button(action, .secondary, size: .small), look: look)
            banner.widthAnchor.constraint(equalToConstant: 440).isActive = true
            row("\(tone)", [held("banner-\(tone)", banner)])
        }
    }

    private func empties() {
        let anatomy = EmptyState(
            icon: .box, title: "No workspaces in harbor yet", body: "A workspace is your own copy of harbor on the runner.",
            actions: [button("New Workspace", .primary, size: .small)], look: look, titleFont: title, mono: mono
        )
        let check = button("Check Again", .secondary)
        check.shortcut("r", modifiers: .command, mono: mono)
        let missing = EmptyState(
            icon: .layers, title: "demo isn’t ready yet", body: "demo has no template yet. An admin builds demo’s template on the runner first; then you can create workspaces here.",
            actions: [check], line: "signed in as ana · member · main.acme.dev", missing: true, look: look, titleFont: title, mono: mono
        )
        row("Anatomy · something missing", [held("empty-anatomy", EmptyFrame(anatomy, look: look)), held("empty-missing", EmptyFrame(missing, look: look))])
    }

    /// A list of `entries` in a holder of its own height, `width` wide.
    private func listed(_ entries: [SailList.Entry], _ style: RowStyle, width: Double) -> (SailList, NSView) {
        let list = SailList(rowStyle: style, look: look, mono: mono)
        list.entries = entries
        let height = Double(list.rect(ofRow: entries.count - 1).maxY)
        list.frame = NSRect(x: 0, y: 0, width: width, height: height)
        let holder = SurfaceView(look: look)
        holder.addSubview(list)
        holder.widthAnchor.constraint(equalToConstant: width).isActive = true
        holder.heightAnchor.constraint(equalToConstant: height).isActive = true
        return (list, holder)
    }

    /// One row of `list`, cut on its own.
    private func listRow(_ name: String, _ list: SailList, _ holder: NSView, row: Int, id: String? = nil, state: [String] = []) -> Specimen {
        var specimen = Specimen(name: name, view: list, cell: holder)
        specimen.list = list
        specimen.row = row
        specimen.id = id
        specimen.state = state
        return specimen
    }

    static func slug(_ label: String) -> String {
        label.lowercased().replacingOccurrences(of: " ", with: "-")
    }

    /// `view` in a cell 4 pt bigger on every side than its alignment rect: the halo's room.
    private func held(_ name: String, _ view: NSView) -> Specimen {
        let cell = NSView()
        view.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(view)
        let room = Look.haloRoom
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: room), view.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -room),
            view.topAnchor.constraint(equalTo: cell.topAnchor, constant: room), view.bottomAnchor.constraint(equalTo: cell.bottomAnchor, constant: -room),
        ])
        return Specimen(name: name, view: view, cell: cell)
    }

    /// A caption in mono capitals, and the specimens in a row under it.
    private func row(_ caption: String, _ added: [Specimen], showing views: [NSView]? = nil, spacing: Double = 2) {
        let label = NSTextField(labelWithAttributedString: NSAttributedString(string: caption.uppercased(), attributes: [
            .font: mono.font(Look.Step.caption.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary), .kern: 0.6,
        ]))
        let line = NSStackView(views: views ?? added.map { $0.cell })
        line.spacing = spacing
        line.alignment = .centerY
        let group = NSStackView(views: [label, line])
        group.orientation = .vertical
        group.alignment = .leading
        group.spacing = 4
        rows.append(group)
        specimens += added
    }

    /// The window sized to the sheet again, once a refusal or typed text changed a specimen's size.
    func fit() {
        stack.layoutSubtreeIfNeeded()
        window.setContentSize(stack.fittingSize)
    }

    private lazy var stack = NSStackView(views: rows)

    lazy var window: NSWindow = {
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 18
        stack.edgeInsets = NSEdgeInsets(top: 22, left: 22, bottom: 26, right: 22)
        let sheet = KitSheet(look: look)
        stack.translatesAutoresizingMaskIntoConstraints = false
        sheet.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: sheet.leadingAnchor), stack.topAnchor.constraint(equalTo: sheet.topAnchor),
        ])
        sheet.layoutSubtreeIfNeeded()
        let size = stack.fittingSize
        let window = KitWindow(contentRect: NSRect(x: 60, y: 60, width: max(size.width, 200), height: max(size.height, 100)), styleMask: [.borderless], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: look.palette.isDark ? .darkAqua : .aqua)
        window.backgroundColor = NSColor(Tint(look.canvas))
        window.autorecalculatesKeyViewLoop = true
        window.contentView = sheet
        window.isReleasedWhenClosed = false
        return window
    }()
}

/// The gallery's window: borderless, so the shot is the sheet alone, and able to take the keys.
final class KitWindow: NSWindow {
    override var canBecomeKey: Bool {
        true
    }
}

/// The sheet's ground, the canvas, read from the top.
final class KitSheet: NSView {
    let look: Look

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_: NSRect) {
        NSColor(Tint(look.canvas)).setFill()
        NSBezierPath(rect: bounds).fill()
    }
}

/// A list's frame holding one row of skeleton bars where a workspace row's name, instance, state
/// and owner will be, as the loading list draws it.
final class SkeletonFrame: NSView {
    let look: Look

    init(look: Look) {
        self.look = look
        super.init(frame: NSRect(x: 0, y: 0, width: 442, height: 55))
        let bars: [(Double, Double, Double, Double)] = [(21, 15, 64, 10), (21, 32, 104, 8), (179, 19, 74, 18), (283, 23, 60, 10)]
        for (x, y, width, height) in bars {
            let bar = Skeleton(width: width, height: height, look: look)
            bar.frame = NSRect(x: x, y: y, width: width, height: height)
            addSubview(bar)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: 442, height: 55)
    }

    override func draw(_: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: Look.contentRadius, yRadius: Look.contentRadius).fill()
        NSColor(look.gridLine).setFill()
        NSBezierPath(rect: NSRect(x: 1, y: 1, width: bounds.width - 2, height: 1)).fill()
        NSColor(look.separator).setStroke()
        NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: Look.contentRadius - 0.5, yRadius: Look.contentRadius - 0.5).stroke()
    }
}

/// A workspace row's words as the design's list specimen sets them: the name in 14 medium over its
/// instance in mono 11, 158 pt, then the state's pill; the labels' frames start 2 pt early, as a
/// label draws its text 2 pt in.
final class WorkspaceLine: NSStackView {
    init(_ name: String, state: PillState, look: Look, mono: LabelFont) {
        let title = NSTextField(labelWithAttributedString: NSAttributedString(string: name, attributes: [
            .font: NSFont.systemFont(ofSize: 14, weight: .medium), .foregroundColor: NSColor(look.text),
        ]))
        let instance = NSTextField(labelWithAttributedString: NSAttributedString(string: "harbor-\(name)", attributes: [
            .font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary),
        ]))
        let words = NSStackView(views: [title, instance])
        words.orientation = .vertical
        words.alignment = .leading
        words.spacing = 3
        words.widthAnchor.constraint(equalToConstant: 160).isActive = true
        super.init(frame: .zero)
        setViews([words, Pill(state, look: look, mono: mono)], in: .leading)
        spacing = 0
        alignment = .centerY
        edgeInsets = NSEdgeInsets(top: 0, left: -2, bottom: 0, right: 0)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }
}

/// The design's frame around an empty state: the surface in 6 pt corners with a hairline, 420 wide
/// inside it and 28 pt above and below.
final class EmptyFrame: NSView {
    let look: Look

    init(_ empty: EmptyState, look: Look) {
        self.look = look
        super.init(frame: .zero)
        empty.translatesAutoresizingMaskIntoConstraints = false
        addSubview(empty)
        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: 422), empty.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 1),
            empty.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -1), empty.topAnchor.constraint(equalTo: topAnchor, constant: 29),
            empty.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -29),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func draw(_: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: Look.contentRadius, yRadius: Look.contentRadius).fill()
        NSColor(look.separator).setStroke()
        NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: Look.contentRadius - 0.5, yRadius: Look.contentRadius - 0.5).stroke()
    }
}

/// The surface a list or the left panel stands on, under the rows the gallery shows.
final class SurfaceView: NSView {
    let look: Look

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func draw(_: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        NSBezierPath(rect: bounds).fill()
    }
}

/// What a ⋯ menu saw as it opened: whether the tooltip still showed, and where its window stood.
@MainActor
final class MenuWatch: NSObject, NSMenuDelegate {
    let tooltip: Tooltip
    var opened = false
    var chipShown = true
    var frame: NSRect?

    init(_ tooltip: Tooltip) {
        self.tooltip = tooltip
    }

    nonisolated func menuWillOpen(_: NSMenu) {
        MainActor.assumeIsolated {
            opened = true
            chipShown = tooltip.isShown
        }
    }
}
