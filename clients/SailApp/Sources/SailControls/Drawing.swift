import AppKit
import SailLook

/// What every control draws the same way: a box in its corners, filled, with a 1 pt edge laid on
/// pixel boundaries, and the focus halo around it.
enum Draw {
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

    /// `text` set in `font` and `ink`, letter-spaced `tracking` ems, upper-cased when `upper`.
    static func text(_ text: String, _ font: NSFont, _ ink: Tint, tracking: Double = 0, upper: Bool = false) -> NSAttributedString {
        NSAttributedString(
            string: upper ? text.uppercased() : text,
            attributes: [.font: font, .foregroundColor: NSColor(ink), .kern: tracking * Double(font.pointSize)]
        )
    }

    /// An SF Symbol at `points` in one ink.
    static func symbol(_ name: String, points: Double, ink: Tint) -> NSImage? {
        let configuration = NSImage.SymbolConfiguration(pointSize: points, weight: .regular)
            .applying(NSImage.SymbolConfiguration(paletteColors: [NSColor(ink)]))
        return NSImage(systemSymbolName: name, accessibilityDescription: nil)?.withSymbolConfiguration(configuration)
    }

    /// `size` centred on `point`, its edges on whole points.
    static func centred(_ size: NSSize, on point: NSPoint) -> NSRect {
        NSRect(x: (point.x - size.width / 2).rounded(), y: (point.y - size.height / 2).rounded(), width: size.width, height: size.height)
    }
}
