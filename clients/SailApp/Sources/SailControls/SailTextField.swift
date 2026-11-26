import AppKit
import SailLook

/// A text field with an optional eyebrow label above it and one line under it, help or an error
/// with its mark (`FieldPaint`). The field is a plain `NSTextField` (or `NSSecureTextField`) with
/// its own box, bezel and ring off, inside a box this view draws, with the halo around the box;
/// the frame keeps `Look.haloRoom` on every side (the alignment rect is the content), so nothing
/// of its own clips the halo. The caret and the selection are the look's. VoiceOver reads the
/// eyebrow as the field's label and the help or the error as its help, and hears a new error.
@MainActor
public final class SailTextField: NSView {
    public let field: NSTextField
    public let size: FieldSize

    public var look: Look {
        didSet { repaint() }
    }

    public var help: String? {
        didSet { relaid() }
    }

    /// A refusal shown under the field in place of the help, announced when it appears.
    public var error: String? {
        didSet {
            if let error, error != oldValue {
                NSAccessibility.post(
                    element: field, notification: .announcementRequested,
                    userInfo: [.announcement: error, .priority: NSAccessibilityPriorityLevel.high.rawValue]
                )
            }
            relaid()
        }
    }

    /// The box's widest, when the line under it may run wider than the box.
    public var boxWidth: Double? {
        didSet { needsLayout = true }
    }

    public var isEnabled: Bool {
        get { field.isEnabled }
        set {
            field.isEnabled = newValue
            repaint()
        }
    }

    private let label: String?
    private let placeholder: String
    private let mono: LabelFont
    private(set) var hovered = false
    private var hoverArea: NSTrackingArea?
    private lazy var keysWatch = HolderWatch { [weak self] in self?.keysChanged() }

    public init(label: String?, placeholder: String, help: String? = nil, size: FieldSize = .regular, secure: Bool = false, look: Look, mono: LabelFont) {
        field = secure ? NSSecureTextField() : NSTextField()
        self.label = label
        self.placeholder = placeholder
        self.help = help
        self.size = size
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        clipsToBounds = false
        field.isBezeled = false
        field.isBordered = false
        field.drawsBackground = false
        field.focusRingType = .none
        field.font = .systemFont(ofSize: size.text)
        field.cell?.usesSingleLineMode = true
        field.cell?.isScrollable = true
        field.setAccessibilityLabel(label ?? placeholder)
        field.setAccessibilityHelp(help)
        addSubview(field)
        repaint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    var state: FieldState {
        FieldState(enabled: isEnabled, hovered: hovered, editing: field.currentEditor() != nil && window?.isKeyWindow == true, failed: error != nil)
    }

    var paint: FieldPaint {
        FieldPaint(size, state, look: look)
    }

    override public var alignmentRectInsets: NSEdgeInsets {
        NSEdgeInsets(top: Look.haloRoom, left: Look.haloRoom, bottom: Look.haloRoom, right: Look.haloRoom)
    }

    /// The eyebrow's line, 10 pt as Mast sets it (line height 1), the gap under it, then the box.
    package var box: NSRect {
        let top = Look.haloRoom + (label == nil ? 0 : Look.Step.caption.points + FieldSize.gap)
        let room = max(Double(bounds.width) - 2 * Look.haloRoom, 0), width = min(boxWidth ?? room, room)
        return NSRect(x: rightToLeft ? Look.haloRoom + room - width : Look.haloRoom, y: top, width: width, height: size.height)
    }

    /// The text cell's own inset before its first glyph, taken off so the text starts at the inset.
    static let textPadding = 2.0

    private var eyebrow: NSAttributedString? {
        label.map { Draw.text($0, mono.font(Look.Step.caption.points) as NSFont, paint.eyebrow, tracking: Look.Tracking.eyebrow, upper: true) }
    }

    private var lineText: NSAttributedString? {
        guard let said = error ?? help else { return nil }
        let style = NSMutableParagraphStyle(), font = NSFont.systemFont(ofSize: Look.Step.footnote.points)
        style.alignment = rightToLeft ? .right : .left
        style.minimumLineHeight = Look.Step.footnote.points * 1.4
        style.maximumLineHeight = style.minimumLineHeight
        return NSAttributedString(string: said, attributes: [
            .font: font, .paragraphStyle: style, .foregroundColor: NSColor(error == nil ? look.textTertiary : Tint(look.error)),
            .baselineOffset: TextLine.centring(lineHeight: Double(style.minimumLineHeight), ascent: Double(font.ascender), descent: -Double(font.descender)),
        ])
    }

    private var rightToLeft: Bool {
        userInterfaceLayoutDirection == .rightToLeft
    }

    /// Where the line under the box is drawn, after the error's mark (before it right to left),
    /// wrapped to the width.
    func lineRect(width: Double) -> NSRect? {
        guard let lineText else { return nil }
        let indent: Double = error == nil ? 0 : FieldSize.mark + FieldSize.gap
        let room = max(width - 2 * Look.haloRoom - indent, 1)
        let height = Double(ceil(lineText.boundingRect(with: NSSize(width: room, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin]).height))
        return NSRect(x: Look.haloRoom + (rightToLeft ? 0 : indent), y: Double(box.maxY) + FieldSize.gap, width: room, height: height)
    }

    override public var intrinsicContentSize: NSSize {
        let width: Double = bounds.width > 0 ? Double(bounds.width) : 10000
        let bottom = Double(lineRect(width: width)?.maxY ?? box.maxY)
        return NSSize(width: NSView.noIntrinsicMetric, height: bottom - Look.haloRoom)
    }

    override public func setFrameSize(_ newSize: NSSize) {
        let widened = newSize.width != frame.width
        super.setFrameSize(newSize)
        needsLayout = true
        if widened { invalidateIntrinsicContentSize() }
    }

    override public func layout() {
        super.layout()
        let box = self.box, height = Double(ceil(field.intrinsicContentSize.height)), padding = Self.textPadding
        field.frame = NSRect(
            x: Double(box.minX) + size.textInset - padding, y: (Double(box.midY) - height / 2).rounded(), width: Double(box.width) - 2 * size.textInset + 2 * padding,
            height: height
        )
    }

    override public func draw(_: NSRect) {
        let paint = self.paint
        if let eyebrow {
            let x = rightToLeft ? Double(box.maxX - eyebrow.size().width) : Double(box.minX), points = Look.Step.caption.points
            Draw.line(eyebrow, x: x, baseline: TextLine.baseline(top: Look.haloRoom, points: points, lineHeight: points))
        }
        if let halo = paint.halo { Draw.halo(halo, around: box, radius: Look.contentRadius) }
        if let context = NSGraphicsContext.current?.cgContext {
            context.saveGState()
            context.setAlpha(paint.boxOpacity)
            Draw.box(box, radius: Look.contentRadius, fill: paint.fill, edge: paint.edge)
            context.restoreGState()
        }
        guard let lineText, let line = lineRect(width: Double(bounds.width)) else { return }
        if error != nil {
            let x = rightToLeft ? Double(box.maxX) - FieldSize.mark : Double(box.minX)
            Draw.icon(.error, in: NSRect(x: x, y: Double(line.minY) + 1, width: FieldSize.mark, height: FieldSize.mark), ink: Tint(look.error))
        }
        lineText.draw(with: line, options: [.usesLineFragmentOrigin])
    }

    private func relaid() {
        field.setAccessibilityHelp(error ?? help)
        invalidateIntrinsicContentSize()
        repaint()
    }

    private func repaint() {
        field.textColor = NSColor(look.text)
        field.alphaValue = paint.boxOpacity
        field.alignment = .natural
        let style = NSMutableParagraphStyle()
        style.alignment = rightToLeft ? .right : .left
        style.baseWritingDirection = rightToLeft ? .rightToLeft : .leftToRight
        field.placeholderAttributedString = NSAttributedString(string: placeholder, attributes: [
            .font: NSFont.systemFont(ofSize: size.text), .foregroundColor: NSColor(look.textTertiary), .paragraphStyle: style,
        ])
        if let editor = field.currentEditor() as? NSTextView {
            editor.selectedTextAttributes = [.backgroundColor: NSColor(look.textSelection), .foregroundColor: NSColor(look.text)]
            editor.insertionPointColor = NSColor(Tint(look.accent))
        }
        needsDisplay = true
    }

    override public func viewWillMove(toWindow newWindow: NSWindow?) {
        super.viewWillMove(toWindow: newWindow)
        keysWatch.watch(newWindow)
    }

    /// How many times what holds its window's keys changed while it was there.
    private(set) var keyChanges = 0

    private func keysChanged() {
        keyChanges += 1
        repaint()
    }

    override public func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let hoverArea { removeTrackingArea(hoverArea) }
        let area = NSTrackingArea(rect: box, options: [.mouseEnteredAndExited, .activeInKeyWindow], owner: self)
        addTrackingArea(area)
        hoverArea = area
    }

    override public func mouseEntered(with _: NSEvent) {
        hovered = true
        needsDisplay = true
    }

    override public func mouseExited(with _: NSEvent) {
        hovered = false
        needsDisplay = true
    }
}

extension SailTextField: Marked {
    /// Its focus ring and caret while it holds the keys; its error edge and sentence when refused.
    package var drawnMarks: [Mark] {
        let paint = paint, caret = state.editing && state.enabled ? look.marks(line: Tint(look.accent)) : []
        return look.marks(line: paint.edge) + caret + (error == nil ? [] : [.errorInk])
    }
}
