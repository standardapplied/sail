import AppKit
import SailLook
import SailTerminalView

/// The one prominent action: terracotta filled, its label `onAccent` in bold, content corners at 6
/// pt; pressed, the fill is the accent's pressed shade; disabled, the fill is the strong separator,
/// a grey, and the label secondary, as macOS dims a prominent button. A label wider than the button
/// is cut short with an ellipsis, as the system's own is. It draws its own fill and its own label,
/// so the system accent never reaches it, and a change of look or of being enabled only redraws it:
/// its title, which sizes it, is set once, and nothing is measured or laid out again. The label is
/// bold because white on terracotta is 3.43:1, which only large text may be (bold at any size, HIG
/// Accessibility).
@MainActor
public final class AccentButton: NSButton {
    /// The look it is drawn in; a new one repaints it.
    var look: Look {
        didSet { needsDisplay = true }
    }

    static let font = NSFont.systemFont(ofSize: Look.Step.body.points, weight: .bold)

    public init(_ title: String, look: Look, target: AnyObject?, action: Selector) {
        self.look = look
        super.init(frame: .zero)
        self.target = target
        self.action = action
        isBordered = false
        setTitle(title)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// What it is filled with now.
    var fill: NSColor {
        guard isEnabled else { return NSColor(look.separatorStrong) }
        return isHighlighted ? NSColor(look.accentPressed) : NSColor(look.accent)
    }

    /// What its label is drawn in now.
    var ink: NSColor {
        isEnabled ? NSColor(look.onAccent) : NSColor(look.textSecondary)
    }

    public func setTitle(_ title: String) {
        attributedTitle = NSAttributedString(string: title, attributes: [.font: Self.font])
        invalidateIntrinsicContentSize()
        needsDisplay = true
    }

    override public func draw(_: NSRect) {
        fill.setFill()
        NSBezierPath(roundedRect: bounds, xRadius: Look.contentRadius, yRadius: Look.contentRadius).fill()
        let line = NSMutableParagraphStyle()
        line.lineBreakMode = .byTruncatingTail
        line.alignment = .center
        let label = NSAttributedString(string: title, attributes: [.foregroundColor: ink, .font: Self.font, .paragraphStyle: line])
        let height = ceil(label.size().height)
        let room = NSRect(x: Look.Space.s, y: (bounds.height - height) / 2, width: max(0, bounds.width - 2 * Look.Space.s), height: height)
        label.draw(with: room, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine])
    }

    override public var isHighlighted: Bool {
        didSet { needsDisplay = true }
    }

    override public var intrinsicContentSize: NSSize {
        let label = attributedTitle.size()
        return NSSize(width: ceil(label.width) + 2 * Look.Space.l, height: ceil(label.height) + 2 * Look.Space.s)
    }

    override public var isEnabled: Bool {
        didSet { needsDisplay = true }
    }
}
