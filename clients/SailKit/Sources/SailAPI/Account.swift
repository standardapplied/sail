import Foundation

/// What makes and uses passkeys: the platform's on a device, a software one in the drive.
public protocol PasskeyAuthenticator: Sendable {
    func register(_ request: PasskeyRegistrationRequest) async throws(PasskeyFailure) -> PasskeyRegistration
    func assert(_ request: PasskeyAssertionRequest) async throws(PasskeyFailure) -> PasskeyAssertion
}

/// The server's creation options as an authenticator is given them, unchanged.
public struct PasskeyRegistrationRequest: Equatable, Sendable {
    public let relyingParty: String
    public let challenge: Data
    public let userId: Data
    public let userName: String
    public let displayName: String
    public let excluded: [Data]
}

/// The server's request options as an authenticator is given them, unchanged.
public struct PasskeyAssertionRequest: Equatable, Sendable {
    public let relyingParty: String
    public let challenge: Data
}

/// How an authenticator ended without a passkey. The texts are the system's own words.
public enum PasskeyFailure: Error, Equatable, Sendable {
    /// The person cancelled, or no passkey was there to offer: the platform does not tell them apart.
    case cancelled
    case alreadyHeld
    case associationNotConfirmed(String)
    case cannotCreate(String)
    case failed(String)
}

/// One session per control plane, kept where only this app reads it.
public protocol TokenVault: Sendable {
    func session(for controlPlane: ControlPlane) throws(VaultFailure) -> StoredSession?
    func keep(_ session: StoredSession, for controlPlane: ControlPlane) throws(VaultFailure)
    func forget(_ controlPlane: ControlPlane) throws(VaultFailure)
}

/// The vault refused: its status in words, and what to do when the vault knows.
public struct VaultFailure: Error, Equatable, Sendable {
    public let status: String
    public let remedy: String?

    public init(status: String, remedy: String? = nil) {
        self.status = status
        self.remedy = remedy
    }
}

/// A session as the vault keeps it. Its description never holds the token.
public struct StoredSession: Codable, Equatable, Sendable, CustomStringConvertible {
    public let token: String
    public let fde: String
    public let expiresAt: Date

    public init(token: String, fde: String, expiresAt: Date) {
        self.token = token
        self.fde = fde
        self.expiresAt = expiresAt
    }

    public var description: String {
        "a session for \(fde) until \(expiresAt)"
    }
}

/// Who this Mac is signed in as at a control plane, until when, and what `whoami` answered: the
/// person, or a refusal that did not end the session.
public struct SignedIn: Equatable, Sendable {
    public let controlPlane: ControlPlane
    public let fde: String
    public let until: Date
    public let whoami: Result<Whoami, AccountRefusal>
}

/// Signing in to one control plane with a passkey: a ticket enrolls one, the passkey signs in, the
/// session goes to the vault and nowhere else. The ceremony is the server's: its challenge, relying
/// party, user and excluded credentials reach the authenticator unchanged, and a relying party other
/// than Sail's is refused before the authenticator is asked.
public struct Account: Sendable {
    public static let relyingParty = "sail.standardapplied.com"
    public static let passkeyLabel = "Sail for Mac"

    private let authenticator: any PasskeyAuthenticator
    private let vault: any TokenVault
    private let connect: @Sendable (ControlPlane) -> any Transport

    public init(
        authenticator: any PasskeyAuthenticator, vault: any TokenVault,
        connect: @escaping @Sendable (ControlPlane) -> any Transport = { URLSessionTransport(base: $0.url) }
    ) {
        self.authenticator = authenticator
        self.vault = vault
        self.connect = connect
    }

    /// Enrolls a passkey with the ticket in `text`. It signs no one in: that is a second ceremony.
    public func enroll(ticket text: String, at plane: ControlPlane) async throws(AccountRefusal) -> Enrollment {
        let ticket = try EnrollmentTicket.parse(text)
        let transport = connect(plane)
        let ceremony = try await call(.registrationStart(ticket: ticket), on: transport, at: plane, step: .enrolling)
        let options = ceremony.publicKey
        try requireSails(options.rp.id, at: plane)
        let request = PasskeyRegistrationRequest(
            relyingParty: options.rp.id, challenge: options.challenge.data, userId: options.user.id.data, userName: options.user.name,
            displayName: options.user.displayName, excluded: options.excludeCredentials.map(\.id.data)
        )
        let made: PasskeyRegistration
        do {
            made = try await authenticator.register(request)
        } catch {
            throw AccountRefusal(error, signingIn: false, at: plane)
        }
        let finish = try built { () throws(SailError) in
            try Endpoint.registrationFinish(ticket: ticket, challengeId: ceremony.challengeId, made, label: Self.passkeyLabel)
        }
        return try await call(finish, on: transport, at: plane, step: .enrolling)
    }

    /// Signs in with a passkey this Mac holds, keeps the session in the vault, and asks who it is.
    public func signIn(at plane: ControlPlane) async throws(AccountRefusal) -> SignedIn {
        let transport = connect(plane)
        let ceremony = try await call(.signInStart, on: transport, at: plane, step: .signingIn)
        try requireSails(ceremony.publicKey.rpId, at: plane)
        let assertion: PasskeyAssertion
        do {
            assertion = try await authenticator.assert(
                PasskeyAssertionRequest(relyingParty: ceremony.publicKey.rpId, challenge: ceremony.publicKey.challenge.data)
            )
        } catch {
            throw AccountRefusal(error, signingIn: true, at: plane)
        }
        let finish = try built { () throws(SailError) in try Endpoint.signInFinish(challengeId: ceremony.challengeId, assertion) }
        let session = try await call(finish, on: transport, at: plane, step: .signingIn)
        let stored = StoredSession(token: session.sessionToken, fde: session.fde, expiresAt: session.expiresAt)
        try vaulted { () throws(VaultFailure) in try vault.keep(stored, for: plane) }
        return try await standing(stored, at: plane, on: transport)
    }

    /// Who this Mac is signed in as, nil when it holds no session; a session the server ended is
    /// forgotten and said.
    public func current(at plane: ControlPlane) async throws(AccountRefusal) -> SignedIn? {
        guard let stored = try vaulted({ () throws(VaultFailure) in try vault.session(for: plane) }) else { return nil }
        return try await standing(stored, at: plane, on: connect(plane))
    }

    /// Forgets the session on this Mac. The server keeps it until it expires or an admin revokes it.
    public func signOut(at plane: ControlPlane) throws(AccountRefusal) {
        try vaulted { () throws(VaultFailure) in try vault.forget(plane) }
    }

    private func standing(_ stored: StoredSession, at plane: ControlPlane, on transport: any Transport) async throws(AccountRefusal) -> SignedIn {
        do {
            let whoami = try await SailClient(transport: transport, token: stored.token).send(.whoami)
            return SignedIn(controlPlane: plane, fde: stored.fde, until: stored.expiresAt, whoami: .success(whoami))
        } catch {
            let refusal = AccountRefusal(error, at: plane, step: .asking)
            guard refusal.reason != .ended else {
                try signOut(at: plane)
                throw refusal
            }
            return SignedIn(controlPlane: plane, fde: stored.fde, until: stored.expiresAt, whoami: .failure(refusal))
        }
    }

    private func requireSails(_ relyingParty: String, at plane: ControlPlane) throws(AccountRefusal) {
        guard relyingParty == Self.relyingParty else { throw AccountRefusal(.foreignRelyingParty(relyingParty), at: plane) }
    }

    private func call<Response>(
        _ endpoint: Endpoint<Response>, on transport: any Transport, at plane: ControlPlane, step: AccountRefusal.Step
    ) async throws(AccountRefusal) -> Response {
        do {
            return try endpoint.decode(try await transport.send(endpoint.request))
        } catch {
            throw AccountRefusal(error, at: plane, step: step)
        }
    }

    private func built<Response>(_ make: () throws(SailError) -> Endpoint<Response>) throws(AccountRefusal) -> Endpoint<Response> {
        do {
            return try make()
        } catch {
            throw AccountRefusal(.unreadable, system: "\(error)")
        }
    }

    private func vaulted<Value>(_ act: () throws(VaultFailure) -> Value) throws(AccountRefusal) -> Value {
        do {
            return try act()
        } catch {
            throw AccountRefusal(.keychain(error.status), remedy: error.remedy)
        }
    }
}
