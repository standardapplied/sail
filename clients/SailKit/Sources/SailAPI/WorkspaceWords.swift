/// What a person reads of a refusal in the app: the server's sentence, then what they can do. A
/// fix that names a command is said as who acts, never as the command, since a person on the team
/// types none; one the app does not know is left out, the sentence alone.
public enum Said {
    /// What the person can do about a refusal whose remedy is `remedy`: its first command said as
    /// who acts when the app knows it, nothing when it does not; `words` when it names no command.
    /// A missing git token or email of a workspace's owner is set by an admin for now: the server's
    /// sentence says whose and what.
    public static func fix(_ remedy: [RemedyPart], otherwise words: String?) -> String? {
        let commands = remedy.compactMap { part -> (verb: String, args: [String])? in
            if case let .command(_, verb, args) = part { (verb, args) } else { nil }
        }
        guard let command = commands.first else { return words }
        switch command.verb {
        case "project template build": return "An admin builds \(command.args.first ?? "the project")'s template on the runner first."
        case "git login": return "An admin stores it on the runner for now."
        case "fde update": return "An admin sets it on main for now."
        case "project workspace delete" where command.args.contains("--force"): return "Ask an admin to delete it."
        default: return nil
        }
    }

    /// What a person reads of a refusal, a line each: its sentence, then what they can do (``fix(_:otherwise:)``).
    /// The one composition every refusal is said by, the API's and a terminal host's alike.
    public static func lines(_ message: String, remedy: [RemedyPart], otherwise words: String?) -> [String] {
        [message] + (fix(remedy, otherwise: words).map { [$0] } ?? [])
    }

    /// `text` as a sentence: its first letter upper case and a full stop at its end.
    public static func sentence(_ text: String) -> String {
        let capital = text.prefix(1).uppercased() + text.dropFirst()
        return capital.hasSuffix(".") ? capital : capital + "."
    }
}

extension Refusal {
    public var lines: [String] {
        Said.lines(message, remedy: remedy, otherwise: action)
    }
}

extension WorkspaceList {
    /// The workspaces a person picks from: theirs first, then everyone else's, running before the
    /// rest and by name within each; the project's own container left out.
    public func rows(for handle: String) -> [WorkspaceRow] {
        workspaces.filter { $0.name != "main" }.sorted { one, other in
            let order = { (row: WorkspaceRow) in (row.owner == handle ? 0 : 1, row.state == .known(.running) ? 0 : 1) }
            let (first, second) = (order(one), order(other))
            return first != second ? first < second : one.name < other.name
        }
    }
}

extension WorkspaceRow {
    /// The owner the server names when the workspace's owner's account is not active, removed or
    /// disabled alike (`Workspaces.REMOVED_OWNER`): never a handle.
    public static let removedOwner = "removed"

    /// Whether the app offers Open on it: a person's own, or any to an admin. The server decides
    /// either way.
    public func opens(for handle: String, admin: Bool) -> Bool {
        admin || owner == handle
    }

    /// Why Open is not offered on it to `handle`, as its row says it; nil when it is.
    public func whyNotOpened(for handle: String, admin: Bool) -> String? {
        guard !opens(for: handle, admin: admin) else { return nil }
        switch owner {
        case nil: return "No one owns it: only an admin opens it"
        case Self.removedOwner?: return "Its owner’s account is not active: only an admin opens it"
        case let owner?: return "Only \(owner) or an admin opens it"
        }
    }
}

/// The workspaces listed of each project, the left panel's rows under it and its page's alike,
/// asked again and again: each project's latest request wins. Requests are numbered across
/// projects, so an answer names the project it was asked for; one overtaken by a later request for
/// its project is dropped, while another project's request overtakes none. The rows stay paired
/// with the project they were listed for, which Open and Create act on.
public struct WorkspaceListing: Sendable {
    /// Each project's rows as last listed; a project never listed has none.
    public private(set) var rows: [String: [WorkspaceRow]] = [:]
    /// The latest request's number, of any project.
    public private(set) var latest = 0
    /// Each project's latest request.
    private var asked: [String: Int] = [:]

    public init() {}

    /// A request for `project`'s list: its number, which ``take(_:request:)`` checks. Nil asks for
    /// nothing anyone takes.
    public mutating func ask(_ project: String?) -> Int {
        latest += 1
        if let project { asked[project] = latest }
        return latest
    }

    /// The project request `number` asked for while it is that project's latest; nil once overtaken.
    public func project(of number: Int) -> String? {
        asked.first { $0.value == number }?.key
    }

    /// Whether `project`'s list was ever asked.
    public func asked(_ project: String) -> Bool {
        asked[project] != nil
    }

    /// Takes `rows`, listed by request `number`, for the project it asked when it is still that
    /// project's latest: that project; nil, and nothing taken, when a later request overtook it.
    public mutating func take(_ rows: [WorkspaceRow], request number: Int) -> String? {
        guard let project = project(of: number) else { return nil }
        self.rows[project] = rows
        return project
    }

    /// Row `name` of `project` as an action's answer left it, until a list asked after that answer is
    /// taken: only the latest request's list is, so a list already in flight cannot bring the old
    /// state back. Nil `state` takes the row out (deleted).
    public mutating func settle(_ name: String, in project: String, as state: WorkspaceState?) {
        guard let listed = rows[project] else { return }
        guard let state else { return rows[project] = listed.filter { $0.name != name } }
        rows[project] = listed.map { $0.name == name ? WorkspaceRow(name: $0.name, instance: $0.instance, state: .known(state), owner: $0.owner) : $0 }
    }

    /// Lists nothing: no project to list, every answer on its way dropped.
    public mutating func clear() {
        latest += 1
        asked = [:]
        rows = [:]
    }
}
