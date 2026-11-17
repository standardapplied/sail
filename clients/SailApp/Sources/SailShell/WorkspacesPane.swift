import AppKit
import SailAPI
import SailLook

/// The person's workspaces on the runner: a project of theirs, its workspaces (theirs first, each
/// its state in words), a name and Make, and Open, which opens a terminal in one as a tab. What the
/// server refuses is shown under the list in its words and what the person can do in the app. The
/// list is asked again when the pane shows and after each change it makes.
@MainActor
public final class WorkspacesPane: NSView, NSTableViewDataSource, NSTableViewDelegate {
    public let projects = NSPopUpButton()
    public let list = OpeningTable()
    public let open: AccentButton
    public let name = NSTextField()
    public let make = NSButton(title: "Make", target: nil, action: nil)
    public let said = NSTextField(wrappingLabelWithString: "")
    public private(set) var rows: [WorkspaceRow] = []
    /// Whether a call is under way, and how many have ended: what the drive waits on.
    public private(set) var busy = false
    public private(set) var ended = 0

    /// The API as the person signed in, their handle and whether they are an admin; nil signed out.
    var client: SailClient?
    var person = (handle: "", admin: false)
    /// Asked to open a terminal in workspace `name` of `project`.
    var onOpen: @MainActor (_ project: String, _ name: String) -> Void = { _, _ in }
    var look: Look {
        didSet { paint() }
    }

    private let heading = NSTextField(labelWithString: "Workspaces")
    private var lines: [String] = []

    init(look: Look, title: TitleFont) {
        self.look = look
        open = AccentButton("Open", look: look, target: nil, action: #selector(WorkspacesPane.openSelected(_:)))
        super.init(frame: .zero)
        heading.font = title.font(.title2) as NSFont
        compose()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Everything the pane says now, a line each, as the drive reads it.
    public var text: String {
        ([heading.stringValue, projects.titleOfSelectedItem ?? ""] + rows.map(line) + (said.isHidden ? [] : [said.stringValue]))
            .filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// The pane is shown: its projects, then the chosen one's workspaces, asked again.
    func shown() {
        Task { await refresh() }
    }

    func refresh() async {
        await run { () async throws(SailError) in
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
        Task { await run { () async throws(SailError) in try await self.listWorkspaces() } }
    }

    @objc public func makeWorkspace(_: Any?) {
        let wanted = name.stringValue.trimmingCharacters(in: .whitespaces)
        guard let project = projects.titleOfSelectedItem, let client, !wanted.isEmpty else { return }
        say(["Making \(wanted) (about 3 s)"])
        Task {
            await run { () async throws(SailError) in
                let made = try await client.send(.createWorkspace(wanted, project: project))
                self.name.stringValue = ""
                try await self.listWorkspaces(selecting: made.workspace)
                self.say(made.stale ? ["\(made.workspace) was made from an older template."] : [])
            }
        }
    }

    @objc public func openSelected(_: Any?) {
        guard let project = projects.titleOfSelectedItem, rows.indices.contains(list.selectedRow) else { return }
        let row = rows[list.selectedRow]
        guard row.opens(for: person.handle, admin: person.admin) else { return }
        onOpen(project, row.name)
    }

    public func numberOfRows(in _: NSTableView) -> Int {
        rows.count
    }

    public func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
        let cell = NSTextField(labelWithString: line(rows[row]))
        cell.font = .systemFont(ofSize: Look.Step.body.points)
        cell.textColor = NSColor(look.text)
        return cell
    }

    public func tableViewSelectionDidChange(_: Notification) {
        enableOpen()
    }

    private func line(_ row: WorkspaceRow) -> String {
        "\(row.title(for: person.handle)) · \(row.said)"
    }

    private func listWorkspaces(selecting wanted: String? = nil) async throws(SailError) {
        guard let client, let project = projects.titleOfSelectedItem else {
            rows = []
            return list.reloadData()
        }
        rows = try await client.send(.workspaces(project: project)).rows(for: person.handle)
        list.reloadData()
        if let index = rows.firstIndex(where: { $0.name == wanted }) { list.selectRowIndexes([index], byExtendingSelection: false) }
        enableOpen()
    }

    private func enableOpen() {
        open.isEnabled = rows.indices.contains(list.selectedRow) && rows[list.selectedRow].opens(for: person.handle, admin: person.admin)
    }

    private func run(_ act: @MainActor () async throws(SailError) -> Void) async {
        busy = true
        [make, open].forEach { $0.isEnabled = false }
        defer {
            busy = false
            ended += 1
            make.isEnabled = true
            enableOpen()
        }
        do {
            try await act()
        } catch let .refused(refusal) {
            say(refusal.lines)
        } catch {
            say([TerminalPane.sentence("\(error)")])
        }
    }

    private func say(_ lines: [String]) {
        self.lines = lines
        let text = NSMutableAttributedString()
        for (index, line) in lines.enumerated() {
            text.append(NSAttributedString(
                string: (index == 0 ? "" : "\n") + line,
                attributes: [.foregroundColor: NSColor(index == 0 ? look.text : look.textSecondary), .font: NSFont.systemFont(ofSize: Look.Step.callout.points)]
            ))
        }
        said.attributedStringValue = text
        said.isHidden = lines.isEmpty
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        heading.textColor = NSColor(look.text)
        list.backgroundColor = NSColor(look.canvas)
        open.look = look
        name.layer?.borderColor = NSColor(look.accent).cgColor
        say(lines)
        list.reloadData()
    }

    private func compose() {
        wantsLayer = true
        projects.target = self
        projects.action = #selector(pickProject(_:))
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("workspace"))
        list.addTableColumn(column)
        list.headerView = nil
        list.dataSource = self
        list.delegate = self
        list.rowHeight = 28
        list.target = self
        list.doubleAction = #selector(openSelected(_:))
        list.focusRingType = .none
        let scroll = NSScrollView()
        scroll.documentView = list
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        open.target = self
        name.placeholderString = "name of a new workspace"
        name.focusRingType = .none
        name.wantsLayer = true
        name.target = self
        name.action = #selector(makeWorkspace(_:))
        make.target = self
        make.action = #selector(makeWorkspace(_:))
        said.isSelectable = true
        said.isHidden = true
        let making = NSStackView(views: [name, make])
        making.spacing = Look.Space.s
        let stack = NSStackView(views: [heading, projects, scroll, open, making, said])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = Look.Space.l
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.x3l + Look.Space.l),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Look.Space.x3l),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -Look.Space.x3l),
            scroll.widthAnchor.constraint(equalToConstant: 380), scroll.heightAnchor.constraint(equalToConstant: 220),
            name.widthAnchor.constraint(equalToConstant: 300), projects.widthAnchor.constraint(equalToConstant: 380),
            said.widthAnchor.constraint(lessThanOrEqualToConstant: 560),
        ])
        enableOpen()
        paint()
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
