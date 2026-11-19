import AppKit
import SailLook

/// The content card's edge on the chrome, as the website's nav meets its page: a 1 pt hairline on
/// the card's leading and top edges, round at the top-leading corner. It lies over the card and
/// draws only two 1 pt lines and the 6 pt corner, each its own small layer, under an empty host:
/// a full-card bitmap was painted again at every change of look (the four-pane switch held the
/// main thread twice as long), and a full-card shape layer over the terminals was drawn again with
/// every frame they committed (their drawables held back 80 to 100 ms in the drive's floods).
@MainActor
final class CardEdge: NSView {
    var look: Look {
        didSet { if look != oldValue { paint() } }
    }

    private let leadingLine = CALayer(), topLine = CALayer(), corner = CAShapeLayer()

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        let host = CALayer()
        for line in [leadingLine, topLine, corner] as [CALayer] {
            line.actions = ["bounds": NSNull(), "position": NSNull(), "backgroundColor": NSNull(), "strokeColor": NSNull(), "path": NSNull()]
            host.addSublayer(line)
        }
        corner.fillColor = nil
        corner.lineWidth = 1
        layer = host
        wantsLayer = true
        setAccessibilityElement(false)
        paint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func hitTest(_: NSPoint) -> NSView? {
        nil
    }

    /// How far the round corner reaches along each edge: the card's radius.
    static let reach = Look.contentRadius

    /// Its lines in the look's edge; with none, hidden, so no layer lies over the card.
    private func paint() {
        isHidden = look.cardEdge == nil
        guard let edge = look.cardEdge.map({ NSColor($0).cgColor }) else { return }
        leadingLine.backgroundColor = edge
        topLine.backgroundColor = edge
        corner.strokeColor = edge
    }

    /// The lines and the corner in the layer's own space (y up), the leading edge on the right when
    /// the layout runs right to left.
    override func layout() {
        super.layout()
        let width = Double(bounds.width), height = Double(bounds.height), reach = Self.reach
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        let x = { (left: Double, span: Double) in rightToLeft ? width - left - span : left }
        leadingLine.frame = CGRect(x: x(0, 1), y: 0, width: 1, height: max(0, height - reach))
        topLine.frame = CGRect(x: x(reach, max(0, width - reach)), y: height - 1, width: max(0, width - reach), height: 1)
        corner.frame = CGRect(x: x(0, reach), y: height - reach, width: reach, height: reach)
        let path = CGMutablePath(), radius = reach - 0.5
        let start = CGPoint(x: rightToLeft ? reach - 0.5 : 0.5, y: 0), end = CGPoint(x: rightToLeft ? 0 : reach, y: radius)
        path.move(to: start)
        path.addArc(tangent1End: CGPoint(x: start.x, y: radius), tangent2End: end, radius: radius)
        corner.path = path
    }
}
