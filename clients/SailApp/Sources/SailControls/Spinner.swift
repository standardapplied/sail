import AppKit
import SailLook

/// The spinner's layers: a 14 pt ring at 1.5 pt with a quarter arc turning once every 0.7 s, or
/// holding still while motion is reduced. Its own layers, so it turns about its centre wherever
/// its host lays it.
@MainActor
final class SpinnerRing {
    static let size = 14.0, width = 1.5

    let layer = CALayer()
    let arc = CAShapeLayer()
    private let track = CAShapeLayer()

    init() {
        let circle = CGPath(ellipseIn: CGRect(x: 0, y: 0, width: Self.size, height: Self.size).insetBy(dx: Self.width / 2, dy: Self.width / 2), transform: nil)
        let quarter = CGMutablePath()
        quarter.addArc(center: CGPoint(x: Self.size / 2, y: Self.size / 2), radius: (Self.size - Self.width) / 2, startAngle: .pi / 4, endAngle: 3 * .pi / 4, clockwise: false)
        for (shape, path) in [(track, circle), (arc, quarter as CGPath)] {
            shape.path = path
            shape.fillColor = nil
            shape.lineWidth = Self.width
            shape.frame = CGRect(x: 0, y: 0, width: Self.size, height: Self.size)
            layer.addSublayer(shape)
        }
        layer.bounds = CGRect(x: 0, y: 0, width: Self.size, height: Self.size)
    }

    func paint(_ ink: SpinnerInk, opacity: Double = 1) {
        still {
            track.strokeColor = NSColor(ink.track).cgColor
            arc.strokeColor = NSColor(ink.arc).cgColor
            layer.opacity = Float(opacity)
        }
    }

    /// Centred on `point` of its host's layer, upright whether or not the host is flipped, so the
    /// arc held still is at the top and it turns clockwise.
    func place(at point: CGPoint, hidden: Bool) {
        still {
            layer.position = point
            layer.isHidden = hidden
            layer.isGeometryFlipped = false
            layer.isGeometryFlipped = layer.contentsAreFlipped()
        }
    }

    var isTurning: Bool {
        arc.animation(forKey: Self.turnKey) != nil
    }

    func turn(_ on: Bool) {
        guard on, !Motion.shared.reduced else { return arc.removeAnimation(forKey: Self.turnKey) }
        guard !isTurning else { return }
        let turn = CABasicAnimation(keyPath: "transform.rotation.z")
        turn.fromValue = 0
        turn.toValue = -2 * Double.pi
        turn.duration = Look.Timing.turn
        turn.repeatCount = .infinity
        arc.add(turn, forKey: Self.turnKey)
    }

    private func still(_ change: () -> Void) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        change()
        CATransaction.commit()
    }

    static let turnKey = "turn"
}

/// A spinner on its own: the look's strong separator with an ink arc.
@MainActor
public final class Spinner: NSView {
    public var look: Look {
        didSet { ring.paint(.plain(look)) }
    }

    let ring = SpinnerRing()

    public init(look: Look) {
        self.look = look
        super.init(frame: NSRect(x: 0, y: 0, width: SpinnerRing.size, height: SpinnerRing.size))
        wantsLayer = true
        layer?.addSublayer(ring.layer)
        ring.paint(.plain(look))
        setAccessibilityElement(true)
        setAccessibilityRole(.progressIndicator)
        setAccessibilityLabel("Busy")
        NotificationCenter.default.addObserver(self, selector: #selector(motionChanged), name: Motion.changed, object: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var intrinsicContentSize: NSSize {
        NSSize(width: SpinnerRing.size, height: SpinnerRing.size)
    }

    override public func layout() {
        super.layout()
        ring.place(at: CGPoint(x: bounds.midX, y: bounds.midY), hidden: false)
    }

    override public func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        ring.turn(window != nil)
    }

    @objc private func motionChanged() {
        ring.turn(window != nil)
    }
}
