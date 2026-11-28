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

    /// A ticket to read the files of workspace `workspace` of `project`; refused `workspace_stopped`
    /// when it is stopped, which the ticket never starts.
    public static func terminalTicket(files project: String, workspace: String) throws(SailError) -> Self {
        Self(
            .post, Route.terminalTickets,
            body: try SailJSON.encode(["action": "files", "project": project, "workspace": workspace])
        )
    }

    /// A ticket to attach to `session`.
    public static func terminalTicket(attach session: String) throws(SailError) -> Self {
        Self(.post, Route.terminalTickets, body: try SailJSON.encode(["action": "attach", "session": session]))
    }
}

/// The caller's sessions in one workspace, newest first, as the server lists them, each row by its
/// `form`: the terminals its runner's host holds (their own, or every one for an admin) and the
/// caller's own chats; live, or ended within the time each is kept. A row of a form this app does
/// not know is kept apart, by its form alone.
public struct RunnerSessions: Codable, Equatable, Sendable {
    public let sessions: [RunnerSession]
    public let chats: [ChatRow]
    public let otherForms: [String]

    public init(sessions: [RunnerSession], chats: [ChatRow] = [], otherForms: [String] = []) {
        self.sessions = sessions
        self.chats = chats
        self.otherForms = otherForms
    }

    public init(from decoder: any Decoder) throws {
        let rows = try decoder.container(keyedBy: CodingKeys.self).decode([Row].self, forKey: .sessions)
        sessions = rows.compactMap { if case let .terminal(session) = $0 { session } else { nil } }
        chats = rows.compactMap { if case let .chat(chat) = $0 { chat } else { nil } }
        otherForms = rows.compactMap { if case let .other(form) = $0 { form } else { nil } }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(sessions.map(Row.terminal) + chats.map(Row.chat) + otherForms.map(Row.other), forKey: .sessions)
    }

    private enum CodingKeys: String, CodingKey { case sessions }

    private enum Row: Codable {
        case terminal(RunnerSession)
        case chat(ChatRow)
        case other(String)

        private enum Key: String, CodingKey { case form }

        init(from decoder: any Decoder) throws {
            switch try decoder.container(keyedBy: Key.self).decode(String.self, forKey: .form) {
            case "terminal": self = .terminal(try RunnerSession(from: decoder))
            case "chat": self = .chat(try ChatRow(from: decoder))
            case let form: self = .other(form)
            }
        }

        func encode(to encoder: any Encoder) throws {
            var form = encoder.container(keyedBy: Key.self)
            switch self {
            case let .terminal(session):
                try form.encode("terminal", forKey: .form)
                try session.encode(to: encoder)
            case let .chat(chat):
                try form.encode("chat", forKey: .form)
                try chat.encode(to: encoder)
            case let .other(other):
                try form.encode(other, forKey: .form)
            }
        }
    }

    /// The live sessions `handle` opened, in the listing's order (newest first): what a person's
    /// terminals in a workspace are, for its count, its Stop and Delete questions and its Open.
    public func live(for handle: String) -> [RunnerSession] {
        sessions.filter { $0.live && $0.opener == handle }
    }
}

/// One terminal session on the runner: whether it lives, how many clients it has, who holds its
/// keyboard (empty for no one), who opened it and when, what it runs (`["bash", "-l"]` for the login
/// shell, the harness's own command for an agent), the harness it runs (nil for a command), and the
/// host's words for why it ended, nil while it lives.
public struct RunnerSession: Codable, Equatable, Sendable {
    public let name: String
    public let live: Bool
    public let attached: Int
    public let writer: String
    public let opener: String
    public let command: [String]
    public let agent: RunnerAgent?
    public let createdAt: Date
    public let endedReason: String?

    public init(
        name: String, live: Bool, attached: Int, writer: String, opener: String, command: [String],
        agent: RunnerAgent? = nil, createdAt: Date, endedReason: String?
    ) {
        self.name = name
        self.live = live
        self.attached = attached
        self.writer = writer
        self.opener = opener
        self.command = command
        self.agent = agent
        self.createdAt = createdAt
        self.endedReason = endedReason
    }

    private enum CodingKeys: String, CodingKey {
        case name, live, attached, writer, opener, command, agent
        case createdAt = "created_at"
        case endedReason = "ended_reason"
    }
}

/// The harness a runner session runs, by the host's name for it, in one of its own modes, and
/// whether it was signed in with its person's stored login (`stored`) or signs in inside (`inside`).
public struct RunnerAgent: Codable, Equatable, Sendable {
    public let harness: String
    /// The harness's name as a person reads it ("Claude Code"), which titles its tab.
    public let display: String
    public let mode: String
    public let login: String

    public init(harness: String, display: String, mode: String, login: String) {
        self.harness = harness
        self.display = display
        self.mode = mode
        self.login = login
    }
}

extension Endpoint where Response == RunnerSessions {
    /// The caller's sessions in workspace `name` of `project`: the terminals the runner would let
    /// the caller attach to (none of another person's) and the caller's chats; not found when the
    /// caller may not read the project, or the runner holds no such workspace; refused for a
    /// credential that stands for no person, as a ticket is.
    public static func sessions(workspace name: String, project: String) -> Self {
        Self(.get, Route.workspace(name, project: project) + ["sessions"])
    }
}
