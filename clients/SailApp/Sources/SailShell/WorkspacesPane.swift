import AppKit
import SailAPI
import SailLook

/// The person's workspaces on the runner, laid out as the account pane is: the title; PROJECT and
/// its chooser, with Refresh (⌘R) beside it; the project's workspaces as a list that hugs its rows
/// (up to ``shownRows``, then it scrolls), theirs first, each its name, its state as a dot and a word
/// and, when it is another person's, its owner at the trailing edge; Open directly under it, "Start
/// and Open" for a stopped workspace, which opening starts, and why when it is not offered; then
/// NEW WORKSPACE, a name and Make. Where there is no list the body says why in a sentence: the
/// sign-in not yet known, signed out (with Sign In), no project, no workspace, or the list not read
/// (with Try Again). What the server refuses of a make is said under it in its words. The latest
/// request wins (``WorkspaceListing``), and Open and Make act on the project the rows were listed
/// for, never on what the chooser says since.
@MainActor
public final class WorkspacesPane: NSView, NSTableViewDataSource, NSTableViewDelegate, NSTextFieldDelegate {
    /// The rows the list shows before it scrolls.
    static let shownRows = 6
    static let width = 380.0

    /// What the pane's body shows where the list goes.
    enum Body: Equatable {
        case listed
        case askingWhoIsSignedIn
        case signedOut
        case signInUnknown([String])
        case noProjects
        case noWorkspaces(project: String)
        case unreadable([String])
    }

    public let projects = NSPopUpButton()
    public let refreshButton = NSButton()
    public let list = OpeningTable()
    public let open: AccentButton
    /// Why Open is not offered on the chosen row, under it.
    public let whyNot = NSTextField(wrappingLabelWithString: "")
    public let name = NSTextField()
    public let make = NSButton(title: "Make", target: nil, action: nil)
    public let said = NSTextField(wrappingLabelWithString: "")
    /// Why there is no list, where the list would be.
    public let empty = NSTextField(wrappingLabelWithString: "")
    public let tryAgain = NSButton(title: "Try Again", target: nil, action: nil)
    public var rows: [WorkspaceRow] {
        listing.rows
    }

    /// Whether a call is under way, and how many have ended: what the drive waits on.
    public var busy: Bool {
        calls > 0
    }

    public private(set) var ended = 0
    private(set) var body = Body.listed

    /// The API as the person signed in, their handle and whether they are an admin; nil signed out.
    var client: SailClient?
    var person = (handle: "", admin: false)
    /// Asked to open a terminal in workspace `name` of `project`.
    var onOpen: @MainActor (_ project: String, _ name: String) -> Void = { _, _ in }
    /// Asked to show the account, where the person signs in.
    var onSignIn: @MainActor () -> Void = {}
    /// Asked to learn again who is signed in, when the control plane could not be asked.
    var onAskWhoIsSignedIn: @MainActor () -> Void = {}
    var look: Look {
        didSet { paint() }
    }

    private let heading = NSTextField(labelWithString: "Workspaces")
    private let scroll = NSScrollView()
    private lazy var listHeight = scroll.heightAnchor.constraint(equalToConstant: 0)
    private let projectGroup = NSStackView()
    private let newGroup = NSStackView()
    private let emptyGroup = NSStackView()
    private let openGroup = NSStackView()
    private lazy var nameFrame = FieldFrame(name, look: look)
    private var lines: [String] = []
    private var listing = WorkspaceListing()
    private var calls = 0
    /// Workspaces a person asked made, not yet made: only these hold Make and Open, never a listing.
    private var making = 0
    private var refreshes = 0

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

    /// Everything the pane says now, a line each, as the drive reads it: the rows as a person reads
    /// them ("quay · running · ana").
    public var text: String {
        let project = projectGroup.isHidden ? "" : projects.titleOfSelectedItem ?? ""
        let listed: [String] = scroll.isHidden ? [] : rows.map { $0.reading(for: person.handle) }
        let words: [(NSView, String)] = [(whyNot, whyNot.stringValue), (emptyGroup, empty.stringValue), (said, said.stringValue)]
        let shown: [String] = [heading.stringValue, project] + listed + words.filter { !$0.0.isHidden }.map(\.1)
        return shown.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// The pane is shown: its projects, then the chosen one's workspaces, asked again; nothing is
    /// asked while no one is signed in.
    func shown() {
        guard client != nil else { return }
        asking()
    }

    /// Until the sign-in is known, at a launch onto this pane: nothing misleading is shown.
    func waitForSignIn() {
        show(.askingWhoIsSignedIn)
    }

    /// No one is signed in: said, with Sign In.
    func signedOut() {
        forgetProjects()
        show(.signedOut)
    }

    /// Who is signed in could not be asked, in these words: said, with Try Again.
    func signInUnknown(_ lines: [String]) {
        forgetProjects()
        show(.signInUnknown(lines))
    }

    func refresh() async {
        await asking().value
    }

    /// Asks the projects, then the chosen one's workspaces: one call, busy from now.
    @discardableResult
    private func asking() -> Task<Void, Never> {
        refreshes += 1
        let mine = refreshes
        return run(failing: { self.show(.unreadable($0)) }) { () async throws(SailError) in
            guard let client = self.client else { return }
            let names = try await client.send(.projects).projects.map(\.name)
            guard mine == self.refreshes else { return }
            let chosen = self.projects.titleOfSelectedItem
            self.projects.removeAllItems()
            self.projects.addItems(withTitles: names)
            if let chosen, names.contains(chosen) { self.projects.selectItem(withTitle: chosen) }
            try await self.listWorkspaces()
        }
    }

    @objc public func pickProject(_: Any?) {
        run(failing: { self.show(.unreadable($0)) }) { () async throws(SailError) in try await self.listWorkspaces() }
    }

    @objc public func refreshList(_: Any?) {
        shown()
    }

    @objc public func makeWorkspace(_: Any?) {
        let wanted = name.stringValue.trimmingCharacters(in: .whitespaces)
        guard making == 0, let project = projects.titleOfSelectedItem, let client, !wanted.isEmpty else { return }
        say(["Making \(wanted) (about 3 s)"])
        make.title = "Making…"
        run(byAPerson: true, failing: { self.say($0) }) { () async throws(SailError) in
            let made = try await client.send(.createWorkspace(wanted, project: project))
            self.name.stringValue = ""
            try await self.listWorkspaces(selecting: made.workspace)
            self.say(made.stale ? ["\(made.workspace) was made from an older template."] : [])
        }
    }

    /// Opens the chosen workspace in a tab, in the project it was listed for, its button saying so
    /// meanwhile; pressed again, or during a call, it does nothing.
    @objc public func openSelected(_: Any?) {
        guard making == 0, open.isEnabled, let project = listing.listed, let row = chosen,
              row.opens(for: person.handle, admin: person.admin) else { return }
        open.setTitle(row.startsWhenOpened ? "Starting…" : "Opening…")
        open.isEnabled = false
        onOpen(project, row.name)
    }

    @objc func askAgain(_: Any?) {
        switch body {
        case .signedOut: onSignIn()
        case .signInUnknown: onAskWhoIsSignedIn()
        default: shown()
        }
    }

    public func controlTextDidChange(_: Notification) {
        enable()
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
        enable()
    }

    private var chosen: WorkspaceRow? {
        rows.indices.contains(list.selectedRow) ? rows[list.selectedRow] : nil
    }

    private func forgetProjects() {
        projects.removeAllItems()
        listing.clear()
        list.reloadData()
    }

    /// Lists the chosen project's workspaces; an answer overtaken by a later request, or for a
    /// project no longer chosen, is dropped, and so is its failure.
    private func listWorkspaces(selecting wanted: String? = nil) async throws(SailError) {
        guard let client, let project = projects.titleOfSelectedItem else {
            forgetProjects()
            return show(.noProjects)
        }
        let number = listing.ask(project)
        let kept = wanted ?? chosen?.name
        let listed: [WorkspaceRow]
        do throws(SailError) {
            listed = try await client.send(.workspaces(project: project)).rows(for: person.handle)
        } catch {
            guard listing.isLatest(number) else { return }
            throw error
        }
        guard listing.take(listed, request: number) else { return }
        list.reloadData()
        listHeight.constant = Rail.item * Double(min(rows.count, Self.shownRows))
        show(rows.isEmpty ? .noWorkspaces(project: project) : .listed)
        if let index = rows.firstIndex(where: { $0.name == kept }) ?? (rows.isEmpty ? nil : 0) {
            list.selectRowIndexes([index], byExtendingSelection: false)
            list.scrollRowToVisible(index)
            if !isHiddenOrHasHiddenAncestor { window?.makeFirstResponder(list) }
        }
        enable()
    }

    /// Shows `body` where the list goes, and the groups that make sense beside it.
    private func show(_ body: Body) {
        self.body = body
        let hasProjects = projects.numberOfItems > 0
        projectGroup.isHidden = !hasProjects
        newGroup.isHidden = !hasProjects
        scroll.isHidden = body != .listed
        open.isHidden = body != .listed
        openGroup.isHidden = body != .listed
        emptyGroup.isHidden = body == .listed
        tryAgain.isHidden = true
        switch body {
        case .listed:
            paintEmpty([])
        case .askingWhoIsSignedIn:
            paintEmpty(["Asking the control plane who is signed in…"])
        case .signedOut:
            offer("Sign In")
            paintEmpty(["You are signed out of the control plane.", "Sign in to see your workspaces on the runner."])
        case let .signInUnknown(lines):
            offer("Try Again")
            paintEmpty(lines)
        case .noProjects:
            paintEmpty(["No project on this control plane is open to you yet.", "An admin adds you to one."])
        case let .noWorkspaces(project):
            paintEmpty(["No workspaces in \(project) yet.", "Name one below to make it."])
        case let .unreadable(lines):
            offer("Try Again")
            paintEmpty(lines)
        }
        enable()
    }

    private func offer(_ title: String) {
        tryAgain.title = title
        tryAgain.isHidden = false
    }

    /// Open, its words and why not; Make, while a name is typed; neither while a workspace a person
    /// asked for is being made. A listing under way holds neither: it says nothing a person could
    /// read as why.
    private func enable() {
        open.setTitle(chosen?.startsWhenOpened == true ? "Start and Open" : "Open")
        open.isEnabled = making == 0 && chosen?.opens(for: person.handle, admin: person.admin) == true
        let why = body == .listed ? chosen?.notOpened(for: person.handle, admin: person.admin) : nil
        whyNot.stringValue = why ?? ""
        whyNot.isHidden = why == nil
        if making == 0 { make.title = "Make" }
        make.isEnabled = making == 0 && !name.stringValue.trimmingCharacters(in: .whitespaces).isEmpty
        refreshButton.isEnabled = client != nil
    }

    /// The pane's calls and its one busy count: busy from this call on, its failure said by
    /// `failing`. A call `byAPerson` (making a workspace) holds Open and Make until it ends; a
    /// listing holds nothing.
    @discardableResult
    private func run(
        byAPerson person: Bool = false, failing: @escaping @MainActor ([String]) -> Void,
        _ act: @escaping @MainActor () async throws(SailError) -> Void
    ) -> Task<Void, Never> {
        calls += 1
        if person { making += 1 }
        [make, open].forEach { $0.isEnabled = false }
        return Task { await self.finish(byAPerson: person, failing: failing, act) }
    }

    private func finish(
        byAPerson person: Bool, failing: @MainActor ([String]) -> Void, _ act: @MainActor () async throws(SailError) -> Void
    ) async {
        defer {
            calls -= 1
            if person { making -= 1 }
            ended += 1
            enable()
        }
        do {
            try await act()
        } catch let .refused(refusal) {
            failing(refusal.lines)
        } catch {
            failing([Said.sentence(error.description)])
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
        whyNot.textColor = NSColor(look.textSecondary)
        refreshButton.contentTintColor = NSColor(look.textSecondary)
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
        let chooser = NSStackView(views: [projects, refreshButton])
        chooser.spacing = Look.Space.s
        refreshButton.image = NSImage(systemSymbolName: "arrow.clockwise", accessibilityDescription: "List again")
        refreshButton.isBordered = false
        refreshButton.toolTip = "List again (⌘R)"
        refreshButton.keyEquivalent = "r"
        refreshButton.keyEquivalentModifierMask = .command
        refreshButton.target = self
        refreshButton.action = #selector(refreshList(_:))
        whyNot.font = .systemFont(ofSize: Look.Step.callout.points)
        whyNot.isHidden = true
        name.delegate = self
        let groups: [(NSStackView, [NSView])] = [
            (projectGroup, [eyebrow("Project", mono: mono), chooser]), (newGroup, [eyebrow("New workspace", mono: mono), making]),
            (emptyGroup, [empty, tryAgain]), (openGroup, [open, whyNot]),
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
        for group in [projectGroup, newGroup, emptyGroup, openGroup] {
            group.orientation = .vertical
            group.alignment = .leading
            group.spacing = Look.Space.s
            group.detachesHiddenViews = true
        }
        emptyGroup.spacing = Look.Space.m
        let stack = NSStackView(views: [heading, projectGroup, scroll, emptyGroup, openGroup, newGroup, said])
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
            whyNot.widthAnchor.constraint(lessThanOrEqualToConstant: Self.width),
        ])
        show(.listed)
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
        NSColor(isEmphasized ? look.fillSelected : look.fillSelectedQuiet).setFill()
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
