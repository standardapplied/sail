import Foundation
import SailTerminalWire

/// The sessions this machine's own host holds for its person: read page by page, or one ended
/// without attaching to it.
public enum SessionListing {
    /// How many sessions one page asks for.
    public static let pageSize: Int32 = 100
    /// The most sessions a listing takes before it calls the host's paging broken.
    public static let largest = 10_000

    /// Every session the local host at `address` lists, in name order, each answer awaited at most
    /// `timeout` seconds (1 or more: nothing waits for ever). A page whose cursor does not move
    /// forward, or a listing past ``largest``, is a protocol violation.
    public static func sessions(at address: HostAddress, timeout: Int = 10) async throws(TerminalError) -> [SessionInfo] {
        guard timeout >= 1 else { throw .unsupported("a listing waits 1 s or more for each answer, not \(timeout) s") }
        return try await offThread { () throws(TerminalError) in try list(at: address, timeout: timeout) }
    }

    /// Ends `session` at the local host at `address`, as closing a pane that holds no connection
    /// to the host does; the answer awaited at most `timeout` seconds.
    public static func end(_ session: String, at address: HostAddress, timeout: Int = 10) async throws(TerminalError) {
        guard timeout >= 1 else { throw .unsupported("ending a session waits 1 s or more for the answer, not \(timeout) s") }
        try await offThread { () throws(TerminalError) in try kill(session, at: address, timeout: timeout) }
    }

    private static func offThread<T: Sendable>(_ work: @escaping @Sendable () throws(TerminalError) -> T) async throws(TerminalError) -> T {
        let result: Result<T, TerminalError> = await withCheckedContinuation { answer in
            Thread { answer.resume(returning: Result { () throws(TerminalError) in try work() }) }.start()
        }
        return try result.get()
    }

    private static func kill(_ session: String, at address: HostAddress, timeout: Int) throws(TerminalError) {
        let connection = try Connection.connect(to: address, timeout: timeout)
        defer { connection.close() }
        _ = try connection.admit(token: "")
        try connection.send(.kill(session: session))
        switch try connection.next() {
        case .accepted?: return
        case let .refused(refusal)?: throw .refused(refusal)
        case nil: throw .closed
        case let other?: throw .protocolViolation("it answered Kill with \(other.name)")
        }
    }

    private static func list(at address: HostAddress, timeout: Int) throws(TerminalError) -> [SessionInfo] {
        let connection = try Connection.connect(to: address, timeout: timeout)
        defer { connection.close() }
        _ = try connection.admit(token: "")
        var sessions: [SessionInfo] = []
        var after = ""
        while true {
            try connection.send(.listSessions(after: after, limit: pageSize))
            switch try connection.next() {
            case let .sessions(page):
                sessions += page.sessions
                guard sessions.count <= largest else { throw .protocolViolation("it listed more than \(largest) sessions") }
                if page.next.isEmpty { return sessions }
                guard page.next > after else { throw .protocolViolation("its listing went back to '\(page.next)' after '\(after)'") }
                after = page.next
            case let .refused(refusal): throw .refused(refusal)
            case nil: throw .closed
            case let other?: throw .protocolViolation("it answered ListSessions with \(other.name)")
            }
        }
    }
}
