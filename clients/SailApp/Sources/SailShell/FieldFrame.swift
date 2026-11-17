import AppKit
import SailLook

/// Every text field of the app's panes, a secure one included, drawn in the look: its text 8 pt
/// in, on the surface, in content corners, ringed 1 pt in the strong separator, 2 pt in the accent
/// while it is edited (the system's ring off); the editor's selection and caret are the look's. The
/// frame draws the ring itself, so it shows wherever the view is drawn.
@MainActor
public final class FieldFrame: NSView {
    static let height = 28.0
    static let inset = 8.0

    public let field: NSTextField

    var look: Look {
        didSet { repaint() }
    }

    private var focusWatch: NSKeyValueObservation?

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
        field.currentEditor() == nil ? (1, NSColor(look.separatorStrong)) : (2, NSColor(look.accent))
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
            editor.selectedTextAttributes = [.backgroundColor: NSColor(look.fillSelected), .foregroundColor: NSColor(look.text)]
            editor.insertionPointColor = NSColor(look.accent)
        }
        needsDisplay = true
    }
}
