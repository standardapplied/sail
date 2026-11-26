import AppKit
import SailLook

/// A control's watch on its window becoming and ceasing to be key, moved with the control from window
/// to window so it never watches one it has left.
@MainActor
enum KeyWatch {
    static let names = [NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification]

    static func move(_ watcher: NSObject, from old: NSWindow?, to new: NSWindow?, calling selector: Selector) {
        for name in names {
            if let old { NotificationCenter.default.removeObserver(watcher, name: name, object: old) }
            if let new { NotificationCenter.default.addObserver(watcher, selector: selector, name: name, object: new) }
        }
    }
}

/// Who holds the keys: the one answer every key hint asks, as a hint is on screen only while its key
/// does what it says. A view holds them while it is the first responder of a window that is key; a
/// sheet over the window, or another window, taking key status takes them from it.
@MainActor
public enum KeyHolder {
    /// `window` is key: its first responder holds the keys, and a key equivalent reaches any of its views.
    public static func isKey(_ window: NSWindow?) -> Bool {
        window?.isKeyWindow == true
    }

    /// `view` holds the keys: it is the first responder of its window, which is key.
    public static func holds(_ view: NSView) -> Bool {
        guard let window = view.window, isKey(window) else { return false }
        return of(window.firstResponder) === view
    }

    /// Who holds the keys through `responder`: a field being edited, not the window's field editor,
    /// which forgets the field once its editing ends.
    public static func of(_ responder: NSResponder?) -> NSResponder? {
        guard let editor = responder as? NSText, editor.isFieldEditor else { return responder }
        return editor.delegate as? NSResponder
    }
}

/// A watch on what holds a window's keys (``KeyHolder``): it tells whenever the window becomes or
/// ceases to be key, or its first responder changes (once AppKit has recorded it), watching only
/// the window last given it.
@MainActor
public final class HolderWatch: NSObject {
    private let changed: @MainActor () -> Void
    private weak var window: NSWindow?
    private var responder: NSKeyValueObservation?

    public init(_ changed: @escaping @MainActor () -> Void) {
        self.changed = changed
    }

    /// Watches `window` from now on, and no longer the one before.
    public func watch(_ window: NSWindow?) {
        guard window !== self.window || responder == nil else { return }
        KeyWatch.move(self, from: self.window, to: window, calling: #selector(told))
        self.window = window
        responder = window?.observe(\.firstResponder) { [weak self] _, _ in
            MainActor.assumeIsolated { self?.changed() }
        }
    }

    @objc private func told() {
        changed()
    }
}

/// What every control draws the same way: a box in its corners, filled, with a 1 pt edge laid on
/// pixel boundaries, and the focus halo around it.
package enum Draw {
    static func box(_ box: NSRect, radius: Double, fill: Tint?, edge: Tint?, dash: [CGFloat] = []) {
        if let fill {
            NSColor(fill).setFill()
            NSBezierPath(roundedRect: box, xRadius: radius, yRadius: radius).fill()
        }
        if let edge { stroke(box.insetBy(dx: 0.5, dy: 0.5), radius: radius - 0.5, width: 1, ink: edge, dash: dash) }
    }

    /// The halo outside `box`: the gap, then the 3 pt ring, each following the box's corners.
    static func halo(_ halo: Halo, around box: NSRect, radius: Double) {
        if let gap = halo.gap, halo.gapWidth > 0 {
            let out = halo.gapWidth / 2
            stroke(box.insetBy(dx: -out, dy: -out), radius: radius + out, width: halo.gapWidth, ink: gap)
        }
        let out = halo.gapWidth + Halo.width / 2
        stroke(box.insetBy(dx: -out, dy: -out), radius: radius + out, width: Halo.width, ink: halo.ring)
    }

    static func stroke(_ rect: NSRect, radius: Double, width: Double, ink: Tint, dash: [CGFloat] = []) {
        let path = NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
        path.lineWidth = width
        if !dash.isEmpty { path.setLineDash(dash, count: dash.count, phase: 0) }
        NSColor(ink).setStroke()
        path.stroke()
    }

    /// `text` drawn with its baseline at `baseline` (a flipped view's points from the top).
    static func line(_ text: NSAttributedString, x: Double, baseline: Double) {
        let font = text.length > 0 ? text.attribute(.font, at: 0, effectiveRange: nil) as? NSFont : nil
        let scale = Double(NSGraphicsContext.current?.cgContext.ctm.a ?? 1)
        text.draw(at: NSPoint(x: x, y: TextLine.snapped(baseline, scale: max(scale, 1)) - Double(font?.ascender ?? 0)))
    }

    /// `text` set in `font` and `ink`, letter-spaced `tracking` ems, upper-cased when `upper`.
    static func text(_ text: String, _ font: NSFont, _ ink: Tint, tracking: Double = 0, upper: Bool = false) -> NSAttributedString {
        NSAttributedString(
            string: upper ? text.uppercased() : text,
            attributes: [.font: font, .foregroundColor: NSColor(ink), .kern: tracking * Double(font.pointSize)]
        )
    }

    /// One of the design's line icons filling `rect`, its strokes scaled with it as an SVG's are.
    package static func icon(_ icon: LineIcon, in rect: NSRect, ink: Tint) {
        let scale = Double(rect.width) / LineIcon.side, down = NSGraphicsContext.current?.isFlipped ?? true
        func at(_ point: LineIcon.Point) -> NSPoint {
            NSPoint(x: Double(rect.minX) + point.x * scale, y: down ? Double(rect.minY) + point.y * scale : Double(rect.maxY) - point.y * scale)
        }
        let strokes = NSBezierPath(), dots = NSBezierPath()
        for stroke in icon.strokes {
            switch stroke {
            case let .lines(points, closed):
                strokes.move(to: at(points[0]))
                for point in points.dropFirst() { strokes.line(to: at(point)) }
                if closed { strokes.close() }
            case let .circle(centre, radius):
                strokes.appendOval(in: NSRect(origin: at(LineIcon.Point(centre.x - radius, centre.y - (down ? radius : -radius))), size: NSSize(width: 2 * radius * scale, height: 2 * radius * scale)))
            case let .arc(centre, radius, from, to):
                strokes.appendArc(withCenter: at(centre), radius: radius * scale, startAngle: down ? from : -from, endAngle: down ? to : -to, clockwise: !down)
            case let .dot(centre, radius):
                dots.appendOval(in: NSRect(origin: at(LineIcon.Point(centre.x - radius, centre.y - (down ? radius : -radius))), size: NSSize(width: 2 * radius * scale, height: 2 * radius * scale)))
            }
        }
        NSColor(ink).set()
        strokes.lineWidth = LineIcon.width * scale
        strokes.lineCapStyle = .round
        strokes.lineJoinStyle = .round
        strokes.stroke()
        dots.fill()
    }
}

extension NSAttributedString {
    /// These words cut at `mode` with an ellipsis where they do not fit their one line. An
    /// attributed label draws with its words' own line breaking, whatever the field's
    /// `lineBreakMode`, so a cut is set here; any other paragraph style is kept.
    public func cut(_ mode: NSLineBreakMode) -> NSAttributedString {
        let cut = NSMutableAttributedString(attributedString: self)
        let style = ((length > 0 ? attribute(.paragraphStyle, at: 0, effectiveRange: nil) : nil) as? NSParagraphStyle)?.mutableCopy() as? NSMutableParagraphStyle ?? NSMutableParagraphStyle()
        style.lineBreakMode = mode
        cut.addAttribute(.paragraphStyle, value: style, range: NSRange(location: 0, length: cut.length))
        return cut
    }
}
