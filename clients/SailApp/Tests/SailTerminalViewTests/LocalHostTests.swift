import Foundation
@testable import SailPreview
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
        let host = LocalHost(binary: binary.path, address: address, log: folder.appending(path: "host.log").path)
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
}
