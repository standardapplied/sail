import Foundation
import Testing

/// A workspace or a project is created, never made, in every sentence the clients say: no string
/// literal in SailKit's or the app's sources says make, makes, making or made within two lines of
/// a workspace or a project, or in a file named for one (a lone button title has no neighbour that
/// names what it makes). The twin of the server's `CreateNotMakeTest`.
@Suite struct CreateNotMakeTests {
    static let clients = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()

    @Test func aWorkspaceOrAProjectIsCreatedNeverMade() throws {
        let sources = ["SailKit/Sources", "SailApp/Sources"].map { Self.clients.appending(path: $0) }
        let files = try sources.flatMap(Self.swiftFiles)
        #expect(files.count > 50, "the sources were not found under \(Self.clients.path)")
        let made = try files.flatMap(Self.made)
        #expect(made.isEmpty, "a workspace or a project made, not created:\n\(made.joined(separator: "\n"))")
    }

    @Test func aMadeWorkspaceIsFoundAndAMadeTerminalIsNot() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "create-not-make-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let pane = folder.appending(path: "WorkspacesPane.swift"), terminal = folder.appending(path: "TerminalPane.swift")
        try "let create = NSButton(title: \"Make\", target: nil, action: nil)\n// say(\"Making it\")\n".write(to: pane, atomically: true, encoding: .utf8)
        try "let failed = \"the terminal screen could not be made\"\n\n\n\nlet next = \"Making new (about 3 s)\"\nlet project = 1\n"
            .write(to: terminal, atomically: true, encoding: .utf8)
        #expect(try Self.made(pane) == ["WorkspacesPane.swift:1: Make"])
        #expect(try Self.made(terminal) == ["TerminalPane.swift:5: Making new (about 3 s)"])
    }

    static func swiftFiles(under directory: URL) throws -> [URL] {
        guard let walk = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else { return [] }
        return walk.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }.sorted { $0.path < $1.path }
    }

    /// Each literal in `file` that makes a workspace or a project, as `file:line: literal`.
    static func made(_ file: URL) throws -> [String] {
        let literal = try Regex(#""((?:[^"\\]|\\.)*)""#)
        let make = try Regex(#"\b(?:[Mm]akes?|[Mm]aking|[Mm]ade)\b"#)
        let about = try Regex("workspace|project").ignoresCase()
        let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n")
        return lines.indices.flatMap { index -> [String] in
            guard !lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("//") else { return [] }
            let near = ([file.lastPathComponent] + lines[max(0, index - 2) ..< min(lines.count, index + 3)]).joined(separator: " ")
            return lines[index].matches(of: literal).compactMap { match -> String? in
                guard let said = match.output[1].substring, said.contains(make), near.contains(about) else { return nil }
                return "\(file.lastPathComponent):\(index + 1): \(said)"
            }
        }
    }
}
