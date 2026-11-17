import AppKit
import SailLook
import SailTerminalView

/// The one prominent action: terracotta filled, its label `onAccent` in bold, content corners at
/// 6 pt; disabled, the fill is the strong separator, a grey, and the label secondary, as macOS dims
/// a prominent button. It draws its own fill, so the system accent never reaches it. The label is
/// bold because white on terracotta is 3.43:1, which only large text may be (bold at any size, HIG
/// Accessibility).
@MainActor
public final class AccentButton: NSButton {
    /// The look it is drawn in; a new one repaints it.
    var look: Look {
        didSet { setTitle(title) }
    }

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
        isEnabled ? NSColor(look.accent) : NSColor(look.separatorStrong)
    }

    public func setTitle(_ title: String) {
        let ink = isEnabled ? NSColor(look.onAccent) : NSColor(look.textSecondary)
        attributedTitle = NSAttributedString(
            string: title, attributes: [.foregroundColor: ink, .font: NSFont.systemFont(ofSize: Look.Step.body.points, weight: .bold)]
        )
        invalidateIntrinsicContentSize()
        needsDisplay = true
    }

    override public func draw(_ dirtyRect: NSRect) {
        fill.setFill()
        NSBezierPath(roundedRect: bounds, xRadius: Look.contentRadius, yRadius: Look.contentRadius).fill()
        super.draw(dirtyRect)
    }

    override public var intrinsicContentSize: NSSize {
        let label = attributedTitle.size()
        return NSSize(width: ceil(label.width) + 2 * Look.Space.l, height: ceil(label.height) + 2 * Look.Space.s)
    }

    override public var isEnabled: Bool {
        didSet { setTitle(title) }
    }
}

extension NSColor {
    /// A token with its opacity.
    convenience init(_ tint: Tint) {
        self.init(srgbRed: Double(tint.rgb.red) / 255, green: Double(tint.rgb.green) / 255, blue: Double(tint.rgb.blue) / 255, alpha: tint.alpha)
    }
}
