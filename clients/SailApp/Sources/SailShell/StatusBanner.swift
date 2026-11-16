import AppKit
import SailLook
import SailTerminalView

/// A card over the terminal saying where the session stands, with the one thing to do about it:
/// the Sail look's surface and hairline, content corners at 6 pt, the action in the accent.
/// Its button answers Return while it shows.
@MainActor
final class StatusBanner: NSView {
    private let message = NSTextField(wrappingLabelWithString: "")
    private let action: AccentButton
    private var handler: () -> Void = {}
    /// The look it is drawn in; a new one repaints it.
    var look: Look {
        didSet { paint() }
    }

    init(look: Look) {
        self.look = look
        action = AccentButton("", look: look, target: nil, action: #selector(StatusBanner.act))
        super.init(frame: .zero)
        wantsLayer = true
        layer?.borderWidth = 1
        layer?.cornerRadius = Look.contentRadius
        message.font = .systemFont(ofSize: Look.Step.callout.points)
        paint()
        action.keyEquivalent = "\r"
        action.target = self
        let stack = NSStackView(views: [message, action])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = Look.Space.m
        stack.edgeInsets = NSEdgeInsets(top: Look.Space.l, left: Look.Space.l, bottom: Look.Space.l, right: Look.Space.l)
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
        action.setTitle(button ?? "")
        isHidden = false
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.surface).cgColor
        layer?.borderColor = NSColor(look.separator).cgColor
        message.textColor = NSColor(look.text)
        action.look = look
    }

    func hide() {
        isHidden = true
        action.isHidden = true
    }

    /// What the banner says now, nil when hidden: for the drive harness.
    var shown: String? {
        isHidden ? nil : message.stringValue
    }

    func press() {
        handler()
    }

    @objc private func act() {
        handler()
    }
}
