import Foundation
import SailAPI
import SailTerminalWire
import Synchronization

/// What ending a session at its host came to.
public enum EndOutcome: Equatable, Sendable {
    /// The host ended it.
    case ended
    /// It had ended already: the API has no session by that name for this person to attach.
    case gone
    /// The host did not confirm it ended, said why: it may still be running.
    case unconfirmed([String])

    /// Whether a tab whose end came to this may close: only when the session ended, or had; one
    /// not confirmed keeps its tab, which says it may still be running.
    public var closesTab: Bool {
        if case .unconfirmed = self { false } else { true }
    }

    /// What `error`, an end's failure, comes to: a ticket to attach refused as not found is a
    /// session gone already, by the refusal's code; anything else leaves the end unconfirmed, in the
    /// words of whoever refused it.
    static func of(_ error: TerminalError) -> EndOutcome {
        switch error {
        case let .ticketRefused(refusal) where refusal.code == .known(.notFound): .gone
        case let .ticketRefused(refusal): .unconfirmed(refusal.lines)
        case let .refused(refusal): .unconfirmed(refusal.lines)
        case let .signIn(needed): .unconfirmed(needed.lines)
        default: .unconfirmed([Said.sentence(error.description)])
        }
    }

    /// What is true of a session whose end was not confirmed, `tab` naming it by its tab's title,
    /// `why` the end's words: no timeout ends a session left detached, so it runs, and keeps its
    /// workspace running, until someone ends it or stops the workspace; on this Mac it runs until
    /// someone ends it, and the next launch opens it as a tab of its own. The one sentence a closed
    /// tab, a quit and a sign-out say it in.
    public static func mayStillRun(_ tab: String, _ why: [String], onThisMac: Bool = false) -> String {
        let (place, after) = onThisMac
            ? ("this Mac", "If it is, it keeps running until someone ends it, and Sail opens it as a tab of its own at its next launch.")
            : ("the runner", "If it is, it keeps running there, and keeps its workspace running, until someone ends it or stops the workspace.")
        return (["\(tab) may still be running on \(place)."] + why + [after]).joined(separator: " ")
    }
}

extension Door {
    /// How long an end waits in all, the ticket included, before it is unconfirmed.
    public static let endLimit = Duration.seconds(10)

    /// Ends `session` at the host this door reaches, on a connection of its own, waiting
    /// `limit` in all, the ticket included: a call still under way then is left to finish on its
    /// own and the end is unconfirmed. A runner's door admits Kill only from a connection attached
    /// to the session as its writer, so there it is an attach ticket for the session, attached as
    /// the writer, then Kill; when someone else holds the token the door refuses, in its words.
    /// `total` is the whole deadline this end is the rest of, which is what a person is told.
    public func end(_ session: String, within limit: Duration = Door.endLimit, of total: Duration? = nil) async -> EndOutcome {
        let said = "\(answerer) did not answer within \(max(1, Int((total ?? limit).components.seconds))) s."
        guard limit > .zero else { return .unconfirmed([said]) }
        let seconds = max(1, Int(limit.components.seconds))
        let ended: EndOutcome? = await Self.within(limit) { () async -> EndOutcome in
            do throws(TerminalError) {
                try await self.kill(session, timeout: seconds)
                return .ended
            } catch {
                if case .timedOut = error { return .unconfirmed([said]) }
                return await self.outcome(of: error, ending: session, timeout: seconds)
            }
        }
        return ended ?? .unconfirmed([said])
    }

    private var answerer: String {
        if case .runner = self { "The runner" } else { "This Mac's terminal host" }
    }

    /// What a failed end comes to. This Mac's host refuses a Kill of a session it no longer holds
    /// in words, with no code to tell it from any other refusal, so its own list says whether the
    /// session is gone; a host that cannot be reached, or hangs up, may have stopped, and every
    /// session it held ended with it, which is said.
    private func outcome(of error: TerminalError, ending session: String, timeout: Int) async -> EndOutcome {
        guard case let .local(address) = self else { return .of(error) }
        switch error {
        case let .unreachable(reason):
            return .unconfirmed(["This Mac's terminal host could not be reached (\(reason)); if it has stopped, this terminal ended with it."])
        case .closed:
            return .unconfirmed(["This Mac's terminal host hung up; if it has stopped, this terminal ended with it."])
        case .refused:
            guard let listed = try? await SessionListing.sessions(at: address, timeout: timeout),
                  !listed.contains(where: { $0.name == session && $0.live })
            else { return .of(error) }
            return .gone
        default:
            return .of(error)
        }
    }

    /// Ends `session` on a connection of its own, each answer awaited at most `timeout` seconds.
    func kill(_ session: String, timeout: Int) async throws(TerminalError) {
        let entry = try await entry(for: .attach(session: session))
        let asWriter = if case .runner = self { true } else { false }
        try await offThread { () throws(TerminalError) in
            try Self.kill(session, at: entry.address, token: entry.token, asWriter: asWriter, timeout: timeout)
        }
    }

    /// What `work` answers within `limit`, or nil when it has not by then; the work is not waited
    /// for past it.
    static func within<T: Sendable>(_ limit: Duration, _ work: @escaping @Sendable () async -> T) async -> T? {
        await withCheckedContinuation { (done: CheckedContinuation<T?, Never>) in
            let answered = Mutex(false)
            let answer = { @Sendable (value: T?) in
                let first = answered.withLock { was in
                    defer { was = true }
                    return !was
                }
                if first { done.resume(returning: value) }
            }
            Task { answer(await work()) }
            Task {
                try? await Task.sleep(for: limit)
                answer(nil)
            }
        }
    }

    private static func kill(_ session: String, at address: HostAddress, token: String, asWriter: Bool, timeout: Int) throws(TerminalError) {
        let connection = try Connection.connect(to: address, timeout: timeout)
        defer { connection.close() }
        _ = try connection.admit(token: token)
        if asWriter {
            try connection.send(.attach(session: session, write: true))
            try answer(to: "the attach to '\(session)'", on: connection, within: timeout)
        }
        try connection.send(.kill(session: session))
        try answer(to: "the Kill of '\(session)'", on: connection, within: timeout)
    }

    /// Waits at most `timeout` seconds for the host's answer to `what`, past the frames an
    /// attachment streams meanwhile.
    private static func answer(to what: String, on connection: Connection, within timeout: Int) throws(TerminalError) {
        let deadline = ContinuousClock.now + .seconds(timeout)
        while true {
            switch try connection.next() {
            case .accepted?: return
            case let .refused(refusal)?: throw .refused(refusal)
            case nil: throw .closed
            case .output?, .replayBegin?, .replayEnd?, .writerChanged?, .resized?, .paused?, .continued?, .sessionEnded?:
                guard ContinuousClock.now < deadline else { throw .timedOut(what) }
            case let other?: throw .protocolViolation("it answered \(what) with \(other.name)")
            }
        }
    }
}
