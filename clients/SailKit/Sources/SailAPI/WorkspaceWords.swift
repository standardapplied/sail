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

/// The pane's list of a project's workspaces, asked again and again: the latest request wins. A
/// list is taken only for the project and the request still shown, so an answer that arrives after
/// another project was chosen, or after a later request, is dropped; the rows stay paired with the
/// project they were listed for, which Open and Create act on.
public struct WorkspaceListing: Sendable {
    /// The project of the request now shown.
    public private(set) var asking: String?
    /// The project the rows were listed for.
    public private(set) var listed: String?
    public private(set) var rows: [WorkspaceRow] = []
    private var latest = 0

    public init() {}

    /// A request for `project`'s list: its number, which ``take(_:request:)`` checks.
    public mutating func ask(_ project: String?) -> Int {
        latest += 1
        asking = project
        return latest
    }

    /// Takes `rows`, listed by request `number` for the project it asked, when that is still the
    /// request shown; false, and nothing taken, when a later request overtook it. Every request
    /// is a later one, another project's included.
    public mutating func take(_ rows: [WorkspaceRow], request number: Int) -> Bool {
        guard number == latest else { return false }
        self.rows = rows
        listed = asking
        return true
    }

    /// Whether request `number` is still the one shown.
    public func isLatest(_ number: Int) -> Bool {
        number == latest
    }

    /// Lists nothing: no project to list.
    public mutating func clear() {
        latest += 1
        asking = nil
        listed = nil
        rows = []
    }
}
