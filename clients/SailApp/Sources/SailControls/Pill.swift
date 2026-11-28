import AppKit
import SailLook

/// A workspace's or a template's state (`PillPaint`): a hairline frame in inner corners, a square
/// dot carrying the colour, and the state in mono capitals. Starting, creating and building pulse
/// their dot every 1.2 s, held still while motion is reduced. A pill may say words of its own in a
/// state's paint (the control plane's "Reconnecting").
@MainActor
public final class Pill: NSView {
    public var look: Look {
        didSet { repaint() }
    }

    public var state: PillState {
        didSet { repaint() }
    }

    /// The words said in place of the state's label.
    public var words: String? {
        didSet { repaint() }
    }

    public let size: PillSize
    private let mono: LabelFont
    let dot = PillDot()

    public init(_ state: PillState, size: PillSize = .regular, look: Look, mono: LabelFont) {
        self.state = state
        self.size = size
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        addSubview(dot)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
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
        Draw.text(said, mono.font(size.text) as NSFont, paint.text, tracking: size.tracking, upper: true)
    }

    public var said: String {
        words ?? state.label
    }

    override public var intrinsicContentSize: NSSize {
        let words = Double(ceil(label.size().width))
        return NSSize(width: size.width(label: words), height: size.height)
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
        let x = leftToRight ? size.leading + size.dot + size.gap : size.trailing
        Draw.line(label, x: x, baseline: TextLine.baseline(top: (Double(bounds.height) - size.text) / 2, points: size.text, lineHeight: size.text))
    }

    private func repaint() {
        dot.paint(state, look: look)
        setAccessibilityLabel(said)
        invalidateIntrinsicContentSize()
        needsDisplay = true
    }
}

/// A state's square, the pill's dot and a workspace row's in the left panel: filled, or a 1 pt
/// square outline for a stopped workspace; pulsing every 1.2 s while in a window for a state under
/// way, held still while motion is reduced.
package final class PillDot: NSView {
    package private(set) var ink = Tint(0) {
        didSet { needsDisplay = true }
    }

    package private(set) var hollow = false {
        didSet { needsDisplay = true }
    }

    var pulses = false {
        didSet { pulse() }
    }

    package init() {
        super.init(frame: .zero)
        wantsLayer = true
        NotificationCenter.default.addObserver(self, selector: #selector(motionChanged), name: Motion.changed, object: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Drawn as `state`'s dot is in `look`.
    package func paint(_ state: PillState, look: Look) {
        let paint = PillPaint(state, look: look)
        ink = paint.dot
        hollow = paint.hollow
        pulses = state.pulses
    }

    override package func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        pulse()
    }

    @objc private func motionChanged() {
        pulse()
    }

    private func pulse() {
        Motion.pulse(layer, pulses && window != nil)
    }

    override package func draw(_: NSRect) {
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
    public static let height = 19.0

    override public var intrinsicContentSize: NSSize {
        NSSize(width: ceil(label.size().width) + 12, height: Self.height)
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

/// A person's initial in a circle drawn as `mark` (`Look.avatar`, a hairline ring, unless given):
/// 20 pt in a caption, or as large as a header asks in its own face and mark.
@MainActor
public final class Initial: NSView {
    public var look: Look {
        didSet { needsDisplay = true }
    }

    public var name: String {
        didSet {
            setAccessibilityLabel(name)
            needsDisplay = true
        }
    }

    private let side: Double
    private let font: NSFont
    private let mark: KeyPath<Look, InkMark>

    public init(
        _ name: String, side: Double = 20, font: NSFont = .systemFont(ofSize: Look.Step.caption.points, weight: .semibold), mark: KeyPath<Look, InkMark> = \.avatar, look: Look
    ) {
        self.name = name
        self.look = look
        self.side = side
        self.font = font
        self.mark = mark
        super.init(frame: NSRect(x: 0, y: 0, width: side, height: side))
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
        NSSize(width: side, height: side)
    }

    /// The mark as it is drawn in its look.
    package var drawn: InkMark {
        look[keyPath: mark]
    }

    override public func draw(_: NSRect) {
        let mark = drawn
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
        let letter = Draw.text(String(name.prefix(1)).uppercased(), font, mark.ink)
        letter.draw(at: NSPoint(x: ((bounds.width - letter.size().width) / 2).rounded(), y: ((bounds.height - letter.size().height) / 2).rounded()))
    }
}

extension Initial: Marked {
    package var drawnMarks: [Mark] {
        look.marks(fill: drawn.fill, area: Double(bounds.width * bounds.height))
    }
}
