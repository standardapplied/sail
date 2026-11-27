import AppKit
import SailControls
import SailLook

/// How the app's own sheets (Store a Key, New project) lay themselves out, every frame set by
/// arithmetic: words set to the leading edge (or the trailing) in the look's inks, keeping their
/// own direction right to left; a view placed at its own height for a width; and a frame laid out
/// left to right turned about the sheet's middle right to left.
@MainActor
struct SheetLayout {
    let width: Double
    let direction: NSUserInterfaceLayoutDirection
    let look: Look
    let titleFont: TitleFont

    func titled(_ words: String) -> NSAttributedString {
        let style = paragraph()
        style.minimumLineHeight = (Look.Step.title3.points * 1.2).rounded()
        style.maximumLineHeight = style.minimumLineHeight
        return NSAttributedString(string: words, attributes: [
            .font: titleFont.font(.title3) as NSFont, .foregroundColor: NSColor(look.text), .paragraphStyle: style, .kern: -0.012 * Look.Step.title3.points,
        ])
    }

    func text(_ words: String, _ step: Look.Step, _ ink: Tint, trailing: Bool = false, wraps: Bool = false) -> NSAttributedString {
        NSAttributedString(string: words, attributes: [
            .font: NSFont.systemFont(ofSize: step.points), .foregroundColor: NSColor(ink), .paragraphStyle: paragraph(trailing: trailing, wraps: wraps),
        ])
    }

    /// Set to the leading edge, or the trailing; a line that does not wrap is cut with an ellipsis.
    func paragraph(trailing: Bool = false, wraps: Bool = true) -> NSMutableParagraphStyle {
        let style = NSMutableParagraphStyle()
        style.alignment = (direction == .rightToLeft) != trailing ? .right : .left
        style.baseWritingDirection = .natural
        style.lineBreakMode = wraps ? .byWordWrapping : .byTruncatingTail
        return style
    }

    /// `view` at `x`, `y`, `width` wide, at its own height for that width; where it ends. A field's
    /// frame holds the halo's room around its box.
    func place(_ view: NSView, x: Double, y: Double, width: Double) -> Double {
        let room = view is SailTextField ? Look.haloRoom : 0
        view.frame = mirrored(NSRect(x: x - room, y: y - room, width: width + 2 * room, height: 10))
        let height = (view as? NSTextField).map { Self.height($0, width) } ?? Double(ceil(view.intrinsicContentSize.height))
        view.frame = mirrored(NSRect(x: x - room, y: y - room, width: width + 2 * room, height: height + 2 * room))
        return y + height
    }

    /// A label's height as its cell draws it at `width`.
    static func height(_ label: NSTextField, _ width: Double) -> Double {
        Double(ceil(label.cell?.cellSize(forBounds: NSRect(x: 0, y: 0, width: width, height: 10000)).height ?? 0))
    }

    /// `rect`, laid out left to right, turned about the sheet's middle right to left.
    func mirrored(_ rect: NSRect) -> NSRect {
        guard direction == .rightToLeft else { return rect }
        return NSRect(x: width - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }
}

/// A sheet's ground and the hairline over its footer.
@MainActor
final class SheetGround: NSView {
    var look: Look {
        didSet { needsDisplay = true }
    }

    var footerTop = 0.0

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_: NSRect) {
        NSColor(Tint(look.surfacePop)).setFill()
        bounds.fill()
        NSColor(look.separator).setFill()
        NSRect(x: 0, y: footerTop, width: Double(bounds.width), height: Look.hairline).fill()
    }
}
