import AppKit
import Darwin
import SailAPI
import SailControls
import SailLook
import SailWorkbench
import SystemConfiguration

/// This Mac's page in Workspaces, as the approved design draws it: "This Mac" with its plain
/// "Local · not synced" pill, the line saying which Mac it is and that nothing here reaches the
/// control plane, New Shell at the top right, then SESSIONS: a row per terminal of this Mac (its
/// tab's title over its folder, Running or Ended, its program or how it ended), the keys under them
/// while a row is chosen, and what This Mac is not. It draws the workbench's panes as it is told
/// them and keeps none of its own; narrower than its wide rows leave a name room for, the rows drop
/// the program's column and New Shell is its icon.
@MainActor
package final class LocalProjectView: NSView {
    enum Act: Equatable {
        case open(PaneID), end(PaneID), newShell
    }

    static let endSession = "End Session", noteGap = 22.0, hintGap = 16.0, noteWidth = 520.0
    static let noShell = "No shell is open on this Mac.", unlisted = "Couldn’t list this Mac’s terminals."

    /// This Mac's terminals as the page knows them: still being listed, listed, or their listing failed.
    enum Terminals: Equatable {
        case listing, listed([LocalSession]), unlisted

        /// The terminals listed; none until they are.
        var rows: [LocalSession] {
            if case let .listed(sessions) = self { sessions } else { [] }
        }
    }

    /// The program a shell of this Mac runs: the person's login shell, as this Mac's terminal host
    /// reads it from the account database.
    static let program: String = {
        guard let account = getpwuid(getuid()), let shell = account.pointee.pw_shell else { return "sh" }
        return URL(filePath: String(cString: shell)).lastPathComponent
    }()

    /// This Mac as its person knows it: `computer`, its name as System Settings shows it ("Uday’s
    /// MacBook Pro"), or `host` when it has none.
    static func name(computer: String?, host: String) -> String {
        guard let computer, !computer.trimmingCharacters(in: .whitespaces).isEmpty else { return host }
        return computer
    }

    /// The name the lede says, read from the system's configuration, never asked of the network,
    /// and sent nowhere.
    package var macName = LocalProjectView.name(computer: SCDynamicStoreCopyComputerName(nil, nil) as String?, host: WorkbenchView.hostName) {
        didSet { redraw() }
    }

    var on: @MainActor (Act) -> Void = { _ in }
    var look: Look {
        didSet { relook() }
    }

    package let list: SailList
    let title = NSTextField(labelWithString: "")
    let pill: Pill
    let lede = NSTextField(wrappingLabelWithString: "")
    package let newShell: SailButton
    let newShellIcon: SailButton
    let note = NSTextField(wrappingLabelWithString: "")
    /// Under SESSIONS in a row's place: no terminal is open, or none could be listed.
    let empty = NSTextField(labelWithString: "")
    /// The keys that act on the chosen row, shown only while they do.
    let keys = NSStackView()
    private let frameView: ListFrame
    private let column = NSStackView()
    private let head = NSView()
    private let words = NSStackView()
    private let mono: LabelFont
    private let titleFont: TitleFont
    /// What it was told last: drawn again in a new look or width.
    private var shown: (terminals: Terminals, controlPlane: String)?
    /// The program's column: as wide as the longest program or ending a row says, 104 pt at least.
    private var programColumn = WorkspaceLine.ownerColumn
    private(set) var narrow = false {
        didSet { if narrow != oldValue { redraw() } }
    }

    /// The actions column of a wide row (Open with its ⏎, then ⋯) and of a narrow one (no ⏎),
    /// measured, so every row's pill stands at one x.
    private lazy var columns = (wide: actionsColumn(returnKey: true), narrow: actionsColumn(returnKey: false))

    init(look: Look, mono: LabelFont, titleFont: TitleFont) {
        self.look = look
        self.mono = mono
        self.titleFont = titleFont
        list = SailList(rowStyle: .list, look: look, mono: mono)
        frameView = ListFrame(scrolling: list, look: look)
        pill = Pill(plain: "Local · not synced", icon: .lock, look: look, mono: mono)
        newShell = SailButton("New Shell", kind: .secondary, icon: .plus, look: look, target: nil, action: nil)
        newShellIcon = SailButton(icon: .plus, label: "New Shell", kind: .secondary, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        for button in [newShell, newShellIcon] {
            button.shortcut("t", modifiers: .command, mono: mono)
            button.target = self
            button.action = #selector(openShell(_:))
        }
        list.onChoose = { [weak self] _ in self?.showKeys() }
        list.onKeys = { [weak self] in self?.showKeys() }
        compose()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    /// Drawn narrow or wide by its own width, drawn again only when that changes.
    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        fit()
    }

    private func fit() {
        if bounds.width > 0 { narrow = Double(bounds.width) < wide }
    }

    /// The least width drawn wide: the wide row's columns, insets and frame, and 150 pt for a name.
    private var wide: Double {
        2 * ProjectView.padding.sides + 2 + RowStyle.list.leading + RowStyle.list.trailing + WorkspaceLine.pillColumn + programColumn + columns.wide + 150
    }

    /// Draws `terminals`, This Mac's now, kept from `controlPlane`; the same again draws nothing.
    func render(_ terminals: Terminals, controlPlane: String) {
        guard shown.map({ $0.terminals != terminals || $0.controlPlane != controlPlane }) ?? true else { return }
        shown = (terminals, controlPlane)
        programColumn = terminals.rows.reduce(WorkspaceLine.ownerColumn) { widest, session in
            max(widest, Double(WorkspaceLine.secondary(Self.program(session), look: look).intrinsicContentSize.width).rounded(.up))
        }
        let was = narrow
        fit()
        if narrow == was { redraw() }
    }

    /// What stands under SESSIONS in a row's place: why no row does, nothing while they are listed or listing.
    static func instead(_ terminals: Terminals) -> String? {
        switch terminals {
        case .listing: nil
        case .unlisted: Self.unlisted
        case let .listed(sessions): sessions.isEmpty ? Self.noShell : nil
        }
    }

    /// A session as a person reads it: its title, its state, its folder, and its program or how it
    /// ended ("This Mac · running · ~/workspace/sail · zsh").
    static func said(_ session: LocalSession) -> String {
        [session.title, state(session).label.lowercased(), session.folder, program(session)].joined(separator: " · ")
    }

    /// What a session runs, or how it ended.
    private static func program(_ session: LocalSession) -> String {
        session.ended ?? program
    }

    private static func state(_ session: LocalSession) -> PillState {
        session.ended == nil ? .running : .ended
    }

    private func redraw() {
        guard let shown else { return }
        title.attributedStringValue = NSAttributedString(string: WorkspacesModel.thisMac, attributes: [
            .font: titleFont.font(.title1) as NSFont, .foregroundColor: NSColor(look.text), .kern: -0.012 * Look.Step.title1.points,
        ])
        lede.attributedStringValue = NSAttributedString(string: WorkspacesModel.thisMacLede(host: macName, controlPlane: shown.controlPlane), attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ])
        note.attributedStringValue = NSAttributedString(string: WorkspacesModel.thisMacNote, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.footnote.points), .foregroundColor: NSColor(look.textTertiary),
        ])
        let instead = Self.instead(shown.terminals), sessions = shown.terminals.rows
        empty.attributedStringValue = NSAttributedString(string: instead ?? "", attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textTertiary),
        ])
        empty.isHidden = instead == nil
        newShell.isHidden = narrow
        newShellIcon.isHidden = !narrow
        list.actionsColumn = narrow ? columns.narrow : columns.wide
        list.returnKeyShown = !narrow
        let count = if case .listed = shown.terminals { sessions.count } else { Int?.none }
        let entries: [SailList.Entry] = [.head("Sessions", count: count)] + sessions.map { .item(item($0)) }
        list.entries = entries
        frameView.height = entries.indices.reduce(empty.isHidden ? 0.0 : RowStyle.list.height) { $0 + Double(list.tableView(list, heightOfRow: $1)) }
        showKeys()
    }

    private func item(_ session: LocalSession) -> SailList.Item {
        let narrow = narrow, look = look, mono = mono, pane = session.pane
        let line = WorkspaceLine(
            session.title, below: session.folder, cut: .byTruncatingMiddle, pill: Self.state(session), whose: WorkspaceLine.secondary(Self.program(session), look: look),
            whoseWidth: programColumn, ownerTip: nil, narrow: narrow, look: look, mono: mono
        )
        let actions = [
            SailList.Action("Open") { [weak self] in self?.on(.open(pane)) },
            SailList.Action(Self.endSession, key: "w", modifiers: .command, onRow: false) { [weak self] in self?.on(.end(pane)) },
        ]
        return SailList.Item(id: "\(pane.value)", name: session.title, voice: Self.said(session).replacingOccurrences(of: " · ", with: ", "), content: line, actions: actions)
    }

    /// The keys under the list, each named only while it acts on the chosen row: a row chosen and
    /// the list holding the keys (``SailList/holdsKeys``), asked again whenever either changes.
    private func showKeys() {
        let working = list.chosen != nil && list.holdsKeys
        keys.arrangedSubviews.forEach { $0.isHidden = !working }
    }

    /// The keys' own words, as the approved list's foot says them.
    private static let hints = [("⏎", "open"), ("⌘T", "new shell"), ("⌘W", "end session")]

    private func buildKeys() {
        keys.setViews(Self.hints.map { key, words in
            let pair = NSStackView(views: [Keycap(key, look: look, mono: mono), NSTextField(labelWithAttributedString: NSAttributedString(string: words, attributes: [
                .font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary),
            ]))])
            pair.spacing = ProjectView.buttonGap
            return pair
        }, in: .leading)
        showKeys()
    }

    private func relook() {
        list.look = look
        frameView.look = look
        pill.look = look
        for button in [newShell, newShellIcon] {
            button.look = look
        }
        buildKeys()
        redraw()
    }

    private func compose() {
        for label in [title, lede, note, empty] {
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        title.lineBreakMode = .byTruncatingTail
        let named = NSStackView(views: [title, pill])
        named.spacing = 10
        named.alignment = .centerY
        named.setClippingResistancePriority(.defaultLow, for: .horizontal)
        words.orientation = .vertical
        words.alignment = .leading
        words.spacing = 6
        words.setViews([named, lede], in: .top)
        words.setClippingResistancePriority(.defaultLow, for: .horizontal)
        let buttons = NSStackView(views: [newShell, newShellIcon])
        buttons.setHuggingPriority(.required, for: .horizontal)
        for view in [words, buttons] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            head.addSubview(view)
        }
        keys.spacing = Self.hintGap
        buildKeys()
        let foot = NSView(), noted = NSView()
        keys.translatesAutoresizingMaskIntoConstraints = false
        foot.addSubview(keys)
        note.translatesAutoresizingMaskIntoConstraints = false
        noted.addSubview(note)
        empty.lineBreakMode = .byTruncatingTail
        empty.translatesAutoresizingMaskIntoConstraints = false
        frameView.addSubview(empty)
        column.orientation = .vertical
        column.alignment = .width
        column.spacing = 0
        column.setClippingResistancePriority(.defaultLow, for: .horizontal)
        column.setViews([head, ProjectView.spacer(ProjectView.listGap), frameView, ProjectView.spacer(ProjectView.footGap), foot, ProjectView.spacer(Self.noteGap), noted], in: .top)
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        let above = column.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -ProjectView.padding.bottom)
        above.priority = WorkspacesView.yields
        let hug = head.heightAnchor.constraint(equalToConstant: 0)
        hug.priority = .fittingSizeCompression
        NSLayoutConstraint.activate([
            words.leadingAnchor.constraint(equalTo: head.leadingAnchor), words.topAnchor.constraint(equalTo: head.topAnchor),
            lede.widthAnchor.constraint(equalTo: words.widthAnchor), buttons.trailingAnchor.constraint(equalTo: head.trailingAnchor),
            note.leadingAnchor.constraint(equalTo: noted.leadingAnchor), note.trailingAnchor.constraint(lessThanOrEqualTo: noted.trailingAnchor),
            note.topAnchor.constraint(equalTo: noted.topAnchor), note.bottomAnchor.constraint(equalTo: noted.bottomAnchor),
            note.widthAnchor.constraint(lessThanOrEqualToConstant: Self.noteWidth),
            empty.leadingAnchor.constraint(equalTo: frameView.leadingAnchor, constant: Look.hairline + RowStyle.list.leading),
            empty.trailingAnchor.constraint(lessThanOrEqualTo: frameView.trailingAnchor, constant: -RowStyle.list.trailing),
            empty.centerYAnchor.constraint(equalTo: frameView.bottomAnchor, constant: -Look.hairline - RowStyle.list.height / 2),
            buttons.topAnchor.constraint(equalTo: head.topAnchor), buttons.leadingAnchor.constraint(equalTo: words.trailingAnchor, constant: ProjectView.headGap),
            head.bottomAnchor.constraint(greaterThanOrEqualTo: words.bottomAnchor), head.bottomAnchor.constraint(greaterThanOrEqualTo: buttons.bottomAnchor), hug,
            keys.leadingAnchor.constraint(equalTo: foot.leadingAnchor), keys.centerYAnchor.constraint(equalTo: foot.centerYAnchor),
            foot.heightAnchor.constraint(equalToConstant: Keycap.height),
            column.leadingAnchor.constraint(equalTo: leadingAnchor, constant: ProjectView.padding.sides),
            column.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -ProjectView.padding.sides),
            column.topAnchor.constraint(equalTo: topAnchor, constant: ProjectView.padding.top), above,
        ])
    }

    /// The widest actions a row shows, Open (with ⏎ when `returnKey`) and ⋯, measured with the gap before them.
    private func actionsColumn(returnKey: Bool) -> Double {
        let open = SailButton("Open", kind: .primary, size: .small, look: look, target: nil, action: nil)
        if returnKey { open.shortcut("\r", mono: mono) }
        return WorkspaceLine.actionsColumn([open, SailButton(icon: .more, label: "More", size: .small, look: look, target: nil, action: nil)])
    }

    /// New Shell, its icon or ⌘T: a shell at once, in Terminals, with no chooser.
    @objc private func openShell(_: Any?) {
        on(.newShell)
    }
}
