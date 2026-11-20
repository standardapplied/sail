import AppKit
import SailLook

/// The content card's outline on the chrome: a 1 pt hairline round the card, its corners round,
/// in the look's `cardEdge`; with none (light), hidden. It is eight small views laid around the
/// card, four 1 pt lines and four corners of the card's radius, each with its own small layer and
/// taking no clicks, so nothing the card's size lies over the terminals: a full-card bitmap was
/// painted again at every change of look, and a full-card shape layer over them was drawn again
/// with every frame they committed (their drawables held back 80 to 100 ms in the floods).
@MainActor
final class CardEdge {
    enum Corner: CaseIterable {
        case bottomLeading, bottomTrailing, topTrailing, topLeading
    }

    var look: Look {
        didSet { if look != oldValue { paint() } }
    }

    /// The lines (leading, trailing, top, bottom), then the corners in ``Corner``'s order.
    let parts: [NSView]
    private let lines: [Line]
    private let corners: [Arc]

    init(look: Look) {
        self.look = look
        lines = (0 ..< 4).map { _ in Line() }
        corners = Corner.allCases.map(Arc.init)
        parts = lines + corners
        paint()
    }

    /// Whether the outline is drawn: not when the look has no edge.
    var isHidden: Bool {
        parts.allSatisfy(\.isHidden)
    }

    /// Lays the parts over `card`'s edge in `root`, above the card.
    func place(around card: NSView, in root: NSView) {
        let reach = Look.contentRadius
        for part in parts {
            part.translatesAutoresizingMaskIntoConstraints = false
            root.addSubview(part, positioned: .above, relativeTo: card)
        }
        let (leading, trailing, top, bottom) = (lines[0], lines[1], lines[2], lines[3])
        var constraints = [
            leading.leadingAnchor.constraint(equalTo: card.leadingAnchor), leading.widthAnchor.constraint(equalToConstant: 1),
            leading.topAnchor.constraint(equalTo: card.topAnchor, constant: reach), leading.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -reach),
            trailing.trailingAnchor.constraint(equalTo: card.trailingAnchor), trailing.widthAnchor.constraint(equalToConstant: 1),
            trailing.topAnchor.constraint(equalTo: card.topAnchor, constant: reach), trailing.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -reach),
            top.topAnchor.constraint(equalTo: card.topAnchor), top.heightAnchor.constraint(equalToConstant: 1),
            top.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: reach), top.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -reach),
            bottom.bottomAnchor.constraint(equalTo: card.bottomAnchor), bottom.heightAnchor.constraint(equalToConstant: 1),
            bottom.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: reach), bottom.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -reach),
        ]
        for corner in corners {
            constraints += [corner.widthAnchor.constraint(equalToConstant: reach), corner.heightAnchor.constraint(equalToConstant: reach)]
            switch corner.corner {
            case .bottomLeading: constraints += [corner.leftAnchor.constraint(equalTo: card.leftAnchor), corner.bottomAnchor.constraint(equalTo: card.bottomAnchor)]
            case .bottomTrailing: constraints += [corner.rightAnchor.constraint(equalTo: card.rightAnchor), corner.bottomAnchor.constraint(equalTo: card.bottomAnchor)]
            case .topTrailing: constraints += [corner.rightAnchor.constraint(equalTo: card.rightAnchor), corner.topAnchor.constraint(equalTo: card.topAnchor)]
            case .topLeading: constraints += [corner.leftAnchor.constraint(equalTo: card.leftAnchor), corner.topAnchor.constraint(equalTo: card.topAnchor)]
            }
        }
        NSLayoutConstraint.activate(constraints)
    }

    /// The parts in the look's edge; with none, hidden.
    private func paint() {
        let edge = look.cardEdge.map { NSColor($0).cgColor }
        for part in parts {
            part.isHidden = edge == nil
        }
        guard let edge else { return }
        lines.forEach { $0.layer?.backgroundColor = edge }
        corners.forEach { $0.shape.strokeColor = edge }
    }

    /// A 1 pt line of the outline: its layer's colour, and no bitmap.
    final class Line: NSView {
        init() {
            super.init(frame: .zero)
            wantsLayer = true
            layerContentsRedrawPolicy = .never
            setAccessibilityElement(false)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            nil
        }

        override var wantsUpdateLayer: Bool {
            true
        }

        override func updateLayer() {}

        override func hitTest(_: NSPoint) -> NSView? {
            nil
        }
    }

    /// One corner of the outline: a quarter circle of the card's radius, in its own shape layer.
    final class Arc: NSView {
        let corner: Corner
        let shape = CAShapeLayer()

        init(_ corner: Corner) {
            self.corner = corner
            super.init(frame: .zero)
            shape.fillColor = nil
            shape.lineWidth = 1
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

        /// The quarter circle in the layer's own space (y up), from one edge's line to the other's.
        override func layout() {
            super.layout()
            let reach = Double(bounds.width), radius = reach - 0.5
            let (start, bend, end): (CGPoint, CGPoint, CGPoint) = switch corner {
            case .bottomLeading: (CGPoint(x: 0.5, y: reach), CGPoint(x: 0.5, y: 0.5), CGPoint(x: reach, y: 0.5))
            case .bottomTrailing: (CGPoint(x: 0, y: 0.5), CGPoint(x: radius, y: 0.5), CGPoint(x: radius, y: reach))
            case .topTrailing: (CGPoint(x: radius, y: 0), CGPoint(x: radius, y: radius), CGPoint(x: 0, y: radius))
            case .topLeading: (CGPoint(x: reach, y: radius), CGPoint(x: 0.5, y: radius), CGPoint(x: 0.5, y: 0))
            }
            let path = CGMutablePath()
            path.move(to: start)
            path.addArc(tangent1End: bend, tangent2End: end, radius: radius)
            shape.path = path
        }
    }
}
