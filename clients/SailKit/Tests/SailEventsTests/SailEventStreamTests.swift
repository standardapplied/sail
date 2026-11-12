import Foundation
import SailAPI
import SailEvents
import SailKitTestSupport
import Testing

@Suite struct SailEventStreamTests {
    @Test func everyGoldenEventDecodesAsAWellKnownTypeAndTheStreamEndsWithTheServersRefusal() async throws {
        let (events, ended) = try await readGolden("events/stream.sse")
        #expect(ended == .refused(Refusal(
            code: .known(.invalidBearerToken), message: "Session token is invalid or expired.", action: nil, remedy: []
        )))
        let (roomEvents, _) = try await readGolden("events/room-stream.sse")
        let types = (events + roomEvents).map(\.type)
        #expect(types.allSatisfy { if case .known = $0 { true } else { false } })
        #expect(Set(types.map(\.rawValue)) == Set(try Goldens.vocabulary("event_types")))
        #expect(EventType.allCases.map(\.rawValue) == (try Goldens.vocabulary("event_types")))
        #expect(events.map(\.id) == (1 ... Int64(events.count)).map { Optional($0) })
    }

    @Test func anEventReadsEveryFieldTheServerSends() async throws {
        let (events, _) = try await readGolden("events/stream.sse")
        let posted = try #require(events.first)
        #expect(posted.version == 1)
        #expect(posted.id == 1)
        #expect(posted.timestamp.milliseconds == Goldens.pinnedMilliseconds)
        #expect(posted.project == "harbor")
        #expect(posted.spec == "harbor-design")
        #expect(posted.type == .known(.specMessagePosted))
        #expect(posted.agent == "uday")
        #expect(posted.host == "box")
        #expect(posted.data == [
            "message_id": .string("00000000-0000-7000-8000-000000000001"),
            "preview": .string("Which berth takes the ferry — north or south? ⚓"),
            "question": .bool(true),
        ])
        #expect(posted.publisher == nil)
        let published = try #require(events.first { $0.publisher != nil })
        #expect(published.publisher == Publisher(handle: "uday", role: "admin", lane: "api"))
        #expect(published.spec == nil)
        #expect(published.agent == "mast")
        #expect(published.host == "laptop")
    }

    @Test func anEventsDataKeepsEveryKindOfValueAClientPublished() async throws {
        let (events, _) = try await readGolden("events/stream.sse")
        let published = try #require(events.first { $0.publisher != nil })
        #expect(published.data["berths"] == .array([.number(4), .number(12.5), .bool(true), .string("south"), .null]))
    }

    @Test func anEventWhoseEmitterWritesNoDataReadsAsNone() async throws {
        let (events, _) = try await readGolden("events/stream.sse")
        let filed = try #require(events.first { $0.type == .known(.boardUpdated) })
        #expect(filed.data == [:])
    }

    @Test func aStreamThatEndedWithARefusalAnswersNothingMore() async throws {
        let server = ScriptedStreams([.answer(200, [try Goldens.bytes("events/room-stream.sse")], .close)])
        var events = SailClient(transport: server, token: "tok").events(sleep: Naps().sleep).makeAsyncIterator()
        var read = 0
        await #expect(throws: SailError.self) {
            while try await events.next() != nil {
                read += 1
            }
        }
        #expect(read == 16)
        #expect(try await events.next() == nil)
        #expect(await server.requests.count == 1)
    }

    @Test func aRoomsStreamOpensWithWhereItsMemberStandsUnnumbered() async throws {
        let (events, _) = try await readGolden("events/room-stream.sse")
        let state = try #require(events.first)
        #expect(state.type == .known(.agentTurnState))
        #expect(state.id == nil)
        #expect(state.data == ["open": .bool(false)])
        #expect(events.dropFirst().allSatisfy { $0.spec == "harbor-design" })
    }

    @Test func anEventTypeTheClientDoesNotKnowIsKeptAsUnknown() async throws {
        let line = try dataLines("events/stream.sse")[0]
        let altered = try alter(line) { $0["type"] = "ferry_docked"; $0["berth"] = 4 }
        let (events, _) = await read(stream([altered]))
        #expect(events.map(\.type) == [.unknown("ferry_docked")])
    }

    @Test func anEventMissingAFieldNamesTheStreamAndTheField() async throws {
        let line = try dataLines("events/stream.sse")[0]
        let (events, ended) = await read(stream([try alter(line) { $0["ts"] = nil }]))
        #expect(events.isEmpty)
        #expect(ended == .undecodable(Undecodable(route: "GET /v1/events/stream", field: "ts", reason: "it is missing")))
    }

    @Test func theClientAsksForTheEventsItsFilterNames() async throws {
        let server = ScriptedStreams([])
        let client = SailClient(transport: server, token: "tok")
        _ = await read(client.events(EventFilter(room: "harbor-design"), sleep: Naps().sleep))
        _ = await read(client.events(
            EventFilter(project: "harbor", types: [.specMessagePosted, .agentTextDelta]), sleep: Naps().sleep
        ))
        _ = await read(client.events(sleep: Naps().sleep))
        let requests = await server.requests
        #expect(requests.map(\.path) == [
            "/v1/events/stream?room=harbor-design",
            "/v1/events/stream?project=harbor&type=spec_message_posted%2Cagent_text_delta",
            "/v1/events/stream",
        ])
        #expect(requests.allSatisfy { $0.headers["Authorization"] == "Bearer tok" })
    }
}

/// The events `golden` holds, as a client reads them, and how the stream ended.
func readGolden(_ golden: String) async throws -> ([Event], SailError?) {
    await read(stream([try Goldens.bytes(golden)]))
}

/// Every event `events` yields, and the error it ended with, if any.
func read(_ events: SailEventStream) async -> ([Event], SailError?) {
    await Task {
        var read: [Event] = []
        do throws(SailError) {
            for try await event in events {
                read.append(event)
            }
            return (read, nil)
        } catch {
            return (read, error)
        }
    }.value
}

/// A client's event stream over one connection that delivers `chunks` and closes.
func stream(_ chunks: [[UInt8]]) -> SailEventStream {
    let client = SailClient(transport: ScriptedStreams([.answer(200, chunks, .close)]), token: "tok")
    return client.events(sleep: Naps().sleep)
}

func dataLines(_ golden: String) throws -> [String] {
    String(decoding: try Goldens.bytes(golden), as: UTF8.self)
        .split(separator: "\n").filter { $0.hasPrefix("data: ") }.map { String($0.dropFirst(6)) }
}

/// `line`'s event as one complete SSE frame, its JSON altered by `change`.
func alter(_ line: String, _ change: (inout [String: Any]) -> Void) throws -> [UInt8] {
    var event = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any] ?? [:]
    change(&event)
    return Array("data: ".utf8) + [UInt8](try JSONSerialization.data(withJSONObject: event)) + Array("\n\n".utf8)
}
