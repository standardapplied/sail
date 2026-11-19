import Darwin
import Foundation
@testable import SailShell
import SailTerminal
import SailTerminalWire
import Synchronization
import Testing

@Suite(.timeLimit(.minutes(1))) struct LocalHostTests {
    /// A bundled host that refuses to start is told in its own words, at once, not as "did not
    /// answer within 15 s".
    @Test func aHostThatRefusesToStartIsToldInItsOwnWords() async throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-host-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let binary = folder.appending(path: "sail")
        try "#!/bin/sh\necho 'pty-host: starting' >&2\necho 'pty-host: refused: the folder is open to others' >&2\nexit 3\n"
            .write(to: binary, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: binary.path)
        let address = try HostAddress.local(home: folder.path)
        let host = LocalHost(binary: binary.path, address: address, log: folder.appending(path: "host.log").path, copies: folder.appending(path: "copies"))
        let started = ContinuousClock.now
        do {
            try await host.ensureServing()
            Issue.record("a host that exits at once was taken as serving")
        } catch {
            #expect(error.description.contains("saying: pty-host: refused: the folder is open to others"), "\(error)")
        }
        #expect(ContinuousClock.now - started < .seconds(10))
        let mode = try FileManager.default.attributesOfItem(atPath: folder.appending(path: "host.log").path)[.posixPermissions] as? Int
        #expect(mode == 0o600, "the host's log is its owner's alone")
    }

    /// R2-M1: a host this app started that is killed (a crash) is started again; one stopped by
    /// SIGTERM, whoever sent it, is not, and the sentence its panes say is recorded.
    @Test func aKilledHostIsStartedAgainAndAStoppedOneIsNotAndSaysSo() async throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-stop-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let binary = folder.appending(path: "sail")
        try "#!/bin/sh\nexec tail -f /dev/null\n".write(to: binary, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: binary.path)
        let host = LocalHost(
            binary: binary.path, address: try HostAddress.local(home: folder.path), log: folder.appending(path: "host.log").path,
            copies: folder.appending(path: "copies"), shortestLife: .zero
        )
        try host.start(binary.path)
        let first = try #require(host.startedPID)
        defer { if let pid = host.startedPID { kill(pid, SIGTERM) } }
        kill(first, SIGKILL)
        #expect(await Self.until { host.startedPID.map { $0 != first } ?? false }, "a killed host was not started again")
        #expect(host.endedBy == nil)
        let second = try #require(host.startedPID)
        kill(second, SIGTERM)
        #expect(await Self.until { host.endedBy != nil }, "a stopped host said nothing")
        #expect(host.endedBy == LocalHost.stoppedSentence)
        #expect(host.startedPID == nil, "a stopped host was started again")
        #expect(kill(second, 0) == -1, "the stopped host still runs")
    }

    /// R2-M1: a host that comes to serve while this build's own is starting, from another
    /// executable, is said and not taken, and nothing is ended.
    @Test func anotherHostServingOnceOursStartedIsSaid() async throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-race-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let binary = folder.appending(path: "sail")
        try "#!/bin/sh\nexec tail -f /dev/null\n".write(to: binary, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: binary.path)
        let path = folder.appending(path: "h.sock").path
        let host = LocalHost(
            binary: binary.path, address: .unix(path: path), log: folder.appending(path: "host.log").path,
            copies: folder.appending(path: "copies"), shortestLife: .seconds(60)
        )
        defer { if let pid = host.startedPID { kill(pid, SIGTERM) } }
        let serving = Task { () -> ShellError? in
            do throws(ShellError) {
                try await host.ensureServing()
                return nil
            } catch {
                return error
            }
        }
        #expect(await Self.until { host.startedPID != nil }, "this build's host was not started")
        let stop = try Self.answeringHost(at: path)
        defer { stop() }
        #expect(await serving.value == .host(LocalHost.startedElsewhere))
        #expect(host.startedPID != nil, "nothing is ended: this build's own host still runs")
    }

    /// A host in this process at `path` that admits any client and lists no session, as another
    /// program's would; the closure returned stops it.
    static func answeringHost(at path: String) throws -> @Sendable () -> Void {
        let listener = socket(AF_UNIX, SOCK_STREAM, 0)
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: path.utf8) }
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        try #require(bound == 0 && listen(listener, 8) == 0)
        let stopped = Flag()
        Thread {
            defer {
                close(listener)
                unlink(path)
            }
            while !stopped.raised.load(ordering: .relaxed) {
                var ready = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
                guard poll(&ready, 1, 50) == 1 else { continue }
                let client = accept(listener, nil, nil)
                guard client >= 0 else { continue }
                answer(client)
                close(client)
            }
        }.start()
        return { stopped.raised.store(true, ordering: .relaxed) }
    }

    private final class Flag: Sendable {
        let raised = Atomic(false)
    }

    /// The host's side of one connection: its magic, a welcome to the hello, an empty page to a
    /// listing, until the client closes. A client already gone (``LocalHost/servingProcess()``
    /// connects only to learn the pid) is not written to: XNU refuses a socket option on a socket
    /// shut down both ways, so without `SO_NOSIGPIPE` the write would raise SIGPIPE in the test.
    private static func answer(_ client: Int32) {
        var on: Int32 = 1
        guard setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size)) == 0 else { return }
        let say = { (bytes: [UInt8]) in _ = bytes.withUnsafeBytes { write(client, $0.baseAddress, $0.count) } }
        say(Handshake.magic)
        var reader = FrameReader()
        var magicLeft = Handshake.magic.count
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let count = read(client, &buffer, buffer.count)
            guard count > 0 else { return }
            let skipped = min(magicLeft, count)
            magicLeft -= skipped
            guard let frames = try? reader.read(buffer[skipped ..< count]) else { return }
            for frame in frames {
                switch frame {
                case .hello: if let bytes = try? FrameCodec.encode(.welcome(hostBootID: "another")) { say(bytes) }
                case .listSessions: if let bytes = try? FrameCodec.encode(.sessions(SessionPage(sessions: [], next: ""))) { say(bytes) }
                default: break
                }
            }
        }
    }

    /// The in-use sentence says how many of the host's terminals a client holds, never a session's
    /// own name, so it stays true for one, several, or a CLI's `sail session attach`.
    @Test func theInUseSentenceSaysHowManyTerminalsAreHeld() {
        #expect(LocalHost.inUse(attached: 1) == "This Mac's terminal host is in use by another program, attached to one of its terminals. Close it, then try again.")
        #expect(LocalHost.inUse(attached: 3) == "This Mac's terminal host is in use by another program, attached to 3 of its terminals. Close it, then try again.")
    }

    /// Waits, at most 10 s, until `condition` holds.
    static func until(_ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(10)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return true
    }

    /// P4: the host runs from a copy named for its checksum, outside any app bundle, so none is kept
    /// busy: the copy is the bundled binary's bytes, `0700`; it is reused while it matches, made
    /// again when it was changed, and a new build's binary gets a copy of its own.
    @Test func theHostRunsFromACopyNamedForItsChecksum() async throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-copy-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let binary = folder.appending(path: "Sail.app/Contents/Helpers/sail")
        try FileManager.default.createDirectory(at: binary.deletingLastPathComponent(), withIntermediateDirectories: true)
        try "#!/bin/sh\necho \"ran $0\" >&2\nexit 3\n".write(to: binary, atomically: true, encoding: .utf8)
        let copies = folder.appending(path: "copies")
        let copy = try LocalHost.installed(binary.path, under: copies)
        #expect(!copy.contains("Sail.app"))
        #expect(try Data(contentsOf: URL(filePath: copy)) == Data(contentsOf: binary))
        #expect(try FileManager.default.attributesOfItem(atPath: copy)[.posixPermissions] as? Int == 0o700)
        #expect(try LocalHost.installed(binary.path, under: copies) == copy)
        try "tampered".write(toFile: copy, atomically: true, encoding: .utf8)
        #expect(try LocalHost.installed(binary.path, under: copies) == copy)
        #expect(try Data(contentsOf: URL(filePath: copy)) == Data(contentsOf: binary), "a changed copy is made again")
        let host = LocalHost(binary: binary.path, address: try HostAddress.local(home: folder.path), log: folder.appending(path: "host.log").path, copies: copies)
        do {
            try await host.ensureServing()
            Issue.record("a host that exits at once was taken as serving")
        } catch {
            #expect(error.description.contains("ran \(copy)"), "the host ran from \(error.description), not \(copy)")
        }
        try "#!/bin/sh\nexit 4\n".write(to: binary, atomically: true, encoding: .utf8)
        let newer = try LocalHost.installed(binary.path, under: copies)
        #expect(newer != copy && FileManager.default.fileExists(atPath: copy))
        try "half".write(toFile: newer + ".1.staged", atomically: true, encoding: .utf8)
        LocalHost.prune(copies, keeping: newer)
        #expect(!FileManager.default.fileExists(atPath: copy), "an older build's copy is removed")
        #expect(FileManager.default.fileExists(atPath: newer))
        #expect(try FileManager.default.contentsOfDirectory(atPath: URL(filePath: newer).deletingLastPathComponent().path) == ["sail"], "a staged file is removed")
    }

    /// What answers at the person's socket with another protocol's magic (here a listener in this
    /// test writing `SAILPTY3`) is said once as another version's host, with the command that ends
    /// it, and no host of this build is started beside it to lose the socket's lock.
    @Test func anotherVersionsHostIsSaidWithItsRemedyAndNothingIsStarted() async throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-other-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let started = folder.appending(path: "started")
        let binary = folder.appending(path: "sail")
        try "#!/bin/sh\ntouch '\(started.path)'\nexit 3\n".write(to: binary, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: binary.path)
        let address = try HostAddress.local(home: folder.path)
        guard case let .unix(path) = address else {
            Issue.record("the local address is not a socket")
            return
        }
        try FileManager.default.createDirectory(atPath: URL(filePath: path).deletingLastPathComponent().path, withIntermediateDirectories: true)
        let listener = socket(AF_UNIX, SOCK_STREAM, 0)
        defer {
            shutdown(listener, SHUT_RDWR)
            close(listener)
        }
        var name = sockaddr_un()
        name.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &name.sun_path) { $0.copyBytes(from: path.utf8) }
        let bound = withUnsafePointer(to: &name) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        try #require(bound == 0 && listen(listener, 8) == 0)
        Thread {
            while true {
                let client = accept(listener, nil, nil)
                guard client >= 0 else { return }
                var one: Int32 = 1
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
                var theirs = [UInt8](repeating: 0, count: 8)
                let read = theirs.withUnsafeMutableBytes { recv(client, $0.baseAddress, $0.count, MSG_WAITALL) }
                if read == 8 {
                    _ = Array("SAILPTY3".utf8).withUnsafeBytes { send(client, $0.baseAddress, $0.count, 0) }
                }
                close(client)
            }
        }.start()
        let host = LocalHost(binary: binary.path, address: address, log: folder.appending(path: "host.log").path, copies: folder.appending(path: "copies"))
        let asked = ContinuousClock.now
        do {
            try await host.ensureServing()
            Issue.record("another version's host was taken as serving")
        } catch {
            #expect(error.description == LocalHost.otherVersion(pid: getpid()), "\(error)")
        }
        #expect(ContinuousClock.now - asked < .seconds(5), "said at once")
        #expect(host.startedPID == nil && !FileManager.default.fileExists(atPath: started.path), "no host of this build was started beside it")
    }

    /// The process serving at a socket is found by its pid and its executable: here, this test's own.
    @Test func theProcessServingAtASocketIsFound() throws {
        let path = FileManager.default.temporaryDirectory.appending(path: "sail-peer-\(UUID().uuidString.prefix(8)).sock").path
        let listener = socket(AF_UNIX, SOCK_STREAM, 0)
        defer {
            close(listener)
            unlink(path)
        }
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: path.utf8) }
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        try #require(bound == 0 && listen(listener, 1) == 0)
        let host = LocalHost(binary: nil, address: .unix(path: path), log: path + ".log", copies: URL(filePath: path + ".copies"))
        let serving = try #require(host.servingProcess())
        #expect(serving.pid == getpid())
        var own = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        #expect(proc_pidpath(getpid(), &own, UInt32(own.count)) > 0)
        #expect(serving.executable == String(cString: own))
        #expect(LocalHost(binary: nil, address: .unix(path: path + ".none"), log: path + ".log", copies: URL(filePath: path)).servingProcess() == nil)
    }
}
