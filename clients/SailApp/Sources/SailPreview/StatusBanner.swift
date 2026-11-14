import AppKit
import SailLook
import SailTerminalView

/// A card over the terminal saying where the session stands, with the one thing to do about it:
/// Mast's surface and hairline, content corners at 6 pt, the action in the accent with dark text.
/// Its button answers Return while it shows.
@MainActor
final class StatusBanner: NSView {
    private let message = NSTextField(wrappingLabelWithString: "")
    private let action = NSButton(title: "", target: nil, action: nil)
    private var handler: () -> Void = {}
    private let look: Look

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor(look.surface).cgColor
        layer?.borderColor = NSColor(look.border).cgColor
        layer?.borderWidth = 1
        layer?.cornerRadius = Look.contentRadius
        message.font = .systemFont(ofSize: 13)
        message.textColor = NSColor(look.foreground)
        action.bezelStyle = .push
        action.bezelColor = NSColor(look.accent)
        action.keyEquivalent = "\r"
        action.target = self
        action.action = #selector(act)
        let stack = NSStackView(views: [message, action])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            widthAnchor.constraint(lessThanOrEqualToConstant: 420),
        ])
        isHidden = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Shows `text`, and `button` running `handler` when there is something to do.
    func show(_ text: String, button: String? = nil, handler: @escaping () -> Void = {}) {
        message.stringValue = text
        self.handler = handler
        action.isHidden = button == nil
        action.attributedTitle = NSAttributedString(
            string: button ?? "", attributes: [.foregroundColor: NSColor(look.onAccent), .font: NSFont.systemFont(ofSize: 13, weight: .medium)]
        )
        isHidden = false
    }

    func hide() {
        isHidden = true
        action.isHidden = true
    }

    /// What the banner says now, nil when hidden: for the drive harness.
    var shown: String? {
        isHidden ? nil : message.stringValue
    }

    @objc private func act() {
        handler()
    }
}
