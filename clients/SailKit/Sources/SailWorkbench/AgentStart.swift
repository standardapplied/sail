import Foundation
import SailAPI
import SailTerminalWire

/// A harness a person starts in a workspace: by the runner's name for it and in one of its modes,
/// with the display name its tab is titled by, and whether it signs in inside instead of with the
/// person's stored login. The host composes the command; nothing here names one.
public struct AgentStart: Hashable, Codable, Sendable {
    public let harness: String
    public let display: String
    public let mode: String
    public let inside: Bool

    public init(harness: String, display: String, mode: String, inside: Bool = false) {
        self.harness = harness
        self.display = display
        self.mode = mode
        self.inside = inside
    }

    /// What a `Create` carries for it.
    public var wire: SessionAgent {
        SessionAgent(harness: harness, mode: mode, inside: inside)
    }
}

extension RunnerAgent {
    /// The start a listed agent session was, so a tab attached to it is titled by its harness.
    public var start: AgentStart {
        AgentStart(harness: harness, display: display, mode: mode, inside: login == "inside")
    }
}

/// What a person last started, per harness the mode, and the last start whole, which the row's
/// menu names and starts again without the panel. Kept by the app between launches.
public struct ModeMemory: Codable, Equatable, Sendable {
    public private(set) var modes: [String: String]
    public private(set) var last: AgentStart?

    public init(modes: [String: String] = [:], last: AgentStart? = nil) {
        self.modes = modes
        self.last = last
    }

    public mutating func remember(_ start: AgentStart) {
        modes[start.harness] = start.mode
        last = AgentStart(harness: start.harness, display: start.display, mode: start.mode)
    }
}

/// The New Session panel's model for one workspace, from what its agents route answered: the
/// harnesses it offers in its order and a Shell after them, the one chosen, its mode (the person's
/// last for that harness while it is offered, else the harness's own default), and what a start
/// would do. Every sentence is the server's; no harness's, mode's or kind's name is branched on.
public struct NewSession: Equatable, Sendable {
    public enum Choice: Hashable, Sendable {
        case harness(String)
        case shell
    }

    /// What Start does for the choice: start it signed in; start it to sign in inside, its login
    /// refused (`Start and Sign In There`); nothing, said why; or open a shell.
    public enum Readiness: Equatable, Sendable {
        case start(AgentStart)
        case signInThere(AgentStart, Refusal)
        case needsLogin(Refusal)
        case refused(Refusal)
        case shell
    }

    public let agents: WorkspaceAgents
    public private(set) var chosen: Choice
    public private(set) var memory: ModeMemory
    private var modes: [String: String]

    public init(_ agents: WorkspaceAgents, memory: ModeMemory) {
        self.agents = agents
        self.memory = memory
        modes = [:]
        let lastOffered = memory.last.flatMap { last in agents.offered.first { $0.harness == last.harness } }
        chosen = (lastOffered ?? agents.offered.first).map { .harness($0.harness) } ?? .shell
        for offer in agents.offered {
            let remembered = memory.modes[offer.harness]
            modes[offer.harness] = offer.modes.contains { $0.mode == remembered } ? remembered : offer.modeDefault
        }
    }

    /// Why nothing starts in the workspace at all, its person's or gone; nil when its harnesses list.
    public var refusal: Refusal? {
        agents.refusal
    }

    /// The panel's rows in order: each offered harness, then the Shell.
    public var choices: [Choice] {
        agents.offered.map { .harness($0.harness) } + [.shell]
    }

    public func offer(_ harness: String) -> AgentOffer? {
        agents.offered.first { $0.harness == harness }
    }

    /// The chosen harness's mode, as a start asks for it.
    public func mode(of harness: String) -> String? {
        modes[harness]
    }

    public mutating func choose(_ choice: Choice) {
        guard choices.contains(choice) else { return }
        chosen = choice
    }

    /// The next row up or down from the chosen one, stopping at either end.
    public mutating func move(by step: Int) {
        let all = choices
        guard let at = all.firstIndex(of: chosen) else { return }
        chosen = all[max(0, min(all.count - 1, at + step))]
    }

    /// Chooses `mode` for the chosen harness when that harness offers it; anything else is ignored.
    public mutating func choose(mode: String) {
        guard case let .harness(harness) = chosen, offer(harness)?.modes.contains(where: { $0.mode == mode }) == true else { return }
        modes[harness] = mode
    }

    public var readiness: Readiness {
        guard case let .harness(harness) = chosen, let offer = offer(harness), let mode = modes[harness] else { return .shell }
        if let refusal = offer.refusal { return .refused(refusal) }
        guard offer.login == nil, let refused = offer.loginRefusal else {
            return .start(AgentStart(harness: harness, display: offer.display, mode: mode))
        }
        return offer.inside ? .signInThere(AgentStart(harness: harness, display: offer.display, mode: mode, inside: true), refused) : .needsLogin(refused)
    }

    /// The start Start makes now, nil when it makes none or opens a shell; the memory then holds
    /// its mode for its harness and it as the last start.
    public mutating func start() -> AgentStart? {
        let start: AgentStart? = switch readiness {
        case let .start(start), let .signInThere(start, _): start
        case .needsLogin, .refused, .shell: nil
        }
        if let start { memory.remember(start) }
        return start
    }
}

/// The panel's words, each the server's where the server says it.
public enum NewSessionWords {
    public static func title(_ workspace: String) -> (lead: String, place: String) {
        ("New session", "in \(workspace)")
    }

    /// The quiet line under the title for a stopped workspace: a fact, not a refusal.
    public static func stopped(_ workspace: String) -> String {
        "\(workspace) is stopped. Starting a session starts it."
    }

    public static let shell = (name: "Shell", line: "a terminal, no agent")
    public static let hint = "↑↓ choose · ⌘M mode"
    public static let mode = "Mode"

    /// A harness row's trailing line: its login and the kind's label, or what stops it.
    public static func line(_ offer: AgentOffer) -> String {
        if offer.refusal?.code == .known(.harnessNotInstalled) { return "not installed here" }
        if let refusal = offer.refusal { return refusal.message }
        guard let login = offer.login else { return offer.loginRefusal == nil ? "" : "no login stored" }
        let label = offer.kinds.first { $0.kind == login.kind }?.label ?? login.kind
        return "login “\(login.name)” · \(label)"
    }

    /// A refusal's remedy as a person reads it: its words when it names no command, else nothing,
    /// since a command is never shown and the words around one are not a sentence alone.
    public static func remedy(_ refusal: Refusal) -> String? {
        remedy(refusal.remedy, action: refusal.action)
    }

    /// A start the host refused, as its tab's card says it: the sentence, then the remedy's words
    /// as ``remedy(_:)`` says them.
    public static func refused(_ refusal: HostRefusal) -> String {
        [refusal.message, remedy(refusal.remedy, action: nil)].compactMap(\.self).joined(separator: " ")
    }

    private static func remedy(_ parts: [RemedyPart], action: String?) -> String? {
        let commands = parts.contains { if case .command = $0 { true } else { false } }
        guard !commands else { return nil }
        let words = parts.compactMap { part -> String? in if case let .words(text) = part { text } else { nil } }.joined()
        let said = words.isEmpty ? action ?? "" : words
        return said.isEmpty ? nil : said
    }

    public static func storeLogin(_ offer: AgentOffer) -> String {
        "Store a \(offer.display) Login"
    }

    /// The kinds a person stores, by the server's labels, in its order.
    public static func kinds(_ offer: AgentOffer) -> String {
        offer.kinds.map(\.label).joined(separator: " or ")
    }

    /// How to make the login the server names first, in its words, which Store a Login says in
    /// place until logins are stored in the app; nil when it names none.
    public static func how(_ offer: AgentOffer) -> String? {
        offer.kinds.first.map { Said.sentence($0.how) }
    }

    /// What the chosen harness's mode says under it: a harness of one mode the server's note on
    /// why it has no other, else the mode's own words.
    public static func about(_ offer: AgentOffer, mode: String) -> String {
        let about = offer.modes.first { $0.mode == mode }?.about ?? ""
        return offer.modes.count == 1 ? offer.note ?? about : about
    }

    public static func signInThere(_ offer: AgentOffer, workspace: String) -> String {
        "Or start it and sign in inside \(offer.display): that login stays in \(workspace) only."
    }

    public static func primary(_ readiness: NewSession.Readiness) -> (title: String, enabled: Bool) {
        switch readiness {
        case .start: ("Start", true)
        case .signInThere: ("Start and Sign In There", true)
        case .needsLogin, .refused: ("Start", false)
        case .shell: ("Start", true)
        }
    }

    /// The row's menu item that starts the harness named `display`, the one started last, again.
    public static func quickStart(_ display: String) -> String {
        "New \(display) Session"
    }

    public static let newSession = "New Session"
    public static let openAShell = "Open a Shell"

    /// What an agent tab says while its host composes it and hands its login over.
    public static func starting(_ start: AgentStart, in place: String) -> String {
        start.inside ? "Starting \(start.display) in \(place); you sign in inside it" : "Starting \(start.display) in \(place), signed in with your login"
    }
}
