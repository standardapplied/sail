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

/// What a person last started, per harness the mode and the way in, the last start whole, which
/// the row's menu names and starts again, and whether the kind opened last was the Shell. Kept by
/// the app between launches.
public struct ModeMemory: Codable, Equatable, Sendable {
    public private(set) var modes: [String: String]
    public private(set) var ways: [String: Way]
    public private(set) var last: AgentStart?
    public private(set) var shellLast: Bool

    public init(modes: [String: String] = [:], ways: [String: Way] = [:], last: AgentStart? = nil, shellLast: Bool = false) {
        self.modes = modes
        self.ways = ways
        self.last = last
        self.shellLast = shellLast
    }

    public mutating func remember(_ start: AgentStart) {
        modes[start.harness] = start.mode
        ways[start.harness] = start.inside ? .signIn : .key
        last = start
        shellLast = false
    }

    public mutating func rememberShell() {
        shellLast = true
    }

    /// These starts with the last harness as the kind last opened: what the row's quick item, which
    /// names that harness, opens at.
    public var atLastHarness: ModeMemory {
        ModeMemory(modes: modes, ways: ways, last: last)
    }

    /// `way` chosen for `harness` without a start: storing a key chooses it.
    public mutating func choose(_ way: Way, for harness: String) {
        ways[harness] = way
    }
}

/// The New Session panel's model for one workspace, from what its agents route answered: the
/// harnesses it offers in its order and a Shell after them, narrowed past seven by what is typed,
/// the one chosen (the kind opened last while it is offered), its mode and its way in (each the
/// person's last for that harness while it is offered, else the harness's own default mode and its
/// own sign-in), and what a start would do.
/// Every sentence is the server's; no harness's, mode's or kind's name is branched on.
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

    /// The most rows shown whole; past them typing narrows the rows.
    public static let unnarrowed = 7

    public let agents: WorkspaceAgents
    public private(set) var chosen: Choice
    public private(set) var memory: ModeMemory
    /// What typing narrowed the rows to: the start of their names; empty, every row.
    public private(set) var filter = ""
    /// The row chosen before typing narrowed the rows, chosen again once it is erased.
    private var unfiltered: Choice?
    /// Learn more was asked: what a person must do first is said in its place, for every harness
    /// while the panel is open.
    public private(set) var more = false
    private var modes: [String: String]
    private var ways: [String: Way]

    public init(_ agents: WorkspaceAgents, memory: ModeMemory) {
        self.agents = agents
        self.memory = memory
        modes = [:]
        ways = [:]
        let lastOffered = memory.last.flatMap { last in agents.offered.first { $0.harness == last.harness } }
        chosen = memory.shellLast ? .shell : (lastOffered ?? agents.offered.first).map { .harness($0.harness) } ?? .shell
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

    /// The panel's rows in order: each offered harness, then the Shell, those whose name starts
    /// with the filter.
    public var choices: [Choice] {
        every.filter { matches($0, filter) }
    }

    private var every: [Choice] {
        agents.offered.map { .harness($0.harness) } + [.shell]
    }

    /// Whether typing narrows the rows: there are more than ``unnarrowed``.
    public var filters: Bool {
        every.count > Self.unnarrowed
    }

    /// A row's name: its harness's display name, or the Shell's.
    public func name(of choice: Choice) -> String {
        guard case let .harness(harness) = choice else { return NewSessionWords.shell }
        return offer(harness)?.display ?? harness
    }

    private func matches(_ choice: Choice, _ start: String) -> Bool {
        name(of: choice).range(of: start, options: [.anchored, .caseInsensitive, .diacriticInsensitive]) != nil || start.isEmpty
    }

    /// The rows narrowed to those whose name starts with `typed`, the first chosen, where typing
    /// narrows them and some row matches; whether it did. Erased, every row is back and the row
    /// chosen before typing is chosen again.
    public mutating func narrow(to typed: String) -> Bool {
        guard filters, let first = every.first(where: { matches($0, typed) }) else { return false }
        if filter.isEmpty { unfiltered = chosen }
        filter = typed
        chosen = typed.isEmpty ? unfiltered ?? first : first
        return true
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

    public mutating func openMore() {
        more = true
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

    /// Whether the chosen harness's chosen mode skips its own permission checks, as the server says.
    public var risky: Bool {
        guard case let .harness(harness) = chosen, let mode = modes[harness] else { return false }
        return offer(harness)?.modes.first { $0.mode == mode }?.risky == true
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
    /// its mode and way for its harness and it as the last start, or the Shell as the kind opened
    /// last.
    public mutating func start() -> AgentStart? {
        switch readiness {
        case let .start(start):
            memory.remember(start)
            return start
        case .shell:
            memory.rememberShell()
            return nil
        case .refused:
            return nil
        }
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

    public static let shell = "Shell"

    /// The Shell row's line.
    public static func shellLine(_ workspace: String) -> String {
        "A terminal in \(workspace), no agent"
    }

    /// A kind's tile: the first two letters or digits of its display name, the first upper case.
    public static func monogram(_ display: String) -> String {
        let letters = display.filter { $0.isLetter || $0.isNumber }.prefix(2)
        return letters.prefix(1).uppercased() + letters.dropFirst().lowercased()
    }

    /// What holds the panel's keys, as the app's one rule for it says (the first responder of a
    /// window that is key): its rows, its Sign in pop-up (after ⇥), or nothing of the panel, a sheet
    /// over the window or another window being key.
    public enum Holder: Equatable, Sendable {
        case rows, signIn, none
    }

    /// The keys the foot names while `holder` holds them, each only while it does what it says:
    /// while something is `typed` to narrow the rows, only it and ⌫ to widen them while the rows
    /// hold the keys, so what was typed always shows; with nothing typed, ↑↓ choose while the rows
    /// hold them (Sign in holding them, ↑↓ open its menu) and ⌘M while the chosen harness has a mode
    /// menu for it to open (`modes`), whichever of the panel's holds them; none while nothing of the
    /// panel holds them. What was typed is isolated, so right-to-left letters never reorder the
    /// keys, and it alone is cut, at its end, to the first words that `fits`; where not one letter of
    /// it fits, it is left out and ⌫ is still named whole. With nothing typed, the keys that do not
    /// fit go whole from the end; a key is never cut.
    public static func hint(_ holder: Holder, modes: Bool, typed: String = "", fits: (String) -> Bool = { _ in true }) -> String {
        guard holder != .none else { return "" }
        let keys = typed.isEmpty ? (holder == .rows ? ["↑↓ choose"] : []) + (modes ? ["⌘M mode"] : []) : holder == .rows ? ["⌫ widen"] : []
        guard !typed.isEmpty else { return (0 ... keys.count).reversed().map { keys.prefix($0).joined(separator: " · ") }.first(where: fits) ?? "" }
        let cuts = [typed] + (1 ..< typed.count).reversed().map { String(typed.prefix($0)) + "…" }
        let said = cuts.map { (["“\u{2068}\($0)\u{2069}”"] + keys).joined(separator: " · ") }
        return said.first(where: fits) ?? keys.joined(separator: " · ")
    }
    public static let mode = "Mode"
    /// What VoiceOver hears as the Mode pop-up's name where the chosen mode is risky; the words under
    /// it, after the warning mark, say why.
    public static let modeWarning = "Mode, warning"
    public static let signIn = "Sign in"
    public static let storeKey = "Store a Key"

    /// A harness row's line: how it signs in as its Sign in is set, in Sign in's words; or, where it
    /// does not start, the server's few words for why.
    public static func line(_ offer: AgentOffer, way: Way) -> String {
        if let refusal = offer.refusal { return offer.unavailable ?? refusal.message }
        return self.way(offer, way)
    }

    /// How `offer` signs in in `way`, as Sign in names it: the stored default key where that is
    /// chosen and stored, else its own sign-in's name.
    public static func way(_ offer: AgentOffer, _ way: Way) -> String {
        let account = offer.signIn?.name
        return (way == .key ? key(offer) ?? account : account ?? key(offer)) ?? ""
    }

    /// The line under Sign in: on the sign-in, that the workspace holds it already, else its own
    /// sentence of what happens; nothing on the key, whose menu offers the sign-in.
    public static func signInHelp(_ offer: AgentOffer, way: Way) -> String? {
        guard let signIn = offer.signIn, way == .signIn else { return nil }
        return signIn.signedIn == true ? signedIn : signIn.said
    }

    public static let signedIn = "Already signed in."
    public static let learnMore = "Learn more"

    /// What a person must do first, behind Learn more under Sign in's line: the server's `more`, on
    /// the harness's own sign-in where the workspace may not hold it yet; nil otherwise.
    public static func more(_ offer: AgentOffer, way: Way) -> String? {
        guard let signIn = offer.signIn, way == .signIn, signIn.signedIn != true else { return nil }
        return signIn.moreSaid
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

    /// What the chosen harness's mode says under it: the mode's own words.
    public static func about(_ offer: AgentOffer, mode: String) -> String {
        offer.modes.first { $0.mode == mode }?.about ?? ""
    }

    /// The primary, which names the choice: the harness started, the Shell opened; none where the
    /// chosen harness does not start.
    public static func primary(_ readiness: NewSession.Readiness) -> String? {
        switch readiness {
        case let .start(start): "Start \(start.display)"
        case .refused: nil
        case .shell: openShell
        }
    }

    /// The row's menu item that starts the harness named `display`, the one started last, again.
    public static func quickStart(_ display: String) -> String {
        "New \(display) Session"
    }

    public static let newSession = "New Session"
    /// What the row's + says while what can start is asked.
    public static let asking = "Asking"
    public static let openShell = "Open Shell"

    /// What an agent tab says while its host composes it and hands its key over.
    public static func starting(_ start: AgentStart, in place: String) -> String {
        start.inside ? "Starting \(start.display) in \(place)" : "Starting \(start.display) in \(place), with your key"
    }
}
