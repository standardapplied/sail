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
        /// It starts a group of its own in the menu, under a separator (Stop after Copy Name).
        public let startsGroup: Bool
        /// It destroys something: its menu item is drawn in the error ink (Delete).
        public let destructive: Bool
        public let run: @MainActor () -> Void

        public init(
            _ title: String, key: String = "", modifiers: NSEvent.ModifierFlags = [], onRow: Bool = true, startsGroup: Bool = false, destructive: Bool = false,
            run: @escaping @MainActor () -> Void
        ) {
            self.title = title
            self.key = key
            self.modifiers = modifiers
            self.onRow = onRow
            self.startsGroup = startsGroup
            self.destructive = destructive
            self.run = run
        }
    }

    /// An item: its id, the name type-select finds, what VoiceOver reads, what it shows, what it does.
    public struct Item {
        public let id: String
        public let name: String
        public let voice: String
        /// What it shows, made when its row is: the table makes rows only for what is on screen,
        /// so a long list drawn again builds a screenful, not every row.
        @MainActor public var content: NSView {
            make()
        }

        private let make: @MainActor () -> NSView
        public let actions: [Action]
        /// Words before its actions, shown with them (who may open it).
        public let note: String?
        /// Its own height, for a row taller than its style's (the create row with its refusal).
        public let height: Double?
        /// How it is drawn whatever its state: the create row, drawn chosen while its field holds the
        /// keys, and in the error's tint once refused.
        public let paint: RowPaint?
        /// Its content takes the whole row, past the actions column: a row that holds its own buttons.
        public let full: Bool
        /// A view after its note among its actions (a spinner).
        public let accessory: NSView?
        /// Words among the rows that cannot be chosen (a folder's refusal in a tree): the keys pass it.
        public let inert: Bool

        public init(
            id: String, name: String, voice: String, content: @autoclosure @escaping @MainActor () -> NSView, actions: [Action] = [], note: String? = nil, height: Double? = nil,
            paint: RowPaint? = nil, full: Bool = false, accessory: NSView? = nil, inert: Bool = false
        ) {
            self.id = id
            self.name = name
            self.voice = voice
            make = content
            self.actions = actions
            self.note = note
            self.height = height
            self.paint = paint
            self.full = full
            self.accessory = accessory
            self.inert = inert
        }
    }

    public enum Entry {
        /// A group head with its count; with none, its title alone (a list still loading).
        case head(String, count: Int?)
        case item(Item)
    }

    /// Its look: a change redraws the rows and never tells `onChoose`, as no one chose.
    public var look: Look {
        didSet {
            reloading = true
            reloadKeepingHover()
            reloading = false
        }
    }

    /// Where the pointer is in the window while the app is active and its window key; nil, not
    /// known. Tests place it (`pointer`).
    private var pointerNow: NSPoint? {
        if let pointer { return pointer() }
        guard let window, NSApplication.shared.isActive, window.isKeyWindow else { return nil }
        return window.mouseLocationOutsideOfEventStream
    }

    package var pointer: (@MainActor () -> NSPoint?)?

    /// Rows made, each a cell for a row on screen: a redraw makes a screenful, not every row.
    package private(set) var rowsMade = 0

    /// Reloads the rows with the one under the pointer drawn hovered, its actions shown: a new row
    /// does not know the pointer is over it until the pointer moves. While the app is active the
    /// row is found where the pointer is; otherwise the row hovered before is hovered again.
    private func reloadKeepingHover() {
        let hovered = (0 ..< numberOfRows).first { (rowView(atRow: $0, makeIfNecessary: false) as? SailRowView)?.hovered == true }.flatMap { item(at: $0)?.id }
        Tooltip.putAway()
        reloadData()
        let row: Int?
        if let point = pointerNow {
            let inList = convert(point, from: nil), content = window?.contentView
            let hit = content.flatMap { $0.hitTest($0.superview?.convert(point, from: nil) ?? point) }
            let under = visibleRect.contains(inList) && hit?.isDescendant(of: self) == true ? self.row(at: inList) : -1
            row = under >= 0 ? under : nil
        } else {
            row = hovered.flatMap { id in entries.indices.first { item(at: $0)?.id == id } }
        }
        guard let row, item(at: row) != nil else { return }
        (rowView(atRow: row, makeIfNecessary: true) as? SailRowView)?.hovered = true
    }

    public let rowStyle: RowStyle
    /// A width kept for every row's actions, so the rows' content lines up in columns whatever
    /// actions each shows; nil, the content runs to its row's actions.
    public var actionsColumn: Double?
    /// The chosen row's first action shows ⏎ while the list holds the keys; off, it shows none,
    /// and Return still presses it (a narrow row's actions column has no room for the keycap).
    public var returnKeyShown = true

    /// How far in from the list's leading edge the chosen row's bar starts: a hairline where the
    /// card's own edge is drawn over that edge (the panel at the card's leading edge, in dark), so
    /// the edge does not cover the bar's outer point.
    public var barInset = 0.0 {
        didSet { if barInset != oldValue { needsDisplay = true; enumerateAvailableRowViews { row, _ in row.needsDisplay = true } } }
    }
    public var onChoose: @MainActor (String?) -> Void = { _ in }
    /// A key the list's owner takes before the list does (a tree's → and ←): true, taken.
    package var onKey: (@MainActor (NSEvent) -> Bool)?
    private let mono: LabelFont

    /// The rows. A new set keeps the chosen item chosen by its id wherever it now is, and tells
    /// `onChoose` only when the choice really changed (its item went).
    public var entries: [Entry] = [] {
        willSet { kept = chosen }
        didSet {
            guard !applying else { return }
            fills += 1
            reloading = true
            reloadKeepingHover()
            select(kept)
            reloading = false
            if chosen != kept { onChoose(chosen) }
        }
    }

    /// The chosen item's id as the old rows had it, taken before they change.
    private var kept: String?
    private var reloading = false
    private var applying = false
    /// Times the rows were filled afresh, and rows `apply` inserted or made again: what the drive
    /// holds the rows made against, so only changed rows are ever made.
    package private(set) var fills = 0
    package private(set) var appliedRows = 0

    /// How many rows in or out ``apply(_:changed:)`` moves one by one: past it the table is filled
    /// afresh. Measured on the drive (#508, round five): 5,000 rows inserted one by one took 188 ms to
    /// draw against a fill's 21 ms, while a watch's change moves a few rows; 200 sits between, the
    /// exact crossing unmeasured. Counts apart by more are filled before any difference is taken,
    /// whose cost grows with the rows times the changes (98 ms for a 5,000-row folder, round six).
    public static let applied = 200

    /// The rows as `new`, changing only what differs, by id: rows gone removed and new ones inserted
    /// in one update, and only the rows named in `changed` made again, so a listing of thousands that
    /// adds one entry rebuilds one row. The choice follows its row as the table moves it.
    public func apply(_ new: [Entry], changed: Set<String>) {
        guard abs(new.count - entries.count) <= Self.applied else {
            entries = new
            return
        }
        let difference = new.map(Self.id).difference(from: entries.map(Self.id))
        func offsets(_ changes: [CollectionDifference<String?>.Change]) -> IndexSet {
            IndexSet(changes.map { change in
                switch change {
                case let .insert(offset, _, _), let .remove(offset, _, _): offset
                }
            })
        }
        let removed = offsets(difference.removals), inserted = offsets(difference.insertions)
        guard removed.count + inserted.count <= Self.applied else {
            entries = new
            return
        }
        applying = true
        entries = new
        applying = false
        reloading = true
        beginUpdates()
        if !removed.isEmpty { removeRows(at: removed, withAnimation: []) }
        if !inserted.isEmpty { insertRows(at: inserted, withAnimation: []) }
        endUpdates()
        let remade = IndexSet(new.indices.filter { Self.id(new[$0]).map(changed.contains) ?? false }.filter { !inserted.contains($0) })
        appliedRows += inserted.count + remade.count
        if !remade.isEmpty {
            reloadData(forRowIndexes: remade, columnIndexes: IndexSet(integer: 0))
            noteHeightOfRows(withIndexesChanged: remade)
        }
        reloading = false
    }

    /// A row's id in an update: its item's, or its head's title.
    private static func id(_ entry: Entry) -> String? {
        switch entry {
        case let .item(item): item.id
        case let .head(title, _): "\u{0}head\u{0}" + title
        }
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
        guard let column = tableColumns.first, column.width != newSize.width else { return }
        column.width = newSize.width
    }

    func item(at row: Int) -> Item? {
        guard entries.indices.contains(row), case let .item(item) = entries[row] else { return nil }
        return item
    }

    /// The row drawing item `id`, and how it is drawn: for the calm tests, which find the chosen row
    /// by its bar and its weight.
    package func row(_ id: String) -> (view: NSTableRowView, paint: RowPaint)? {
        guard let index = entries.indices.first(where: { item(at: $0)?.id == id }),
              let row = rowView(atRow: index, makeIfNecessary: true) as? SailRowView
        else { return nil }
        return (row, row.paint)
    }

    /// The chosen item's id.
    public var chosen: String? {
        item(at: selectedRow)?.id
    }

    /// Chooses the item of `id`, as a pane restoring its last choice does; nil chooses nothing.
    public func choose(_ id: String?) {
        guard let row = select(id) else { return }
        scrollRowToVisible(row)
    }

    /// Chooses `id` where it stands without scrolling to it: new rows keep the person's scroll.
    @discardableResult
    private func select(_ id: String?) -> Int? {
        guard let row = entries.indices.first(where: { item(at: $0)?.id == id }) else {
            deselectAll(nil)
            return nil
        }
        selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
        return row
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
        if onKey?(event) == true { return true }
        let items = entries.indices.filter { item(at: $0).map { !$0.inert } ?? false }
        switch event.keyCode {
        case 115, 119:
            guard let row = event.keyCode == 115 ? items.first : items.last else { return false }
            selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
            scrollRowToVisible(row == items.first ? 0 : row)
            return true
        case 36, 76:
            guard let primary = item(at: selectedRow)?.actions.first(where: { $0.onRow }) else { return false }
            primary.run()
            return true
        default:
            let typed = event.charactersIgnoringModifiers?.lowercased() ?? ""
            let pressed = typed == "\u{7f}" ? "\u{8}" : typed
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
        item(at: row).map { !$0.inert } ?? false
    }

    public func tableView(_: NSTableView, heightOfRow row: Int) -> CGFloat {
        let hairline = rowStyle.hairline && row > 0 ? 1.0 : 0
        guard let item = item(at: row) else { return rowStyle.head + hairline }
        return (item.height ?? rowStyle.height) + (rowStyle.hairline ? 1 : 0)
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
        case let .head(title, count):
            let leading = rowStyle == .sidebar ? rowStyle.margin + rowStyle.leading + RowStyle.disclosure + RowStyle.gap : RowStyle.list.leading
            return HeadCell(title, count: count, leading: leading, look: look, mono: mono, hairline: rowStyle.hairline && row > 0)
        case let .item(item):
            rowsMade += 1
            return RowCell(item, rowStyle: rowStyle, actionsColumn: actionsColumn, returnKey: returnKeyShown, look: look, mono: mono)
        }
    }

    public func tableViewSelectionDidChange(_: Notification) {
        if !reloading { onChoose(chosen) }
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
    /// The progress line along its foot, when its paint asks for one.
    private(set) var line: ProgressLine?

    override func layout() {
        super.layout()
        line?.frame = NSRect(x: 0, y: Double(bounds.height) - ProgressLine.height, width: Double(bounds.width), height: ProgressLine.height)
    }

    var hovered = false {
        didSet { if hovered != oldValue { refresh() } }
    }

    init(rowStyle: RowStyle, look: Look, item: SailList.Item?, first: Bool) {
        self.rowStyle = rowStyle
        self.look = look
        self.item = item
        self.first = first
        super.init(frame: .zero)
        guard let item else { return }
        if item.paint?.progress == true {
            let line = ProgressLine(look: look)
            addSubview(line)
            self.line = line
        }
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
        item?.paint ?? RowPaint(RowState(chosen: isSelected, emphasized: isEmphasized, hovered: hovered))
    }

    /// The chosen row's bar, which says which row is chosen: in the accent while its list holds the
    /// keys, in the quiet ink while another does.
    var barInk: Tint {
        paint.barInk(look)
    }

    /// The table sets a row's state on every layout pass; only a change touches the row, or each
    /// pass would ask for another.
    override var isSelected: Bool {
        didSet { if isSelected != oldValue { refresh() } }
    }

    override var isEmphasized: Bool {
        didSet { if isEmphasized != oldValue { refresh() } }
    }

    override var interiorBackgroundStyle: NSView.BackgroundStyle {
        .normal
    }

    override var isFlipped: Bool {
        true
    }

    /// The table sets a new row's state before the row holds its cell, when asking for the cell
    /// throws; the cell's arrival refreshes the row again.
    private func refresh() {
        let paint = self.paint
        if numberOfColumns > 0, let cell = view(atColumn: 0) as? RowCell {
            cell.show(paint.actions, chosen: isSelected, keys: isEmphasized)
            (cell.content as? MarkedRow)?.mark(chosen: isSelected, strong: paint.strong)
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
            NSColor(item == nil ? look.separator : look.gridLine).setFill()
            NSBezierPath(rect: NSRect(x: 0, y: 0, width: Double(bounds.width), height: 1)).fill()
        }
        guard item != nil else { return }
        let paint = self.paint
        guard paint.bar else { return }
        let ink = barInk, rightToLeft = userInterfaceLayoutDirection == .rightToLeft, half = RowStyle.barWidth / 2
        let top = Double(content.minY) + (min(Double(content.height), rowStyle.height) - rowStyle.bar) / 2
        let inset = (superview as? SailList)?.barInset ?? 0
        let bar = NSRect(x: rightToLeft ? Double(bounds.width) - RowStyle.barWidth - inset : inset, y: top, width: RowStyle.barWidth, height: rowStyle.bar)
        // The bar is a rounded rect and a square half at the edge, overlapping: drawn opaque in one
        // layer, then laid down once at the ink's strength, so a translucent ink is not doubled there.
        let context = NSGraphicsContext.current?.cgContext
        context?.saveGState()
        context?.setAlpha(ink.alpha)
        context?.beginTransparencyLayer(auxiliaryInfo: nil)
        NSColor(Tint(ink.rgb)).setFill()
        NSBezierPath(roundedRect: bar, xRadius: half, yRadius: half).fill()
        NSBezierPath(rect: NSRect(x: rightToLeft ? Double(bar.maxX) - half : Double(bar.minX), y: Double(bar.minY), width: half, height: Double(bar.height))).fill()
        context?.endTransparencyLayer()
        context?.restoreGState()
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
        guard let item, !item.actions.isEmpty else { return nil }
        return RowMenu(item.actions, look: look)
    }
}

/// A group head's words: mono capitals 20 pt in, the count 8 pt after them, on the line the
/// browser puts a 10 pt line in the row's middle.
@MainActor
final class HeadCell: NSView {
    static let countGap = 8.0

    private let title: NSAttributedString
    private let count: NSAttributedString
    private let hairline: Bool
    /// Where its words start: a list's leading inset, or a sidebar's tiles.
    private let leading: Double

    init(_ title: String, count: Int?, leading: Double, look: Look, mono: LabelFont, hairline: Bool) {
        self.leading = leading
        let font = mono.font(Look.Step.caption.points) as NSFont
        self.title = Draw.text(title, font, look.textTertiary, tracking: Look.Tracking.eyebrow, upper: true)
        self.count = Draw.text(count.map { "\($0)" } ?? "", font, Tint(look.textTertiary.rgb, alpha: look.textTertiary.alpha * 0.8), tracking: Look.Tracking.eyebrow)
        self.hairline = hairline
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        setAccessibilityLabel(count.map { "\(title), \($0)" } ?? title)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_: NSRect) {
        let points = Look.Step.caption.points, top = hairline ? 1.0 : 0
        let baseline = TextLine.baseline(top: top + (Double(bounds.height) - top - points) / 2, points: points, lineHeight: points)
        let titleWidth = Double(title.size().width), countWidth = Double(count.size().width)
        if userInterfaceLayoutDirection == .rightToLeft {
            let x = Double(bounds.width) - leading - titleWidth
            Draw.line(title, x: x, baseline: baseline)
            Draw.line(count, x: x - Self.countGap - countWidth, baseline: baseline)
        } else {
            Draw.line(title, x: leading, baseline: baseline)
            Draw.line(count, x: leading + titleWidth + Self.countGap, baseline: baseline)
        }
    }
}

/// An item's cell: what the item shows, from the row's leading inset and stretched to its actions
/// (or to the trailing inset when it has none), and its actions at the trailing edge: the first
/// on-row action secondary, or primary with ⏎ while the row is chosen; the rest secondary; ⋯ for
/// the menu when some action lives only there. Hidden actions are hidden, not only clear, so they
/// take no click and Tab passes them. A row with nothing at its end (no action, note or accessory,
/// as a files tree's) is laid out by frames, with no constraint: a row scrolled into or out of a long
/// list then costs the window's layout engine nothing.
@MainActor
final class RowCell: NSView {
    let content: NSView
    /// Laid out by frames: nothing shown at its end.
    private let bare: Bool
    private let insets: (leading: Double, trailing: Double, top: Double)
    let actions = NSStackView()
    /// Its first action shows ⏎ while chosen with the list's keys.
    private let returnKey: Bool
    private let first: SailButton?
    private let runs: [Run]
    private var shown = false
    /// The note in full on hover while it is cut short (a tracking area does not keep its owner).
    let noteTip: Tooltip?

    init(_ item: SailList.Item, rowStyle: RowStyle, actionsColumn: Double?, returnKey: Bool = true, look: Look, mono: LabelFont) {
        self.returnKey = returnKey
        content = item.content
        let runs = item.actions.map { Run($0) }
        self.runs = runs
        var buttons: [SailButton] = []
        let onRow = runs.filter { $0.action.onRow }
        if let primary = onRow.first {
            let first = SailButton(primary.action.title, kind: .secondary, size: .small, look: look, target: primary, action: #selector(Run.go(_:)))
            if returnKey { first.shortcut("\r", mono: mono) }
            first.keyEquivalent = ""
            self.first = first
            buttons.append(first)
        } else {
            first = nil
        }
        for other in onRow.dropFirst() {
            buttons.append(SailButton(other.action.title, kind: .secondary, size: .small, look: look, target: other, action: #selector(Run.go(_:))))
        }
        if rowStyle != .sidebar, runs.contains(where: { !$0.action.onRow }) {
            let more = SailButton(icon: .more, label: "More", size: .small, look: look, target: nil, action: nil)
            buttons.append(more)
            moreMenu = RowMenu(item.actions, look: look)
            more.target = MenuOpener.shared
            more.action = #selector(MenuOpener.open(_:))
            more.menu = moreMenu
        }
        let note = item.note.map { words -> NSTextField in
            let label = NSTextField(labelWithAttributedString: Draw.text(words, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary).cut(.byTruncatingTail))
            label.lineBreakMode = .byTruncatingTail
            label.maximumNumberOfLines = 1
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            return label
        }
        if let note, let words = item.note {
            let tip = Tooltip(words, look: look, mono: nil)
            tip.onlyWhenCut = true
            tip.attach(to: note, saying: words)
            noteTip = tip
        } else {
            noteTip = nil
        }
        let top = rowStyle.hairline ? 1.0 : 0
        bare = buttons.isEmpty && note == nil && item.accessory == nil
        insets = (rowStyle.margin + rowStyle.leading, rowStyle.margin + rowStyle.trailing + (item.full ? 0 : actionsColumn ?? 0), top)
        super.init(frame: .zero)
        if bare {
            addSubview(content)
            return
        }
        actions.setViews(([note, item.accessory].compactMap { $0 } as [NSView]) + buttons, in: .trailing)
        if let note { actions.setCustomSpacing(6 + 4, after: note) }
        actions.spacing = 6
        actions.alphaValue = 0
        actions.isHidden = true
        for view in [content, actions] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        let gap = buttons.isEmpty && note == nil ? 0.0 : 8
        let actionsColumn = item.full ? 0 : actionsColumn
        let stretched = actionsColumn.map { content.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -(rowStyle.margin + rowStyle.trailing + $0)) }
            ?? content.trailingAnchor.constraint(equalTo: actions.leadingAnchor, constant: -gap)
        stretched.priority = actionsColumn == nil ? .defaultHigh : .required
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: leadingAnchor, constant: rowStyle.margin + rowStyle.leading),
            actionsColumn == nil ? content.trailingAnchor.constraint(lessThanOrEqualTo: actions.leadingAnchor, constant: -gap) : actions.widthAnchor.constraint(lessThanOrEqualToConstant: actionsColumn ?? 0),
            stretched,
            content.centerYAnchor.constraint(equalTo: centerYAnchor, constant: top / 2),
            actions.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -(rowStyle.margin + rowStyle.trailing)),
            actions.centerYAnchor.constraint(equalTo: centerYAnchor, constant: top / 2),
        ])
        show(false, chosen: false, keys: false)
    }

    private var moreMenu: NSMenu?

    /// A content's own height: its intrinsic one, else what its own constraints give it (a sidebar
    /// row's 20 pt), else the row's.
    private static func height(of content: NSView, in room: Double) -> Double {
        let intrinsic = Double(content.intrinsicContentSize.height)
        if intrinsic != Double(NSView.noIntrinsicMetric) { return intrinsic }
        let fitting = Double(content.fittingSize.height)
        return fitting > 0 ? min(fitting, room) : room
    }

    /// A bare row's content, from its leading inset to its trailing one (mirrored right to left), its
    /// own height centred on the row under any hairline.
    override func layout() {
        super.layout()
        guard bare else { return }
        let height = Self.height(of: content, in: Double(bounds.height) - insets.top)
        let below = (Double(bounds.height) - insets.top - height) / 2
        let frame = NSRect(
            x: userInterfaceLayoutDirection == .rightToLeft ? insets.trailing : insets.leading, y: isFlipped ? insets.top + below : below,
            width: max(Double(bounds.width) - insets.leading - insets.trailing, 0), height: height
        )
        if content.frame != frame { content.frame = frame }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The actions shown (over 120 ms, at once while motion is reduced) or gone; the primary loud
    /// while the row is chosen, and Return its key (shown as ⏎) only while the list holds the keys.
    func show(_ shown: Bool, chosen: Bool, keys: Bool) {
        if let first {
            let kind: ButtonKind = chosen ? .primary : .secondary, key = chosen && keys && returnKey ? "\r" : ""
            if first.kind != kind { first.kind = kind }
            if first.keyEquivalent != key { first.keyEquivalent = key }
        }
        guard shown != self.shown else { return }
        self.shown = shown
        if shown, actions.isHidden { actions.isHidden = false }
        let alpha: CGFloat = shown ? 1 : 0
        guard !Motion.shared.reduced, window != nil else {
            actions.alphaValue = alpha
            if !shown { actions.isHidden = true }
            return
        }
        NSAnimationContext.runAnimationGroup { context in
            context.duration = Look.Timing.hover
            self.actions.animator().alphaValue = alpha
        } completionHandler: { [weak self] in
            MainActor.assumeIsolated {
                guard let self, !self.shown else { return }
                self.actions.isHidden = true
            }
        }
    }

    /// Every action of a row, as its ⋯ and its right-click show them.
}

/// A row's menu, its actions in order with a separator before each group: a destructive one
/// (Delete) in the look's error ink, and plain while it is highlighted, where red on the selection
/// reads badly.
@MainActor
final class RowMenu: NSMenu, NSMenuDelegate {
    private let ink: NSColor

    init(_ actions: [SailList.Action], look: Look) {
        ink = NSColor(Tint(look.error))
        super.init(title: "")
        delegate = self
        for action in actions {
            if action.startsGroup, !items.isEmpty { addItem(.separator()) }
            let run = Run(action)
            let entry = NSMenuItem(title: action.title, action: #selector(Run.go(_:)), keyEquivalent: action.key)
            entry.keyEquivalentModifierMask = action.modifiers
            entry.target = run
            entry.representedObject = run
            addItem(entry)
        }
        paint(highlighted: nil)
    }

    @available(*, unavailable)
    required init(coder: NSCoder) {
        fatalError("a row's menu is made from its actions")
    }

    private func paint(highlighted: NSMenuItem?) {
        for item in items where (item.representedObject as? Run)?.action.destructive == true {
            item.attributedTitle = item === highlighted ? nil : NSAttributedString(string: item.title, attributes: [.font: NSFont.menuFont(ofSize: 0), .foregroundColor: ink])
        }
    }

    func menu(_: NSMenu, willHighlight item: NSMenuItem?) {
        paint(highlighted: item)
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

/// Opens the menu a button carries, 2 pt under its box from its leading corner, as a pop-up does;
/// right to left the menu is laid out so and hangs from the box's right corner.
@MainActor
final class MenuOpener: NSObject {
    static let shared = MenuOpener()

    /// AppKit hangs a popped-up menu by its first item, not its edge, so the menu's own top padding
    /// (5 pt, as the mockup's native menu has it) stands above the location given. AppKit says this
    /// padding nowhere a program can read it; the drive's real click on ⋯ checks the menu's edge on
    /// screen, so a macOS that changes it fails there.
    static let menuPadding = 5.0

    @objc func open(_ sender: NSButton) {
        sender.menu?.userInterfaceLayoutDirection = sender.userInterfaceLayoutDirection
        sender.menu?.popUp(positioning: nil, at: Self.corner(under: sender), in: sender)
    }

    /// Where the menu's first item goes: under the box's leading corner, so that the menu's edge is
    /// 2 pt under the box.
    static func corner(under button: NSView) -> NSPoint {
        let box = button.bounds, insets = button.alignmentRectInsets, below = 2 + menuPadding
        let bottom = button.isFlipped ? Double(box.maxY) - Double(insets.bottom) + below : Double(box.minY) + Double(insets.bottom) - below
        let leading = button.userInterfaceLayoutDirection == .rightToLeft ? Double(box.maxX) - Double(insets.right) : Double(box.minX) + Double(insets.left)
        return NSPoint(x: leading, y: bottom)
    }
}

/// A 2 pt accent line along a row's foot growing to 94 % of it over 3 s, again and again, eased as
/// the design's `prog` is (cubic-bezier .2 .6 .3 1): a create under way, the server's median long.
/// Held at 94 % while motion is reduced.
@MainActor
final class ProgressLine: NSView, Marked {
    static let height = 2.0, reach = 0.94, seconds = 3.0

    let bar = CALayer()
    /// Teal, as the Creating pill: something under way, not an action.
    let ink: Tint
    private let look: Look

    package var drawnMarks: [Mark] {
        look.marks(line: ink)
    }
    private var laidWidth = 0.0

    init(look: Look) {
        self.look = look
        ink = Tint(look.info)
        super.init(frame: .zero)
        wantsLayer = true
        bar.backgroundColor = NSColor(ink).cgColor
        bar.anchorPoint = CGPoint(x: 0, y: 0.5)
        layer?.addSublayer(bar)
        setAccessibilityElement(false)
        NotificationCenter.default.addObserver(self, selector: #selector(motionChanged), name: Motion.changed, object: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func layout() {
        super.layout()
        guard Double(bounds.width) != laidWidth else { return }
        laidWidth = Double(bounds.width)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        bar.bounds = CGRect(x: 0, y: 0, width: Double(bounds.width) * Self.reach, height: Self.height)
        bar.position = CGPoint(x: 0, y: Double(bounds.height) / 2)
        CATransaction.commit()
        run()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        run()
    }

    @objc private func motionChanged() {
        run()
    }

    /// The growth from nothing, while in a window and motion is not reduced.
    private func run() {
        bar.removeAnimation(forKey: "grow")
        guard window != nil, !Motion.shared.reduced, bounds.width > 0 else { return }
        let grow = CABasicAnimation(keyPath: "bounds.size.width")
        grow.fromValue = 0
        grow.toValue = Double(bounds.width) * Self.reach
        grow.duration = Self.seconds
        grow.repeatCount = .infinity
        grow.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.6, 0.3, 1)
        bar.add(grow, forKey: "grow")
    }
}

extension SailRowView: Marked {
    package var drawnMarks: [Mark] {
        paint.bar ? look.marks(line: barInk) : []
    }
}

/// A row's content that marks it chosen without a fill, as paper and ink does: its name at full
/// weight while `strong` (chosen or under the pointer), and its own chosen mark.
@MainActor package protocol MarkedRow: AnyObject {
    func mark(chosen: Bool, strong: Bool)
}
