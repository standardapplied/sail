import Foundation
import SailAPI
import SailKitTestSupport
import Synchronization
import Testing

/// Every call the app makes, by where a person makes it.
let callSites: [(String, @Sendable (SailClient) async throws -> Void)] = [
    ("the projects listing", { _ = try await $0.send(.projects) }),
    ("a project's workspaces listing", { _ = try await $0.send(.workspaces(project: "harbor")) }),
    ("a workspace's sessions", { _ = try await $0.send(.sessions(workspace: "dock", project: "harbor")) }),
    ("Start", { _ = try await $0.send(.startWorkspace("dock", project: "harbor")) }),
    ("Stop", { _ = try await $0.send(.stopWorkspace("dock", project: "harbor")) }),
    ("Delete", { _ = try await $0.send(.deleteWorkspace("dock", project: "harbor")) }),
    ("Create", { _ = try await $0.send(.createWorkspace("quay", project: "harbor")) }),
    ("the New session popover's agents", { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) }),
    ("Store a Key's keys", { _ = try await $0.send(.logins) }),
    ("Store a Key", { _ = try await $0.send(.storeLogin("sk-test", kind: "api_key", harness: "claude-code", name: "work")) }),
    ("a key made the one in use", { _ = try await $0.send(.useLogin(harness: "claude-code", name: "work")) }),
    ("a key removed", { _ = try await $0.send(.removeLogin(harness: "claude-code", name: "work")) }),
    ("the harnesses", { _ = try await $0.send(.knownAgents) }),
    ("the passkeys", { _ = try await $0.send(.passkeys) }),
    ("a passkey removed", { _ = try await $0.send(.removePasskey("pk-1")) }),
    ("Create Project", { _ = try await $0.create(marina, by: "uday").get() }),
    ("the template's build", { _ = try await $0.send(.buildTemplate(project: "lighthouse")) }),
    ("the template's read", { _ = try await $0.send(.template(project: "lighthouse")) }),
    ("a terminal's ticket", { _ = try await $0.send(.terminalTicket(open: "harbor", workspace: "dock")) }),
    ("a terminal's ticket to attach", { _ = try await $0.send(.terminalTicket(attach: "dock-a")) }),
    ("the files door's ticket", { _ = try await $0.send(.terminalTicket(files: "harbor", workspace: "dock")) }),
]

/// A control plane that answers every request as `golden` was answered, whatever its route.
actor AnsweringAll: Transport {
    private let answer: HTTPResponse
    private(set) var asked = 0

    init(_ golden: String) throws {
        let exchange = try Exchange(golden)
        answer = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
    }

    init(code: String) throws {
        let exchange = try Exchange("refusal-not-found").altered(at: ["error", "code"], to: code)
        answer = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
    }

    func send(_: HTTPRequest) throws(SailError) -> HTTPResponse {
        asked += 1
        return answer
    }

    func open(_ request: HTTPRequest) throws(SailError) -> HTTPStream {
        throw .unreachable("no stream \(request.route)")
    }
}

/// The server says a session has ended in one place a call passes through, the client: every
/// call's answer is then the same ended sign-in, in Sail's words, and the client tells whoever made
/// it, once a call.
@Suite(.timeLimit(.minutes(1))) struct SessionEndedTests {
    static let ends = ["refusal-missing-bearer-token", "refusal-invalid-bearer-token"]

    /// What `call` ends in, and how many times the client said the session ended.
    static func ending(_ call: @Sendable (SailClient) async throws -> Void, on server: some Transport) async -> (SailError?, Int) {
        let told = Mutex(0)
        let client = SailClient(transport: server, token: "tok") { told.withLock { $0 += 1 } }
        do {
            try await call(client)
            return (nil, told.withLock { $0 })
        } catch let error as SailError {
            return (error, told.withLock { $0 })
        } catch {
            return (.unsendable("not a call's failure: \(error)"), told.withLock { $0 })
        }
    }

    @Test(arguments: callSites.map(\.0), ends)
    func everyCallFindingTheSessionEndedSaysItInSailsWordsAndTellsOnce(_ site: String, _ golden: String) async throws {
        let call = try #require(callSites.first { $0.0 == site }?.1)
        let server = try AnsweringAll(golden)
        let (error, told) = await Self.ending(call, on: server)
        #expect(error == .signIn(.ended), "\(site): \(String(describing: error))")
        let asked = await server.asked
        #expect(told == 1 && asked == 1, "\(site): told \(told), asked \(asked)")
        let said = [error?.description, error?.said(at: "https://sail.standardapplied.com")].compactMap { $0 }
        #expect(said == ["Your sign-in ended.", "Your sign-in ended."], "\(site): \(said)")
    }

    @Test func anEndedSignInIsSaidInTheWelcomesWords() {
        #expect(SignIn.ended.lines == ["Your sign-in ended.", "Your sessions on the runner kept running.", "Sign in again with your passkey."])
        #expect(SailError.signIn(.ended).route == nil)
    }

    /// Every other code the server's refusals carry, and one it may grow, is the server's own refusal,
    /// in its words, and tells nothing.
    @Test(arguments: ErrorCode.allCases.filter { ![.invalidBearerToken, .missingBearerToken].contains($0) }.map(\.rawValue) + ["harbor_closed"])
    func noOtherRefusalEndsTheSession(_ code: String) async throws {
        let (error, told) = await Self.ending({ _ = try await $0.send(.projects) }, on: try AnsweringAll(code: code))
        guard case let .refused(refusal)? = error else {
            Issue.record("\(code): \(String(describing: error))")
            return
        }
        #expect(refusal.code.rawValue == code && told == 0)
    }

    /// A call the server did not answer is unchanged, and tells nothing.
    @Test func noAnswerEndsNoSession() async {
        let (error, told) = await Self.ending({ _ = try await $0.send(.projects) }, on: RefusingTransport())
        #expect(error == .unreachable("GET /v1/projects") && told == 0)
    }

    /// The check of who is signed in reads the ended sign-in as the welcome says it.
    @Test func theCheckReadsAnEndedSignInAsEnded() throws {
        let refusal = AccountRefusal(.signIn(.ended), at: try ControlPlane(address: "sail.standardapplied.com"), step: .asking)
        #expect(refusal.reason == .ended && refusal.lines == ["Your sign-in to https://sail.standardapplied.com has ended.", "Sign in again with your passkey."])
    }

    /// A list that could not be had for an ended sign-in offers no Try Again, which cannot mend it
    /// (the welcome comes by itself); for any other failure it does.
    @Test func anEndedSignInsBannerOffersNoTryAgain() throws {
        for (error, again) in [(SailError.signIn(.ended), false), (.unreachable("lost"), true)] {
            var model = WorkspacesModelTests.listed(WorkspacesModelTests.ana)
            model.projectsListed(.failure(error), request: model.askingProjects())
            let projects = try #require(WorkspacesModelTests.page(model)?.banner)
            model.listed(.failure(error), request: model.listAgain(), at: WorkspacesModelTests.at)
            let workspaces = try #require(WorkspacesModelTests.page(model)?.banner)
            let said = error.said(at: "main.acme.dev")
            #expect(projects == WorkspacesModel.Notice(tone: .error, title: "Couldn’t list your projects", line: said, tryAgain: again), "\(projects)")
            #expect(workspaces == WorkspacesModel.Notice(tone: .error, title: "Couldn’t list harbor’s workspaces", line: said, tryAgain: again), "\(workspaces)")
        }
    }
}
