import AppKit
import SailControls
import SailLook

/// The one skeleton every page of the app's sections is drawn in, so none is drawn its own way: a
/// column at most ``widest`` wide, centred in the page with its padding around it, the page's head
/// and everything under it sharing that column, each part spanning it. The head is the page's
/// title in Newsreader and at most one line under it at the leading edge, its buttons at the
/// trailing edge, 20 pt between them (the design's `.ph`); the foot, a keycap tall, its count at the
/// leading edge and its keys at the trailing edge. A Workspaces page is drawn in this view, pinned
/// to its edges; the Account page, laid out by arithmetic, takes its column from
/// ``frame(width:)``. A stack's width alignment holds no part to the column by itself: This Mac's
/// head, never pinned to it, hugged its words and stood at the right of a wide window.
@MainActor
package final class PageColumn: NSView {
    package static let padding = (top: 26.0, sides: 32.0, bottom: 20.0), widest = 720.0, listGap = 18.0, footGap = 12.0
    /// The room between the name's column and the buttons (the design's `.ph` gap), and between two
    /// buttons (`.ph-acts`, `.acts`): the stacks place the buttons' boxes, not their halos.
    static let headGap = 20.0, buttonGap = 6.0
    /// How hard the column fills the page up to ``widest``: over what its words hug, under the
    /// window's own size, so a page wider than the column never pulls the window narrower.
    static let fills = NSLayoutConstraint.Priority(NSLayoutConstraint.Priority.windowSizeStayPut.rawValue - 10)

    package let title = NSTextField(labelWithString: "")
    let lede = NSTextField(labelWithString: "")
    let words = NSStackView()
    let buttons = NSStackView()
    let head = NSView()
    let foot = NSView()
    let count = NSTextField(labelWithString: "")
    private let column = NSStackView()
    private var spans: [NSLayoutConstraint] = []

    /// The column of a page `width` wide: at most ``widest``, centred, the padding beside it at least.
    package static func frame(width: Double) -> (x: Double, width: Double) {
        let column = min(widest, max(width - 2 * padding.sides, 1))
        return ((width - column) / 2, column)
    }

    init(buttons shown: [NSView]) {
        super.init(frame: .zero)
        for label in [title, lede] {
            label.lineBreakMode = .byTruncatingTail
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        title.lineBreakMode = .byTruncatingMiddle
        count.lineBreakMode = .byTruncatingTail
        count.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        buildHead(shown)
        foot.heightAnchor.constraint(equalToConstant: Keycap.height).isActive = true
        column.orientation = .vertical
        column.alignment = .width
        column.setClippingResistancePriority(.defaultLow, for: .horizontal)
        column.spacing = 0
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        let above = column.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -Self.padding.bottom)
        above.priority = WorkspacesView.yields
        let spans = column.widthAnchor.constraint(equalTo: widthAnchor, constant: -2 * Self.padding.sides)
        spans.priority = Self.fills
        NSLayoutConstraint.activate([
            column.centerXAnchor.constraint(equalTo: centerXAnchor), spans, column.widthAnchor.constraint(lessThanOrEqualToConstant: Self.widest),
            column.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -2 * Self.padding.sides),
            column.topAnchor.constraint(equalTo: topAnchor, constant: Self.padding.top), above,
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// Pinned to `page`'s edges, which it fills.
    func fill(_ page: NSView) {
        translatesAutoresizingMaskIntoConstraints = false
        page.addSubview(self, positioned: .below, relativeTo: nil)
        NSLayoutConstraint.activate([
            leadingAnchor.constraint(equalTo: page.leadingAnchor), trailingAnchor.constraint(equalTo: page.trailingAnchor),
            topAnchor.constraint(equalTo: page.topAnchor), bottomAnchor.constraint(equalTo: page.bottomAnchor),
        ])
    }

    /// The head says `name` in the page title's style and `lede` under it, which a narrow page drops.
    func name(_ name: String, lede said: String, narrow: Bool, look: Look, titleFont: TitleFont) {
        title.attributedStringValue = NSAttributedString(string: name, attributes: [
            .font: titleFont.font(.title1) as NSFont, .foregroundColor: NSColor(look.text), .kern: -0.012 * Look.Step.title1.points,
        ])
        title.toolTip = name
        lede.attributedStringValue = NSAttributedString(string: said, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ])
        lede.isHidden = narrow || said.isEmpty
    }

    /// `views` down the column, each spanning it.
    func show(_ views: [NSView]) {
        NSLayoutConstraint.deactivate(spans)
        column.setViews(views, in: .top)
        spans = views.map { $0.widthAnchor.constraint(equalTo: column.widthAnchor) }
        NSLayoutConstraint.activate(spans)
    }

    /// The foot drawn again: the count at the leading edge, `keys` at the trailing edge, each a
    /// keycap and its words in the foot's mono.
    func foot(_ keys: [(key: String, words: String)], look: Look, mono: LabelFont) {
        foot.subviews.forEach { $0.removeFromSuperview() }
        let shown = NSStackView(views: keys.flatMap { key, words in
            [Keycap(key, look: look, mono: mono), NSTextField(labelWithAttributedString: Self.micro(words, look: look, mono: mono))]
        })
        shown.spacing = Self.buttonGap
        shown.setClippingResistancePriority(.defaultLow, for: .horizontal)
        for view in [count, shown] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            foot.addSubview(view)
        }
        NSLayoutConstraint.activate([
            count.leadingAnchor.constraint(equalTo: foot.leadingAnchor), count.centerYAnchor.constraint(equalTo: foot.centerYAnchor),
            count.trailingAnchor.constraint(lessThanOrEqualTo: shown.leadingAnchor, constant: -12),
            shown.trailingAnchor.constraint(equalTo: foot.trailingAnchor), shown.centerYAnchor.constraint(equalTo: foot.centerYAnchor),
        ])
    }

    /// Words in mono 11, tertiary: the foot's.
    static func micro(_ words: String, look: Look, mono: LabelFont) -> NSAttributedString {
        NSAttributedString(string: words, attributes: [.font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary)])
    }

    /// A gap `height` tall down the column.
    static func spacer(_ height: Double) -> NSView {
        let view = NSView()
        view.heightAnchor.constraint(equalToConstant: height).isActive = true
        return view
    }

    /// `content` in a list's frame: at `height`, or, with none, at its own height 52 pt in from the
    /// frame's top and bottom (an empty state in place of the list).
    static func framed(_ content: NSView, height: Double? = nil, look: Look) -> ListFrame {
        let frame = ListFrame(look: look)
        frame.height = height
        frame.addSubview(content)
        guard height == nil else { return frame }
        content.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: frame.leadingAnchor), content.trailingAnchor.constraint(equalTo: frame.trailingAnchor),
            content.topAnchor.constraint(equalTo: frame.topAnchor, constant: 52), content.bottomAnchor.constraint(equalTo: frame.bottomAnchor, constant: -52),
        ])
        return frame
    }

    /// The name and the line under it, taking the room the buttons leave (less the design's 20 pt).
    private func buildHead(_ shown: [NSView]) {
        words.orientation = .vertical
        words.setViews([title, lede], in: .top)
        words.alignment = .leading
        words.spacing = 6
        words.setClippingResistancePriority(.defaultLow, for: .horizontal)
        buttons.setViews(shown, in: .leading)
        buttons.spacing = Self.buttonGap
        buttons.setHuggingPriority(.required, for: .horizontal)
        for view in [words, buttons] {
            view.translatesAutoresizingMaskIntoConstraints = false
            head.addSubview(view)
        }
        let hug = head.heightAnchor.constraint(equalToConstant: 0)
        hug.priority = .fittingSizeCompression
        NSLayoutConstraint.activate([
            words.leadingAnchor.constraint(equalTo: head.leadingAnchor), words.topAnchor.constraint(equalTo: head.topAnchor),
            buttons.trailingAnchor.constraint(equalTo: head.trailingAnchor), buttons.topAnchor.constraint(equalTo: head.topAnchor),
            buttons.leadingAnchor.constraint(equalTo: words.trailingAnchor, constant: Self.headGap),
            head.bottomAnchor.constraint(greaterThanOrEqualTo: words.bottomAnchor), head.bottomAnchor.constraint(greaterThanOrEqualTo: buttons.bottomAnchor), hug,
        ])
    }
}
