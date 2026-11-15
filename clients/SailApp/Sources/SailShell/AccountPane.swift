import AppKit
import SailAPI

/// The account: signed out, the control plane's address, Sign In with Passkey and Enroll with a
/// Ticket; signed in, who, their role, the control plane and until when, and Sign Out. A refusal
/// shows under the buttons, line by line. The ticket field is cleared as soon as it is read.
@MainActor
public final class AccountPane: NSView {
    static let addressKey = "controlPlane.address"

    public let address = NSTextField()
    public let ticket = NSSecureTextField()
    public let signIn = NSButton(title: "Sign In with Passkey", target: nil, action: nil)
    public let enroll = NSButton(title: "Enroll with a Ticket", target: nil, action: nil)
    public let signOut = NSButton(title: "Sign Out", target: nil, action: nil)
    public let said = NSTextField(wrappingLabelWithString: "")
    public private(set) var standing: SignedIn?
    /// Told when the person signs in or out.
    public var onStanding: @MainActor (SignedIn?) -> Void = { _ in }

    private let account: Account
    private let defaults: UserDefaults
    private let heading = NSTextField(labelWithString: "Sign in to Sail")
    private let who = NSTextField(wrappingLabelWithString: "")
    private let signedOutRows: NSStackView
    private let signedInRows: NSStackView
    private var busy = false

    public init(account: Account, defaults: UserDefaults = .standard) {
        self.account = account
        self.defaults = defaults
        signedOutRows = NSStackView()
        signedInRows = NSStackView()
        super.init(frame: .zero)
        compose()
        address.stringValue = defaults.string(forKey: Self.addressKey) ?? ""
        show(nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The control plane the address field names, or the refusal saying why it names none.
    func controlPlane() throws(AccountRefusal) -> ControlPlane {
        try ControlPlane(address: address.stringValue)
    }

    /// Asks the server who this Mac is signed in as, when it holds a session.
    public func refresh() async {
        guard !busy, let plane = try? controlPlane() else { return }
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
        } catch {
            say(error.lines)
        }
    }

    private func remembered() throws(AccountRefusal) -> ControlPlane {
        let plane = try controlPlane()
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
        heading.stringValue = signedIn == nil ? "Sign in to Sail" : "Signed in"
        if let signedIn {
            who.stringValue = Self.describe(signedIn)
            if case let .failure(refused) = signedIn.whoami { say(refused.lines) }
        }
        onStanding(signedIn)
    }

    /// Display name, `@handle`, role, control plane and "Signed in until <date>", a line each.
    static func describe(_ signedIn: SignedIn) -> String {
        let whoami = try? signedIn.whoami.get()
        let until = signedIn.until.formatted(date: .long, time: .shortened)
        return [
            whoami?.displayName ?? whoami?.name ?? signedIn.fde, "@\(whoami?.fde ?? signedIn.fde)", whoami?.role.rawValue ?? "",
            signedIn.controlPlane.description, "Signed in until \(until)",
        ].filter { !$0.isEmpty }.joined(separator: "\n")
    }

    private func say(_ lines: [String]) {
        said.stringValue = lines.joined(separator: "\n")
        said.isHidden = lines.isEmpty
    }

    private func compose() {
        address.placeholderString = "http://100.84.93.13:7443"
        ticket.placeholderString = "Enrollment ticket"
        signIn.target = self
        signIn.action = #selector(signInWithPasskey(_:))
        signIn.keyEquivalent = "\r"
        enroll.target = self
        enroll.action = #selector(enrollWithTicket(_:))
        signOut.target = self
        signOut.action = #selector(signOutHere(_:))
        said.isSelectable = true
        heading.font = .systemFont(ofSize: 22, weight: .medium)
        let addressLabel = NSTextField(labelWithString: "Control plane")
        let ticketLabel = NSTextField(labelWithString: "Enrollment ticket")
        for row in [addressLabel, address, signIn, ticketLabel, ticket, enroll] {
            signedOutRows.addArrangedSubview(row)
        }
        for row in [who, signOut] {
            signedInRows.addArrangedSubview(row)
        }
        for rows in [signedOutRows, signedInRows] {
            rows.orientation = .vertical
            rows.alignment = .leading
            rows.spacing = 8
        }
        let column = NSStackView(views: [heading, signedOutRows, signedInRows, said])
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = 16
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        NSLayoutConstraint.activate([
            column.topAnchor.constraint(equalTo: topAnchor, constant: 32), column.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 32),
            column.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -32),
            address.widthAnchor.constraint(equalToConstant: 360), ticket.widthAnchor.constraint(equalToConstant: 360),
            said.widthAnchor.constraint(lessThanOrEqualToConstant: 520),
        ])
    }
}
