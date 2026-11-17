import AppKit
import SailLook
import SailTerminalView

/// The one prominent action: terracotta filled, its label `onAccent` in bold, content corners at
/// 6 pt. It draws its own fill, so the system accent never reaches it. The label is bold because
/// white on terracotta is 3.43:1, which only large text may be (bold at any size, HIG Accessibility).
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
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        setTitle(title)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    public func setTitle(_ title: String) {
        layer?.backgroundColor = NSColor(look.accent).cgColor
        attributedTitle = NSAttributedString(
            string: title, attributes: [.foregroundColor: NSColor(look.onAccent), .font: NSFont.systemFont(ofSize: Look.Step.body.points, weight: .bold)]
        )
        invalidateIntrinsicContentSize()
    }

    override public var intrinsicContentSize: NSSize {
        let label = attributedTitle.size()
        return NSSize(width: ceil(label.width) + 2 * Look.Space.l, height: ceil(label.height) + 2 * Look.Space.s)
    }

    override public var isEnabled: Bool {
        didSet { alphaValue = isEnabled ? 1 : 0.5 }
    }
}

extension NSColor {
    /// A token with its opacity.
    convenience init(_ tint: Tint) {
        self.init(srgbRed: Double(tint.rgb.red) / 255, green: Double(tint.rgb.green) / 255, blue: Double(tint.rgb.blue) / 255, alpha: tint.alpha)
    }
}
