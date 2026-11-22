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
        ("See src/a.ts.", "a.ts", "src/a.ts", nil, nil),
        ("in a.ts, line 4", "a.ts", "a.ts", nil, nil),
        ("(see a.ts)", "a.ts", "a.ts", nil, nil),
        ("  ./src/cli.ts:7:3  error  'x' is never used", "cli.ts", "./src/cli.ts", 7, 3),
        ("Node.js v20 here", "Node.js", "Node.js", nil, nil),
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
        ("zoom to 1.5x", "1.5x"),
        ("took 2.5s", "2.5s"),
        ("upgrade to 3.x soon", "3.x"),
        ("needs build v2.5x first", "v2.5x"),
    ])
    func whatIsNotAPathIsNotFound(_ line: String, _ under: String) {
        #expect(Self.found(line, at: under) == nil, "found a path in \(line)")
    }

    /// The pointer on the path's own characters takes it; one cell past its end, or on the
    /// sentence's full stop after it, does not.
    @Test func onlyThePathsOwnCharactersTakeIt() throws {
        let line = "See src/a.ts. Then go."
        #expect(TerminalPath.find(in: line, at: 5)?.path == "src/a.ts")
        #expect(TerminalPath.find(in: line, at: 12)?.path == "src/a.ts")
        #expect(TerminalPath.find(in: line, at: 13) == nil, "the full stop after the path took it")
        #expect(TerminalPath.find(in: "open a.ts now", at: 10) == nil, "the space past the path took it")
    }

    /// `.` and `..` are taken lexically, `~/` is the home; a path leaving the home is nil.
    @Test func pathsAreResolvedAndNeverLeaveTheHome() {
        let root = "/home/dev/workspace"
        #expect(TerminalPath.resolved("./src/a.ts", in: root) == "/home/dev/workspace/src/a.ts")
        #expect(TerminalPath.resolved("src/../lib/./a.ts", in: root) == "/home/dev/workspace/lib/a.ts")
        #expect(TerminalPath.resolved("~/workspace/a.ts", in: root) == "/home/dev/workspace/a.ts")
        #expect(TerminalPath.resolved("../notes.txt", in: root) == "/home/dev/notes.txt")
        #expect(TerminalPath.resolved("../../etc/passwd", in: root) == nil)
        #expect(TerminalPath.resolved("/etc/hosts", in: root) == nil)
    }

    static func folder(_ name: String) -> FileEntry {
        FileEntry(name: FileName(Array(name.utf8)), kind: .known(.folder), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o040755)
    }

    /// The places asked are bounded and fixed: the terminal's folder, the root, then the root
    /// listed and at most 16 folders under it, first by name, dot-names left out, the listing's
    /// pages gathered; each asked only once it answered the last as not found.
    @Test func theLookupAsksABoundedFixedListOfPlaces() throws {
        var lookup = TerminalPath.Lookup(path: "./src/a.ts", cwd: "/home/dev/workspace/api", workspace: "dock", firstID: 0)
        var asked: [Frame] = []
        var next = lookup.start()
        let root = 500
        var listed = false
        while case let .ask(frame) = next {
            asked.append(frame)
            switch frame {
            case let .filesStat(id, _):
                next = lookup.receive(.filesRefused(id: id, kind: .known(.notFound), message: "No a.ts."))
            case let .filesList(id, path):
                #expect(path == FileTree.root && !listed)
                listed = true
                let names = [".git", ".cache"] + (0 ..< root).map { String(format: "f%03d", root - 1 - $0) }
                _ = lookup.receive(.filesListing(FileListing(id: id, version: "v1", entries: names.prefix(250).map(Self.folder), more: true, truncated: false)))
                next = lookup.receive(.filesListing(FileListing(id: id, version: "v1", entries: names.dropFirst(250).map(Self.folder), more: false, truncated: false)))
            default:
                Issue.record("asked \(frame)")
                next = .nothing
            }
        }
        #expect(next == .said("No file at ./src/a.ts in dock."))
        let stats = asked.compactMap { if case let .filesStat(_, path) = $0 { path.shown } else { nil } }
        #expect(stats.count == 2 + TerminalPath.folders && asked.count == stats.count + 1, "\(asked.count) asked")
        #expect(Array(stats.prefix(3)) == ["/home/dev/workspace/api/src/a.ts", "/home/dev/workspace/src/a.ts", "/home/dev/workspace/f000/src/a.ts"])
        #expect(stats.last == "/home/dev/workspace/f015/src/a.ts" && !stats.contains { $0.contains("/.") })
    }

    /// The lookup asks each place in turn; the first the workspace finds is opened; none found is
    /// said in one sentence naming the workspace (M17, M18); another refusal in its own words; a
    /// path leaving the home is not asked, and says so; an absolute one is asked as written only.
    @Test func theFirstPlaceTheWorkspaceFindsIsOpened() throws {
        var lookup = TerminalPath.Lookup(path: "src/a.ts", cwd: "/home/dev/workspace/api", workspace: "dock", firstID: 6)
        #expect(lookup.start() == .ask(.filesStat(id: 7, path: "/home/dev/workspace/api/src/a.ts")))
        #expect(lookup.receive(.filesRefused(id: 7, kind: .known(.notFound), message: "No a.ts.")) == .ask(.filesStat(id: 8, path: "/home/dev/workspace/src/a.ts")))
        let entry = FileEntry(name: "a.ts", kind: .known(.file), target: .known(.none), size: 3, mtimeNanos: 0, mode: 0o100644)
        #expect(lookup.receive(.filesEntry(id: 8, entry: entry, version: "v1")) == .found("/home/dev/workspace/src/a.ts", entry))
        var refused = TerminalPath.Lookup(path: "src/a.ts", cwd: nil, workspace: "dock", firstID: 0)
        _ = refused.start()
        #expect(refused.receive(.filesRefused(id: 1, kind: .known(.notPermitted), message: "The workspace's user may not read src.")) == .said("The workspace's user may not read src."))
        var outside = TerminalPath.Lookup(path: "../../etc/passwd", cwd: nil, workspace: "dock", firstID: 0)
        #expect(outside.start() == .said("../../etc/passwd is outside the workspace's home, so Sail does not look for it."))
        var absolute = TerminalPath.Lookup(path: "/home/dev/workspace/Gone.java", cwd: "/home/dev/workspace/api", workspace: "dock", firstID: 0)
        #expect(absolute.start() == .ask(.filesStat(id: 1, path: "/home/dev/workspace/Gone.java")))
        #expect(absolute.receive(.filesRefused(id: 1, kind: .known(.notFound), message: "No Gone.java.")) == .said("No file at /home/dev/workspace/Gone.java in dock."))
        var listing = TerminalPath.Lookup(path: "a.ts", cwd: nil, workspace: "dock", firstID: 0)
        _ = listing.start()
        #expect(listing.receive(.filesRefused(id: 1, kind: .known(.notFound), message: "No a.ts.")) == .ask(.filesList(id: 2, path: FileTree.root)))
        #expect(listing.receive(.filesRefused(id: 2, kind: .known(.notPermitted), message: "The workspace's user may not list it.")) == .said("The workspace's user may not list it."))
    }
}
