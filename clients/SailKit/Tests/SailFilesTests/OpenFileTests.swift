import Foundation
import SailAPI
@testable import SailFiles
import SailTerminalWire
import Testing

/// A text file open in a pane: read whole, shown as it arrives, typed in once whole; clean exactly
/// when its text is the bytes last read or saved; following the file while clean and never replacing
/// typed text by itself; saved only on the person's act, never over a newer file.
@Suite struct OpenFileTests {
    static let path: FileName = "/home/dev/workspace/src/tide.ts"

    static func entry(_ size: Int, kind: FileKind = .file, target: FileKind = .none, mode: Int32 = 0o100644) -> FileEntry {
        FileEntry(name: "tide.ts", kind: .known(kind), target: .known(target), size: Int64(size), mtimeNanos: 0, mode: mode)
    }

    static func digest(_ bytes: [UInt8]) -> String {
        "d\(bytes.count)-\(bytes.reduce(0) { ($0 &* 31 &+ Int($1)) & 0xFFFFFF })"
    }

    /// The pane's text as its pieces arrived, and whether it may be typed in.
    struct Pane {
        var file: OpenFile
        var text = ""
        var said: [OpenFile.Event] = []
        var asked: [OpenFile.Ask] = []

        init(_ path: FileName = OpenFileTests.path, size: Int, kind: FileKind = .file, mode: Int32 = 0o100644) {
            file = OpenFile(path: path, entry: OpenFileTests.entry(size, kind: kind, mode: mode))
            take(file.open())
        }

        mutating func take(_ step: OpenFile.Step) {
            asked += step.asks
            said += step.events
            for event in step.events {
                switch event {
                case let .arrived(piece): text += piece
                case let .replaced(all): text = all
                default: break
                }
            }
        }

        var lastRead: Int32? {
            asked.reversed().lazy.compactMap { if case let .read(id, _) = $0 { id } else { nil } }.first
        }

        var lastWrite: (id: Int32, base: String, bytes: [UInt8])? {
            asked.reversed().lazy.compactMap { if case let .write(id, _, base, bytes) = $0 { (id, base, bytes) } else { nil } }.first
        }

        /// The workspace answers the last read with `bytes`, in chunks of `chunk`.
        mutating func answer(_ bytes: [UInt8], chunk: Int = 256 * 1024, version: String = "v1") {
            guard let id = lastRead else { return }
            var offset = 0
            repeat {
                let end = min(offset + chunk, bytes.count), last = end == bytes.count
                take(file.receive(.data(id: id, version: version, bytes: Array(bytes[offset ..< end]), eof: last, whole: last, digest: last ? OpenFileTests.digest(bytes) : "")))
                offset = end
            } while offset < bytes.count
        }

        mutating func type(_ text: String) {
            self.text = text
            take(file.edited(text))
        }
    }

    static func opened(_ text: String) -> Pane {
        var pane = Pane(size: text.utf8.count)
        pane.answer(Array(text.utf8))
        return pane
    }

    // MARK: Reading

    /// M12, F15: the read is whole, its pieces shown as they arrive, a chunk boundary inside a
    /// character never splitting it; keys wait until the last chunk.
    @Test func aWholeReadIsShownAsItArrivesAndTypedInOnceWhole() throws {
        let text = String(repeating: "ébène ⚓ ", count: 40_000)
        let bytes = Array(text.utf8)
        var pane = Pane(size: bytes.count)
        #expect(pane.asked == [.read(id: try #require(pane.lastRead), path: Self.path)])
        let id = try #require(pane.lastRead)
        let cut = 100_001
        pane.take(pane.file.receive(.data(id: id, version: "v1", bytes: Array(bytes[..<cut]), eof: false, whole: false, digest: "")))
        #expect(!pane.text.isEmpty && !pane.file.canType, "nothing shown from the first chunk, or typed in before the file was whole")
        pane.take(pane.file.receive(.data(id: id, version: "v1", bytes: Array(bytes[cut...]), eof: true, whole: true, digest: Self.digest(bytes))))
        #expect(pane.text == text && pane.file.canType && pane.file.clean && pane.file.digest == Self.digest(bytes))
        #expect(pane.said.contains(.whole))
    }

    /// Text is decided by content in the first 64 KiB, never by name: a NUL is not text, and the
    /// rest is not read; a `.png` holding text is text; invalid UTF-8 found later turns the pane to
    /// the sentence, never typed in (M14a, M14c).
    @Test func textIsDecidedByContentNotByName() throws {
        var png = Pane("/home/dev/workspace/chart.png", size: 319_488)
        let id = try #require(png.lastRead)
        png.take(png.file.receive(.data(id: id, version: "v1", bytes: [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] + [UInt8](repeating: 0, count: 65_536), eof: false, whole: false, digest: "")))
        #expect(png.file.state == .notText("chart.png isn't text: a PNG image, 312 KB.") && png.asked.last == .cancel(id: id))
        var named = Pane("/home/dev/workspace/notes.png", size: 5)
        named.answer(Array("hello".utf8))
        #expect(named.file.state == .text && named.text == "hello", "a text file was judged by its name")
        var nul = Pane("/home/dev/workspace/records.dat", size: 2048)
        nul.answer(Array("valid UTF-8 until".utf8) + [0] + [UInt8](repeating: 0x62, count: 2030))
        #expect(nul.file.state == .notText("records.dat isn't text: binary data, 2 KB.") && nul.text.isEmpty, "a NUL in valid UTF-8 was taken as text")
        let good = [UInt8](repeating: 0x61, count: 100_000)
        var late = Pane(size: good.count + 3)
        late.answer(good + [0xC3, 0x28, 0x61])
        #expect(late.file.state == .notText("tide.ts isn't text: binary data, 98 KB.") && !late.file.canType)
    }

    /// Over the limit nothing is read; a socket is never read; a broken link opens nothing (M13, M14b).
    @Test func whatIsNotReadIsSaidAtOnce() {
        let large = Pane("/home/dev/workspace/soundings.csv", size: 48 * 1024 * 1024)
        #expect(large.file.state == .tooLarge("soundings.csv is 48 MB. Sail shows text files up to 20 MB.") && large.asked.isEmpty)
        let socket = Pane("/home/dev/workspace/events.sock", size: 0, kind: .other, mode: 0o140755)
        #expect(socket.file.state == .notAFile("events.sock isn't a file Sail can show: a socket.") && socket.asked.isEmpty)
    }

    /// A close cancels the read under way.
    @Test func aCloseCancelsTheReadUnderWay() throws {
        var pane = Pane(size: 1_000_000)
        let id = try #require(pane.lastRead)
        #expect(pane.file.close().asks == [.cancel(id: id)])
    }

    // MARK: Clean

    /// Clean means equal, by bytes: typing makes it unsaved, typing back makes it clean; two
    /// spellings of one character that compare equal as strings are not equal bytes.
    @Test func cleanMeansTheBytesLastReadOrSaved() {
        var pane = Self.opened("café\n")
        pane.type("café!\n")
        #expect(!pane.file.clean)
        pane.type("café\n")
        #expect(pane.file.clean)
        pane.type("cafe\u{301}\n")
        #expect(!pane.file.clean, "a decomposed é compared equal to the composed one read")
    }

    /// M54: CRLF, a BOM, mixed endings and no final newline come back byte for byte.
    @Test func whatIsReadIsWhatIsSavedByteForByte() throws {
        let read: [UInt8] = [0xEF, 0xBB, 0xBF] + Array("one\r\ntwo\nthree\r\nno newline".utf8)
        var pane = Pane(size: read.count)
        pane.answer(read)
        let text = pane.text
        pane.type(text + "x")
        pane.type(text)
        pane.type(text + "y")
        pane.take(pane.file.save())
        let write = try #require(pane.lastWrite)
        #expect(write.bytes == read + Array("y".utf8) && write.base == Self.digest(read))
    }

    /// For any bytes that open as text: a save with no edit sends nothing; a character typed and
    /// deleted, anywhere, is clean again; a save then sends exactly the bytes read with the edit.
    static func roundTrips(_ bytes: [UInt8], chunk: Int) -> String? {
        var pane = Pane(size: bytes.count)
        pane.answer(bytes, chunk: chunk)
        guard pane.file.canType else { return nil }
        if !pane.text.utf8.elementsEqual(bytes) { return "the text shown is not the bytes read" }
        if !pane.file.save().asks.isEmpty { return "a save with no edit wrote" }
        let text = pane.text, middle = text.index(text.startIndex, offsetBy: text.count / 2)
        for typed in [text + "x", String(text[..<middle]) + "x" + String(text[middle...])] {
            pane.type(typed)
            pane.type(text)
            if !pane.file.clean { return "a character typed and deleted is not clean" }
        }
        pane.type(text + "x")
        guard case let .write(_, _, base, written)? = pane.file.save().asks.first else { return "a save after an edit wrote nothing" }
        return written == bytes + Array("x".utf8) && base == OpenFileTests.digest(bytes) ? nil : "the bytes saved are not the bytes read and the edit"
    }

    @Test(arguments: [
        ("a lone CR", Array("one\rtwo".utf8)),
        ("mixed endings", Array("a\r\nb\nc\rd\r\n".utf8)),
        ("a BOM", [0xEF, 0xBB, 0xBF] + Array("bom\n".utf8)),
        ("a BOM alone", [0xEF, 0xBB, 0xBF]),
        ("ending mid-line", Array("no newline at the end".utf8)),
        ("empty", []),
        ("CRLF across a chunk", Array(String(repeating: "x", count: 262_143).utf8) + Array("\r\nmore".utf8)),
        ("a character across a chunk", Array(String(repeating: "x", count: 262_143).utf8) + Array("⚓é".utf8)),
    ] as [(String, [UInt8])])
    func oddFilesComeBackByteForByte(_ name: String, _ bytes: [UInt8]) {
        #expect(Self.roundTrips(bytes, chunk: 256 * 1024) == nil, "\(name)")
    }

    @Test func aFileOneByteUnderTheLimitComesBackByteForByte() {
        let bytes = [UInt8](repeating: 0x61, count: Int(OpenFile.limit) - 2) + [0x0D, 0x0A]
        #expect(Self.roundTrips(bytes, chunk: 256 * 1024) == nil)
    }

    /// Bytes drawn at random from line endings, a BOM, accented and astral characters, tabs and
    /// stray high bytes, cut into chunks of random sizes: every one that opens as text comes back.
    @Test func randomFilesThatOpenAsTextComeBackByteForByte() {
        var seed: UInt64 = 0x5EED
        func next() -> Int {
            seed = seed &+ 0x9E37_79B9_7F4A_7C15
            var z = seed
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return Int(truncatingIfNeeded: (z ^ (z >> 31)) >> 1)
        }
        let pieces: [[UInt8]] = [[0x0D], [0x0A], [0x0D, 0x0A], [0xEF, 0xBB, 0xBF], Array("é".utf8), Array("⚓".utf8), Array("🧭".utf8), [0x09], Array("tide".utf8), [0x20], [0xC3], [0xFF], [0x80]]
        var opened = 0
        for round in 0 ..< 300 {
            let alphabet = next() % 3 == 0 ? pieces.count : pieces.count - 3
            let bytes = (0 ..< next() % 400).flatMap { _ in pieces[next() % alphabet] }
            let chunk = 1 + next() % 97
            var pane = Pane(size: bytes.count)
            pane.answer(bytes, chunk: chunk)
            guard pane.file.canType else { continue }
            opened += 1
            if let wrong = Self.roundTrips(bytes, chunk: chunk) { Issue.record("round \(round), \(bytes.count) bytes in chunks of \(chunk): \(wrong)") }
        }
        #expect(opened >= 30, "only \(opened) of the random files opened as text")
    }

    /// A followed read that is no longer text: a clean pane shows the sentence, and follows back to
    /// text when it is again; an unsaved pane keeps its text and says the file changed.
    @Test func aFollowedFileThatStopsBeingTextNeverTakesTypedText() throws {
        var clean = Self.opened("one\n")
        clean.take(clean.file.changed())
        clean.answer([0x89, 0x50, 0x4E, 0x47, 0x00, 0x01], version: "v2")
        #expect(clean.file.state == .notText("tide.ts isn't text: a PNG image, 6 bytes.") && !clean.file.canType)
        clean.take(clean.file.changed())
        clean.answer(Array("two\n".utf8), version: "v3")
        #expect(clean.file.state == .text && clean.text == "two\n" && clean.file.canType, "a file that is text again was not followed back")
        var typed = Self.opened("one\n")
        typed.type("mine\n")
        typed.take(typed.file.changed())
        typed.answer([0x89, 0x50, 0x4E, 0x47, 0x00, 0x01], version: "v2")
        #expect(typed.file.state == .text && typed.text == "mine\n" && typed.file.note == .changed && typed.file.canType)
    }

    // MARK: Following

    /// F14: clean, a change is read and shown; the same bytes again only move the base.
    @Test func aCleanPaneFollowsTheFile() throws {
        var pane = Self.opened("one\ntwo\n")
        pane.take(pane.file.changed())
        pane.answer(Array("one\ntwo\nthree\n".utf8), version: "v2")
        #expect(pane.text == "one\ntwo\nthree\n" && pane.file.clean && pane.file.version == "v2")
        #expect(pane.said.contains(.replaced("one\ntwo\nthree\n")))
        let replaced = pane.said.count
        pane.take(pane.file.changed())
        pane.answer(Array("one\ntwo\nthree\n".utf8), version: "v3")
        #expect(pane.said.count == replaced && pane.file.version == "v3", "the same bytes replaced the text")
    }

    /// M15b, M15c: unsaved, equal bytes move the base and raise nothing; different bytes raise the
    /// line and change no text.
    @Test func anUnsavedPaneNeverReloadsByItself() throws {
        var pane = Self.opened("one\n")
        pane.type("one\nmine\n")
        pane.take(pane.file.changed())
        pane.answer(Array("one\n".utf8), version: "v2")
        #expect(pane.file.note == .none && pane.file.version == "v2" && pane.text == "one\nmine\n")
        pane.take(pane.file.changed())
        pane.answer(Array("one\ntheirs\n".utf8), version: "v3")
        #expect(pane.file.note == .changed && pane.text == "one\nmine\n" && !pane.file.clean, "an unsaved pane was replaced or stayed silent")
        #expect(pane.file.theirs.map { String(decoding: $0, as: UTF8.self) } == "one\ntheirs\n")
    }

    /// M50: undoing back to the saved text makes it clean, and following resumes with what the
    /// workspace has now.
    @Test func undoingBackToCleanResumesFollowing() {
        var pane = Self.opened("one\n")
        pane.type("one\nmine\n")
        pane.take(pane.file.changed())
        pane.answer(Array("one\ntheirs\n".utf8), version: "v2")
        pane.type("one\n")
        #expect(pane.file.clean && pane.file.note == .none && pane.text == "one\ntheirs\n")
    }

    /// M49: Discard Mine shows the workspace's bytes and follows again.
    @Test func discardMineTakesTheWorkspacesBytes() {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.take(pane.file.changed())
        pane.answer(Array("theirs\n".utf8), version: "v2")
        pane.take(pane.file.discardMine())
        #expect(pane.text == "theirs\n" && pane.file.clean && pane.file.note == .none && pane.file.digest == Self.digest(Array("theirs\n".utf8)))
    }

    /// M16a, M16b: a deleted file keeps the text: dimmed while clean, said while typed in.
    @Test func aDeletedFileKeepsTheText() throws {
        var clean = Self.opened("one\n")
        clean.take(clean.file.changed())
        clean.take(clean.file.receive(.refused(id: try #require(clean.lastRead), kind: .known(.notFound), message: "No tide.ts in dock.")))
        #expect(clean.file.note == .deleted && clean.text == "one\n")
        var typed = Self.opened("one\n")
        typed.type("mine\n")
        typed.take(typed.file.changed())
        typed.take(typed.file.receive(.refused(id: try #require(typed.lastRead), kind: .known(.notFound), message: "No tide.ts in dock.")))
        #expect(typed.file.note == .deletedWhileTyped && typed.text == "mine\n")
    }

    /// A read refused as changed (torn) is asked again at the next tick, not at once.
    @Test func aTornReadIsAskedAgainAtTheTick() throws {
        var pane = Self.opened("one\n")
        pane.take(pane.file.changed())
        let id = try #require(pane.lastRead)
        let refused = pane.file.receive(.refused(id: id, kind: .known(.changed), message: "tide.ts changed while it was read."))
        #expect(refused.asks.isEmpty)
        pane.take(refused)
        pane.take(pane.file.tick())
        #expect(pane.lastRead != id)
    }

    // MARK: Saving

    /// M26: a save on a matching base writes the pane's bytes against the digest read; its answer
    /// makes the pane clean at the new digest, unless it was typed in meanwhile.
    @Test func aSaveOnAMatchingBase() throws {
        var pane = Self.opened("one\n")
        pane.type("one\ntwo\n")
        pane.take(pane.file.save())
        let write = try #require(pane.lastWrite)
        #expect(write.base == Self.digest(Array("one\n".utf8)) && write.bytes == Array("one\ntwo\n".utf8))
        pane.type("one\ntwo\nthree\n")
        pane.take(pane.file.receive(.written(id: write.id, version: "s1")))
        #expect(!pane.file.clean && pane.file.digest == "s1", "typed during the save, yet clean")
        pane.type("one\ntwo\n")
        #expect(pane.file.clean)
    }

    /// M27: a stale base writes nothing; the workspace's bytes are read for the sheet; Keep Mine
    /// saves against the version shown, and meets a third change as another conflict.
    @Test func aSaveOverANewerFileWritesNothingAndShowsBoth() throws {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.take(pane.file.save())
        let first = try #require(pane.lastWrite)
        pane.take(pane.file.receive(.conflict(id: first.id, version: "t1")))
        pane.answer(Array("theirs\n".utf8), version: "v2")
        #expect(pane.said.contains(.conflict(theirs: "theirs\n")) && pane.text == "mine\n" && !pane.file.clean)
        pane.take(pane.file.keepMine())
        let again = try #require(pane.lastWrite)
        #expect(again.base == Self.digest(Array("theirs\n".utf8)) && again.bytes == Array("mine\n".utf8), "Keep Mine did not save against the version shown")
        pane.take(pane.file.receive(.conflict(id: again.id, version: "t2")))
        pane.answer(Array("third\n".utf8), version: "v3")
        #expect(pane.said.last == .conflict(theirs: "third\n"))
        pane.take(pane.file.keepTheirs())
        #expect(pane.text == "third\n" && pane.file.clean)
    }

    /// M32: a save whose answer was lost is decided by the next read: the bytes it sent mean saved;
    /// anything else is the workspace's change, said as such.
    @Test func aLostAnswerIsDecidedByTheNextRead() throws {
        var saved = Self.opened("one\n")
        saved.type("mine\n")
        saved.take(saved.file.save())
        saved.file.disconnected()
        saved.take(saved.file.admitted())
        saved.answer(Array("mine\n".utf8), version: "v2")
        #expect(saved.file.clean && saved.file.note == .none && saved.said.contains(.saved))
        var lost = Self.opened("one\n")
        lost.type("mine\n")
        lost.take(lost.file.save())
        lost.file.disconnected()
        lost.take(lost.file.admitted())
        lost.answer(Array("theirs\n".utf8), version: "v2")
        #expect(!lost.file.clean && lost.file.note == .changed && lost.text == "mine\n")
    }

    /// A save refused as `failed` cannot tell whether it was written: the file is read again and
    /// decided as a lost answer is. Any other refusal is said and the pane stays unsaved (M29, M30).
    @Test func aSaveRefusedIsSaidAndAFailedOneIsReadAgain() throws {
        var full = Self.opened("one\n")
        full.type("mine\n")
        full.take(full.file.save())
        full.take(full.file.receive(.refused(id: try #require(full.lastWrite).id, kind: Open(rawValue: "no_space"), message: "dock's disk is full (12 KB free). Nothing was changed.")))
        #expect(full.said.last == .saveRefused("dock's disk is full (12 KB free). Nothing was changed.") && !full.file.clean)
        var failed = Self.opened("one\n")
        failed.type("mine\n")
        failed.take(failed.file.save())
        let reads = failed.asked.count
        failed.take(failed.file.receive(.refused(id: try #require(failed.lastWrite).id, kind: .known(.failed), message: "Sail could not tell whether tide.ts was saved; read it again.")))
        #expect(failed.asked.count == reads + 1, "a save of unknown outcome was not read again")
        failed.answer(Array("mine\n".utf8), version: "v2")
        #expect(failed.file.clean && failed.said.contains(.saved))
    }

    /// M28: a save meeting a deleted file writes nothing and says so; Save Anyway makes it anew,
    /// never replacing.
    @Test func aSaveOfADeletedFileAsksBeforeMakingItAnew() throws {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.take(pane.file.save())
        pane.take(pane.file.receive(.conflict(id: try #require(pane.lastWrite).id, version: OpenFile.absent)))
        #expect(pane.file.note == .deletedWhileTyped && pane.said.last == .gone)
        pane.take(pane.file.saveAnyway())
        #expect(pane.lastWrite?.base == OpenFile.absent && pane.lastWrite?.bytes == Array("mine\n".utf8))
    }

    /// F13, F15: nothing is written before the file is whole, nor without a save: opening, typing,
    /// following, discarding and reconnecting ask no write.
    @Test func nothingIsWrittenWithoutASave() throws {
        var pane = Pane(size: 300_000)
        let id = try #require(pane.lastRead)
        pane.take(pane.file.receive(.data(id: id, version: "v1", bytes: [UInt8](repeating: 0x61, count: 262_144), eof: false, whole: false, digest: "")))
        pane.take(pane.file.save())
        pane.take(pane.file.edited("typed early"))
        #expect(pane.file.clean, "a key was taken before the file was whole")
        pane.answer([UInt8](repeating: 0x61, count: 300_000))
        pane.type("mine")
        pane.take(pane.file.changed())
        pane.answer(Array("theirs".utf8), version: "v2")
        pane.take(pane.file.discardMine())
        pane.file.disconnected()
        pane.take(pane.file.admitted())
        pane.answer(Array("theirs".utf8), version: "v3")
        #expect(pane.lastWrite == nil, "something was written without a save")
    }

    /// Not connected, a save writes nothing and says so (M33).
    @Test func notConnectedASaveSaysSoAndKeepsTheText() {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.file.disconnected()
        let step = pane.file.save()
        #expect(step.asks.isEmpty && step.events == [.notConnected] && !pane.file.clean)
    }
}
