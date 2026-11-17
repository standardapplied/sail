import AppKit
import SailLook

/// The app's button, in five kinds and three sizes (`ButtonPaint`, `ButtonSize`), drawn whole by
/// itself so neither the system accent nor a bezel reaches it. Its frame keeps `Look.haloRoom`
/// around the box for the focus halo (the alignment rect is the box), so nothing of its own clips
/// the halo. Hover comes from a tracking area on the box, pressed from the cell's highlight, focus
/// from being first responder, which a button only becomes with keyboard navigation on, as on
/// macOS. In flight it shows a spinner and its "-ing" word, keeps at least its width, and ignores
/// presses. Return answers it only when it is `isDefault`.
@MainActor
public final class SailButton: NSButton {
    public var look: Look {
        didSet {
            tooltip?.look = look
            repaint()
        }
    }

    /// An icon-only button's tooltip, saying its label.
    public private(set) var tooltip: Tooltip?

    public let kind: ButtonKind
    public let size: ButtonSize
    public private(set) var busyWord: String?
    private let icon: LineIcon?
    private let iconOnly: Bool
    private var showsShortcut = false
    private var mono: LabelFont?
    private(set) var hovered = false
    private var hoverArea: NSTrackingArea?
    private(set) var focused = false
    private var heldWidth = 0.0
    let spinner = SpinnerRing()

    /// A labelled button, with a leading line icon when `icon` names one.
    public init(_ title: String, kind: ButtonKind, size: ButtonSize = .regular, icon: LineIcon? = nil, look: Look, target: AnyObject?, action: Selector?) {
        self.look = look
        self.kind = kind
        self.size = size
        self.icon = icon
        iconOnly = false
        super.init(frame: .zero)
        prepare(title, target: target, action: action)
    }

    /// A square button showing only `icon`; `label` is what VoiceOver reads and the tooltip says.
    public init(icon: LineIcon, label: String, kind: ButtonKind = .quiet, size: ButtonSize = .regular, look: Look, target: AnyObject?, action: Selector?) {
        self.look = look
        self.kind = kind
        self.size = size
        self.icon = icon
        iconOnly = true
        super.init(frame: .zero)
        prepare(label, target: target, action: action)
        let tooltip = Tooltip(label, look: look, mono: nil)
        tooltip.attach(to: self, saying: label)
        self.tooltip = tooltip
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    private func prepare(_ title: String, target: AnyObject?, action: Selector?) {
        self.title = title
        self.target = target
        self.action = action
        isBordered = false
        focusRingType = .none
        wantsLayer = true
        clipsToBounds = false
        layer?.addSublayer(spinner.layer)
        spinner.place(at: .zero, hidden: true)
        setAccessibilityRole(.button)
        setAccessibilityLabel(title)
        NotificationCenter.default.addObserver(self, selector: #selector(motionChanged), name: Motion.changed, object: nil)
        repaint()
    }

    /// The key that presses it, made its key equivalent and shown after the label in mono (Create ⏎,
    /// New Workspace ⌘N): what it says is read from the key equivalent, so the two always agree.
    public func shortcut(_ key: String, modifiers: NSEvent.ModifierFlags = [], mono: LabelFont) {
        keyEquivalent = key
        keyEquivalentModifierMask = modifiers
        showsShortcut = true
        self.mono = mono
        if let words = shortcutWords { tooltip?.chip.hint(words, mono: mono) }
        invalidateIntrinsicContentSize()
        needsDisplay = true
    }

    /// The shortcut's words, from the key equivalent; nil when it shows none.
    var shortcutWords: String? {
        guard showsShortcut, !keyEquivalent.isEmpty else { return nil }
        let held = keyEquivalentModifierMask
        return Shortcut.said(keyEquivalent, control: held.contains(.control), option: held.contains(.option), shift: held.contains(.shift), command: held.contains(.command))
    }

    override public var title: String {
        didSet {
            guard busyWord == nil else { return }
            setAccessibilityLabel(title)
            NSAccessibility.post(element: self, notification: .titleChanged)
        }
    }

    override public var keyEquivalent: String {
        didSet {
            invalidateIntrinsicContentSize()
            needsDisplay = true
        }
    }

    /// Return presses it: a sheet's or a banner's primary. There is never a window-wide default.
    public var isDefault = false {
        didSet { keyEquivalent = isDefault ? "\r" : "" }
    }

    /// In flight under `word` (Opening, Starting, Creating), or, given nil, back to its title.
    public func fly(_ word: String?) {
        if busyWord == nil { heldWidth = intrinsicContentSize.width }
        busyWord = word
        setAccessibilityLabel(word.map { "\($0), busy" } ?? title)
        NSAccessibility.post(element: self, notification: .titleChanged)
        spinner.turn(word != nil)
        invalidateIntrinsicContentSize()
        needsLayout = true
        needsDisplay = true
    }

    var drawnState: ButtonState {
        ButtonState(enabled: isEnabled, hovered: hovered, pressed: isHighlighted, focused: focused && window?.isKeyWindow == true, busy: busyWord != nil)
    }

    var paint: ButtonPaint {
        ButtonPaint(kind, drawnState, look: look)
    }

    var box: NSRect {
        let room = Look.haloRoom
        return NSRect(x: room, y: room, width: max(bounds.width - 2 * room, 0), height: max(bounds.height - 2 * room, 0))
    }

    override public var alignmentRectInsets: NSEdgeInsets {
        NSEdgeInsets(top: Look.haloRoom, left: Look.haloRoom, bottom: Look.haloRoom, right: Look.haloRoom)
    }

    private var labelFont: NSFont {
        .systemFont(ofSize: size.label, weight: paint.bold ? .bold : .semibold)
    }

    private var label: NSAttributedString {
        Draw.text(busyWord ?? title, labelFont, paint.label)
    }

    private var hintText: NSAttributedString? {
        guard busyWord == nil, !iconOnly, let words = shortcutWords, let mono else { return nil }
        return Draw.text(words, mono.font(Look.Step.caption.points) as NSFont, Tint(paint.label.rgb, alpha: paint.label.alpha * ButtonSize.hintOpacity))
    }

    override public var intrinsicContentSize: NSSize {
        guard !iconOnly else { return NSSize(width: size.height, height: size.height) }
        let hint: Double? = hintText.map { Double(ceil($0.size().width)) }
        let width = size.width(label: Double(ceil(label.size().width)), mark: busyWord != nil || icon != nil, hint: hint)
        return NSSize(width: max(width, busyWord == nil ? 0 : heldWidth), height: size.height)
    }

    /// Where the leading mark (icon or spinner), the label and the hint sit in the box, centred
    /// together, in reading order: mirrored right to left. An icon-only button's icon is centred on
    /// the box exactly, as the design's grid centres it.
    func arrangement() -> (mark: NSRect?, label: NSRect, hint: NSRect?) {
        let box = self.box, labelSize = label.size()
        guard !iconOnly else { return (box.insetBy(dx: (box.width - ButtonSize.icon) / 2, dy: (box.height - ButtonSize.icon) / 2), .zero, nil) }
        let marked = busyWord != nil || icon != nil, hintSize = hintText?.size()
        let mark: Double = marked ? ButtonSize.mark + ButtonSize.markGap : 0
        let hinted: Double = hintSize.map { ButtonSize.hintGap + Double($0.width) } ?? 0
        let total = mark + Double(labelSize.width) + hinted
        var x = (Double(box.midX) - total / 2).rounded()
        func next(_ width: Double, _ height: Double, gap: Double) -> NSRect {
            defer { x += width + gap }
            return NSRect(x: x, y: (Double(box.midY) - height / 2).rounded(), width: width, height: height)
        }
        let markRect = marked ? next(ButtonSize.mark, ButtonSize.mark, gap: ButtonSize.markGap) : nil
        let labelRect = next(Double(labelSize.width), Double(labelSize.height), gap: ButtonSize.hintGap)
        let hintRect = hintSize.map { next(Double($0.width), Double($0.height), gap: 0) }
        guard userInterfaceLayoutDirection == .rightToLeft else { return (markRect, labelRect, hintRect) }
        func mirrored(_ rect: NSRect) -> NSRect {
            NSRect(x: box.minX + box.maxX - rect.maxX, y: rect.minY, width: rect.width, height: rect.height)
        }
        return (markRect.map(mirrored), mirrored(labelRect), hintRect.map(mirrored))
    }

    override public func draw(_: NSRect) {
        guard let context = NSGraphicsContext.current?.cgContext else { return }
        let paint = self.paint, (mark, labelRect, hintRect) = arrangement()
        context.saveGState()
        context.setAlpha(paint.opacity)
        context.beginTransparencyLayer(auxiliaryInfo: nil)
        if let halo = paint.halo { Draw.halo(halo, around: box, radius: size.radius) }
        Draw.box(box, radius: size.radius, fill: paint.fill, edge: paint.edge)
        if busyWord == nil, let mark, let icon { Draw.icon(icon, in: mark, ink: paint.label) }
        if !iconOnly { label.draw(in: labelRect) }
        if let hintRect, let hintText {
            let points = Look.Step.caption.points, top = Double(box.minY) + (Double(box.height) - TextLine.height(points: points)) / 2
            Draw.line(hintText, x: Double(hintRect.minX), baseline: TextLine.baseline(top: top, points: points))
        }
        context.endTransparencyLayer()
        context.restoreGState()
    }

    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        needsLayout = true
    }

    override public func viewWillMove(toWindow newWindow: NSWindow?) {
        super.viewWillMove(toWindow: newWindow)
        KeyWatch.move(self, from: window, to: newWindow, calling: #selector(keyChanged))
    }

    /// How many times its window became or ceased to be key while it was there.
    private(set) var keyChanges = 0

    @objc private func keyChanged() {
        keyChanges += 1
        needsDisplay = true
    }

    override public func layout() {
        super.layout()
        let mark = arrangement().mark ?? .zero
        spinner.place(at: CGPoint(x: mark.midX, y: mark.midY), hidden: busyWord == nil)
    }

    private func repaint() {
        spinner.paint(paint.spinner, opacity: paint.opacity)
        needsDisplay = true
    }

    override public var isEnabled: Bool {
        didSet { repaint() }
    }

    override public var isHighlighted: Bool {
        didSet { needsDisplay = true }
    }

    /// In flight, a press does nothing: a click, Space, Return or VoiceOver's press.
    override public func sendAction(_ action: Selector?, to target: Any?) -> Bool {
        busyWord == nil && super.sendAction(action, to: target)
    }

    override public func hitTest(_ point: NSPoint) -> NSView? {
        guard let superview, box.contains(convert(point, from: superview)) else { return nil }
        return super.hitTest(point)
    }

    override public func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let hoverArea { removeTrackingArea(hoverArea) }
        let area = NSTrackingArea(rect: box, options: [.mouseEnteredAndExited, .activeInKeyWindow], owner: self)
        addTrackingArea(area)
        hoverArea = area
    }

    override public func mouseEntered(with _: NSEvent) {
        hovered = true
        needsDisplay = true
    }

    override public func mouseExited(with _: NSEvent) {
        hovered = false
        needsDisplay = true
    }

    override public var acceptsFirstResponder: Bool {
        isEnabled && NSApplication.shared.isFullKeyboardAccessEnabled
    }

    override public var canBecomeKeyView: Bool {
        acceptsFirstResponder
    }

    override public func becomeFirstResponder() -> Bool {
        focused = super.becomeFirstResponder()
        needsDisplay = true
        return focused
    }

    override public func resignFirstResponder() -> Bool {
        let resigned = super.resignFirstResponder()
        if resigned { focused = false }
        needsDisplay = true
        return resigned
    }

    @objc private func motionChanged() {
        spinner.turn(busyWord != nil)
    }
}
