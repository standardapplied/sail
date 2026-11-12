import Foundation
import SailAPI
import SailEvents
import SailKitTestSupport
import Testing

@Suite struct EventSourceTests {
    @Test func aStreamThatEndsMidEventResumesFromTheLastCompleteEventAndDropsThePart() async throws {
        let stream = try Goldens.bytes("events/stream.sse")
        let third = try #require(range(of: "id: 3\n", in: stream))
        let partial = Array(stream[..<(third.upperBound + 20)])
        let server = ScriptedStreams([.answer(200, [partial], .close), .answer(200, [Array("id: 4\ndata: next\n\n".utf8)], .close)])
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps().sleep)
        let events = try await read(source)
        #expect(events.map(\.lastEventID) == ["1", "2", "4"])
        #expect(events.last?.data == "next")
        let requests = await server.requests
        #expect(requests.map { $0.headers["Last-Event-ID"] } == [nil, "2", "4"])
        #expect(requests.allSatisfy { $0.headers["Accept"] == "text/event-stream" })
    }

    @Test func anEventSentWithoutAnIdAfterAReconnectKeepsWhereTheStreamResumes() async throws {
        let room = try Goldens.bytes("events/room-stream.sse")
        let opening = try #require(range(of: "id: 2\n", in: room))
        let server = ScriptedStreams([.answer(200, [Array(room[..<opening.lowerBound])], .close), .answer(200, [room], .close)])
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps().sleep)
        let events = try await read(source)
        #expect(events.map(\.lastEventID).prefix(4) == ["", "1", "1", "1"])
        #expect(await server.requests.map { $0.headers["Last-Event-ID"] }.prefix(2) == [nil, "1"])
    }

    @Test func aConnectionThatBreaksIsReopenedAfterTheRetryTheServerSet() async throws {
        let server = ScriptedStreams([
            .answer(200, [Array("retry: 2500\nid: 1\ndata: one\n\n".utf8)], .drop),
            .unreachable,
            .answer(503, [], .close),
            .answer(200, [Array("id: 2\ndata: two\n\n".utf8)], .close),
        ])
        let naps = Naps()
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: naps.sleep)
        #expect(try await read(source).map(\.data) == ["one", "two"])
        #expect(await naps.taken == [.milliseconds(2500), .milliseconds(2500), .milliseconds(2500), .milliseconds(2500)])
        #expect(await server.requests.map { $0.headers["Last-Event-ID"] } == [nil, "1", "1", "1", "2"])
    }

    @Test func theFirstReconnectWaitsThreeSecondsUnlessTheServerSaysOtherwise() async throws {
        let naps = Naps()
        let source = EventSource(
            transport: ScriptedStreams([.answer(200, [], .close)]),
            request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: naps.sleep
        )
        #expect(try await read(source).isEmpty)
        #expect(await naps.taken == [.seconds(3)])
    }

    @Test func aRefusedStreamIsTheServersRefusalAndIsNotRetried() async throws {
        let refusal = try Exchange("refusal-invalid-bearer-token")
        let server = ScriptedStreams([.answer(refusal.status, [Array(refusal.body)], .close)])
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps().sleep)
        var events = source.makeAsyncIterator()
        await #expect(throws: SailError.refused(Refusal(
            code: .known(.invalidBearerToken), message: "Bearer token is invalid.", action: nil, remedy: []
        ))) { try await events.next() }
        #expect(try await events.next() == nil)
        #expect(await server.requests.count == 1)
    }

    @Test func aFailedAnswerWithNoRefusalIsUnexpected() async throws {
        let server = ScriptedStreams([.answer(405, [], .close)])
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps().sleep)
        await #expect(throws: SailError.unexpected(route: "GET /v1/events/stream", status: 405)) { try await read(source) }
    }

    @Test func aCancelledReaderStopsWithoutReconnecting() async throws {
        let server = ScriptedStreams([.answer(200, [Array("data: one\n\n".utf8)], .close)])
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps().sleep)
        let reader = Task {
            var iterator = source.makeAsyncIterator()
            let first = try await iterator.next()
            withUnsafeCurrentTask { $0?.cancel() }
            return (first, try await iterator.next())
        }
        let (first, after) = try await reader.value
        #expect(first?.data == "one")
        #expect(after == nil)
        #expect(await server.requests.count == 1)
    }
}

/// Every event `source` yields until it stops, read in a task of its own, which a script that
/// runs out cancels.
func read(_ source: EventSource) async throws -> [ServerSentEvent] {
    try await Task {
        var events: [ServerSentEvent] = []
        for try await event in source {
            events.append(event)
        }
        return events
    }.value
}

func range(of text: String, in bytes: [UInt8]) -> Range<Int>? {
    let needle = Array(text.utf8)
    return bytes.indices.first { bytes[$0...].starts(with: needle) }.map { $0 ..< $0 + needle.count }
}

/// Reconnect waits that return at once, each recorded.
actor Naps {
    private(set) var taken: [Duration] = []

    nonisolated var sleep: @Sendable (Duration) async throws -> Void {
        { await self.nap($0) }
    }

    private func nap(_ duration: Duration) {
        taken.append(duration)
    }
}

/// A server that answers each stream it is asked for with the next connection of its script, and
/// once the script is over stops the reader asking, as a reader that went away stops.
actor ScriptedStreams: Transport {
    enum Ending { case close, drop }
    enum Connection {
        case answer(Int, [[UInt8]], Ending)
        case unreachable
    }

    struct Dropped: Error {}

    private var script: [Connection]
    private(set) var requests: [HTTPRequest] = []

    init(_ script: [Connection]) {
        self.script = script
    }

    func send(_ request: HTTPRequest) throws(SailError) -> HTTPResponse {
        throw .unreachable("streams only")
    }

    func open(_ request: HTTPRequest) throws(SailError) -> HTTPStream {
        requests.append(request)
        guard !script.isEmpty else {
            withUnsafeCurrentTask { $0?.cancel() }
            throw .unreachable("the script is over")
        }
        guard case let .answer(status, chunks, ending) = script.removeFirst() else {
            throw .unreachable("no connection")
        }
        return HTTPStream(status: status, body: AsyncThrowingStream { stream in
            chunks.forEach { stream.yield($0) }
            stream.finish(throwing: ending == .drop ? Dropped() : nil)
        })
    }
}
