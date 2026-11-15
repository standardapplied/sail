import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The passkey routes as the server recorded them: each answer decodes, and each request this
/// client builds from the same values is the body the server read.
@Suite struct PasskeyRouteTests {
    func body(_ name: String) throws -> [String: String] {
        try #require(try JSONSerialization.jsonObject(with: Exchange(name).requestBody ?? Data()) as? [String: String])
    }

    func bytes(_ text: String?) throws -> Data {
        try #require(text.flatMap(Base64URL.decode))
    }

    @Test func registrationStartSendsTheTicketAndReadsTheCreationOptions() throws {
        let exchange = try Exchange("passkey-register-start")
        let endpoint = Endpoint.registrationStart(ticket: aTicket)
        #expect(endpoint.request.route == "\(exchange.method) \(exchange.path)")
        #expect(endpoint.request.headers == ["Content-Type": "application/json", "X-Enrollment-Ticket": aTicket])
        #expect(try json(endpoint.request.body) == json(exchange.requestBody))
        let ceremony = try endpoint.decode(response(exchange))
        #expect(ceremony.challengeId == "wac_golden1")
        #expect(ceremony.publicKey.rp.id == "sail.standardapplied.com")
        #expect(ceremony.publicKey.user.name == "uday" && ceremony.publicKey.user.displayName == "Uday")
        #expect(!ceremony.publicKey.challenge.data.isEmpty && !ceremony.publicKey.user.id.data.isEmpty)
        #expect(ceremony.publicKey.excludeCredentials.isEmpty)
        let enrolled = try Endpoint.registrationStart(ticket: aTicket).decode(response(Exchange("passkey-register-start-enrolled")))
        #expect(enrolled.publicKey.excludeCredentials.count == 1)
    }

    @Test func registrationFinishSendsWhatThePasskeyMadeAndReadsTheEnrollment() throws {
        let exchange = try Exchange("passkey-register-finish")
        let sent = try body("passkey-register-finish")
        let made = PasskeyRegistration(
            credentialId: Data(), clientDataJSON: try bytes(sent["client_data_json"]), attestationObject: try bytes(sent["attestation_object"])
        )
        let endpoint = try Endpoint.registrationFinish(ticket: aTicket, challengeId: sent["challenge_id"] ?? "", made, label: "Sail for Mac")
        #expect(endpoint.request.route == "\(exchange.method) \(exchange.path)")
        #expect(endpoint.request.headers["X-Enrollment-Ticket"] == aTicket)
        #expect(try json(endpoint.request.body) == json(exchange.requestBody))
        let enrollment = try endpoint.decode(response(exchange))
        #expect(enrollment.fde == "uday")
    }

    @Test func signInStartReadsTheRequestOptions() throws {
        let exchange = try Exchange("passkey-login-start")
        #expect(Endpoint.signInStart.request.route == "\(exchange.method) \(exchange.path)")
        #expect(Endpoint.signInStart.request.headers["Authorization"] == nil)
        #expect(try json(Endpoint.signInStart.request.body) == json(exchange.requestBody))
        let ceremony = try Endpoint.signInStart.decode(response(exchange))
        #expect(ceremony.challengeId == "wac_golden9")
        #expect(ceremony.publicKey.rpId == "sail.standardapplied.com")
    }

    @Test func signInFinishSendsTheAssertionAndReadsTheSession() throws {
        let exchange = try Exchange("passkey-login-finish")
        let sent = try body("passkey-login-finish")
        let signed = PasskeyAssertion(
            credentialId: try bytes(sent["credential_id"]), clientDataJSON: try bytes(sent["client_data_json"]),
            authenticatorData: try bytes(sent["authenticator_data"]), signature: try bytes(sent["signature"]), userHandle: nil
        )
        let endpoint = try Endpoint.signInFinish(challengeId: sent["challenge_id"] ?? "", signed)
        #expect(try json(endpoint.request.body) == json(exchange.requestBody))
        let session = try endpoint.decode(response(exchange))
        #expect(session.sessionToken == "sess_golden14" && session.fde == "uday")
        #expect(session.expiresAt.milliseconds == Goldens.pinnedMilliseconds + 30 * 86_400_000)
        #expect(!"\(session)".contains("sess_"))
    }

    @Test(arguments: [
        ("refusal-forbidden", ErrorCode.forbidden), ("refusal-passkey-not-configured", .passkeyNotConfigured),
        ("refusal-invalid-request", .invalidRequest), ("refusal-authentication-failed", .authenticationFailed),
    ])
    func eachPasskeyRefusalDecodesWithItsCode(_ name: String, _ code: ErrorCode) throws {
        do {
            let answered = try Endpoint.signInStart.decode(response(Exchange(name)))
            Issue.record("\(name) decoded as \(answered)")
        } catch let SailError.refused(refusal) {
            #expect(refusal.code == .known(code))
        }
    }

    @Test func base64URLRoundTripsWithoutPaddingAndRefusesOtherText() {
        for length in 0 ... 7 {
            let data = Data((0 ..< length).map { UInt8(($0 * 37 + 250) % 256) })
            let text = Base64URL.encode(data)
            #expect(!text.contains("=") && !text.contains("+") && !text.contains("/"))
            #expect(Base64URL.decode(text) == data)
        }
        #expect(Base64URL.decode("a+b/") == nil)
        #expect(Base64URL.decode("ab==") == nil)
    }
}
