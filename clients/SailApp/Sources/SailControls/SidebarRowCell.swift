import AppKit
import SailLook

/// A project's tile (`TilePaint`): its lower-case initial in mono in an outlined well, 20 pt square;
/// the chosen project's in full ink.
@MainActor
public final class Tile: NSView {
    public var look: Look {
        didSet { needsDisplay = true }
    }

    private let name: String
    private let mono: LabelFont
    /// The chosen project's: outlined and lettered in full ink.
    package var chosen = false {
        didSet { if chosen != oldValue { needsDisplay = true } }
    }

    public init(_ name: String, look: Look, mono: LabelFont) {
        self.name = name
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
        let initial = Draw.text(String(name.prefix(1)).lowercased(), mono.font(points, .semibold) as NSFont, paint.ink)
        let top = (TilePaint.side - points) / 2
        Draw.line(initial, x: (TilePaint.side - Double(initial.size().width)) / 2, baseline: TextLine.baseline(top: top, points: points, lineHeight: points))
    }
}

/// A sidebar row's content, as the left panel draws a project: the disclosure's 12 pt slot, the
/// tile, the name in medium ink (the secondary ink while what the panel shows is stale, as the
/// design's stale frame draws it) and, when the template is not ready, its small pill at the
/// trailing edge, 7 pt apart.
@MainActor
public final class SidebarRowCell: NSView {
    public var look: Look {
        didSet { repaint() }
    }

    /// The name in the secondary ink: what the panel shows failed to refresh.
    public var stale = false {
        didSet { if stale != oldValue { repaint() } }
    }

    package let tile: Tile
    package let label = NSTextField(labelWithString: "")
    let pill: Pill?
    private let name: String

    public init(_ name: String, template: PillState?, look: Look, mono: LabelFont) {
        self.name = name
        self.look = look
        tile = Tile(name, look: look, mono: mono)
        pill = template.map { Pill($0, size: .small, look: look, mono: mono) }
        super.init(frame: .zero)
        label.lineBreakMode = .byTruncatingTail
        label.maximumNumberOfLines = 1
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let views: [NSView] = [tile, label] + [pill].compactMap { $0 }
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        var constraints = [
            tile.leadingAnchor.constraint(equalTo: leadingAnchor, constant: RowStyle.disclosure + RowStyle.gap), tile.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.leadingAnchor.constraint(equalTo: tile.trailingAnchor, constant: RowStyle.gap), label.centerYAnchor.constraint(equalTo: centerYAnchor),
            heightAnchor.constraint(equalToConstant: TilePaint.side),
        ]
        if let pill {
            constraints += [
                pill.leadingAnchor.constraint(greaterThanOrEqualTo: label.trailingAnchor, constant: RowStyle.gap), pill.trailingAnchor.constraint(equalTo: trailingAnchor),
                pill.centerYAnchor.constraint(equalTo: centerYAnchor),
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

    /// The name at full weight: the project is chosen, or under the pointer.
    private var strong = false

    private func repaint() {
        let weight: NSFont.Weight = strong ? .semibold : .medium
        label.attributedStringValue = Draw.text(name, .systemFont(ofSize: Look.Step.callout.points, weight: weight), stale ? look.textSecondary : look.text).cut(.byTruncatingTail)
        tile.look = look
        pill?.look = look
    }
}

extension SidebarRowCell: MarkedRow {
    package func mark(chosen: Bool, strong: Bool) {
        tile.chosen = chosen
        guard strong != self.strong else { return }
        self.strong = strong
        repaint()
    }
}

extension Tile: Marked {
    package var drawnMarks: [Mark] {
        let paint = TilePaint(look: look, chosen: chosen)
        return look.marks(fill: paint.fill, area: TilePaint.side * TilePaint.side) + look.marks(line: paint.ink) + look.marks(line: paint.ring)
    }
}
