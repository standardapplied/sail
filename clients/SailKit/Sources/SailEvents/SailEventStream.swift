import Foundation
import SailAPI

/// Which events a stream carries; a nil field narrows nothing. A room's stream opens with where its
/// live member stands.
public struct EventFilter: Equatable, Sendable {
    public var project: String?
    public var room: String?
    public var types: [EventType]?

    public init(project: String? = nil, room: String? = nil, types: [EventType]? = nil) {
        self.project = project
        self.room = room
        self.types = types
    }
}

/// What the server's live events tell a reader.
public enum StreamUpdate: Equatable, Sendable {
    case event(Event)
    /// The stream is connected and carries what the server publishes from now on. What it
    /// published while no connection was open is not replayed, so a reader that shows state
    /// fetches it again now (``SailAPI/Endpoint`` for the lists, `recentEvents` for the feed);
    /// `after` is the id of the last event this stream delivered before, nil when there was none.
    case connected(after: Int64?)
    /// The server could not be reached, or is at its connection limit, and is being asked again.
    case connectFailed(SailError)
    /// The server sent an event this client cannot read; the stream goes on.
    case unreadable(Undecodable)
}

/// The server's live events as a caller reads them, across reconnects: each connection is
/// announced, since events published between two connections are not sent again, and so is each
/// failed attempt to connect. It ends with the server's refusal when the server ends it for one.
public struct SailEventStream: AsyncSequence, Sendable {
    public typealias Element = StreamUpdate
    public typealias Failure = SailError

    private let source: EventSource
    private let route: String

    init(source: EventSource, route: String) {
        self.source = source
        self.route = route
    }

    public func makeAsyncIterator() -> Iterator {
        Iterator(updates: source.makeAsyncIterator(), route: route)
    }

    public struct Iterator: AsyncIteratorProtocol {
        private var updates: EventSource.Iterator
        private let route: String
        private var ended = false

        init(updates: EventSource.Iterator, route: String) {
            self.updates = updates
            self.route = route
        }

        /// The next update; nil once the stream has ended, by a cancelled reader or an error.
        public mutating func next() async throws(SailError) -> StreamUpdate? {
            guard !ended else { return nil }
            do throws(SailError) {
                switch try await updates.next() {
                case nil: return nil
                case let .connected(lastEventID): return .connected(after: Int64(lastEventID))
                case let .connectFailed(error): return .connectFailed(error)
                case let .event(sent): return try read(sent)
                }
            } catch {
                ended = true
                throw error
            }
        }

        /// The event `sent` holds, or the refusal the server ended the stream with.
        private func read(_ sent: ServerSentEvent) throws(SailError) -> StreamUpdate {
            let data = Data(sent.data.utf8)
            do throws(SailError) {
                guard sent.type != "error" else {
                    throw SailError.refused(try SailJSON.decode(Refusal.self, from: data, route: route))
                }
                return .event(try SailJSON.decode(Event.self, from: data, route: route))
            } catch {
                guard case let .undecodable(unreadable) = error else { throw error }
                return .unreadable(unreadable)
            }
        }
    }
}

extension SailClient {
    /// The events `filter` names, as this caller may read them.
    public func events(_ filter: EventFilter = EventFilter()) -> SailEventStream {
        events(filter) { try await Task.sleep(for: $0) }
    }

    /// The events `filter` names, waiting between reconnects with `sleep`.
    package func events(
        _ filter: EventFilter, sleep: @escaping @Sendable (Duration) async throws -> Void
    ) -> SailEventStream {
        let request = authorized(HTTPRequest(
            .get, Route.eventStream,
            query: [
                ("project", filter.project), ("room", filter.room),
                ("type", filter.types.map { $0.map(\.rawValue).joined(separator: ",") }),
            ]
        ))
        return SailEventStream(source: EventSource(transport: transport, request: request, sleep: sleep), route: request.route)
    }
}
