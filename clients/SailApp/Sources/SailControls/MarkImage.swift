import AppKit
import SailLook

/// A line icon drawn into a bitmap there and then, on the main thread: the one way a mark becomes
/// an image. An image left holding a drawing handler is drawn when and where AppKit pleases, and a
/// main-actor handler called off the main thread traps, as the card's mark did.
@MainActor
package enum MarkImage {
    /// `icon` in `ink`, `side` points square, at `scale` pixels a point.
    package static func bitmap(_ icon: LineIcon, side: Double, ink: Tint, scale: Double) -> CGImage? {
        var box = NSRect(x: 0, y: 0, width: side, height: side)
        let image = NSImage(size: box.size, flipped: true) { rect in
            Draw.icon(icon, in: rect, ink: ink)
            return true
        }
        return image.cgImage(forProposedRect: &box, context: nil, hints: [.ctm: AffineTransform(scale: scale)])
    }

    /// The same as an image of `side` points, for a run of text: its bitmap already drawn.
    package static func image(_ icon: LineIcon, side: Double, ink: Tint, scale: Double) -> NSImage {
        let size = NSSize(width: side, height: side)
        return bitmap(icon, side: side, ink: ink, scale: scale).map { NSImage(cgImage: $0, size: size) } ?? NSImage(size: size)
    }
}
