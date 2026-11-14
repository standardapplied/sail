import Foundation
import Testing

/// Scenario 19: the Linux build script checks every download against its pin before it uses it.
@Suite(.timeLimit(.minutes(1))) struct BuildScriptTests {
    static let script = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "scripts/libghostty-vt")

    #if os(Linux)
    @Test func aDownloadWhoseChecksumDiffersFromItsPinStopsTheBuildNamingTheFile() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "libghostty-vt-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let downloads = root.appending(path: "downloads")
        try FileManager.default.createDirectory(at: downloads, withIntermediateDirectories: true)
        for name in ["libghostty-vt-1.0.1.tar.gz", "libghostty-vt-1.0.1-deps.tar.gz", "zig-x86_64-linux-0.17.1.tar.xz"] {
            try Data("not the release".utf8).write(to: downloads.appending(path: name))
        }
        let install = root.appending(path: "install")
        let process = Process()
        process.executableURL = Self.script
        process.arguments = [install.path, downloads.path]
        let errors = Pipe()
        process.standardError = errors
        process.standardOutput = FileHandle.nullDevice
        try process.run()
        let said = String(decoding: errors.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        process.waitUntilExit()
        #expect(process.terminationStatus == 1)
        #expect(said.contains("\(downloads.path)/libghostty-vt-1.0.1.tar.gz has sha256"))
        #expect(said.contains("not the pinned 333dfce97641ef9b536bb9e08907ad86abaaada50e8f7417b70d9284adef37aa: refusing to build"))
        #expect(!FileManager.default.fileExists(atPath: install.appending(path: "lib").path))
    }
    #endif
}
