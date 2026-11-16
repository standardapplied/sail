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
    /// The most bytes one `PutChunk` carries: the host's bound.
    public static let chunk = 32 * 1024
    public static let window = 2
    /// The largest file uploaded: the host's bound, checked here first so it is refused before a
    /// byte is sent.
    public static let largest: Int64 = 64 << 20
}

/// The upload a session has in flight, at most one, and the order the next ones wait in.
final class Uploads: Sendable {
    private struct Gate {
        var busy = false
        var waiting: [CheckedContinuation<Void, Never>] = []
    }

    private let gate = Mutex(Gate())
    private let current = Mutex<UploadFlight?>(nil)
    private let nextID = Mutex<Int32>(0)

    /// Waits for the uploads begun before to finish: one connection carries one upload at a time.
    func enter() async {
        await withCheckedContinuation { (turn: CheckedContinuation<Void, Never>) in
            let now = gate.withLock { gate in
                guard gate.busy else {
                    gate.busy = true
                    return true
                }
                gate.waiting.append(turn)
                return false
            }
            if now { turn.resume() }
        }
    }

    func leave() {
        let next = gate.withLock { gate -> CheckedContinuation<Void, Never>? in
            guard !gate.waiting.isEmpty else {
                gate.busy = false
                return nil
            }
            return gate.waiting.removeFirst()
        }
        next?.resume()
    }

    /// A flight for an upload on `connection`, numbered apart from every other on the session.
    func begin(on connection: Connection) -> UploadFlight {
        let id = nextID.withLock { id in
            defer { id &+= 1 }
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

    /// `connection` is gone: an upload on it ended with it.
    func ended(_ connection: Connection) {
        let flight = current.withLock { $0 }
        if flight?.connection === connection { flight?.settle(.ended) }
    }
}

/// One upload, between the thread that sends it and the thread that reads the host's answers.
final class UploadFlight: Sendable {
    enum Answer {
        case acked(Int64)
        case stored(String)
        case refused(Open<UploadRefusal.Kind>, Int64)
        case ended
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
    }

    let id: Int32
    let connection: Connection
    private let state = Mutex(State())

    init(id: Int32, connection: Connection) {
        self.id = id
        self.connection = connection
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
            case .ended: state.outcome = state.outcome ?? .ended
            }
            guard let waiter = state.waiter, state.outcome != nil || state.acked >= waiter.need else { return nil }
            state.waiter = nil
            return waiter.turn
        }
        woken?.resume()
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
            let woken = state.withLock { state -> CheckedContinuation<Void, Never>? in
                state.outcome = state.outcome ?? .cancelled
                defer { state.waiter = nil }
                return state.waiter?.turn
            }
            woken?.resume()
        }
        return state.withLock { $0.outcome }
    }

    /// Waits for the outcome.
    func outcome() async -> Outcome {
        await wait(until: .max) ?? .ended
    }
}
