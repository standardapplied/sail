import SailAPI

/// What a runner pane says when the runner would not attach its session at a reattach, from what
/// the workspace's listing answered: ended, in the host's words, while the workspace still lists it;
/// gone when it no longer does; the workspace itself not on this runner when the listing is not
/// found; and the host's own words, to try again, when the terminal host does not answer.
public struct RunnerReturn: Equatable, Sendable {
    public static let gone = "This terminal is no longer on the runner: its workspace was stopped or deleted, or the runner restarted."
    public static let elsewhere = "This terminal's workspace is not on this runner: it was deleted, or it lives on another runner."

    /// What the pane says.
    public let said: String
    /// What the pane offers: a new session there, or asking again.
    public let offer: Offer

    public enum Offer: Equatable, Sendable {
        /// Open a New One in the workspace, or Close.
        case openANewOne
        /// Try Again, the terminal host not answering now, or Close.
        case tryAgain
        /// Close alone: there is nowhere here to open one.
        case close
    }

    /// What a pane holding `session` says from its workspace's `listing`; nil when the listing names
    /// it live, or failed otherwise, so the refusal is said in its own words.
    public static func of(_ session: String, _ listing: Result<RunnerSessions, SailError>) -> RunnerReturn? {
        switch listing {
        case let .success(listed):
            guard let found = listed.sessions.first(where: { $0.name == session }) else { return RunnerReturn(said: gone, offer: .openANewOne) }
            guard !found.live else { return nil }
            guard let reason = found.endedReason, !reason.isEmpty else { return RunnerReturn(said: gone, offer: .openANewOne) }
            return RunnerReturn(said: "This terminal ended: \(reason)" + (reason.hasSuffix(".") ? "" : "."), offer: .openANewOne)
        case let .failure(.refused(refusal)) where [.known(.notFound), .known(.projectNotInCatalog)].contains(refusal.code):
            return RunnerReturn(said: elsewhere, offer: .close)
        case let .failure(.refused(refusal)) where refusal.code == .known(.terminalUnavailable):
            return RunnerReturn(said: refusal.lines.joined(separator: " "), offer: .tryAgain)
        case .failure:
            return nil
        }
    }
}
