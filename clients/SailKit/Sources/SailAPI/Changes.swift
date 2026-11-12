import Foundation

/// The rows the change feed serves: what a client keeps of the board and its conversations.
public enum ChangeType: String, CaseIterable, Sendable {
    case spec, room, message
}

/// One row's change after a position, as `GET /v1/changes` answers it and the event stream sends
/// it: the order of the row's revision (`seq`), the row's type and id, and what became of the row.
/// A row changed many times since is told once a page, as it is now, at its newest seq. A row is
/// told as its own record holds it: the fields a list derives from other rows are absent, and
/// ``HeldRows`` derives them. A change to one row never tells another.
///
/// A client follows the feed only from the position of the reads it holds, never an older one, and
/// applies each change in the order it is told; a change whose `seq` is not newer than the last it
/// applied to the row is one it was already told.
public struct Change: Decodable, Equatable, Sendable {
    public let seq: Int64
    public let type: Open<ChangeType>
    public let id: String
    public let fate: Fate

    /// What a change tells of its row: the row as its own record holds it; that it was deleted,
    /// told with no content; or that it was erased for good, its id never used again. A row that
    /// leaves the reader's projects is not told at all: the reader is told to resync.
    public enum Fate: Equatable, Sendable {
        case spec(Spec)
        case room(Room)
        case message(Message)
        /// A row of a type this client does not know, of which it keeps nothing.
        case unknown
        case gone
        case erased
    }

    public init(seq: Int64, type: Open<ChangeType>, id: String, fate: Fate) {
        self.seq = seq
        self.type = type
        self.id = id
        self.fate = fate
    }

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        seq = try fields.decode(Int64.self, forKey: .seq)
        type = try fields.decode(Open<ChangeType>.self, forKey: .type)
        id = try fields.decode(String.self, forKey: .id)
        if try fields.decodeIfPresent(Bool.self, forKey: .erased) == true {
            fate = .erased
        } else if try fields.decodeIfPresent(Bool.self, forKey: .gone) == true {
            fate = .gone
        } else {
            switch type {
            case .known(.spec): fate = .spec(try fields.decode(Spec.self, forKey: .row))
            case .known(.room): fate = .room(try fields.decode(Room.self, forKey: .row))
            case .known(.message): fate = .message(try fields.decode(Message.self, forKey: .row))
            case .unknown: fate = .unknown
            }
        }
    }

    private enum CodingKeys: String, CodingKey {
        case seq, type, id, row, gone, erased
    }
}

/// One page of the change feed: its changes in order, the position to ask from next, and whether
/// more changes follow it. A position is opaque: it names the server's database and the entry it
/// stands on, and the client only hands it back.
public struct ChangePage: Decodable, Equatable, Sendable {
    public let changes: [Change]
    public let next: String
    public let more: Bool

    public init(changes: [Change], next: String, more: Bool) {
        self.changes = changes
        self.next = next
        self.more = more
    }
}

extension Endpoint where Response == ChangePage {
    /// The changes after `since` the caller may read, `limit` of them at most (the server allows 1
    /// through 500, 100 when none is named), of `types`, every fed type when nil. A position the
    /// server can no longer answer from is refused as `resync_required`: read the rows afresh, and
    /// follow the feed from the position those reads answer (``HTTPResponse/position``). A
    /// position from another database, from before the server's database was restored, or from an
    /// older client is refused the same way.
    public static func changes(since: String, types: [ChangeType]? = nil, limit: Int? = nil) -> Self {
        Self(.get, Route.changes, query: [
            ("since", since),
            ("types", types.map { $0.map(\.rawValue).joined(separator: ",") }),
            ("limit", limit.map(String.init)),
        ])
    }
}

extension HTTPResponse {
    /// The position the answer was read at, which every list and detail of a fed row carries:
    /// reading the change feed from it misses nothing the answer does not hold.
    public var position: String? {
        headers.first { $0.key.lowercased() == "seq" }.map(\.value).flatMap { $0.isEmpty ? nil : $0 }
    }
}
