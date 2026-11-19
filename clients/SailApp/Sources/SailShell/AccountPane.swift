import AppKit
import SailAPI
import SailLook
import SailTerminalView

/// The account. Signed out: a title, the control plane's address, Sign In with Passkey and Enroll
/// with a Ticket (a secure field, cleared as soon as it is read). Signed in: who, `@handle`, role,
/// the control plane, until when, and Sign Out. A refusal shows under the buttons, line by line:
/// the sentence, what to do, the server's words, the system's. The field being edited takes a ring
/// in the accent, its selection the selection fill and its caret the accent (set on the field
/// editor once the field has set it up, as it resets them). Return in a field sends that field's
/// own action, Sign In from the address and Enroll from the ticket: no button is the window's default,
/// which AppKit would paint in the system accent.
@MainActor
public final class AccountPane: NSView, NSMenuItemValidation {
    static let addressKey = "controlPlane.address"

    public let address = NSTextField()
    public let ticket = NSSecureTextField()
    public let signIn: AccentButton
    public let enroll = NSButton(title: "Enroll with a Ticket", target: nil, action: nil)
    public let signOut = NSButton(title: "Sign Out", target: nil, action: nil)
    public let said = NSTextField(wrappingLabelWithString: "")
    public private(set) var standing: SignedIn?
    /// Whether a ceremony or a call is under way, and how many have ended.
    public var busy: Bool {
        running > 0 || acting > 0
    }

    public private(set) var ended = 0
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
    private var labels: [NSTextField] = []
    private lazy var addressFrame = FieldFrame(address, look: look)
    private lazy var ticketFrame = FieldFrame(ticket, look: look)
    private let heading = NSTextField(labelWithString: "Sign in to Sail")
    private let who = NSTextField(wrappingLabelWithString: "")
    private let signedOutRows = NSStackView()
    private let signedInRows = NSStackView()

    init(account: Account, look: Look, title: TitleFont, mono: TerminalFonts, defaults: UserDefaults = .standard) {
        self.account = account
        self.defaults = defaults
        self.look = look
        self.mono = mono
        signIn = AccentButton("Sign In with Passkey", look: look, target: nil, action: #selector(AccountPane.signInWithPasskey(_:)))
        super.init(frame: .zero)
        signIn.target = self
        heading.font = title.font(.title2) as NSFont
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
        [heading.stringValue, standing == nil ? "" : who.stringValue, said.isHidden ? "" : said.stringValue].filter { !$0.isEmpty }
            .joined(separator: "\n")
    }

    /// Asks the server who this Mac is signed in as at the control plane it last used, when it
    /// holds a session there; what is typed in the field and not yet used is not asked about. The
    /// check holds no button: a person may act while it is under way. An answer that comes during
    /// a person's action waits for it to end, so the pane never changes under a passkey sheet.
    public func refresh() async {
        guard !busy, let used = defaults.string(forKey: Self.addressKey), let plane = try? ControlPlane(address: used) else { return }
        let acted = actions, seen = shown
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
        if actions == acted { say([]) }
        switch answer {
        case let .success(found):
            show(found)
        case let .failure(refusal):
            forgetIfGone(refusal)
            say(lines + refusal.lines)
        }
    }

    private func whenNoOneActs(_ go: @escaping @MainActor () -> Void) {
        guard acting > 0 else { return go() }
        heldAnswer = go
    }

    @objc public func signInWithPasskey(_: Any?) {
        guard acting == 0 else { return }
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
        begin()
        Task {
            await run { () async throws(AccountRefusal) in
                let plane = try self.remembered()
                let enrolled = try await self.account.enroll(ticket: pasted, at: plane)
                self.say(["A passkey for @\(enrolled.fde) is on this Mac.", Self.signInWithIt])
            }
        }
    }

    /// Signs out at once, asking nothing: what the sign-in opened on the runner lives on there,
    /// for the same person's next sign-in.
    @objc public func signOutHere(_: Any?) {
        guard standing != nil, acting == 0 else { return }
        signOutNow()
    }

    private func signOutNow() {
        guard let plane = standing?.controlPlane else { return }
        do {
            try account.signOut(at: plane)
            show(nil)
            say([])
        } catch {
            say(error.lines)
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
            say(error.lines)
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
    /// "Sign in with it." is never said beside someone signed in.
    private func show(_ signedIn: SignedIn?) {
        shown += 1
        standing = signedIn
        signedOutRows.isHidden = signedIn != nil
        signedInRows.isHidden = signedIn == nil
        onStanding(signedIn)
        guard let signedIn else {
            heading.stringValue = "Sign in to Sail"
            return
        }
        let whoami = try? signedIn.whoami.get()
        heading.stringValue = whoami?.displayName ?? whoami?.name ?? signedIn.fde
        who.stringValue = Self.describe(signedIn)
        var now = lines.filter { $0 != Self.signInWithIt }
        if case let .failure(refused) = signedIn.whoami { now += refused.lines }
        say(now)
    }

    static let signInWithIt = "Sign in with it."

    /// `@handle`, role, control plane and "Signed in until <date>", a line each; a sign-in this Mac
    /// remembers that the control plane has not confirmed says so instead.
    static func describe(_ signedIn: SignedIn) -> String {
        let whoami = try? signedIn.whoami.get()
        let until = signedIn.until.formatted(date: .long, time: .shortened)
        return [
            "@\(whoami?.fde ?? signedIn.fde)", whoami?.role.rawValue ?? "", signedIn.controlPlane.description,
            whoami == nil ? "Not yet confirmed: remembered on this Mac until \(until)" : "Signed in until \(until)",
        ].filter { !$0.isEmpty }.joined(separator: "\n")
    }

    private func paint() {
        lookPaints += 1
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        heading.textColor = NSColor(look.text)
        who.textColor = NSColor(look.textSecondary)
        labels.forEach { $0.textColor = NSColor(look.textSecondary) }
        signIn.look = look
        addressFrame.look = look
        ticketFrame.look = look
        say(lines)
    }

    private func say(_ lines: [String]) {
        self.lines = lines
        let text = NSMutableAttributedString()
        for (index, line) in lines.enumerated() {
            let color = NSColor(index == 0 ? look.text : look.textSecondary)
            text.append(NSAttributedString(
                string: (index == 0 ? "" : "\n") + line,
                attributes: [.foregroundColor: color, .font: NSFont.systemFont(ofSize: Look.Step.callout.points)]
            ))
        }
        said.attributedStringValue = text
        said.isHidden = lines.isEmpty
    }

    private func label(_ text: String) -> NSTextField {
        let label = eyebrow(text, mono: mono)
        labels.append(label)
        return label
    }

    private func monospaced(_ step: Look.Step) -> NSFont {
        monoFont(step, from: mono)
    }

    private func compose() {
        wantsLayer = true
        who.font = .systemFont(ofSize: Look.Step.body.points)
        address.placeholderString = "https://sail.example.com or http://100.64.0.7:7443"
        address.font = monospaced(.callout)
        ticket.placeholderString = "enr_… or the link sail fde enroll printed"
        for field in [address, ticket] {
            field.target = self
        }
        address.action = #selector(signInWithPasskey(_:))
        ticket.action = #selector(enrollWithTicket(_:))
        enroll.target = self
        enroll.action = #selector(enrollWithTicket(_:))
        enroll.controlSize = .large
        signOut.target = self
        signOut.action = #selector(signOutHere(_:))
        said.isSelectable = true
        said.isHidden = true
        for row in [label("Control plane"), addressFrame, signIn, label("Enrollment ticket"), ticketFrame, enroll] {
            signedOutRows.addArrangedSubview(row)
        }
        signedOutRows.setCustomSpacing(Look.Space.xxl, after: signIn)
        for row in [who, signOut] {
            signedInRows.addArrangedSubview(row)
        }
        for rows in [signedOutRows, signedInRows] {
            rows.orientation = .vertical
            rows.alignment = .leading
            rows.spacing = Look.Space.s
        }
        let column = NSStackView(views: [heading, signedOutRows, signedInRows, said])
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = Look.Space.l
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        NSLayoutConstraint.activate([
            column.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.x3l + Look.Space.l),
            column.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Look.Space.x3l),
            column.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -Look.Space.x3l),
            addressFrame.widthAnchor.constraint(equalToConstant: 380), ticketFrame.widthAnchor.constraint(equalToConstant: 380),
            said.widthAnchor.constraint(lessThanOrEqualToConstant: 560),
        ])
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
