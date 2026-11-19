import AppKit
import SailControls
import SailLook

/// The list's frame: the surface in 6 pt corners with a hairline. It holds a view at `height`, or
/// placed by its own constraints with none. Holding the list, it scrolls it with the quiet
/// scroller: never taller than the rows, it gives way below that to the card's foot, under the
/// column's own priority and so never holding the window.
@MainActor
final class ListFrame: NSView {
    /// The height of what it holds (the list's rows, the skeleton's); nil, placed by constraints.
    var height: Double? = 0 {
        didSet {
            invalidateIntrinsicContentSize()
            tallest?.constant = (height ?? 0) + 2
            needsLayout = true
        }
    }

    /// Below the column's foot (`WorkspacesView.yields`): the frame shrinks before the column runs
    /// past the card.
    static let givesWay = NSLayoutConstraint.Priority(WorkspacesView.yields.rawValue - 10)

    let scroll: QuietScrollView?
    private var tallest: NSLayoutConstraint?

    init(look: Look) {
        self.look = look
        scroll = nil
        super.init(frame: .zero)
    }

    init(scrolling list: NSView, look: Look) {
        self.look = look
        let scroll = QuietScrollView(look: look)
        self.scroll = scroll
        super.init(frame: .zero)
        scroll.documentView = list
        addSubview(scroll)
        let tallest = heightAnchor.constraint(lessThanOrEqualToConstant: 2)
        tallest.isActive = true
        self.tallest = tallest
        setContentCompressionResistancePriority(Self.givesWay, for: .vertical)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: height.map { $0 + 2 } ?? NSView.noIntrinsicMetric)
    }

    var look: Look {
        didSet {
            scroll?.look = look
            needsDisplay = true
        }
    }

    override var isFlipped: Bool {
        true
    }

    override func layout() {
        super.layout()
        guard let height else { return }
        let inside = NSRect(x: 1, y: 1, width: Double(bounds.width) - 2, height: Double(bounds.height) - 2)
        guard let scroll else {
            subviews.first?.frame = NSRect(x: 1, y: 1, width: inside.width, height: height)
            return
        }
        scroll.frame = inside
        scroll.documentView?.frame = NSRect(x: 0, y: 0, width: Double(scroll.contentSize.width), height: height)
    }

    override func draw(_: NSRect) {
        let shape = NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: Look.contentRadius - 0.5, yRadius: Look.contentRadius - 0.5)
        NSColor(Tint(look.surface)).setFill()
        shape.fill()
        NSColor(look.separator).setStroke()
        shape.stroke()
    }
}
