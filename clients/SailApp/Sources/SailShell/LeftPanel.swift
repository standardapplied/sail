import AppKit
import SailAPI
import SailControls
import SailLook
import SailWorkbench

/// The app's one left panel, as the approved navigation draws it, with the Workspaces section's
/// projects: a 46 pt header with the section's title over a hairline, then two groups to its
/// bottom edge: THIS MAC, holding the one local project, and the control plane's projects under its
/// host with their count, as the panel's project rows (disclosure, tile, name, readiness pill). An open
/// project lists the person's own workspaces under it, 14 pt in, each with its state square and its +
/// for New Session (the row's under the pointer, else the chosen row's); then one quiet row counting
/// other people's, and a quiet line where its workspaces could not be listed. → opens a project or
/// goes to its first row, ← shuts it or goes to the project a row is under, Return opens a workspace.
/// Who is signed in and where is the user menu's to say. With no project it says so under the control plane's head;
/// before any is listed it shows skeleton rows there, and, when none could be, why, with Try Again.
/// It is 236 pt by default; its trailing edge drags it between 200 and 360 pt, remembered on this Mac.
@MainActor
package final class LeftPanel: NSView {
    static let width = (standard: 236.0, least: 200.0, most: 360.0), header = 46.0, edge = 6.0
    /// A row under a project: 28 pt, 14 pt in, as the design's `.lr.w`.
    static let nested = (height: 28.0, indent: 14.0)
    static let remembered = "leftPanel.width"

    /// A project chosen, or This Mac; also a project's row of other people's workspaces.
    var onChoose: @MainActor (String) -> Void = { _ in }
    /// The person's own workspace (second) of a project (first) chosen.
    var onChooseRow: @MainActor (String, String) -> Void = { _, _ in }
    /// A project opened or shut.
    var onToggle: @MainActor (String) -> Void = { _ in }
    /// A workspace's + pressed.
    var onNewSession: @MainActor (String, String) -> Void = { _, _ in }
    /// Return on a workspace's row.
    var onOpen: @MainActor (String, String) -> Void = { _, _ in }
    /// Create Project, on the control plane's head beside its count.
    var onCreate: @MainActor () -> Void = {}
    /// Try Again under the control plane's head, which could list no project.
    var onTryAgain: @MainActor () -> Void = {}
    /// Its look; a change redraws its words and rows in it.
    var look: Look {
        didSet { relook() }
    }

    package let list: SailList
    /// The projects scroll here, between the header and the bottom edge; its scroller sits clear of the
    /// drag edge, so a reach for the thumb never resizes the panel.
    package let scroll: QuietScrollView
    let heading = NSTextField(labelWithString: "Workspaces")
    /// "No projects yet." or "No project is open to you yet.", with none listed, or why none could be.
    let emptyLine = NSTextField(labelWithString: "")
    /// Under the empty line while no project could be listed and This Mac's page is shown, where
    /// no card offers its own: the projects asked again.
    package let tryAgain: SailButton
    /// Create Project, a quiet + on the control plane's head before its count, offered to those who
    /// may create and build a project once projects are listed.
    package let createProject: SailButton
    /// Skeleton rows, each a tile and a name, before any project is listed.
    private let skeletons: [(tile: Skeleton, name: Skeleton)]
    private let mono: LabelFont
    private(set) var widthConstraint: NSLayoutConstraint!
    private var projects: [WorkspacesModel.Project] = []
    private var headed: String?
    /// The control plane's head's count as drawn, nil before its projects are listed.
    private var counted: Int?
    /// Create Project is on the control plane's head as drawn.
    private var offered = false
    /// The names are drawn in the secondary ink: what is shown failed to refresh.
    private var stale = false
    /// What it last showed, drawn again in a new look.
    private var shown: WorkspacesModel.Panel?
    /// What each row of the control plane's group is, by its id, as it is drawn: a row whose kind
    /// changed is made again, and only it.
    private var kinds: [String: Kind] = [:]
    /// Its rows are being drawn from the model: a choice made so is the model's, told no one.
    private var drawing = false
    /// The row whose + opened New Session, its + drawn pressed while that is open.
    var opened: String? {
        didSet {
            guard opened != oldValue else { return }
            for id in [oldValue, opened].compactMap({ $0 }) { cell(id)?.held = id == opened }
        }
    }

    private enum Kind: Equatable {
        /// A project's row: its name, its template's mark, its disclosure (nil for none), and whether it is open.
        case project(String, mark: WorkspacesModel.Mark?, disclosure: Bool?, expanded: Bool)
        case workspace(String, WorkspacesModel.Workspace)
        /// A quiet row under a project, in its words: other people's, which chooses the project; or a note.
        case others(String, String)
        case note(String, String)
    }

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        list = SailList(rowStyle: .sidebar, look: look, mono: mono)
        list.barInset = look.cardEdge == nil ? 0 : Look.hairline
        scroll = QuietScrollView(look: look)
        skeletons = [70.0, 52, 88, 60, 74].map { (tile: Skeleton(width: TilePaint.side, height: TilePaint.side, look: look), name: Skeleton(width: $0, look: look)) }
        tryAgain = SailButton("Try Again", kind: .quiet, size: .small, look: look, target: nil, action: nil)
        createProject = SailButton(icon: .plus, label: "Create Project", kind: .quiet, size: .small, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        createProject.isHidden = true
        createProject.target = self
        createProject.action = #selector(create(_:))
        emptyLine.isHidden = true
        addSubview(emptyLine)
        tryAgain.isHidden = true
        tryAgain.target = self
        tryAgain.action = #selector(askAgain(_:))
        addSubview(tryAgain)
        for (tile, name) in skeletons {
            tile.isHidden = true
            name.isHidden = true
            addSubview(tile)
            addSubview(name)
        }
        heading.font = .systemFont(ofSize: Look.Step.headline.points, weight: .semibold)
        heading.textColor = NSColor(look.text)
        heading.translatesAutoresizingMaskIntoConstraints = false
        addSubview(heading)
        scroll.documentView = list
        scroll.scrollerInsets.right = Self.edge
        list.actionsColumn = Self.trackRoom
        addSubview(scroll)
        let width = UserDefaults.standard.object(forKey: Self.remembered) as? Double ?? Self.width.standard
        widthConstraint = widthAnchor.constraint(equalToConstant: Self.clamped(width))
        NSLayoutConstraint.activate([
            widthConstraint, heading.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            heading.centerYAnchor.constraint(equalTo: topAnchor, constant: Self.header / 2),
        ])
        list.onChoose = { [weak self] id in if let id { self?.chose(id) } }
        list.onKey = { [weak self] event in self?.key(event) ?? false }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    /// What a project row keeps clear at its end, past its 6 pt inset, so its pill never sits under
    /// the scroller's track, which runs to the drag edge.
    static let trackRoom = ScrollerStyle.track + edge - RowStyle.sidebar.margin - RowStyle.sidebar.trailing

    static func clamped(_ width: Double) -> Double {
        min(max(width, Self.width.least), Self.width.most)
    }

    private func relook() {
        heading.textColor = NSColor(look.text)
        list.look = look
        scroll.look = look
        list.barInset = look.cardEdge == nil ? 0 : Look.hairline
        tryAgain.look = look
        createProject.look = look
        for (tile, name) in skeletons {
            tile.look = look
            name.look = look
        }
        projects = []
        headed = nil
        if let shown {
            scroll.keepingScroll { render(shown) }
        }
        needsDisplay = true
    }

    /// Shows `panel`: This Mac's group, then the control plane's projects, its chosen one chosen;
    /// before they are listed, skeleton rows in their place, or why none could be with Try Again.
    func render(_ panel: WorkspacesModel.Panel) {
        shown = panel
        let failed = !panel.listed && panel.emptyLine != nil
        loading(!panel.listed && !failed)
        tryAgain.isHidden = !panel.tryAgain || panel.chosen != WorkspacesModel.thisMac
        createProject.isHidden = !panel.creates
        emptyLine.isHidden = panel.emptyLine == nil
        emptyLine.attributedStringValue = NSAttributedString(string: panel.emptyLine ?? "", attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.footnote.points), .foregroundColor: NSColor(look.textTertiary),
        ])
        let count = panel.listed ? panel.projects.count : nil
        let was = list.chosen
        drawing = true
        defer { drawing = false }
        let head = panel.stale != stale || panel.controlPlane != headed || count != counted || panel.creates != offered
        if head || panel.projects != projects {
            projects = panel.projects
            stale = panel.stale
            headed = panel.controlPlane
            counted = count
            offered = panel.creates
            let before = kinds
            kinds = [:]
            let entries = thisMac + [.head(panel.controlPlane, count: count, icon: .cloud, action: panel.creates ? createProject : nil)] + panel.projects.flatMap(entries)
            if head {
                list.entries = entries
            } else {
                scroll.keepingScroll { list.apply(entries, changed: Set(kinds.filter { id, kind in before[id].map { $0 != kind } ?? false }.keys)) }
            }
        }
        let chosen = chosenID(panel)
        if list.chosen != chosen {
            if was == chosen { list.select(chosen) } else { list.choose(chosen) }
        }
        needsLayout = true
    }

    /// The row chosen: the person's own workspace chosen while its project is open, else the project.
    private func chosenID(_ panel: WorkspacesModel.Panel) -> String? {
        guard let project = panel.chosen, let row = panel.chosenRow, panel.projects.first(where: { $0.name == project })?.expanded == true else { return panel.chosen }
        return Self.id(project, row)
    }

    /// A workspace's row id: its project's name and its own, which hold no slash.
    package static func id(_ project: String, _ workspace: String) -> String {
        project + "/" + workspace
    }

    /// The person chose row `id`: what it is, told on.
    private func chose(_ id: String) {
        guard !drawing else { return }
        switch kinds[id] {
        case let .workspace(project, workspace)?: onChooseRow(project, workspace.name)
        case let .others(project, _)?: onChoose(project)
        case .note?: break
        case .project?, nil: onChoose(id)
        }
    }

    @objc private func askAgain(_: Any?) {
        onTryAgain()
    }

    @objc private func create(_: Any?) {
        onCreate()
    }

    private func loading(_ loading: Bool) {
        for (tile, name) in skeletons {
            tile.isHidden = !loading
            name.isHidden = !loading
        }
        needsLayout = true
    }

    /// The THIS MAC head and its one project: the terminal in its well and a plain Local pill, no
    /// action of any kind.
    private var thisMac: [SailList.Entry] {
        let name = WorkspacesModel.thisMac, cell = SidebarRowCell(name, lead: .tile(.terminal), tag: Self.local, look: look, mono: mono)
        return [.head(name, count: nil, icon: .laptop), .item(SailList.Item(id: name, name: name, voice: "\(name), \(Self.local.lowercased())", content: cell))]
    }

    static let local = "Local"

    /// A project's rows: its own, then, while it is open, what it holds.
    private func entries(_ project: WorkspacesModel.Project) -> [SailList.Entry] {
        let open: Bool? = project.holds?.empty == true ? nil : project.expanded
        kinds[project.name] = .project(project.name, mark: project.mark, disclosure: open, expanded: project.expanded)
        var rows = [row(project, open: open)]
        if project.expanded, let holds = project.holds {
            rows += holds.yours.map { row($0, in: project.name) }
            if let others = holds.othersSaid { rows.append(quiet(others, id: project.name + "\u{0}others", kind: .others(project.name, others))) }
            if let failed = holds.failed { rows.append(quiet(failed, id: project.name + "\u{0}failed", kind: .note(project.name, failed))) }
        }
        return rows.map { .item($0) }
    }

    /// A project's row: its disclosure (`open`, nil for none, where it holds nothing), its tile, its
    /// name and its template's pill; VoiceOver reads its name and pill.
    private func row(_ project: WorkspacesModel.Project, open: Bool?) -> SailList.Item {
        let pill = Self.pill(project.mark), look = look, mono = mono, stale = stale, name = project.name
        let voice = pill.map { "\(project.name), \($0.label.lowercased())" } ?? project.name
        return SailList.Item(id: name, name: name, voice: voice, content: {
            let cell = SidebarRowCell(name, disclosure: open, template: pill, look: look, mono: mono, onDisclose: { [weak self] in self?.onToggle(name) })
            cell.stale = stale
            return cell
        }())
    }

    /// One of the person's own workspaces under `project`: its state square, its name, its +.
    private func row(_ workspace: WorkspacesModel.Workspace, in project: String) -> SailList.Item {
        let id = Self.id(project, workspace.name), look = look, mono = mono, state = WorkspaceLine.pill(state: workspace.state, busy: workspace.busy)
        kinds[id] = .workspace(project, workspace)
        let cell = SidebarRowCell(
            workspace.name, lead: .state(state), indent: Self.nested.indent, plus: workspace.newSession ? NewSessionWords.newSession : nil, look: look, mono: mono,
            onPlus: { [weak self] in self?.onNewSession(project, workspace.name) }
        )
        cell.held = id == opened
        return SailList.Item(id: id, name: workspace.name, voice: "\(workspace.name), \(state.label.lowercased())", content: cell, height: Self.nested.height)
    }

    /// A quiet row under a project: other people's workspaces counted, shut, which chooses the
    /// project; or why its workspaces are not listed, which cannot be chosen.
    private func quiet(_ words: String, id: String, kind: Kind) -> SailList.Item {
        let look = look, mono = mono, note: Bool = if case .note = kind { true } else { false }
        kinds[id] = kind
        return SailList.Item(
            id: id, name: words, voice: words, content: SidebarRowCell(words, lead: .words, indent: Self.nested.indent, disclosure: note ? nil : false, look: look, mono: mono),
            height: Self.nested.height, inert: note
        )
    }

    /// → opens a shut project or goes to its first row; ← shuts an open one or goes to the project a
    /// row is under; Return opens a workspace as its page's row does. Right to left the arrows swap.
    private func key(_ event: NSEvent) -> Bool {
        guard event.modifierFlags.intersection([.command, .option, .control]).isEmpty, let id = list.chosen, let kind = kinds[id] else { return false }
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        let inward: UInt16 = rightToLeft ? 123 : 124, outward: UInt16 = rightToLeft ? 124 : 123
        switch (event.keyCode, kind) {
        case (inward, .project(let name, _, _, let expanded)):
            if !expanded { onToggle(name) } else if let first = next(after: id), kinds[first].map(Self.isUnder) == true { list.choose(first) }
            return true
        case (outward, .project(let name, _, _, true)):
            onToggle(name)
            return true
        case (outward, .workspace(let project, _)), (outward, .others(let project, _)):
            list.choose(project)
            return true
        case (36, .workspace(let project, let workspace)), (76, .workspace(let project, let workspace)):
            if workspace.primary != nil { onOpen(project, workspace.name) }
            return true
        default:
            return false
        }
    }

    /// The id of the row after `id`'s, nil at the end.
    private func next(after id: String) -> String? {
        let ids = list.entries.compactMap { entry -> String? in if case let .item(item) = entry { item.id } else { nil } }
        return ids.firstIndex(of: id).flatMap { ids.indices.contains($0 + 1) ? ids[$0 + 1] : nil }
    }

    /// A row under a project that can be chosen.
    private static func isUnder(_ kind: Kind) -> Bool {
        switch kind {
        case .workspace, .others: true
        case .project, .note: false
        }
    }

    /// Where row `workspace` of `project` is in the window, nil while it is not shown.
    package func rowFrame(_ project: String, _ workspace: String) -> NSRect? {
        let id = Self.id(project, workspace)
        guard let row = list.entries.firstIndex(where: { if case let .item(item) = $0 { item.id == id } else { false } }) else { return nil }
        return list.convert(list.rect(ofRow: row), to: nil)
    }

    /// Row `workspace`'s + of `project` while it shows: what opened its panel, which a click on toggles.
    package func plus(_ project: String, _ workspace: String) -> NSView? {
        cell(Self.id(project, workspace))?.plus.flatMap { $0.isHiddenOrHasHiddenAncestor ? nil : $0 }
    }

    /// Row `id`'s content while its row is made.
    private func cell(_ id: String) -> SidebarRowCell? {
        guard let row = list.entries.firstIndex(where: { if case let .item(item) = $0 { item.id == id } else { false } }),
              let cell = list.rowView(atRow: row, makeIfNecessary: false)?.view(atColumn: 0) as? NSView
        else { return nil }
        return Self.cells(in: cell).first
    }

    private static func cells(in view: NSView) -> [SidebarRowCell] {
        if let cell = view as? SidebarRowCell { return [cell] }
        return view.subviews.flatMap(cells)
    }

    static func pill(_ mark: WorkspacesModel.Mark?) -> PillState? {
        switch mark {
        case .noTemplate: .noTemplate
        case .building: .building
        case .buildFailed: .buildFailed
        case nil: nil
        }
    }

    override public func layout() {
        super.layout()
        let top = Self.header + 4
        let rows = Double(list.rect(ofRow: max(list.numberOfRows - 1, 0)).maxY)
        scroll.frame = NSRect(x: 0, y: top, width: Double(bounds.width) - Look.hairline, height: min(rows, max(Double(bounds.height) - top, 0)))
        list.frame = NSRect(x: 0, y: 0, width: Double(scroll.contentSize.width), height: rows)
        let under = top + rows
        emptyLine.frame = NSRect(x: 14, y: under + 8, width: max(Double(bounds.width) - 28, 0), height: Double(emptyLine.intrinsicContentSize.height))
        let button = tryAgain.intrinsicContentSize, leftToRight = userInterfaceLayoutDirection != .rightToLeft
        let buttonX = leftToRight ? 14 : Double(bounds.width) - 14 - Double(button.width)
        tryAgain.frame = tryAgain.frame(forAlignmentRect: NSRect(x: buttonX, y: Double(emptyLine.frame.maxY) + 6, width: Double(button.width), height: Double(button.height)))
        for (index, (tile, name)) in skeletons.enumerated() {
            let middle = under + (Double(index) + 0.5) * RowStyle.sidebar.height
            tile.frame = NSRect(x: Self.tileX, y: middle - TilePaint.side / 2, width: TilePaint.side, height: TilePaint.side)
            name.frame = NSRect(x: Self.tileX + TilePaint.side + 7, y: middle - 5, width: Double(name.intrinsicContentSize.width), height: 10)
        }
    }

    /// Where a project row's tile starts (the sidebar row's inset and its disclosure's 12 pt slot).
    static let tileX = RowStyle.sidebar.margin + RowStyle.sidebar.leading + RowStyle.disclosure + RowStyle.gap

    override public func draw(_: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        bounds.fill()
        NSColor(look.separator).setFill()
        NSRect(x: 0, y: Self.header, width: Double(bounds.width), height: Look.hairline).fill()
        NSRect(x: Double(bounds.width) - Look.hairline, y: 0, width: Look.hairline, height: Double(bounds.height)).fill()
    }

    // MARK: The edge

    /// The drag edge takes the mouse over the rows too: the list runs to the panel's edge.
    override public func hitTest(_ point: NSPoint) -> NSView? {
        guard let superview, !isHidden else { return super.hitTest(point) }
        let at = convert(point, from: superview)
        return bounds.contains(at) && Double(at.x) >= Double(bounds.width) - Self.edge ? self : super.hitTest(point)
    }

    override public func resetCursorRects() {
        addCursorRect(NSRect(x: Double(bounds.width) - Self.edge, y: 0, width: Self.edge, height: Double(bounds.height)), cursor: .resizeLeftRight)
    }

    override public func mouseDown(with event: NSEvent) {
        let at = convert(event.locationInWindow, from: nil)
        guard Double(at.x) >= Double(bounds.width) - Self.edge else { return super.mouseDown(with: event) }
        let start = Double(event.locationInWindow.x), from = Double(widthConstraint.constant)
        while let next = window?.nextEvent(matching: [.leftMouseDragged, .leftMouseUp]), next.type == .leftMouseDragged {
            resize(to: from + Double(next.locationInWindow.x) - start)
        }
        UserDefaults.standard.set(Double(widthConstraint.constant), forKey: Self.remembered)
    }

    /// The panel `width` wide, kept between its least and most.
    func resize(to width: Double) {
        widthConstraint.constant = Self.clamped(width)
    }
}
