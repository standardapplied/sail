import Foundation
import SailAPI
import SailEvents
import SailKitTestSupport
import Testing

@Suite(.timeLimit(.minutes(1))) struct SailEventStreamTests {
    @Test func everyGoldenEventDecodesAsAWellKnownTypeAndTheStreamEndsWithTheServersRefusal() async throws {
        let (updates, ended) = try await readGolden("events/stream.sse")
        #expect(ended == .refused(Refusal(code: .known(.invalidBearerToken), message: "Session token is invalid or expired.")))
        #expect(updates.first == .connected(after: nil))
        let all = events(in: updates)
        #expect(all.count == updates.count - 1)
        let (roomUpdates, _) = try await readGolden("events/room-stream.sse")
        let types = (all + events(in: roomUpdates)).map(\.type)
        #expect(types.allSatisfy { if case .known = $0 { true } else { false } })
        #expect(Set(types.map(\.rawValue)) == Set(try Goldens.vocabulary("event_types")))
        #expect(EventType.allCases.map(\.rawValue) == (try Goldens.vocabulary("event_types")))
        #expect(all.map(\.id) == (1 ... Int64(all.count)).map { Optional($0) })
    }

    @Test func anEventReadsEveryFieldTheServerSends() async throws {
        let all = events(in: try await readGolden("events/stream.sse").0)
        let posted = try #require(all.first)
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
        let published = try #require(all.first { $0.publisher != nil })
        #expect(published.publisher == Publisher(handle: "uday", role: .known(.admin), lane: .known(.api)))
        #expect(published.spec == nil)
        #expect(published.agent == "mast")
        #expect(published.host == "laptop")
        #expect(Lane.allCases.map(\.rawValue) == (try Goldens.vocabulary("publisher_lanes")))
    }

    @Test func anEventsDataKeepsEveryKindOfValueAndAnIntegerExactly() async throws {
        let all = events(in: try await readGolden("events/stream.sse").0)
        let published = try #require(all.first { $0.publisher != nil })
        #expect(published.data == [
            "berths": .array([.integer(4), .number(12.5), .bool(true), .string("south"), .null]),
            "displacement": .integer(9_007_199_254_740_993),
        ])
        let stopped = try #require(all.first { $0.type == .known(.agentSessionStopped) })
        #expect(stopped.data["exit_code"] == .integer(2))
        let turn = try #require(events(in: try await readGolden("events/room-stream.sse").0).first)
        #expect(turn.data == ["open": .bool(false)])
    }

    @Test func aNumberTooWideForAnIntegerIsKeptAsANumberAndAnObjectAsAnObject() async throws {
        let line = try dataLines("events/stream.sse")[0]
        let widened = try alter(line) { $0["data"] = ["tonnage": 1e30, "berth": ["north": 4]] }
        let (updates, ended) = try await read(stream([widened]))
        #expect(ended == nil)
        #expect(events(in: updates).map(\.data) == [
            ["tonnage": .number(1e30), "berth": .object(["north": .integer(4)])],
        ])
    }

    @Test func anEventWhoseEmitterWritesNoDataReadsAsNone() async throws {
        let all = events(in: try await readGolden("events/stream.sse").0)
        let filed = try #require(all.first { $0.type == .known(.boardUpdated) })
        #expect(filed.data == [:])
    }

    @Test func aStreamThatEndedWithARefusalAnswersNothingMore() async throws {
        let server = try ScriptedStreams([.stream([try Goldens.bytes("events/room-stream.sse")], .close)])
        var updates = SailClient(transport: server, token: "tok").events(EventFilter(), sleep: Naps().sleep).makeAsyncIterator()
        var read = 0
        await #expect(throws: SailError.self) {
            while try await updates.next() != nil {
                read += 1
            }
        }
        #expect(read == 17)
        #expect(try await updates.next() == nil)
        #expect(await server.requests.count == 1)
    }

    @Test func aRoomsStreamOpensWithWhereItsMemberStandsUnnumbered() async throws {
        let all = events(in: try await readGolden("events/room-stream.sse").0)
        let state = try #require(all.first)
        #expect(state.type == .known(.agentTurnState))
        #expect(state.id == nil)
        #expect(all.dropFirst().allSatisfy { $0.spec == "harbor-design" })
    }

    @Test func everyConnectionIsAnnouncedWithTheLastEventReadBeforeItAndEveryFailedOneWithWhy() async throws {
        let golden = try Goldens.bytes("events/stream.sse")
        let third = try #require(range(of: "id: 3\n", in: golden))
        let server = try ScriptedStreams([
            .stream([Array(golden[..<third.lowerBound])], .drop),
            .unreachable,
            .full,
            .stream([Array(golden[third.lowerBound...])], .close),
        ])
        let (updates, _) = await read(SailClient(transport: server, token: "tok").events(EventFilter(), sleep: Naps().sleep))
        #expect(updates.prefix(7).map(\.summary) == [
            "connected after nil", "event 1", "event 2", "failed", "failed", "connected after 2", "event 3",
        ])
        #expect(updates[3] == .connectFailed(.unreachable("no connection")))
        #expect(updates[4] == .connectFailed(.unexpected(route: "GET /v1/events/stream", status: 503)))
    }

    @Test func anEventTypeTheClientDoesNotKnowIsKeptAsUnknown() async throws {
        let line = try dataLines("events/stream.sse")[0]
        let altered = try alter(line) { $0["type"] = "ferry_docked"; $0["berth"] = 4 }
        let (updates, _) = try await read(stream([altered]))
        #expect(events(in: updates).map(\.type) == [.unknown("ferry_docked")])
    }

    @Test func anEventTheClientCannotReadIsToldAsUnreadableAndTheStreamGoesOn() async throws {
        let lines = try dataLines("events/stream.sse")
        let (updates, ended) = try await read(stream([alter(lines[0]) { $0["ts"] = nil }, alter(lines[1]) { _ in }]))
        #expect(ended == nil)
        #expect(updates.map(\.summary) == ["connected after nil", "unreadable", "event 2"])
        #expect(updates[1] == .unreadable(Undecodable(route: "GET /v1/events/stream", field: "ts", reason: "it is missing")))
    }

    @Test func aNumberNoClientTypeHoldsMakesItsEventUnreadableNotTheStream() async throws {
        let lines = try dataLines("events/stream.sse")
        let huge = Array("data: \(lines[0].replacingOccurrences(of: #""question": true"#, with: #""question": 1e999"#))\n\n".utf8)
        let (updates, ended) = try await read(stream([huge, alter(lines[1]) { _ in }]))
        #expect(ended == nil)
        #expect(updates.map(\.summary) == ["connected after nil", "unreadable", "event 2"])
    }

    @Test func theClientAsksForTheEventsItsFilterNames() async throws {
        let server = try ScriptedStreams([])
        let client = SailClient(transport: server, token: "tok")
        _ = await read(client.events(EventFilter(room: "harbor-design"), sleep: Naps().sleep))
        _ = await read(client.events(
            EventFilter(project: "harbor", types: [.specMessagePosted, .agentTextDelta]), sleep: Naps().sleep
        ))
        _ = await read(client.events(EventFilter(), sleep: Naps().sleep))
        let requests = await server.requests
        #expect(requests.map(\.path) == [
            "/v1/events/stream?room=harbor-design",
            "/v1/events/stream?project=harbor&type=spec_message_posted%2Cagent_text_delta",
            "/v1/events/stream",
        ])
        #expect(requests.allSatisfy { $0.headers["Authorization"] == "Bearer tok" })
    }

    @Test func aClientsStreamIsReadWithoutASeamUntilItsReaderIsCancelled() async throws {
        let golden = try Goldens.bytes("events/room-stream.sse")
        let server = try ScriptedStreams([.stream([Array(golden[..<(try #require(range(of: "id: 2\n", in: golden))).lowerBound])], .close)])
        let stream = SailClient(transport: server, token: "tok").events()
        let reader = Task {
            var updates = stream.makeAsyncIterator()
            let read = [try await updates.next(), try await updates.next(), try await updates.next()]
            withUnsafeCurrentTask { $0?.cancel() }
            return (read.compactMap(\.?.summary), try await updates.next())
        }
        let (read, after) = try await reader.value
        #expect(read == ["connected after nil", "event unnumbered", "event 1"])
        #expect(after == nil)
        #expect(await server.requests.map(\.path) == ["/v1/events/stream"])
    }

    @Test func theClientAsksForTheNewestEventsAndReadsTheServersAnswer() async throws {
        let exchange = try Exchange("events-recent")
        let server = RecordedServer([exchange])
        let recent = try await SailClient(transport: server, token: "tok").send(.recentEvents(limit: 3))
        #expect(recent == RecentEvents(limit: 3, returned: 0, events: []))
        let request = try #require(await server.requests.first)
        #expect(request.route == "\(exchange.method) \(exchange.path)")
        #expect(request.headers["Authorization"] == "Bearer tok")
        #expect(Endpoint.recentEvents().request.path == "/v1/events/recent")
    }

    @Test func anEventIsBuiltAsItIsReadAndWrittenAsItIsRead() async throws {
        let all = events(in: try await readGolden("events/stream.sse").0)
        let published = try #require(all.first { $0.publisher != nil })
        #expect(published == Event(
            id: 7, timestamp: published.timestamp, project: "harbor", type: .known(.boardUpdated), agent: "mast",
            host: "laptop", data: published.data, publisher: Publisher(handle: "uday", role: .known(.admin), lane: .known(.api))
        ))
        let stopped = try #require(all.first { $0.type == .known(.agentSessionStopped) })
        #expect(stopped == Event(
            version: 1, id: 13, timestamp: stopped.timestamp, project: "harbor", spec: "harbor-docks",
            type: .known(.agentSessionStopped), agent: "sail", host: "box",
            data: ["exit_code": .integer(2), "run_id": .string("run-1"), "source": .string("sync")]
        ))
        let nested = Event(
            timestamp: stopped.timestamp, project: "harbor", type: .unknown("ferry_docked"), agent: "mast", host: "laptop",
            data: ["berth": .object(["north": .integer(4), "tide": .null, "draft": .number(12.5)])]
        )
        let page = RecentEvents(limit: all.count + 1, returned: all.count + 1, events: all + [nested])
        #expect(try SailJSON.decode(RecentEvents.self, from: SailJSON.encode(page), route: "cache") == page)
    }
}

/// The updates `golden` gives a client that reads it, and how the stream ended.
func readGolden(_ golden: String) async throws -> ([StreamUpdate], SailError?) {
    try await read(stream([Goldens.bytes(golden)]))
}

/// Every update `stream` yields, and the error it ended with, if any.
func read(_ stream: SailEventStream) async -> ([StreamUpdate], SailError?) {
    let reading = Task {
        var read: [StreamUpdate] = []
        do throws(SailError) {
            for try await update in stream {
                read.append(update)
            }
            return (read, SailError?.none)
        } catch {
            return (read, error)
        }
    }
    return await withTaskCancellationHandler { await reading.value } onCancel: { reading.cancel() }
}

func events(in updates: [StreamUpdate]) -> [Event] {
    updates.compactMap { if case let .event(event) = $0 { event } else { nil } }
}

extension StreamUpdate {
    /// This update in a word or two, for a test that asserts an order.
    var summary: String {
        switch self {
        case let .event(event): "event \(event.id.map(String.init) ?? "unnumbered")"
        case let .connected(after): "connected after \(after.map(String.init) ?? "nil")"
        case .connectFailed: "failed"
        case .unreadable: "unreadable"
        }
    }
}

/// A client's event stream over one connection that delivers `chunks` and closes.
func stream(_ chunks: [[UInt8]]) throws -> SailEventStream {
    let server = try ScriptedStreams([.stream(chunks, .close)])
    return SailClient(transport: server, token: "tok").events(EventFilter(), sleep: Naps().sleep)
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
