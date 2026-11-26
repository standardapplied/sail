import AppKit
import SailAPI
import SailControls
import SailLook
import SailTerminalView
import SailWorkbench

/// The Account view (`first-impressions-mockup.html`, section 2): a header (the initial's 52 pt
/// disc, the name in Newsreader, `@handle`, the role as a plain pill, the email: the user menu's own
/// name, handle and role), then four groups in the list frame, each under its eyebrow. The control
/// plane: its address, when the session ends, and its state as a pill; out of reach, a banner in the
/// group with Try Again; under three days left, the end in the warning ink and Sign In Again. Your
/// passkeys and your keys, each a list of the server's rows asked at once and drawn as its own
/// answer lands, the drawing mark in the frame until its first answer, the last answer dimmed while
/// the call that drew it fails, each refusal said where it happened. Then Sign Out, beside what it
/// ends and what it does not. No button here is primary. Signed out it shows nothing: the welcome
/// is on Workspaces. Laid out whole by arithmetic in a scrolling column, mirrored right to left.
@MainActor
public final class AccountPane: NSView, NSMenuItemValidation {
    package static let inset = (top: 36.0, sides: 48.0)
    static let column = 640.0, groupGap = 28.0, headGap = 8.0, disc = 52.0, row = 56.0
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
    /// The groups' last load; a person's last action, or the hand-over to the check: what a test
    /// awaits.
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
    /// Keys was chosen as this load was started: the view goes to its keys once it has drawn them.
    private var keysAfter: Int?
    /// What the control plane's banner says now: it is made again only when that changes.
    private var bannerLines: [String]?

    private let titleFont: TitleFont
    private let mono: TerminalFonts
    private let labels: LabelFont
    package let scroll: QuietScrollView
    private let page = AccountColumn()
    let disc: Initial
    let heading = NSTextField(labelWithString: "")
    let handle = NSTextField(labelWithString: "")
    let role: Pill
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
    let outWords = NSTextField(wrappingLabelWithString: "")
    let outNote = NSTextField(wrappingLabelWithString: AccountPage.signOutNote)
    let outSaid = NSTextField(wrappingLabelWithString: "")

    init(flow: AccountFlow, look: Look, title: TitleFont, mono: TerminalFonts, labels: LabelFont) {
        self.flow = flow
        self.look = look
        titleFont = title
        self.mono = mono
        self.labels = labels
        scroll = QuietScrollView(look: look)
        disc = Initial("", side: Self.disc, font: CTFontCreateCopyWithAttributes(title.font(.title1), 24, nil, nil) as NSFont, mark: \.headerAvatar, look: look)
        role = Pill(plain: "", size: .small, look: look, mono: labels)
        state = Pill(.running, look: look, mono: labels)
        signInAgain = SailButton("Sign In Again", kind: .secondary, size: .small, look: look, target: nil, action: nil)
        signOut = SailButton("Sign Out", kind: .secondary, icon: .out, look: look, target: nil, action: nil)
        passkeyList = SailList(rowStyle: .list, look: look, mono: labels)
        keyList = SailList(rowStyle: .list, look: look, mono: labels)
        passkeyFrame = ListFrame(scrolling: passkeyList, look: look)
        keyFrame = ListFrame(scrolling: keyList, look: look)
        passkeyMark = BrandMark(.drawing(label: nil), side: 28, look: look, mono: labels)
        keyMark = BrandMark(.drawing(label: nil), side: 28, look: look, mono: labels)
        planeRow = AccountRowView(.icon(.cloud), title: "", mono: true, look: look, labels: labels)
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

    /// Gone from view, the load under way is stopped: shown again, the groups are asked again.
    override public func viewDidHide() {
        super.viewDidHide()
        loading?.cancel()
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
        var lines = [content.name, [content.handle, content.role, (try? standing.whoami.get())?.email].compactMap { $0 }.joined(separator: " · ")]
        lines.append("\(content.address ?? "") · \(state.said)")
        lines += [ending?.said ?? ""] + planeLines
        lines += passkeyRows.flatMap { row in [[row.label, row.thisMac ? AccountPage.thisMac : nil, rowDetail(row.id, row.detail)].compactMap { $0 }.joined(separator: " · ")] + (refusals[row.id] ?? []) }
        lines += passkeys.failure ?? []
        lines += keyRows.flatMap { row in ["\(row.display) · \(rowDetail(row.id, row.detail))"] + (refusals[row.id] ?? []) }
        lines += (keys.failure ?? []) + [storedLine].compactMap { $0 }
        return (lines + [outWords.stringValue] + signOutLines).filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// A row's detail: what it says, or that its removal is under way.
    private func rowDetail(_ id: String, _ said: String) -> String {
        removing.contains(id) ? AccountPage.removing : said
    }

    // MARK: Asking

    /// Shown (the rail's Account, the menu's Account or Keys): the groups asked again.
    func shown() {
        load()
    }

    /// Asks the server for the groups' answers while someone is signed in and the view is shown,
    /// all at once: their own passkeys, the harnesses it knows and their keys, each group drawn as
    /// its own answer lands. The load before is stopped, and only the latest one's answers are
    /// taken, for this sign-in; a group keeps its last answer until a call answers again. A refusal
    /// under a row is said until the next load.
    func load() {
        guard !isHiddenOrHasHiddenAncestor, let client = flow.client() else { return }
        loading?.cancel()
        loads += 1
        refusals = [:]
        let load = (number: loads, era: era)
        loading = Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                group.addTask { [weak self] in await self?.takePasskeys(from: client, in: load) }
                group.addTask { [weak self] in await self?.takeKeys(from: client, in: load) }
            }
            guard let self, isLatest(load), keysAfter == load.number else { return }
            keysAfter = nil
            scrollNowToKeys()
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

    /// A group's answer, drawn when it is the latest load's. A call that finds the session ended is
    /// not drawn: it hands over to the check at once, which says so where every check does, in a
    /// task of its own that neither a hide nor a new load stops.
    private func take<Value>(
        _ group: ReferenceWritableKeyPath<AccountPane, AccountPage.Answered<Value>>, in load: (number: Int, era: Int), _ work: @Sendable () async throws(SailError) -> Value
    ) async {
        let answer = await Self.result(work)
        guard isLatest(load), let plane = flow.standing?.controlPlane else { return }
        if case let .failure(error) = answer, AccountRefusal(error, at: plane, step: .asking).reason == .ended {
            acting = Task { [flow] in await flow.refresh() }
            return
        }
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

    /// Sign In Again: the sign-in ceremony at this control plane, the view kept; a refusal said in
    /// the control plane's group, else the groups asked again, as the passkey signed in with is now
    /// This Mac's.
    @objc func signInAgainNow(_: Any?) {
        signInLines = []
        guard flow.signInAgain(refused: { [weak self] refusal in
            self?.signInLines = refusal.lines
            self?.show()
        }) else { return }
        let underWay = flow.underWay
        acting = Task { [weak self] in
            await underWay?.value
            guard let self, signInLines.isEmpty else { return }
            load()
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

    /// A key the sheet stored, by `client`.
    package func stored(_ stored: StoreLogin, by client: SailClient) {
        if case .stored(_, clearsClipboard: true) = stored.stage { pasteboard.clearContents() }
        storedLine = stored.stored
        let era = self.era
        acting = Task { [weak self] in
            let inUse = await self?.useStored?(stored, client)
            guard let self, era == self.era else { return }
            if inUse == false { storedLine = stored.notUsed }
            show()
            load()
        }
    }

    /// Keys, from the user menu: the view at its keys, as far as the page scrolls, and again once
    /// the groups asked as it was shown have answered.
    func scrollToKeys() {
        keysAfter = loads
        scrollNowToKeys()
    }

    private func scrollNowToKeys() {
        layoutSubtreeIfNeeded()
        let top = Double(eyebrows[2].frame.minY) - Self.inset.top
        let most = max(Double(page.frame.height) - Double(scroll.contentSize.height), 0)
        scroll.contentView.scroll(to: NSPoint(x: 0, y: min(max(top, 0), most)))
        scroll.reflectScrolledClipView(scroll.contentView)
    }

    /// Where the view is scrolled to, for the tests and the drive.
    package var scrolledTo: Double {
        Double(scroll.contentView.bounds.minY)
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
        heading.attributedStringValue = words(content.name, titleFont.font(.title1) as NSFont, look.text, cut: true)
        disc.name = content.name
        handle.attributedStringValue = words(content.handle ?? "", labels.font(Look.Step.footnote.points) as NSFont, look.textSecondary, cut: true)
        role.words = content.role
        role.isHidden = content.role == nil
        email.attributedStringValue = words((try? standing?.whoami.get())?.email ?? "", .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, cut: true)
        showPlane(content.plane)
        showPasskeys()
        showKeys()
        outWords.attributedStringValue = words(standing.map { AccountPage.signedInHere((try? $0.whoami.get())?.fde ?? $0.fde) } ?? "", .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary)
        outNote.attributedStringValue = words(AccountPage.signOutNote, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary)
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
        planeRow = AccountRowView(.icon(.cloud), title: place, mono: true, detail: ending?.said ?? "", warns: ending?.soon == true, look: look, labels: labels)
        planeFrame.addSubview(planeRow)
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
        signInAgain.isHidden = ending?.soon != true
        signInAgain.isEnabled = !flow.isActing
    }

    /// Remove in a row's ⋯ and on ⌘⌫: off while that row's removal is under way.
    private func removal(of id: String, asking ask: @escaping @MainActor () -> Void) -> SailList.Action {
        SailList.Action("Remove", key: "\u{8}", modifiers: .command, onRow: false, destructive: true, enabled: { [weak self] in self?.removing.contains(id) == false }, run: ask)
    }

    private func showPasskeys() {
        let rows = passkeyRows
        passkeyList.entries = rows.flatMap { row -> [SailList.Entry] in
            let remove = removal(of: row.id) { [weak self] in
                self?.ask(row.removing, row: row.id) { (client) async throws(SailError) in _ = try await client.send(.removePasskey(row.id)) }
            }
            let detail = rowDetail(row.id, row.detail)
            let voice = [row.label, row.thisMac ? AccountPage.thisMac : nil, detail].compactMap { $0 }.joined(separator: ", ")
            let item = SailList.Item(
                id: row.id, name: row.label, voice: voice, content: self.rowView(.icon(.fingerprint), title: row.label, tag: row.thisMac ? AccountPage.thisMac : nil, detail: detail),
                actions: [remove], height: Self.row
            )
            return [.item(item)] + refused(row.id)
        }
        showGroup(passkeys, frame: passkeyFrame, list: passkeyList, mark: passkeyMark, said: passkeySaid, note: passkeyNote, extra: nil)
    }

    private func showKeys() {
        keyList.entries = keyRows.flatMap { row -> [SailList.Entry] in
            let store = row.stores ? SailList.Action(row.login == nil ? "Add Key" : "Replace", run: { [weak self] in self?.storeKey(row) }) : nil
            var actions = [store].compactMap(\.self)
            if let asked = row.removing, let login = row.login {
                actions.append(removal(of: row.id) { [weak self] in
                    self?.ask(asked, row: row.id) { (client) async throws(SailError) in _ = try await client.send(.removeLogin(harness: row.agent.harness, name: login)) }
                })
            }
            let offered = row.login == nil ? store : nil, detail = rowDetail(row.id, row.detail)
            let item = SailList.Item(
                id: row.id, name: row.display, voice: "\(row.display), \(detail)",
                content: self.rowView(.letters(row.monogram), title: row.display, detail: detail, offer: offered),
                actions: actions, height: Self.row, full: offered != nil
            )
            return [.item(item)] + refused(row.id)
        }
        showGroup(keys, frame: keyFrame, list: keyList, mark: keyMark, said: keySaid, note: keyNote, extra: storedLine)
    }

    /// A row of a list, made each time its list asks: an always-shown button of its own runs `offer`.
    private func rowView(_ mark: AccountRowView.Mark, title: String, tag: String? = nil, detail: String, offer: SailList.Action? = nil) -> AccountRowView {
        let row = AccountRowView(mark, title: title, tag: tag, detail: detail, look: look, labels: labels)
        if let offer {
            let run = Run(offer)
            let button = SailButton(offer.title, kind: .secondary, size: .small, icon: .plus, look: look, target: run, action: #selector(Run.go(_:)))
            button.setAccessibilityLabel("\(offer.title), \(title)")
            row.offer(button, keeping: run)
        }
        return row
    }

    /// The refusal of row `id`'s removal, as words under it, wrapped to the row, that the keys pass.
    private func refused(_ id: String) -> [SailList.Entry] {
        guard let lines = refusals[id] else { return [] }
        let width = max(listWidth - RowStyle.list.leading - RowStyle.list.trailing, 1), said = refusalWords(lines, look: look, scale: scale)
        let height = Double(ceil(said.boundingRect(with: NSSize(width: width - 4, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin]).height))
        let item = SailList.Item(
            id: id + "\u{1F}refused", name: "", voice: lines.joined(separator: " "), content: Self.wrapped(said, width: width), height: height + 2 * Self.refusalPadding, inert: true
        )
        return [.item(item)]
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
        for view in [role, state] { view.look = look }
        for button in [signInAgain, signOut] { button.look = look }
        for frame in [planeFrame, passkeyFrame, keyFrame] { frame.look = look }
        for list in [passkeyList, keyList] { list.look = look }
        for mark in [passkeyMark, keyMark] { mark.look = look }
        eyebrows.forEach { $0.textColor = NSColor(look.textSecondary) }
        page.look = look
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
        for label in [heading, handle, email] {
            label.maximumNumberOfLines = 1
            label.lineBreakMode = .byTruncatingTail
        }
        for label in [passkeySaid, keySaid, outSaid] { label.isSelectable = true }
        let views: [NSView] = [disc, heading, handle, role, email] + eyebrows + [planeFrame, passkeyFrame, passkeySaid, passkeyNote, keyFrame, keySaid, keyNote, outWords, outNote, outSaid, signOut]
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
        min(Self.column, max(Double(scroll.contentSize.width) - 2 * Self.inset.sides, 1))
    }

    /// Every frame of the column, top to bottom, left to right then turned about the page's middle
    /// right to left; the column's height.
    private func lay() -> Double {
        let width = columnWidth, x = Self.inset.sides
        var y = Self.inset.top
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
        let subHeight = max(Double(role.intrinsicContentSize.height), line(handle, width: textWidth))
        let top = y + (Self.disc - headingHeight - sub - subHeight) / 2
        put(heading, NSRect(x: text, y: top, width: textWidth, height: headingHeight))
        var subX = text
        let subY = top + headingHeight + sub
        for view in [handle, role, email] as [NSView] where !view.isHidden {
            let wanted = (view as? NSTextField).map { Double(ceil($0.attributedStringValue.size().width)) + 4 } ?? Double(view.intrinsicContentSize.width)
            let room = max(text + textWidth - subX, 0), shown = min(wanted, room)
            let height = (view as? NSTextField).map { line($0, width: shown) } ?? Double(view.intrinsicContentSize.height)
            put(view, NSRect(x: subX, y: subY + (subHeight - height) / 2, width: shown, height: height))
            subX += shown + Look.Space.s
        }
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
        for label in [outWords, outNote, outSaid] where !label.isHidden {
            let height = line(label, width: wordsWidth)
            put(label, NSRect(x: x, y: y, width: wordsWidth, height: height))
            y += height + 2
        }
        let buttonTop = start + max((y - start - Double(button.height)) / 2, 0)
        put(signOut, signOut.frame(forAlignmentRect: NSRect(x: x + width - Double(button.width), y: buttonTop, width: Double(button.width), height: Double(button.height))))
        page.rule = mirrored(NSRect(x: x, y: rule, width: width, height: 1), inWidth: pageWidth)
        return max(y, buttonTop + Double(button.height)) + Self.inset.top
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

    /// The control plane's row inside its frame, `width` wide, and the banner under it; its height.
    private func layPlane(width: Double) -> Double {
        func at(_ rect: NSRect) -> NSRect {
            mirrored(rect, inWidth: width + 2)
        }
        var trailing = 1 + width - RowStyle.list.trailing
        let pill = state.intrinsicContentSize
        state.frame = at(NSRect(x: trailing - Double(pill.width), y: 1 + (Self.row - Double(pill.height)) / 2, width: Double(pill.width), height: Double(pill.height)))
        trailing -= Double(pill.width) + Look.Space.s
        if !signInAgain.isHidden {
            let size = signInAgain.intrinsicContentSize
            signInAgain.frame = at(signInAgain.frame(forAlignmentRect: NSRect(x: trailing - Double(size.width), y: 1 + (Self.row - Double(size.height)) / 2, width: Double(size.width), height: Double(size.height))))
            trailing -= Double(size.width) + Look.Space.s
        }
        let rowHeight = Double(planeRow.intrinsicContentSize.height), lead = 1 + RowStyle.list.leading
        planeRow.frame = at(NSRect(x: lead, y: 1 + (Self.row - rowHeight) / 2, width: max(trailing - lead, 1), height: rowHeight))
        guard let banner else { return Self.row }
        let start = RowStyle.list.leading + AccountRowView.tile + AccountRowView.gap, bannerWidth = max(width - start - RowStyle.list.trailing, 1)
        banner.frame = NSRect(x: 0, y: 0, width: bannerWidth, height: 10)
        let height = Double(banner.intrinsicContentSize.height)
        banner.frame = at(NSRect(x: 1 + start, y: 1 + Self.row, width: bannerWidth, height: height))
        return Self.row + height + Self.bannerFoot
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
/// icon or a harness's monogram, the title (mono for an address) with a tag after it, the detail
/// under it in the tertiary ink (the warning's when it warns), and a button of its own at its
/// trailing edge where it offers one whatever the pointer does (Add Key). Mirrored right to left.
@MainActor
final class AccountRowView: NSView {
    enum Mark: Equatable {
        case icon(LineIcon), letters(String)
    }

    static let tile = 28.0, gap = 12.0, lineGap = 3.0

    let mark: Mark
    let title = NSTextField(labelWithString: "")
    let detail = NSTextField(labelWithString: "")
    let tagged: String?
    private(set) var offered: SailButton?
    private var keep: AnyObject?
    private let look: Look
    private let labels: LabelFont
    private let mono: Bool

    init(_ mark: Mark, title words: String, mono: Bool = false, tag: String? = nil, detail said: String = "", warns: Bool = false, look: Look, labels: LabelFont) {
        self.mark = mark
        self.tagged = tag
        self.look = look
        self.labels = labels
        self.mono = mono
        super.init(frame: .zero)
        let titleFont = mono ? labels.font(Look.Step.callout.points) as NSFont : NSFont.systemFont(ofSize: 13.5, weight: .medium)
        title.attributedStringValue = Draw.text(words, titleFont, look.text).cut(.byTruncatingTail)
        detail.attributedStringValue = Draw.text(said, .systemFont(ofSize: Look.Step.footnote.points), warns ? Tint(look.warning) : look.textTertiary).cut(.byTruncatingTail)
        for label in [title, detail] {
            label.maximumNumberOfLines = 1
            label.lineBreakMode = .byTruncatingTail
            addSubview(label)
        }
        setAccessibilityElement(false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// `button` at its trailing edge, `keeping` what it runs.
    func offer(_ button: SailButton, keeping run: AnyObject) {
        offered = button
        keep = run
        addSubview(button)
    }

    private var lines: (title: Double, detail: Double) {
        (Double(ceil(title.attributedStringValue.size().height)), Double(ceil(detail.attributedStringValue.size().height)))
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: max(Self.tile, lines.title + Self.lineGap + lines.detail))
    }

    private var tagWords: NSAttributedString? {
        tagged.map { NSAttributedString(string: $0.uppercased(), attributes: [.font: labels.font(9.5) as NSFont, .foregroundColor: NSColor(look.textTertiary), .kern: 0.95]) }
    }

    /// The tag's box after the title's words.
    var tagBox: NSRect? {
        guard let tagWords else { return nil }
        let size = tagWords.size(), words = Double(ceil(title.attributedStringValue.size().width))
        let x = Self.tile + Self.gap + min(words, Double(title.frame.width)) + 8
        return mirrored(NSRect(x: x, y: Double(title.frame.midY) - 8, width: Double(ceil(size.width)) + 10, height: 16))
    }

    private func mirrored(_ rect: NSRect) -> NSRect {
        mirrored(rect, inWidth: Double(bounds.width))
    }

    override func layout() {
        super.layout()
        let width = Double(bounds.width), height = Double(bounds.height), (titleLine, detailLine) = lines
        var end = width
        if let offered {
            let size = offered.intrinsicContentSize
            offered.frame = offered.frame(forAlignmentRect: mirrored(NSRect(x: width - Double(size.width), y: (height - Double(size.height)) / 2, width: Double(size.width), height: Double(size.height))))
            end -= Double(size.width) + Self.gap
        }
        let start = Self.tile + Self.gap, top = (height - titleLine - Self.lineGap - detailLine) / 2
        let room = max(end - start - (tagWords.map { Double(ceil($0.size().width)) + 18 } ?? 0), 1)
        title.frame = mirrored(NSRect(x: start - 2, y: top, width: min(room, Double(ceil(title.attributedStringValue.size().width)) + 4), height: titleLine))
        detail.frame = mirrored(NSRect(x: start - 2, y: top + titleLine + Self.lineGap, width: max(end - start, 1) + 4, height: detailLine))
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
        guard let box = tagBox, let tagWords else { return }
        let edge = NSBezierPath(roundedRect: box.insetBy(dx: 0.5, dy: 0.5), xRadius: 2.5, yRadius: 2.5)
        NSColor(look.separator).setStroke()
        edge.lineWidth = 1
        edge.stroke()
        let size = tagWords.size()
        tagWords.draw(at: NSPoint(x: Double(box.midX) - Double(size.width) / 2, y: Double(box.midY) - Double(size.height) / 2))
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
