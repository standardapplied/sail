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
        guard let fonts = Bundle.main.resourceURL?.appending(path: "Fonts"), let mono = try? LabelFont(directory: fonts) else {
            return (false, "the drive's bundle has no fonts")
        }
        let motion = Motion.shared, was = motion.reduced
        motion.reduced = true
        defer { motion.reduced = was }
        var said: [String] = []
        for (name, look) in [("light", Look.light), ("dark", Look.dark), ("light-contrast", Look.light.contrasted()), ("dark-contrast", Look.dark.contrasted())] {
            let kit = Kit(sheet, look: look, mono: mono)
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
        for specimen in kit.specimens where specimen.focused {
            guard await tab(to: specimen, in: window, skipping: kit.specimens.filter { $0.state.contains("disabled") }) else {
                return (false, "Tab never brought the keys to \(specimen.name), or gave them to a disabled control on the way")
            }
            if specimen.view is SailTextField, let right = Keys.named("arrowRight", modifiers: []) { send(right) }
            guard let shot = await take(window, kit: kit, as: nil) else { return (false, "the window could not be taken") }
            cut += save(shot, specimen, window: window, as: "\(specimen.name)-\(look)") ? 1 : 0
            if specimen.view is SailButton {
                let before = kit.presses.count
                key(" ")
                guard await waitUntil(2, { kit.presses.count == before + 1 }) else { return (false, "Space did not press \(specimen.name)") }
            }
        }
        let wanted = kit.specimens.count
        return (cut == wanted, "\(cut) of \(wanted) states cut at \(String(format: "%.0f", window.backingScaleFactor))x")
    }

    /// Hover, a press, disabled, in flight, a refusal, or text typed into a field.
    private func prepare(_ specimen: Specimen, in window: NSWindow) async -> Bool {
        let hover = NSEvent.enterExitEvent(
            with: .mouseEntered, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 0,
            trackingNumber: 0, userData: nil
        )
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
        let frame = specimen.cell.convert(specimen.cell.bounds, to: nil)
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

    var focused: Bool {
        state.contains("focused")
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
    let presses = KitPresses()
    private(set) var specimens: [Specimen] = []
    private var rows: [NSView] = []

    init(_ sheet: String, look: Look, mono: LabelFont) {
        self.sheet = sheet
        self.look = look
        self.mono = mono
        switch sheet {
        case "button": buttons()
        case "field": fields()
        case "pill": pills()
        case "spinner": spinners()
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
            held("button-small-create", button("Create", .primary, size: .small)), held("button-symbol-build-template", button("Build Template", .primary, symbol: "hammer")),
            held("button-large-sign-in", button("Sign In with Passkey", .primary, size: .large)),
            held("button-symbol-new-workspace", button("New Workspace", .secondary, symbol: "plus")),
            held("button-icon-list-again", SailButton(icon: "arrow.clockwise", label: "List Again", look: look, target: presses, action: #selector(KitPresses.press(_:)))),
            held("button-icon-more", SailButton(icon: "ellipsis", label: "More", size: .small, look: look, target: presses, action: #selector(KitPresses.press(_:)))),
            held("button-hint-open", open), held("button-hint-check-again", check),
        ])
    }

    private func button(_ title: String, _ kind: ButtonKind, size: ButtonSize = .regular, symbol: String? = nil) -> SailButton {
        SailButton(title, kind: kind, size: size, symbol: symbol, look: look, target: presses, action: #selector(KitPresses.press(_:)))
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
    private func row(_ caption: String, _ added: [Specimen]) {
        let label = NSTextField(labelWithAttributedString: NSAttributedString(string: caption.uppercased(), attributes: [
            .font: mono.font(Look.Step.caption.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary), .kern: 0.6,
        ]))
        let line = NSStackView(views: added.map { $0.cell })
        line.spacing = 2
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
        super.init(frame: NSRect(x: 0, y: 0, width: 440, height: 55))
        let bars: [(Double, Double, Double, Double)] = [(21, 15, 64, 10), (21, 32, 104, 8), (177, 19, 74, 18), (281, 23, 60, 10)]
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
        NSSize(width: 440, height: 55)
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
