import AppKit
import SailLook

/// Sail's mark, from the design's 150 pt box: the sigma at 14 with round caps and joins, and the
/// short line over it at 6 in the accent. Still, it stands on its tile (34/150 corners) turned over
/// to ink, as the welcome draws it. Drawing, it has no tile and stands for a wait that owns a view
/// or a card: the sigma in secondary ink draws itself and leaves while the line flares, Mast's
/// loading mark (`Look.Timing.draw`), with an optional label under it in the eyebrow face that is
/// also what VoiceOver says. A button keeps its spinner; a row, `Spinner`. Under Reduce Motion the
/// drawing mark holds still, whole, following `Motion` live.
@MainActor
public final class BrandMark: NSView {
    public enum Presentation: Equatable, Sendable {
        case still
        case drawing(label: String?)
    }

    public static let side = 44.0
    static let box = 150.0, tileRadius = 34.0, sigmaStroke = 14.0, lineStroke = 6.0
    static let sigmaPoints: [CGPoint] = [CGPoint(x: 111, y: 53), CGPoint(x: 39, y: 53), CGPoint(x: 75, y: 83), CGPoint(x: 39, y: 113), CGPoint(x: 111, y: 113)]
    static let linePoints: [CGPoint] = [CGPoint(x: 57, y: 33), CGPoint(x: 93, y: 33)]
    static let drawKey = "draw"

    public var look: Look {
        didSet { repaint() }
    }

    public let presentation: Presentation
    public let markSide: Double
    /// Shows the drawing mark held at this fraction of its cycle, whatever the motion: the kit's
    /// shots of its frames.
    package var held: Double? {
        didSet { move() }
    }

    private let mono: LabelFont
    let glyph = NSView()
    let stage = CALayer()
    let tile = CAShapeLayer()
    let sigma = CAShapeLayer()
    let line = CAShapeLayer()

    public init(_ presentation: Presentation, side: Double = BrandMark.side, look: Look, mono: LabelFont) {
        self.presentation = presentation
        markSide = side
        self.look = look
        self.mono = mono
        super.init(frame: NSRect(x: 0, y: 0, width: side, height: side))
        glyph.wantsLayer = true
        addSubview(glyph)
        glyph.layer?.addSublayer(stage)
        let scale = CGAffineTransform(scaleX: side / Self.box, y: side / Self.box), corner = Self.tileRadius * side / Self.box
        tile.path = CGPath(roundedRect: CGRect(x: 0, y: 0, width: side, height: side), cornerWidth: corner, cornerHeight: corner, transform: nil)
        let sigmaPath = CGMutablePath(), linePath = CGMutablePath()
        sigmaPath.addLines(between: Self.sigmaPoints, transform: scale)
        linePath.addLines(between: Self.linePoints, transform: scale)
        sigma.path = sigmaPath
        line.path = linePath
        for (shape, width) in [(sigma, Self.sigmaStroke), (line, Self.lineStroke)] {
            shape.fillColor = nil
            shape.lineWidth = width * side / Self.box
            shape.lineCap = .round
            shape.lineJoin = .round
        }
        for shape in [tile, sigma, line] {
            shape.frame = CGRect(x: 0, y: 0, width: side, height: side)
            stage.addSublayer(shape)
        }
        tile.isHidden = presentation != .still
        setAccessibilityElement(true)
        switch presentation {
        case .still:
            setAccessibilityRole(.image)
            setAccessibilityLabel("Sail")
        case let .drawing(label):
            setAccessibilityRole(.progressIndicator)
            setAccessibilityLabel(label ?? "Busy")
        }
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

    /// What the label says, as drawn: the eyebrow's capitals.
    var labelText: NSAttributedString? {
        guard case let .drawing(label?) = presentation else { return nil }
        return Draw.text(label, mono.font(Look.Step.micro.points) as NSFont, look.textTertiary, tracking: Look.Tracking.eyebrow, upper: true)
    }

    private var labelHeight: Double {
        let points = Look.Step.micro.points
        return (TextLine.monoAscent * points).rounded() + (TextLine.monoDescent * points).rounded()
    }

    override public var intrinsicContentSize: NSSize {
        guard let labelText else { return NSSize(width: markSide, height: markSide) }
        return NSSize(width: max(markSide, Double(ceil(labelText.size().width))), height: markSide + Look.Space.l + labelHeight)
    }

    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        needsLayout = true
    }

    override public func layout() {
        super.layout()
        glyph.frame = backingAlignedRect(NSRect(x: (Double(bounds.width) - markSide) / 2, y: 0, width: markSide, height: markSide), options: .alignAllEdgesNearest)
        still {
            stage.frame = glyph.bounds
            stage.isGeometryFlipped = false
            stage.isGeometryFlipped = !stage.contentsAreFlipped()
        }
    }

    /// The label is centred by its glyphs' outlines, not its advance, which ends in the letter-spacing
    /// after the last capital.
    override public func draw(_: NSRect) {
        guard let labelText else { return }
        let ink = CTLineGetBoundsWithOptions(CTLineCreateWithAttributedString(labelText), .useGlyphPathBounds)
        Draw.line(labelText, x: (Double(bounds.width) - Double(ink.width)) / 2 - Double(ink.minX), baseline: TextLine.baseline(top: markSide + Look.Space.l, points: Look.Step.micro.points))
    }

    override public func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        move()
    }

    override public func viewDidHide() {
        super.viewDidHide()
        move()
    }

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        move()
    }

    /// Whether the mark is drawing itself now: one held at a point of its cycle stands still.
    package var isDrawing: Bool {
        [sigma, line].contains { $0.animation(forKey: Self.drawKey).map { $0.speed != 0 } ?? false }
    }

    @objc private func motionChanged() {
        move()
    }

    /// The mark draws itself only where it can be seen: in a window, not hidden nor under a hidden
    /// view, motion not reduced (or held, for the kit's shots).
    private func move() {
        guard presentation != .still, window != nil, !isHiddenOrHasHiddenAncestor, held != nil || !Motion.shared.reduced else {
            sigma.removeAnimation(forKey: Self.drawKey)
            line.removeAnimation(forKey: Self.drawKey)
            return
        }
        guard held != nil || !isDrawing else { return }
        let (draw, flare) = Self.cycle(heldAt: held)
        sigma.add(draw, forKey: Self.drawKey)
        line.add(flare, forKey: Self.drawKey)
    }

    /// One cycle of the mark, repeating: the sigma's end runs to the whole path by `drawn`, holds to
    /// `held`, then its start follows it off, its opacity rising from .35 and falling back; the line's
    /// opacity flares from .2 to full at `drawn` and back. Each step eases in and out, as Mast's
    /// keyframes do. Held at a fraction, it runs at speed 0 from that point.
    static func cycle(heldAt fraction: Double?) -> (draw: CAAnimationGroup, flare: CAKeyframeAnimation) {
        let draw = CAAnimationGroup(), drawn = Look.Timing.drawn, held = Look.Timing.held
        draw.animations = [
            keyframes("strokeEnd", [0, 1, 1, 1], at: [0, drawn, held, 1]), keyframes("strokeStart", [0, 0, 0, 1], at: [0, drawn, held, 1]),
            keyframes("opacity", [0.35, 1, 1, 0.35], at: [0, drawn, held, 1]),
        ]
        let flare = keyframes("opacity", [0.2, 1, 0.2], at: [0, drawn, 1])
        for animation in [draw, flare] as [CAAnimation] {
            animation.duration = Look.Timing.draw
            animation.repeatCount = .infinity
            if let fraction {
                animation.speed = 0
                animation.timeOffset = fraction * Look.Timing.draw
            }
        }
        return (draw, flare)
    }

    private static func keyframes(_ path: String, _ values: [Double], at times: [Double]) -> CAKeyframeAnimation {
        let animation = CAKeyframeAnimation(keyPath: path)
        animation.values = values
        animation.keyTimes = times.map { NSNumber(value: $0) }
        animation.timingFunctions = Array(repeating: CAMediaTimingFunction(name: .easeInEaseOut), count: values.count - 1)
        animation.duration = Look.Timing.draw
        return animation
    }

    private func repaint() {
        still {
            tile.fillColor = NSColor(Tint(look.inverse)).cgColor
            sigma.strokeColor = presentation == .still ? NSColor(Tint(look.onInverse)).cgColor : NSColor(look.textSecondary).cgColor
            line.strokeColor = NSColor(Tint(look.accent)).cgColor
        }
        invalidateIntrinsicContentSize()
        needsDisplay = true
    }

    private func still(_ change: () -> Void) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        change()
        CATransaction.commit()
    }
}
