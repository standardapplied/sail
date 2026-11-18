import AppKit
import SailLook

/// The ink chip a tooltip shows (`TooltipStyle`): the look inverted, SF 12, the shortcut as a
/// keycap, an arrow on the side facing its anchor, and a soft shadow, in a frame with room for both;
/// its alignment rect is the chip alone, as a control's is its box.
@MainActor
public final class TooltipChip: NSView {
    static let room = 24.0

    public var look: Look {
        didSet { needsDisplay = true }
    }

    var side: TooltipPlacement.Side = .below
    /// Along the arrow's edge, from the chip's left or bottom.
    var arrowAt: Double?
    private let text: String
    private var shortcut: String?
    private var mono: LabelFont?

    public init(_ text: String, hint: String? = nil, side: TooltipPlacement.Side = .below, look: Look, mono: LabelFont?) {
        self.text = text
        shortcut = hint
        self.side = side
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        fit()
    }

    /// The shortcut after the words (List Again ⌘R).
    public func hint(_ text: String, mono: LabelFont) {
        shortcut = text
        self.mono = mono
        fit()
    }

    private func fit() {
        setFrameSize(NSSize(width: Double(chip.width) + 2 * Self.room, height: Double(chip.height) + 2 * Self.room))
        invalidateIntrinsicContentSize()
        needsDisplay = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    private var label: NSAttributedString {
        let lines = NSMutableParagraphStyle()
        lines.minimumLineHeight = (Look.Step.footnote.points * 1.45).rounded()
        lines.maximumLineHeight = lines.minimumLineHeight
        return NSAttributedString(string: text, attributes: [.font: NSFont.systemFont(ofSize: Look.Step.footnote.points), .foregroundColor: NSColor(Tint(look.onInk)), .paragraphStyle: lines])
    }

    private var hintText: NSAttributedString? {
        guard let shortcut, let mono else { return nil }
        return Draw.text(shortcut, mono.font(Look.Step.caption.points) as NSFont, Tint(look.onInk, alpha: TooltipStyle.keycapText))
    }

    /// The keycap's box: its text, padded.
    private var hintBox: NSSize? {
        guard let size = hintText?.size() else { return nil }
        let padding = TooltipStyle.keycapPadding
        return NSSize(width: Double(size.width) + 2 * padding.horizontal, height: Double(size.height) + 2 * padding.vertical)
    }

    /// The chip alone, without the arrow or the shadow's room.
    var chip: NSSize {
        let text = label.size(), hint = hintBox.map { Double($0.width) + TooltipStyle.hintGap } ?? 0
        return NSSize(width: ceil(Double(text.width) + hint) + TooltipStyle.leading + TooltipStyle.trailing, height: ceil(Double(text.height)) + 2 * TooltipStyle.vertical)
    }

    override public var intrinsicContentSize: NSSize {
        chip
    }

    override public var alignmentRectInsets: NSEdgeInsets {
        NSEdgeInsets(top: Self.room, left: Self.room, bottom: Self.room, right: Self.room)
    }

    override public func draw(_: NSRect) {
        let box = bounds.insetBy(dx: Self.room, dy: Self.room)
        let shape = NSBezierPath(roundedRect: box, xRadius: TooltipStyle.radius, yRadius: TooltipStyle.radius)
        let half = TooltipStyle.arrow / 2
        let tip = NSBezierPath(roundedRect: NSRect(x: -half, y: -half, width: TooltipStyle.arrow, height: TooltipStyle.arrow), xRadius: 1, yRadius: 1)
        switch side {
        case .below: tip.transform(using: Self.turned(at: NSPoint(x: Double(box.minX) + (arrowAt ?? Double(box.width) / 2), y: Double(box.maxY))))
        case .above: tip.transform(using: Self.turned(at: NSPoint(x: Double(box.minX) + (arrowAt ?? Double(box.width) / 2), y: Double(box.minY))))
        case .right: tip.transform(using: Self.turned(at: NSPoint(x: Double(box.minX), y: Double(box.minY) + (arrowAt ?? Double(box.height) / 2))))
        case .left: tip.transform(using: Self.turned(at: NSPoint(x: Double(box.maxX), y: Double(box.minY) + (arrowAt ?? Double(box.height) / 2))))
        }
        shape.append(tip)
        NSColor(Tint(look.ink)).setFill()
        for each in TooltipStyle.shadows {
            NSGraphicsContext.saveGraphicsState()
            let shadow = NSShadow()
            shadow.shadowColor = NSColor(white: 0, alpha: each.alpha)
            shadow.shadowOffset = NSSize(width: 0, height: -each.y)
            shadow.shadowBlurRadius = each.blur
            shadow.set()
            shape.fill()
            NSGraphicsContext.restoreGraphicsState()
        }
        let label = self.label, size = label.size()
        let words = reading(NSRect(x: Double(box.minX) + TooltipStyle.leading, y: (Double(box.midY) - Double(size.height) / 2).rounded(), width: Double(size.width), height: Double(size.height)), in: box)
        label.draw(at: words.origin)
        guard let hintText, let hintBox else { return }
        let at = reading(NSRect(
            x: Double(box.minX) + TooltipStyle.leading + Double(size.width) + TooltipStyle.hintGap, y: (Double(box.midY) - Double(hintBox.height) / 2).rounded(),
            width: Double(hintBox.width), height: Double(hintBox.height)
        ), in: box)
        NSColor(Tint(look.onInk, alpha: TooltipStyle.keycapFill)).setFill()
        NSBezierPath(roundedRect: at, xRadius: TooltipStyle.keycapRadius, yRadius: TooltipStyle.keycapRadius).fill()
        let text = hintText.size()
        hintText.draw(at: NSPoint(x: Double(at.midX) - Double(text.width) / 2, y: Double(at.midY) - Double(text.height) / 2))
    }

    /// `rect` as it stands in `box` in the reading direction: the keycap leads the words right to left.
    private func reading(_ rect: NSRect, in box: NSRect) -> NSRect {
        guard userInterfaceLayoutDirection == .rightToLeft else { return rect }
        return NSRect(x: Double(box.minX) + Double(box.maxX) - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }

        private static func turned(at point: NSPoint) -> AffineTransform {
        var turn = AffineTransform(translationByX: point.x, byY: point.y)
        turn.rotate(byDegrees: 45)
        return turn
    }
}

/// A tooltip on a view: the chip in a borderless child panel the pointer passes through, shown
/// after 0.6 s of hover (the platform's timing) and gone when the pointer leaves or clicks. The
/// view's VoiceOver help says the same words.
@MainActor
public final class Tooltip: NSResponder {
    /// When the last tooltip went: within the style's warm period the next shows at once.
    private static var lastHidden: CFTimeInterval?

    public let chip: TooltipChip
    private let beside: Bool
    private weak var anchor: NSView?
    private var panel: NSPanel?
    private var pending: DispatchWorkItem?

    public init(_ text: String, hint: String? = nil, beside: Bool = false, look: Look, mono: LabelFont?) {
        chip = TooltipChip(text, hint: hint, look: look, mono: mono)
        self.beside = beside
        super.init()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    public var look: Look {
        get { chip.look }
        set { chip.look = newValue }
    }

    public var isShown: Bool {
        panel?.isVisible == true
    }

    /// Shows on `view`'s hover from now on.
    public func attach(to view: NSView, saying text: String) {
        anchor = view
        view.addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self))
        view.setAccessibilityHelp(text)
    }

    override public func mouseEntered(with _: NSEvent) {
        pending?.cancel()
        if let last = Self.lastHidden, CACurrentMediaTime() - last < TooltipStyle.warm { return show() }
        let show = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated { self?.show() }
        }
        pending = show
        DispatchQueue.main.asyncAfter(deadline: .now() + TooltipStyle.delay, execute: show)
    }

    override public func mouseExited(with _: NSEvent) {
        hide()
    }

    public func hide() {
        pending?.cancel()
        pending = nil
        if isShown { Self.lastHidden = CACurrentMediaTime() }
        if let panel { panel.parent?.removeChildWindow(panel) }
        panel?.orderOut(nil)
    }

    /// Placed by `TooltipPlacement` against the anchor's frame on its screen.
    func show() {
        guard let anchor, let window = anchor.window, let screen = window.screen else { return }
        let size = chip.chip
        let at = window.convertToScreen(anchor.convert(anchor.alignmentRect(forFrame: anchor.bounds), to: nil))
        let place = TooltipPlacement(
            chip: size, anchor: at, window: window.frame, screen: screen.visibleFrame, beside: beside, rightToLeft: anchor.userInterfaceLayoutDirection == .rightToLeft
        )
        chip.side = place.side
        chip.arrowAt = place.arrow
        chip.needsDisplay = true
        let room = TooltipChip.room
        let panel = self.panel ?? NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.ignoresMouseEvents = true
        panel.contentView = chip
        panel.setFrame(place.frame.insetBy(dx: -room, dy: -room), display: true)
        window.addChildWindow(panel, ordered: .above)
        panel.orderFront(nil)
        self.panel = panel
    }
}
