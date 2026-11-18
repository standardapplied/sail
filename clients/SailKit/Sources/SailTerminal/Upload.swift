import SailAPI
import SailTerminalWire
import Synchronization

/// A file to upload: its size, and its bytes read in order, at most the count asked for each time.
public struct UploadSource: Sendable {
    public var size: Int64
    public var read: @Sendable (_ count: Int) throws -> [UInt8]

    public init(size: Int64, read: @escaping @Sendable (_ count: Int) throws -> [UInt8]) {
        self.size = size
        self.read = read
    }

    /// `bytes`, held in memory.
    public static func bytes(_ bytes: [UInt8]) -> UploadSource {
        let offset = Mutex(0)
        return UploadSource(size: Int64(bytes.count)) { count in
            offset.withLock { offset in
                defer { offset = min(bytes.count, offset + count) }
                return Array(bytes[offset ..< min(bytes.count, offset + count)])
            }
        }
    }
}

/// A source that ended before the size it declared.
struct UploadSourceShrank: Error {}

/// How an upload shares its connection with the person's typing: chunks of ``chunk`` bytes, each
/// frame written on its own so a key waits behind one chunk at most, and no more than ``window``
/// of them sent and not yet acknowledged, so the bytes queued ahead of a key in the socket and the
/// network stay that few.
public enum UploadPacing {
    /// The bytes one `PutChunk` carries: half the host's bound of 64 KiB, so a key typed behind a
    /// chunk waits for 32 KiB at most.
    public static let chunk = 32 * 1024
    public static let window = 2
    /// The largest file uploaded: the host's bound, checked here first so it is refused before a
    /// byte is sent.
    public static let largest: Int64 = 64 << 20
}

/// The upload a session has in flight, at most one, and the order the next ones wait in.
final class Uploads: Sendable {
    private struct Gate {
        var holder: Int?
        var issued = 0
        var waiting: [(ticket: Int, turn: CheckedContinuation<Void, Never>)] = []
    }

    private let gate = Mutex(Gate())
    private let current = Mutex<UploadFlight?>(nil)
    private let nextID = Mutex<Int32>(0)

    /// Waits for the uploads begun before to finish: one connection carries one upload at a time.
    /// A task cancelled while it waits stops waiting and throws `CancellationError`.
    func enter() async throws {
        let ticket = gate.withLock { gate in
            defer { gate.issued += 1 }
            return gate.issued
        }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (turn: CheckedContinuation<Void, Never>) in
                let now = gate.withLock { gate in
                    guard gate.holder != nil else {
                        gate.holder = ticket
                        return true
                    }
                    if Task.isCancelled { return true }
                    gate.waiting.append((ticket, turn))
                    return false
                }
                if now { turn.resume() }
            }
        } onCancel: {
            let turn = gate.withLock { gate -> CheckedContinuation<Void, Never>? in
                guard let index = gate.waiting.firstIndex(where: { $0.ticket == ticket }) else { return nil }
                return gate.waiting.remove(at: index).turn
            }
            turn?.resume()
        }
        guard gate.withLock({ $0.holder == ticket }) else { throw CancellationError() }
        if Task.isCancelled {
            leave()
            throw CancellationError()
        }
    }

    /// How many uploads wait their turn.
    var waiting: Int {
        gate.withLock { $0.waiting.count }
    }

    func leave() {
        let next = gate.withLock { gate -> CheckedContinuation<Void, Never>? in
            guard !gate.waiting.isEmpty else {
                gate.holder = nil
                return nil
            }
            let next = gate.waiting.removeFirst()
            gate.holder = next.ticket
            return next.turn
        }
        next?.resume()
    }

    /// A flight for an upload on `connection`, numbered apart from every other on the session.
    func begin(on connection: Connection) -> UploadFlight {
        let id = nextID.withLock { id in
            defer { id = id == .max ? 0 : id + 1 }
            return id
        }
        let flight = UploadFlight(id: id, connection: connection)
        current.withLock { $0 = flight }
        return flight
    }

    func finish(_ flight: UploadFlight) {
        current.withLock { if $0 === flight { $0 = nil } }
    }

    /// Takes the host's answer `frame` to the upload in flight; an answer to an upload no longer
    /// in flight is dropped.
    func take(_ frame: Frame) {
        let flight = current.withLock { $0 }
        switch frame {
        case let .putAck(id, received):
            if flight?.id == id { flight?.settle(.acked(received)) }
        case let .putStored(id, path):
            if flight?.id == id { flight?.settle(.stored(path)) }
        case let .putRefused(id, kind, limit):
            if flight?.id == id { flight?.settle(.refused(kind, limit)) }
        default:
            break
        }
    }

    /// `connection` dropped: an upload on it is lost with it. Nothing was kept when its end was not
    /// sent; when it was, whether the host stored it is not known.
    func dropped(_ connection: Connection) {
        current.withLock { $0 }?.dropped(connection)
    }

    /// The session will not attach again for now: an upload in flight ends as ``UploadFlight/Outcome/ended``
    /// when `ended` says the session itself ended, else as lost.
    func over(sessionEnded: Bool) {
        current.withLock { $0 }?.over(sessionEnded: sessionEnded)
    }
}

/// One upload, between the thread that sends it and the thread that reads the host's answers.
final class UploadFlight: Sendable {
    enum Answer {
        case acked(Int64)
        case stored(String)
        case refused(Open<UploadRefusal.Kind>, Int64)
    }

    enum Outcome: Equatable {
        case stored(String)
        case refused(Open<UploadRefusal.Kind>, Int64)
        case ended
        case cancelled
    }

    private struct State {
        var acked: Int64 = -1
        var outcome: Outcome?
        var waiter: (need: Int64, turn: CheckedContinuation<Void, Never>)?
        var connection: Connection?
        var endSent = false
    }

    let id: Int32
    private let state: Mutex<State>

    init(id: Int32, connection: Connection) {
        self.id = id
        state = Mutex(State(connection: connection))
    }

    /// The bytes the host has said it holds.
    var acked: Int64 {
        max(0, state.withLock { $0.acked })
    }

    func settle(_ answer: Answer) {
        let woken = state.withLock { state -> CheckedContinuation<Void, Never>? in
            switch answer {
            case let .acked(received): state.acked = max(state.acked, received)
            case let .stored(path): state.outcome = state.outcome ?? .stored(path)
            case let .refused(kind, limit): state.outcome = state.outcome ?? .refused(kind, limit)
            }
            return Self.woken(&state)
        }
        woken?.resume()
    }

    private func end(_ outcome: Outcome) {
        let woken = state.withLock { state -> CheckedContinuation<Void, Never>? in
            state.outcome = state.outcome ?? outcome
            return Self.woken(&state)
        }
        woken?.resume()
    }

    private static func woken(_ state: inout State) -> CheckedContinuation<Void, Never>? {
        guard let waiter = state.waiter, state.outcome != nil || state.acked >= waiter.need else { return nil }
        state.waiter = nil
        return waiter.turn
    }

    private static func lost(_ endSent: Bool) -> Outcome {
        .refused(.known(endSent ? .notKnown : .connectionLost), 0)
    }

    /// Sends `frame` of this upload, before its end, on its connection.
    func send(_ frame: Frame) throws(TerminalError) {
        let connection = state.withLock { state in
            if case .putEnd = frame { state.endSent = true }
            return state.connection
        }
        try connection?.send(frame)
    }

    /// See ``Uploads/dropped(_:)``.
    func dropped(_ connection: Connection) {
        let lost = state.withLock { state -> Outcome? in
            guard state.connection === connection else { return nil }
            state.connection = nil
            return Self.lost(state.endSent)
        }
        if let lost { end(lost) }
    }

    /// See ``Uploads/over(sessionEnded:)``.
    func over(sessionEnded: Bool) {
        let endSent = state.withLock { $0.endSent }
        end(sessionEnded ? .ended : Self.lost(endSent))
    }

    /// Waits until the host holds `need` bytes or the upload has an outcome: the outcome, nil while
    /// it goes on. Cancelling the waiting task ends the wait as ``Outcome/cancelled``.
    func wait(until need: Int64) async -> Outcome? {
        await withTaskCancellationHandler {
            await withCheckedContinuation { (turn: CheckedContinuation<Void, Never>) in
                let now = state.withLock { state in
                    if Task.isCancelled { state.outcome = state.outcome ?? .cancelled }
                    guard state.outcome == nil, state.acked < need else { return true }
                    state.waiter = (need, turn)
                    return false
                }
                if now { turn.resume() }
            }
        } onCancel: {
            end(.cancelled)
        }
        return state.withLock { $0.outcome }
    }

    /// Waits for the outcome of an upload whose end was sent: the host's answer, which a cancel of
    /// the waiting task does not change, or the connection's loss.
    func outcome() async -> Outcome {
        await withCheckedContinuation { (turn: CheckedContinuation<Void, Never>) in
            let now = state.withLock { state in
                guard state.outcome == nil else { return true }
                state.waiter = (.max, turn)
                return false
            }
            if now { turn.resume() }
        }
        return state.withLock { $0.outcome } ?? .ended
    }
}
