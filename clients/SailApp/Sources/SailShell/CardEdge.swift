import AppKit
import SailLook

/// The content card's outline on the chrome: a 1 pt hairline round the card, its 6 pt corners
/// round, in the look's `cardEdge`; with none (light), hidden, so nothing lies over the card. It
/// lies over the card and draws four 1 pt lines and four small corners, each its own layer, under an
/// empty host: a full-card bitmap was painted again at every change of look (the four-pane switch
/// held the main thread twice as long), and a full-card shape layer over the terminals was drawn
/// again with every frame they committed (their drawables held back 80 to 100 ms in the floods).
@MainActor
final class CardEdge: NSView {
    var look: Look {
        didSet { if look != oldValue { paint() } }
    }

    private let lines = (0 ..< 4).map { _ in CALayer() }
    private let corners = (0 ..< 4).map { _ in CAShapeLayer() }

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        let host = CALayer()
        for part in lines + corners as [CALayer] {
            part.actions = ["bounds": NSNull(), "position": NSNull(), "backgroundColor": NSNull(), "strokeColor": NSNull(), "path": NSNull()]
            host.addSublayer(part)
        }
        for corner in corners {
            corner.fillColor = nil
            corner.lineWidth = 1
        }
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

    /// How far each round corner reaches along its edges: the card's radius.
    static let reach = Look.contentRadius

    /// Its lines in the look's edge; with none, hidden, so no layer lies over the card.
    private func paint() {
        isHidden = look.cardEdge == nil
        guard let edge = look.cardEdge.map({ NSColor($0).cgColor }) else { return }
        lines.forEach { $0.backgroundColor = edge }
        corners.forEach { $0.strokeColor = edge }
    }

    /// The four lines between the corners, and each corner's quarter circle, in the layer's own
    /// space; a quarter turn maps one corner onto the next, so the outline is the same in either
    /// layout direction.
    override func layout() {
        super.layout()
        let width = Double(bounds.width), height = Double(bounds.height), reach = Self.reach
        let along = max(0, width - 2 * reach), down = max(0, height - 2 * reach)
        lines[0].frame = CGRect(x: 0, y: reach, width: 1, height: down)
        lines[1].frame = CGRect(x: width - 1, y: reach, width: 1, height: down)
        lines[2].frame = CGRect(x: reach, y: 0, width: along, height: 1)
        lines[3].frame = CGRect(x: reach, y: height - 1, width: along, height: 1)
        let origins = [CGPoint(x: 0, y: 0), CGPoint(x: width - reach, y: 0), CGPoint(x: width - reach, y: height - reach), CGPoint(x: 0, y: height - reach)]
        let radius = reach - 0.5
        let arcs: [(start: CGPoint, bend: CGPoint, end: CGPoint)] = [
            (CGPoint(x: 0.5, y: reach), CGPoint(x: 0.5, y: 0.5), CGPoint(x: reach, y: 0.5)),
            (CGPoint(x: 0, y: 0.5), CGPoint(x: radius, y: 0.5), CGPoint(x: radius, y: reach)),
            (CGPoint(x: radius, y: 0), CGPoint(x: radius, y: radius), CGPoint(x: 0, y: radius)),
            (CGPoint(x: reach, y: radius), CGPoint(x: 0.5, y: radius), CGPoint(x: 0.5, y: 0)),
        ]
        for (corner, (origin, arc)) in zip(corners, zip(origins, arcs)) {
            corner.frame = CGRect(origin: origin, size: CGSize(width: reach, height: reach))
            let path = CGMutablePath()
            path.move(to: arc.start)
            path.addArc(tangent1End: arc.bend, tangent2End: arc.end, radius: radius)
            corner.path = path
        }
    }
}
