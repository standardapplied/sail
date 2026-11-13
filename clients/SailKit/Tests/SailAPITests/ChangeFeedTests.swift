import Foundation
import SailAPI
import SailKitTestSupport
import Testing

@Suite struct ChangeFeedTests {
    @Test func aPageReadsEachChangeAsItsRowOrWhatBecameOfIt() throws {
        let page = try Endpoint.changes(since: "golden.0.0").decode(response(try Exchange("changes")))
        #expect(page.changes.map { String($0.seq) } == (try Goldens.changeSeqs()))
        #expect(page.next.hasPrefix("golden."), "a position is opaque, and handed back as it came")
        #expect(page.more == false)
        let docks = try #require(page.changes.first { $0.id == "harbor-docks" })
        guard case let .spec(spec) = docks.fate else { Issue.record("\(docks.fate)"); return }
        #expect(spec.title == "Docks take the ferry")
        let room = try #require(page.changes.first { $0.id == "harbor-design" })
        guard case let .room(design) = room.fate else { Issue.record("\(room.fate)"); return }
        #expect(design.title == "Docks")
        let message = try #require(page.changes.first { $0.type == .known(.message) })
        guard case let .message(posted) = message.fate else { Issue.record("\(message.fate)"); return }
        #expect(posted.id == message.id)
        #expect(page.changes.filter { $0.fate == .gone }.map(\.id) == ["harbor-quay"])
        #expect(page.changes.filter { $0.fate == .erased }.map(\.id) == ["harbor-buoys", "harbor-buoys"])
    }

    @Test func aFedRowCarriesNoFieldItsListDerivesFromOtherRows() throws {
        let page = try Endpoint.changes(since: "golden.0.0").decode(response(try Exchange("changes")))
        let rooms = page.changes.compactMap { if case let .room(room) = $0.fate { room } else { nil } }
        let specs = page.changes.compactMap { if case let .spec(spec) = $0.fate { spec } else { nil } }
        #expect(!rooms.isEmpty && !specs.isEmpty)
        #expect(rooms.allSatisfy { $0.specIDs.isEmpty && $0.lastActivityAt == nil && !$0.needsReply })
        #expect(specs.allSatisfy { $0.wake == nil && $0.engagement == nil && $0.lastActivityAt == nil })
    }

    @Test func theRowsAClientHoldsDeriveWhatItsListsShowFieldForField() throws {
        let page = try Endpoint.changes(since: "golden.0.0").decode(response(try Exchange("changes")))
        var held = HeldRows()
        for change in page.changes {
            held.apply(change)
        }
        let rooms = try Endpoint.rooms(project: "harbor").decode(response(try Exchange("board-rooms")))
        let specs = try Endpoint.specs(SpecFilter(project: "harbor")).decode(response(try Exchange("board-specs")))
        #expect(!rooms.rooms.isEmpty && !specs.specs.isEmpty)
        for room in rooms.rooms {
            let listed = Room(
                id: room.id, project: room.project, title: room.title, assignee: room.assignee, wake: room.wake,
                effectiveWake: room.effectiveWake, members: room.members, specIDs: room.specIDs.sorted(),
                createdBy: room.createdBy, createdAt: room.createdAt, updatedAt: room.updatedAt,
                updatedBy: room.updatedBy, lastActivityAt: room.lastActivityAt, needsReply: room.needsReply,
                questionMessageID: room.questionMessageID
            )
            #expect(held.room(room.id) == listed, "room \(room.id)")
        }
        for spec in specs.specs {
            #expect(held.spec(spec.id) == spec, "spec \(spec.id)")
        }
        #expect(held.room("harbor-buoys") == nil, "an erased room is held no more")
    }

    @Test func aSpecToldGoneLeavesItsRoomAsTheListsDo() throws {
        let at = Date(timeIntervalSince1970: 1_794_387_600)
        let room = Room(id: "dock", project: "harbor", title: "Dock", effectiveWake: .known(.on), createdAt: at, updatedAt: at)
        let spec = Spec(id: "crane", project: "harbor", title: "Crane", status: .known(.draft), createdAt: at, updatedAt: at, roomID: "dock")
        var held = HeldRows(specs: [spec], rooms: [room])
        #expect(held.room("dock")?.specIDs == ["crane"])
        held.apply(Change(seq: 3, type: .known(.spec), id: "crane", fate: .gone))
        #expect(held.room("dock")?.specIDs == [], "the server's list reads only live specs")
        #expect(held.spec("crane") == nil)
        held.apply(Change(seq: 5, type: .known(.room), id: "dock", fate: .gone))
        #expect(held.room("dock") == nil)
    }

    @Test func aReplyFromSomeoneWhoDoesNotSteerTheRoomClearsItsQuestionHereNotOnTheList() throws {
        let at = Date(timeIntervalSince1970: 1_794_387_600)
        let room = Room(id: "dock", project: "harbor", title: "Dock", effectiveWake: .known(.on), createdAt: at, updatedAt: at)
        let asked = Message(id: "m1", specID: "dock", author: "claude/run-1", body: "?", createdAt: at, isQuestion: true)
        let kim = Message(id: "m2", specID: "dock", author: "kim", body: "north", createdAt: at.addingTimeInterval(1))
        let held = HeldRows(rooms: [room], messages: [asked, kim])
        #expect(held.room("dock")?.needsReply == false, "a known limit: the list still shows m1, as kim does not steer")
    }

    @Test func aQuestionOlderThanTheMessagesHeldIsNotOpenHereThoughItIsOnTheList() throws {
        let at = Date(timeIntervalSince1970: 1_794_387_600)
        let room = Room(id: "dock", project: "harbor", title: "Dock", effectiveWake: .known(.on), createdAt: at, updatedAt: at)
        let page = (1 ... 50).map {
            Message(id: String(format: "m%03d", $0 + 1), specID: "dock", author: "claude/run-1", body: "progress",
                    createdAt: at.addingTimeInterval(Double($0)))
        }
        let held = HeldRows(rooms: [room], messages: page)
        #expect(held.room("dock")?.needsReply == false, "a known limit: m001, older than the page, is open on the list")
    }

    @Test func aRoomsOpenQuestionIsItsLatestAgentQuestionNoPersonAnsweredAndItsSpecsShowIt() throws {
        let at = Date(timeIntervalSince1970: 1_794_387_600)
        let room = Room(id: "dock", project: "harbor", title: "Dock", effectiveWake: .known(.on), createdAt: at, updatedAt: at)
        let spec = Spec(id: "crane", project: "harbor", title: "Crane", status: .known(.draft), createdAt: at, updatedAt: at, roomID: "dock")
        let asked = Message(id: "m1", specID: "dock", author: "claude/run-1", body: "?", createdAt: at.addingTimeInterval(1), isQuestion: true)
        var held = HeldRows(specs: [spec], rooms: [room], messages: [asked])
        #expect(held.room("dock")?.questionMessageID == "m1")
        #expect(held.spec("crane")?.needsReply == true)
        #expect(held.room("dock")?.lastActivityAt == at.addingTimeInterval(1))
        held.apply(Change(seq: 6, type: .known(.message), id: "m2", fate: .message(
            Message(id: "m2", specID: "dock", author: "sail", body: "noted", createdAt: at.addingTimeInterval(2)))))
        #expect(held.room("dock")?.needsReply == true, "the box speaking answers nothing")
        held.apply(Change(seq: 7, type: .known(.message), id: "m3", fate: .message(
            Message(id: "m3", specID: "dock", author: "uday", body: "yes", createdAt: at.addingTimeInterval(3)))))
        #expect(held.room("dock")?.needsReply == false)
        #expect(held.spec("crane")?.questionMessageID == nil)
        held.apply(Change(seq: 8, type: .known(.message), id: "m3", fate: .erased))
        #expect(held.room("dock")?.questionMessageID == "m1", "an erased answer reopens it, as the list does")
    }

    @Test func aChangeOfATypeTheClientDoesNotKnowKeepsNothingOfItsRow() throws {
        let altered = try Exchange("changes").altered(at: ["changes", 0, "type"], to: "ferry")
        let page = try Endpoint.changes(since: "golden.0.0").decode(response(altered))
        #expect(page.changes.first?.type == .unknown("ferry"))
        #expect(page.changes.first?.fate == .unknown)
    }

    @Test func aPositionTheServerCannotAnswerFromIsRefusedAsResyncRequired() async throws {
        let (result, _) = try await answer("refusal-resync-required") { try await $0.send(.changes(since: "999999999")) }
        guard case let .failure(.refused(refusal)) = result else { Issue.record("\(result)"); return }
        #expect(refusal.code == .known(.resyncRequired))
        #expect(refusal.action == "Read the rows afresh, then follow the feed from the seq header those reads answer.")
    }

    @Test func aListIsReadWithThePositionToFollowTheFeedFrom() async throws {
        let exchange = try Exchange("rooms")
        let server = RecordedServer([exchange])
        let (rooms, position) = try await SailClient(transport: server, token: "tok").positioned(.rooms(project: "harbor"))
        #expect(rooms.count == 3)
        #expect(position == exchange.headers["seq"])
        #expect(position != nil)
        #expect(HTTPResponse(status: 200, headers: ["SEQ": "box.7.0a1b2c3d4e5f"], body: Data()).position == "box.7.0a1b2c3d4e5f")
        #expect(HTTPResponse(status: 200, headers: ["Seq": ""], body: Data()).position == nil)
        #expect(HTTPResponse(status: 200, body: Data()).position == nil)
    }

    @Test func theClientAsksForTheChangesItNames() throws {
        #expect(Endpoint.changes(since: "box.5.0a1b2c3d4e5f", types: [.spec, .room], limit: 10).request.path
            == "/v1/changes?since=box.5.0a1b2c3d4e5f&types=spec%2Croom&limit=10")
        #expect(Endpoint.changes(since: "box.0.0").request.path == "/v1/changes?since=box.0.0")
        #expect(ChangeType.allCases.map(\.rawValue) == (try Goldens.vocabulary("change_types")))
    }
}
