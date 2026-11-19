import AppKit
import SailLook

/// A link among words, as the refused frame's "Show dock" is drawn (`f-refused`): the words in the
/// accent's text ink at weight 500, no box, the pointing hand over them; pressed, in the accent's
/// pressed ink. Where its line is short its words are cut at their end first, so the words after
/// it keep their room.
@MainActor
public final class LinkButton: NSButton {
    public var look: Look {
        didSet { paint() }
    }

    private let points: Double

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
        let font = NSFont.systemFont(ofSize: points, weight: .medium)
        let cut = NSMutableParagraphStyle()
        cut.lineBreakMode = .byTruncatingTail
        attributedTitle = NSAttributedString(string: title, attributes: [.font: font, .foregroundColor: NSColor(Tint(look.accentText)), .paragraphStyle: cut])
        attributedAlternateTitle = NSAttributedString(string: title, attributes: [.font: font, .foregroundColor: NSColor(Tint(look.accentPressed)), .paragraphStyle: cut])
    }

    override public func resetCursorRects() {
        addCursorRect(bounds, cursor: .pointingHand)
    }
}
