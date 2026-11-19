import AppKit
import SailLook

/// The content card's edge on the paper chrome, as the website's nav meets its page: a 1 pt
/// hairline along the card's leading and top edges, round at the top-leading corner. It lies over
/// the card and takes no clicks. It hosts a shape layer and draws nothing itself: the card is the
/// whole window, and a view that size painting its own bitmap would be drawn again at every change
/// of look, over the terminals' frames.
@MainActor
final class CardEdge: NSView {
    var look: Look {
        didSet { if look != oldValue { shape.strokeColor = NSColor(look.separator).cgColor } }
    }

    let shape = CAShapeLayer()

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        shape.fillColor = nil
        shape.lineWidth = 1
        shape.strokeColor = NSColor(look.separator).cgColor
        shape.actions = ["path": NSNull(), "strokeColor": NSNull(), "bounds": NSNull(), "position": NSNull()]
        layer = shape
        wantsLayer = true
        setAccessibilityElement(false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func hitTest(_: NSPoint) -> NSView? {
        nil
    }

    override func layout() {
        super.layout()
        shape.path = edge
    }

    /// The hairline's path in the layer's own space (y up): up the leading edge, round the corner,
    /// along the top.
    var edge: CGPath {
        let radius = Look.contentRadius, inset = 0.5, height = Double(bounds.height), width = Double(bounds.width)
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        let x = { (value: Double) in rightToLeft ? width - value : value }
        let path = CGMutablePath()
        path.move(to: CGPoint(x: x(inset), y: 0))
        path.addLine(to: CGPoint(x: x(inset), y: height - radius))
        path.addArc(tangent1End: CGPoint(x: x(inset), y: height - inset), tangent2End: CGPoint(x: x(radius), y: height - inset), radius: radius - inset)
        path.addLine(to: CGPoint(x: x(width), y: height - inset))
        return path
    }
}
