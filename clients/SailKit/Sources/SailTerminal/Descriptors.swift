#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Synchronization

/// Held while a process starts, and by a close: a process being started holds a copy of every
/// descriptor of this one until its exec closes them, so a socket closed meanwhile would stay open,
/// listening or connected, for as long as that takes.
private let starting = Mutex(())

/// The one place SailKit and the app make a descriptor, each close-on-exec from the moment it is
/// returned, so no process this one starts holds a connection or a listener of it: a child's copy
/// would keep a port listening, or a connection open, after this process closed its own. Linux sets
/// the flag on the call that makes a socket. Darwin has no such call, and Glibc's Swift module
/// declares neither `accept4` nor `pipe2` (they need `_GNU_SOURCE`), so those set it on the next
/// call; ``Spawn``, which gives a child only what it names, closes that window for every process
/// the client starts.
public enum Descriptors {
    /// A stream socket of `family`; -1 with `errno` set when none is made.
    public static func socket(_ family: Int32) -> Int32 {
        #if canImport(Glibc)
        Glibc.socket(family, Int32(SOCK_STREAM.rawValue | SOCK_CLOEXEC.rawValue), 0)
        #else
        closingOnExec(Darwin.socket(family, SOCK_STREAM, 0))
        #endif
    }

    /// The next connection `listener` takes; -1 with `errno` set when none is taken.
    public static func accept(_ listener: Int32) -> Int32 {
        #if canImport(Glibc)
        closingOnExec(Glibc.accept(listener, nil, nil))
        #else
        closingOnExec(Darwin.accept(listener, nil, nil))
        #endif
    }

    /// A pipe's two ends; nil with `errno` set when none is made.
    public static func pipe() -> (read: Int32, write: Int32)? {
        var ends: [Int32] = [-1, -1]
        #if canImport(Glibc)
        guard Glibc.pipe(&ends) == 0 else { return nil }
        #else
        guard Darwin.pipe(&ends) == 0 else { return nil }
        #endif
        let read = closingOnExec(ends[0]), write = closingOnExec(ends[1])
        guard read >= 0, write >= 0 else {
            if read >= 0 { close(read) }
            if write >= 0 { close(write) }
            return nil
        }
        return (read, write)
    }

    /// Closes `descriptor` once no process is being started, so no copy of it outlives the close.
    public static func close(_ descriptor: Int32) {
        starting.withLock { _ in
            #if canImport(Glibc)
            _ = Glibc.close(descriptor)
            #else
            _ = Darwin.close(descriptor)
            #endif
        }
    }

    private static func closingOnExec(_ descriptor: Int32) -> Int32 {
        guard descriptor >= 0 else { return descriptor }
        guard fcntl(descriptor, F_SETFD, FD_CLOEXEC) == 0 else {
            let reason = errno
            close(descriptor)
            errno = reason
            return -1
        }
        return descriptor
    }
}

/// The one place the client starts a process. The child holds its three standard descriptors as
/// they are given and nothing else of this process (`POSIX_SPAWN_CLOEXEC_DEFAULT` on Darwin,
/// `posix_spawn_file_actions_addclosefrom_np`, glibc 2.34 and later, on Linux), whether or not a
/// descriptor was marked close-on-exec; it starts with no signal blocked and every signal's default
/// action.
public enum Spawn {
    /// Where one of a child's standard descriptors goes.
    public enum Standard: Sendable {
        case null
        /// A descriptor of this process, above the standard three.
        case descriptor(Int32)
        /// A file, created for this account alone and emptied first.
        case appending(String)
    }

    /// Starts `executable` with `arguments` and this process's environment: its pid.
    public static func process(
        _ executable: String, _ arguments: [String], input: Standard = .null, output: Standard = .null, errors: Standard = .null,
        newSession: Bool = false
    ) throws(SpawnRefused) -> pid_t {
        #if canImport(Darwin)
        var actions: posix_spawn_file_actions_t?
        var attributes: posix_spawnattr_t?
        #else
        var actions = posix_spawn_file_actions_t()
        var attributes = posix_spawnattr_t()
        #endif
        posix_spawn_file_actions_init(&actions)
        posix_spawnattr_init(&attributes)
        defer {
            posix_spawn_file_actions_destroy(&actions)
            posix_spawnattr_destroy(&attributes)
        }
        for (target, standard) in [(Int32(0), input), (1, output), (2, errors)] {
            let added: Int32 = switch standard {
            case .null: posix_spawn_file_actions_addopen(&actions, target, "/dev/null", O_RDWR, 0)
            case let .descriptor(descriptor) where descriptor > 2: posix_spawn_file_actions_adddup2(&actions, descriptor, target)
            case let .descriptor(descriptor): throw SpawnRefused("descriptor \(descriptor) is one of the child's own standard three")
            case let .appending(path): posix_spawn_file_actions_addopen(&actions, target, path, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0o600)
            }
            guard added == 0 else { throw SpawnRefused(code: added) }
        }
        var flags = POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF | (newSession ? setSession : 0)
        #if canImport(Darwin)
        flags |= POSIX_SPAWN_CLOEXEC_DEFAULT
        #else
        posix_spawn_file_actions_addclosefrom_np(&actions, 3)
        #endif
        var none = sigset_t(), all = sigset_t()
        sigemptyset(&none)
        sigfillset(&all)
        posix_spawnattr_setsigmask(&attributes, &none)
        posix_spawnattr_setsigdefault(&attributes, &all)
        posix_spawnattr_setflags(&attributes, Int16(flags))
        let words: [UnsafeMutablePointer<CChar>?] = ([executable] + arguments).map { strdup($0) } + [nil]
        defer { words.forEach { free($0) } }
        var pid: pid_t = 0
        let spawned = starting.withLock { _ in posix_spawn(&pid, executable, &actions, &attributes, words, environ) }
        guard spawned == 0 else { throw SpawnRefused(code: spawned) }
        return pid
    }

    #if canImport(Darwin)
    private static let setSession = POSIX_SPAWN_SETSID
    #else
    /// glibc's `POSIX_SPAWN_SETSID` (2.26 and later), which its Swift module does not declare
    /// without `_GNU_SOURCE`.
    private static let setSession: Int32 = 0x80
    #endif
}

/// Why a process could not be started.
public struct SpawnRefused: Error, CustomStringConvertible {
    public let description: String

    init(_ description: String) {
        self.description = description
    }

    init(code: Int32) {
        description = String(cString: strerror(code))
    }
}
