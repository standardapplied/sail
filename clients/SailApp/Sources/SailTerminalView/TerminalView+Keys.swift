import AppKit
import QuartzCore
import SailSurface
import SailVT

/// Keys and the input method, as Ghostty's `SurfaceView` takes them: every key goes through
/// `interpretKeyEvents`, so input methods and dead keys compose; what the input method commits
/// during the key is the key's text; a key that commits nothing is encoded from its own
/// characters. While text is marked nothing is sent (V4).
extension TerminalView: @preconcurrency NSTextInputClient {
    override public func keyDown(with event: NSEvent) {
        let composedBefore = composition.isComposing
        committedDuringKey = []
        interpretKeyEvents([event])
        let committed = committedDuringKey ?? []
        committedDuringKey = nil
        let inputs = Typing.inputs(
            for: Self.macEvent(event, action: event.isARepeat ? .repeat : .press), committed: committed,
            composedBefore: composedBefore, composingNow: composition.isComposing
        )
        for key in inputs {
            send(key, at: event.timestamp)
        }
        if composedBefore || composition.isComposing { needFrame() }
    }

    override public func keyUp(with event: NSEvent) {
        guard !composition.isComposing else { return }
        for key in Typing.inputs(for: Self.macEvent(event, action: .release), committed: [], composedBefore: false, composingNow: false) {
            send(key, at: event.timestamp)
        }
    }

    /// A modifier pressed or released alone. Such an event has no characters (asking for them
    /// raises), so only its key code and flags are read.
    override public func flagsChanged(with event: NSEvent) {
        let modifier = MacKeyEvent(keyCode: event.keyCode, modifierFlags: event.modifierFlags.rawValue)
        guard !composition.isComposing, let key = Typing.modifierKey(modifier) else { return }
        send(key, at: event.timestamp, typed: false)
    }

    override public func doCommand(by _: Selector) {}

    public func insertText(_ string: Any, replacementRange _: NSRange) {
        let text = Self.plain(string)
        let wasComposing = composition.isComposing
        composition.unmark()
        if committedDuringKey != nil {
            committedDuringKey?.append(text)
        } else if let key = Typing.inserted(text) {
            send(key, at: CACurrentMediaTime())
        }
        if wasComposing { needFrame() }
    }

    public func setMarkedText(_ string: Any, selectedRange _: NSRange, replacementRange _: NSRange) {
        composition.mark(Self.plain(string))
        needFrame()
    }

    public func unmarkText() {
        composition.unmark()
        needFrame()
    }

    public func selectedRange() -> NSRange {
        NSRange(location: composition.marked.utf16.count, length: 0)
    }

    public func markedRange() -> NSRange {
        composition.isComposing ? NSRange(location: 0, length: composition.marked.utf16.count) : NSRange(location: NSNotFound, length: 0)
    }

    public func hasMarkedText() -> Bool {
        composition.isComposing
    }

    public func attributedSubstring(forProposedRange _: NSRange, actualRange _: NSRangePointer?) -> NSAttributedString? {
        nil
    }

    public func validAttributesForMarkedText() -> [NSAttributedString.Key] {
        []
    }

    /// The cursor's cell on screen: where the input method puts its candidate window.
    public func firstRect(forCharacterRange _: NSRange, actualRange _: NSRangePointer?) -> NSRect {
        let rect = convert(cursorRect, to: nil)
        return window?.convertToScreen(rect) ?? rect
    }

    public func characterIndex(for _: NSPoint) -> Int {
        NSNotFound
    }

    /// The cursor's cell in the view's points, the top-left cell when the cursor is out of view.
    public var cursorRect: NSRect {
        let scale = renderer.faces.scale
        let cell = lastFrame?.cursor.position ?? CellPoint(x: 0, y: 0)
        let origin = metrics.origin(of: cell)
        return NSRect(
            x: Double(origin.x) / scale, y: Double(origin.y) / scale, width: Double(metrics.width) / scale,
            height: Double(metrics.height) / scale
        )
    }

    static func plain(_ string: Any) -> String {
        (string as? NSAttributedString)?.string ?? (string as? String) ?? ""
    }

    static func macEvent(_ event: NSEvent, action: KeyAction) -> MacKeyEvent {
        let flags = event.modifierFlags
        return MacKeyEvent(
            keyCode: event.keyCode, modifierFlags: flags.rawValue, characters: event.characters ?? "",
            charactersWithoutControl: event.characters(byApplyingModifiers: flags.subtracting(.control)) ?? "",
            unmodified: event.characters(byApplyingModifiers: []) ?? "", action: action
        )
    }
}
