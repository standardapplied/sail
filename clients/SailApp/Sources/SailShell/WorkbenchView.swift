import AppKit
import SailAPI
import SailControls
import SailFiles
import SailLook
import SailSurface
import SailTerminal
import SailTerminalView
import SailTerminalWire
import SailVT
import SailWorkbench

/// "This Mac": the window's terminals in tabs and splits. Each command changes the `Workbench` value
/// and `apply()` draws it, kept in user defaults; the first showing restores what lives.
@MainActor
public final class WorkbenchView: NSView {
    package static let arrangementKey = "workbench.arrangement"
    static let dividerStep = 10.0

    public let host: LocalHost
    public let strip: TabStrip
    public internal(set) var model = Workbench()
    public private(set) var panes: [PaneID: TerminalPane] = [:]
    var onTitle: @MainActor (String) -> Void = { _ in }
    /// Asked to show "This Mac", for a command given while it is not shown.
    var onShow: @MainActor () -> Void = {}
    /// Who is signed in now and where they ask tickets, nil while no one is: a tab in a workspace
    /// asks it at every ticket (``SignedInTickets``), so it never holds a sign-in of its own.
    var signedIn: @MainActor @Sendable () -> (handle: String, tickets: any TicketSource)? = { nil }
    /// Asked to show the account, where a runner tab's lapsed sign-in is signed in again.
    var onSignIn: @MainActor () -> Void = {}
    /// A runner tab in a workspace attached or went, so what counts its sessions counts again.
    var onRunnerTabs: @MainActor (_ project: String, _ name: String) -> Void = { _, _ in }
    /// This Mac's terminals changed (one opened, went, was titled, moved folder or ended), which
    /// This Mac's page lists (``localSessions``).
    var onSessions: @MainActor () -> Void = {}
    /// How each of this Mac's panes whose session ended, the pane kept, ended ("exited 3 · 11:02").
    private var endings: [PaneID: String] = [:]
    /// The window is closed or in the Dock: no workspace's files are followed.
    var filesAway = false
    /// Starts a workspace for its files panel's Start, answering nil once started or what refused it.
    var startWorkspace: @MainActor (_ project: String, _ name: String) async -> [String]? = { _, _ in ["No one is signed in."] }

    var look: Look {
        didSet { paint() }
    }

    private let fonts: TerminalFonts
    /// The files panels' words in mono, as every list's.
    let labels: LabelFont
    /// The files panels' questions' titles, as every sheet's.
    let titles: TitleFont
    private(set) var tabViews: [TabID: TabView] = [:]
    private var shownSelected: TabID?
    private let banner: Card
    private let emptyLabel = NSTextField(labelWithString: "No terminal is open")
    private let newTerminal: SailButton
    private var started = false
    private var pendingKeep: DispatchWorkItem?
    private var kept: Data?
    static let keepDelay = 0.3
    /// The arrangement is the person's from here on: restored, so worth keeping.
    private(set) var restored = false
    /// Workspaces asked to open before the first restore, as a shell or the agent each starts (with
    /// who asked for it, on which control plane), opened once it has replaced the model; an agent
    /// only for that same person there.
    private var pendingWorkspaces: [(place: Place, agent: AgentStart?, asker: [String]?)] = []
    /// The control plane signed in to, which with the handle says who asked for a queued start.
    var controlPlane: @MainActor () -> String = { "" }

    /// Who is signed in, on which control plane; nil, no one.
    private var asker: [String]? {
        signedIn().map { [controlPlane(), $0.handle] }
    }
    /// Runner tabs kept aside while no one is signed in, and whose they are: still kept with the
    /// arrangement, and brought back by that person's sign-in alone.
    private var held: [Arrangement.Tab] = []
    private var heldFor: String?

    convenience init(fonts directory: URL, helper: String?, look: Look, defaults: UserDefaults = .standard) throws(ShellError) {
        let host: LocalHost
        do {
            host = try LocalHost(binary: helper)
        } catch {
            throw .host("this Mac's terminal host has no place to listen: \(error)")
        }
        try self.init(fonts: directory, host: host, look: look, defaults: defaults)
    }

    /// Where the arrangement is kept, and which workspaces show their files: the app's own defaults,
    /// or a test's.
    let defaults: UserDefaults

    init(fonts directory: URL, host: LocalHost, look: Look, defaults: UserDefaults = .standard) throws(ShellError) {
        self.look = look
        self.host = host
        self.defaults = defaults
        do {
            fonts = try TerminalFonts(directory: directory)
            labels = try LabelFont(directory: directory)
            titles = try TitleFont(directory: directory)
        } catch {
            throw .resources("the terminal's fonts cannot be read: \(error)")
        }
        banner = Card(look: look)
        strip = TabStrip(look: look)
        newTerminal = SailButton("New Terminal", kind: .primary, look: look, target: nil, action: #selector(WorkbenchView.newTab(_:)))
        super.init(frame: NSRect(x: 0, y: 0, width: 800, height: 600))
        compose()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    public var focused: TerminalPane? {
        model.focusedPane.flatMap { panes[$0.id] }
    }

    /// The first showing restores what lives; every showing gives the focused pane the keys.
    func shown() {
        if let focused { window?.makeFirstResponder(focused.terminal) }
        restoreOnce()
    }

    /// What lives restored, once, whether or not the terminals are shown: This Mac's page lists them.
    func restoreOnce() {
        guard !started else { return }
        started = true
        Task { await restore() }
    }

    /// This Mac's terminals as its page lists them, a row each.
    var localSessions: [LocalSession] {
        model.localSessions(ended: endings, hostNames: [Self.hostName], home: NSHomeDirectory())
    }

    /// Pane `id` focused and its tab chosen: Open on This Mac's row.
    func focus(_ id: PaneID) {
        change { $0.focus(id) }
    }

    private func restore() async {
        banner.show("Connecting to this Mac's terminal host")
        let listed: [SessionInfo]
        do {
            try await host.ensureServing { [weak self] sentence in await self?.tell(sentence) }
            listed = try await SessionListing.sessions(at: host.address)
        } catch {
            banner.show(Said.sentence("\(error)"), tone: .error, button: "Try Again") { [weak self] in
                Task { await self?.restore() }
            }
            return
        }
        if banner.shown != told { banner.hide() }
        let kept = defaults.data(forKey: Self.arrangementKey).flatMap { try? JSONDecoder().decode(Arrangement.self, from: $0) }
        let person = signedIn()?.handle
        model = Workbench.restore(kept, live: listed.filter(\.live).map(\.name) + (kept?.runnerSessions(for: person) ?? []), home: NSHomeDirectory())
        if person == nil, let kept {
            held = kept.runnerTabs
            heldFor = kept.runner
        }
        restored = true
        for pane in model.panes.values.sorted(by: { $0.id < $1.id }) {
            if makeView(pane, start: .resume, points: FontLadder.standard) == nil { model.close(pane.id) }
        }
        model.fit(in: room, minimum: minimum(of: Array(panes.values)), scale: scale)
        let waiting = pendingWorkspaces
        pendingWorkspaces = []
        if model.tabs.isEmpty, waiting.isEmpty, held.isEmpty { openTab() } else { apply() }
        for (place, agent, asker) in waiting {
            guard case let .workspace(project, name) = place else { continue }
            if let agent {
                if asker == self.asker { openAgent(agent, project: project, name: name) }
            } else {
                openWorkspace(project: project, name: name)
            }
        }
    }

    /// Who is signed in changed: signed out, the runner tabs leave the window and keep their
    /// places for that person, their sessions running on; that person signed in again, they come
    /// back attached; anyone else signed in, they are dropped.
    func standingChanged() {
        guard restored else { return }
        let person = signedIn()?.handle
        guard let person else {
            let leaving = panes.values.filter(\.place.onRunner)
            guard !leaving.isEmpty else { return }
            if held.isEmpty { heldFor = Self.runnerPerson(of: leaving) }
            held += model.arrangement.runnerTabs
            leaving.forEach { $0.detach() }
            leaving.forEach(remove)
            return apply()
        }
        guard !held.isEmpty else { return }
        let coming = heldFor == person ? model.adopt(held) : []
        held = []
        heldFor = nil
        for id in coming {
            if let pane = model.panes[id], makeView(pane, start: .resume, points: points) == nil { model.close(id) }
        }
        if model.selectedTab == nil, let tab = coming.lazy.compactMap(model.tab(holding:)).first { model.select(tab.id) }
        apply()
    }

    /// The person the runner panes `views` were reached as.
    private static func runnerPerson(of views: [TerminalPane]) -> String? {
        views.map(\.person).first { !$0.isEmpty }
    }

    func detachAll() {
        keep()
        panes.values.forEach { $0.detach() }
    }

    /// Draws the model, by frames and `isHidden` only (W2), and keeps it soon after.
    func apply() {
        if model.focusedPane?.id != shownFocus {
            shownFocus = model.focusedPane?.id
            focusMoves += 1
        }
        endRenamesLeftBehind()
        for (id, view) in tabViews where !model.tabs.contains(where: { $0.id == id }) {
            view.files?.close()
            view.removeFromSuperview()
            tabViews[id] = nil
        }
        model.tabs.forEach { _ = tabView($0.id) }
        prepareFiles()
        connectFiles()
        for tab in model.tabs {
            let view = tabView(tab.id)
            view.isHidden = tab.id != model.selected
            layout(tab, in: view)
        }
        panes.values.forEach { $0.begin() }
        emptyLabel.isHidden = !model.tabs.isEmpty || !restored
        newTerminal.isHidden = emptyLabel.isHidden
        showTitles()
        if let focused, !isHidden, !strip.isRenaming, !filesHoldTheKeys, window?.firstResponder !== focused.terminal { window?.makeFirstResponder(focused.terminal) }
        keepSoon()
    }

    /// A redraw ends a rename only when another tab was chosen, the tab selected before still
    /// there (a selection moved by its tab closing is no one's choice, and closing a tab selects
    /// none), or when the renamed tab itself is gone; the name typed is kept. Any other redraw (a
    /// focus change, a shell ending in another tab, a change of look) leaves the field as it is, so
    /// Escape still keeps nothing.
    private func endRenamesLeftBehind() {
        let chosen = shownSelected.map { shown in shown != model.selected && model.tabs.contains { $0.id == shown } } ?? false
        shownSelected = model.selected
        let left = strip.tabs.filter { tab in
            tab.field != nil && (chosen && tab.id != model.selected || !model.tabs.contains { $0.id == tab.id })
        }
        left.forEach { $0.endRename() }
    }

    private func showTitles() {
        let items = model.tabs.map { tab in
            TabStrip.Item(
                id: tab.id, title: model.title(of: tab.id), zoomed: tab.zoomed != nil,
                unended: tab.layout.leaves.contains { panes[$0]?.unended == true }
            )
        }
        strip.show(items, selected: model.selected)
        onTitle(model.selectedTab.map { model.title(of: $0.id) } ?? ShellApp.title)
        onSessions()
    }

    /// The selected tab laid out again, the rest as it was: a divider moved by drag or key.
    private func layoutSelected() {
        guard let tab = model.selectedTab, let view = tabViews[tab.id] else { return }
        layout(tab, in: view)
        keepSoon()
    }

    override public func setFrameSize(_ size: NSSize) {
        super.setFrameSize(size)
        model.tabs.forEach { tab in tabViews[tab.id].map { layout(tab, in: $0) } }
    }

    /// The focused pane as last drawn, and how many times the focus moved since the workbench was
    /// made: what tells a late end whether the person moved since it began.
    private var shownFocus: PaneID?
    private var focusMoves = 0

    /// How many times a tab's panes were laid out: what a change of look leaves alone.
    package private(set) var tabLayouts = 0

    private func layout(_ tab: Tab, in view: TabView) {
        tabLayouts += 1
        let give = filePaneGive(of: tab, in: view, panelWidth: filesBesideWidth(of: tab, in: view))
        let room = placeFiles(of: tab, in: view, trailing: give)
        placeFilePane(of: tab, in: view, room: room, give: give)
        let frames = model.frames(of: tab.id, in: room, scale: scale)
        for id in tab.layout.leaves {
            guard let pane = panes[id] else { continue }
            pane.isHidden = frames.panes[id] == nil
            if let frame = frames.panes[id], pane.frame != frame { pane.frame = frame }
            pane.isDimmed = frames.panes.count > 1 && id != tab.focused
        }
        view.show(frames.dividers, look: look)
    }

    private func tabView(_ id: TabID) -> TabView {
        if let view = tabViews[id] { return view }
        let room = TabView(frame: bounds)
        room.autoresizingMask = [.width, .height]
        room.onDrag = { [weak self] path, position in self?.drag(path, to: position) }
        room.onDragEnded = { [weak self] in self?.keepSoon() }
        addSubview(room, positioned: .below, relativeTo: banner)
        tabViews[id] = room
        return room
    }

    /// Whether a keep of the arrangement is scheduled and not yet done: what tells a change's write
    /// is still to come.
    package var isKeepingSoon: Bool {
        pendingKeep != nil
    }

    /// Keeps the arrangement a moment after the last change, so a burst of changes (held keys, a
    /// drag) writes once; quitting keeps it at once.
    private func keepSoon() {
        pendingKeep?.cancel()
        let work = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated { self?.keep() }
        }
        pendingKeep = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.keepDelay, execute: work)
    }

    private func keep() {
        pendingKeep?.cancel()
        pendingKeep = nil
        let arrangement = model.arrangement(runner: held.isEmpty ? signedIn()?.handle : heldFor, holding: held)
        guard restored, let data = try? JSONEncoder().encode(arrangement), data != kept else { return }
        defaults.set(data, forKey: Self.arrangementKey)
        kept = data
    }

    /// The view for `pane`, added once to its tab's view; nil, said in the banner, when it cannot
    /// be made.
    @discardableResult
    func makeView(_ pane: Pane, start: TerminalPane.Start, points: Double) -> TerminalPane? {
        guard let tab = model.tab(holding: pane.id) else { return nil }
        let view: TerminalPane
        do {
            let reached = pane.place.onRunner ? signedIn() : nil
            let tickets = reached.map { SignedInTickets(for: $0.handle, current: signedIn) }
            view = try TerminalPane(
                pane, start: start, host: host, runner: tickets, person: reached?.handle ?? "", fonts: fonts, points: points, look: look
            )
        } catch {
            banner.show(Said.sentence(error.description), tone: .error)
            return nil
        }
        view.onEvent = { [weak self] view, event in self?.handle(event, from: view) }
        view.onSignIn = { [weak self] in self?.onSignIn() }
        view.onClose = { [weak self] view in
            self?.remove(view)
            self?.apply()
        }
        view.terminal.contextMenu = { [weak self] in self?.paneMenu() }
        view.terminal.onFocus = { [weak self, id = pane.id] in self?.focusClicked(id) }
        if pane.place.onRunner {
            view.terminal.linkIn = { [weak self, id = pane.id] text, column in
                self?.model.panes[id]?.elsewhere == false ? TerminalPath.find(in: text, at: column)?.range : nil
            }
            view.terminal.onLink = { [weak self, weak view] text, column in
                guard let self, let view else { return }
                openTerminalPath(in: text, at: column, from: view)
            }
        }
        panes[pane.id] = view
        tabView(tab.id).addPane(view)
        return view
    }

    private func open(_ id: PaneID, start: TerminalPane.Start = .open, points: Double) {
        if let pane = model.panes[id], makeView(pane, start: start, points: points) == nil {
            model.close(id)
        }
        apply()
    }

    private func remove(_ view: TerminalPane) {
        endings[view.id] = nil
        model.close(view.id)
        view.removeFromSuperview()
        panes[view.id] = nil
        runnerTabsChanged(view)
    }

    private func runnerTabsChanged(_ view: TerminalPane) {
        guard case let .workspace(project, name) = view.place else { return }
        onRunnerTabs(project, name)
    }

    private func handle(_ event: SessionEvent, from view: TerminalPane) {
        switch event {
        case .phase(.attached):
            runnerTabsChanged(view)
        case let .screen(.title(title)):
            model.setTitle(title, of: view.id)
            showTitles()
        case let .screen(.workingDirectory(folder)):
            model.setFolder(folder, of: view.id)
            onSessions()
        case let .phase(.ended(.ended(reason))) where Ending.closesPane(reason: reason, lived: ContinuousClock.now - view.opened):
            view.detach()
            remove(view)
            apply()
        case let .phase(.ended(end)) where !view.place.onRunner && end != .closed:
            endings[view.id] = Self.said(end, at: Date())
            onSessions()
        default:
            break
        }
    }

    /// A click made a pane the first responder: the model follows at once, the drawing next turn.
    private func focusClicked(_ id: PaneID) {
        model.tab(holding: id).flatMap { tabViews[$0.id]?.filePane }?.keysTakenElsewhere()
        guard model.focusedPane?.id != id else { return }
        model.focus(id)
        applySoon()
    }

    /// Draws the model on the next turn, for a change told from inside AppKit's own first-responder
    /// change, which drawing now would re-enter.
    private func applySoon() {
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.apply() }
        }
    }

    private var scale: Double {
        window.map { Double($0.backingScaleFactor) } ?? 2
    }

    private var room: CGSize {
        bounds.size
    }

    /// The least a pane of the selected tab may be: 10 columns by 3 rows and the padding, at the
    /// largest font among its panes, so every pane keeps its own.
    private var minimum: CGSize {
        minimum(of: (model.selectedTab?.layout.leaves ?? []).compactMap { panes[$0] })
    }

    private func minimum(of views: [TerminalPane]) -> CGSize {
        var least = (width: 0.0, height: 0.0)
        for metrics in views.map(\.terminal.metrics) {
            least.width = max(least.width, Double(10 * metrics.width + 2 * metrics.padding.width) / metrics.scale)
            least.height = max(least.height, Double(3 * metrics.height + 2 * metrics.padding.height) / metrics.scale)
        }
        return CGSize(width: least.width, height: least.height)
    }

    /// Where a new pane opens: as the focused one, in the folder its shell reported when that is
    /// on this Mac, else where it opened; in the home folder with no tab.
    private func newPlace() -> Place {
        .thisMac(folder: model.folderForNewPane(hostNames: [Self.hostName], isFolder: Self.isFolder) ?? NSHomeDirectory())
    }

    /// How a session of this Mac ended, its pane kept, and when: "exited 3 · 11:02".
    static func said(_ end: SessionEnd, at date: Date) -> String {
        let how = if case let .ended(reason) = end, reason.hasPrefix("exited("), reason.hasSuffix(")") {
            "exited " + reason.dropFirst("exited(".count).dropLast()
        } else {
            end.description
        }
        return "\(how) · \(clock.string(from: date))"
    }

    private static let clock = {
        let clock = DateFormatter()
        clock.dateFormat = "HH:mm"
        return clock
    }()

    /// This Mac's name as `hostname` says it, read from the kernel: `ProcessInfo.hostName` asks
    /// the network, which can hold the main thread for seconds and has macOS ask the person for
    /// local network access. It is said on This Mac's page and sent nowhere.
    package static let hostName: String = {
        var name = [CChar](repeating: 0, count: 256)
        gethostname(&name, name.count)
        return String(decoding: name.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }()

    private static func isFolder(_ path: String) -> Bool {
        var folder: ObjCBool = false
        return FileManager.default.fileExists(atPath: path, isDirectory: &folder) && folder.boolValue
    }

    private var points: Double {
        focused?.terminal.fontPoints ?? FontLadder.standard
    }

    private func drag(_ path: [Side], to position: Double) {
        guard model.drag(path, to: position, in: room, minimum: minimum, scale: scale) else { return }
        layoutSelected()
    }

    /// What the workbench's banner says now: for the drive.
    public var bannerSays: String? {
        banner.shown
    }

    /// The last sentence ``tell(_:)`` said, which the end of a restore leaves in the banner.
    private var told: String?

    /// Where the banner's top sits: 32 pt below the terminals' top, or lower, a small gap below the
    /// first row of the tallest font shown.
    private var bannerTop = NSLayoutConstraint()

    /// Says `sentence` in the banner for 10 s, unless something else is said meanwhile. The banner
    /// sits at the top of the terminals, below their first row and clear of a pane's own card at
    /// the foot, so it hides no first line and neither covers the other's actions.
    func tell(_ sentence: String) {
        let firstRow = panes.values.map { pane in
            let metrics = pane.terminal.metrics
            return Double(metrics.padding.height + metrics.height) / metrics.scale
        }.max() ?? 0
        bannerTop.constant = max(Look.Space.x3l, firstRow + Look.Space.s)
        told = sentence
        banner.show(sentence)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            if self?.banner.shown == sentence { self?.banner.hide() }
        }
    }

    private func openTab() {
        openTab(in: newPlace())
    }

    /// Workspace `name` of `project` beside this Mac's terminals: a tab attached to each of the
    /// person's live sessions there that no tab holds, newest first, or a tab with a new one when
    /// there are none (or they cannot be listed); before the first restore, once it is done.
    func openWorkspace(project: String, name: String) {
        let place = Place.workspace(project: project, name: name)
        guard restored else { return pendingWorkspaces.append((place, nil, nil)) }
        guard let reached = signedIn() else { return openTab(in: place) }
        Task { [weak self] in
            let listed = try? await reached.tickets.sessions(in: project, workspace: name)
            self?.open(place, attaching: listed, for: reached.handle)
        }
    }

    /// Opens `place` for `person`, still the one signed in: a tab attached to each of their live
    /// sessions there that no tab holds, newest first; when every one is in a tab already, the
    /// newest's tab is chosen and nothing starts (a split of one of its panes starts one); with none,
    /// a new one.
    func open(_ place: Place, attaching listed: RunnerSessions?, for person: String) {
        guard signedIn()?.handle == person else { return }
        let held = Set(model.panes.values.map(\.session) + self.held.flatMap(\.layout.sessions))
        let mine = listed?.live(for: person) ?? []
        let theirs = mine.filter { !held.contains($0.name) }
        let panes = model.panes.values
        if theirs.isEmpty, let newest = mine.lazy.compactMap({ session in panes.first { $0.session == session.name }?.id }).first {
            model.focus(newest)
            return apply()
        }
        guard !theirs.isEmpty else { return openTab(in: place) }
        let size = points
        theirs.forEach { open(model.newTab(session: $0.name, place: place, agent: $0.agent?.start), start: .attach, points: size) }
    }

    private func openTab(in place: Place) {
        open(model.newTab(session: place.newSessionName, place: place), points: points)
    }

    /// A new runner tab in workspace `name` of `project` whose session is `agent`: the host composes
    /// its command and signs it in, and the tab is titled by its harness and workspace. Asked before
    /// the first restore (the section never shown yet), it opens once that is done.
    func openAgent(_ agent: AgentStart, project: String, name: String) {
        let place = Place.workspace(project: project, name: name)
        guard restored else { return pendingWorkspaces.append((place, agent, asker)) }
        open(model.newTab(session: place.newSessionName, place: place, agent: agent), points: points)
    }

    @objc public func newTab(_: Any?) {
        onShow()
        guard restored else { return }
        openTab()
    }

    @objc public func splitRight(_: Any?) {
        split(.sideBySide)
    }

    @objc public func splitDown(_: Any?) {
        split(.stacked)
    }

    private func split(_ axis: Axis) {
        let size = points
        let place = if let focused = model.focusedPane, focused.place.onRunner { focused.place } else { newPlace() }
        guard let id = model.split(axis, session: place.newSessionName, place: place, in: room, minimum: minimum, scale: scale) else {
            return NSSound.beep()
        }
        open(id, points: size)
    }

    @objc public func closePane(_: Any?) {
        if let file = filePaneHoldingKeys { return file.closePressed() }
        guard let pane = focused else { return }
        endSession(pane.id)
    }

    /// Ends pane `id`'s session as closing it does, asking first as Close asks: Close, and End
    /// Session on This Mac's row.
    func endSession(_ id: PaneID) {
        guard let pane = panes[id], !leaving(elsewhere: [pane]).isEmpty else { return }
        confirm(ending: [pane], question: "Close this terminal?", detail: "The program running in it will end.")
    }

    /// Closes those of `views` whose keyboard is elsewhere by detaching them only: each session goes
    /// on where its keyboard is, which is said once. Ending one is done there, or after Take
    /// Keyboard. Answers the rest, whose sessions closing ends.
    private func leaving(elsewhere views: [TerminalPane]) -> [TerminalPane] {
        let elsewhere = views.filter(\.keyboardElsewhere)
        guard let first = elsewhere.first else { return views }
        let title = model.tab(holding: first.id).map { model.title(of: $0.id) } ?? "This terminal"
        let said = Card.fitting(title, width: min(Card.width, Double(bounds.width) - 2 * Look.Space.l)) { first.stillRunning($0) ?? $0 }
        for view in elsewhere {
            view.detach()
            remove(view)
        }
        apply()
        tell(said)
        return views.filter { !$0.keyboardElsewhere }
    }

    /// The tab a command acts on: the one whose menu it came from (a tab's menu acts on that tab,
    /// selected or not), else the selected one.
    private func tab(for sender: Any?) -> Tab? {
        let id = (sender as? NSMenuItem)?.representedObject as? TabID
        return id.flatMap { id in model.tabs.first { $0.id == id } } ?? model.selectedTab
    }

    @objc public func closeTab(_ sender: Any?) {
        guard let tab = self.tab(for: sender) else { return }
        close(tab)
    }

    /// Ends `tab`'s terminals, asking first when one may end a running program. Closing a tab
    /// chooses no tab: it is not selected first, so a rename open in another tab goes on.
    private func close(_ tab: Tab) {
        guard tabViews[tab.id]?.filePane?.unsaved == true else { return closeTerminals(of: tab) }
        askAboutFile(in: tab) { [weak self] in
            self?.tabViews[tab.id]?.filePane?.finishClosing()
            self?.closeTerminals(of: tab)
        }
    }

    private func closeTerminals(of tab: Tab) {
        let views = leaving(elsewhere: tab.layout.leaves.compactMap { panes[$0] })
        guard !views.isEmpty else { return }
        let question = closeQuestion(for: tab, terminals: views.count)
        guard views.count > 1 else {
            return confirm(ending: views, question: question, detail: "The program running in it will end.")
        }
        confirm(ending: views, question: question, detail: "Its \(views.count) terminals will end.")
    }

    /// What closing `tab`, of `count` terminals, asks: a tab that is not the one shown, closed by
    /// its X from another, is named as the strip names it, so the question says which it means.
    func closeQuestion(for tab: Tab, terminals count: Int) -> String {
        guard tab.id == model.selected else { return "Close \(model.title(of: tab.id))?" }
        return count > 1 ? "Close this tab?" : "Close this terminal?"
    }

    /// Closes every tab but this one, asking first as Close Tab does.
    @objc public func closeOtherTabs(_ sender: Any?) {
        guard let kept = tab(for: sender)?.id else { return }
        let views = leaving(elsewhere: model.tabs.filter { $0.id != kept }.flatMap(\.layout.leaves).compactMap { panes[$0] })
        guard !views.isEmpty else { return }
        confirm(ending: views, question: "Close the other tabs?", detail: "Their \(views.count == 1 ? "terminal" : "\(views.count) terminals") will end.")
    }

    /// Ends `views`' sessions, asking first when one of them may end a running program, as one not
    /// attached may.
    private func confirm(ending views: [TerminalPane], question: String, detail: String) {
        guard views.contains(where: \.mayEndAProgram), let window else { return end(views) }
        Confirmation.ask(question, detail: detail + " Quitting Sail instead leaves every terminal running.", action: "Close", on: window) { [weak self] close in
            if close { self?.end(views) }
        }
    }

    /// Ends `views`' sessions. A pane stays until its host says its session ended (or had
    /// already), the focus moving on at once; when the host does not confirm, the pane says so and
    /// offers to end it again or to close it anyway, never vanishing as if it had ended.
    private func end(_ views: [TerminalPane]) {
        var ending: [(TerminalPane, Task<EndOutcome, Never>)] = []
        for view in views where panes[view.id] === view {
            guard let task = view.end() else {
                remove(view)
                continue
            }
            view.showEnding()
            ending.append((view, task))
        }
        focusPastEnding()
        apply()
        let moves = focusMoves
        for (view, task) in ending {
            Task { [weak self] in
                let outcome = await task.value
                self?.settle(view, outcome, focusMovesAtEnd: moves)
            }
        }
    }

    /// Moves the focus off a pane being ended to where its close will put it: the tab's most
    /// recently focused other pane, else the tab before's, else the one after's. A second ⌘W while
    /// the host answers closes the next pane, never the same one again. Moving it ends a zoom, so
    /// the pane the keys go to is one the person sees.
    private func focusPastEnding() {
        guard let focused = model.focusedPane, panes[focused.id]?.isEnding == true,
              let at = model.tabs.firstIndex(where: { $0.id == model.selected }) else { return }
        let tabs = model.tabs
        var order = [tabs[at]]
        order += tabs[..<at].reversed()
        order += tabs[(at + 1)...]
        let candidates = order.flatMap { $0.history.reversed() }
        guard let next = candidates.first(where: { panes[$0]?.isEnding == false }) else { return }
        model.focus(next)
    }

    /// What an end came to. An unconfirmed pane is shown, its tab selected and the pane focused,
    /// only when the focus has not moved since the end left it (`focusMovesAtEnd`, so moving away
    /// and back counts as moving); once the person has moved, the pane says so where it is and its
    /// tab is marked, the keys staying where the person is.
    private func settle(_ view: TerminalPane, _ outcome: EndOutcome, focusMovesAtEnd moves: Int) {
        guard panes[view.id] === view else { return }
        guard !outcome.closesTab, case let .unconfirmed(why) = outcome else {
            remove(view)
            return apply()
        }
        if focusMoves == moves { model.focus(view.id) }
        apply()
        view.showUnended(why, endAgain: { [weak self] in self?.end([view]) }, closeAnyway: { [weak self] in
            guard let self, panes[view.id] === view else { return }
            remove(view)
            apply()
        })
    }

    /// Zooms the focused terminal: the file pane hides while it is zoomed, and the keys go to the
    /// terminal.
    @objc public func zoomSplit(_: Any?) {
        change { $0.toggleZoom() }
        if filePaneHoldingKeys != nil || model.selectedTab?.zoomed != nil, let terminal = focused { window?.makeFirstResponder(terminal.terminal) }
    }

    @objc public func selectPreviousSplit(_: Any?) {
        guard !moveKeysAmongSides(next: false) else { return }
        change { $0.focusNeighbour(next: false) }
    }

    @objc public func selectNextSplit(_: Any?) {
        guard !moveKeysAmongSides(next: true) else { return }
        change { $0.focusNeighbour(next: true) }
    }

    /// Focuses the pane the menu item's tag points to: its `Direction` in `Direction.allCases`.
    @objc public func selectSplit(_ sender: NSMenuItem) {
        guard Direction.allCases.indices.contains(sender.tag) else { return }
        let (direction, size, pixels) = (Direction.allCases[sender.tag], room, scale)
        if filePaneHoldingKeys != nil || filesHoldTheKeys { return _ = moveKeysTowardSide(direction, focusMoved: false) }
        let before = model.selectedTab?.focused
        change { $0.moveFocus(direction, in: size, scale: pixels) }
        _ = moveKeysTowardSide(direction, focusMoved: model.selectedTab?.focused != before)
    }

    @objc public func equalizeSplits(_: Any?) {
        let (size, least, pixels) = (room, minimum, scale)
        change { $0.equalize(in: size, minimum: least, scale: pixels) }
    }

    /// Moves the focused pane's nearest divider 10 pt the way the menu item's tag points: its
    /// `Direction` in `Direction.allCases`.
    @objc public func moveDivider(_ sender: NSMenuItem) {
        guard Direction.allCases.indices.contains(sender.tag) else { return }
        guard model.moveDivider(Direction.allCases[sender.tag], by: Self.dividerStep, in: room, minimum: minimum, scale: scale) else { return }
        layoutSelected()
    }

    @objc public func showPreviousTab(_: Any?) {
        change { $0.selectNeighbourTab(next: false) }
    }

    @objc public func showNextTab(_: Any?) {
        change { $0.selectNeighbourTab(next: true) }
    }

    /// The menu item's tag names the tab, 1 to 8, or 9 for the last.
    @objc public func selectTab(_ sender: NSMenuItem) {
        let index = sender.tag
        change { bench in
            if index == 9 { bench.selectLastTab() } else { bench.selectTab(at: index - 1) }
        }
    }

    @objc public func renameTab(_ sender: Any?) {
        guard let id = tab(for: sender)?.id else { return }
        strip.tabs.first { $0.id == id }?.rename()
    }

    @objc public func toggleLatency(_ sender: Any?) {
        focused?.toggleLatency(sender)
    }

    /// The workbench's own shortcuts are taken here, before the menu bar, which answers only after
    /// highlighting its menu's title (⌘D held 80 to 180 ms on the runner). The matching menu item's
    /// action is sent, validated as the menu would; a disabled one does nothing.
    override public func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard !isHidden, let item = Self.shortcut(for: event, in: NSApp.mainMenu, target: self), let action = item.action else {
            return super.performKeyEquivalent(with: event)
        }
        if validateMenuItem(item) { NSApp.sendAction(action, to: self, from: item) }
        return true
    }

    static func shortcut(for event: NSEvent, in menu: NSMenu?, target: AnyObject) -> NSMenuItem? {
        let modifiers = event.modifierFlags.intersection([.command, .option, .control, .shift])
        for item in menu?.items ?? [] {
            if let found = shortcut(for: event, in: item.submenu, target: target) { return found }
            guard item.target === target, !item.keyEquivalent.isEmpty, item.keyEquivalentModifierMask == modifiers else { continue }
            if item.keyEquivalent == event.charactersIgnoringModifiers { return item }
        }
        return nil
    }

    func change(_ command: (inout Workbench) -> Void) {
        command(&model)
        apply()
    }

    private func compose() {
        wantsLayer = true
        emptyLabel.font = .systemFont(ofSize: Look.Step.body.points)
        newTerminal.target = self
        let empty = NSStackView(views: [emptyLabel, newTerminal])
        empty.orientation = .vertical
        empty.spacing = Look.Space.m
        empty.translatesAutoresizingMaskIntoConstraints = false
        banner.translatesAutoresizingMaskIntoConstraints = false
        bannerTop = banner.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.x3l)
        addSubview(empty)
        addSubview(banner)
        NSLayoutConstraint.activate([
            empty.centerXAnchor.constraint(equalTo: centerXAnchor), empty.centerYAnchor.constraint(equalTo: centerYAnchor),
            banner.centerXAnchor.constraint(equalTo: centerXAnchor),
            bannerTop,
            banner.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: Look.Space.l),
        ])
        emptyLabel.isHidden = true
        newTerminal.isHidden = true
        strip.onSelect = { [weak self] id in self?.change { $0.select(id) } }
        strip.onMenu = { [weak self] id in self?.tabMenu(for: id) }
        strip.onClose = { [weak self] id in
            guard let self, let tab = model.tabs.first(where: { $0.id == id }) else { return }
            close(tab)
        }
        strip.onNew = { [weak self] in self?.newTab(nil) }
        strip.onRename = { [weak self] id, name in
            if let name { self?.model.rename(id, to: name) }
            self?.applySoon()
        }
        strip.onMove = { [weak self] id, index in self?.change { $0.moveTab(id, to: index) } }
        paint()
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.palette.background).cgColor
        emptyLabel.textColor = NSColor(look.textSecondary)
        newTerminal.look = look
        banner.look = look
        strip.look = look
        panes.values.forEach { $0.look = look }
        tabViews.values.forEach { $0.paint(look) }
        tabViews.values.forEach { view in
            view.files?.look = look
            view.filePane?.look = look
            view.fileDivider.look = look
        }
        (window?.attachedSheet as? ConflictSheet)?.look = look
    }
}

extension WorkbenchView: NSMenuItemValidation {
    /// Every workbench command waits while a sheet is on the window.
    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        guard window?.attachedSheet == nil else { return false }
        let panes = model.selectedTab?.layout.leaves.count ?? 0
        switch item.action {
        case #selector(newTab(_:)): return true
        case #selector(closeOtherTabs(_:)): return !isHidden && model.tabs.count > 1
        case #selector(zoomSplit(_:)), #selector(equalizeSplits(_:)), #selector(selectNextSplit(_:)), #selector(selectPreviousSplit(_:)):
            return !isHidden && panes > 1
        default: return !isHidden && panes > 0
        }
    }
}

final class TabView: NSView {
    /// Its workspace's files panel, once shown.
    var files: FilesPanel?
    /// Its workspace's file pane, while a file is open, and the divider before it.
    var filePane: FilePane?
    let fileDivider = DividerLine()
    /// Whether the file pane is in front where it takes the terminals' place (a narrow tab).
    var filePaneInFront = true

    override init(frame: NSRect) {
        super.init(frame: frame)
        fileDivider.isHidden = true
        addSubview(fileDivider)
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }
    var onDrag: @MainActor ([Side], Double) -> Void = { _, _ in }
    var onDragEnded: @MainActor () -> Void = {}
    private var dividers: [DividerView] = []

    override var isFlipped: Bool {
        true
    }

    /// Adds a pane's view under the dividers; the only place a pane's view is added (W2).
    func addPane(_ pane: TerminalPane) {
        addSubview(pane, positioned: .below, relativeTo: dividers.first)
    }

    func show(_ shown: [Divider], look: Look) {
        while dividers.count > shown.count {
            dividers.removeLast().removeFromSuperview()
        }
        while dividers.count < shown.count {
            let divider = DividerView()
            divider.onDrag = { [weak self] path, position in self?.onDrag(path, position) }
            divider.onDragEnded = { [weak self] in self?.onDragEnded() }
            addSubview(divider)
            dividers.append(divider)
        }
        for (view, divider) in zip(dividers, shown) {
            view.show(divider, color: NSColor(look.separatorStrong))
        }
    }

    /// The dividers take `look`'s colour where they are: a change of look moves nothing, so it lays
    /// nothing out, and a line's colour changes at once, never fading through both looks.
    func paint(_ look: Look) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        dividers.forEach { $0.line.backgroundColor = NSColor(look.separatorStrong).cgColor }
        CATransaction.commit()
    }
}

final class DividerView: NSView {
    var onDrag: @MainActor ([Side], Double) -> Void = { _, _ in }
    var onDragEnded: @MainActor () -> Void = {}
    private var divider: Divider?
    let line = CALayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.addSublayer(line)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func show(_ divider: Divider, color: NSColor) {
        self.divider = divider
        frame = divider.hitArea
        line.frame = CGRect(x: divider.line.minX - frame.minX, y: divider.line.minY - frame.minY, width: divider.line.width, height: divider.line.height)
        line.backgroundColor = color.cgColor
        window?.invalidateCursorRects(for: self)
    }

    override var isFlipped: Bool {
        true
    }

    override var mouseDownCanMoveWindow: Bool {
        false
    }

    override func resetCursorRects() {
        addCursorRect(bounds, cursor: divider?.axis == .stacked ? .resizeUpDown : .resizeLeftRight)
    }

    override func mouseDown(with _: NSEvent) {}

    override func mouseUp(with _: NSEvent) {
        onDragEnded()
    }

    override func mouseDragged(with event: NSEvent) {
        guard let divider, let room = superview else { return }
        let point = room.convert(event.locationInWindow, from: nil)
        onDrag(divider.path, Double(divider.axis == .sideBySide ? point.x : point.y))
    }
}
