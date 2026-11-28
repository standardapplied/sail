import AppKit
import SailAPI
import SailControls
import SailLook
import SailTerminalView
import SailWorkbench

/// The Account view (`first-impressions-mockup.html`, section 2): a header (the initial's 52 pt
/// disc, the name in Newsreader, the email under it), then four groups in the list frame, each
/// under its eyebrow. The control plane: its address, when the session ends, and its state as a
/// pill only while it is not connected; out of reach, a banner in the
/// group with Try Again; under three days left, the end in the warning ink and Sign In Again. Your
/// passkeys and your keys, each a list of the server's rows asked at once and drawn as its own
/// answer lands, the drawing mark in the frame until its first answer, the last answer dimmed while
/// the call that drew it fails, each refusal said where it happened. Then Sign Out. No button here
/// is primary. Signed out it shows nothing: the welcome is on Workspaces. Laid out whole by
/// arithmetic in the page skeleton's column (``PageColumn/frame(width:)``), scrolling, mirrored
/// right to left.
@MainActor
public final class AccountPane: NSView, NSMenuItemValidation {
    static let groupGap = 28.0, headGap = 8.0, disc = 52.0, row = 56.0
    static let refusalPadding = 9.0, bannerFoot = 12.0, dimmed = 0.45

    /// The harnesses the server lists and the person's logins: what the Keys group is drawn from.
    package struct KeyLists: Equatable, Sendable {
        package let agents: [KnownAgent]
        package let logins: [StoredLogin]
    }

    public let flow: AccountFlow
    public let signOut: SailButton

    /// The look it is drawn in; a new one repaints it.
    var look: Look {
        didSet { painter.lookChanged(hidden: isHidden) }
    }

    private lazy var painter = PaintWhenShown { [unowned self] in self.paint() }

    /// How many times the pane was painted, wherever from: none while it is hidden at a change of
    /// look.
    package private(set) var lookPaints = 0

    /// What a group drew from its call.
    package private(set) var passkeys = AccountPage.Answered<[OwnPasskey]>()
    package private(set) var keys = AccountPage.Answered<KeyLists>()
    /// Why a removal of a row was refused, by the row's id, said under it.
    package private(set) var refusals: [String: [String]] = [:]
    /// The groups' last load and a person's last action: what a test awaits.
    package private(set) var loading: Task<Void, Never>?
    package private(set) var acting: Task<Void, Never>?
    /// Told each time a group draws an answer: what a test awaits of one group alone.
    var onDrawn: @MainActor () -> Void = {}
    /// The rows whose removal is under way, by id: each takes no second Remove meanwhile.
    package private(set) var removing: Set<String> = []
    /// Makes a key just stored the one new sessions take where the server did not, and New Session
    /// start with it: whether it is. The shell's is the New Session panel's own; none, nothing is.
    var useStored: (@MainActor (StoreLogin, SailClient) async -> Bool)?
    /// The question asked now, or the Store a Key sheet up.
    package private(set) var question: Sheet?
    package private(set) var loginSheet: LoginSheet?
    var now: @MainActor () -> Date = { Date() }
    var clock = AccountPage.Clock()
    var pasteboard = NSPasteboard.general

    private var signInLines: [String] = []
    private var signOutLines: [String] = []
    private var storedLine: String?
    /// Whose groups these are, the control plane and the handle: another's are cleared.
    private var whose: String?
    private var era = 0
    /// How many loads were started: only the latest one's answers are taken.
    private var loads = 0
    /// What the control plane's banner says now: it is made again only when that changes.
    private var bannerLines: [String]?
    /// The lists' width their rows were made for.
    private var listedAt = 0.0

    private let titleFont: TitleFont
    private let mono: TerminalFonts
    private let labels: LabelFont
    package let scroll: QuietScrollView
    private let page = AccountColumn()
    package let disc: Initial
    let heading = NSTextField(labelWithString: "")
    let email = NSTextField(labelWithString: "")
    /// The groups' mono eyebrows: the control plane, passkeys, keys.
    package private(set) var eyebrows: [NSTextField] = []
    let planeFrame = ListFrame(look: .light)
    private(set) var planeRow: AccountRowView
    let state: Pill
    let signInAgain: SailButton
    private(set) var banner: Banner?
    let passkeyFrame: ListFrame
    package let passkeyList: SailList
    let passkeyMark: BrandMark
    let passkeySaid = NSTextField(wrappingLabelWithString: "")
    let passkeyNote = NSTextField(wrappingLabelWithString: AccountPage.passkeysNote)
    let keyFrame: ListFrame
    package let keyList: SailList
    let keyMark: BrandMark
    let keySaid = NSTextField(wrappingLabelWithString: "")
    let keyNote = NSTextField(wrappingLabelWithString: AccountPage.keysNote)
    let outSaid = NSTextField(wrappingLabelWithString: "")

    init(flow: AccountFlow, look: Look, title: TitleFont, mono: TerminalFonts, labels: LabelFont) {
        self.flow = flow
        self.look = look
        titleFont = title
        self.mono = mono
        self.labels = labels
        scroll = QuietScrollView(look: look)
        disc = Initial("", side: Self.disc, font: CTFontCreateCopyWithAttributes(title.font(.title1), 24, nil, nil) as NSFont, mark: \.headerAvatar, look: look)
        state = Pill(.running, look: look, mono: labels)
        signInAgain = SailButton("Sign In Again", kind: .secondary, size: .small, look: look, target: nil, action: nil)
        signOut = SailButton("Sign Out", kind: .secondary, icon: .out, look: look, target: nil, action: nil)
        passkeyList = SailList(rowStyle: .list, look: look, mono: labels)
        keyList = SailList(rowStyle: .list, look: look, mono: labels)
        passkeyFrame = ListFrame(scrolling: passkeyList, look: look)
        keyFrame = ListFrame(scrolling: keyList, look: look)
        passkeyMark = BrandMark(.drawing(label: nil), side: 28, look: look, mono: labels)
        keyMark = BrandMark(.drawing(label: nil), side: 28, look: look, mono: labels)
        planeRow = AccountRowView(.icon(.cloud), title: "", mono: true, direction: .leftToRight, look: look, labels: labels)
        super.init(frame: .zero)
        compose()
        show()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        painter.shown()
    }

    /// A new width makes the lists' rows again at the height their words wrap to there, the lists
    /// first given that width: a row is made as wide as its list is then.
    override public func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        scroll.frame = bounds
        guard listWidth != listedAt else { return }
        for list in [passkeyList, keyList] { list.setFrameSize(NSSize(width: listWidth, height: Double(list.frame.height))) }
        relist()
    }

    /// Gone from view, the load under way is stopped and the refusals and what storing a key said
    /// are done with: shown again, the groups are asked again, and what came while it was hidden is
    /// said.
    override public func viewDidHide() {
        super.viewDidHide()
        loading?.cancel()
        refusals = [:]
        storedLine = nil
    }

    // MARK: What it says

    /// The control plane's address as the view names it, and as a key's sheet is told it.
    private var place: String {
        flow.standing?.controlPlane.named ?? ""
    }

    var ending: AccountPage.Ending? {
        flow.standing.map { AccountPage.ending($0.until, now: now(), clock: clock) }
    }

    var passkeyRows: [AccountPage.PasskeyRow] {
        AccountPage.passkeyRows(passkeys.value ?? [], now: now(), clock: clock)
    }

    var keyRows: [AccountPage.KeyRow] {
        keys.value.map { AccountPage.keyRows($0.agents, logins: $0.logins) } ?? []
    }

    /// What is said in the control plane's group under its row: why the check could not confirm the
    /// session (out of reach in the view's own words), else why Sign In Again was refused.
    var planeLines: [String] {
        guard case let .failure(refusal)? = flow.standing?.whoami else { return signInLines }
        return refusal.reason == .unreachable ? AccountPage.unreachable(at: place) : refusal.lines
    }

    /// Everything the view says now, a line each, as the drive reads it.
    public var text: String {
        guard let standing = flow.standing else { return "" }
        let content = flow.menuContent
        var lines = [content.name, (try? standing.whoami.get())?.email ?? ""]
        lines.append(([content.address ?? ""] + (state.isHidden ? [] : [state.said])).joined(separator: " · "))
        lines += [ending?.said ?? ""] + planeLines
        lines += passkeyRows.flatMap { row in [[row.label, passkeyDetail(row)].joined(separator: " · ")] + (refusals[row.id] ?? []) }
        lines += passkeys.failure ?? []
        lines += keyRows.flatMap { row in ["\(row.display) · \(rowDetail(row.id, row.detail))"] + (refusals[row.id] ?? []) }
        lines += (keys.failure ?? []) + [storedLine].compactMap { $0 }
        return (lines + signOutLines).filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// A row's detail: what it says, or that its removal is under way.
    private func rowDetail(_ id: String, _ said: String) -> String {
        removing.contains(id) ? AccountPage.removing : said
    }

    // MARK: Asking

    /// Shown (the rail's Account, the menu's Account or Keys): the groups asked again.
    func shown() {
        load(keepingSaid: true)
    }

    /// Asks the server for the groups' answers while someone is signed in and the view is shown,
    /// all at once: their own passkeys, the harnesses it knows and their keys, each group drawn as
    /// its own answer lands. The load before is stopped, and only the latest one's answers are
    /// taken, for this sign-in; a group keeps its last answer until a call answers again. A refusal
    /// under a row, and what storing a key said, are said until the next load but the one the
    /// view's showing asks: what they speak of may have changed since (a key removed, another sign-in).
    func load(keepingSaid: Bool = false) {
        guard !isHiddenOrHasHiddenAncestor, let client = flow.client() else { return }
        loading?.cancel()
        loads += 1
        if !keepingSaid {
            refusals = [:]
            storedLine = nil
        }
        let load = (number: loads, era: era)
        loading = Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                group.addTask { [weak self] in await self?.takePasskeys(from: client, in: load) }
                group.addTask { [weak self] in await self?.takeKeys(from: client, in: load) }
            }
        }
    }

    private func isLatest(_ load: (number: Int, era: Int)) -> Bool {
        !Task.isCancelled && load.number == loads && load.era == era
    }

    private func takePasskeys(from client: SailClient, in load: (number: Int, era: Int)) async {
        await take(\.passkeys, in: load) { () async throws(SailError) in try await client.send(.passkeys).passkeys }
    }

    /// The harnesses' and the keys' answers, drawn together.
    private func takeKeys(from client: SailClient, in load: (number: Int, era: Int)) async {
        await take(\.keys, in: load) { () async throws(SailError) in
            KeyLists(agents: try await client.send(.knownAgents).agents, logins: try await client.send(.logins).logins)
        }
    }

    /// A group's answer, drawn when it is the latest load's.
    private func take<Value>(
        _ group: ReferenceWritableKeyPath<AccountPane, AccountPage.Answered<Value>>, in load: (number: Int, era: Int), _ work: @Sendable () async throws(SailError) -> Value
    ) async {
        let answer = await Self.result(work)
        guard isLatest(load) else { return }
        self[keyPath: group].take(answer, at: place)
        show()
        onDrawn()
    }

    private static func result<Value>(_ work: () async throws(SailError) -> Value) async -> Result<Value, SailError> {
        do throws(SailError) {
            return .success(try await work())
        } catch {
            return .failure(error)
        }
    }

    /// `error` as said where it happened: the server's words, or the control plane failing.
    private func lines(_ error: SailError) -> [String] {
        if case let .refused(refusal) = error { return refusal.lines }
        return [error.said(at: place)]
    }

    // MARK: Acting

    /// Signs out at once, asking nothing: what the sign-in opened on the runner lives on there,
    /// for the same person's next sign-in.
    @objc public func signOutHere(_: Any?) {
        let refused = flow.signOut()
        signOutLines = refused?.lines ?? []
        whose = flow.standing.map(Self.whose)
        show()
    }

    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        item.action != #selector(signOutHere(_:)) || (flow.standing != nil && !flow.isActing)
    }

    /// Try Again: who is signed in asked again, then the groups.
    @objc func tryAgain(_: Any?) {
        acting = Task { [weak self] in
            await self?.flow.refresh()
            self?.load()
        }
    }

    /// Sign In Again: the sign-in ceremony at this control plane, the view kept, a refusal said in
    /// the control plane's group; then the groups asked again, as the passkey signed in with is now
    /// This Mac's, and as an ask that ended during the ceremony was not handed over.
    @objc func signInAgainNow(_: Any?) {
        signInLines = []
        guard flow.signInAgain(refused: { [weak self] refusal in
            self?.signInLines = refusal.lines
            self?.show()
        }) else { return }
        let underWay = flow.underWay
        acting = Task { [weak self] in
            await underWay?.value
            self?.load()
        }
        show()
    }

    /// Remove on a row asks first, Cancel the default; confirmed, `remove` is sent, the row said as
    /// being removed and taking no second Remove until it answers, and its refusal said under the
    /// row, the row kept. Either way the groups are asked again.
    private func ask(_ asked: AccountPage.Question, row id: String, remove: @escaping @Sendable (SailClient) async throws(SailError) -> Void) {
        guard let window, question == nil, loginSheet == nil, !removing.contains(id), flow.client() != nil else { return }
        let sheet = Sheet(title: asked.title, body: asked.body, confirm: "Remove", kind: .destructive, look: look, titleFont: titleFont)
        question = sheet
        sheet.ask(on: window) { [weak self] confirmed in
            guard let self else { return }
            question = nil
            guard confirmed, let client = flow.client() else { return }
            refusals[id] = nil
            removing.insert(id)
            show()
            let era = self.era
            acting = Task { [weak self] in
                let answer = await Self.result { () async throws(SailError) in try await remove(client) }
                guard let self, era == self.era else { return }
                removing.remove(id)
                load()
                if case let .failure(error) = answer { refusals[id] = lines(error) }
                show()
            }
        }
    }

    /// Add Key and Replace: the Store a Key sheet for the row's harness, storing under the key it
    /// replaces. Once stored, the clipboard is cleared where it still holds the key, the key is made
    /// the one new sessions take where the server did not make it so, as the New Session panel makes
    /// it, and the keys asked again.
    private func storeKey(_ row: AccountPage.KeyRow) {
        guard let window, question == nil, loginSheet == nil, let client = flow.client(), let lists = keys.value,
              let asked = StoreLogin(row.agent, logins: lists.logins, address: place, naming: row.login)
        else { return }
        let width = min(LoginSheet.width, Double(window.contentLayoutRect.width) - 2 * LoginSheet.inset)
        let sheet = LoginSheet(asked, width: width, direction: userInterfaceLayoutDirection, look: look, titleFont: titleFont, mono: labels, pasteboard: pasteboard)
        sheet.send = { endpoint in await Self.result { () async throws(SailError) in try await client.send(endpoint) } }
        sheet.onStored = { [weak self] stored in self?.stored(stored, by: client) }
        sheet.onEnd = { [weak self, weak sheet] in
            guard let self, loginSheet === sheet else { return }
            loginSheet = nil
        }
        loginSheet = sheet
        window.beginSheet(sheet)
    }

    /// A key the sheet stored, by `client`: said under the keys until the view is left or the groups
    /// are asked again after the ask it starts.
    package func stored(_ stored: StoreLogin, by client: SailClient) {
        if case .stored(_, clearsClipboard: true) = stored.stage { pasteboard.clearContents() }
        storedLine = stored.stored
        let era = self.era
        acting = Task { [weak self] in
            let inUse = await self?.useStored?(stored, client)
            guard let self, era == self.era else { return }
            load()
            storedLine = inUse == false ? stored.notUsed : stored.stored
            show()
        }
    }

    // MARK: Drawing

    private static func whose(_ standing: SignedIn) -> String {
        standing.controlPlane.named + "\u{1F}" + standing.fde
    }

    /// Draws what the flow and the groups know now. Another sign-in clears the groups and asks again.
    package func show() {
        let standing = flow.standing, who = standing.map(Self.whose)
        if who != whose {
            whose = who
            era += 1
            passkeys = .init()
            keys = .init()
            refusals = [:]
            removing = []
            signInLines = []
            storedLine = nil
            if who != nil { signOutLines = [] }
            if who != nil { load() }
        }
        let content = flow.menuContent
        eyebrows.forEach { $0.alignment = userInterfaceLayoutDirection == .rightToLeft ? .right : .left }
        heading.attributedStringValue = words(content.name, titleFont.font(.title1) as NSFont, look.text, cut: true)
        disc.name = content.name
        email.attributedStringValue = words((try? standing?.whoami.get())?.email ?? "", .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, cut: true)
        showPlane(content.plane)
        relist()
        showGroup(passkeys, frame: passkeyFrame, list: passkeyList, mark: passkeyMark, said: passkeySaid, note: passkeyNote, extra: nil)
        showGroup(keys, frame: keyFrame, list: keyList, mark: keyMark, said: keySaid, note: keyNote, extra: storedLine)
        outSaid.attributedStringValue = refusalWords(signOutLines, look: look, scale: scale)
        outSaid.isHidden = signOutLines.isEmpty
        signOut.isEnabled = !flow.isActing
        needsLayout = true
        page.needsLayout = true
    }

    private var scale: Double {
        Double(window?.backingScaleFactor ?? 2)
    }

    /// The control plane's row, its pill and Sign In Again, and its banner: the banner made again
    /// only when what it says, or the look, changes, so a check keeps Try Again, and its focus.
    private func showPlane(_ plane: UserMenuContent.Plane?) {
        let ending = self.ending, lines = planeLines
        planeRow.removeFromSuperview()
        planeRow = AccountRowView(.icon(.cloud), title: place, mono: true, detail: ending?.said ?? "", warns: ending?.soon == true, direction: userInterfaceLayoutDirection, look: look, labels: labels)
        planeFrame.addSubview(planeRow, positioned: .below, relativeTo: nil)
        if lines != bannerLines {
            bannerLines = lines
            banner?.removeFromSuperview()
            banner = lines.first.map { first in
                let again = lines == AccountPage.unreachable(at: place) ? SailButton("Try Again", kind: .secondary, size: .small, look: look, target: self, action: #selector(tryAgain(_:))) : nil
                let rest = lines.dropFirst().joined(separator: " ")
                let made = Banner(.error, title: first, line: rest.isEmpty ? nil : rest, action: again, look: look)
                planeFrame.addSubview(made)
                return made
            }
        }
        banner?.action?.isEnabled = !flow.busy
        let (paint, words): (PillState, String) = switch plane {
        case .reconnecting?: (.starting, UserMenuContent.Plane.reconnecting.said)
        case .unreachable?: (.error, UserMenuContent.Plane.unreachable.said)
        case .connected?, nil: (.running, UserMenuContent.Plane.connected.said)
        }
        state.state = paint
        state.words = words
        state.isHidden = paint == .running
        signInAgain.isHidden = ending?.soon != true
        signInAgain.isEnabled = !flow.isActing
        planeRow.controls = planeControls.map(\.intrinsicContentSize)
    }

    /// The control plane row's controls, in order from its words: Sign In Again and the pill, each
    /// while shown.
    private var planeControls: [NSView] {
        [signInAgain, state].filter { !$0.isHidden }
    }

    /// Remove in a row's ⋯ and on ⌘⌫: off while that row's removal is under way.
    private func removal(of id: String, asking ask: @escaping @MainActor () -> Void) -> SailList.Action {
        SailList.Action("Remove", key: "\u{8}", modifiers: .command, onRow: false, destructive: true, enabled: { [weak self] in self?.removing.contains(id) == false }, run: ask)
    }

    /// The lists' rows made again for the column's width, which their words wrap to.
    private func relist() {
        listedAt = listWidth
        passkeyList.entries = passkeyEntries
        keyList.entries = keyEntries
    }

    private var passkeyEntries: [SailList.Entry] {
        passkeyRows.flatMap { row -> [SailList.Entry] in
            let remove = removal(of: row.id) { [weak self] in
                self?.ask(row.removing, row: row.id) { (client) async throws(SailError) in _ = try await client.send(.removePasskey(row.id)) }
            }
            let detail = passkeyDetail(row), width = rowWidth(in: listWidth, actions: passkeyList.actionsColumn ?? 0)
            let voice = "\(row.label), \(detail)"
            let made = { self.rowView(.icon(.fingerprint), title: row.label, detail: detail, width: width) }
            let item = SailList.Item(id: row.id, name: row.label, voice: voice, content: made(), actions: [remove], height: made().height)
            return [.item(item)] + refused(row.id)
        }
    }

    private var keyEntries: [SailList.Entry] {
        keyRows.flatMap { row -> [SailList.Entry] in
            let store = row.stores ? SailList.Action(row.login == nil ? "Add Key" : "Replace", run: { [weak self] in self?.storeKey(row) }) : nil
            var actions = [store].compactMap(\.self)
            if let asked = row.removing, let login = row.login {
                actions.append(removal(of: row.id) { [weak self] in
                    self?.ask(asked, row: row.id) { (client) async throws(SailError) in _ = try await client.send(.removeLogin(harness: row.agent.harness, name: login)) }
                })
            }
            let offered = row.login == nil ? store : nil, detail = rowDetail(row.id, row.detail)
            let width = rowWidth(in: listWidth, actions: offered == nil ? keyList.actionsColumn ?? 0 : 0)
            let made = { self.rowView(.letters(row.monogram), title: row.display, detail: detail, offer: offered, width: width) }
            let item = SailList.Item(
                id: row.id, name: row.display, voice: "\(row.display), \(detail)", content: made(), actions: actions, height: made().height, full: offered != nil
            )
            return [.item(item)] + refused(row.id)
        }
    }

    /// A row of a list, made each time its list asks, `width` wide and as tall as that makes it: an
    /// always-shown button of its own runs `offer`.
    /// A passkey's detail: "This Mac" first on this Mac's own, then when it was added and last used.
    private func passkeyDetail(_ row: AccountPage.PasskeyRow) -> String {
        ([row.thisMac ? AccountPage.thisMac : nil] + [rowDetail(row.id, row.detail)]).compactMap { $0 }.joined(separator: " · ")
    }

    private func rowView(_ mark: AccountRowView.Mark, title: String, detail: String, offer: SailList.Action? = nil, width: Double) -> AccountRowView {
        let row = AccountRowView(mark, title: title, detail: detail, direction: userInterfaceLayoutDirection, look: look, labels: labels)
        if let offer {
            let run = Run(offer)
            let button = SailButton(offer.title, kind: .secondary, size: .small, icon: .plus, look: look, target: run, action: #selector(Run.go(_:)))
            button.setAccessibilityLabel("\(offer.title), \(title)")
            row.offer(button, keeping: run)
        }
        row.height = Self.height(of: row.fit(width: width))
        return row
    }

    /// The room a list keeps for its rows' own buttons, so its rows' words wrap at one width: the
    /// widest row's (`titles`, then ⋯), as Workspaces measures its own.
    private func actionsColumn(_ titles: [String]) -> Double {
        let more = SailButton(icon: .more, label: "More", size: .small, look: look, target: nil, action: nil)
        return WorkspaceLine.actionsColumn(titles.map { SailButton($0, kind: .secondary, size: .small, look: look, target: nil, action: nil) } + [more])
    }

    /// The refusal of row `id`'s removal, as words under its words, wrapped to the row, that the
    /// keys pass.
    private func refused(_ id: String) -> [SailList.Entry] {
        guard let lines = refusals[id] else { return [] }
        let width = rowWidth(in: listWidth, actions: 0), said = underTheWords(refusalWords(lines, look: look, scale: scale))
        let height = Double(ceil(said.boundingRect(with: NSSize(width: width - 4, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin]).height))
        let item = SailList.Item(
            id: id + "\u{1F}refused", name: "", voice: lines.joined(separator: " "), content: Self.wrapped(said, width: width), height: height + 2 * Self.refusalPadding, full: true, inert: true
        )
        return [.item(item)]
    }

    /// `said` from where the rows' words start, as the control plane's banner stands, in the view's
    /// direction: a label's own 2 pt inside its frame less.
    private func underTheWords(_ said: NSAttributedString) -> NSAttributedString {
        let under = NSMutableAttributedString(attributedString: said), start = AccountRowView.tile + AccountRowView.gap - 2, style = NSMutableParagraphStyle()
        if userInterfaceLayoutDirection == .rightToLeft {
            style.alignment = .right
            style.tailIndent = -start
        } else {
            style.firstLineHeadIndent = start
            style.headIndent = start
        }
        under.addAttribute(.paragraphStyle, value: style, range: NSRange(location: 0, length: under.length))
        return under
    }

    private static func wrapped(_ said: NSAttributedString, width: Double) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: "")
        field.attributedStringValue = said
        field.preferredMaxLayoutWidth = width
        return field
    }

    private func showGroup<Value>(_ answered: AccountPage.Answered<Value>, frame: ListFrame, list: SailList, mark: BrandMark, said: NSTextField, note: NSTextField, extra: String?) {
        mark.isHidden = !answered.waiting
        list.isHidden = answered.value == nil
        frame.isHidden = !answered.waiting && answered.value == nil
        frame.alphaValue = answered.dimmed ? Self.dimmed : 1
        let failure = answered.failure ?? []
        let told = NSMutableAttributedString(attributedString: refusalWords(failure, look: look, scale: scale))
        if let extra { told.append(words((failure.isEmpty ? "" : "\n") + extra, .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary)) }
        said.attributedStringValue = told
        said.isHidden = told.length == 0
        note.attributedStringValue = words(note.stringValue, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary)
    }

    private func words(_ text: String, _ font: NSFont, _ ink: Tint, cut: Bool = false) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = cut ? .byTruncatingTail : .byWordWrapping
        style.alignment = userInterfaceLayoutDirection == .rightToLeft ? .right : .left
        return NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style])
    }

    private func paint() {
        lookPaints += 1
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        scroll.look = look
        disc.look = look
        state.look = look
        for button in [signInAgain, signOut] { button.look = look }
        for frame in [planeFrame, passkeyFrame, keyFrame] { frame.look = look }
        for list in [passkeyList, keyList] { list.look = look }
        for mark in [passkeyMark, keyMark] { mark.look = look }
        eyebrows.forEach { $0.textColor = NSColor(look.textSecondary) }
        page.look = look
        question?.look = look
        loginSheet?.look = look
        bannerLines = nil
        show()
    }

    private func compose() {
        wantsLayer = true
        scroll.documentView = page
        addSubview(scroll)
        eyebrows = ["Control plane", "Passkeys", "Keys"].map { eyebrow($0, mono: mono) }
        planeFrame.height = nil
        planeFrame.addSubview(state)
        planeFrame.addSubview(signInAgain)
        passkeyFrame.addSubview(passkeyMark)
        keyFrame.addSubview(keyMark)
        for list in [passkeyList, keyList] {
            list.primaryElsewhere = true
            list.returnKeyShown = false
        }
        passkeyList.actionsColumn = actionsColumn([])
        keyList.actionsColumn = actionsColumn(["Replace"])
        for label in [heading, email] {
            label.maximumNumberOfLines = 1
            label.lineBreakMode = .byTruncatingTail
        }
        let views: [NSView] = [disc, heading, email] + eyebrows + [planeFrame, passkeyFrame, passkeySaid, passkeyNote, keyFrame, keySaid, keyNote, outSaid, signOut]
        views.forEach(page.addSubview)
        signInAgain.target = self
        signInAgain.action = #selector(signInAgainNow(_:))
        signOut.target = self
        signOut.action = #selector(signOutHere(_:))
        paint()
    }

    override public func layout() {
        super.layout()
        scroll.frame = bounds
        let width = Double(scroll.contentSize.width)
        if Double(page.frame.width) != width { page.setFrameSize(NSSize(width: width, height: page.frame.height)) }
        let height = max(lay(), Double(scroll.contentSize.height))
        if Double(page.frame.height) != height { page.setFrameSize(NSSize(width: width, height: height)) }
    }

    /// The width of a list's rows: the column's, less the frame's hairlines.
    private var listWidth: Double {
        columnWidth - 2
    }

    private var columnWidth: Double {
        PageColumn.frame(width: Double(scroll.contentSize.width)).width
    }

    /// Every frame of the column, top to bottom, left to right then turned about the page's middle
    /// right to left; the column's height.
    private func lay() -> Double {
        let (x, width) = PageColumn.frame(width: Double(scroll.contentSize.width))
        var y = PageColumn.padding.top
        let pageWidth = Double(page.bounds.width)
        func put(_ view: NSView, _ rect: NSRect) {
            let frame = mirrored(rect, inWidth: pageWidth)
            if view.frame != frame { view.frame = frame }
        }
        func line(_ label: NSTextField, width: Double) -> Double {
            Double(ceil(label.cell?.cellSize(forBounds: NSRect(x: 0, y: 0, width: width, height: 10000)).height ?? 0))
        }
        put(disc, NSRect(x: x, y: y, width: Self.disc, height: Self.disc))
        let text = x + Self.disc + Look.Space.l, textWidth = max(width - Self.disc - Look.Space.l, 1)
        let headingHeight = line(heading, width: textWidth), sub = 5.0
        let subHeight = line(email, width: textWidth)
        let top = y + (Self.disc - headingHeight - sub - subHeight) / 2
        put(heading, NSRect(x: text, y: top, width: textWidth, height: headingHeight))
        put(email, NSRect(x: text, y: top + headingHeight + sub, width: textWidth, height: subHeight))
        y += Self.disc + Self.groupGap
        y = group(eyebrows[0], frame: planeFrame, y: y, x: x, width: width, put: put) { inner in self.layPlane(width: inner) } + Self.groupGap
        y = group(eyebrows[1], frame: passkeyFrame, y: y, x: x, width: width, put: put) { inner in self.layList(passkeyList, in: passkeyFrame, mark: passkeyMark, width: inner) }
        y = notes([passkeySaid, passkeyNote], y: y, x: x, width: width, put: put, line: line) + Self.groupGap
        y = group(eyebrows[2], frame: keyFrame, y: y, x: x, width: width, put: put) { inner in self.layList(keyList, in: keyFrame, mark: keyMark, width: inner) }
        y = notes([keySaid, keyNote], y: y, x: x, width: width, put: put, line: line) + Self.groupGap
        let rule = y
        y += 1 + 18
        let button = signOut.intrinsicContentSize
        let wordsWidth = max(width - Double(button.width) - Look.Space.l, 1)
        let start = y
        if !outSaid.isHidden {
            let height = line(outSaid, width: wordsWidth)
            put(outSaid, NSRect(x: x, y: y, width: wordsWidth, height: height))
            y += height + 2
        }
        let buttonTop = start + max((y - start - Double(button.height)) / 2, 0)
        put(signOut, signOut.frame(forAlignmentRect: NSRect(x: x + width - Double(button.width), y: buttonTop, width: Double(button.width), height: Double(button.height))))
        page.rule = mirrored(NSRect(x: x, y: rule, width: width, height: 1), inWidth: pageWidth)
        return max(y, buttonTop + Double(button.height)) + PageColumn.padding.bottom
    }

    /// A group's eyebrow, then its frame holding what `inner` lays out at its width; where it ends.
    private func group(_ eyebrow: NSTextField, frame: ListFrame, y: Double, x: Double, width: Double, put: (NSView, NSRect) -> Void, inner: (Double) -> Double) -> Double {
        let eyebrowHeight = Double(ceil(eyebrow.fittingSize.height))
        put(eyebrow, NSRect(x: x, y: y, width: width, height: eyebrowHeight))
        var bottom = y + eyebrowHeight + Self.headGap
        guard !frame.isHidden else { return bottom }
        let height = inner(width - 2)
        put(frame, NSRect(x: x, y: bottom, width: width, height: height + 2))
        bottom += height + 2
        return bottom
    }

    /// Lines under a group's frame, each wrapped to the column; where they end.
    private func notes(_ labels: [NSTextField], y: Double, x: Double, width: Double, put: (NSView, NSRect) -> Void, line: (NSTextField, Double) -> Double) -> Double {
        var y = y
        for label in labels where !label.isHidden {
            y += Self.headGap
            let height = line(label, min(width, 560))
            put(label, NSRect(x: x, y: y, width: min(width, 560), height: height))
            y += height
        }
        return y
    }

    /// The control plane's row inside its frame, `width` wide, its controls where the row's
    /// ``AccountRowView/fit(width:)`` puts them, and the banner under it; its height.
    private func layPlane(width: Double) -> Double {
        func at(_ rect: NSRect) -> NSRect {
            mirrored(rect, inWidth: width + 2)
        }
        let lead = 1 + RowStyle.list.leading, rowWidth = rowWidth(in: width, actions: 0), fit = planeRow.fit(width: rowWidth)
        let height = Self.height(of: fit), top = 1 + (height - fit.height) / 2
        planeRow.frame = at(NSRect(x: lead, y: top, width: rowWidth, height: fit.height))
        for (control, box) in zip(planeControls, fit.controls) {
            control.frame = at(control.frame(forAlignmentRect: box.offsetBy(dx: lead, dy: top)))
        }
        guard let banner else { return height }
        let start = RowStyle.list.leading + AccountRowView.tile + AccountRowView.gap, bannerWidth = max(width - start - RowStyle.list.trailing, 1)
        banner.frame = NSRect(x: 0, y: 0, width: bannerWidth, height: 10)
        let bannerHeight = Double(banner.intrinsicContentSize.height)
        banner.frame = at(NSRect(x: 1 + start, y: 1 + height, width: bannerWidth, height: bannerHeight))
        return height + bannerHeight + Self.bannerFoot
    }

    /// A row's height in its list: its words and controls between the page's 9 pt padding, at least
    /// the page's 56 pt.
    private static func height(of fit: AccountRowView.Fit) -> Double {
        max(Self.row, fit.height + 2 * AccountRowView.padding)
    }

    /// The width a row's words and controls are given in a list `width` wide, past `actions` kept
    /// for the list's own.
    private func rowWidth(in width: Double, actions: Double) -> Double {
        max(width - 2 * RowStyle.list.margin - RowStyle.list.leading - RowStyle.list.trailing - actions, 1)
    }

    /// A list's rows in its frame, which holds them at their height, or the drawing mark while its
    /// first answer is awaited; their height.
    private func layList(_ list: SailList, in frame: ListFrame, mark: BrandMark, width: Double) -> Double {
        let height = list.isHidden ? Self.row : list.entries.indices.reduce(0.0) { $0 + Double(list.tableView(list, heightOfRow: $1)) }
        if frame.height != height { frame.height = height }
        mark.frame = NSRect(x: 1 + (width - 28) / 2, y: 1 + (Self.row - 28) / 2, width: 28, height: 28)
        return height
    }
}

/// The column the Account view scrolls: flipped, its views laid out by the view, a hairline over
/// Sign Out.
@MainActor
final class AccountColumn: NSView {
    var look = Look.light {
        didSet { needsDisplay = true }
    }

    var rule = NSRect.zero {
        didSet { if rule != oldValue { needsDisplay = true } }
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_: NSRect) {
        NSColor(look.separator).setFill()
        rule.fill()
    }
}

/// A row of the Account view, as the page's `.li` draws it: a 28 pt tile in 6 pt corners holding an
/// icon or a harness's monogram, the title (mono for an address), the detail
/// under it in the tertiary ink (the warning's when it warns), and controls at its trailing edge
/// whatever the pointer does: a button of its own (Add Key), or those its view lays out by
/// ``fit(width:)`` (the control plane's pill and Sign In Again). Its words are read whole: the
/// controls stand beside them while the title fits beside them, else on a line under them, and the
/// detail wraps to the words' width. Mirrored right to left.
@MainActor
final class AccountRowView: NSView {
    enum Mark: Equatable {
        case icon(LineIcon), letters(String)
    }

    /// Where a row's words and controls stand in it, left to right: the words' width and top, each
    /// control's box, and the row's height less its padding.
    struct Fit {
        let words: Double
        let top: Double
        let controls: [NSRect]
        let height: Double
    }

    static let tile = 28.0, gap = 12.0, lineGap = 3.0, padding = 9.0

    let mark: Mark
    let title = NSTextField(labelWithString: "")
    let detail = NSTextField(wrappingLabelWithString: "")
    /// Its controls by size, at its trailing edge or under its words: its own button's, or those its
    /// view lays out.
    var controls: [NSSize] = [] {
        didSet { needsLayout = true }
    }

    /// The height its list's row gives it, all of which it takes.
    var height = 0.0
    private(set) var offered: SailButton?
    private var keep: AnyObject?
    private let look: Look
    private let labels: LabelFont
    private let mono: Bool

    init(
        _ mark: Mark, title words: String, mono: Bool = false, detail said: String = "", warns: Bool = false, direction: NSUserInterfaceLayoutDirection,
        look: Look, labels: LabelFont
    ) {
        self.mark = mark
        self.look = look
        self.labels = labels
        self.mono = mono
        super.init(frame: .zero)
        userInterfaceLayoutDirection = direction
        let titleFont = mono ? labels.font(Look.Step.callout.points) as NSFont : NSFont.systemFont(ofSize: 13.5, weight: .medium)
        title.attributedStringValue = Draw.text(words, titleFont, look.text).cut(.byTruncatingTail)
        title.maximumNumberOfLines = 1
        title.lineBreakMode = .byTruncatingTail
        let style = NSMutableParagraphStyle()
        style.alignment = direction == .rightToLeft ? .right : .left
        let ink = warns ? Tint(look.warning) : look.textTertiary
        detail.attributedStringValue = NSAttributedString(string: said, attributes: [.font: NSFont.systemFont(ofSize: Look.Step.footnote.points), .foregroundColor: NSColor(ink), .paragraphStyle: style])
        detail.isSelectable = false
        [title, detail].forEach(addSubview)
        setAccessibilityElement(false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// `button` its one control, in its direction, `keeping` what it runs.
    func offer(_ button: SailButton, keeping run: AnyObject) {
        offered = button
        keep = run
        button.userInterfaceLayoutDirection = userInterfaceLayoutDirection
        addSubview(button)
        controls = [button.intrinsicContentSize]
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: height)
    }

    private var titleLine: Double {
        Double(ceil(title.attributedStringValue.size().height))
    }

    private var titleWidth: Double {
        Double(ceil(title.attributedStringValue.size().width))
    }

    private func detailHeight(_ width: Double) -> Double {
        Double(ceil(detail.cell?.cellSize(forBounds: NSRect(x: 0, y: 0, width: width + 4, height: 10000)).height ?? 0))
    }

    /// Its words and ``controls`` `width` wide: the controls `Look.Space.s` apart at the trailing
    /// edge while the title fits whole beside them, else from the words' start on a line under them.
    func fit(width: Double) -> Fit {
        let start = Self.tile + Self.gap, sizes = controls
        let row = sizes.reduce(0.0) { $0 + Double($1.width) } + Double(max(sizes.count - 1, 0)) * Look.Space.s
        let beside = sizes.isEmpty || titleWidth + Self.gap + row <= width - start
        let words = max(width - start - (sizes.isEmpty || !beside ? 0 : row + Self.gap), 1)
        let block = titleLine + Self.lineGap + detailHeight(words), tallest = sizes.map { Double($0.height) }.max() ?? 0
        let whole = beside ? block : block + Look.Space.s + tallest, height = max(Self.tile, whole, tallest)
        let top = (height - whole) / 2
        var x = beside ? width - row : start
        let boxes = sizes.map { size -> NSRect in
            let y = beside ? (height - Double(size.height)) / 2 : top + block + Look.Space.s
            defer { x += Double(size.width) + Look.Space.s }
            return NSRect(x: x, y: y, width: Double(size.width), height: Double(size.height))
        }
        return Fit(words: words, top: beside ? (height - block) / 2 : top, controls: boxes, height: height)
    }

    private func mirrored(_ rect: NSRect) -> NSRect {
        mirrored(rect, inWidth: Double(bounds.width))
    }

    /// Its words and controls as ``fit(width:)`` places them, the whole centred in its height.
    override func layout() {
        super.layout()
        let fit = fit(width: Double(bounds.width)), start = Self.tile + Self.gap
        let below = (Double(bounds.height) - fit.height) / 2, top = below + fit.top
        if let offered, let box = fit.controls.first {
            offered.frame = offered.frame(forAlignmentRect: mirrored(box.offsetBy(dx: 0, dy: below)))
        }
        title.frame = mirrored(NSRect(x: start - 2, y: top, width: min(fit.words, titleWidth) + 4, height: titleLine))
        detail.frame = mirrored(NSRect(x: start - 2, y: top + titleLine + Self.lineGap, width: fit.words + 4, height: detailHeight(fit.words)))
        needsDisplay = true
    }

    var tileBox: NSRect {
        mirrored(NSRect(x: 0, y: (Double(bounds.height) - Self.tile) / 2, width: Self.tile, height: Self.tile))
    }

    override func draw(_: NSRect) {
        let box = tileBox, shape = NSBezierPath(roundedRect: box.insetBy(dx: 0.5, dy: 0.5), xRadius: Look.contentRadius - 0.5, yRadius: Look.contentRadius - 0.5)
        NSColor(look.canvas).setFill()
        shape.fill()
        NSColor(look.separatorStrong).setStroke()
        shape.lineWidth = 1
        shape.stroke()
        switch mark {
        case let .icon(icon):
            Draw.icon(icon, in: box.insetBy(dx: (Self.tile - 15) / 2, dy: (Self.tile - 15) / 2), ink: look.textSecondary)
        case let .letters(letters):
            let words = NSAttributedString(string: letters, attributes: [.font: labels.font(11, .semibold) as NSFont, .foregroundColor: NSColor(look.textSecondary)])
            let size = words.size()
            words.draw(at: NSPoint(x: Double(box.midX) - Double(size.width) / 2, y: Double(box.midY) - Double(size.height) / 2))
        }
    }
}

extension NSView {
    /// `rect` in a `width` wide box of this view's, turned about its middle right to left.
    fileprivate func mirrored(_ rect: NSRect, inWidth width: Double) -> NSRect {
        guard userInterfaceLayoutDirection == .rightToLeft else { return rect }
        return NSRect(x: width - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }
}

/// JetBrains Mono at `step`, the face the terminal uses.
@MainActor
func monoFont(_ step: Look.Step, from mono: TerminalFonts) -> NSFont {
    (try? mono.faces(points: step.points, scale: 1).font(.regular)).map { $0 as NSFont }
        ?? .monospacedSystemFont(ofSize: step.points, weight: .regular)
}

/// A group's eyebrow, as "CONTROL PLANE" is: its name upper case in JetBrains Mono at the micro
/// step, which its pane paints in the secondary text colour.
@MainActor
func eyebrow(_ text: String, mono: TerminalFonts) -> NSTextField {
    let label = NSTextField(labelWithString: text.uppercased())
    label.font = monoFont(.micro, from: mono)
    return label
}
