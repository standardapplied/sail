import Foundation
import SailAPI
import SailTerminalWire

/// A path in a terminal's output, as compilers and searches print one: `path`, `path:line`,
/// `path:line:column` or `path(line,column)`. Found only where it reads as a path (a slash, or a
/// name with a lettered extension), never inside a URL or a time of day; opened by asking the
/// workspace where it is, never by guessing.
public struct TerminalPath: Equatable, Sendable {
    public let path: String
    public let line: Int?
    public let column: Int?
    /// Where it sits in the terminal's line, in characters.
    public let range: Range<Int>

    private static let pattern = try? NSRegularExpression(
        pattern: #"(?<![\w/.~:@+-])((?:~|\.{1,2})?/?(?:[\w.+@-]+/)*[\w.+@-]+)(?::(\d+)(?::(\d+))?|\((\d+),(\d+)\))?"#
    )

    /// The path under character `column` (1-based) of `line`, if one is there.
    public static func find(in line: String, at column: Int) -> TerminalPath? {
        guard let pattern else { return nil }
        let text = line as NSString, at = (line.prefix(max(column - 1, 0)) as Substring).utf16.count
        for match in pattern.matches(in: line, range: NSRange(location: 0, length: text.length)) {
            guard match.range.contains(at) || match.range.upperBound == at, let found = make(match, in: text) else { continue }
            return found
        }
        return nil
    }

    private static func make(_ match: NSTextCheckingResult, in text: NSString) -> TerminalPath? {
        func group(_ index: Int) -> String? {
            let range = match.range(at: index)
            return range.location == NSNotFound ? nil : text.substring(with: range)
        }
        guard let path = group(1), readsAsAPath(path) else { return nil }
        let start = text.substring(to: match.range.location).count
        return TerminalPath(
            path: path, line: (group(2) ?? group(4)).flatMap { Int($0) }, column: (group(3) ?? group(5)).flatMap { Int($0) },
            range: start ..< start + text.substring(with: match.range).count
        )
    }

    /// A slash, or a name ending in an extension with a letter in it, and a letter somewhere:
    /// `src/a.ts` and `App.java`, never `12`, `1.2.3` or `word`.
    private static func readsAsAPath(_ path: String) -> Bool {
        guard path.contains(where: \.isLetter) else { return false }
        if path.contains("/") { return true }
        guard let dot = path.lastIndex(of: "."), dot != path.startIndex else { return false }
        return path[path.index(after: dot)...].contains(where: \.isLetter)
    }

    /// Where to ask for `path`, in order: as written when absolute (`~/` the home); otherwise in the
    /// directory the terminal last reported, then the workspace's root, then each folder directly
    /// under it.
    public static func places(for path: String, cwd: FileName?, root: FileName, folders: [FileName]) -> [FileName] {
        if path.hasPrefix("/") { return [FileName(Array(path.utf8))] }
        if path.hasPrefix("~/") { return [FileName(Array(("/home/dev" + path.dropFirst()).utf8))] }
        let relative = FileName(Array(path.utf8))
        var places: [FileName] = []
        for folder in [cwd].compactMap({ $0 }) + [root] + folders {
            let place = folder.appending(relative)
            if !places.contains(place) { places.append(place) }
        }
        return places
    }

    /// Asking each place in turn: the first the workspace finds is the file; none found, one
    /// sentence naming the workspace; any other refusal, its own words.
    public struct Lookup: Sendable {
        public enum Next: Equatable, Sendable {
            case ask(Frame)
            case found(FileName, FileEntry)
            case said(String)
            case nothing
        }

        let asked: String
        let places: [FileName]
        let workspace: String
        private var index = 0
        private var id: Int32

        public init(asked: String, places: [FileName], workspace: String, firstID: Int32) {
            self.asked = asked
            self.places = places
            self.workspace = workspace
            id = firstID
        }

        public mutating func start() -> Frame? {
            places.isEmpty ? nil : .filesStat(id: id, path: places[0])
        }

        public mutating func receive(_ frame: Frame) -> Next {
            switch frame {
            case let .filesEntry(answered, entry, _) where answered == id:
                return .found(places[index], entry)
            case let .filesRefused(answered, kind, message) where answered == id:
                guard kind == .known(.notFound) else { return .said(message) }
                index += 1
                guard index < places.count else { return .said("No file at \(asked) in \(workspace).") }
                id += 1
                return .ask(.filesStat(id: id, path: places[index]))
            default:
                return .nothing
            }
        }
    }
}
