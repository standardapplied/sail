import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The call this client makes for each recorded exchange, by the golden's name.
let calls: [String: @Sendable (SailClient) async throws -> Void] = [
    "whoami": { _ = try await $0.send(.whoami) },
    "projects": { _ = try await $0.send(.projects) },
    "rooms": { _ = try await $0.send(.rooms(project: "harbor")) },
    "room": { _ = try await $0.send(.room("harbor-design")) },
    "room-bare": { _ = try await $0.send(.room("harbor-quay")) },
    "messages": { _ = try await $0.send(.messages(room: "harbor-design", limit: 50)) },
    "message-posted": { _ = try await $0.send(.post(question, room: "harbor-design")) },
    "message-reply": { _ = try await $0.send(.post(reply, room: "harbor-design")) },
    "specs": { _ = try await $0.send(.specs(SpecFilter(project: "harbor"))) },
    "spec": { _ = try await $0.send(.spec("harbor-docks")) },
    "spec-bare": { _ = try await $0.send(.spec("harbor-buoys")) },
    "refusal-read-only-credential": { _ = try await $0.send(.post(MessagePost(body: "hello"), room: "harbor-design")) },
    "refusal-room-not-found": { _ = try await $0.send(.room("nowhere")) },
    "refusal-spec-not-found": { _ = try await $0.send(.spec("nothing")) },
    "refusal-missing-bearer-token": { _ = try await $0.send(.whoami) },
    "refusal-invalid-bearer-token": { _ = try await $0.send(.whoami) },
    "refusal-bad-request": { _ = try await $0.send(.post(MessagePost(body: ""), room: "harbor-design")) },
    "refusal-not-found": { _ = try await $0.send(Endpoint<Whoami>(.get, ["v1", "nowhere"])) },
    "terminal-ticket": { _ = try await $0.send(.terminalTicket(open: "harbor")) },
    "attention": { _ = try await $0.send(.attention(conversation: "harbor-docks")) },
    "changes": { _ = try await $0.send(.changes(since: "golden.0.0")) },
    "board-specs": { _ = try await $0.send(.specs(SpecFilter(project: "harbor"))) },
    "board-rooms": { _ = try await $0.send(.rooms(project: "harbor")) },
    "refusal-resync-required": { _ = try await $0.send(.changes(since: "999999999")) },
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
        #expect(Set(calls.keys).union(refusalsOfRoutesNotYetCalled).union(answersOfRoutesNotYetCalled)
            == Set(try Goldens.names(in: "http")))
    }

    @Test(arguments: calls.keys.sorted())
    func theClientSendsTheRecordedRequestAndReadsItsAnswer(name: String) async throws {
        let exchange = try Exchange(name)
        let (result, sent) = try await answer(name) { client in try await calls[name]?(client) }
        let request = try #require(sent)
        #expect(request.method.rawValue == exchange.method)
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

    @Test func aTerminalTicketReadsTheTicketWhereToPresentItAndWhenItLapses() throws {
        let ticket = try Endpoint.terminalTicket(open: "harbor").decode(response(try Exchange("terminal-ticket")))
        #expect(ticket.ticket == "tkt_harbor")
        #expect(ticket.address == "100.64.0.7:7072")
        #expect(ticket.expiresAt.milliseconds == Goldens.pinnedMilliseconds + 30_000)
    }

    @Test func aHeartbeatReadsTheConversationTheServersMinuteAndWhetherItWasNew() throws {
        let minute = try Endpoint<AttentionMinute>.attention(conversation: "harbor-docks")
            .decode(response(try Exchange("attention")))
        #expect(minute.conversation == "harbor-docks")
        #expect(minute.recorded)
        #expect(Int64(minute.minute.timeIntervalSince1970 * 1000) == Goldens.pinnedMilliseconds / 60_000 * 60_000)
    }

    @Test func projectsReadEachProjectAndItsContainer() throws {
        let list = try Endpoint.projects.decode(response(try Exchange("projects")))
        #expect(list.total == 1)
        #expect(list.projects.map(\.name) == ["harbor"])
        #expect(list.projects.map(\.containerStatus) == [.known(.notCreated)])
        #expect(list.projects.map(\.id) == ["harbor"])
    }

    @Test func aRoomReadsEveryFieldTheServerSends() throws {
        let room = try Endpoint.room("harbor-design").decode(response(try Exchange("room")))
        let listed = try Endpoint.rooms().decode(response(try Exchange("rooms")))
        #expect(listed.count == 3)
        #expect(listed.rooms.map(\.id) == ["harbor-buoys", "harbor-design", "harbor-quay"])
        #expect(listed.rooms[1] == room)
        #expect(room.id == "harbor-design")
        #expect(room.project == "harbor")
        #expect(room.title == "Docks")
        #expect(room.assignee == "uday")
        #expect(room.wake == .known(.mention))
        #expect(room.effectiveWake == .known(.mention))
        #expect(room.members.count == 1)
        expectSeated(room.members.first)
        #expect(room.specIDs == ["harbor-docks"])
        #expect(room.createdBy == "uday")
        #expect(room.createdAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(room.updatedAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(room.updatedBy == "sail")
        #expect(room.lastActivityAt?.milliseconds == Goldens.pinnedMilliseconds)
        #expect(room.needsReply)
        #expect(room.questionMessageID == "00000000-0000-7000-8000-000000000003")
    }

    @Test func aRoomWithNothingOptionalReadsAsNothingSeatedFiledOrAsked() throws {
        let room = try Endpoint.room("harbor-quay").decode(response(try Exchange("room-bare")))
        #expect(room.id == "harbor-quay")
        #expect(room.assignee == "uday")
        #expect(room.wake == nil)
        #expect(room.effectiveWake == .known(.on))
        #expect(room.members.isEmpty)
        #expect(room.specIDs.isEmpty)
        #expect(room.lastActivityAt == nil)
        #expect(!room.needsReply)
        #expect(room.questionMessageID == nil)
    }

    @Test func messagesReadEveryFieldWithAReplyAndAQuestion() throws {
        let list = try Endpoint.messages(room: "harbor-design").decode(response(try Exchange("messages")))
        #expect(list.specID == "harbor-design")
        #expect(list.total == 4)
        let (asked, answered, failed, agent) = (list.messages[0], list.messages[1], list.messages[2], list.messages[3])
        #expect(asked.id == "00000000-0000-7000-8000-000000000001")
        #expect(asked.specID == "harbor-design")
        #expect(asked.author == "uday")
        #expect(asked.body == question.body)
        #expect(asked.replyTo == nil)
        #expect(asked.createdAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(asked.isQuestion)
        #expect(!asked.isError)
        #expect(answered.body == reply.body)
        #expect(answered.replyTo == asked.id)
        #expect(!answered.isQuestion)
        #expect(failed.author == "claude/run-1")
        #expect(failed.body == "The harness exited before it answered.")
        #expect(failed.isError)
        #expect(!failed.isQuestion)
        #expect(agent.author == "claude/run-1")
        #expect(agent.isQuestion)
        #expect(!agent.isError)
        let posted = try Endpoint.post(question, room: "harbor-design").decode(response(try Exchange("message-posted")))
        #expect(posted.message == asked)
        let replied = try Endpoint.post(reply, room: "harbor-design").decode(response(try Exchange("message-reply")))
        #expect(replied.message == answered)
    }

    @Test func aSpecReadsEveryFieldTheServerSends() throws {
        let detail = try Endpoint.spec("harbor-docks").decode(response(try Exchange("spec")))
        let list = try Endpoint.specs().decode(response(try Exchange("specs")))
        #expect(list.total == 2)
        #expect(list.specs.map(\.id) == ["harbor-docks", "harbor-buoys"])
        let listed = try #require(list.specs.first)
        #expect(detail.body == "The south berth takes the ferry.")
        #expect(detail.plan == "")
        #expect(detail.openFindings == 1)
        let run = try #require(detail.latestRun)
        #expect(run.id == "run-1")
        #expect(run.node == "uday")
        #expect(run.status == .known(.running))
        #expect(run.exitCode == nil)
        #expect(run.principal == "claude/run-1")
        #expect(run.owner == "uday")
        #expect(run.sessionID == nil)
        #expect(run.sessionSource == nil)
        #expect(run.lastActivityAt?.milliseconds == Goldens.pinnedMilliseconds)
        #expect(run.presence == .known(.working))
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
        #expect(spec.wake == .known(.mention))
        expectSeated(spec.engagement)
        #expect(spec.createdBy == "uday")
        #expect(spec.createdAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(spec.updatedAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(spec.updatedBy == "uday")
        #expect(spec.roomID == "harbor-design")
        #expect(spec.lastActivityAt == nil)
        #expect(listed.lastActivityAt?.milliseconds == Goldens.pinnedMilliseconds)
        #expect(spec.needsReply)
        #expect(spec.questionMessageID == "00000000-0000-7000-8000-000000000003")
    }

    @Test func aSpecWithNothingOptionalReadsAsUnassignedUnfiledAndUnasked() throws {
        let detail = try Endpoint.spec("harbor-buoys").decode(response(try Exchange("spec-bare")))
        #expect(detail.body == "")
        #expect(detail.openFindings == 0)
        #expect(detail.latestRun == nil)
        let spec = detail.spec
        #expect(spec.id == "harbor-buoys")
        #expect(spec.status == .known(.pending))
        #expect(spec.assignee == nil)
        #expect(spec.agent == nil)
        #expect(spec.model == nil)
        #expect(spec.reasoningEffort == nil)
        #expect(spec.branch == nil)
        #expect(spec.priority == 0)
        #expect(spec.dependsOn.isEmpty)
        #expect(spec.repos.isEmpty)
        #expect(spec.wake == nil)
        #expect(spec.engagement == nil)
        #expect(spec.roomID == "harbor-buoys")
        #expect(spec.updatedAt.milliseconds == Goldens.pinnedMilliseconds + 1)
        #expect(spec.lastActivityAt == nil)
        #expect(!spec.needsReply)
        #expect(spec.questionMessageID == nil)
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

    @Test func anAnswerItsModelRefusesForItsOwnReasonIsUndecodableAsAWhole() {
        struct Unwilling: Decodable {
            init(from decoder: any Decoder) throws {
                throw CancellationError()
            }
        }
        #expect(throws: SailError.undecodable(Undecodable(route: "GET /v1/whoami", field: "(body)", reason: "CancellationError()"))) {
            try SailJSON.decode(Unwilling.self, from: Data("{}".utf8), route: "GET /v1/whoami")
        }
    }

    @Test func aRefusalThatNamesNoFieldErrorsKeepsItsWords() throws {
        let bare = try Exchange("refusal-room-not-found").altered(at: ["error", "field_errors"], to: nil)
        #expect(throws: SailError.refused(Refusal(code: .known(.roomNotFound), message: "Room 'nowhere' was not found."))) {
            try Endpoint.room("nowhere").decode(response(bare))
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

    @Test func aRefusalKeepsWhatItIsAboutFieldByField() throws {
        #expect(throws: SailError.refused(Refusal(
            code: .known(.runOnOtherNode),
            message: "Run run-2 executed on ada; its logs live there, not on this box.",
            fieldErrors: [
                FieldError(field: "node", message: "ada"), FieldError(field: "spec", message: "harbor-docks"),
                FieldError(field: "project", message: "harbor"),
            ]
        ))) { try Endpoint.whoami.decode(response(try Exchange("refusal-run-on-other-node"))) }
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
let refusalsOfRoutesNotYetCalled: Set<String> = [
    "refusal-out-of-scope", "refusal-project-not-created", "refusal-run-on-other-node",
]

/// The answers of routes this client does not call yet, recorded for the apps that will.
let answersOfRoutesNotYetCalled: Set<String> = ["spec-measures", "specs-measures", "workspaces"]

/// The member the golden writer seats in the room.
func expectSeated(_ member: RoomMember?, sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(member?.agent == "claude-code", sourceLocation: sourceLocation)
    #expect(member?.agentName == "Claude Code", sourceLocation: sourceLocation)
    #expect(member?.mode == .known(.readOnly), sourceLocation: sourceLocation)
    #expect(member?.model == "opus", sourceLocation: sourceLocation)
    #expect(member?.engagedAt.milliseconds == Goldens.pinnedMilliseconds, sourceLocation: sourceLocation)
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
    HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
}

func json(_ data: Data?) throws -> NSDictionary? {
    try data.map { try JSONSerialization.jsonObject(with: $0) as? NSDictionary } ?? nil
}
