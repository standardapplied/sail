import Foundation
import SailAPI
import SailTerminal
import SailTerminalWire
import Synchronization

/// A workspace's files connection: through the runner's terminal door with a files ticket, kept up
/// through drops on the same reconnect waits a terminal rides (``ReconnectWaits``). It says what
/// happens and hands on every frame the host sends, a listing's pages gathered and sorted once on its
/// own reader thread, so the main thread is handed a whole listing in the order it shows; it decides
/// nothing about the tree (``FileTree`` does), and sends what it is given.
///
/// A files ticket never starts a stopped workspace: one refused as stopped, or a connection the host
/// ends with its stop's sentence, ends here, said as ``Phase/stopped(_:)``, until the person asks
/// again. Past its patience it stops trying, said as ``Phase/unreachable(_:)``, until
/// ``reconnectNow()``.
public final class FilesSession: Sendable {
    /// What the connection is doing, in words a person reads.
    public enum Phase: Equatable, Sendable {
        case connecting
        case connected
        /// Lost, and being tried again: why it was lost.
        case reconnecting(String)
        /// Tried for its patience and stopped: why; ``reconnectNow()`` tries again.
        case unreachable(String)
        /// The workspace is stopped, in the host's or the API's words (its stop's record); nothing
        /// starts it from here.
        case stopped(String)
        /// The workspace is gone.
        case gone(String)
        /// Refused for good (who may read it, a sign-in), in lines a person reads.
        case refused([String])
    }

    public enum Event: Sendable {
        case phase(Phase)
        /// Admitted: what the tree holds is to be sent again.
        case admitted
        case frame(Frame)
    }

    /// How long a connect and the host's first answers may take, in seconds.
    public static let timeout = 10
    /// The name of the thread that reads the host's frames.
    public static let readerName = "sh.standardapplied.sail.files.reader"

    public let project: String
    public let workspace: String
    public let events: AsyncStream<Event>

    private let door: Door
    private let waits: ReconnectWaits
    private let timeout: Int
    private let alarm: Alarm
    private let sink: AsyncStream<Event>.Continuation
    private let state = Mutex(State())

    private struct State {
        var connection: Connection?
        var supervisor: Task<Void, Never>?
        var closed = false
    }

    public convenience init(project: String, workspace: String, door: Door, waits: ReconnectWaits = .standard, timeout: Int = FilesSession.timeout) {
        self.init(project: project, workspace: workspace, door: door, waits: waits, timeout: timeout) { try await Task.sleep(for: $0) }
    }

    package init(
        project: String, workspace: String, door: Door, waits: ReconnectWaits, timeout: Int, sleep: @escaping @Sendable (Duration) async throws -> Void
    ) {
        self.project = project
        self.workspace = workspace
        self.door = door
        self.waits = waits
        self.timeout = timeout
        alarm = Alarm(sleep: sleep)
        (events, sink) = AsyncStream.makeStream(of: Event.self, bufferingPolicy: .unbounded)
    }

    /// Connects, and keeps connecting until it ends.
    public func start() {
        state.withLock { state in
            guard state.supervisor == nil, !state.closed else { return }
            state.supervisor = Task { await self.supervise() }
        }
    }

    /// Sends `frames` on the connection admitted now; nothing while none is, as the tree sends what
    /// it holds again at the next admission.
    public func send(_ frames: [Frame]) {
        guard !frames.isEmpty, let connection = state.withLock({ $0.connection }) else { return }
        for frame in frames {
            do throws(TerminalError) {
                try connection.send(frame)
            } catch {
                connection.close()
                return
            }
        }
    }

    /// Tries again at once: a wait cut short, a connection that stopped trying started again, or one
    /// that ended (a workspace started since) begun anew.
    public func reconnectNow() {
        let restart = state.withLock { state -> Bool in
            guard !state.closed else { return false }
            if state.supervisor == nil {
                state.supervisor = Task { await self.supervise() }
                return false
            }
            return true
        }
        if restart { alarm.ring() }
    }

    /// Ends the connection for good.
    public func close() {
        let (connection, supervisor) = state.withLock { state in
            state.closed = true
            defer {
                state.connection = nil
                state.supervisor = nil
            }
            return (state.connection, state.supervisor)
        }
        supervisor?.cancel()
        connection?.close()
        alarm.ring()
        sink.finish()
    }

    private enum Outcome {
        case failed(String)
        case dropped(String, attachedFor: Duration)
        case finished(Phase)
    }

    /// The terminal's own loop (``TerminalSession``): a failure waits longer each time, a drop after a
    /// steady connection starts the waits over, and past the patience it waits to be asked.
    private func supervise() async {
        var failures = 0
        var waited = Duration.zero
        var first = true
        var retrying = false
        var reason = ""
        let clock = ContinuousClock()
        while !Task.isCancelled {
            if !first {
                if waits.parks(after: failures, waited: waited) {
                    sink.yield(.phase(.unreachable(reason)))
                    await alarm.wait(nil)
                    failures = 0
                    waited = .zero
                } else {
                    let wait = waits.wait(after: failures)
                    sink.yield(.phase(.reconnecting(reason)))
                    await alarm.wait(wait)
                    waited += wait
                }
                guard !Task.isCancelled else { return }
            } else {
                sink.yield(.phase(.connecting))
            }
            retrying = !first
            first = false
            let began = clock.now
            let outcome = await attempt()
            guard !Task.isCancelled else { return }
            if retrying { waited += clock.now - began }
            switch outcome {
            case let .failed(why):
                failures += 1
                reason = why
            case let .dropped(why, attachedFor):
                failures = attachedFor >= TerminalSession.stableAttachment || !retrying ? 0 : failures + 1
                if failures == 0 { waited = .zero }
                reason = why
            case let .finished(phase):
                state.withLock { $0.supervisor = nil }
                sink.yield(.phase(phase))
                return
            }
        }
    }

    private func attempt() async -> Outcome {
        let entry: (address: HostAddress, token: String)
        do throws(TerminalError) {
            entry = try await door.entry(for: .files(project: project, workspace: workspace))
        } catch let .ticketRefused(refusal) {
            if refusal.code == .known(.workspaceStopped) { return .finished(.stopped(refusal.message)) }
            if refusal.code == .known(.terminalUnavailable) || refusal.code == .known(.rateLimited) { return .failed(refusal.message) }
            return .finished(.refused(refusal.lines))
        } catch let .signIn(needed) {
            return .finished(.refused(needed.lines))
        } catch {
            return .failed(error.description)
        }
        return await withCheckedContinuation { result in
            let reader = Thread { result.resume(returning: self.serve(entry.address, token: entry.token)) }
            reader.name = Self.readerName
            reader.start()
        }
    }

    /// One connection, from its connect to its end, on its own thread: every frame handed on as it
    /// comes, the host's ending said in its own words.
    private func serve(_ address: HostAddress, token: String) -> Outcome {
        let connection: Connection
        do throws(TerminalError) {
            connection = try Connection.connect(to: address, timeout: timeout)
            _ = try connection.admit(token: token)
        } catch let .refused(refusal) {
            return Self.ending(refusal) ?? .failed(refusal.message)
        } catch {
            return .failed(error.description)
        }
        let open = state.withLock { state -> Bool in
            guard !state.closed else { return false }
            state.connection = connection
            return true
        }
        guard open else {
            connection.close()
            return .finished(.connecting)
        }
        connection.stream()
        let admitted = ContinuousClock.now
        sink.yield(.phase(.connected))
        sink.yield(.admitted)
        defer {
            state.withLock { if $0.connection === connection { $0.connection = nil } }
            connection.close()
        }
        var pages: [Int32: [FileEntry]] = [:]
        while true {
            let frame: Frame?
            do throws(TerminalError) {
                frame = try connection.next()
            } catch {
                return .dropped(error.description, attachedFor: ContinuousClock.now - admitted)
            }
            guard let frame else { return .dropped(TerminalError.closed.description, attachedFor: ContinuousClock.now - admitted) }
            if case let .refused(refusal) = frame {
                return Self.ending(refusal) ?? .dropped(refusal.message, attachedFor: ContinuousClock.now - admitted)
            }
            if case let .filesListing(listing) = frame {
                let entries = (pages.removeValue(forKey: listing.id) ?? []) + listing.entries
                guard !listing.more else {
                    pages[listing.id] = entries
                    continue
                }
                sink.yield(.frame(.filesListing(FileListing(
                    id: listing.id, version: listing.version, entries: FileTree.sorted(entries), more: false, truncated: listing.truncated
                ))))
                continue
            }
            if case let .filesRefused(id, _, _) = frame { pages[id] = nil }
            sink.yield(.frame(frame))
        }
    }

    /// A host's refusal that ends the connection for good: the workspace stopped or gone.
    private static func ending(_ refusal: HostRefusal) -> Outcome? {
        switch refusal.code {
        case ErrorCode.workspaceStopped.rawValue: .finished(.stopped(refusal.message))
        case ErrorCode.notFound.rawValue: .finished(.gone(refusal.message))
        default: nil
        }
    }
}
