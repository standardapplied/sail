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

/// The server's live events as a caller reads them, across reconnects. It ends with the server's
/// refusal when the server ends it for one, and with an error naming the field when an event
/// cannot be read.
public struct SailEventStream: AsyncSequence, Sendable {
    public typealias Element = Event
    public typealias Failure = SailError

    private let source: EventSource
    private let route: String

    init(source: EventSource, route: String) {
        self.source = source
        self.route = route
    }

    public func makeAsyncIterator() -> Iterator {
        Iterator(events: source.makeAsyncIterator(), route: route)
    }

    public struct Iterator: AsyncIteratorProtocol {
        private var events: EventSource.Iterator
        private let route: String
        private var ended = false

        init(events: EventSource.Iterator, route: String) {
            self.events = events
            self.route = route
        }

        /// The next event; nil once the stream has ended, by a cancelled reader or an error.
        public mutating func next() async throws(SailError) -> Event? {
            guard !ended else { return nil }
            do throws(SailError) {
                guard let event = try await events.next() else { return nil }
                let data = Data(event.data.utf8)
                guard event.type != "error" else {
                    throw .refused(try SailJSON.decode(Refusal.self, from: data, route: route))
                }
                return try SailJSON.decode(Event.self, from: data, route: route)
            } catch {
                ended = true
                throw error
            }
        }
    }
}

extension SailClient {
    /// The events `filter` names, as this caller may read them, waiting between reconnects with
    /// `sleep`.
    public func events(
        _ filter: EventFilter = EventFilter(),
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
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
