import AppKit
import SailAPI
import SailControls
import SailLook

/// The menu behind the rail's user button (`calm-accent-mockup.html`, in Paper and ink): who is
/// signed in and the control plane with its state; Account (⌘,), Keys (not yet usable, saying
/// why), Appearance Light / Dark / Auto, and Sign Out apart; signed out, Not signed in, Sign In and
/// Appearance. It is drawn by us, not an `NSMenu`, whose highlight is the system's accent: 260 pt,
/// its leading edge on the button's, its foot 8 pt above it, on the popover's surface and shadow.
/// Opened by the pointer it highlights nothing, as a Mac menu does; opened by the keys or VoiceOver
/// its first item. The item under the keys or the pointer, the avatar and the chosen Appearance are
/// turned over to ink. The window keeps the keys: while it is open a key catcher there, which
/// VoiceOver reads as the menu, takes ↑ ↓ Tab ← → Return and Escape, and the keys go back where
/// they were when it closes (to the button, when it was opened by anything but the pointer), or,
/// when that view has left the window, to the shown pane's own. It closes on Escape, a click
/// outside it, the button again, a choice (but Appearance), or the window resigning key.
@MainActor
package final class UserMenu: NSObject {
    static let width = 260.0, gap = 8.0

    var look: Look {
        didSet {
            view.look = look
            popover.look = look
        }
    }

    var onChoose: @MainActor (UserMenuContent.Item) -> Void = { _ in }
    var onAppearance: @MainActor (Appearance) -> Void = { _ in }
    /// Told when it closes, so the button stops being drawn open.
    var onClose: @MainActor () -> Void = {}

    package let view: UserMenuView
    package let popover: Popover
    private let keys = MenuKeys()
    private weak var anchor: NSView?

    init(look: Look, mono: LabelFont) {
        self.look = look
        view = UserMenuView(look: look, mono: mono)
        popover = Popover(view, keys: keys, look: look, radius: Look.contentRadius)
        super.init()
        popover.onDismiss = { [weak self] in self?.close() }
        keys.owner = self
        keys.box = view
        view.onHighlight = { [weak self] element in
            guard let self, isOpen, let element else { return }
            NSAccessibility.post(element: element, notification: .focusedUIElementChanged)
        }
        view.onChoose = { [weak self] item in self?.choose(item) }
        view.onChanged = { [weak self] in
            guard let self, isOpen, let anchor else { return }
            popover.place(box(over: anchor))
        }
        view.onAppearance = { [weak self] appearance in self?.onAppearance(appearance) }
    }

    package var isOpen: Bool {
        popover.isOpen
    }

    /// Opens from `button` saying `content`, `appearance` chosen; `withKeys` when the keys opened it.
    func open(from button: NSView, saying content: UserMenuContent, appearance: Appearance, withKeys: Bool) {
        guard let window = button.window, !isOpen else { return }
        anchor = button
        view.direction = button.userInterfaceLayoutDirection
        view.show(content, appearance: appearance, fresh: true, highlightingFirst: withKeys)
        popover.open(over: window, anchor: button, at: box(over: button), withKeys: withKeys)
        keys.setAccessibilityElement(true)
        NSAccessibility.post(element: keys, notification: NSAccessibility.Notification(rawValue: "AXMenuOpened"))
    }

    /// Says `content` while open: who is signed in, or the control plane's state, changed.
    func update(_ content: UserMenuContent, appearance: Appearance) {
        guard isOpen else { return }
        view.show(content, appearance: appearance, fresh: false, highlightingFirst: false)
    }

    package func close() {
        guard isOpen else { return }
        view.letGo()
        view.tip.hide()
        popover.close()
        NSAccessibility.post(element: keys, notification: NSAccessibility.Notification(rawValue: "AXMenuClosed"))
        keys.setAccessibilityElement(false)
        onClose()
    }

    /// The box over `button` on screen: its leading edge on the button's, its foot `gap` above it.
    private func box(over button: NSView) -> NSRect {
        let height = Double(view.fittingSize.height)
        guard let window = button.window else { return .zero }
        let square = window.convertToScreen(button.convert(button.alignmentRect(forFrame: button.bounds), to: nil))
        let rightToLeft = button.userInterfaceLayoutDirection == .rightToLeft
        return NSRect(x: rightToLeft ? square.maxX - Self.width : square.minX, y: square.maxY + Self.gap, width: Self.width, height: height)
    }

    /// The box on screen, for the tests and the drive.
    var box: NSRect {
        popover.frame
    }

    /// A press let go after the menu closed (Escape or another app while the mouse was held) chooses
    /// nothing: Sign Out acts at once.
    private func choose(_ item: UserMenuContent.Item) {
        guard item.enabled, isOpen else { return }
        close()
        onChoose(item)
    }

    /// A key while open: ↑ ↓ and Tab move (Tab never takes the keys out of the menu), ← → choose the
    /// Appearance, Return or Space chooses, Escape closes.
    fileprivate func key(_ event: NSEvent) -> Bool {
        switch event.keyCode {
        case 125: view.move(1)
        case 126: view.move(-1)
        case 48: view.move(event.modifierFlags.contains(.shift) ? -1 : 1)
        case 123: view.stepAppearance(view.userInterfaceLayoutDirection == .rightToLeft ? 1 : -1)
        case 124: view.stepAppearance(view.userInterfaceLayoutDirection == .rightToLeft ? -1 : 1)
        case 36, 76, 49: view.chooseHighlighted()
        case 53: close()
        default: return false
        }
        return true
    }

    fileprivate func cancel() {
        close()
    }
}

/// Takes the window's keys while the menu is open; VoiceOver reads it as the menu, its children the
/// menu's items and Appearance, its focus the one highlighted, only while it is open: closed, it is
/// no element and has no children.
@MainActor
final class MenuKeys: NSView {
    weak var owner: UserMenu?
    weak var box: UserMenuView?

    override init(frame: NSRect) {
        super.init(frame: frame)
        setAccessibilityElement(false)
        setAccessibilityRole(.menu)
        setAccessibilityLabel("Account")
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func accessibilityChildren() -> [Any]? {
        guard owner?.isOpen == true else { return nil }
        return box.map { $0.items as [Any] + [$0.segments] }
    }

    override var accessibilityFocusedUIElement: Any? {
        box?.highlightedElement ?? self
    }

    override var acceptsFirstResponder: Bool {
        true
    }

    override func keyDown(with event: NSEvent) {
        if owner?.key(event) != true { super.keyDown(with: event) }
    }

    override func cancelOperation(_: Any?) {
        owner?.cancel()
    }
}

/// The menu's box: identity, the control plane, the items, Appearance; drawn on `surfacePop` with a
/// `separatorStrong` edge and 6 pt corners, a hairline between its sections.
@MainActor
package final class UserMenuView: NSView {
    static let row = 28.0, appearanceRow = 36.0, padding = 16.0, sectionPad = 4.0, avatar = 32.0

    var look: Look {
        didSet { repaint() }
    }

    var onChoose: @MainActor (UserMenuContent.Item) -> Void = { _ in }
    var onAppearance: @MainActor (Appearance) -> Void = { _ in }
    /// Told when what it says changed while open, so the box is sized again.
    var onChanged: @MainActor () -> Void = {}
    /// Told the element highlighted (an item, or Appearance), for VoiceOver to follow.
    var onHighlight: @MainActor (NSView?) -> Void = { _ in }

    private let mono: LabelFont
    /// What it is to say once the item being pressed is let go: an item is never taken from under
    /// a press (at launch, who is signed in is learnt while the menu may be open).
    private var waiting: (content: UserMenuContent, appearance: Appearance)?
    package private(set) var content = UserMenuContent.signedOut(lastUsed: nil)
    package private(set) var chosenLook = Appearance.auto
    package private(set) var items: [MenuRow] = []
    package private(set) var segments: MenuSegments
    private let name = NSTextField(labelWithString: "")
    private let detail = NSTextField(labelWithString: "")
    /// The control plane's line, the Account pane's own.
    let planeRow: ControlPlaneRow
    private let appearanceLabel = NSTextField(labelWithString: "Appearance")
    package let tip: Tooltip
    /// What the keys move through: the enabled items, then Appearance.
    package private(set) var highlighted: Int?

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        segments = MenuSegments(look: look)
        tip = Tooltip(UserMenuContent.Item.keys.why ?? "", look: look, mono: nil)
        planeRow = ControlPlaneRow(look: look, mono: mono, points: Look.Step.micro.points)
        super.init(frame: .zero)
        for label in [name, detail] {
            label.lineBreakMode = .byTruncatingTail
            label.maximumNumberOfLines = 1
            addSubview(label)
        }
        addSubview(planeRow)
        appearanceLabel.lineBreakMode = .byTruncatingTail
        appearanceLabel.maximumNumberOfLines = 1
        addSubview(appearanceLabel)
        addSubview(segments)
        segments.onChoose = { [weak self] appearance in
            self?.chosenLook = appearance
            self?.onAppearance(appearance)
        }
        setAccessibilityElement(true)
        setAccessibilityRole(.menu)
        setAccessibilityLabel("Account")
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    /// The direction it is laid out in, its button's: its rows and segments with it.
    var direction: NSUserInterfaceLayoutDirection {
        get { userInterfaceLayoutDirection }
        set {
            userInterfaceLayoutDirection = newValue
            segments.userInterfaceLayoutDirection = newValue
            planeRow.userInterfaceLayoutDirection = newValue
            items.forEach { $0.userInterfaceLayoutDirection = newValue }
            needsLayout = true
        }
    }

    /// The rows the keys stop at, top to bottom: the enabled items above Appearance, Appearance
    /// (index `items.count`), then Sign Out under it.
    private var stops: [Int] {
        let above = items.indices.filter { items[$0].item.enabled && items[$0].item != .signOut }
        return above + [items.count] + items.indices.filter { items[$0].item == .signOut }
    }

    /// Says `content` with `appearance` chosen: `fresh`, just opened, its first item highlighted when
    /// `highlightingFirst` (opened by the keys) and none when not (by the pointer, as a Mac menu);
    /// else with what was highlighted when it is still there.
    func show(_ content: UserMenuContent, appearance: Appearance, fresh: Bool, highlightingFirst: Bool) {
        if !fresh, items.contains(where: \.pressed) {
            waiting = (content, appearance)
            return
        }
        waiting = nil
        let before = highlighted.flatMap { items.indices.contains($0) ? items[$0].item : nil }, onAppearance = highlighted == items.count
        self.content = content
        self.chosenLook = appearance
        items.forEach { $0.removeFromSuperview() }
        items = content.items.map { item in
            let row = MenuRow(item, look: look, mono: mono)
            row.userInterfaceLayoutDirection = userInterfaceLayoutDirection
            row.onChoose = { [weak self] in self?.onChoose(item) }
            row.onHover = { [weak self, weak row] in
                guard let self, let row, let index = items.firstIndex(where: { $0 === row }) else { return }
                highlight(index)
            }
            row.onRelease = { [weak self] in
                guard let self, let waiting else { return }
                show(waiting.content, appearance: waiting.appearance, fresh: false, highlightingFirst: false)
            }
            row.onLeave = { [weak self, weak row] in
                guard let self, let row, items.firstIndex(where: { $0 === row }) == highlighted else { return }
                highlight(nil)
            }
            addSubview(row)
            return row
        }
        if let logins = items.first(where: { $0.item == .keys }) { tip.attach(to: logins, saying: logins.item.why ?? "") }
        segments.chosen = appearance
        if fresh {
            highlight(highlightingFirst ? stops.first : nil)
        } else {
            highlight(onAppearance ? items.count : before.flatMap { item in items.firstIndex { $0.item == item } })
        }
        repaint()
        needsLayout = true
        if !fresh { onChanged() }
    }

    private func highlight(_ index: Int?) {
        highlighted = index
        for (at, row) in items.enumerated() {
            row.highlighted = at == index
            row.setAccessibilitySelected(at == index)
        }
        segments.highlighted = index == items.count
        segments.setAccessibilitySelected(index == items.count)
        if let index, items.indices.contains(index), items[index].item == .keys { tip.show() } else { tip.hide() }
        onHighlight(highlightedElement)
    }

    /// The element highlighted, for VoiceOver: an item, Appearance, or none.
    var highlightedElement: NSView? {
        guard let highlighted else { return nil }
        return items.indices.contains(highlighted) ? items[highlighted] : highlighted == items.count ? segments : nil
    }

    /// A press under way is dropped as the menu closes, with what was waiting on it.
    func letGo() {
        waiting = nil
        items.forEach { $0.cancelPress() }
    }

    func move(_ by: Int) {
        let stops = stops
        guard !stops.isEmpty else { return }
        let at = highlighted.flatMap { stops.firstIndex(of: $0) } ?? (by > 0 ? -1 : stops.count)
        highlight(stops[(at + by + stops.count) % stops.count])
    }

    func stepAppearance(_ by: Int) {
        guard highlighted == items.count else { return }
        let all = Appearance.allCases, at = all.firstIndex(of: chosenLook) ?? 0
        let next = all[min(max(at + by, 0), all.count - 1)]
        guard next != chosenLook else { return }
        segments.chosen = next
        chosenLook = next
        onAppearance(next)
    }

    func chooseHighlighted() {
        guard let highlighted, items.indices.contains(highlighted) else { return }
        onChoose(items[highlighted].item)
    }

    // MARK: Drawing and layout

    /// Section tops, measured as the mockup does (`.um-id`, `.um-cp`, `.um-sec`).
    private var sections: (identity: Double, plane: Double?, items: Double, appearance: Double, signOut: Double?, end: Double) {
        let identity = content.disc == nil ? 14 + 18 + 12 : 14 + Self.avatar + 12
        let plane: Double? = content.address == nil ? nil : identity
        let itemsTop = (plane.map { $0 + 33 } ?? identity)
        let main = content.items.filter { $0 != .signOut }
        let appearanceTop = itemsTop + Self.sectionPad * 2 + Double(main.count) * Self.row
        let signOut: Double? = content.items.contains(.signOut) ? appearanceTop + Self.sectionPad * 2 + Self.appearanceRow : nil
        let end = (signOut.map { $0 + Self.sectionPad * 2 + Self.row } ?? appearanceTop + Self.sectionPad * 2 + Self.appearanceRow)
        return (0, plane, itemsTop, appearanceTop, signOut, end)
    }

    package override var fittingSize: NSSize {
        NSSize(width: UserMenu.width, height: sections.end)
    }

    /// `rect` laid out left to right, turned about the middle in a right-to-left layout.
    private func mirrored(_ rect: NSRect) -> NSRect {
        guard userInterfaceLayoutDirection == .rightToLeft else { return rect }
        return NSRect(x: Double(bounds.width) - rect.maxX, y: rect.minY, width: rect.width, height: rect.height)
    }

    /// Where the avatar's disc is drawn.
    var avatarDisc: NSRect {
        mirrored(NSRect(x: Self.padding, y: 14, width: Self.avatar, height: Self.avatar))
    }

    /// Where the control plane's state square is drawn.
    var planeSquare: NSRect? {
        sections.plane == nil ? nil : convert(planeRow.squareRect, from: planeRow)
    }

    package override func layout() {
        super.layout()
        let width = Double(bounds.width), at = sections, text = Self.padding + (content.disc == nil ? 0 : Self.avatar + 10)
        name.frame = mirrored(NSRect(x: text, y: 14, width: width - text - Self.padding, height: 17))
        detail.frame = mirrored(NSRect(x: text, y: 31, width: width - text - Self.padding, height: 15))
        if let plane = at.plane {
            planeRow.frame = NSRect(x: Self.padding, y: plane + 9, width: width - Self.padding * 2, height: 16)
            planeRow.layoutSubtreeIfNeeded()
        }
        var y = at.items + Self.sectionPad
        for row in items where row.item != .signOut {
            row.frame = NSRect(x: 6, y: y, width: width - 12, height: Self.row)
            y += Self.row
        }
        let seg = segments.fittingSize
        let segTop = at.appearance + Self.sectionPad + (Self.appearanceRow - Double(seg.height)) / 2
        segments.frame = mirrored(NSRect(x: width - 16 - Double(seg.width), y: segTop, width: Double(seg.width), height: Double(seg.height)))
        appearanceLabel.frame = mirrored(NSRect(x: 16, y: at.appearance + Self.sectionPad + 10, width: width - 32 - Double(seg.width) - 8, height: 16))
        if let signOut = at.signOut, let row = items.first(where: { $0.item == .signOut }) {
            row.frame = NSRect(x: 6, y: signOut + Self.sectionPad, width: width - 12, height: Self.row)
        }
        planeRow.isHidden = at.plane == nil
        detail.isHidden = content.detail == nil
    }

    /// `words` on one line in `font` and `ink`, cut at `cut` with an ellipsis, set to the layout's
    /// leading edge (or its trailing one).
    private func line(_ words: String, _ font: NSFont, _ ink: Tint, cut: NSLineBreakMode = .byTruncatingTail, trailing: Bool = false) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = cut
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        style.alignment = rightToLeft != trailing ? .right : .left
        return NSAttributedString(string: words, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style])
    }

    private func repaint() {
        let micro = mono.font(Look.Step.micro.points, .regular) as NSFont
        name.attributedStringValue = line(content.name, .systemFont(ofSize: Look.Step.body.points, weight: .semibold), look.text)
        detail.attributedStringValue = line(content.detail ?? "", micro, look.textTertiary)
        planeRow.look = look
        if let address = content.address, let plane = content.plane { planeRow.show(address: address, plane: plane) }
        appearanceLabel.attributedStringValue = line("Appearance", .systemFont(ofSize: Look.Step.body.points), look.text)
        items.forEach { $0.look = look }
        segments.look = look
        tip.look = look
        needsLayout = true
        needsDisplay = true
    }

    package override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        repaint()
    }

    /// The control plane's square: green connected, teal reconnecting, crimson out of reach.
    var planeInk: Tint? {
        content.plane == nil ? nil : planeRow.ink
    }

    package override func draw(_: NSRect) {
        let box = bounds.insetBy(dx: 0.5, dy: 0.5)
        let shape = NSBezierPath(roundedRect: box, xRadius: Look.contentRadius - 0.5, yRadius: Look.contentRadius - 0.5)
        NSColor(look.surfacePop).setFill()
        shape.fill()
        let at = sections, width = Double(bounds.width)
        NSColor(look.separator).setFill()
        for line in [at.plane, at.items, at.appearance, at.signOut].compactMap({ $0 }) where line > 0 {
            NSRect(x: 0, y: line, width: width, height: Look.hairline).fill()
        }
        switch content.disc {
        case let .initial(initial)?:
            let mark = look.menuAvatar, disc = avatarDisc
            if let fill = mark.fill {
                NSColor(fill).setFill()
                NSBezierPath(ovalIn: disc).fill()
            }
            let letter = NSAttributedString(string: initial, attributes: [
                .font: NSFont.systemFont(ofSize: Look.Step.body.points, weight: .semibold), .foregroundColor: NSColor(mark.ink),
            ])
            let size = letter.size()
            letter.draw(at: NSPoint(x: disc.midX - size.width / 2, y: disc.midY - size.height / 2))
        case .person?:
            drawPerson(in: avatarDisc)
        case nil:
            break
        }
        NSColor(look.separatorStrong).setStroke()
        shape.lineWidth = 1
        shape.stroke()
    }
}

extension UserMenuView {
    /// No one signed in: the disc an outline in the strong hairline, a person's head and shoulders in
    /// the secondary ink, as the mockup's 16 pt person icon.
    fileprivate func drawPerson(in disc: NSRect) {
        NSColor(look.separatorStrong).setStroke()
        let ring = NSBezierPath(ovalIn: disc.insetBy(dx: 0.5, dy: 0.5))
        ring.lineWidth = 1
        ring.stroke()
        let unit = 16.0 / 24, x = Double(disc.midX) - 8, y = Double(disc.midY) - 8
        let person = NSBezierPath()
        person.appendOval(in: NSRect(x: x + 8 * unit, y: y + 4 * unit, width: 8 * unit, height: 8 * unit))
        person.move(to: NSPoint(x: x + 5 * unit, y: y + 20 * unit))
        person.curve(to: NSPoint(x: x + 19 * unit, y: y + 20 * unit), controlPoint1: NSPoint(x: x + 6 * unit, y: y + 14 * unit), controlPoint2: NSPoint(x: x + 18 * unit, y: y + 14 * unit))
        person.lineWidth = LineIcon.width * unit
        person.lineCapStyle = .round
        NSColor(look.textSecondary).setStroke()
        person.stroke()
    }
}

extension UserMenuView: Marked {
    package var drawnMarks: [Mark] {
        if case .initial? = content.disc { return look.marks(fill: look.menuAvatar.fill, area: .pi * Self.avatar * Self.avatar / 4) }
        return []
    }
}

/// One item of the menu: its title, ⌘ and its key at its end when it has one; turned over to ink
/// under the keys or the pointer; dimmed and said why when it cannot be chosen.
@MainActor
package final class MenuRow: NSView {
    package let item: UserMenuContent.Item
    var look: Look {
        didSet { needsDisplay = true }
    }

    var highlighted = false {
        didSet { if highlighted != oldValue { needsDisplay = true } }
    }

    var onChoose: @MainActor () -> Void = {}
    var onHover: @MainActor () -> Void = {}
    var onLeave: @MainActor () -> Void = {}
    /// Told when a press on it ends, chosen or not.
    var onRelease: @MainActor () -> Void = {}
    /// The mouse-downs and mouse-ups it took, for the drive to say where a click went.
    package private(set) var downs = 0, ups = 0
    /// Pressed: a mouse-down taken and its mouse-up not yet.
    private(set) var pressed = false
    private let mono: LabelFont

    init(_ item: UserMenuContent.Item, look: Look, mono: LabelFont) {
        self.item = item
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self))
        setAccessibilityElement(true)
        setAccessibilityRole(.menuItem)
        setAccessibilityLabel(item.rawValue)
        setAccessibilityEnabled(item.enabled)
        if let why = item.why { setAccessibilityHelp(why) }
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    /// The highlight's fill and the words' ink: turned over while highlighted and enabled.
    var drawn: (fill: Tint?, ink: Tint) {
        guard item.enabled else { return (nil, look.textTertiary) }
        return highlighted ? (look.menuHighlight.fill, look.menuHighlight.ink) : (nil, look.text)
    }

    package override func draw(_: NSRect) {
        let ink = drawn
        if let fill = ink.fill {
            NSColor(fill).setFill()
            NSBezierPath(roundedRect: bounds, xRadius: Look.innerRadius, yRadius: Look.innerRadius).fill()
        }
        let title = NSAttributedString(string: item.rawValue, attributes: [.font: NSFont.systemFont(ofSize: Look.Step.body.points), .foregroundColor: NSColor(ink.ink)])
        let size = title.size()
        let leading = 10.0
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        title.draw(at: NSPoint(x: rightToLeft ? Double(bounds.width) - leading - Double(size.width) : leading, y: (Double(bounds.height) - Double(size.height)) / 2))
        guard let key = item.key else { return }
        let cap = NSAttributedString(string: "⌘" + key, attributes: [
            .font: mono.font(10, .regular) as NSFont, .foregroundColor: NSColor(highlighted ? look.onInverseSecondary : look.textSecondary),
        ])
        let capSize = cap.size()
        let box = NSRect(x: rightToLeft ? leading : Double(bounds.width) - leading - Double(capSize.width) - 8, y: (Double(bounds.height) - 16) / 2, width: Double(capSize.width) + 8, height: 16)
        NSColor(highlighted ? look.onInverseEdge : look.separatorStrong).setStroke()
        let edge = NSBezierPath(roundedRect: box.insetBy(dx: 0.5, dy: 0.5), xRadius: 3, yRadius: 3)
        edge.lineWidth = 1
        edge.stroke()
        cap.draw(at: NSPoint(x: box.minX + 4, y: box.midY - capSize.height / 2))
    }

    package override func mouseEntered(with _: NSEvent) {
        onHover()
    }

    package override func mouseExited(with _: NSEvent) {
        onLeave()
    }

    /// A click chooses it; on one that cannot be chosen it says why, as pointing at it does.
    package override func mouseUp(with event: NSEvent) {
        ups += 1
        pressed = false
        defer { onRelease() }
        guard bounds.contains(convert(event.locationInWindow, from: nil)) else { return }
        if item.enabled { onChoose() } else { onHover() }
    }

    package override func mouseDown(with _: NSEvent) {
        downs += 1
        pressed = true
    }

    /// The press is dropped: the menu closed under it.
    func cancelPress() {
        pressed = false
    }

    package override func acceptsFirstMouse(for _: NSEvent?) -> Bool {
        true
    }

    package override func accessibilityPerformPress() -> Bool {
        guard item.enabled else { return false }
        onChoose()
        return true
    }
}

extension MenuRow: Marked {
    package var drawnMarks: [Mark] {
        look.marks(fill: drawn.fill, area: Double(bounds.width * bounds.height))
    }
}

/// Appearance's three segments, Light, Dark and Auto, in a 1 pt outline; the chosen one turned over.
@MainActor
package final class MenuSegments: NSView {
    static let height = 26.0, pad = 2.0

    var look: Look {
        didSet { needsDisplay = true }
    }

    var chosen = Appearance.auto {
        didSet {
            needsDisplay = true
            for (segment, appearance) in zip(buttons, Appearance.allCases) {
                segment.setAccessibilityValue(appearance == chosen ? 1 : 0)
            }
        }
    }

    var highlighted = false {
        didSet { needsDisplay = true }
    }

    var onChoose: @MainActor (Appearance) -> Void = { _ in }
    private(set) var buttons: [NSView] = []
    /// The track is Paper and ink's outline, never filled; a fill here is counted as a control's,
    /// even in a ground's colour, where it reads as a well.
    let trackFill: Tint? = nil

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        buttons = Appearance.allCases.map { appearance in
            let segment = NSView()
            segment.setAccessibilityElement(true)
            segment.setAccessibilityRole(.radioButton)
            segment.setAccessibilityLabel(appearance.said)
            addSubview(segment)
            return segment
        }
        setAccessibilityElement(true)
        setAccessibilityRole(.radioGroup)
        setAccessibilityLabel("Appearance")
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    private var words: [NSAttributedString] {
        Appearance.allCases.map { appearance in
            let ink = appearance == chosen ? look.menuHighlight.ink : look.textTertiary
            return NSAttributedString(string: appearance.said, attributes: [.font: NSFont.systemFont(ofSize: Look.Step.micro.points, weight: .medium), .foregroundColor: NSColor(ink)])
        }
    }

    /// Each segment's rect, padded 9 pt about its word, Light at the leading end.
    package var rects: [NSRect] {
        var x = Self.pad
        let laid = words.map { word in
            let width = ceil(Double(word.size().width)) + 18
            defer { x += width }
            return NSRect(x: x, y: Self.pad, width: width, height: Self.height - 2 * Self.pad)
        }
        guard userInterfaceLayoutDirection == .rightToLeft else { return laid }
        let whole = x + Self.pad
        return laid.map { NSRect(x: whole - $0.maxX, y: $0.minY, width: $0.width, height: $0.height) }
    }

    package override var fittingSize: NSSize {
        NSSize(width: (rects.map(\.maxX).max() ?? 0) + Self.pad, height: Self.height)
    }

    package override func layout() {
        super.layout()
        for (segment, rect) in zip(buttons, rects) {
            segment.frame = rect
        }
    }

    package override func draw(_: NSRect) {
        let edge = NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: Look.contentRadius - 0.5, yRadius: Look.contentRadius - 0.5)
        if let trackFill {
            NSColor(trackFill).setFill()
            edge.fill()
        }
        NSColor(highlighted ? look.textSecondary : look.separatorStrong).setStroke()
        edge.lineWidth = 1
        edge.stroke()
        for (appearance, (rect, word)) in zip(Appearance.allCases, zip(rects, words)) {
            if appearance == chosen, let fill = look.menuHighlight.fill {
                NSColor(fill).setFill()
                NSBezierPath(roundedRect: rect, xRadius: Look.innerRadius, yRadius: Look.innerRadius).fill()
            }
            let size = word.size()
            word.draw(at: NSPoint(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2))
        }
    }

    package override func mouseDown(with _: NSEvent) {}

    package override func acceptsFirstMouse(for _: NSEvent?) -> Bool {
        true
    }

    package override func mouseUp(with event: NSEvent) {
        let at = convert(event.locationInWindow, from: nil)
        guard let index = rects.firstIndex(where: { $0.contains(at) }) else { return }
        let appearance = Appearance.allCases[index]
        guard appearance != chosen else { return }
        chosen = appearance
        onChoose(appearance)
    }
}

extension Appearance {
    /// The app's appearance for this choice: Auto is none of its own, the Mac's.
    package var appearance: NSAppearance? {
        switch self {
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        case .auto: nil
        }
    }
}

extension MenuSegments: Marked {
    package var drawnMarks: [Mark] {
        let track = look.marks(fill: trackFill, area: Double(bounds.width * bounds.height), inControl: true)
        guard let index = Appearance.allCases.firstIndex(of: chosen), rects.indices.contains(index) else { return track }
        return track + look.marks(fill: look.menuHighlight.fill, area: Double(rects[index].width * rects[index].height))
    }
}
