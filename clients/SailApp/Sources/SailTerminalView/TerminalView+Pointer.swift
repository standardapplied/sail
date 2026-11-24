import AppKit
import SailLook
import SailSurface
import SailVT

/// The pointer, the wheel, copy, paste and the font size.
extension TerminalView {
    /// A click on a view not focused only focuses it. The window makes the clicked view first
    /// responder before handing it the press, so a press older than the view's focus is that click.
    override public func mouseDown(with event: NSEvent) {
        if event.modifierFlags.contains(.command), let place = place(of: event), let found = linkUnder(place.cell) {
            linkClicked = true
            if let url = found.url { openURL(url) } else { onLink(found.text, found.column) }
            return
        }
        if window?.firstResponder !== self { window?.makeFirstResponder(self) }
        focusingClick = event.timestamp <= focusedAt
        guard !focusingClick else { return }
        guard let link, let place = place(of: event) else { return }
        let action = pointer.down(
            at: place.cell, clicks: event.clickCount, shift: event.modifierFlags.contains(.shift), programTracksMouse: link.screen.tracksMouse
        )
        apply(action, MouseInput(action: .press, button: .left, x: place.x, y: place.y, modifiers: Self.modifiers(event)))
    }

    /// A drag moves the pointer as a program sees it, so the next motion without a button is
    /// reported whatever cell it is in.
    override public func mouseDragged(with event: NSEvent) {
        guard !linkClicked else { return }
        hovered = nil
        guard !focusingClick, let place = place(of: event) else { return }
        apply(pointer.dragged(to: place.cell), MouseInput(action: .motion, button: .left, x: place.x, y: place.y, modifiers: Self.modifiers(event)))
    }

    override public func mouseUp(with event: NSEvent) {
        guard !linkClicked else {
            linkClicked = false
            return
        }
        guard !focusingClick else {
            focusingClick = false
            return
        }
        guard let place = place(of: event) else { return }
        apply(pointer.up(), MouseInput(action: .release, button: .left, x: place.x, y: place.y, modifiers: Self.modifiers(event)))
    }

    override public func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseMoved, .mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self, userInfo: nil))
    }

    /// The pointer moving into another cell with no button held, as xterm and Ghostty report it:
    /// sent, from the input queue, only while a program tracks the mouse, and the encoder sends it
    /// only to one that asked for every motion (mode 1003).
    override public func mouseMoved(with event: NSEvent) {
        guard let place = place(of: event) else { return }
        showLink(at: place.cell, command: event.modifierFlags.contains(.command))
        guard let link, place.cell != hovered else { return }
        hovered = place.cell
        let mouse = MouseInput(action: .motion, button: nil, x: place.x, y: place.y, modifiers: Self.modifiers(event))
        input.async {
            guard link.screen.tracksMouse else { return }
            link.send(mouse)
        }
    }

    /// The right button goes to a program that tracks the mouse, in a focused view, without Shift;
    /// otherwise it opens the view's context menu (``menu(for:)``), as Ghostty's does.
    override public func rightMouseDown(with event: NSEvent) {
        guard programTakesTheButton(event) else { return super.rightMouseDown(with: event) }
        rightReported = true
        report(event, .press, .right)
    }

    override public func rightMouseUp(with event: NSEvent) {
        guard rightReported else { return super.rightMouseUp(with: event) }
        rightReported = false
        report(event, .release, .right)
    }

    /// A right-click or a control-click the program does not take focuses the view, then offers
    /// the context menu.
    override public func menu(for event: NSEvent) -> NSMenu? {
        guard event.type == .rightMouseDown || event.type == .leftMouseDown && event.modifierFlags.contains(.control),
              !programTakesTheButton(event)
        else { return nil }
        if window?.firstResponder !== self { window?.makeFirstResponder(self) }
        return contextMenu()
    }

    private func programTakesTheButton(_ event: NSEvent) -> Bool {
        window?.firstResponder === self && link?.screen.tracksMouse == true && !event.modifierFlags.contains(.shift)
    }

    /// Edit > Select All: everything the terminal holds, its history included.
    @objc override public func selectAll(_: Any?) {
        guard let link, link.screen.select(.all) else { return }
        needFrame()
    }

    override public func otherMouseDown(with event: NSEvent) {
        report(event, .press, .middle)
    }

    override public func otherMouseUp(with event: NSEvent) {
        report(event, .release, .middle)
    }

    /// The wheel goes to a program that tracks the mouse, becomes arrow keys for a full-screen
    /// program that asked for alternate scroll, or else scrolls the history; the screen decides.
    override public func scrollWheel(with event: NSEvent) {
        guard let link, let place = place(of: event) else { return }
        let lines = gatherer.lines(
            deltaY: event.scrollingDeltaY, precise: event.hasPreciseScrollingDeltas, lineHeight: Double(metrics.height) / renderer.faces.scale
        )
        guard lines != 0 else { return }
        input.async { [weak self] in
            link.scroll(lines: lines, x: place.x, y: place.y)
            Task { @MainActor in self?.needFrame() }
        }
    }

    /// ⌘C: the selection as libghostty-vt formats it, wrapped lines joined.
    @objc public func copy(_: Any?) {
        guard let text = link?.screen.selectedText(), !text.isEmpty else { return }
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }

    /// ⌘V: the pasteboard's text through the screen's paste encoder, from the input queue, the view
    /// back at the bottom first and drawn there; text that could run commands in a program that did
    /// not ask for pastes is sent only once the person confirms.
    @objc public func paste(_: Any?) {
        guard let text = pasteboard.string(forType: .string) else { return }
        paste(text: text)
    }

    /// `text` pasted at the cursor as ⌘V pastes the pasteboard's: bracketed when the program asked,
    /// and asked first where it could run commands in one that did not.
    public func paste(text: String) {
        guard let link, !text.isEmpty else { return }
        input.async { [weak self] in
            link.screen.scrollToBottom()
            let outcome = link.paste(text, confirmed: false)
            Task { @MainActor in
                self?.drawNowIfIdle()
                if outcome == .needsConfirmation { self?.askToPaste(text, into: link) }
            }
        }
    }

    @objc public func increaseFontSize(_: Any?) {
        setFontSize(FontLadder.larger(than: fontPoints))
    }

    @objc public func decreaseFontSize(_: Any?) {
        setFontSize(FontLadder.smaller(than: fontPoints))
    }

    @objc public func resetFontSize(_: Any?) {
        setFontSize(FontLadder.standard)
    }

    /// How many lines `text` pastes, a line ending in CR, LF or CR LF alike (each runs a command);
    /// a trailing line break ends the last line and starts none.
    static func lines(in text: String) -> Int {
        let breaks = text.unicodeScalars.filter { $0 == "\r" || $0 == "\n" }.count - text.components(separatedBy: "\r\n").count + 1
        let last = text.unicodeScalars.last
        return breaks + (last == "\r" || last == "\n" ? 0 : 1)
    }

    private func askToPaste(_ text: String, into link: any TerminalLink) {
        confirmPaste(Self.lines(in: text)) { [weak self] confirmed in
            guard confirmed, let self else { return }
            input.async { _ = link.paste(text, confirmed: true) }
        }
    }

    func presentPasteSheet(lines: Int, answer: @escaping @MainActor (Bool) -> Void) {
        guard let window else { return answer(false) }
        Confirmation.ask(
            lines == 1 ? "Paste this line?" : "Paste \(lines) lines?",
            detail: "The program in this terminal did not ask for pastes, so each line may run as a command as it arrives.",
            action: "Paste", on: window, answer: answer
        )
    }

    /// The right or middle button: on a view that is not focused, its press only focuses it, as the
    /// left button's does, and its release is not reported either.
    private func report(_ event: NSEvent, _ action: MouseAction, _ button: MouseButton) {
        if action == .press, window?.firstResponder !== self {
            window?.makeFirstResponder(self)
            focusingClick = true
            return
        }
        if action == .release, focusingClick {
            focusingClick = false
            return
        }
        guard let link, link.screen.tracksMouse, !event.modifierFlags.contains(.shift), let place = place(of: event) else { return }
        let mouse = MouseInput(action: action, button: button, x: place.x, y: place.y, modifiers: Self.modifiers(event))
        input.async { link.send(mouse) }
    }

    private func apply(_ action: Pointer.Action, _ mouse: MouseInput) {
        guard let link else { return }
        switch action {
        case .report:
            input.async { link.send(mouse) }
        case let .select(request):
            link.screen.select(request)
            needFrame()
        case .clearSelection:
            link.screen.clearSelection()
            needFrame()
        case .nothing:
            break
        }
    }

    override public func mouseExited(with _: NSEvent) {
        showLink(at: nil, command: false)
    }

    /// The cell under the event, and its place in pixels from the grid's top left.
    func place(of event: NSEvent) -> (cell: CellPoint, x: Double, y: Double)? {
        guard let grid = lastFrame?.size ?? grid else { return nil }
        let point = convert(event.locationInWindow, from: nil)
        let scale = renderer.faces.scale
        let x = Double(point.x) * scale
        let y = Double(point.y) * scale
        return (metrics.cell(atX: x, y: y, in: grid), x - Double(metrics.padding.width), y - Double(metrics.padding.height))
    }

    private static func modifiers(_ event: NSEvent) -> Modifiers {
        Typing.modifiers(event.modifierFlags.rawValue)
    }
}

extension NSColor {
    /// An sRGB colour from a token.
    public convenience init(_ rgb: RGB) {
        self.init(srgbRed: Double(rgb.red) / 255, green: Double(rgb.green) / 255, blue: Double(rgb.blue) / 255, alpha: 1)
    }
}

extension TerminalView: NSMenuItemValidation {
    /// Copy with a selection, Paste with text to paste, Select All with a session; the rest as the
    /// responder chain says. Asked each time a menu opens, so neither the selection's text nor the
    /// clipboard's is read: only whether there is some.
    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        switch item.action {
        case #selector(copy(_:)): link?.screen.hasSelection == true
        case #selector(paste(_:)): link != nil && pasteboard.availableType(from: [.string]) != nil
        case #selector(selectAll(_:)): link != nil
        default: responds(to: item.action)
        }
    }
}
