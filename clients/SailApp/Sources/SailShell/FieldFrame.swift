import AppKit
import SailLook

/// Every text field of the app's panes, a secure one included, drawn in the look: its text 8 pt
/// in, on the surface, in content corners, ringed 1 pt in the strong separator, 2 pt in the accent
/// while it is edited (the system's ring off), in the error's crimson while refused, as the
/// Workspaces create row's field is; the editor's selection and caret are the look's. The frame
/// draws the ring itself, so it shows wherever the view is drawn.
@MainActor
public final class FieldFrame: NSView {
    static let height = 28.0
    static let inset = 8.0

    public let field: NSTextField

    var look: Look {
        didSet { repaint() }
    }

    private var focusWatch: NSKeyValueObservation?

    /// Refused: what it holds was refused, until it is edited again or another action starts.
    var failed = false {
        didSet { if failed != oldValue { repaint() } }
    }

    init(_ field: NSTextField, look: Look) {
        self.field = field
        self.look = look
        super.init(frame: .zero)
        field.isBezeled = false
        field.isBordered = false
        field.drawsBackground = false
        field.focusRingType = .none
        field.translatesAutoresizingMaskIntoConstraints = false
        addSubview(field)
        NSLayoutConstraint.activate([
            heightAnchor.constraint(equalToConstant: Self.height),
            field.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Self.inset),
            field.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -Self.inset),
            field.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
        repaint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The ring drawn now: 2 pt in the accent while the field is edited, else 1 pt in the strong
    /// separator.
    var ring: (width: Double, colour: NSColor) {
        let editing = field.currentEditor() != nil
        if failed { return (editing ? 2 : 1, NSColor(look.error)) }
        return editing ? (2, NSColor(look.accent)) : (1, NSColor(look.separatorStrong))
    }

    override public func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        focusWatch = window?.observe(\.firstResponder) { [weak self] _, _ in
            MainActor.assumeIsolated { self?.repaint() }
        }
    }

    override public func draw(_: NSRect) {
        let (width, colour) = ring
        NSColor(look.surface).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: Look.contentRadius, yRadius: Look.contentRadius).fill()
        let edge = NSBezierPath(
            roundedRect: bounds.insetBy(dx: width / 2, dy: width / 2), xRadius: Look.contentRadius - width / 2, yRadius: Look.contentRadius - width / 2
        )
        edge.lineWidth = width
        colour.setStroke()
        edge.stroke()
    }

    private func repaint() {
        field.textColor = NSColor(look.text)
        if let editor = field.currentEditor() as? NSTextView {
            editor.selectedTextAttributes = [.backgroundColor: NSColor(look.textSelection), .foregroundColor: NSColor(look.text)]
            editor.insertionPointColor = NSColor(look.accent)
        }
        needsDisplay = true
    }
}

extension FieldFrame: Marked {
    /// Its focus ring and caret while it is edited; its crimson edge while refused.
    package var drawnMarks: [Mark] {
        let caret = field.currentEditor() == nil ? [] : look.marks(line: Tint(look.accent))
        if failed { return look.marks(line: Tint(look.error)) + caret }
        return field.currentEditor() == nil ? [] : look.marks(line: Tint(look.accent)) + caret
    }
}
