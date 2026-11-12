import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The models as values an app builds and a cache stores: each is made from its fields, equal to
/// the one the server's answer decodes to, and written as JSON reads back as itself.
@Suite struct ModelTests {
    let at = Date(timeIntervalSince1970: TimeInterval(Goldens.pinnedMilliseconds) / 1000)

    @Test func aRoomIsBuiltFromItsFieldsAsTheServersAnswerReads() throws {
        let room = try Endpoint.room("harbor-design").decode(response(try Exchange("room")))
        let seated = try #require(room.members.first)
        #expect(seated == RoomMember(
            agent: "claude-code", agentName: "Claude Code", mode: .known(.readOnly), model: "opus",
            engagedAt: seated.engagedAt
        ))
        #expect(room == Room(
            id: "harbor-design", project: "harbor", title: "Docks", assignee: "uday", wake: .known(.mention),
            effectiveWake: .known(.mention), members: [seated], specIDs: ["harbor-docks"], createdBy: "uday",
            createdAt: room.createdAt, updatedAt: room.updatedAt, updatedBy: "sail",
            lastActivityAt: room.lastActivityAt, needsReply: true,
            questionMessageID: "00000000-0000-7000-8000-000000000003"
        ))
        let bare = try Endpoint.room("harbor-quay").decode(response(try Exchange("room-bare")))
        #expect(bare == Room(
            id: "harbor-quay", project: "harbor", title: "Quay", assignee: "uday", effectiveWake: .known(.on),
            createdBy: "uday", createdAt: bare.createdAt, updatedAt: bare.updatedAt, updatedBy: "uday"
        ))
        #expect(RoomList(rooms: [bare], count: 1).rooms == [bare])
        #expect(RoomList(rooms: [bare], count: 1).count == 1)
    }

    @Test func aSpecIsBuiltFromItsFieldsAsTheServersAnswerReads() throws {
        let detail = try Endpoint.spec("harbor-docks").decode(response(try Exchange("spec")))
        let spec = detail.spec
        let run = try #require(detail.latestRun)
        #expect(run == RunSummary(
            id: "run-1", node: "uday", status: .known(.running), principal: "claude/run-1", owner: "uday",
            lastActivityAt: run.lastActivityAt, presence: .known(.working)
        ))
        #expect(RunSummary(id: "run-9", node: "ada", status: .known(.failed), exitCode: 2, sessionID: "s-1", sessionSource: "startup")
            == (try roundTrip(RunSummary(
                id: "run-9", node: "ada", status: .known(.failed), exitCode: 2, sessionID: "s-1", sessionSource: "startup"
            ))))
        #expect(spec == Spec(
            id: "harbor-docks", project: "harbor", title: "Docks take the ferry", status: .known(.pending),
            assignee: "uday", agent: "claude-code", model: "opus", reasoningEffort: "high",
            branch: "agent/harbor-docks", priority: 2, dependsOn: ["harbor-berths"], repos: ["harbor-api"],
            wake: .known(.mention), engagement: spec.engagement, createdBy: "uday", createdAt: spec.createdAt,
            updatedAt: spec.updatedAt, updatedBy: "uday", roomID: "harbor-design", needsReply: true,
            questionMessageID: "00000000-0000-7000-8000-000000000003"
        ))
        #expect(detail == SpecDetail(
            spec: spec, body: "The south berth takes the ferry.", plan: "", openFindings: 1, latestRun: run
        ))
        let bare = try Endpoint.spec("harbor-buoys").decode(response(try Exchange("spec-bare")))
        #expect(bare == SpecDetail(
            spec: Spec(
                id: "harbor-buoys", project: "harbor", title: "Buoys mark the channel", status: .known(.pending),
                createdBy: "uday", createdAt: bare.spec.createdAt, updatedAt: bare.spec.updatedAt, updatedBy: "uday",
                roomID: "harbor-buoys"
            ),
            body: "", plan: ""
        ))
        let listed = try Endpoint.specs().decode(response(try Exchange("specs")))
        #expect(listed == SpecList(specs: listed.specs, total: 2))
        #expect(listed.specs.first?.lastActivityAt?.milliseconds == Goldens.pinnedMilliseconds)
    }

    @Test func aMessageIsBuiltFromItsFieldsAsTheServersAnswerReads() throws {
        let list = try Endpoint.messages(room: "harbor-design").decode(response(try Exchange("messages")))
        let (asked, answered, failed) = (list.messages[0], list.messages[1], list.messages[2])
        #expect(asked == Message(
            id: "00000000-0000-7000-8000-000000000001", specID: "harbor-design", author: "uday",
            body: "Which berth takes the ferry — north or south? ⚓", createdAt: asked.createdAt, isQuestion: true
        ))
        #expect(answered == Message(
            id: "00000000-0000-7000-8000-000000000002", specID: "harbor-design", author: "uday",
            body: "South: it has the deeper draft. 🚢", replyTo: asked.id, createdAt: answered.createdAt
        ))
        #expect(failed == Message(
            id: "00000000-0000-7000-8000-000000000004", specID: "harbor-design", author: "claude/run-1",
            body: "The harness exited before it answered.", createdAt: failed.createdAt, isError: true
        ))
        #expect(list == MessageList(specID: "harbor-design", messages: list.messages, total: 4))
        #expect(MessagePosted(message: asked).message == asked)
    }

    @Test func whoamiAndAProjectAreBuiltFromTheirFieldsAsTheServersAnswersRead() throws {
        #expect(try Endpoint.whoami.decode(response(try Exchange("whoami"))) == Whoami(
            fde: "uday", name: "uday", displayName: "Uday", email: "uday@example.com", role: .known(.admin),
            capabilities: [.known(.read), .known(.write), .known(.admin)]
        ))
        #expect(try Endpoint.projects.decode(response(try Exchange("projects"))) == ProjectList(
            projects: [ProjectSummary(name: "harbor", containerStatus: .known(.notCreated))], total: 1
        ))
    }

    @Test func everyModelWrittenAsJSONReadsBackAsItself() throws {
        let room = try Endpoint.room("harbor-design").decode(response(try Exchange("room")))
        #expect(try roundTrip(room) == room)
        let rooms = try Endpoint.rooms().decode(response(try Exchange("rooms")))
        #expect(try roundTrip(rooms) == rooms)
        let detail = try Endpoint.spec("harbor-docks").decode(response(try Exchange("spec")))
        #expect(try roundTrip(detail) == detail)
        let bare = try Endpoint.spec("harbor-buoys").decode(response(try Exchange("spec-bare")))
        #expect(try roundTrip(bare) == bare)
        let specs = try Endpoint.specs().decode(response(try Exchange("specs")))
        #expect(try roundTrip(specs) == specs)
        let messages = try Endpoint.messages(room: "harbor-design").decode(response(try Exchange("messages")))
        #expect(try roundTrip(messages) == messages)
        let posted = try Endpoint.post(question, room: "harbor-design").decode(response(try Exchange("message-posted")))
        #expect(try roundTrip(posted) == posted)
        let whoami = try Endpoint.whoami.decode(response(try Exchange("whoami")))
        #expect(try roundTrip(whoami) == whoami)
        let projects = try Endpoint.projects.decode(response(try Exchange("projects")))
        #expect(try roundTrip(projects) == projects)
    }

    @Test func aModelIsWrittenWithTheServersKeysAndItsInstantsToTheMillisecond() throws {
        let message = Message(
            id: "m-1", specID: "harbor-design", author: "uday", body: "Aye", replyTo: "m-0", createdAt: at,
            isQuestion: true
        )
        #expect(String(decoding: try SailJSON.encode(message), as: UTF8.self) == """
        {"author":"uday","body":"Aye","created_at":"2026-11-11T09:00:00.656Z","error":false,"id":"m-1",\
        "question":true,"reply_to":"m-0","spec_id":"harbor-design"}
        """)
    }

    @Test(arguments: [
        (0.0, "1970-01-01T00:00:00.000Z"), (1_794_387_600.657, "2026-11-11T09:00:00.657Z"),
        (1_794_387_600.9996, "2026-11-11T09:00:01.000Z"), (-0.001, "1969-12-31T23:59:59.999Z"),
        (-1.5, "1969-12-31T23:59:58.500Z"),
    ])
    func anInstantIsWrittenToTheNearestMillisecond(seconds: Double, written: String) throws {
        let encoded = try SailJSON.encode([Date(timeIntervalSince1970: seconds)])
        #expect(String(decoding: encoded, as: UTF8.self) == #"[""# + written + #""]"#)
        let read = try SailJSON.decode([Date].self, from: encoded, route: "cache")
        #expect(read.map(\.milliseconds) == [Int64((seconds * 1000).rounded())])
    }

    @Test func aWordAModelDoesNotKnowIsWrittenBackAsItCame() throws {
        let project = ProjectSummary(name: "harbor", containerStatus: .unknown("drydock"))
        #expect(try roundTrip(project) == project)
        #expect(String(decoding: try SailJSON.encode(project), as: UTF8.self)
            == #"{"container_status":"drydock","name":"harbor"}"#)
    }

    @Test func whatHasAnIdIsKnownByIt() throws {
        let room = try Endpoint.room("harbor-design").decode(response(try Exchange("room")))
        let detail = try Endpoint.spec("harbor-docks").decode(response(try Exchange("spec")))
        let messages = try Endpoint.messages(room: "harbor-design").decode(response(try Exchange("messages")))
        #expect(ids(of: [room]) == ["harbor-design"])
        #expect(ids(of: [detail.spec]) == ["harbor-docks"])
        #expect(ids(of: [detail.latestRun].compactMap(\.self)) == ["run-1"])
        #expect(ids(of: messages.messages).count == 4)
        #expect(ids(of: [ProjectSummary(name: "harbor", containerStatus: .known(.running))]) == ["harbor"])
    }
}

/// `value` written as the client writes JSON and read back as it reads the server's.
func roundTrip<Value: Codable>(_ value: Value) throws -> Value {
    try SailJSON.decode(Value.self, from: SailJSON.encode(value), route: "cache")
}

func ids<Model: Identifiable>(of models: [Model]) -> [Model.ID] {
    models.map(\.id)
}
