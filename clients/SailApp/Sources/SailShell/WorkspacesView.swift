import AppKit
import SailAPI
import SailControls
import SailLook
import SailWorkbench

/// The Workspaces section: the left panel with the projects and, beside it, the chosen project's
/// Workspaces view; before anyone is signed in, or with nothing listed, one card saying why. It asks
/// the control plane, hands the answers to ``WorkspacesModel`` and draws what the model says. Only
/// a person's action holds a control (#462): a refresh never does. Under a 720 pt card the panel
/// hides, and ⌃⌘S (View ▸ Show Sidebar) shows it over the content until a project is chosen or
/// Escape; on a wider card ⌃⌘S hides and shows it beside the content. A row's New Session asks the
/// workspace's agents route and shows its answer in the New Session panel under the row.
@MainActor
public final class WorkspacesView: NSView {
    static let narrow = 720.0, cardWidth = 440.0
    /// Below the window's own size priority: a width the section would like never holds the window
    /// wider than the person made it (a 750 here held the card at 472 pt, not the least 416).
    static let yields = NSLayoutConstraint.Priority.defaultLow

    var client: SailClient?
    /// Who is signed in: someone else, or another control plane, starts the section afresh; their
    /// role confirmed or changed keeps what is shown and under way.
    var person = WorkspacesModel.Person(handle: "", admin: false, confirmed: false, controlPlane: "") {
        didSet {
            guard person != oldValue else { return }
            if person.handle == oldValue.handle, person.controlPlane == oldValue.controlPlane {
                model.person = person
            } else {
                model = WorkspacesModel(person: person)
                memory = remembered(person)
                model.lastStarted = memory.last?.display
                era += 1
                project.forgetList()
                newSession.close()
            }
        }
    }

    var onOpen: @MainActor (_ project: String, _ name: String) -> Void = { _, _ in }
    /// Told an agent to start in workspace `name` of `project`, after ``memory`` took it.
    var onStart: @MainActor (_ start: AgentStart, _ project: String, _ name: String) -> Void = { _, _, _ in }
    /// Reads the starts kept for a person on a control plane: asked again whenever someone else signs in.
    var remembered: @MainActor (WorkspacesModel.Person) -> ModeMemory = { _ in ModeMemory() }
    /// The person's starts: the mode chosen for each harness and the last start, which their own
    /// rows offer to start again.
    var memory = ModeMemory() {
        didSet {
            guard memory != oldValue else { return }
            model.lastStarted = memory.last?.display
            render()
        }
    }
    var onSignIn: @MainActor () -> Void = {}
    /// The clipboard a login's sheet reads and clears: the person's; a test's own.
    var pasteboard = NSPasteboard.general
    var onAskWhoIsSignedIn: @MainActor () -> Void = {}
    /// Its look, taken at once while shown and on being shown while hidden: a look switched under
    /// another section costs that section's redraw nothing (the drive's panes4 step waited 101 ms when it did).
    var look: Look {
        didSet {
            guard look != oldValue else { return }
            painter.lookChanged(hidden: isHidden)
        }
    }

    private lazy var painter = PaintWhenShown { [unowned self] in self.relook() }
    /// How many times the section was painted for a change of look: none while it is hidden, once
    /// when it is next shown.
    package private(set) var lookPaints = 0

    package let panel: LeftPanel
    /// The pop shadow the panel casts while it is slid over the content; none beside it.
    let panelShadow: PopShadowView
    package let project: ProjectView
    package let newSession: NewSessionPanel
    /// The workspace the New Session panel was last asked for.
    private var startingIn: (project: String, name: String)?
    /// The newest ask of an agents route: an answer to any earlier one is dropped when it lands.
    private var asks = 0
    /// Where the panel's row was when it opened, and what it said: the panel follows it or closes.
    private var panelRow: (frame: NSRect, line: WorkspacesModel.Line)?
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
    /// Which model an answer was asked by: one asked for someone signed in before is dropped, as its
    /// request numbers start again in the new model.
    private var era = 0
    private var door = Door.asking
    private var overlaid = false
    /// A person's action is under way; the drive waits on it.
    package var busy: Bool {
        underWay > 0
    }

    private var underWay = 0

    private enum Door: Equatable {
        case asking, signedOut, unknown([String]), signedIn
    }

    init(look: Look, title: TitleFont, mono: LabelFont) {
        self.look = look
        titleFont = title
        self.mono = mono
        panel = LeftPanel(look: look, mono: mono)
        panelShadow = PopShadowView(look: look)
        panelShadow.isHidden = true
        project = ProjectView(look: look, mono: mono, titleFont: title)
        newSession = NewSessionPanel(look: look, mono: mono)
        tryAgain = SailButton("Try Again", kind: .primary, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        let cardWidth = card.widthAnchor.constraint(equalToConstant: Self.cardWidth)
        cardWidth.priority = Self.yields
        for view in [project, card, panelShadow, panel] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: leadingAnchor), panel.topAnchor.constraint(equalTo: topAnchor), panel.bottomAnchor.constraint(equalTo: bottomAnchor),
            project.topAnchor.constraint(equalTo: topAnchor), project.bottomAnchor.constraint(equalTo: bottomAnchor), project.trailingAnchor.constraint(equalTo: trailingAnchor),
            card.centerXAnchor.constraint(equalTo: centerXAnchor), card.centerYAnchor.constraint(equalTo: centerYAnchor), cardWidth,
            card.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -32),
            panelShadow.leadingAnchor.constraint(equalTo: panel.leadingAnchor), panelShadow.trailingAnchor.constraint(equalTo: panel.trailingAnchor),
            panelShadow.topAnchor.constraint(equalTo: panel.topAnchor), panelShadow.bottomAnchor.constraint(equalTo: panel.bottomAnchor),
        ])
        contentLeading = project.leadingAnchor.constraint(equalTo: panel.trailingAnchor)
        contentFull = project.leadingAnchor.constraint(equalTo: leadingAnchor)
        contentLeading.isActive = true
        panel.onChoose = { [weak self] name in self?.choose(name) }
        project.on = { [weak self] act in self?.act(act) }
        newSession.onStart = { [weak self] start, memory in self?.started(start, memory) }
        newSession.onStoreLogin = { [weak self] offer in self?.storeLogin(offer) }
        newSession.onClose = { [weak self] in
            self?.panelRow = nil
            self?.project.list.primaryElsewhere = false
        }
        if let clip = project.listScroll?.contentView {
            clip.postsBoundsChangedNotifications = true
            NotificationCenter.default.addObserver(self, selector: #selector(listScrolled(_:)), name: NSView.boundsDidChangeNotification, object: clip)
        }
        newSession.onShell = { [weak self] in
            guard let self, let place = startingIn else { return }
            onOpen(place.project, place.name)
        }
        paint()
        render()
    }

    private func relook() {
        lookPaints += 1
        let typing = project.fieldHasKeys
        panel.look = look
        panelShadow.look = look
        question?.sheet.look = look
        project.look = look
        newSession.look = look
        paint()
        render(typing: typing)
    }

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        painter.shown()
        showQuestion()
    }

    override public func viewDidHide() {
        super.viewDidHide()
        newSession.close()
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
        case let .shown(_, .noProjects(empty)): return (["Workspaces", empty.title, empty.body] + (empty.line.map { [$0] } ?? [])).joined(separator: "\n")
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

    /// A row as a person reads it: its name, its state, its owner when not theirs, and how many of
    /// their sessions run in their own ("quay · running · ana", "dock · running · 1 session").
    private func said(_ line: WorkspacesModel.Line) -> String {
        var parts = [line.name, WorkspaceLine.pill(line).label.lowercased()]
        if let owner = line.owner { parts.append(WorkspaceLine.said(owner)) }
        if let sessions = line.sessionsSaid { parts.append(sessions) }
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

    @objc func askAgain(_: Any?) {
        switch door {
        case .signedOut: onSignIn()
        case .unknown: onAskWhoIsSignedIn()
        case .asking: break
        case .signedIn: askProjects()
        }
    }

    // MARK: Asking the control plane

    /// The projects asked again, and the chosen project's workspaces after them: Check Again, ⌘R,
    /// Try Again and showing the section all ask both, so a template built meanwhile is seen.
    @discardableResult
    private func askProjects() -> Task<Void, Never> {
        let asked = model.askingProjects(), era = self.era
        render()
        return Task {
            guard let client else { return }
            let answer = await Self.result { () async throws(SailError) in try await client.send(.projects) }
            guard era == self.era else { return }
            if case .failure = answer { triedAt = Date() }
            if let request = model.projectsListed(answer, request: asked) { list(request) }
            render()
        }
    }

    private func list(_ request: Int) {
        guard let client, let name = model.chosen else { return }
        let era = self.era
        Task {
            let answer = await Self.result { () async throws(SailError) in try await client.send(.workspaces(project: name)) }
            guard era == self.era else { return }
            model.listed(answer, request: request, at: Date())
            render()
            if case let .success(list) = answer { countSessions(in: list, by: client) }
        }
    }

    /// Asks each of the person's running workspaces in `list` for its sessions, so its row says
    /// how many of theirs run in it, drawn once all have answered; a workspace that cannot be asked
    /// says nothing of them. The route lists one workspace's sessions, so each is its own request.
    private func countSessions(in list: WorkspaceList, by client: SailClient) {
        let names = list.workspaces.filter { $0.owner == model.person.handle && $0.state == .known(.running) }.map(\.name)
        count(names, in: list.project, by: client)
    }

    /// Asks `names` of `project` for their sessions, each request numbered, and draws once.
    private func count(_ names: [String], in project: String, by client: SailClient) {
        guard !names.isEmpty else { return }
        let era = self.era
        let asked = names.map { ($0, model.askingSessions(of: $0)) }
        Task {
            var answers: [(String, Int, RunnerSessions)] = []
            for (name, request) in asked {
                if let listing = try? await client.send(.sessions(workspace: name, project: project)) { answers.append((name, request, listing)) }
            }
            guard era == self.era else { return }
            for (name, request, listing) in answers {
                model.sessionsListed(listing, project: project, workspace: name, request: request)
            }
            render()
        }
    }

    /// A runner tab in workspace `name` of `project` started or went: its row counts again.
    func recount(project: String, name: String) {
        guard let client, model.chosen == project else { return }
        count([name], in: project, by: client)
    }

    private func choose(_ name: String) {
        guard name != model.chosen else { return }
        newSession.close()
        if overlaid {
            overlaid = false
            arrange()
        }
        let request = model.choose(name)
        render()
        list(request)
        showQuestion()
    }

    /// Runs a person's action: busy while it runs.
    private func byAPerson(_ work: @escaping @MainActor () async -> Void) {
        underWay += 1
        Task {
            await work()
            underWay -= 1
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
        case let .newSession(name): askAgents(name, project: projectName, client, again: false)
        case let .startLast(name): askAgents(name, project: projectName, client, again: true)
        case let .stop(name): ask(.stop, name)
        case let .delete(name): ask(.delete(force: false), name)
        case let .copyName(name):
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(name, forType: .string)
        case .newWorkspace:
            model.beginCreate()
            render(claim: .field)
        case let .typed(name):
            let refused = model.screen.createRefused
            model.type(name)
            guard refused else { return }
            render()
        case .create:
            guard let name = model.sendCreate() else { return }
            render()
            let era = self.era
            byAPerson { [weak self] in
                guard let self else { return }
                let answer = await Self.result { () async throws(SailError) in try await client.send(.createWorkspace(name, project: projectName)) }
                guard era == self.era else { return }
                let request = model.created(answer, project: projectName)
                let claim: Keys? = switch answer {
                case _ where model.chosen != projectName || !holdsTheKeys: nil
                case .failure: .field
                case .success: .list
                }
                render(claim: claim)
                if let request { list(request) }
            }
        case .cancelCreate:
            model.cancelCreate()
            render(claim: .list)
        case let .chose(name): model.chooseRow(name)
        case let .show(name):
            model.chooseRow(name)
            render()
        case .listAgain, .checkAgain:
            askProjects()
        }
    }

    // MARK: New Session

    /// Asks what can start in workspace `name` of `project`, its row's + in flight meanwhile, and
    /// shows it under its row; asked again for the workspace it shows, the panel closes. `again`,
    /// the row's quick item: the last start starts at once when it still can (``NewSession/startsAgain``),
    /// else the panel opens at its harness saying why. The newest press wins: a press during an ask
    /// takes it over, its row then marked asking, and the earlier answer is dropped when it lands.
    private func askAgents(_ name: String, project projectName: String, _ client: SailClient, again: Bool) {
        let asked = startingIn.map { $0.project == projectName && $0.name == name } ?? false
        if newSession.isOpen {
            newSession.close()
            if asked, !again { return }
        }
        asks += 1
        let ask = asks
        model.askingAgentsIn = name
        startingIn = (projectName, name)
        render()
        let era = self.era
        byAPerson { [weak self] in
            guard let self else { return }
            let answer = await Self.result { () async throws(SailError) in try await client.send(.agents(workspace: name, project: projectName)) }
            guard era == self.era, ask == asks else { return }
            model.askingAgentsIn = nil
            guard model.chosen == projectName, !isHiddenOrHasHiddenAncestor else { return }
            if again, case let .success(agents) = answer, agents.refusal == nil {
                var panel = NewSession(agents, memory: memory)
                if panel.startsAgain, let start = panel.start() { return started(start, panel.memory) }
            }
            render()
            guard let row = project.rowFrame(name), let line = model.screen.line(name) else { return }
            newSession.open(said(answer), workspace: name, memory: memory, under: row, page: project, narrow: project.narrow, anchor: project.plus(name))
            guard newSession.isOpen else { return }
            panelRow = (row, line)
            project.list.primaryElsewhere = true
        }
    }

    /// The agents route's answer as the panel says it.
    private func said(_ answer: Result<WorkspaceAgents, SailError>) -> NewSessionPanel.Answer {
        switch answer {
        case let .success(agents): .agents(agents)
        case let .failure(.refused(refusal)): .failed(refusal.message, remedy: NewSessionWords.remedy(refusal))
        case let .failure(error): .failed(error.said(at: model.person.controlPlane), remedy: nil)
        }
    }

    /// Store a Login on `offer`: the person's logins asked, so the sheet asks a name only where they
    /// have one of the harness; not read, the panel says why and no sheet opens (a name not asked
    /// could replace one they have). Then the sheet over the panel, 420 pt or the window less 12 pt
    /// each side on a narrow page. Asked for a workspace the panel no longer shows, nothing opens.
    private func storeLogin(_ offer: AgentOffer) {
        guard let client, let place = startingIn else { return }
        let era = self.era
        byAPerson { [weak self] in
            guard let self else { return }
            let listed = await Self.result { () async throws(SailError) in try await client.send(.logins) }
            guard era == self.era, newSession.isOpen, startingIn.map({ $0 == place }) == true, let window else { return }
            let address = model.person.controlPlane
            let logins: [StoredLogin]
            switch listed {
            case let .success(list): logins = list.logins
            case let .failure(error): return newSession.say(.unread(StoreLogin.unread(error, address: address)))
            }
            guard let asked = StoreLogin(offer, logins: logins, address: address) else { return }
            let width = project.narrow ? Double(window.contentLayoutRect.width) - 2 * LoginSheet.inset : LoginSheet.width
            let sheet = LoginSheet(asked, width: width, direction: project.userInterfaceLayoutDirection, look: look, titleFont: titleFont, mono: mono, pasteboard: pasteboard)
            sheet.send = { endpoint in await Self.result { () async throws(SailError) in try await client.send(endpoint) } }
            sheet.onStored = { [weak self] stored in self?.loginStored(stored, in: place, era: era) }
            newSession.present(sheet)
        }
    }

    /// A login stored: where the panel is still open on its workspace, it says so at once, the
    /// clipboard cleared there and then when it still held the key, so the line that says so shows;
    /// then it shows what the route answers now, the harness chosen. With the panel gone, nothing
    /// says it, so the clipboard is left as it is.
    private func loginStored(_ stored: StoreLogin, in place: (project: String, name: String), era: Int) {
        guard let client, let line = stored.stored, era == self.era, newSession.isOpen, startingIn.map({ $0 == place }) == true else { return }
        if case .stored(_, clearsClipboard: true) = stored.stage { pasteboard.clearContents() }
        newSession.say(.stored(line))
        byAPerson { [weak self] in
            guard let self else { return }
            let answer = await Self.result { () async throws(SailError) in try await client.send(.agents(workspace: place.name, project: place.project)) }
            guard era == self.era, newSession.isOpen, startingIn.map({ $0 == place }) == true else { return }
            newSession.show(said(answer), memory: memory, stored: line, harness: stored.harness)
        }
    }

    /// The panel follows its row or closes: a row that moved (the list scrolled or drawn again), went,
    /// changed state or changed hands is no longer the one its Start acts on.
    private func keepPanelOnItsRow() {
        guard newSession.isOpen, let place = startingIn, let opened = panelRow else { return }
        let line = model.chosen == place.project ? model.screen.line(place.name) : nil
        guard let line, line.state == opened.line.state, line.owner == opened.line.owner, line.mine == opened.line.mine,
              project.rowFrame(place.name) == opened.frame
        else { return newSession.close() }
    }

    @objc private func listScrolled(_: Notification) {
        keepPanelOnItsRow()
    }

    /// Start pressed: the person's starts remember it, and it opens.
    private func started(_ start: AgentStart, _ memory: ModeMemory) {
        guard let place = startingIn else { return }
        self.memory = memory
        onStart(start, place.project, place.name)
    }

    // MARK: Stop and delete

    /// The sheet asking now, held while it shows.
    private var question: (sheet: Sheet, asked: WorkspacesModel.Question)?

    /// Asks before `action` on `name`. A stop counts the person's own terminals in it first, from
    /// the runner's sessions, so its sheet says what ends; one of their own that ends none is sent
    /// at once. One chosen while a question is open is not asked, and the page says so.
    private func ask(_ action: WorkspacesModel.Action, _ name: String) {
        guard let client, let project = model.chosen else { return }
        let era = self.era
        byAPerson { [weak self] in
            guard let self else { return }
            let terminals = action == .stop ? await terminals(in: name, project: project, client) : nil
            guard era == self.era, model.chosen == project else { return }
            switch model.ask(action, name, terminals: terminals) {
            case let .send(project, name, action)?: send(action, name, project: project)
            case .question?: showQuestion()
            case nil: break
            }
        }
    }

    /// How many of the person's own terminals are live in `name`, as the runner says now; nil when
    /// it does not answer.
    private func terminals(in name: String, project: String, _ client: SailClient) async -> Int? {
        let handle = model.person.handle
        return (try? await client.send(.sessions(workspace: name, project: project))).map { $0.live(for: handle).count }
    }

    /// The question open, in the library's sheet on the window: the sheet holds that one question,
    /// and confirming it sends exactly that, to its own project. A question waiting its turn is
    /// shown once this one ends, and only over this section, never over This Mac's terminals.
    private func showQuestion() {
        guard let asked = model.asking, let window, question == nil, !isHiddenOrHasHiddenAncestor else { return }
        if let detail = asked.detail { NSLog("Sail: %@", detail) }
        let sheet = Sheet(title: asked.title, body: asked.body, confirm: asked.confirm, kind: asked.action == .stop ? .destructiveQuiet : .destructive, look: look, titleFont: titleFont)
        question = (sheet, asked)
        sheet.ask(on: window) { [weak self] confirmed in
            guard let self else { return }
            guard confirmed else {
                question = nil
                model.cancel(asked)
                render()
                showQuestion()
                return
            }
            confirm(asked)
        }
    }

    /// A confirmed question sent. A stop of the person's own workspace counts their terminals again
    /// first: one opened while the sheet asked is asked about before it ends. The sheet's question
    /// is held until then, so nothing shows it twice.
    private func confirm(_ asked: WorkspacesModel.Question) {
        guard let client, asked.action == .stop, let counted = asked.terminals else { return sendConfirmed(asked) }
        let era = self.era
        byAPerson { [weak self] in
            guard let self else { return }
            let now = await terminals(in: asked.name, project: asked.project, client)
            question = nil
            guard era == self.era else { return }
            guard let now, now > counted, model.askAgain(asked, terminals: now) != nil else { return sendConfirmed(asked) }
            showQuestion()
        }
    }

    private func sendConfirmed(_ asked: WorkspacesModel.Question) {
        question = nil
        guard let sent = model.confirm(asked) else { return render() }
        send(sent.action, sent.name, project: sent.project)
        showQuestion()
    }

    /// Sends `action` on `name` in `project`, its row waiting; its answer lists the project again, asks
    /// Delete Anyway in the server's words (after any question open), or says the refusal over its page.
    private func send(_ action: WorkspacesModel.Action, _ name: String, project: String) {
        guard let client else { return }
        render()
        let era = self.era
        byAPerson { [weak self] in
            guard let self else { return }
            let answer = await Self.result { () async throws(SailError) in
                switch action {
                case .stop: try await client.send(.stopWorkspace(name, project: project))
                case let .delete(force): try await client.send(.deleteWorkspace(name, project: project, force: force))
                }
            }
            guard era == self.era else { return }
            let request = model.changed(action, name, project: project, answer)
            if let request { list(request) }
            showQuestion()
        }
    }

    /// Delete Anyway questions waiting behind the one open, for the drive.
    package var questionsWaiting: Int {
        model.questionsWaiting
    }

    /// Where a person's action sends the keys: into the name field (New Workspace, a refusal), or
    /// to the list (a create done or cancelled, so ⏎ opens).
    private enum Keys {
        case field, list
    }

    private static func result<Value>(_ work: () async throws(SailError) -> Value) async -> Result<Value, SailError> {
        do throws(SailError) {
            return .success(try await work())
        } catch {
            return .failure(error)
        }
    }

    // MARK: Drawing

    /// Draws what the model says. A redraw takes the shared name field out of the window, so the
    /// keys it held are given back to it. A `claim` is honoured then or never, and only while the
    /// project's page is shown in the key window.
    private func render(claim: Keys? = nil, typing: Bool? = nil) {
        if let shown = question, model.asking != shown.asked {
            question = nil
            shown.sheet.withdraw()
        }
        defer { keepPanelOnItsRow() }
        let typing = typing ?? project.fieldHasKeys
        draw()
        let naming = model.screen.createOpen, shown = window?.isKeyWindow == true && !project.isHiddenOrHasHiddenAncestor
        switch claim {
        case .field? where shown && naming:
            project.showCreateRow()
            project.focusField()
        case .list? where shown: window?.makeFirstResponder(project.list)
        default: if typing, naming { project.focusField() }
        }
    }

    /// The project's page shown in the key window with the keys in the section or in no view: an
    /// answer that comes while the person is elsewhere (a create can wait 60 s) claims nothing.
    private var holdsTheKeys: Bool {
        guard let window, window.isKeyWindow, !project.isHiddenOrHasHiddenAncestor else { return false }
        return switch window.firstResponder {
        case nil: true
        case let view as NSView: view.isDescendant(of: self)
        case let responder?: responder === window
        }
    }

    private func draw() {
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

    /// ⌃⌘S: on a narrow card the panel shown over the content or put away; on a wider one, hidden
    /// or shown beside it.
    @objc func toggleSidebar(_: Any?) {
        if isNarrow { overlaid.toggle() } else { tucked.toggle() }
        arrange()
    }

    /// Escape puts away the panel shown over the content.
    override public func cancelOperation(_ sender: Any?) {
        guard overlaid else { return super.cancelOperation(sender) }
        overlaid = false
        arrange()
    }

    /// The panel put away on a wide card by ⌃⌘S.
    private var tucked = false

    private var isNarrow: Bool {
        Double(bounds.width) < Self.narrow
    }

    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        arrange()
    }

    /// The panel beside the content, put away by ⌃⌘S, or hidden under a narrow card unless shown
    /// over it. It runs from `setFrameSize`, inside the superview's layout pass, and swaps the
    /// content's constraints only when the panel's place changes (crossing 720 pt, or ⌃⌘S).
    private func arrange() {
        guard let contentLeading, let contentFull else { return }
        let narrow = isNarrow, beside = panelShown && !narrow && !tucked
        let hidden = !panelShown || (narrow ? !overlaid : tucked)
        if panel.isHidden != hidden { panel.isHidden = hidden }
        let floating = !hidden && narrow
        if panelShadow.isHidden == floating { panelShadow.isHidden = !floating }
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
    /// The listed row of workspace `name` in the project shown.
    func line(_ name: String) -> WorkspacesModel.Line? {
        guard case let .shown(_, .project(page)) = self, case let .list(table) = page.body else { return nil }
        return (table.yours + table.others).first { $0.name == name }
    }

    /// A create was refused and its refusal is shown.
    var createRefused: Bool {
        guard case let .shown(_, .project(page)) = self, case let .list(table) = page.body else { return false }
        return !(table.create?.refusal.isEmpty ?? true)
    }

    /// The create row is open for a name, not under way.
    var createOpen: Bool {
        guard case let .shown(_, .project(page)) = self, case let .list(table) = page.body, let create = table.create else { return false }
        return !create.underWay
    }
}

extension WorkspacesView {
    /// What View ▸ ⌃⌘S says and does here: Hide Sidebar while the panel is shown; off, and why,
    /// while the section is one card with no projects beside it.
    var sidebarCommand: (title: String, enabled: Bool, reason: String?) {
        let title = panel.isHidden ? "Show Sidebar" : "Hide Sidebar"
        return panelShown && !isHidden ? (title, true, nil) : (title, false, "The sidebar lists projects, and none is listed here yet.")
    }
}
