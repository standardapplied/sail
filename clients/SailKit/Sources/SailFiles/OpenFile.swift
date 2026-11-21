import Foundation
import SailAPI
import SailTerminalWire

/// A text file open in a workspace's file pane: read whole and shown as it arrives, typed in only
/// once the view holds every byte of it (F15); clean exactly when its text is the bytes last read or
/// saved, byte for byte (F14). Clean, it follows the file; unsaved, it never takes the workspace's
/// bytes by itself, and takes a new version only with bytes equal to its own. Written only by the
/// person's save, one at a time, the bytes the view holds, against a version the person was shown,
/// never over a newer file (F2, F13).
///
/// Nothing here speaks to a connection: each act answers what to ask and what the pane must show,
/// and the caller does both, so the whole of it is tested on its own.
public struct OpenFile: Sendable {
    /// The largest file shown: past it nothing is read.
    public static let limit: Int64 = 20 * 1024 * 1024
    /// How much of a file decides whether it is text.
    public static let decidedBy = 64 * 1024
    /// The base of a save that makes the file anew, never replacing one.
    public static let absent = "absent"

    /// What the pane asks the workspace.
    public enum Ask: Equatable, Sendable {
        /// The whole file, from its start.
        case read(id: Int32, path: FileName)
        case cancel(id: Int32)
        /// `bytes` as the file, only while it is at digest `base` (``absent``: while nothing is there);
        /// `seen` the version that base was read at, which lets the host refuse early.
        case write(id: Int32, path: FileName, base: String, seen: String, bytes: [UInt8])
        /// A save given up before its answer: its upload is ended on the wire, so the host lets go of
        /// the file at once rather than when the upload idles out.
        case cancelSave(id: Int32)
    }

    /// What the workspace answers.
    public enum Answer: Equatable, Sendable {
        /// The next bytes of read `id`; on the last, `digest` of every byte it sent, and `whole` when
        /// it ran from the file's start to its end. The frame with the digest is the read's last:
        /// `eof` says only that it reached the file's end, which a read stopped at the limit did not.
        case data(id: Int32, version: String, bytes: [UInt8], eof: Bool, whole: Bool, digest: String)
        case refused(id: Int32, kind: Open<FileRefusal>, message: String)
        /// Write `id` is the file now, at `digest`, its version `version`.
        case written(id: Int32, digest: String, version: String)
        /// Write `id` wrote nothing: the file is at `digest`, ``absent`` when gone.
        case conflict(id: Int32, digest: String)
    }

    /// What the pane shows.
    public enum State: Equatable, Sendable {
        case reading
        case text
        case notText(String)
        case tooLarge(String)
        case notAFile(String)
        case refused(String)
    }

    /// The one line over an open file.
    public enum Note: Equatable, Sendable {
        case none
        /// Unsaved, and the workspace's bytes differ from those it was based on (M15b).
        case changed
        /// Clean, and the file was deleted: the text stays, dimmed (M16a).
        case deleted
        /// Unsaved, and the file was deleted (M16b, M28).
        case deletedWhileTyped
        /// Unsaved, and the file grew past ``limit``: the text is kept here and cannot be saved.
        case tooLarge
        /// The view did not take the whole of what was read: nothing can be typed or saved.
        case notShownWhole
    }

    /// The workspace's bytes as a sheet shows them: what Keep Mine writes against and Keep Theirs
    /// takes, whatever the workspace holds by the time the person chooses.
    public struct Theirs: Equatable, Sendable {
        public let bytes: [UInt8]
        public let digest: String
        public let version: String

        public init(bytes: [UInt8], digest: String, version: String) {
            self.bytes = bytes
            self.digest = digest
            self.version = version
        }

        public var text: String {
            String(decoding: bytes, as: UTF8.self)
        }
    }

    /// Text given to the view, or taken by it: its UTF-16 length and the SHA-256 of its UTF-8, as
    /// lowercase hex (the digest the workspace gives a read). The view holds what it was given
    /// exactly when the two are equal.
    public struct TextAccount: Equatable, Sendable {
        public let units: Int
        public let digest: String

        public init(units: Int, digest: String) {
            self.units = units
            self.digest = digest
        }
    }

    /// What the pane must do now.
    public enum Event: Equatable, Sendable {
        /// The next piece of the file's text, appended as it arrives.
        case arrived(String)
        /// Every byte of the first read has arrived: once the view holds them all, ``inView(_:)``.
        case allArrived
        /// The view holds the whole file: it may be typed in.
        case whole
        /// The view's text is replaced whole, `account` being that text's; `chosen` when the person
        /// chose it (Keep Theirs, Discard Mine), so they can undo it.
        case replaced(String, TextAccount, chosen: Bool)
        /// The first read starts again from the file's start: what the view took of it is dropped.
        case restarted
        case saved
        /// A save asked while one was under way: that one answered, the pane's text is saved now.
        case saveAgain
        /// A save met a newer file: the sheet shows `theirs` beside the pane's text; `again` when it
        /// was Keep Mine, against a version the file has since moved past.
        case conflict(Theirs, again: Bool)
        /// A save met no file: Save Anyway or Discard.
        case gone
        case saveRefused(String)
        case notConnected
        /// A save whose answer the connection lost, decided by the read after it.
        case lostSave(applied: Bool)
    }

    /// What an act asks and what the pane must show.
    public struct Step: Equatable, Sendable {
        public var asks: [Ask] = []
        public var events: [Event] = []
    }

    private enum Why: Equatable {
        case open, follow, compare
    }

    private enum Write: Equatable {
        case save, keepMine, saveAnyway
    }

    public let path: FileName
    public let name: String
    private let size: Int64
    public private(set) var state: State
    /// The view holds every byte of the first read: keys and saves are taken.
    public private(set) var whole = false
    public private(set) var clean = true
    public private(set) var note = Note.none
    /// The digest of the bytes last read or saved: a save's base.
    public private(set) var digest = ""
    /// The version the watch holds the file at.
    public private(set) var version = ""
    /// The workspace's bytes, read while the pane was unsaved and found different.
    public private(set) var theirs: Theirs?
    private var base: [UInt8] = []
    private var baseUnits = 0
    private var text = ""
    /// Whether the pane was typed in since `text` was last given.
    private var textStale = false
    private var reading: (id: Int32, why: Why, bytes: [UInt8], shown: Int)?
    /// Every byte of the first read is here; the view may still be taking them.
    private var arrived = false
    /// Whether any piece of the first read was given to the view.
    private var given = false
    /// The text the view was last given whole: what it must hold before a key or a save.
    private var handed: TextAccount?
    private var saving: (id: Int32, bytes: [UInt8], why: Write)?
    /// ⌘S while a save was under way: saved again once that one is answered.
    private var saveAgain = false
    /// A save whose answer never came: the next read decides it (M32); `dropped` when the
    /// connection lost it, which is said.
    private var unanswered: (bytes: [UInt8], dropped: Bool)?
    /// The conflict being read was Keep Mine's.
    private var conflictAgain = false
    private var readAgain = false
    private var dueAtTick = false
    private var connected = true
    private var nextID: Int32

    /// `path` as the tree listed it; requests numbered from `firstID`, apart from the tree's.
    public init(path: FileName, entry: FileEntry, firstID: Int32 = 1 << 30) {
        self.path = path
        name = FileName(Array(path.bytes.split(separator: 0x2F).last ?? [])).shown
        size = entry.size
        nextID = firstID
        let kind = entry.kind == .known(.link) ? entry.target : entry.kind
        switch kind {
        case .known(.file) where entry.size > Self.limit:
            state = .tooLarge(Self.overTheLimit(name, entry.size))
        case .known(.file):
            state = .reading
        case .known(.missing), .known(.loop):
            state = .notAFile("\(name) is a link that leads nowhere.")
        default:
            state = .notAFile("\(name) isn't a file Sail can show: \(Self.special(entry.mode)).")
        }
    }

    /// Whether keys may change the text: the view holds the whole file, and it is text.
    public var canType: Bool {
        whole && state == .text
    }

    /// Begins reading it, unless it is not to be read.
    public mutating func open() -> Step {
        state == .reading ? Step(asks: [read(.open)]) : Step()
    }

    /// Ends it: the read under way is cancelled, and a save under way given up on the wire.
    public mutating func close() -> Step {
        var asks = reading.map { [Ask.cancel(id: $0.id)] } ?? []
        if let saving { asks.append(.cancelSave(id: saving.id)) }
        reading = nil
        saving = nil
        saveAgain = false
        return Step(asks: asks)
    }

    /// The view holds what it took of the first read, `taken` its account: equal to every byte read,
    /// the file may be typed in, and a change named meanwhile is read now; anything else and it may
    /// be neither typed in nor saved.
    public mutating func inView(_ taken: TextAccount) -> Step {
        guard arrived, !whole, state == .text else { return Step() }
        guard taken == handed else {
            note = .notShownWhole
            return Step()
        }
        whole = true
        var step = Step(events: [.whole])
        if readAgain {
            readAgain = false
            step.asks.append(read(.follow))
        }
        return step
    }

    /// The pane's text is now `units` UTF-16 units long, the text itself not read: a length other
    /// than the base's is unsaved for certain, decided here; nil for the base's own length, which
    /// only the text decides (``edited(_:)``). A key in a large file costs no copy of its text.
    public mutating func edited(units: Int) -> Step? {
        guard canType else { return Step() }
        guard units != baseUnits else { return nil }
        clean = false
        textStale = true
        return Step()
    }

    /// The pane's text is now `text`: clean exactly when its bytes are the base's. Back to clean
    /// with the workspace's newer bytes known, it takes them and follows again (M50).
    public mutating func edited(_ text: String) -> Step {
        guard canType else { return Step() }
        self.text = text
        textStale = false
        clean = equalsBase(text)
        guard clean else { return Step() }
        if note == .changed, let theirs { return take(theirs.bytes, digest: theirs.digest, version: theirs.version, chosen: false) }
        if note == .deletedWhileTyped { note = .deleted }
        return Step()
    }

    /// The watch named the file: it is read again, or once more after the read under way, or once
    /// the view holds the first read.
    public mutating func changed() -> Step {
        guard whole, reading == nil else {
            readAgain = true
            return Step()
        }
        return Step(asks: [read(.follow)])
    }

    /// The watch is live: a read refused as torn is asked again now.
    public mutating func tick() -> Step {
        guard dueAtTick, reading == nil else { return Step() }
        dueAtTick = false
        return whole ? Step(asks: [read(.follow)]) : reopen()
    }

    /// The connection ended: what is held stays; a save under way is decided by the next read, and
    /// the host lets go of its upload with the connection.
    public mutating func disconnected() {
        connected = false
        if let saving { unanswered = (saving.bytes, true) }
        saving = nil
        saveAgain = false
        reading = nil
    }

    /// A connection was admitted: the file is read again (M34), once the view holds the first read.
    public mutating func admitted() -> Step {
        connected = true
        guard state == .text || state == .reading else { return Step() }
        if whole { return Step(asks: [read(.follow)]) }
        guard !arrived else {
            readAgain = true
            return Step()
        }
        return reopen()
    }

    /// ⌘S of the pane's `text`, the view having taken `taken` of what it was given: written only
    /// while the file is at the digest read (M26, M27), and only while the view holds all it was
    /// given; a clean pane writes nothing. While a save is under way it is remembered, once, and
    /// asked again when that one is answered.
    public mutating func save(_ text: String, taken: TextAccount) -> Step {
        guard state == .text || state == .reading else { return Step() }
        if let words = unwritable(taken) { return Step(events: [.saveRefused(words)]) }
        guard saving == nil else {
            saveAgain = true
            return Step()
        }
        _ = edited(text)
        return clean ? Step() : write(against: digest, seen: version, why: .save)
    }

    /// Keep Mine: the pane's `text`, written against the version the sheet showed, `shown`.
    public mutating func keepMine(_ text: String, taken: TextAccount, shown: Theirs) -> Step {
        if let words = unwritable(taken) { return Step(events: [.saveRefused(words)]) }
        _ = edited(text)
        return write(against: shown.digest, seen: shown.version, why: .keepMine)
    }

    /// Save Anyway, for a deleted file: the pane's `text`, made anew, never replacing one (M28).
    public mutating func saveAnyway(_ text: String, taken: TextAccount) -> Step {
        if let words = unwritable(taken) { return Step(events: [.saveRefused(words)]) }
        _ = edited(text)
        return write(against: Self.absent, seen: "", why: .saveAnyway)
    }

    /// Keep Theirs: the workspace's bytes the sheet showed, `shown`; a newer file is then followed.
    public mutating func keepTheirs(_ shown: Theirs) -> Step {
        guard canType else { return Step() }
        return merge(giveUpSaving(), take(shown.bytes, digest: shown.digest, version: shown.version, chosen: true))
    }

    /// Discard Mine: the workspace's bytes, read if they are not held (M49).
    public mutating func discardMine() -> Step {
        guard canType else { return Step() }
        let given = giveUpSaving()
        if let theirs { return merge(given, take(theirs.bytes, digest: theirs.digest, version: theirs.version, chosen: true)) }
        clean = true
        note = .none
        text = String(decoding: base, as: UTF8.self)
        textStale = false
        let account = TextAccount(units: baseUnits, digest: digest)
        handed = account
        return merge(given, Step(asks: [read(.follow)], events: [.replaced(text, account, chosen: true)]))
    }

    /// What the workspace answered.
    public mutating func receive(_ answer: Answer) -> Step {
        switch answer {
        case let .data(id, version, bytes, eof, isWhole, digest):
            guard reading?.id == id else { return Step() }
            reading?.bytes += bytes
            return digest.isEmpty ? arriving() : finished(version: version, digest: digest, whole: isWhole && eof)
        case let .refused(id, kind, message):
            if saving?.id == id { return saveRefused(kind, message) }
            guard let held = reading, held.id == id else { return Step() }
            reading = nil
            return readRefused(kind, message, held.why)
        case let .written(id, digest, version):
            guard let saved = saving, saved.id == id else { return Step() }
            saving = nil
            adopt(saved.bytes, digest: digest)
            self.version = version
            note = .none
            theirs = nil
            var step = Step(events: [.saved])
            if saveAgain {
                saveAgain = false
                step.events.append(.saveAgain)
            }
            return step
        case let .conflict(id, digest):
            guard let saved = saving, saved.id == id else { return Step() }
            saving = nil
            saveAgain = false
            guard digest != Self.absent else {
                note = .deletedWhileTyped
                return Step(events: [.gone])
            }
            conflictAgain = saved.why == .keepMine
            return Step(asks: [read(.compare)])
        }
    }

    /// Why a write may not go now, in the person's words; nil when it may. The view must hold all it
    /// was given, `taken` against what the model gave it: the one check every write passes.
    private func unwritable(_ taken: TextAccount) -> String? {
        if note == .notShownWhole { return Self.notShownWhole(name) }
        guard whole else { return "\(name) is still loading. Nothing was saved." }
        guard taken == handed else { return Self.notShownWhole(name) }
        if note == .tooLarge { return Self.tooLargeNow(name) }
        return nil
    }

    /// The first read's pieces as they arrive, from the first chunk once text is decided.
    private mutating func arriving() -> Step {
        guard let held = reading, held.why == .open else { return Step() }
        if held.bytes.count >= Self.decidedBy || held.shown > 0 { return shown(ending: false) }
        return Step()
    }

    private mutating func shown(ending: Bool) -> Step {
        guard let held = reading else { return Step() }
        let decided = min(held.bytes.count, Self.decidedBy)
        if held.shown == 0, held.bytes[..<decided].contains(0) { return notText(held.bytes) }
        let complete = ending ? held.bytes.count : Self.completeUTF8(held.bytes)
        guard complete > held.shown else { return Step() }
        guard let piece = String(validating: held.bytes[held.shown ..< complete], as: UTF8.self) else { return notText(held.bytes) }
        reading?.shown = complete
        state = .text
        given = true
        return Step(events: [.arrived(piece)])
    }

    private mutating func finished(version: String, digest: String, whole isWhole: Bool) -> Step {
        guard let held = reading else { return Step() }
        if !isWhole, held.bytes.count >= Self.limit {
            reading = nil
            return grewTooLarge(held.why)
        }
        var step = Step()
        if held.why == .open {
            step = shown(ending: true)
            if case .notText = state { return step }
        }
        reading = nil
        if readAgain, whole {
            readAgain = false
            step.asks.append(read(.follow))
        }
        guard isWhole else { return step }
        guard held.why == .open || String(validating: held.bytes, as: UTF8.self) != nil && !held.bytes.prefix(Self.decidedBy).contains(0) else {
            self.version = version
            return held.why == .open ? step : merge(step, notTextAnyMore(held.bytes, digest: digest))
        }
        self.version = version
        switch held.why {
        case .open:
            state = .text
            adopt(held.bytes, digest: digest)
            handed = TextAccount(units: baseUnits, digest: digest)
            arrived = true
            step.events.append(.allArrived)
        case .follow:
            step = merge(step, settle(held.bytes, digest: digest))
        case .compare:
            let shown = Theirs(bytes: held.bytes, digest: digest, version: version)
            theirs = shown
            if !clean, held.bytes != base { note = .changed }
            step.events.append(.conflict(shown, again: conflictAgain))
            conflictAgain = false
        }
        return step
    }

    /// A read that stopped at the limit, short of the file's end: the file grew past it. A clean pane
    /// shows the sentence; an unsaved one keeps its text and says it can no longer be saved here.
    private mutating func grewTooLarge(_ why: Why) -> Step {
        let sentence = "\(name) is now over \(Self.amount(Self.limit)). Sail shows text files up to \(Self.amount(Self.limit))."
        guard why != .open else {
            state = .tooLarge(sentence)
            return Step(events: given ? [.restarted] : [])
        }
        if clean {
            state = .tooLarge(sentence)
        } else {
            note = .tooLarge
        }
        conflictAgain = false
        return Step()
    }

    /// A read after a change: the pane's own save under way is no change; a lost save decided and
    /// said; equal bytes move the base; a clean pane takes different bytes; an unsaved one only says so.
    private mutating func settle(_ bytes: [UInt8], digest: String) -> Step {
        if let saving, bytes == saving.bytes { return Step() }
        var lost: [Event] = []
        if let unanswered {
            self.unanswered = nil
            if bytes == unanswered.bytes {
                adopt(bytes, digest: digest)
                note = .none
                theirs = nil
                return Step(events: unanswered.dropped ? [.saved, .lostSave(applied: true)] : [.saved])
            }
            if unanswered.dropped { lost = [.lostSave(applied: false)] }
        }
        if bytes == base {
            self.digest = digest
            if note == .deleted || note == .deletedWhileTyped || note == .tooLarge { note = .none }
            return Step(events: lost)
        }
        if clean { return merge(take(bytes, digest: digest, version: nil, chosen: false), Step(events: lost)) }
        theirs = Theirs(bytes: bytes, digest: digest, version: version)
        note = .changed
        return Step(events: lost)
    }

    /// A followed read that is no longer text: a clean pane shows the sentence; an unsaved one keeps
    /// its text and says the file changed.
    private mutating func notTextAnyMore(_ bytes: [UInt8], digest: String) -> Step {
        guard clean else {
            theirs = Theirs(bytes: bytes, digest: digest, version: version)
            note = .changed
            return Step()
        }
        base = bytes
        self.digest = digest
        baseUnits = -1
        state = .notText("\(name) isn't text: \(Self.kind(of: bytes)), \(Self.amount(Int64(bytes.count))).")
        return Step()
    }

    private mutating func readRefused(_ kind: Open<FileRefusal>, _ message: String, _ why: Why) -> Step {
        if kind == .known(.changed) {
            dueAtTick = true
            return Step()
        }
        guard whole else {
            state = .refused(message)
            return Step()
        }
        if kind == .known(.notFound) { note = clean ? .deleted : .deletedWhileTyped }
        return Step()
    }

    private mutating func saveRefused(_ kind: Open<FileRefusal>, _ message: String) -> Step {
        guard let saved = saving else { return Step() }
        saving = nil
        saveAgain = false
        guard kind == .known(.failed) else { return Step(events: [.saveRefused(message)]) }
        unanswered = (saved.bytes, false)
        return Step(asks: [read(.follow)], events: [.saveRefused(message)])
    }

    /// The pane's text written: a save under way is given up first, on the wire.
    private mutating func write(against base: String, seen: String, why: Write) -> Step {
        guard connected else { return Step(events: [.notConnected]) }
        var step = giveUpSaving()
        let id = nextID
        nextID += 1
        let bytes = Array(text.utf8)
        saving = (id, bytes, why)
        step.asks.append(.write(id: id, path: path, base: base, seen: seen, bytes: bytes))
        return step
    }

    /// The save under way, given up: its upload cancelled, its answer ignored.
    private mutating func giveUpSaving() -> Step {
        saveAgain = false
        guard let saving else { return Step() }
        self.saving = nil
        return Step(asks: [.cancelSave(id: saving.id)])
    }

    /// The first read asked again from the file's start: the view drops what it took of the last.
    private mutating func reopen() -> Step {
        let restarted = given
        given = false
        arrived = false
        return Step(asks: [read(.open)], events: restarted ? [.restarted] : [])
    }

    /// The workspace's bytes become the pane's text and its base: it is clean and follows again.
    private mutating func take(_ bytes: [UInt8], digest: String, version: String?, chosen: Bool) -> Step {
        state = .text
        text = String(decoding: bytes, as: UTF8.self)
        textStale = false
        adopt(bytes, digest: digest)
        if let version { self.version = version }
        note = .none
        theirs = nil
        let account = TextAccount(units: baseUnits, digest: digest)
        handed = account
        return Step(events: [.replaced(text, account, chosen: chosen)])
    }

    private mutating func adopt(_ bytes: [UInt8], digest: String) {
        base = bytes
        self.digest = digest
        baseUnits = String(decoding: bytes, as: UTF8.self).utf16.count
        if !whole { text = String(decoding: bytes, as: UTF8.self) }
        clean = !textStale && equalsBase(text)
        if clean { theirs = nil }
    }

    /// Equal by bytes, its UTF-16 length first: a key that changes the length is told at once; the
    /// bytes compared whole where the text holds them in one run.
    private func equalsBase(_ text: String) -> Bool {
        guard text.utf16.count == baseUnits else { return false }
        if let same = text.utf8.withContiguousStorageIfAvailable({ mine in
            mine.count == base.count && base.withUnsafeBufferPointer { theirs in
                guard let one = mine.baseAddress, let other = theirs.baseAddress else { return true }
                return memcmp(one, other, mine.count) == 0
            }
        }) { return same }
        return text.utf8.elementsEqual(base)
    }

    private mutating func notText(_ bytes: [UInt8]) -> Step {
        let id = reading?.id
        reading = nil
        let total = whole || bytes.count > size ? Int64(bytes.count) : max(size, Int64(bytes.count))
        state = .notText("\(name) isn't text: \(Self.kind(of: bytes)), \(Self.amount(total)).")
        return Step(asks: id.map { [.cancel(id: $0)] } ?? [])
    }

    private mutating func read(_ why: Why) -> Ask {
        let id = nextID
        nextID += 1
        reading = (id, why, [], 0)
        return .read(id: id, path: path)
    }

    private func merge(_ one: Step, _ other: Step) -> Step {
        Step(asks: one.asks + other.asks, events: one.events + other.events)
    }

    /// The sentence for a file over the limit.
    static func overTheLimit(_ name: String, _ size: Int64) -> String {
        "\(name) is \(amount(size)). Sail shows text files up to \(amount(limit))."
    }

    /// What an unsaved pane says, and its save, once its file grew past the limit.
    public static func tooLargeNow(_ name: String) -> String {
        "\(name) is now over \(amount(limit)) on the workspace, so Sail can't show or save it here. Your text is kept here."
    }

    /// What a pane says, and its save, when the view did not take the whole of what was read.
    public static func notShownWhole(_ name: String) -> String {
        "Sail could not confirm all of \(name) is shown, so it can't be typed in or saved. Nothing was saved; close it and open it again."
    }

    /// How many of `bytes` end on a whole UTF-8 character: a chunk boundary may fall inside one.
    static func completeUTF8(_ bytes: [UInt8]) -> Int {
        var start = bytes.count
        while start > 0, start > bytes.count - 4, bytes[start - 1] & 0xC0 == 0x80 {
            start -= 1
        }
        guard start > 0 else { return bytes.count }
        let lead = bytes[start - 1]
        let needs = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1
        return bytes.count - (start - 1) >= needs ? bytes.count : start - 1
    }

    /// What a file that is not text holds, by its first bytes.
    static func kind(of bytes: [UInt8]) -> String {
        let marks: [([UInt8], String)] = [
            ([0x89, 0x50, 0x4E, 0x47], "a PNG image"), ([0xFF, 0xD8, 0xFF], "a JPEG image"), (Array("GIF8".utf8), "a GIF image"),
            (Array("%PDF".utf8), "a PDF document"), ([0x50, 0x4B, 0x03, 0x04], "a zip archive"), ([0x1F, 0x8B], "a gzip archive"),
            ([0x7F, 0x45, 0x4C, 0x46], "an ELF program"), ([0xCF, 0xFA, 0xED, 0xFE], "a Mach-O program"), ([0x00, 0x61, 0x73, 0x6D], "a WebAssembly module"),
        ]
        return marks.first { bytes.starts(with: $0.0) }?.1 ?? "binary data"
    }

    /// What a file that is neither a file nor a folder is, by its mode's type.
    static func special(_ mode: Int32) -> String {
        switch mode & 0o170000 {
        case 0o140000: "a socket"
        case 0o010000: "a pipe"
        case 0o020000, 0o060000: "a device"
        default: "not a regular file"
        }
    }

    /// A size as a person reads it: bytes, KB, MB or GB, in units of 1,024.
    static func amount(_ bytes: Int64) -> String {
        let units = ["KB", "MB", "GB"]
        var value = Double(bytes), unit = -1
        while value >= 1024, unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        guard unit >= 0 else { return bytes == 1 ? "1 byte" : "\(bytes) bytes" }
        return value < 10 && value.rounded() != value ? String(format: "%.1f %@", value, units[unit]) : "\(Int(value.rounded())) \(units[unit])"
    }
}
