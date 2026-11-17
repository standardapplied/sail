import AppKit
import SailAPI
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
    public static let arrangementKey = "workbench.arrangement"
    static let dividerStep = 10.0

    public let host: LocalHost
    public let strip: TabStrip
    public private(set) var model = Workbench()
    public private(set) var panes: [PaneID: TerminalPane] = [:]
    var onTitle: @MainActor (String) -> Void = { _ in }
    /// Asked to show "This Mac", for a command given while it is not shown.
    var onShow: @MainActor () -> Void = {}
    /// Who is signed in now and where they ask tickets, nil while no one is: a tab in a workspace
    /// asks it at every ticket (``SignedInTickets``), so it never holds a sign-in of its own.
    var signedIn: @MainActor @Sendable () -> (handle: String, tickets: any TicketSource)? = { nil }
    /// Asked to show the account, where a runner tab's lapsed sign-in is signed in again.
    var onSignIn: @MainActor () -> Void = {}

    let palette: Palette
    var look: Look {
        didSet { paint() }
    }

    private let fonts: TerminalFonts
    private var tabViews: [TabID: TabView] = [:]
    private let banner: StatusBanner
    private let emptyLabel = NSTextField(labelWithString: "No terminal is open")
    private let newTerminal: AccentButton
    private var started = false
    private var pendingKeep: DispatchWorkItem?
    private var kept: Data?
    static let keepDelay = 0.3
    /// The arrangement is the person's from here on: restored, so worth keeping.
    private(set) var restored = false
    /// Workspaces asked to open before the first restore, opened once it has replaced the model.
    private var pendingWorkspaces: [Place] = []

    init(fonts directory: URL, helper: String?, look: Look) throws(ShellError) {
        self.look = look
        palette = look.palette
        do {
            fonts = try TerminalFonts(directory: directory)
        } catch {
            throw .resources("the terminal's fonts cannot be read: \(error)")
        }
        do {
            host = try LocalHost(binary: helper)
        } catch {
            throw .host("this Mac's terminal host has no place to listen: \(error)")
        }
        banner = StatusBanner(look: look)
        strip = TabStrip(look: look)
        newTerminal = AccentButton("New Terminal", look: look, target: nil, action: #selector(WorkbenchView.newTab(_:)))
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
        guard !started else { return }
        started = true
        Task { await restore() }
    }

    private func restore() async {
        banner.show("Connecting to this Mac's terminal host")
        let listed: [SessionInfo]
        do {
            try await host.ensureServing { [weak self] sentence in await self?.tell(sentence) }
            listed = try await SessionListing.sessions(at: host.address)
        } catch {
            banner.show(Said.sentence("\(error)"), button: "Try Again") { [weak self] in
                Task { await self?.restore() }
            }
            return
        }
        if banner.shown != told { banner.hide() }
        let kept = UserDefaults.standard.data(forKey: Self.arrangementKey).flatMap { try? JSONDecoder().decode(Arrangement.self, from: $0) }
        model = Workbench.restore(kept, live: listed.filter(\.live).map(\.name), home: NSHomeDirectory())
        restored = true
        for pane in model.panes.values.sorted(by: { $0.id < $1.id }) {
            if makeView(pane, start: .attach, points: FontLadder.standard) == nil { model.close(pane.id) }
        }
        model.fit(in: room, minimum: minimum(of: Array(panes.values)), scale: scale)
        let waiting = pendingWorkspaces
        pendingWorkspaces = []
        if model.tabs.isEmpty, waiting.isEmpty { openTab() } else { apply() }
        waiting.forEach(openTab(in:))
    }

    func detachAll() {
        keep()
        panes.values.forEach { $0.detach() }
    }

    /// Draws the model, by frames and `isHidden` only (W2), and keeps it soon after.
    func apply() {
        strip.tabs.filter { $0.field != nil && $0.id != model.selected }.forEach { $0.endRename() }
        for (id, view) in tabViews where !model.tabs.contains(where: { $0.id == id }) {
            view.removeFromSuperview()
            tabViews[id] = nil
        }
        for tab in model.tabs {
            let view = tabView(tab.id)
            view.isHidden = tab.id != model.selected
            layout(tab, in: view)
        }
        panes.values.forEach { $0.begin() }
        emptyLabel.isHidden = !model.tabs.isEmpty || !restored
        newTerminal.isHidden = emptyLabel.isHidden
        showTitles()
        if let focused, !isHidden, !strip.isRenaming, window?.firstResponder !== focused.terminal { window?.makeFirstResponder(focused.terminal) }
        keepSoon()
    }

    private func showTitles() {
        strip.show(model.tabs.map { TabStrip.Item(id: $0.id, title: model.title(of: $0.id), zoomed: $0.zoomed != nil) }, selected: model.selected)
        onTitle(model.selectedTab.map { model.title(of: $0.id) } ?? ShellApp.title)
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

    private func layout(_ tab: Tab, in view: TabView) {
        let frames = model.frames(of: tab.id, in: view.bounds.size, scale: scale)
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
    public var isKeepingSoon: Bool {
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
        guard restored, let data = try? JSONEncoder().encode(model.arrangement), data != kept else { return }
        UserDefaults.standard.set(data, forKey: Self.arrangementKey)
        kept = data
    }

    /// The view for `pane`, added once to its tab's view; nil, said in the banner, when it cannot
    /// be made.
    @discardableResult
    private func makeView(_ pane: Pane, start: TerminalPane.Start, points: Double) -> TerminalPane? {
        guard let tab = model.tab(holding: pane.id) else { return nil }
        let view: TerminalPane
        do {
            let tickets = pane.place.onRunner ? signedIn().map { SignedInTickets(for: $0.handle, current: signedIn) } : nil
            view = try TerminalPane(pane, start: start, host: host, runner: tickets, fonts: fonts, points: points, look: look, palette: palette)
        } catch {
            banner.show(Said.sentence(error.description))
            return nil
        }
        view.onEvent = { [weak self] view, event in self?.handle(event, from: view) }
        view.onSignIn = { [weak self] in self?.onSignIn() }
        view.terminal.contextMenu = { [weak self] in self?.paneMenu() }
        view.terminal.onFocus = { [weak self, id = pane.id] in self?.focusClicked(id) }
        panes[pane.id] = view
        tabView(tab.id).addPane(view)
        return view
    }

    private func open(_ id: PaneID, points: Double) {
        if let pane = model.panes[id], makeView(pane, start: .open, points: points) == nil {
            model.close(id)
        }
        apply()
    }

    private func remove(_ view: TerminalPane) {
        model.close(view.id)
        view.removeFromSuperview()
        panes[view.id] = nil
    }

    private func handle(_ event: SessionEvent, from view: TerminalPane) {
        switch event {
        case let .screen(.title(title)):
            model.setTitle(title, of: view.id)
            if title.hasPrefix("t"), Int(title.dropFirst()) != nil, let tab = model.tab(holding: view.id) {
                model.rename(tab.id, to: title)
                keepSoon()
            }
            showTitles()
        case let .screen(.workingDirectory(folder)):
            model.setFolder(folder, of: view.id)
        case let .phase(.ended(.ended(reason))) where Ending.closesPane(reason: reason, lived: ContinuousClock.now - view.opened):
            view.detach()
            remove(view)
            apply()
        default:
            break
        }
    }

    /// A click made a pane the first responder: the model follows at once, the drawing next turn.
    private func focusClicked(_ id: PaneID) {
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

    /// This Mac's name as `hostname` says it, read from the kernel: `ProcessInfo.hostName` asks
    /// the network, which can hold the main thread for seconds and has macOS ask the person for
    /// local network access.
    private static let hostName: String = {
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

    /// Says `sentence` in the banner for 10 s, unless something else is said meanwhile.
    func tell(_ sentence: String) {
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

    /// A tab with a terminal in workspace `name` of `project`, beside this Mac's own; before the
    /// first restore, once it is done, as it replaces the model.
    func openWorkspace(project: String, name: String) {
        let place = Place.workspace(project: project, name: name)
        guard restored else { return pendingWorkspaces.append(place) }
        openTab(in: place)
    }

    private func openTab(in place: Place) {
        open(model.newTab(session: place.newSessionName, place: place), points: points)
    }

    /// The panes whose sessions are on the runner, which do not outlive the app.
    var runnerPanes: [TerminalPane] {
        panes.values.filter(\.place.onRunner).sorted { $0.id < $1.id }
    }

    /// What a person calls `view`: its tab's title, never its session's name.
    func title(of view: TerminalPane) -> String {
        model.tab(holding: view.id).map { model.title(of: $0.id) } ?? view.place.label ?? "A terminal on the runner"
    }

    /// Takes every runner tab away, its session ended or left to the runner: what follows a
    /// sign-out, so no tab outlives the sign-in it was opened under.
    func removeRunnerPanes() {
        runnerPanes.forEach(remove)
        apply()
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
        guard let pane = focused else { return }
        confirm(ending: [pane], question: "Close this terminal?", detail: "The program running in it will end.")
    }

    /// The tab a command acts on: the one whose menu it came from (a tab's menu acts on that tab,
    /// selected or not), else the selected one.
    private func tab(for sender: Any?) -> Tab? {
        let id = (sender as? NSMenuItem)?.representedObject as? TabID
        return id.flatMap { id in model.tabs.first { $0.id == id } } ?? model.selectedTab
    }

    @objc public func closeTab(_ sender: Any?) {
        guard let tab = self.tab(for: sender) else { return }
        let views = tab.layout.leaves.compactMap { panes[$0] }
        guard views.count > 1 else {
            return confirm(ending: views, question: "Close this terminal?", detail: "The program running in it will end.")
        }
        confirm(ending: views, question: "Close this tab?", detail: "Its \(views.count) terminals will end.")
    }

    /// Closes every tab but this one, asking first as Close Tab does.
    @objc public func closeOtherTabs(_ sender: Any?) {
        guard let kept = tab(for: sender)?.id else { return }
        let views = model.tabs.filter { $0.id != kept }.flatMap(\.layout.leaves).compactMap { panes[$0] }
        guard !views.isEmpty else { return }
        confirm(ending: views, question: "Close the other tabs?", detail: "Their \(views.count == 1 ? "terminal" : "\(views.count) terminals") will end.")
    }

    /// Ends `views`' sessions, asking first when one of them may end a running program, as one not
    /// attached may.
    private func confirm(ending views: [TerminalPane], question: String, detail: String) {
        guard views.contains(where: \.mayEndAProgram), let window else { return end(views) }
        let onThisMac = views.allSatisfy { !$0.place.onRunner }
        Confirmation.ask(question, detail: detail + (onThisMac ? " Quitting Sail instead leaves every terminal running." : ""), action: "Close", on: window) { [weak self] close in
            if close { self?.end(views) }
        }
    }

    /// Ends `views`' sessions. A runner tab stays until the runner says its session ended (or had
    /// already); when the runner does not confirm, the tab says so and offers to end it again or to
    /// close it anyway, never vanishing as if it had ended.
    private func end(_ views: [TerminalPane]) {
        for view in views where panes[view.id] === view {
            guard view.place.onRunner, let ending = view.end() else {
                view.end()
                remove(view)
                continue
            }
            view.showEnding()
            Task { [weak self] in
                let outcome = await ending.value
                self?.settle(view, outcome)
            }
        }
        apply()
    }

    private func settle(_ view: TerminalPane, _ outcome: EndOutcome) {
        guard panes[view.id] === view else { return }
        guard !outcome.closesTab, case let .unconfirmed(why) = outcome else {
            remove(view)
            return apply()
        }
        view.showUnended(why, endAgain: { [weak self] in self?.end([view]) }, closeAnyway: { [weak self] in
            guard let self, panes[view.id] === view else { return }
            remove(view)
            apply()
        })
    }

    @objc public func zoomSplit(_: Any?) {
        change { $0.toggleZoom() }
    }

    @objc public func selectPreviousSplit(_: Any?) {
        change { $0.focusNeighbour(next: false) }
    }

    @objc public func selectNextSplit(_: Any?) {
        change { $0.focusNeighbour(next: true) }
    }

    /// Focuses the pane the menu item's tag points to: its `Direction` in `Direction.allCases`.
    @objc public func selectSplit(_ sender: NSMenuItem) {
        guard Direction.allCases.indices.contains(sender.tag) else { return }
        let (direction, size, pixels) = (Direction.allCases[sender.tag], room, scale)
        change { $0.moveFocus(direction, in: size, scale: pixels) }
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

    private func change(_ command: (inout Workbench) -> Void) {
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
        addSubview(empty)
        addSubview(banner)
        NSLayoutConstraint.activate([
            empty.centerXAnchor.constraint(equalTo: centerXAnchor), empty.centerYAnchor.constraint(equalTo: centerYAnchor),
            banner.centerXAnchor.constraint(equalTo: centerXAnchor),
            banner.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Look.Space.x3l),
            banner.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: Look.Space.l),
        ])
        emptyLabel.isHidden = true
        newTerminal.isHidden = true
        strip.onSelect = { [weak self] id in self?.change { $0.select(id) } }
        strip.onMenu = { [weak self] id in self?.tabMenu(for: id) }
        strip.onClose = { [weak self] id in
            self?.change { $0.select(id) }
            self?.closeTab(nil)
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
        layer?.backgroundColor = NSColor(palette.background).cgColor
        emptyLabel.textColor = NSColor(look.textSecondary)
        newTerminal.look = look
        banner.look = look
        strip.look = look
        panes.values.forEach { $0.look = look }
        model.tabs.forEach { tab in tabViews[tab.id].map { layout(tab, in: $0) } }
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
}

final class DividerView: NSView {
    var onDrag: @MainActor ([Side], Double) -> Void = { _, _ in }
    var onDragEnded: @MainActor () -> Void = {}
    private var divider: Divider?
    private let line = CALayer()

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
