import AppKit
import SailLook

/// The library's sheet, attached to a window under its title bar: 440 pt on the pop surface, a
/// Newsreader 18 title and a 13 pt secondary body, padded 22 / 24 / 18 with 14 between, and a
/// footer under a hairline holding the buttons trailing, 8 pt apart. Esc is Cancel. Return is the
/// primary, except on a destructive sheet (a destructive or destructive-quiet confirm): its
/// destructive button comes last, takes no Return, and the keys start on Cancel.
@MainActor
public final class Sheet: NSObject {
    public static let width = 440.0

    public let window: NSPanel
    package let title: NSTextField
    package let body: NSTextField
    public let cancel: SailButton
    public let confirm: SailButton
    private var answer: (@MainActor (Bool) -> Void)?
    private let titleFont: TitleFont

    /// Its look: a new one, as the app's appearance changes while it asks, paints it again.
    public var look: Look {
        didSet {
            guard look != oldValue else { return }
            paint(title: title.stringValue, body: body.stringValue)
            cancel.look = look
            confirm.look = look
            (window.contentView as? SheetContent)?.look = look
        }
    }

    private func paint(title words: String, body said: String) {
        let lines = NSMutableParagraphStyle()
        lines.minimumLineHeight = (Look.Step.title3.points * 1.2).rounded()
        lines.maximumLineHeight = lines.minimumLineHeight
        title.attributedStringValue = NSAttributedString(string: words, attributes: [
            .font: titleFont.font(.title3) as NSFont, .foregroundColor: NSColor(look.text), .paragraphStyle: lines,
            .kern: -0.012 * Look.Step.title3.points,
        ])
        body.attributedStringValue = NSAttributedString(string: said, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ])
    }

    public init(title: String, body: String, confirm: String, kind: ButtonKind, look: Look, titleFont: TitleFont) {
        let destructive = kind == .destructive || kind == .destructiveQuiet
        self.title = NSTextField(wrappingLabelWithString: title)
        self.body = NSTextField(wrappingLabelWithString: body)
        cancel = SailButton("Cancel", kind: .secondary, look: look, target: nil, action: nil)
        self.confirm = SailButton(confirm, kind: kind, look: look, target: nil, action: nil)
        window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: Self.width, height: 160), styleMask: [.titled], backing: .buffered, defer: true)
        self.titleFont = titleFont
        self.look = look
        super.init()
        paint(title: title, body: body)
        cancel.target = self
        cancel.action = #selector(cancelled(_:))
        cancel.keyEquivalent = "\u{1b}"
        self.confirm.target = self
        self.confirm.action = #selector(confirmed(_:))
        self.confirm.isDefault = !destructive
        window.contentView = SheetContent(title: self.title, body: self.body, cancel: cancel, confirm: self.confirm, look: look)
        window.initialFirstResponder = destructive ? cancel : self.confirm
    }

    /// Its content laid out at its size, for a gallery that shows it outside a sheet.
    package func laidOut() -> NSView {
        let content = window.contentView as? SheetContent
        content?.fit()
        content?.layoutSubtreeIfNeeded()
        return window.contentView ?? NSView()
    }

    /// Shows the sheet on `parent`; `answer` hears true for the confirm button, false for Cancel.
    public func ask(on parent: NSWindow, _ answer: @escaping @MainActor (Bool) -> Void) {
        self.answer = answer
        (window.contentView as? SheetContent)?.fit()
        parent.beginSheet(window)
    }

    @objc private func cancelled(_: Any?) {
        end(false)
    }

    @objc private func confirmed(_: Any?) {
        end(true)
    }

    private func end(_ confirmed: Bool) {
        let answer = self.answer
        self.answer = nil
        window.sheetParent?.endSheet(window)
        answer?(confirmed)
    }
}

/// The sheet's content: the words, then the footer under its hairline. It holds the views it lays
/// out, not the sheet: shown in a gallery, it outlives the sheet that made it.
@MainActor
final class SheetContent: NSView {
    static let padding = (top: 22.0, sides: 24.0, bottom: 18.0), gap = 14.0, footer = (vertical: 14.0, sides: 16.0), buttonGap = 8.0

    var look: Look {
        didSet { needsDisplay = true }
    }

    private let title: NSTextField, body: NSTextField, cancel: SailButton, confirm: SailButton

    init(title: NSTextField, body: NSTextField, cancel: SailButton, confirm: SailButton, look: Look) {
        self.title = title
        self.body = body
        self.cancel = cancel
        self.confirm = confirm
        self.look = look
        super.init(frame: NSRect(x: 0, y: 0, width: Sheet.width, height: 160))
        for view in [title, body, cancel, confirm] as [NSView] { addSubview(view) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// The words' column wide, the window as tall as its content.
    func fit() {
        let column = Sheet.width - 2 * Self.padding.sides
        let titleHeight = Self.height(title, column), bodyHeight = Self.height(body, column)
        let footerTop = Self.padding.top + titleHeight + Self.gap + bodyHeight + Self.padding.bottom
        let button = Double(confirm.intrinsicContentSize.height)
        window?.setContentSize(NSSize(width: Sheet.width, height: footerTop + 2 * Self.footer.vertical + button))
        needsLayout = true
    }

    /// A label's height as it draws its words at `width` (its cell, not the string's bounding rect,
    /// which wraps differently and lost a last line in the empty state).
    private static func height(_ label: NSTextField, _ width: Double) -> Double {
        let inset = 2.0, cell = label.cell
        return Double(ceil(cell?.cellSize(forBounds: NSRect(x: 0, y: 0, width: width + 2 * inset, height: .greatestFiniteMagnitude)).height ?? 0))
    }

    override func layout() {
        super.layout()
        let column = Sheet.width - 2 * Self.padding.sides, inset = 2.0
        let titleHeight = Self.height(title, column), bodyHeight = Self.height(body, column)
        title.frame = NSRect(x: Self.padding.sides - inset, y: Self.padding.top, width: column + 2 * inset, height: titleHeight)
        body.frame = NSRect(x: Self.padding.sides - inset, y: Self.padding.top + titleHeight + Self.gap, width: column + 2 * inset, height: bodyHeight)
        let footerTop = Self.padding.top + titleHeight + Self.gap + bodyHeight + Self.padding.bottom
        var trailing = Double(bounds.width) - Self.footer.sides
        for button in [confirm, cancel] {
            let size = button.intrinsicContentSize
            let box = NSRect(x: trailing - Double(size.width), y: footerTop + Self.footer.vertical, width: Double(size.width), height: Double(size.height))
            button.frame = button.frame(forAlignmentRect: box)
            trailing = Double(box.minX) - Self.buttonGap
        }
    }

    override func draw(_: NSRect) {
        NSColor(Tint(look.surfacePop)).setFill()
        bounds.fill()
        let footerTop = Double(confirm.alignmentRect(forFrame: confirm.frame).minY) - Self.footer.vertical
        NSColor(look.separator).setFill()
        NSRect(x: 0, y: footerTop, width: Double(bounds.width), height: Look.hairline).fill()
    }
}
