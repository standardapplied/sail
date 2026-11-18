import AppKit
import SailAPI
import SailControls
import SailLook

/// A project's Workspaces view, as the approved design draws it: its name and the line under it,
/// List Again and New Workspace at the top right, a banner when something went wrong, then the list
/// (yours, then other people's, the create row first among yours), an empty state, or skeleton rows;
/// a foot with the count and the keys. It draws what ``WorkspacesModel`` says and sends the person's
/// actions back; it keeps nothing of its own. Narrower than its wide rows' columns leave a name room
/// for, as at the minimum window, it is drawn as the design's narrow frame: the line under the name
/// goes, New Workspace is an icon, and rows drop the owner's column.
@MainActor
package final class ProjectView: NSView, NSTextFieldDelegate {
    package enum Act: Equatable {
        case open(String), startAndOpen(String), copyName(String)
        case newWorkspace, listAgain, checkAgain, typed(String), create, cancelCreate, chose(String?), show(String)
    }

    static let padding = (top: 26.0, sides: 32.0, bottom: 20.0), listGap = 18.0, footGap = 12.0, actionsColumn = 214.0
    /// The room between the name's column and the buttons (the design's `.ph` gap).
    static let headGap = 20.0
    /// The least width drawn wide: the wide row's columns, insets and frame, and 150 pt for a name.
    static let wide = 2 * padding.sides + 2 + RowStyle.list.leading + RowStyle.list.trailing + WorkspaceLine.pillColumn + WorkspaceLine.ownerColumn
        + actionsColumn + 150

    package var on: @MainActor (Act) -> Void = { _ in }
    /// Its look; a change repaints what it holds, and the next render draws its words in it.
    var look: Look {
        didSet { relook() }
    }
    package let list: SailList
    let title = NSTextField(labelWithString: "")
    let lede = NSTextField(labelWithString: "")
    let listAgain: SailButton
    package let newWorkspace: SailButton
    let newWorkspaceIcon: SailButton
    package let field: SailTextField
    let words = NSStackView()
    let buttons = NSStackView()
    let count = NSTextField(labelWithString: "")
    private(set) var narrow = false {
        didSet { if narrow != oldValue, let page { render(page) } }
    }

    private let mono: LabelFont
    private let titleFont: TitleFont
    private let column = NSStackView()
    private let head = NSView()
    private let foot = NSView()
    private let frameView = ListFrame()
    /// The head, the list and the foot span the column, whatever their content's width.
    private lazy var spans = [head, foot, frameView].map { $0.widthAnchor.constraint(equalTo: column.widthAnchor) }
    private var page: WorkspacesModel.Page?
    /// The empty state drawn alone, centred, when no project is open.
    private var centred: NSView?

    init(look: Look, mono: LabelFont, titleFont: TitleFont) {
        self.look = look
        self.mono = mono
        self.titleFont = titleFont
        list = SailList(rowStyle: .list, look: look, mono: mono)
        listAgain = SailButton(icon: .refresh, label: "List Again", look: look, target: nil, action: nil)
        newWorkspace = SailButton("New Workspace", kind: .secondary, icon: .plus, look: look, target: nil, action: nil)
        newWorkspaceIcon = SailButton(icon: .plus, label: "New Workspace", kind: .secondary, look: look, target: nil, action: nil)
        field = SailTextField(label: nil, placeholder: "name of a new workspace", look: look, mono: mono)
        super.init(frame: .zero)
        listAgain.shortcut("r", modifiers: .command, mono: mono)
        newWorkspace.keyEquivalent = "n"
        newWorkspace.keyEquivalentModifierMask = .command
        newWorkspaceIcon.shortcut("n", modifiers: .command, mono: mono)
        for button in [listAgain, newWorkspace, newWorkspaceIcon] {
            button.target = self
            button.action = #selector(pressed(_:))
        }
        list.onChoose = { [weak self] in self?.on(.chose($0)) }
        field.field.delegate = self
        field.boxWidth = CreateRow.box
        for label in [title, lede] {
            label.lineBreakMode = .byTruncatingTail
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        frameView.look = look
        frameView.addSubview(list)
        buildHead()
        buildFoot()
        column.orientation = .vertical
        column.alignment = .width
        column.setClippingResistancePriority(.defaultLow, for: .horizontal)
        column.spacing = 0
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        let above = column.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -Self.padding.bottom)
        above.priority = WorkspacesView.yields
        NSLayoutConstraint.activate([
            column.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Self.padding.sides),
            column.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -Self.padding.sides),
            column.topAnchor.constraint(equalTo: topAnchor, constant: Self.padding.top), above,
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    /// Drawn narrow or wide by its own width, redrawn only when that changes.
    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        if newSize.width > 0 { narrow = Double(newSize.width) < Self.wide }
    }

    /// Draws `page`.
    func render(_ page: WorkspacesModel.Page) {
        self.page = page
        title.attributedStringValue = NSAttributedString(string: page.name, attributes: [
            .font: titleFont.font(.title1) as NSFont, .foregroundColor: NSColor(look.text), .kern: -0.012 * Look.Step.title1.points,
        ])
        lede.attributedStringValue = NSAttributedString(string: page.lede, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ])
        lede.isHidden = narrow
        newWorkspace.isHidden = narrow
        newWorkspaceIcon.isHidden = !narrow
        for button in [newWorkspace, newWorkspaceIcon] {
            button.isEnabled = page.canCreate
            button.why = page.createWhy
        }
        newWorkspace.kind = Self.isEmptyList(page.body) ? .primary : .secondary
        centred?.removeFromSuperview()
        var views: [NSView] = [head, spacer(Self.listGap)]
        if let notice = page.banner { views += [banner(notice), spacer(12)] }
        switch page.body {
        case .loading: views.append(loadingList)
        case .unlisted: break
        case let .empty(empty): views.append(Self.framed(emptyState(empty), look: look))
        case let .list(table):
            fill(table)
            frameView.alphaValue = page.stale ? 0.55 : 1
            let listed = table.count + (table.creating == nil ? 0 : 1)
            count.attributedStringValue = micro(page.stale ? Self.listed(page.listedAt) : "\(listed) workspace\(listed == 1 ? "" : "s")")
            views += [frameView, spacer(Self.footGap), foot]
        }
        show(views)
    }

    private func relook() {
        list.look = look
        frameView.look = look
        field.look = look
        for button in [listAgain, newWorkspace, newWorkspaceIcon] {
            button.look = look
        }
        for skeleton in Self.found(Skeleton.self, in: loadingWords) {
            skeleton.look = look
        }
        loadingRows.look = look
        loadingList.look = look
        buildFoot()
        if let page { render(page) }
    }

    private static func found<Found: NSView>(_ kind: Found.Type, in view: NSView) -> [Found] {
        if let view = view as? Found { return [view] }
        return view.subviews.flatMap { found(kind, in: $0) }
    }

    /// `views` down the column, each spanning it but the spacers.
    private func show(_ views: [NSView]) {
        column.setViews(views, in: .top)
        NSLayoutConstraint.activate(spans.filter { span in views.contains { $0 === span.firstItem } })
        let transient = views.filter { view in view is ListFrame && view !== frameView }
        NSLayoutConstraint.activate(transient.map { $0.widthAnchor.constraint(equalTo: column.widthAnchor) })
    }

    /// The design's loading frame, before any project is listed: bars where the name and the line
    /// under it will be, and the list's skeleton.
    func renderLoading() {
        page = nil
        centred?.removeFromSuperview()
        show([loadingWords, spacer(Self.listGap + 11), loadingList])
    }

    /// Bars for the name and the line under it, made once: a skeleton shows 150 ms after it enters
    /// the window, so one drawn anew at each redraw (a change of look) would never be seen.
    private lazy var loadingWords: NSStackView = {
        let words = NSStackView(views: [Skeleton(width: 160, height: 22, look: look), Skeleton(width: 340, height: 8, look: look)])
        words.orientation = .vertical
        words.alignment = .leading
        words.spacing = 14
        return words
    }()

    /// The list's skeleton, made once for the same reason.
    private lazy var loadingRows = SkeletonRows(look: look)
    private lazy var loadingList = Self.framed(loadingRows, height: SkeletonRows.height, look: look)

    /// The name field given the keys with the caret after its name: every create row drawn holds the
    /// one field, so a redraw while typing takes it out of the window and the keys with it.
    func focusField() {
        layoutSubtreeIfNeeded()
        guard window?.makeFirstResponder(field.field) == true, let editor = field.field.currentEditor() else { return }
        editor.selectedRange = NSRange(location: (field.field.stringValue as NSString).length, length: 0)
    }

    package func controlTextDidChange(_: Notification) {
        on(.typed(field.field.stringValue))
    }

    /// Return creates and Esc cancels, from the name field.
    package func control(_: NSControl, textView _: NSTextView, doCommandBy selector: Selector) -> Bool {
        switch selector {
        case #selector(NSResponder.insertNewline(_:)): on(.create)
        case #selector(NSResponder.cancelOperation(_:)): on(.cancelCreate)
        default: return false
        }
        return true
    }

    /// Draws only `empty`, centred in the view: no project to show.
    func renderEmpty(_ empty: WorkspacesModel.Empty) {
        page = nil
        show([])
        centred?.removeFromSuperview()
        let state = emptyState(empty)
        state.translatesAutoresizingMaskIntoConstraints = false
        addSubview(state)
        let wide = state.widthAnchor.constraint(equalToConstant: WorkspacesView.cardWidth)
        wide.priority = WorkspacesView.yields
        NSLayoutConstraint.activate([
            state.centerXAnchor.constraint(equalTo: centerXAnchor), state.centerYAnchor.constraint(equalTo: centerYAnchor), wide,
            state.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -2 * Self.padding.sides),
        ])
        centred = state
    }

    /// `content` in the list's frame: at `height`, or, with none, at its own height 52 pt in from the
    /// frame's top and bottom (an empty state in place of the list).
    private static func framed(_ content: NSView, height: Double? = nil, look: Look) -> ListFrame {
        let frame = ListFrame()
        frame.look = look
        frame.height = height
        frame.addSubview(content)
        guard height == nil else { return frame }
        content.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: frame.leadingAnchor), content.trailingAnchor.constraint(equalTo: frame.trailingAnchor),
            content.topAnchor.constraint(equalTo: frame.topAnchor, constant: 52), content.bottomAnchor.constraint(equalTo: frame.bottomAnchor, constant: -52),
        ])
        return frame
    }

    private static func isEmptyList(_ body: WorkspacesModel.Body) -> Bool {
        if case let .empty(empty) = body { return empty.action == .newWorkspace }
        return false
    }

    private static func listed(_ at: Date?) -> String {
        "listed " + (at.map { Ago.said(Date().timeIntervalSince($0)) } ?? "earlier")
    }

    /// The list's entries: Yours (the create row first), then Other people's.
    private func fill(_ table: WorkspacesModel.Table) {
        list.actionsColumn = narrow ? nil : Self.actionsColumn
        let creating = table.creating == nil ? 0 : 1
        var entries: [SailList.Entry] = [.head("Yours", count: table.yours.count + creating)]
        if let creating = table.creating {
            entries.append(.item(creatingItem(creating)))
        } else if let create = table.create {
            entries.append(.item(createItem(create)))
        }
        entries += table.yours.map { .item(item($0)) }
        if !table.others.isEmpty { entries += [.head("Other people's", count: table.others.count)] + table.others.map { .item(item($0)) } }
        list.entries = entries
        list.choose((table.yours + table.others).first(where: \.chosen)?.name)
        let height = entries.indices.reduce(0.0) { $0 + Double(list.tableView(list, heightOfRow: $1)) }
        list.frame = NSRect(x: 0, y: 0, width: Double(frameView.bounds.width), height: height)
        frameView.height = height
    }

    private func item(_ line: WorkspacesModel.Line) -> SailList.Item {
        let content = WorkspaceLine(line, narrow: narrow, look: look, mono: mono)
        let actions: [SailList.Action] = line.menu.map { entry in
            switch entry {
            case .open: SailList.Action("Open") { [weak self] in self?.on(.open(line.name)) }
            case .startAndOpen: SailList.Action("Start and Open") { [weak self] in self?.on(.startAndOpen(line.name)) }
            case .copyName: SailList.Action("Copy Name", key: "c", modifiers: .command, onRow: false) { [weak self] in self?.on(.copyName(line.name)) }
            }
        }
        let said = [line.name, WorkspaceLine.pill(line.state).label.lowercased()] + (line.owner.map { [$0] } ?? [])
        return SailList.Item(id: line.name, name: line.name, voice: said.joined(separator: ", "), content: content, actions: actions, note: line.why)
    }

    /// The create under way as the row it will be, in the quiet chosen fill: its name and instance,
    /// Creating, "about 3 s" with a spinner, and the progress line along its foot.
    private func creatingItem(_ line: WorkspacesModel.Line) -> SailList.Item {
        let content = WorkspaceLine(line, narrow: narrow, look: look, mono: mono)
        let paint = RowPaint(fill: look.fillSelectedQuiet, bar: false, actions: true, progress: true)
        let voice = [line.name, WorkspaceLine.pill(line.state).label.lowercased(), line.why].compactMap { $0 }.joined(separator: ", ")
        return SailList.Item(id: line.name, name: line.name, voice: voice, content: content, note: line.why, paint: paint, accessory: Spinner(look: look))
    }

    /// The create row, taking the whole row: drawn chosen while open, in the error's tint once refused.
    private func createItem(_ create: WorkspacesModel.CreateLine) -> SailList.Item {
        let refused = !create.refusal.isEmpty
        field.error = refused ? create.refusal.joined(separator: " ") : nil
        field.help = WorkspacesModel.nameRule
        field.isEnabled = !create.underWay
        if field.field.stringValue != create.name { field.field.stringValue = create.name }
        let row = CreateRow(field: field, underWay: create.underWay, existing: create.existing, look: look, mono: mono) { [weak self] act in self?.on(act) }
        let room = Double(bounds.width) - 2 * Self.padding.sides - 2 - RowStyle.list.leading - RowStyle.list.trailing
        let height = row.height(room: bounds.width > 0 ? room : CreateRow.column + CreateRow.gap + Double(row.buttons.fittingSize.width))
        let paint = refused ? RowPaint(fill: Tint(look.error, alpha: 0.05), bar: false) : RowPaint(fill: look.fillSelectedQuiet, bar: true)
        return SailList.Item(id: "\u{0}create", name: "", voice: "A new workspace's name", content: row, height: height, paint: paint, full: true)
    }

    /// The name and the line under it, taking the room the buttons leave (less the design's 20 pt):
    /// the line is one line where it fits and wraps below that, as a flex row's first child does.
    private func buildHead() {
        words.orientation = .vertical
        words.setViews([title, lede], in: .top)
        words.alignment = .leading
        words.spacing = 6
        words.setClippingResistancePriority(.defaultLow, for: .horizontal)
        buttons.setViews([listAgain, newWorkspace, newWorkspaceIcon], in: .leading)
        buttons.spacing = 6 - 2 * Look.haloRoom
        buttons.setHuggingPriority(.required, for: .horizontal)
        for view in [words, buttons] {
            view.translatesAutoresizingMaskIntoConstraints = false
            head.addSubview(view)
        }
        let hug = head.heightAnchor.constraint(equalToConstant: 0)
        hug.priority = .fittingSizeCompression
        NSLayoutConstraint.activate([
            words.leadingAnchor.constraint(equalTo: head.leadingAnchor), words.topAnchor.constraint(equalTo: head.topAnchor),
            buttons.trailingAnchor.constraint(equalTo: head.trailingAnchor), buttons.topAnchor.constraint(equalTo: head.topAnchor),
            buttons.leadingAnchor.constraint(equalTo: words.trailingAnchor, constant: Self.headGap),
            head.bottomAnchor.constraint(greaterThanOrEqualTo: words.bottomAnchor), head.bottomAnchor.constraint(greaterThanOrEqualTo: buttons.bottomAnchor), hug,
        ])
    }

    /// The count (or when the list was last listed) at the left, the keys at the right.
    private func buildFoot() {
        foot.subviews.forEach { $0.removeFromSuperview() }
        var shown: [NSView] = []
        for (key, words) in [("⏎", "Open"), ("⌘N", "New"), ("⌘R", "List again")] {
            shown.append(Keycap(key, look: look, mono: mono))
            shown.append(NSTextField(labelWithAttributedString: micro(words)))
        }
        let keys = NSStackView(views: shown)
        keys.spacing = 6
        keys.setClippingResistancePriority(.defaultLow, for: .horizontal)
        count.lineBreakMode = .byTruncatingTail
        count.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        for view in [count, keys] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            foot.addSubview(view)
        }
        NSLayoutConstraint.activate([
            count.leadingAnchor.constraint(equalTo: foot.leadingAnchor), count.centerYAnchor.constraint(equalTo: keys.centerYAnchor),
            count.trailingAnchor.constraint(lessThanOrEqualTo: keys.leadingAnchor, constant: -12),
            keys.trailingAnchor.constraint(equalTo: foot.trailingAnchor), keys.topAnchor.constraint(equalTo: foot.topAnchor),
            keys.bottomAnchor.constraint(equalTo: foot.bottomAnchor),
        ])
    }

    private func banner(_ notice: WorkspacesModel.Notice) -> Banner {
        let again = notice.tryAgain ? SailButton("Try Again", kind: .secondary, size: .small, look: look, target: self, action: #selector(pressed(_:))) : nil
        return Banner(notice.tone == .error ? .error : .warning, title: notice.title, line: notice.line.isEmpty ? nil : notice.line, action: again, look: look)
    }

    private func emptyState(_ empty: WorkspacesModel.Empty) -> NSView {
        let action = empty.action == .newWorkspace
            ? SailButton("New Workspace", kind: .primary, icon: .plus, look: look, target: self, action: #selector(pressed(_:)))
            : SailButton("Check Again", kind: .secondary, look: look, target: self, action: #selector(pressed(_:)))
        action.shortcut(empty.action == .newWorkspace ? "n" : "r", modifiers: .command, mono: mono)
        return EmptyState(icon: empty.missing ? .layers : .box, title: empty.title, body: empty.body, actions: [action], line: empty.line, lineFirst: true, missing: empty.missing, look: look, titleFont: titleFont, mono: mono)
    }

    /// Words in mono 11, tertiary: the foot's.
    private func micro(_ words: String) -> NSAttributedString {
        NSAttributedString(string: words, attributes: [.font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary)])
    }

    private func spacer(_ height: Double) -> NSView {
        let view = NSView()
        view.heightAnchor.constraint(equalToConstant: height).isActive = true
        return view
    }

    @objc private func pressed(_ sender: NSButton) {
        switch sender {
        case listAgain: on(.listAgain)
        case newWorkspace, newWorkspaceIcon: on(.newWorkspace)
        default:
            switch sender.title {
            case "New Workspace": on(.newWorkspace)
            case "Check Again", "Try Again": on(.checkAgain)
            default: break
            }
        }
    }
}

/// The list's frame: the surface in 6 pt corners with a hairline, holding the list at its height;
/// with no height, what it holds is placed by constraints.
@MainActor
final class ListFrame: NSView {
    var height: Double? = 0 {
        didSet { invalidateIntrinsicContentSize() }
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: height.map { $0 + 2 } ?? NSView.noIntrinsicMetric)
    }

    var look = Look.light {
        didSet { needsDisplay = true }
    }

    override var isFlipped: Bool {
        true
    }

    override func layout() {
        super.layout()
        guard let height else { return }
        subviews.first?.frame = NSRect(x: 1, y: 1, width: Double(bounds.width) - 2, height: height)
    }

    override func draw(_: NSRect) {
        let shape = NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: Look.contentRadius - 0.5, yRadius: Look.contentRadius - 0.5)
        NSColor(Tint(look.surface)).setFill()
        shape.fill()
        NSColor(look.separator).setStroke()
        shape.stroke()
    }
}

/// The list before it is listed, as the design's loading frame draws it: the head and three rows,
/// with skeleton bars where a row's name, instance, pill and owner will be, in the wide row's columns.
@MainActor
final class SkeletonRows: NSView {
    static let words: [(name: Double, place: Double)] = [(64, 104), (48, 88), (80, 120)]
    static let height = RowStyle.groupHeight + Double(words.count) * RowStyle.list.height

    var look: Look {
        didSet {
            for bar in bars.joined() {
                bar.look = look
            }
            needsDisplay = true
        }
    }

    private let bars: [[Skeleton]]

    init(look: Look) {
        self.look = look
        bars = [[Skeleton(width: 46, height: 8, look: look)]] + Self.words.map { words in
            [Skeleton(width: words.name, look: look), Skeleton(width: words.place, height: 8, look: look), Skeleton(width: 74, height: 18, look: look), Skeleton(width: 60, look: look)]
        }
        super.init(frame: .zero)
        for bar in bars.joined() {
            addSubview(bar)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// Each row's pill where the wide row's pill column starts, its owner after it, and the name's
    /// two lines at the row's leading inset.
    override func layout() {
        super.layout()
        let leading = RowStyle.list.leading, pill = max(leading + 130, Double(bounds.width) - RowStyle.list.trailing - ProjectView.actionsColumn - WorkspaceLine.ownerColumn - WorkspaceLine.pillColumn)
        bars[0][0].frame = NSRect(x: leading, y: (RowStyle.groupHeight - 8) / 2, width: 46, height: 8)
        for (index, row) in bars.dropFirst().enumerated() {
            let top = RowStyle.groupHeight + Double(index) * RowStyle.list.height
            let at = [(leading, top + 14), (leading, top + 30), (pill, top + 17), (pill + WorkspaceLine.pillColumn, top + 21)]
            for (bar, (x, y)) in zip(row, at) {
                bar.frame = NSRect(origin: NSPoint(x: x, y: y), size: bar.intrinsicContentSize)
            }
        }
    }

    override func draw(_: NSRect) {
        NSColor(look.fillHover).setFill()
        NSRect(x: 0, y: 0, width: Double(bounds.width), height: RowStyle.groupHeight).fill()
        NSColor(look.gridLine).setFill()
        for index in Self.words.indices {
            NSRect(x: 0, y: RowStyle.groupHeight + Double(index) * RowStyle.list.height, width: Double(bounds.width), height: 1).fill()
        }
    }
}

/// The create row as the approved frames draw it: the name field (a 250 pt box) with the server's rule
/// under it, or its refusal running the column's width (at most 440 pt), and, when the name is
/// already listed, a line under that: "Show dock or choose another name."; Cancel and Create with ⏎
/// at the row's end, level with the box. While it is under way the field waits and Create says so.
@MainActor
final class CreateRow: NSView {
    static let column = 440.0, box = 250.0, gap = 16.0, padding = 12.0, below = 7.0

    let field: SailTextField
    let cancel: SailButton
    let create: SailButton
    let buttons: NSStackView
    /// "Show dock or choose another name.", for a name already listed.
    let showLine: NSStackView?
    /// The field's width, held against this row rather than on the field, which every create row
    /// shares: the constraint goes when the field moves to the next row.
    private var fieldWidth: NSLayoutConstraint?

    init(field: SailTextField, underWay: Bool, existing: String?, look: Look, mono: LabelFont, on: @escaping @MainActor (ProjectView.Act) -> Void) {
        let runs = Runs(on)
        let cancel = SailButton("Cancel", kind: .quiet, size: .small, look: look, target: runs, action: #selector(Runs.cancel(_:)))
        let create = SailButton("Create", kind: .primary, size: .small, look: look, target: runs, action: #selector(Runs.create(_:)))
        create.shortcut("\r", mono: mono)
        create.keyEquivalent = ""
        if underWay { create.fly("Creating") }
        cancel.isEnabled = !underWay
        let buttons = NSStackView(views: [cancel, create])
        buttons.spacing = 6 - 2 * Look.haloRoom
        self.field = field
        self.cancel = cancel
        self.create = create
        self.buttons = buttons
        showLine = existing.map { name in
            runs.existing = name
            return Self.showLine(name, look: look, target: runs)
        }
        super.init(frame: .zero)
        self.runs = runs
        var views: [NSView] = [field, buttons]
        if let showLine { views.append(showLine) }
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        let fieldWidth = field.trailingAnchor.constraint(equalTo: leadingAnchor, constant: Self.column)
        fieldWidth.priority = .defaultHigh
        self.fieldWidth = fieldWidth
        var constraints = [
            field.leadingAnchor.constraint(equalTo: leadingAnchor), field.topAnchor.constraint(equalTo: topAnchor), fieldWidth,
            field.trailingAnchor.constraint(lessThanOrEqualTo: buttons.leadingAnchor, constant: -Self.gap),
            buttons.trailingAnchor.constraint(equalTo: trailingAnchor),
            buttons.topAnchor.constraint(equalTo: field.topAnchor, constant: (field.size.height - ButtonSize.small.height) / 2),
        ]
        if let showLine {
            constraints += [
                showLine.leadingAnchor.constraint(equalTo: field.leadingAnchor), showLine.topAnchor.constraint(equalTo: field.bottomAnchor, constant: Self.below),
                showLine.trailingAnchor.constraint(lessThanOrEqualTo: buttons.leadingAnchor, constant: -Self.gap),
            ]
        }
        NSLayoutConstraint.activate(constraints)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// The row's height when its content has `room` across, and its column the width that leaves:
    /// the field with its line, the line under that, and 12 pt above and below; never under a list row's.
    func height(room: Double) -> Double {
        let width = max(min(Self.column, room - Self.gap - Double(buttons.fittingSize.width)), 1)
        fieldWidth?.constant = width
        field.setFrameSize(NSSize(width: width + 2 * Look.haloRoom, height: Double(field.frame.height)))
        return max(RowStyle.list.height, (2 * Self.padding + words).rounded(.up))
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: words)
    }

    /// The field with the line under its box, and the show line under that.
    private var words: Double {
        Double(field.intrinsicContentSize.height) + (showLine.map { Self.below + Double($0.fittingSize.height) } ?? 0)
    }

    private static func showLine(_ name: String, look: Look, target: Runs) -> NSStackView {
        let font = NSFont.systemFont(ofSize: Look.Step.footnote.points)
        let show = NSButton(title: "Show \(name)", target: target, action: #selector(Runs.show(_:)))
        show.isBordered = false
        show.attributedTitle = NSAttributedString(string: "Show \(name)", attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.footnote.points, weight: .medium), .foregroundColor: NSColor(look.accentText),
        ])
        let rest = NSTextField(labelWithAttributedString: NSAttributedString(string: "or choose another name.", attributes: [.font: font, .foregroundColor: NSColor(look.textTertiary)]))
        let line = NSStackView(views: [show, rest])
        line.spacing = 4
        line.alignment = .firstBaseline
        return line
    }

    private var runs: Runs?

    /// The row's buttons' target.
    @MainActor
    final class Runs: NSObject {
        let on: @MainActor (ProjectView.Act) -> Void
        var existing: String?

        init(_ on: @escaping @MainActor (ProjectView.Act) -> Void) {
            self.on = on
        }

        @objc func cancel(_: Any?) {
            on(.cancelCreate)
        }

        @objc func create(_: Any?) {
            on(.create)
        }

        @objc func show(_: Any?) {
            if let existing { on(.show(existing)) }
        }
    }
}
