import AppKit
import SailLook
import SailSurface
import SailTerminal
import SailTerminalView
import SailTerminalWire
import SailVT
import SailWorkbench

/// "This Mac": the window's terminals in tabs and splits. The arrangement is a `Workbench` value;
/// each command changes it and `apply()` draws it, setting frames and `isHidden` only: a pane's
/// view is made once, in its tab's view, and stays there until the pane closes (W2). Every change
/// is kept in user defaults; at the first showing only the panes whose sessions the host lists live
/// come back, and with none one new tab opens. Closing a pane ends its session, asked first unless
/// the shell marked its prompt; detaching (quitting) ends none.
@MainActor
public final class WorkbenchView: NSView {
    static let arrangementKey = "workbench.arrangement"
    /// How far a key moves a divider, in points.
    static let dividerStep = 10.0

    public let host: LocalHost
    public let strip: TabStrip
    public private(set) var model = Workbench()
    public private(set) var panes: [PaneID: TerminalPane] = [:]
    /// Told the selected tab's title each time it may have changed.
    var onTitle: @MainActor (String) -> Void = { _ in }
    /// Asked to show "This Mac", for a command given while it is not shown.
    var onShow: @MainActor () -> Void = {}

    /// The terminals' palette, the launch appearance's.
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
    /// The arrangement is the person's from here on: restored, so worth keeping.
    private(set) var restored = false

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

    /// "No terminal is open" shows, with New Terminal.
    public var saysNoTerminal: Bool {
        !emptyLabel.isHidden
    }

    /// The selected tab's focused pane.
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
            try await host.ensureServing()
            listed = try await SessionListing.sessions(at: host.address)
        } catch {
            banner.show(TerminalPane.sentence("\(error)"), button: "Try Again") { [weak self] in
                Task { await self?.restore() }
            }
            return
        }
        banner.hide()
        let kept = UserDefaults.standard.data(forKey: Self.arrangementKey).flatMap { try? JSONDecoder().decode(Arrangement.self, from: $0) }
        model = Workbench.restore(kept, live: listed.filter(\.live).map(\.name), home: NSHomeDirectory())
        restored = true
        for pane in model.panes.values.sorted(by: { $0.id < $1.id }) {
            if makeView(pane, start: .attach, points: FontLadder.standard) == nil { model.close(pane.id) }
        }
        if model.tabs.isEmpty { openTab() } else { apply() }
    }

    /// Detaches every pane: each session lives on at the host.
    func detachAll() {
        panes.values.forEach { $0.detach() }
    }

    /// Draws the model: each tab's view shown only when selected, each pane at its frame or
    /// hidden, the dividers, the scrim, the strip, the window's title; the focused pane the first
    /// responder; the arrangement kept.
    func apply() {
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
        strip.show(model.tabs.map { TabStrip.Item(id: $0.id, title: model.title(of: $0.id), zoomed: $0.zoomed != nil) }, selected: model.selected)
        onTitle(model.selectedTab.map { model.title(of: $0.id) } ?? ShellApp.title)
        if let focused, !isHidden, !strip.isRenaming, window?.firstResponder !== focused.terminal { window?.makeFirstResponder(focused.terminal) }
        keep()
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
        addSubview(room, positioned: .below, relativeTo: banner)
        tabViews[id] = room
        return room
    }

    private func keep() {
        guard restored, let data = try? JSONEncoder().encode(model.arrangement) else { return }
        UserDefaults.standard.set(data, forKey: Self.arrangementKey)
    }

    /// The view for `pane`, added once to its tab's view; nil, said in the banner, when it cannot
    /// be made.
    @discardableResult
    private func makeView(_ pane: Pane, start: TerminalPane.Start, points: Double) -> TerminalPane? {
        guard let tab = model.tab(holding: pane.id) else { return nil }
        let view: TerminalPane
        do {
            view = try TerminalPane(pane, start: start, host: host, fonts: fonts, points: points, look: look, palette: palette)
        } catch {
            banner.show(TerminalPane.sentence(error.description))
            return nil
        }
        view.onEvent = { [weak self] view, event in self?.handle(event, from: view) }
        view.onReopen = { [weak self] view in self?.reopen(view) }
        view.terminal.onFocus = { [weak self, id = pane.id] in self?.focusClicked(id) }
        panes[pane.id] = view
        tabView(tab.id).addPane(view)
        return view
    }

    private func open(_ id: PaneID, points: Double) {
        if let pane = model.panes[id], makeView(pane, start: .open(folder: pane.place.folder), points: points) == nil {
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
            apply()
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

    private func reopen(_ view: TerminalPane) {
        guard let place = model.panes[view.id]?.place, let id = model.reopen(view.id, session: place.newSessionName) else { return }
        view.detach()
        view.removeFromSuperview()
        panes[view.id] = nil
        open(id, points: view.terminal.fontPoints)
    }

    private func focusClicked(_ id: PaneID) {
        guard model.focusedPane?.id != id else { return }
        model.focus(id)
        apply()
    }

    private var scale: Double {
        window.map { Double($0.backingScaleFactor) } ?? 2
    }

    private var room: CGSize {
        bounds.size
    }

    /// The least a pane may be: 10 columns by 3 rows and the padding, at the focused pane's font.
    private var minimum: CGSize {
        guard let metrics = focused?.terminal.metrics else { return .zero }
        return CGSize(
            width: Double(10 * metrics.width + 2 * metrics.padding.width) / metrics.scale,
            height: Double(3 * metrics.height + 2 * metrics.padding.height) / metrics.scale
        )
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
        apply()
    }

    private func openTab() {
        let place = newPlace()
        open(model.newTab(session: place.newSessionName, place: place), points: points)
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
        let place = newPlace(), size = points
        guard let id = model.split(axis, session: place.newSessionName, place: place, in: room, minimum: minimum, scale: scale) else {
            return NSSound.beep()
        }
        open(id, points: size)
    }

    @objc public func closePane(_: Any?) {
        guard let pane = focused else { return }
        confirm(ending: [pane], question: "Close this terminal?", detail: "The program running in it will end.")
    }

    @objc public func closeTab(_: Any?) {
        guard let tab = model.selectedTab else { return }
        let views = tab.layout.leaves.compactMap { panes[$0] }
        guard views.count > 1 else { return closePane(nil) }
        confirm(ending: views, question: "Close this tab?", detail: "Its \(views.count) terminals will end.")
    }

    /// Ends `views`' sessions, asking first when one of them may end a running program.
    private func confirm(ending views: [TerminalPane], question: String, detail: String) {
        guard views.contains(where: \.mayEndAProgram), let window else { return end(views) }
        Confirmation.ask(question, detail: detail + " Quitting Sail instead leaves every terminal running.", action: "Close", on: window) { [weak self] close in
            if close { self?.end(views) }
        }
    }

    private func end(_ views: [TerminalPane]) {
        for view in views where panes[view.id] === view {
            view.end()
            remove(view)
        }
        apply()
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
        change { $0.equalize() }
    }

    /// Moves the focused pane's nearest divider 10 pt the way the menu item's tag points: its
    /// `Direction` in `Direction.allCases`.
    @objc public func moveDivider(_ sender: NSMenuItem) {
        guard Direction.allCases.indices.contains(sender.tag) else { return }
        let (direction, size, least, pixels) = (Direction.allCases[sender.tag], room, minimum, scale)
        change { $0.moveDivider(direction, by: Self.dividerStep, in: size, minimum: least, scale: pixels) }
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

    /// The selected tab's title becomes a field to type its name into.
    @objc public func renameTab(_: Any?) {
        strip.tabs.first(where: \.isSelected)?.rename()
    }

    @objc public func toggleLatency(_ sender: Any?) {
        focused?.toggleLatency(sender)
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
        strip.onClose = { [weak self] id in
            self?.change { $0.select(id) }
            self?.closeTab(nil)
        }
        strip.onNew = { [weak self] in self?.newTab(nil) }
        strip.onRename = { [weak self] id, name in self?.change { bench in name.map { bench.rename(id, to: $0) } } }
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
    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        let panes = model.selectedTab?.layout.leaves.count ?? 0
        switch item.action {
        case #selector(newTab(_:)): return true
        case #selector(zoomSplit(_:)), #selector(equalizeSplits(_:)), #selector(selectNextSplit(_:)), #selector(selectPreviousSplit(_:)):
            return !isHidden && panes > 1
        default: return !isHidden && panes > 0
        }
    }
}

/// One tab's room: its panes, laid out by the workbench, and its dividers over them.
final class TabView: NSView {
    var onDrag: @MainActor ([Side], Double) -> Void = { _, _ in }
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
            addSubview(divider)
            dividers.append(divider)
        }
        for (view, divider) in zip(dividers, shown) {
            view.show(divider, color: NSColor(look.separatorStrong))
        }
    }
}

/// A divider: a 1 pt line of `separatorStrong` in a 7 pt area the pointer drags.
final class DividerView: NSView {
    var onDrag: @MainActor ([Side], Double) -> Void = { _, _ in }
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

    override func mouseDragged(with event: NSEvent) {
        guard let divider, let room = superview else { return }
        let point = room.convert(event.locationInWindow, from: nil)
        onDrag(divider.path, Double(divider.axis == .sideBySide ? point.x : point.y))
    }
}
