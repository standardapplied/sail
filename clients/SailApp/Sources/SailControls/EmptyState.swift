import AppKit
import SailLook

/// What an empty place says, centred in its frame (`EmptyTilePaint`): a 56 pt tile with an 8 pt grid
/// and a line icon, a Newsreader title, a body at most 400 pt wide, its actions, and an optional mono
/// line, after the actions (when it was last tried) or before them (who is asking). Its words are
/// labels, so VoiceOver reads the title, the body, then the action.
@MainActor
public final class EmptyState: NSView {
    static let column = 400.0, gap = 10.0, tileGap = 6.0, actionsGap = 8.0, iconSide = 24.0

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
    let line: NSTextField?

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
        self.line = line.map { NSTextField(labelWithString: $0) }
        super.init(frame: .zero)
        clipsToBounds = false
        for label in [self.title, self.body] + [self.line].compactMap({ $0 }) {
            label.alignment = .center
            addSubview(label)
        }
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
        line?.attributedStringValue = Self.centred(line?.stringValue ?? "", mono.font(Look.Step.micro.points, .regular) as NSFont, look.textTertiary, lineHeight: 1.4)
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
        func measured(_ label: NSTextField) -> Double {
            Double(ceil(label.attributedStringValue.boundingRect(with: NSSize(width: column, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin]).height))
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
            let lineRect = NSRect(x: left, y: Double(actionsRect.maxY) + Self.gap, width: column, height: measured(line))
            return (tileRect, titleRect, bodyRect, actionsRect, lineRect, Double(lineRect.maxY))
        }
        let lineRect = NSRect(x: left, y: Double(bodyRect.maxY) + Self.gap, width: column, height: measured(line))
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
        let padding = 2.0
        title.frame = placed.title.insetBy(dx: -padding, dy: 0)
        body.frame = placed.body.insetBy(dx: -padding, dy: 0)
        if let line, let at = placed.line { line.frame = at.insetBy(dx: -padding, dy: 0) }
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
