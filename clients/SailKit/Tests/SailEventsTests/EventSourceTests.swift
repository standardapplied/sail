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
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps(2).sleep)
        let events = try await read(source)
        #expect(events.map(\.lastEventID) == ["1", "2", "4"])
        #expect(events.last?.data == "next")
        let requests = await server.requests
        #expect(requests.map { $0.headers["Last-Event-ID"] } == [nil, "2", "4"])
        #expect(requests.allSatisfy { $0.headers["Accept"] == "text/event-stream" })
    }

    @Test func aConnectionThatBreaksIsReopenedAfterTheRetryTheServerSet() async throws {
        let server = ScriptedStreams([
            .answer(200, [Array("retry: 2500\nid: 1\ndata: one\n\n".utf8)], .drop),
            .unreachable,
            .answer(503, [], .close),
            .answer(200, [Array("id: 2\ndata: two\n\n".utf8)], .close),
        ])
        let naps = Naps(4)
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: naps.sleep)
        #expect(try await read(source).map(\.data) == ["one", "two"])
        #expect(await naps.taken == [.milliseconds(2500), .milliseconds(2500), .milliseconds(2500), .milliseconds(2500)])
        #expect(await server.requests.map { $0.headers["Last-Event-ID"] } == [nil, "1", "1", "1", "2"])
    }

    @Test func theFirstReconnectWaitsThreeSecondsUnlessTheServerSaysOtherwise() async throws {
        let naps = Naps(1)
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
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps(0).sleep)
        var events = source.makeAsyncIterator()
        await #expect(throws: SailError.refused(Refusal(
            code: .known(.invalidBearerToken), message: "Bearer token is invalid.", action: nil, remedy: []
        ))) { try await events.next() }
        #expect(try await events.next() == nil)
        #expect(await server.requests.count == 1)
    }

    @Test func aFailedAnswerWithNoRefusalIsUnexpected() async throws {
        let server = ScriptedStreams([.answer(405, [], .close)])
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps(0).sleep)
        await #expect(throws: SailError.unexpected(route: "GET /v1/events/stream", status: 405)) { try await read(source) }
    }

    @Test func aCancelledReaderStopsWithoutReconnecting() async throws {
        let server = ScriptedStreams([.answer(200, [Array("data: one\n\n".utf8)], .close)])
        let source = EventSource(transport: server, request: HTTPRequest(method: "GET", path: "/v1/events/stream"), sleep: Naps(9).sleep)
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

/// Every event `source` yields until it stops.
func read(_ source: EventSource) async throws(SailError) -> [ServerSentEvent] {
    var events: [ServerSentEvent] = []
    for try await event in source {
        events.append(event)
    }
    return events
}

func range(of text: String, in bytes: [UInt8]) -> Range<Int>? {
    let needle = Array(text.utf8)
    return bytes.indices.first { bytes[$0...].starts(with: needle) }.map { $0 ..< $0 + needle.count }
}

/// Reconnect waits that return at once, recorded, until `allowed` are used and the reader is told
/// to stop as a cancelled task is.
actor Naps {
    private let allowed: Int
    private(set) var taken: [Duration] = []

    init(_ allowed: Int) {
        self.allowed = allowed
    }

    nonisolated var sleep: @Sendable (Duration) async throws -> Void {
        { try await self.nap($0) }
    }

    private func nap(_ duration: Duration) throws {
        guard taken.count < allowed else { throw CancellationError() }
        taken.append(duration)
    }
}

/// A server that answers each stream it is asked for with the next connection of its script.
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
        guard !script.isEmpty, case let .answer(status, chunks, ending) = script.removeFirst() else {
            throw .unreachable("no connection")
        }
        return HTTPStream(status: status, body: AsyncThrowingStream { stream in
            chunks.forEach { stream.yield($0) }
            stream.finish(throwing: ending == .drop ? Dropped() : nil)
        })
    }
}
