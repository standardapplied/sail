import AppKit
import SailLook

/// A project's tile (`TilePaint`): its lower-case initial in mono, or its icon, in an outlined well,
/// 20 pt square; the chosen project's in full ink.
@MainActor
public final class Tile: NSView {
    public var look: Look {
        didSet { needsDisplay = true }
    }

    private let name: String
    private let icon: LineIcon?
    private let mono: LabelFont
    /// The chosen project's: outlined and lettered in full ink.
    package var chosen = false {
        didSet { if chosen != oldValue { needsDisplay = true } }
    }

    public init(_ name: String, icon: LineIcon? = nil, look: Look, mono: LabelFont) {
        self.name = name
        self.icon = icon
        self.look = look
        self.mono = mono
        super.init(frame: NSRect(x: 0, y: 0, width: TilePaint.side, height: TilePaint.side))
        setAccessibilityElement(false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    override public var intrinsicContentSize: NSSize {
        NSSize(width: TilePaint.side, height: TilePaint.side)
    }

    override public func draw(_: NSRect) {
        let paint = TilePaint(look: look, chosen: chosen), points = TilePaint.points
        Draw.box(bounds, radius: TilePaint.radius, fill: paint.fill, edge: paint.ring)
        if let icon {
            let inset = (TilePaint.side - TilePaint.icon) / 2
            return Draw.icon(icon, in: NSRect(x: inset, y: inset, width: TilePaint.icon, height: TilePaint.icon), ink: paint.ink)
        }
        let initial = Draw.text(String(name.prefix(1)).lowercased(), mono.font(points, .semibold) as NSFont, paint.ink)
        let top = (TilePaint.side - points) / 2
        Draw.line(initial, x: (TilePaint.side - Double(initial.size().width)) / 2, baseline: TextLine.baseline(top: top, points: points, lineHeight: points))
    }
}

/// A sidebar row's content, as the left panel draws it, `indent` in from the row's inset (0 for a
/// project, 14 for what is under one): the disclosure's 12 pt slot, its chevron open or shut where
/// the row has one; then what leads it: a project's tile (its icon in place of the initial when it
/// has one), a workspace's 7 pt state square as its pill's dot, or nothing before quiet words; the
/// name: a project's in medium ink (the secondary ink while what the panel shows is stale, as the
/// design's stale frame draws it), a workspace's in regular ink, cut in its middle with its tooltip
/// whole, each semibold while chosen or under the pointer; quiet words in 12 pt tertiary ink; and at
/// the trailing edge, 7 pt apart, the template's small pill when not ready, a `tag`'s plain small pill
/// (This Mac's "Local"), or its + while it is chosen or under the pointer.
@MainActor
public final class SidebarRowCell: NSView {
    /// What stands between the disclosure's slot and the name.
    public enum Lead {
        case tile(LineIcon?)
        case state(PillState)
        /// Nothing: the row is quiet words.
        case words
    }

    public var look: Look {
        didSet { repaint() }
    }

    /// The name in the secondary ink: what the panel shows failed to refresh.
    public var stale = false {
        didSet { if stale != oldValue { repaint() } }
    }

    package let tile: Tile?
    package let square: PillDot?
    package let disclosure: Disclosure?
    package let label = NSTextField(labelWithString: "")
    let pill: Pill?
    /// The row's +, shown while it is chosen or under the pointer.
    package let plus: SailButton?
    private let name: String
    private let lead: Lead
    package let nameTip: Tooltip?
    private let onPlus: @MainActor () -> Void

    /// A row of `name`, `lead` before it; `disclosure` its chevron, open or shut, nil for none, which
    /// `onDisclose` presses (nil: a press there chooses the row); `plus` the label of its + (nil for
    /// none), which runs `onPlus`.
    public init(
        _ name: String, lead: Lead = .tile(nil), indent: Double = 0, disclosure open: Bool? = nil, template: PillState? = nil, tag: String? = nil, plus: String? = nil,
        look: Look, mono: LabelFont, onDisclose: (@MainActor () -> Void)? = nil, onPlus: @escaping @MainActor () -> Void = {}
    ) {
        self.name = name
        self.lead = lead
        self.look = look
        self.onPlus = onPlus
        if case let .tile(icon) = lead { tile = Tile(name, icon: icon, look: look, mono: mono) } else { tile = nil }
        if case let .state(state) = lead {
            let square = PillDot()
            square.paint(state, look: look)
            self.square = square
        } else {
            square = nil
        }
        disclosure = open.map { Disclosure(open: $0, look: look, onPress: onDisclose) }
        pill = template.map { Pill($0, size: .small, look: look, mono: mono) } ?? tag.map { Pill(plain: $0, size: .small, look: look, mono: mono) }
        self.plus = plus.map { SailButton(icon: .plus, label: $0, size: .row, look: look, target: nil, action: nil) }
        nameTip = lead.cutInTheMiddle ? Tooltip(name, look: look, mono: nil) : nil
        super.init(frame: .zero)
        self.plus?.target = self
        self.plus?.action = #selector(pressPlus(_:))
        self.plus?.isHidden = true
        label.lineBreakMode = lead.cutInTheMiddle ? .byTruncatingMiddle : .byTruncatingTail
        label.maximumNumberOfLines = 1
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        if let nameTip {
            nameTip.onlyWhenCut = true
            nameTip.attach(to: label, saying: name)
        }
        let leading: NSView? = tile.map { $0 as NSView } ?? square
        let end: NSView? = pill.map { $0 as NSView } ?? self.plus
        let views: [NSView] = [disclosure, leading, label, end].compactMap { $0 }
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        let start = indent + RowStyle.disclosure + RowStyle.gap
        var constraints = [label.centerYAnchor.constraint(equalTo: centerYAnchor), heightAnchor.constraint(equalToConstant: TilePaint.side)]
        if let disclosure {
            constraints += [
                disclosure.leadingAnchor.constraint(equalTo: leadingAnchor, constant: indent), disclosure.widthAnchor.constraint(equalToConstant: RowStyle.disclosure),
                disclosure.topAnchor.constraint(equalTo: topAnchor), disclosure.bottomAnchor.constraint(equalTo: bottomAnchor),
            ]
        }
        if let leading {
            let side = leading === square ? Self.square : TilePaint.side
            constraints += [
                leading.leadingAnchor.constraint(equalTo: leadingAnchor, constant: start), leading.centerYAnchor.constraint(equalTo: centerYAnchor),
                leading.widthAnchor.constraint(equalToConstant: side), leading.heightAnchor.constraint(equalToConstant: side),
                label.leadingAnchor.constraint(equalTo: leading.trailingAnchor, constant: RowStyle.gap),
            ]
        } else {
            constraints.append(label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: start))
        }
        if let end {
            constraints += [
                end.leadingAnchor.constraint(greaterThanOrEqualTo: label.trailingAnchor, constant: RowStyle.gap), end.trailingAnchor.constraint(equalTo: trailingAnchor),
                end.centerYAnchor.constraint(equalTo: centerYAnchor),
            ]
        } else {
            constraints.append(label.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor))
        }
        NSLayoutConstraint.activate(constraints)
        repaint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// A workspace's state square, as the design's `.wsq`.
    static let square = 7.0

    /// The name at full weight: the row is chosen, or under the pointer.
    private var strong = false

    @objc private func pressPlus(_: Any?) {
        onPlus()
    }

    private func repaint() {
        let words: NSAttributedString
        switch lead {
        case .tile:
            words = Draw.text(name, .systemFont(ofSize: Look.Step.callout.points, weight: strong ? .semibold : .medium), stale ? look.textSecondary : look.text).cut(.byTruncatingTail)
        case .state:
            words = Draw.text(name, .systemFont(ofSize: Look.Step.callout.points, weight: strong ? .semibold : .regular), look.text).cut(.byTruncatingMiddle)
        case .words:
            words = Draw.text(name, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary).cut(.byTruncatingTail)
        }
        label.attributedStringValue = words
        tile?.look = look
        pill?.look = look
        plus?.look = look
        disclosure?.look = look
        if case let .state(state) = lead { square?.paint(state, look: look) }
    }
}

extension SidebarRowCell.Lead {
    /// A workspace's name yields in its middle, as a host does: its two ends tell workspaces apart.
    var cutInTheMiddle: Bool {
        if case .state = self { true } else { false }
    }
}

extension SidebarRowCell: MarkedRow {
    package func mark(chosen: Bool, strong: Bool) {
        tile?.chosen = chosen
        plus?.isHidden = !strong
        guard strong != self.strong else { return }
        self.strong = strong
        repaint()
    }
}

/// A sidebar row's disclosure in its 12 pt slot, as the files tree draws its own: an 11 pt chevron in
/// the tertiary ink, down while open, pointing along the line while shut (mirrored right to left). A
/// press on it runs `onPress`, which opens or shuts the row; with none, the press chooses the row as
/// one beside it does.
@MainActor
package final class Disclosure: NSView {
    static let chevron = 11.0

    package var open: Bool {
        didSet { needsDisplay = true }
    }

    var look: Look {
        didSet { needsDisplay = true }
    }

    private let onPress: (@MainActor () -> Void)?

    init(open: Bool, look: Look, onPress: (@MainActor () -> Void)?) {
        self.open = open
        self.look = look
        self.onPress = onPress
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override package var isFlipped: Bool {
        true
    }

    override package func draw(_: NSRect) {
        let rect = NSRect(x: (Double(bounds.width) - Self.chevron) / 2, y: (Double(bounds.height) - Self.chevron) / 2, width: Self.chevron, height: Self.chevron)
        guard !open, userInterfaceLayoutDirection == .rightToLeft else { return Draw.icon(open ? .down : .right, in: rect, ink: look.textTertiary) }
        let context = NSGraphicsContext.current?.cgContext
        context?.saveGState()
        context?.translateBy(x: rect.midX * 2, y: 0)
        context?.scaleBy(x: -1, y: 1)
        Draw.icon(.right, in: rect, ink: look.textTertiary)
        context?.restoreGState()
    }

    override package func mouseDown(with event: NSEvent) {
        guard let onPress else { return super.mouseDown(with: event) }
        onPress()
    }
}

extension Tile: Marked {
    package var drawnMarks: [Mark] {
        let paint = TilePaint(look: look, chosen: chosen)
        return look.marks(fill: paint.fill, area: TilePaint.side * TilePaint.side) + look.marks(line: paint.ink) + look.marks(line: paint.ring)
    }
}
