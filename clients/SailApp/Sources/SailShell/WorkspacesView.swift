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
    public static let narrow = 720.0

    public var client: SailClient?
    public var person = WorkspacesModel.Person(handle: "", admin: false, confirmed: false, controlPlane: "") {
        didSet { if person != oldValue { model = WorkspacesModel(person: person) } }
    }

    public var onOpen: @MainActor (_ project: String, _ name: String) -> Void = { _, _ in }
    public var onSignIn: @MainActor () -> Void = {}
    public var onAskWhoIsSignedIn: @MainActor () -> Void = {}
    public var look: Look {
        didSet {
            panel.look = look
            render()
        }
    }

    public let panel: LeftPanel
    public let project: ProjectView
    let card = NSStackView()
    let tryAgain: SailButton
    private let cardWords = NSTextField(wrappingLabelWithString: "")
    private let titleFont: TitleFont
    private var model = WorkspacesModel(person: WorkspacesModel.Person(handle: "", admin: false, confirmed: false, controlPlane: ""))
    private var door = Door.asking
    private var overlaid = false
    /// A person's action is under way; the drive waits on it.
    public private(set) var busy = false
    /// Actions a person started that have ended, for the drive.
    public private(set) var ended = 0

    private enum Door: Equatable {
        case asking, signedOut, unknown([String]), signedIn
    }

    public init(look: Look, title: TitleFont, mono: LabelFont) {
        self.look = look
        titleFont = title
        panel = LeftPanel(look: look, mono: mono)
        project = ProjectView(look: look, mono: mono, titleFont: title)
        tryAgain = SailButton("Try Again", kind: .primary, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        tryAgain.target = self
        tryAgain.action = #selector(askAgain(_:))
        card.setViews([cardWords, tryAgain], in: .top)
        card.orientation = .vertical
        card.alignment = .centerX
        card.spacing = 14
        for view in [project, card, panel] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: leadingAnchor), panel.topAnchor.constraint(equalTo: topAnchor), panel.bottomAnchor.constraint(equalTo: bottomAnchor),
            project.topAnchor.constraint(equalTo: topAnchor), project.bottomAnchor.constraint(equalTo: bottomAnchor), project.trailingAnchor.constraint(equalTo: trailingAnchor),
            card.centerXAnchor.constraint(equalTo: centerXAnchor), card.centerYAnchor.constraint(equalTo: centerYAnchor),
            card.widthAnchor.constraint(lessThanOrEqualToConstant: 420),
        ])
        contentLeading = project.leadingAnchor.constraint(equalTo: panel.trailingAnchor)
        contentFull = project.leadingAnchor.constraint(equalTo: leadingAnchor)
        contentLeading.isActive = true
        panel.onChoose = { [weak self] name in self?.choose(name) }
        project.on = { [weak self] act in self?.act(act) }
        render()
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
        case .asking, .signedOut, .unknown: return (["Workspaces"] + cardWords.stringValue.split(separator: "\n").map(String.init)).joined(separator: "\n")
        case .signedIn: break
        }
        switch model.screen {
        case .loading: return "Workspaces"
        case let .unreachable(said): return "Workspaces\nSail can't reach the control plane\n" + said
        case let .shown(panel, .noProjects(empty)): return (["Workspaces", panel.who, empty.title, empty.body] + (empty.line.map { [$0] } ?? [])).joined(separator: "\n")
        case let .shown(_, .project(page)):
            var lines = ["Workspaces", page.name, page.lede] + (page.banner.map { [$0.title, $0.line] } ?? [])
            switch page.body {
            case .loading, .unlisted: break
            case let .empty(empty): lines += [empty.title, empty.body]
            case let .list(table):
                lines += table.create?.refusal ?? []
                lines += (table.yours + table.others).map(said)
            }
            return lines.filter { !$0.isEmpty }.joined(separator: "\n")
        }
    }

    /// A row as a person reads it: its name, its state, and its owner when not theirs ("quay · running · ana").
    private func said(_ line: WorkspacesModel.Line) -> String {
        var parts = [line.name, WorkspaceLine.pill(line.state).label.lowercased()]
        if let owner = line.owner, owner != person.handle { parts.append(owner) }
        return parts.joined(separator: " · ")
    }

    public var screen: WorkspacesModel.Screen {
        model.screen
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

    @objc func askAgain(_: Any?) {
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
        Task {
            guard let client else { return }
            let answer = await Self.result { () async throws(SailError) in try await client.send(.projects) }
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
            window?.makeFirstResponder(project.field.field)
        case let .typed(name):
            let refused = model.screen.createRefused
            model.type(name)
            if refused { render() }
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
        default:
            project.isHidden = true
            setPanel(shown: false)
            card.isHidden = false
            let (words, button): ([String], String?) = switch (door, screen) {
            case (.asking, _): (["Asking the control plane who is signed in…"], nil)
            case (.signedOut, _): (["You are signed out of the control plane.", "Sign in to see your workspaces on the runner."], "Sign In")
            case let (.unknown(lines), _): (lines, "Try Again")
            case let (_, .unreachable(said)?): (
                    ["Sail can't reach the control plane", said, "Your terminals on this Mac keep working; your workspaces come back when the control plane answers."], "Try Again"
                )
            default: ([], nil)
            }
            cardWords.stringValue = words.joined(separator: "\n")
            cardWords.alignment = .center
            tryAgain.title = button ?? "Try Again"
            tryAgain.isHidden = button == nil
        }
    }

    private var panelShown = true

    private func setPanel(shown: Bool) {
        panelShown = shown
        arrange()
    }

    /// ⌃⌘S: the hidden panel slides over the content, or away.
    @objc public func toggleSidebar(_: Any?) {
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
