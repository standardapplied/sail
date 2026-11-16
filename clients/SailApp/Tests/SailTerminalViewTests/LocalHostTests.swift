import Darwin
import Foundation
@testable import SailShell
import SailTerminal
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
        let serving = try #require(LocalHost.serving(at: path))
        #expect(serving.pid == getpid())
        var own = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        #expect(proc_pidpath(getpid(), &own, UInt32(own.count)) > 0)
        #expect(serving.executable == String(cString: own))
        #expect(LocalHost.serving(at: path + ".none") == nil)
    }
}
