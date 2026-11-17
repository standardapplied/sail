import AppKit
import SailLook
import SailTerminalView

/// A card over the terminal saying where the session stands, with the thing to do about it, and
/// at most one other: the Sail look's surface and hairline, content corners at 6 pt, the action in
/// the accent, the other a plain button. The action answers Return while it shows.
@MainActor
final class StatusBanner: NSView {
    private let message = NSTextField(wrappingLabelWithString: "")
    private let action: AccentButton
    private let other = NSButton(title: "", target: nil, action: nil)
    private var handler: () -> Void = {}
    private var otherHandler: () -> Void = {}
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
        other.target = self
        other.action = #selector(actOther)
        other.isHidden = true
        let buttons = NSStackView(views: [action, other])
        buttons.spacing = Look.Space.s
        buttons.detachesHiddenViews = true
        let stack = NSStackView(views: [message, buttons])
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

    /// Shows `text`, and `button` running `handler` when there is something to do, and `second`
    /// running `secondHandler` beside it when there is another.
    func show(
        _ text: String, button: String? = nil, handler: @escaping () -> Void = {}, second: String? = nil,
        secondHandler: @escaping () -> Void = {}
    ) {
        message.stringValue = text
        self.handler = handler
        otherHandler = secondHandler
        action.isHidden = button == nil
        action.setTitle(button ?? "")
        other.isHidden = second == nil
        other.title = second ?? ""
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
        other.isHidden = true
    }

    /// What the banner says now, nil when hidden: for the drive harness.
    var shown: String? {
        isHidden ? nil : message.stringValue
    }

    /// The button the banner offers now, nil when it offers none.
    var offered: String? {
        isHidden || action.isHidden ? nil : action.title
    }

    /// The other button the banner offers now, nil when it offers none.
    var offeredOther: String? {
        isHidden || other.isHidden ? nil : other.title
    }

    /// Presses the button titled `title` the banner offers, as a click does; false when it offers
    /// none so titled.
    func press(_ title: String) -> Bool {
        if offered == title { handler() } else if offeredOther == title { otherHandler() } else { return false }
        return true
    }

    @objc private func act() {
        handler()
    }

    @objc private func actOther() {
        otherHandler()
    }
}
