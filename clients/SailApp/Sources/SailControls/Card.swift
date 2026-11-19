import AppKit
import SailLook

/// A card over what it is about (a terminal), saying where things stand and what to do about it:
/// drawn as a banner is (`BannerPaint`, in content corners, its edge, its 16 pt mark), information on
/// the quiet surface and a warning or an error in its tone; its words in callout ink, a primary
/// action and at most one secondary beside it, the primary answering Return while it shows unless
/// Return belongs to what the card is over. At most 420 pt wide, padded as a banner, its mark
/// mirrored right to left.
@MainActor
public final class Card: NSView {
    static let width = 420.0
    private let message = NSTextField(wrappingLabelWithString: "")
    private let primary: SailButton
    private let secondary: SailButton
    /// The actions' row, taking no room when the card offers none.
    private let actions: NSStackView
    private var tone = BannerTone.info
    private var primaryHandler: () -> Void = {}
    private var secondaryHandler: () -> Void = {}

    public var look: Look {
        didSet { paint() }
    }

    public init(look: Look) {
        self.look = look
        primary = SailButton("", kind: .primary, look: look, target: nil, action: nil)
        secondary = SailButton("", kind: .secondary, look: look, target: nil, action: nil)
        actions = NSStackView(views: [primary, secondary])
        super.init(frame: .zero)
        message.font = .systemFont(ofSize: Look.Step.callout.points)
        primary.target = self
        primary.action = #selector(actPrimary)
        secondary.target = self
        secondary.action = #selector(actSecondary)
        actions.spacing = Look.Space.s
        actions.detachesHiddenViews = true
        let stack = NSStackView(views: [message, actions])
        stack.detachesHiddenViews = true
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = Look.Space.m
        let pad = Banner.padding
        stack.edgeInsets = NSEdgeInsets(top: pad.top, left: pad.leading + Banner.mark + Banner.gap, bottom: pad.bottom, right: pad.trailing)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            widthAnchor.constraint(lessThanOrEqualToConstant: Self.width),
            message.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -(pad.leading + Banner.mark + Banner.gap + pad.trailing)),
        ])
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
        paint()
        hide()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    /// Shows `text` in `tone`, with `button` running `handler` when there is something to do, and
    /// `second` running `secondHandler` beside it when there is another; `button` answers Return when
    /// `returns`.
    public func show(
        _ text: String, tone: BannerTone = .info, button: String? = nil, returns: Bool = true, handler: @escaping () -> Void = {},
        second: String? = nil, secondHandler: @escaping () -> Void = {}
    ) {
        primary.isDefault = returns
        message.stringValue = text
        self.tone = tone
        primaryHandler = handler
        secondaryHandler = secondHandler
        primary.isHidden = button == nil
        primary.title = button ?? ""
        secondary.isHidden = second == nil
        secondary.title = second ?? ""
        actions.isHidden = button == nil && second == nil
        setAccessibilityLabel(text)
        isHidden = false
        needsDisplay = true
    }

    public func hide() {
        isHidden = true
        primary.isHidden = true
        secondary.isHidden = true
    }

    /// What the card says now, nil when hidden.
    public var shown: String? {
        isHidden ? nil : message.stringValue
    }

    /// Whether its primary answers Return now.
    public var returns: Bool {
        !isHidden && primary.isDefault
    }

    /// The actions it offers now, the primary first; none while hidden.
    public var offers: [String] {
        isHidden ? [] : [primary, secondary].filter { !$0.isHidden }.map(\.title)
    }

    /// Presses the action titled `title` it offers, as a click does; false when it offers none so
    /// titled.
    public func press(_ title: String) -> Bool {
        guard offers.contains(title) else { return false }
        if primary.title == title { primaryHandler() } else { secondaryHandler() }
        return true
    }

    private func paint() {
        message.textColor = NSColor(look.text)
        primary.look = look
        secondary.look = look
        needsDisplay = true
    }

    /// The mark's box, at the top of the leading edge.
    var markBox: NSRect {
        let box = NSRect(x: Banner.padding.leading, y: Banner.padding.top + 1, width: Banner.mark, height: Banner.mark)
        guard userInterfaceLayoutDirection == .rightToLeft else { return box }
        return NSRect(x: Double(bounds.width) - Double(box.maxX), y: Double(box.minY), width: Double(box.width), height: Double(box.height))
    }

    override public func draw(_: NSRect) {
        let paint = tone == .info ? BannerPaint(quiet: look) : BannerPaint(tone, look: look)
        Draw.box(bounds, radius: Look.contentRadius, fill: Tint(paint.fill), edge: paint.edge)
        Draw.icon(tone.icon, in: markBox, ink: paint.mark)
    }

    @objc private func actPrimary() {
        primaryHandler()
    }

    @objc private func actSecondary() {
        secondaryHandler()
    }
}
