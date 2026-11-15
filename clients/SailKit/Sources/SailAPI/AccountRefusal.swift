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
        case redirected
        case refused
        case unreadable
        case authenticatorFailed
    }

    /// Which call a server's answer came to: the same code means a different thing at each.
    enum Step {
        case enrolling, signingIn, asking
    }

    public let reason: Reason
    public let sentence: String
    public let remedy: String?
    public let server: Refusal?
    public let system: String?

    /// Every line to show, in order, each once.
    public var lines: [String] {
        var seen: Set<String> = []
        return [sentence, remedy, server?.message, server?.action, system].compactMap { $0 }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    init(_ reason: Reason, at plane: ControlPlane? = nil, server: Refusal? = nil, system: String? = nil, remedy: String? = nil) {
        let (sentence, said) = Self.words(reason, address: plane?.description ?? "The control plane", server: server)
        self.reason = reason
        self.sentence = sentence
        self.remedy = remedy ?? said
        self.server = reason == .refused ? nil : server
        self.system = system
    }

    init(_ error: SailError, at plane: ControlPlane, step: Step) {
        guard case let .refused(refusal) = error else {
            if case let .unreachable(text) = error {
                self.init(.unreachable, at: plane, system: text)
            } else if case let .unexpected(_, status) = error, (300 ..< 400).contains(status) {
                self.init(.redirected, at: plane, system: Self.text(of: error))
            } else {
                self.init(.unreadable, at: plane, system: Self.text(of: error))
            }
            return
        }
        self.init(Self.reason(refusal, step: step), at: plane, server: refusal)
    }

    init(_ failure: PasskeyFailure, signingIn: Bool, at plane: ControlPlane) {
        switch failure {
        case .cancelled: self.init(.cancelled(signingIn: signingIn), at: plane)
        case .alreadyHeld: self.init(.alreadyHeld, at: plane)
        case let .associationNotConfirmed(text): self.init(.associationNotConfirmed, at: plane, system: text)
        case let .cannotCreate(text): self.init(.cannotCreate, at: plane, system: text)
        case let .failed(text): self.init(.authenticatorFailed, at: plane, system: text)
        }
    }

    static let originRefused = "Registration origin not allowed: "

    private static func reason(_ refusal: Refusal, step: Step) -> Reason {
        switch (refusal.code, step) {
        case (.known(.passkeyNotConfigured), _): .passkeysNotConfigured
        case (.known(.invalidBearerToken), .asking), (.known(.missingBearerToken), .asking): .ended
        case (.known(.forbidden), .enrolling): .ticketRefused
        case (.known(.authenticationFailed), .signingIn): .notAccepted
        case (.known(.invalidRequest), .enrolling) where refusal.message.hasPrefix(originRefused):
            .originNotAllowed(String(refusal.message.dropFirst(originRefused.count)))
        default: .refused
        }
    }

    private static func text(of error: SailError) -> String {
        switch error {
        case let .undecodable(undecodable): undecodable.description
        case let .unexpected(route, status): "\(route) answered \(status)"
        case let .notAnEventStream(route, type): "\(route) answered \(type ?? "no content type")"
        case let .unsendable(reason), let .unreachable(reason): reason
        case let .refused(refusal): refusal.message
        }
    }

    private static let configure = "On the control plane, run:"
    private static let restart = "sudo systemctl restart sail-api"

    private static func words(_ reason: Reason, address: String, server: Refusal?) -> (String, String?) {
        let sails = Account.relyingParty
        switch reason {
        case .notAnAddress:
            return ("That is not a control plane address.", "Type it as https://<name>, or http://<private IP address>:<port> such as http://10.0.0.2:7443.")
        case .notATicket:
            return ("That is not an enrollment ticket.", "Paste the ticket sail fde enroll printed, or the link it printed.")
        case let .addressNotAllowed(host):
            return (
                "Sail sends plain HTTP only to a private-network address. Use https:// for \(host).",
                "Plain http:// is for an IP address in 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, ::1 or fc00::/7, on a network that encrypts."
            )
        case .unreachable:
            return ("Sail cannot reach \(address).", "Check that this Mac is on its network and the address is right.")
        case .passkeysNotConfigured:
            return ("\(address) does not take passkeys yet.", [
                configure, "sail host config set webauthn-rp-id \(sails)", "sail host config set webauthn-rp-name Sail",
                "sail host config set webauthn-origin https://\(sails)", restart,
            ].joined(separator: "\n"))
        case let .foreignRelyingParty(party):
            return (
                "\(address) uses passkeys for \(party); Sail can use them only for \(sails).",
                [configure, "sail host config set webauthn-rp-id \(sails)", restart].joined(separator: "\n")
            )
        case .ticketRefused:
            return (
                "This enrollment ticket is used, expired or not from \(address).",
                "A ticket lasts 15 minutes and enrolls one passkey. Ask an admin: sail fde enroll <handle>."
            )
        case let .originNotAllowed(origin):
            return ("\(address) does not accept passkeys from this app.", [configure, "sail host config set webauthn-origin \(origin)", restart].joined(separator: "\n"))
        case .notAccepted:
            return ("\(address) did not accept that passkey.", "Pick the passkey you enrolled there, or enroll one.")
        case .alreadyHeld:
            return ("This Mac already holds your passkey for \(address).", "Sign in with it.")
        case .associationNotConfirmed:
            return ("macOS could not confirm that Sail may use passkeys for \(sails).", "Try again later; if it stays, tell the Sail team.")
        case .cannotCreate:
            return ("This Mac cannot make passkeys right now.", "Turn on iCloud Keychain in System Settings.")
        case let .cancelled(signingIn):
            return ("Cancelled.", signingIn ? "If this Mac holds no passkey for \(address), enroll one with a ticket from an admin." : nil)
        case let .keychain(verb, status):
            return ("Sail could not \(verb) your sign-in in the Keychain: \(status).", nil)
        case .ended:
            return ("Your sign-in to \(address) has ended.", "Sign in again with your passkey.")
        case .refused:
            return (server?.message ?? "\(address) refused.", server?.action)
        case .unreadable:
            return ("\(address) answered in a way Sail cannot read.", nil)
        case .redirected:
            return ("\(address) answered with a redirect; Sail does not follow one.", "Type the control plane's own address.")
        case .authenticatorFailed:
            return ("macOS did not finish the passkey request.", nil)
        }
    }
}
