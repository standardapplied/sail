import AppKit
import SailAPI
import SailLook
import SailTerminalView

/// The account. Signed out: a title, the control plane's address, Sign In with Passkey and Enroll
/// with a Ticket (a secure field, cleared as soon as it is read). Signed in: who, `@handle`, role,
/// the control plane, until when, and Sign Out. A refusal shows under the buttons, line by line:
/// the sentence, what to do, the server's words, the system's.
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
    /// Whether a ceremony or a call is under way.
    public private(set) var busy = false

    private let account: Account
    private let defaults: UserDefaults
    private let look: Look
    private let mono: TerminalFonts
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

    /// Asks the server who this Mac is signed in as, when it holds a session.
    public func refresh() async {
        guard !busy, let plane = try? ControlPlane(address: address.stringValue) else { return }
        await run { () async throws(AccountRefusal) in
            self.show(try await self.account.current(at: plane))
        }
    }

    @objc public func signInWithPasskey(_: Any?) {
        Task {
            await run { () async throws(AccountRefusal) in
                let plane = try self.remembered()
                self.show(try await self.account.signIn(at: plane))
            }
        }
    }

    @objc public func enrollWithTicket(_: Any?) {
        let pasted = ticket.stringValue
        ticket.stringValue = ""
        Task {
            await run { () async throws(AccountRefusal) in
                let plane = try self.remembered()
                let enrolled = try await self.account.enroll(ticket: pasted, at: plane)
                self.say(["A passkey for @\(enrolled.fde) is on this Mac. Sign in with it."])
            }
        }
    }

    @objc public func signOutHere(_: Any?) {
        guard let plane = standing?.controlPlane else { return }
        do {
            try account.signOut(at: plane)
            show(nil)
            say([])
        } catch {
            say(error.lines)
        }
    }

    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        item.action != #selector(signOutHere(_:)) || (standing != nil && !busy)
    }

    private func remembered() throws(AccountRefusal) -> ControlPlane {
        let plane = try ControlPlane(address: address.stringValue)
        address.stringValue = plane.description
        defaults.set(plane.description, forKey: Self.addressKey)
        return plane
    }

    private func run(_ act: @MainActor () async throws(AccountRefusal) -> Void) async {
        busy = true
        say([])
        [signIn, enroll, signOut].forEach { $0.isEnabled = false }
        defer {
            busy = false
            [signIn, enroll, signOut].forEach { $0.isEnabled = true }
        }
        do {
            try await act()
        } catch {
            if error.reason == .ended { show(nil) }
            say(error.lines)
        }
    }

    private func show(_ signedIn: SignedIn?) {
        standing = signedIn
        signedOutRows.isHidden = signedIn != nil
        signedInRows.isHidden = signedIn == nil
        guard let signedIn else {
            heading.stringValue = "Sign in to Sail"
            return
        }
        let whoami = try? signedIn.whoami.get()
        heading.stringValue = whoami?.displayName ?? whoami?.name ?? signedIn.fde
        who.stringValue = Self.describe(signedIn)
        if case let .failure(refused) = signedIn.whoami { say(refused.lines) }
    }

    /// `@handle`, role, control plane and "Signed in until <date>", a line each.
    static func describe(_ signedIn: SignedIn) -> String {
        let whoami = try? signedIn.whoami.get()
        return [
            "@\(whoami?.fde ?? signedIn.fde)", whoami?.role.rawValue ?? "", signedIn.controlPlane.description,
            "Signed in until \(signedIn.until.formatted(date: .long, time: .shortened))",
        ].filter { !$0.isEmpty }.joined(separator: "\n")
    }

    private func say(_ lines: [String]) {
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
        let label = NSTextField(labelWithString: text.uppercased())
        label.font = monospaced(.micro)
        label.textColor = NSColor(look.textSecondary)
        return label
    }

    /// JetBrains Mono at `step`, the face the terminal uses.
    private func monospaced(_ step: Look.Step) -> NSFont {
        (try? mono.faces(points: step.points, scale: 1).font(.regular)).map { $0 as NSFont }
            ?? .monospacedSystemFont(ofSize: step.points, weight: .regular)
    }

    private func compose() {
        wantsLayer = true
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        heading.textColor = NSColor(look.text)
        who.textColor = NSColor(look.textSecondary)
        who.font = .systemFont(ofSize: Look.Step.body.points)
        address.placeholderString = "https://sail.example.com or http://100.64.0.7:7443"
        address.font = monospaced(.callout)
        ticket.placeholderString = "enr_… or the link sail fde enroll printed"
        signIn.keyEquivalent = "\r"
        enroll.target = self
        enroll.action = #selector(enrollWithTicket(_:))
        signOut.target = self
        signOut.action = #selector(signOutHere(_:))
        said.isSelectable = true
        said.isHidden = true
        for row in [label("Control plane"), address, signIn, label("Enrollment ticket"), ticket, enroll] {
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
            address.widthAnchor.constraint(equalToConstant: 380), ticket.widthAnchor.constraint(equalToConstant: 380),
            said.widthAnchor.constraint(lessThanOrEqualToConstant: 560),
        ])
    }
}
