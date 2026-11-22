import Foundation
import SailAPI
@testable import SailFiles
import SailTerminalWire
import Testing

/// A path in a terminal's output, found under the pointer as `path`, `path:line` or
/// `path:line:column`, and resolved by asking the workspace, never by guessing.
@Suite struct TerminalPathTests {
    static func found(_ line: String, at text: String) -> TerminalPath? {
        guard let range = line.range(of: text) else { return nil }
        return TerminalPath.find(in: line, at: line.distance(from: line.startIndex, to: range.lowerBound) + 1)
    }

    @Test(arguments: [
        ("src/main/java/App.java:42: error: ';' expected", "App.java", "src/main/java/App.java", 42, nil),
        ("src/app.ts(12,5): error TS2322: Type 'string' is not assignable", "app.ts", "src/app.ts", 12, 5),
        ("src/app.ts:12:5 - error TS2322: Type 'string'", "app.ts", "src/app.ts", 12, 5),
        ("/home/dev/workspace/Sources/Tide/Tide.swift:7:13: error: cannot find 'x' in scope", "Tide.swift", "/home/dev/workspace/Sources/Tide/Tide.swift", 7, 13),
        ("crates/berth/src/lib.rs:118:    let quay = Quay::new();", "lib.rs", "crates/berth/src/lib.rs", 118, nil),
        ("docs/guide.md:3:## Mooring", "guide.md", "docs/guide.md", 3, nil),
        ("web/src/tide.ts:42:export function roundTide(at: Date): string {", "tide.ts", "web/src/tide.ts", 42, nil),
        ("  modified:   Package.swift", "Package.swift", "Package.swift", nil, nil),
        ("see ~/workspace/notes.txt for more", "notes.txt", "~/workspace/notes.txt", nil, nil),
    ] as [(String, String, String, Int?, Int?)])
    func realOutputIsFound(_ line: String, _ under: String, _ path: String, _ number: Int?, _ column: Int?) throws {
        let found = try #require(Self.found(line, at: under), "nothing found in \(line)")
        #expect(found.path == path && found.line == number && found.column == column, "\(line): \(found)")
    }

    @Test(arguments: [
        ("fetched https://example.com/a/b.ts:12 just now", "example.com"),
        ("12:30:05 build started", "30"),
        ("released v1.2.3 today", "1.2"),
        ("a plain word here", "plain"),
    ])
    func whatIsNotAPathIsNotFound(_ line: String, _ under: String) {
        #expect(Self.found(line, at: under) == nil, "found a path in \(line)")
    }

    /// An absolute path is asked as written; a relative one in the directory the terminal last
    /// reported, then the workspace's root, then each folder directly under it.
    @Test func placesAreAskedInOrder() {
        let root: FileName = "/home/dev/workspace", folders: [FileName] = ["/home/dev/workspace/api", "/home/dev/workspace/web"]
        #expect(TerminalPath.places(for: "/etc/hosts", cwd: "/home/dev/workspace/api", root: root, folders: folders) == ["/etc/hosts"])
        #expect(TerminalPath.places(for: "~/workspace/a.ts", cwd: nil, root: root, folders: folders) == ["/home/dev/workspace/a.ts"])
        #expect(TerminalPath.places(for: "src/a.ts", cwd: "/home/dev/workspace/api", root: root, folders: folders)
            == ["/home/dev/workspace/api/src/a.ts", "/home/dev/workspace/src/a.ts", "/home/dev/workspace/web/src/a.ts"])
        #expect(TerminalPath.places(for: "src/a.ts", cwd: nil, root: root, folders: folders)
            == ["/home/dev/workspace/src/a.ts", "/home/dev/workspace/api/src/a.ts", "/home/dev/workspace/web/src/a.ts"])
    }

    /// The lookup asks each place in turn; the first the workspace finds is opened; none found is
    /// said in one sentence naming the workspace (M17, M18).
    @Test func theFirstPlaceTheWorkspaceFindsIsOpened() throws {
        var lookup = TerminalPath.Lookup(asked: "src/a.ts", places: ["/home/dev/workspace/api/src/a.ts", "/home/dev/workspace/src/a.ts"], workspace: "dock", firstID: 7)
        #expect(lookup.start() == .filesStat(id: 7, path: "/home/dev/workspace/api/src/a.ts"))
        #expect(lookup.receive(.filesRefused(id: 7, kind: .known(.notFound), message: "No a.ts.")) == .ask(.filesStat(id: 8, path: "/home/dev/workspace/src/a.ts")))
        let entry = FileEntry(name: "a.ts", kind: .known(.file), target: .known(.none), size: 3, mtimeNanos: 0, mode: 0o100644)
        #expect(lookup.receive(.filesEntry(id: 8, entry: entry, version: "v1")) == .found("/home/dev/workspace/src/a.ts", entry))
        var none = TerminalPath.Lookup(asked: "src/Gone.java", places: ["/home/dev/workspace/src/Gone.java"], workspace: "dock", firstID: 1)
        _ = none.start()
        #expect(none.receive(.filesRefused(id: 1, kind: .known(.notFound), message: "No Gone.java.")) == .said("No file at src/Gone.java in dock."))
        var refused = TerminalPath.Lookup(asked: "/root/x", places: ["/root/x"], workspace: "dock", firstID: 1)
        _ = refused.start()
        #expect(refused.receive(.filesRefused(id: 1, kind: .known(.outsideHome), message: "/root/x is outside the workspace's home.")) == .said("/root/x is outside the workspace's home."))
    }
}
