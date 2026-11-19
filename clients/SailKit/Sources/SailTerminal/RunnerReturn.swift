import SailAPI

/// What a runner pane says when the runner would not attach its session at a reattach: ended, in
/// the host's words, while the workspace still lists it; gone when it no longer does.
public enum RunnerReturn {
    public static let gone = "This terminal is no longer on the runner: its workspace was stopped or deleted, or the runner restarted."

    /// What a pane holding `session` says from its workspace's `listing`; nil when it lists the
    /// session live, so the refusal was another, said in its own words.
    public static func said(_ session: String, in listing: RunnerSessions) -> String? {
        guard let listed = listing.sessions.first(where: { $0.name == session }) else { return gone }
        guard !listed.live else { return nil }
        guard let reason = listed.endedReason, !reason.isEmpty else { return gone }
        return "This terminal ended: \(reason)" + (reason.hasSuffix(".") ? "" : ".")
    }
}
