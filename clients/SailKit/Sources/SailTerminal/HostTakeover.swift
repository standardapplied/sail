#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import SailTerminalWire

/// What an app does with the host serving at the person's socket, on three positive facts: it is
/// not this build's copy (the same file, by device and inode), a Sail app made it (a copy under the
/// app's copies folder, never a CLI install's), and no client
/// holds any of its sessions. With a client, nothing is ended.
public enum HostTakeover: Equatable, Sendable {
    /// Use it as it is: this build's, not a Sail app's, or its sessions could not be listed.
    case keep
    /// Another copy of Sail made it and nobody is looking at it: end it and start this build's.
    case end
    /// Another copy of Sail made it and a client holds a session: end nothing, say so.
    case refuse

    public static func decide(serving executable: String, copy: String, copies: String, sessions: [SessionInfo]?) -> HostTakeover {
        guard !sameFile(executable, copy), sailMade(executable, copies: copies), let sessions else { return .keep }
        return sessions.allSatisfy { $0.attached == 0 } ? .end : .refuse
    }

    /// Whether two paths name one file: the same device and inode, however each is spelled.
    public static func sameFile(_ one: String, _ other: String) -> Bool {
        var first = stat(), second = stat()
        guard stat(one, &first) == 0, stat(other, &second) == 0 else { return false }
        return first.st_dev == second.st_dev && first.st_ino == second.st_ino
    }

    /// A host a Sail app made: a copy under `copies`.
    public static func sailMade(_ executable: String, copies: String) -> Bool {
        let path = URL(filePath: executable).resolvingSymlinksInPath().path
        let folder = URL(filePath: copies).resolvingSymlinksInPath().path
        return path.hasPrefix(folder + "/")
    }
}

/// Ends a process and waits until it is gone, not merely silent: a host gives up its lock last, so
/// one started while the old one is still exiting finds the lock held.
public enum ProcessEnd {
    /// `SIGTERM`, then up to `within` for the process to exit; then `SIGKILL` and up to `within`
    /// again. Each signal is sent only while `pid` still runs `executable` (`runs` says what it runs
    /// now), so a pid reused by another process is never signalled. True when it is gone. A pid of 1
    /// or less names no single process (`kill` would signal a group, or every process of the
    /// account), so it is refused.
    public static func end(_ pid: Int32, running executable: String, runs: @Sendable (Int32) -> String?, within limit: Duration) async -> Bool {
        guard pid > 1 else { return false }
        guard let before = runs(pid), HostTakeover.sameFile(before, executable) else { return await exited(pid, within: .zero) }
        kill(pid, SIGTERM)
        if await exited(pid, within: limit) { return true }
        guard let now = runs(pid), HostTakeover.sameFile(now, executable) else { return await exited(pid, within: .zero) }
        kill(pid, SIGKILL)
        return await exited(pid, within: limit)
    }

    /// Whether `pid` has exited within `limit`.
    public static func exited(_ pid: Int32, within limit: Duration) async -> Bool {
        guard pid > 1 else { return false }
        let deadline = ContinuousClock.now + limit
        while kill(pid, 0) == 0 || errno != ESRCH {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(20))
        }
        return true
    }
}
