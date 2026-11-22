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

    /// Looking for a path, one place at a time, the first file the workspace finds being the one:
    /// as written when absolute (`~/` the home); else in the folder the terminal reported, then in
    /// the workspace's root, each place resolving `..` on its own. A path leaving the home from
    /// every place is not asked, and says so; a folder is never the file; none found says where it
    /// looked; any other refusal is said in its own words.
    public struct Lookup: Sendable {
        public enum Next: Equatable, Sendable {
            case ask(Frame)
            case found(FileName, FileEntry)
            case said(String)
            case nothing
        }

        /// The most places one lookup asks: what its ids leave room for.
        public static let places = 2

        let asked: String
        private let unfound: String
        private var queue: [FileName] = []
        private var current: (id: Int32, place: FileName)?
        private var folder = false
        private var id: Int32

        /// Looking for `path`, as the terminal shows it, from the folder the terminal reported.
        public init(path: String, cwd: String?, workspace: String, firstID: Int32) {
            asked = path
            id = firstID
            let root = FileTree.root.shown, absolute = path.hasPrefix("/") || path.hasPrefix("~/")
            let folder = absolute ? nil : cwd.flatMap { Self.plain($0) ? TerminalPath.resolved(".", in: $0) : nil }
            var named: [String] = []
            for (place, name) in [(folder, folder.map { Self.name(of: $0, workspace: workspace) }), (root, workspace)] {
                guard let place, let name, let resolved = TerminalPath.resolved(path, in: place).map({ FileName($0) }), !queue.contains(resolved) else { continue }
                queue.append(resolved)
                named.append(name)
            }
            unfound = "No file at \(path) in " + (named.count == 2 ? "\(named[0]) or in \(workspace)'s root" : named.first ?? workspace) + "."
        }

        /// Whether a reported folder holds no control or format character (a newline, a bidi
        /// control, any C0 or C1, a line or paragraph separator): one that does is a program's text,
        /// never said as Sail's words, and is taken as unreported.
        private static func plain(_ folder: String) -> Bool {
            !folder.unicodeScalars.contains { [.control, .format, .lineSeparator, .paragraphSeparator].contains($0.properties.generalCategory) }
        }

        /// A folder as said in a sentence: under the root, from it; else from the home.
        private static func name(of folder: String, workspace: String) -> String {
            let root = FileTree.root.shown
            if folder == root { return workspace }
            if folder.hasPrefix(root + "/") { return String(folder.dropFirst(root.count + 1)) }
            return "~" + folder.dropFirst(TerminalPath.home.count)
        }

        /// What it asks first, or says at once of a path leaving the home.
        public mutating func start() -> Next {
            guard !queue.isEmpty else { return .said("\(asked) is outside the workspace's home, so Sail does not look for it.") }
            return next()
        }

        public mutating func receive(_ frame: Frame) -> Next {
            switch frame {
            case let .filesEntry(answered, entry, _) where answered == current?.id:
                guard entry.kind == .known(.folder) || entry.kind == .known(.link) && entry.target == .known(.folder) else {
                    return .found(current?.place ?? FileTree.root, entry)
                }
                folder = true
                return next()
            case let .filesRefused(answered, kind, message) where answered == current?.id:
                return kind == .known(.notFound) ? next() : .said(message)
            default:
                return .nothing
            }
        }

        /// The next place asked; with none left, a folder found or none found said.
        private mutating func next() -> Next {
            current = nil
            guard !queue.isEmpty else { return .said(folder ? "\(asked) is a folder." : unfound) }
            id += 1
            current = (id, queue.removeFirst())
            return .ask(.filesStat(id: id, path: current?.place ?? FileTree.root))
        }
    }
}
