import Foundation
import SailAPI
import SailKitTestSupport
import Synchronization
import Testing

/// Defaults of their own, empty: what this Mac remembers in one test, and nothing of another's.
func freshDefaults() throws -> UserDefaults {
    try #require(UserDefaults(suiteName: "sail-welcome-\(UUID().uuidString)"))
}

let example = "https://sail.example.com"

/// The exchange Continue makes, which `ReachTests` reads.
let reachExchanges: Set<String> = ["health"]

/// A control plane that never answers: a request waits until the task that sent it is cancelled,
/// as a URLSession request does, and is then said lost.
final class Silent: Transport, Sendable {
    private let held = Mutex<[AsyncStream<Never>.Continuation]>([])

    func send(_: HTTPRequest) async throws(SailError) -> HTTPResponse {
        let (never, kept) = AsyncStream<Never>.makeStream()
        held.withLock { $0.append(kept) }
        for await _ in never {}
        throw .unreachable("The request was cancelled.")
    }

    func open(_: HTTPRequest) async throws(SailError) -> HTTPStream {
        throw .unreachable("The request was cancelled.")
    }
}

/// Reaching a control plane: Continue's one unauthenticated `GET /v1/health`, and only Sail's answer
/// moves on.
@Suite struct ReachTests {
    func reached(_ typed: String, answering exchanges: [Exchange]) async throws -> (Result<ControlPlane, AccountRefusal>, [HTTPRequest]) {
        let server = RecordedServer(exchanges)
        let account = Account(authenticator: ToldPasskey(), vault: MemoryVault(), reachWithin: .seconds(Patience.answer)) { _ in server }
        do throws(AccountRefusal) {
            return (.success(try await account.reach(typed)), await server.requests)
        } catch {
            return (.failure(error), await server.requests)
        }
    }

    @Test func sailsHealthAnswerReachesItWithOneUnauthenticatedGet() async throws {
        let (result, requests) = try await reached("sail.example.com", answering: [Exchange("health")])
        #expect(try result.get().description == example)
        #expect(requests.map(\.route) == ["GET /v1/health"] && requests.first?.headers["Authorization"] == nil && requests.first?.body == nil)
    }

    @Test func anAnswerThatIsNotSailsHealthIsSaidAndGoesNoFurther() async throws {
        let health = try Exchange("health")
        let notOK = Exchange(method: health.method, path: health.path, status: 200, body: Data(#"{"schema_version": 1, "status": "starting"}"#.utf8))
        let notSail = Exchange(method: health.method, path: health.path, status: 200, body: Data("<html>hello</html>".utf8))
        let missing = Exchange(method: health.method, path: health.path, status: 404, body: Data("not found".utf8))
        for answer in [notOK, notSail, missing] {
            let (result, _) = try await reached(example, answering: [answer])
            #expect(throws: AccountRefusal.self) { try result.get() }
            guard case let .failure(refused) = result else { continue }
            #expect(refused.reason == .unreadable && Array(refused.lines.prefix(2)) == ["\(example) answered in a way Sail cannot read.", "Check that this is your team's control plane address."], "\(refused.lines)")
        }
    }

    @Test func aRedirectIsSaidAndNotFollowed() async throws {
        let health = try Exchange("health")
        let (result, requests) = try await reached(example, answering: [Exchange(method: health.method, path: health.path, status: 302, body: Data())])
        guard case let .failure(refused) = result else {
            Issue.record("a redirect reached the control plane")
            return
        }
        #expect(refused.reason == .redirected && requests.count == 1)
    }

    @Test func noAnswerIsSaidAsUnreachable() async throws {
        let (result, _) = try await reached(example, answering: [])
        guard case let .failure(refused) = result else {
            Issue.record("nothing answered and it was reached")
            return
        }
        #expect(Array(refused.lines.prefix(2)) == ["Sail cannot reach \(example).", "Check that this Mac is on its network and the address is right."])
    }

    /// A control plane that does not answer within the limit is said out of reach, as one that
    /// refuses the connection is; the request is cancelled, not left waiting. The limit is the
    /// test's, so no real second passes; its own bound is the time limit, should the limit not hold.
    @Test(.timeLimit(.minutes(1))) func noAnswerWithinTheLimitIsSaidAsUnreachable() async {
        let account = Account(authenticator: ToldPasskey(), vault: MemoryVault(), reachWithin: .zero) { _ in Silent() }
        do throws(AccountRefusal) {
            _ = try await account.reach("sail.example.com")
            Issue.record("a control plane that never answered was reached")
        } catch {
            #expect(error.reason == .unreachable && Array(error.lines.prefix(2)) == ["Sail cannot reach \(example).", "Check that this Mac is on its network and the address is right."])
        }
    }

    @Test func whatIsNotAnAddressIsRefusedBeforeAnythingIsSent() async throws {
        let (result, requests) = try await reached("http://sail.example.com", answering: [Exchange("health")])
        guard case let .failure(refused) = result else {
            Issue.record("plain HTTP to a name was sent")
            return
        }
        #expect(refused.reason == .addressNotAllowed("sail.example.com") && requests.isEmpty)
    }
}

/// What this Mac remembers, and the screen it shows from that alone.
@Suite struct SignInMemoryTests {
    @Test func noControlPlaneKnownIsTheWelcome() throws {
        let memory = SignInMemory(try freshDefaults())
        #expect(memory.plane == nil && memory.welcome.screen == .address)
    }

    @Test func aControlPlaneReachedWithNoSignInThereIsEnroll() throws {
        let memory = SignInMemory(try freshDefaults())
        let plane = try ControlPlane(address: example)
        memory.reached(plane)
        #expect(memory.plane == plane && memory.welcome.screen == .ticket && memory.welcome.plane == plane)
    }

    @Test func aSignInMadeThereIsWelcomeBackByTheFirstWordOfTheName() throws {
        let memory = SignInMemory(try freshDefaults())
        let plane = try ControlPlane(address: example)
        memory.signedIn(RememberedSignIn(handle: "mara", name: "Mara Quill"), at: plane)
        #expect(memory.plane == plane && memory.welcome.screen == .back && memory.welcome.title == "Welcome back, Mara")
        memory.signedIn(RememberedSignIn(handle: "mara", name: nil), at: plane)
        #expect(memory.welcome.title == "Welcome back")
    }

    /// A sign-in is remembered for its own control plane only: another one reached is Enroll.
    @Test func aSignInIsRememberedForItsControlPlaneOnly() throws {
        let memory = SignInMemory(try freshDefaults())
        memory.signedIn(RememberedSignIn(handle: "mara", name: "Mara"), at: try ControlPlane(address: example))
        memory.reached(try ControlPlane(address: "https://other.example.com"))
        #expect(memory.welcome.screen == .ticket)
        #expect(memory.signIn(at: try ControlPlane(address: example)) == RememberedSignIn(handle: "mara", name: "Mara"))
    }

    /// What the defaults hold is an address and a handle and a name: no token, nothing of the vault.
    @Test func theDefaultsHoldNoToken() throws {
        let defaults = try freshDefaults()
        SignInMemory(defaults).signedIn(RememberedSignIn(handle: "mara", name: "Mara Quill"), at: try ControlPlane(address: example))
        let held = defaults.dictionaryRepresentation().filter { $0.key.hasPrefix("controlPlane.") || $0.key.hasPrefix("signIn") }
        let words = held.map { "\($0.key)=\(String(decoding: ($0.value as? Data) ?? Data("\($0.value)".utf8), as: UTF8.self))" }.sorted()
        #expect(words.count == 2 && words.allSatisfy { !$0.contains("sess_") && !$0.contains("token") }, "\(words)")
    }
}

/// Every sentence of the three screens and their states, as the page draws them.
@Suite struct WelcomeWordsTests {
    let plane = try! ControlPlane(address: example)

    @Test func theWelcomeAsksForTheAddressAlone() {
        let welcome = Welcome(plane: nil, person: nil)
        #expect(welcome.showsMark && welcome.title == "Welcome to Sail")
        #expect(welcome.lede == "Run Claude Code, Codex and your shells in workspaces that keep going when you close the lid. Start with your team's control plane.")
        #expect(welcome.field == Welcome.Field(label: "Control plane", placeholder: "sail.example.com", help: "The address your admin gave you. Sail adds https:// when you leave it out.", secure: false))
        #expect(welcome.primary == "Continue" && welcome.busyWord == nil && welcome.note == nil)
        #expect(welcome.other == Welcome.Other(before: "No control plane yet?", link: "Open Terminals", after: "for shells on this Mac."))
    }

    @Test func connectingIsContinueBusy() {
        var welcome = Welcome(plane: nil, person: nil)
        welcome.began(.connecting)
        #expect(welcome.busyWord == "Connecting" && welcome.note == nil && welcome.refusal == nil)
    }

    @Test func enrollAsksForTheTicketUnderTheControlPlane() {
        let welcome = Welcome(plane: plane, person: nil)
        #expect(welcome.screen == .ticket && welcome.showsMark && welcome.title == "Enroll this Mac" && welcome.host == "sail.example.com")
        #expect(welcome.lede == "Your admin gives you a one-time ticket. Paste it here and Sail creates your passkey, kept in iCloud Keychain, then signs you in.")
        #expect(welcome.field == Welcome.Field(label: "Enrollment ticket", placeholder: "Paste the ticket or its link", help: "A ticket lasts 15 minutes and enrolls one passkey.", secure: true))
        #expect(welcome.primary == "Create Passkey" && welcome.other == Welcome.Other(before: "Already enrolled?", link: "Sign In with Passkey", after: nil))
    }

    @Test func creatingThenSigningInSayEachSheet() {
        var welcome = Welcome(plane: plane, person: nil)
        welcome.began(.creating)
        #expect(welcome.busyWord == "Creating Passkey" && welcome.note == Welcome.Note(created: false, words: "Confirm with Touch ID to create your passkey."))
        welcome.began(.signingAfterEnrolling(handle: "mara"))
        #expect(welcome.busyWord == "Signing In" && welcome.note == Welcome.Note(created: true, words: "Passkey created for @mara. Confirm once more to sign in."))
    }

    @Test func welcomeBackHasOnePrimaryAndNoMark() {
        let welcome = Welcome(plane: plane, person: RememberedSignIn(handle: "mara", name: "Mara Quill"))
        #expect(welcome.screen == .back && !welcome.showsMark && welcome.title == "Welcome back, Mara" && welcome.field == nil)
        #expect(welcome.lede == "Sign in to sail.example.com with your passkey." && welcome.primary == "Sign In with Passkey")
        #expect(welcome.other == Welcome.Other(before: "New on this Mac?", link: "Enroll with a Ticket", after: nil))
        var signing = welcome
        signing.began(.signingIn)
        #expect(signing.busyWord == "Signing In" && signing.note == Welcome.Note(created: false, words: "Confirm with Touch ID."))
    }

    @Test func eachScreenLeadsToTheOtherSoAWrongGuessCostsOneClick() {
        var welcome = Welcome(plane: plane, person: RememberedSignIn(handle: "mara", name: "Mara"))
        welcome.enrollInstead()
        #expect(welcome.screen == .ticket)
        welcome.signInInstead()
        #expect(welcome.screen == .back)
        welcome.change()
        #expect(welcome.screen == .address && welcome.plane == plane && welcome.typed == "sail.example.com")
        let plain = Welcome(plane: try? ControlPlane(address: "http://127.0.0.1:7979"), person: nil)
        #expect(plain.typed == "http://127.0.0.1:7979" && plain.host == "127.0.0.1:7979")
    }

    /// Change puts the control plane back as a person types it, and what it puts back reaches the
    /// same control plane: an IPv6 literal keeps its brackets over https as over http.
    @Test(arguments: [
        "sail.example.com", "sail.example.com:8443", "https://[fd00::1]:7443", "https://[fd00::1]", "http://[::1]:7979", "http://[fd12::7]:7443",
        "http://10.0.0.2:7443", "127.0.0.1:7979",
    ])
    func whatChangePutsBackReachesTheSameControlPlane(_ address: String) throws {
        let plane = try ControlPlane(address: address)
        var welcome = Welcome(plane: plane, person: nil)
        welcome.change()
        #expect(try ControlPlane(address: welcome.typed) == plane, "\(address) came back as \(welcome.typed)")
    }

    @Test func aRefusalStandsUnderItsFieldAsItsSentenceAndRemedy() {
        var welcome = Welcome(plane: plane, person: nil)
        welcome.began(.creating)
        welcome.refused(AccountRefusal(.ticketRefused, at: plane))
        #expect(welcome.stage == nil && welcome.said == ["This ticket is used, expired or not from sail.example.com.", "A ticket lasts 15 minutes and enrolls one passkey. Ask your admin for a new one."])
    }

    /// A sign-in after enrolling that does not finish leaves Welcome back: the passkey is made, so a
    /// cancel there says so plainly and points at no new ticket; a refusal is said as it is.
    @Test func aSignInAfterEnrollingThatFailsIsWelcomeBack() {
        var welcome = Welcome(plane: plane, person: nil)
        welcome.began(.signingAfterEnrolling(handle: "mara"))
        welcome.refused(AccountRefusal(.cancelled(signingIn: true), at: plane))
        #expect(welcome.screen == .back && welcome.title == "Welcome back" && welcome.stage == nil)
        #expect(welcome.cancelled == "Passkey created for @mara. Sign in with it." && welcome.said.isEmpty && welcome.refusal == nil)
        welcome.began(.signingIn)
        #expect(welcome.cancelled == nil)
        var refused = Welcome(plane: plane, person: nil)
        refused.began(.signingAfterEnrolling(handle: "mara"))
        refused.refused(AccountRefusal(.notAccepted, at: plane))
        #expect(refused.screen == .back && refused.cancelled == nil && refused.said.first == "https://sail.example.com did not accept that passkey.")
    }

    /// Touch ID cancelled as the passkey is made leaves Enroll as it was, its primary free: nothing
    /// changed, said plainly, no refusal. Cancelled on Welcome back, it is the page's refusal.
    @Test func aCancelledFirstTouchIDChangesNothing() {
        var welcome = Welcome(plane: plane, person: nil)
        welcome.began(.creating)
        welcome.refused(AccountRefusal(.cancelled(signingIn: false), at: plane))
        #expect(welcome.screen == .ticket && welcome.stage == nil && welcome.busyWord == nil && welcome.refusal == nil && welcome.said.isEmpty)
        #expect(welcome.cancelled == "Nothing changed. Paste the ticket again to create your passkey.")
        welcome.signInInstead()
        #expect(welcome.cancelled == nil)
        welcome.began(.signingIn)
        welcome.refused(AccountRefusal(.cancelled(signingIn: true), at: plane))
        #expect(welcome.cancelled == nil && welcome.said == ["Cancelled.", "If this Mac holds no passkey for sail.example.com, enroll with a ticket from your admin."])
    }

    @Test func aSessionThatEndedIsABannerOnWelcomeBack() throws {
        var welcome = Welcome(plane: plane, person: nil)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let day = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 25, hour: 12)))
        welcome.ended(on: day)
        #expect(welcome.screen == .back && welcome.banner == "Your sign-in ended on 25 November. Your sessions on the runner kept running.")
        welcome.began(.signingIn)
        #expect(welcome.banner == nil)
    }

    @Test func theVersionLineIsTheAppsOwn() {
        #expect(Welcome.version("0.76.0") == "Sail 0.76.0" && Welcome.version(nil) == "Sail")
    }
}

/// No line a person reads on these screens tells them to run a command.
@Suite struct WordsOnlyTests {
    /// `sail` and a verb, `sudo`, `systemctl` or a route.
    static func namesACommand(_ line: String) -> Bool {
        line.range(of: #"(^|[^A-Za-z.])sail [a-z]|sudo|systemctl|/v1/"#, options: .regularExpression) != nil
    }

    /// Every reason, by name: a reason added and not listed here does not compile.
    static func listed(_ reason: AccountRefusal.Reason) -> AccountRefusal.Reason {
        switch reason {
        case .notAnAddress, .notATicket, .addressNotAllowed, .unreachable, .passkeysNotConfigured, .foreignRelyingParty, .ticketRefused, .originNotAllowed,
             .notAccepted, .alreadyHeld, .associationNotConfirmed, .cannotCreate, .cancelled, .keychain, .ended, .unreadableSignIn, .redirected, .refused,
             .unreadable, .authenticatorFailed: reason
        }
    }

    static let reasons: [AccountRefusal.Reason] = [
        .notAnAddress, .notATicket, .addressNotAllowed("sail.example.com"), .unreachable, .passkeysNotConfigured, .foreignRelyingParty("example.com"),
        .ticketRefused, .originNotAllowed("https://elsewhere.example"), .notAccepted, .alreadyHeld, .associationNotConfirmed, .cannotCreate,
        .cancelled(signingIn: false), .cancelled(signingIn: true), .keychain("read", "User interaction is not allowed."), .ended, .unreadableSignIn,
        .redirected, .refused, .unreadable, .authenticatorFailed,
    ].map(listed)

    @Test func noReasonsLineNamesACommand() throws {
        let plane = try ControlPlane(address: example)
        for reason in Self.reasons {
            let lines = AccountRefusal(reason, at: plane).lines
            #expect(!lines.isEmpty && lines.allSatisfy { !Self.namesACommand($0) }, "\(reason): \(lines)")
        }
    }

    /// The server's own refusals that carry a command as their remedy keep their sentence and lose
    /// the command, whichever call they answer.
    @Test(arguments: ["refusal-passkey-not-configured", "refusal-project-not-created", "refusal-forbidden", "refusal-invalid-request"])
    func aServersCommandIsLeftOut(_ golden: String) async throws {
        let refused = try Exchange(golden)
        for step in [AccountRefusal.Step.enrolling, .signingIn, .asking, .reaching] {
            let error: SailError
            do {
                _ = try Endpoint.whoami.decode(HTTPResponse(status: refused.status, headers: refused.headers, body: refused.body))
                Issue.record("\(golden) decoded")
                return
            } catch let failed {
                error = failed
            }
            let lines = AccountRefusal(error, at: try ControlPlane(address: example), step: step).lines
            #expect(lines.allSatisfy { !Self.namesACommand($0) && !$0.contains("'server start'") && !$0.contains("project apply") }, "\(golden) at \(step): \(lines)")
        }
    }

    @Test func thePageReplacesTheRemediesThatNamedCommands() throws {
        let plane = try ControlPlane(address: example)
        #expect(AccountRefusal(.notATicket).remedy == "Paste the ticket or the link your admin sent.")
        #expect(AccountRefusal(.ticketRefused, at: plane).remedy == "A ticket lasts 15 minutes and enrolls one passkey. Ask your admin for a new one.")
        for reason in [AccountRefusal.Reason.passkeysNotConfigured, .foreignRelyingParty("example.com"), .originNotAllowed("https://elsewhere.example")] {
            #expect(AccountRefusal(reason, at: plane).remedy == "Ask your admin to set up passkeys for Sail on this control plane.", "\(reason)")
        }
        #expect(AccountRefusal(.cancelled(signingIn: true), at: plane).remedy == "If this Mac holds no passkey for sail.example.com, enroll with a ticket from your admin.")
    }
}
