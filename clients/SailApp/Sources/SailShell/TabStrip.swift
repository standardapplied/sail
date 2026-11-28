import AppKit
import SailControls
import SailLook
import SailTerminalView
import SailWorkbench

/// The tabs in the title bar's row, laid out by ``TabRow``. A tab never moves the window; the
/// strip's empty part takes no mouse event of its own, so in the title bar's accessory the title
/// bar's own view gets it: dragging, and the double-click System Settings names. With the tabs not
/// shown (another pane selected) the strip stays, empty and clear, so the row stays the title bar's.
/// Past what fits, a sideways scroll moves the tabs a tab a step, an edge with tabs past it fades
/// over the next tab, and the count at the strip's end lists every tab.
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
    /// The menu the count opens: every tab.
    package var onCount: @MainActor () -> NSMenu = { NSMenu() }

    var look: Look {
        didSet { paint() }
    }

    public private(set) var tabs: [TabButton] = []
    public let add: RingedButton
    /// The number of tabs and a chevron, shown while not every tab is.
    public let count: RingedButton
    /// The room the tabs are drawn in, which cuts the tab past a fading edge.
    let lane = NSView()
    let fades = (before: StripFade(), after: StripFade())
    private let mono: LabelFont

    var showsTabs = true {
        didSet {
            needsLayout = true
            paint()
        }
    }

    public var isRenaming: Bool {
        tabs.contains { $0.field != nil }
    }

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        add = RingedButton(symbol: "plus", tip: "New Tab", look: look)
        count = RingedButton(symbol: "chevron.down", tip: "All Tabs", look: look)
        super.init(frame: NSRect(x: 0, y: 0, width: 600, height: Self.tabHeight))
        wantsLayer = true
        lane.wantsLayer = true
        lane.layer?.masksToBounds = true
        add.target = self
        add.action = #selector(addTab)
        count.target = self
        count.action = #selector(openCount)
        count.imagePosition = .imageTrailing
        for view in [lane, fades.before, fades.after, add, count] as [NSView] {
            addSubview(view)
        }
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

    private var rightToLeft: Bool {
        userInterfaceLayoutDirection == .rightToLeft
    }

    /// The left edge of a frame `width` wide whose leading edge is `x` from the leading edge of a
    /// room `room` wide: right to left the tabs run from the strip's right edge, next to the window
    /// buttons AppKit put there.
    private func placed(_ x: Double, width: Double, in room: Double? = nil) -> Double {
        rightToLeft ? (room ?? Double(bounds.width)) - x - width : x
    }

    func show(_ items: [Item], selected: TabID?) {
        let held = Dictionary(uniqueKeysWithValues: tabs.map { ($0.id, $0) })
        let index = items.firstIndex { $0.id == selected }
        if selected != selectedID || index != selectedIndex || items.count != tabs.count { revealing = true }
        tabs = items.map { item in
            let tab = held[item.id] ?? makeTab(item.id)
            tab.show(item, selected: item.id == selected, look: look)
            return tab
        }
        for (id, tab) in held where !items.contains(where: { $0.id == id }) {
            tab.removeFromSuperview()
        }
        selectedID = selected
        selectedIndex = index
        count.attributedTitle = countTitle
        setAccessibilityChildren(tabs)
        needsLayout = true
    }

    private var selectedID: TabID?
    private var selectedIndex: Int?
    /// The first tab asked to be shown whole: where a scroll left the strip.
    private var scrolled = 0
    /// The selection, the tabs or the width changed since the last layout: the selected tab is
    /// brought into view, the strip moved the least.
    private var revealing = true
    /// A trackpad's travel since its last step.
    private var travelled = 0.0

    /// The strip's own empty space before the first tab, the title bar's to drag and double-click:
    /// what the room after the window buttons leaves of it; none right to left, where the strip
    /// ends that room before the buttons.
    var leading = Look.Space.s {
        didSet { needsLayout = true }
    }

    /// A tab's height, centred in the band.
    static let tabHeight = 26.0

    package var row: TabRow {
        TabRow(tabs: tabs.count, width: Double(bounds.width) - leading, first: scrolled)
    }

    override public func setFrameSize(_ size: NSSize) {
        if size.width != frame.width { revealing = true }
        super.setFrameSize(size)
    }

    override public func layout() {
        super.layout()
        if revealing {
            scrolled = row.revealing(selectedIndex).first
            revealing = false
        }
        let row = self.row, height = Double(bounds.height)
        lane.frame = CGRect(x: placed(leading, width: row.room), y: 0, width: row.room, height: height)
        for (index, tab) in tabs.enumerated() {
            let start = row.start(of: index)
            tab.isHidden = !showsTabs || start == nil
            guard let start, !tab.isHidden, !tab.dragging else { continue }
            let tall = min(Self.tabHeight, max(0, height - 8))
            tab.frame = CGRect(x: placed(start, width: row.width, in: row.room), y: (height - tall) / 2, width: row.width, height: tall)
        }
        add.frame = CGRect(x: placed(leading + row.add, width: TabRow.addSide), y: (height - TabRow.addSide) / 2, width: TabRow.addSide, height: TabRow.addSide)
        add.isHidden = !showsTabs
        let countWidth = TabRow.countSide - Look.Space.s
        count.frame = CGRect(x: placed(Double(bounds.width) - TabRow.countSide, width: countWidth), y: (height - TabRow.addSide) / 2, width: countWidth, height: TabRow.addSide)
        count.isHidden = !showsTabs || !row.scrolls
        fades.before.frame = CGRect(x: placed(leading, width: TabRow.fade), y: 0, width: TabRow.fade, height: height)
        fades.after.frame = CGRect(x: placed(leading + row.room - TabRow.fade, width: TabRow.fade), y: 0, width: TabRow.fade, height: height)
        fades.before.isHidden = !showsTabs || !row.fadesBefore
        fades.after.isHidden = !showsTabs || !row.fadesAfter
        fades.before.solidOnTheLeft = !rightToLeft
        fades.after.solidOnTheLeft = rightToLeft
    }

    /// A sideways scroll, a trackpad's or a mouse's with Shift, moves the tabs a tab a step and
    /// selects none: a mouse's wheel a step a notch, a trackpad a step a tab's width of travel.
    override public func scrollWheel(with event: NSEvent) {
        let row = self.row, along = Double(event.scrollingDeltaX)
        guard showsTabs, row.scrolls, abs(along) > abs(Double(event.scrollingDeltaY)) else { return super.scrollWheel(with: event) }
        let steps: Int
        if event.hasPreciseScrollingDeltas {
            if event.phase == .began { travelled = 0 }
            travelled += along
            steps = Int(travelled / (row.width + TabRow.gap))
            travelled -= Double(steps) * (row.width + TabRow.gap)
        } else {
            steps = along > 0 ? 1 : -1
        }
        guard steps != 0 else { return }
        scrolled = row.first + (rightToLeft ? steps : -steps)
        needsLayout = true
    }

    private func makeTab(_ id: TabID) -> TabButton {
        let tab = TabButton(id: id, look: look)
        tab.onSelect = { [weak self] in self?.onSelect(id) }
        tab.onClose = { [weak self] in self?.onClose(id) }
        tab.onRename = { [weak self] name in self?.onRename(id, name) }
        tab.onDrop = { [weak self] tab in self?.drop(tab) }
        tab.onMenu = { [weak self] in self?.onMenu(id) }
        lane.addSubview(tab)
        return tab
    }

    /// A dragged tab let go: its place is where its middle is among the tabs shown whole.
    private func drop(_ tab: TabButton) {
        let row = self.row
        let middle = placed(Double(tab.frame.midX), width: 0, in: row.room)
        let place = Int(((middle - row.inset) / (row.width + TabRow.gap)).rounded(.down))
        tab.dragging = false
        onMove(tab.id, row.first + min(max(0, place), row.shown - 1))
        needsLayout = true
    }

    @objc private func addTab() {
        onNew()
    }

    /// The count's menu, hung from the count's trailing corner 2 pt under it, the count washed
    /// while it is open.
    @objc private func openCount() {
        let menu = onCount()
        menu.userInterfaceLayoutDirection = userInterfaceLayoutDirection
        let box = count.bounds, width = Double(menu.size.width)
        let x = rightToLeft ? Double(box.minX) + width : Double(box.maxX) - width
        count.layer?.backgroundColor = NSColor(Tint(look.chromeWash)).cgColor
        menu.popUp(positioning: nil, at: NSPoint(x: x, y: Double(MenuOpener.corner(under: count).y)), in: count)
        count.layer?.backgroundColor = NSColor.clear.cgColor
    }

    /// The number of tabs, in the strip's mono label.
    private var countTitle: NSAttributedString {
        NSAttributedString(string: String(tabs.count), attributes: [.font: mono.font(Look.Step.micro.points) as NSFont, .foregroundColor: NSColor(look.textSecondary)])
    }

    private func paint() {
        layer?.backgroundColor = showsTabs ? NSColor(look.chrome).cgColor : NSColor.clear.cgColor
        add.paint(look)
        count.paint(look)
        count.attributedTitle = countTitle
        fades.before.chrome = look.chrome
        fades.after.chrome = look.chrome
        tabs.forEach { $0.look = look }
    }
}

/// The fade over an edge of the strip with tabs past it: the chrome, solid at the edge and gone at
/// its inner side. It takes no click: one on it reaches the tab under it.
@MainActor
final class StripFade: NSView {
    private let gradient = CAGradientLayer()

    var chrome = Look.light.chrome {
        didSet { draw() }
    }

    var solidOnTheLeft = true {
        didSet { if solidOnTheLeft != oldValue { draw() } }
    }

    init() {
        super.init(frame: .zero)
        layer = gradient
        wantsLayer = true
        gradient.locations = [0, 0.4, 1]
        draw()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func hitTest(_: NSPoint) -> NSView? {
        nil
    }

    /// Its colour at its left edge and at its right: what the tests read.
    var solid: (left: CGColor?, right: CGColor?) {
        let colours = gradient.colors as? [CGColor] ?? []
        let left = solidOnTheLeft ? colours.first : colours.last
        let right = solidOnTheLeft ? colours.last : colours.first
        return (left, right)
    }

    private func draw() {
        let solid = NSColor(chrome).cgColor, clear = NSColor(chrome).withAlphaComponent(0).cgColor
        gradient.colors = [solid, solid, clear]
        gradient.startPoint = CGPoint(x: solidOnTheLeft ? 0 : 1, y: 0.5)
        gradient.endPoint = CGPoint(x: solidOnTheLeft ? 1 : 0, y: 0.5)
    }
}

/// A tab's mark as its menu items draw it: a 10 pt slot for its state's mark, empty for now, then
/// its form's icon, one template image AppKit inks as the menu's text.
@MainActor
enum TabMark {
    static let slot = 10.0
    static let terminal = listed("terminal")

    private static func listed(_ symbol: String) -> NSImage {
        let configuration = NSImage.SymbolConfiguration(pointSize: Look.Step.callout.points, weight: .regular)
        let icon = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(configuration) ?? NSImage()
        let size = NSSize(width: slot + Look.Space.s + Double(icon.size.width), height: max(Double(icon.size.height), slot))
        let image = NSImage(size: size, flipped: false) { _ in
            icon.draw(in: NSRect(x: slot + Look.Space.s, y: (Double(size.height) - Double(icon.size.height)) / 2, width: Double(icon.size.width), height: Double(icon.size.height)))
            return true
        }
        image.isTemplate = true
        return image
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
