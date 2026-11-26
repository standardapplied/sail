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
    func session(for controlPlane: ControlPlane) throws(VaultFailure) -> PasskeySession?
    func keep(_ session: PasskeySession, for controlPlane: ControlPlane) throws(VaultFailure)
    func forget(_ controlPlane: ControlPlane) throws(VaultFailure)
}

/// The vault refused: its status in words, and what to do when the vault knows. `unreadable` is a
/// session it held and could not read, which it has removed.
public struct VaultFailure: Error, Equatable, Sendable {
    public let status: String
    public let remedy: String?
    public let unreadable: Bool

    public init(status: String, remedy: String? = nil, unreadable: Bool = false) {
        self.status = status
        self.remedy = remedy
        self.unreadable = unreadable
    }
}

/// Who this Mac is signed in as at a control plane, until when, and what `whoami` answered: the
/// person, or a refusal that did not end the session.
public struct SignedIn: Equatable, Sendable {
    public let controlPlane: ControlPlane
    public let fde: String
    public let until: Date
    public let whoami: Result<Whoami, AccountRefusal>

    public init(controlPlane: ControlPlane, fde: String, until: Date, whoami: Result<Whoami, AccountRefusal>) {
        self.controlPlane = controlPlane
        self.fde = fde
        self.until = until
        self.whoami = whoami
    }
}

/// Signing in to one control plane with a passkey: a ticket enrolls one, the passkey signs in, the
/// session goes to the vault and nowhere else. The ceremony is the server's: its challenge, relying
/// party, user and excluded credentials reach the authenticator unchanged, and a relying party other
/// than Sail's is refused before the authenticator is asked.
public struct Account: Sendable {
    public static let relyingParty = "sail.standardapplied.com"
    public static let passkeyLabel = "Sail for Mac"
    /// How long Continue waits for a control plane's health before saying it cannot reach it. A
    /// reachable one answers within a few seconds even far away or over a relayed overlay: a name
    /// looked up, then TCP, TLS 1.3 and one small GET, four round trips of at most half a second
    /// each, a relay's first packets a second or two more. Twice that is ten seconds, where
    /// URLSession's own sixty kept a person a minute on an address that will not answer.
    public static let reachLimit = Duration.seconds(10)

    private let authenticator: any PasskeyAuthenticator
    private let vault: any TokenVault
    private let connect: @Sendable (ControlPlane) -> any Transport
    private let reachWithin: Duration

    public init(
        authenticator: any PasskeyAuthenticator, vault: any TokenVault, reachWithin: Duration = Account.reachLimit,
        connect: @escaping @Sendable (ControlPlane) -> any Transport = { URLSessionTransport(base: $0.url) }
    ) {
        self.authenticator = authenticator
        self.vault = vault
        self.connect = connect
        self.reachWithin = reachWithin
    }

    /// The control plane `typed` names, once it answers Sail's health within `reachWithin`: one
    /// unauthenticated `GET /v1/health`, and only `ok` moves on. A bare host is taken as `https://`.
    /// No answer in time is said as out of reach, and so is a check cancelled, which stops at once
    /// and is the canceller's to leave unsaid.
    public func reach(_ typed: String) async throws(AccountRefusal) -> ControlPlane {
        let plane = try ControlPlane(address: typed)
        let transport = connect(plane), limit = reachWithin
        let answered = await withTaskGroup(of: Result<Health, AccountRefusal>?.self) { group in
            group.addTask {
                do throws(AccountRefusal) {
                    return .success(try await self.call(.health, on: transport, at: plane, step: .reaching))
                } catch {
                    return .failure(error)
                }
            }
            group.addTask {
                try? await Task.sleep(for: limit)
                return nil
            }
            defer { group.cancelAll() }
            return await group.next() ?? nil
        }
        guard !Task.isCancelled, let answered else { throw AccountRefusal(.unreachable, at: plane) }
        guard try answered.get().status == "ok" else { throw AccountRefusal(.unreadable, at: plane, remedy: AccountRefusal.notSails) }
        return plane
    }

    /// Until when the session held for `plane` lasts, nil with none held. A session the vault could
    /// not read is gone once read, so it is said here, as ``current(at:)`` says it.
    public func heldUntil(at plane: ControlPlane) throws(AccountRefusal) -> Date? {
        try held(at: plane)?.expiresAt
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
        try vaulted("keep") { () throws(VaultFailure) in try vault.keep(session, for: plane) }
        return try await standing(session, at: plane, on: transport)
    }

    /// Who this Mac is signed in as, nil when it holds no session; a session the server ended, or
    /// one the vault could not read, is gone and said.
    public func current(at plane: ControlPlane) async throws(AccountRefusal) -> SignedIn? {
        guard let stored = try held(at: plane) else { return nil }
        return try await standing(stored, at: plane, on: connect(plane))
    }

    /// The API as the person signed in at `plane`, with the session the vault holds there; nil with
    /// none held.
    public func client(at plane: ControlPlane) throws(AccountRefusal) -> SailClient? {
        try held(at: plane).map { SailClient(transport: connect(plane), token: $0.sessionToken) }
    }

    /// The session the vault holds for `plane`; one it could not read is said as gone.
    private func held(at plane: ControlPlane) throws(AccountRefusal) -> PasskeySession? {
        do {
            return try vault.session(for: plane)
        } catch {
            guard error.unreadable else { throw AccountRefusal(.keychain("read", error.status), remedy: error.remedy) }
            throw AccountRefusal(.unreadableSignIn, at: plane)
        }
    }

    /// Forgets the session on this Mac. The server keeps it until it expires or an admin revokes it.
    public func signOut(at plane: ControlPlane) throws(AccountRefusal) {
        try vaulted("remove") { () throws(VaultFailure) in try vault.forget(plane) }
    }

    /// Who `stored` signs in as. A session the server ended is forgotten and said, whether or not
    /// the vault could forget it: the next launch is told again and tries again.
    private func standing(_ stored: PasskeySession, at plane: ControlPlane, on transport: any Transport) async throws(AccountRefusal) -> SignedIn {
        do {
            let whoami = try await SailClient(transport: transport, token: stored.sessionToken).send(.whoami)
            return SignedIn(controlPlane: plane, fde: stored.fde, until: stored.expiresAt, whoami: .success(whoami))
        } catch {
            let refusal = AccountRefusal(error, at: plane, step: .asking)
            guard refusal.reason != .ended else {
                try? vault.forget(plane)
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

    private func vaulted<Value>(_ verb: String, _ act: () throws(VaultFailure) -> Value) throws(AccountRefusal) -> Value {
        do {
            return try act()
        } catch {
            throw AccountRefusal(.keychain(verb, error.status), remedy: error.remedy)
        }
    }
}
