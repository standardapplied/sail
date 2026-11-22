import AppKit
import SailAPI
import SailControls
import SailLook
import SailWorkbench

/// A workspace row's New Session panel (`agent-start-a1-mockup.html`): what its agents route
/// answered, in a 372 pt box 4 pt under the row, its trailing edge on the row's ⋯ (spanning the page
/// less 8 pt where the page is drawn narrow), and over the row when the window has no room under
/// it. It lists each harness offered in the route's order with its login, then the Shell; the
/// chosen one alone shows its mode and the mode's words, or what stops it in the server's sentence.
/// Start, its one primary, starts the chosen harness in its mode, signed in, or to sign in there
/// (Start and Sign In There) when no login is stored; for the Shell it is the row's Open. Where no
/// agent starts for the person it says why, with Open a Shell or Close. The window keeps the keys:
/// ↑ ↓ choose, ⌘M opens the mode's menu, Return presses Start and Escape closes; so do a click
/// outside it, the window giving up the keys or being resized, and the section going.
@MainActor
package final class NewSessionPanel: NSObject {
    static let width = 372.0, gap = 4.0, inset = 8.0

    /// The agents route's answer as the panel takes it: what can start, or why nothing was listed.
    enum Answer: Equatable {
        case agents(WorkspaceAgents)
        case failed(String, remedy: String?)
    }

    var look: Look {
        didSet {
            view.look = look
            popover.look = look
            redraw()
        }
    }

    /// Told the start Start made, and what the person's starts now remember.
    var onStart: @MainActor (AgentStart, ModeMemory) -> Void = { _, _ in }
    /// Told when Start opens the Shell, or Open a Shell is pressed: the row's Open.
    var onShell: @MainActor () -> Void = {}
    /// Shows the mode button's menu: the library's opener, whose menu tracks the pointer; a unit
    /// test, which runs no menu tracking, reads the menu instead.
    var popUp: @MainActor (SailButton) -> Void = { MenuOpener.shared.open($0) }

    package let view: NewSessionView
    package let popover: Popover
    private let keys = NewSessionKeys()
    /// What it shows.
    package private(set) var content: NewSessionView.Content?
    package private(set) var workspace = ""
    private var showsHow = false
    /// The box on screen, and whether it hangs under its row (its top kept as it grows) or over it.
    private var placed = NSRect.zero
    private var below = true
    private var resizeWatch: (any NSObjectProtocol)?

    init(look: Look, mono: LabelFont) {
        self.look = look
        view = NewSessionView(look: look, mono: mono)
        popover = Popover(view, keys: keys, look: look)
        super.init()
        keys.owner = self
        popover.onDismiss = { [weak self] in self?.close() }
        view.on = { [weak self] act in self?.act(act) }
    }

    package var isOpen: Bool {
        popover.isOpen
    }

    /// Opens for `workspace` saying `answer`, under `row` (in `page`'s window, its frame there),
    /// its modes as `memory` remembers them; `anchor`, the row's +, toggles it.
    func open(_ answer: Answer, workspace: String, memory: ModeMemory, under row: NSRect, page: NSView, narrow: Bool, anchor: NSView?) {
        guard let window = page.window, !isOpen else { return }
        self.workspace = workspace
        showsHow = false
        content = Self.content(answer, memory: memory)
        view.direction = page.userInterfaceLayoutDirection
        let area = page.convert(page.bounds, to: nil)
        let width = narrow ? Double(area.width) - 2 * Self.inset : min(Self.width, Double(area.width) - 2 * Self.inset)
        draw(width: width)
        let height = view.height
        let rightToLeft = page.userInterfaceLayoutDirection == .rightToLeft, trailing = RowStyle.list.margin + RowStyle.list.trailing
        let wanted = narrow ? Double(area.minX) + Self.inset : rightToLeft ? Double(row.minX) + trailing : Double(row.maxX) - trailing - width
        let x = min(max(wanted, Double(area.minX) + Self.inset), Double(area.maxX) - Self.inset - width)
        let under = Double(row.minY) - Self.gap - height
        below = under >= Double(area.minY) + Self.inset || Double(row.maxY) + Self.gap + height > Double(area.maxY) - Self.inset
        let box = NSRect(x: x, y: below ? under : Double(row.maxY) + Self.gap, width: width, height: height)
        placed = window.convertToScreen(box)
        popover.open(over: window, anchor: anchor, at: placed, withKeys: false)
        keys.setAccessibilityElement(true)
        keys.setAccessibilityLabel(view.accessibilityLabel())
        resizeWatch = NotificationCenter.default.addObserver(forName: NSWindow.didResizeNotification, object: window, queue: nil) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }
    }

    package func close() {
        guard isOpen else { return }
        if let resizeWatch { NotificationCenter.default.removeObserver(resizeWatch) }
        resizeWatch = nil
        popover.close()
        keys.setAccessibilityElement(false)
    }

    static func content(_ answer: Answer, memory: ModeMemory) -> NewSessionView.Content {
        switch answer {
        case let .failed(sentence, remedy): return .refused(sentence: sentence, remedy: remedy, shell: false)
        case let .agents(agents):
            guard let refusal = agents.refusal else { return .offered(NewSession(agents, memory: memory)) }
            return .refused(sentence: refusal.message, remedy: NewSessionWords.remedy(refusal), shell: refusal.code == .known(.notYourWorkspace))
        }
    }

    private func draw(width: Double) {
        guard let content else { return }
        view.show(content, workspace: workspace, showsHow: showsHow, width: width)
    }

    /// Drawn again where it is, its top kept under its row (its foot over it) as its height changes.
    private func redraw() {
        guard isOpen else { return }
        draw(width: Double(placed.width))
        let height = view.height
        placed = NSRect(x: placed.minX, y: below ? placed.maxY - height : placed.minY, width: placed.width, height: height)
        popover.place(placed)
    }

    private func act(_ act: NewSessionView.Act) {
        guard isOpen, let content else { return }
        switch (act, content) {
        case let (.choose(choice), .offered(panel)):
            var chosen = panel
            chosen.choose(choice)
            self.content = .offered(chosen)
        case let (.mode(mode), .offered(panel)):
            var chosen = panel
            chosen.choose(mode: mode)
            self.content = .offered(chosen)
        case (.how, _):
            showsHow = true
        case (.start, _):
            return start()
        case (.shell, _):
            close()
            return onShell()
        case (.close, _):
            return close()
        default:
            return
        }
        redraw()
    }

    /// Start: the chosen harness started (the person's starts remember it), or the Shell opened.
    private func start() {
        guard case var .offered(panel) = content else { return }
        if panel.readiness == .shell {
            close()
            return onShell()
        }
        guard let start = panel.start() else { return }
        close()
        onStart(start, panel.memory)
    }

    /// The chosen harness's mode menu, ⌘M; none for a harness of one mode.
    private func openModes() {
        guard let button = view.modes else { return }
        popUp(button)
    }

    /// A key while open: ↑ ↓ choose, ⌘M the modes, Return presses Start, Escape closes.
    fileprivate func key(_ event: NSEvent) -> Bool {
        let held = event.modifierFlags.intersection([.command, .option, .control, .shift])
        if held == .command, event.charactersIgnoringModifiers?.lowercased() == "m" {
            openModes()
            return true
        }
        guard held.isEmpty else { return false }
        switch event.keyCode {
        case 125, 126:
            guard case var .offered(panel) = content else { return true }
            panel.move(by: event.keyCode == 125 ? 1 : -1)
            content = .offered(panel)
            redraw()
        case 36, 76:
            start()
        case 53:
            close()
        default:
            return false
        }
        return true
    }
}

/// Takes the window's keys while the panel is open; VoiceOver reads it as the panel.
@MainActor
final class NewSessionKeys: NSView {
    weak var owner: NewSessionPanel?

    override init(frame: NSRect) {
        super.init(frame: frame)
        setAccessibilityElement(false)
        setAccessibilityRole(.popover)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func accessibilityChildren() -> [Any]? {
        guard owner?.isOpen == true else { return nil }
        return owner.map { [$0.view] }
    }

    override var acceptsFirstResponder: Bool {
        true
    }

    override func keyDown(with event: NSEvent) {
        if owner?.key(event) != true { super.keyDown(with: event) }
    }

    /// ⌘M is the panel's while it holds the keys, not the window's Minimize.
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard window?.firstResponder === self, owner?.key(event) == true else { return super.performKeyEquivalent(with: event) }
        return true
    }

    override func cancelOperation(_: Any?) {
        owner?.close()
    }
}

/// The panel's box, on the popover's surface with a strong hairline edge and 8 pt corners: its
/// title, the stopped workspace's line, the rows with the chosen one's detail under it, and the
/// foot with the keys and Start; or, where nothing starts for the person, why, and one button.
/// Built whole by ``show(_:workspace:showsHow:width:)``, every frame set there, so no layout pass
/// changes what it shows; mirrored right to left.
@MainActor
package final class NewSessionView: NSView {
    static let radius = 8.0, padding = 14.0, row = 30.0, gap = 7.0, about = 17.0

    /// What it shows: the route's harnesses and the Shell, or why no agent starts and whether the
    /// workspace still opens as a shell for the person.
    package enum Content: Equatable {
        case offered(NewSession)
        case refused(sentence: String, remedy: String?, shell: Bool)
    }

    enum Act: Equatable {
        case choose(NewSession.Choice), mode(String), how, start, shell, close
    }

    var look: Look {
        didSet { needsDisplay = true }
    }

    var on: @MainActor (Act) -> Void = { _ in }
    var direction: NSUserInterfaceLayoutDirection = .leftToRight

    private let mono: LabelFont
    package private(set) var height = 0.0
    /// The rows, top to bottom, the chosen one marked.
    package private(set) var rows: [NewSessionRow] = []
    /// Start, Start and Sign In There, Open a Shell or Close.
    package private(set) var primary: SailButton?
    package private(set) var secondary: SailButton?
    /// The chosen harness's mode pop-up, nil for a harness of one mode or none chosen.
    package private(set) var modes: SailButton?
    package private(set) var storeLogin: LinkButton?
    /// The hairlines across it, the chosen row's bar, and the stopped line's square, as last laid out.
    private var hairlines: [Double] = []
    private var bar: NSRect?
    private var square: NSRect?
    /// The width it is being built at.
    private var boxWidth = 0.0

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    /// Every word it shows, top to bottom, as the drive reads them.
    package var words: [String] {
        Self.texts(self)
    }

    private static func texts(_ view: NSView) -> [String] {
        ((view as? NSTextField).map { [$0.stringValue] } ?? (view as? NSButton).map { [$0.title] } ?? []) + view.subviews.flatMap(texts)
    }

    /// Builds it for `content` in workspace `workspace`, `width` wide; `showsHow` once Store a Login
    /// was pressed.
    func show(_ content: Content, workspace: String, showsHow: Bool, width: Double) {
        subviews.forEach { $0.removeFromSuperview() }
        rows = []
        primary = nil
        secondary = nil
        modes = nil
        storeLogin = nil
        hairlines = []
        bar = nil
        square = nil
        boxWidth = width
        let edge = Look.hairline, inner = width - 2 * (Self.padding + edge)
        let title = NSMutableAttributedString(attributedString: Self.text(NewSessionWords.title(workspace).lead, .systemFont(ofSize: Look.Step.callout.points, weight: .semibold), look.text, direction: direction))
        title.append(Self.text(" " + NewSessionWords.title(workspace).place, .systemFont(ofSize: Look.Step.callout.points), look.textTertiary, direction: direction))
        setAccessibilityLabel(title.string)
        var y = edge + 12
        y = place(Self.label(title.cut(.byTruncatingTail)), x: edge + Self.padding, y: y, width: inner) + 10
        switch content {
        case let .offered(panel):
            if !panel.agents.running {
                hairlines.append(y)
                let start = edge + Self.padding + 6 + 8
                let words = Self.wrapping(NewSessionWords.stopped(workspace), .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary, width: width - start - Self.padding - edge, direction: direction)
                let top = y + 7
                y = place(words, x: start, y: top, width: width - start - Self.padding - edge) + 8
                square = mirrored(NSRect(x: edge + Self.padding, y: top + (min(Double(words.frame.height), Self.about) - 6) / 2, width: 6, height: 6))
            }
            hairlines.append(y)
            y += 4
            for choice in panel.choices {
                y = list(choice, of: panel, workspace: workspace, showsHow: showsHow, y: y, width: width)
            }
            y += 4
            hairlines.append(y)
            let (title, enabled) = NewSessionWords.primary(panel.readiness)
            let start = SailButton(title, kind: .primary, size: .small, look: look, target: self, action: #selector(pressedStart(_:)))
            start.hint("\r", mono: mono)
            start.isEnabled = enabled
            start.why = enabled ? nil : Self.refusal(panel.readiness)?.message
            primary = start
            let hint = Self.label(Self.text(NewSessionWords.hint, mono.font(Look.Step.micro.points, .regular) as NSFont, look.textTertiary, direction: direction).cut(.byTruncatingTail))
            y = foot(start, hint: hint, y: y, width: width)
        case let .refused(sentence, remedy, shell):
            hairlines.append(y)
            y += 12
            y = place(Self.wrapping(sentence, .systemFont(ofSize: Look.Step.callout.points), look.text, width: inner, direction: direction), x: edge + Self.padding, y: y, width: inner)
            if let remedy {
                y = place(Self.wrapping(remedy, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, width: inner, direction: direction), x: edge + Self.padding, y: y + 10, width: inner)
            }
            y += 12
            hairlines.append(y)
            let button = SailButton(shell ? NewSessionWords.openAShell : "Close", kind: .secondary, size: .small, look: look, target: self, action: shell ? #selector(pressedShell(_:)) : #selector(pressedClose(_:)))
            secondary = button
            y = foot(button, hint: nil, y: y, width: width)
        }
        height = y + edge
        needsDisplay = true
    }

    /// The row of `choice` at `y` and, chosen, its detail under it; where the next row starts.
    private func list(_ choice: NewSession.Choice, of panel: NewSession, workspace: String, showsHow: Bool, y top: Double, width: Double) -> Double {
        let edge = Look.hairline, chosen = panel.chosen == choice
        let offer: AgentOffer? = if case let .harness(harness) = choice { panel.offer(harness) } else { nil }
        let name = offer?.display ?? NewSessionWords.shell.name
        let line = offer.map(NewSessionWords.line) ?? NewSessionWords.shell.line
        let off = offer?.refusal != nil
        let row = NewSessionRow(choice, name: name, line: line, chosen: chosen, off: off, look: look, direction: direction)
        row.onChoose = { [weak self] in self?.on(.choose(choice)) }
        row.frame = mirrored(NSRect(x: edge + 6, y: top, width: width - 2 * (edge + 6), height: Self.row))
        row.lay()
        addSubview(row)
        rows.append(row)
        if chosen { bar = mirrored(NSRect(x: edge, y: top + 8, width: RowStyle.barWidth, height: 14)) }
        guard chosen, let offer, case let .harness(harness) = choice else { return top + Self.row }
        let x = edge + 6 + 10, inner = width - 2 * x
        var y = top + Self.row + 2
        switch panel.readiness {
        case let .refused(refusal):
            y = place(said(refusal.message, width: inner), x: x, y: y, width: inner)
            if let remedy = NewSessionWords.remedy(refusal) { y = place(quiet(remedy, width: inner), x: x, y: y + Self.gap, width: inner) }
        case let .signInThere(_, refusal), let .needsLogin(refusal):
            y = place(said(refusal.message, width: inner), x: x, y: y, width: inner)
            y = logins(offer, showsHow: showsHow, x: x, y: y + Self.gap, width: inner)
            if offer.inside { y = place(quiet(NewSessionWords.signInThere(offer, workspace: workspace), width: inner), x: x, y: y + Self.gap, width: inner) }
        case .start, .shell:
            let mode = panel.mode(of: harness) ?? offer.modeDefault
            y = modeLine(offer, mode: mode, x: x, y: y, width: inner)
            y = place(quiet(NewSessionWords.about(offer, mode: mode), width: inner), x: x, y: y + Self.gap, width: inner)
        }
        return y + 10
    }

    /// "Mode" and the pop-up of the server's labels, or, for a harness of one mode, its label as
    /// words; where the line ends.
    private func modeLine(_ offer: AgentOffer, mode: String, x: Double, y: Double, width: Double) -> Double {
        let label = Self.label(Self.text(NewSessionWords.mode, .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary, direction: direction).cut(.byTruncatingTail))
        let title = offer.modes.first { $0.mode == mode }?.label ?? mode
        let high = ButtonSize.small.height, labelSize = label.fittingSize
        label.frame = mirrored(NSRect(x: x, y: y + (high - Double(labelSize.height)) / 2, width: Double(labelSize.width), height: Double(labelSize.height)))
        addSubview(label)
        let after = x + Double(labelSize.width) + 10, room = x + width - after
        guard offer.modes.count > 1 else {
            let fixed = Self.label(Self.text(title, .systemFont(ofSize: Look.Step.footnote.points, weight: .medium), look.text, direction: direction).cut(.byTruncatingTail))
            let size = fixed.fittingSize
            fixed.frame = mirrored(NSRect(x: after, y: y + (high - Double(size.height)) / 2, width: min(Double(size.width), room), height: Double(size.height)))
            addSubview(fixed)
            return y + high
        }
        let button = SailButton(title, kind: .secondary, size: .small, popUp: true, look: look, target: MenuOpener.shared, action: #selector(MenuOpener.open(_:)))
        let menu = NSMenu()
        for each in offer.modes {
            let item = NSMenuItem(title: each.label, action: #selector(choseMode(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = each.mode
            item.state = each.mode == mode ? .on : .off
            menu.addItem(item)
        }
        button.menu = menu
        button.setAccessibilityLabel("\(NewSessionWords.mode), \(title)")
        button.userInterfaceLayoutDirection = direction
        modes = button
        let box = NSRect(x: after, y: y, width: min(Double(button.intrinsicContentSize.width), room), height: high)
        button.frame = mirrored(box.insetBy(dx: -Look.haloRoom, dy: -Look.haloRoom))
        addSubview(button)
        return y + high
    }

    /// Store a Login and the kinds the server takes beside it, or, once pressed, how to make the
    /// first under it; where it ends.
    private func logins(_ offer: AgentOffer, showsHow: Bool, x: Double, y: Double, width: Double) -> Double {
        let link = LinkButton(NewSessionWords.storeLogin(offer), points: Look.Step.callout.points, look: look, target: self, action: #selector(pressedHow(_:)))
        link.userInterfaceLayoutDirection = direction
        storeLogin = link
        let size = link.fittingSize
        let linkWidth = min(Double(size.width), width)
        link.frame = mirrored(NSRect(x: x, y: y, width: linkWidth, height: Double(size.height)))
        addSubview(link)
        let end = y + Double(size.height)
        guard !showsHow else {
            return NewSessionWords.how(offer).map { place(said($0, width: width), x: x, y: end + Self.gap, width: width) } ?? end
        }
        let kinds = Self.label(Self.text(NewSessionWords.kinds(offer), .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, direction: direction).cut(.byTruncatingTail))
        let kindsSize = kinds.fittingSize, room = width - linkWidth - 10
        guard Double(kindsSize.width) <= room else { return place(kinds, x: x, y: end + 2, width: width) }
        kinds.frame = mirrored(NSRect(x: x + linkWidth + 10, y: y + (Double(size.height) - Double(kindsSize.height)) / 2, width: Double(kindsSize.width), height: Double(kindsSize.height)))
        addSubview(kinds)
        return end
    }

    /// The foot at `y`: the keys at its leading end, its button at the trailing; where it ends.
    private func foot(_ button: SailButton, hint: NSTextField?, y: Double, width: Double) -> Double {
        let edge = Look.hairline, size = button.intrinsicContentSize, room = Look.haloRoom
        let box = NSRect(x: width - edge - 12 - Double(size.width), y: y + 10, width: Double(size.width), height: Double(size.height))
        button.frame = mirrored(box.insetBy(dx: -room, dy: -room))
        button.userInterfaceLayoutDirection = direction
        addSubview(button)
        if let hint {
            let hintSize = hint.fittingSize
            let hintWidth = min(Double(hintSize.width), Double(box.minX) - edge - 12 - 12)
            hint.frame = mirrored(NSRect(x: edge + 12, y: Double(box.midY) - Double(hintSize.height) / 2, width: max(hintWidth, 0), height: Double(hintSize.height)))
            addSubview(hint)
        }
        return Double(box.maxY) + 10
    }

    /// `view` added at `x`, `y`, `width` wide at its own height for that width; where it ends.
    private func place(_ view: NSTextField, x: Double, y: Double, width: Double) -> Double {
        let height = Self.height(of: view, width: width)
        view.frame = mirrored(NSRect(x: x, y: y, width: width, height: height))
        addSubview(view)
        return y + height
    }

    private func said(_ words: String, width: Double) -> NSTextField {
        Self.wrapping(words, .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary, width: width, direction: direction, lineHeight: Self.about)
    }

    private func quiet(_ words: String, width: Double) -> NSTextField {
        Self.wrapping(words, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, width: width, direction: direction, lineHeight: Self.about)
    }

    private static func refusal(_ readiness: NewSession.Readiness) -> Refusal? {
        switch readiness {
        case let .refused(refusal), let .needsLogin(refusal), let .signInThere(_, refusal): refusal
        case .start, .shell: nil
        }
    }

    /// `rect`, laid out left to right in the box, turned about its middle right to left.
    private func mirrored(_ rect: NSRect) -> NSRect {
        guard direction == .rightToLeft else { return rect }
        return NSRect(x: boxWidth - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }

    static func text(_ words: String, _ font: NSFont, _ ink: Tint, direction: NSUserInterfaceLayoutDirection, trailing: Bool = false, lineHeight: Double? = nil) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.alignment = (direction == .rightToLeft) != trailing ? .right : .left
        if let lineHeight {
            style.minimumLineHeight = lineHeight
            style.maximumLineHeight = lineHeight
        }
        return NSAttributedString(string: words, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style])
    }

    static func label(_ words: NSAttributedString) -> NSTextField {
        let label = NSTextField(labelWithAttributedString: words)
        label.lineBreakMode = .byTruncatingTail
        label.maximumNumberOfLines = 1
        return label
    }

    static func wrapping(_ words: String, _ font: NSFont, _ ink: Tint, width: Double, direction: NSUserInterfaceLayoutDirection, lineHeight: Double? = nil) -> NSTextField {
        let label = NSTextField(wrappingLabelWithString: "")
        label.attributedStringValue = text(words, font, ink, direction: direction, lineHeight: lineHeight)
        label.maximumNumberOfLines = 0
        label.lineBreakMode = .byWordWrapping
        label.preferredMaxLayoutWidth = width
        return label
    }

    /// A label's height `width` wide: its words wrapped, whole points.
    static func height(of label: NSTextField, width: Double) -> Double {
        guard label.maximumNumberOfLines != 1, let cell = label.cell else { return ceil(Double(label.fittingSize.height)) }
        return ceil(Double(cell.cellSize(forBounds: NSRect(x: 0, y: 0, width: width, height: 10000)).height))
    }

    @objc private func pressedStart(_: Any?) {
        on(.start)
    }

    @objc private func pressedShell(_: Any?) {
        on(.shell)
    }

    @objc private func pressedClose(_: Any?) {
        on(.close)
    }

    @objc private func pressedHow(_: Any?) {
        on(.how)
    }

    @objc private func choseMode(_ item: NSMenuItem) {
        guard let mode = item.representedObject as? String else { return }
        on(.mode(mode))
    }

    package override func draw(_: NSRect) {
        let box = bounds.insetBy(dx: 0.5, dy: 0.5)
        let shape = NSBezierPath(roundedRect: box, xRadius: Self.radius - 0.5, yRadius: Self.radius - 0.5)
        NSColor(look.surfacePop).setFill()
        shape.fill()
        NSColor(look.separator).setFill()
        for line in hairlines {
            NSRect(x: Look.hairline, y: line, width: Double(bounds.width) - 2 * Look.hairline, height: Look.hairline).fill()
        }
        if let square {
            NSColor(look.textTertiary).setFill()
            square.fill()
        }
        if let bar {
            NSColor(look.accent).setFill()
            let half = RowStyle.barWidth / 2, rightToLeft = direction == .rightToLeft
            NSBezierPath(roundedRect: bar, xRadius: half, yRadius: half).fill()
            NSRect(x: rightToLeft ? Double(bar.maxX) - half : Double(bar.minX), y: Double(bar.minY), width: half, height: Double(bar.height)).fill()
        }
        NSColor(look.separatorStrong).setStroke()
        shape.lineWidth = 1
        shape.stroke()
    }
}

extension NewSessionView: Marked {
    package var drawnMarks: [Mark] {
        bar == nil ? [] : look.marks(line: Tint(look.accent))
    }
}

/// One row of the panel: a harness by its name, its login or what stops it at the trailing end,
/// cut there; the Shell; the chosen one's name at full weight. A click chooses it.
@MainActor
package final class NewSessionRow: NSView {
    package let choice: NewSession.Choice
    package let name: NSTextField
    package let line: NSTextField
    var onChoose: @MainActor () -> Void = {}
    private let direction: NSUserInterfaceLayoutDirection

    init(_ choice: NewSession.Choice, name words: String, line said: String, chosen: Bool, off: Bool, look: Look, direction: NSUserInterfaceLayoutDirection) {
        self.choice = choice
        self.direction = direction
        let points = Look.Step.callout.points
        name = NewSessionView.label(NewSessionView.text(words, .systemFont(ofSize: points, weight: chosen ? .semibold : .medium), off ? look.textTertiary : look.text, direction: direction).cut(.byTruncatingTail))
        line = NewSessionView.label(NewSessionView.text(said, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, direction: direction, trailing: true).cut(.byTruncatingTail))
        line.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        super.init(frame: .zero)
        addSubview(name)
        addSubview(line)
        setAccessibilityElement(true)
        setAccessibilityRole(.radioButton)
        setAccessibilityLabel(said.isEmpty ? words : "\(words), \(said)")
        setAccessibilityValue(chosen ? 1 : 0)
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    package var chosen: Bool {
        accessibilityValue() as? Int == 1
    }

    /// The name 10 pt in at its own width, the line after it to 10 pt from the end, both centred.
    func lay() {
        let width = Double(bounds.width), height = Double(bounds.height), inset = 10.0, gap = 10.0
        let nameSize = name.fittingSize, lineSize = line.fittingSize
        let nameWidth = min(Double(nameSize.width), width - 2 * inset)
        let lineWidth = max(min(Double(lineSize.width), width - 2 * inset - nameWidth - gap), 0)
        let rightToLeft = direction == .rightToLeft
        name.frame = NSRect(x: rightToLeft ? width - inset - nameWidth : inset, y: (height - Double(nameSize.height)) / 2, width: nameWidth, height: Double(nameSize.height))
        line.frame = NSRect(x: rightToLeft ? inset : width - inset - lineWidth, y: (height - Double(lineSize.height)) / 2, width: lineWidth, height: Double(lineSize.height))
        line.isHidden = lineWidth < 1
    }

    package override func mouseDown(with _: NSEvent) {}

    package override func mouseUp(with event: NSEvent) {
        guard bounds.contains(convert(event.locationInWindow, from: nil)) else { return }
        onChoose()
    }

    package override func acceptsFirstMouse(for _: NSEvent?) -> Bool {
        true
    }

    package override func accessibilityPerformPress() -> Bool {
        onChoose()
        return true
    }
}

extension ModeMemory {
    static let key = "sail.agentStarts"

    /// What `defaults` remember of the person's starts; nothing, before any or past a reading.
    static func stored(in defaults: UserDefaults) -> ModeMemory {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(ModeMemory.self, from: $0) } ?? ModeMemory()
    }

    func store(in defaults: UserDefaults) {
        guard let data = try? JSONEncoder().encode(self) else { return }
        defaults.set(data, forKey: Self.key)
    }
}
