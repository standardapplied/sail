import Foundation
import Testing

/// The terminal core's sources, read as text: SailVT and SailTerminal import only Foundation, the
/// platform C library, libghostty-vt, the standard library's `Synchronization` and SailKit's own
/// modules, and write nothing anywhere, so no terminal byte, ticket or token can reach a log.
@Suite struct SweepTests {
    static let sources = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "Sources")

    static let allowedImports: Set = [
        "Foundation", "Glibc", "Darwin", "GhosttyVt", "Synchronization", "SailAPI", "SailTerminalWire", "SailVT", "os",
    ]

    static var writing: Regex<AnyRegexOutput> { try! Regex(#"\b(print|debugPrint|NSLog|os_log|fputs|fprintf|puts)\s*\(|\bLogger\b|\bFileHandle\b|\bstandardError\b|\bstandardOutput\b|\bwrite\s*\(\s*(1|2)\s*,"#).wordBoundaryKind(.simple) }

    @Test(arguments: ["SailVT", "SailTerminal"])
    func theTerminalCoreImportsOnlyWhatItMay(_ module: String) throws {
        for (file, text) in try Self.files(of: module) {
            let imports = text.split(separator: "\n").compactMap { line -> String? in
                let words = line.split(separator: " ")
                guard let index = words.firstIndex(of: "import"), index <= 1 else { return nil }
                return words.dropFirst(index + 1).first.map(String.init)
            }
            #expect(Set(imports).subtracting(Self.allowedImports).isEmpty, "\(file) imports \(Set(imports).subtracting(Self.allowedImports).sorted())")
        }
    }

    @Test(arguments: ["SailVT", "SailTerminal"])
    func theTerminalCoreWritesNothingAnywhere(_ module: String) throws {
        for (file, text) in try Self.files(of: module) {
            let found = text.split(separator: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("///") && $0.contains(Self.writing) }
            #expect(found.isEmpty, "\(file) writes: \(found)")
        }
    }

    @Test func theSweepSeesTheSources() throws {
        #expect(try Self.files(of: "SailTerminal").count >= 8)
        #expect(try Self.files(of: "SailVT").count >= 8)
        #expect("        print(ticket)".contains(Self.writing))
        #expect("    FileHandle.standardError.write(bytes)".contains(Self.writing))
    }

    private static func files(of module: String) throws -> [(String, String)] {
        let directory = sources.appending(path: module)
        return try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0.hasSuffix(".swift") }
            .sorted()
            .map { ($0, try String(contentsOf: directory.appending(path: $0), encoding: .utf8)) }
    }
}
