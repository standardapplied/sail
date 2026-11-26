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
        /// A terminal in it may still be running: its end was not confirmed.
        public var unended = false
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
        add = RingedButton(symbol: "plus", tip: "New Tab", look: look)
        super.init(frame: NSRect(x: 0, y: 0, width: 600, height: Self.tabHeight))
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

    override public var userInterfaceLayoutDirection: NSUserInterfaceLayoutDirection {
        didSet { if userInterfaceLayoutDirection != oldValue { needsLayout = true } }
    }

    /// The left edge of a frame `width` wide whose leading edge is `x` from the strip's: right to
    /// left the tabs run from the strip's right edge, next to the window buttons AppKit put there.
    private func placed(_ x: Double, width: Double) -> Double {
        userInterfaceLayoutDirection == .rightToLeft ? Double(bounds.width) - x - width : x
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
    /// what the room after the window buttons leaves of it; none right to left, where the strip
    /// ends that room before the buttons.
    var leading = Look.Space.s {
        didSet { needsLayout = true }
    }

    /// A tab's height, centred in the band.
    static let tabHeight = 26.0

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
            if !tab.dragging { tab.frame = CGRect(x: placed(x, width: row.width), y: (Double(bounds.height) - height) / 2, width: row.width, height: height) }
            x += row.width + TabRow.gap
        }
        add.frame = CGRect(x: placed(x, width: TabRow.addSide), y: (Double(bounds.height) - TabRow.addSide) / 2, width: TabRow.addSide, height: TabRow.addSide)
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

    /// A dragged tab let go: its place is where its middle is, from the strip's leading edge.
    private func drop(_ tab: TabButton) {
        let row = self.row
        let middle = placed(Double(tab.frame.midX), width: 0)
        let place = Int(((middle - leading) / (row.width + TabRow.gap)).rounded(.down))
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

    let zoomMark = NSImageView()
    /// Marks a tab holding a terminal whose end was not confirmed, where the zoom mark goes.
    let unendedMark = NSImageView()

    /// Whether the tab shows it holds a terminal that may still be running.
    package var showsUnended: Bool {
        !unendedMark.isHidden
    }
    /// The selected tab's mark: a 2 pt bar along its bottom edge in `accent`, as the rail's
    /// bar marks its chosen item and Mast underlines its active tab.
    let bar = NSView()
    private var hovering = false
    private var grabbedAt: (pointer: Double, origin: Double)?
    private var cancelled = false

    init(id: TabID, look: Look) {
        self.id = id
        self.look = look
        close = RingedButton(symbol: "xmark", tip: "Close Tab", look: look)
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        label.font = .systemFont(ofSize: Look.Step.callout.points)
        label.lineBreakMode = .byTruncatingMiddle
        label.alignment = .center
        close.target = self
        close.action = #selector(closeTab)
        bar.wantsLayer = true
        for view in [label, close, zoomMark, unendedMark, bar] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Look.contentRadius),
            bar.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -Look.contentRadius),
            bar.bottomAnchor.constraint(equalTo: bottomAnchor), bar.heightAnchor.constraint(equalToConstant: Self.barHeight),
            close.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 4), close.centerYAnchor.constraint(equalTo: centerYAnchor),
            close.widthAnchor.constraint(equalToConstant: 16),
            zoomMark.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4), zoomMark.centerYAnchor.constraint(equalTo: centerYAnchor),
            zoomMark.widthAnchor.constraint(equalToConstant: 16),
            unendedMark.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4), unendedMark.centerYAnchor.constraint(equalTo: centerYAnchor),
            unendedMark.widthAnchor.constraint(equalToConstant: 16),
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
    /// The selected tab's bar, as thick as the rail's.
    static let barHeight = 2.0

    /// AppKit's subrole for a tab (`NSAccessibilityTabButtonSubrole`, `AXTabButton`).
    public static let tabButton = NSAccessibility.Subrole(rawValue: "AXTabButton")

    /// A strip symbol at its size and weight, in `colour` (``SymbolInk``).
    static func symbol(_ name: String, accessibility: String, colour: Tint) -> NSImage {
        SymbolInk.image(name, points: 11, weight: .medium, colour: colour, accessibility: accessibility)
    }

    func show(_ item: TabStrip.Item, selected: Bool, look: Look) {
        label.stringValue = item.title
        toolTip = item.title
        unendedMark.isHidden = !item.unended
        zoomMark.isHidden = !item.zoomed || item.unended
        isSelected = selected
        setAccessibilityLabel(item.unended ? "\(item.title), a terminal in it may still be running" : item.title)
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

    /// The title becomes a field where it is: the same font, baseline and centring, in the tab's own
    /// pill ringed in the accent. Return, a click elsewhere or another tab selected names the tab
    /// what it holds (nothing clears the name); Escape leaves the name as it was.
    public func rename() {
        guard field == nil else { return }
        layoutSubtreeIfNeeded()
        let field = NSTextField(string: label.stringValue)
        field.font = label.font
        field.alignment = .center
        field.focusRingType = .none
        field.isBezeled = false
        field.isBordered = false
        field.drawsBackground = false
        field.usesSingleLineMode = true
        field.cell?.isScrollable = true
        field.delegate = self
        field.frame = NSRect(x: Self.titleInset, y: label.frame.minY, width: max(0, bounds.width - 2 * Self.titleInset), height: label.frame.height)
        self.field = field
        cancelled = false
        label.isHidden = true
        paint()
        addSubview(field)
        window?.makeFirstResponder(field)
        if let editor = field.currentEditor() as? NSTextView {
            editor.selectedTextAttributes = [.backgroundColor: NSColor(look.textSelection), .foregroundColor: NSColor(look.text)]
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

    /// Editing ended with Tab or Return: the name is kept. With no key (the field lost the keys),
    /// what happens is decided once AppKit is done moving them, in ``keepOrEnd()``.
    public func controlTextDidEndEditing(_ note: Notification) {
        guard field != nil else { return }
        guard note.userInfo?[NSText.movementUserInfoKey] as? Int == NSTextMovement.other.rawValue else { return endRename() }
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.keepOrEnd() }
        }
    }

    /// The field lost the keys. Taken by something (a click on a terminal, another tab selected),
    /// the name typed is kept; taken by nothing (the title bar laid out again, as on a change of
    /// appearance, leaves the window itself first responder), the field takes them back and the
    /// rename goes on, so Escape still restores the name.
    private func keepOrEnd() {
        guard let field else { return }
        guard let window = field.window, window.firstResponder === window else { return endRename() }
        window.makeFirstResponder(field)
        (field.currentEditor() as? NSTextView)?.moveToEndOfDocument(nil)
    }

    func endRename() {
        guard let field else { return }
        self.field = nil
        field.removeFromSuperview()
        label.isHidden = false
        paint()
        onRename(cancelled ? nil : field.stringValue)
    }

    private func paint() {
        let renaming = field != nil
        let ink = drawnInk
        layer?.backgroundColor = ink.fill.map { NSColor($0).cgColor } ?? NSColor.clear.cgColor
        layer?.borderWidth = renaming ? 2 : 0
        layer?.borderColor = NSColor(ink.edge).cgColor
        bar.layer?.backgroundColor = NSColor(ink.bar).cgColor
        bar.isHidden = !isSelected || renaming
        label.textColor = NSColor(isSelected ? look.text : look.textSecondary)
        let font = NSFont.systemFont(ofSize: Look.Step.callout.points, weight: isSelected ? .semibold : .regular)
        if label.font != font { label.font = font }
        close.paint(look)
        zoomMark.image = Self.symbol("arrow.up.left.and.arrow.down.right", accessibility: "Zoomed", colour: look.textSecondary)
        unendedMark.image = Self.symbol("exclamationmark.triangle", accessibility: "May still be running", colour: Tint(look.warning))
        close.isHidden = !hovering || renaming
        field?.textColor = NSColor(look.text)
    }
}

/// A strip button with no bezel whose keyboard focus (Full Keyboard Access) is a 2 pt ring in the
/// accent, never the system's ring.
@MainActor
public final class RingedButton: NSButton {
    private let symbol: String

    /// A button showing the strip symbol `symbol`, coloured for `look`.
    init(symbol: String, tip: String, look: Look) {
        self.symbol = symbol
        super.init(frame: .zero)
        image = TabButton.symbol(symbol, accessibility: tip, colour: look.textSecondary)
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
        image = TabButton.symbol(symbol, accessibility: toolTip ?? symbol, colour: look.textSecondary)
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

extension TabButton: Marked {
    /// What the tab draws now: the surface while renamed, the ink pill when chosen, the hover fill
    /// under the pointer; the rename's ring and the chosen bar in the accent.
    var drawnInk: (fill: Tint?, edge: Tint, bar: Tint) {
        let renaming = field != nil
        return (renaming ? Tint(look.surface) : nil, Tint(look.accent), Tint(look.accent))
    }

    package var drawnMarks: [Mark] {
        let ink = drawnInk, renaming = field != nil
        let caret = renaming ? look.marks(line: Tint(look.accent)) : []
        return look.marks(fill: ink.fill, area: Double(bounds.width * bounds.height)) + (renaming ? look.marks(line: ink.edge) : []) + caret
            + (isSelected && !renaming ? look.marks(line: ink.bar) : [])
    }
}

extension RingedButton: Marked {
    /// Its 2 pt accent ring while it holds the keys.
    package var drawnMarks: [Mark] {
        layer?.borderWidth == 2 ? [.accentLine] : []
    }
}
