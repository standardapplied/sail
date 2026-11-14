import Darwin
import Foundation
import SailTerminal
import Synchronization

/// This Mac's own terminal host for the preview: the one already answering at the person's
/// socket, or else the bundled `sail _pty-host --local`, started in a session of its own so it
/// outlives the app (spec 3 replaces this with a LaunchAgent). A host the preview started and that
/// then dies is started again at once, unless it died within ``shortestLife`` of starting.
public final class LocalHost: Sendable {
    public static let startDeadline = Duration.seconds(15)
    public static let shortestLife = Duration.seconds(2)

    public let address: HostAddress
    private let binary: String?
    private let child = Mutex<pid_t?>(nil)

    public init(binary: String?) throws(TerminalError) {
        address = try HostAddress.local()
        self.binary = binary
    }

    /// The pid of the host the preview started, nil when it found one serving.
    public var startedPID: pid_t? {
        child.withLock { $0 }
    }

    /// Returns once a host answers, starting the bundled one when none does.
    public func ensureServing() async throws(PreviewError) {
        if await answers() { return }
        guard let binary else { throw .host("no terminal host answers at \(address), and this build carries none to start") }
        if startedPID == nil {
            try start(binary)
        }
        let deadline = ContinuousClock.now + Self.startDeadline
        while ContinuousClock.now < deadline {
            if await answers() { return }
            try? await Task.sleep(for: .milliseconds(100))
        }
        throw .host("the bundled terminal host did not answer at \(address) within \(Self.startDeadline)")
    }

    /// Kills the host the preview started, as a crash would; it is started again.
    public func crash() {
        guard let pid = startedPID else { return }
        kill(pid, SIGKILL)
    }

    private func answers() async -> Bool {
        (try? await SessionListing.sessions(at: address, timeout: 1)) != nil
    }

    private func start(_ binary: String) throws(PreviewError) {
        var attributes: posix_spawnattr_t?
        var actions: posix_spawn_file_actions_t?
        posix_spawnattr_init(&attributes)
        posix_spawn_file_actions_init(&actions)
        defer {
            posix_spawnattr_destroy(&attributes)
            posix_spawn_file_actions_destroy(&actions)
        }
        posix_spawnattr_setflags(&attributes, Int16(POSIX_SPAWN_SETSID))
        for descriptor: Int32 in [0, 1, 2] {
            posix_spawn_file_actions_addopen(&actions, descriptor, "/dev/null", descriptor == 0 ? O_RDONLY : O_WRONLY, 0)
        }
        let words = [binary, "_pty-host", "--local"]
        let settings = ProcessInfo.processInfo.environment.map { "\($0.key)=\($0.value)" }
        let arguments: [UnsafeMutablePointer<CChar>?] = words.map { strdup($0) } + [nil]
        let environment: [UnsafeMutablePointer<CChar>?] = settings.map { strdup($0) } + [nil]
        defer { (arguments + environment).forEach { free($0) } }
        var pid: pid_t = 0
        let spawned = posix_spawn(&pid, binary, &actions, &attributes, arguments, environment)
        guard spawned == 0 else { throw .host("\(binary) could not be started: \(String(cString: strerror(spawned)))") }
        child.withLock { $0 = pid }
        watch(pid, binary: binary, since: ContinuousClock.now)
    }

    /// Waits for the host to exit on a thread of its own, and starts it again if it lived long
    /// enough to have served.
    private func watch(_ pid: pid_t, binary: String, since started: ContinuousClock.Instant) {
        Thread { [self] in
            var status: Int32 = 0
            while waitpid(pid, &status, 0) < 0, errno == EINTR {}
            let ours = child.withLock { current -> Bool in
                guard current == pid else { return false }
                current = nil
                return true
            }
            guard ours, ContinuousClock.now - started >= Self.shortestLife else { return }
            try? start(binary)
        }.start()
    }
}

public enum PreviewError: Error, Equatable, CustomStringConvertible {
    case host(String)
    case resources(String)

    public var description: String {
        switch self {
        case let .host(what), let .resources(what): what
        }
    }
}
