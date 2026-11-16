import AppKit
import SailLook
import SailTerminalView
import SailWorkbench

/// The tabs, in the title bar's row after the window's buttons, on `chrome` as the rail is: each
/// 120 to 240 pt, the selected one on `fillSelected` in `text`, the others in `textSecondary` and on
/// `fillHover` under the pointer; a close button on the selected tab and the one under the pointer;
/// `+` last. A tab is clicked to select, double-clicked to rename in place, dragged to reorder; it
/// never moves the window. The strip's empty part is a plain view, as the rail's top is, so there
/// the title bar's own dragging and double-click action (the person's system setting) stay.
@MainActor
public final class TabStrip: NSView {
    public struct Item: Equatable, Sendable {
        public let id: TabID
        public let title: String
        public let zoomed: Bool
    }

    static let narrowest = 120.0
    static let widest = 240.0
    static let gap = 4.0
    static let addSide = 24.0

    var onSelect: @MainActor (TabID) -> Void = { _ in }
    var onClose: @MainActor (TabID) -> Void = { _ in }
    var onNew: @MainActor () -> Void = {}
    /// A rename ended: the name typed, or nil when it was cancelled.
    var onRename: @MainActor (TabID, String?) -> Void = { _, _ in }
    var onMove: @MainActor (TabID, Int) -> Void = { _, _ in }

    var look: Look {
        didSet { paint() }
    }

    public private(set) var tabs: [TabButton] = []
    public let add: NSButton

    /// A tab's name is being typed.
    var isRenaming: Bool {
        tabs.contains { $0.field != nil }
    }

    init(look: Look) {
        self.look = look
        add = NSButton(image: TabButton.symbol("plus", accessibility: "New Tab"), target: nil, action: nil)
        super.init(frame: NSRect(x: 0, y: 0, width: 600, height: 28))
        wantsLayer = true
        add.isBordered = false
        add.target = self
        add.action = #selector(addTab)
        add.toolTip = "New Tab"
        addSubview(add)
        setAccessibilityElement(true)
        setAccessibilityRole(.tabGroup)
        setAccessibilityLabel("Tabs")
        paint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    /// The tabs as `items` say, in their order, `selected` marked.
    func show(_ items: [Item], selected: TabID?) {
        let held = Dictionary(uniqueKeysWithValues: tabs.map { ($0.id, $0) })
        tabs = items.map { item in
            let tab = held[item.id] ?? makeTab(item.id)
            tab.show(item, selected: item.id == selected, look: look)
            return tab
        }
        for (id, tab) in held where !items.contains(where: { $0.id == id }) {
            tab.removeFromSuperview()
        }
        setAccessibilityChildren(tabs)
        needsLayout = true
    }

    private var tabWidth: Double {
        let room = Double(bounds.width) - Self.addSide - Self.gap
        let count = Double(max(1, tabs.count))
        return min(Self.widest, max(Self.narrowest, (room - Self.gap * (count - 1)) / count))
    }

    override public func layout() {
        super.layout()
        var x = 0.0
        for tab in tabs {
            if !tab.dragging { tab.frame = CGRect(x: x, y: 4, width: tabWidth, height: max(0, Double(bounds.height) - 8)) }
            x += tabWidth + Self.gap
        }
        add.frame = CGRect(x: x, y: (Double(bounds.height) - Self.addSide) / 2, width: Self.addSide, height: Self.addSide)
    }

    private func makeTab(_ id: TabID) -> TabButton {
        let tab = TabButton(id: id, look: look)
        tab.onSelect = { [weak self] in self?.onSelect(id) }
        tab.onClose = { [weak self] in self?.onClose(id) }
        tab.onRename = { [weak self] name in self?.onRename(id, name) }
        tab.onDrop = { [weak self] tab in self?.drop(tab) }
        addSubview(tab)
        return tab
    }

    /// A dragged tab let go: its place is where its middle is.
    private func drop(_ tab: TabButton) {
        let index = Int((Double(tab.frame.midX) / (tabWidth + Self.gap)).rounded(.down))
        tab.dragging = false
        onMove(tab.id, min(max(0, index), tabs.count - 1))
        needsLayout = true
    }

    @objc private func addTab() {
        onNew()
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.chrome).cgColor
        add.contentTintColor = NSColor(look.textSecondary)
        tabs.forEach { $0.look = look }
    }
}

/// One tab of the strip, an accessibility tab button.
@MainActor
public final class TabButton: NSView, NSTextFieldDelegate {
    public let id: TabID
    public let label = NSTextField(labelWithString: "")
    public let close: NSButton
    public private(set) var isSelected = false
    /// The field a rename types into, while one is under way.
    public private(set) var field: NSTextField?
    var onSelect: @MainActor () -> Void = {}
    var onClose: @MainActor () -> Void = {}
    var onRename: @MainActor (String?) -> Void = { _ in }
    var onDrop: @MainActor (TabButton) -> Void = { _ in }
    var dragging = false
    var look: Look {
        didSet { paint() }
    }

    private let zoomMark = NSImageView(image: TabButton.symbol("arrow.up.left.and.arrow.down.right", accessibility: "Zoomed"))
    private var hovering = false
    private var grabbedAt: (pointer: Double, origin: Double)?
    private var renamed = false

    init(id: TabID, look: Look) {
        self.id = id
        self.look = look
        close = NSButton(image: Self.symbol("xmark", accessibility: "Close Tab"), target: nil, action: nil)
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        label.font = .systemFont(ofSize: Look.Step.callout.points)
        label.lineBreakMode = .byTruncatingTail
        close.isBordered = false
        close.target = self
        close.action = #selector(closeTab)
        close.toolTip = "Close Tab"
        for view in [label, close, zoomMark] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            zoomMark.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Look.Space.s), zoomMark.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.leadingAnchor.constraint(equalTo: zoomMark.trailingAnchor, constant: 4), label.centerYAnchor.constraint(equalTo: centerYAnchor),
            close.leadingAnchor.constraint(equalTo: label.trailingAnchor, constant: 4), close.centerYAnchor.constraint(equalTo: centerYAnchor),
            close.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4), close.widthAnchor.constraint(equalToConstant: 16),
        ])
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setAccessibilityElement(true)
        setAccessibilityRole(.radioButton)
        setAccessibilitySubrole(.tabButton)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    static func symbol(_ name: String, accessibility: String) -> NSImage {
        let image = NSImage(systemSymbolName: name, accessibilityDescription: accessibility) ?? NSImage()
        return image.withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: 11, weight: .medium)) ?? image
    }

    func show(_ item: TabStrip.Item, selected: Bool, look: Look) {
        label.stringValue = item.title
        toolTip = item.title
        zoomMark.isHidden = !item.zoomed
        isSelected = selected
        setAccessibilityLabel(item.title)
        setAccessibilityValue(selected)
        self.look = look
    }

    override public var mouseDownCanMoveWindow: Bool {
        false
    }

    /// The tab takes every click on it but its close button's and, while renaming, its field's: its
    /// title is no label of its own to click (or to move the window by).
    override public func hitTest(_ point: NSPoint) -> NSView? {
        guard let hit = super.hitTest(point) else { return nil }
        return hit === close || field.map { hit.isDescendant(of: $0) } == true ? hit : self
    }

    override public func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self, userInfo: nil))
    }

    override public func mouseEntered(with _: NSEvent) {
        hovering = true
        paint()
    }

    override public func mouseExited(with _: NSEvent) {
        hovering = false
        paint()
    }

    override public func mouseDown(with event: NSEvent) {
        guard event.clickCount < 2 else { return rename() }
        grabbedAt = (Double(event.locationInWindow.x), Double(frame.minX))
        onSelect()
    }

    override public func mouseDragged(with event: NSEvent) {
        guard let grabbedAt else { return }
        let moved = Double(event.locationInWindow.x) - grabbedAt.pointer
        guard dragging || abs(moved) > 3 else { return }
        dragging = true
        setFrameOrigin(NSPoint(x: grabbedAt.origin + moved, y: frame.minY))
    }

    override public func mouseUp(with _: NSEvent) {
        grabbedAt = nil
        if dragging { onDrop(self) }
    }

    @objc private func closeTab() {
        onClose()
    }

    /// The title becomes a field: Return names the tab what it holds (nothing clears the name),
    /// Escape leaves the name as it was.
    public func rename() {
        guard field == nil else { return }
        let field = NSTextField(string: label.stringValue)
        field.font = label.font
        field.focusRingType = .none
        field.isBordered = false
        field.drawsBackground = true
        field.wantsLayer = true
        field.layer?.borderWidth = 2
        field.layer?.cornerRadius = Look.contentRadius
        field.delegate = self
        field.frame = bounds.insetBy(dx: 4, dy: 2)
        self.field = field
        renamed = false
        paint()
        addSubview(field)
        window?.makeFirstResponder(field)
        if let editor = field.currentEditor() as? NSTextView {
            editor.selectedTextAttributes = [.backgroundColor: NSColor(look.fillSelected), .foregroundColor: NSColor(look.text)]
            editor.insertionPointColor = NSColor(look.accent)
            editor.selectAll(nil)
        }
    }

    public func control(_: NSControl, textView _: NSTextView, doCommandBy command: Selector) -> Bool {
        switch command {
        case #selector(NSResponder.insertNewline(_:)):
            renamed = true
            endRename()
            return true
        case #selector(NSResponder.cancelOperation(_:)):
            endRename()
            return true
        default:
            return false
        }
    }

    public func controlTextDidEndEditing(_: Notification) {
        endRename()
    }

    private func endRename() {
        guard let field else { return }
        self.field = nil
        field.removeFromSuperview()
        onRename(renamed ? field.stringValue : nil)
    }

    private func paint() {
        let fill = isSelected ? NSColor(look.fillSelected) : hovering ? NSColor(look.fillHover) : .clear
        layer?.backgroundColor = fill.cgColor
        label.textColor = NSColor(isSelected ? look.text : look.textSecondary)
        close.contentTintColor = NSColor(look.textSecondary)
        zoomMark.contentTintColor = NSColor(look.textSecondary)
        close.isHidden = !(isSelected || hovering)
        field?.textColor = NSColor(look.text)
        field?.backgroundColor = NSColor(look.surface)
        field?.layer?.borderColor = NSColor(look.accent).cgColor
    }
}
