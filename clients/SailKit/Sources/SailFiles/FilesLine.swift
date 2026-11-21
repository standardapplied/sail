/// The files panel's one quiet line: said only when the tree is not the workspace's live truth, and
/// gone once that no longer applies; "Reconnecting." only while the connection truly is; of two at once, the more severe (stopped, then can't reach, then
/// not live, then listing). Nothing is said for the first 300 ms of a listing, so a fast one never
/// flashes. A reason the workspace gave is said as given (a stop's own record), never one guessed.
public struct FilesLine: Equatable, Sendable {
    /// What the line offers beside its words.
    public enum Action: Equatable, Sendable {
        /// Asks for the connection again.
        case tryAgain
        /// Starts the stopped workspace: the person's own act, since looking never starts one.
        case start
    }

    /// What leads the words, as the mockup's line draws it.
    public enum Mark: Equatable, Sendable {
        /// A spinner: a listing under way.
        case working
        /// A pulsing `info` square: waiting for the workspace to answer again.
        case waiting
        /// An `error` square, the words in `error` too.
        case bad
        /// A quiet `textTertiary` square: a state, nothing wrong.
        case quiet
        case none
    }

    public static let listingAfter = 0.3
    public static let slowAfter = 6.0

    public let words: String
    public let mark: Mark
    public let action: Action?
    /// Whether the tree under it is shown dimmed, as no longer the workspace's truth.
    public let dims: Bool

    public init(words: String, mark: Mark, action: Action? = nil, dims: Bool = false) {
        self.words = words
        self.mark = mark
        self.action = action
        self.dims = dims
    }

    /// The next moment after `now` when time alone may change the line or the rows, nil for none:
    /// a first listing's 300 ms and each second of its wait from 6 s, the tick's silence running
    /// out, a cue's 10 s. A panel wakes then and at no beat of its own.
    public static func nextChange(tree: FileTree, phase: FilesSession.Phase, at now: Double) -> Double? {
        var moments: [Double] = []
        if let cue = tree.nextCueEnd(after: now) { moments.append(cue) }
        switch phase {
        case .stopped, .gone, .refused, .unreachable, .limited:
            return moments.min()
        case .connecting, .connected, .reconnecting:
            break
        }
        if !tree.rootKnown {
            let waited = now - tree.opened
            moments.append(waited < listingAfter ? tree.opened + listingAfter : waited < slowAfter ? tree.opened + slowAfter : tree.opened + (waited + 1).rounded(.down))
        } else if tree.isLive(at: now), let heard = tree.heard {
            moments.append(heard + FileTree.silence)
        }
        return moments.min()
    }

    /// The line for `tree` with its connection at `phase`, at `now` in the tree's seconds; nil when
    /// nothing is to be said. `time` names an instant in those seconds as a time of day.
    public init?(tree: FileTree, phase: FilesSession.Phase, workspace: String, at now: Double, time: (Double) -> String) {
        let shown = tree.rootKnown
        switch phase {
        case let .stopped(record):
            self.init(words: record.isEmpty ? "\(workspace) is stopped." : record, mark: .quiet, action: .start, dims: true)
            return
        case let .gone(message):
            self.init(words: message, mark: .bad, dims: true)
            return
        case let .refused(lines):
            self.init(words: lines.joined(separator: " "), mark: .bad, dims: true)
            return
        case let .limited(message):
            self.init(words: message, mark: .bad, action: .tryAgain, dims: shown)
            return
        case .unreachable:
            self.init(words: "Can't reach the files.", mark: .bad, action: .tryAgain, dims: shown)
            return
        case .connecting, .connected, .reconnecting:
            break
        }
        if let refusal = tree.refusals[FileTree.root] {
            self.init(words: refusal, mark: .bad)
        } else if !shown {
            let waited = now - tree.opened
            guard waited >= Self.listingAfter else { return nil }
            self.init(
                words: waited < Self.slowAfter
                    ? "Listing \(FileTree.rootName)"
                    : "Still listing \(FileTree.rootName) (\(Int(waited)) s). The terminal still works.",
                mark: .working
            )
        } else if !tree.isLive(at: now) {
            let trying = phase == .connecting || { if case .reconnecting = phase { true } else { false } }()
            self.init(words: "Not live since \(time(tree.heard ?? tree.opened)).\(trying ? " Reconnecting." : "")", mark: .waiting, dims: true)
        } else if let unsettled = tree.unsettled {
            let name = FileTree.shownName(of: unsettled.path)
            self.init(words: unsettled.shownAsOf.map { "\(name) keeps changing; shown as of \(time($0))." } ?? "\(name) keeps changing.", mark: .waiting)
        } else if tree.folders[FileTree.root]?.entries.isEmpty == true {
            self.init(words: "\(FileTree.rootName) is empty.", mark: .none)
        } else if !tree.followsAll {
            self.init(words: "Following the \(FileTree.watchCap.formatted()) most recent folders live.", mark: .quiet)
        } else {
            return nil
        }
    }
}
