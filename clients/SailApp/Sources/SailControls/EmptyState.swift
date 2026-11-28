import AppKit
import SailLook

/// What an empty place says, centred in its frame (`EmptyTilePaint`): a 56 pt tile with an 8 pt grid
/// and a line icon, a Newsreader title, a body at most 400 pt wide, its actions, and an optional mono
/// line, after the actions (when it was last tried) or before them (who is asking). Its words are
/// labels, so VoiceOver reads the title, the body, then the action.
@MainActor
public final class EmptyState: NSView {
    static let column = 400.0, gap = 10.0, tileGap = 6.0, actionsGap = 8.0, iconSide = 24.0
    /// Each label runs this far past the column on each side: a text field's own inset.
    static let labelPadding = 2.0

    public var look: Look {
        didSet { repaint() }
    }

    public let actions: [SailButton]
    private let icon: LineIcon
    private let missing: Bool
    private let lineFirst: Bool
    private let titleFont: TitleFont
    private let mono: LabelFont
    let title: NSTextField
    let body: NSTextField
    let line: PartsLine?

    public init(
        icon: LineIcon, title: String, body: String, actions: [SailButton], line: String? = nil, lineFirst: Bool = false, missing: Bool = false, look: Look,
        titleFont: TitleFont, mono: LabelFont
    ) {
        self.icon = icon
        self.missing = missing
        self.lineFirst = lineFirst
        self.actions = actions
        self.look = look
        self.titleFont = titleFont
        self.mono = mono
        self.title = NSTextField(wrappingLabelWithString: title)
        self.body = NSTextField(wrappingLabelWithString: body)
        self.line = line.map(PartsLine.init)
        super.init(frame: .zero)
        clipsToBounds = false
        for label in [self.title, self.body] {
            label.isSelectable = false
            label.alignment = .center
            addSubview(label)
        }
        if let line = self.line { addSubview(line) }
        for action in actions {
            addSubview(action)
        }
        repaint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    private func repaint() {
        title.attributedStringValue = Self.centred(title.stringValue, titleFont.font(.title2) as NSFont, look.text, lineHeight: 1.2, tracking: -0.012)
        body.attributedStringValue = Self.centred(body.stringValue, .systemFont(ofSize: Look.Step.callout.points), look.textSecondary, lineHeight: 1.5)
        line?.attributes = Self.centred(" ", mono.font(Look.Step.micro.points, .regular) as NSFont, look.textTertiary, lineHeight: 1.4).attributes(at: 0, effectiveRange: nil)
        actions.forEach { $0.look = look }
        needsDisplay = true
    }

    static func centred(_ text: String, _ font: NSFont, _ ink: Tint, lineHeight: Double, tracking: Double = 0) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.alignment = .center
        style.minimumLineHeight = (lineHeight * Double(font.pointSize)).rounded()
        style.maximumLineHeight = style.minimumLineHeight
        return NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style, .kern: tracking * Double(font.pointSize)])
    }

    /// The column's frames, top to bottom, for a frame `width` wide: the tile, each label, and the
    /// row of actions; the last value is the whole height.
    func frames(width: Double) -> (tile: NSRect, title: NSRect, body: NSRect, actions: NSRect, line: NSRect?, height: Double) {
        let column = min(width, Self.column), left = (width - column) / 2
        /// The label's own measure at the width it is given (the column and its padding), so its
        /// height holds every line it wraps to; the text's bounding box at the column wrapped one
        /// line fewer than the label draws (an admin's longer line lost its last word).
        func measured(_ label: NSTextField) -> Double {
            let bounds = NSRect(x: 0, y: 0, width: column + 2 * Self.labelPadding, height: .greatestFiniteMagnitude)
            return Double(ceil(label.cell?.cellSize(forBounds: bounds).height ?? 0))
        }
        let tileRect = NSRect(x: (width - EmptyTilePaint.size) / 2, y: 0, width: EmptyTilePaint.size, height: EmptyTilePaint.size)
        let titleTop = EmptyTilePaint.size + Self.tileGap + Self.gap
        let titleRect = NSRect(x: left, y: titleTop, width: column, height: measured(title))
        let bodyRect = NSRect(x: left, y: Double(titleRect.maxY) + Self.gap, width: column, height: measured(body))
        let sizes = actions.map { $0.intrinsicContentSize }
        let row = sizes.reduce(0) { $0 + Double($1.width) } + Self.actionsGap * Double(max(sizes.count - 1, 0))
        let tallest = sizes.map { Double($0.height) }.max() ?? 0
        guard let line else {
            let actionsRect = NSRect(x: (width - row) / 2, y: Double(bodyRect.maxY) + Self.gap + Self.actionsGap, width: row, height: tallest)
            return (tileRect, titleRect, bodyRect, actionsRect, nil, Double(actionsRect.maxY))
        }
        guard lineFirst else {
            let actionsRect = NSRect(x: (width - row) / 2, y: Double(bodyRect.maxY) + Self.gap + Self.actionsGap, width: row, height: tallest)
            let lineRect = NSRect(x: left, y: Double(actionsRect.maxY) + Self.gap, width: column, height: line.height(width: column))
            return (tileRect, titleRect, bodyRect, actionsRect, lineRect, Double(lineRect.maxY))
        }
        let lineRect = NSRect(x: left, y: Double(bodyRect.maxY) + Self.gap, width: column, height: line.height(width: column))
        let actionsRect = NSRect(x: (width - row) / 2, y: Double(lineRect.maxY) + Self.gap + Self.actionsGap, width: row, height: tallest)
        return (tileRect, titleRect, bodyRect, actionsRect, lineRect, Double(actionsRect.maxY))
    }

    override public var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: frames(width: bounds.width > 0 ? Double(bounds.width) : Self.column).height)
    }

    override public func setFrameSize(_ newSize: NSSize) {
        let widened = newSize.width != frame.width
        super.setFrameSize(newSize)
        needsLayout = true
        if widened { invalidateIntrinsicContentSize() }
    }

    override public func layout() {
        super.layout()
        let placed = frames(width: Double(bounds.width))
        let padding = Self.labelPadding
        title.frame = placed.title.insetBy(dx: -padding, dy: 0)
        body.frame = placed.body.insetBy(dx: -padding, dy: 0)
        if let line, let at = placed.line { line.frame = at }
        var x = Double(placed.actions.minX)
        for action in actions {
            let size = action.intrinsicContentSize
            let box = NSRect(x: x, y: Double(placed.actions.midY) - Double(size.height) / 2, width: Double(size.width), height: Double(size.height))
            action.frame = action.frame(forAlignmentRect: box)
            x += Double(size.width) + Self.actionsGap
        }
    }

    override public func draw(_: NSRect) {
        let tile = frames(width: Double(bounds.width)).tile
        let paint = EmptyTilePaint(missing: missing, look: look)
        let shape = NSBezierPath(roundedRect: tile, xRadius: Look.contentRadius, yRadius: Look.contentRadius)
        NSColor(paint.fill).setFill()
        shape.fill()
        NSGraphicsContext.saveGraphicsState()
        shape.addClip()
        NSColor(paint.grid).setFill()
        var at = Double(tile.minX) + 1
        while at < Double(tile.maxX) {
            NSBezierPath(rect: NSRect(x: at, y: Double(tile.minY), width: 1, height: Double(tile.height))).fill()
            at += EmptyTilePaint.grid
        }
        at = Double(tile.minY) + 1
        while at < Double(tile.maxY) {
            NSBezierPath(rect: NSRect(x: Double(tile.minX), y: at, width: Double(tile.width), height: 1)).fill()
            at += EmptyTilePaint.grid
        }
        NSGraphicsContext.restoreGraphicsState()
        Draw.box(tile, radius: Look.contentRadius, fill: nil, edge: paint.edge, dash: paint.dashed ? [3, 2] : [])
        Draw.icon(icon, in: tile.insetBy(dx: (tile.width - Self.iconSide) / 2, dy: (tile.height - Self.iconSide) / 2), ink: paint.ink)
    }
}

/// Words in parts, on one line joined by " · " while they fit; a part that does not fit starts a
/// new line with no dot, so no line starts or ends with one. Centred, each part whole.
@MainActor
package final class PartsLine: NSView {
    package let words: String
    private let parts: [String]
    /// Its font, ink and paragraph.
    var attributes: [NSAttributedString.Key: Any] = [:] {
        didSet { needsDisplay = true }
    }

    init(_ words: String) {
        self.words = words
        parts = words.components(separatedBy: " · ")
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        setAccessibilityLabel(words)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override package var isFlipped: Bool {
        true
    }

    package func width(of text: String) -> Double {
        Double(NSAttributedString(string: text, attributes: attributes).size().width)
    }

    /// Its lines at `width`.
    package func lines(width: Double) -> [String] {
        parts.reduce(into: []) { lines, part in
            if let last = lines.last, self.width(of: last + " · " + part) <= width { lines[lines.count - 1] = last + " · " + part } else { lines.append(part) }
        }
    }

    package func height(width: Double) -> Double {
        Double(drawn(width: width).boundingRect(with: NSSize(width: width, height: .greatestFiniteMagnitude), options: .usesLineFragmentOrigin).height).rounded(.up)
    }

    private func drawn(width: Double) -> NSAttributedString {
        NSAttributedString(string: lines(width: width).joined(separator: "\n"), attributes: attributes)
    }

    override package func draw(_: NSRect) {
        drawn(width: Double(bounds.width)).draw(with: bounds, options: .usesLineFragmentOrigin)
    }
}
