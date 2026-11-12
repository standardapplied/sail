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
    /// A row changed: the change feed's change, at the position a reconnect resumes after. Each
    /// is sent once, in order, from where the stream resumed.
    case change(Change)
    /// The server cannot resume from where this stream asked, or what the caller may read changed:
    /// what the reader holds may be stale, so it reads the rows afresh and follows the feed from the
    /// position those reads answer. A stream told so as it connects goes on with the changes after
    /// `after`; one told so later is ended by the server, and reconnects.
    case resync(after: String)
    /// The stream is connected. A stream that resumed (`after` is the position of the last change
    /// it delivered, nil when there was none) is sent every change since, then live ones; the bus's
    /// events published while no connection was open are not sent again.
    case connected(after: String?)
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
                case let .connected(lastEventID): return .connected(after: lastEventID.isEmpty ? nil : lastEventID)
                case let .connectFailed(error): return .connectFailed(error)
                case let .event(sent): return try read(sent)
                }
            } catch {
                ended = true
                throw error
            }
        }

        /// The event `sent` holds: a bus event, a row change, a resync, or the refusal the server
        /// ended the stream with.
        private func read(_ sent: ServerSentEvent) throws(SailError) -> StreamUpdate {
            let data = Data(sent.data.utf8)
            do throws(SailError) {
                switch sent.type {
                case "error": throw SailError.refused(try SailJSON.decode(Refusal.self, from: data, route: route))
                case "change": return .change(try SailJSON.decode(Change.self, from: data, route: route))
                case "resync": return .resync(after: try SailJSON.decode(Resync.self, from: data, route: route).position)
                case "message": return .event(try SailJSON.decode(Event.self, from: data, route: route))
                default:
                    return .unreadable(Undecodable(
                        route: route, field: "(event)", reason: "an event named '\(sent.type)' is not one this client reads"
                    ))
                }
            } catch {
                guard case let .undecodable(unreadable) = error else { throw error }
                return .unreadable(unreadable)
            }
        }
    }
}

/// Where a stream told to resync reads afresh from.
private struct Resync: Decodable {
    let position: String
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
