import AppKit
import SailLook
import SailSurface
import SailVT

/// The pointer, the wheel, copy, paste and the font size.
extension TerminalView {
    override public func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        guard let link, let place = place(of: event) else { return }
        let action = pointer.down(
            at: place.cell, clicks: event.clickCount, shift: event.modifierFlags.contains(.shift), programTracksMouse: link.screen.tracksMouse
        )
        apply(action, MouseInput(action: .press, button: .left, x: place.x, y: place.y, modifiers: Self.modifiers(event)))
    }

    override public func mouseDragged(with event: NSEvent) {
        guard let place = place(of: event) else { return }
        apply(pointer.dragged(to: place.cell), MouseInput(action: .motion, button: .left, x: place.x, y: place.y, modifiers: Self.modifiers(event)))
    }

    override public func mouseUp(with event: NSEvent) {
        guard let place = place(of: event) else { return }
        apply(pointer.up(), MouseInput(action: .release, button: .left, x: place.x, y: place.y, modifiers: Self.modifiers(event)))
    }

    override public func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseMoved, .activeInKeyWindow, .inVisibleRect], owner: self, userInfo: nil))
    }

    /// The pointer moving with no button held: reported to a program that asked for every motion
    /// (mode 1003); the encoder sends nothing otherwise.
    override public func mouseMoved(with event: NSEvent) {
        guard let link, let place = place(of: event) else { return }
        let mouse = MouseInput(action: .motion, button: nil, x: place.x, y: place.y, modifiers: Self.modifiers(event))
        input.async { link.send(mouse) }
    }

    override public func rightMouseDown(with event: NSEvent) {
        report(event, .press, .right)
    }

    override public func rightMouseUp(with event: NSEvent) {
        report(event, .release, .right)
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

    /// ⌘V: the pasteboard's text through the screen's paste encoder; text that could run commands
    /// in a program that did not ask for pastes is sent only once the person confirms.
    @objc public func paste(_: Any?) {
        guard let link, let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        link.screen.scrollToBottom()
        input.async { [weak self] in
            guard link.paste(text, confirmed: false) == .needsConfirmation else { return }
            Task { @MainActor in self?.askToPaste(text, into: link) }
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

    /// The paste sheet: the line count, the risk in a sentence, Paste in the accent, and Cancel the
    /// default, so Return does not run what the sheet warns of. The keys are given once the sheet is
    /// laid out, which gives the first button Return.
    func presentPasteSheet(lines: Int, answer: @escaping @MainActor (Bool) -> Void) {
        guard let window else {
            answer(false)
            return
        }
        let alert = NSAlert()
        alert.messageText = lines == 1 ? "Paste this line?" : "Paste \(lines) lines?"
        alert.informativeText = "The program in this terminal did not ask for pastes, so each line may run as a command as it arrives."
        let paste = alert.addButton(withTitle: "Paste")
        let cancel = alert.addButton(withTitle: "Cancel")
        paste.bezelColor = NSColor(Look.dark.accent)
        paste.attributedTitle = NSAttributedString(string: "Paste", attributes: [.foregroundColor: NSColor(Look.dark.onAccent)])
        alert.beginSheetModal(for: window) { response in
            MainActor.assumeIsolated { answer(response == .alertFirstButtonReturn) }
        }
        paste.keyEquivalent = ""
        cancel.keyEquivalent = "\r"
    }

    private func report(_ event: NSEvent, _ action: MouseAction, _ button: MouseButton) {
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

    /// The cell under the event, and its place in pixels from the grid's top left.
    private func place(of event: NSEvent) -> (cell: CellPoint, x: Double, y: Double)? {
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
