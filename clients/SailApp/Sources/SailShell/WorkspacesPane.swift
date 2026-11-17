import AppKit
import SailAPI
import SailLook

/// The person's workspaces on the runner, laid out as the account pane is: the title; PROJECT and
/// its chooser; the project's workspaces as a list that hugs its rows (up to ``shownRows``, then it
/// scrolls), theirs first, each its name, its state as a dot and a word and, when it is another
/// person's, its owner at the trailing edge; Open directly under it, "Start and Open" for a stopped
/// workspace, which opening starts; then NEW WORKSPACE, a name and Make. Where there is no list the
/// body says why in a sentence, with Try Again when asking again can help. What the server refuses
/// of a make is said under it in its words. The list is asked again when the pane shows and after
/// each change it makes.
@MainActor
public final class WorkspacesPane: NSView, NSTableViewDataSource, NSTableViewDelegate {
    /// The rows the list shows before it scrolls.
    static let shownRows = 6
    static let width = 380.0

    /// What the pane's body shows where the list goes.
    enum Body: Equatable {
        case listed
        case noProjects
        case noWorkspaces(project: String)
        case unreadable([String])
    }

    public let projects = NSPopUpButton()
    public let list = OpeningTable()
    public let open: AccentButton
    public let name = NSTextField()
    public let make = NSButton(title: "Make", target: nil, action: nil)
    public let said = NSTextField(wrappingLabelWithString: "")
    /// Why there is no list, where the list would be.
    public let empty = NSTextField(wrappingLabelWithString: "")
    public let tryAgain = NSButton(title: "Try Again", target: nil, action: nil)
    public private(set) var rows: [WorkspaceRow] = []
    /// Whether a call is under way, and how many have ended: what the drive waits on.
    public private(set) var busy = false
    public private(set) var ended = 0
    private(set) var body = Body.listed

    /// The API as the person signed in, their handle and whether they are an admin; nil signed out.
    var client: SailClient?
    var person = (handle: "", admin: false)
    /// Asked to open a terminal in workspace `name` of `project`.
    var onOpen: @MainActor (_ project: String, _ name: String) -> Void = { _, _ in }
    var look: Look {
        didSet { paint() }
    }

    private let heading = NSTextField(labelWithString: "Workspaces")
    private let scroll = NSScrollView()
    private lazy var listHeight = scroll.heightAnchor.constraint(equalToConstant: 0)
    private let projectGroup = NSStackView()
    private let newGroup = NSStackView()
    private let emptyGroup = NSStackView()
    private lazy var nameFrame = FieldFrame(name, look: look)
    private var lines: [String] = []

    init(look: Look, title: TitleFont, mono: TerminalFonts) {
        self.look = look
        open = AccentButton("Open", look: look, target: nil, action: #selector(WorkspacesPane.openSelected(_:)))
        super.init(frame: .zero)
        heading.font = title.font(.title2) as NSFont
        compose(mono: mono)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Everything the pane says now, a line each, as the drive reads it.
    public var text: String {
        let shown = [projectGroup.isHidden ? "" : projects.titleOfSelectedItem ?? ""] + rows.map(line)
            + (emptyGroup.isHidden ? [] : [empty.stringValue]) + (said.isHidden ? [] : [said.stringValue])
        return ([heading.stringValue] + shown).filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// The pane is shown: its projects, then the chosen one's workspaces, asked again.
    func shown() {
        busy = true
        Task { await refresh() }
    }

    func refresh() async {
        await run(failing: { self.show(.unreadable($0)) }) { () async throws(SailError) in
            guard let client = self.client else { return }
            let chosen = self.projects.titleOfSelectedItem
            let names = try await client.send(.projects).projects.map(\.name)
            self.projects.removeAllItems()
            self.projects.addItems(withTitles: names)
            if let chosen, names.contains(chosen) { self.projects.selectItem(withTitle: chosen) }
            try await self.listWorkspaces()
        }
    }

    @objc public func pickProject(_: Any?) {
        Task { await run(failing: { self.show(.unreadable($0)) }) { () async throws(SailError) in try await self.listWorkspaces() } }
    }

    @objc public func makeWorkspace(_: Any?) {
        let wanted = name.stringValue.trimmingCharacters(in: .whitespaces)
        guard !busy, let project = projects.titleOfSelectedItem, let client, !wanted.isEmpty else { return }
        say(["Making \(wanted) (about 3 s)"])
        busy = true
        make.title = "Making…"
        [make, open].forEach { $0.isEnabled = false }
        Task {
            await run(failing: { self.say($0) }) { () async throws(SailError) in
                let made = try await client.send(.createWorkspace(wanted, project: project))
                self.name.stringValue = ""
                try await self.listWorkspaces(selecting: made.workspace)
                self.say(made.stale ? ["\(made.workspace) was made from an older template."] : [])
            }
        }
    }

    /// Opens the chosen workspace in a tab, its button saying so meanwhile; pressed again, or
    /// during a call, it does nothing.
    @objc public func openSelected(_: Any?) {
        guard !busy, open.isEnabled, let project = projects.titleOfSelectedItem, let row = chosen,
              row.opens(for: person.handle, admin: person.admin) else { return }
        open.setTitle(row.startsWhenOpened ? "Starting…" : "Opening…")
        open.isEnabled = false
        onOpen(project, row.name)
    }

    @objc func askAgain(_: Any?) {
        shown()
    }

    public func numberOfRows(in _: NSTableView) -> Int {
        rows.count
    }

    public func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
        WorkspaceCell(rows[row], for: person.handle, look: look)
    }

    public func tableView(_: NSTableView, rowViewForRow _: Int) -> NSTableRowView? {
        WorkspaceRowView(look: look)
    }

    public func tableViewSelectionDidChange(_: Notification) {
        enableOpen()
    }

    private var chosen: WorkspaceRow? {
        rows.indices.contains(list.selectedRow) ? rows[list.selectedRow] : nil
    }

    private func line(_ row: WorkspaceRow) -> String {
        "\(row.title(for: person.handle)) · \(row.said)"
    }

    private func listWorkspaces(selecting wanted: String? = nil) async throws(SailError) {
        guard let client, let project = projects.titleOfSelectedItem else {
            rows = []
            list.reloadData()
            return show(.noProjects)
        }
        let kept = wanted ?? chosen?.name
        rows = try await client.send(.workspaces(project: project)).rows(for: person.handle)
        list.reloadData()
        listHeight.constant = Rail.item * Double(min(rows.count, Self.shownRows))
        show(rows.isEmpty ? .noWorkspaces(project: project) : .listed)
        if let index = rows.firstIndex(where: { $0.name == kept }) ?? (rows.isEmpty ? nil : 0) {
            list.selectRowIndexes([index], byExtendingSelection: false)
            list.scrollRowToVisible(index)
            if !isHiddenOrHasHiddenAncestor { window?.makeFirstResponder(list) }
        }
        enableOpen()
    }

    /// Shows `body` where the list goes, and the groups that make sense beside it.
    private func show(_ body: Body) {
        self.body = body
        let hasProjects = projects.numberOfItems > 0
        projectGroup.isHidden = !hasProjects
        newGroup.isHidden = !hasProjects
        scroll.isHidden = body != .listed
        open.isHidden = body != .listed
        emptyGroup.isHidden = body == .listed
        tryAgain.isHidden = true
        switch body {
        case .listed:
            paintEmpty([])
        case .noProjects:
            paintEmpty(["No project on this control plane is open to you yet.", "An admin adds you to one."])
        case let .noWorkspaces(project):
            paintEmpty(["No workspaces in \(project) yet.", "Name one below to make it."])
        case let .unreadable(lines):
            tryAgain.isHidden = false
            paintEmpty(lines)
        }
    }

    private func enableOpen() {
        open.setTitle(chosen?.startsWhenOpened == true ? "Start and Open" : "Open")
        open.isEnabled = !busy && chosen?.opens(for: person.handle, admin: person.admin) == true
    }

    private func run(failing: @MainActor ([String]) -> Void, _ act: @MainActor () async throws(SailError) -> Void) async {
        busy = true
        [make, open].forEach { $0.isEnabled = false }
        defer {
            busy = false
            ended += 1
            make.title = "Make"
            make.isEnabled = true
            enableOpen()
        }
        do {
            try await act()
        } catch let .refused(refusal) {
            failing(refusal.lines)
        } catch {
            failing([TerminalPane.sentence("\(error)")])
        }
    }

    private func say(_ lines: [String]) {
        self.lines = lines
        said.attributedStringValue = words(lines)
        said.isHidden = lines.isEmpty
    }

    private func paintEmpty(_ lines: [String]) {
        empty.attributedStringValue = words(lines)
    }

    /// `lines` as the pane says them: the first in the text colour, the rest secondary.
    private func words(_ lines: [String]) -> NSAttributedString {
        let text = NSMutableAttributedString()
        for (index, line) in lines.enumerated() {
            text.append(NSAttributedString(
                string: (index == 0 ? "" : "\n") + line,
                attributes: [.foregroundColor: NSColor(index == 0 ? look.text : look.textSecondary), .font: NSFont.systemFont(ofSize: Look.Step.callout.points)]
            ))
        }
        return text
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        heading.textColor = NSColor(look.text)
        for group in [projectGroup, newGroup] {
            (group.arrangedSubviews.first as? NSTextField)?.textColor = NSColor(look.textSecondary)
        }
        list.backgroundColor = NSColor(look.canvas)
        open.look = look
        nameFrame.look = look
        say(lines)
        show(body)
        let kept = list.selectedRowIndexes
        list.reloadData()
        list.selectRowIndexes(kept, byExtendingSelection: false)
    }

    private func compose(mono: TerminalFonts) {
        wantsLayer = true
        let making = NSStackView(views: [nameFrame, make])
        making.spacing = Look.Space.s
        make.controlSize = .large
        nameFrame.setContentHuggingPriority(.defaultLow, for: .horizontal)
        make.setContentHuggingPriority(.required, for: .horizontal)
        let groups: [(NSStackView, [NSView])] = [
            (projectGroup, [eyebrow("Project", mono: mono), projects]), (newGroup, [eyebrow("New workspace", mono: mono), making]),
            (emptyGroup, [empty, tryAgain]),
        ]
        for (group, views) in groups {
            views.forEach { group.addArrangedSubview($0) }
        }
        projects.target = self
        projects.action = #selector(pickProject(_:))
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("workspace"))
        column.width = Self.width
        column.resizingMask = .autoresizingMask
        list.addTableColumn(column)
        list.headerView = nil
        list.style = .plain
        list.intercellSpacing = .zero
        list.columnAutoresizingStyle = .uniformColumnAutoresizingStyle
        list.dataSource = self
        list.delegate = self
        list.rowHeight = Rail.item
        list.target = self
        list.doubleAction = #selector(openSelected(_:))
        list.focusRingType = .none
        scroll.documentView = list
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        open.target = self
        name.placeholderString = "name of a new workspace"
        name.target = self
        name.action = #selector(makeWorkspace(_:))
        make.target = self
        make.action = #selector(makeWorkspace(_:))
        tryAgain.target = self
        tryAgain.action = #selector(askAgain(_:))
        said.isSelectable = true
        said.isHidden = true
        empty.isSelectable = true
        for group in [projectGroup, newGroup, emptyGroup] {
            group.orientation = .vertical
            group.alignment = .leading
            group.spacing = Look.Space.s
            group.detachesHiddenViews = true
        }
        emptyGroup.spacing = Look.Space.m
        let stack = NSStackView(views: [heading, projectGroup, scroll, emptyGroup, open, newGroup, said])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = Look.Space.xxl
        stack.detachesHiddenViews = true
        stack.setCustomSpacing(Look.Space.l, after: heading)
        stack.setCustomSpacing(Look.Space.m, after: scroll)
        stack.setCustomSpacing(Look.Space.s, after: newGroup)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.x3l + Look.Space.l),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Look.Space.x3l),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -Look.Space.x3l),
            scroll.widthAnchor.constraint(equalToConstant: Self.width), listHeight,
            projects.widthAnchor.constraint(equalToConstant: Self.width), making.widthAnchor.constraint(equalToConstant: Self.width),
            empty.widthAnchor.constraint(equalToConstant: Self.width), said.widthAnchor.constraint(lessThanOrEqualToConstant: 560),
        ])
        show(.listed)
        enableOpen()
        paint()
    }
}

/// A workspace's row: chosen, it takes the look's selected fill at content corners and the accent
/// bar at its leading edge, measured as the rail's chosen item is (a 2 by 24 pt bar, the fill
/// 9 pt in); while the list is not what the keys act on the fill is half as strong, as macOS lists
/// show a selection they are not acting on.
final class WorkspaceRowView: NSTableRowView {
    static let inset = (Rail.width - Rail.item) / 2

    var look: Look {
        didSet { needsDisplay = true }
    }

    /// Whether the pointer is over it: an unchosen row then takes the look's hover fill.
    var hovered = false {
        didSet { needsDisplay = true }
    }

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var interiorBackgroundStyle: NSView.BackgroundStyle {
        .normal
    }

    override func drawBackground(in dirtyRect: NSRect) {
        NSColor(look.canvas).setFill()
        dirtyRect.fill()
        guard hovered, !isSelected else { return }
        NSColor(look.fillHover).setFill()
        NSBezierPath(roundedRect: Self.fill(in: bounds), xRadius: Look.contentRadius, yRadius: Look.contentRadius).fill()
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self))
    }

    override func mouseEntered(with _: NSEvent) {
        hovered = true
    }

    override func mouseExited(with _: NSEvent) {
        hovered = false
    }

    override func drawSelection(in _: NSRect) {
        let fill = look.fillSelected
        NSColor(fill).withAlphaComponent(isEmphasized ? fill.alpha : fill.alpha / 2).setFill()
        NSBezierPath(roundedRect: Self.fill(in: bounds), xRadius: Look.contentRadius, yRadius: Look.contentRadius).fill()
        NSColor(look.accent).setFill()
        Self.bar(in: bounds).fill()
    }

    static func fill(in bounds: NSRect) -> NSRect {
        NSRect(x: inset, y: bounds.minY, width: bounds.width - inset, height: bounds.height)
    }

    static func bar(in bounds: NSRect) -> NSRect {
        NSRect(x: 0, y: bounds.midY - Rail.barHeight / 2, width: Rail.bar, height: Rail.barHeight)
    }
}

/// A workspace's name in the body text; under it its state, a dot (the accent while it runs, the
/// secondary text colour otherwise) and a word in secondary text; and, when it is another person's,
/// their handle with a person symbol in secondary text at the trailing edge.
final class WorkspaceCell: NSView {
    init(_ row: WorkspaceRow, for handle: String, look: Look) {
        super.init(frame: .zero)
        let name = NSTextField(labelWithString: row.name)
        name.font = .systemFont(ofSize: Look.Step.body.points, weight: .medium)
        name.textColor = NSColor(look.text)
        let state = NSTextField(labelWithString: row.said)
        state.font = .systemFont(ofSize: Look.Step.micro.points)
        state.textColor = NSColor(look.textSecondary)
        let dot = StateDot(colour: row.isUp ? NSColor(look.accent) : NSColor(look.textSecondary))
        let owner = NSStackView()
        owner.spacing = 4
        owner.isHidden = row.owner(apartFrom: handle) == nil
        if let who = row.owner(apartFrom: handle) {
            let symbol = NSImageView(image: NSImage(systemSymbolName: "person.fill", accessibilityDescription: "owner") ?? NSImage())
            symbol.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: Look.Step.micro.points, weight: .regular)
            symbol.contentTintColor = NSColor(look.textSecondary)
            let label = NSTextField(labelWithString: who)
            label.font = .systemFont(ofSize: Look.Step.callout.points)
            label.textColor = NSColor(look.textSecondary)
            [symbol, label].forEach { owner.addArrangedSubview($0) }
        }
        for view in [name, state, dot, owner] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        let leading = WorkspaceRowView.inset + Look.Space.m
        NSLayoutConstraint.activate([
            name.leadingAnchor.constraint(equalTo: leadingAnchor, constant: leading),
            name.bottomAnchor.constraint(equalTo: centerYAnchor, constant: 1),
            dot.leadingAnchor.constraint(equalTo: name.leadingAnchor), dot.widthAnchor.constraint(equalToConstant: StateDot.size),
            dot.heightAnchor.constraint(equalToConstant: StateDot.size), dot.centerYAnchor.constraint(equalTo: state.centerYAnchor),
            state.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: StateDot.size),
            state.topAnchor.constraint(equalTo: centerYAnchor, constant: 1),
            owner.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -Look.Space.m),
            owner.centerYAnchor.constraint(equalTo: centerYAnchor),
            owner.leadingAnchor.constraint(greaterThanOrEqualTo: name.trailingAnchor, constant: Look.Space.s),
            owner.leadingAnchor.constraint(greaterThanOrEqualTo: state.trailingAnchor, constant: Look.Space.s),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }
}

/// A workspace's state as a filled dot, drawn, so it shows wherever its row is drawn.
final class StateDot: NSView {
    static let size = 6.0

    let colour: NSColor

    init(colour: NSColor) {
        self.colour = colour
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func draw(_: NSRect) {
        colour.setFill()
        NSBezierPath(ovalIn: bounds).fill()
    }
}

/// A list whose Return opens the selected row as a double click does; no button is the window's
/// default, which AppKit would paint in the system accent.
public final class OpeningTable: NSTableView {
    override public func keyDown(with event: NSEvent) {
        guard [36, 76].contains(event.keyCode), let action = doubleAction else { return super.keyDown(with: event) }
        NSApp.sendAction(action, to: target, from: self)
    }
}
