import CryptoKit
import Darwin
import Foundation
import SailTerminal
import Synchronization

/// This Mac's own terminal host for "This Mac": the one already answering at the person's
/// socket, or else the bundled `sail _pty-host --local`, started in a session of its own so it
/// outlives the app (a later spec replaces this with a LaunchAgent). It runs from a copy of the
/// bundled binary under Application Support, named for its checksum, so no running host keeps an
/// app bundle busy and a newer build replaces it while terminals run (P4); a host serving from
/// anywhere else (another build's) is ended first, and the person told. A host the app started and that
/// then dies is started again at once, unless it died within ``shortestLife`` of starting. Its
/// standard error goes to a file only its owner reads, so when it refuses to start, its own last
/// sentence is what the person is told; the host appends to it, and the file is emptied each time
/// the app finds the host serving, so it holds at most what one run of the app saw.
public final class LocalHost: Sendable {
    public static let startDeadline = Duration.seconds(15)
    public static let shortestLife = Duration.seconds(2)

    public static let endingAnother = "Your terminals from another Sail build will close: its terminal host is ending so this build can start its own."

    public let address: HostAddress
    private let binary: String?
    private let log: String
    private let copies: URL
    private let child = Mutex<pid_t?>(nil)

    public convenience init(binary: String?) throws(TerminalError) {
        try self.init(
            binary: binary, address: HostAddress.local(), log: FileManager.default.temporaryDirectory.appending(path: "sail-terminal-host.log").path,
            copies: URL.applicationSupportDirectory.appending(path: "Sail/Host")
        )
    }

    init(binary: String?, address: HostAddress, log: String, copies: URL) {
        self.address = address
        self.binary = binary
        self.log = log
        self.copies = copies
    }

    /// The pid of the host the app started, nil when it found one serving.
    public var startedPID: pid_t? {
        child.withLock { $0 }
    }

    /// Returns once a host answers, starting this build's when none does, or when the one that
    /// does runs from anywhere but this build's copy; then it returns ``endingAnother``, to be told.
    @discardableResult
    public func ensureServing() async throws(ShellError) -> String? {
        var copy: String?
        if let binary { copy = try Self.installed(binary, under: copies) }
        var ending: String?
        if await answers() {
            guard let copy, case let .unix(path) = address, let serving = Self.serving(at: path), serving.executable != copy else {
                _ = truncate(log, 0)
                return nil
            }
            ending = Self.endingAnother
            await end(serving.pid)
        }
        guard let copy else { throw .host("no terminal host answers at \(address), and this build carries none to start") }
        if startedPID == nil {
            try start(copy)
        }
        let deadline = ContinuousClock.now + Self.startDeadline
        while ContinuousClock.now < deadline {
            if await answers() { return ending }
            guard startedPID != nil else {
                throw .host("this Mac's terminal host stopped as it started\(said)")
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
        throw .host("the bundled terminal host did not answer at \(address) within \(Self.startDeadline)\(said)")
    }

    /// The host's last line on standard error, as a clause: ", saying …", or nothing.
    private var said: String {
        let text = (try? String(contentsOfFile: log, encoding: .utf8)) ?? ""
        guard let line = text.split(whereSeparator: \.isNewline).last.map({ $0.trimmingCharacters(in: .whitespaces) }), !line.isEmpty else {
            return ""
        }
        return ", saying: \(line.prefix(300))"
    }

    /// The bundled host copied to `folder`/<first 16 hex digits of its SHA-256>/sail, `0700`,
    /// unless a copy there already has the bundled binary's checksum: written whole to a staging
    /// name, then renamed, so no host ever runs a half-written copy.
    static func installed(_ binary: String, under folder: URL) throws(ShellError) -> String {
        let bytes: Data
        do {
            bytes = try Data(contentsOf: URL(filePath: binary), options: .mappedIfSafe)
        } catch {
            throw .host("the bundled terminal host \(binary) cannot be read: \(error.localizedDescription)")
        }
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let place = folder.appending(path: String(digest.prefix(16)))
        let copy = place.appending(path: "sail")
        if let held = try? Data(contentsOf: copy, options: .mappedIfSafe), SHA256.hash(data: held).map({ String(format: "%02x", $0) }).joined() == digest {
            return copy.path
        }
        let staged = place.appending(path: "sail.\(getpid()).staged")
        do {
            try FileManager.default.createDirectory(at: place, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try bytes.write(to: staged)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: staged.path)
        } catch {
            throw .host("the terminal host could not be copied to \(place.path): \(error.localizedDescription)")
        }
        guard rename(staged.path, copy.path) == 0 else {
            throw .host("the terminal host could not be copied to \(copy.path): \(String(cString: strerror(errno)))")
        }
        return copy.path
    }

    /// The process serving at the socket `path`: its pid and its executable, nil when none answers.
    public static func serving(at path: String) -> (pid: pid_t, executable: String)? {
        let socket = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
        guard socket >= 0 else { return nil }
        defer { close(socket) }
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        let capacity = MemoryLayout.size(ofValue: address.sun_path)
        guard path.utf8.count < capacity else { return nil }
        withUnsafeMutableBytes(of: &address.sun_path) { raw in
            raw.copyBytes(from: path.utf8)
        }
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(socket, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        var pid: pid_t = 0
        var size = socklen_t(MemoryLayout<pid_t>.size)
        guard connected == 0, getsockopt(socket, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0 else { return nil }
        var executable = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        guard proc_pidpath(pid, &executable, UInt32(executable.count)) > 0 else { return nil }
        return (pid, String(cString: executable))
    }

    /// Ends another build's host: asked to stop, then, if it still answers after 5 s, killed.
    private func end(_ pid: pid_t) async {
        kill(pid, SIGTERM)
        let deadline = ContinuousClock.now + .seconds(5)
        while ContinuousClock.now < deadline, await answers() {
            try? await Task.sleep(for: .milliseconds(100))
        }
        if await answers() {
            kill(pid, SIGKILL)
            try? await Task.sleep(for: .milliseconds(500))
        }
    }

    /// Kills the host the app started, as a crash would; it is started again.
    public func crash() {
        guard let pid = startedPID else { return }
        kill(pid, SIGKILL)
    }

    private func answers() async -> Bool {
        (try? await SessionListing.sessions(at: address, timeout: 1)) != nil
    }

    private func start(_ binary: String) throws(ShellError) {
        var attributes: posix_spawnattr_t?
        var actions: posix_spawn_file_actions_t?
        posix_spawnattr_init(&attributes)
        posix_spawn_file_actions_init(&actions)
        defer {
            posix_spawnattr_destroy(&attributes)
            posix_spawn_file_actions_destroy(&actions)
        }
        posix_spawnattr_setflags(&attributes, Int16(POSIX_SPAWN_SETSID))
        posix_spawn_file_actions_addopen(&actions, 0, "/dev/null", O_RDONLY, 0)
        posix_spawn_file_actions_addopen(&actions, 1, "/dev/null", O_WRONLY, 0)
        posix_spawn_file_actions_addopen(&actions, 2, log, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0o600)
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
