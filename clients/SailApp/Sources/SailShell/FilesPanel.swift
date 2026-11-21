import AppKit
import SailAPI
import SailControls
import SailFiles
import SailLook
import SailTerminal
import SailTerminalWire

/// A workspace's files, in the panel at its tab's leading edge (⌃⌘S): a plain tree and nothing
/// else until something needs saying, then one quiet line at its top (``FilesLine``). Folders open
/// lazily; the tree follows the workspace through the watch (``FileTree``) over its own files
/// connection (``FilesSession``), which rides out a drop as the tab's terminals do and never starts a
/// stopped workspace: Start is the person's own act.
@MainActor
final class FilesPanel: NSView {
    static let width = 236.0
    /// How long after a moment time alone changes the screen the panel looks again, so the moment
    /// has passed (``FilesLine/nextChange(tree:phase:at:)``).
    static let past = 0.01

    let project: String
    let workspace: String
    var look: Look {
        didSet { relook() }
    }

    /// Escape while it floats over the terminals.
    var onDismiss: @MainActor () -> Void = {}
    /// Starts the workspace, answering nil once started or what refused it.
    var onStart: @MainActor (_ project: String, _ name: String) async -> [String]? = { _, _ in nil }
    /// A file row opened (⏎, a double click): the tab's file pane shows it.
    var onOpenFile: @MainActor (FileName, FileEntry) -> Void = { _, _ in }
    /// The tab's file pane, which speaks over this connection: its requests' answers, the watch's
    /// word on its file, the ticks, admissions and drops reach it here.
    weak var filePane: FilePane?
    /// The file whose pane holds unsaved text: its row carries the dot.
    var unsaved: FileName? {
        didSet { if unsaved != oldValue { shownRevision = nil; refresh() } }
    }

    private var shownUnsaved: FileName?

    package let list: SailList
    package let scroll: QuietScrollView
    package let line: FilesLineView
    private let castShadow: PopShadowView
    private let tickets: (any TicketSource)?
    private let mono: LabelFont
    /// Seconds on the panel's own clock: monotonic, and a test's to move.
    package var clock: @MainActor () -> Double
    package private(set) var tree: FileTree
    package private(set) var phase = FilesSession.Phase.connecting
    private var session: FilesSession?
    private var listening: Task<Void, Never>?
    private var wake: Timer?
    /// The tree's revision as last shown, its rows, and the entries marked new among them.
    private var shownRevision: Int?
    private var shownRows: [FileTree.Row] = []
    private(set) var shownNew: Set<FileName> = []
    /// How many times the rows were set: what a tick that changes nothing leaves alone.
    private(set) var rowSets = 0
    /// The chosen entry, by its path: the panel's own, never the list's row. Rows made again keep it
    /// chosen while it is listed; gone, the choice moves to its nearest folder still shown, else to
    /// the row now in its place.
    private(set) var chosenPath: FileName?
    private var rebuilding = false
    package private(set) var shownLine: FilesLine?
    private var startRefusal: [String]?
    private var starting = false

    /// Whether it floats over the tab's terminals (a narrow tab), with its shadow.
    var floating = false

    init(project: String, workspace: String, tickets: (any TicketSource)?, look: Look, mono: LabelFont, clock: @escaping @MainActor () -> Double = FilesPanel.monotonic) {
        self.project = project
        self.workspace = workspace
        self.tickets = tickets
        self.look = look
        self.mono = mono
        self.clock = clock
        tree = FileTree(openedAt: clock())
        list = SailList(rowStyle: .tree, look: look, mono: mono)
        scroll = QuietScrollView(look: look)
        line = FilesLineView(look: look)
        castShadow = PopShadowView(look: look)
        super.init(frame: NSRect(x: 0, y: 0, width: Self.width, height: 400))
        castShadow.isHidden = true
        scroll.documentView = list
        addSubview(scroll)
        addSubview(line)
        list.returnKeyShown = false
        list.onKey = { [weak self] event in self?.key(event) ?? false }
        list.onFocus = { [weak self] in self?.filePane?.keysTakenElsewhere() }
        list.onChoose = { [weak self] key in
            guard let self, !rebuilding else { return }
            chosenPath = key.flatMap { key in shownRows.first { $0.path.key == key }?.path }
        }
        list.target = self
        list.doubleAction = #selector(doubleClicked(_:))
        line.onAction = { [weak self] action in self?.act(action) }
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
        setAccessibilityLabel("Files of \(workspace)")
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    static func monotonic() -> Double {
        let since = ContinuousClock.now - origin
        return Double(since.components.seconds) + Double(since.components.attoseconds) / 1e18
    }

    private static let origin = ContinuousClock.now

    override var isFlipped: Bool {
        true
    }

    /// The shadow floats under the panel in its superview, beside it.
    var shadowView: NSView {
        castShadow
    }

    // MARK: Connection

    /// Connects, when not already, and keeps the tree live while shown.
    func open() {
        guard session == nil else { return }
        openedAt = CACurrentMediaTime()
        let session = FilesSession(project: project, workspace: workspace, door: .runner(tickets ?? Unsigned()))
        self.session = session
        listening = Task { [weak self] in
            for await event in session.events {
                self?.heard(event)
            }
        }
        session.start()
        refresh()
    }

    /// Ends the connection and lets the tree go: shown again, it is listed afresh, as a first
    /// listing (no entry of it marked new).
    func close() {
        guard session != nil else { return }
        wake?.invalidate()
        wake = nil
        listening?.cancel()
        listening = nil
        session?.close()
        session = nil
        tree = FileTree(openedAt: clock())
        shownRevision = nil
        phase = .connecting
        startRefusal = nil
        refresh()
    }

    /// When, on the main thread's media clock, the last whole listing and the last change to each
    /// folder were heard: what the drive measures each drawing from.
    package private(set) var listingHeard: (path: String, at: Double)?
    package private(set) var changeHeard: [String: Double] = [:]
    /// When the connection was asked for and when it was admitted: a first listing's wait split
    /// into the ticket and connect, and the rest.
    package private(set) var openedAt: Double?
    package private(set) var admittedAt: Double?

    /// One event of the connection, as the tree takes it.
    package func heard(_ event: FilesSession.Event) {
        let now = clock()
        switch event {
        case let .phase(phase):
            self.phase = phase
            if phase != .connected {
                tree.disconnected()
                filePane?.disconnected()
            }
            if phase == .connected { startRefusal = nil }
        case .admitted:
            admittedAt = CACurrentMediaTime()
            send(tree.watchFile(filePane?.watched))
            send(tree.admitted())
            filePane?.admitted()
        case let .frame(frame):
            if filePane?.takes(frame) == true { break }
            send(tree.receive(frame, at: now))
            if TreeRowCell.measuring, frame.isWholeListing, let path = tree.lastListed { listingHeard = (path.key, CACurrentMediaTime()) }
            if case let .filesChanged(paths) = frame {
                let at = CACurrentMediaTime()
                if TreeRowCell.measuring { paths.forEach { changeHeard[$0.key] = at } }
            }
        }
        refresh()
    }

    /// What the tree asks of the host, sent on the connection admitted now, if any: the tree has
    /// taken its own change either way.
    func send(_ frames: [Frame]) {
        session?.send(frames)
    }

    /// The open file watched at `watched`, or none.
    func watchFile(_ watched: WatchedPath?) {
        send(tree.watchFile(watched))
    }

    /// Whether a connection is up or being made: what a file pane opening needs.
    var connected: Bool {
        session != nil
    }

    // MARK: What it shows

    /// The line and the rows as the tree and the connection say now, each drawn again only when it
    /// changed: a tick that changes nothing touches no view.
    package func refresh() {
        let now = clock()
        let said = startRefusal.map { FilesLine(words: $0.joined(separator: " "), mark: .bad, action: .start) }
            ?? FilesLine(tree: tree, phase: phase, workspace: workspace, at: now) { [clock] at in Self.timeOfDay(at, now: clock()) }
        if said != shownLine {
            shownLine = said
            line.show(said, starting: starting)
            scroll.alphaValue = said?.dims == true ? Self.dimmed : 1
            needsLayout = true
        }
        schedule(after: now)
        let new = tree.newPaths(at: now), marked = new != shownNew || unsaved != shownUnsaved
        guard tree.revision != shownRevision || marked else { return }
        let rows = tree.rows, wasNew = shownNew
        shownRevision = tree.revision
        shownNew = new
        guard rows != shownRows || marked else { return }
        let place = chosenPath.flatMap { path in shownRows.firstIndex { $0.path == path } }
        let before = Dictionary(shownRows.map { ($0.path, $0) }, uniquingKeysWith: { first, _ in first })
        let dotted = Set([unsaved, shownUnsaved].compactMap { $0 })
        shownUnsaved = unsaved
        let changed = Set(rows.filter { row in before[row.path].map { $0 != row } ?? false || wasNew.contains(row.path) != new.contains(row.path) || dotted.contains(row.path) }.map(\.path))
        let fresh = shownRows.isEmpty
        shownRows = rows
        rowSets += 1
        rebuilding = true
        let entries = rows.map { SailList.Entry.item(item($0, new: new.contains($0.path))) }
        scroll.keepingScroll { if fresh { list.entries = entries } else { list.apply(entries, changed: Set(changed.map(\.key))) } }
        rebuilding = false
        keepChoice(in: rows, place: place)
        needsLayout = true
    }

    /// The chosen path chosen again in `rows`: itself while listed; else its nearest folder still
    /// shown; else the row now at its old place `place`, never a note.
    private func keepChoice(in rows: [FileTree.Row], place: Int?) {
        guard let path = chosenPath else { return }
        let target: FileName?
        if rows.contains(where: { $0.path == path }) {
            target = path
        } else if let folder = sequence(first: path, next: FileTree.parent).dropFirst().first(where: { folder in rows.contains { $0.path == folder } }) {
            target = folder
        } else if let place, !rows.isEmpty {
            let index = min(place, rows.count - 1)
            target = (rows[index...].first { $0.kind != .note } ?? rows[..<index].last { $0.kind != .note })?.path
        } else {
            target = nil
        }
        chosenPath = target
        guard list.chosen != target?.key else { return }
        rebuilding = true
        if let target { list.selectRowIndexes(IndexSet(integer: rows.firstIndex { $0.path == target } ?? 0), byExtendingSelection: false) } else { list.deselectAll(nil) }
        rebuilding = false
    }

    /// Wakes when time alone next changes what is shown, and at no other moment, while connected.
    private func schedule(after now: Double) {
        wake?.invalidate()
        wake = nil
        guard session != nil, let next = FilesLine.nextChange(tree: tree, phase: phase, at: now) else { return }
        wake = Timer.scheduledTimer(withTimeInterval: max(next - now, 0) + Self.past, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    /// The stale tree's opacity, as the mockup's `.pt.stale .tree`.
    static let dimmed = 0.55

    static func timeOfDay(_ at: Double, now: Double) -> String {
        Date().addingTimeInterval(at - now).formatted(date: .omitted, time: .shortened)
    }

    private func item(_ row: FileTree.Row, new: Bool) -> SailList.Item {
        let look = self.look, direction = userInterfaceLayoutDirection
        let width = Self.width - RowStyle.tree.margin * 2 - RowStyle.tree.leading - RowStyle.tree.trailing
        if row.kind == .note {
            let height = TreeRowCell.noteHeight(row.name, depth: row.depth, width: width)
            return SailList.Item(id: row.path.key, name: "", voice: row.name, content: TreeRowCell(row, new: false, height: height, direction: direction, look: look), height: height, inert: true)
        }
        let kind = switch row.kind {
        case .folder: row.expanded ? "folder, expanded" : "folder, collapsed"
        case .brokenLink: "broken link"
        case .other: "special file"
        default: "file"
        }
        let dotted = row.path == unsaved
        return SailList.Item(
            id: row.path.key, name: row.name, voice: "\(row.name), \(kind)\(new ? ", new" : "")\(dotted ? ", unsaved" : "")",
            content: TreeRowCell(row, new: new, unsaved: dotted, height: RowStyle.tree.height, direction: direction, look: look) { [weak self] in self?.toggle(row.path) },
            outline: SailList.Outline(level: row.depth, expanded: row.kind == .folder ? row.expanded : nil)
        )
    }

    // MARK: What the person does

    /// Opens a shut folder or shuts an open one; a broken link says why under it.
    func toggle(_ path: FileName) {
        guard let row = tree.rows.first(where: { $0.path == path }) else { return }
        send(row.kind == .folder && row.expanded ? tree.collapse(path) : tree.open(path))
        refresh()
    }

    @objc private func doubleClicked(_: Any?) {
        guard list.clickedRow >= 0, let path = chosenPath else { return }
        if !openFile(path) { toggle(path) }
    }

    /// Opens a file's row in the tab's file pane; false for a folder, a note or a broken link.
    @discardableResult
    func openFile(_ path: FileName) -> Bool {
        guard let row = tree.rows.first(where: { $0.path == path }), row.kind == .file || row.kind == .other, let entry = tree.entry(at: path) else { return false }
        onOpenFile(path, entry)
        return true
    }

    /// → opens a shut folder or goes into an open one; ← shuts an open folder or goes to the folder
    /// holding the row; Space opens or shuts a folder. Right to left the arrows swap, as the tree
    /// points.
    private func key(_ event: NSEvent) -> Bool {
        guard event.modifierFlags.intersection([.command, .option, .control]).isEmpty, let path = chosenPath,
              let index = shownRows.firstIndex(where: { $0.path == path }) else { return false }
        let row = shownRows[index], rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        let inward: UInt16 = rightToLeft ? 123 : 124, outward: UInt16 = rightToLeft ? 124 : 123
        switch event.keyCode {
        case inward where row.kind == .brokenLink:
            toggle(path)
            return true
        case inward where row.kind == .folder:
            if !row.expanded { toggle(path) } else if index + 1 < shownRows.count, shownRows[index + 1].depth > row.depth, shownRows[index + 1].kind != .note {
                list.choose(shownRows[index + 1].path.key)
            }
            return true
        case outward:
            if row.kind == .folder, row.expanded {
                toggle(path)
            } else if let parent = FileTree.parent(of: path), parent != FileTree.root {
                list.choose(parent.key)
            }
            return true
        case 49 where row.kind == .folder || row.kind == .brokenLink:
            toggle(path)
            return true
        case 36, 76:
            return openFile(path)
        default:
            return false
        }
    }

    private func act(_ action: FilesLine.Action) {
        switch action {
        case .tryAgain:
            session?.reconnectNow()
        case .start:
            guard !starting else { return }
            starting = true
            line.show(shownLine, starting: true)
            Task { [weak self, project, workspace] in
                let refused = await self?.onStart(project, workspace)
                guard let self else { return }
                starting = false
                startRefusal = refused
                shownLine = nil
                if refused == nil { session?.reconnectNow() }
                refresh()
            }
        }
    }

    /// Laid out right to left (or back), its line and rows are drawn so again, each row made anew.
    override var userInterfaceLayoutDirection: NSUserInterfaceLayoutDirection {
        didSet {
            guard userInterfaceLayoutDirection != oldValue else { return }
            for view in [list, scroll, line] as [NSView] {
                view.userInterfaceLayoutDirection = userInterfaceLayoutDirection
            }
            line.show(shownLine, starting: starting)
            shownRevision = nil
            shownRows = []
            refresh()
        }
    }

    override func cancelOperation(_ sender: Any?) {
        guard floating else { return super.cancelOperation(sender) }
        onDismiss()
    }

    // MARK: Drawing

    private func relook() {
        list.look = look
        scroll.look = look
        line.look = look
        castShadow.look = look
        shownRevision = nil
        shownRows = []
        shownLine = nil
        refresh()
        needsDisplay = true
    }

    override func layout() {
        super.layout()
        let lineHeight = line.isHidden ? 0 : line.height(for: Double(bounds.width))
        let top = FilesLineView.top
        line.frame = NSRect(x: 0, y: top, width: Double(bounds.width) - Look.hairline, height: lineHeight)
        let below = top + lineHeight
        let rows = Double(list.rect(ofRow: max(list.numberOfRows - 1, 0)).maxY)
        let leading = userInterfaceLayoutDirection == .rightToLeft ? Look.hairline : 0
        scroll.frame = NSRect(x: leading, y: below, width: Double(bounds.width) - Look.hairline, height: max(Double(bounds.height) - below, 0))
        list.frame = NSRect(x: 0, y: 0, width: Double(scroll.contentSize.width), height: max(rows, Double(scroll.contentSize.height)))
    }

    override func draw(_: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        bounds.fill()
        NSColor(look.separator).setFill()
        let edge = userInterfaceLayoutDirection == .rightToLeft ? 0 : Double(bounds.width) - Look.hairline
        NSRect(x: edge, y: 0, width: Look.hairline, height: Double(bounds.height)).fill()
    }

    /// No one signed in: every ticket refused so, said in the line.
    private struct Unsigned: TicketSource {
        func ticket(for _: TicketRequest) async throws(SailError) -> TerminalTicket {
            throw .signIn(.signedOut)
        }

        func sessions(in _: String, workspace _: String) async throws(SailError) -> RunnerSessions {
            throw .signIn(.signedOut)
        }
    }
}

/// A tree row's content, as the mockup's third pass draws it: 12 pt further in each level, the
/// folder's 11 pt disclosure in its 12 pt slot (none for a file), 4 pt, the name in 13 pt regular
/// ink, semibold while chosen, and a 5 pt `info` square after it while the entry is new. A note under
/// a folder (its refusal, or that it holds more than is shown) is 12 pt `textTertiary`, wrapped.
@MainActor
final class TreeRowCell: NSView {
    static let slot = 12.0, triangle = 11.0, gap = 4.0, fresh = 5.0, dot = 6.0, notePoints = Look.Step.footnote.points

    let row: FileTree.Row
    let new: Bool
    /// Its file's pane holds unsaved text: a 6 pt ink dot after the name.
    let unsaved: Bool
    private let height: Double
    private let look: Look
    private let onDisclose: @MainActor () -> Void
    private(set) var strong = false

    init(_ row: FileTree.Row, new: Bool, unsaved: Bool = false, height: Double, direction: NSUserInterfaceLayoutDirection, look: Look, onDisclose: @escaping @MainActor () -> Void = {}) {
        self.row = row
        self.new = new
        self.unsaved = unsaved
        self.height = height
        self.look = look
        self.onDisclose = onDisclose
        super.init(frame: .zero)
        userInterfaceLayoutDirection = direction
        setAccessibilityElement(false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: height)
    }

    static func noteHeight(_ words: String, depth: Int, width: Double) -> Double {
        let room = max(width - Double(depth) * RowStyle.indent - slot - gap, 40)
        let box = note(words, ink: Look.light.textTertiary).boundingRect(with: NSSize(width: room, height: 1000), options: [.usesLineFragmentOrigin])
        return max(RowStyle.tree.height, ceil(Double(box.height)) + 8)
    }

    private static func note(_ words: String, ink: Tint) -> NSAttributedString {
        NSAttributedString(string: words, attributes: [.font: NSFont.systemFont(ofSize: notePoints), .foregroundColor: NSColor(ink)])
    }

    /// Where the disclosure is drawn, in its slot after the row's indent.
    var disclosure: NSRect {
        let indent = Double(row.depth) * RowStyle.indent
        let x = userInterfaceLayoutDirection == .rightToLeft ? Double(bounds.width) - indent - Self.slot : indent
        return NSRect(x: x + (Self.slot - Self.triangle) / 2, y: (Double(bounds.height) - Self.triangle) / 2, width: Self.triangle, height: Self.triangle)
    }

    /// The name's attributes, made once for each weight and ink and kept as Foundation holds them: a
    /// row scrolled into a long tree draws its name without building them (round six's scroll stacks
    /// spent its hold hashing a new attribute dictionary per row).
    private static var inked: [Inked: [NSAttributedString.Key: Any]] = [:]

    private struct Inked: Hashable {
        let strong: Bool
        let ink: Tint
    }

    private static func attributes(strong: Bool, ink: Tint) -> [NSAttributedString.Key: Any] {
        let key = Inked(strong: strong, ink: ink)
        if let made = inked[key] { return made }
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byTruncatingTail
        let made = NSDictionary(dictionary: [
            NSAttributedString.Key.font: NSFont.systemFont(ofSize: Look.Step.callout.points, weight: strong ? .semibold : .regular),
            NSAttributedString.Key.foregroundColor: NSColor(ink), NSAttributedString.Key.paragraphStyle: style,
        ]) as? [NSAttributedString.Key: Any] ?? [:]
        inked[key] = made
        return made
    }

    /// The name as drawn, built once for each weight the row shows: a draw builds none.
    var name: NSAttributedString {
        if let built, built.strong == strong { return built.text }
        let text = NSAttributedString(string: row.name, attributes: Self.attributes(strong: strong, ink: row.kind == .brokenLink ? look.textTertiary : look.text))
        built = (strong, text)
        if Self.measuring { Self.namesBuilt += 1 }
        return text
    }

    private var built: (strong: Bool, text: NSAttributedString)?
    /// Rows drawn and names built, in all: what the drive holds a scroll's drawing against.
    static private(set) var draws = 0
    static private(set) var namesBuilt = 0
    /// The main thread's own CPU spent in rows' draws, in all and the most one draw took, in ns.
    static private(set) var drawCPU: (total: UInt64, most: UInt64) = (0, 0)
    /// Whether the probes above and below count: off in the app, on only while the drive measures.
    package static var measuring = false

    /// The last row drawn, on the main thread's media clock: what the drive measures a listing or a
    /// change to its rows on the screen by.
    static private(set) var lastDrawn = (at: 0.0, path: "", new: false)
    /// When each entry was first drawn marked new: a change's row on the screen, whatever order the
    /// rows around it are drawn in.
    static private(set) var newDrawn: [String: Double] = [:]

    override func draw(_: NSRect) {
        let now = CACurrentMediaTime(), began = Self.measuring ? clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID) : 0
        defer {
            if Self.measuring {
                let spent = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID) - began
                Self.drawCPU = (Self.drawCPU.total + spent, max(Self.drawCPU.most, spent))
            }
        }
        if Self.measuring {
            Self.draws += 1
            Self.lastDrawn = (now, row.path.key, new)
            if new, Self.newDrawn[row.path.key] == nil {
                Self.newDrawn = Self.newDrawn.filter { now - $0.value <= FileTree.newFor }
                Self.newDrawn[row.path.key] = now
            }
        }
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft, width = Double(bounds.width)
        let start = Double(row.depth) * RowStyle.indent + Self.slot + Self.gap
        if row.kind == .note {
            let words = Self.note(row.name, ink: look.textTertiary)
            let room = max(width - start, 0), box = NSRect(x: rightToLeft ? 0 : start, y: 4, width: room, height: Double(bounds.height) - 8)
            let style = NSMutableParagraphStyle()
            style.alignment = rightToLeft ? .right : .left
            let aligned = NSMutableAttributedString(attributedString: words)
            aligned.addAttribute(.paragraphStyle, value: style, range: NSRange(location: 0, length: aligned.length))
            aligned.draw(with: box, options: [.usesLineFragmentOrigin])
            return
        }
        if row.kind == .folder {
            let flip = rightToLeft && !row.expanded
            let rect = disclosure
            if flip {
                let context = NSGraphicsContext.current?.cgContext
                context?.saveGState()
                context?.translateBy(x: rect.midX * 2, y: 0)
                context?.scaleBy(x: -1, y: 1)
                Draw.icon(.right, in: rect, ink: look.textTertiary)
                context?.restoreGState()
            } else {
                Draw.icon(row.expanded ? .down : .right, in: rect, ink: look.textTertiary)
            }
        }
        let name = self.name, freshRoom = (new ? Self.gap + Self.fresh : 0) + (unsaved ? Self.gap + Self.dot : 0)
        let room = max(width - start - freshRoom, 0), drawn = min(Double(name.size().width), room)
        let points = Look.Step.callout.points
        let baseline = TextLine.baseline(top: (Double(bounds.height) - points) / 2, points: points, lineHeight: points)
        let x = rightToLeft ? width - start - drawn : start
        let font = name.length > 0 ? name.attribute(.font, at: 0, effectiveRange: nil) as? NSFont : nil
        name.draw(with: NSRect(x: x, y: baseline - Double(font?.ascender ?? 0), width: drawn, height: points * 1.5), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine])
        var after = rightToLeft ? x : x + drawn
        if new {
            let at = rightToLeft ? after - Self.gap - Self.fresh : after + Self.gap
            NSColor(Tint(look.info)).setFill()
            NSRect(x: at, y: (Double(bounds.height) - Self.fresh) / 2, width: Self.fresh, height: Self.fresh).fill()
            after = rightToLeft ? at : at + Self.fresh
        }
        if unsaved {
            let at = rightToLeft ? after - Self.gap - Self.dot : after + Self.gap
            NSColor(look.text).setFill()
            NSBezierPath(ovalIn: NSRect(x: at, y: (Double(bounds.height) - Self.dot) / 2, width: Self.dot, height: Self.dot)).fill()
        }
    }

    /// Whether a press at `point` opens or shuts the folder: inside the disclosure's own 12 pt slot,
    /// the row's whole height; past it, the press chooses the row.
    func opens(at point: NSPoint) -> Bool {
        guard row.kind == .folder else { return false }
        let indent = Double(row.depth) * RowStyle.indent
        let x = userInterfaceLayoutDirection == .rightToLeft ? Double(bounds.width) - indent - Self.slot : indent
        return NSRect(x: x, y: 0, width: Self.slot, height: Double(bounds.height)).contains(point)
    }

    override func mouseDown(with event: NSEvent) {
        guard opens(at: convert(event.locationInWindow, from: nil)) else { return super.mouseDown(with: event) }
        onDisclose()
    }
}

extension TreeRowCell: MarkedRow {
    package func mark(chosen: Bool, strong _: Bool) {
        guard chosen != strong else { return }
        strong = chosen
        needsDisplay = true
    }
}

/// The panel's one quiet line, as the mockup's `.sline` draws it: 6 pt in from the panel's sides
/// with 4 pt under it, 8 pt in, 4 pt above and 6 pt below its words, a hairline under it; its mark
/// (a spinner, or a 6 pt square in its tone), the words in 12 pt secondary ink (`error` for a bad
/// one), wrapped, and its action at the trailing end: Try Again, or Start in the accent.
@MainActor
final class FilesLineView: NSView {
    static let top = 6.0, margin = 6.0, inset = 8.0, above = 4.0, below = 6.0, under = 4.0, square = 6.0, gap = 8.0, points = Look.Step.footnote.points

    var look: Look {
        didSet { show(shown, starting: starting) }
    }

    var onAction: @MainActor (FilesLine.Action) -> Void = { _ in }
    private(set) var shown: FilesLine?
    private var starting = false
    package let spinner: Spinner
    /// The library's own button: Start the one primary action in view, Try Again quiet.
    package let button: SailButton

    init(look: Look) {
        self.look = look
        spinner = Spinner(look: look)
        button = SailButton("Try Again", kind: .quiet, size: .small, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        button.target = self
        button.action = #selector(pressed(_:))
        addSubview(spinner)
        addSubview(button)
        isHidden = true
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    func show(_ line: FilesLine?, starting: Bool) {
        shown = line
        self.starting = starting
        isHidden = line == nil
        spinner.isHidden = line?.mark != .working
        spinner.look = look
        let title = switch line?.action {
        case .tryAgain: "Try Again"
        case .start: starting ? "Starting" : "Start"
        case nil: ""
        }
        button.isHidden = line?.action == nil
        button.isEnabled = !starting
        button.look = look
        let kind: ButtonKind = line?.action == .start ? .primary : .quiet
        if button.kind != kind { button.kind = kind }
        if button.title != title { button.title = title }
        setAccessibilityLabel(line?.words)
        needsLayout = true
        needsDisplay = true
    }

    private var words: NSAttributedString {
        let ink = shown?.mark == .bad ? Tint(look.error) : look.textSecondary
        let style = NSMutableParagraphStyle()
        style.alignment = userInterfaceLayoutDirection == .rightToLeft ? .right : .left
        return NSAttributedString(string: shown?.words ?? "", attributes: [.font: NSFont.systemFont(ofSize: Self.points), .foregroundColor: NSColor(ink), .paragraphStyle: style])
    }

    private var markWidth: Double {
        switch shown?.mark {
        case .working: Double(spinner.intrinsicContentSize.width)
        case .waiting, .bad, .quiet: Self.square
        default: 0
        }
    }

    /// The room for the words and the action in a line `width` wide: after the mark.
    private func room(_ width: Double) -> Double {
        let mark = markWidth
        return width - 2 * Self.margin - 2 * Self.inset - (mark > 0 ? mark + Self.gap : 0)
    }

    /// Whether the action takes a row under the words in a line `width` wide (``OneLine``).
    private func actionUnder(_ width: Double) -> Bool {
        guard !button.isHidden else { return false }
        return OneLine.actionsUnder(words: Double(words.size().width), actions: Double(button.intrinsicContentSize.width) + Self.gap, room: room(width))
    }

    /// Where the words go in a line `width` wide: after the mark, before the action beside them.
    private func wordsWidth(_ width: Double) -> Double {
        let action = button.isHidden || actionUnder(width) ? 0 : Double(button.intrinsicContentSize.width) + Self.gap
        return max(room(width) - action, 40)
    }

    /// Its height in a panel `width` wide: its words wrapped, with their padding and the gap under it.
    func height(for width: Double) -> Double {
        let under = actionUnder(width) ? Self.gap + Double(button.intrinsicContentSize.height) : 0
        // Whole points, so the hairline under it and the rows below start on the pixel grid: words
        // centred on a button's height would end it, and start them, on half a pixel.
        return ceil(Self.above + max(wordsHeight(width) + wordsTop(width), firstRow(width)) + under + Self.below + Look.hairline + Self.under)
    }

    private func wordsHeight(_ width: Double) -> Double {
        ceil(Double(words.boundingRect(with: NSSize(width: wordsWidth(width), height: 1000), options: [.usesLineFragmentOrigin]).height))
    }

    /// The first row's height: a line of words, or the button's beside them, whichever is taller.
    private func firstRow(_ width: Double) -> Double {
        max(Self.points * 1.25, button.isHidden || actionUnder(width) ? 0 : Double(button.intrinsicContentSize.height))
    }

    /// How far the words start below the line's top padding: centred on the first row.
    private func wordsTop(_ width: Double) -> Double {
        (firstRow(width) - Self.points * 1.25) / 2
    }

    override func layout() {
        super.layout()
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft, width = Double(bounds.width)
        let firstLine = Self.above + firstRow(width) / 2
        let start = Self.margin + Self.inset
        let side = Double(spinner.intrinsicContentSize.width)
        spinner.frame = NSRect(x: rightToLeft ? width - start - side : start, y: firstLine - side / 2, width: side, height: side)
        let size = button.intrinsicContentSize
        let y = actionUnder(width) ? Self.above + wordsHeight(width) + Self.gap : firstLine - Double(size.height) / 2
        button.frame = NSRect(x: rightToLeft ? start : width - start - Double(size.width), y: y, width: Double(size.width), height: Double(size.height))
    }

    override func draw(_: NSRect) {
        guard let shown else { return }
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft, width = Double(bounds.width)
        let start = Self.margin + Self.inset, mark = markWidth
        let firstLine = Self.above + firstRow(width) / 2
        let tone: Tint? = switch shown.mark {
        case .waiting: Tint(look.info)
        case .bad: Tint(look.error)
        case .quiet: look.textTertiary
        default: nil
        }
        if let tone {
            NSColor(tone).setFill()
            NSRect(x: rightToLeft ? width - start - Self.square : start, y: firstLine - Self.square / 2, width: Self.square, height: Self.square).fill()
        }
        let lead = mark > 0 ? mark + Self.gap : 0, room = wordsWidth(width)
        let x = rightToLeft ? width - start - lead - room : start + lead
        words.draw(with: NSRect(x: x, y: Self.above + wordsTop(width), width: room, height: Double(bounds.height)), options: [.usesLineFragmentOrigin])
        NSColor(look.separator).setFill()
        NSRect(x: Self.margin, y: Double(bounds.height) - Self.under - Look.hairline, width: width - 2 * Self.margin, height: Look.hairline).fill()
    }

    @objc private func pressed(_: Any?) {
        guard let action = shown?.action, !starting else { return }
        onAction(action)
    }
}

extension Frame {
    /// A listing's last frame.
    var isWholeListing: Bool {
        if case let .filesListing(listing) = self { !listing.more } else { false }
    }
}
