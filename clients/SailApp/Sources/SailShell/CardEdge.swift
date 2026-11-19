import AppKit
import SailLook

/// The content card's edge on the paper chrome, as the website's nav meets its page: a 1 pt
/// hairline along the card's leading and top edges, round at the top-leading corner. It lies over
/// the card and takes no clicks.
@MainActor
final class CardEdge: NSView {
    var look: Look {
        didSet { needsDisplay = true }
    }

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        setAccessibilityElement(false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func hitTest(_: NSPoint) -> NSView? {
        nil
    }

    /// The hairline's path: up the leading edge, round the corner, along the top.
    var edge: NSBezierPath {
        let radius = Look.contentRadius, inset = 0.5, height = Double(bounds.height), width = Double(bounds.width)
        let path = NSBezierPath()
        path.move(to: NSPoint(x: inset, y: height))
        path.line(to: NSPoint(x: inset, y: radius))
        path.appendArc(withCenter: NSPoint(x: radius, y: radius), radius: radius - inset, startAngle: 180, endAngle: 270)
        path.line(to: NSPoint(x: width, y: inset))
        guard userInterfaceLayoutDirection == .rightToLeft else { return path }
        var mirror = AffineTransform(scaleByX: -1, byY: 1)
        mirror.append(AffineTransform(translationByX: width, byY: 0))
        path.transform(using: mirror)
        return path
    }

    override func draw(_: NSRect) {
        NSColor(look.separator).setStroke()
        let path = edge
        path.lineWidth = 1
        path.stroke()
    }
}
