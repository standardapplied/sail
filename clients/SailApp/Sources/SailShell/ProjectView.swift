import AppKit
import SailAPI
import SailControls
import SailLook
import SailWorkbench

/// A project's Workspaces view, as the approved design draws it: its name and the line under it,
/// List Again and New Workspace at the top right, a banner when something went wrong, then the list
/// (yours, then other people's, the create row first among yours), an empty state, a new project's
/// build card (with neither button), or skeleton rows; a foot with the count and the keys. It draws
/// what ``WorkspacesModel`` says and sends the person's actions back; it keeps nothing of its own.
/// Narrower than its wide rows' columns leave a name room for, as at the minimum window, it is drawn
/// as the design's narrow frame: the line under the name goes, New Workspace is an icon, and rows
/// drop the owner's column.
@MainActor
package final class ProjectView: NSView, NSTextFieldDelegate {
    enum Act: Equatable {
        case open(String), startAndOpen(String), newSession(String), startLast(String), copyName(String), stop(String), delete(String)
        case newWorkspace, listAgain, checkAgain, typed(String), create, cancelCreate, chose(String?), show(String), createProject, buildAgain
    }

    static let padding = (top: 26.0, sides: 32.0, bottom: 20.0), listGap = 18.0, footGap = 12.0, actionsColumn = 214.0
    /// The create row's and the creating row's ids: never a workspace's name, so neither shares an
    /// id with a listed row of the same name.
    static let createID = "\u{0}create", creatingID = "\u{0}creating"
    /// The room between the name's column and the buttons (the design's `.ph` gap), and between two
    /// buttons (`.ph-acts`, `.acts`): the stacks place the buttons' boxes, not their halos.
    static let headGap = 20.0, buttonGap = 6.0
    /// The least width drawn wide: the wide row's columns, insets and frame, and 150 pt for a name.
    static let wide = 2 * padding.sides + 2 + RowStyle.list.leading + RowStyle.list.trailing + WorkspaceLine.pillColumn + WorkspaceLine.ownerColumn
        + actionsColumn + 150

    var on: @MainActor (Act) -> Void = { _ in }
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
    let foot = NSView()
    private let frameView: ListFrame

    /// The narrow rows' actions column: the widest actions a narrow row shows (Start and Open with
    /// no keycap, which narrow rows drop; + and ⋯ beside it), measured with the gap before them, so
    /// every list's pills stand at one x, clear of the actions, and a row's name keeps the rest; a
    /// note ("Only uday or an admin opens it") is cut within it.
    lazy var narrowActionsColumn = WorkspaceLine.actionsColumn([
        SailButton("Start and Open", kind: .primary, size: .small, look: look, target: nil, action: nil),
        SailButton(icon: .plus, label: NewSessionWords.newSession, size: .small, look: look, target: nil, action: nil),
        SailButton(icon: .more, label: "More", size: .small, look: look, target: nil, action: nil),
    ])
    /// The project whose rows the list last held: its rows drawn again keep the person's scroll;
    /// another project's, or anyone's after a change of person, start at the top.
    private var filled: String?

    /// Someone else signed in: whatever list comes next starts at its top.
    func forgetList() {
        filled = nil
    }

    /// The create row brought into view with the Yours head over it, as it opens or comes back
    /// refused; not on every redraw while it is open.
    func showCreateRow() {
        list.scrollRowToVisible(0)
    }
    /// The list's scroll view, for the drive.
    package var listScroll: NSScrollView? {
        frameView.scroll
    }
    /// The head, the list, the foot, the loading bars and the build card span the column, whatever
    /// their content's width.
    private lazy var spans = [head, foot, frameView, loadingWords, loadingList, buildFrame].map { $0.widthAnchor.constraint(equalTo: column.widthAnchor) }
    /// The build card, made once: the mark drawing in it keeps drawing across the reads that redraw it.
    private(set) lazy var buildCard: BuildCard = {
        let card = BuildCard(look: look, titleFont: titleFont, mono: mono)
        card.onBuildAgain = { [weak self] in self?.on(.buildAgain) }
        return card
    }()

    /// The build card's frame, as tall as the card at its width, which scrolls it where the window is
    /// too short for it.
    private(set) lazy var buildFrame = ListFrame(scrolling: buildCard, look: look) { [card = self.buildCard] in card.height(width: $0) }
    private var page: WorkspacesModel.Page?
    /// The empty state drawn alone, centred, when no project is open.
    private var centred: NSView?

    init(look: Look, mono: LabelFont, titleFont: TitleFont) {
        self.look = look
        self.mono = mono
        self.titleFont = titleFont
        list = SailList(rowStyle: .list, look: look, mono: mono)
        frameView = ListFrame(scrolling: list, look: look)
        listAgain = SailButton(icon: .refresh, label: "List Again", look: look, target: nil, action: nil)
        newWorkspace = SailButton("New Workspace", kind: .secondary, icon: .plus, look: look, target: nil, action: nil)
        newWorkspaceIcon = SailButton(icon: .plus, label: "New Workspace", kind: .secondary, look: look, target: nil, action: nil)
        field = SailTextField(label: nil, placeholder: "name of a new workspace", look: look, mono: mono)
        super.init(frame: .zero)
        listAgain.shortcut("r", modifiers: .command, mono: mono)
        newWorkspace.keyEquivalent = "n"
        newWorkspace.keyEquivalentModifierMask = .command
        newWorkspaceIcon.shortcut("n", modifiers: .command, mono: mono)
        listAgain.target = self
        listAgain.action = #selector(askAgain(_:))
        for button in [newWorkspace, newWorkspaceIcon] {
            button.target = self
            button.action = #selector(beginCreate(_:))
        }
        list.onChoose = { [weak self] in
            self?.on(.chose($0))
            self?.showKeys()
        }
        list.onKey = { [weak self] event in self?.newSession(event) ?? false }
        list.onKeys = { [weak self] in self?.showKeys() }
        field.field.delegate = self
        field.boxWidth = CreateRow.box
        for label in [title, lede] {
            label.lineBreakMode = .byTruncatingTail
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        title.lineBreakMode = .byTruncatingMiddle
        buildHead()
        buildFoot()
        foot.heightAnchor.constraint(equalToConstant: Keycap.height).isActive = true
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
        drawn = .page(page)
        ages = nil
        title.attributedStringValue = NSAttributedString(string: page.name, attributes: [
            .font: titleFont.font(.title1) as NSFont, .foregroundColor: NSColor(look.text), .kern: -0.012 * Look.Step.title1.points,
        ])
        title.toolTip = page.name
        let building = if case .build = page.body { true } else { false }
        lede.attributedStringValue = NSAttributedString(string: page.lede, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ])
        lede.isHidden = narrow
        listAgain.isHidden = building
        newWorkspace.isHidden = narrow || building
        newWorkspaceIcon.isHidden = !narrow || building
        for button in [newWorkspace, newWorkspaceIcon] {
            button.isEnabled = page.canCreate
            button.why = page.createWhy
        }
        centred?.removeFromSuperview()
        var views: [NSView] = [head, Self.spacer(Self.listGap)]
        if let notice = page.banner { views += [banner(notice), Self.spacer(12)] }
        switch page.body {
        case .loading: views.append(loadingList)
        case .unlisted: break
        case let .empty(empty): views.append(Self.framed(emptyState(empty), look: look))
        case let .build(build):
            if buildCard.build != build {
                buildCard.show(build)
                buildFrame.invalidateIntrinsicContentSize()
                buildFrame.needsLayout = true
            }
            views.append(buildFrame)
        case let .list(table):
            if let scroll = frameView.scroll, filled == page.name {
                scroll.keepingScroll { fill(table) }
            } else {
                fill(table)
                frameView.scroll?.scrollToTop()
            }
            filled = page.name
            frameView.alphaValue = page.stale ? 0.55 : 1
            let listed = table.count + (table.creating == nil ? 0 : 1)
            count.attributedStringValue = micro(page.stale ? Self.listed(page.listedAt) : "\(listed) workspace\(listed == 1 ? "" : "s")")
            ages = page.stale ? Timer.scheduledTimer(withTimeInterval: Self.ageTick, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.retellAge() }
            } : nil
            views += [frameView, Self.spacer(Self.footGap), foot]
            showKeys()
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
        buildCard.look = look
        buildFrame.look = look
        buildFoot()
        switch drawn {
        case let .page(page)?: render(page)
        case .loading?: renderLoading()
        case let .empty(empty)?: renderEmpty(empty)
        case nil: break
        }
    }

    /// What it last drew, drawn again in a new look.
    private enum Drawn {
        case page(WorkspacesModel.Page), loading, empty(WorkspacesModel.Empty)
    }

    private var drawn: Drawn?

    /// The name field holds the keys now.
    package var fieldHasKeys: Bool {
        (window?.firstResponder as? NSTextView)?.delegate === field.field
    }

    private static func found<Found: NSView>(_ kind: Found.Type, in view: NSView) -> [Found] {
        if let view = view as? Found { return [view] }
        return view.subviews.flatMap { found(kind, in: $0) }
    }

    /// `views` down the column, each spanning it but the spacers.
    private func show(_ views: [NSView]) {
        column.setViews(views, in: .top)
        NSLayoutConstraint.activate(spans.filter { span in views.contains { $0 === span.firstItem } })
        let transient = views.filter { view in view is ListFrame && !spans.contains { $0.firstItem === view } }
        NSLayoutConstraint.activate(transient.map { $0.widthAnchor.constraint(equalTo: column.widthAnchor) })
    }

    /// The design's loading frame, before any project is listed: bars where the name and the line
    /// under it will be, and the list's skeleton.
    func renderLoading() {
        page = nil
        drawn = .loading
        ages = nil
        centred?.removeFromSuperview()
        show([loadingWords, Self.spacer(Self.listGap + 11), loadingList])
    }

    /// Bars for the name and the line under it, made once: a skeleton shows 150 ms after it enters
    /// the window, so one drawn anew at each redraw (a change of look) would never be seen.
    private lazy var loadingWords: NSStackView = {
        let bars = [Skeleton(width: 160, height: 22, look: look), Skeleton(width: 340, height: 8, look: look)]
        for bar in bars {
            bar.setContentCompressionResistancePriority(WorkspacesView.yields, for: .horizontal)
        }
        let words = NSStackView(views: bars)
        words.setClippingResistancePriority(WorkspacesView.yields, for: .horizontal)
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
        drawn = .empty(empty)
        ages = nil
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
        let frame = ListFrame(look: look)
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

    /// How often "listed … ago" is said again while the list is stale.
    static let ageTick = 15.0

    /// Says again how long ago the stale list was listed; stopped by the next redraw.
    private var ages: Timer? {
        didSet { oldValue?.invalidate() }
    }

    private func retellAge() {
        guard let page, page.stale else { return }
        count.attributedStringValue = micro(Self.listed(page.listedAt))
    }

    private static func listed(_ at: Date?) -> String {
        "listed " + (at.map { Ago.said(Date().timeIntervalSince($0)) } ?? "earlier")
    }

    /// The list's entries: Yours (the create row first), then Other people's.
    private func fill(_ table: WorkspacesModel.Table) {
        list.actionsColumn = narrow ? narrowActionsColumn : Self.actionsColumn
        list.returnKeyShown = !narrow
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
        frameView.height = height
    }

    /// ⌥⌘T on the chosen row, anyone's: the panel says what starts in it, or why nothing does.
    private func newSession(_ event: NSEvent) -> Bool {
        let held = event.modifierFlags.intersection([.command, .option, .control, .shift])
        guard held == [.command, .option], event.charactersIgnoringModifiers?.lowercased() == "t" else { return false }
        guard let name = list.chosen, ![Self.createID, Self.creatingID].contains(name) else { return true }
        on(.newSession(name))
        return true
    }

    /// Where row `name` is in the window, nil while it is not listed.
    func rowFrame(_ name: String) -> NSRect? {
        guard let row = list.entries.firstIndex(where: { if case let .item(item) = $0 { item.id == name } else { false } }) else { return nil }
        return list.convert(list.rect(ofRow: row), to: nil)
    }

    /// Row `name`'s + while it shows: what opened its panel, which a click on toggles.
    func plus(_ name: String) -> NSView? {
        guard let index = list.entries.firstIndex(where: { if case let .item(item) = $0 { item.id == name } else { false } }),
              let row = list.rowView(atRow: index, makeIfNecessary: false)
        else { return nil }
        return Self.found(SailButton.self, in: row).first { $0.title == NewSessionWords.newSession && !$0.isHiddenOrHasHiddenAncestor }
    }

    private func item(_ line: WorkspacesModel.Line) -> SailList.Item {
        let narrow = narrow, look = look, mono = mono
        let actions: [SailList.Action] = line.menu.map { entry in
            switch entry {
            case .open: SailList.Action("Open") { [weak self] in self?.on(.open(line.name)) }
            case .startAndOpen: SailList.Action("Start and Open") { [weak self] in self?.on(.startAndOpen(line.name)) }
            case let .startLast(display): SailList.Action(NewSessionWords.quickStart(display), onRow: false) { [weak self] in self?.on(.startLast(line.name)) }
            case .newSession:
                SailList.Action(NewSessionWords.newSession, key: "t", modifiers: [.command, .option], icon: .plus, busy: line.askingAgents ? NewSessionWords.asking : nil) { [weak self] in
                    self?.on(.newSession(line.name))
                }
            case .copyName: SailList.Action("Copy Name", key: "c", modifiers: .command, onRow: false) { [weak self] in self?.on(.copyName(line.name)) }
            case .stop: SailList.Action("Stop", onRow: false, startsGroup: true) { [weak self] in self?.on(.stop(line.name)) }
            case .delete:
                SailList.Action("Delete", key: "\u{8}", modifiers: .command, onRow: false, startsGroup: !line.menu.contains(.stop), destructive: true) { [weak self] in
                    self?.on(.delete(line.name))
                }
            }
        }
        var said = [line.name, WorkspaceLine.pill(line).label.lowercased()]
        if let owner = line.owner { said.append(WorkspaceLine.said(owner)) }
        if let sessions = line.sessionsSaid { said.append(sessions) }
        if let why = line.why { said.append(why) }
        return SailList.Item(
            id: line.name, name: line.name, voice: said.joined(separator: ", "), content: WorkspaceLine(line, narrow: narrow, look: look, mono: mono), actions: actions, note: line.why
        )
    }

    /// The create under way as the row it will be, in the quiet chosen fill: its name and instance,
    /// Creating, "about 3 s" with a spinner, and the progress line along its foot.
    private func creatingItem(_ line: WorkspacesModel.Line) -> SailList.Item {
        let content = WorkspaceLine(line, narrow: narrow, look: look, mono: mono)
        let voice = [line.name, WorkspaceLine.pill(line).label.lowercased(), line.why].compactMap { $0 }.joined(separator: ", ")
        return SailList.Item(
            id: Self.creatingID, name: line.name, voice: voice, content: content, note: line.why, paint: .creating, accessory: Spinner(look: look)
        )
    }

    /// The create row, taking the whole row: drawn chosen while open, in the error's tint once refused.
    private func createItem(_ create: WorkspacesModel.CreateLine) -> SailList.Item {
        let refused = !create.refusal.isEmpty
        field.error = refused ? create.refusal.joined(separator: " ") : nil
        field.help = WorkspacesModel.nameRule
        if field.field.stringValue != create.name { field.field.stringValue = create.name }
        let row = CreateRow(field: field, existing: create.existing, look: look, mono: mono) { [weak self] act in self?.on(act) }
        let room = Double(bounds.width) - 2 * Self.padding.sides - 2 - RowStyle.list.leading - RowStyle.list.trailing
        let height = row.height(room: bounds.width > 0 ? room : CreateRow.column + CreateRow.gap + Double(row.buttons.fittingSize.width))
        let paint: RowPaint = refused ? .refused : .naming
        return SailList.Item(id: Self.createID, name: "", voice: "A new workspace's name", content: row, height: height, paint: paint, full: true)
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
        buttons.spacing = Self.buttonGap
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

    /// The keys the foot shows now.
    private var footShows: [WorkspacesModel.Key] = []

    /// The keys the foot names now, for the row chosen and what holds the keys
    /// (``WorkspacesModel/Page/keys(chosen:held:)``, asked of ``KeyHolder``): the New Session panel
    /// or another list holding them, the list's own keys do nothing; a sheet over the window, or
    /// another window, holding them, none does.
    private var footKeys: [WorkspacesModel.Key] {
        page?.keys(chosen: list.chosen, held: list.holdsKeys ? .list : KeyHolder.isKey(window) ? .window : .none) ?? []
    }

    /// The foot drawn again when the keys it names changed: the row chosen, its state, the page, or
    /// what holds the keys (``SailList/onKeys``).
    func showKeys() {
        guard footKeys != footShows else { return }
        buildFoot()
    }

    /// A key as the foot names it.
    private static func said(_ key: WorkspacesModel.Key) -> (key: String, words: String) {
        switch key {
        case .open: ("⏎", "Open")
        case .newSession: ("⌥⌘T", NewSessionWords.newSession)
        case .newWorkspace: ("⌘N", "New")
        }
    }

    /// The count (or when the list was last listed) at the left, the keys at the right
    /// (``showKeys()``), as the approved list's foot says them (⌘R says itself on List Again). It
    /// is a keycap tall with or without keys (held once, from `init`), so keys coming and going never
    /// move the list: a row that moved closes its New Session panel.
    private func buildFoot() {
        foot.subviews.forEach { $0.removeFromSuperview() }
        footShows = footKeys
        var shown: [NSView] = []
        for (key, words) in footShows.map(Self.said) {
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
            count.leadingAnchor.constraint(equalTo: foot.leadingAnchor), count.centerYAnchor.constraint(equalTo: foot.centerYAnchor),
            count.trailingAnchor.constraint(lessThanOrEqualTo: keys.leadingAnchor, constant: -12),
            keys.trailingAnchor.constraint(equalTo: foot.trailingAnchor), keys.centerYAnchor.constraint(equalTo: foot.centerYAnchor),
        ])
    }

    private func banner(_ notice: WorkspacesModel.Notice) -> Banner {
        let again = notice.tryAgain ? SailButton("Try Again", kind: .secondary, size: .small, look: look, target: self, action: #selector(askAgain(_:))) : nil
        return Banner(notice.tone == .error ? .error : .warning, title: notice.title, line: notice.line.isEmpty ? nil : notice.line, action: again, look: look)
    }

    private func emptyState(_ empty: WorkspacesModel.Empty) -> NSView {
        let action: SailButton? = switch empty.action {
        case .newWorkspace?: SailButton("New Workspace", kind: .primary, icon: .plus, look: look, target: self, action: #selector(beginCreate(_:)))
        case .checkAgain?: SailButton("Check Again", kind: .secondary, look: look, target: self, action: #selector(askAgain(_:)))
        case .createProject?: SailButton("Create Project", kind: .primary, icon: .plus, look: look, target: self, action: #selector(createProject(_:)))
        case nil: nil
        }
        let createsProject = empty.action == .createProject
        if !createsProject { action?.shortcut(empty.action == .newWorkspace ? "n" : "r", modifiers: .command, mono: mono) }
        return EmptyState(
            icon: empty.missing ? .layers : .box, title: empty.title, body: empty.body, actions: action.map { [$0] } ?? [], line: empty.line, lineFirst: !createsProject, missing: empty.missing, look: look,
            titleFont: titleFont, mono: mono
        )
    }

    /// Create Project, in the empty state of a control plane with no project.
    @objc private func createProject(_: Any?) {
        on(.createProject)
    }

    /// Words in mono 11, tertiary: the foot's.
    private func micro(_ words: String) -> NSAttributedString {
        NSAttributedString(string: words, attributes: [.font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary)])
    }

    /// A gap `height` tall down a column: this page's and This Mac's.
    static func spacer(_ height: Double) -> NSView {
        let view = NSView()
        view.heightAnchor.constraint(equalToConstant: height).isActive = true
        return view
    }

    /// New Workspace, in the head or the empty state.
    @objc private func beginCreate(_: Any?) {
        on(.newWorkspace)
    }

    /// List Again, Check Again and Try Again: the projects and the list asked again.
    @objc private func askAgain(_: Any?) {
        on(.checkAgain)
    }
}
