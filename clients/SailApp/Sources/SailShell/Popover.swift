import AppKit
import SailControls
import SailLook

/// A box of ours floating over a window, as the user menu and the New Session panel are: in a
/// borderless child panel that never takes the keys, over the look's pop shadow, with `room` around
/// the box for all of the shadow. While it shows, `keys`, a view of its owner's added to the window, holds
/// the window's keys; they go back where they were when it closes: to the anchor when the keys
/// opened it and the anchor takes them, else to what held them (a field being edited, its selection
/// given back), else, when that view has left the window, to `fallback`. A click outside it (one on
/// the anchor is the anchor's own) or the window giving up the keys tells `onDismiss`, except while
/// it ``stays``.
@MainActor
package final class Popover: NSObject {
    /// Clear room around the box, as far as the pop shadow reaches in either look.
    static let room = [Look.light, Look.dark].map(PopShadow.reach).max() ?? 0

    package let panel: NSPanel
    private let shadow: PopShadowView
    private let box: NSView
    private let keys: NSView

    var look: Look {
        didSet { shadow.look = look }
    }

    /// Told of a click outside the box, or of the window giving up the keys: the owner closes.
    var onDismiss: @MainActor () -> Void = {}
    /// What tells it its window gave up the keys: the window's own notice; a test names one of its
    /// own, so another test's window taking the keys closes nothing of its.
    var keysLeft = NSWindow.didResignKeyNotification
    /// While a sheet of its owner's asks over its window it stays: neither a click nor the window
    /// giving its keys to the sheet tells `onDismiss`.
    var stays = false
    /// Where the keys go when the view that held them has left the window (a tab closed, a rename
    /// ended): the shown pane's own, or the window.
    var fallback: @MainActor () -> NSResponder? = { nil }

    private weak var anchor: NSView?
    private weak var window: NSWindow?
    private weak var restore: NSResponder?
    /// The selection of the field being edited when it opened, given back with the keys.
    private var selection: NSRange?
    private var withKeys = false
    private var mouseWatch: Any?
    private var resignWatch: (any NSObjectProtocol)?

    /// Floats `box`, whose corners are `radius`, its keys taken by `keys`.
    init(_ box: NSView, keys: NSView, look: Look, radius: Double) {
        self.box = box
        self.keys = keys
        self.look = look
        shadow = PopShadowView(look: look)
        shadow.radius = radius
        panel = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        super.init()
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.becomesKeyOnlyIfNeeded = true
        let host = NSView()
        host.wantsLayer = true
        host.addSubview(shadow)
        host.addSubview(box)
        panel.contentView = host
    }

    package var isOpen: Bool {
        panel.isVisible
    }

    /// The box on screen.
    package var frame: NSRect {
        panel.frame.insetBy(dx: Self.room, dy: Self.room)
    }

    /// Shows the box at `frame` on screen over `window`, the keys taken by `keys`; `anchor` is what
    /// opened it, if anything (a click on it is its own), and `withKeys` says the keys did.
    func open(over window: NSWindow, anchor: NSView?, at frame: NSRect, withKeys: Bool) {
        guard !isOpen else { return }
        self.window = window
        self.anchor = anchor
        self.withKeys = withKeys
        place(frame)
        window.addChildWindow(panel, ordered: .above)
        panel.orderFront(nil)
        if keys.superview !== window.contentView { window.contentView?.addSubview(keys) }
        restore = Self.holder(of: window.firstResponder)
        selection = (window.firstResponder as? NSText).flatMap { $0.isFieldEditor ? $0.selectedRange : nil }
        window.makeFirstResponder(keys)
        mouseWatch = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] event in
            MainActor.assumeIsolated { self?.clickedAnywhere(event) }
            return event
        }
        resignWatch = NotificationCenter.default.addObserver(forName: keysLeft, object: window, queue: nil) { [weak self] _ in
            MainActor.assumeIsolated {
                guard self?.stays == false else { return }
                self?.onDismiss()
            }
        }
    }

    /// The box at `frame` on screen; the panel around it holds the shadow's room.
    func place(_ frame: NSRect) {
        panel.setFrame(frame.insetBy(dx: -Self.room, dy: -Self.room), display: false)
        let inside = NSRect(x: Self.room, y: Self.room, width: frame.width, height: frame.height)
        box.frame = inside
        shadow.frame = inside
        // Laid out now, not at the next display: only the panel's first ordering in draws it at once,
        // and a click on a later opening's new rows would find them with no frame.
        box.layoutSubtreeIfNeeded()
    }

    /// Takes the box away and gives the keys back.
    func close() {
        guard isOpen else { return }
        if let mouseWatch { NSEvent.removeMonitor(mouseWatch) }
        if let resignWatch { NotificationCenter.default.removeObserver(resignWatch) }
        mouseWatch = nil
        resignWatch = nil
        panel.parent?.removeChildWindow(panel)
        panel.orderOut(nil)
        guard let window, window.firstResponder === keys else { return }
        // A window asked to focus a view that refuses the keys takes them itself and says yes,
        // and a field was being edited through the window's field editor, not by itself.
        let button = withKeys && anchor?.acceptsFirstResponder == true ? anchor : nil
        let back = button ?? Self.still(restore, in: window) ?? fallback()
        window.makeFirstResponder(back)
        if button == nil, back === restore, let selection, let editor = (back as? NSTextField)?.currentEditor() {
            editor.selectedRange = NSRange(location: min(selection.location, editor.string.utf16.count), length: min(selection.length, max(editor.string.utf16.count - selection.location, 0)))
        }
    }

    /// A click outside the box dismisses it, on its shadow too; one on the anchor is the anchor's own
    /// (it toggles).
    func clickedAnywhere(_ event: NSEvent) {
        if stays { return }
        if event.window === panel, box.frame.contains(event.locationInWindow) { return }
        if let anchor, event.window === window {
            let at = anchor.convert(event.locationInWindow, from: nil)
            if anchor.bounds.contains(at) { return }
        }
        onDismiss()
    }

    /// `responder` when it can still take the keys in `window`: the window itself, or a view shown in it.
    static func still(_ responder: NSResponder?, in window: NSWindow) -> NSResponder? {
        if responder === window { return responder }
        guard let view = responder as? NSView, view.window === window, !view.isHiddenOrHasHiddenAncestor else { return nil }
        return view
    }

    /// Who holds the keys through `responder`: a field being edited, not the window's field editor,
    /// which forgets the field once its editing ends.
    static func holder(of responder: NSResponder?) -> NSResponder? {
        guard let editor = responder as? NSText, editor.isFieldEditor else { return responder }
        return editor.delegate as? NSResponder
    }
}
