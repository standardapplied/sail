import AppKit
import Darwin
import SailAPI
import SailControls
import SailLook
import SailWorkbench
import SystemConfiguration

/// This Mac's page in Workspaces, drawn in the page skeleton as a project's is: "This Mac" with
/// the Mac's name under it, New Shell at the top right, then SESSIONS: a row per terminal of this
/// Mac (its tab's title over its folder, Running or Ended, its program or how it ended) and the
/// foot's keys while a row is chosen; with none open, the empty state with New Shell. It draws the
/// workbench's panes as it is told them and keeps none of its own; narrower than its wide rows
/// leave a name room for, the rows drop the program's column and New Shell is its icon.
@MainActor
package final class LocalProjectView: NSView {
    enum Act: Equatable {
        case open(PaneID), end(PaneID), newShell
    }

    static let endSession = "End Session", noShell = "No shells open", unlisted = "Couldn’t list this Mac’s shells"

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

    /// The name under the title, read from the system's configuration, never asked of the network,
    /// and sent nowhere.
    package var macName = LocalProjectView.name(computer: SCDynamicStoreCopyComputerName(nil, nil) as String?, host: WorkbenchView.hostName) {
        didSet { redraw() }
    }

    var on: @MainActor (Act) -> Void = { _ in }
    var look: Look {
        didSet { relook() }
    }

    package let list: SailList
    package let skeleton: PageColumn
    package let newShell: SailButton
    let newShellIcon: SailButton
    private let frameView: ListFrame
    private let mono: LabelFont
    private let titleFont: TitleFont
    /// What it was told last: drawn again in a new look or width.
    private var shown: Terminals?
    /// The foot names its keys: a row is chosen in the list holding the keys.
    private var footShows = false
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
        newShell = SailButton("New Shell", kind: .secondary, icon: .plus, look: look, target: nil, action: nil)
        newShellIcon = SailButton(icon: .plus, label: "New Shell", kind: .secondary, look: look, target: nil, action: nil)
        skeleton = PageColumn(buttons: [newShell, newShellIcon])
        super.init(frame: .zero)
        newShell.keyEquivalent = "t"
        newShell.keyEquivalentModifierMask = .command
        newShellIcon.shortcut("t", modifiers: .command, mono: mono)
        for button in [newShell, newShellIcon] {
            button.target = self
            button.action = #selector(openShell(_:))
        }
        list.onChoose = { [weak self] _ in self?.showKeys() }
        list.onKeys = { [weak self] in self?.showKeys() }
        skeleton.fill(self)
        skeleton.foot([], look: look, mono: mono)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
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
        2 * PageColumn.padding.sides + 2 + RowStyle.list.leading + RowStyle.list.trailing + WorkspaceLine.pillColumn + programColumn + columns.wide + 150
    }

    /// Draws `terminals`, This Mac's now; the same again draws nothing.
    func render(_ terminals: Terminals) {
        guard shown != terminals else { return }
        shown = terminals
        programColumn = terminals.rows.reduce(WorkspaceLine.ownerColumn) { widest, session in
            max(widest, Double(WorkspaceLine.secondary(Self.program(session), look: look).intrinsicContentSize.width).rounded(.up))
        }
        let was = narrow
        fit()
        if narrow == was { redraw() }
    }

    /// What the page says in its rows' place: why no row shows, nothing while they are listed or listing.
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
        skeleton.name(WorkspacesModel.thisMac, lede: macName, narrow: narrow, look: look, titleFont: titleFont)
        newShell.isHidden = narrow
        newShellIcon.isHidden = !narrow
        var views: [NSView] = [skeleton.head]
        switch shown {
        case .listing: break
        case .unlisted: views += [PageColumn.spacer(PageColumn.listGap), Banner(.error, title: Self.unlisted, look: look)]
        case .listed([]): views += [PageColumn.spacer(PageColumn.listGap), PageColumn.framed(emptyState(), look: look)]
        case let .listed(sessions):
            list.actionsColumn = narrow ? columns.narrow : columns.wide
            list.returnKeyShown = !narrow
            let entries: [SailList.Entry] = [.head("Sessions", count: sessions.count)] + sessions.map { .item(item($0)) }
            list.entries = entries
            frameView.height = entries.indices.reduce(0.0) { $0 + Double(list.tableView(list, heightOfRow: $1)) }
            views += [PageColumn.spacer(PageColumn.listGap), frameView, PageColumn.spacer(PageColumn.footGap), skeleton.foot]
        }
        skeleton.show(views)
        showKeys()
    }

    /// No shell open: the project page's empty state, its one action New Shell.
    private func emptyState() -> EmptyState {
        let action = SailButton("New Shell", kind: .primary, icon: .plus, look: look, target: self, action: #selector(openShell(_:)))
        action.shortcut("t", modifiers: .command, mono: mono)
        return EmptyState(icon: .terminal, title: Self.noShell, body: "", actions: [action], look: look, titleFont: titleFont, mono: mono)
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

    /// The foot's keys, each named only while it acts on the chosen row: a row chosen and the list
    /// holding the keys (``SailList/holdsKeys``), asked again whenever either changes.
    private func showKeys() {
        let working = list.chosen != nil && list.holdsKeys
        guard working != footShows else { return }
        footShows = working
        skeleton.foot(working ? Self.keys : [], look: look, mono: mono)
    }

    /// The keys as the foot names them, as a project's foot does.
    static let keys = [(key: "⏎", words: "Open"), (key: "⌘T", words: "New"), (key: "⌘W", words: LocalProjectView.endSession)]

    private func relook() {
        list.look = look
        frameView.look = look
        for button in [newShell, newShellIcon] {
            button.look = look
        }
        skeleton.foot(footShows ? Self.keys : [], look: look, mono: mono)
        redraw()
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
