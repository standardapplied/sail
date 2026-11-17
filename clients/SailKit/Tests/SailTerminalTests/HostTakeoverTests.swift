import Foundation
import SailTerminal
import SailTerminalWire
import Testing

/// The design note's rule for another copy's host, and ending one (review 1, M1 and M2).
@Suite struct HostTakeoverTests {
    static func session(attached: Int32) -> SessionInfo {
        SessionInfo(name: "s", instanceID: "i", live: true, attached: attached, writerFDE: "", room: "", command: [])
    }

    /// Ours (the same file however it is spelled), a CLI install's, or one whose sessions could not
    /// be listed: kept. Another copy's under the copies folder or inside an app bundle: ended when
    /// nobody is attached, refused when a client is.
    @Test func onlyAnotherCopysHostThatNobodyHoldsIsEnded() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "takeover-\(UUID().uuidString.prefix(8))")
        defer { try? FileManager.default.removeItem(at: root) }
        let copies = root.appending(path: "Sail/Host")
        func file(_ path: String) throws -> String {
            let url = root.appending(path: path)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("x\(path)".utf8).write(to: url)
            return url.path
        }
        let copy = try file("Sail/Host/aaaa/sail"), older = try file("Sail/Host/bbbb/sail")
        let bundled = try file("Old/Sail.app/Contents/Helpers/sail"), cli = try file("usr/local/bin/sail")
        let spelled = root.appending(path: "linked").path
        try FileManager.default.createSymbolicLink(atPath: spelled, withDestinationPath: copy)
        let idle = [Self.session(attached: 0), Self.session(attached: 0)], held = [Self.session(attached: 0), Self.session(attached: 1)]
        let decide = { (serving: String, sessions: [SessionInfo]?) in HostTakeover.decide(serving: serving, copy: copy, copies: copies.path, sessions: sessions) }
        #expect(decide(copy, idle) == .keep)
        #expect(decide(spelled, idle) == .keep, "the same file by another path is this build's")
        #expect(decide(cli, idle) == .keep, "a CLI install's host is never ended")
        #expect(decide(older, idle) == .end)
        #expect(decide(bundled, []) == .end)
        #expect(decide(older, held) == .refuse)
        #expect(decide(bundled, held) == .refuse)
        #expect(decide(older, nil) == .keep, "with no listing nothing is known, so nothing is ended")
        #expect(!HostTakeover.sameFile(copy, older))
    }

    /// M1: an ended process is waited for until it is gone, not merely silent. One that ignores
    /// SIGTERM is killed, only while its pid still runs the same executable; no pid of 1 or less is
    /// ever signalled. Each is reaped by a thread of its parent, as another app reaps its host.
    @Test func anEndedProcessIsWaitedForUntilItIsGone() async throws {
        let runs = { @Sendable (_: Int32) -> String? in "/bin/sh" }
        let polite = try Self.spawn("exec tail -f /dev/null")
        #expect(await ProcessEnd.end(polite, running: "/bin/sh", runs: runs, within: .seconds(10)))
        #expect(Self.gone(polite), "end returned before the process was gone")
        let stubborn = try Self.spawn("trap '' TERM; exec tail -f /dev/null")
        #expect(await ProcessEnd.end(stubborn, running: "/bin/sh", runs: runs, within: .milliseconds(300)), "one that ignored SIGTERM was killed")
        #expect(Self.gone(stubborn))
        let other = try Self.spawn("trap '' TERM; exec tail -f /dev/null")
        let elsewhere = { @Sendable (_: Int32) -> String? in "/bin/ls" }
        #expect(!(await ProcessEnd.end(other, running: "/bin/sh", runs: elsewhere, within: .milliseconds(300))))
        #expect(kill(other, 0) == 0, "a pid that no longer runs the same executable is not killed")
        kill(other, SIGKILL)
        #expect(await ProcessEnd.exited(other, within: .seconds(10)))
        for pid: Int32 in [-1, 0, 1] {
            #expect(!(await ProcessEnd.end(pid, running: "/bin/sh", runs: runs, within: .milliseconds(10))))
        }
    }

    static func gone(_ pid: Int32) -> Bool {
        kill(pid, 0) == -1 && errno == ESRCH
    }

    /// `/bin/sh -c script`, a child of this test, reaped the moment it exits by a thread that waits.
    static func spawn(_ script: String) throws -> Int32 {
        var pid: pid_t = 0
        let words = ["/bin/sh", "-c", script].map { strdup($0) } + [nil]
        defer { words.forEach { free($0) } }
        var attributes = posix_spawnattr_t()
        posix_spawnattr_init(&attributes)
        defer { posix_spawnattr_destroy(&attributes) }
        var none = sigset_t(), all = sigset_t()
        sigemptyset(&none)
        sigfillset(&all)
        posix_spawnattr_setsigmask(&attributes, &none)
        posix_spawnattr_setsigdefault(&attributes, &all)
        posix_spawnattr_setflags(&attributes, Int16(POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF))
        let spawned = posix_spawn(&pid, "/bin/sh", nil, &attributes, words, environ)
        try #require(spawned == 0 && pid > 1, "posix_spawn: \(spawned)")
        let reaped = pid
        Thread {
            var status: Int32 = 0
            while waitpid(reaped, &status, 0) < 0, errno == EINTR {}
        }.start()
        let deadline = ContinuousClock.now + .seconds(10)
        while !(executable(of: pid)?.hasSuffix("/tail") ?? false) {
            try #require(ContinuousClock.now < deadline, "the shell never became tail")
            sched_yield()
        }
        return pid
    }

    /// What `pid` runs now: its executable, as the kernel names it.
    static func executable(of pid: Int32) -> String? {
        #if canImport(Darwin)
        var path = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        return proc_pidpath(pid, &path, UInt32(path.count)) > 0 ? String(cString: path) : nil
        #else
        return try? FileManager.default.destinationOfSymbolicLink(atPath: "/proc/\(pid)/exe")
        #endif
    }
}
