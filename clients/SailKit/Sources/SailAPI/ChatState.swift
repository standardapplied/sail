import Foundation

/// Where a chat stands, read from its log alone: nothing sent yet, a turn running, a request of
/// its agent unanswered, the turn ended (or stopped, or cut) and the person's to take, its last
/// turn failed, or ended for good.
public enum ChatState: String, CaseIterable, Sendable {
    case idle, working
    case needsYou = "needs_you"
    case yourTurn = "your_turn"
    case error, ended
}

/// One of the caller's chats in a workspace as its sessions listing names it: its id, whether it
/// is open, where it stands, who opened it with which harness and when, and its title (the first
/// line of its first message), nil before one.
public struct ChatRow: Codable, Equatable, Sendable {
    public let name: String
    public let live: Bool
    public let state: Open<ChatState>
    public let opener: String
    public let agent: RunnerAgent
    public let createdAt: Date
    public let title: String?

    public init(name: String, live: Bool, state: Open<ChatState>, opener: String, agent: RunnerAgent, createdAt: Date, title: String? = nil) {
        self.name = name
        self.live = live
        self.state = state
        self.opener = opener
        self.agent = agent
        self.createdAt = createdAt
        self.title = title
    }

    private enum CodingKeys: String, CodingKey {
        case name, live, state, opener, agent, title
        case createdAt = "created_at"
    }
}
