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

/// How a person starts a harness: with its own sign-in inside the session, or with the key they
/// stored for it.
public enum Way: String, Codable, Hashable, Sendable {
    case signIn, key
}

/// What a person last started, per harness the mode and the way in, and the last start whole,
/// which the row's menu names and starts again. Kept by the app between launches.
public struct ModeMemory: Codable, Equatable, Sendable {
    public private(set) var modes: [String: String]
    public private(set) var ways: [String: Way]
    public private(set) var last: AgentStart?

    public init(modes: [String: String] = [:], ways: [String: Way] = [:], last: AgentStart? = nil) {
        self.modes = modes
        self.ways = ways
        self.last = last
    }

    public mutating func remember(_ start: AgentStart) {
        modes[start.harness] = start.mode
        ways[start.harness] = start.inside ? .signIn : .key
        last = start
    }

    /// `way` chosen for `harness` without a start: storing a key chooses it.
    public mutating func choose(_ way: Way, for harness: String) {
        ways[harness] = way
    }
}

/// The New Session panel's model for one workspace, from what its agents route answered: the
/// harnesses it offers in its order and a Shell after them, the one chosen, its mode and its way in
/// (each the person's last for that harness while it is offered, else the harness's own default
/// mode and its own sign-in), and what a start would do. Every sentence is the server's; no
/// harness's, mode's or kind's name is branched on.
public struct NewSession: Equatable, Sendable {
    public enum Choice: Hashable, Sendable {
        case harness(String)
        case shell
    }

    /// What Start does for the choice: start it, in its way in; nothing, said why; or open a shell.
    public enum Readiness: Equatable, Sendable {
        case start(AgentStart)
        case refused(Refusal)
        case shell
    }

    public let agents: WorkspaceAgents
    public private(set) var chosen: Choice
    public private(set) var memory: ModeMemory
    private var modes: [String: String]
    private var ways: [String: Way]

    public init(_ agents: WorkspaceAgents, memory: ModeMemory) {
        self.agents = agents
        self.memory = memory
        modes = [:]
        ways = [:]
        let lastOffered = memory.last.flatMap { last in agents.offered.first { $0.harness == last.harness } }
        chosen = (lastOffered ?? agents.offered.first).map { .harness($0.harness) } ?? .shell
        for offer in agents.offered {
            let remembered = memory.modes[offer.harness]
            modes[offer.harness] = offer.modes.contains { $0.mode == remembered } ? remembered : offer.modeDefault
            let way = memory.ways[offer.harness] ?? .signIn
            ways[offer.harness] = Self.offered(way, by: offer) ? way : .signIn
        }
    }

    /// Whether `offer` starts in `way`: its own sign-in always, a key where one is its default.
    /// An offer the server says does not start inside does not start at all (its `refusal`).
    private static func offered(_ way: Way, by offer: AgentOffer) -> Bool {
        way == .signIn || offer.login != nil
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

    /// How `harness` starts: its own sign-in, or the key.
    public func way(of harness: String) -> Way? {
        ways[harness]
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

    /// Chooses `way` for the chosen harness when it starts so; anything else is ignored.
    public mutating func choose(way: Way) {
        guard case let .harness(harness) = chosen, let offer = offer(harness), Self.offered(way, by: offer) else { return }
        ways[harness] = way
    }

    /// Whether Start makes the person sign in: the chosen harness starts with its own sign-in in a
    /// workspace not known to hold it.
    public var signsIn: Bool {
        guard case let .start(start) = readiness, start.inside, let offer = offer(start.harness) else { return false }
        return offer.signIn?.signedIn != true
    }

    /// Whether the row's quick item starts the last start's harness again at once, in the way in
    /// and mode the person chose for it: it is chosen, its mode and way still offered (a key
    /// chosen is still stored), and it starts. Otherwise the panel opens at that harness.
    public var startsAgain: Bool {
        guard let last = memory.last, chosen == .harness(last.harness), offer(last.harness)?.modes.contains(where: { $0.mode == last.mode }) == true,
              ways[last.harness] == memory.ways[last.harness] ?? .signIn
        else { return false }
        if case .start = readiness { return true }
        return false
    }

    public var readiness: Readiness {
        guard case let .harness(harness) = chosen, let offer = offer(harness), let mode = modes[harness] else { return .shell }
        if let refusal = offer.refusal { return .refused(refusal) }
        return .start(AgentStart(harness: harness, display: offer.display, mode: mode, inside: ways[harness] != .key))
    }

    /// The start Start makes now, nil when it makes none or opens a shell; the memory then holds
    /// its mode and way for its harness and it as the last start.
    public mutating func start() -> AgentStart? {
        guard case let .start(start) = readiness else { return nil }
        memory.remember(start)
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
    /// The keys the foot names: ⌘M only while the chosen harness has a mode menu for it to open.
    public static func hint(modes: Bool) -> String {
        modes ? "↑↓ choose · ⌘M mode" : "↑↓ choose"
    }
    public static let mode = "Mode"
    public static let signIn = "Sign in"
    public static let storeKey = "Store a Key"

    /// A harness row's trailing line: how it starts, its sign-in's name or its key, or what stops
    /// it.
    public static func line(_ offer: AgentOffer, way: Way?) -> String {
        if offer.refusal?.code == .known(.harnessNotInstalled) { return "not installed here" }
        if let refusal = offer.refusal { return refusal.message }
        return way == .key ? key(offer) ?? "" : offer.signIn?.name ?? ""
    }

    /// The person's default key of `offer`: the kind's label and its name, nil with none.
    public static func key(_ offer: AgentOffer) -> String? {
        offer.login.map { "\(label(of: $0, in: offer)) “\($0.name)”" }
    }

    /// What VoiceOver hears of a harness ready to start on a key just stored: its kind's label and
    /// name; nil with none.
    public static func ready(_ offer: AgentOffer) -> String? {
        offer.login.map { "\(offer.display) ready, \(label(of: $0, in: offer)) \($0.name)." }
    }

    /// The server's label of `login`'s kind, its kind's name where the server labels it not.
    private static func label(of login: AgentLogin, in offer: AgentOffer) -> String {
        offer.kinds.first { $0.kind == login.kind }?.label ?? login.kind
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

    /// What the chosen harness's mode says under it: a harness of one mode the server's note on
    /// why it has no other, else the mode's own words.
    public static func about(_ offer: AgentOffer, mode: String) -> String {
        let about = offer.modes.first { $0.mode == mode }?.about ?? ""
        return offer.modes.count == 1 ? offer.note ?? about : about
    }

    /// Start, its title and whether it is on: Start and Sign In where starting makes the person
    /// sign in (``NewSession/signsIn``).
    public static func primary(_ readiness: NewSession.Readiness, signsIn: Bool) -> (title: String, enabled: Bool) {
        switch readiness {
        case .start: (signsIn ? "Start and Sign In" : "Start", true)
        case .refused: ("Start", false)
        case .shell: ("Start", true)
        }
    }

    /// The row's menu item that starts the harness named `display`, the one started last, again.
    public static func quickStart(_ display: String) -> String {
        "New \(display) Session"
    }

    public static let newSession = "New Session"
    /// What the row's + says while what can start is asked.
    public static let asking = "Asking"
    public static let openAShell = "Open a Shell"

    /// What an agent tab says while its host composes it and hands its key over.
    public static func starting(_ start: AgentStart, in place: String) -> String {
        start.inside ? "Starting \(start.display) in \(place)" : "Starting \(start.display) in \(place), with your key"
    }
}
