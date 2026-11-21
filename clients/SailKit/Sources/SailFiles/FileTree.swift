import Foundation
import SailTerminalWire

/// What a workspace's files panel holds of its tree, and what it asks the host: the listings of the
/// folders it shows, by path; which folders are expanded (the root always); each folder's refusal; and
/// whether it is live. Names and kinds only, so a file's contents changing never moves a row.
///
/// Every change is a fresh listing: a folder the watch names is listed again whole and replaces the
/// one held, and the rows keep the person's selection, expansion and scroll by path. The watch set is
/// what is shown: every expanded folder, at the version its listing came at; collapsing a folder, or
/// its leaving its parent, drops it and what was expanded under it. A folder that changed while it was
/// listed is asked again at the next tick, so one that never stops changing is asked once a tick and
/// holds back nothing else. A tree is live once a tick or a listing comes after admission, and not
/// once it has heard none for ``silence``.
///
/// Nothing here speaks to a connection: each change answers the frames to send, and the caller sends
/// them, so the whole of it is tested on its own.
public struct FileTree: Sendable {
    /// The folder the tree shows: the workspace's own, under its home.
    public static let root: FileName = "/home/dev/workspace"
    /// How the root is named on screen.
    public static let rootName = "~/workspace"
    /// How long the tree may hear no tick and still say it is live.
    public static let silence = 2.0
    /// How long an entry that appeared through the watch is marked new.
    public static let newFor = 10.0
    /// How many paths a watch holds: past it, the root and the folders expanded most recently.
    public static let watchCap = 1024
    /// How many listings of a folder refused as changed in a row make it unsettled, said as such.
    public static let unsettledAfter = 3
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

        /// Its path, which is also its id, as bytes: a note's is its folder's with a suffix no file
        /// name can hold.
        public let path: FileName
        /// Its name as a person reads it.
        public let name: String
        public let depth: Int
        public let kind: Kind
        public let expanded: Bool
    }

    public private(set) var folders: [FileName: Folder] = [:]
    public private(set) var expanded: Set<FileName> = [Self.root]
    /// The folders expanded, the oldest expansion first: which the watch follows past its cap.
    private var recency: [FileName] = []
    public private(set) var refusals: [FileName: String] = [:]
    /// When each entry that appeared in a folder already shown was first listed.
    private var appeared: [FileName: Double] = [:]
    /// Broken links opened: each says why under it.
    private var explained: Set<FileName> = []
    /// Moves whenever the rows may have changed, and only then: what a redraw is skipped by.
    public private(set) var revision = 0
    /// The folder the last whole listing was of.
    public private(set) var lastListed: FileName?
    /// Whether a connection is admitted now.
    public private(set) var connected = false
    /// When the last tick, or the last listing, was heard, in the caller's seconds.
    public private(set) var heard: Double?
    /// Whether a tick or a listing has come since this connection was admitted.
    private var heardSinceAdmission = false
    private var pending: [Int32: Pending] = [:]
    /// Folders listed again while a listing of them is under way: listed once more after it.
    private var again: Set<FileName> = []
    /// Folders that changed while they were listed: asked again at the next tick, and no sooner
    /// however often the host names them meanwhile.
    private var atTick: Set<FileName> = []
    /// How many listings of each folder were refused as changed since one was taken.
    private var refusedInARow: [FileName: Int] = [:]
    /// When each folder's listing shown was taken.
    private var listedAt: [FileName: Double] = [:]
    /// The listings an admission asked: the watch waits for the last of them, then goes once.
    private var batch: Set<Int32> = []
    /// The watch set this connection was last sent.
    private var sent: [WatchedPath]?
    /// The file open in the pane, watched beside the folders at the version its pane holds.
    private var file: WatchedPath?
    /// Each folder's latest listing asked: an older one answering after it is not taken.
    private var latest: [FileName: Int32] = [:]
    private var nextID: Int32 = 1

    private struct Pending {
        let path: FileName
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
        guard connected, heardSinceAdmission, let heard else { return false }
        return now - heard <= Self.silence
    }

    /// Whether entry `path` appeared through the watch within ``newFor`` of `now`: an entry of a
    /// folder's first listing never did.
    public func isNew(_ path: FileName, at now: Double) -> Bool {
        guard let since = appeared[path] else { return false }
        return now - since <= Self.newFor
    }

    /// The entries marked new at `now`.
    public func newPaths(at now: Double) -> Set<FileName> {
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

    /// The first folder, by path, whose listings were refused as changed ``unsettledAfter`` times in
    /// a row, with when the listing shown of it was taken, if one was.
    public var unsettled: (path: FileName, shownAsOf: Double?)? {
        refusedInARow.filter { $0.value >= Self.unsettledAfter && expanded.contains($0.key) }.keys
            .min { $0.bytes.lexicographicallyPrecedes($1.bytes) }
            .map { ($0, listedAt[$0]) }
    }

    /// How a folder is named to a person: under the root by its path there, the root by its name.
    public static func shownName(of path: FileName) -> String {
        path == root ? rootName : FileName(Array(path.bytes.dropFirst(root.bytes.count + 1))).shown
    }

    /// Whether every expanded folder is followed live: false past ``watchCap``.
    public var followsAll: Bool {
        expanded.count <= Self.watchCap
    }

    /// The watch set: the root and the folders expanded most recently, up to ``watchCap``, each held
    /// one at the version its listing came at.
    public var watchSet: [WatchedPath] {
        let followed = [Self.root] + recency.suffix(Self.watchCap - 1)
        let shown = followed.sorted { $0.bytes.lexicographicallyPrecedes($1.bytes) }.compactMap { path in folders[path].map { WatchedPath(path: path, version: $0.version) } }
        return shown + [file].compactMap { $0 }
    }

    /// The file open in the pane, at the version its pane holds, or none: watched from now on.
    public mutating func watchFile(_ file: WatchedPath?) -> [Frame] {
        self.file = file
        return connected ? watch() : []
    }

    /// A connection was admitted: the watch set is sent again and every expanded folder listed again.
    public mutating func admitted() -> [Frame] {
        connected = true
        heardSinceAdmission = false
        pending = [:]
        again = []
        atTick = []
        refusedInARow = [:]
        sent = nil
        let watch = self.watch()
        let listings = expanded.sorted { $0.bytes.lexicographicallyPrecedes($1.bytes) }.map { list($0) }
        batch = Set(pending.keys)
        return watch + listings
    }

    /// The connection ended: what is held stays, and is not live.
    public mutating func disconnected() {
        connected = false
        pending = [:]
        again = []
        atTick = []
        batch = []
    }

    /// Expands folder `path`: listed when it is not held, and watched.
    public mutating func expand(_ path: FileName) -> [Frame] {
        guard !expanded.contains(path) else { return [] }
        expanded.insert(path)
        recency.append(path)
        revision += 1
        guard connected else { return [] }
        return [list(path)]
    }

    /// Opens the row at `path` as the person does: a folder expands; a broken link says why under it;
    /// a file, or a pipe, socket or device, opens nothing here.
    public mutating func open(_ path: FileName) -> [Frame] {
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
    public mutating func collapse(_ path: FileName) -> [Frame] {
        guard path != Self.root, expanded.contains(path) else { return [] }
        drop(path)
        return connected ? watch() : []
    }

    /// What frame `frame` from the host asks of the tree, heard at `now`: listings gathered and held,
    /// refusals said, changed folders listed again, ticks heard.
    public mutating func receive(_ frame: Frame, at now: Double) -> [Frame] {
        switch frame {
        case .filesTick:
            hear(at: now)
            let due = atTick
            atTick = []
            return relist(due)
        case let .filesChanged(paths):
            return relist(Set(paths).subtracting(atTick))
        case let .filesListing(listing):
            guard var held = pending[listing.id] else { return [] }
            held.entries += listing.entries
            guard !listing.more else {
                pending[listing.id] = held
                return []
            }
            pending[listing.id] = nil
            hear(at: now)
            guard latest[held.path] == listing.id else { return batchDone() }
            lastListed = held.path
            return took(Folder(entries: Self.sorted(held.entries), version: listing.version, truncated: listing.truncated), for: held.path, at: now)
        case let .filesRefused(id, kind, message):
            guard let held = pending.removeValue(forKey: id) else { return [] }
            hear(at: now)
            guard latest[held.path] == id, expanded.contains(held.path) else { return batchDone() }
            if kind == .known(.changed) {
                atTick.insert(held.path)
                refusedInARow[held.path, default: 0] += 1
                return batchDone()
            }
            refusals[held.path] = message
            folders[held.path] = nil
            revision += 1
            for path in expanded where path.isUnder(held.path) {
                drop(path)
            }
            return relisted(held.path) + batchDone()
        default:
            return []
        }
    }

    /// The rows shown: the root's entries, each expanded folder's under it, its refusal or the note
    /// that it holds more than shown under it in place. The root's own refusal is the panel's line
    /// (``FilesLine``), not a row.
    public var rows: [Row] {
        var rows: [Row] = []
        func add(_ path: FileName, depth: Int) {
            if let refusal = refusals[path] {
                guard path != Self.root else { return }
                rows.append(Row(path: path.noted("note"), name: refusal, depth: depth, kind: .note, expanded: false))
                return
            }
            guard let folder = folders[path] else { return }
            for entry in folder.entries {
                let child = path.appending(entry.name)
                let kind = Self.kind(of: entry)
                let open = kind == .folder && expanded.contains(child)
                rows.append(Row(path: child, name: entry.name.shown, depth: depth, kind: kind, expanded: open))
                if open { add(child, depth: depth + 1) }
                if kind == .brokenLink, explained.contains(child) {
                    let why = entry.target == .known(.loop) ? "This link leads back to itself." : "This link leads nowhere."
                    rows.append(Row(path: child.noted("why"), name: why, depth: depth + 1, kind: .note, expanded: false))
                }
            }
            if folder.truncated {
                rows.append(Row(path: path.noted("more"), name: Self.truncatedNote(folder.entries.count), depth: depth, kind: .note, expanded: false))
            }
        }
        add(Self.root, depth: 0)
        return rows
    }

    /// The folder holding `path`, nil for the root.
    public static func parent(of path: FileName) -> FileName? {
        guard path != root, let slash = path.bytes.lastIndex(of: 0x2F) else { return nil }
        return FileName(Array(path.bytes[..<slash]))
    }

    private mutating func took(_ folder: Folder, for path: FileName, at now: Double) -> [Frame] {
        guard expanded.contains(path) else { return relisted(path) }
        appeared = appeared.filter { now - $0.value <= Self.newFor }
        if let shown = folders[path] {
            let had = Set(shown.entries.map(\.name))
            for entry in folder.entries where !had.contains(entry.name) {
                appeared[path.appending(entry.name)] = now
            }
        }
        folders[path] = folder
        refusals[path] = nil
        refusedInARow[path] = nil
        listedAt[path] = now
        revision += 1
        let shown = Set(folder.entries.filter { Self.kind(of: $0) == .folder }.map { path.appending($0.name) })
        for child in expanded where Self.parent(of: child) == path && !shown.contains(child) {
            drop(child)
        }
        return relisted(path) + batchDone()
    }

    private mutating func hear(at now: Double) {
        heard = now
        heardSinceAdmission = true
    }

    /// Lists each expanded folder of `paths` again, or once more after the listing under way.
    private mutating func relist(_ paths: Set<FileName>) -> [Frame] {
        paths.filter { expanded.contains($0) }.sorted { $0.bytes.lexicographicallyPrecedes($1.bytes) }.flatMap { path -> [Frame] in
            if pending.values.contains(where: { $0.path == path }) {
                again.insert(path)
                return []
            }
            return [list(path)]
        }
    }

    /// The watch set when it changed: after a listing taken, except while an admission's listings
    /// are still answering, after whose last it goes once; a reconnect of many folders sends it
    /// once, not with each, and no folder still being listed holds it back past its admission.
    private mutating func batchDone() -> [Frame] {
        batch = batch.filter { pending[$0] != nil }
        return batch.isEmpty ? watch() : []
    }

    /// The watch set, unless this connection already holds it as it is.
    private mutating func watch() -> [Frame] {
        let paths = watchSet
        guard paths != sent else { return [] }
        sent = paths
        return [.filesWatch(paths)]
    }

    /// A folder changed while it was being listed is listed once more.
    private mutating func relisted(_ path: FileName) -> [Frame] {
        guard again.remove(path) != nil, expanded.contains(path), connected else { return [] }
        return [list(path)]
    }

    private mutating func drop(_ path: FileName) {
        revision += 1
        recency.removeAll { $0 == path || $0.isUnder(path) }
        for child in expanded where child == path || child.isUnder(path) {
            expanded.remove(child)
            folders[child] = nil
            refusals[child] = nil
            again.remove(child)
            atTick.remove(child)
            refusedInARow[child] = nil
            listedAt[child] = nil
        }
    }

    private mutating func list(_ path: FileName) -> Frame {
        let id = nextID
        nextID += 1
        pending[id] = Pending(path: path)
        latest[path] = id
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
            let order = one.name.shown.localizedStandardCompare(other.name.shown)
            return order == .orderedSame ? one.name.bytes.lexicographicallyPrecedes(other.name.bytes) : order == .orderedAscending
        }
    }
}

extension FileName {
    /// The path of entry `name` in this folder.
    public func appending(_ name: FileName) -> FileName {
        FileName(bytes + [0x2F] + name.bytes)
    }

    /// Whether this path lies somewhere under folder `folder`.
    public func isUnder(_ folder: FileName) -> Bool {
        bytes.count > folder.bytes.count + 1 && bytes.starts(with: folder.bytes + [0x2F])
    }

    /// A row's path with a suffix no file name can hold (a NUL), for the words shown under it.
    func noted(_ what: String) -> FileName {
        FileName(bytes + [0] + Array(what.utf8))
    }

    /// The one string that names this path in a list and nowhere else: the path itself while it is
    /// UTF-8, else its bytes in hex after a NUL, which no UTF-8 path holds; so two names that read
    /// alike are never one row.
    public var key: String {
        String(validating: bytes, as: UTF8.self) ?? "\u{0}" + bytes.map { String(format: "%02x", $0) }.joined()
    }
}
