/// The Create Project sheet as a person fills it in: the name, the repositories with the folder each
/// is cloned to, the agents the template installs (the server's harnesses, all chosen at first),
/// and each workspace's size; what Create sends, and the server's refusal under the field it names.
/// The sheet only draws this and sends the person's edits here.
public struct NewProject: Equatable, Sendable {
    public enum Size: String, CaseIterable, Sendable {
        case small = "Small", medium = "Medium", large = "Large", custom = "Custom"

        /// The preset's cores, memory and disk; none for Custom, which the person types.
        public var preset: Resources? {
            switch self {
            case .small: Resources(cores: 2, memory: 4, disk: 30)
            case .medium: Resources(cores: 4, memory: 8, disk: 50)
            case .large: Resources(cores: 8, memory: 16, disk: 100)
            case .custom: nil
            }
        }
    }

    /// A size in the app's words: cores, and memory and disk in GB.
    public struct Resources: Equatable, Sendable {
        public let cores: Int
        public let memory: Int
        public let disk: Int

        public init(cores: Int, memory: Int, disk: Int) {
            self.cores = cores
            self.memory = memory
            self.disk = disk
        }
    }

    /// One of Custom's three fields.
    public enum Part: CaseIterable, Sendable {
        case cores, memory, disk
    }

    public struct Repo: Equatable, Sendable {
        public fileprivate(set) var url = ""
        public fileprivate(set) var path = ""
        /// The person typed the path: it no longer follows the URL.
        fileprivate var pathTyped = false
    }

    public struct Agent: Equatable, Sendable {
        public let name: String
        public let display: String
        public fileprivate(set) var checked = true

        public init(name: String, display: String) {
            self.name = name
            self.display = display
        }
    }

    /// The server's refusal of a field: its sentence, and what to do.
    public struct Refused: Equatable, Sendable {
        public let said: String
        public let remedy: String?

        public init(said: String, remedy: String?) {
            self.said = said
            self.remedy = remedy
        }
    }

    public static let nameHelp = "Lowercase letters, digits and dashes. Workspaces are named after it."
    public static let agentsHelp = "Installed in the template, so every workspace has them."
    public static let footHelp = "Sail builds the template next; it takes a few minutes."
    /// Where every repository's folder is, which its path field shows before the folder.
    public static let folder = "~/workspace/"

    public private(set) var name = ""
    public private(set) var repos = [Repo()]
    public private(set) var agents: [Agent]
    /// Why the server's harnesses could not be listed, said under AGENTS; nil when they were.
    public private(set) var agentsUnlisted: String?
    public private(set) var size = Size.medium
    /// Custom's fields as typed, digits only, starting at the size chosen before.
    public private(set) var custom: [Part: String] = [.cores: "4", .memory: "8", .disk: "50"]
    /// Create was pressed and the server has not answered.
    public private(set) var underWay = false
    /// The server's refusal of the name, said under it until the name is edited.
    public private(set) var nameRefused: Refused?
    /// A refusal that names no field of the sheet, said over its foot, a line each, until an edit.
    public private(set) var refused: [String] = []

    public init(agents: [Agent], unlisted: String? = nil) {
        self.agents = agents
        agentsUnlisted = unlisted
    }

    /// Create is offered: a name is typed, the agents were listed, every path typed has a URL to
    /// clone into it, and nothing is under way.
    public var creates: Bool {
        !underWay && agentsUnlisted == nil && !name.trimmingCharacters(in: .whitespaces).isEmpty
            && !repos.contains { $0.url.trimmingCharacters(in: .whitespaces).isEmpty && !$0.path.isEmpty }
    }

    /// The server's harnesses as `GET /v1/agents` answered, each checked; or why they could not be
    /// listed, failing to reach the server said at `address`.
    public mutating func agentsListed(_ listed: Result<KnownAgents, SailError>, at address: String) {
        switch listed {
        case let .success(known):
            agents = known.agents.map { Agent(name: $0.name, display: $0.displayName) }
            agentsUnlisted = nil
        case let .failure(error):
            agentsUnlisted = "Sail could not list the agents: \(error.said(at: address))"
        }
    }

    public mutating func named(_ typed: String) {
        name = typed
        nameRefused = nil
        refused = []
    }

    /// Repository `row`'s URL typed: its path follows the URL's last part, less `.git`, until the
    /// person types the path; a path another row holds gets the first free `-2`, `-3`.
    public mutating func typeURL(_ url: String, row: Int) {
        guard repos.indices.contains(row) else { return }
        repos[row].url = url
        if !repos[row].pathTyped { repos[row].path = free(Self.path(of: url), row: row) }
        refused = []
    }

    private func free(_ path: String, row: Int) -> String {
        let held = Set(repos.indices.filter { $0 != row }.map { repos[$0].path })
        guard !path.isEmpty, held.contains(path) else { return path }
        var suffix = 2
        while held.contains("\(path)-\(suffix)") { suffix += 1 }
        return "\(path)-\(suffix)"
    }

    public mutating func typePath(_ path: String, row: Int) {
        guard repos.indices.contains(row) else { return }
        repos[row].path = path
        repos[row].pathTyped = true
        refused = []
    }

    public mutating func addRepo() {
        repos.append(Repo())
    }

    public mutating func removeRepo(_ row: Int) {
        guard repos.indices.contains(row) else { return }
        repos.remove(at: row)
        refused = []
    }

    public mutating func check(_ agent: String, _ checked: Bool) {
        guard let at = agents.firstIndex(where: { $0.name == agent }) else { return }
        agents[at].checked = checked
        refused = []
    }

    public mutating func choose(_ size: Size) {
        if size == .custom, let before = self.size.preset { custom = [.cores: "\(before.cores)", .memory: "\(before.memory)", .disk: "\(before.disk)"] }
        self.size = size
        refused = []
    }

    /// Custom's `part` typed, kept to its digits: what the field then holds.
    @discardableResult
    public mutating func typeCustom(_ part: Part, _ typed: String) -> String {
        let digits = String(typed.filter { $0.isASCII && $0.isNumber })
        custom[part] = digits
        refused = []
        return digits
    }

    /// The size chosen, Custom's as typed (an empty field is 0, which the server refuses).
    public var resources: Resources {
        size.preset ?? Resources(cores: Int(custom[.cores] ?? "") ?? 0, memory: Int(custom[.memory] ?? "") ?? 0, disk: Int(custom[.disk] ?? "") ?? 0)
    }

    /// What SIZE says of the size chosen.
    public var sizeHelp: String {
        let size = resources
        return "\(size.cores) core\(size.cores == 1 ? "" : "s") · \(size.memory) GB memory · \(size.disk) GB disk for each workspace."
    }

    /// The definition Create sends: every repository given a URL (one typed as a bare host and path
    /// taken as https, which the server clones over), the agents checked, the size chosen.
    public var definition: ProjectDefinition {
        let size = resources
        return ProjectDefinition(
            name: name,
            repos: repos.filter { !$0.url.trimmingCharacters(in: .whitespaces).isEmpty }.map { ProjectDefinition.Repo(url: Self.https($0.url), path: $0.path) },
            agent: ProjectDefinition.Agent(install: agents.filter(\.checked).map(\.name)),
            resources: ProjectDefinition.Resources(cpu: size.cores, memory: "\(size.memory)GB", disk: "\(size.disk)GB")
        )
    }

    /// Create pressed: the definition to send, now under way; nil while one is, or with no name.
    public mutating func send() -> ProjectDefinition? {
        guard creates else { return nil }
        underWay = true
        nameRefused = nil
        refused = []
        return definition
    }

    /// The create refused: a refusal naming the name stands under it, in the server's words, with
    /// what to do; any other stands over the foot. What was typed stays.
    public mutating func refuse(_ error: SailError, at address: String) {
        underWay = false
        guard case let .refused(refusal) = error else {
            refused = [error.said(at: address)]
            return
        }
        if let name = Self.nameRefusal(error) {
            nameRefused = name
        } else {
            refused = refusal.lines
        }
    }

    /// The server's refusal of the name, where `error` is one: its sentence and what to do.
    public static func nameRefusal(_ error: SailError) -> Refused? {
        guard case let .refused(refusal) = error, let field = refusal.fieldErrors.first(where: { $0.field == "name" }) else { return nil }
        return Refused(said: field.message, remedy: Said.fix(refusal.remedy, otherwise: refusal.action))
    }

    /// The folder a repository is cloned to by default: its URL's last part, less `.git`, its query
    /// and its fragment; an ssh address with no folder (`git@host:app`) its part after the colon.
    static func path(of url: String) -> String {
        let address = url.trimmingCharacters(in: .whitespaces).prefix { $0 != "?" && $0 != "#" }
        let last = address.split(separator: "/").last.map { $0.split(separator: ":").last ?? $0 }.map(String.init) ?? ""
        return last.hasSuffix(".git") ? String(last.dropLast(4)) : last
    }

    /// `url` as the server clones it: a bare host and path (`github.com/acme/app`) over https; one
    /// with a scheme, or an ssh address, as typed, for the server to say what it takes.
    static func https(_ url: String) -> String {
        let url = url.trimmingCharacters(in: .whitespaces)
        return url.contains("://") || url.contains("@") ? url : "https://" + url
    }
}
