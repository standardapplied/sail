#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif
import Foundation
import SailAPI
import SailTerminal
import SailTerminalWire
import Synchronization

/// A runner's terminal door played by a test or the drive, on a loopback port, refusing what the
/// real door refuses, in its words: it welcomes a ticket it issued, once, and nothing else; a
/// connection does only what its ticket covers (an open ticket makes one session in its workspace
/// and attaches to it, an attach ticket attaches to its session), and the door hangs up on any
/// other frame; it ends a session only for the connection attached to it as its writer. A refused
/// attach is answered and the connection goes on. A test can have someone else hold a session's
/// write token, and hold a `Create`'s answer while the host makes it. Its live sessions are its
/// session list.
///
/// Made `thisMac`, it plays this Mac's own host instead, as the real one admits its person: a
/// blank token is welcomed, a connection does anything, a Kill ends the session it names or is
/// refused as no session, and ListSessions lists the live ones. A test can have it refuse or
/// hold its Kills.
public final class FakeRunnerDoor: Sendable {
    public let port: UInt16
    private let listener: Int32
    private let thisMac: Bool
    private let state = Mutex(State())
    private let creates = Mutex(Creates())
    private let killing = Mutex(Killing())

    private struct State {
        var issued = 0
        var unused: [String: TicketRequest] = [:]
        var used: Set<String> = []
        var live: [String] = []
        /// Each live session's writer: the connection's number, or 0 for someone else.
        var writers: [String: Int] = [:]
        var connections = 0
        var kills: [String] = []
    }

    /// The `Create`s the door holds before it makes their sessions.
    private struct Creates {
        var holding = false
        var losing = false
        var received = 0
        var held: [DispatchSemaphore] = []
    }

    /// What this Mac's host does with the Kills it receives.
    private struct Killing {
        var refusal: String?
        var holding = false
        var held: [DispatchSemaphore] = []
    }

    public init(thisMac: Bool = false) throws {
        self.thisMac = thisMac
        let socket = Self.socket()
        guard socket >= 0 else { throw DoorError("no socket for the door") }
        var address = sockaddr_in()
        #if canImport(Darwin)
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        #endif
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socket, $0, size) } }
        guard bound == 0, listen(socket, 16) == 0 else {
            close(socket)
            throw DoorError("the door could not listen")
        }
        var named = size
        _ = withUnsafeMutablePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socket, $0, &named) } }
        port = UInt16(bigEndian: address.sin_port)
        listener = socket
        Thread { [self] in serve() }.start()
    }

    /// Where the door listens, as a ticket names it.
    public var address: String {
        "127.0.0.1:\(port)"
    }

    /// A ticket for one connection doing `request`, as the API hands one out for the real door.
    public func issue(for request: TicketRequest) -> TerminalTicket {
        let ticket = state.withLock { state in
            state.issued += 1
            let ticket = "tkt_door_\(state.issued)"
            state.unused[ticket] = request
            return ticket
        }
        return TerminalTicket(ticket: ticket, address: address, expiresAt: Date().addingTimeInterval(30))
    }

    /// From now on a `Create` is made and its answer lost: the door hangs up before it answers, as a
    /// connection that drops while the runner starts a workspace.
    public func loseCreateAnswers() {
        creates.withLock { $0.losing = true }
    }

    /// From now on a `Create` waits, unanswered and its session not yet made, until ``releaseCreates()``:
    /// a workspace still starting.
    public func holdCreates() {
        creates.withLock { $0.holding = true }
    }

    /// The `Create`s held go on: each session is made, then answered, as the real host does.
    public func releaseCreates() {
        let held = creates.withLock { creates in
            creates.holding = false
            defer { creates.held = [] }
            return creates.held
        }
        held.forEach { $0.signal() }
    }

    /// How many `Create`s the door has received, held or not.
    public var createsReceived: Int {
        creates.withLock { $0.received }
    }

    /// The sessions it holds now, as the host lists them.
    public var sessions: [String] {
        state.withLock { $0.live }
    }

    /// The sessions it was asked to end, in order, whether or not it did.
    public var kills: [String] {
        state.withLock { $0.kills }
    }

    /// From now on this Mac's host refuses every Kill, in `words`, its session left running; nil
    /// ends them again.
    public func refuseKills(saying words: String?) {
        killing.withLock { $0.refusal = words }
    }

    /// From now on a Kill waits, unanswered and its session running, until ``releaseKills()``.
    public func holdKills() {
        killing.withLock { $0.holding = true }
    }

    /// The Kills held go on: each session is ended, then answered.
    public func releaseKills() {
        let held = killing.withLock { killing in
            killing.holding = false
            defer { killing.held = [] }
            return killing.held
        }
        held.forEach { $0.signal() }
    }

    /// Someone else holds `session`'s write token from now on: an attach asking for it observes.
    public func holdWrite(_ session: String) {
        state.withLock { $0.writers[session] = 0 }
    }

    /// Someone else lets go of `session`'s write token.
    public func release(_ session: String) {
        state.withLock { state in
            if state.writers[session] == 0 { state.writers[session] = nil }
        }
    }

    /// The runner ends `session` on its own (its workspace stopped, say): no longer listed.
    public func forget(_ session: String) {
        state.withLock { state in
            state.live.removeAll { $0 == session }
            state.writers[session] = nil
        }
    }

    public func stop() {
        shutdown(listener, Int32(SHUT_RDWR))
        close(listener)
    }

    /// The real door's words for a Kill of its own session from a connection that does not hold
    /// the session's write token.
    public static let notTheWriter = "This connection does not hold the terminal's keyboard, so it was not ended; someone else may hold it."

    private static func socket() -> Int32 {
        #if canImport(Darwin)
        Darwin.socket(AF_INET, SOCK_STREAM, 0)
        #else
        Glibc.socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        #endif
    }

    private func serve() {
        while true {
            let connection = accept(listener, nil, nil)
            guard connection >= 0 else { return }
            #if canImport(Darwin)
            var one: Int32 = 1
            setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            #endif
            let number = state.withLock { state in
                state.connections += 1
                return state.connections
            }
            Thread { [self] in converse(connection, number) }.start()
        }
    }

    private func converse(_ connection: Int32, _ number: Int) {
        defer {
            state.withLock { state in
                for (session, writer) in state.writers where writer == number {
                    state.writers[session] = nil
                }
            }
            close(connection)
        }
        guard receive(8, on: connection) == Handshake.magic else { return }
        transmit(Handshake.magic, on: connection)
        guard case let .hello(token) = frame(on: connection) else { return }
        if thisMac, token.isEmpty {
            answer(.welcome(hostBootID: "mac-1"), on: connection)
            return served(connection)
        }
        switch admit(token) {
        case let .success(ticket):
            answer(.welcome(hostBootID: "door-1"), on: connection)
            attached(connection, number, ticket)
        case let .failure(refused):
            answer(.refused(HostRefusal(message: refused.description, remedy: [])), on: connection)
        }
    }

    /// What `token` admits, spent now; or why it is not welcomed.
    private func admit(_ token: String) -> Result<TicketRequest, DoorError> {
        state.withLock { state in
            if let ticket = state.unused.removeValue(forKey: token) {
                state.used.insert(token)
                return .success(ticket)
            }
            return .failure(DoorError(
                state.used.contains(token)
                    ? "This ticket was presented already, and a ticket admits one connection; ask the API for a new one."
                    : "This is not a ticket this host issued; ask the API for a new one."
            ))
        }
    }

    private func attached(_ connection: Int32, _ number: Int, _ ticket: TicketRequest) {
        var opened: String?
        while let frame = frame(on: connection) {
            if let outside = Self.refusal(of: frame, under: ticket, opened: opened) {
                return answer(.refused(HostRefusal(message: outside, remedy: [])), on: connection)
            }
            switch frame {
            case let .create(create):
                made(create.session)
                if creates.withLock({ $0.losing }) { return }
                opened = create.session
                answer(.accepted, on: connection)
            case let .attach(session, write):
                let live = state.withLock { state in
                    guard state.live.contains(session) else { return false }
                    if write, state.writers[session] != 0 { state.writers[session] = number }
                    return true
                }
                answer(live ? .accepted : .refused(HostRefusal(message: "No live session '\(session)' to attach.", remedy: [])), on: connection)
            case let .kill(session):
                let ended = state.withLock { state in
                    state.kills.append(session)
                    guard state.writers[session] == number else { return false }
                    state.live.removeAll { $0 == session }
                    state.writers[session] = nil
                    return true
                }
                guard ended else {
                    return answer(.refused(HostRefusal(message: Self.notTheWriter, remedy: [])), on: connection)
                }
                answer(.sessionEnded(reason: "ended"), on: connection)
                answer(.accepted, on: connection)
            default:
                continue
            }
        }
    }

    /// This Mac's host serving its person, who may do anything.
    private func served(_ connection: Int32) {
        while let frame = frame(on: connection) {
            switch frame {
            case let .create(create):
                made(create.session)
                answer(.accepted, on: connection)
            case let .attach(session, _):
                let live = state.withLock { $0.live.contains(session) }
                answer(live ? .accepted : .refused(HostRefusal(message: "No live session '\(session)' to attach.", remedy: [])), on: connection)
            case let .kill(session):
                answer(killed(session), on: connection)
            case .listSessions:
                let live = state.withLock { $0.live }.map { SessionInfo(name: $0, instanceID: "i-\($0)", live: true, attached: 0, writerFDE: "", room: "", command: []) }
                answer(.sessions(SessionPage(sessions: live, next: "")), on: connection)
            default:
                continue
            }
        }
    }

    /// This Mac's host's answer to a Kill of `session`, once Kills are no longer held.
    private func killed(_ session: String) -> Frame {
        let (held, refusal) = killing.withLock { killing -> (DispatchSemaphore?, String?) in
            guard killing.holding else { return (nil, killing.refusal) }
            let held = DispatchSemaphore(value: 0)
            killing.held.append(held)
            return (held, killing.refusal)
        }
        held?.wait()
        let ended = state.withLock { state in
            state.kills.append(session)
            guard refusal == nil, state.live.contains(session) else { return false }
            state.live.removeAll { $0 == session }
            return true
        }
        if let refusal { return .refused(HostRefusal(message: refusal, remedy: [])) }
        return ended ? .accepted : .refused(HostRefusal(message: "No session '\(session)'.", remedy: []))
    }

    /// Makes `session`, once the `Create`s are no longer held: as the host does, before it answers.
    private func made(_ session: String) {
        let held = creates.withLock { creates -> DispatchSemaphore? in
            creates.received += 1
            guard creates.holding else { return nil }
            let held = DispatchSemaphore(value: 0)
            creates.held.append(held)
            return held
        }
        held?.wait()
        state.withLock { $0.live.append(session) }
    }

    /// The real door's words for a frame its ticket does not cover, nil when it does.
    static func refusal(of frame: Frame, under ticket: TicketRequest, opened: String?) -> String? {
        let own: String? = switch ticket {
        case .open: opened
        case let .attach(session): session
        }
        let covered = switch frame {
        case let .create(create):
            if case let .open(project, workspace) = ticket { opened == nil && create.project == project && create.workspace == workspace } else { false }
        case let .attach(session, _): session == own
        case let .kill(session): session == own
        default: true
        }
        guard !covered else { return nil }
        let scope = switch ticket {
        case let .open(project, workspace): workspace.isEmpty ? "open a session in project '\(project)'" : "open a session in workspace '\(workspace)' of project '\(project)'"
        case let .attach(session): "attach to session '\(session)'"
        }
        return "This connection's ticket is to \(scope), and it admits nothing else; ask the API for a ticket for that."
    }

    private func frame(on connection: Int32) -> Frame? {
        guard let header = receive(4, on: connection) else { return nil }
        let length = header.reduce(0) { $0 << 8 | Int($1) }
        guard length > 0, length <= FrameCodec.maxPayload, let payload = receive(length, on: connection) else { return nil }
        return try? FrameCodec.decode(payload: payload)
    }

    private func receive(_ count: Int, on connection: Int32) -> [UInt8]? {
        var bytes = [UInt8](repeating: 0, count: count)
        var got = 0
        while got < count {
            let read = bytes.withUnsafeMutableBytes { recv(connection, $0.baseAddress! + got, count - got, 0) }
            guard read > 0 else { return nil }
            got += read
        }
        return bytes
    }

    private func answer(_ frame: Frame, on connection: Int32) {
        guard let bytes = try? FrameCodec.encode(frame) else { return }
        transmit(bytes, on: connection)
    }

    private func transmit(_ bytes: [UInt8], on connection: Int32) {
        #if canImport(Darwin)
        let flags: Int32 = 0
        #else
        let flags = Int32(MSG_NOSIGNAL)
        #endif
        _ = bytes.withUnsafeBytes { send(connection, $0.baseAddress, $0.count, flags) }
    }
}

public struct DoorError: Error, CustomStringConvertible {
    public let description: String

    public init(_ description: String) {
        self.description = description
    }
}
