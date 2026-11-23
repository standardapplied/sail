import Foundation

/// What can start in a workspace for the person asking: whose it is and whether it runs (a start
/// starts a stopped one), and either why no agent starts in it (`refusal`, with nothing offered) or
/// each harness the runner knows. Every word is the server's; a client branches on no harness's or
/// mode's name.
public struct WorkspaceAgents: Codable, Equatable, Sendable {
    public let project: String
    public let workspace: String
    /// Whose it is; nil for no one's.
    public let owner: String?
    public let running: Bool
    public let refusal: Refusal?
    public let offered: [AgentOffer]
}

/// One harness as a workspace offers it: its modes in its own words, the first the one it starts in
/// until a person chooses (`modeDefault`), and why it has no others (`note`); the login it starts
/// signed in with, or why there is none (`loginRefusal`, whose remedy stores one or chooses one),
/// and the kinds a person stores one of; whether a person may start it and sign in inside it; and
/// why it does not start at all (`refusal`).
public struct AgentOffer: Codable, Equatable, Sendable {
    public let harness: String
    public let display: String
    public let modes: [AgentMode]
    public let modeDefault: String
    public let note: String?
    public let login: AgentLogin?
    public let loginRefusal: Refusal?
    public let kinds: [AgentLoginKind]
    public let inside: Bool
    public let refusal: Refusal?

    private enum CodingKeys: String, CodingKey {
        case harness, display, modes, note, login, kinds, inside, refusal
        case modeDefault = "mode_default"
        case loginRefusal = "login_refusal"
    }
}

/// A mode a harness starts in: its name as a start asks for it, and what the harness calls it and
/// does in it.
public struct AgentMode: Codable, Equatable, Sendable {
    public let mode: String
    public let label: String
    public let about: String
}

/// The stored login a harness starts signed in with: its name and kind, never its value.
public struct AgentLogin: Codable, Equatable, Sendable {
    public let name: String
    public let kind: String
}

/// A kind of login a harness takes: its name, what the harness calls it, and how to make one, as
/// words and the commands among them.
public struct AgentLoginKind: Codable, Equatable, Sendable {
    public let kind: String
    public let label: String
    public let how: [RemedyPart]

    /// How to make one as a sentence a person reads, its words alone; nil when it names a command,
    /// since a person is never shown one.
    public var said: String? {
        var words = ""
        for part in how {
            guard case let .words(text) = part else { return nil }
            words += text
        }
        return words.isEmpty ? nil : Said.sentence(words)
    }
}

extension AgentOffer {
    /// The kind a person stores from the app: the first the server names whose how names no
    /// command, so the app says how to make it and stores what is pasted; nil when every kind is
    /// made by running something.
    public var storable: AgentLoginKind? {
        kinds.first { $0.said != nil }
    }
}

extension Endpoint where Response == WorkspaceAgents {
    /// What can start in workspace `name` of `project` for the caller, its owner or an admin;
    /// refused another member, as every workspace door is, and not found where the runner holds no
    /// such workspace.
    public static func agents(workspace name: String, project: String) -> Self {
        Self(.get, Route.workspace(name, project: project) + ["agents"])
    }
}
