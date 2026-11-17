import AppKit
import SailLook
import SailTerminalView

/// A card over the terminal saying where the session stands, with the thing to do about it, and
/// at most one other: the Sail look's surface and hairline, content corners at 6 pt, the action in
/// the accent, the other a plain button. The action answers Return while it shows.
@MainActor
public final class StatusBanner: NSView {
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

    /// What the banner says now, nil when hidden.
    public var shown: String? {
        isHidden ? nil : message.stringValue
    }

    /// The buttons it offers now, the action first; none while hidden.
    public var offers: [String] {
        isHidden ? [] : ([action, other] as [NSButton]).filter { !$0.isHidden }.map(\.title)
    }

    /// Presses the button titled `title` it offers, as a click does; false when it offers none so
    /// titled.
    public func press(_ title: String) -> Bool {
        guard offers.contains(title) else { return false }
        if action.title == title { handler() } else { otherHandler() }
        return true
    }

    /// Lines wrap at the message's own width, so its right margin is its left.
    override public func layout() {
        super.layout()
        guard message.preferredMaxLayoutWidth != message.frame.width else { return }
        message.preferredMaxLayoutWidth = message.frame.width
        super.layout()
    }

    @objc private func act() {
        handler()
    }

    @objc private func actOther() {
        otherHandler()
    }
}
