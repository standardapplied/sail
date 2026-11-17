import AppKit
import SailLook

/// A list of rows in one of the two row styles (`RowStyle`): group heads and items. It is its own
/// data source and delegate, and owns the row keys: ↑↓ move the choice (group heads cannot be
/// chosen), Home and End jump to the first and last item, type-select finds a name, Return presses
/// the chosen row's first action, and every other action's key works on the chosen row. A row
/// offers all its actions to VoiceOver as custom actions and in its context menu.
@MainActor
public final class SailList: NSTableView, NSTableViewDataSource, NSTableViewDelegate {
    /// One thing a row does; on the row as a small button, or only in its menu (⋯ and right-click).
    public struct Action {
        public let title: String
        public let key: String
        public let modifiers: NSEvent.ModifierFlags
        public let onRow: Bool
        public let run: @MainActor () -> Void

        public init(_ title: String, key: String = "", modifiers: NSEvent.ModifierFlags = [], onRow: Bool = true, run: @escaping @MainActor () -> Void) {
            self.title = title
            self.key = key
            self.modifiers = modifiers
            self.onRow = onRow
            self.run = run
        }
    }

    /// An item: its id, the name type-select finds, what VoiceOver reads, what it shows, what it does.
    public struct Item {
        public let id: String
        public let name: String
        public let voice: String
        public let content: NSView
        public let actions: [Action]

        public init(id: String, name: String, voice: String, content: NSView, actions: [Action] = []) {
            self.id = id
            self.name = name
            self.voice = voice
            self.content = content
            self.actions = actions
        }
    }

    public enum Entry {
        case head(String, count: Int)
        case item(Item)
    }

    public var look: Look {
        didSet { reloadData() }
    }

    public let rowStyle: RowStyle
    public var onChoose: @MainActor (String?) -> Void = { _ in }
    private let mono: LabelFont

    public var entries: [Entry] = [] {
        didSet { reloadData() }
    }

    public init(rowStyle: RowStyle, look: Look, mono: LabelFont) {
        self.rowStyle = rowStyle
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("rows"))
        column.resizingMask = .autoresizingMask
        addTableColumn(column)
        columnAutoresizingStyle = .uniformColumnAutoresizingStyle
        headerView = nil
        style = .plain
        intercellSpacing = .zero
        backgroundColor = .clear
        focusRingType = .none
        floatsGroupRows = false
        allowsTypeSelect = true
        gridStyleMask = []
        dataSource = self
        delegate = self
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        sizeLastColumnToFit()
    }

    func item(at row: Int) -> Item? {
        guard entries.indices.contains(row), case let .item(item) = entries[row] else { return nil }
        return item
    }

    /// The chosen item's id.
    public var chosen: String? {
        item(at: selectedRow)?.id
    }

    /// Chooses the item of `id`, as a pane restoring its last choice does; nil chooses nothing.
    public func choose(_ id: String?) {
        guard let row = entries.indices.first(where: { item(at: $0)?.id == id }) else { return deselectAll(nil) }
        selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
        scrollRowToVisible(row)
    }

    // MARK: Keys

    override public func keyDown(with event: NSEvent) {
        if !act(on: event) { super.keyDown(with: event) }
    }

    override public func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard window?.firstResponder === self, act(on: event) else { return super.performKeyEquivalent(with: event) }
        return true
    }

    /// Return, Home, End, or an action's key on the chosen row; false when the key is none of them.
    func act(on event: NSEvent) -> Bool {
        let items = entries.indices.filter { item(at: $0) != nil }
        switch event.keyCode {
        case 115, 119:
            guard let row = event.keyCode == 115 ? items.first : items.last else { return false }
            selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
            scrollRowToVisible(row)
            return true
        case 36, 76:
            guard let primary = item(at: selectedRow)?.actions.first(where: { $0.onRow }) else { return false }
            primary.run()
            return true
        default:
            let pressed = event.charactersIgnoringModifiers?.lowercased() ?? ""
            let held = event.modifierFlags.intersection([.command, .option, .control, .shift])
            guard !pressed.isEmpty, let action = item(at: selectedRow)?.actions.first(where: { $0.key == pressed && $0.modifiers == held }) else { return false }
            action.run()
            return true
        }
    }

    // MARK: Data source and delegate

    public func numberOfRows(in _: NSTableView) -> Int {
        entries.count
    }

    public func tableView(_: NSTableView, isGroupRow row: Int) -> Bool {
        item(at: row) == nil
    }

    public func tableView(_: NSTableView, shouldSelectRow row: Int) -> Bool {
        item(at: row) != nil
    }

    public func tableView(_: NSTableView, heightOfRow row: Int) -> CGFloat {
        let hairline = rowStyle.hairline && row > 0 ? 1.0 : 0
        guard item(at: row) != nil else { return RowStyle.groupHeight + hairline }
        return rowStyle.height + rowStyle.spacing + (rowStyle.hairline ? 1 : 0)
    }

    public func tableView(_: NSTableView, typeSelectStringFor _: NSTableColumn?, row: Int) -> String? {
        item(at: row)?.name
    }

    public func tableView(_: NSTableView, rowViewForRow row: Int) -> NSTableRowView? {
        SailRowView(rowStyle: rowStyle, look: look, item: item(at: row), first: row == 0)
    }

    public func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
        guard entries.indices.contains(row) else { return nil }
        switch entries[row] {
        case let .head(title, count): return HeadCell(title, count: count, look: look, mono: mono, hairline: rowStyle.hairline && row > 0)
        case let .item(item): return RowCell(item, rowStyle: rowStyle, look: look, mono: mono)
        }
    }

    public func tableViewSelectionDidChange(_: Notification) {
        onChoose(chosen)
    }
}

/// A row as the look draws it (`RowPaint`): the fill, the accent bar
/// at the list's leading edge and the hairline above a list's row; its actions shown while it is
/// hovered or chosen. VoiceOver reads its words and offers its actions.
@MainActor
final class SailRowView: NSTableRowView {
    let rowStyle: RowStyle
    let look: Look
    let item: SailList.Item?
    private let first: Bool
    private var hoverArea: NSTrackingArea?

    var hovered = false {
        didSet { refresh() }
    }

    init(rowStyle: RowStyle, look: Look, item: SailList.Item?, first: Bool) {
        self.rowStyle = rowStyle
        self.look = look
        self.item = item
        self.first = first
        super.init(frame: .zero)
        guard let item else { return }
        setAccessibilityLabel(item.voice)
        setAccessibilityCustomActions(item.actions.map { action in
            let run = Run(action)
            return NSAccessibilityCustomAction(name: action.title) {
                MainActor.assumeIsolated { run.go(nil) }
                return true
            }
        })
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    var paint: RowPaint {
        RowPaint(RowState(chosen: isSelected, emphasized: isEmphasized, hovered: hovered), look: look)
    }

    override var isSelected: Bool {
        didSet { refresh() }
    }

    override var isEmphasized: Bool {
        didSet { refresh() }
    }

    override var interiorBackgroundStyle: NSView.BackgroundStyle {
        .normal
    }

    override var isFlipped: Bool {
        true
    }

    private func refresh() {
        let paint = self.paint
        if let cell = view(atColumn: 0) as? RowCell {
            cell.show(paint.actions, chosen: isSelected, keys: isEmphasized)
        }
        needsDisplay = true
    }

    override func didAddSubview(_ subview: NSView) {
        super.didAddSubview(subview)
        refresh()
    }

    /// The top of the row's own area, under a list's hairline.
    private var top: Double {
        rowStyle.hairline && !(item == nil && first) ? 1 : 0
    }

    override func drawBackground(in _: NSRect) {
        let content = NSRect(x: 0, y: top, width: Double(bounds.width), height: Double(bounds.height) - top)
        if top > 0 {
            NSColor(look.gridLine).setFill()
            NSBezierPath(rect: NSRect(x: 0, y: 0, width: Double(bounds.width), height: 1)).fill()
        }
        guard item != nil else {
            NSColor(look.fillHover).setFill()
            return NSBezierPath(rect: content).fill()
        }
        let paint = self.paint, area = content.insetBy(dx: rowStyle.margin, dy: rowStyle.spacing / 2)
        if let fill = paint.fill {
            NSColor(fill).setFill()
            NSBezierPath(roundedRect: area, xRadius: rowStyle.radius, yRadius: rowStyle.radius).fill()
        }
        guard paint.bar else { return }
        NSColor(Tint(look.accent)).setFill()
        let x = userInterfaceLayoutDirection == .rightToLeft ? Double(bounds.width) - RowStyle.barWidth : 0
        NSBezierPath(rect: NSRect(x: x, y: Double(content.midY) - rowStyle.bar / 2, width: RowStyle.barWidth, height: rowStyle.bar)).fill()
    }

    override func drawSelection(in _: NSRect) {}

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let hoverArea { removeTrackingArea(hoverArea) }
        let area = NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self)
        addTrackingArea(area)
        hoverArea = area
    }

    override func mouseEntered(with _: NSEvent) {
        hovered = true
    }

    override func mouseExited(with _: NSEvent) {
        hovered = false
    }

    override func menu(for _: NSEvent) -> NSMenu? {
        item.map { RowCell.menu($0.actions) }
    }
}

/// A group head's words: mono capitals with the count beside them, 20 pt in.
@MainActor
final class HeadCell: NSView {
    private let words: NSAttributedString
    private let hairline: Bool

    init(_ title: String, count: Int, look: Look, mono: LabelFont, hairline: Bool) {
        let font = mono.font(Look.Step.caption.points) as NSFont
        let words = NSMutableAttributedString(attributedString: Draw.text(title, font, look.textTertiary, tracking: Look.Tracking.eyebrow, upper: true))
        words.append(Draw.text("  \(count)", font, Tint(look.textTertiary.rgb, alpha: look.textTertiary.alpha * 0.8), tracking: Look.Tracking.eyebrow))
        self.words = words
        self.hairline = hairline
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        setAccessibilityLabel("\(title), \(count)")
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_: NSRect) {
        let top = hairline ? 1.0 : 0, height = Double(words.size().height)
        let x = userInterfaceLayoutDirection == .rightToLeft ? Double(bounds.width) - RowStyle.list.leading - Double(words.size().width) : RowStyle.list.leading
        words.draw(at: NSPoint(x: x, y: top + (Double(bounds.height) - top - height) / 2))
    }
}

/// An item's cell: what the item shows, from the row's leading inset and stretched to its actions
/// (or to the trailing inset when it has none), and its actions at the trailing edge: the first
/// on-row action secondary, or primary with ⏎ while the row is chosen; the rest secondary; ⋯ for
/// the menu when some action lives only there.
@MainActor
final class RowCell: NSView {
    let content: NSView
    let actions = NSStackView()
    private let quiet: SailButton?
    private let loud: SailButton?
    private let runs: [Run]

    init(_ item: SailList.Item, rowStyle: RowStyle, look: Look, mono: LabelFont) {
        content = item.content
        let runs = item.actions.map { Run($0) }
        self.runs = runs
        var buttons: [SailButton] = []
        let onRow = runs.filter { $0.action.onRow }
        if let primary = onRow.first {
            let quiet = SailButton(primary.action.title, kind: .secondary, size: .small, look: look, target: primary, action: #selector(Run.go(_:)))
            let loud = SailButton(primary.action.title, kind: .primary, size: .small, look: look, target: primary, action: #selector(Run.go(_:)))
            loud.shortcut("\r", mono: mono)
            loud.keyEquivalent = ""
            self.quiet = quiet
            self.loud = loud
            buttons += [quiet, loud]
        } else {
            quiet = nil
            loud = nil
        }
        for other in onRow.dropFirst() {
            buttons.append(SailButton(other.action.title, kind: .secondary, size: .small, look: look, target: other, action: #selector(Run.go(_:))))
        }
        if rowStyle != .sidebar, runs.contains(where: { !$0.action.onRow }) {
            let more = SailButton(icon: .more, label: "More", size: .small, look: look, target: nil, action: nil)
            buttons.append(more)
            moreMenu = Self.menu(item.actions)
            more.target = MenuOpener.shared
            more.action = #selector(MenuOpener.open(_:))
            more.menu = moreMenu
        }
        super.init(frame: .zero)
        actions.setViews(buttons, in: .trailing)
        actions.spacing = 6
        actions.alphaValue = 0
        for view in [content, actions] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        let top = rowStyle.hairline ? 1.0 : 0, gap = buttons.isEmpty ? 0.0 : 8
        let stretched = content.trailingAnchor.constraint(equalTo: actions.leadingAnchor, constant: -gap)
        stretched.priority = .defaultHigh
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: leadingAnchor, constant: rowStyle.margin + rowStyle.leading),
            content.trailingAnchor.constraint(lessThanOrEqualTo: actions.leadingAnchor, constant: -gap), stretched,
            content.centerYAnchor.constraint(equalTo: centerYAnchor, constant: top / 2),
            actions.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -(rowStyle.margin + rowStyle.trailing)),
            actions.centerYAnchor.constraint(equalTo: centerYAnchor, constant: top / 2),
        ])
        show(false, chosen: false, keys: false)
    }

    private var moreMenu: NSMenu?

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The actions shown (over 120 ms, at once while motion is reduced) or gone; the primary loud
    /// while the row is chosen, and Return its key (shown as ⏎) only while the list holds the keys.
    func show(_ shown: Bool, chosen: Bool, keys: Bool) {
        quiet?.isHidden = chosen
        loud?.isHidden = !chosen
        loud?.keyEquivalent = chosen && keys ? "\r" : ""
        let alpha: CGFloat = shown ? 1 : 0
        guard actions.alphaValue != alpha else { return }
        guard !Motion.shared.reduced, window != nil else {
            actions.alphaValue = alpha
            return
        }
        NSAnimationContext.beginGrouping()
        NSAnimationContext.current.duration = Look.Timing.hover
        actions.animator().alphaValue = alpha
        NSAnimationContext.endGrouping()
    }

    /// Every action of a row, as its ⋯ and its right-click show them.
    static func menu(_ actions: [SailList.Action]) -> NSMenu {
        let menu = NSMenu()
        for action in actions {
            let run = Run(action)
            let entry = NSMenuItem(title: action.title, action: #selector(Run.go(_:)), keyEquivalent: action.key)
            entry.keyEquivalentModifierMask = action.modifiers
            entry.target = run
            entry.representedObject = run
            menu.addItem(entry)
        }
        return menu
    }
}

/// An action as a target: buttons and menu items send `go(_:)`.
@MainActor
final class Run: NSObject {
    let action: SailList.Action

    init(_ action: SailList.Action) {
        self.action = action
    }

    @objc func go(_: Any?) {
        action.run()
    }
}

/// Opens the menu a button carries, 2 pt under its box, as a pop-up does.
@MainActor
final class MenuOpener: NSObject {
    static let shared = MenuOpener()

    @objc func open(_ sender: NSButton) {
        sender.menu?.popUp(positioning: nil, at: Self.corner(under: sender), in: sender)
    }

    /// The menu's top left: the box's leading corner, 2 pt under it.
    static func corner(under button: NSView) -> NSPoint {
        let box = button.bounds, insets = button.alignmentRectInsets
        let bottom = button.isFlipped ? Double(box.maxY) - Double(insets.bottom) + 2 : Double(box.minY) + Double(insets.bottom) - 2
        return NSPoint(x: Double(box.minX) + Double(insets.left), y: bottom)
    }
}
