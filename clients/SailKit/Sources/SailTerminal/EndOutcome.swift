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
    /// session gone already, by the refusal's code; anything else leaves the end unconfirmed.
    static func of(_ error: TerminalError) -> EndOutcome {
        switch error {
        case let .ticketRefused(refusal) where refusal.code == .known(.notFound): .gone
        case let .ticketRefused(refusal): .unconfirmed(refusal.lines)
        case let .refused(refusal): .unconfirmed(refusal.lines)
        default: .unconfirmed([Said.sentence(error.description)])
        }
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
    public func end(_ session: String, within limit: Duration = Door.endLimit) async -> EndOutcome {
        let seconds = max(1, Int(limit.components.seconds))
        let ended: EndOutcome? = await Self.within(limit) { () async -> EndOutcome in
            do throws(TerminalError) {
                try await self.kill(session, timeout: seconds)
                return .ended
            } catch {
                return .of(error)
            }
        }
        return ended ?? .unconfirmed(["The runner did not answer within \(seconds) s."])
    }

    /// Ends `session` on a connection of its own, each answer awaited at most `timeout` seconds.
    func kill(_ session: String, timeout: Int) async throws(TerminalError) {
        let entry = try await entry(for: .attach(session: session))
        let asWriter = if case .runner = self { true } else { false }
        try await SessionListing.offThread { () throws(TerminalError) in
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
        do throws(TerminalError) {
            try answer(to: "the Kill of '\(session)'", on: connection, within: timeout)
        } catch {
            guard asWriter, case .refused = error else { throw error }
            throw .refused(HostRefusal(message: someoneElseHoldsIt, remedy: nil))
        }
    }

    /// Why a runner refuses the Kill of a session attached as its writer: the door admits it only
    /// from the write token's holder, so someone else holds it. Said so, never in the door's words,
    /// which name the session's own name.
    static let someoneElseHoldsIt = "Someone else holds this terminal's keyboard, so the runner did not end it."

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
