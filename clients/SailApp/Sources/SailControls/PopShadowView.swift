import AppKit
import SailLook

/// The pop shadow (`PopShadow`) cast by a rectangle: set behind a view floating over the content,
/// with the same frame, it draws each layer of the look's shadow and takes no clicks.
@MainActor
public final class PopShadowView: NSView {
    public var look: Look {
        didSet { if look != oldValue { needsLayout = true } }
    }

    /// The corners of the rectangle that casts it: a rounded card's, so no square corner of shadow
    /// shows past the card's.
    public var radius = 0.0 {
        didSet { if radius != oldValue { needsLayout = true } }
    }

    public init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        wantsLayer = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The layers as drawn, one per layer of the look's shadow.
    public var cast: [CALayer] {
        layer?.sublayers ?? []
    }

    override public func layout() {
        super.layout()
        guard let layer else { return }
        let shadows = PopShadow.layers(look)
        while (layer.sublayers?.count ?? 0) < shadows.count {
            layer.addSublayer(CALayer())
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for (shadow, cast) in zip(shadows, cast) {
            cast.frame = layer.bounds
            let box = layer.bounds.insetBy(dx: -shadow.spread, dy: -shadow.spread), corner = min(radius + shadow.spread, Double(min(box.width, box.height)) / 2)
            cast.shadowPath = radius > 0 ? CGPath(roundedRect: box, cornerWidth: corner, cornerHeight: corner, transform: nil) : CGPath(rect: box, transform: nil)
            cast.shadowColor = NSColor(Tint(shadow.color.rgb)).cgColor
            cast.shadowOpacity = Float(shadow.color.alpha)
            cast.shadowRadius = shadow.blur / 2
            cast.shadowOffset = CGSize(width: 0, height: -shadow.y)
        }
        CATransaction.commit()
    }

    override public func hitTest(_: NSPoint) -> NSView? {
        nil
    }
}
