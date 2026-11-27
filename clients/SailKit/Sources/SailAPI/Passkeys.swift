import Foundation

/// Bytes the passkey routes carry as unpadded base64url text.
public struct Base64URL: Codable, Hashable, Sendable {
    public let data: Data

    public init(_ data: Data) {
        self.data = data
    }

    public init(from decoder: any Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard let data = Self.decode(text) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "it is not base64url"))
        }
        self.data = data
    }

    public func encode(to encoder: any Encoder) throws {
        var text = encoder.singleValueContainer()
        try text.encode(Self.encode(data))
    }

    public static func encode(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ text: String) -> Data? {
        guard text.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        let standard = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        return Data(base64Encoded: standard + String(repeating: "=", count: (4 - standard.count % 4) % 4))
    }
}

/// What `register/start` answers: the ceremony's id and the creation options the authenticator is
/// given unchanged.
public struct RegistrationCeremony: Decodable, Equatable, Sendable {
    public let challengeId: String
    public let publicKey: CreationOptions

    private enum CodingKeys: String, CodingKey {
        case challengeId = "challenge_id"
        case publicKey = "public_key"
    }
}

public struct CreationOptions: Decodable, Equatable, Sendable {
    public struct Party: Decodable, Equatable, Sendable {
        public let id: String
    }

    public struct User: Decodable, Equatable, Sendable {
        public let id: Base64URL
        public let name: String
        public let displayName: String
    }

    public let rp: Party
    public let user: User
    public let challenge: Base64URL
    public let excludeCredentials: [CredentialDescriptor]

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        rp = try fields.decode(Party.self, forKey: .rp)
        user = try fields.decode(User.self, forKey: .user)
        challenge = try fields.decode(Base64URL.self, forKey: .challenge)
        excludeCredentials = try fields.decodeIfPresent([CredentialDescriptor].self, forKey: .excludeCredentials) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case rp, user, challenge, excludeCredentials
    }
}

public struct CredentialDescriptor: Decodable, Equatable, Sendable {
    public let id: Base64URL
}

/// What `register/finish` answers: the passkey is enrolled for `fde`. It mints no session.
public struct Enrollment: Decodable, Equatable, Sendable {
    public let fde: String
}

/// What `login/start` answers: the ceremony's id and the request options (no allow list: any
/// passkey for the relying party).
public struct SignInCeremony: Decodable, Equatable, Sendable {
    public struct Options: Decodable, Equatable, Sendable {
        public let challenge: Base64URL
        public let rpId: String
    }

    public let challengeId: String
    public let publicKey: Options

    private enum CodingKeys: String, CodingKey {
        case challengeId = "challenge_id"
        case publicKey = "public_key"
    }
}

/// What `login/finish` answers, and what the vault keeps: a session for `fde` until `expiresAt`.
/// Its description never holds the token.
public struct PasskeySession: Codable, Equatable, Sendable, CustomStringConvertible {
    public let sessionToken: String
    public let fde: String
    public let expiresAt: Date

    public init(sessionToken: String, fde: String, expiresAt: Date) {
        self.sessionToken = sessionToken
        self.fde = fde
        self.expiresAt = expiresAt
    }

    public var description: String {
        "a session for \(fde) until \(expiresAt)"
    }

    private enum CodingKeys: String, CodingKey {
        case fde
        case sessionToken = "session_token"
        case expiresAt = "expires_at"
    }
}

/// One of the caller's own passkeys: its id, its label, when it was enrolled and last signed in
/// with (none before its first use), and whether this session was signed in with it.
public struct OwnPasskey: Codable, Equatable, Sendable {
    public let id: String
    public let label: String
    public let createdAt: Date
    public let lastUsedAt: Date?
    public let current: Bool

    private enum CodingKeys: String, CodingKey {
        case id, label, current
        case createdAt = "created_at"
        case lastUsedAt = "last_used_at"
    }
}

/// The caller's own passkeys, oldest first.
public struct OwnPasskeys: Codable, Equatable, Sendable {
    public let passkeys: [OwnPasskey]
}

extension Endpoint where Response == OwnPasskeys {
    public static var passkeys: Self {
        Self(.get, Route.passkeys)
    }
}

extension Endpoint where Response == NoContent {
    /// Removes the caller's passkey `id`, and ends the sessions it signed in; the server refuses the
    /// last one, the one this session signed in with, and any but a passkey session's call.
    public static func removePasskey(_ id: String) -> Self {
        Self(.delete, Route.passkeys + [id])
    }
}

/// What a platform or software authenticator made at registration.
public struct PasskeyRegistration: Equatable, Sendable {
    public let credentialId: Data
    public let clientDataJSON: Data
    public let attestationObject: Data

    public init(credentialId: Data, clientDataJSON: Data, attestationObject: Data) {
        self.credentialId = credentialId
        self.clientDataJSON = clientDataJSON
        self.attestationObject = attestationObject
    }
}

/// What a platform or software authenticator signed at sign-in.
public struct PasskeyAssertion: Equatable, Sendable {
    public let credentialId: Data
    public let clientDataJSON: Data
    public let authenticatorData: Data
    public let signature: Data
    public let userHandle: Data?

    public init(credentialId: Data, clientDataJSON: Data, authenticatorData: Data, signature: Data, userHandle: Data?) {
        self.credentialId = credentialId
        self.clientDataJSON = clientDataJSON
        self.authenticatorData = authenticatorData
        self.signature = signature
        self.userHandle = userHandle
    }
}

/// What a control plane says of itself, unasked who is calling: Sail's answers `ok`.
public struct Health: Decodable, Equatable, Sendable {
    public let status: String
}

extension Endpoint where Response == Health {
    /// The liveness probe, the one route a control plane answers anyone.
    public static var health: Self {
        Self(.get, Route.health)
    }
}

extension Endpoint where Response == RegistrationCeremony {
    /// The ceremony that enrolls a passkey for the FDE `ticket` names.
    public static func registrationStart(ticket: String) -> Self {
        Self(.post, Route.registerStart, headers: [Route.ticketHeader: ticket], body: Data("{}".utf8))
    }
}

extension Endpoint where Response == Enrollment {
    /// The registration `ticket` started, finished: the ticket is spent.
    public static func registrationFinish(
        ticket: String, challengeId: String, _ registration: PasskeyRegistration, label: String
    ) throws(SailError) -> Self {
        let body = [
            "challenge_id": challengeId, "client_data_json": Base64URL.encode(registration.clientDataJSON),
            "attestation_object": Base64URL.encode(registration.attestationObject), "label": label,
        ]
        return Self(.post, Route.registerFinish, headers: [Route.ticketHeader: ticket], body: try SailJSON.encode(body))
    }
}

extension Endpoint where Response == SignInCeremony {
    public static var signInStart: Self {
        Self(.post, Route.loginStart, body: Data("{}".utf8))
    }
}

extension Endpoint where Response == PasskeySession {
    public static func signInFinish(challengeId: String, _ assertion: PasskeyAssertion) throws(SailError) -> Self {
        var body = [
            "challenge_id": challengeId, "credential_id": Base64URL.encode(assertion.credentialId),
            "client_data_json": Base64URL.encode(assertion.clientDataJSON),
            "authenticator_data": Base64URL.encode(assertion.authenticatorData), "signature": Base64URL.encode(assertion.signature),
        ]
        body["user_handle"] = assertion.userHandle.map(Base64URL.encode)
        return Self(.post, Route.loginFinish, body: try SailJSON.encode(body))
    }
}
