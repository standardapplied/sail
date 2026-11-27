import AppKit
import SailAPI
import SailControls
import SailLook

/// The app's one left panel, as the approved navigation draws it, with the Workspaces section's
/// projects: a 46 pt header with the section's title over a hairline, then two groups to its
/// bottom edge: THIS MAC, holding the one local project, and the control plane's projects under its
/// host with their count, as the panel's project rows (tile, name, readiness pill). Who is signed in
/// and where is the user menu's to say. With no project it says so under the control plane's head;
/// before any is listed it shows skeleton rows there, and, when none could be, why, with Try Again.
/// It is 236 pt by default; its trailing edge drags it between 200 and 360 pt, remembered on this Mac.
@MainActor
package final class LeftPanel: NSView {
    static let width = (standard: 236.0, least: 200.0, most: 360.0), header = 46.0, edge = 6.0
    static let remembered = "leftPanel.width"

    var onChoose: @MainActor (String) -> Void = { _ in }
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
        list.onChoose = { [weak self] name in if let name { self?.onChoose(name) } }
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
        tryAgain.isHidden = !failed || panel.chosen != WorkspacesModel.thisMac
        createProject.isHidden = !panel.creates
        emptyLine.isHidden = panel.emptyLine == nil
        emptyLine.attributedStringValue = NSAttributedString(string: panel.emptyLine ?? "", attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.footnote.points), .foregroundColor: NSColor(look.textTertiary),
        ])
        let count = panel.listed ? panel.projects.count : nil
        if panel.projects != projects || panel.stale != stale || panel.controlPlane != headed || count != counted || panel.creates != offered {
            projects = panel.projects
            stale = panel.stale
            headed = panel.controlPlane
            counted = count
            offered = panel.creates
            list.entries = thisMac + [.head(panel.controlPlane, count: count, icon: .cloud, action: panel.creates ? createProject : nil)] + panel.projects.map { project in
                .item(row(project))
            }
        }
        if list.chosen != panel.chosen { list.choose(panel.chosen) }
        needsLayout = true
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
        let name = WorkspacesModel.thisMac, cell = SidebarRowCell(name, icon: .terminal, template: nil, tag: Self.local, look: look, mono: mono)
        return [.head(name, count: nil, icon: .laptop), .item(SailList.Item(id: name, name: name, voice: "\(name), \(Self.local.lowercased())", content: cell))]
    }

    static let local = "Local"

    /// A project's row: its tile, its name and its template's pill; VoiceOver reads its name and pill.
    private func row(_ project: WorkspacesModel.Project) -> SailList.Item {
        let pill = Self.pill(project.mark)
        let voice = pill.map { "\(project.name), \($0.label.lowercased())" } ?? project.name
        let cell = SidebarRowCell(project.name, template: pill, look: look, mono: mono)
        cell.stale = stale
        return SailList.Item(id: project.name, name: project.name, voice: voice, content: cell)
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
