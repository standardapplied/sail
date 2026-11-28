import Foundation

/// The types of item a chat's log holds, in the server's order: what Sail itself logs, then what
/// the harness said.
public enum ChatItemType: String, CaseIterable, Sendable {
    case message, delivered, told, answered
    case modeSet = "mode_set"
    case session
    case modeChanged = "mode_changed"
    case text, tool
    case toolDone = "tool_done"
    case permission, question, plan, withdrawn, retrying, compacted, interrupted, refused, unreadable
    case selfStarted = "self_started"
    case failed
    case turnEnded = "turn_ended"
}

/// What a tool call works on, by the `type` its detail names.
public enum ChatDetailType: String, CaseIterable, Sendable {
    case command, edit, read, search, fetch, other
}

/// What a tool call does, as a person reads its row.
public enum ChatToolKind: String, CaseIterable, Sendable {
    case command, edit, read, search, web, mcp, agent, other
}

/// How a file changes.
public enum ChatChangeKind: String, CaseIterable, Sendable {
    case change, add, delete, move
}

/// What a person decides on a request their agent made.
public enum ChatDecision: String, CaseIterable, Codable, Sendable {
    case allow
    case allowAlways = "allow_always"
    case deny, answer
}

/// A page of a chat's log: the items after the seq asked from, and whether the chat has ended.
public struct ChatPage: Decodable, Equatable, Sendable {
    public let items: [ChatItem]
    public let ended: Bool
}

/// One entry of a chat's log: its place in the log, when it was logged, and what it says. Every
/// field a known type writes is read, and one missing fails the page naming the item's seq; a type
/// this app does not know is ``Body/unknown(_:)``, so a newer server never stops a reader.
public struct ChatItem: Decodable, Equatable, Sendable {
    public let seq: Int64
    public let at: Date
    public let body: Body

    public enum Body: Equatable, Sendable {
        case message(Message)
        case delivered(Delivered)
        case told(Told)
        case answered(Answered)
        case modeSet(ModeSet)
        case session(Session)
        case modeChanged(ModeChanged)
        case text(Text)
        case tool(Tool)
        case toolDone(ToolDone)
        case permission(Permission)
        case question(Question)
        case plan(Plan)
        case withdrawn(Withdrawn)
        case retrying(Retrying)
        case compacted(Compacted)
        case interrupted
        case refused(Refused)
        case unreadable(Unreadable)
        case selfStarted
        case failed(Failed)
        case turnEnded(TurnEnded)
        case unknown(String)
    }

    private enum CodingKeys: String, CodingKey {
        case seq, at, type
    }

    public init(from decoder: any Decoder) throws {
        let envelope = try decoder.container(keyedBy: CodingKeys.self)
        seq = try envelope.decode(Int64.self, forKey: .seq)
        at = try envelope.decode(Date.self, forKey: .at)
        let type = try envelope.decode(String.self, forKey: .type)
        do {
            body = try Self.body(type, from: decoder)
        } catch let error as DecodingError {
            guard let said = error.said else { throw error }
            throw DecodingError.dataCorrupted(.init(codingPath: said.path, debugDescription: "\(said.reason), in the item at seq \(seq)"))
        }
    }

    private static func body(_ type: String, from decoder: any Decoder) throws -> Body {
        guard let known = ChatItemType(rawValue: type) else { return .unknown(type) }
        return switch known {
        case .message: .message(try Message(from: decoder))
        case .delivered: .delivered(try Delivered(from: decoder))
        case .told: .told(try Told(from: decoder))
        case .answered: .answered(try Answered(from: decoder))
        case .modeSet: .modeSet(try ModeSet(from: decoder))
        case .session: .session(try Session(from: decoder))
        case .modeChanged: .modeChanged(try ModeChanged(from: decoder))
        case .text: .text(try Text(from: decoder))
        case .tool: .tool(try Tool(from: decoder))
        case .toolDone: .toolDone(try ToolDone(from: decoder))
        case .permission: .permission(try Permission(from: decoder))
        case .question: .question(try Question(from: decoder))
        case .plan: .plan(try Plan(from: decoder))
        case .withdrawn: .withdrawn(try Withdrawn(from: decoder))
        case .retrying: .retrying(try Retrying(from: decoder))
        case .compacted: .compacted(try Compacted(from: decoder))
        case .interrupted: .interrupted
        case .refused: .refused(try Refused(from: decoder))
        case .unreadable: .unreadable(try Unreadable(from: decoder))
        case .selfStarted: .selfStarted
        case .failed: .failed(try Failed(from: decoder))
        case .turnEnded: .turnEnded(try TurnEnded(from: decoder))
        }
    }
}

extension ChatItem {
    /// What the person said; `queued` when said while a turn was open, so it waits for its end.
    public struct Message: Decodable, Equatable, Sendable {
        public let text: String
        public let queued: Bool
    }

    /// The person's message logged as `message`, which waited for a turn's end, went in.
    public struct Delivered: Decodable, Equatable, Sendable {
        public let message: Int64
    }

    /// What Sail tells the person of the chat, in its own words.
    public struct Told: Decodable, Equatable, Sendable {
        public let words: String
    }

    /// `by` answered the request `request` with `decision`: a denial's own words (`message`), or a
    /// question's `answers`.
    public struct Answered: Decodable, Equatable, Sendable {
        public let request: String
        public let decision: Open<ChatDecision>
        public let message: String?
        public let answers: [Answer]?
        public let by: String
    }

    /// An answer to one question, named by its text: labels of its choices, or the person's own words.
    public struct Answer: Decodable, Equatable, Sendable {
        public let question: String
        public let chosen: [String]
        public let own: String?
    }

    /// `by` put the chat in `mode`, asked of its process as `request`, or, with none to ask, kept
    /// for the next one.
    public struct ModeSet: Decodable, Equatable, Sendable {
        public let mode: String
        public let request: String?
        public let by: String
    }

    /// The conversation took a turn: its model and mode where the harness says, and the commands and
    /// skills a person can name in it.
    public struct Session: Decodable, Equatable, Sendable {
        public let model: String?
        public let mode: String?
        public let commands: [String]
        public let skills: [String]
        public let clipped: Bool
    }

    /// The agent is in `mode` now, whoever changed it.
    public struct ModeChanged: Decodable, Equatable, Sendable {
        public let mode: String
        public let clipped: Bool
    }

    /// The agent's text, appended to what it said before or starting a new paragraph; `under` the
    /// tool row of the subagent saying it.
    public struct Text: Decodable, Equatable, Sendable {
        public let under: String?
        public let text: String
        public let newParagraph: Bool
        public let clipped: Bool

        private enum CodingKeys: String, CodingKey {
            case under, text, clipped
            case newParagraph = "new_paragraph"
        }
    }

    /// A tool call began: its row in the plain words of what it does, and what it works on.
    public struct Tool: Decodable, Equatable, Sendable {
        public let under: String?
        public let id: String
        public let kind: Open<ChatToolKind>
        public let words: String
        public let detail: ChatDetail
        public let clipped: Bool
    }

    /// The tool call `id` came back with `output`, which says why when it `failed`.
    public struct ToolDone: Decodable, Equatable, Sendable {
        public let under: String?
        public let id: String
        public let output: String
        public let failed: Bool
        public let clipped: Bool
    }

    /// The agent, or its subagent `agent`, asks to make the call `call`: in its own words, why where
    /// the harness says, what the call works on, and the ways to allow it always.
    public struct Permission: Decodable, Equatable, Sendable {
        public let id: String
        public let call: String
        public let agent: String?
        public let kind: Open<ChatToolKind>
        public let words: String
        public let reason: String?
        public let detail: ChatDetail
        public let always: [Always]
        public let clipped: Bool
    }

    /// One way to allow a call always: everything choosing it changes, a sentence each.
    public struct Always: Decodable, Equatable, Sendable {
        public let words: [String]
    }

    /// The agent asks a person to choose, one answer to each question.
    public struct Question: Decodable, Equatable, Sendable {
        public let id: String
        public let call: String
        public let questions: [Asked]
    }

    /// One question: its text, its short header, and its choices, one or several.
    public struct Asked: Decodable, Equatable, Sendable {
        public let question: String?
        public let header: String?
        public let choices: [Choice]
        public let multiSelect: Bool
        public let clipped: Bool

        private enum CodingKeys: String, CodingKey {
            case question, header, choices, clipped
            case multiSelect = "multi_select"
        }
    }

    /// One choice of a question, and what it means.
    public struct Choice: Decodable, Equatable, Sendable {
        public let label: String?
        public let about: String?
        public let clipped: Bool
    }

    /// The agent asks a person to approve its plan before it changes anything.
    public struct Plan: Decodable, Equatable, Sendable {
        public let id: String
        public let call: String
        public let plan: String
        public let clipped: Bool
    }

    /// The request `id` needs no answer now.
    public struct Withdrawn: Decodable, Equatable, Sendable {
        public let id: String
    }

    /// A request to the model failed with `error`; try `attempt` of `attempts`, after `delayMs`.
    public struct Retrying: Decodable, Equatable, Sendable {
        public let attempt: Int
        public let attempts: Int
        public let delayMs: Int64
        public let error: String?
        public let clipped: Bool

        private enum CodingKeys: String, CodingKey {
            case attempt, attempts, error, clipped
            case delayMs = "delay_ms"
        }
    }

    /// The conversation was compacted from `tokensBefore` tokens to `tokensAfter`.
    public struct Compacted: Decodable, Equatable, Sendable {
        public let trigger: String?
        public let tokensBefore: Int64
        public let tokensAfter: Int64
        public let clipped: Bool

        private enum CodingKeys: String, CodingKey {
            case trigger, clipped
            case tokensBefore = "tokens_before"
            case tokensAfter = "tokens_after"
        }
    }

    /// The harness refused what the host asked of it (`id`, where it names one), in its own words.
    public struct Refused: Decodable, Equatable, Sendable {
        public let id: String?
        public let words: String
        public let clipped: Bool
    }

    /// The agent asked something no person could be shown whole, so none was asked.
    public struct Unreadable: Decodable, Equatable, Sendable {
        public let tooLarge: Bool
        public let stopped: Bool

        private enum CodingKeys: String, CodingKey {
            case stopped
            case tooLarge = "too_large"
        }
    }

    /// The turn failed, in the harness's words, and what came of the login when that is why.
    public struct Failed: Decodable, Equatable, Sendable {
        public let words: String
        public let login: ChatLoginOutcome?
        public let clipped: Bool
    }

    /// A turn ended: its cost at API prices where the harness says, its tokens and how long it took.
    public struct TurnEnded: Decodable, Equatable, Sendable {
        public let costUsd: Double?
        public let tokensIn: Int64
        public let tokensOut: Int64
        public let durationMs: Int64

        private enum CodingKeys: String, CodingKey {
            case costUsd = "cost_usd"
            case tokensIn = "tokens_in"
            case tokensOut = "tokens_out"
            case durationMs = "duration_ms"
        }
    }
}

/// What a tool call works on, by its own `type`; a type this app does not know kept by its name.
public enum ChatDetail: Decodable, Equatable, Sendable {
    case command(Command)
    case edit([Change])
    case read(Read)
    case search(Search)
    case fetch(Fetch)
    case other(Other)
    case unknown(String)

    /// A command line run in the workspace's shell, in the `background` when it outlives its turn.
    public struct Command: Decodable, Equatable, Sendable {
        public let line: String?
        public let background: Bool
        public let clipped: Bool
    }

    /// One file's change, of `kind`, moved from `from` when it moves, from `before` to `after`.
    public struct Change: Decodable, Equatable, Sendable {
        public let kind: Open<ChatChangeKind>
        public let path: String?
        public let from: String?
        public let before: String?
        public let after: String?
        public let everywhere: Bool
        public let clipped: Bool
    }

    public struct Read: Decodable, Equatable, Sendable {
        public let path: String?
        public let clipped: Bool
    }

    /// A search for `what`, in `where` when it says.
    public struct Search: Decodable, Equatable, Sendable {
        public let what: String?
        public let `where`: String?
        public let clipped: Bool
    }

    public struct Fetch: Decodable, Equatable, Sendable {
        public let address: String?
        public let clipped: Bool
    }

    /// Any other call: the tool's name and its input as text.
    public struct Other: Decodable, Equatable, Sendable {
        public let name: String
        public let input: String
        public let clipped: Bool
    }

    private enum CodingKeys: String, CodingKey {
        case type, changes
    }

    public init(from decoder: any Decoder) throws {
        let detail = try decoder.container(keyedBy: CodingKeys.self)
        let type = try detail.decode(String.self, forKey: .type)
        guard let known = ChatDetailType(rawValue: type) else {
            self = .unknown(type)
            return
        }
        self = switch known {
        case .command: .command(try Command(from: decoder))
        case .edit: .edit(try detail.decode([Change].self, forKey: .changes))
        case .read: .read(try Read(from: decoder))
        case .search: .search(try Search(from: decoder))
        case .fetch: .fetch(try Fetch(from: decoder))
        case .other: .other(try Other(from: decoder))
        }
    }
}

/// What came of a login when a turn failed for it: refused, out of quota until `resets` where the
/// harness said, or never delivered; another kept by its name.
public enum ChatLoginOutcome: Decodable, Equatable, Sendable {
    case refused
    case limited(resets: String?)
    case undelivered
    case unknown(String)

    private enum CodingKeys: String, CodingKey {
        case type, resets
    }

    public init(from decoder: any Decoder) throws {
        let outcome = try decoder.container(keyedBy: CodingKeys.self)
        self = switch try outcome.decode(String.self, forKey: .type) {
        case "refused": .refused
        case "limited": .limited(resets: try outcome.decodeIfPresent(String.self, forKey: .resets))
        case "undelivered": .undelivered
        case let type: .unknown(type)
        }
    }
}
