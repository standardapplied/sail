import Foundation
import SailAPI
import SailTerminalWire

/// A path in a terminal's output, as compilers and searches print one: `path`, `path:line`,
/// `path:line:column` or `path(line,column)`. Found only where it reads as a path: with a slash, or
/// with no slash a name whose extension starts with a letter and whose stem is not all digits
/// (`App.java`, never `1.5x` or `v1.2.3`); never inside a URL or a time of day, and never with the
/// punctuation that ends a sentence after it. Whether it is a file is the workspace's answer alone.
public struct TerminalPath: Equatable, Sendable {
    public let path: String
    public let line: Int?
    public let column: Int?
    /// Where it sits in the terminal's line, in characters.
    public let range: Range<Int>

    /// The workspace's home: no path outside it is asked.
    public static let home = "/home/dev"
    /// How many folders directly under the workspace's root a relative path is looked for in, at
    /// most: the first by name, a name starting with a dot left out.
    public static let folders = 16

    private static let pattern = try? NSRegularExpression(
        pattern: #"(?<![\w/.~:@+-])((?:~|\.{1,2})?/?(?:[\w.+@-]+/)*[\w.+@-]+)(?::(\d+)(?::(\d+))?|\((\d+),(\d+)\))?"#
    )

    /// The path under character `column` (1-based) of `line`, if one is there: the pointer on one
    /// of its own characters.
    public static func find(in line: String, at column: Int) -> TerminalPath? {
        guard let pattern else { return nil }
        let text = line as NSString, at = (line.prefix(max(column - 1, 0)) as Substring).utf16.count
        for match in pattern.matches(in: line, range: NSRange(location: 0, length: text.length)) {
            guard let found = make(match, in: text), found.utf16.contains(at) else { continue }
            return found.path
        }
        return nil
    }

    private static func make(_ match: NSTextCheckingResult, in text: NSString) -> (path: TerminalPath, utf16: Range<Int>)? {
        func group(_ index: Int) -> String? {
            let range = match.range(at: index)
            return range.location == NSNotFound ? nil : text.substring(with: range)
        }
        guard var path = group(1) else { return nil }
        let numbered = group(2) != nil || group(4) != nil
        var end = match.range.upperBound
        if !numbered {
            while let last = path.last, ".-+@".contains(last) {
                path.removeLast()
                end -= String(last).utf16.count
            }
        }
        guard !path.isEmpty, readsAsAPath(path) else { return nil }
        let start = text.substring(to: match.range.location).count
        let range = start ..< start + text.substring(with: NSRange(location: match.range.location, length: end - match.range.location)).count
        return (TerminalPath(path: path, line: (group(2) ?? group(4)).flatMap { Int($0) }, column: (group(3) ?? group(5)).flatMap { Int($0) }, range: range),
                match.range.location ..< end)
    }

    /// A slash and a letter; or with no slash, a name ending in an extension that starts with a
    /// letter, its stem not all digits.
    private static func readsAsAPath(_ path: String) -> Bool {
        guard path.contains(where: \.isLetter) else { return false }
        if path.contains("/") { return true }
        guard let dot = path.lastIndex(of: "."), dot != path.startIndex else { return false }
        let stem = path[..<dot], suffix = path[path.index(after: dot)...]
        return suffix.first?.isLetter == true && !stem.allSatisfy(\.isNumber)
    }

    /// `path` resolved lexically against `folder` (`.` and `..` taken, `~/` the home), nil when it
    /// leaves the home.
    public static func resolved(_ path: String, in folder: String) -> String? {
        let joined = path.hasPrefix("/") ? path : path.hasPrefix("~/") ? home + path.dropFirst() : folder + "/" + path
        var parts: [Substring] = []
        for part in joined.split(separator: "/") {
            if part == "." { continue }
            if part == ".." {
                guard !parts.isEmpty else { return nil }
                parts.removeLast()
                continue
            }
            parts.append(part)
        }
        let resolved = "/" + parts.joined(separator: "/")
        return resolved == home || resolved.hasPrefix(home + "/") ? resolved : nil
    }

    /// Looking for a path, one place at a time, the first the workspace finds being the file: as
    /// written when absolute (`~/` the home); else in the folder the terminal reported, then the
    /// workspace's root, then at most ``folders`` folders directly under the root, which it lists
    /// itself. A path leaving the home is not asked, and says so; none found says so; any other
    /// refusal is said in its own words.
    public struct Lookup: Sendable {
        public enum Next: Equatable, Sendable {
            case ask(Frame)
            case found(FileName, FileEntry)
            case said(String)
            case nothing
        }

        let asked: String
        let workspace: String
        private var queue: [FileName] = []
        private var tried: Set<FileName> = []
        private var current: (id: Int32, place: FileName)?
        private var listing: (id: Int32, entries: [FileEntry])?
        private var listed: Bool
        private var id: Int32

        /// Looking for `path`, as the terminal shows it, from the folder the terminal reported.
        public init(path: String, cwd: String?, workspace: String, firstID: Int32) {
            asked = path
            self.workspace = workspace
            id = firstID
            let root = FileTree.root.shown, absolute = path.hasPrefix("/") || path.hasPrefix("~/")
            listed = absolute
            guard let fromRoot = TerminalPath.resolved(path, in: root) else { return }
            let fromCwd = absolute ? nil : cwd.flatMap { TerminalPath.resolved(path, in: $0) }
            for place in [fromCwd, fromRoot].compactMap({ $0 }).map({ FileName($0) }) where !queue.contains(place) {
                queue.append(place)
            }
        }

        /// What it asks first, or says at once of a path leaving the home.
        public mutating func start() -> Next {
            guard !queue.isEmpty else { return .said("\(asked) is outside the workspace's home, so Sail does not look for it.") }
            return next()
        }

        public mutating func receive(_ frame: Frame) -> Next {
            switch frame {
            case let .filesEntry(answered, entry, _) where answered == current?.id:
                return .found(current?.place ?? FileTree.root, entry)
            case let .filesRefused(answered, kind, message) where answered == current?.id:
                return kind == .known(.notFound) ? next() : .said(message)
            case let .filesListing(page) where page.id == listing?.id:
                listing?.entries += page.entries
                guard !page.more, let entries = listing?.entries else { return .nothing }
                listing = nil
                let root = FileTree.root.shown
                let folders = entries
                    .filter { ($0.kind == .known(.folder) || $0.kind == .known(.link) && $0.target == .known(.folder)) && !$0.name.shown.hasPrefix(".") }
                    .map(\.name.shown).sorted().prefix(TerminalPath.folders)
                for folder in folders {
                    guard let place = TerminalPath.resolved(asked, in: root + "/" + folder).map({ FileName($0) }), !tried.contains(place), !queue.contains(place) else { continue }
                    queue.append(place)
                }
                return next()
            case let .filesRefused(answered, _, message) where answered == listing?.id:
                listing = nil
                return .said(message)
            default:
                return .nothing
            }
        }

        /// The next place asked; the root listed once the first places are spent; then none found.
        private mutating func next() -> Next {
            current = nil
            if !queue.isEmpty {
                let place = queue.removeFirst()
                tried.insert(place)
                id += 1
                current = (id, place)
                return .ask(.filesStat(id: id, path: place))
            }
            guard listed else {
                listed = true
                id += 1
                listing = (id, [])
                return .ask(.filesList(id: id, path: FileTree.root))
            }
            return .said("No file at \(asked) in \(workspace).")
        }
    }
}
