import Foundation

/// The rows a client keeps of the board and its conversations, brought up to date by the change
/// feed, with the fields a list derives from other rows derived from the rows held. The feed sends
/// a row as its own journal entry holds it, so a fed room or spec carries none of them:
///
/// - a room's `specIDs`: the held specs whose room it is;
/// - a room's `lastActivityAt`: the newest message held of it;
/// - a room's `needsReply` and `questionMessageID`: its latest question an agent (an author with a
///   `/`) asked of the messages held that no person spoke after;
/// - a spec's `wake` and `engagement`: its room row's wake and first member;
/// - a spec's `needsReply` and `questionMessageID`: its room's open question;
/// - a spec's `lastActivityAt`: the later of its `updatedAt` and the newest message held of the
///   conversation named as the spec is.
///
/// A reader holds the messages it may read, and the list derives from those alone. Deriving without
/// the box's roster has known limits, where a client's copy can disagree with the list:
///
/// - the server counts a question answered only by a voice that steers its room; a client counts
///   any person's message, so a reply from someone who does not steer the room clears its
///   `needsReply` here while the list still shows it;
/// - a client that rebuilt from the lists holds only the newest page of a room's messages, so a
///   question older than that page, with no person's message since, is open on the list and not
///   here: the derivation needs the room's messages back to its latest agent question;
/// - a reader that hears only a room's steering voices (an agent) is listed with the time of the
///   room's newest message of any voice, which it is never sent, and is never told the erasure of
///   a message it was told, which it keeps.
public struct HeldRows: Sendable {
    public private(set) var specs: [String: Spec] = [:]
    public private(set) var rooms: [String: Room] = [:]
    public private(set) var messages: [String: Message] = [:]

    public init(specs: [Spec] = [], rooms: [Room] = [], messages: [Message] = []) {
        for spec in specs { self.specs[spec.id] = spec }
        for room in rooms { self.rooms[room.id] = room }
        for message in messages { self.messages[message.id] = message }
    }

    /// Applies `change` as the feed tells it: a row replaces the one held; `gone` and `erased` drop
    /// it.
    public mutating func apply(_ change: Change) {
        switch change.fate {
        case let .spec(spec): specs[spec.id] = spec
        case let .room(room): rooms[room.id] = room
        case let .message(message): messages[message.id] = message
        case .unknown: break
        case .gone, .erased:
            specs[change.id] = nil
            rooms[change.id] = nil
            messages[change.id] = nil
        }
    }

    /// The room `id` with the fields its list derives from other rows derived from those held.
    public func room(_ id: String) -> Room? {
        guard let room = rooms[id] else { return nil }
        let question = openQuestion(in: id)
        return Room(
            id: room.id, project: room.project, title: room.title, assignee: room.assignee, wake: room.wake,
            effectiveWake: room.effectiveWake, members: room.members,
            specIDs: specs.values.filter { $0.roomID == id }.map(\.id).sorted(),
            createdBy: room.createdBy, createdAt: room.createdAt, updatedAt: room.updatedAt,
            updatedBy: room.updatedBy, lastActivityAt: latest(in: id), needsReply: question != nil,
            questionMessageID: question
        )
    }

    /// The spec `id` with the fields its list derives from other rows derived from those held.
    public func spec(_ id: String) -> Spec? {
        guard let spec = specs[id] else { return nil }
        let room = spec.roomID.flatMap { rooms[$0] }
        let question = room.flatMap { openQuestion(in: $0.id) }
        let said = latest(in: spec.id)
        return Spec(
            id: spec.id, project: spec.project, title: spec.title, status: spec.status, assignee: spec.assignee,
            agent: spec.agent, model: spec.model, reasoningEffort: spec.reasoningEffort, branch: spec.branch,
            priority: spec.priority, dependsOn: spec.dependsOn, repos: spec.repos, wake: room?.wake,
            engagement: room?.members.first, createdBy: spec.createdBy, createdAt: spec.createdAt,
            updatedAt: spec.updatedAt, updatedBy: spec.updatedBy, roomID: spec.roomID,
            lastActivityAt: said.map { max($0, spec.updatedAt) } ?? spec.updatedAt,
            needsReply: question != nil, questionMessageID: question
        )
    }

    private func latest(in room: String) -> Date? {
        messages.values.filter { $0.specID == room }.map(\.createdAt).max()
    }

    private func openQuestion(in room: String) -> String? {
        var open: String?
        for message in messages.values.filter({ $0.specID == room }).sorted(by: { $0.id < $1.id }) {
            if message.isQuestion && message.author.contains("/") {
                open = message.id
            } else if !message.author.contains("/") && message.author != "sail" {
                open = nil
            }
        }
        return open
    }
}
