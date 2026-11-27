import AppKit
import SailAPI
import SailControls
import SailLook
import SailWorkbench

/// A workspace row's New Session popover (`first-impressions-mockup.html`, 3A): what its agents
/// route answered, in a 388 pt box 4 pt under the row, its trailing edge on the row's ⋯, always
/// within what shows of the page less 8 pt each side (spanning that where the page is drawn narrow,
/// as at the least window), and over the row when the window has no room under it. It is placed
/// once, by the tallest of its kinds in each of their modes, and its top stays there while it is
/// open; its rows scroll in what that leaves. Every kind is an equal row, the route's harnesses
/// in its order then the Shell: a tile, the name, and how it signs in as its Sign in is set, or
/// the server's few words for why it does not start. The chosen harness's options stand under its
/// own name: Mode, a pop-up of its modes (one mode shown off, with the server's note), its words
/// under it, in the warning tone where the server says the mode is risky; Sign in, a pop-up of its
/// own sign-in, the person's key and Store a Key, and whether the workspace holds the sign-in. A harness that does
/// not start says why in place of its options. The one primary names the choice (Start Claude
/// Code, Open Shell, which opens a new shell and is remembered as the kind opened last); there is
/// none where the chosen harness does not start. Store a Key opens its sheet over the panel
/// (``LoginSheet``), which the panel stays open behind; once a key is stored the panel shows what
/// the route answers then with the key chosen, says so in the place of Sign in's words, and draws
/// the primary holding the keys.
/// Where no agent starts for the person it says why, with Open Shell or Close. The window keeps
/// the keys: ↑ ↓ choose, Home and End the first and the last, typing narrows the rows past seven,
/// ⌘M opens the mode's menu, ⇥ reaches Sign in, whose menu Space or ↑ ↓ open, Return presses the
/// primary and Escape closes; so do a click outside it, the window giving up the keys or being
/// resized, and the section going.
@MainActor
package final class NewSessionPanel: NSObject {
    static let width = 388.0, gap = 4.0
    package static let inset = 8.0

    /// What shows of `page` on screen: its bounds as far as its ancestors and the window show them.
    /// `visibleRect` alone is not cut to the page's own bounds: it reaches as far as the window shows
    /// the page's ancestors, so a card laid out wider than the page widened the box past it.
    package static func shows(_ page: NSView, in window: NSWindow) -> NSRect {
        window.convertToScreen(page.convert(page.visibleRect.intersection(page.bounds), to: nil))
    }

    /// The agents route's answer as the panel takes it: what can start, or why nothing was listed.
    enum Answer: Equatable {
        case agents(WorkspaceAgents)
        case failed(String, remedy: String?)
    }

    var look: Look {
        didSet {
            view.look = look
            popover.look = look
            loginSheet?.look = look
            redraw()
        }
    }

    /// Told the start the primary made, nil for the Shell, and what the person's starts now remember.
    var onStart: @MainActor (AgentStart?, ModeMemory) -> Void = { _, _ in }
    /// Told when Open Shell is pressed where nothing starts for the person: a new shell in the
    /// workspace, nothing remembered.
    var onShell: @MainActor () -> Void = {}
    /// Told when it closes, however it closes.
    var onClose: @MainActor () -> Void = {}
    /// Told once it stands open, placed by what showed of the page then.
    package var onOpen: @MainActor () -> Void = {}
    /// Told Store a Key was chosen on the chosen harness.
    var onStoreKey: @MainActor (AgentOffer) -> Void = { _ in }
    /// Shows a pop-up's menu, the mode's or Sign in's: the library's opener, whose menu tracks the
    /// pointer; a unit test, which runs no menu tracking, reads the menu instead.
    var popUp: @MainActor (SailButton) -> Void = { MenuOpener.shared.open($0) }

    package let view: NewSessionView
    package let popover: Popover
    private let keys = NewSessionKeys()
    /// What it shows.
    package private(set) var content: NewSessionView.Content?
    package private(set) var workspace = ""
    /// The quiet line over the foot until the panel closes: a key stored, or why none can be.
    package private(set) var line: NewSessionView.Line?
    /// Sign in holds the keys (⇥), so Space and ↑ ↓ open its menu.
    package private(set) var signInFocused = false
    /// What VoiceOver was last told of a key just stored: ready on it where it became the way in,
    /// or the line where it could not be made the one in use; nil when told nothing.
    package private(set) var announced: String?

    /// The line saying a key was stored.
    package var stored: String? {
        if case let .stored(words) = line { words } else { nil }
    }
    /// The Store a Key sheet over it, while it asks.
    package private(set) var loginSheet: LoginSheet?
    /// The box on screen; the top it was placed at, kept while it is open, and the lowest its foot
    /// may go, the page's less 8 pt.
    private var placed = NSRect.zero
    private var top = 0.0, lowest = 0.0
    private var resizeWatch: (any NSObjectProtocol)?

    init(look: Look, mono: LabelFont) {
        self.look = look
        view = NewSessionView(look: look, mono: mono)
        popover = Popover(view, keys: keys, look: look, radius: NewSessionView.radius)
        super.init()
        keys.owner = self
        keys.onKeys = { [weak self] in self?.redraw() }
        popover.onDismiss = { [weak self] in self?.close() }
        view.on = { [weak self] act in self?.act(act) }
    }

    package var isOpen: Bool {
        popover.isOpen
    }

    /// Opens for `workspace` saying `answer`, under `row` (in `page`'s window, its frame there),
    /// its modes as `memory` remembers them; `anchor`, the row's +, toggles it. Its top is placed by
    /// the tallest of its kinds in each of their modes: hanging under the row where that fits, else
    /// over the row where that fits, else as high as it needs within the page; never so high that
    /// the room its shadow keeps reaches over the screen's menu bar, where AppKit would push the box
    /// down.
    func open(_ answer: Answer, workspace: String, memory: ModeMemory, under row: NSRect, page: NSView, narrow: Bool, anchor: NSView?) {
        guard let window = page.window, !isOpen else { return }
        self.workspace = workspace
        line = nil
        signInFocused = false
        content = Self.content(answer, memory: memory)
        view.direction = page.userInterfaceLayoutDirection
        let area = Self.shows(page, in: window), row = window.convertToScreen(row)
        let width = narrow ? Double(area.width) - 2 * Self.inset : min(Self.width, Double(area.width) - 2 * Self.inset)
        let rightToLeft = page.userInterfaceLayoutDirection == .rightToLeft, trailing = RowStyle.list.margin + RowStyle.list.trailing
        let wanted = narrow ? Double(area.minX) + Self.inset : rightToLeft ? Double(row.minX) + trailing : Double(row.maxX) - trailing - width
        let x = min(max(wanted, Double(area.minX) + Self.inset), Double(area.maxX) - Self.inset - width)
        let tallest = tallest(width: width)
        let ceiling = min(Double(area.maxY) - Self.inset, window.screen.map { Double($0.visibleFrame.maxY) - Popover.room } ?? .infinity)
        let under = Double(row.minY) - Self.gap, over = Double(row.maxY) + Self.gap
        lowest = Double(area.minY) + Self.inset
        top = min(under - tallest >= lowest ? under : over + tallest <= ceiling ? over + tallest : lowest + tallest, ceiling)
        draw(width: width)
        placed = NSRect(x: x, y: top - view.height, width: width, height: view.height)
        popover.open(over: window, anchor: anchor, at: placed, withKeys: false)
        view.pointed()
        keys.setAccessibilityElement(true)
        keys.setAccessibilityLabel(view.accessibilityLabel())
        resizeWatch = NotificationCenter.default.addObserver(forName: NSWindow.didResizeNotification, object: window, queue: nil) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }
        onOpen()
    }

    package func close() {
        guard isOpen else { return }
        loginSheet?.withdraw()
        line = nil
        if let resizeWatch { NotificationCenter.default.removeObserver(resizeWatch) }
        resizeWatch = nil
        popover.close()
        keys.setAccessibilityElement(false)
        onClose()
    }

    /// Asks with `sheet` on the window, the panel staying open behind it: neither the sheet taking
    /// the keys nor a click in it closes the panel, and the panel closing ends the sheet.
    func present(_ sheet: LoginSheet) {
        guard isOpen, loginSheet == nil, let window = popover.panel.parent else { return }
        loginSheet = sheet
        popover.stays = true
        sheet.onEnd = { [weak self, weak sheet] in
            guard let self, loginSheet === sheet else { return }
            loginSheet = nil
            popover.stays = false
            redraw()
        }
        redraw()
        window.beginSheet(sheet)
    }

    /// What the route answers once a key of `harness` was stored, `harness` chosen with `memory`'s
    /// way in for it, `line` said over the foot until the panel closes, the primary holding the keys;
    /// VoiceOver hears it ready on the key where the key is its way in, and hears `line` where the
    /// key is not `inUse`, as a sighted person reads it.
    func show(_ answer: Answer, memory: ModeMemory, stored line: String, harness: String, inUse: Bool) {
        guard isOpen else { return }
        var shown = Self.content(answer, memory: memory)
        if case var .offered(panel) = shown {
            panel.choose(.harness(harness))
            shown = .offered(panel)
        }
        content = shown
        self.line = .stored(line)
        signInFocused = false
        announced = nil
        redraw()
        if !inUse { announce(line) }
        guard case let .offered(panel) = shown, let offer = panel.offer(harness), let start = view.primary else { return }
        if inUse, panel.way(of: harness) == .key, let ready = NewSessionWords.ready(offer) { announce(ready) }
        NSAccessibility.post(element: start, notification: .layoutChanged, userInfo: [.uiElements: [start]])
    }

    private func announce(_ words: String) {
        announced = words
        NSAccessibility.post(element: view, notification: .announcementRequested, userInfo: [.announcement: words, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    /// `said` over the foot of what it shows now, until it closes; nil, none.
    func say(_ said: NewSessionView.Line?) {
        guard isOpen else { return }
        line = said
        redraw()
    }

    static func content(_ answer: Answer, memory: ModeMemory) -> NewSessionView.Content {
        switch answer {
        case let .failed(sentence, remedy): return .refused(sentence: sentence, remedy: remedy, shell: false)
        case let .agents(agents):
            guard let refusal = agents.refusal else { return .offered(NewSession(agents, memory: memory)) }
            return .refused(sentence: refusal.message, remedy: NewSessionWords.remedy(refusal), shell: refusal.code == .known(.notYourWorkspace))
        }
    }

    /// What of it holds the keys (``KeyHolder``): its rows, or Sign in after ⇥, while its keys view
    /// holds the window's; nothing while it does not.
    private var holder: NewSessionWords.Holder {
        KeyHolder.holds(keys) ? (signInFocused ? .signIn : .rows) : .none
    }

    /// Built `width` wide showing `shown`, what it shows by default, no taller than its top leaves.
    private func draw(width: Double, showing shown: NewSessionView.Content? = nil, cap: Double? = nil) {
        guard let content = shown ?? content else { return }
        view.show(content, workspace: workspace, line: line, veiled: loginSheet != nil, holder: holder, keys: keys, width: width, cap: cap ?? top - lowest)
    }

    /// The height of the tallest of its kinds whole, `width` wide, each harness in each of its modes.
    private func tallest(width: Double) -> Double {
        guard case let .offered(panel) = content else {
            draw(width: width, cap: .infinity)
            return view.height
        }
        return Self.everyLook(panel).map { each in
            draw(width: width, showing: .offered(each), cap: .infinity)
            return view.height
        }.max() ?? 0
    }

    /// `panel` with each of its kinds chosen, a harness in each of its modes.
    private static func everyLook(_ panel: NewSession) -> [NewSession] {
        panel.choices.flatMap { choice -> [NewSession] in
            var chosen = panel
            chosen.choose(choice)
            guard case let .harness(harness) = choice, let offer = panel.offer(harness) else { return [chosen] }
            return offer.modes.isEmpty ? [chosen] : offer.modes.map { mode in
                var each = chosen
                each.choose(mode: mode.mode)
                return each
            }
        }
    }

    /// Drawn again where it is, its top where it was placed, its foot following what it shows.
    private func redraw() {
        guard isOpen else { return }
        draw(width: Double(placed.width))
        placed = NSRect(x: placed.minX, y: top - view.height, width: placed.width, height: view.height)
        popover.place(placed)
    }

    /// What the panel's controls do; nothing while its sheet asks over the window, as the window's
    /// own controls do nothing then.
    private func act(_ act: NewSessionView.Act) {
        guard isOpen, loginSheet == nil, let content else { return }
        switch (act, content) {
        case let (.choose(choice), .offered(panel)):
            var chosen = panel
            chosen.choose(choice)
            self.content = .offered(chosen)
            signInFocused = false
        case let (.mode(mode), .offered(panel)):
            var chosen = panel
            chosen.choose(mode: mode)
            self.content = .offered(chosen)
        case let (.way(way), .offered(panel)):
            var chosen = panel
            chosen.choose(way: way)
            self.content = .offered(chosen)
        case let (.storeKey, .offered(panel)):
            guard case let .harness(harness) = panel.chosen, let offer = panel.offer(harness) else { return }
            return onStoreKey(offer)
        case (.start, _):
            return start()
        case (.shell, _):
            close()
            return onShell()
        case (.close, _):
            return close()
        default:
            return
        }
        redraw()
    }

    /// The primary: the chosen harness started, or the Shell opened, the person's starts
    /// remembering it; nothing where the chosen harness does not start.
    private func start() {
        guard case var .offered(panel) = content else { return }
        let start = panel.start()
        guard start != nil || panel.readiness == .shell else { return }
        close()
        onStart(start, panel.memory)
    }

    /// The chosen harness's mode menu, ⌘M; none for a harness of one mode.
    private func openModes() {
        guard let button = view.modes else { return }
        popUp(button)
    }

    /// ⇥: Sign in takes the keys, or gives them back to the rows; nothing where none shows.
    private func tab() {
        guard view.signIn != nil || signInFocused else { return }
        signInFocused.toggle()
        redraw()
    }

    /// Past seven rows, what is typed while the rows hold the keys narrows them, ⌫ widens them
    /// again; nothing typed is passed on there. Whether `event` was such a key.
    private func narrow(_ event: NSEvent, held: NSEvent.ModifierFlags) -> Bool {
        guard !signInFocused, held.isEmpty || held == .shift, case var .offered(panel) = content, panel.filters else { return false }
        let typed: String
        if event.keyCode == 51 {
            guard !panel.filter.isEmpty else { return true }
            typed = String(panel.filter.dropLast())
        } else {
            guard let words = event.characters, !words.isEmpty, words.unicodeScalars.allSatisfy({ ![.control, .privateUse].contains($0.properties.generalCategory) }) else { return false }
            typed = panel.filter + words
        }
        if panel.narrow(to: typed) {
            content = .offered(panel)
            redraw()
        }
        return true
    }

    /// A key while open: ↑ ↓ choose, Home and End the first and the last, or ↑ ↓ open Sign in's
    /// menu while it holds the keys, as Space does; ⇥ moves between them; ⌘M the modes, Return
    /// presses the primary, Escape closes; past seven rows, typing narrows them.
    fileprivate func key(_ event: NSEvent) -> Bool {
        guard isOpen else { return false }
        let held = event.modifierFlags.intersection([.command, .option, .control, .shift])
        if held == .command, event.charactersIgnoringModifiers?.lowercased() == "m" {
            openModes()
            return true
        }
        if event.keyCode == 48, held.isEmpty || held == .shift {
            tab()
            return true
        }
        if narrow(event, held: held) { return true }
        guard held.isEmpty else { return false }
        if signInFocused, [49, 125, 126].contains(event.keyCode), let button = view.signIn {
            popUp(button)
            return true
        }
        switch event.keyCode {
        case 115, 119, 125, 126:
            guard case var .offered(panel) = content else { return true }
            let rows = panel.choices.count
            panel.move(by: [115: -rows, 119: rows, 125: 1, 126: -1][event.keyCode] ?? 0)
            content = .offered(panel)
            signInFocused = false
            redraw()
        case 36, 76:
            start()
        case 53:
            close()
        default:
            return false
        }
        return true
    }
}

/// Takes the window's keys while the panel is open; VoiceOver reads it as the panel.
@MainActor
final class NewSessionKeys: NSView {
    weak var owner: NewSessionPanel?
    /// What holds the window's keys may have changed (a sheet over it, it taking them or giving them
    /// up): the panel names its keys only while it holds them.
    var onKeys: @MainActor () -> Void = {}
    private lazy var keysWatch = HolderWatch { [weak self] in self?.onKeys() }

    override init(frame: NSRect) {
        super.init(frame: frame)
        setAccessibilityElement(false)
        setAccessibilityRole(.popover)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func accessibilityChildren() -> [Any]? {
        guard owner?.isOpen == true else { return nil }
        return owner.map { [$0.view] }
    }

    override var acceptsFirstResponder: Bool {
        true
    }

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        super.viewWillMove(toWindow: newWindow)
        keysWatch.watch(newWindow)
    }

    /// While its panel is closed it keeps any key it is still handed: passed on, a Return would
    /// press the list's Open.
    override func keyDown(with event: NSEvent) {
        guard owner?.isOpen == true else { return }
        if owner?.key(event) != true { super.keyDown(with: event) }
    }

    /// ⌘M is the panel's while it holds the keys, not the window's Minimize.
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard window?.firstResponder === self, owner?.key(event) == true else { return super.performKeyEquivalent(with: event) }
        return true
    }

    override func cancelOperation(_: Any?) {
        owner?.close()
    }
}

/// The panel's box, on the popover's surface with a strong hairline edge and 8 pt corners: its
/// title, the stopped workspace's line, the kinds as equal rows, the chosen harness's options under
/// its name, a key just stored said in one line, and the foot with the keys and the primary; or,
/// where nothing starts for the person, why, and one button. The rows scroll where the box would
/// be taller than it may be, the chosen one kept in view; the options and the foot always show.
/// Built whole by ``show(_:workspace:line:veiled:holder:keys:width:cap:)``, every frame set there,
/// so no layout pass changes what it shows; mirrored right to left.
@MainActor
package final class NewSessionView: NSView {
    static let radius = 8.0, padding = 14.0, row = 46.0, rowGap = 2.0, listInset = 6.0, gap = 10.0, about = 17.0, labelWidth = 62.0, mark = 13.0

    /// What it shows: the route's harnesses and the Shell, or why no agent starts and whether the
    /// workspace still opens as a shell for the person.
    package enum Content: Equatable {
        case offered(NewSession)
        case refused(sentence: String, remedy: String?, shell: Bool)
    }

    enum Act: Equatable {
        case choose(NewSession.Choice), mode(String), way(Way), storeKey, start, shell, close
    }

    var look: Look {
        didSet { needsDisplay = true }
    }

    var on: @MainActor (Act) -> Void = { _ in }
    var direction: NSUserInterfaceLayoutDirection = .leftToRight

    private let mono: LabelFont
    package private(set) var height = 0.0
    /// The rows, top to bottom, the chosen one marked.
    package private(set) var rows: [NewSessionRow] = []
    /// The primary, which names the choice, nil where the chosen harness does not start; or Open
    /// Shell or Close where nothing starts for the person.
    package private(set) var primary: SailButton?
    package private(set) var secondary: SailButton?
    /// The chosen harness's mode pop-up, nil for a harness of one mode or none chosen.
    package private(set) var modes: SailButton?
    /// The chosen harness's Sign in pop-up: its own sign-in, the person's key, Store a Key; nil
    /// where it does not start.
    package private(set) var signIn: SailButton?
    /// The mode's words said in the warning tone, after the warning mark; nil where none warns.
    package private(set) var warned: String?
    /// Under its sheet's veil, its primary elsewhere: the sheet's.
    package private(set) var veiled = false
    /// What of the panel holds the keys (``KeyHolder``), as last shown: Sign in is drawn with the
    /// ring a control holding them has; the primary with Return's while the rows hold them.
    private var holder = NewSessionWords.Holder.none
    /// The veil drawn over it while its sheet asks.
    private var veil: NSView?
    /// The rows' scroll view, kept across builds so a scroll is kept as it is drawn again.
    package let list: QuietScrollView
    /// Where the pointer is in its window while the app is active and its window key; nil, not
    /// known. The tests and the drive place it.
    package var pointer: (@MainActor () -> NSPoint?)?
    /// The hairlines across it, the stopped line's square, the stored line's and the warning mark,
    /// as last laid out.
    private var hairlines: [Double] = []
    private var square: NSRect?
    private var lineSquare: NSRect?
    private var warning: NSRect?
    private var lineFailed = false

    /// The quiet line over the foot: a key stored, or why none can be stored now.
    package enum Line: Equatable {
        case stored(String)
        case unread(String)

        /// Its words, and whether they say why no key can be stored.
        var said: (words: String, failed: Bool) {
            switch self {
            case let .stored(words): (words, false)
            case let .unread(words): (words, true)
            }
        }
    }
    /// The width it is being built at.
    private var boxWidth = 0.0

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        list = QuietScrollView(look: look)
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    /// Every word it shows, top to bottom, as the drive reads them.
    package var words: [String] {
        Self.texts(self)
    }

    private static func texts(_ view: NSView) -> [String] {
        ((view as? NSTextField).map { [$0.stringValue] } ?? (view as? NSButton).map { [$0.title] } ?? []) + view.subviews.flatMap(texts)
    }

    /// Builds it for `content` in workspace `workspace`, `width` wide and no taller than `cap`, its
    /// rows scrolling in what the rest leaves, saying `line` over the foot; `veiled` while its sheet
    /// asks: its primary outlined, as a row's Open is under the panel, the sheet's Store the one
    /// primary on screen, and the sheet's veil over it. The foot names the keys of `holder`, the
    /// primary wears Return's ring once a key is stored only while `holder` is the rows (nothing
    /// under a sheet), and names its ⏎ while `keys`, whose Return presses it, holds the keys. The
    /// rows keep their scroll, the chosen one scrolled into view, and the one under the pointer,
    /// else the one hovered before, is hovered.
    func show(_ content: Content, workspace: String, line: Line?, veiled: Bool, holder: NewSessionWords.Holder, keys: NSView, width: Double, cap: Double) {
        let hovered = rows.first(where: \.hovered)?.choice, scrolled = Double(list.contentView.bounds.minY)
        build(content, workspace: workspace, line: line, veiled: veiled, holder: holder, keys: keys, width: width, rows: .infinity)
        if height > cap, list.superview === self {
            build(content, workspace: workspace, line: line, veiled: veiled, holder: holder, keys: keys, width: width, rows: max(Double(list.frame.height) - (height - cap), Self.row + 2 * Self.listInset))
        }
        reveal(scrolled: scrolled, hovered: hovered)
    }

    private func build(_ content: Content, workspace: String, line: Line?, veiled: Bool, holder: NewSessionWords.Holder, keys: NSView, width: Double, rows room: Double) {
        self.veiled = veiled
        self.holder = holder
        subviews.forEach { $0.removeFromSuperview() }
        rows = []
        primary = nil
        secondary = nil
        modes = nil
        signIn = nil
        warned = nil
        hairlines = []
        square = nil
        lineSquare = nil
        warning = nil
        boxWidth = width
        let edge = Look.hairline, inner = width - 2 * (Self.padding + edge)
        let title = NSMutableAttributedString(attributedString: Self.text(NewSessionWords.title(workspace).lead, .systemFont(ofSize: Look.Step.callout.points, weight: .semibold), look.text, direction: direction))
        title.append(Self.text(" " + NewSessionWords.title(workspace).place, .systemFont(ofSize: Look.Step.callout.points), look.textTertiary, direction: direction))
        setAccessibilityLabel(title.string)
        var y = edge + 12
        y = place(Self.label(title.cut(.byTruncatingTail)), x: edge + Self.padding, y: y, width: inner) + 10
        switch content {
        case let .offered(panel):
            if !panel.agents.running {
                square = quietLine(NewSessionWords.stopped(workspace), y: &y, width: width)
            }
            hairlines.append(y)
            y = list(panel, workspace: workspace, y: y, width: width, room: room)
            y = options(panel, workspace: workspace, line: line, y: y, width: width)
            hairlines.append(y)
            let start = NewSessionWords.primary(panel.readiness).map { title in
                SailButton(title, kind: veiled ? .secondary : .primary, size: .small, look: look, target: self, action: #selector(pressedStart(_:)))
            }
            start?.hint("\r", through: keys, mono: mono)
            start?.holdsKeys = line.map { if case .stored = $0 { true } else { false } } == true && holder == .rows
            primary = start
            let hint = { (room: Double) in
                self.hint(NewSessionWords.hint(holder, modes: self.modes != nil, typed: panel.filter) { Double(self.hint($0).fittingSize.width) <= room })
            }
            y = foot(start, hint: hint, y: y, width: width)
        case let .refused(sentence, remedy, shell):
            hairlines.append(y)
            y += 12
            y = place(Self.wrapping(sentence, .systemFont(ofSize: Look.Step.callout.points), look.text, width: inner, direction: direction), x: edge + Self.padding, y: y, width: inner)
            if let remedy {
                y = place(Self.wrapping(remedy, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, width: inner, direction: direction), x: edge + Self.padding, y: y + 10, width: inner)
            }
            y += 12
            said(line, y: &y, width: width)
            hairlines.append(y)
            let button = SailButton(shell ? NewSessionWords.openShell : "Close", kind: .secondary, size: .small, look: look, target: self, action: shell ? #selector(pressedShell(_:)) : #selector(pressedClose(_:)))
            secondary = button
            y = foot(button, hint: nil, y: y, width: width)
        }
        height = y + edge
        veil = veiled ? addVeil() : nil
        needsDisplay = true
    }

    /// The sheet's veil laid over the whole box, above its controls. AppKit dims the window under a
    /// sheet inside the window itself, which a child panel floats over, so the panel lays the
    /// page's veil over itself; it takes no click.
    private func addVeil() -> NSView {
        let veil = Veil(frame: NSRect(x: 0, y: 0, width: boxWidth, height: height))
        veil.wantsLayer = true
        veil.layer?.backgroundColor = NSColor(Self.veil(look)).cgColor
        veil.layer?.cornerRadius = Self.radius
        addSubview(veil)
        return veil
    }

    /// The page's veil (`login-store-mockup.html`): ink at 18 % over paper, black at 40 % in the dark.
    static func veil(_ look: Look) -> Tint {
        look.palette.isDark ? Tint(0x000000, alpha: 0.4) : Tint(0x0A0E11, alpha: 0.18)
    }

    /// The rows from `y` in their scroll view, `room` tall at most and all of them where they fit,
    /// the chosen one's bar at the list's edge; where it ends.
    private func list(_ panel: NewSession, workspace: String, y: Double, width: Double, room: Double) -> Double {
        let edge = Look.hairline, inner = width - 2 * edge
        let document = NewSessionList(look: look, rightToLeft: direction == .rightToLeft)
        var top = Self.listInset
        for choice in panel.choices {
            let offer: AgentOffer? = if case let .harness(harness) = choice { panel.offer(harness) } else { nil }
            let line = offer.map { NewSessionWords.line($0, way: panel.way(of: $0.harness) ?? .signIn) } ?? NewSessionWords.shellLine(workspace)
            let tile: NewSessionRow.Tile = offer.map { .monogram(NewSessionWords.monogram($0.display)) } ?? .icon(.terminal)
            let chosen = panel.chosen == choice
            let row = NewSessionRow(choice, name: panel.name(of: choice), line: line, tile: tile, chosen: chosen, off: offer?.refusal != nil, look: look, mono: mono, direction: direction)
            row.onChoose = { [weak self] in self?.on(.choose(choice)) }
            row.frame = NSRect(x: Self.listInset, y: top, width: inner - 2 * Self.listInset, height: Self.row)
            row.lay()
            document.addSubview(row)
            rows.append(row)
            if chosen { document.bar = NSRect(x: document.rightToLeft ? inner - RowStyle.barWidth : 0, y: top + (Self.row - RowStyle.list.bar) / 2, width: RowStyle.barWidth, height: RowStyle.list.bar) }
            top += Self.row + Self.rowGap
        }
        document.frame = NSRect(x: 0, y: 0, width: inner, height: top - Self.rowGap + Self.listInset)
        list.look = look
        list.userInterfaceLayoutDirection = direction
        list.documentView = document
        list.frame = NSRect(x: edge, y: y, width: inner, height: min(Double(document.frame.height), room))
        addSubview(list)
        return y + Double(list.frame.height)
    }

    /// The rows scrolled back to `scrolled` as far as they still reach, the chosen one into view;
    /// the row under the pointer hovered, else the one `hovered` before.
    private func reveal(scrolled: Double, hovered: NewSession.Choice?) {
        guard list.superview === self, let document = list.documentView else { return }
        list.contentView.scroll(to: NSPoint(x: 0, y: min(scrolled, max(Double(document.frame.height) - Double(list.contentSize.height), 0))))
        list.reflectScrolledClipView(list.contentView)
        if let chosen = rows.first(where: \.chosen) { chosen.scrollToVisible(chosen.bounds.insetBy(dx: 0, dy: -Self.listInset)) }
        guard pointerNow == nil else { return pointed() }
        for row in rows {
            row.hovered = row.choice == hovered
        }
    }

    private var pointerNow: NSPoint? {
        if let pointer { return pointer() }
        guard let window, NSApplication.shared.isActive, (window.parent ?? window).isKeyWindow else { return nil }
        return window.mouseLocationOutsideOfEventStream
    }

    /// The row under the pointer hovered and no other, where the pointer is known.
    package func pointed() {
        guard let point = pointerNow, let content = window?.contentView else { return }
        let hit = content.hitTest(content.superview?.convert(point, from: nil) ?? point)
        for row in rows {
            row.hovered = hit.map { $0 === row || $0.isDescendant(of: row) } ?? false
        }
    }

    /// The chosen harness's options under its name, from `y`: its Mode and Sign in with their
    /// words, `line` in the place of Sign in's, or why it does not start; none for the Shell. Where
    /// they end.
    private func options(_ panel: NewSession, workspace: String, line: Line?, y top: Double, width: Double) -> Double {
        guard case let .harness(harness) = panel.chosen, let offer = panel.offer(harness) else { return top }
        hairlines.append(top)
        let edge = Look.hairline, x = edge + Self.padding, inner = width - 2 * x
        let eyebrow = Self.label(Draw.text(offer.display, mono.font(Look.Step.caption.points) as NSFont, look.textTertiary, tracking: Look.Tracking.eyebrow, upper: true).cut(.byTruncatingTail))
        var y = place(eyebrow, x: x, y: top + 12, width: min(Double(eyebrow.fittingSize.width), inner)) + Self.gap
        if case let .refused(refusal) = panel.readiness {
            y = place(said(refusal.message, width: inner), x: x, y: y, width: inner)
            if let remedy = NewSessionWords.remedy(refusal) { y = place(quiet(remedy, width: inner), x: x, y: y + 4, width: inner) }
            return y + 14
        }
        let mode = panel.mode(of: harness) ?? offer.modeDefault, way = panel.way(of: harness) ?? .signIn
        let after = x + Self.labelWidth + 10, room = x + inner - after
        if !offer.modes.isEmpty {
            let about = NewSessionWords.about(offer, mode: mode)
            y = modeLine(offer, mode: mode, name: panel.risky ? NewSessionWords.modeWarning : NewSessionWords.mode, x: x, after: after, y: y, room: room)
            y = help(about, warns: panel.risky, x: after, y: y + 4, width: room) + Self.gap
        }
        y = signInLine(offer, way: way, x: x, after: after, y: y, room: room)
        return signInWords(offer, way: way, line: line, workspace: workspace, x: after, y: y + 4, width: room) + 14
    }

    /// Under Sign in, a slot as tall as the taller of its two ways' words, one line at least, so
    /// neither choosing a way nor a key stored changes the box's height: the words of `way`, or,
    /// while `line` says a key was stored or why none can be, that line after its square in their
    /// place, cut at its tail where it is longer; where the slot ends.
    private func signInWords(_ offer: AgentOffer, way: Way, line: Line?, workspace: String, x: Double, y: Double, width: Double) -> Double {
        let helps = [Way.signIn, .key].compactMap { NewSessionWords.signInHelp(offer, way: $0, in: workspace) }
        let slot = max(helps.map { Self.height(of: quiet($0, width: width), width: width) }.max() ?? 0, Self.about)
        guard let line else {
            if let words = NewSessionWords.signInHelp(offer, way: way, in: workspace) { _ = place(quiet(words, width: width), x: x, y: y, width: width) }
            return y + slot
        }
        let (words, failed) = line.said
        let after = 6.0 + 8, label = said(words, width: width - after)
        label.cell?.truncatesLastVisibleLine = true
        label.frame = mirrored(NSRect(x: x + after, y: y, width: width - after, height: min(Self.height(of: label, width: width - after), slot)))
        addSubview(label)
        lineFailed = failed
        lineSquare = mirrored(NSRect(x: x, y: y + (Self.about - 6) / 2, width: 6, height: 6))
        return y + slot
    }

    /// `words` under a pop-up at `x`, `width` wide: quiet, or in the warning tone after its mark
    /// where they warn; where they end.
    private func help(_ words: String, warns: Bool, x: Double, y: Double, width: Double) -> Double {
        guard warns else { return place(quiet(words, width: width), x: x, y: y, width: width) }
        let after = Self.mark + 6
        warned = words
        warning = mirrored(NSRect(x: x, y: y + (Self.about - Self.mark) / 2, width: Self.mark, height: Self.mark))
        let warn = Self.wrapping(words, .systemFont(ofSize: Look.Step.footnote.points), Tint(look.warning), width: width - after, direction: direction, lineHeight: Self.about)
        return place(warn, x: x + after, y: y, width: width - after)
    }

    private func lineLabel(_ words: String) -> NSTextField {
        Self.label(Self.text(words, .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary, direction: direction).cut(.byTruncatingTail))
    }

    /// `words` as a line's label at `x`, centred on a small button's height at `y`.
    private func placeLabel(_ words: String, x: Double, y: Double) {
        let label = lineLabel(words), size = label.fittingSize
        label.frame = mirrored(NSRect(x: x, y: y + (ButtonSize.small.height - Double(size.height)) / 2, width: min(Double(size.width), Self.labelWidth), height: Double(size.height)))
        addSubview(label)
    }

    /// A small pop-up titled `title`, of `menu`, at `x` and `y` within `room`, named `name` to
    /// VoiceOver with its title as its value; with no menu, shown off.
    private func popUp(_ title: String, menu: NSMenu?, name: String, x: Double, y: Double, room: Double) -> SailButton {
        let button = SailButton(title, kind: .secondary, size: .small, popUp: menu != nil, look: look, target: MenuOpener.shared, action: #selector(MenuOpener.open(_:)))
        button.menu = menu
        button.isEnabled = menu != nil
        button.setAccessibilityLabel(name)
        button.setAccessibilityValue(title)
        button.userInterfaceLayoutDirection = direction
        let box = NSRect(x: x, y: y, width: min(Double(button.intrinsicContentSize.width), room), height: ButtonSize.small.height)
        button.frame = mirrored(box.insetBy(dx: -Look.haloRoom, dy: -Look.haloRoom))
        addSubview(button)
        return button
    }

    /// "Mode" and the pop-up of the server's labels, shown off for a harness of one mode, named
    /// `name` to VoiceOver; where the line ends.
    private func modeLine(_ offer: AgentOffer, mode: String, name: String, x: Double, after: Double, y: Double, room: Double) -> Double {
        placeLabel(NewSessionWords.mode, x: x, y: y)
        let title = offer.modes.first { $0.mode == mode }?.label ?? mode
        guard offer.modes.count > 1 else {
            _ = popUp(title, menu: nil, name: name, x: after, y: y, room: room)
            return y + ButtonSize.small.height
        }
        let menu = NSMenu()
        for each in offer.modes {
            let item = NSMenuItem(title: each.label, action: #selector(choseMode(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = each.mode
            item.state = each.mode == mode ? .on : .off
            menu.addItem(item)
        }
        modes = popUp(title, menu: menu, name: name, x: after, y: y, room: room)
        return y + ButtonSize.small.height
    }

    /// "Sign in" and its pop-up, titled by how the harness starts now: its own sign-in, the person's
    /// key where they have one, a separator and Store a Key; where the line ends.
    private func signInLine(_ offer: AgentOffer, way: Way, x: Double, after: Double, y: Double, room: Double) -> Double {
        placeLabel(NewSessionWords.signIn, x: x, y: y)
        let account = offer.signIn?.name, key = NewSessionWords.key(offer)
        let menu = NSMenu()
        for (title, chose) in [(account, Way.signIn), (key, Way.key)] {
            guard let title, !title.isEmpty else { continue }
            let item = NSMenuItem(title: title, action: #selector(choseWay(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = chose.rawValue
            item.state = chose == way ? .on : .off
            menu.addItem(item)
        }
        menu.addItem(.separator())
        let store = NSMenuItem(title: NewSessionWords.storeKey, action: #selector(choseStoreKey(_:)), keyEquivalent: "")
        store.target = self
        menu.addItem(store)
        let button = popUp(NewSessionWords.way(offer, way), menu: menu, name: NewSessionWords.signIn, x: after, y: y, room: room)
        button.holdsKeys = holder == .signIn
        signIn = button
        return y + ButtonSize.small.height
    }

    /// `line`, if any, said at `y` after its square: the success's for a store, the error's for none.
    private func said(_ line: Line?, y: inout Double, width: Double) {
        guard let said = line?.said else { return }
        lineFailed = said.failed
        lineSquare = quietLine(said.words, y: &y, width: width)
    }

    /// A quiet line across the box at `y`, under a hairline, after a square: a fact said, not a
    /// refusal. `y` moves past it; the square's place is returned.
    private func quietLine(_ said: String, y: inout Double, width: Double) -> NSRect {
        let edge = Look.hairline, start = edge + Self.padding + 6 + 8
        hairlines.append(y)
        let words = Self.wrapping(said, .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary, width: width - start - Self.padding - edge, direction: direction)
        let top = y + 7
        y = place(words, x: start, y: top, width: width - start - Self.padding - edge) + 8
        return mirrored(NSRect(x: edge + Self.padding, y: top + (min(Double(words.frame.height), Self.about) - 6) / 2, width: 6, height: 6))
    }

    /// The foot's keys, quiet mono, ordered in the box's own direction whatever was typed.
    private func hint(_ words: String) -> NSTextField {
        Self.label(Self.text(words, mono.font(Look.Step.micro.points, .regular) as NSFont, look.textTertiary, direction: direction, writing: direction == .rightToLeft ? .rightToLeft : .leftToRight).cut(.byTruncatingTail))
    }

    /// The foot at `y`: the keys, as `hint` words them for the room the button leaves, at its leading
    /// end, its button, if any, at the trailing; a small button tall either way; where it ends.
    private func foot(_ button: SailButton?, hint: ((Double) -> NSTextField)?, y: Double, width: Double) -> Double {
        let edge = Look.hairline, size = button?.intrinsicContentSize ?? .zero, room = Look.haloRoom
        let box = NSRect(x: width - edge - 12 - Double(size.width), y: y + 10, width: Double(size.width), height: ButtonSize.small.height)
        if let button {
            button.frame = mirrored(box.insetBy(dx: -room, dy: -room))
            button.userInterfaceLayoutDirection = direction
            addSubview(button)
        }
        if let worded = hint {
            let room = Double(box.minX) - edge - 12 - 12, hint = worded(room), hintSize = hint.fittingSize
            let hintWidth = min(Double(hintSize.width), room)
            hint.frame = mirrored(NSRect(x: edge + 12, y: Double(box.midY) - Double(hintSize.height) / 2, width: max(hintWidth, 0), height: Double(hintSize.height)))
            addSubview(hint)
        }
        return Double(box.maxY) + 10
    }

    /// `view` added at `x`, `y`, `width` wide at its own height for that width; where it ends.
    private func place(_ view: NSTextField, x: Double, y: Double, width: Double) -> Double {
        let height = Self.height(of: view, width: width)
        view.frame = mirrored(NSRect(x: x, y: y, width: width, height: height))
        addSubview(view)
        return y + height
    }

    private func said(_ words: String, width: Double) -> NSTextField {
        Self.wrapping(words, .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary, width: width, direction: direction, lineHeight: Self.about)
    }

    private func quiet(_ words: String, width: Double) -> NSTextField {
        Self.wrapping(words, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, width: width, direction: direction, lineHeight: Self.about)
    }

    /// `rect`, laid out left to right in the box, turned about its middle right to left.
    private func mirrored(_ rect: NSRect) -> NSRect {
        guard direction == .rightToLeft else { return rect }
        return NSRect(x: boxWidth - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }

    static func text(_ words: String, _ font: NSFont, _ ink: Tint, direction: NSUserInterfaceLayoutDirection, lineHeight: Double? = nil, writing: NSWritingDirection = .natural) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.alignment = direction == .rightToLeft ? .right : .left
        style.baseWritingDirection = writing
        if let lineHeight {
            style.minimumLineHeight = lineHeight
            style.maximumLineHeight = lineHeight
        }
        return NSAttributedString(string: words, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style])
    }

    static func label(_ words: NSAttributedString) -> NSTextField {
        let label = NSTextField(labelWithAttributedString: words)
        label.lineBreakMode = .byTruncatingTail
        label.maximumNumberOfLines = 1
        return label
    }

    static func wrapping(_ words: String, _ font: NSFont, _ ink: Tint, width: Double, direction: NSUserInterfaceLayoutDirection, lineHeight: Double? = nil) -> NSTextField {
        let label = NSTextField(wrappingLabelWithString: "")
        label.attributedStringValue = text(words, font, ink, direction: direction, lineHeight: lineHeight)
        label.maximumNumberOfLines = 0
        label.lineBreakMode = .byWordWrapping
        label.preferredMaxLayoutWidth = width
        return label
    }

    /// A label's height `width` wide: its words wrapped, whole points.
    static func height(of label: NSTextField, width: Double) -> Double {
        guard label.maximumNumberOfLines != 1, let cell = label.cell else { return ceil(Double(label.fittingSize.height)) }
        return ceil(Double(cell.cellSize(forBounds: NSRect(x: 0, y: 0, width: width, height: 10000)).height))
    }

    @objc private func pressedStart(_: Any?) {
        on(.start)
    }

    @objc private func pressedShell(_: Any?) {
        on(.shell)
    }

    @objc private func pressedClose(_: Any?) {
        on(.close)
    }

    @objc private func choseStoreKey(_: NSMenuItem) {
        on(.storeKey)
    }

    @objc private func choseWay(_ item: NSMenuItem) {
        guard let way = (item.representedObject as? String).flatMap(Way.init(rawValue:)) else { return }
        on(.way(way))
    }

    @objc private func choseMode(_ item: NSMenuItem) {
        guard let mode = item.representedObject as? String else { return }
        on(.mode(mode))
    }

    package override func draw(_: NSRect) {
        let box = bounds.insetBy(dx: 0.5, dy: 0.5)
        let shape = NSBezierPath(roundedRect: box, xRadius: Self.radius - 0.5, yRadius: Self.radius - 0.5)
        NSColor(look.surfacePop).setFill()
        shape.fill()
        NSColor(look.separator).setFill()
        for line in hairlines {
            NSRect(x: Look.hairline, y: line, width: Double(bounds.width) - 2 * Look.hairline, height: Look.hairline).fill()
        }
        if let square {
            NSColor(look.textTertiary).setFill()
            square.fill()
        }
        if let lineSquare {
            NSColor(Tint(lineFailed ? look.error : look.success)).setFill()
            lineSquare.fill()
        }
        if let warning { Draw.icon(.warning, in: warning, ink: Tint(look.warning)) }
        NSColor(look.separatorStrong).setStroke()
        shape.lineWidth = 1
        shape.stroke()
    }
}

/// The panel's rows as its scroll view holds them, the chosen one's accent bar at their edge.
@MainActor
final class NewSessionList: NSView {
    private let look: Look
    let rightToLeft: Bool
    var bar: NSRect?

    init(look: Look, rightToLeft: Bool) {
        self.look = look
        self.rightToLeft = rightToLeft
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_: NSRect) {
        guard let bar else { return }
        NSColor(look.accent).setFill()
        let half = RowStyle.barWidth / 2
        NSBezierPath(roundedRect: bar, xRadius: half, yRadius: half).fill()
        NSRect(x: rightToLeft ? Double(bar.maxX) - half : Double(bar.minX), y: Double(bar.minY), width: half, height: Double(bar.height)).fill()
    }
}

extension NewSessionList: Marked {
    package var drawnMarks: [Mark] {
        bar == nil ? [] : look.marks(line: Tint(look.accent))
    }
}

/// One kind as a row of the panel, 46 pt: its tile (a monogram, or the Shell's terminal), its name
/// and one line under it; the chosen one's name at full weight, its tile outlined in ink and the
/// accent check at its end; a kind that does not start dimmed. The pointer over it, through its
/// tracking area, sets its name at full weight, as a list row's is, and outlines its tile in ink
/// as the chosen one's is: no fill, no bar, no check. A click chooses it.
@MainActor
package final class NewSessionRow: NSView {
    /// What its tile shows.
    package enum Tile: Equatable {
        case monogram(String), icon(LineIcon)
    }

    static let tile = 28.0, check = 15.0, inset = 8.0, gap = 10.0

    package let choice: NewSession.Choice
    package let name: NSTextField
    package let line: NSTextField
    var onChoose: @MainActor () -> Void = {}
    private let words: String
    package let tile: Tile
    private let off: Bool
    private let look: Look
    private let mono: LabelFont
    private let direction: NSUserInterfaceLayoutDirection
    /// Under the pointer: its name at full weight, its tile outlined in ink.
    package var hovered = false {
        didSet {
            guard hovered != oldValue else { return }
            named()
            lay()
            needsDisplay = true
        }
    }

    init(_ choice: NewSession.Choice, name words: String, line said: String, tile: Tile, chosen: Bool, off: Bool, look: Look, mono: LabelFont, direction: NSUserInterfaceLayoutDirection) {
        self.choice = choice
        self.words = words
        self.tile = tile
        self.off = off
        self.look = look
        self.mono = mono
        self.direction = direction
        name = NewSessionView.label(NSAttributedString())
        line = NewSessionView.label(NewSessionView.text(said, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, direction: direction).cut(.byTruncatingTail))
        super.init(frame: .zero)
        addSubview(name)
        addSubview(line)
        setAccessibilityElement(true)
        setAccessibilityRole(.radioButton)
        setAccessibilityLabel(said.isEmpty ? words : "\(words), \(said)")
        setAccessibilityValue(chosen ? 1 : 0)
        named()
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self))
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    package var chosen: Bool {
        accessibilityValue() as? Int == 1
    }

    /// Its tile's paint: in ink while it is chosen or under the pointer, else the quiet well.
    package var well: InkMark {
        chosen || hovered ? look.wellChosen : look.well
    }

    private func named() {
        let strong = RowPaint(RowState(chosen: chosen, hovered: hovered)).strong
        name.attributedStringValue = NewSessionView.text(words, .systemFont(ofSize: Look.Step.callout.points, weight: strong ? .semibold : .medium), off ? look.textTertiary : look.text, direction: direction).cut(.byTruncatingTail)
    }

    /// The tile 8 pt in, the name over its line after it, the check's room at the end; mirrored
    /// right to left.
    func lay() {
        let width = Double(bounds.width), height = Double(bounds.height)
        let start = Self.inset + Self.tile + Self.gap, room = max(width - start - Self.gap - Self.check - Self.gap, 0)
        let nameHeight = Double(name.fittingSize.height), lineHeight = Double(line.fittingSize.height), top = (height - nameHeight - 2 - lineHeight) / 2
        name.frame = mirrored(NSRect(x: start, y: top, width: min(Double(name.fittingSize.width), room), height: nameHeight))
        line.frame = mirrored(NSRect(x: start, y: top + nameHeight + 2, width: min(Double(line.fittingSize.width), room), height: lineHeight))
    }

    private func mirrored(_ rect: NSRect) -> NSRect {
        guard direction == .rightToLeft else { return rect }
        return NSRect(x: Double(bounds.width) - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }

    package override func draw(_: NSRect) {
        let ink = off ? look.textTertiary : well.ink
        let box = mirrored(NSRect(x: Self.inset, y: (Double(bounds.height) - Self.tile) / 2, width: Self.tile, height: Self.tile))
        Draw.box(box, radius: Look.contentRadius, fill: well.fill, edge: well.edge)
        switch tile {
        case let .monogram(letters):
            let text = Draw.text(letters, mono.font(Look.Step.micro.points, .semibold) as NSFont, ink), size = text.size()
            text.draw(at: NSPoint(x: Double(box.midX) - Double(size.width) / 2, y: Double(box.midY) - Double(size.height) / 2))
        case let .icon(icon):
            Draw.icon(icon, in: box.insetBy(dx: (Self.tile - Self.check) / 2, dy: (Self.tile - Self.check) / 2), ink: ink)
        }
        guard chosen else { return }
        Draw.icon(.check, in: mirrored(NSRect(x: Double(bounds.width) - Self.gap - Self.check, y: (Double(bounds.height) - Self.check) / 2, width: Self.check, height: Self.check)), ink: Tint(look.accent))
    }

    package override func mouseEntered(with _: NSEvent) {
        hovered = true
    }

    package override func mouseExited(with _: NSEvent) {
        hovered = false
    }

    package override func mouseDown(with _: NSEvent) {}

    package override func mouseUp(with event: NSEvent) {
        guard bounds.contains(convert(event.locationInWindow, from: nil)) else { return }
        onChoose()
    }

    package override func acceptsFirstMouse(for _: NSEvent?) -> Bool {
        true
    }

    package override func accessibilityPerformPress() -> Bool {
        onChoose()
        return true
    }
}

extension NewSessionRow: Marked {
    package var drawnMarks: [Mark] {
        chosen ? look.marks(line: Tint(look.accent)) : []
    }
}

extension ModeMemory {
    /// Where `person`'s starts are kept: one person on one control plane, so whoever signs in next
    /// on this Mac, or the same handle on another control plane, inherits nothing. The parts are
    /// joined by the unit separator, which neither an address nor a handle holds.
    static func key(for person: WorkspacesModel.Person) -> String {
        ["sail.agentStarts", person.controlPlane, person.handle].joined(separator: "\u{1F}")
    }

    /// What `defaults` remember of `person`'s starts; nothing, before any or past a reading.
    static func stored(in defaults: UserDefaults, for person: WorkspacesModel.Person) -> ModeMemory {
        defaults.data(forKey: key(for: person)).flatMap { try? JSONDecoder().decode(ModeMemory.self, from: $0) } ?? ModeMemory()
    }

    func store(in defaults: UserDefaults, for person: WorkspacesModel.Person) {
        guard let data = try? JSONEncoder().encode(self) else { return }
        defaults.set(data, forKey: Self.key(for: person))
    }
}

/// A veil over the panel: drawn, never clicked.
@MainActor
final class Veil: NSView {
    override func hitTest(_: NSPoint) -> NSView? {
        nil
    }
}
