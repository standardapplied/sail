import CryptoKit
import Darwin
import Foundation
import SailTerminal
import Synchronization

/// This Mac's own terminal host for "This Mac": the one already answering at the person's
/// socket, or else the bundled `sail _pty-host --local`, started in a session of its own so it
/// outlives the app (a later spec replaces this with a LaunchAgent). It runs from a copy of the
/// bundled binary under Application Support, named for its checksum, so no running host keeps an
/// app bundle busy (P4). Another Sail app's host is ended only on three facts (the design note's
/// rule): it is not this build's copy, a Sail app made it, and no client holds any of its sessions;
/// with a client, nothing is ended and the person is told what holds it. A host the app started
/// that then dies is started again at once, unless it died within ``shortestLife`` of starting or
/// was stopped (`SIGTERM`, by whoever sent it), which is recorded in ``endedBy`` for its panes. Its
/// standard error goes to a file only its owner reads, so when it refuses to start, its own last
/// sentence is what the person is told; the host appends to it, and the file is emptied each time
/// the app finds the host serving, so it holds at most what one run of the app saw.
public final class LocalHost: Sendable {
    public static let startDeadline = Duration.seconds(15)
    /// How long another copy's host is given to exit once asked to stop, and again once killed.
    static let endDeadline = Duration.seconds(10)

    public static let closingAnother = "Your terminals from another copy of Sail are closing: this build is starting its own terminal host."
    public static let startedElsewhere = "Another program started this Mac's terminal host while Sail was starting its own. Close it, then try again."
    public static let stoppedSentence = "This Mac's terminal host was stopped, and with it these terminals."

    /// What holds another copy's host: how many of its terminals have a client. The host does not
    /// tell whose client it is (another copy of Sail, or a `sail session attach`), and its session
    /// names are its own, never shown to the person.
    public static func inUse(attached: Int) -> String {
        "This Mac's terminal host is in use by another program, attached to \(attached == 1 ? "one of its terminals" : "\(attached) of its terminals"). Close it, then try again."
    }

    public let address: HostAddress
    private let binary: String?
    private let log: String
    private let copies: URL
    private let shortestLife: Duration
    private let child = Mutex<pid_t?>(nil)
    private let stopped = Mutex<String?>(nil)

    public convenience init(binary: String?) throws(TerminalError) {
        try self.init(
            binary: binary, address: HostAddress.local(), log: FileManager.default.temporaryDirectory.appending(path: "sail-terminal-host.log").path,
            copies: URL.applicationSupportDirectory.appending(path: "Sail/Host")
        )
    }

    init(binary: String?, address: HostAddress, log: String, copies: URL, shortestLife: Duration = .seconds(2)) {
        self.address = address
        self.binary = binary
        self.log = log
        self.copies = copies
        self.shortestLife = shortestLife
    }

    /// The pid of the host the app started, nil when it found one serving.
    public var startedPID: pid_t? {
        child.withLock { $0 }
    }

    /// What ended the host this app started, when something stopped it.
    public var endedBy: String? {
        stopped.withLock { $0 }
    }

    /// Returns once a host answers: the one serving, unless the design note's rule ends it, or else
    /// this build's, started from its copy. What the person must know is passed to `tell` when it
    /// happens: that another copy's terminals are closing (before they close, and only when it had
    /// some), or that the copy could not be made and the host already serving is used.
    public func ensureServing(telling say: @escaping @Sendable (String) async -> Void = { _ in }) async throws(ShellError) {
        var copy: String?
        var copyFailure: ShellError?
        if let binary {
            do {
                copy = try Self.installed(binary, under: copies)
            } catch {
                copyFailure = error
            }
        }
        if await answers() {
            guard let copy else {
                if let copyFailure { await say("This build's terminal host could not be copied (\(copyFailure)), so the one already running is used.") }
                _ = truncate(log, 0)
                return
            }
            guard try await endAnother(keeping: copy, before: say) else {
                if serves(copy) { Self.prune(copies, keeping: copy) }
                _ = truncate(log, 0)
                return
            }
        }
        guard let copy else { throw copyFailure ?? .host("no terminal host answers at \(address), and this build carries none to start") }
        stopped.withLock { $0 = nil }
        if startedPID == nil {
            try start(copy)
        }
        let deadline = ContinuousClock.now + Self.startDeadline
        while ContinuousClock.now < deadline {
            if await answers() {
                guard serves(copy) else { throw .host(Self.startedElsewhere) }
                Self.prune(copies, keeping: copy)
                return
            }
            guard startedPID != nil else {
                if await answers(), !serves(copy) { throw .host(Self.startedElsewhere) }
                throw .host("this Mac's terminal host stopped as it started\(said)")
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
        throw .host("the bundled terminal host did not answer at \(address) within \(Self.startDeadline)\(said)")
    }

    /// Ends the host serving now when the rule allows it, saying first that its terminals are
    /// closing when it has any; true when it did. With a client on any of its sessions, nothing is
    /// ended, and what holds it is thrown (``inUse(attached:)``).
    private func endAnother(keeping copy: String, before say: @Sendable (String) async -> Void) async throws(ShellError) -> Bool {
        guard let serving = servingProcess() else { return false }
        let sessions = try? await SessionListing.sessions(at: address, timeout: 1)
        switch HostTakeover.decide(serving: serving.executable, copy: copy, copies: copies.path, sessions: sessions) {
        case .keep: return false
        case .refuse: throw .host(Self.inUse(attached: (sessions ?? []).filter { $0.attached > 0 }.count))
        case .end: break
        }
        if sessions?.isEmpty == false { await say(Self.closingAnother) }
        guard await ProcessEnd.end(serving.pid, running: serving.executable, runs: { Self.executable(of: $0) }, within: Self.endDeadline) else {
            throw .host("the terminal host of another copy of Sail (pid \(serving.pid)) did not stop when asked to")
        }
        return true
    }

    /// The process serving at the socket now: its pid and its executable.
    public func servingProcess() -> (pid: pid_t, executable: String)? {
        guard let pid = address.listenerPID(), let executable = Self.executable(of: pid) else { return nil }
        return (pid, executable)
    }

    private func serves(_ copy: String) -> Bool {
        servingProcess().map { HostTakeover.sameFile($0.executable, copy) } ?? false
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
        let digest = Self.digest(bytes)
        let place = folder.appending(path: String(digest.prefix(16)))
        let copy = place.appending(path: "sail")
        if let held = try? Data(contentsOf: copy, options: .mappedIfSafe), Self.digest(held) == digest {
            return copy.path
        }
        let staged = place.appending(path: "sail.\(getpid()).staged")
        do {
            try FileManager.default.createDirectory(at: place, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try bytes.write(to: staged)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: staged.path)
        } catch {
            try? FileManager.default.removeItem(at: staged)
            throw .host("the terminal host could not be copied to \(place.path): \(error.localizedDescription)")
        }
        guard rename(staged.path, copy.path) == 0 else {
            throw .host("the terminal host could not be copied to \(copy.path): \(String(cString: strerror(errno)))")
        }
        return copy.path
    }

    private static func digest(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    /// The executable `pid` runs, as the kernel names it.
    static func executable(of pid: pid_t) -> String? {
        var path = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        guard proc_pidpath(pid, &path, UInt32(path.count)) > 0 else { return nil }
        return String(cString: path)
    }

    /// Every copy under `copies` but `copy`, and any staging file left beside it. Called only while
    /// this build's copy serves the socket, so no host runs from what it removes.
    static func prune(_ copies: URL, keeping copy: String) {
        let kept = URL(filePath: copy).deletingLastPathComponent().lastPathComponent
        for folder in (try? FileManager.default.contentsOfDirectory(atPath: copies.path)) ?? [] where folder != kept {
            try? FileManager.default.removeItem(at: copies.appending(path: folder))
        }
        let place = URL(filePath: copy).deletingLastPathComponent()
        for file in (try? FileManager.default.contentsOfDirectory(atPath: place.path)) ?? [] where file.hasSuffix(".staged") {
            try? FileManager.default.removeItem(at: place.appending(path: file))
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

    func start(_ binary: String) throws(ShellError) {
        var attributes: posix_spawnattr_t?
        var actions: posix_spawn_file_actions_t?
        posix_spawnattr_init(&attributes)
        posix_spawn_file_actions_init(&actions)
        defer {
            posix_spawnattr_destroy(&attributes)
            posix_spawn_file_actions_destroy(&actions)
        }
        var none = sigset_t(), all = sigset_t()
        sigemptyset(&none)
        sigfillset(&all)
        posix_spawnattr_setsigmask(&attributes, &none)
        posix_spawnattr_setsigdefault(&attributes, &all)
        posix_spawnattr_setflags(&attributes, Int16(POSIX_SPAWN_SETSID | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF))
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
    /// enough to have served and was not stopped: a `SIGTERM` (another copy of Sail, Activity
    /// Monitor's Quit, a `kill`) is recorded for the panes to say, never fought.
    private func watch(_ pid: pid_t, binary: String, since started: ContinuousClock.Instant) {
        Thread { [self] in
            var status: Int32 = 0
            while waitpid(pid, &status, 0) < 0, errno == EINTR {}
            let ours = child.withLock { current -> Bool in
                guard current == pid else { return false }
                current = nil
                return true
            }
            guard ours else { return }
            if status & 0x7F == SIGTERM {
                stopped.withLock { $0 = Self.stoppedSentence }
                return
            }
            guard ContinuousClock.now - started >= shortestLife else { return }
            try? start(binary)
        }.start()
    }
}
