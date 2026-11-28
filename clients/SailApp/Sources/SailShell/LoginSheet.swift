import AppKit
import SailAPI
import SailControls
import SailLook
import SailWorkbench

/// Store a Key (`login-store-mockup.html`): a sheet on the window, over the New Session panel,
/// 420 pt or, on a narrow page, the window less 12 pt each side. Its title; the kind's label over
/// one secure field holding the keys, with the server's how under it, or a refusal in its place;
/// where the person has a login of the harness, the name as words with Change, which puts the key's
/// field away (and its value with it) for the name's field alone until Done: a secure field and an
/// editable one are never shown together, which AppKit takes for a login form and offers Passwords'
/// AutoFill over; where it is kept; and Cancel and Store, the one primary, off until the field holds
/// something (Done while naming). Return stores (or is Done), Escape cancels, Tab goes field,
/// Change, Cancel, Store. The value is read from the field once, into the store's
/// request, and the field is cleared at once and again when the sheet ends; nothing else holds it.
/// A store that succeeds ends the sheet and tells ``onStored``, also when the sheet was cancelled
/// while it went; the panel, saying so, clears the clipboard where it still holds the value. Built whole by ``show()``,
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
            done.look = look
            change?.look = look
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
    /// Done, the primary while the name is named.
    package let done: SailButton
    /// Change, beside the name's words: names it.
    package let change: LinkButton?
    private let content: SheetGround
    private let heading = NSTextField(wrappingLabelWithString: "")
    private let label = NSTextField(labelWithString: "")
    private let nameLabel = NSTextField(labelWithString: "")
    private let nameWords = NSTextField(labelWithString: "")
    private let already = NSTextField(labelWithString: "")
    private let kept = NSTextField(wrappingLabelWithString: "")
    private let storing = NSTextField(labelWithString: "")
    private let spinner: Spinner
    private let titleFont: TitleFont
    private let mono: LabelFont
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
        self.mono = mono
        self.pasteboard = pasteboard
        self.direction = direction
        sheetWidth = width
        field = SailTextField(label: nil, placeholder: model.placeholder, help: model.help, secure: true, look: look, mono: mono)
        name = model.asksName ? SailTextField(label: nil, placeholder: model.name, size: .small, look: look, mono: mono) : nil
        cancel = SailButton("Cancel", kind: .secondary, look: look, target: nil, action: nil)
        store = SailButton(StoreLogin.store, kind: .primary, look: look, target: nil, action: nil)
        done = SailButton(StoreLogin.done, kind: .primary, look: look, target: nil, action: nil)
        change = model.asksName ? LinkButton(StoreLogin.change, look: look, target: nil, action: nil) : nil
        spinner = Spinner(look: look)
        content = SheetGround(look: look)
        super.init(contentRect: NSRect(x: 0, y: 0, width: width, height: 300), styleMask: [.titled], backing: .buffered, defer: true)
        title = model.title
        contentView = content
        for label in [heading, kept] {
            label.isSelectable = false
        }
        cancel.target = self
        cancel.action = #selector(cancelled(_:))
        store.target = self
        store.action = #selector(pressedStore(_:))
        store.shortcut("\r", mono: mono)
        store.setAccessibilityLabel(model.storeName)
        done.target = self
        done.action = #selector(pressedDone(_:))
        done.shortcut("\r", mono: mono)
        change?.target = self
        change?.action = #selector(pressedChange(_:))
        change?.setAccessibilityLabel(StoreLogin.changeName)
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
        let views: [NSView] = [heading, label, nameLabel, nameWords, already, kept, storing, spinner, field, cancel, store, done] + [name, change].compactMap(\.self)
        for view in views {
            view.userInterfaceLayoutDirection = direction
            content.addSubview(view)
        }
        initialFirstResponder = field.field
        show()
    }

    /// Draws what the model says, every frame set here: the key's field and the name's field never
    /// both in the sheet, the one not shown taken out of it.
    func show() {
        let width = sheetWidth, inner = width - 2 * Self.padding.sides, x = Self.padding.sides
        let naming: String?? = if case let .naming(refused) = model.stage { .some(refused) } else { .none }
        var y = Self.padding.top
        heading.attributedStringValue = layout.titled(model.title)
        y = layout.place(heading, x: x, y: y, width: inner) + Self.gap
        if let refused = naming, let name {
            hold([name], dropping: [field, label])
            y = nameLine(words: false, x: x, y: y, width: inner)
            name.error = refused
            y = layout.place(name, x: x, y: y, width: inner) + Self.gap
        } else {
            hold([field, label], dropping: [name].compactMap(\.self))
            label.attributedStringValue = layout.text(model.label, Look.Step.footnote, look.textSecondary)
            y = layout.place(label, x: x, y: y, width: inner) + Self.gap
            field.error = if case let .refused(said) = model.stage { said } else { nil }
            field.help = model.help
            field.isEnabled = model.stage != .storing
            y = layout.place(field, x: x, y: y, width: inner) + Self.gap
            if name != nil { y = nameLine(words: true, x: x, y: y, width: inner) - FieldSize.gap + Self.gap }
        }
        kept.attributedStringValue = layout.text(model.kept, Look.Step.footnote, look.textTertiary, wraps: true)
        kept.maximumNumberOfLines = 0
        kept.preferredMaxLayoutWidth = inner
        y = layout.place(kept, x: x, y: y, width: inner) + Self.padding.bottom
        content.footerTop = y
        let busy = model.stage == .storing, primary = naming == nil ? store : done
        store.isHidden = naming != nil
        done.isHidden = naming == nil
        store.isEnabled = model.storable
        done.isEnabled = model.nameGiven
        store.keyEquivalent = naming != nil ? "" : "\r"
        done.keyEquivalent = naming == nil ? "" : "\r"
        store.setAccessibilityLabel(busy ? StoreLogin.storing : model.storeName)
        change?.isEnabled = !busy
        var trailing = width - Self.footer.sides
        var buttonBox = NSRect.zero
        for button in [primary, cancel] {
            let size = button.intrinsicContentSize
            buttonBox = NSRect(x: trailing - Double(size.width), y: y + Self.footer.vertical, width: Double(size.width), height: Double(size.height))
            button.frame = layout.mirrored(buttonBox).insetBy(dx: -Look.haloRoom, dy: -Look.haloRoom)
            trailing = Double(buttonBox.minX) - Self.buttonGap
        }
        storing.attributedStringValue = layout.text(StoreLogin.storing, Look.Step.footnote, look.textSecondary)
        let storingSize = storing.fittingSize, middle = Double(buttonBox.midY), side = Double(spinner.intrinsicContentSize.width)
        spinner.frame = layout.mirrored(NSRect(x: Self.footer.sides, y: middle - side / 2, width: side, height: side))
        storing.frame = layout.mirrored(NSRect(x: Self.footer.sides + side + Self.buttonGap, y: middle - Double(storingSize.height) / 2, width: Double(storingSize.width), height: Double(storingSize.height)))
        spinner.isHidden = !busy
        storing.isHidden = !busy
        chainKeys(naming: naming != nil)
        let height = Double(buttonBox.maxY) + Self.footer.vertical
        setContentSize(NSSize(width: width, height: height))
        content.frame = NSRect(x: 0, y: 0, width: width, height: height)
        content.needsDisplay = true
    }

    /// "Name" at the leading edge and the names the person has at the trailing; with `words`, the
    /// name itself after "Name" and Change after it. Where the line ends, less the gap to a field.
    private func nameLine(words: Bool, x: Double, y: Double, width: Double) -> Double {
        nameLabel.attributedStringValue = layout.text(StoreLogin.nameLabel, Look.Step.footnote, look.textSecondary)
        already.attributedStringValue = layout.text(model.already, Look.Step.footnote, look.textTertiary, trailing: true)
        nameWords.attributedStringValue = NSAttributedString(string: model.name, attributes: [
            .font: mono.font(Look.Step.callout.points) as NSFont, .foregroundColor: NSColor(look.text), .paragraphStyle: layout.paragraph(wraps: false),
        ])
        let line = Double(ceil(nameLabel.fittingSize.height)), labelWidth = Double(nameLabel.fittingSize.width)
        nameLabel.frame = layout.mirrored(NSRect(x: x, y: y, width: labelWidth, height: line))
        var lead = x + labelWidth + Self.gap
        nameWords.isHidden = !words
        change?.isHidden = !words
        if words, let change {
            let wordsSize = nameWords.fittingSize, linkSize = change.fittingSize
            let wordsWidth = min(Double(wordsSize.width), max(x + width - lead - Double(linkSize.width) - Self.gap, 0))
            nameWords.frame = layout.mirrored(NSRect(x: lead, y: y + (line - Double(wordsSize.height)) / 2, width: wordsWidth, height: Double(wordsSize.height)))
            lead += wordsWidth + Self.gap
            change.frame = layout.mirrored(NSRect(x: lead, y: y + (line - Double(linkSize.height)) / 2, width: Double(linkSize.width), height: Double(linkSize.height)))
            lead += Double(linkSize.width) + Self.gap
        }
        let alreadyWidth = min(Double(already.fittingSize.width), max(x + width - lead, 0))
        already.frame = layout.mirrored(NSRect(x: x + width - alreadyWidth, y: y, width: alreadyWidth, height: line))
        return y + line + FieldSize.gap
    }

    /// `shown` in the sheet and `dropped` taken out of it, the key's value with its field.
    private func hold(_ shown: [NSView], dropping dropped: [NSView]) {
        for view in dropped where view.superview != nil {
            if view === field { field.field.stringValue = "" }
            view.removeFromSuperview()
        }
        for view in shown where view.superview == nil { content.addSubview(view) }
    }

    /// Tab: the key's field, Change, Cancel, Store; while naming, the name, Cancel, Done.
    private func chainKeys(naming: Bool) {
        let chain: [NSView] = naming ? [name?.field, cancel, done].compactMap(\.self) : [field.field, change, cancel, store].compactMap(\.self)
        for (at, view) in chain.enumerated() { view.nextKeyView = chain[(at + 1) % chain.count] }
    }

    /// Return in the key's field stores, in the name's is Done; Escape cancels, as the sheet's Cancel.
    package func control(_ control: NSControl, textView _: NSTextView, doCommandBy selector: Selector) -> Bool {
        switch selector {
        case #selector(NSResponder.insertNewline(_:)):
            if control === name?.field { doneNaming() } else { storeNow() }
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

    @objc private func pressedChange(_: Any?) {
        model.changeName()
        show()
        if let name, name.superview != nil { makeFirstResponder(name.field) }
    }

    @objc private func pressedDone(_: Any?) {
        doneNaming()
    }

    /// Done: the name kept, the key's field back and holding the keys.
    private func doneNaming() {
        model.doneNaming()
        show()
        if field.superview != nil { makeFirstResponder(field.field) }
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
        guard case .stored = model.stage else {
            show()
            makeFirstResponder(field.superview == nil ? name?.field : field.field)
            return
        }
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

    private var layout: SheetLayout {
        SheetLayout(width: sheetWidth, direction: direction, look: look, titleFont: titleFont)
    }
}
