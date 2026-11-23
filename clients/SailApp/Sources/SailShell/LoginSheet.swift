import AppKit
import SailAPI
import SailControls
import SailLook
import SailWorkbench

/// Store a Login (`login-store-mockup.html`): a sheet on the window, over the New Session panel,
/// 420 pt or, on a narrow page, the window less 12 pt each side. Its title; the kind's label over
/// one secure field holding the keys, with the server's how under it, or a refusal in its place;
/// the name, asked only where the person has a login of the harness; where it is kept; and Cancel
/// and Store, the one primary, off until the field holds something. Return stores, Escape cancels,
/// Tab goes field, name, Cancel, Store. The value is read from the field once, into the store's
/// request, and the field is cleared at once and again when the sheet ends; nothing else holds it.
/// A store that succeeds clears the clipboard where it still holds the value, ends the sheet and
/// tells ``onStored``, also when the sheet was cancelled while it went. Built whole by ``show()``,
/// every frame set there, so no layout pass changes what it shows; mirrored right to left.
@MainActor
package final class LoginSheet: NSPanel, NSTextFieldDelegate {
    static let width = 420.0, inset = 12.0
    static let padding = (top: 20.0, sides: 22.0, bottom: 14.0), gap = 12.0, helpGap = 8.0, footer = (vertical: 14.0, sides: 16.0), buttonGap = 8.0
    static let nameWidth = 180.0

    var look: Look {
        didSet {
            guard look != oldValue else { return }
            field.look = look
            name?.look = look
            cancel.look = look
            store.look = look
            spinner.look = look
            content.look = look
            show()
        }
    }

    /// Sends the store and gives its answer: the host's client.
    var send: @MainActor (Endpoint<LoginStored>) async -> Result<LoginStored, SailError> = { _ in .failure(.unreachable("nothing sends it")) }
    /// Told once a store succeeded, its model stored; whether or not the sheet is still up.
    var onStored: @MainActor (StoreLogin) -> Void = { _ in }
    /// Told when it ends, stored, cancelled or withdrawn.
    var onEnd: @MainActor () -> Void = {}

    package private(set) var model: StoreLogin
    package let field: SailTextField
    package let name: SailTextField?
    package let cancel: SailButton
    package let store: SailButton
    private let content: LoginSheetContent
    private let heading = NSTextField(wrappingLabelWithString: "")
    private let label = NSTextField(labelWithString: "")
    private let nameLabel = NSTextField(labelWithString: "")
    private let already = NSTextField(labelWithString: "")
    private let kept = NSTextField(wrappingLabelWithString: "")
    private let storing = NSTextField(labelWithString: "")
    private let spinner: Spinner
    private let titleFont: TitleFont
    private let pasteboard: NSPasteboard
    private let direction: NSUserInterfaceLayoutDirection
    private let sheetWidth: Double
    private var ended = false

    /// The sheet for `model`, `width` wide, laid out in `direction`; the clipboard it reads and
    /// clears is `pasteboard`.
    init(_ model: StoreLogin, width: Double, direction: NSUserInterfaceLayoutDirection, look: Look, titleFont: TitleFont, mono: LabelFont, pasteboard: NSPasteboard = .general) {
        self.model = model
        self.look = look
        self.titleFont = titleFont
        self.pasteboard = pasteboard
        self.direction = direction
        sheetWidth = width
        field = SailTextField(label: nil, placeholder: model.placeholder, help: model.help, secure: true, look: look, mono: mono)
        name = model.asksName ? SailTextField(label: nil, placeholder: model.name, size: .small, look: look, mono: mono) : nil
        cancel = SailButton("Cancel", kind: .secondary, look: look, target: nil, action: nil)
        store = SailButton(StoreLogin.store, kind: .primary, look: look, target: nil, action: nil)
        spinner = Spinner(look: look)
        content = LoginSheetContent(look: look)
        super.init(contentRect: NSRect(x: 0, y: 0, width: width, height: 300), styleMask: [.titled], backing: .buffered, defer: true)
        title = model.title
        contentView = content
        cancel.target = self
        cancel.action = #selector(cancelled(_:))
        store.target = self
        store.action = #selector(pressedStore(_:))
        store.shortcut("\r", mono: mono)
        store.setAccessibilityLabel(model.storeName)
        field.field.delegate = self
        field.field.setAccessibilityLabel(model.fieldName)
        if let name {
            name.field.stringValue = model.name
            name.field.font = mono.font(Look.Step.callout.points) as NSFont
            name.field.delegate = self
            name.field.setAccessibilityLabel(StoreLogin.nameLabel)
            name.boxWidth = Self.nameWidth
        }
        storing.stringValue = StoreLogin.storing
        let views: [NSView] = [heading, label, nameLabel, already, kept, storing, spinner, field, cancel, store] + (name.map { [$0] } ?? [])
        for view in views {
            view.userInterfaceLayoutDirection = direction
            content.addSubview(view)
        }
        field.field.nextKeyView = name?.field ?? cancel
        name?.field.nextKeyView = cancel
        cancel.nextKeyView = store
        store.nextKeyView = field.field
        initialFirstResponder = field.field
        show()
    }

    /// Draws what the model says, every frame set here.
    func show() {
        let width = sheetWidth, inner = width - 2 * Self.padding.sides, x = Self.padding.sides
        var y = Self.padding.top
        heading.attributedStringValue = titled(model.title)
        y = place(heading, x: x, y: y, width: inner) + Self.gap
        label.attributedStringValue = text(model.label, Look.Step.footnote, look.textSecondary)
        y = place(label, x: x, y: y, width: inner) + Self.gap
        let refusal: (said: String, under: StoreLogin.Field)? = if case let .refused(said, under) = model.stage { (said, under) } else { nil }
        field.error = refusal?.under == .value ? refusal?.said : nil
        field.isEnabled = model.stage != .storing
        y = place(field, x: x, y: y, width: inner) + Self.gap
        if let name {
            nameLabel.attributedStringValue = text(StoreLogin.nameLabel, Look.Step.footnote, look.textSecondary)
            already.attributedStringValue = text(model.already, Look.Step.footnote, look.textTertiary, trailing: true)
            let line = Double(ceil(nameLabel.fittingSize.height))
            let nameSize = nameLabel.fittingSize
            nameLabel.frame = mirrored(NSRect(x: x, y: y, width: Double(nameSize.width), height: line))
            let room = max(inner - Double(nameSize.width) - Self.gap, 0)
            already.frame = mirrored(NSRect(x: x + inner - min(Double(already.fittingSize.width), room), y: y, width: min(Double(already.fittingSize.width), room), height: line))
            y += line + FieldSize.gap
            name.error = refusal?.under == .name ? refusal?.said : nil
            name.isEnabled = model.stage != .storing
            y = place(name, x: x, y: y, width: inner) + Self.gap
        }
        kept.attributedStringValue = text(model.kept, Look.Step.footnote, look.textTertiary, wraps: true)
        kept.maximumNumberOfLines = 0
        kept.preferredMaxLayoutWidth = inner
        y = place(kept, x: x, y: y, width: inner) + Self.padding.bottom
        content.footerTop = y
        let busy = model.stage == .storing
        store.isEnabled = model.storable
        store.keyEquivalent = busy ? "" : "\r"
        store.setAccessibilityLabel(busy ? StoreLogin.storing : model.storeName)
        var trailing = width - Self.footer.sides
        var buttonBox = NSRect.zero
        for button in [store, cancel] {
            let size = button.intrinsicContentSize
            buttonBox = NSRect(x: trailing - Double(size.width), y: y + Self.footer.vertical, width: Double(size.width), height: Double(size.height))
            button.frame = mirrored(buttonBox).insetBy(dx: -Look.haloRoom, dy: -Look.haloRoom)
            trailing = Double(buttonBox.minX) - Self.buttonGap
        }
        storing.attributedStringValue = text(StoreLogin.storing, Look.Step.footnote, look.textSecondary)
        let storingSize = storing.fittingSize, middle = Double(buttonBox.midY), side = Double(spinner.intrinsicContentSize.width)
        spinner.frame = mirrored(NSRect(x: Self.footer.sides, y: middle - side / 2, width: side, height: side))
        storing.frame = mirrored(NSRect(x: Self.footer.sides + side + Self.buttonGap, y: middle - Double(storingSize.height) / 2, width: Double(storingSize.width), height: Double(storingSize.height)))
        spinner.isHidden = !busy
        storing.isHidden = !busy
        let height = Double(buttonBox.maxY) + Self.footer.vertical
        setContentSize(NSSize(width: width, height: height))
        content.frame = NSRect(x: 0, y: 0, width: width, height: height)
        content.needsDisplay = true
    }

    /// Return in the field or the name stores; Escape cancels, as the sheet's Cancel.
    package func control(_: NSControl, textView _: NSTextView, doCommandBy selector: Selector) -> Bool {
        switch selector {
        case #selector(NSResponder.insertNewline(_:)):
            storeNow()
            return true
        case #selector(NSResponder.cancelOperation(_:)):
            end()
            return true
        default:
            return false
        }
    }

    package func controlTextDidChange(_ note: Notification) {
        guard let changed = note.object as? NSTextField else { return }
        if changed === field.field {
            model.typed(holdsSomething: !changed.stringValue.isEmpty)
        } else {
            model.named(changed.stringValue)
        }
        show()
    }

    package override func cancelOperation(_: Any?) {
        end()
    }

    @objc private func cancelled(_: Any?) {
        end()
    }

    @objc private func pressedStore(_: Any?) {
        storeNow()
    }

    /// The value read once into the store, and the field cleared; the answer taken when it comes.
    private func storeNow() {
        let clipboard = StoreLogin.Clipboard(count: pasteboard.changeCount, text: pasteboard.string(forType: .string))
        guard let endpoint = model.store(field.field.stringValue, clipboard: clipboard) else { return }
        field.field.stringValue = ""
        show()
        let send = self.send
        Task {
            let answer = await send(endpoint)
            answered(answer)
        }
    }

    private func answered(_ answer: Result<LoginStored, SailError>) {
        model.answered(answer, clipboard: pasteboard.changeCount)
        guard case let .stored(_, clears) = model.stage else {
            show()
            makeFirstResponder(field.field)
            return
        }
        if clears { pasteboard.clearContents() }
        end()
        onStored(model)
    }

    /// Ends it as Cancel does, when its panel has gone; nothing once it ended.
    func withdraw() {
        end()
    }

    private func end() {
        field.field.stringValue = ""
        guard !ended else { return }
        ended = true
        sheetParent?.endSheet(self)
        onEnd()
    }

    // MARK: Words and frames

    private func titled(_ words: String) -> NSAttributedString {
        let style = paragraph()
        style.minimumLineHeight = (Look.Step.title3.points * 1.2).rounded()
        style.maximumLineHeight = style.minimumLineHeight
        return NSAttributedString(string: words, attributes: [
            .font: titleFont.font(.title3) as NSFont, .foregroundColor: NSColor(look.text), .paragraphStyle: style, .kern: -0.012 * Look.Step.title3.points,
        ])
    }

    private func text(_ words: String, _ step: Look.Step, _ ink: Tint, trailing: Bool = false, wraps: Bool = false) -> NSAttributedString {
        NSAttributedString(string: words, attributes: [
            .font: NSFont.systemFont(ofSize: step.points), .foregroundColor: NSColor(ink), .paragraphStyle: paragraph(trailing: trailing, wraps: wraps),
        ])
    }

    /// Set to the leading edge, or the trailing; words keep their own direction right to left. A
    /// line that does not wrap is cut with an ellipsis.
    private func paragraph(trailing: Bool = false, wraps: Bool = true) -> NSMutableParagraphStyle {
        let style = NSMutableParagraphStyle()
        style.alignment = (direction == .rightToLeft) != trailing ? .right : .left
        style.baseWritingDirection = .natural
        style.lineBreakMode = wraps ? .byWordWrapping : .byTruncatingTail
        return style
    }

    /// `view` at `x`, `y`, `width` wide, at its own height for that width; where it ends. A field's
    /// frame holds the halo's room around its box.
    private func place(_ view: NSView, x: Double, y: Double, width: Double) -> Double {
        let room = view is SailTextField ? Look.haloRoom : 0
        view.frame = mirrored(NSRect(x: x - room, y: y - room, width: width + 2 * room, height: 10))
        let height: Double = if let label = view as? NSTextField, let cell = label.cell {
            Double(ceil(cell.cellSize(forBounds: NSRect(x: 0, y: 0, width: width, height: 10000)).height))
        } else {
            Double(ceil(view.intrinsicContentSize.height))
        }
        view.frame = mirrored(NSRect(x: x - room, y: y - room, width: width + 2 * room, height: height + 2 * room))
        return y + height
    }

    /// `rect`, laid out left to right, turned about the sheet's middle right to left.
    private func mirrored(_ rect: NSRect) -> NSRect {
        guard direction == .rightToLeft else { return rect }
        return NSRect(x: sheetWidth - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }
}

/// The sheet's ground and the hairline over its footer.
@MainActor
final class LoginSheetContent: NSView {
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
