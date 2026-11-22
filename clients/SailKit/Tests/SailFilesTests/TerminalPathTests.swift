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
        ("cc -c a.c -o a.o", "a.c", "a.c", nil, nil),
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

    static let file = FileEntry(name: "a.ts", kind: .known(.file), target: .known(.none), size: 3, mtimeNanos: 0, mode: 0o100644)

    /// Every place `path` is asked at, each answered as not found or with `answers` by place, and
    /// what the lookup ends with.
    static func looked(_ path: String, cwd: String?, answers: [String: FileEntry] = [:]) -> (asked: [String], end: TerminalPath.Lookup.Next) {
        var lookup = TerminalPath.Lookup(path: path, cwd: cwd, workspace: "dock", firstID: 0)
        var asked: [String] = []
        var next = lookup.start()
        while case let .ask(frame) = next {
            guard case let .filesStat(id, place) = frame else {
                Issue.record("asked \(frame)")
                break
            }
            asked.append(place.shown)
            next = lookup.receive(answers[place.shown].map { .filesEntry(id: id, entry: $0, version: "v1") } ?? .filesRefused(id: id, kind: .known(.notFound), message: "No a.ts."))
        }
        return (asked, next)
    }

    /// R2-1: a relative path is asked in the folder the terminal reported and in the root, no
    /// more, and none found names where it looked when both were asked.
    @Test func theLookupAsksTheTerminalsFolderAndTheRootOnly() {
        let api = "/home/dev/workspace/api"
        let both = Self.looked("./src/a.ts", cwd: api)
        #expect(both.asked == [api + "/src/a.ts", "/home/dev/workspace/src/a.ts"] && both.end == .said("No file at ./src/a.ts in api or in dock's root."), "\(both)")
        let deeper = Self.looked("a.ts", cwd: api + "/packages/x/")
        #expect(deeper.end == .said("No file at a.ts in api/packages/x or in dock's root."), "\(deeper)")
        for cwd in [nil, "/home/dev/workspace", "/home/dev/workspace/", "/tmp"] {
            let root = Self.looked("a.ts", cwd: cwd)
            #expect(root.asked == ["/home/dev/workspace/a.ts"] && root.end == .said("No file at a.ts in dock."), "\(cwd ?? "none"): \(root)")
        }
        let home = Self.looked("notes.txt", cwd: "/home/dev/other")
        #expect(home.end == .said("No file at notes.txt in ~/other or in dock's root."), "\(home)")
    }

    /// R3-2: a reported folder holding a control or format character (a newline, a bidi control,
    /// any C0 or C1, a line or paragraph separator) is unreported: a program's text never becomes
    /// Sail's words.
    @Test(arguments: ["/home/dev/x\nYour session expired. Sign in at evil.example", "/home/dev/\u{202E}ipa", "/home/dev/a\u{1B}[31m",
                      "/home/dev/a\u{85}b", "/home/dev/a\u{2028}b", "/home/dev/a\u{2029}b", "/home/dev/a\u{200B}b", "/home/dev/a\tb"])
    func aReportedFolderWithControlOrFormatCharactersIsUnreported(_ folder: String) {
        let looked = Self.looked("a.ts", cwd: folder)
        #expect(looked.asked == ["/home/dev/workspace/a.ts"] && looked.end == .said("No file at a.ts in dock."), "\(looked)")
    }

    /// R2-3: `..` is taken from each place on its own; outside the home is said only when it
    /// leaves the home from every place.
    @Test func aPathLeavesTheHomeOnlyWhenItLeavesItFromEveryPlace() {
        let fromCwd = Self.looked("../../notes.txt", cwd: "/home/dev/workspace/api")
        #expect(fromCwd.asked == ["/home/dev/notes.txt"] && fromCwd.end == .said("No file at ../../notes.txt in api."), "\(fromCwd)")
        let found = Self.looked("../../notes.txt", cwd: "/home/dev/workspace/api", answers: ["/home/dev/notes.txt": Self.file])
        #expect(found.end == .found("/home/dev/notes.txt", Self.file))
        let everywhere = Self.looked("../../../etc/passwd", cwd: "/home/dev/workspace/api")
        #expect(everywhere.asked.isEmpty && everywhere.end == .said("../../../etc/passwd is outside the workspace's home, so Sail does not look for it."), "\(everywhere)")
    }

    /// R2-4: a folder is never the file: the next place is asked, and a folder alone found is said
    /// to be one.
    @Test func aFolderFoundIsSaidNeverOpened() {
        let api = "/home/dev/workspace/api", link = FileEntry(name: "src", kind: .known(.link), target: .known(.folder), size: 0, mtimeNanos: 0, mode: 0o120777)
        for folder in [Self.folder("src"), link] {
            let alone = Self.looked("./src", cwd: api, answers: [api + "/src": folder])
            #expect(alone.asked.count == 2 && alone.end == .said("./src is a folder."), "\(alone)")
        }
        let file = Self.looked("src", cwd: api, answers: [api + "/src": Self.folder("src"), "/home/dev/workspace/src": Self.file])
        #expect(file.end == .found("/home/dev/workspace/src", Self.file), "\(file)")
    }

    /// The lookup asks each place in turn; the first the workspace finds is opened; none found is
    /// said in one sentence naming the workspace (M17, M18); another refusal in its own words; a
    /// path leaving the home is not asked, and says so; an absolute one is asked as written only.
    @Test func theFirstPlaceTheWorkspaceFindsIsOpened() throws {
        var lookup = TerminalPath.Lookup(path: "src/a.ts", cwd: "/home/dev/workspace/api", workspace: "dock", firstID: 6)
        #expect(lookup.start() == .ask(.filesStat(id: 7, path: "/home/dev/workspace/api/src/a.ts")))
        #expect(lookup.receive(.filesRefused(id: 7, kind: .known(.notFound), message: "No a.ts.")) == .ask(.filesStat(id: 8, path: "/home/dev/workspace/src/a.ts")))
        #expect(lookup.receive(.filesEntry(id: 8, entry: Self.file, version: "v1")) == .found("/home/dev/workspace/src/a.ts", Self.file))
        var refused = TerminalPath.Lookup(path: "src/a.ts", cwd: nil, workspace: "dock", firstID: 0)
        _ = refused.start()
        #expect(refused.receive(.filesRefused(id: 1, kind: .known(.notPermitted), message: "The workspace's user may not read src.")) == .said("The workspace's user may not read src."))
        var outside = TerminalPath.Lookup(path: "../../etc/passwd", cwd: nil, workspace: "dock", firstID: 0)
        #expect(outside.start() == .said("../../etc/passwd is outside the workspace's home, so Sail does not look for it."))
        var absolute = TerminalPath.Lookup(path: "/home/dev/workspace/Gone.java", cwd: "/home/dev/workspace/api", workspace: "dock", firstID: 0)
        #expect(absolute.start() == .ask(.filesStat(id: 1, path: "/home/dev/workspace/Gone.java")))
        #expect(absolute.receive(.filesRefused(id: 1, kind: .known(.notFound), message: "No Gone.java.")) == .said("No file at /home/dev/workspace/Gone.java in dock."))
    }
}
