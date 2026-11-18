import AppKit
import SailAPI
import SailControls
import SailLook

/// A project's Workspaces view, as the approved design draws it: its name and the line under it,
/// List Again and New Workspace at the top right, a banner when something went wrong, then the list
/// (yours, then other people's, the create row first among yours), an empty state, or skeleton rows;
/// a foot with the count and the keys. It draws what ``WorkspacesModel`` says and sends the person's
/// actions back; it keeps nothing of its own.
@MainActor
public final class ProjectView: NSView, NSTextFieldDelegate {
    public enum Act: Equatable {
        case open(String), startAndOpen(String), copyName(String)
        case newWorkspace, listAgain, checkAgain, typed(String), create, cancelCreate, chose(String?), show(String)
    }

    static let padding = (top: 26.0, sides: 32.0, bottom: 20.0), listGap = 18.0, footGap = 12.0, actionsColumn = 214.0

    public var on: @MainActor (Act) -> Void = { _ in }
    public var look: Look
    let list: SailList
    let title = NSTextField(labelWithString: "")
    let lede = NSTextField(labelWithString: "")
    let listAgain: SailButton
    let newWorkspace: SailButton
    let field: SailTextField
    private let mono: LabelFont
    private let titleFont: TitleFont
    private let column = NSStackView()
    private let frameView = ListFrame()
    private var page: WorkspacesModel.Page?

    public init(look: Look, mono: LabelFont, titleFont: TitleFont) {
        self.look = look
        self.mono = mono
        self.titleFont = titleFont
        list = SailList(rowStyle: .list, look: look, mono: mono)
        list.actionsColumn = Self.actionsColumn
        listAgain = SailButton(icon: .refresh, label: "List Again", look: look, target: nil, action: nil)
        newWorkspace = SailButton("New Workspace", kind: .secondary, icon: .plus, look: look, target: nil, action: nil)
        field = SailTextField(label: nil, placeholder: "name of a new workspace", size: .small, look: look, mono: mono)
        super.init(frame: .zero)
        listAgain.shortcut("r", modifiers: .command, mono: mono)
        newWorkspace.shortcut("n", modifiers: .command, mono: mono)
        listAgain.target = self
        listAgain.action = #selector(pressed(_:))
        newWorkspace.target = self
        newWorkspace.action = #selector(pressed(_:))
        list.onChoose = { [weak self] in self?.on(.chose($0)) }
        field.field.delegate = self
        frameView.look = look
        frameView.addSubview(list)
        column.orientation = .vertical
        column.alignment = .width
        column.spacing = 0
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        NSLayoutConstraint.activate([
            column.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Self.padding.sides),
            column.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -Self.padding.sides),
            column.topAnchor.constraint(equalTo: topAnchor, constant: Self.padding.top),
            column.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -Self.padding.bottom),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    /// Draws `page`.
    public func render(_ page: WorkspacesModel.Page) {
        self.page = page
        title.attributedStringValue = NSAttributedString(string: page.name, attributes: [
            .font: titleFont.font(.title1) as NSFont, .foregroundColor: NSColor(look.text), .kern: -0.012 * Look.Step.title1.points,
        ])
        lede.attributedStringValue = NSAttributedString(string: page.lede, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ])
        newWorkspace.isEnabled = page.canCreate
        newWorkspace.kind = Self.isEmptyList(page.body) ? .primary : .secondary
        var views: [NSView] = [header(), spacer(Self.listGap)]
        if let notice = page.banner { views += [banner(notice), spacer(12)] }
        switch page.body {
        case .loading: views.append(skeletons())
        case .unlisted: break
        case let .empty(empty): views.append(emptyState(empty))
        case let .list(table):
            fill(table)
            frameView.alphaValue = page.stale ? 0.55 : 1
            views += [frameView, spacer(Self.footGap), foot(table.count, listedAt: page.stale ? page.listedAt : nil)]
        }
        column.setViews(views, in: .top)
    }

    public func controlTextDidChange(_: Notification) {
        on(.typed(field.field.stringValue))
    }

    /// Return creates and Esc cancels, from the name field.
    public func control(_: NSControl, textView _: NSTextView, doCommandBy selector: Selector) -> Bool {
        switch selector {
        case #selector(NSResponder.insertNewline(_:)): on(.create)
        case #selector(NSResponder.cancelOperation(_:)): on(.cancelCreate)
        default: return false
        }
        return true
    }

    /// Draws only `empty`, centred: no project to show.
    public func renderEmpty(_ empty: WorkspacesModel.Empty) {
        page = nil
        column.setViews([spacer(120), emptyState(empty)], in: .top)
    }

    private static func isEmptyList(_ body: WorkspacesModel.Body) -> Bool {
        if case let .empty(empty) = body { return empty.action == .newWorkspace }
        return false
    }

    /// The list's entries: Yours (the create row first), then Other people's.
    private func fill(_ table: WorkspacesModel.Table) {
        var entries: [SailList.Entry] = [.head("Yours", count: table.yours.count)]
        if let create = table.create { entries.append(.item(createItem(create))) }
        entries += table.yours.map { .item(item($0)) }
        if !table.others.isEmpty { entries += [.head("Other people's", count: table.others.count)] + table.others.map { .item(item($0)) } }
        list.entries = entries
        if let chosen = (table.yours + table.others).first(where: \.chosen) { list.choose(chosen.name) }
        let height = entries.indices.reduce(0.0) { $0 + Double(list.tableView(list, heightOfRow: $1)) }
        list.frame = NSRect(x: 0, y: 0, width: Double(frameView.bounds.width), height: height)
        frameView.height = height
    }

    private func item(_ line: WorkspacesModel.Line) -> SailList.Item {
        let content = WorkspaceLine(name: line.name, instance: line.instance, state: WorkspaceLine.pill(line.state), owner: line.owner, look: look, mono: mono)
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

    private func createItem(_ create: WorkspacesModel.CreateLine) -> SailList.Item {
        field.error = create.refusal.isEmpty ? nil : create.refusal.joined(separator: " ")
        field.isEnabled = !create.underWay
        if field.field.stringValue != create.name { field.field.stringValue = create.name }
        let row = CreateRow(field: field, underWay: create.underWay, existing: create.existing, look: look, mono: mono) { [weak self] act in self?.on(act) }
        return SailList.Item(id: "\u{0}create", name: "", voice: "A new workspace's name", content: row, height: create.refusal.isEmpty ? nil : 76)
    }

    private func header() -> NSView {
        let words = NSStackView(views: [title, lede])
        words.orientation = .vertical
        words.alignment = .leading
        words.spacing = 6
        let buttons = NSStackView(views: [listAgain, newWorkspace])
        buttons.spacing = 6 - 2 * Look.haloRoom
        let row = NSStackView(views: [words, buttons])
        row.alignment = .top
        row.distribution = .equalSpacing
        return row
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
        return EmptyState(icon: empty.missing ? .layers : .box, title: empty.title, body: empty.body, actions: [action], line: empty.line, missing: empty.missing, look: look, titleFont: titleFont, mono: mono)
    }

    private func skeletons() -> NSView {
        let rows = NSStackView(views: (0 ..< 3).map { _ in Skeleton(width: 220, look: look) })
        rows.orientation = .vertical
        rows.alignment = .leading
        rows.spacing = 30
        return rows
    }

    private func foot(_ count: Int, listedAt: Date?) -> NSView {
        let said = listedAt.map { "listed \(RelativeDateTimeFormatter().localizedString(for: $0, relativeTo: Date()))" } ?? "\(count) workspace\(count == 1 ? "" : "s")"
        var shown: [NSView] = []
        for (key, words) in [("⏎", "Open"), ("⌘N", "New"), ("⌘R", "List again")] {
            shown.append(Keycap(key, look: look, mono: mono))
            shown.append(micro(words))
        }
        let left = micro(said), keys = NSStackView(views: shown)
        keys.spacing = 6
        let row = NSStackView(views: [left, keys])
        row.distribution = .equalSpacing
        return row
    }

    /// Words in mono 11, tertiary: the foot's.
    private func micro(_ words: String) -> NSTextField {
        let font = mono.font(Look.Step.micro.points, .regular) as NSFont
        return NSTextField(labelWithAttributedString: NSAttributedString(string: words, attributes: [.font: font, .foregroundColor: NSColor(look.textTertiary)]))
    }

    private func spacer(_ height: Double) -> NSView {
        let view = NSView()
        view.heightAnchor.constraint(equalToConstant: height).isActive = true
        return view
    }

    @objc private func pressed(_ sender: NSButton) {
        switch sender {
        case listAgain: on(.listAgain)
        case newWorkspace: on(.newWorkspace)
        default:
            switch sender.title {
            case "New Workspace": on(.newWorkspace)
            case "Check Again", "Try Again": on(.checkAgain)
            default: break
            }
        }
    }
}

/// The list's frame: the surface in 6 pt corners with a hairline, holding the list at its height.
@MainActor
final class ListFrame: NSView {
    var height = 0.0 {
        didSet { invalidateIntrinsicContentSize() }
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: height + 2)
    }

    var look = Look.light {
        didSet { needsDisplay = true }
    }

    override var isFlipped: Bool {
        true
    }

    override func layout() {
        super.layout()
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

/// The create row: the name field, Cancel, and Create with ⏎; while it is under way the field
/// waits and Create says so; a refusal is under the field, with Show for a name already listed.
@MainActor
final class CreateRow: NSStackView {
    init(field: SailTextField, underWay: Bool, existing: String?, look: Look, mono: LabelFont, on: @escaping @MainActor (ProjectView.Act) -> Void) {
        let runs = Runs(on)
        let cancel = SailButton("Cancel", kind: .quiet, size: .small, look: look, target: runs, action: #selector(Runs.cancel(_:)))
        let create = SailButton("Create", kind: .primary, size: .small, look: look, target: runs, action: #selector(Runs.create(_:)))
        create.shortcut("\r", mono: mono)
        create.keyEquivalent = ""
        if underWay { create.fly("Creating") }
        cancel.isEnabled = !underWay
        let show = existing.map { name in
            runs.existing = name
            return SailButton("Show \(name)", kind: .quiet, size: .small, look: look, target: runs, action: #selector(Runs.show(_:)))
        }
        super.init(frame: .zero)
        self.runs = runs
        field.widthAnchor.constraint(equalToConstant: 250).isActive = true
        setViews(([field] as [NSView]) + [show].compactMap { $0 } + [cancel, create], in: .leading)
        alignment = .top
        spacing = 6
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
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
