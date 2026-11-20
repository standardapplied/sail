import AppKit
import SailLook

/// A banner in the flow above what it is about (`BannerPaint`): the tone mixed into the surface in
/// content corners, a 16 pt mark, a semibold title, an optional line under it, and at most one
/// small secondary action at its trailing edge, padded 10, 10, 10 and 12 pt inside its 1 pt edge and
/// mirrored right to left. VoiceOver reads it as a group with its words, the action inside it.
@MainActor
public final class Banner: NSView {
    static let padding = (top: 10 + Look.hairline, trailing: 10 + Look.hairline, bottom: 10 + Look.hairline, leading: 12 + Look.hairline)
    static let mark = 16.0, gap = 10.0

    public var look: Look {
        didSet {
            action?.look = look
            needsDisplay = true
        }
    }

    public let tone: BannerTone
    public let action: SailButton?
    private let title: String
    private let line: String?

    public init(_ tone: BannerTone, title: String, line: String? = nil, action: SailButton? = nil, look: Look) {
        self.tone = tone
        self.title = title
        self.line = line
        self.action = action
        self.look = look
        super.init(frame: .zero)
        clipsToBounds = false
        if let action { addSubview(action) }
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
        setAccessibilityLabel(title)
        setAccessibilityHelp(line)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    private var rightToLeft: Bool {
        userInterfaceLayoutDirection == .rightToLeft
    }

    /// `rect` as it stands in the banner's reading direction.
    private func reading(_ rect: NSRect) -> NSRect {
        guard rightToLeft else { return rect }
        return NSRect(x: Double(bounds.width) - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }

    private var words: NSAttributedString {
        let words = NSMutableAttributedString(string: title, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points, weight: .semibold), .foregroundColor: NSColor(look.text),
            .paragraphStyle: Self.lines(1.4, Look.Step.callout.points, rightToLeft: rightToLeft),
        ])
        if let line {
            words.append(NSAttributedString(string: "\n" + line, attributes: [
                .font: NSFont.systemFont(ofSize: Look.Step.footnote.points), .foregroundColor: NSColor(look.textSecondary),
                .paragraphStyle: Self.lines(1.4, Look.Step.footnote.points, before: 2, rightToLeft: rightToLeft),
            ]))
        }
        return words
    }

    static func lines(_ height: Double, _ points: Double, before: Double = 0, rightToLeft: Bool) -> NSParagraphStyle {
        let style = NSMutableParagraphStyle()
        style.alignment = .natural
        style.baseWritingDirection = rightToLeft ? .rightToLeft : .leftToRight
        style.minimumLineHeight = (height * points).rounded()
        style.maximumLineHeight = style.minimumLineHeight
        style.paragraphSpacingBefore = before
        return style
    }

    /// The action's box, at the top of the trailing edge.
    private var actionBox: NSRect? {
        guard let action else { return nil }
        let size = action.intrinsicContentSize
        return NSRect(x: Double(bounds.width) - Self.padding.trailing - Double(size.width), y: Self.padding.top, width: Double(size.width), height: Double(size.height))
    }

    /// The words' rectangle for a banner `width` wide.
    func wordsRect(width: Double) -> NSRect {
        let leading = Self.padding.leading + Self.mark + Self.gap
        let room = max(width - leading - Self.padding.trailing - (action.map { Double($0.intrinsicContentSize.width) + Self.gap } ?? 0), 1)
        let height = Double(ceil(words.boundingRect(with: NSSize(width: room, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin]).height))
        return NSRect(x: leading, y: Self.padding.top, width: room, height: height)
    }

    override public var intrinsicContentSize: NSSize {
        let width = bounds.width > 0 ? Double(bounds.width) : 10000
        let content = max(Double(wordsRect(width: width).height), Self.mark + 1, Double(action?.intrinsicContentSize.height ?? 0))
        return NSSize(width: NSView.noIntrinsicMetric, height: Self.padding.top + content + Self.padding.bottom)
    }

    override public func setFrameSize(_ newSize: NSSize) {
        let widened = newSize.width != frame.width
        super.setFrameSize(newSize)
        needsLayout = true
        if widened { invalidateIntrinsicContentSize() }
    }

    override public func layout() {
        super.layout()
        if let action, let box = actionBox { action.frame = action.frame(forAlignmentRect: reading(box)) }
    }

    override public func draw(_: NSRect) {
        let paint = BannerPaint(tone, look: look)
        Draw.box(bounds, radius: Look.contentRadius, fill: Tint(paint.fill), edge: paint.edge)
        Draw.icon(tone.icon, in: reading(NSRect(x: Self.padding.leading, y: Self.padding.top + 1, width: Self.mark, height: Self.mark)), ink: paint.mark)
        words.draw(with: reading(wordsRect(width: Double(bounds.width))), options: [.usesLineFragmentOrigin])
    }
}

extension Banner: Marked {
    package var drawnMarks: [Mark] {
        let paint = BannerPaint(tone, look: look)
        return look.marks(fill: Tint(paint.fill), area: Double(bounds.width * bounds.height)) + look.marks(line: paint.mark)
    }
}
