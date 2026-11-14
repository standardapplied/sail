import Foundation
import SailAPI
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
    private let changed = Alarm { try await Task.sleep(for: $0) }

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

    /// Waits until the waits held are `durations`, failing after five seconds.
    func until(held durations: [Duration]) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while held != durations {
            let left = deadline - ContinuousClock.now
            guard left > .zero else { throw HostError("the waits held are \(held), never \(durations)") }
            await changed.wait(left)
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

/// Tickets as a control plane hands them out, each one new, every request kept.
final class TicketDesk: TicketSource {
    private let state = Mutex<(issued: Int, requests: [TicketRequest], refusal: Refusal?)>((0, [], nil))
    private let address: HostAddress

    init(address: HostAddress) {
        self.address = address
    }

    var requests: [TicketRequest] {
        state.withLock { $0.requests }
    }

    /// Every request from now on is refused so.
    func refuse(_ refusal: Refusal) {
        state.withLock { $0.refusal = refusal }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
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

extension FakeHost.Peer {
    /// Takes an attach (the create before it too, when there is one) and accepts it, then sends the
    /// attach's prologue: the geometry, an empty replay and the writer.
    func acceptAttach(columns: Int32 = 80, rows: Int32 = 24, writer: String = "ada") async throws -> Frame {
        var frame = try await read()
        if case .create = frame {
            try send(.accepted)
            frame = try await read()
        }
        guard case .attach = frame else { throw HostError("expected an attach, got \(frame)") }
        try send([.accepted, .resized(TerminalSize(columns: columns, rows: rows)), .replayBegin(safe: true), .replayEnd, .writerChanged(fde: writer)])
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
    sleep: @escaping @Sendable (Duration) async throws -> Void = Naps().sleep
) async throws -> (FakeHost, TerminalSession, FakeHost.Peer, Watch) {
    let host = try FakeHost()
    let size = TerminalSize(columns: Int32(columns), rows: Int32(rows))
    let create = SessionCreate(session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: size)
    let session = TerminalSession(
        open: create, door: .local(host.address), screen: try screen(columns: columns, rows: rows), timeout: 5,
        stableAfter: stableAfter, sleep: sleep
    )
    let watch = Watch(session)
    session.start()
    let peer = try await host.accept()
    try await peer.welcome()
    _ = try await peer.acceptAttach(columns: size.columns, rows: size.rows)
    try await watch.until("took the attach's prologue") { watch.seen.contains(.writerChanged("ada")) }
    return (host, session, peer, watch)
}
