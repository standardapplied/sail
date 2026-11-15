import Foundation
import SailAPI
import SailKitTestSupport
import Synchronization
import Testing

/// The passkey exchanges the server recorded; `PasskeyRouteTests` and `AccountTests` read them.
let passkeyExchanges: Set<String> = [
    "passkey-register-start", "passkey-register-start-enrolled", "passkey-register-finish", "passkey-login-start",
    "passkey-login-finish", "refusal-forbidden", "refusal-passkey-not-configured", "refusal-invalid-request",
    "refusal-authentication-failed",
]

let preview = "http://100.84.93.13:7443"
let aTicket = "enr_0123456789abcdef0123456789abcdef"

/// An authenticator that answers as it was told and keeps what it was asked.
final class ToldPasskey: PasskeyAuthenticator {
    private let asked = Mutex<(registrations: [PasskeyRegistrationRequest], assertions: [PasskeyAssertionRequest])>(([], []))
    private let failure: PasskeyFailure?

    init(failing failure: PasskeyFailure? = nil) {
        self.failure = failure
    }

    var registrations: [PasskeyRegistrationRequest] {
        asked.withLock { $0.registrations }
    }

    var assertions: [PasskeyAssertionRequest] {
        asked.withLock { $0.assertions }
    }

    static let made = PasskeyRegistration(credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.create\"}".utf8), attestationObject: Data([0xA3, 0x63]))
    static let signed = PasskeyAssertion(
        credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.get\"}".utf8), authenticatorData: Data([5, 6]),
        signature: Data([7, 8]), userHandle: Data("uday".utf8)
    )

    func register(_ request: PasskeyRegistrationRequest) throws(PasskeyFailure) -> PasskeyRegistration {
        asked.withLock { $0.registrations.append(request) }
        if let failure { throw failure }
        return Self.made
    }

    func assert(_ request: PasskeyAssertionRequest) throws(PasskeyFailure) -> PasskeyAssertion {
        asked.withLock { $0.assertions.append(request) }
        if let failure { throw failure }
        return Self.signed
    }
}

/// A vault holding sessions by control plane, only those it was given.
final class MemoryVault: TokenVault {
    private let held = Mutex<[ControlPlane: PasskeySession]>([:])
    private let failure: VaultFailure?

    init(failing failure: VaultFailure? = nil) {
        self.failure = failure
    }

    func holding(_ session: PasskeySession, at plane: ControlPlane) -> Self {
        held.withLock { $0[plane] = session }
        return self
    }

    var sessions: [ControlPlane: PasskeySession] {
        held.withLock { $0 }
    }

    func session(for controlPlane: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        if let failure { throw failure }
        return held.withLock { $0[controlPlane] }
    }

    func keep(_ session: PasskeySession, for controlPlane: ControlPlane) throws(VaultFailure) {
        if let failure { throw failure }
        held.withLock { $0[controlPlane] = session }
    }

    func forget(_ controlPlane: ControlPlane) throws(VaultFailure) {
        if let failure { throw failure }
        held.withLock { $0[controlPlane] = nil }
    }
}

/// A vault holding one session that it cannot forget, as a locked Keychain refuses a delete.
final class StuckVault: TokenVault {
    private let held: PasskeySession

    init(held: PasskeySession) {
        self.held = held
    }

    func session(for controlPlane: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        held
    }

    func keep(_ session: PasskeySession, for controlPlane: ControlPlane) throws(VaultFailure) {}

    func forget(_ controlPlane: ControlPlane) throws(VaultFailure) {
        throw VaultFailure(status: "User interaction is not allowed. (-25308)")
    }
}

func plane(_ address: String = preview) throws -> ControlPlane {
    try ControlPlane(address: address)
}

/// An account whose control plane is a server answering with `exchanges`, and that server.
func makeAccount(
    _ exchanges: [Exchange], passkey: ToldPasskey = ToldPasskey(), vault: any TokenVault = MemoryVault()
) throws -> (Account, RecordedServer) {
    let server = RecordedServer(exchanges)
    let address = try plane()
    return (Account(authenticator: passkey, vault: vault) { asked in
        precondition(asked == address, "the account reached \(asked), not \(address)")
        return server
    }, server)
}

func refusal(_ act: () async throws(AccountRefusal) -> some Any) async -> AccountRefusal? {
    do {
        _ = try await act()
        return nil
    } catch {
        return error
    }
}

/// The session the recorded sign-in mints: 30 days from the golden writer's clock.
let thirtyDays: Int64 = 30 * 86_400_000
let held = PasskeySession(sessionToken: "sess_golden14", fde: "uday", expiresAt: Date(timeIntervalSince1970: Double(Goldens.pinnedMilliseconds + thirtyDays) / 1000))

@Suite struct AddressRuleTests {
    @Test(arguments: [
        ("http://100.84.93.13:7443", "http://100.84.93.13:7443"), ("http://10.0.0.2", "http://10.0.0.2"),
        ("http://[fd00::1]:7443", "http://[fd00::1]:7443"), ("http://127.0.0.1:7979", "http://127.0.0.1:7979"),
        ("https://x.example", "https://x.example"), ("HTTPS://Box.Example:8443/", "https://box.example:8443"),
        ("http://172.16.0.1", "http://172.16.0.1"), ("http://172.31.255.255", "http://172.31.255.255"),
        ("http://192.168.1.1", "http://192.168.1.1"), ("http://100.127.0.1", "http://100.127.0.1"), ("http://[::1]", "http://[::1]"),
        ("http://[FC00::7]", "http://[fc00::7]"), ("  http://10.0.0.2:1  ", "http://10.0.0.2:1"),
        ("http://10.0.0.2:65535", "http://10.0.0.2:65535"),
    ])
    func anAddressThePlainHTTPRuleAllows(_ address: String, _ kept: String) throws {
        #expect(try ControlPlane(address: address).description == kept)
    }

    @Test(arguments: [
        ("http://box.ts.net", "box.ts.net"), ("http://8.8.8.8", "8.8.8.8"), ("http://172.15.0.1", "172.15.0.1"),
        ("http://172.32.0.1", "172.32.0.1"), ("http://100.63.0.1", "100.63.0.1"), ("http://100.128.0.1", "100.128.0.1"),
        ("http://169.254.1.1", "169.254.1.1"), ("http://[fe80::1]", "fe80::1"), ("http://[2001:db8::1]", "2001:db8::1"),
        ("http://010.0.0.1", "010.0.0.1"), ("http://10.1", "10.1"), ("http://localhost", "localhost"),
        ("http://[::ffff:10.0.0.1]", "::ffff:10.0.0.1"),
    ])
    func plainHTTPToAnythingElseIsRefusedNamingTheHost(_ address: String, _ host: String) throws {
        #expect(throws: AccountRefusal.self) { try ControlPlane(address: address) }
        do {
            _ = try ControlPlane(address: address)
        } catch {
            #expect(error.reason == .addressNotAllowed(host))
            #expect(error.sentence == "Sail sends plain HTTP only to a private-network address. Use https:// for \(host).")
        }
    }

    @Test(arguments: [
        "100.84.93.13:7443", "ftp://10.0.0.2", "http://", "http://u:p@10.0.0.2", "http://10.0.0.2/v1", "http://10.0.0.2?x=1",
        "http://10.0.0.2#top", "enr_0123456789abcdef", "", "http://10.0.0.2:0", "http://10.0.0.2:99999",
    ])
    func whatIsNotAnAddressIsSaidWithoutEchoingIt(_ text: String) {
        do {
            _ = try ControlPlane(address: text)
            Issue.record("\(text) was taken as an address")
        } catch {
            #expect(error.reason == .notAnAddress)
            #expect(error.lines == [
                "That is not a control plane address.",
                "Type it as https://<name>, or http://<private IP address>:<port> such as http://10.0.0.2:7443.",
            ])
        }
    }

    @Test func aTicketIsTakenBareOrFromTheLinkSailFdeEnrollPrinted() throws {
        #expect(try EnrollmentTicket.parse("  \(aTicket)\n") == aTicket)
        #expect(try EnrollmentTicket.parse("https://sail.standardapplied.com/enroll?ticket=\(aTicket)") == aTicket)
        for junk in ["", "short", "https://sail.standardapplied.com/enroll", "enr_0123 456789abcdef", "enr_0123456789abcdef&x=1"] {
            do {
                _ = try EnrollmentTicket.parse(junk)
                Issue.record("'\(junk)' was taken as a ticket")
            } catch {
                #expect(error.reason == .notATicket)
            }
        }
    }
}

@Suite struct AccountTests {
    @Test func enrollingGivesTheServersCeremonyToThePasskeyUnchangedAndSpendsTheTicket() async throws {
        let passkey = ToldPasskey()
        let (account, server) = try makeAccount([Exchange("passkey-register-start"), Exchange("passkey-register-finish")], passkey: passkey)
        let enrolled = try await account.enroll(ticket: "https://sail.standardapplied.com/enroll?ticket=\(aTicket)", at: plane())
        #expect(enrolled.fde == "uday")
        let options = try JSONSerialization.jsonObject(with: Exchange("passkey-register-start").body) as? [String: Any]
        let publicKey = try #require(options?["public_key"] as? [String: Any])
        let user = try #require(publicKey["user"] as? [String: String])
        let asked = try #require(passkey.registrations.first)
        #expect(passkey.registrations.count == 1)
        #expect(asked.relyingParty == "sail.standardapplied.com")
        #expect(Base64URL.encode(asked.challenge) == publicKey["challenge"] as? String)
        #expect(Base64URL.encode(asked.userId) == user["id"])
        #expect(asked.userName == "uday" && asked.displayName == "Uday" && asked.excluded.isEmpty)
        let requests = await server.requests
        #expect(requests.map(\.path) == ["/v1/auth/register/start", "/v1/auth/register/finish"])
        #expect(requests.allSatisfy { $0.headers["X-Enrollment-Ticket"] == aTicket && $0.headers["Authorization"] == nil })
        let finish = try #require(try json(requests.last?.body))
        #expect(finish == [
            "challenge_id": "wac_golden1", "label": "Sail for Mac",
            "client_data_json": Base64URL.encode(ToldPasskey.made.clientDataJSON),
            "attestation_object": Base64URL.encode(ToldPasskey.made.attestationObject),
        ])
    }

    @Test func aPasskeyEnrolledThereIsOfferedAsExcluded() async throws {
        let passkey = ToldPasskey()
        let start = try Exchange("passkey-register-start-enrolled")
        let (account, _) = try makeAccount([start, Exchange("passkey-register-finish")], passkey: passkey)
        _ = try await account.enroll(ticket: aTicket, at: plane())
        let enrolled = try Exchange("passkey-register-finish")
        let credential = try #require((try JSONSerialization.jsonObject(with: enrolled.body) as? [String: Any])?["credential_id"] as? String)
        #expect(passkey.registrations.first?.excluded.map(Base64URL.encode) == [credential])
    }

    @Test func signingInKeepsTheSessionInTheVaultAndAsksWhoItIsWithIt() async throws {
        let vault = MemoryVault()
        let passkey = ToldPasskey()
        let (account, server) = try makeAccount(
            [Exchange("passkey-login-start"), Exchange("passkey-login-finish"), Exchange("whoami")], passkey: passkey, vault: vault
        )
        let address = try plane()
        let signedIn = try await account.signIn(at: address)
        let kept = try #require(vault.sessions[address])
        #expect(vault.sessions.count == 1 && kept.sessionToken == held.sessionToken && kept.fde == "uday")
        #expect(kept.expiresAt.milliseconds == Goldens.pinnedMilliseconds + thirtyDays)
        #expect(signedIn.fde == "uday" && signedIn.until == kept.expiresAt && signedIn.controlPlane == address)
        let whoami = try signedIn.whoami.get()
        #expect(whoami.displayName == "Uday" && whoami.role == .known(.admin))
        #expect(passkey.assertions.map(\.relyingParty) == ["sail.standardapplied.com"])
        let requests = await server.requests
        #expect(requests.map(\.path) == ["/v1/auth/login/start", "/v1/auth/login/finish", "/v1/whoami"])
        #expect(requests.map { $0.headers["Authorization"] } == [nil, nil, "Bearer sess_golden14"])
        #expect(try json(requests[1].body) == [
            "challenge_id": "wac_golden9", "credential_id": "AQID", "client_data_json": Base64URL.encode(ToldPasskey.signed.clientDataJSON),
            "authenticator_data": "BQY", "signature": "Bwg", "user_handle": "dWRheQ",
        ])
    }

    @Test func aSessionInTheVaultIsUsedWithNoCeremony() async throws {
        let passkey = ToldPasskey()
        let (account, _) = try makeAccount([Exchange("whoami")], passkey: passkey, vault: MemoryVault().holding(held, at: plane()))
        let signedIn = try #require(try await account.current(at: plane()))
        #expect(try signedIn.whoami.get().fde == "uday")
        #expect(passkey.registrations.isEmpty && passkey.assertions.isEmpty)
        let (other, _) = try makeAccount([], passkey: passkey)
        #expect(try await other.current(at: plane()) == nil)
    }

    @Test(arguments: [
        "example.com", "evil.standardapplied.com", "x.sail.standardapplied.com", "standardapplied.com", "sail.standardapplied.com.",
        "sail.example.com", "sail.standardapplied.co", "SAIL.standardapplied.com", "",
    ])
    func aRelyingPartyOtherThanSailsIsRefusedBeforeThePasskeyIsAsked(_ party: String) async throws {
        let passkey = ToldPasskey()
        let enrolling = try Exchange("passkey-register-start").altered(at: ["public_key", "rp", "id"], to: party)
        let signing = try Exchange("passkey-login-start").altered(at: ["public_key", "rpId"], to: party)
        let (account, _) = try makeAccount([enrolling, signing], passkey: passkey)
        let refusals = [await refusal { () async throws(AccountRefusal) in try await account.enroll(ticket: aTicket, at: try! plane()) },
                        await refusal { () async throws(AccountRefusal) in try await account.signIn(at: try! plane()) }]
        for refused in refusals {
            #expect(refused?.reason == .foreignRelyingParty(party))
            #expect(refused?.sentence == "\(preview) uses passkeys for \(party); Sail can use them only for sail.standardapplied.com.")
            #expect(refused?.remedy == "On the control plane, run:\nsail host config set webauthn-rp-id sail.standardapplied.com\nsudo systemctl restart sail-api")
        }
        #expect(passkey.registrations.isEmpty && passkey.assertions.isEmpty)
    }

    @Test func aForbiddenWhoamiSignsNoOneOut() async throws {
        let vault = MemoryVault().holding(held, at: try plane())
        let forbidden = try Exchange("refusal-forbidden")
        let whoami = try Exchange("whoami")
        let answer = Exchange(method: whoami.method, path: whoami.path, status: forbidden.status, body: forbidden.body)
        let (account, _) = try makeAccount([answer], vault: vault)
        let signedIn = try #require(try await account.current(at: plane()))
        #expect(vault.sessions == [try plane(): held])
        guard case let .failure(refused) = signedIn.whoami else {
            Issue.record("whoami answered")
            return
        }
        #expect(refused.reason == .refused && refused.sentence == "Enrollment ticket is invalid or expired.")
    }

    @Test func signedInWithTheControlPlaneUnreachableStaysSignedInAndSaysSo() async throws {
        let vault = MemoryVault().holding(held, at: try plane())
        let (account, _) = try makeAccount([], vault: vault)
        let signedIn = try #require(try await account.current(at: plane()))
        #expect(signedIn.fde == "uday" && signedIn.until == held.expiresAt)
        #expect(vault.sessions == [try plane(): held])
        guard case let .failure(refused) = signedIn.whoami else {
            Issue.record("whoami answered")
            return
        }
        #expect(refused.reason == .unreachable && refused.sentence == "Sail cannot reach \(preview).")
    }

    /// Only `invalid_bearer_token` and `missing_bearer_token` end a session: every other code the
    /// server's refusals carry, and one it may grow, leaves it.
    @Test(arguments: ErrorCode.allCases.filter { ![.invalidBearerToken, .missingBearerToken].contains($0) }.map(\.rawValue) + ["harbor_closed"])
    func noOtherRefusalEndsTheSession(_ code: String) async throws {
        let vault = MemoryVault().holding(held, at: try plane())
        let refusing = try Exchange("refusal-not-found").altered(at: ["error", "code"], to: code)
        let whoami = try Exchange("whoami")
        let (account, _) = try makeAccount([Exchange(method: whoami.method, path: whoami.path, status: refusing.status, body: refusing.body)], vault: vault)
        let signedIn = try #require(try await account.current(at: plane()))
        #expect(vault.sessions == [try plane(): held], "\(code) ended the session")
        guard case let .failure(refused) = signedIn.whoami else {
            Issue.record("whoami answered")
            return
        }
        #expect(refused.reason != .ended)
    }

    @Test func anEndedSessionIsSaidEvenWhenTheVaultCannotForgetIt() async throws {
        let vault = StuckVault(held: held)
        let (account, _) = try makeAccount([Exchange("refusal-invalid-bearer-token")], vault: vault)
        let refused = await refusal { () async throws(AccountRefusal) in try await account.current(at: try! plane()) }
        #expect(refused?.reason == .ended)
    }

    @Test func aVaultThatCannotBeReadSaysItCouldNotRead() async throws {
        let (account, _) = try makeAccount([], vault: MemoryVault(failing: VaultFailure(status: "User interaction is not allowed. (-25308)")))
        let refused = await refusal { () async throws(AccountRefusal) in try await account.current(at: try! plane()) }
        #expect(refused?.sentence == "Sail could not read your sign-in in the Keychain: User interaction is not allowed. (-25308).")
    }

    @Test(arguments: ["refusal-invalid-bearer-token", "refusal-missing-bearer-token"])
    func anEndedSessionIsForgottenAndSaid(_ golden: String) async throws {
        let vault = MemoryVault().holding(held, at: try plane())
        let (account, _) = try makeAccount([Exchange(golden)], vault: vault)
        let refused = await refusal { () async throws(AccountRefusal) in try await account.current(at: try! plane()) }
        #expect(refused?.reason == .ended)
        #expect(refused?.sentence == "Your sign-in to \(preview) has ended.")
        #expect(refused?.remedy == "Sign in again with your passkey.")
        #expect(vault.sessions.isEmpty)
    }

    @Test func signingOutForgetsTheSessionOnThisMacOnly() async throws {
        let vault = MemoryVault().holding(held, at: try plane()).holding(held, at: try plane("http://10.0.0.2"))
        let (account, server) = try makeAccount([], vault: vault)
        try account.signOut(at: plane())
        #expect(vault.sessions == [try plane("http://10.0.0.2"): held])
        #expect(await server.requests.isEmpty)
    }
}

@Suite struct RefusalRowTests {
    func refused(
        _ exchanges: [Exchange], passkey: ToldPasskey = ToldPasskey(), vault: any TokenVault = MemoryVault(), signingIn: Bool
    ) async throws -> AccountRefusal? {
        let (account, _) = try makeAccount(exchanges, passkey: passkey, vault: vault)
        return signingIn
            ? await refusal { () async throws(AccountRefusal) in try await account.signIn(at: try! plane()) }
            : await refusal { () async throws(AccountRefusal) in try await account.enroll(ticket: aTicket, at: try! plane()) }
    }

    @Test func row2AControlPlaneThatCannotBeReached() async throws {
        let refused = try await refused([], signingIn: true)
        #expect(refused?.reason == .unreachable)
        #expect(refused?.lines == [
            "Sail cannot reach \(preview).", "Check that this Mac is on its network and the address is right.",
            "no golden answers POST /v1/auth/login/start",
        ])
    }

    @Test func row3PasskeysNotConfiguredSaysWhatToRunAndTheServersWords() async throws {
        let notConfigured = try Exchange("refusal-passkey-not-configured")
        let start = Exchange(method: "POST", path: "/v1/auth/register/start", status: notConfigured.status, body: notConfigured.body)
        let refused = try await refused([start], signingIn: false)
        #expect(refused?.reason == .passkeysNotConfigured)
        #expect(refused?.lines == [
            "\(preview) does not take passkeys yet.",
            """
            On the control plane, run:
            sail host config set webauthn-rp-id sail.standardapplied.com
            sail host config set webauthn-rp-name Sail
            sail host config set webauthn-origin https://sail.standardapplied.com
            sudo systemctl restart sail-api
            """,
            "Passkey login is not configured on this server.", "Set the webauthn block in host.yaml or pass --origin to 'server start'.",
        ])
    }

    @Test func row5ASpentTicket() async throws {
        let refused = try await refused([Exchange("refusal-forbidden")], signingIn: false)
        #expect(refused?.reason == .ticketRefused)
        #expect(refused?.lines == [
            "This enrollment ticket is used, expired or not from \(preview).",
            "A ticket lasts 15 minutes and enrolls one passkey. Ask an admin: sail fde enroll <handle>.",
            "Enrollment ticket is invalid or expired.",
        ])
    }

    @Test func row6AnOriginTheServerDoesNotAcceptIsNamedInTheCommandToFixIt() async throws {
        let refused = try await refused([Exchange("passkey-register-start"), Exchange("refusal-invalid-request")], signingIn: false)
        #expect(refused?.reason == .originNotAllowed("https://elsewhere.example"))
        #expect(refused?.sentence == "\(preview) does not accept passkeys from this app.")
        #expect(refused?.remedy == "On the control plane, run:\nsail host config set webauthn-origin https://elsewhere.example\nsudo systemctl restart sail-api")
        #expect(refused?.server?.message == "Registration origin not allowed: https://elsewhere.example")
    }

    @Test func row8APasskeyTheServerDoesNotAccept() async throws {
        let refused = try await refused([Exchange("passkey-login-start"), Exchange("refusal-authentication-failed")], signingIn: true)
        #expect(refused?.reason == .notAccepted)
        #expect(refused?.lines.prefix(3) == [
            "\(preview) did not accept that passkey.", "Pick the passkey you enrolled there, or enroll one.", "Passkey authentication failed.",
        ])
    }

    @Test(arguments: [
        (PasskeyFailure.alreadyHeld, false, ["This Mac already holds your passkey for \(preview).", "Sign in with it."]),
        (.associationNotConfirmed("Unable to verify webcredentials association"), false, [
            "macOS could not confirm that Sail may use passkeys for sail.standardapplied.com.",
            "Try again later; if it stays, tell the Sail team.", "Unable to verify webcredentials association",
        ]),
        (.cannotCreate("not set up"), false, ["This Mac cannot make passkeys right now.", "Turn on iCloud Keychain in System Settings.", "not set up"]),
        (.cancelled, false, ["Cancelled."]),
        (.cancelled, true, ["Cancelled.", "If this Mac holds no passkey for \(preview), enroll one with a ticket from an admin."]),
        (.failed("The operation couldn’t be completed. (1004)"), true, ["macOS did not finish the passkey request.", "The operation couldn’t be completed. (1004)"]),
    ])
    func rows9To12WhatThePasskeySaid(_ failure: PasskeyFailure, _ signingIn: Bool, _ lines: [String]) async throws {
        let exchanges = try [Exchange("passkey-register-start"), Exchange("passkey-login-start")]
        let refused = try await refused(exchanges, passkey: ToldPasskey(failing: failure), signingIn: signingIn)
        #expect(refused?.lines == lines)
    }

    @Test func row13AVaultThatRefusesSaysItsStatusAndKeepsNoSession() async throws {
        let vault = MemoryVault(failing: VaultFailure(status: "errSecMissingEntitlement (-34018)", remedy: "This build is unsigned. Use a signed build."))
        let refused = try await refused([Exchange("passkey-login-start"), Exchange("passkey-login-finish")], vault: vault, signingIn: true)
        #expect(refused?.lines == [
            "Sail could not keep your sign-in in the Keychain: errSecMissingEntitlement (-34018).", "This build is unsigned. Use a signed build.",
        ])
    }

    @Test func row15AnyOtherRefusalIsTheServersMessageAndAction() async throws {
        let created = try Exchange("refusal-project-not-created")
        let start = Exchange(method: "POST", path: "/v1/auth/login/start", status: created.status, body: created.body)
        let refused = try await refused([start], signingIn: true)
        #expect(refused?.reason == .refused)
        #expect(refused?.lines == ["Project 'harbor' has no container on this box.", "Create it: project apply harbor"])
    }

    @Test func noLineOfAnyRefusalHoldsTheTicketOrTheToken() async throws {
        let token = "sess_golden14"
        var all: [AccountRefusal] = []
        for golden in ["refusal-forbidden", "refusal-passkey-not-configured", "refusal-invalid-request", "refusal-authentication-failed"] {
            for signingIn in [false, true] {
                let exchanges = try [Exchange("passkey-register-start"), Exchange("passkey-login-start"), Exchange(golden)]
                if let refused = try await refused(exchanges, signingIn: signingIn) { all.append(refused) }
            }
        }
        for failure in [PasskeyFailure.cancelled, .alreadyHeld, .associationNotConfirmed("a"), .cannotCreate("b"), .failed("c")] {
            let exchanges = try [Exchange("passkey-register-start"), Exchange("passkey-login-start")]
            if let refused = try await refused(exchanges, passkey: ToldPasskey(failing: failure), signingIn: false) { all.append(refused) }
        }
        #expect(all.count >= 13)
        #expect(all.flatMap(\.lines).allSatisfy { !$0.contains(aTicket) && !$0.contains(token) })
        #expect("\(held)" == "a session for uday until \(held.expiresAt)")
    }
}
