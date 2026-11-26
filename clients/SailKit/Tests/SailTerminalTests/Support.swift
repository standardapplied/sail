import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailTerminal
import SailTerminalWire
import SailVT
@testable import sail_term_probe
import Synchronization

typealias Watch = Follower

/// Waits a session asks for, taken at once and recorded.
final class Naps: Sendable {
    private let taken = Mutex<[Duration]>([])

    var durations: [Duration] {
        taken.withLock { $0 }
    }

    func sleep(_ duration: Duration) async throws {
        taken.withLock { $0.append(duration) }
        try Task.checkCancellation()
    }
}

/// Waits a session asks for, each held until the test lets it pass or the session cancels it.
final class Timeline: Sendable {
    private struct Waiting {
        let duration: Duration
        let id: Int
        let release: CheckedContinuation<Void, any Error>
    }

    private let waiting = Mutex<(next: Int, held: [Waiting])>((0, []))
    private let changed = Alarm { _ in }

    func sleep(_ duration: Duration) async throws {
        let id = waiting.withLock { state in
            defer { state.next += 1 }
            return state.next
        }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { release in
                let cancelled = waiting.withLock { state in
                    guard !Task.isCancelled else { return true }
                    state.held.append(Waiting(duration: duration, id: id, release: release))
                    return false
                }
                if cancelled { release.resume(throwing: CancellationError()) }
                changed.ring()
            }
        } onCancel: {
            let cancelled = waiting.withLock { state -> CheckedContinuation<Void, any Error>? in
                guard let index = state.held.firstIndex(where: { $0.id == id }) else { return nil }
                return state.held.remove(at: index).release
            }
            cancelled?.resume(throwing: CancellationError())
            changed.ring()
        }
    }

    /// The waits held now, oldest first.
    var held: [Duration] {
        waiting.withLock { $0.held.map(\.duration) }
    }

    /// Waits until the waits held are `durations`, for as long as the test runs.
    func until(held durations: [Duration]) async throws {
        try await until("\(durations)") { held == durations }
    }

    private func until(_ wanted: String, _ done: () -> Bool) async throws {
        while !done() {
            guard !Task.isCancelled else { throw HostError("the waits held are \(held), never \(wanted)") }
            await changed.wait(nil)
        }
    }

    /// Lets every held wait pass.
    func passAll() {
        let released = waiting.withLock { state in
            defer { state.held = [] }
            return state.held.map(\.release)
        }
        released.forEach { $0.resume() }
    }
}

/// Timers the test fires by hand: each one set is kept, with its delay, until fired or called off.
final class ManualTimers: Sendable {
    private final class Entry: Scheduled {
        let delay: Duration
        let work: @Sendable () -> Void
        let cancelled = Mutex(false)

        init(delay: Duration, work: @escaping @Sendable () -> Void) {
            self.delay = delay
            self.work = work
        }

        func cancel() {
            cancelled.withLock { $0 = true }
        }
    }

    private let entries = Mutex<(set: Int, live: [Entry])>((0, []))
    private let changed = Alarm { _ in }
    private let awaited = Mutex(0)

    var after: HoldTick.After {
        { delay, work in
            let entry = Entry(delay: delay, work: work)
            self.entries.withLock { entries in
                entries.set += 1
                entries.live.append(entry)
            }
            self.changed.ring()
            return entry
        }
    }

    /// The delays of the timers set and neither fired nor called off.
    var live: [Duration] {
        entries.withLock { $0.live.filter { !$0.cancelled.withLock { $0 } }.map(\.delay) }
    }

    /// Returns once one more timer is set than this has returned for, for as long as the test runs.
    func awaitSet() async throws {
        let wanted = awaited.withLock { awaited in
            awaited += 1
            return awaited
        }
        while entries.withLock({ $0.set }) < wanted {
            guard !Task.isCancelled else { throw HostError("timer \(wanted) was never set") }
            await changed.wait(nil)
        }
    }

    /// Runs every live timer's work on this thread, as its timer would.
    func fireAll() {
        let due = entries.withLock { entries in
            defer { entries.live = [] }
            return entries.live
        }
        due.filter { !$0.cancelled.withLock { $0 } }.forEach { $0.work() }
    }
}

/// Tickets as a control plane hands them out, each one new, every request kept.
final class TicketDesk: TicketSource {
    private let state = Mutex<(issued: Int, requests: [TicketRequest], refusal: Refusal?)>((0, [], nil))
    private let address: HostAddress
    private let holdersAfter: Int
    private let seen = Mutex<[String]>([])

    /// `holdersAfter`: from the ask that follows that many requests, each ask also records who
    /// holds a TCP socket on the host's port, so a test that expects no more asks names what
    /// answered when one comes (``holders``).
    init(address: HostAddress, holdersAfter: Int = .max) {
        self.address = address
        self.holdersAfter = holdersAfter
    }

    var requests: [TicketRequest] {
        state.withLock { $0.requests }
    }

    /// What each ask past `holdersAfter` found on the host's port.
    var holders: [String] {
        seen.withLock { $0 }
    }

    /// Every request from now on is refused so.
    func refuse(_ refusal: Refusal) {
        state.withLock { $0.refusal = refusal }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        let asked = state.withLock { $0.requests.count }
        if asked >= holdersAfter, case let .tcp(_, port) = address {
            let found = PortHolders.of(port)
            seen.withLock { $0.append("ask \(asked + 1) at \(ContinuousClock.now): \(found)") }
        }
        let answer: Result<Int, SailError> = state.withLock { state in
            state.requests.append(request)
            if let refusal = state.refusal { return .failure(.refused(refusal)) }
            state.issued += 1
            return .success(state.issued)
        }
        let number = try answer.get()
        return TerminalTicket(ticket: "ticket-\(number)", address: address.description, expiresAt: Date().addingTimeInterval(30))
    }
}

func screen(columns: Int = 80, rows: Int = 24) throws -> Screen {
    try Screen(size: GridSize(columns: columns, rows: rows), scrollbackBytes: 1 << 20, palette: .dark)
}

func output(_ text: String, applied: Int64 = 0) -> Frame {
    .output(lastInputSequence: applied, bytes: Array(text.utf8))
}

extension Frame {
    /// This frame with an attach's client left out: each session names itself anew.
    var unnamed: Frame {
        guard case let .attach(session, write, _, takes) = self else { return self }
        return .attach(session: session, write: write, takes: takes)
    }
}

extension FakeHost.Peer {
    /// Takes an attach (the create before it too, when there is one) and accepts it, then sends the
    /// attach's prologue: the geometry, an empty replay and the writer, which is this client when it
    /// asked to write.
    func acceptAttach(columns: Int32 = 80, rows: Int32 = 24) async throws -> Frame {
        var frame = try await read()
        if case .create = frame {
            try send(.accepted)
            frame = try await read()
        }
        guard case let .attach(_, write, _, _) = frame else { throw HostError("expected an attach, got \(frame)") }
        try send([.accepted, .resized(TerminalSize(columns: columns, rows: rows)), .replayBegin(safe: true), .replayEnd, .writerChanged(fde: "ada", yours: write)])
        return frame
    }
}

/// A running total of bytes, counted from any thread.
final class ByteCount: Sendable {
    private let count = Mutex(0)

    var total: Int {
        count.withLock { $0 }
    }

    func add(_ bytes: Int) {
        count.withLock { $0 += bytes }
    }
}

/// This process's resident memory, where the system says it (`/proc/self/statm`); nil elsewhere.
func residentBytes() -> Int? {
    guard let statm = try? String(contentsOfFile: "/proc/self/statm", encoding: .utf8),
          let pages = statm.split(separator: " ").dropFirst().first.flatMap({ Int($0) })
    else { return nil }
    return pages * Int(sysconf(Int32(_SC_PAGESIZE)))
}

/// A session attached to a fake host; its connections count as stable at once unless
/// `stableAfter` says otherwise.
func attachedLocally(
    columns: Int = 80, rows: Int = 24, stableAfter: Duration = .zero,
    sleep: @escaping @Sendable (Duration) async throws -> Void = Naps().sleep,
    now: @escaping @Sendable () -> ContinuousClock.Instant = { ContinuousClock.now }
) async throws -> (FakeHost, TerminalSession, FakeHost.Peer, Watch) {
    let host = try FakeHost()
    let size = TerminalSize(columns: Int32(columns), rows: Int32(rows))
    let create = SessionCreate(session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: size)
    let session = TerminalSession(
        open: create, door: .local(host.address), screen: try screen(columns: columns, rows: rows), timeout: Patience.answer,
        stableAfter: stableAfter, sleep: sleep, now: now
    )
    let watch = Watch(session)
    session.start()
    let peer = try await host.accept()
    try await peer.welcome()
    _ = try await peer.acceptAttach(columns: size.columns, rows: size.rows)
    try await watch.until("took the attach's prologue") { watch.seen.contains { if case .writerChanged("ada", _) = $0 { true } else { false } } }
    return (host, session, peer, watch)
}

/// A clock a test moves by hand.
final class Hands: Sendable {
    private let instant = Mutex(ContinuousClock.now)

    var now: ContinuousClock.Instant {
        instant.withLock { $0 }
    }

    func advance(_ duration: Duration) {
        instant.withLock { $0 += duration }
    }
}

/// Who holds a TCP socket on a port: every socket of any process this account can read whose local
/// or foreign port it is, with its state. A connect to a stopped fake host that was answered is
/// named by it: a listener still there (which process), or a socket connected to itself.
enum PortHolders {
    static func of(_ port: UInt16) -> String {
        let found = sockets(on: port)
        return found.isEmpty ? "no socket on \(port)" : found.joined(separator: "; ")
    }

    #if canImport(Darwin)
    private static func sockets(on port: UInt16) -> [String] {
        var found: [String] = []
        let count = proc_listallpids(nil, 0)
        var pids = [pid_t](repeating: 0, count: Int(max(count, 0)) + 64)
        let listed = proc_listallpids(&pids, Int32(pids.count * MemoryLayout<pid_t>.size))
        for pid in pids.prefix(Int(max(listed, 0))) where pid > 0 {
            for descriptor in descriptors(of: pid) {
                var info = socket_fdinfo()
                let size = Int32(MemoryLayout<socket_fdinfo>.size)
                guard proc_pidfdinfo(pid, descriptor, PROC_PIDFDSOCKETINFO, &info, size) == size, info.psi.soi_kind == Int32(SOCKINFO_TCP) else { continue }
                let tcp = info.psi.soi_proto.pri_tcp
                let local = UInt16(bigEndian: UInt16(truncatingIfNeeded: tcp.tcpsi_ini.insi_lport))
                let foreign = UInt16(bigEndian: UInt16(truncatingIfNeeded: tcp.tcpsi_ini.insi_fport))
                guard local == port || foreign == port else { continue }
                var name = [CChar](repeating: 0, count: 256)
                proc_name(pid, &name, UInt32(name.count))
                found.append("pid \(pid) \(String(cString: name)) fd \(descriptor): \(local) to \(foreign), state \(tcp.tcpsi_state)")
            }
        }
        return found
    }

    private static func descriptors(of pid: pid_t) -> [Int32] {
        let stride = MemoryLayout<proc_fdinfo>.stride
        let size = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, nil, 0)
        guard size > 0 else { return [] }
        var listed = [proc_fdinfo](repeating: proc_fdinfo(), count: Int(size) / stride + 8)
        let filled = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, &listed, Int32(listed.count * stride))
        return listed.prefix(Int(max(filled, 0)) / stride).filter { $0.proc_fdtype == UInt32(PROX_FDTYPE_SOCKET) }.map(\.proc_fd)
    }
    #else
    private static func sockets(on port: UInt16) -> [String] {
        let suffix = String(format: ":%04X", port)
        var byInode: [String: String] = [:]
        for table in ["/proc/net/tcp", "/proc/net/tcp6"] {
            for line in ((try? String(contentsOfFile: table, encoding: .utf8)) ?? "").split(separator: "\n").dropFirst() {
                let fields = line.split(separator: " ")
                guard fields.count > 9, fields[1].hasSuffix(suffix) || fields[2].hasSuffix(suffix) else { continue }
                byInode[String(fields[9])] = "\(fields[1]) to \(fields[2]), state \(fields[3])"
            }
        }
        var found: [String] = []
        var named: Set<String> = []
        let files = FileManager.default
        for pid in (try? files.contentsOfDirectory(atPath: "/proc")) ?? [] where Int(pid) != nil {
            for descriptor in (try? files.contentsOfDirectory(atPath: "/proc/\(pid)/fd")) ?? [] {
                guard let link = try? files.destinationOfSymbolicLink(atPath: "/proc/\(pid)/fd/\(descriptor)"), link.hasPrefix("socket:[") else { continue }
                let inode = String(link.dropFirst(8).dropLast())
                guard let socket = byInode[inode] else { continue }
                let name = ((try? String(contentsOfFile: "/proc/\(pid)/comm", encoding: .utf8)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                found.append("pid \(pid) \(name) fd \(descriptor): \(socket)")
                named.insert(inode)
            }
        }
        return found + byInode.filter { !named.contains($0.key) }.map { "no process: \($0.value)" }
    }
    #endif
}
