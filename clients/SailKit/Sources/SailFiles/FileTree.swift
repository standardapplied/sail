import Foundation
import SailTerminalWire

/// What a workspace's files panel holds of its tree, and what it asks the host: the listings of the
/// folders it shows, by path; which folders are expanded (the root always); each folder's refusal; and
/// whether it is live. Names and kinds only, so a file's contents changing never moves a row.
///
/// Every change is a fresh listing: a folder the watch names is listed again whole and replaces the
/// one held, and the rows keep the person's selection, expansion and scroll by path. The watch set is
/// what is shown: every expanded folder, at the version its listing came at; collapsing a folder, or
/// its leaving its parent, drops it and what was expanded under it. A tree that has heard no tick for
/// ``silence`` is not live until a fresh listing arrives.
///
/// Nothing here speaks to a connection: each change answers the frames to send, and the caller sends
/// them, so the whole of it is tested on its own.
public struct FileTree: Sendable {
    /// The folder the tree shows: the workspace's own, under its home.
    public static let root = "/home/dev/workspace"
    /// How the root is named on screen.
    public static let rootName = "~/workspace"
    /// How long the tree may hear no tick and still say it is live.
    public static let silence = 2.0
    /// How long an entry that appeared through the watch is marked new.
    public static let newFor = 10.0
    /// How many paths a watch holds: past it, the root and the folders expanded most recently.
    public static let watchCap = 1024
    /// What a folder holding more than the host lists says.
    public static func truncatedNote(_ shown: Int) -> String {
        "This folder has more than \(shown.formatted()) entries; Sail shows \(shown.formatted()) of them."
    }

    /// A folder as last listed: its entries in the order shown, and the version it was listed at.
    public struct Folder: Equatable, Sendable {
        public var entries: [FileEntry]
        public var version: String
        public var truncated: Bool
    }

    /// One row as the panel shows it.
    public struct Row: Equatable, Sendable {
        public enum Kind: Equatable, Sendable {
            /// A folder, or a link to one, which expands.
            case folder
            case file
            /// A pipe, a socket or a device.
            case other
            /// A link that leads back to itself or nowhere.
            case brokenLink
            /// Words under a folder: its refusal, or that it holds more than is shown.
            case note
        }

        /// Its path, which is also its id: a note's is its folder's with a suffix.
        public let path: String
        public let name: String
        public let depth: Int
        public let kind: Kind
        public let expanded: Bool
    }

    public private(set) var folders: [String: Folder] = [:]
    public private(set) var expanded: Set<String> = [Self.root]
    /// The folders expanded, the oldest expansion first: which the watch follows past its cap.
    private var recency: [String] = []
    public private(set) var refusals: [String: String] = [:]
    /// When each entry that appeared in a folder already shown was first listed.
    private var appeared: [String: Double] = [:]
    /// Broken links opened: each says why under it.
    private var explained: Set<String> = []
    /// Moves whenever the rows may have changed, and only then: what a redraw is skipped by.
    public private(set) var revision = 0
    /// The folder the last whole listing was of.
    public private(set) var lastListed: String?
    /// Whether a connection is admitted now.
    public private(set) var connected = false
    /// When the last tick, or the last listing, was heard, in the caller's seconds.
    public private(set) var heard: Double?
    private var pending: [Int32: Pending] = [:]
    /// Folders listed again while a listing of them is under way: listed once more after it.
    private var again: Set<String> = []
    private var nextID: Int32 = 1

    private struct Pending {
        let path: String
        var entries: [FileEntry] = []
    }

    /// When the panel asked for the tree, in the caller's seconds: how long a first listing has
    /// been waited for is counted from here.
    public let opened: Double

    public init(openedAt opened: Double = 0) {
        self.opened = opened
    }

    /// Whether the tree is the workspace's truth: admitted, and a tick or a listing heard within
    /// ``silence`` of `now`.
    public func isLive(at now: Double) -> Bool {
        guard connected, let heard else { return false }
        return now - heard <= Self.silence
    }

    /// Whether entry `path` appeared through the watch within ``newFor`` of `now`: an entry of a
    /// folder's first listing never did.
    public func isNew(_ path: String, at now: Double) -> Bool {
        guard let since = appeared[path] else { return false }
        return now - since <= Self.newFor
    }

    /// The entries marked new at `now`.
    public func newPaths(at now: Double) -> Set<String> {
        Set(appeared.filter { now - $0.value <= Self.newFor }.keys)
    }

    /// When the last mark of an entry new at `now` ends.
    public func nextCueEnd(after now: Double) -> Double? {
        appeared.values.map { $0 + Self.newFor }.filter { $0 > now }.min()
    }

    /// Whether the root has been listed, or refused.
    public var rootKnown: Bool {
        folders[Self.root] != nil || refusals[Self.root] != nil
    }

    /// Whether every expanded folder is followed live: false past ``watchCap``.
    public var followsAll: Bool {
        expanded.count <= Self.watchCap
    }

    /// The watch set: the root and the folders expanded most recently, up to ``watchCap``, each held
    /// one at the version its listing came at.
    public var watchSet: [WatchedPath] {
        let followed = [Self.root] + recency.suffix(Self.watchCap - 1)
        return followed.sorted().compactMap { path in folders[path].map { WatchedPath(path: path, version: $0.version) } }
    }

    /// A connection was admitted: the watch set is sent again and every expanded folder listed again.
    public mutating func connected(at now: Double) -> [Frame] {
        connected = true
        heard = now
        pending = [:]
        again = []
        return [.filesWatch(watchSet)] + expanded.sorted().map { list($0) }
    }

    /// The connection ended: what is held stays, and is not live.
    public mutating func disconnected() {
        connected = false
        pending = [:]
        again = []
    }

    /// Expands folder `path`: listed when it is not held, and watched.
    public mutating func expand(_ path: String) -> [Frame] {
        guard !expanded.contains(path) else { return [] }
        expanded.insert(path)
        recency.append(path)
        revision += 1
        guard connected else { return [] }
        return [list(path)]
    }

    /// Opens the row at `path` as the person does: a folder expands; a broken link says why under it;
    /// a file, or a pipe, socket or device, opens nothing here.
    public mutating func open(_ path: String) -> [Frame] {
        guard let row = rows.first(where: { $0.path == path }) else { return [] }
        switch row.kind {
        case .folder: return expand(path)
        case .brokenLink:
            explained.insert(path)
            revision += 1
            return []
        default: return []
        }
    }

    /// Collapses folder `path`: it, and every folder expanded under it, is dropped and no longer watched.
    public mutating func collapse(_ path: String) -> [Frame] {
        guard path != Self.root, expanded.contains(path) else { return [] }
        drop(path)
        return connected ? [.filesWatch(watchSet)] : []
    }

    /// What frame `frame` from the host asks of the tree, heard at `now`: listings gathered and held,
    /// refusals said, changed folders listed again, ticks heard.
    public mutating func receive(_ frame: Frame, at now: Double) -> [Frame] {
        switch frame {
        case .filesTick:
            heard = now
            return []
        case let .filesChanged(paths):
            return paths.filter { expanded.contains($0) }.flatMap { path -> [Frame] in
                if pending.values.contains(where: { $0.path == path }) {
                    again.insert(path)
                    return []
                }
                return [list(path)]
            }
        case let .filesListing(listing):
            guard var held = pending[listing.id] else { return [] }
            held.entries += listing.entries
            guard !listing.more else {
                pending[listing.id] = held
                return []
            }
            pending[listing.id] = nil
            heard = now
            lastListed = held.path
            return took(Folder(entries: Self.sorted(held.entries), version: listing.version, truncated: listing.truncated), for: held.path, at: now)
        case let .filesRefused(id, _, message):
            guard let held = pending.removeValue(forKey: id) else { return [] }
            heard = now
            refusals[held.path] = message
            folders[held.path] = nil
            revision += 1
            for path in expanded where path.hasPrefix(held.path + "/") {
                drop(path)
            }
            return relisted(held.path) + [.filesWatch(watchSet)]
        default:
            return []
        }
    }

    /// The rows shown: the root's entries, each expanded folder's under it, its refusal or the note
    /// that it holds more than shown under it in place. The root's own refusal is the panel's line
    /// (``FilesLine``), not a row.
    public var rows: [Row] {
        var rows: [Row] = []
        func add(_ path: String, depth: Int) {
            if let refusal = refusals[path] {
                guard path != Self.root else { return }
                rows.append(Row(path: path + "\u{0}note", name: refusal, depth: depth, kind: .note, expanded: false))
                return
            }
            guard let folder = folders[path] else { return }
            for entry in folder.entries {
                let child = path + "/" + entry.name
                let kind = Self.kind(of: entry)
                let open = kind == .folder && expanded.contains(child)
                rows.append(Row(path: child, name: entry.name, depth: depth, kind: kind, expanded: open))
                if open { add(child, depth: depth + 1) }
                if kind == .brokenLink, explained.contains(child) {
                    let why = entry.target == .known(.loop) ? "This link leads back to itself." : "This link leads nowhere."
                    rows.append(Row(path: child + "\u{0}why", name: why, depth: depth + 1, kind: .note, expanded: false))
                }
            }
            if folder.truncated {
                rows.append(Row(path: path + "\u{0}more", name: Self.truncatedNote(folder.entries.count), depth: depth, kind: .note, expanded: false))
            }
        }
        add(Self.root, depth: 0)
        return rows
    }

    /// The folder holding `path`, nil for the root.
    public static func parent(of path: String) -> String? {
        guard path != root, let slash = path.lastIndex(of: "/") else { return nil }
        return String(path[..<slash])
    }

    private mutating func took(_ folder: Folder, for path: String, at now: Double) -> [Frame] {
        guard expanded.contains(path) else { return relisted(path) }
        appeared = appeared.filter { now - $0.value <= Self.newFor }
        if let shown = folders[path] {
            let had = Set(shown.entries.map(\.name))
            for entry in folder.entries where !had.contains(entry.name) {
                appeared[path + "/" + entry.name] = now
            }
        }
        folders[path] = folder
        refusals[path] = nil
        revision += 1
        let shown = Set(folder.entries.filter { Self.kind(of: $0) == .folder }.map { path + "/" + $0.name })
        for child in expanded where Self.parent(of: child) == path && !shown.contains(child) {
            drop(child)
        }
        return relisted(path) + [.filesWatch(watchSet)]
    }

    /// A folder changed while it was being listed is listed once more.
    private mutating func relisted(_ path: String) -> [Frame] {
        guard again.remove(path) != nil, expanded.contains(path), connected else { return [] }
        return [list(path)]
    }

    private mutating func drop(_ path: String) {
        revision += 1
        recency.removeAll { $0 == path || $0.hasPrefix(path + "/") }
        for child in expanded where child == path || child.hasPrefix(path + "/") {
            expanded.remove(child)
            folders[child] = nil
            refusals[child] = nil
            again.remove(child)
        }
    }

    private mutating func list(_ path: String) -> Frame {
        let id = nextID
        nextID += 1
        pending[id] = Pending(path: path)
        return .filesList(id: id, path: path)
    }

    /// How an entry is shown: a folder or a link to one expands; a link that leads nowhere or back to
    /// itself is broken.
    static func kind(of entry: FileEntry) -> Row.Kind {
        switch entry.kind {
        case .known(.folder): return .folder
        case .known(.file): return .file
        case .known(.link):
            switch entry.target {
            case .known(.folder): return .folder
            case .known(.file): return .file
            case .known(.missing), .known(.loop): return .brokenLink
            default: return .other
            }
        default: return .other
        }
    }

    /// Folders first, then as Finder sorts names. The connection's reader sorts each whole listing
    /// so before the main thread has it; sorted again here, a listing already in order costs one pass.
    public static func sorted(_ entries: [FileEntry]) -> [FileEntry] {
        entries.sorted { one, other in
            let oneFolder = kind(of: one) == .folder, otherFolder = kind(of: other) == .folder
            if oneFolder != otherFolder { return oneFolder }
            return one.name.localizedStandardCompare(other.name) == .orderedAscending
        }
    }
}
