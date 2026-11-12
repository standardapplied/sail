import Foundation
import SailAPI

/// A Server-Sent Events stream that follows its server through every reconnect, as the WHATWG
/// `EventSource` does: when a connection ends or breaks, or the server cannot be reached or is at
/// its connection limit, it waits the reconnection time and asks again with the last event id it
/// saw. It ends when the task reading it is cancelled, and fails only on an answer that will not
/// change by asking again: a refusal, or a status with none.
public struct EventSource: AsyncSequence, Sendable {
    public typealias Element = ServerSentEvent
    public typealias Failure = SailError

    /// The reconnection time until a stream sets its own.
    public static let defaultReconnectionTime = Duration.seconds(3)

    private let transport: any Transport
    private let request: HTTPRequest
    private let sleep: @Sendable (Duration) async throws -> Void

    /// Follows `request` over `transport`, waiting between connections with `sleep`.
    public init(
        transport: any Transport, request: HTTPRequest,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
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
        private var pending: [ServerSentEvent] = []
        private var body: AsyncThrowingStream<[UInt8], any Error>.AsyncIterator?
        private var hasConnected = false
        private var ended = false
        private var reconnectionTime = EventSource.defaultReconnectionTime

        init(source: EventSource) {
            self.source = source
        }

        /// The next event; nil once the reader is cancelled or the stream failed.
        public mutating func next() async throws(SailError) -> ServerSentEvent? {
            while pending.isEmpty {
                guard !ended, !Task.isCancelled else { return nil }
                guard var connection = body else {
                    guard await waitToReconnect() else { return nil }
                    do throws(SailError) {
                        body = try await connect()
                    } catch {
                        ended = true
                        throw error
                    }
                    continue
                }
                do {
                    if let chunk = try await connection.next() {
                        pending += parser.feed(chunk)
                        reconnectionTime = parser.retry ?? reconnectionTime
                        body = connection
                    } else {
                        body = nil
                    }
                } catch {
                    body = nil
                }
            }
            return pending.removeFirst()
        }

        /// Waits the reconnection time before every connection but the first; false once the
        /// reader is cancelled.
        private mutating func waitToReconnect() async -> Bool {
            defer { hasConnected = true }
            guard hasConnected else { return true }
            do {
                try await source.sleep(reconnectionTime)
                return true
            } catch {
                return false
            }
        }

        private mutating func connect() async throws(SailError) -> AsyncThrowingStream<[UInt8], any Error>.AsyncIterator? {
            var request = source.request
            request.headers["Accept"] = "text/event-stream"
            if !parser.lastEventID.isEmpty {
                request.headers["Last-Event-ID"] = parser.lastEventID
            }
            let stream: HTTPStream
            do {
                stream = try await source.transport.open(request)
            } catch .unreachable {
                return nil
            }
            switch stream.status {
            case 200:
                parser = EventStreamParser(lastEventID: parser.lastEventID)
                return stream.body.makeAsyncIterator()
            case 503:
                return nil
            default:
                let body = await Self.collect(stream.body, upTo: 64 * 1024)
                throw SailJSON.refusal(HTTPResponse(status: stream.status, headers: stream.headers, body: body), route: request.route)
            }
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
