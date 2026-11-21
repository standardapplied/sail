import AppKit
import SailAPI
import SailControls
import SailLook
import SailTerminalView

/// The account, in the user menu's language. Signed out: "Sign in to Sail" as the display heading,
/// the control plane's address under its mono eyebrow, Sign In with Passkey (the one primary), and
/// Enroll with a Ticket (secondary; a secure field, cleared as soon as it is read). Signed in: the
/// person's name as the heading, `@handle · Role` under SIGNED IN AS, the control plane as the menu's
/// line under CONTROL PLANE (its address, state square and word, live as checks start and end), the
/// session's end in quiet text, and Sign Out (secondary). A refusal is said under the field it is
/// about (the control plane's, the ticket's, or signed in the control plane's line), its sentence in
/// crimson and the rest in the secondary ink, line by line: what to do, the server's words, the
/// system's; never a tinted row. The field being edited takes a ring
/// in the accent, its selection the selection fill and its caret the accent (set on the field
/// editor once the field has set it up, as it resets them). Return in a field sends that field's
/// own action, Sign In from the address and Enroll from the ticket: no button is the window's default,
/// which AppKit would paint in the system accent.
@MainActor
public final class AccountPane: NSView, NSMenuItemValidation, NSTextFieldDelegate {
    static let addressKey = "controlPlane.address"

    public let address = NSTextField()
    public let ticket = NSSecureTextField()
    public let signIn: SailButton
    public let enroll: SailButton
    public let signOut: SailButton
    public let said = NSTextField(wrappingLabelWithString: "")
    /// Whether what is said is a refusal, its sentence then in crimson.
    private var refused = false
    /// The field what a person's action says is about: the control plane's, or the ticket's.
    private var about: NSView?
    /// Where the lines said now sit, kept with them: said again unchanged (a change of look, the
    /// signed-out rows shown again) they stay there, after the action that placed them has ended.
    private var saidUnder: NSView?
    public private(set) var standing: SignedIn?
    /// Whether a ceremony or a call is under way, and how many have ended.
    public var busy: Bool {
        running > 0 || acting > 0
    }

    public private(set) var ended = 0
    /// How many checks of who is signed in are under way, told as it changes: a menu saying the
    /// control plane is reconnecting says so only while one is.
    public private(set) var checks = 0
    var onChecking: @MainActor () -> Void = {}
    /// How many actions a person has started here (a sign-in, an enrollment, a sign-out): what the
    /// drive waits on, and what a check's late answer is said after.
    public private(set) var actions = 0
    /// Whether an action a person started is under way.
    public var isActing: Bool {
        acting > 0
    }

    private var running = 0
    private var acting = 0
    /// How many times who is signed in was set here: a check that began before the latest answers
    /// too late to be shown.
    private var shown = 0
    /// A check's answer that came while a person's action was under way, taken once it ends.
    private var heldAnswer: (@MainActor () -> Void)?
    /// Who is signed in now, nil for no one: said each time the pane learns it.
    var onStanding: @MainActor (SignedIn?) -> Void = { _ in }

    private let account: Account
    private let defaults: UserDefaults
    /// The look it is drawn in; a new one repaints it.
    var look: Look {
        didSet { painter.lookChanged(hidden: isHidden) }
    }

    private lazy var painter = PaintWhenShown { [unowned self] in self.paint() }

    /// How many times the pane was painted, wherever from: none while it is hidden at a change of
    /// look.
    package private(set) var lookPaints = 0

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        painter.shown()
    }

    private let mono: TerminalFonts
    /// What the pane says now beside who is signed in, a line each: why it could not ask, say.
    private(set) var lines: [String] = []
    /// The facts' mono eyebrows, signed out and signed in.
    private(set) var eyebrows: [NSTextField] = []
    private(set) lazy var addressFrame = FieldFrame(address, look: look)
    private(set) lazy var ticketFrame = FieldFrame(ticket, look: look)
    /// The display heading: who is signed in, or "Sign in to Sail"; cut at its end, said in full on
    /// hover.
    let heading = NSTextField(labelWithString: "Sign in to Sail")
    /// `@handle · Role`, signed in; cut at its end, said in full on hover.
    let handle = NSTextField(labelWithString: "")
    private(set) lazy var headingTip = Tooltip("", look: look, mono: nil)
    private(set) lazy var handleTip = Tooltip("", look: look, mono: nil)
    private let headingFont: NSFont
    /// The control plane as the user menu draws it, signed in.
    let planeRow: ControlPlaneRow
    /// Until when the session lasts, or that it is not yet confirmed, in quiet text that wraps.
    let until = NSTextField(wrappingLabelWithString: "")
    private let signedOutRows = NSStackView()
    private let signedInRows = NSStackView()

    init(account: Account, look: Look, title: TitleFont, mono: TerminalFonts, labels: LabelFont, defaults: UserDefaults = .standard) {
        self.account = account
        self.defaults = defaults
        self.look = look
        self.mono = mono
        planeRow = ControlPlaneRow(look: look, mono: labels, points: Look.Step.callout.points)
        headingFont = title.font(.title2) as NSFont
        signIn = SailButton("Sign In with Passkey", kind: .primary, look: look, target: nil, action: #selector(AccountPane.signInWithPasskey(_:)))
        enroll = SailButton("Enroll with a Ticket", kind: .secondary, look: look, target: nil, action: nil)
        signOut = SailButton("Sign Out", kind: .secondary, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        signIn.target = self
        compose()
        address.stringValue = defaults.string(forKey: Self.addressKey) ?? ""
        show(nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Everything the pane says now, a line each, as the drive reads it.
    public var text: String {
        let facts = standing == nil ? [] : [handle.stringValue, "\(planeRow.said) · \(planeRow.plane?.said ?? "")", until.stringValue]
        return ([heading.stringValue] + facts + lines).filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// What the user menu says, from what the pane knows: who is signed in at which control plane
    /// and whether it answers; while it is still asking and knows no one, that it is checking, never
    /// "Not signed in". The pane's own heading, handle and control plane line are drawn from it too,
    /// so the two never disagree.
    var menuContent: UserMenuContent {
        let lastUsed = defaults.string(forKey: Self.addressKey).flatMap { try? ControlPlane(address: $0) }.map(\.named)
        guard let standing else {
            if checks > 0, let lastUsed { return .checking(at: lastUsed) }
            return .signedOut(lastUsed: lastUsed)
        }
        let whoami = try? standing.whoami.get()
        let unreachable: Bool = if case let .failure(refusal) = standing.whoami { refusal.reason == .unreachable } else { false }
        return .signedIn(handle: whoami?.fde ?? standing.fde, address: standing.controlPlane.named, whoami: whoami, unreachable: unreachable, refreshing: checks > 0)
    }

    /// Asks the server who this Mac is signed in as at the control plane it last used, when it
    /// holds a session there; what is typed in the field and not yet used is not asked about. The
    /// check holds no button: a person may act while it is under way. An answer that comes during
    /// a person's action waits for it to end, so the pane never changes under a passkey sheet.
    public func refresh() async {
        guard !busy, let used = defaults.string(forKey: Self.addressKey), let plane = try? ControlPlane(address: used) else { return }
        let acted = actions, seen = shown
        checks += 1
        checking()
        defer {
            checks -= 1
            checking()
        }
        await run(byAPerson: false) { () async throws(AccountRefusal) in
            let answer: Result<SignedIn?, AccountRefusal>
            do throws(AccountRefusal) {
                answer = .success(try await self.account.current(at: plane))
            } catch {
                answer = .failure(error)
            }
            self.whenNoOneActs { self.take(answer, acted: acted, seen: seen) }
        }
    }

    /// A check's answer, dropped when who is signed in was set since the check began (a sign-in, a
    /// sign-out), which it would undo. Otherwise shown, and what it says (why the session ended,
    /// why it is not yet confirmed) said after what a person's action said meanwhile.
    private func take(_ answer: Result<SignedIn?, AccountRefusal>, acted: Int, seen: Int) {
        guard shown == seen else { return }
        if actions == acted {
            about = nil
            say([])
        }
        switch answer {
        case let .success(found):
            show(found)
        case let .failure(refusal):
            forgetIfGone(refusal)
            say(lines + refusal.lines, refused: true)
        }
    }

    /// A check started or ended: the control plane's line says so, and so does the menu.
    private func checking() {
        paintFacts()
        onChecking()
    }

    private func whenNoOneActs(_ go: @escaping @MainActor () -> Void) {
        guard acting > 0 else { return go() }
        heldAnswer = go
    }

    @objc public func signInWithPasskey(_: Any?) {
        guard acting == 0 else { return }
        about = addressFrame
        begin()
        Task {
            await run { () async throws(AccountRefusal) in
                let plane = try self.remembered()
                self.show(try await self.account.signIn(at: plane))
            }
        }
    }

    @objc public func enrollWithTicket(_: Any?) {
        guard acting == 0 else { return }
        let pasted = ticket.stringValue
        ticket.stringValue = ""
        about = ticketFrame
        begin()
        Task {
            await run { () async throws(AccountRefusal) in
                let plane = try self.remembered()
                let enrolled = try await self.account.enroll(ticket: pasted, at: plane)
                self.say(["A passkey for @\(enrolled.fde) is on this Mac.", Self.signInWithIt], refused: false)
            }
        }
    }

    /// Signs out at once, asking nothing: what the sign-in opened on the runner lives on there,
    /// for the same person's next sign-in.
    @objc public func signOutHere(_: Any?) {
        guard standing != nil, acting == 0 else { return }
        begin(clearing: false)
        signOutNow()
        end()
    }

    private func signOutNow() {
        guard let plane = standing?.controlPlane else { return }
        do {
            try account.signOut(at: plane)
            show(nil)
            say([])
        } catch {
            say(error.lines, refused: true)
        }
        ended += 1
    }

    /// The API as the person signed in, nil when no one is or the vault cannot say.
    func client() -> SailClient? {
        standing.flatMap { try? account.client(at: $0.controlPlane) }
    }

    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        item.action != #selector(signOutHere(_:)) || (standing != nil && acting == 0)
    }

    private func remembered() throws(AccountRefusal) -> ControlPlane {
        let plane = try ControlPlane(address: address.stringValue)
        address.stringValue = plane.description
        defaults.set(plane.description, forKey: Self.addressKey)
        return plane
    }

    /// A person's action begins, before the click or Return that started it returns: the buttons
    /// are held, so a second one starts nothing, and (`clearing`) what the pane said is cleared.
    private func begin(clearing: Bool = true) {
        acting += 1
        actions += 1
        if clearing { say([]) }
        holdTheButtons()
    }

    /// A person's action ends: the buttons are offered again once no other is under way, and a
    /// check's answer that came meanwhile is taken.
    private func end() {
        acting -= 1
        about = nil
        holdTheButtons()
        guard acting == 0, let held = heldAnswer else { return }
        heldAnswer = nil
        held()
    }

    /// Runs `act`, a person's action (``begin()`` already called) or (not `byAPerson`) a check of
    /// who is signed in. Only a person's action holds the buttons.
    private func run(byAPerson person: Bool = true, _ act: @MainActor () async throws(AccountRefusal) -> Void) async {
        running += 1
        holdTheButtons()
        defer {
            running -= 1
            if person { end() }
            ended += 1
        }
        do {
            try await act()
        } catch {
            forgetIfGone(error)
            say(error.lines, refused: true)
        }
    }

    /// No one is signed in when the session ended or could not be read.
    private func forgetIfGone(_ refusal: AccountRefusal) {
        if [.ended, .unreadableSignIn].contains(refusal.reason) { show(nil) }
    }

    /// The buttons are held only while an action a person started is under way.
    private func holdTheButtons() {
        [signIn, enroll, signOut].forEach { $0.isEnabled = acting == 0 }
    }

    /// Shows who is signed in, and why `whoami` could not confirm it, after what the pane says;
    /// "Sign in with it." is never said beside someone signed in. The drive shows one of its own for
    /// the frames of long words at the least window.
    package func show(_ signedIn: SignedIn?) {
        shown += 1
        standing = signedIn
        signedOutRows.isHidden = signedIn != nil
        signedInRows.isHidden = signedIn == nil
        onStanding(signedIn)
        guard let signedIn else {
            paintFacts()
            say(lines, refused: refused)
            return
        }
        let whoami = try? signedIn.whoami.get()
        let ends = signedIn.until.formatted(date: .long, time: .shortened)
        until.stringValue = whoami == nil ? "Not yet confirmed: remembered on this Mac until \(ends)" : "Signed in until \(ends)"
        paintFacts()
        var now = lines.filter { $0 != Self.signInWithIt }
        var refusing = refused && !now.isEmpty
        if case let .failure(refusal) = signedIn.whoami {
            now += refusal.lines
            refusing = true
        }
        say(now, refused: refusing)
    }

    /// The heading, the handle and the control plane's line, from what the menu says: the person's
    /// name, `@handle · Role`, the address and whether it answers (reconnecting while a check asks
    /// again after it did not); signed out, "Sign in to Sail".
    private func paintFacts() {
        let content = menuContent
        let signedIn = standing != nil
        set(heading, to: signedIn ? content.name : "Sign in to Sail", font: headingFont, ink: look.text, tip: headingTip)
        set(handle, to: signedIn ? content.detail ?? "" : "", font: monospaced(.callout), ink: look.textSecondary, tip: handleTip)
        if signedIn, let address = content.address, let plane = content.plane { planeRow.show(address: address, plane: plane) }
    }

    /// `label` saying `words` on one line, cut at its end, with `tip` saying them in full when cut.
    private func set(_ label: NSTextField, to words: String, font: NSFont, ink: Tint, tip: Tooltip) {
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byTruncatingTail
        label.attributedStringValue = NSAttributedString(string: words, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style])
        tip.text = words
    }

    static let signInWithIt = "Sign in with it."

    private func paint() {
        lookPaints += 1
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        until.textColor = NSColor(look.textTertiary)
        headingTip.look = look
        handleTip.look = look
        paintFacts()
        eyebrows.forEach { $0.textColor = NSColor(look.textSecondary) }
        planeRow.look = look
        signIn.look = look
        enroll.look = look
        signOut.look = look
        addressFrame.look = look
        ticketFrame.look = look
        say(lines, refused: refused)
    }

    /// Says `lines` under the field they are about (signed in, the control plane's line), the first
    /// in crimson when `refused`, else in ink, the rest in the secondary ink. Lines said again
    /// unchanged stay under the field they were first said under, while it is shown, and leave that
    /// field as it is: refused, or corrected since by the person.
    private func say(_ lines: [String], refused: Bool = false) {
        let shown = standing == nil ? signedOutRows : signedInRows
        let kept = lines == self.lines ? saidUnder.flatMap { $0.superview === shown ? $0 : nil } : nil
        let anchor = kept ?? (standing == nil ? about ?? addressFrame : planeRow)
        saidUnder = lines.isEmpty ? nil : anchor
        self.lines = lines
        self.refused = refused && !lines.isEmpty
        let text = NSMutableAttributedString()
        let font = NSFont.systemFont(ofSize: Look.Step.callout.points)
        for (index, line) in lines.enumerated() {
            let color = NSColor(index == 0 ? (self.refused ? Tint(look.error) : look.text) : look.textSecondary)
            if index == 0, self.refused { text.append(errorMark(color: color, font: font)) }
            text.append(NSAttributedString(string: (index == 0 ? "" : "\n") + line, attributes: [.foregroundColor: color, .font: font]))
        }
        said.attributedStringValue = text
        said.isHidden = lines.isEmpty
        if kept == nil {
            addressFrame.failed = self.refused && anchor === addressFrame
            ticketFrame.failed = self.refused && anchor === ticketFrame
        }
        place(said, under: anchor)
    }

    /// The error's mark before a refusal's sentence, as the create row's field draws it.
    private func errorMark(color: NSColor, font: NSFont) -> NSAttributedString {
        let side = 12.0
        let mark = NSTextAttachment()
        mark.image = MarkImage.image(.error, side: side, ink: Tint(look.error), scale: Double(window?.backingScaleFactor ?? 2))
        mark.bounds = NSRect(x: 0, y: (Double(font.capHeight) - side) / 2, width: side, height: side)
        let words = NSMutableAttributedString(attachment: mark)
        words.append(NSAttributedString(string: " "))
        words.addAttributes([.foregroundColor: color, .font: font], range: NSRange(location: 0, length: words.length))
        return words
    }

    /// A refused field is drawn refused until it is edited again.
    public func controlTextDidChange(_ notification: Notification) {
        if notification.object as AnyObject? === address { addressFrame.failed = false }
        if notification.object as AnyObject? === ticket { ticketFrame.failed = false }
    }

    /// `view` in the column, straight after `anchor`.
    private func place(_ view: NSView, under anchor: NSView) {
        guard let rows = anchor.superview as? NSStackView, let at = rows.arrangedSubviews.firstIndex(of: anchor) else { return }
        if view.superview === rows, rows.arrangedSubviews.firstIndex(of: view) == at + 1 { return }
        (view.superview as? NSStackView)?.removeArrangedSubview(view)
        view.removeFromSuperview()
        let index = rows.arrangedSubviews.firstIndex(of: anchor).map { $0 + 1 } ?? rows.arrangedSubviews.count
        rows.insertArrangedSubview(view, at: index)
    }

    private func label(_ text: String) -> NSTextField {
        let label = eyebrow(text, mono: mono)
        eyebrows.append(label)
        return label
    }

    private func monospaced(_ step: Look.Step) -> NSFont {
        monoFont(step, from: mono)
    }

    private func compose() {
        wantsLayer = true
        until.font = .systemFont(ofSize: Look.Step.footnote.points)
        for (label, tip) in [(heading, headingTip), (handle, handleTip)] {
            label.maximumNumberOfLines = 1
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            tip.onlyWhenCut = true
            tip.attach(to: label, saying: "")
        }
        until.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        address.placeholderString = "https://sail.example.com or http://100.64.0.7:7443"
        address.font = monospaced(.callout)
        ticket.placeholderString = "enr_… or the link sail fde enroll printed"
        for field in [address, ticket] {
            field.target = self
            field.delegate = self
        }
        address.action = #selector(signInWithPasskey(_:))
        ticket.action = #selector(enrollWithTicket(_:))
        enroll.target = self
        enroll.action = #selector(enrollWithTicket(_:))
        signOut.target = self
        signOut.action = #selector(signOutHere(_:))
        said.isSelectable = true
        said.isHidden = true
        signedOutRows.addArrangedSubview(said)
        for row in [label("Control plane"), addressFrame, signIn, label("Enrollment ticket"), ticketFrame, enroll] {
            signedOutRows.addArrangedSubview(row)
        }
        signedOutRows.setCustomSpacing(Look.Space.xxl, after: signIn)
        for row in [label("Signed in as"), handle, label("Control plane"), planeRow, until, signOut] {
            signedInRows.addArrangedSubview(row)
        }
        signedInRows.setCustomSpacing(Look.Space.l, after: handle)
        signedInRows.setCustomSpacing(Look.Space.l, after: until)
        for rows in [signedOutRows, signedInRows] {
            rows.orientation = .vertical
            rows.alignment = .leading
            rows.spacing = Look.Space.s
        }
        let column = NSStackView(views: [heading, signedOutRows, signedInRows])
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = Look.Space.l
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        NSLayoutConstraint.activate([
            column.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.x3l + Look.Space.l),
            column.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Look.Space.x3l),
            column.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -Look.Space.x3l),
            said.widthAnchor.constraint(lessThanOrEqualToConstant: 560),
            until.widthAnchor.constraint(lessThanOrEqualToConstant: 380),
        ])
        // 380 pt where the pane has room, narrower at the window's least frame.
        for view in [addressFrame, ticketFrame, planeRow] as [NSView] {
            let wide = view.widthAnchor.constraint(equalToConstant: 380)
            wide.priority = .defaultHigh
            wide.isActive = true
        }
        paint()
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
