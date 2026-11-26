import AppKit
import SailLook

/// A workspace's or a template's state (`PillPaint`): a hairline frame in inner corners, a square
/// dot carrying the colour, and the state in mono capitals. Starting, creating and building pulse
/// their dot every 1.2 s, held still while motion is reduced. A plain pill says words of its own
/// in the same frame, with no dot, after an icon when it has one (This Mac's "Local").
@MainActor
public final class Pill: NSView {
    /// A plain pill's words and icon.
    private struct Plain {
        let words: String
        let icon: LineIcon?
    }

    /// A plain pill's icon, at the size of the type beside it.
    static let iconSide = Look.Step.micro.points

    public var look: Look {
        didSet { repaint() }
    }

    public var state: PillState {
        didSet { repaint() }
    }

    public let size: PillSize
    private let mono: LabelFont
    private let plain: Plain?
    let dot = PillDot()

    public convenience init(_ state: PillState, size: PillSize = .regular, look: Look, mono: LabelFont) {
        self.init(state, plain: nil, size: size, look: look, mono: mono)
    }

    /// A plain pill saying `words`, `icon` before them.
    public convenience init(plain words: String, icon: LineIcon? = nil, size: PillSize = .regular, look: Look, mono: LabelFont) {
        self.init(.unknown, plain: Plain(words: words, icon: icon), size: size, look: look, mono: mono)
    }

    private init(_ state: PillState, plain: Plain?, size: PillSize, look: Look, mono: LabelFont) {
        self.state = state
        self.plain = plain
        self.size = size
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        dot.wantsLayer = true
        dot.isHidden = plain != nil
        addSubview(dot)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        NotificationCenter.default.addObserver(self, selector: #selector(motionChanged), name: Motion.changed, object: nil)
        repaint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    var paint: PillPaint {
        PillPaint(state, look: look)
    }

    private var label: NSAttributedString {
        Draw.text(plain?.words ?? state.label, mono.font(size.text) as NSFont, paint.text, tracking: size.tracking, upper: true)
    }

    /// The room a plain pill's icon takes before its words.
    private var iconRoom: Double {
        plain?.icon == nil ? 0 : Self.iconSide + size.gap
    }

    override public var intrinsicContentSize: NSSize {
        let words = Double(ceil(label.size().width))
        return NSSize(width: plain == nil ? size.width(label: words) : size.leading + iconRoom + words + size.trailing, height: size.height)
    }

    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        needsLayout = true
    }

    override public func layout() {
        super.layout()
        let width = Double(bounds.width), height = Double(bounds.height)
        let x = userInterfaceLayoutDirection == .rightToLeft ? width - size.leading - size.dot : size.leading
        dot.frame = NSRect(x: x, y: ((height - size.dot) / 2).rounded(), width: size.dot, height: size.dot)
    }

    override public func draw(_: NSRect) {
        Draw.box(bounds, radius: Look.innerRadius, fill: nil, edge: paint.edge, dash: paint.dashed ? PillSize.dash.map { CGFloat($0) } : [])
        let label = self.label, leftToRight = userInterfaceLayoutDirection != .rightToLeft
        let x = leftToRight ? size.leading + (plain == nil ? size.dot + size.gap : iconRoom) : size.trailing
        Draw.line(label, x: x, baseline: TextLine.baseline(top: (Double(bounds.height) - size.text) / 2, points: size.text, lineHeight: size.text))
        guard let icon = plain?.icon else { return }
        let side = Self.iconSide, width = Double(bounds.width)
        Draw.icon(icon, in: NSRect(x: leftToRight ? size.leading : width - size.leading - side, y: (Double(bounds.height) - side) / 2, width: side, height: side), ink: paint.text)
    }

    private func repaint() {
        dot.ink = paint.dot
        dot.hollow = paint.hollow
        setAccessibilityLabel(plain?.words ?? state.label)
        invalidateIntrinsicContentSize()
        Motion.pulse(dot.layer, state.pulses && window != nil)
        needsDisplay = true
    }

    override public func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        Motion.pulse(dot.layer, state.pulses && window != nil)
    }

    @objc private func motionChanged() {
        Motion.pulse(dot.layer, state.pulses && window != nil)
    }
}

/// The pill's square dot: filled, or a 1 pt square outline for a stopped workspace.
final class PillDot: NSView {
    var ink = Tint(0) {
        didSet { needsDisplay = true }
    }

    var hollow = false {
        didSet { needsDisplay = true }
    }

    override func draw(_: NSRect) {
        guard hollow else {
            NSColor(ink).setFill()
            return NSBezierPath(rect: bounds).fill()
        }
        NSColor(ink).setStroke()
        let edge = NSBezierPath(rect: bounds.insetBy(dx: 0.5, dy: 0.5))
        edge.lineWidth = 1
        edge.stroke()
    }
}

/// A key as a person presses it: mono capitals on the surface, a hairline with a 2 pt foot.
@MainActor
public final class Keycap: NSView {
    public var look: Look {
        didSet { needsDisplay = true }
    }

    private let key: String
    private let mono: LabelFont

    public init(_ key: String, look: Look, mono: LabelFont) {
        self.key = key
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        setAccessibilityLabel(key)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    private var label: NSAttributedString {
        Draw.text(key, mono.font(Look.Step.caption.points) as NSFont, look.textSecondary)
    }

    /// Mast's kbd: 3 by 5 pt padding around a 10 pt line, a 1 pt edge and a 2 pt foot.
    override public var intrinsicContentSize: NSSize {
        NSSize(width: ceil(label.size().width) + 12, height: 19)
    }

    override public func draw(_: NSRect) {
        let inner = NSRect(x: 1, y: 1, width: bounds.width - 2, height: bounds.height - 3)
        NSColor(Tint(look.surface)).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: Look.innerRadius, yRadius: Look.innerRadius).fill()
        let edge = NSBezierPath(roundedRect: bounds, xRadius: Look.innerRadius, yRadius: Look.innerRadius)
        edge.append(NSBezierPath(roundedRect: inner, xRadius: Look.innerRadius - 1, yRadius: Look.innerRadius - 1))
        edge.windingRule = .evenOdd
        NSColor(look.separatorStrong).setFill()
        edge.fill()
        let label = self.label, points = Look.Step.caption.points
        Draw.line(label, x: ((bounds.width - label.size().width) / 2).rounded(), baseline: TextLine.baseline(top: Double(inner.minY) + 3, points: points, lineHeight: points))
    }
}

/// A person's initial in a 20 pt circle on the hover fill, ringed by a hairline.
@MainActor
public final class Initial: NSView {
    public var look: Look {
        didSet { needsDisplay = true }
    }

    private let name: String

    public init(_ name: String, look: Look) {
        self.name = name
        self.look = look
        super.init(frame: NSRect(x: 0, y: 0, width: 20, height: 20))
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        setAccessibilityLabel(name)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    override public var intrinsicContentSize: NSSize {
        NSSize(width: 20, height: 20)
    }

    override public func draw(_: NSRect) {
        let mark = look.avatar
        if let fill = mark.fill {
            NSColor(fill).setFill()
            NSBezierPath(ovalIn: bounds).fill()
        }
        if let edge = mark.edge {
            NSColor(edge).setStroke()
            let ring = NSBezierPath(ovalIn: bounds.insetBy(dx: 0.5, dy: 0.5))
            ring.lineWidth = 1
            ring.stroke()
        }
        let letter = Draw.text(String(name.prefix(1)).uppercased(), .systemFont(ofSize: Look.Step.caption.points, weight: .semibold), mark.ink)
        letter.draw(at: NSPoint(x: ((bounds.width - letter.size().width) / 2).rounded(), y: ((bounds.height - letter.size().height) / 2).rounded()))
    }
}

extension Initial: Marked {
    package var drawnMarks: [Mark] {
        look.marks(fill: look.avatar.fill, area: Double(bounds.width * bounds.height))
    }
}
