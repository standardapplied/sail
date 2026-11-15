import CryptoKit
import Foundation
import SailAPI
import Synchronization

/// A passkey made in software, for the drive and tests only, never shipped: a P-256 key per
/// credential, attestation `none`, user present and verified, the origin `https://<rp id>`. What
/// it answers next is set by the drive: a credential, none held (answered as the platform answers
/// it, a cancel) or a cancel. Its credentials live in `file`, so a second launch signs in with
/// what the first enrolled.
public final class SoftwarePasskey: PasskeyAuthenticator {
    public static let marker = "sail-test-passkey-not-for-shipping"

    public enum Answer: String, Codable, Sendable {
        case credential, none, cancel
    }

    struct Credential: Codable {
        let id: Data
        let relyingParty: String
        let userHandle: Data
        let key: Data
        var signCount: UInt32
    }

    struct State: Codable {
        var marker = SoftwarePasskey.marker
        var answer = Answer.credential
        var credentials: [Credential] = []
        var asked = 0
    }

    private let file: URL
    private let state: Mutex<State>

    public init(file: URL) {
        self.file = file
        let saved = (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode(State.self, from: $0) }
        state = Mutex(saved ?? State())
    }

    /// What the next ceremonies answer.
    public func answer(_ answer: Answer) {
        change { $0.answer = answer }
    }

    /// How many ceremonies it was asked for, over every launch.
    public var asked: Int {
        state.withLock { $0.asked }
    }

    /// The ids of the credentials it holds, base64url.
    public var credentialIds: [String] {
        state.withLock { $0.credentials.map { Base64URL.encode($0.id) } }
    }

    public func register(_ request: PasskeyRegistrationRequest) throws(PasskeyFailure) -> PasskeyRegistration {
        let made: Result<PasskeyRegistration, PasskeyFailure> = change { state in
            state.asked += 1
            guard state.answer == .credential else { return .failure(.cancelled) }
            if state.credentials.contains(where: { $0.relyingParty == request.relyingParty && request.excluded.contains($0.id) }) {
                return .failure(.alreadyHeld)
            }
            let key = P256.Signing.PrivateKey()
            let id = Data((0 ..< 16).map { _ in UInt8.random(in: 0 ... 255) })
            let point = key.publicKey.x963Representation.dropFirst()
            let cose = Data([0xA5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20]) + point.prefix(32) + Data([0x22, 0x58, 0x20])
                + point.suffix(32)
            let authenticatorData = Self.hash(request.relyingParty) + Data([0x45]) + Self.bigEndian(0) + Data(count: 16)
                + Data([UInt8(id.count >> 8), UInt8(id.count & 0xFF)]) + id + cose
            let attestation = Data([0xA3]) + Self.text("fmt") + Self.text("none") + Self.text("attStmt") + Data([0xA0]) + Self.text("authData")
                + Data([0x58, UInt8(authenticatorData.count)]) + authenticatorData
            state.credentials.append(Credential(id: id, relyingParty: request.relyingParty, userHandle: request.userId, key: key.rawRepresentation, signCount: 0))
            return .success(PasskeyRegistration(
                credentialId: id, clientDataJSON: Self.clientData("webauthn.create", request.challenge, request.relyingParty), attestationObject: attestation
            ))
        }
        return try made.get()
    }

    public func assert(_ request: PasskeyAssertionRequest) throws(PasskeyFailure) -> PasskeyAssertion {
        let signed: Result<PasskeyAssertion, PasskeyFailure> = change { state in
            state.asked += 1
            guard state.answer == .credential, let index = state.credentials.lastIndex(where: { $0.relyingParty == request.relyingParty })
            else { return .failure(.cancelled) }
            state.credentials[index].signCount += 1
            let credential = state.credentials[index]
            let authenticatorData = Self.hash(request.relyingParty) + Data([0x05]) + Self.bigEndian(credential.signCount)
            let clientData = Self.clientData("webauthn.get", request.challenge, request.relyingParty)
            guard let key = try? P256.Signing.PrivateKey(rawRepresentation: credential.key),
                  let signature = try? key.signature(for: authenticatorData + Data(SHA256.hash(data: clientData)))
            else { return .failure(.failed("the software passkey's key is unreadable")) }
            return .success(PasskeyAssertion(
                credentialId: credential.id, clientDataJSON: clientData, authenticatorData: authenticatorData,
                signature: signature.derRepresentation, userHandle: credential.userHandle
            ))
        }
        return try signed.get()
    }

    private func change<Value>(_ act: (inout State) -> Value) -> Value {
        state.withLock { state in
            let value = act(&state)
            if let data = try? JSONEncoder().encode(state) { try? data.write(to: file, options: .atomic) }
            return value
        }
    }

    private static func clientData(_ type: String, _ challenge: Data, _ relyingParty: String) -> Data {
        Data("{\"type\":\"\(type)\",\"challenge\":\"\(Base64URL.encode(challenge))\",\"origin\":\"https://\(relyingParty)\"}".utf8)
    }

    private static func hash(_ relyingParty: String) -> Data {
        Data(SHA256.hash(data: Data(relyingParty.utf8)))
    }

    private static func bigEndian(_ value: UInt32) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }

    private static func text(_ word: String) -> Data {
        Data([0x60 | UInt8(word.utf8.count)]) + Data(word.utf8)
    }
}
