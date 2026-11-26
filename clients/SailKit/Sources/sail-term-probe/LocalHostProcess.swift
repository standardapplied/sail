#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import SailAPI
import SailTerminal
import Synchronization

/// `sail _pty-host --local`, run by the probe (or a test) as its child: started, found serving by
/// the line it says so with, and ended by its pid. Spawned and reaped through POSIX alone, so no
/// run loop has to turn for it to be seen exiting.
final class LocalHostProcess: Sendable {
    /// How long a host has to say it serves.
    static let startDeadline = 20

    private let binary: String
    private let sleep: @Sendable (Duration) async throws -> Void
    private let spawn: Spawning
    private let child = Mutex<pid_t?>(nil)

    /// Starts `executable` with `arguments` and its standard error where it is told.
    typealias Spawning = @Sendable (_ executable: String, _ arguments: [String], _ errors: Spawn.Standard) throws(SpawnRefused) -> pid_t

    /// `sleep` waits out the start's deadline; `spawn` starts the host.
    init(
        binary: String, sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        spawn: @escaping Spawning = { executable, arguments, errors throws(SpawnRefused) in try Spawn.process(executable, arguments, errors: errors) }
    ) {
        self.binary = binary
        self.sleep = sleep
        self.spawn = spawn
    }

    deinit {
        stop()
    }

    /// Starts the host and returns once it says it serves; its standard error is read to its end
    /// meanwhile and after, so the host never waits on a full pipe. This process gives up its own
    /// copy of the pipe's writing end at once, so a host that ends is seen ending then. A host that
    /// did not say it serves is killed and reaped before the start throws, so none is left behind.
    func start() async throws {
        guard let ends = Descriptors.pipe() else { throw ProbeError("no pipe for the host's output: \(errno)") }
        let spawned = Result { () throws(SpawnRefused) in try spawn(binary, ["_pty-host", "--local"], .descriptor(ends.write)) }
        close(ends.write)
        let pid: pid_t
        do {
            pid = try spawned.get()
        } catch {
            close(ends.read)
            throw ProbeError("\(binary) could not be run: \(error)")
        }
        child.withLock { $0 = pid }
        let said = await said(ends.read)
        guard said != true else { return }
        crash()
        guard said == nil else { throw ProbeError("\(binary) _pty-host --local ended before it said it serves") }
        try Task.checkCancellation()
        throw ProbeError("\(binary) _pty-host --local did not say it serves within \(Self.startDeadline) s")
    }

    /// Whether the host said it serves before its output ended; nil when the deadline passed first,
    /// or the start was cancelled.
    private func said(_ reading: Int32) async -> Bool? {
        let first = Handoff<Bool?>()
        Thread { first.answer(.success(Self.readUntilServing(reading))) }.start()
        let deadline = Task { [sleep] in
            try? await sleep(.seconds(Self.startDeadline))
            first.answer(.success(nil))
        }
        let said = await withTaskCancellationHandler { try? await first.value() } onCancel: { deadline.cancel() }
        deadline.cancel()
        return said
    }

    /// Stops the host with SIGTERM, as launchd and systemd do, and waits for it to exit: it ends
    /// every session, saying so.
    func stop() {
        end(with: SIGTERM)
    }

    /// Kills the host with SIGKILL, as a crash would: it ends nothing and says nothing, so a
    /// client finds out only when it reconnects to the host that replaced it.
    func crash() {
        end(with: SIGKILL)
    }

    private func end(with signal: Int32) {
        guard let pid = child.withLock({ $0.take() }) else { return }
        kill(pid, signal)
        var status: Int32 = 0
        while waitpid(pid, &status, 0) < 0, errno == EINTR {}
    }

    /// Reads the host's standard error until it says it serves or its output ends: whether it said
    /// so. A thread of its own then drains the rest.
    private static func readUntilServing(_ descriptor: Int32) -> Bool {
        var said = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let count = read(descriptor, &buffer, buffer.count)
            if count < 0, errno == EINTR { continue }
            guard count > 0 else {
                close(descriptor)
                return false
            }
            said = (said + buffer[..<count]).suffix(64 * 1024)
            if String(decoding: said, as: UTF8.self).contains("pty-host: serving ") {
                Thread { drain(descriptor) }.start()
                return true
            }
        }
    }

    private static func drain(_ descriptor: Int32) {
        var buffer = [UInt8](repeating: 0, count: 4096)
        while read(descriptor, &buffer, buffer.count) > 0 {}
        close(descriptor)
    }
}

struct ProbeError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}
