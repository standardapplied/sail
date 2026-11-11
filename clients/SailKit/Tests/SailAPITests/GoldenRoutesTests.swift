import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The instant the server's golden writer pins its clock to.
let pinned = Date(timeIntervalSince1970: 1_794_387_600)

/// The call this client makes for each recorded exchange, by the golden's name.
let calls: [String: @Sendable (SailClient) async throws -> Void] = [
    "whoami": { _ = try await $0.send(.whoami) },
    "projects": { _ = try await $0.send(.projects) },
    "rooms": { _ = try await $0.send(.rooms(project: "harbor")) },
    "room": { _ = try await $0.send(.room("harbor-design")) },
    "messages": { _ = try await $0.send(.messages(room: "harbor-design", limit: 50)) },
    "message-posted": { _ = try await $0.send(.post(question, room: "harbor-design")) },
    "message-reply": { _ = try await $0.send(.post(reply, room: "harbor-design")) },
    "specs": { _ = try await $0.send(.specs(SpecFilter(project: "harbor"))) },
    "spec": { _ = try await $0.send(.spec("harbor-docks")) },
    "refusal-read-only-credential": { _ = try await $0.send(.post(MessagePost(body: "hello"), room: "harbor-design")) },
    "refusal-room-not-found": { _ = try await $0.send(.room("nowhere")) },
    "refusal-spec-not-found": { _ = try await $0.send(.spec("nothing")) },
    "refusal-missing-bearer-token": { _ = try await $0.send(.whoami) },
    "refusal-invalid-bearer-token": { _ = try await $0.send(.whoami) },
    "refusal-bad-request": { _ = try await $0.send(.post(MessagePost(body: ""), room: "harbor-design")) },
    "refusal-not-found": { _ = try await $0.send(Endpoint<Whoami>("GET", ["v1", "nowhere"])) },
]

let question = MessagePost(body: "Which berth takes the ferry — north or south? ⚓", asksQuestion: true)
let reply = MessagePost(body: "South: it has the deeper draft. 🚢", replyTo: "00000000-0000-7000-8000-000000000001")

/// What `call` answers when the server is the one that recorded `name`, and the request it sent.
func answer<Response>(
    _ name: String, _ call: (SailClient) async throws -> Response
) async throws -> (Result<Response, SailError>, HTTPRequest?) {
    let server = RecordedServer([try Exchange(name)])
    let client = SailClient(transport: server, token: "tok")
    do {
        let response = try await call(client)
        return (.success(response), await server.requests.first)
    } catch let error as SailError {
        return (.failure(error), await server.requests.first)
    }
}

@Suite struct GoldenRoutesTests {
    @Test func everyRecordedExchangeIsOneThisClientMakes() throws {
        #expect(Set(calls.keys).union(refusalsOfRoutesNotYetCalled) == Set(try Goldens.names(in: "http")))
    }

    @Test(arguments: calls.keys.sorted())
    func theClientSendsTheRecordedRequestAndReadsItsAnswer(name: String) async throws {
        let exchange = try Exchange(name)
        let (result, sent) = try await answer(name) { client in try await calls[name]?(client) }
        let request = try #require(sent)
        #expect(request.method == exchange.method)
        #expect(request.path == exchange.path)
        #expect(request.headers["Authorization"] == "Bearer tok")
        #expect(try json(request.body) == json(exchange.requestBody))
        switch result {
        case .success:
            #expect((200 ..< 300).contains(exchange.status))
        case let .failure(.refused(refusal)):
            #expect(exchange.status >= 400)
            #expect("refusal-" + refusal.code.rawValue.replacingOccurrences(of: "_", with: "-") == name)
        case let .failure(error):
            Issue.record("\(name): \(error)")
        }
    }

    @Test func whoamiReadsTheCallersHandleRoleAndCapabilities() async throws {
        let whoami = try Endpoint.whoami.decode(response(try Exchange("whoami")))
        #expect(whoami.fde == "uday")
        #expect(whoami.name == "uday")
        #expect(whoami.displayName == "Uday")
        #expect(whoami.email == "uday@example.com")
        #expect(whoami.role == .known(.admin))
        #expect(whoami.capabilities == [.known(.read), .known(.write), .known(.admin)])
    }

    @Test func projectsReadEachProjectAndItsContainer() throws {
        let list = try Endpoint.projects.decode(response(try Exchange("projects")))
        #expect(list.total == 1)
        #expect(list.projects.map(\.name) == ["harbor"])
        #expect(list.projects.map(\.containerStatus) == ["not_created"])
    }

    @Test func aRoomReadsEveryFieldTheServerSends() throws {
        let room = try Endpoint.room("harbor-design").decode(response(try Exchange("room")))
        #expect(room == (try Endpoint.rooms().decode(response(try Exchange("rooms")))).rooms.first)
        #expect(room.id == "harbor-design")
        #expect(room.project == "harbor")
        #expect(room.title == "Docks")
        #expect(room.assignee == "uday")
        #expect(room.wake == "mention")
        #expect(room.effectiveWake == "mention")
        #expect(room.members.count == 1)
        expectSeated(room.members.first)
        #expect(room.specIds == ["harbor-docks"])
        #expect(room.createdBy == "uday")
        #expect(room.createdAt == pinned)
        #expect(room.updatedAt == pinned)
        #expect(room.updatedBy == "sail")
        #expect(room.lastActivityAt == pinned)
        #expect(room.needsReply)
        #expect(room.questionMessageId == "00000000-0000-7000-8000-000000000003")
    }

    @Test func messagesReadEveryFieldWithAReplyAndAQuestion() throws {
        let list = try Endpoint.messages(room: "harbor-design").decode(response(try Exchange("messages")))
        #expect(list.specId == "harbor-design")
        #expect(list.total == 3)
        let (asked, answered, agent) = (list.messages[0], list.messages[1], list.messages[2])
        #expect(asked.id == "00000000-0000-7000-8000-000000000001")
        #expect(asked.specId == "harbor-design")
        #expect(asked.author == "uday")
        #expect(asked.body == question.body)
        #expect(asked.replyTo == nil)
        #expect(asked.createdAt == pinned)
        #expect(asked.isQuestion)
        #expect(!asked.isError)
        #expect(answered.body == reply.body)
        #expect(answered.replyTo == asked.id)
        #expect(!answered.isQuestion)
        #expect(agent.author == "claude/run-1")
        #expect(agent.isQuestion)
        let posted = try Endpoint.post(question, room: "harbor-design").decode(response(try Exchange("message-posted")))
        #expect(posted.message == asked)
        let replied = try Endpoint.post(reply, room: "harbor-design").decode(response(try Exchange("message-reply")))
        #expect(replied.message == answered)
    }

    @Test func aSpecReadsEveryFieldTheServerSends() throws {
        let detail = try Endpoint.spec("harbor-docks").decode(response(try Exchange("spec")))
        let listed = try #require(try Endpoint.specs().decode(response(try Exchange("specs"))).specs.first)
        #expect(detail.body == "The south berth takes the ferry.")
        #expect(detail.plan == "")
        let spec = detail.spec
        #expect(spec.id == "harbor-docks")
        #expect(spec.project == "harbor")
        #expect(spec.title == "Docks take the ferry")
        #expect(spec.status == .known(.pending))
        #expect(spec.assignee == "uday")
        #expect(spec.agent == "claude-code")
        #expect(spec.model == "opus")
        #expect(spec.reasoningEffort == "high")
        #expect(spec.branch == "agent/harbor-docks")
        #expect(spec.priority == 2)
        #expect(spec.dependsOn == ["harbor-berths"])
        #expect(spec.repos == ["harbor-api"])
        #expect(spec.wake == "mention")
        expectSeated(spec.engagement)
        #expect(spec.createdBy == "uday")
        #expect(spec.createdAt == pinned)
        #expect(spec.updatedAt == pinned)
        #expect(spec.updatedBy == "uday")
        #expect(spec.roomId == "harbor-design")
        #expect(spec.lastActivityAt == nil)
        #expect(listed.lastActivityAt == pinned)
        #expect(spec.needsReply)
        #expect(spec.questionMessageId == "00000000-0000-7000-8000-000000000003")
    }

    @Test func aFieldTheClientDoesNotKnowIsIgnored() throws {
        let exchange = try Exchange("room")
        let grown = try exchange.altered(at: ["ferry_schedule"], to: ["hourly", "nightly"])
        let member = try exchange.altered(at: ["members", 0, "berth"], to: 4)
        let room = try Endpoint.room("harbor-design").decode(response(exchange))
        #expect(try Endpoint.room("harbor-design").decode(response(grown)) == room)
        #expect(try Endpoint.room("harbor-design").decode(response(member)) == room)
    }

    @Test func aWordAClientDoesNotKnowIsKeptAsUnknown() throws {
        let room = try Exchange("room").altered(at: ["members", 0, "mode"], to: "pilot")
        #expect(try Endpoint.room("harbor-design").decode(response(room)).members.first?.mode == .unknown("pilot"))
        let spec = try Exchange("spec").altered(at: ["spec", "status"], to: "drydock")
        #expect(try Endpoint.spec("harbor-docks").decode(response(spec)).spec.status == .unknown("drydock"))
        let whoami = try Exchange("whoami").altered(at: ["capabilities", 1], to: "steer")
        #expect(try Endpoint.whoami.decode(response(whoami)).capabilities[1] == .unknown("steer"))
        let role = try Exchange("whoami").altered(at: ["role"], to: "harbormaster")
        #expect(try Endpoint.whoami.decode(response(role)).role == .unknown("harbormaster"))
    }

    @Test func aMissingRequiredFieldNamesTheRouteAndTheField() throws {
        let rooms = try Exchange("rooms").altered(at: ["rooms", 0, "effective_wake"], to: nil)
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/rooms?project=harbor", field: "rooms[0].effective_wake", reason: "it is missing"
        ))) { try Endpoint.rooms(project: "harbor").decode(response(rooms)) }
        let spec = try Exchange("spec").altered(at: ["spec", "engagement", "engaged_at"], to: nil)
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/specs/harbor-docks", field: "spec.engagement.engaged_at", reason: "it is missing"
        ))) { try Endpoint.spec("harbor-docks").decode(response(spec)) }
    }

    @Test func aFieldOfTheWrongShapeNamesTheRouteAndTheField() throws {
        let messages = try Exchange("messages").altered(at: ["messages", 1, "created_at"], to: "yesterday")
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/rooms/harbor-design/messages", field: "messages[1].created_at",
            reason: "'yesterday' is not an ISO-8601 instant"
        ))) { try Endpoint.messages(room: "harbor-design").decode(response(messages)) }
        let whoami = try Exchange("whoami").altered(at: ["capabilities"], to: "all")
        expectUndecodable(route: "GET /v1/whoami", field: "capabilities") {
            try Endpoint.whoami.decode(response(whoami))
        }
        let page = try Exchange("messages").altered(at: ["total"], to: NSNull())
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/rooms/harbor-design/messages", field: "total", reason: "it is null"
        ))) { try Endpoint.messages(room: "harbor-design").decode(response(page)) }
    }

    @Test func anAnswerThatIsNotJSONIsUndecodableAsAWhole() {
        expectUndecodable(route: "GET /v1/whoami", field: "(body)") {
            try Endpoint.whoami.decode(HTTPResponse(status: 200, body: Data("<html>".utf8)))
        }
    }

    @Test func aRefusalKeepsItsCodeMessageAndFix() async throws {
        let (result, _) = try await answer("refusal-read-only-credential") { client in
            try await client.send(.post(MessagePost(body: "hello"), room: "harbor-design"))
        }
        #expect(throws: SailError.refused(Refusal(
            code: .known(.readOnlyCredential),
            message: "Your credential is read-only and cannot post in rooms.",
            action: "Ask an admin for a member or admin credential.",
            remedy: []
        ))) { try result.get() }
    }

    @Test func aRefusalWhoseFixNamesACommandKeepsItAsWordsAndAsTheCommand() throws {
        #expect(throws: SailError.refused(Refusal(
            code: .known(.projectNotCreated),
            message: "Project 'harbor' has no container on this box.",
            action: "Create it: project apply harbor",
            remedy: [.words("Create it: "), .command(run: .known(.sail), verb: "project apply", args: ["harbor"])]
        ))) { try Endpoint.whoami.decode(response(try Exchange("refusal-project-not-created"))) }
        #expect(throws: SailError.refused(Refusal(
            code: .known(.notFound),
            message: "Project 'harbor' was not found.",
            action: "Ask an admin on main for the project: fde grant <handle> <project>.",
            remedy: [
                .words("Ask an admin on main for the project: "),
                .command(run: .known(.sail), verb: "fde grant", args: ["<handle>", "<project>"]),
                .words("."),
            ]
        ))) { try Endpoint.whoami.decode(response(try Exchange("refusal-out-of-scope"))) }
    }

    @Test func aRefusalWithAnUnknownCodeOrRunnerKeepsTheRawWord() throws {
        let refused = try Exchange("refusal-project-not-created")
            .altered(at: ["error", "code"], to: "berth_taken")
            .altered(at: ["error", "remedy", 1, "run"], to: "harbormaster")
        #expect(throws: SailError.refused(Refusal(
            code: .unknown("berth_taken"),
            message: "Project 'harbor' has no container on this box.",
            action: "Create it: project apply harbor",
            remedy: [.words("Create it: "), .command(run: .unknown("harbormaster"), verb: "project apply", args: ["harbor"])]
        ))) { try Endpoint.whoami.decode(response(refused)) }
    }

    @Test func aTransportThatCannotReachTheServerSaysSo() async throws {
        let (result, _) = try await answer("whoami") { client in try await client.send(.room("harbor-design")) }
        #expect(throws: SailError.unreachable("no golden answers GET /v1/rooms/harbor-design")) { try result.get() }
    }

    @Test func aFailedAnswerWithNoRefusalKeepsItsStatus() throws {
        #expect(throws: SailError.unexpected(route: "GET /v1/whoami", status: 503)) {
            try Endpoint.whoami.decode(HTTPResponse(status: 503, body: Data()))
        }
    }
}

/// Refusals the server writes for routes this client does not call yet; each still decodes.
let refusalsOfRoutesNotYetCalled: Set<String> = ["refusal-out-of-scope", "refusal-project-not-created"]

/// The member the golden writer seats in the room.
func expectSeated(_ member: RoomMember?, sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(member?.agent == "claude-code", sourceLocation: sourceLocation)
    #expect(member?.agentName == "Claude Code", sourceLocation: sourceLocation)
    #expect(member?.mode == .known(.readOnly), sourceLocation: sourceLocation)
    #expect(member?.model == "opus", sourceLocation: sourceLocation)
    #expect(member?.engagedAt == pinned, sourceLocation: sourceLocation)
}

/// That `decode` fails naming `route` and `field`, in whatever words the platform's decoder gives.
func expectUndecodable(
    route: String, field: String, sourceLocation: SourceLocation = #_sourceLocation,
    _ decode: () throws -> some Any
) {
    do {
        let decoded = try decode()
        Issue.record("decoded \(decoded)", sourceLocation: sourceLocation)
    } catch let SailError.undecodable(undecodable) {
        #expect(undecodable.route == route, sourceLocation: sourceLocation)
        #expect(undecodable.field == field, sourceLocation: sourceLocation)
    } catch {
        Issue.record("\(error)", sourceLocation: sourceLocation)
    }
}

func response(_ exchange: Exchange) -> HTTPResponse {
    HTTPResponse(status: exchange.status, body: exchange.body)
}

func json(_ data: Data?) throws -> NSDictionary? {
    try data.map { try JSONSerialization.jsonObject(with: $0) as? NSDictionary } ?? nil
}
