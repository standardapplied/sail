import Foundation

/// A terminal ticket the terminal host issued for the caller: presented within its life as the
/// `hello` token of one TCP connection to the host at `address`, it admits that connection once, for
/// what it was asked for and nothing else.
public struct TerminalTicket: Codable, Equatable, Sendable {
    public let ticket: String
    /// Where to present it: `host:port`, an IPv6 host in brackets.
    public let address: String
    public let expiresAt: Date

    public init(ticket: String, address: String, expiresAt: Date) {
        self.ticket = ticket
        self.address = address
        self.expiresAt = expiresAt
    }

    private enum CodingKeys: String, CodingKey {
        case ticket, address
        case expiresAt = "expires_at"
    }
}

extension Endpoint where Response == TerminalTicket {
    /// A ticket to open a session in `project`, and attach to it.
    public static func terminalTicket(open project: String, workspace: String = "") throws(SailError) -> Self {
        let asked = workspace.isEmpty
            ? ["action": "open", "project": project]
            : ["action": "open", "project": project, "workspace": workspace]
        return Self(.post, Route.terminalTickets, body: try SailJSON.encode(asked))
    }

    /// A ticket to attach to `session`.
    public static func terminalTicket(attach session: String) throws(SailError) -> Self {
        Self(.post, Route.terminalTickets, body: try SailJSON.encode(["action": "attach", "session": session]))
    }
}

/// The caller's terminal sessions in one workspace, newest first, as its runner's host lists them:
/// their own, or every one for an admin; live, or ended within the time the host keeps an ended one.
public struct RunnerSessions: Codable, Equatable, Sendable {
    public let sessions: [RunnerSession]

    public init(sessions: [RunnerSession]) {
        self.sessions = sessions
    }
}

/// One terminal session on the runner: whether it lives, how many clients it has, who holds its
/// keyboard (empty for no one), who opened it and when, what it runs (empty for the login shell),
/// and the host's words for why it ended, nil while it lives.
public struct RunnerSession: Codable, Equatable, Sendable {
    public let name: String
    public let live: Bool
    public let attached: Int
    public let writer: String
    public let opener: String
    public let command: [String]
    public let createdAt: Date
    public let endedReason: String?

    public init(
        name: String, live: Bool, attached: Int, writer: String, opener: String, command: [String], createdAt: Date,
        endedReason: String?
    ) {
        self.name = name
        self.live = live
        self.attached = attached
        self.writer = writer
        self.opener = opener
        self.command = command
        self.createdAt = createdAt
        self.endedReason = endedReason
    }

    private enum CodingKeys: String, CodingKey {
        case name, live, attached, writer, opener, command
        case createdAt = "created_at"
        case endedReason = "ended_reason"
    }
}

extension Endpoint where Response == RunnerSessions {
    /// The caller's terminal sessions in workspace `name` of `project`, those the runner would let
    /// the caller attach to (none of another person's); not found when the caller may not read the
    /// project, or the runner holds no such workspace; refused for a credential that stands for no
    /// person, as a ticket is.
    public static func sessions(workspace name: String, project: String) -> Self {
        Self(.get, Route.workspace(name, project: project) + ["sessions"])
    }
}
