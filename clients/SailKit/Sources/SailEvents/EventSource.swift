import Foundation
import SailAPI

/// What a followed stream tells its reader: an event, or what became of a connection.
public enum ServerSentUpdate: Equatable, Sendable {
    case event(ServerSentEvent)
    /// A connection opened. It carries what the server publishes from now on and nothing from
    /// before, so whatever was published since the last one is not coming: `lastEventID` is the id
    /// of the last event the connections before it delivered, empty when there was none.
    case connected(lastEventID: String)
    /// An attempt to connect failed, for the reason given, and another follows after the
    /// reconnection time.
    case connectFailed(SailError)
}

/// A Server-Sent Events stream that follows its server through every reconnect, as the WHATWG
/// `EventSource` does: when a connection ends or breaks, or the server cannot be reached or is at
/// its connection limit, it waits the reconnection time and asks again. Each request carries the
/// last event id seen as `Last-Event-ID`, for a server that resumes from it; a server that does
/// not (Sail's does not yet) starts every connection at what it publishes next, which is why each
/// connection is announced to the reader. It ends when the task reading it is cancelled, and fails
/// only on an answer that will not change by asking again: a refusal, a status with none, or an
/// answer that is not an event stream.
public struct EventSource: AsyncSequence, Sendable {
    public typealias Element = ServerSentUpdate
    public typealias Failure = SailError

    /// The reconnection time until a stream sets its own.
    public static let defaultReconnectionTime = Duration.seconds(3)
    /// The longest a stream or a server may make this client wait before it asks again.
    public static let longestReconnectionTime = Duration.seconds(60)

    private let transport: any Transport
    private let request: HTTPRequest
    private let sleep: @Sendable (Duration) async throws -> Void

    /// Follows `request` over `transport`.
    public init(transport: any Transport, request: HTTPRequest) {
        self.init(transport: transport, request: request) { try await Task.sleep(for: $0) }
    }

    /// Follows `request` over `transport`, waiting between connections with `sleep`.
    package init(
        transport: any Transport, request: HTTPRequest, sleep: @escaping @Sendable (Duration) async throws -> Void
    ) {
        self.transport = transport
        self.request = request
        self.sleep = sleep
    }

    public func makeAsyncIterator() -> Iterator {
        Iterator(source: self)
    }

    public struct Iterator: AsyncIteratorProtocol {
        private let source: EventSource
        private var parser = EventStreamParser()
        private var pending: [ServerSentUpdate] = []
        private var body: AsyncThrowingStream<[UInt8], any Error>.AsyncIterator?
        private var hasConnected = false
        private var ended = false
        private var reconnectionTime = EventSource.defaultReconnectionTime
        private var askedToWait: Duration?

        init(source: EventSource) {
            self.source = source
        }

        /// The next update; nil once the reader is cancelled or the stream failed.
        public mutating func next() async throws(SailError) -> ServerSentUpdate? {
            while pending.isEmpty, !ended, !Task.isCancelled {
                guard var connection = body else {
                    guard await waitToReconnect() else { return nil }
                    do throws(SailError) {
                        try await connect()
                    } catch {
                        ended = true
                        throw error
                    }
                    continue
                }
                do {
                    guard let chunk = try await connection.next() else { throw CancellationError() }
                    pending += parser.feed(chunk).map(ServerSentUpdate.event)
                    reconnectionTime = Self.bounded(parser.retry ?? reconnectionTime)
                    body = parser.hasOverflowed ? nil : connection
                } catch {
                    body = nil
                }
            }
            return ended || Task.isCancelled ? nil : pending.removeFirst()
        }

        /// Waits before every connection but the first, for as long as the server last asked or
        /// else the reconnection time; false once the reader is cancelled.
        private mutating func waitToReconnect() async -> Bool {
            defer {
                hasConnected = true
                askedToWait = nil
            }
            guard hasConnected else { return true }
            do {
                try await source.sleep(askedToWait ?? reconnectionTime)
                return true
            } catch {
                return false
            }
        }

        /// Opens a connection and announces it, or announces why not when asking again may help.
        private mutating func connect() async throws(SailError) {
            var request = source.request
            request.headers["Accept"] = Self.eventStream
            if !parser.lastEventID.isEmpty {
                request.headers["Last-Event-ID"] = parser.lastEventID
            }
            let stream: HTTPStream
            do {
                stream = try await source.transport.open(request)
            } catch let .unreachable(reason) {
                pending.append(.connectFailed(.unreachable(reason)))
                return
            }
            switch stream.status {
            case 200:
                let contentType = stream.headers["content-type"]
                guard Self.mediaType(contentType) == Self.eventStream else {
                    throw .notAnEventStream(route: request.route, contentType: contentType)
                }
                parser = EventStreamParser(lastEventID: parser.lastEventID)
                body = stream.body.makeAsyncIterator()
                pending.append(.connected(lastEventID: parser.lastEventID))
            case 503:
                askedToWait = stream.headers["retry-after"].flatMap { Int($0) }.map { Self.bounded(.seconds($0)) }
                pending.append(.connectFailed(.unexpected(route: request.route, status: 503)))
            default:
                let body = await Self.collect(stream.body, upTo: 64 * 1024)
                throw SailJSON.refusal(HTTPResponse(status: stream.status, headers: stream.headers, body: body), route: request.route)
            }
        }

        private static let eventStream = "text/event-stream"

        /// The media type `contentType` names, without its parameters, in lower case.
        private static func mediaType(_ contentType: String?) -> String? {
            contentType?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased()
        }

        /// `wait`, never negative and never longer than a stream or a server may ask for.
        private static func bounded(_ wait: Duration) -> Duration {
            Swift.min(Swift.max(wait, .zero), EventSource.longestReconnectionTime)
        }

        /// A refusal's body, read to its end or `limit` bytes, whichever comes first.
        private static func collect(_ body: AsyncThrowingStream<[UInt8], any Error>, upTo limit: Int) async -> Data {
            var data = Data()
            do {
                for try await chunk in body {
                    data.append(contentsOf: chunk.prefix(limit - data.count))
                    if data.count >= limit { break }
                }
            } catch {
                return data
            }
            return data
        }
    }
}
