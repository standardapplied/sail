import AppKit
import SailLook

/// A link among words, as the refused frame's "Show dock" is drawn (`f-refused`): the words in ink
/// at weight 500 with a 1 pt accent underline 3 pt below their baseline, no box, the pointing hand
/// over them; pressed, the words in the secondary ink. Where its line is short its words are cut at
/// their end first, so the words after it keep their room.
@MainActor
public final class LinkButton: NSButton {
    public var look: Look {
        didSet { paint() }
    }

    private let points: Double

    /// New words are painted as the link's own.
    override public var title: String {
        didSet { if !painting { paint() } }
    }

    private var painting = false

    /// The words are set to the layout's leading edge, where the underline starts.
    override public var userInterfaceLayoutDirection: NSUserInterfaceLayoutDirection {
        didSet { paint() }
    }

    public init(_ title: String, points: Double = Look.Step.footnote.points, look: Look, target: AnyObject?, action: Selector?) {
        self.look = look
        self.points = points
        super.init(frame: .zero)
        self.title = title
        self.target = target
        self.action = action
        isBordered = false
        setButtonType(.momentaryChange)
        cell?.lineBreakMode = .byTruncatingTail
        setContentCompressionResistancePriority(Self.cutFirst, for: .horizontal)
        paint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Below a label's default, so a line that runs short cuts the link before its neighbours.
    public static let cutFirst = NSLayoutConstraint.Priority(NSLayoutConstraint.Priority.defaultLow.rawValue - 1)

    private func paint() {
        painting = true
        defer { painting = false }
        let font = NSFont.systemFont(ofSize: points, weight: .medium)
        let cut = NSMutableParagraphStyle()
        cut.lineBreakMode = .byTruncatingTail
        cut.alignment = userInterfaceLayoutDirection == .rightToLeft ? .right : .left
        attributedTitle = NSAttributedString(string: title, attributes: [.font: font, .foregroundColor: NSColor(look.text), .paragraphStyle: cut])
        attributedAlternateTitle = NSAttributedString(string: title, attributes: [.font: font, .foregroundColor: NSColor(look.textSecondary), .paragraphStyle: cut])
        needsDisplay = true
    }

    /// The underline's ink: the accent marks where a person acts; the words stay ink.
    var underlineInk: Tint {
        Tint(look.accent)
    }

    /// The underline's rect: under the words drawn, 3 pt below their baseline, 1 pt thick.
    var underline: NSRect {
        guard let cell else { return .zero }
        let words = cell.titleRect(forBounds: bounds)
        let drawn = min(Double(attributedTitle.size().width), Double(words.width))
        let font = NSFont.systemFont(ofSize: points, weight: .medium)
        let baseline = Double(words.minY) + (Double(words.height) - Double(font.ascender - font.descender)) / 2 + Double(font.ascender)
        let x = userInterfaceLayoutDirection == .rightToLeft ? Double(words.maxX) - drawn : Double(words.minX)
        return NSRect(x: x, y: isFlipped ? baseline + 3 : Double(bounds.height) - baseline - 4, width: drawn, height: 1)
    }

    override public func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        NSColor(underlineInk).setFill()
        underline.fill()
    }

    override public func resetCursorRects() {
        addCursorRect(bounds, cursor: .pointingHand)
    }

    /// In a panel floating over the window, which never takes the keys, the first click acts.
    override public func acceptsFirstMouse(for _: NSEvent?) -> Bool {
        window is NSPanel
    }
}

extension LinkButton: Marked {
    package var drawnMarks: [Mark] {
        look.marks(line: underlineInk)
    }
}
