import Foundation
import SailAPI
import SailEvents
import SailKitTestSupport
import Synchronization
import Testing

@Suite struct EventSourceTests {
    @Test func aStreamThatEndsMidEventAsksAgainFromTheLastCompleteEventAndDropsThePart() async throws {
        let stream = try Goldens.bytes("events/stream.sse")
        let third = try #require(range(of: "id: 3\n", in: stream))
        let fourth = try #require(range(of: "id: 4\n", in: stream))
        let server = try ScriptedStreams([
            .stream([Array(stream[..<(third.upperBound + 20)])], .close),
            .stream([Array(stream[fourth.lowerBound...])], .close),
        ])
        let updates = try await read(follow(server))
        #expect(updates.prefix(4).map(\.lastEventID) == ["", "1", "2", "2"])
        #expect(updates[0] == .connected(lastEventID: ""))
        #expect(updates[3] == .connected(lastEventID: "2"))
        #expect(events(in: updates).map(\.lastEventID).prefix(4) == ["1", "2", "4", "5"])
        let requests = await server.requests
        #expect(requests.map { $0.headers["Last-Event-ID"] } == [nil, "2", "38"])
        #expect(requests.allSatisfy { $0.headers["Accept"] == "text/event-stream" })
    }

    @Test func anEventSentWithoutAnIdAfterAReconnectKeepsTheLastIdSeen() async throws {
        let room = try Goldens.bytes("events/room-stream.sse")
        let second = try #require(range(of: "id: 2\n", in: room))
        let server = try ScriptedStreams([.stream([Array(room[..<second.lowerBound])], .close), .stream([room], .close)])
        let updates = try await read(follow(server))
        #expect(updates.prefix(6).map(\.lastEventID) == ["", "", "1", "1", "1", "1"])
        #expect(await server.requests.map { $0.headers["Last-Event-ID"] }.prefix(2) == [nil, "1"])
    }

    @Test func aFailedConnectIsAnnouncedAndAskedAgainAfterTheWaitTheServerSet() async throws {
        let server = try ScriptedStreams([
            .stream([Array("retry: 2500\nid: 1\ndata: one\n\n".utf8)], .drop),
            .unreachable,
            .full,
            .stream([Array("id: 2\ndata: two\n\n".utf8)], .close),
        ])
        let naps = Naps()
        let updates = try await read(follow(server, naps: naps))
        #expect(updates == [
            .connected(lastEventID: ""),
            .event(ServerSentEvent(type: "message", data: "one", lastEventID: "1")),
            .connectFailed(.unreachable("no connection")),
            .connectFailed(.unexpected(route: "GET /v1/events/stream", status: 503)),
            .connected(lastEventID: "1"),
            .event(ServerSentEvent(type: "message", data: "two", lastEventID: "2")),
        ])
        #expect(await naps.taken == [.milliseconds(2500), .milliseconds(2500), .seconds(1), .milliseconds(2500)])
        #expect(await server.requests.map { $0.headers["Last-Event-ID"] } == [nil, "1", "1", "1", "2"])
    }

    @Test func theFirstReconnectWaitsThreeSecondsUnlessTheServerSaysOtherwise() async throws {
        let naps = Naps()
        let updates = try await read(follow(ScriptedStreams([.stream([], .close)]), naps: naps))
        #expect(updates == [.connected(lastEventID: "")])
        #expect(await naps.taken == [.seconds(3)])
    }

    @Test func noStreamAndNoServerMakesTheClientWaitLongerThanAMinute() async throws {
        let server = try ScriptedStreams([
            .stream([Array("retry: 9223372036854775807\n\n".utf8)], .close),
            .answer(503, headers: ["retry-after": "86400"], []),
            .answer(503, headers: ["retry-after": "-5"], []),
            .answer(503, headers: ["retry-after": "Wed, 11 Nov 2026 09:00:00 GMT"], []),
            .stream([Array("retry: 100\n\n".utf8)], .close),
        ])
        let naps = Naps()
        _ = try await read(follow(server, naps: naps))
        #expect(await naps.taken == [.seconds(60), .seconds(60), .zero, .seconds(60), .milliseconds(100)])
    }

    @Test func aRefusedStreamIsTheServersRefusalAndIsNotRetried() async throws {
        let refusal = try Exchange("refusal-invalid-bearer-token")
        let server = try ScriptedStreams([.answer(refusal.status, [Array(refusal.body)])])
        var updates = follow(server).makeAsyncIterator()
        await #expect(throws: SailError.refused(Refusal(code: .known(.invalidBearerToken), message: "Bearer token is invalid."))) {
            try await updates.next()
        }
        #expect(try await updates.next() == nil)
        #expect(await server.requests.count == 1)
    }

    @Test func aRefusalLongerThanItsCapIsReadFromItsFirstBytesAndNoFurther() async throws {
        let refusal = try Exchange("refusal-invalid-bearer-token")
        let chunk = 24 * 1024
        let padding = [UInt8](repeating: UInt8(ascii: " "), count: chunk)
        let server = try ScriptedStreams([.answer(refusal.status, [
            Array(refusal.body) + padding.dropFirst(refusal.body.count),
            padding,
            padding.prefix(16 * 1024) + [UInt8](repeating: UInt8(ascii: "x"), count: 8 * 1024),
            padding, padding, padding,
        ])])
        await #expect(throws: SailError.refused(Refusal(code: .known(.invalidBearerToken), message: "Bearer token is invalid."))) {
            try await read(follow(server))
        }
        #expect(server.pulled == 3)
    }

    @Test func aFailedAnswerWithNoRefusalIsUnexpected() async throws {
        let server = try ScriptedStreams([.answer(405, [])])
        await #expect(throws: SailError.unexpected(route: "GET /v1/events/stream", status: 405)) {
            try await read(follow(server))
        }
    }

    @Test(arguments: ["text/html; charset=utf-8", nil])
    func anAnswerThatIsNotAnEventStreamIsAnErrorAndIsNotAskedForAgain(contentType: String?) async throws {
        let server = try ScriptedStreams([
            .answer(200, headers: contentType.map { ["content-type": $0] } ?? [:], [Array("<html>sign in</html>".utf8)]),
        ])
        var updates = follow(server).makeAsyncIterator()
        await #expect(throws: SailError.notAnEventStream(route: "GET /v1/events/stream", contentType: contentType)) {
            try await updates.next()
        }
        #expect(try await updates.next() == nil)
        #expect(await server.requests.count == 1)
        #expect(server.pulled == 0)
    }

    @Test func anEventStreamIsOneWhateverTheCaseOrParametersOfItsContentType() async throws {
        let server = try ScriptedStreams([
            .answer(200, headers: ["content-type": "Text/Event-Stream ; charset=utf-8"], [Array("data: one\n\n".utf8)]),
        ])
        #expect(events(in: try await read(follow(server))).map(\.data) == ["one"])
    }

    @Test func aStreamWithALineTooLongToHoldIsDroppedAsBrokenAndAskedForAgain() async throws {
        let server = try ScriptedStreams([
            .stream([
                Array("id: 1\ndata: one\n\n".utf8) + [UInt8](repeating: UInt8(ascii: "x"), count: EventStreamParser.limit + 1),
                Array("\n\nid: 2\ndata: never\n\n".utf8),
            ], .close),
            .stream([Array("id: 3\ndata: three\n\n".utf8)], .close),
        ])
        let updates = try await read(follow(server))
        #expect(updates == [
            .connected(lastEventID: ""),
            .event(ServerSentEvent(type: "message", data: "one", lastEventID: "1")),
            .connected(lastEventID: "1"),
            .event(ServerSentEvent(type: "message", data: "three", lastEventID: "3")),
        ])
        #expect(server.pulled == 2)
    }

    @Test func aCancelledReaderStopsWithoutReconnecting() async throws {
        let server = try ScriptedStreams([.stream([Array("data: one\n\n".utf8)], .close)])
        let source = follow(server)
        let reader = Task {
            var updates = source.makeAsyncIterator()
            let connected = try await updates.next()
            let first = try await updates.next()
            withUnsafeCurrentTask { $0?.cancel() }
            return (connected, first, try await updates.next())
        }
        let (connected, first, after) = try await reader.value
        #expect(connected == .connected(lastEventID: ""))
        #expect(first == .event(ServerSentEvent(type: "message", data: "one", lastEventID: "")))
        #expect(after == nil)
        #expect(await server.requests.count == 1)
    }
}

/// The server's stream as `server` scripts it, followed with waits that return at once.
func follow(_ server: ScriptedStreams, naps: Naps = Naps()) -> EventSource {
    EventSource(transport: server, request: HTTPRequest(method: .get, path: "/v1/events/stream"), sleep: naps.sleep)
}

/// Every update `source` yields until it stops, read in a task of its own, which a script that
/// runs out cancels.
func read(_ source: EventSource) async throws -> [ServerSentUpdate] {
    try await Task {
        var updates: [ServerSentUpdate] = []
        for try await update in source {
            updates.append(update)
        }
        return updates
    }.value
}

func events(in updates: [ServerSentUpdate]) -> [ServerSentEvent] {
    updates.compactMap { if case let .event(event) = $0 { event } else { nil } }
}

extension ServerSentUpdate {
    /// The last event id this update carries, if it carries one.
    var lastEventID: String? {
        switch self {
        case let .event(event): event.lastEventID
        case let .connected(lastEventID): lastEventID
        case .connectFailed: nil
        }
    }
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

/// A server that answers each stream it is asked for with the next connection of its script, a
/// chunk at a time as its reader asks, and once the script is over stops the reader asking, as a
/// reader that went away stops.
actor ScriptedStreams: Transport {
    enum Ending { case close, drop }
    enum Connection {
        /// The server's event stream, opened as the server opens one.
        case stream([[UInt8]], Ending)
        /// The server with every stream it allows taken.
        case full
        /// Any other answer.
        case answer(Int, headers: [String: String] = [:], [[UInt8]])
        case unreachable
    }

    struct Dropped: Error {}

    private var script: [Connection]
    private let open: (status: Int, headers: [String: String])
    private let full: (status: Int, headers: [String: String])
    private let pulls = Mutex(0)
    private(set) var requests: [HTTPRequest] = []

    init(_ script: [Connection]) throws {
        self.script = script
        open = try Goldens.streamAnswer("open")
        full = try Goldens.streamAnswer("full")
    }

    /// How many chunks readers have taken from the bodies this server answered with.
    nonisolated var pulled: Int {
        pulls.withLock { $0 }
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
        switch script.removeFirst() {
        case let .stream(chunks, ending):
            return HTTPStream(status: open.status, headers: open.headers, body: body(chunks, ending))
        case .full:
            return HTTPStream(status: full.status, headers: full.headers, body: body([], .close))
        case let .answer(status, headers, chunks):
            return HTTPStream(status: status, headers: headers, body: body(chunks, .close))
        case .unreachable:
            throw .unreachable("no connection")
        }
    }

    private nonisolated func body(_ chunks: [[UInt8]], _ ending: Ending) -> AsyncThrowingStream<[UInt8], any Error> {
        let left = Mutex(chunks[...])
        return AsyncThrowingStream {
            guard let chunk = left.withLock({ $0.popFirst() }) else {
                if ending == .drop { throw Dropped() }
                return nil
            }
            self.pulls.withLock { $0 += 1 }
            return chunk
        }
    }
}
