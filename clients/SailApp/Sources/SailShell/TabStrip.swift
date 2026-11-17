import AppKit
import SailLook
import SailTerminalView
import SailWorkbench

/// The tabs in the title bar's row, laid out by ``TabRow``. A tab never moves the window; the
/// strip's empty part takes no mouse event of its own, so in the title bar's accessory the title
/// bar's own view gets it: dragging, and the double-click System Settings names. With the tabs not
/// shown (another pane selected) the strip stays, empty and clear, so the row stays the title bar's.
@MainActor
public final class TabStrip: NSView {
    public struct Item: Equatable, Sendable {
        public let id: TabID
        public let title: String
        public let zoomed: Bool
    }

    var onSelect: @MainActor (TabID) -> Void = { _ in }
    var onClose: @MainActor (TabID) -> Void = { _ in }
    var onNew: @MainActor () -> Void = {}
    /// A rename ended: the name typed, or nil when it was cancelled.
    var onRename: @MainActor (TabID, String?) -> Void = { _, _ in }
    var onMove: @MainActor (TabID, Int) -> Void = { _, _ in }
    /// A tab's context menu, for the tab it names.
    var onMenu: @MainActor (TabID) -> NSMenu? = { _ in nil }

    var look: Look {
        didSet { paint() }
    }

    public private(set) var tabs: [TabButton] = []
    public let add: RingedButton

    var showsTabs = true {
        didSet {
            needsLayout = true
            paint()
        }
    }

    public var isRenaming: Bool {
        tabs.contains { $0.field != nil }
    }

    init(look: Look) {
        self.look = look
        add = RingedButton(TabButton.symbol("plus", accessibility: "New Tab"), tip: "New Tab")
        super.init(frame: NSRect(x: 0, y: 0, width: 600, height: 28))
        wantsLayer = true
        add.target = self
        add.action = #selector(addTab)
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
        selectedIndex = items.firstIndex { $0.id == selected }
        setAccessibilityChildren(tabs)
        needsLayout = true
    }

    private var selectedIndex: Int?

    /// The strip's own empty space before the first tab, the title bar's to drag and double-click:
    /// what the window buttons' inset leaves of it.
    var leading = Look.Space.s {
        didSet { needsLayout = true }
    }

    /// A tab's height, centred in the band.
    static let tabHeight = 28.0

    private var row: TabRow {
        TabRow(tabs: tabs.count, selected: selectedIndex, width: Double(bounds.width) - leading)
    }

    override public func layout() {
        super.layout()
        let row = self.row
        var x = leading
        for (index, tab) in tabs.enumerated() {
            tab.isHidden = !showsTabs || !row.shows(index)
            guard !tab.isHidden else { continue }
            let height = min(Self.tabHeight, max(0, Double(bounds.height) - 8))
            if !tab.dragging { tab.frame = CGRect(x: x, y: (Double(bounds.height) - height) / 2, width: row.width, height: height) }
            x += row.width + TabRow.gap
        }
        add.frame = CGRect(x: x, y: (Double(bounds.height) - TabRow.addSide) / 2, width: TabRow.addSide, height: TabRow.addSide)
        add.isHidden = !showsTabs
    }

    private func makeTab(_ id: TabID) -> TabButton {
        let tab = TabButton(id: id, look: look)
        tab.onSelect = { [weak self] in self?.onSelect(id) }
        tab.onClose = { [weak self] in self?.onClose(id) }
        tab.onRename = { [weak self] name in self?.onRename(id, name) }
        tab.onDrop = { [weak self] tab in self?.drop(tab) }
        tab.onMenu = { [weak self] in self?.onMenu(id) }
        addSubview(tab)
        return tab
    }

    /// A dragged tab let go: its place is where its middle is.
    private func drop(_ tab: TabButton) {
        let row = self.row
        let place = Int(((Double(tab.frame.midX) - leading) / (row.width + TabRow.gap)).rounded(.down))
        tab.dragging = false
        onMove(tab.id, row.first + min(max(0, place), row.shown - 1))
        needsLayout = true
    }

    @objc private func addTab() {
        onNew()
    }

    private func paint() {
        layer?.backgroundColor = showsTabs ? NSColor(look.chrome).cgColor : NSColor.clear.cgColor
        add.paint(look)
        tabs.forEach { $0.look = look }
    }
}

@MainActor
public final class TabButton: NSView, NSTextFieldDelegate {
    public let id: TabID
    public let label = NSTextField(labelWithString: "")
    public let close: RingedButton
    public private(set) var isSelected = false
    public private(set) var field: NSTextField?
    var onSelect: @MainActor () -> Void = {}
    var onClose: @MainActor () -> Void = {}
    var onRename: @MainActor (String?) -> Void = { _ in }
    var onDrop: @MainActor (TabButton) -> Void = { _ in }
    var onMenu: @MainActor () -> NSMenu? = { nil }
    var dragging = false
    var look: Look {
        didSet { paint() }
    }

    private let zoomMark = NSImageView(image: TabButton.symbol("arrow.up.left.and.arrow.down.right", accessibility: "Zoomed"))
    private var hovering = false
    private var grabbedAt: (pointer: Double, origin: Double)?
    private var cancelled = false

    init(id: TabID, look: Look) {
        self.id = id
        self.look = look
        close = RingedButton(Self.symbol("xmark", accessibility: "Close Tab"), tip: "Close Tab")
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        label.font = .systemFont(ofSize: Look.Step.callout.points)
        label.lineBreakMode = .byTruncatingMiddle
        label.alignment = .center
        close.target = self
        close.action = #selector(closeTab)
        for view in [label, close, zoomMark] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            close.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 4), close.centerYAnchor.constraint(equalTo: centerYAnchor),
            close.widthAnchor.constraint(equalToConstant: 16),
            zoomMark.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4), zoomMark.centerYAnchor.constraint(equalTo: centerYAnchor),
            zoomMark.widthAnchor.constraint(equalToConstant: 16),
            label.centerXAnchor.constraint(equalTo: centerXAnchor), label.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: Self.titleInset),
            label.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -Self.titleInset),
        ])
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setAccessibilityElement(true)
        setAccessibilityRole(.radioButton)
        setAccessibilitySubrole(Self.tabButton)
        setAccessibilityCustomActions([
            NSAccessibilityCustomAction(name: "Close Tab") { [weak self] in MainActor.assumeIsolated { self?.onClose() } != nil },
            NSAccessibilityCustomAction(name: "Rename") { [weak self] in MainActor.assumeIsolated { self?.rename() } != nil },
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The title's inset on both sides, room for the close button on the leading edge and the zoom
    /// mark on the trailing one, so the title stays centred in the tab whichever shows: the model
    /// of macOS's own window tabs, which Ghostty uses and Safari draws.
    public static let titleInset = 24.0

    /// AppKit's subrole for a tab (`NSAccessibilityTabButtonSubrole`, `AXTabButton`).
    public static let tabButton = NSAccessibility.Subrole(rawValue: "AXTabButton")

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

    override public func accessibilityPerformPress() -> Bool {
        onSelect()
        return true
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

    /// A right-click or a control-click offers the tab's menu, the tab left as it is (selected or
    /// not); none while renaming.
    override public func menu(for event: NSEvent) -> NSMenu? {
        guard field == nil, event.type == .rightMouseDown || event.modifierFlags.contains(.control) else { return nil }
        return onMenu()
    }

    /// The title becomes a field: Return, a click elsewhere or another tab selected names the tab
    /// what it holds (nothing clears the name); Escape leaves the name as it was.
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
        cancelled = false
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
            endRename()
            return true
        case #selector(NSResponder.cancelOperation(_:)):
            cancelled = true
            endRename()
            return true
        default:
            return false
        }
    }

    public func controlTextDidEndEditing(_: Notification) {
        endRename()
    }

    func endRename() {
        guard let field else { return }
        self.field = nil
        field.removeFromSuperview()
        onRename(cancelled ? nil : field.stringValue)
    }

    private func paint() {
        let fill = isSelected ? NSColor(look.fillSelected) : hovering ? NSColor(look.fillHover) : .clear
        layer?.backgroundColor = fill.cgColor
        label.textColor = NSColor(isSelected ? look.text : look.textSecondary)
        close.paint(look)
        zoomMark.contentTintColor = NSColor(look.textSecondary)
        close.isHidden = !hovering
        field?.textColor = NSColor(look.text)
        field?.backgroundColor = NSColor(look.surface)
        field?.layer?.borderColor = NSColor(look.accent).cgColor
    }
}

/// A strip button with no bezel whose keyboard focus (Full Keyboard Access) is a 2 pt ring in the
/// accent, never the system's ring.
@MainActor
public final class RingedButton: NSButton {
    init(_ symbol: NSImage, tip: String) {
        super.init(frame: .zero)
        image = symbol
        imagePosition = .imageOnly
        isBordered = false
        focusRingType = .none
        toolTip = tip
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius / 2
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func paint(_ look: Look) {
        contentTintColor = NSColor(look.textSecondary)
        layer?.borderColor = NSColor(look.accent).cgColor
    }

    override public func becomeFirstResponder() -> Bool {
        let became = super.becomeFirstResponder()
        if became { layer?.borderWidth = 2 }
        return became
    }

    override public func resignFirstResponder() -> Bool {
        let resigned = super.resignFirstResponder()
        if resigned { layer?.borderWidth = 0 }
        return resigned
    }
}
