#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif
import Foundation
import SailAPI
import SailTerminalWire
import Synchronization

/// A runner's terminal door played by a test or the drive, on a loopback port, refusing what the
/// real door refuses: it welcomes a ticket it issued, once (a ticket presented again, or one it
/// never issued, is refused in the real door's words and the connection closed); it opens and
/// attaches sessions; it ends a session only for the connection attached to it as its writer, and
/// refuses any other Kill in the real door's words, hanging up as the real door does. A test can
/// have someone else hold a session's write token. Its live sessions are its session list.
public final class FakeRunnerDoor: Sendable {
    public let port: UInt16
    private let listener: Int32
    private let state = Mutex(State())

    private struct State {
        var issued = 0
        var unused: Set<String> = []
        var used: Set<String> = []
        var live: [String] = []
        /// Each live session's writer: the connection's number, or 0 for someone else.
        var writers: [String: Int] = [:]
        var connections = 0
        var kills: [String] = []
    }

    public init() throws {
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

    /// A ticket for one connection, as the API hands one out for the real door.
    public func issue() -> TerminalTicket {
        let ticket = state.withLock { state in
            state.issued += 1
            let ticket = "tkt_door_\(state.issued)"
            state.unused.insert(ticket)
            return ticket
        }
        return TerminalTicket(ticket: ticket, address: address, expiresAt: Date().addingTimeInterval(30))
    }

    /// The sessions it holds now, as the host lists them.
    public var sessions: [String] {
        state.withLock { $0.live }
    }

    /// The sessions it was asked to end, in order, whether or not it did.
    public var kills: [String] {
        state.withLock { $0.kills }
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

    /// The real door's words for a frame outside an attach ticket.
    public static func refusal(of session: String) -> String {
        "This connection's ticket is to attach to session '\(session)', and it admits nothing else; ask the API for a ticket for that."
    }

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
        guard let refused = admit(token) else {
            answer(.welcome(hostBootID: "door-1"), on: connection)
            return attached(connection, number)
        }
        answer(.refused(HostRefusal(message: refused, remedy: nil)), on: connection)
    }

    /// Why `token` is not welcomed, nil when it is: spent now.
    private func admit(_ token: String) -> String? {
        state.withLock { state in
            if state.unused.remove(token) != nil {
                state.used.insert(token)
                return nil
            }
            return state.used.contains(token)
                ? "This ticket was presented already, and a ticket admits one connection; ask the API for a new one."
                : "This is not a ticket this host issued; ask the API for a new one."
        }
    }

    private func attached(_ connection: Int32, _ number: Int) {
        var mine: String?
        while let frame = frame(on: connection) {
            switch frame {
            case let .create(create):
                state.withLock { $0.live.append(create.session) }
                answer(.accepted, on: connection)
            case let .attach(session, write):
                let live = state.withLock { state in
                    guard state.live.contains(session) else { return false }
                    if write, state.writers[session] != 0 { state.writers[session] = number }
                    return true
                }
                guard live else {
                    return answer(.refused(HostRefusal(message: "No session '\(session)'.", remedy: nil)), on: connection)
                }
                mine = session
                answer(.accepted, on: connection)
            case let .kill(session):
                let ended = state.withLock { state in
                    state.kills.append(session)
                    guard session == mine, state.writers[session] == number else { return false }
                    state.live.removeAll { $0 == session }
                    state.writers[session] = nil
                    return true
                }
                guard ended else {
                    return answer(.refused(HostRefusal(message: Self.refusal(of: session), remedy: nil)), on: connection)
                }
                answer(.sessionEnded(reason: "ended"), on: connection)
                answer(.accepted, on: connection)
            default:
                continue
            }
        }
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
