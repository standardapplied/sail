import AppKit
import SailAPI
import SailControls
import SailLook

/// The Workspaces section: the left panel with the projects and, beside it, the chosen project's
/// Workspaces view; before anyone is signed in, or with nothing listed, one card saying why. It asks
/// the control plane, hands the answers to ``WorkspacesModel`` and draws what the model says. Only
/// a person's action holds a control (#462): a refresh never does. Under a 720 pt card the panel
/// hides, and ⌃⌘S (View ▸ Show Sidebar) slides it over the content.
@MainActor
public final class WorkspacesView: NSView {
    static let narrow = 720.0, cardWidth = 440.0
    /// Below the window's own size priority: a width the section would like never holds the window
    /// wider than the person made it (a 750 here held the card at 472 pt, not the least 416).
    static let yields = NSLayoutConstraint.Priority.defaultLow

    var client: SailClient?
    var person = WorkspacesModel.Person(handle: "", admin: false, confirmed: false, controlPlane: "") {
        didSet { if person != oldValue { model = WorkspacesModel(person: person) } }
    }

    var onOpen: @MainActor (_ project: String, _ name: String) -> Void = { _, _ in }
    var onSignIn: @MainActor () -> Void = {}
    var onAskWhoIsSignedIn: @MainActor () -> Void = {}
    /// Its look, taken at once while shown and on being shown while hidden: a look switched under
    /// another section costs that section's redraw nothing (the drive's panes4 step waited 101 ms when it did).
    var look: Look {
        didSet {
            guard look != oldValue else { return }
            if isHidden { lookOwed = true } else { relook() }
        }
    }

    private var lookOwed = false

    package let panel: LeftPanel
    package let project: ProjectView
    /// The card shown in place of the panel and the project: an empty state saying why.
    let card = NSView()
    /// The card's first action as last drawn: Sign In or Try Again.
    private(set) var tryAgain: SailButton
    private var cardSaid: [String] = []
    /// When the projects were last asked for and not listed.
    private var triedAt: Date?
    private let titleFont: TitleFont
    private let mono: LabelFont
    private var model = WorkspacesModel(person: WorkspacesModel.Person(handle: "", admin: false, confirmed: false, controlPlane: ""))
    private var door = Door.asking
    private var overlaid = false
    /// A person's action is under way; the drive waits on it.
    package private(set) var busy = false
    /// Actions a person started that have ended, for the drive.
    package private(set) var ended = 0

    private enum Door: Equatable {
        case asking, signedOut, unknown([String]), signedIn
    }

    init(look: Look, title: TitleFont, mono: LabelFont) {
        self.look = look
        titleFont = title
        self.mono = mono
        panel = LeftPanel(look: look, mono: mono)
        project = ProjectView(look: look, mono: mono, titleFont: title)
        tryAgain = SailButton("Try Again", kind: .primary, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        let cardWidth = card.widthAnchor.constraint(equalToConstant: Self.cardWidth)
        cardWidth.priority = Self.yields
        for view in [project, card, panel] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: leadingAnchor), panel.topAnchor.constraint(equalTo: topAnchor), panel.bottomAnchor.constraint(equalTo: bottomAnchor),
            project.topAnchor.constraint(equalTo: topAnchor), project.bottomAnchor.constraint(equalTo: bottomAnchor), project.trailingAnchor.constraint(equalTo: trailingAnchor),
            card.centerXAnchor.constraint(equalTo: centerXAnchor), card.centerYAnchor.constraint(equalTo: centerYAnchor), cardWidth,
            card.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -32),
        ])
        contentLeading = project.leadingAnchor.constraint(equalTo: panel.trailingAnchor)
        contentFull = project.leadingAnchor.constraint(equalTo: leadingAnchor)
        contentLeading.isActive = true
        panel.onChoose = { [weak self] name in self?.choose(name) }
        project.on = { [weak self] act in self?.act(act) }
        paint()
        render()
    }

    private func relook() {
        lookOwed = false
        panel.look = look
        project.look = look
        paint()
        render()
    }

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        if lookOwed { relook() }
    }

    /// The section's ground, the card's canvas, as the approved frames draw it around the list.
    private func paint() {
        wantsLayer = true
        layer?.backgroundColor = NSColor(look.canvas).cgColor
    }

    private var contentLeading: NSLayoutConstraint!
    private var contentFull: NSLayoutConstraint!

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Everything the section says now, a line each, as the drive reads it.
    public var text: String {
        switch door {
        case .asking, .signedOut, .unknown: return (["Workspaces"] + cardSaid).joined(separator: "\n")
        case .signedIn: break
        }
        switch model.screen {
        case .loading: return "Workspaces"
        case .unreachable: return (["Workspaces"] + cardSaid).joined(separator: "\n")
        case let .shown(panel, .noProjects(empty)): return (["Workspaces", panel.who, empty.title, empty.body] + (empty.line.map { [$0] } ?? [])).joined(separator: "\n")
        case let .shown(_, .project(page)):
            var lines = ["Workspaces", page.name, page.lede] + (page.banner.map { [$0.title, $0.line] } ?? [])
            switch page.body {
            case .loading, .unlisted: break
            case let .empty(empty): lines += [empty.title, empty.body]
            case let .list(table):
                lines += table.create?.refusal ?? []
                lines += table.creating.map { [said($0)] } ?? []
                lines += (table.yours + table.others).map(said)
            }
            return lines.filter { !$0.isEmpty }.joined(separator: "\n")
        }
    }

    /// A row as a person reads it: its name, its state, and its owner when not theirs ("quay · running · ana").
    private func said(_ line: WorkspacesModel.Line) -> String {
        var parts = [line.name, WorkspaceLine.pill(line.state).label.lowercased()]
        if let owner = line.owner { parts.append(owner) }
        return parts.joined(separator: " · ")
    }

    // MARK: The door

    /// Until the sign-in is known, at a launch onto this section.
    func waitForSignIn() {
        door = .asking
        render()
    }

    func signedOut() {
        door = .signedOut
        render()
    }

    func signInUnknown(_ lines: [String]) {
        door = .unknown(lines)
        render()
    }

    /// The section is shown: its projects asked again, then the chosen one's workspaces.
    func shown() {
        guard client != nil else { return }
        door = .signedIn
        askProjects()
    }

    func refresh() async {
        await askProjects().value
    }

    @objc package func askAgain(_: Any?) {
        switch door {
        case .signedOut: onSignIn()
        case .unknown: onAskWhoIsSignedIn()
        case .asking: break
        case .signedIn: askProjects()
        }
    }

    // MARK: Asking the control plane

    @discardableResult
    private func askProjects() -> Task<Void, Never> {
        model.askingProjects()
        render()
        return Task {
            guard let client else { return }
            let answer = await Self.result { () async throws(SailError) in try await client.send(.projects) }
            if case .failure = answer { triedAt = Date() }
            if let request = model.projectsListed(answer) { list(request) }
            render()
        }
    }

    private func list(_ request: Int) {
        guard let client, let name = model.chosen else { return }
        Task {
            let answer = await Self.result { () async throws(SailError) in try await client.send(.workspaces(project: name)) }
            model.listed(answer, request: request, at: Date())
            render()
        }
    }

    private func choose(_ name: String) {
        guard name != model.chosen else { return }
        let request = model.choose(name)
        render()
        list(request)
    }

    /// Runs a person's action: busy while it runs, counted when it ends.
    private func byAPerson(_ work: @escaping @MainActor () async -> Void) {
        busy = true
        Task {
            await work()
            busy = false
            ended += 1
            render()
        }
    }

    private func act(_ act: ProjectView.Act) {
        guard let client else { return }
        guard let projectName = model.chosen else {
            if act == .checkAgain || act == .listAgain { askProjects() }
            return
        }
        switch act {
        case let .open(name), let .startAndOpen(name): onOpen(projectName, name)
        case let .copyName(name):
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(name, forType: .string)
        case .newWorkspace:
            model.beginCreate()
            render()
            project.focusField()
        case let .typed(name):
            let refused = model.screen.createRefused
            model.type(name)
            guard refused else { return }
            render()
            project.focusField()
        case .create:
            guard let name = model.sendCreate() else { return }
            render()
            byAPerson { [weak self] in
                guard let self else { return }
                let answer = await Self.result { () async throws(SailError) in try await client.send(.createWorkspace(name, project: projectName)) }
                if let request = model.created(answer) { list(request) }
            }
        case .cancelCreate:
            model.cancelCreate()
            render()
        case let .chose(name): model.chooseRow(name)
        case let .show(name):
            model.chooseRow(name)
            render()
        case .listAgain, .checkAgain:
            if case .shown(_, .project) = model.screen { list(model.listAgain()) } else { askProjects() }
        }
    }

    private static func result<Value>(_ work: () async throws(SailError) -> Value) async -> Result<Value, SailError> {
        do throws(SailError) {
            return .success(try await work())
        } catch {
            return .failure(error)
        }
    }

    // MARK: Drawing

    private func render() {
        let screen: WorkspacesModel.Screen? = door == .signedIn ? model.screen : nil
        switch (door, screen) {
        case let (_, .shown(panelModel, content)?):
            card.isHidden = true
            panel.render(panelModel)
            setPanel(shown: true)
            switch content {
            case let .project(page):
                project.isHidden = false
                project.render(page)
            case let .noProjects(empty):
                project.isHidden = false
                project.renderEmpty(empty)
            }
        case (_, .loading?):
            card.isHidden = true
            panel.renderLoading()
            setPanel(shown: true)
            project.isHidden = false
            project.renderLoading()
        default:
            project.isHidden = true
            setPanel(shown: false)
            card.isHidden = false
            showCard(screen)
        }
    }

    /// The card as an empty state: who is signed in being asked, signed out, not known, or the
    /// control plane out of reach with when it was last tried.
    private func showCard(_ screen: WorkspacesModel.Screen?) {
        let (icon, title, body, button, line): (LineIcon, String, String, String?, String?) = switch (door, screen) {
        case (.asking, _): (.box, "Asking the control plane who is signed in…", "", nil, nil)
        case (.signedOut, _): (.box, "Sign in to see your workspaces", "You are signed out of the control plane.", "Sign In", nil)
        case let (.unknown(lines), _): (.cloudOff, lines.first ?? "", lines.dropFirst().joined(separator: " "), "Try Again", nil)
        case let (_, .unreachable(empty)?): (.cloudOff, empty.title, empty.body, "Try Again", triedAt.map { "last tried " + Self.clock.string(from: $0) })
        default: (.box, "", "", nil, nil)
        }
        cardSaid = [title, body].filter { !$0.isEmpty }
        let first = SailButton(button ?? "Try Again", kind: .primary, look: look, target: self, action: #selector(askAgain(_:)))
        if button == "Try Again" { first.shortcut("r", modifiers: .command, mono: mono) }
        tryAgain = first
        var actions = button == nil ? [] : [first]
        if case .unreachable? = screen { actions.append(SailButton("Open Account", kind: .quiet, look: look, target: self, action: #selector(openAccount(_:)))) }
        let empty = EmptyState(icon: icon, title: title, body: body, actions: actions, line: line, look: look, titleFont: titleFont, mono: mono)
        card.subviews.forEach { $0.removeFromSuperview() }
        empty.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(empty)
        NSLayoutConstraint.activate([
            empty.leadingAnchor.constraint(equalTo: card.leadingAnchor), empty.trailingAnchor.constraint(equalTo: card.trailingAnchor),
            empty.topAnchor.constraint(equalTo: card.topAnchor), empty.bottomAnchor.constraint(equalTo: card.bottomAnchor),
        ])
    }

    private static let clock = {
        let clock = DateFormatter()
        clock.dateFormat = "HH:mm:ss"
        return clock
    }()

    @objc private func openAccount(_: Any?) {
        onSignIn()
    }

    private var panelShown = true

    private func setPanel(shown: Bool) {
        panelShown = shown
        arrange()
    }

    /// ⌃⌘S: the hidden panel slides over the content, or away.
    @objc package func toggleSidebar(_: Any?) {
        overlaid.toggle()
        arrange()
    }

    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        arrange()
    }

    /// The panel beside the content, hidden under a narrow card unless slid over it; changed only
    /// when it changes, never from inside a layout pass.
    private func arrange() {
        guard let contentLeading, let contentFull else { return }
        let narrow = Double(bounds.width) < Self.narrow, beside = panelShown && !narrow
        let hidden = !panelShown || (narrow && !overlaid)
        if panel.isHidden != hidden { panel.isHidden = hidden }
        guard contentLeading.isActive != beside else { return }
        if beside {
            contentFull.isActive = false
            contentLeading.isActive = true
        } else {
            contentLeading.isActive = false
            contentFull.isActive = true
        }
    }
}

extension WorkspacesModel.Screen {
    /// A create was refused and its refusal is shown.
    var createRefused: Bool {
        guard case let .shown(_, .project(page)) = self, case let .list(table) = page.body else { return false }
        return !(table.create?.refusal.isEmpty ?? true)
    }
}
