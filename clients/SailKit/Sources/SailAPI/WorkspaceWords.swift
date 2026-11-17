/// What a person reads of a refusal in the app: the server's sentence, then what they can do. A
/// fix that names a command is said as who acts, never as the command, since a person on the team
/// types none; a fix with no command is shown as the server wrote it.
extension Refusal {
    public var lines: [String] {
        [message] + (fix.map { [$0] } ?? [])
    }

    /// What the person can do: the first command of the remedy, said in words when the app knows it
    /// and nothing when it does not; the server's text when the remedy names no command.
    public var fix: String? {
        let commands = remedy.compactMap { part -> (verb: String, args: [String])? in
            if case let .command(_, verb, args) = part { (verb, args) } else { nil }
        }
        guard let command = commands.first else { return action }
        switch command.verb {
        case "project template build": return "An admin builds \(command.args.first ?? "the project")'s template on the runner first."
        case "git login": return "Ask an admin to store it."
        case "fde update": return "Ask an admin to set your email."
        case "project workspace delete" where command.args.contains("--force"): return "Ask an admin to delete it."
        default: return nil
        }
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
    /// Its name, or "uday's dock" when it is another person's.
    public func title(for handle: String) -> String {
        guard let owner, owner != handle else { return name }
        return "\(owner)'s \(name)"
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

    /// Whether the app offers Open on it: a person's own, or any to an admin. The server decides
    /// either way.
    public func opens(for handle: String, admin: Bool) -> Bool {
        admin || owner == handle
    }
}
