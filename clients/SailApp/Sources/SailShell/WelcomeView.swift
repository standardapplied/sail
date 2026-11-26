import AppKit
import SailAPI
import SailControls
import SailLook

/// Who is signed in on this Mac, and every action that changes it: a check of the session it holds
/// (at launch and each time the app is activated), Continue, Create Passkey (which signs in at once
/// after `register/finish`), Sign In with Passkey and Sign Out. The welcome, the Account pane and
/// the user menu draw it; nothing else decides it. Only a person's action holds the buttons (#462):
/// a check never does, so a person's action is never swallowed by one; a check's answer that comes
/// during a person's action waits for it to end, and is then shown unless who is signed in was set
/// since the check began.
@MainActor
public final class AccountFlow {
    public private(set) var standing: SignedIn?
    /// Whether a ceremony or a call is under way, and how many have ended.
    public var busy: Bool {
        running > 0 || acting > 0
    }

    public private(set) var ended = 0
    /// How many checks of who is signed in are under way: a menu saying the control plane is
    /// reconnecting says so only while one is.
    public private(set) var checks = 0
    /// How many actions a person has started (a reach, an enrollment, a sign-in, a sign-out): what
    /// the drive waits on.
    public private(set) var actions = 0
    public var isActing: Bool {
        acting > 0
    }

    /// What this Mac remembers: the control plane reached and the sign-ins made there.
    public let memory: SignInMemory
    /// The person's action under way, or the last one: what a test awaits.
    package private(set) var underWay: Task<Void, Never>?
    /// Told whenever anything above changes.
    var onChange: @MainActor () -> Void = {}
    /// Who is signed in now, nil for no one, and whether a person's sign-in set it.
    var onStanding: @MainActor (SignedIn?, _ byAPerson: Bool) -> Void = { _, _ in }
    /// A check's answer once shown: who, or the refusal that left no one, with the day an ended
    /// session ended.
    var onChecked: @MainActor (Result<SignedIn?, AccountRefusal>, _ endedOn: Date?) -> Void = { _, _ in }

    private let account: Account
    private let now: @MainActor () -> Date
    private var running = 0
    private var acting = 0
    /// How many times who is signed in was set: a check that began before the latest answers too
    /// late to be shown.
    private var shown = 0
    /// A check's answer that came while a person's action was under way, taken once it ends.
    private var heldAnswer: (@MainActor () -> Void)?

    init(account: Account, memory: SignInMemory, now: @escaping @MainActor () -> Date = { Date() }) {
        self.account = account
        self.memory = memory
        self.now = now
    }

    /// Whether this Mac holds a session for the control plane it last reached: a launch then waits
    /// for the check, else it shows the welcome at once.
    var holdsASession: Bool {
        memory.plane.map { account.heldUntil(at: $0) != nil } ?? false
    }

    /// What the user menu says: who is signed in at which control plane and whether it answers;
    /// while a check asks and knows no one, that it is checking, never "Not signed in".
    var menuContent: UserMenuContent {
        let lastUsed = memory.plane?.named
        guard let standing else {
            if checks > 0, let lastUsed { return .checking(at: lastUsed) }
            return .signedOut(lastUsed: lastUsed)
        }
        let whoami = try? standing.whoami.get()
        let unreachable: Bool = if case let .failure(refusal) = standing.whoami { refusal.reason == .unreachable } else { false }
        return .signedIn(handle: whoami?.fde ?? standing.fde, address: standing.controlPlane.named, whoami: whoami, unreachable: unreachable, refreshing: checks > 0)
    }

    /// The API as the person signed in, nil when no one is or the vault cannot say.
    func client() -> SailClient? {
        standing.flatMap { try? account.client(at: $0.controlPlane) }
    }

    /// Asks the server who this Mac is signed in as at the control plane it last reached, when it
    /// holds a session there. The check holds no button. An answer that comes during a person's
    /// action waits for it to end, so nothing changes under a passkey sheet.
    public func refresh() async {
        guard !busy, let plane = memory.plane else { return }
        let seen = shown, until = account.heldUntil(at: plane)
        checks += 1
        onChange()
        defer {
            checks -= 1
            onChange()
        }
        _ = await run(byAPerson: false) { () async throws(AccountRefusal) in
            let answer: Result<SignedIn?, AccountRefusal>
            do throws(AccountRefusal) {
                answer = .success(try await self.account.current(at: plane))
            } catch {
                answer = .failure(error)
            }
            self.whenNoOneActs { self.take(answer, seen: seen, until: until) }
        }
    }

    /// A check's answer, dropped when who is signed in was set since the check began (a sign-in, a
    /// sign-out), which it would undo. An ended session ended on its own end when that had passed,
    /// else today, when the app was told.
    private func take(_ answer: Result<SignedIn?, AccountRefusal>, seen: Int, until: Date?) {
        guard shown == seen else { return }
        var endedOn: Date?
        switch answer {
        case let .success(found):
            show(found)
        case let .failure(refusal):
            forgetIfGone(refusal)
            if refusal.reason == .ended { endedOn = min(until ?? now(), now()) }
        }
        onChecked(answer, endedOn)
    }

    private func whenNoOneActs(_ go: @escaping @MainActor () -> Void) {
        guard acting > 0 else { return go() }
        heldAnswer = go
    }

    /// Continue: one unauthenticated health check of the control plane `typed` names; it is
    /// remembered only once it answered. False when another action is under way.
    @discardableResult
    func reach(_ typed: String, then done: @escaping @MainActor (Result<ControlPlane, AccountRefusal>) -> Void) -> Bool {
        act { () async throws(AccountRefusal) in
            let plane = try await self.account.reach(typed)
            self.memory.reached(plane)
            done(.success(plane))
        } refused: { done(.failure($0)) }
    }

    /// Create Passkey: enrolls with `ticket`, says whose passkey was made (`created`), then signs in
    /// with it at once, a second Touch ID.
    @discardableResult
    func enroll(ticket: String, at plane: ControlPlane, created: @escaping @MainActor (String) -> Void, refused: @escaping @MainActor (AccountRefusal) -> Void) -> Bool {
        act { () async throws(AccountRefusal) in
            let enrolled = try await self.account.enroll(ticket: ticket, at: plane)
            created(enrolled.fde)
            try await self.signIn(at: plane)
        } refused: { refused($0) }
    }

    /// Sign In with Passkey.
    @discardableResult
    func signIn(at plane: ControlPlane, refused: @escaping @MainActor (AccountRefusal) -> Void) -> Bool {
        act { () async throws(AccountRefusal) in try await self.signIn(at: plane) } refused: { refused($0) }
    }

    /// Signs out at once, asking nothing: what the sign-in opened on the runner lives on there, for
    /// the same person's next sign-in. The refusal when the vault would not forget it.
    func signOut() -> AccountRefusal? {
        guard let plane = standing?.controlPlane, acting == 0 else { return nil }
        begin()
        defer {
            ended += 1
            end()
        }
        do throws(AccountRefusal) {
            try account.signOut(at: plane)
            show(nil)
            return nil
        } catch {
            return error
        }
    }

    /// Signed in at `plane`: the session kept, the person remembered for the next welcome there.
    private func signIn(at plane: ControlPlane) async throws(AccountRefusal) {
        let signedIn = try await account.signIn(at: plane)
        let whoami = try? signedIn.whoami.get()
        let name = [whoami?.displayName, whoami?.name].compactMap { $0 }.first { !$0.isEmpty }
        memory.signedIn(RememberedSignIn(handle: whoami?.fde ?? signedIn.fde, name: name), at: plane)
        show(signedIn, byAPerson: true)
    }

    /// Starts `work`, a person's action, before the click or Return that started it returns: a
    /// second one starts nothing until it ends.
    private func act(_ work: @escaping @MainActor () async throws(AccountRefusal) -> Void, refused: @escaping @MainActor (AccountRefusal) -> Void) -> Bool {
        guard acting == 0 else { return false }
        begin()
        underWay = Task {
            if let refusal = await run(work) { refused(refusal) }
        }
        return true
    }

    private func begin() {
        acting += 1
        actions += 1
        onChange()
    }

    /// A person's action ends: a check's answer that came meanwhile is taken.
    private func end() {
        acting -= 1
        onChange()
        guard acting == 0, let held = heldAnswer else { return }
        heldAnswer = nil
        held()
    }

    /// Runs `act`, a person's action (``begin()`` already called) or (not `byAPerson`) a check;
    /// its refusal, nil when it ended well.
    private func run(byAPerson person: Bool = true, _ act: @MainActor () async throws(AccountRefusal) -> Void) async -> AccountRefusal? {
        running += 1
        onChange()
        defer {
            running -= 1
            if person { end() }
            ended += 1
            onChange()
        }
        do throws(AccountRefusal) {
            try await act()
            return nil
        } catch {
            forgetIfGone(error)
            return error
        }
    }

    /// No one is signed in when the session ended or could not be read.
    private func forgetIfGone(_ refusal: AccountRefusal) {
        if [.ended, .unreadableSignIn].contains(refusal.reason) { show(nil) }
    }

    /// Shows who is signed in. The drive shows one of its own for the frames of long words.
    package func show(_ signedIn: SignedIn?, byAPerson: Bool = false) {
        shown += 1
        standing = signedIn
        onStanding(signedIn, byAPerson)
        onChange()
    }
}

/// The signed-out Workspaces card (section 1 of the first impressions page): a centred 380 pt
/// column asking one question a screen (``Welcome``) with the app's version at the card's foot.
/// Welcome: Sail's still mark, the Newsreader title, the CONTROL PLANE field (holding the keys),
/// Continue and a quiet line whose link opens Terminals. Enroll: the mark, the reached control plane
/// as a chip with Change, the ENROLLMENT TICKET field and Create Passkey. Welcome back: the title by
/// name, Sign In with Passkey, the chip centred under it. Under way, the primary says its "-ing"
/// word with its spinner and never narrows, the field dims and takes no input, and a note over the
/// primary says why the system's sheet is up. A refusal stands under the field it is about (on
/// Welcome back, under the primary): its sentence in crimson with the error's mark, its remedy in
/// the secondary ink. Each screen's own link leads to the other screen.
@MainActor
public final class WelcomeView: NSView {
    static let column = 380.0

    public private(set) var welcome: Welcome
    public let flow: AccountFlow
    /// Terminals, from the welcome's quiet line.
    var onTerminals: @MainActor () -> Void = {}

    /// The look it is drawn in; a new one repaints it, once it is shown.
    var look: Look {
        didSet { painter.lookChanged(hidden: isHidden) }
    }

    private lazy var painter = PaintWhenShown { [unowned self] in self.paint() }
    /// How many times it was painted: none while it is hidden at a change of look.
    package private(set) var lookPaints = 0

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        painter.shown()
    }

    let mark: BrandMark
    /// The control plane over Enroll's title, and under Welcome back's primary, centred.
    package let chip: PlaneChip
    package let chipUnder: PlaneChip
    let heading = NSTextField(wrappingLabelWithString: "")
    let lede = NSTextField(wrappingLabelWithString: "")
    package let addressField: SailTextField
    package let ticketField: SailTextField
    package let proceed: SailButton
    package let create: SailButton
    package let signIn: SailButton
    let note: TouchNote
    let said = NSTextField(wrappingLabelWithString: "")
    package let toTerminals: LinkButton
    package let toSignIn: LinkButton
    package let toEnroll: LinkButton
    let version: NSTextField
    private(set) var banner: Banner?

    private let titleFont: TitleFont
    private let mono: LabelFont
    private let column = NSStackView()
    private let other = NSStackView()
    private let before = NSTextField(labelWithString: "")
    private let after = NSTextField(labelWithString: "")
    private lazy var chipRow = Self.holding(chip, centred: false)
    private lazy var chipUnderRow = Self.holding(chipUnder, centred: true)

    init(flow: AccountFlow, look: Look, title: TitleFont, mono: LabelFont, version: String?) {
        self.flow = flow
        self.look = look
        titleFont = title
        self.mono = mono
        welcome = flow.memory.welcome
        mark = BrandMark(.still, look: look, mono: mono)
        chip = PlaneChip(look: look, mono: mono)
        chipUnder = PlaneChip(look: look, mono: mono)
        let address = Welcome.addressField, ticket = Welcome.ticketField
        addressField = SailTextField(label: address.label, placeholder: address.placeholder, size: .large, look: look, mono: mono)
        ticketField = SailTextField(label: ticket.label, placeholder: ticket.placeholder, size: .large, secure: ticket.secure, look: look, mono: mono)
        proceed = SailButton("Continue", kind: .primary, size: .large, look: look, target: nil, action: nil)
        create = SailButton("Create Passkey", kind: .primary, size: .large, icon: .fingerprint, look: look, target: nil, action: nil)
        signIn = SailButton("Sign In with Passkey", kind: .primary, size: .large, icon: .fingerprint, look: look, target: nil, action: nil)
        note = TouchNote(look: look)
        toTerminals = LinkButton("Open Terminals", look: look, target: nil, action: nil)
        toSignIn = LinkButton("Sign In with Passkey", look: look, target: nil, action: nil)
        toEnroll = LinkButton("Enroll with a Ticket", look: look, target: nil, action: nil)
        self.version = NSTextField(labelWithString: Welcome.version(version))
        super.init(frame: .zero)
        compose()
        render()
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    /// Everything the welcome says now, a line each, as the drive reads it: the title, the
    /// control plane when its chip shows, the banner, the note and the refusal.
    public var text: String {
        let host = welcome.screen == .address ? nil : welcome.host
        return ([welcome.title, host, welcome.banner, welcome.note?.words] + welcome.said.map(Optional.some)).compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// The primary shown now.
    public var primary: SailButton {
        switch welcome.screen {
        case .address: proceed
        case .ticket: create
        case .back: signIn
        }
    }

    /// The chip shown now, nil on Welcome.
    package var shownChip: PlaneChip? {
        switch welcome.screen {
        case .address: nil
        case .ticket: chip
        case .back: chipUnder
        }
    }

    /// The field shown now, nil on Welcome back.
    public var field: SailTextField? {
        switch welcome.screen {
        case .address: addressField
        case .ticket: ticketField
        case .back: nil
        }
    }

    /// The link of the quiet line shown now.
    public var link: LinkButton {
        switch welcome.screen {
        case .address: toTerminals
        case .ticket: toSignIn
        case .back: toEnroll
        }
    }

    /// Signed out again: the screen this Mac's memory chooses, the field holding the keys.
    package func reset() {
        welcome = flow.memory.welcome
        render(claim: true)
    }

    /// A check's answer that left no one: an ended session's banner on Welcome back, or its refusal
    /// under the primary there.
    func checked(_ answer: Result<SignedIn?, AccountRefusal>, endedOn: Date?) {
        guard case let .failure(refusal) = answer, flow.standing == nil else { return }
        welcome = flow.memory.welcome
        if let endedOn {
            welcome.ended(on: endedOn)
        } else {
            welcome.signInInstead()
            welcome.refused(refusal)
        }
        render()
    }

    /// The drive's frames: any state, drawn as the flow would leave it.
    package func show(_ state: Welcome) {
        welcome = state
        render()
    }

    /// Continue: the control plane typed, reached.
    @objc func reach(_: Any?) {
        guard !flow.isActing else { return }
        let typed = addressField.field.stringValue
        welcome.began(.connecting)
        render()
        flow.reach(typed) { [weak self] result in
            guard let self else { return }
            switch result {
            case let .success(plane): welcome.reached(plane, person: flow.memory.signIn(at: plane))
            case let .failure(refusal): welcome.refused(refusal)
            }
            render(claim: true)
        }
    }

    /// Create Passkey: the ticket is read and cleared from the field at once.
    @objc func createPasskey(_: Any?) {
        guard !flow.isActing, let plane = welcome.plane else { return }
        let pasted = ticketField.field.stringValue
        ticketField.field.stringValue = ""
        welcome.began(.creating)
        render()
        flow.enroll(ticket: pasted, at: plane) { [weak self] handle in
            self?.welcome.began(.signingAfterEnrolling(handle: handle))
            self?.render()
        } refused: { [weak self] refusal in
            self?.welcome.refused(refusal)
            self?.render(claim: true)
        }
    }

    /// Sign In with Passkey, the primary of Welcome back or the link of Enroll: the sign-in runs at
    /// once, on Welcome back.
    @objc func signInWithPasskey(_: Any?) {
        guard !flow.isActing, let plane = welcome.plane else { return }
        if welcome.screen != .back { welcome.signInInstead() }
        welcome.began(.signingIn)
        render()
        flow.signIn(at: plane) { [weak self] refusal in
            self?.welcome.refused(refusal)
            self?.render()
        }
    }

    @objc func enrollInstead(_: Any?) {
        guard !flow.isActing else { return }
        welcome.enrollInstead()
        render(claim: true)
    }

    /// Change: Welcome, the field holding the control plane, selected.
    @objc func change(_: Any?) {
        guard !flow.isActing else { return }
        let typed = welcome.typed
        welcome.change()
        render()
        addressField.field.stringValue = typed
        if window?.makeFirstResponder(addressField.field) == true { addressField.field.selectText(nil) }
    }

    @objc func openTerminals(_: Any?) {
        onTerminals()
    }

    /// Draws `welcome`; `claim`, its field takes the keys when the window is key.
    private func render(claim: Bool = false) {
        let screen = welcome.screen, busy = welcome.stage != nil
        mark.isHidden = !welcome.showsMark
        chipRow.isHidden = screen != .ticket
        chipUnderRow.isHidden = screen != .back
        for each in [chip, chipUnder] {
            each.show(welcome.host, answers: welcome.answers)
            each.change.isEnabled = !busy
        }
        heading.attributedStringValue = Self.words(welcome.title, titleFont.font(.display) as NSFont, look.text, lineHeight: 1.1, tracking: -0.012)
        lede.attributedStringValue = Self.words(welcome.lede, .systemFont(ofSize: Look.Step.callout.points), look.textSecondary, lineHeight: 1.5)
        for (field, shown) in [(addressField, screen == .address), (ticketField, screen == .ticket)] {
            field.isHidden = !shown
            guard shown, let words = welcome.field else { continue }
            field.help = words.help
            field.error = welcome.said.first
            field.remedy = welcome.said.dropFirst().first
            field.isEnabled = !busy
        }
        for button in [proceed, create, signIn] {
            button.isHidden = button !== primary
            button.fly(button === primary ? welcome.busyWord : nil)
        }
        note.isHidden = welcome.note == nil
        note.show(welcome.note)
        showBanner(welcome.banner)
        said.isHidden = screen != .back || welcome.said.isEmpty
        said.attributedStringValue = refusalWords(welcome.said, look: look, scale: Double(window?.backingScaleFactor ?? 2))
        for link in [toTerminals, toSignIn, toEnroll] {
            link.isHidden = link !== self.link
            link.isEnabled = !busy
        }
        before.stringValue = welcome.other.before + " "
        after.stringValue = welcome.other.after.map { " " + $0 } ?? ""
        after.isHidden = welcome.other.after == nil
        guard claim, let field, window?.isKeyWindow == true else { return }
        window?.makeFirstResponder(field.field)
    }

    /// Takes the keys into the field shown, as the welcome opens.
    func claimKeys() {
        guard let field else { return }
        window?.makeFirstResponder(field.field)
    }

    /// The session-ended banner, over Welcome back's primary, after the lede.
    private func showBanner(_ words: String?) {
        guard words != banner?.accessibilityLabel() else { return }
        banner?.removeFromSuperview()
        banner = words.map { Banner(.info, title: $0, look: look) }
        guard let banner, let at = column.arrangedSubviews.firstIndex(of: lede) else { return }
        column.insertArrangedSubview(banner, at: at + 1)
        fill(banner)
    }

    /// `chip` in a row as wide as the column, at its leading edge or centred.
    private static func holding(_ chip: PlaneChip, centred: Bool) -> NSView {
        let row = NSView()
        chip.translatesAutoresizingMaskIntoConstraints = false
        row.addSubview(chip)
        NSLayoutConstraint.activate([
            chip.topAnchor.constraint(equalTo: row.topAnchor), chip.bottomAnchor.constraint(equalTo: row.bottomAnchor),
            centred ? chip.centerXAnchor.constraint(equalTo: row.centerXAnchor) : chip.leadingAnchor.constraint(equalTo: row.leadingAnchor),
            chip.leadingAnchor.constraint(greaterThanOrEqualTo: row.leadingAnchor), chip.trailingAnchor.constraint(lessThanOrEqualTo: row.trailingAnchor),
        ])
        return row
    }

    private func paint() {
        lookPaints += 1
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        mark.look = look
        chip.look = look
        chipUnder.look = look
        note.look = look
        banner?.look = look
        for field in [addressField, ticketField] {
            field.look = look
        }
        for button in [proceed, create, signIn] {
            button.look = look
        }
        for link in [toTerminals, toSignIn, toEnroll] {
            link.look = look
        }
        for label in [before, after] {
            label.font = .systemFont(ofSize: Look.Step.footnote.points)
            label.textColor = NSColor(look.textTertiary)
        }
        version.font = mono.font(Look.Step.micro.points, .regular) as NSFont
        version.textColor = NSColor(look.textTertiary)
        render()
    }

    static func words(_ text: String, _ font: NSFont, _ ink: Tint, lineHeight: Double, tracking: Double = 0) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.alignment = .natural
        style.minimumLineHeight = (lineHeight * Double(font.pointSize)).rounded()
        style.maximumLineHeight = style.minimumLineHeight
        return NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style, .kern: tracking * Double(font.pointSize)])
    }

    /// `view` as wide as the column.
    private func fill(_ view: NSView) {
        view.widthAnchor.constraint(equalTo: column.widthAnchor).isActive = true
    }

    private func compose() {
        wantsLayer = true
        proceed.target = self
        proceed.action = #selector(reach(_:))
        create.target = self
        create.action = #selector(createPasskey(_:))
        signIn.target = self
        signIn.action = #selector(signInWithPasskey(_:))
        addressField.field.target = self
        addressField.field.action = #selector(reach(_:))
        ticketField.field.target = self
        ticketField.field.action = #selector(createPasskey(_:))
        toTerminals.target = self
        toTerminals.action = #selector(openTerminals(_:))
        toSignIn.target = self
        toSignIn.action = #selector(signInWithPasskey(_:))
        toEnroll.target = self
        toEnroll.action = #selector(enrollInstead(_:))
        for each in [chip, chipUnder] {
            each.change.target = self
            each.change.action = #selector(change(_:))
        }
        said.isSelectable = true
        for label in [heading, lede, said] {
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        other.orientation = .horizontal
        other.spacing = 0
        other.setViews([before, toTerminals, toSignIn, toEnroll, after], in: .center)
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = Look.Space.xxl
        column.setViews([mark, chipRow, heading, lede, addressField, ticketField, note, proceed, create, signIn, said, chipUnderRow, other], in: .top)
        column.setCustomSpacing(Look.Space.s, after: heading)
        column.translatesAutoresizingMaskIntoConstraints = false
        version.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        addSubview(version)
        for view in [chipRow, heading, lede, addressField, ticketField, note, proceed, create, signIn, said, chipUnderRow, other] as [NSView] {
            fill(view)
        }
        let wide = column.widthAnchor.constraint(equalToConstant: Self.column)
        wide.priority = .defaultHigh
        let centred = column.centerYAnchor.constraint(equalTo: centerYAnchor)
        centred.priority = .defaultHigh
        NSLayoutConstraint.activate([
            wide, centred, column.centerXAnchor.constraint(equalTo: centerXAnchor),
            column.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -2 * Look.Space.l),
            column.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: Look.Space.l),
            column.bottomAnchor.constraint(lessThanOrEqualTo: version.topAnchor, constant: -Look.Space.l),
            version.centerXAnchor.constraint(equalTo: centerXAnchor), version.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Look.Space.l),
        ])
        paint()
    }
}

/// A refusal's lines as they stand under what they are about: the sentence in crimson after the
/// error's 14 pt mark, the rest in the secondary ink.
@MainActor
func refusalWords(_ lines: [String], look: Look, scale: Double) -> NSAttributedString {
    let words = NSMutableAttributedString(), font = NSFont.systemFont(ofSize: Look.Step.footnote.points)
    for (index, line) in lines.enumerated() {
        let ink = NSColor(index == 0 ? Tint(look.error) : look.textSecondary)
        if index == 0 {
            let side = FieldSize.mark
            let mark = NSTextAttachment()
            mark.image = MarkImage.image(.error, side: side, ink: Tint(look.error), scale: scale)
            mark.bounds = NSRect(x: 0, y: (Double(font.capHeight) - side) / 2, width: side, height: side)
            words.append(NSAttributedString(attachment: mark))
            words.append(NSAttributedString(string: " "))
        }
        words.append(NSAttributedString(string: (index == 0 ? "" : "\n") + line, attributes: [.foregroundColor: ink, .font: font]))
    }
    words.addAttribute(.font, value: font, range: NSRange(location: 0, length: words.length))
    return words
}

/// The control plane reached, as one chip: the cloud, the host in mono, a 6 pt square in its
/// state's colour (green once it answered, crimson when it did not, quiet before it was asked),
/// and Change. Mirrored right to left.
@MainActor
package final class PlaneChip: NSView {
    var look: Look {
        didSet { paint() }
    }

    package let change: LinkButton
    private let cloud = NSImageView()
    private let host = NSTextField(labelWithString: "")
    private let square = NSView()
    private let mono: LabelFont
    private(set) var answers: Bool?

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        change = LinkButton("Change", look: look, target: nil, action: nil)
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        layer?.borderWidth = Look.hairline
        square.wantsLayer = true
        let row = NSStackView(views: [cloud, host, square, change])
        row.orientation = .horizontal
        row.spacing = Look.Space.s
        row.setCustomSpacing(Look.Space.m, after: square)
        row.edgeInsets = NSEdgeInsets(top: 0, left: Look.Space.s, bottom: 0, right: Look.Space.s)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor), row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            heightAnchor.constraint(equalToConstant: FieldSize.small.height),
            cloud.widthAnchor.constraint(equalToConstant: FieldSize.mark), cloud.heightAnchor.constraint(equalToConstant: FieldSize.mark),
            square.widthAnchor.constraint(equalToConstant: ControlPlaneRow.square), square.heightAnchor.constraint(equalToConstant: ControlPlaneRow.square),
        ])
        host.lineBreakMode = .byTruncatingMiddle
        host.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
        paint()
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    package var said: String {
        host.stringValue
    }

    func show(_ words: String, answers: Bool?) {
        host.stringValue = words
        self.answers = answers
        setAccessibilityLabel(words)
        paint()
    }

    /// The square's ink: green once the control plane answered, crimson when it did not.
    var ink: Tint {
        switch answers {
        case true?: Tint(look.success)
        case false?: Tint(look.error)
        case nil: look.textTertiary
        }
    }

    override package func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        paint()
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.surface).cgColor
        layer?.borderColor = NSColor(look.separator).cgColor
        square.layer?.backgroundColor = NSColor(ink).cgColor
        cloud.image = MarkImage.image(.cloud, side: FieldSize.mark, ink: look.textSecondary, scale: Double(window?.backingScaleFactor ?? 2))
        host.font = mono.font(Look.Step.footnote.points, .medium) as NSFont
        host.textColor = NSColor(look.text)
        change.look = look
    }
}

/// The one-line note over the primary while a system sheet is up: its icon (a fingerprint while
/// Touch ID asks, a check once the passkey is made) and its words, in a surface box with a
/// separator edge.
@MainActor
final class TouchNote: NSView {
    var look: Look {
        didSet { paint() }
    }

    private let icon = NSImageView()
    private let words = NSTextField(wrappingLabelWithString: "")
    private var shown: Welcome.Note?

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        layer?.borderWidth = Look.hairline
        let row = NSStackView(views: [icon, words])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.spacing = Look.Space.s
        row.edgeInsets = NSEdgeInsets(top: Look.Space.s, left: Look.Space.m, bottom: Look.Space.s, right: Look.Space.m)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor), row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            icon.widthAnchor.constraint(equalToConstant: Look.Step.title3.points), icon.heightAnchor.constraint(equalToConstant: Look.Step.title3.points),
        ])
        words.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        paint()
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    func show(_ note: Welcome.Note?) {
        shown = note
        words.stringValue = note?.words ?? ""
        setAccessibilityLabel(note?.words)
        paint()
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        paint()
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.surface).cgColor
        layer?.borderColor = NSColor(look.separator).cgColor
        words.font = .systemFont(ofSize: Look.Step.footnote.points)
        words.textColor = NSColor(look.textSecondary)
        let side = Look.Step.title3.points, scale = Double(window?.backingScaleFactor ?? 2)
        icon.image = MarkImage.image(shown?.created == true ? .check : .fingerprint, side: side, ink: look.text, scale: scale)
    }
}
