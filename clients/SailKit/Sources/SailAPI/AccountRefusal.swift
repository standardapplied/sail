import Foundation

/// Why signing in did not happen, said for the person: a sentence, what to do, the server's own
/// message and action when it sent them, and the system's words when the system failed. No line
/// holds a ticket or a token.
public struct AccountRefusal: Error, Equatable, Sendable {
    public enum Reason: Equatable, Sendable {
        case notAnAddress
        case notATicket
        case addressNotAllowed(String)
        case unreachable
        case passkeysNotConfigured
        case foreignRelyingParty(String)
        case ticketRefused
        case originNotAllowed(String)
        case notAccepted
        case alreadyHeld
        case associationNotConfirmed
        case cannotCreate
        case cancelled(signingIn: Bool)
        case keychain(String, String)
        case ended
        case unreadableSignIn
        case redirected
        case refused
        case unreadable
        case authenticatorFailed
    }

    /// Which call a server's answer came to: the same code means a different thing at each.
    public enum Step: Sendable {
        case reaching, enrolling, signingIn, asking
    }

    public let reason: Reason
    public let sentence: String
    public let remedy: String?
    public let server: Refusal?
    public let system: String?

    /// Every line to show, in order, each once: the server's action only where it names no command.
    public var lines: [String] {
        var seen: Set<String> = []
        return [sentence, remedy, server?.message, server.flatMap { Said.fix($0.remedy, otherwise: $0.action) }, system].compactMap { $0 }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    package init(_ reason: Reason, at plane: ControlPlane? = nil, server: Refusal? = nil, system: String? = nil, remedy: String? = nil) {
        let (sentence, said) = Self.words(reason, address: plane?.description ?? "The control plane", host: plane?.named ?? "this control plane", server: server)
        self.reason = reason
        self.sentence = sentence
        self.remedy = remedy ?? said
        self.server = reason == .refused ? nil : server
        self.system = system
    }

    public init(_ error: SailError, at plane: ControlPlane, step: Step) {
        guard case let .refused(refusal) = error else {
            if error == .signIn(.ended) {
                self.init(.ended, at: plane)
            } else if case let .unreachable(text) = error {
                self.init(.unreachable, at: plane, system: text)
            } else if case let .unexpected(_, status) = error, (300 ..< 400).contains(status) {
                self.init(.redirected, at: plane, system: error.description)
            } else {
                self.init(.unreadable, at: plane, system: error.description, remedy: step == .reaching ? Self.notSails : nil)
            }
            return
        }
        self.init(Self.reason(refusal, step: step), at: plane, server: refusal)
    }

    public init(_ failure: PasskeyFailure, signingIn: Bool, at plane: ControlPlane) {
        switch failure {
        case .cancelled: self.init(.cancelled(signingIn: signingIn), at: plane)
        case .alreadyHeld: self.init(.alreadyHeld, at: plane)
        case let .associationNotConfirmed(text): self.init(.associationNotConfirmed, at: plane, system: text)
        case let .cannotCreate(text): self.init(.cannotCreate, at: plane, system: text)
        case let .failed(text): self.init(.authenticatorFailed, at: plane, system: text)
        }
    }

    static let originRefused = "Registration origin not allowed: "

    /// What to check when an address answered, but not as Sail's control plane answers.
    static let notSails = "Check that this is your team's control plane address."

    private static func reason(_ refusal: Refusal, step: Step) -> Reason {
        switch (refusal.code, step) {
        case (.known(.passkeyNotConfigured), _): .passkeysNotConfigured
        case (.known(.forbidden), .enrolling): .ticketRefused
        case (.known(.authenticationFailed), .signingIn): .notAccepted
        case (.known(.invalidRequest), .enrolling) where refusal.message.hasPrefix(originRefused):
            .originNotAllowed(String(refusal.message.dropFirst(originRefused.count)))
        default: .refused
        }
    }

    /// What an admin does for a control plane that takes no passkeys from Sail: their steps are the
    /// admin docs' and the server log's, never this screen's.
    private static let askToSetUp = "Ask your admin to set up passkeys for Sail on this control plane."

    private static func words(_ reason: Reason, address: String, host: String, server: Refusal?) -> (String, String?) {
        let sails = Account.relyingParty
        switch reason {
        case .notAnAddress:
            return ("That is not a control plane address.", "Type the address your admin gave you, such as sail.example.com.")
        case .notATicket:
            return ("That is not an enrollment ticket.", "Paste the ticket or the link your admin sent.")
        case let .addressNotAllowed(host):
            return (
                "Sail sends plain HTTP only to a private-network address. Use https:// for \(host).",
                "Plain http:// is for an IP address in 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, ::1 or fc00::/7, on a network that encrypts."
            )
        case .unreachable:
            return ("Sail cannot reach \(address).", "Check that this Mac is on its network and the address is right.")
        case .passkeysNotConfigured:
            return ("\(address) does not take passkeys yet.", askToSetUp)
        case let .foreignRelyingParty(party):
            return ("\(address) uses passkeys for \(party); Sail can use them only for \(sails).", askToSetUp)
        case .ticketRefused:
            return ("This ticket is used, expired or not from \(host).", "A ticket lasts 15 minutes and enrolls one passkey. Ask your admin for a new one.")
        case .originNotAllowed:
            return ("\(address) does not accept passkeys from this app.", askToSetUp)
        case .notAccepted:
            return ("\(address) did not accept that passkey.", "Pick the passkey you enrolled there, or enroll one.")
        case .alreadyHeld:
            return ("This Mac already holds your passkey for \(address).", "Sign in with it.")
        case .associationNotConfirmed:
            return ("macOS could not confirm that Sail may use passkeys for \(sails).", "Try again later; if it stays, tell the Sail team.")
        case .cannotCreate:
            return ("This Mac cannot make passkeys right now.", "Turn on iCloud Keychain in System Settings.")
        case let .cancelled(signingIn):
            return ("Cancelled.", signingIn ? "If this Mac holds no passkey for \(host), enroll with a ticket from your admin." : nil)
        case let .keychain(verb, status):
            return ("Sail could not \(verb) your sign-in in the Keychain: \(status).", nil)
        case .ended:
            return ("Your sign-in to \(address) has ended.", "Sign in again with your passkey.")
        case .unreadableSignIn:
            return ("Your sign-in to \(address) could not be read from the Keychain.", "Sign in again with your passkey.")
        case .refused:
            return (server?.message ?? "\(address) refused.", server.flatMap { Said.fix($0.remedy, otherwise: $0.action) })
        case .unreadable:
            return ("\(address) answered in a way Sail cannot read.", nil)
        case .redirected:
            return ("\(address) answered with a redirect; Sail does not follow one.", "Type the control plane's own address.")
        case .authenticatorFailed:
            return ("macOS did not finish the passkey request.", nil)
        }
    }
}
