import AppKit
import os
import SailLook

private let listLog = Logger(subsystem: "sh.standardapplied.sail", category: "list")

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
        /// On the row it is this icon alone, beside ⋯ (New Session's +), its title the tooltip's.
        public let icon: LineIcon?
        /// It is under way: its button on the row in flight under this word, taking no press.
        public let busy: String?
        /// Whether it can be done now, asked each time its menu opens or its key comes: off, its item
        /// is dimmed and its key does nothing (Reveal in Terminal with no terminal in the tab).
        public let enabled: @MainActor () -> Bool
        public let run: @MainActor () -> Void

        public init(
            _ title: String, key: String = "", modifiers: NSEvent.ModifierFlags = [], onRow: Bool = true, startsGroup: Bool = false, destructive: Bool = false,
            icon: LineIcon? = nil, busy: String? = nil, enabled: @escaping @MainActor () -> Bool = { true }, run: @escaping @MainActor () -> Void
        ) {
            self.title = title
            self.key = key
            self.modifiers = modifiers
            self.onRow = onRow
            self.startsGroup = startsGroup
            self.destructive = destructive
            self.icon = icon
            self.busy = busy
            self.enabled = enabled
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
        /// Its place in a tree, as VoiceOver hears an outline's row: how deep, and open or shut.
        public let outline: Outline?

        public init(
            id: String, name: String, voice: String, content: @autoclosure @escaping @MainActor () -> NSView, actions: [Action] = [], note: String? = nil, height: Double? = nil,
            paint: RowPaint? = nil, full: Bool = false, accessory: NSView? = nil, inert: Bool = false, outline: Outline? = nil
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
            self.outline = outline
        }
    }

    /// A tree row's depth (0 at the top) and whether it is an open folder, a shut one, or no folder.
    public struct Outline: Equatable {
        public let level: Int
        public let expanded: Bool?

        public init(level: Int, expanded: Bool?) {
            self.level = level
            self.expanded = expanded
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
        if editing != nil { editGone() }
        let hovered = (0 ..< numberOfRows).first { (rowView(atRow: $0, makeIfNecessary: false) as? SailRowView)?.hovered == true }.flatMap { item(at: $0)?.id }
        Tooltip.putAway()
        reloadData()
        let row = pointerNow == nil ? hovered.flatMap(rowIndex(of:)) : rowUnderPointer()
        guard let row, item(at: row) != nil else { return }
        (rowView(atRow: row, makeIfNecessary: true) as? SailRowView)?.hovered = true
    }

    /// The row shown under the pointer, nil when it is over none or not known.
    private func rowUnderPointer() -> Int? {
        guard let point = pointerNow else { return nil }
        let inList = convert(point, from: nil), content = window?.contentView
        let hit = content.flatMap { $0.hitTest($0.superview?.convert(point, from: nil) ?? point) }
        let under = visibleRect.contains(inList) && hit?.isDescendant(of: self) == true ? row(at: inList) : -1
        return under >= 0 ? under : nil
    }

    /// The pointer held over the middle of row `id`, wherever the row is laid out, as the drive puts
    /// it there: the row drawn hovered, and found so again by every reload (a change of look or
    /// direction); nil gives the pointer back.
    package func point(at id: String?) {
        pointer = id.map { id -> @MainActor () -> NSPoint? in { [weak self] in self?.middle(of: id) } }
        let under = rowUnderPointer()
        enumerateAvailableRowViews { view, row in (view as? SailRowView)?.hovered = row == under }
    }

    /// Where row `id`'s middle is in its window, nil while it is not a row.
    private func middle(of id: String) -> NSPoint? {
        guard let row = rowIndex(of: id) else { return nil }
        let box = rect(ofRow: row)
        return convert(NSPoint(x: box.midX, y: box.midY), to: nil)
    }

    private func rowIndex(of id: String) -> Int? {
        entries.indices.first { item(at: $0)?.id == id }
    }

    /// Whether row `id`'s actions are shown whole: what a hover or a choice brings up.
    package func actionsShown(_ id: String) -> Bool {
        guard let row = rowIndex(of: id), let cell = rowView(atRow: row, makeIfNecessary: false)?.view(atColumn: 0) as? RowCell else { return false }
        return !cell.actions.isHidden && cell.actions.alphaValue == 1 && !cell.actions.views.isEmpty
    }

    public let rowStyle: RowStyle
    /// A width kept for every row's actions, so the rows' content lines up in columns whatever
    /// actions each shows; nil, the content runs to its row's actions.
    public var actionsColumn: Double?
    /// The chosen row's first action shows ⏎ while the list holds the keys; off, it shows none,
    /// and Return still presses it (a narrow row's actions column has no room for the keycap).
    public var returnKeyShown = true
    /// The one primary in view is elsewhere (a panel floating over the list): no row's first action
    /// is drawn primary, the chosen row's in its outline form until this is false again.
    public var primaryElsewhere = false {
        didSet {
            guard primaryElsewhere != oldValue else { return }
            enumerateAvailableRowViews { row, _ in (row as? SailRowView)?.refresh() }
        }
    }

    /// How far in from the list's leading edge the chosen row's bar starts: a hairline where the
    /// card's own edge is drawn over that edge (the panel at the card's leading edge, in dark), so
    /// the edge does not cover the bar's outer point.
    public var barInset = 0.0 {
        didSet { if barInset != oldValue { needsDisplay = true; enumerateAvailableRowViews { row, _ in row.needsDisplay = true } } }
    }
    public var onChoose: @MainActor (String?) -> Void = { _ in }
    /// A key the list's owner takes before the list does (a tree's → and ←): true, taken.
    package var onKey: (@MainActor (NSEvent) -> Bool)?
    /// The list took the keys.
    package var onFocus: @MainActor () -> Void = {}

    override public func becomeFirstResponder() -> Bool {
        let taken = super.becomeFirstResponder()
        if taken { onFocus() }
        return taken
    }
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
    /// While a row is being edited (``editing``) nothing is filled, so a change of any size goes row
    /// by row: 5,000 rows that way cost the 188 ms above against a fill's 21 ms, paid only while an
    /// edit lasts.
    public static let applied = 200

    /// The id of the row holding an inline edit (a rename, a new file's name), nil for none.
    /// ``apply(_:changed:)`` never remakes it: rows go and come around it, however many, and its
    /// own change waits until this is set back to nil, which remakes it then. A row that moves
    /// among the rows or leaves them goes with its edit, and this is nil again; so does every row at
    /// a fill (new ``entries``, a look or direction changed), which ends the edit and sets this nil.
    public var editing: String? {
        didSet {
            guard oldValue != editing, let oldValue, editedChanged else { return }
            editedChanged = false
            guard let row = entries.firstIndex(where: { Self.id($0) == oldValue }) else { return }
            reloading = true
            beginUpdates()
            removeRows(at: IndexSet(integer: row), withAnimation: [])
            insertRows(at: IndexSet(integer: row), withAnimation: [])
            endUpdates()
            reloading = false
        }
    }

    /// Whether the edited row's item changed while it was being edited.
    private var editedChanged = false

    /// The edited row moved or left, and its edit with it: nothing is remade for it.
    private func editGone() {
        editedChanged = false
        editing = nil
    }

    /// The rows as `new`, changing only what differs, by id: rows gone removed and new ones inserted
    /// in one update, and only the rows named in `changed` made again, so a listing of thousands that
    /// adds one entry rebuilds one row. The choice follows its row as the table moves it, and the
    /// hovered row stays hovered when it is made again. The row being edited (``editing``) is never
    /// made again, and the rows are never filled afresh around it.
    public func apply(_ new: [Entry], changed: Set<String>) {
        let held = editing.flatMap { id in new.contains { Self.id($0) == id } ? id : nil }
        if editing != nil, held == nil { editGone() }
        guard held != nil || abs(new.count - entries.count) <= Self.applied else {
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
        var removed = offsets(difference.removals), inserted = offsets(difference.insertions)
        guard held != nil || removed.count + inserted.count <= Self.applied else {
            entries = new
            return
        }
        if let held, difference.removals.contains(where: { if case let .remove(_, id, _) = $0 { id == held } else { false } }) {
            editGone()
        }
        var remade = changed
        if let held = editing, remade.remove(held) != nil { editedChanged = true }
        let hovered = rows(in: visibleRect).toRange().flatMap { visible in
            visible.first { (rowView(atRow: $0, makeIfNecessary: false) as? SailRowView)?.hovered == true }
        }.flatMap { item(at: $0)?.id }
        // A changed row goes out and comes back whole, its row view with it: a row view holds its
        // item (what VoiceOver hears of it, how it is painted), which only remaking it renews.
        let old = entries
        removed.formUnion(IndexSet(old.indices.filter { Self.id(old[$0]).map(remade.contains) ?? false }))
        inserted.formUnion(IndexSet(new.indices.filter { Self.id(new[$0]).map(remade.contains) ?? false }))
        applying = true
        entries = new
        applying = false
        reloading = true
        beginUpdates()
        if !removed.isEmpty { removeRows(at: removed, withAnimation: []) }
        if !inserted.isEmpty { insertRows(at: inserted, withAnimation: []) }
        endUpdates()
        appliedRows += inserted.count
        reloading = false
        if let hovered, remade.contains(hovered), let row = entries.firstIndex(where: { Self.id($0) == hovered }),
           let point = pointerNow, rect(ofRow: row).contains(convert(point, from: nil)) {
            (rowView(atRow: row, makeIfNecessary: true) as? SailRowView)?.hovered = true
        }
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
        if rowStyle == .tree { setAccessibilityRole(.outline) }
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

    /// Every chosen item's id, in the list's order: a tree's rows are chosen several at once.
    public var chosenIDs: [String] {
        selectedRowIndexes.compactMap { item(at: $0)?.id }
    }

    /// Chooses the item of `id`, as a pane restoring its last choice does; nil chooses nothing.
    public func choose(_ id: String?) {
        guard let row = select(id) else { return }
        scrollRowToVisible(row)
    }

    /// Chooses `id` where it stands without scrolling to it: new rows keep the person's scroll.
    @discardableResult
    package func select(_ id: String?) -> Int? {
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

    /// Return, Home, End, or an action's key on the chosen row; false when the key is none of them,
    /// and for any key while the list is not shown (its section left with the keys still on it), so
    /// a second Return after an agent's Start never presses Open.
    func act(on event: NSEvent) -> Bool {
        guard !isHiddenOrHasHiddenAncestor else { return false }
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
            if action.enabled() { action.run() }
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

    /// Whether a misuse of the list (two items handing it one view) stops the process, as tests,
    /// debug builds and the drive want; a person's app says it once in the log and goes on without
    /// stopping, a row possibly blank until the next fill.
    package static var stopsOnMisuse = asserting
    private static let asserting: Bool = {
        var on = false
        assert({ on = true; return true }())
        return on
    }()

    private static var misuseSaid = false

    /// A programming slip in using the list: the process stops where ``stopsOnMisuse``, and
    /// otherwise the log says so once.
    static func misused(_ words: String) {
        if stopsOnMisuse { preconditionFailure("SailList: " + words) }
        guard !misuseSaid else { return }
        misuseSaid = true
        listLog.fault("SailList: \(words, privacy: .public); the view stays with the row that asked last")
    }

    /// The ids of rows on screen whose cell no longer holds the content its item gave it: a row
    /// gone blank.
    package var blankRows: [String] {
        let visible = rows(in: visibleRect)
        return (visible.lowerBound ..< visible.upperBound).compactMap { row in
            guard let cell = view(atColumn: 0, row: row, makeIfNecessary: false) as? RowCell else { return nil }
            return cell.content.superview === cell ? nil : cell.itemID ?? "row \(row)"
        }
    }

    /// The identifiers a row view and an item's cell are kept by for the next row that enters view.
    static let rowID = NSUserInterfaceItemIdentifier("sail.row"), cellID = NSUserInterfaceItemIdentifier("sail.cell")

    /// Of the rows made (``rowsMade``: each item's cell filled), those whose cell was taken again
    /// from the table's own pool, made over whole, rather than made new.
    package private(set) var rowsReused = 0
    /// The main thread's own CPU spent making rows, row views and cells, made new or made over,
    /// counted only while ``measuring``: what the drive splits a scroll's turn by.
    package private(set) var madeCPU: (rows: Int, nanoseconds: UInt64) = (0, 0)
    /// Whether ``madeCPU`` counts: off in the app, on only while the drive measures.
    package static var measuring = false

    private func made<View>(_ make: () -> View) -> View {
        guard Self.measuring else { return make() }
        let began = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
        let view = make()
        madeCPU = (madeCPU.rows + 1, madeCPU.nanoseconds + clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID) - began)
        return view
    }

    public func tableView(_: NSTableView, rowViewForRow row: Int) -> NSTableRowView? {
        made { rowView(for: row) }
    }

    private func rowView(for row: Int) -> NSTableRowView {
        if let view = makeView(withIdentifier: Self.rowID, owner: nil) as? SailRowView {
            view.userInterfaceLayoutDirection = userInterfaceLayoutDirection
            view.configure(look: look, item: item(at: row), first: row == 0)
            return view
        }
        let view = SailRowView(rowStyle: rowStyle, look: look, item: item(at: row), first: row == 0)
        view.identifier = Self.rowID
        view.userInterfaceLayoutDirection = userInterfaceLayoutDirection
        return view
    }

    public func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
        made { cell(for: row) }
    }

    private func cell(for row: Int) -> NSView? {
        guard entries.indices.contains(row) else { return nil }
        switch entries[row] {
        case let .head(title, count):
            let leading = rowStyle == .sidebar ? rowStyle.margin + rowStyle.leading + RowStyle.disclosure + RowStyle.gap : RowStyle.list.leading
            return HeadCell(title, count: count, leading: leading, look: look, mono: mono, hairline: rowStyle.hairline && row > 0)
        case let .item(item):
            rowsMade += 1
            if let cell = makeView(withIdentifier: Self.cellID, owner: nil) as? RowCell {
                rowsReused += 1
                cell.userInterfaceLayoutDirection = userInterfaceLayoutDirection
                cell.configure(item, actionsColumn: actionsColumn, returnKey: returnKeyShown, look: look)
                (cell.superview as? SailRowView)?.refresh()
                return cell
            }
            let cell = RowCell(item, rowStyle: rowStyle, actionsColumn: actionsColumn, returnKey: returnKeyShown, look: look, mono: mono)
            cell.identifier = Self.cellID
            cell.userInterfaceLayoutDirection = userInterfaceLayoutDirection
            return cell
        }
    }

    public func tableViewSelectionDidChange(_: Notification) {
        if !reloading { onChoose(chosen) }
    }

    // MARK: Moving rows by a drag

    /// A tree's rows moved by a drag within it: where a drop over row `over` (nil past the last row)
    /// lands, the id of the folder row it goes into, "" for the tree's root, nil for nowhere; and the
    /// dragged rows' ids dropped there.
    public struct Moves {
        public let lands: @MainActor (_ over: String?) -> String?
        public let drop: @MainActor (_ ids: [String], _ into: String) -> Void

        public init(lands: @escaping @MainActor (_ over: String?) -> String?, drop: @escaping @MainActor (_ ids: [String], _ into: String) -> Void) {
            self.lands = lands
            self.drop = drop
        }
    }

    /// Rows dragged within the list move; nil, nothing is dragged.
    public var moves: Moves? {
        didSet {
            registerForDraggedTypes(moves == nil ? [] : [Self.rowType])
            setDraggingSourceOperationMask(moves == nil ? [] : .move, forLocal: true)
        }
    }

    static let rowType = NSPasteboard.PasteboardType("sh.standardapplied.sail.row")
    /// A dragged row's opacity while it is dragged, as the mockup's `.drag`.
    static let dragged = 0.45

    public func tableView(_: NSTableView, pasteboardWriterForRow row: Int) -> (any NSPasteboardWriting)? {
        guard moves != nil, let item = item(at: row), !item.inert else { return nil }
        let writer = NSPasteboardItem()
        writer.setString(item.id, forType: Self.rowType)
        return writer
    }

    public func tableView(_: NSTableView, draggingSession _: NSDraggingSession, willBeginAt _: NSPoint, forRowIndexes rows: IndexSet) {
        rows.forEach { rowView(atRow: $0, makeIfNecessary: false)?.alphaValue = Self.dragged }
    }

    public func tableView(_: NSTableView, draggingSession _: NSDraggingSession, endedAt _: NSPoint, operation _: NSDragOperation) {
        enumerateAvailableRowViews { row, _ in row.alphaValue = 1 }
    }

    /// A drop over a row lands on the folder `moves` says, the whole list for its root; a drag from
    /// anywhere else lands nowhere.
    public func tableView(_: NSTableView, validateDrop info: any NSDraggingInfo, proposedRow row: Int, proposedDropOperation _: NSTableView.DropOperation) -> NSDragOperation {
        guard let moves, info.draggingSource as? SailList === self, let into = moves.lands(item(at: row)?.id) else { return [] }
        setDropRow(into.isEmpty ? -1 : entries.firstIndex { Self.id($0) == into } ?? -1, dropOperation: .on)
        return .move
    }

    public func tableView(_: NSTableView, acceptDrop info: any NSDraggingInfo, row: Int, dropOperation _: NSTableView.DropOperation) -> Bool {
        guard let moves, info.draggingSource as? SailList === self else { return false }
        let ids = info.draggingPasteboard.pasteboardItems?.compactMap { $0.string(forType: Self.rowType) } ?? []
        guard !ids.isEmpty else { return false }
        moves.drop(ids, item(at: row)?.id ?? "")
        return true
    }
}

/// A row as the look draws it (`RowPaint`): the fill, the accent bar
/// at the list's leading edge and the hairline above a list's row; its actions shown while it is
/// hovered or chosen. VoiceOver reads its words and offers its actions.
@MainActor
final class SailRowView: NSTableRowView {
    let rowStyle: RowStyle
    private(set) var look: Look
    private(set) var item: SailList.Item?
    private var first: Bool
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
        // One layer a row, its cell and words drawn into it: each layer more is display and commit
        // work for every row that scrolls in (the round's sampled stacks spent the hold there).
        wantsLayer = true
        canDrawSubviewsIntoLayer = true
        configure(look: look, item: item, first: first)
    }

    /// Made over whole for `item`, as a reused row is: not hovered, its progress line only when its
    /// paint asks, what VoiceOver reads and offers.
    func configure(look: Look, item: SailList.Item?, first: Bool) {
        self.look = look
        self.item = item
        self.first = first
        hovered = false
        alphaValue = 1
        line?.removeFromSuperview()
        line = nil
        if item?.paint?.progress == true {
            let line = ProgressLine(look: look)
            addSubview(line)
            self.line = line
        }
        setAccessibilityLabel(item?.voice)
        setAccessibilitySubrole(item?.outline == nil ? nil : .outlineRow)
        setAccessibilityDisclosureLevel(item?.outline?.level ?? 0)
        refresh()
        needsLayout = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Its actions that can be done now as VoiceOver offers them, made when VoiceOver asks: a row
    /// scrolled into a long tree makes none.
    override func accessibilityCustomActions() -> [NSAccessibilityCustomAction]? {
        item?.actions.filter { $0.enabled() }.map { action in
            let run = Run(action)
            return NSAccessibilityCustomAction(name: action.title) {
                MainActor.assumeIsolated { run.go(nil) }
                return true
            }
        }
    }

    /// A tree row is open or shut as its item says: a table's row answers false on its own, so the
    /// row says it.
    override func isAccessibilityExpanded() -> Bool {
        item?.outline?.expanded ?? false
    }

    override func isAccessibilityDisclosed() -> Bool {
        item?.outline?.expanded ?? false
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
    func refresh() {
        let paint = self.paint
        if numberOfColumns > 0, let cell = view(atColumn: 0) as? RowCell {
            cell.show(paint.actions, chosen: isSelected, keys: isEmphasized, quiet: (superview as? SailList)?.primaryElsewhere == true)
            (cell.content as? MarkedRow)?.mark(chosen: isSelected, strong: paint.strong)
        }
        needsDisplay = true
    }

    override func didAddSubview(_ subview: NSView) {
        super.didAddSubview(subview)
        refresh()
    }

    /// Its list's `primaryElsewhere` reaches a row made while it holds.
    override func viewDidMoveToSuperview() {
        super.viewDidMoveToSuperview()
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

    /// The row a drag drops into: a 1 pt ink edge inside its rounded box, as the mockup's `.drop`.
    override func drawDraggingDestinationFeedback(in _: NSRect) {
        let box = NSRect(x: rowStyle.margin, y: top, width: Double(bounds.width) - 2 * rowStyle.margin, height: Double(bounds.height) - top).insetBy(dx: 0.5, dy: 0.5)
        let edge = NSBezierPath(roundedRect: box, xRadius: rowStyle.radius, yRadius: rowStyle.radius)
        edge.lineWidth = 1
        NSColor(look.text).setStroke()
        edge.stroke()
    }

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

/// An item's cell: what the item shows, from the row's leading inset to its actions (or to the
/// trailing inset when it has none), and its actions at the trailing edge: the first on-row action
/// secondary, or primary with ⏎ while the row is chosen; the rest secondary, or an icon of their own
/// (+); ⋯ for the menu when some action lives only there. Hidden actions are hidden, not only clear, so they take no click and Tab
/// passes them. Everything is placed by arithmetic, no constraint: a row scrolled into or out of a
/// long list costs the window's layout engine nothing. A cell is reused for the next row that enters
/// view, made over whole by ``configure(_:actionsColumn:returnKey:look:)``.
@MainActor
final class RowCell: NSView {
    private(set) var content = NSView()
    /// The item it shows: an item's content belongs to its one row.
    private var item: SailList.Item?
    var itemID: String? {
        item?.id
    }
    let actions = RowActions()
    private let rowStyle: RowStyle
    private let mono: LabelFont
    /// Its first action shows ⏎ while chosen with the list's keys.
    private var returnKey = true
    private var first: SailButton?
    private var runs: [Run] = []
    private var shown = false
    /// A tree row's menu-only actions, its ⋯ made the first time its actions show: a row scrolled
    /// into a long tree makes no button and no menu (the drive's scroll and burst budgets).
    private var moreOwed: (actions: [SailList.Action], look: Look)?
    /// What it keeps clear at its trailing end for every row's actions, nil none; 0 for a full row.
    private var column: Double?
    /// The gap between the content and the actions: 8 pt with buttons or a note, else none.
    private var gap = 0.0
    /// The note in full on hover while it is cut short (a tracking area does not keep its owner).
    private(set) var noteTip: Tooltip?

    init(rowStyle: RowStyle, mono: LabelFont) {
        self.rowStyle = rowStyle
        self.mono = mono
        super.init(frame: .zero)
        addSubview(content)
        // A layer of their own under the row's one: their 120 ms fade is the layer's opacity, not
        // the whole row drawn again every frame of it.
        actions.wantsLayer = true
        addSubview(actions)
    }

    convenience init(_ item: SailList.Item, rowStyle: RowStyle, actionsColumn: Double?, returnKey: Bool = true, look: Look, mono: LabelFont) {
        self.init(rowStyle: rowStyle, mono: mono)
        configure(item, actionsColumn: actionsColumn, returnKey: returnKey, look: look)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Made over whole for `item`: its content, its actions, note and tooltip, the actions put away.
    func configure(_ item: SailList.Item, actionsColumn: Double?, returnKey: Bool, look: Look) {
        self.returnKey = returnKey
        let given = item.content
        let sharer = (given.superview as? RowCell).flatMap { $0 !== self && $0.itemID != item.id ? $0 : nil }
        if let sharer {
            SailList.misused("\(item.id)'s content is shown by \(sharer.itemID ?? "another") row too; build each item's content inside its closure")
        }
        if content !== given, content.superview === self { content.removeFromSuperview() }
        content = given
        self.item = item
        addSubview(content, positioned: .below, relativeTo: actions)
        sharer?.contentTaken()
        runs = item.actions.map { Run($0) }
        var buttons: [SailButton] = []
        let onRow = runs.filter { $0.action.onRow }
        first = nil
        if let primary = onRow.first {
            let first = SailButton(primary.action.title, kind: .secondary, size: .small, look: look, target: primary, action: #selector(Run.go(_:)))
            if returnKey { first.shortcut("\r", mono: mono) }
            first.keyEquivalent = ""
            self.first = first
            buttons.append(first)
        }
        for other in onRow.dropFirst() {
            let button = other.action.icon.map { SailButton(icon: $0, label: other.action.title, size: .small, look: look, target: other, action: #selector(Run.go(_:))) }
                ?? SailButton(other.action.title, kind: .secondary, size: .small, look: look, target: other, action: #selector(Run.go(_:)))
            if let busy = other.action.busy { button.fly(busy) }
            buttons.append(button)
        }
        moreOwed = nil
        if rowStyle != .sidebar, runs.contains(where: { !$0.action.onRow }) {
            if rowStyle == .tree { moreOwed = (item.actions, look) } else { buttons.append(Self.more(item.actions, rowStyle: rowStyle, look: look)) }
        }
        let note = item.note.map { words -> NSTextField in
            let label = NSTextField(labelWithAttributedString: Draw.text(words, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary).cut(.byTruncatingTail))
            label.lineBreakMode = .byTruncatingTail
            label.maximumNumberOfLines = 1
            return label
        }
        noteTip?.hide(warming: false)
        noteTip = nil
        if let note, let words = item.note {
            let tip = Tooltip(words, look: look, mono: nil)
            tip.onlyWhenCut = true
            tip.attach(to: note, saying: words)
            noteTip = tip
        }
        actions.set(([note, item.accessory].compactMap { $0 } as [NSView]) + buttons, note: note)
        gap = buttons.isEmpty && note == nil ? 0 : 8
        column = item.full ? 0 : actionsColumn
        // Put away at once: a row made over for another enters view with its actions away, never
        // fading out the new row's actions as it scrolls in.
        shown = false
        actions.alphaValue = 0
        actions.isHidden = true
        show(false, chosen: false, keys: false)
        needsLayout = true
    }

    /// Its content was taken by a row handed the same view: made again from its own item's closure,
    /// or, when that closure hands back the view taken, an empty view: the row is blank until the
    /// next fill.
    private func contentTaken() {
        guard let item else { return }
        let again = item.content
        content = again.superview == nil ? again : NSView()
        addSubview(content, positioned: .below, relativeTo: actions)
        needsLayout = true
    }

    /// A content's own height: its intrinsic one, else what its own constraints give it (a sidebar
    /// row's 20 pt), else the row's.
    private static func height(of content: NSView, in room: Double) -> Double {
        let intrinsic = Double(content.intrinsicContentSize.height)
        if intrinsic != Double(NSView.noIntrinsicMetric) { return intrinsic }
        let fitting = Double(content.fittingSize.height)
        return fitting > 0 ? min(fitting, room) : room
    }

    /// The content from the row's leading inset, its own height centred on the row under any
    /// hairline; the actions at the trailing inset, as wide as they fit (under a column, at most the
    /// column, the note giving way first); the content up to the actions less the gap, or up to the
    /// column. Mirrored right to left.
    override func layout() {
        super.layout()
        let width = Double(bounds.width), top = rowStyle.hairline ? 1.0 : 0, room = Double(bounds.height) - top
        let leading = rowStyle.margin + rowStyle.leading, trailing = rowStyle.margin + rowStyle.trailing
        let actionsWidth = column.map { min(actions.fittingWidth, $0) } ?? actions.fittingWidth
        let contentEnd = column.map { width - trailing - $0 } ?? width - trailing - actionsWidth - gap
        let contentHeight = Self.height(of: content, in: room), actionsHeight = actions.fittingHeight
        func place(_ view: NSView, from start: Double, width: Double, height: Double) {
            let x = userInterfaceLayoutDirection == .rightToLeft ? Double(bounds.width) - start - width : start
            let below = (room - height) / 2
            let frame = backingAlignedRect(NSRect(x: x, y: isFlipped ? top + below : below, width: max(width, 0), height: height), options: .alignAllEdgesNearest)
            if view.frame != frame { view.frame = frame }
        }
        place(content, from: leading, width: contentEnd - leading, height: contentHeight)
        place(actions, from: width - trailing - actionsWidth, width: actionsWidth, height: actionsHeight)
    }

    /// The actions shown (over 120 ms, at once while motion is reduced) or gone; the primary loud
    /// while the row is chosen and the one primary is not elsewhere (`quiet`), and Return its key
    /// (shown as ⏎) only while the list holds the keys.
    func show(_ shown: Bool, chosen: Bool, keys: Bool, quiet: Bool = false) {
        if let first {
            let kind: ButtonKind = chosen && !quiet ? .primary : .secondary, key = chosen && keys && returnKey ? "\r" : ""
            if first.kind != kind { first.kind = kind }
            if first.keyEquivalent != key { first.keyEquivalent = key }
        }
        if shown, let owed = moreOwed {
            moreOwed = nil
            actions.append(Self.more(owed.actions, rowStyle: rowStyle, look: owed.look))
            gap = 8
            needsLayout = true
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
}

extension RowCell {
    /// ⋯, opening the menu of `actions` under it.
    static func more(_ actions: [SailList.Action], rowStyle: RowStyle, look: Look) -> SailButton {
        let more = SailButton(icon: .more, label: "More", size: rowStyle == .tree ? .row : .small, look: look, target: MenuOpener.shared, action: #selector(MenuOpener.open(_:)))
        more.menu = RowMenu(actions, look: look)
        return more
    }
}

/// A row's actions, as a trailing stack lays them out but by arithmetic: each view at its own
/// alignment width and height, 6 pt apart (10 after a note), centred, packed to the trailing end; a
/// note gives up width first when the actions are given less than they fit. Mirrored right to left.
@MainActor
final class RowActions: NSView {
    static let spacing = 6.0, afterNote = 10.0

    private(set) var views: [NSView] = []
    private var note: NSView?

    override var isFlipped: Bool {
        true
    }

    /// Its views as `views`: a view of its last that another row now shows (an item's accessory
    /// shown again through another cell) is left there.
    func set(_ views: [NSView], note: NSView?) {
        self.views.filter { $0.superview === self }.forEach { $0.removeFromSuperview() }
        self.views = views
        self.note = note
        views.forEach { addSubview($0) }
        needsLayout = true
    }

    /// `view` added at the trailing end.
    func append(_ view: NSView) {
        views.append(view)
        addSubview(view)
        needsLayout = true
    }

    private func size(of view: NSView) -> NSSize {
        let intrinsic = view.intrinsicContentSize
        return NSSize(width: max(intrinsic.width, 0), height: max(intrinsic.height, 0))
    }

    private func spacing(after view: NSView) -> Double {
        view === note ? Self.afterNote : Self.spacing
    }

    var fittingWidth: Double {
        views.enumerated().reduce(0) { total, pair in
            total + Double(size(of: pair.element).width) + (pair.offset < views.count - 1 ? spacing(after: pair.element) : 0)
        }
    }

    var fittingHeight: Double {
        views.map { Double(size(of: $0).height) }.max() ?? 0
    }

    override func layout() {
        super.layout()
        let shortfall = max(fittingWidth - Double(bounds.width), 0), height = Double(bounds.height)
        var end = Double(bounds.width)
        for (index, view) in views.enumerated().reversed() {
            var size = size(of: view)
            if view === note { size.width = max(Double(size.width) - shortfall, 0) }
            let alignment = NSRect(x: end - Double(size.width), y: (height - Double(size.height)) / 2, width: Double(size.width), height: Double(size.height))
            var frame = view.frame(forAlignmentRect: alignment)
            if userInterfaceLayoutDirection == .rightToLeft { frame.origin.x = bounds.width - frame.maxX }
            frame = backingAlignedRect(frame, options: .alignAllEdgesNearest)
            if view.frame != frame { view.frame = frame }
            end = Double(alignment.minX) - (index > 0 ? spacing(after: views[index - 1]) : 0)
        }
    }
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

/// An action as a target: buttons and menu items send `go(_:)`, and a menu item is on only while
/// its action can be done.
@MainActor
package final class Run: NSObject, NSMenuItemValidation {
    package let action: SailList.Action

    package init(_ action: SailList.Action) {
        self.action = action
    }

    package func validateMenuItem(_: NSMenuItem) -> Bool {
        action.enabled()
    }

    @objc package func go(_: Any?) {
        if action.enabled() { action.run() }
    }
}

/// Opens the menu a button carries, 2 pt under its box from its leading corner, as a pop-up does;
/// right to left the menu is laid out so and hangs from the box's right corner.
@MainActor
package final class MenuOpener: NSObject {
    package static let shared = MenuOpener()

    /// AppKit hangs a popped-up menu by its first item, not its edge, so the menu's own top padding
    /// (5 pt, as the mockup's native menu has it) stands above the location given. AppKit says this
    /// padding nowhere a program can read it; the drive's real click on ⋯ checks the menu's edge on
    /// screen, so a macOS that changes it fails there.
    static let menuPadding = 5.0

    @objc package func open(_ sender: NSButton) {
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
