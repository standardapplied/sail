#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import Synchronization

/// `sail _pty-host --local`, run by the probe (or a test) as its child: started, found serving by
/// the line it says so with, and ended by its pid. Spawned and reaped through POSIX alone, so no
/// run loop has to turn for it to be seen exiting.
final class LocalHostProcess: Sendable {
    /// How long a host has to say it serves.
    static let startDeadline = 20

    private let binary: String
    private let child = Mutex<pid_t?>(nil)

    init(binary: String) {
        self.binary = binary
    }

    deinit {
        stop()
    }

    /// Starts the host and returns once it says it serves; its standard error is read to its end
    /// meanwhile and after, so the host never waits on a full pipe.
    func start() async throws {
        var ends: [Int32] = [0, 0]
        guard pipe(&ends) == 0 else { throw ProbeError("no pipe for the host's output: \(errno)") }
        let pid = try spawn(errorsTo: ends[1])
        close(ends[1])
        child.withLock { $0 = pid }
        let reading = ends[0]
        let serving = await withCheckedContinuation { (said: CheckedContinuation<Bool, Never>) in
            Thread { said.resume(returning: Self.readUntilServing(reading)) }.start()
        }
        guard serving else {
            crash()
            throw ProbeError("\(binary) _pty-host --local did not say it serves within \(Self.startDeadline) s")
        }
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

    private func spawn(errorsTo descriptor: Int32) throws -> pid_t {
        #if canImport(Darwin)
        var actions: posix_spawn_file_actions_t?
        #else
        var actions = posix_spawn_file_actions_t()
        #endif
        posix_spawn_file_actions_init(&actions)
        defer { posix_spawn_file_actions_destroy(&actions) }
        posix_spawn_file_actions_adddup2(&actions, descriptor, 2)
        posix_spawn_file_actions_addopen(&actions, 1, "/dev/null", O_WRONLY, 0)
        let words: [String] = [binary, "_pty-host", "--local"]
        let settings: [String] = ProcessInfo.processInfo.environment.map { "\($0.key)=\($0.value)" }
        let arguments: [UnsafeMutablePointer<CChar>?] = words.map { strdup($0) } + [nil]
        let environment: [UnsafeMutablePointer<CChar>?] = settings.map { strdup($0) } + [nil]
        defer { (arguments + environment).forEach { free($0) } }
        var pid: pid_t = 0
        let spawned = posix_spawn(&pid, binary, &actions, nil, arguments, environment)
        guard spawned == 0 else { throw ProbeError("\(binary) could not be run: \(String(cString: strerror(spawned)))") }
        return pid
    }

    /// Reads the host's standard error until it says it serves, the deadline passes or it closes:
    /// whether it said so. A thread of its own then drains the rest.
    private static func readUntilServing(_ descriptor: Int32) -> Bool {
        let deadline = ContinuousClock.now + .seconds(startDeadline)
        var said = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while ContinuousClock.now < deadline {
            let left = Int32((deadline - ContinuousClock.now).components.seconds * 1000 + 1)
            var ready = pollfd(fd: descriptor, events: Int16(POLLIN), revents: 0)
            guard poll(&ready, 1, left) > 0 else { continue }
            let count = read(descriptor, &buffer, buffer.count)
            guard count > 0 else { break }
            said = (said + buffer[..<count]).suffix(64 * 1024)
            if String(decoding: said, as: UTF8.self).contains("pty-host: serving ") {
                Thread { drain(descriptor) }.start()
                return true
            }
        }
        close(descriptor)
        return false
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
