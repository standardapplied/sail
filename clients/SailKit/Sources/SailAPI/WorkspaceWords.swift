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

    /// `text` as a sentence: its first letter upper case and a full stop at its end.
    public static func sentence(_ text: String) -> String {
        let capital = text.prefix(1).uppercased() + text.dropFirst()
        return capital.hasSuffix(".") ? capital : capital + "."
    }
}

extension Refusal {
    public var lines: [String] {
        [message] + (fix.map { [$0] } ?? [])
    }

    /// What the person can do, said as ``Said/fix(_:otherwise:)`` says it.
    public var fix: String? {
        Said.fix(remedy, otherwise: action)
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
    /// Its row as a person reads it, a part each: its name, its state, and its owner when that is
    /// not `handle` ("quay · running · ana").
    public func reading(for handle: String) -> String {
        ([name, said] + (owner(apartFrom: handle).map { [$0] } ?? [])).joined(separator: " · ")
    }

    /// Why Open is not offered on it to `handle`, in a sentence; nil when it is.
    public func notOpened(for handle: String, admin: Bool) -> String? {
        guard !opens(for: handle, admin: admin) else { return nil }
        guard let owner else { return "No one owns \(name): only an admin can open it." }
        return "\(name) is \(owner)'s: only \(owner) or an admin can open it."
    }

    /// Its state in words.
    public var said: String {
        switch state {
        case .known(.running): "running"
        case .known(.stopped): "stopped"
        case .known(.frozen): "paused"
        case .known(.incomplete): "not whole, make it again"
        case .known(.notCreated): "not made"
        case let .unknown(raw): raw
        }
    }

    /// Its owner when that is not `handle`, said at its row's trailing edge.
    public func owner(apartFrom handle: String) -> String? {
        owner == handle ? nil : owner
    }

    /// Whether opening it starts it first: the server starts a stopped workspace it is asked to
    /// open a terminal in (`Workspaces.startExisting`).
    public var startsWhenOpened: Bool {
        state == .known(.stopped)
    }

    /// Whether it is up, its dot filled.
    public var isUp: Bool {
        state == .known(.running)
    }

    /// Whether the app offers Open on it: a person's own, or any to an admin. The server decides
    /// either way.
    public func opens(for handle: String, admin: Bool) -> Bool {
        admin || owner == handle
    }
}

/// The pane's list of a project's workspaces, asked again and again: the latest request wins. A
/// list is taken only for the project and the request still shown, so an answer that arrives after
/// another project was chosen, or after a later request, is dropped; the rows stay paired with the
/// project they were listed for, which Open and Make act on.
public struct WorkspaceListing: Sendable {
    /// The project of the request now shown.
    public private(set) var asking: String?
    /// The project the rows were listed for.
    public private(set) var listed: String?
    public private(set) var rows: [WorkspaceRow] = []
    private var latest = 0

    public init() {}

    /// A request for `project`'s list: its number, which ``take(_:for:request:)`` checks.
    public mutating func ask(_ project: String?) -> Int {
        latest += 1
        asking = project
        return latest
    }

    /// Takes `rows`, listed for `project` by request `number`, when that is still the request
    /// shown; false, and nothing taken, when a later request or another project overtook it.
    public mutating func take(_ rows: [WorkspaceRow], for project: String, request number: Int) -> Bool {
        guard number == latest, project == asking else { return false }
        self.rows = rows
        listed = project
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
