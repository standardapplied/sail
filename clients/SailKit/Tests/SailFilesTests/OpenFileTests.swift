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

    /// The pane's text as its pieces arrived, and whether it may be typed in. `loaded` is what the
    /// view took of what it was given, the keys apart; a view that places at once holds the whole file
    /// as soon as it arrives, else only once ``place()``.
    struct Pane {
        var file: OpenFile
        var text = ""
        var loaded = ""
        var placesAtOnce = true
        var said: [OpenFile.Event] = []
        var asked: [OpenFile.Ask] = []

        init(_ path: FileName = OpenFileTests.path, size: Int, kind: FileKind = .file, mode: Int32 = 0o100644, placesAtOnce: Bool = true) {
            file = OpenFile(path: path, entry: OpenFileTests.entry(size, kind: kind, mode: mode))
            self.placesAtOnce = placesAtOnce
            take(file.open())
        }

        var taken: Int {
            loaded.utf16.count
        }

        mutating func take(_ step: OpenFile.Step) {
            asked += step.asks
            said += step.events
            for event in step.events {
                switch event {
                case let .arrived(piece):
                    text += piece
                    loaded += piece
                case let .replaced(all, _, _):
                    text = all
                    loaded = all
                case .restarted:
                    text = ""
                    loaded = ""
                case .allArrived:
                    if placesAtOnce { place() }
                default: break
                }
            }
        }

        /// The view holds every slice it was given.
        mutating func place() {
            take(file.inView(taken))
        }

        mutating func save() {
            take(file.save(text, taken: taken))
        }

        /// The workspace's bytes the last sheet showed.
        var shown: OpenFile.Theirs? {
            said.reversed().lazy.compactMap { if case let .conflict(theirs, _) = $0 { theirs } else { nil } }.first
        }

        var writes: Int {
            asked.filter { if case .write = $0 { true } else { false } }.count
        }

        var lastRead: Int32? {
            asked.reversed().lazy.compactMap { if case let .read(id, _) = $0 { id } else { nil } }.first
        }

        var lastWrite: (id: Int32, base: String, bytes: [UInt8])? {
            asked.reversed().lazy.compactMap { if case let .write(id, _, base, _, bytes) = $0 { (id, base, bytes) } else { nil } }.first
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

    /// B1: the last byte arriving is not the file whole: until the view holds every slice it is
    /// typed in by no key and written by no save, which says why; then it is.
    @Test func aViewStillTakingTheFileTakesNoKeyAndNoSave() throws {
        let text = String(repeating: "x", count: 300_000)
        var pane = Pane(size: text.utf8.count, placesAtOnce: false)
        pane.answer(Array(text.utf8))
        #expect(pane.said.contains(.allArrived) && !pane.file.canType, "the model took keys before the view held the file")
        pane.save()
        #expect(pane.writes == 0 && pane.said.last == .saveRefused("tide.ts is still loading. Nothing was saved."))
        pane.take(pane.file.edited(String(text.dropLast())))
        #expect(pane.file.clean, "a key was taken while the view still took the file")
        pane.place()
        #expect(pane.file.canType && pane.said.last == .whole)
    }

    /// The one check every write passes: the view holds exactly the units it was given; anything
    /// else writes nothing and says why. A view that took other than it was given is never typed in.
    @Test func aWriteGoesOnlyWhileTheViewHoldsAllItWasGiven() throws {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        let refusal = OpenFile.Event.saveRefused(OpenFile.notShownWhole("tide.ts"))
        #expect(pane.file.save(pane.text, taken: pane.taken - 1) == OpenFile.Step(events: [refusal]))
        #expect(pane.file.saveAnyway(pane.text, taken: pane.taken + 1) == OpenFile.Step(events: [refusal]))
        let theirs = OpenFile.Theirs(bytes: Array("theirs\n".utf8), digest: Self.digest(Array("theirs\n".utf8)), version: "v2")
        #expect(pane.file.keepMine(pane.text, taken: -1, shown: theirs) == OpenFile.Step(events: [refusal]))
        var short = Pane(size: 300_000, placesAtOnce: false)
        short.answer([UInt8](repeating: 0x61, count: 300_000))
        short.loaded.removeLast()
        short.place()
        #expect(!short.file.canType && short.file.note == .notShownWhole, "a view missing a slice was typed in")
        short.save()
        #expect(short.writes == 0 && short.said.last == refusal)
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

    /// B2: a change, or a connection admitted, before the view holds the first read reads nothing
    /// until it does; then the file is read once.
    @Test func aChangeBeforeTheViewHoldsTheFileIsReadOnceItDoes() throws {
        let text = String(repeating: "x", count: 300_000)
        var pane = Pane(size: text.utf8.count, placesAtOnce: false)
        pane.answer(Array(text.utf8))
        let asked = pane.asked.count
        pane.take(pane.file.changed())
        pane.file.disconnected()
        pane.take(pane.file.admitted())
        #expect(pane.asked.count == asked, "a read was asked while the view still took the first: \(pane.asked.suffix(2))")
        pane.place()
        #expect(pane.asked.count == asked + 1 && pane.lastRead != nil, "the change was never read")
    }

    /// A first read cut short starts again from the file's start, the view dropping what it took.
    @Test func aFirstReadCutShortStartsAgainAndTheViewDropsWhatItTook() throws {
        let bytes = [UInt8](repeating: 0x61, count: 300_000)
        var pane = Pane(size: bytes.count)
        let id = try #require(pane.lastRead)
        pane.take(pane.file.receive(.data(id: id, version: "v1", bytes: Array(bytes[..<262_144]), eof: false, whole: false, digest: "")))
        #expect(!pane.text.isEmpty)
        pane.file.disconnected()
        pane.take(pane.file.admitted())
        #expect(pane.said.last == .restarted && pane.text.isEmpty && pane.lastRead != id)
        pane.answer(bytes)
        #expect(pane.file.canType && pane.text.utf8.count == bytes.count)
    }

    /// M4: a character split at a chunk boundary is text, never "isn't text".
    @Test func aCharacterSplitAtAChunkBoundaryIsText() throws {
        let bytes = Array(String(repeating: "x", count: 262_143).utf8) + Array("⚓é".utf8)
        var pane = Pane(size: bytes.count)
        let id = try #require(pane.lastRead)
        pane.take(pane.file.receive(.data(id: id, version: "v1", bytes: Array(bytes[..<262_144]), eof: false, whole: false, digest: "")))
        #expect(pane.file.state == .text && pane.text.utf8.count == 262_143, "a split ⚓ was not held back: \(pane.file.state)")
        pane.take(pane.file.receive(.data(id: id, version: "v1", bytes: Array(bytes[262_144...]), eof: true, whole: true, digest: Self.digest(bytes))))
        #expect(pane.file.state == .text && pane.text.utf8.elementsEqual(bytes))
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

    /// M4: the base's length with other bytes is unsaved: the bytes decide, not the length.
    @Test func theBasesLengthWithOtherBytesIsUnsaved() {
        var pane = Self.opened("abc")
        #expect(pane.file.edited(units: 3) == nil)
        pane.type("abd")
        #expect(!pane.file.clean, "a character replaced by another of the same length was clean")
    }

    /// A key that changes the text's length is unsaved for certain, with no text read; the same
    /// length as the base needs the text, and the model says so.
    @Test func aKeyThatChangesTheLengthIsDecidedWithoutTheText() {
        var pane = Self.opened("one\n")
        #expect(pane.file.edited(units: 5) == OpenFile.Step() && !pane.file.clean)
        #expect(pane.file.edited(units: 4) == nil, "the base's length was decided without the text")
        pane.type("one\n")
        #expect(pane.file.clean)
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
        pane.save()
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
        if !pane.file.save(pane.text, taken: pane.taken).asks.isEmpty { return "a save with no edit wrote" }
        let text = pane.text, middle = text.index(text.startIndex, offsetBy: text.count / 2)
        for typed in [text + "x", String(text[..<middle]) + "x" + String(text[middle...])] {
            pane.type(typed)
            pane.type(text)
            if !pane.file.clean { return "a character typed and deleted is not clean" }
        }
        pane.type(text + "x")
        guard case let .write(_, _, base, _, written)? = pane.file.save(pane.text, taken: pane.taken).asks.first else { return "a save after an edit wrote nothing" }
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

    /// The round trip at the limit, edited only at the end: walking 20 MiB by character, as the
    /// shared check's middle edit does, costs seconds of CPU that starve other suites' deadlines.
    @Test func aFileOneByteUnderTheLimitComesBackByteForByte() throws {
        let bytes = [UInt8](repeating: 0x61, count: Int(OpenFile.limit) - 3) + [0x0D, 0x0A]
        var pane = Pane(size: bytes.count)
        pane.answer(bytes)
        #expect(pane.file.canType && pane.text.utf8.count == bytes.count)
        #expect(pane.file.save(pane.text, taken: pane.taken).asks.isEmpty, "a save with no edit wrote")
        let text = pane.text
        pane.type(text + "x")
        pane.type(text)
        #expect(pane.file.clean, "a character typed and deleted is not clean")
        pane.type(text + "x")
        guard case let .write(_, _, base, _, written)? = pane.file.save(pane.text, taken: pane.taken).asks.first else {
            Issue.record("a save after an edit wrote nothing")
            return
        }
        let same = written.withUnsafeBufferPointer { mine in
            bytes.withUnsafeBufferPointer { read in
                guard let one = mine.baseAddress, let other = read.baseAddress else { return false }
                return memcmp(one, other, read.count) == 0
            }
        }
        #expect(base == Self.digest(bytes) && written.count == bytes.count + 1 && written.last == 0x78 && same)
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
        #expect(pane.said.contains { if case .replaced("one\ntwo\nthree\n", _, chosen: false) = $0 { true } else { false } })
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
        #expect(pane.file.theirs?.text == "one\ntheirs\n")
    }

    /// M4: a change named while a follow read runs is read once that read ends.
    @Test func aChangeDuringAReadIsReadAfterIt() throws {
        var pane = Self.opened("one\n")
        pane.take(pane.file.changed())
        let first = try #require(pane.lastRead)
        pane.take(pane.file.changed())
        #expect(pane.lastRead == first, "a second read went while the first ran")
        pane.answer(Array("two\n".utf8), version: "v2")
        #expect(pane.lastRead != first, "the change named during the read was never read")
    }

    /// M2: a file grown past the limit while open, its read stopping there short of the end: a clean
    /// pane says it is over the limit; an unsaved one keeps its text, says it can no longer be saved
    /// here, and its save writes nothing; the watch's version stays.
    @Test func aFileGrownPastTheLimitIsSaidAndTypedTextKept() throws {
        let over = [UInt8](repeating: 0x61, count: Int(OpenFile.limit))
        var clean = Self.opened("one\n")
        clean.take(clean.file.changed())
        clean.take(clean.file.receive(.data(id: try #require(clean.lastRead), version: "v9", bytes: over, eof: false, whole: false, digest: Self.digest(over))))
        #expect(clean.file.state == .tooLarge("tide.ts is now over 20 MB. Sail shows text files up to 20 MB.") && clean.file.version == "v1")
        var typed = Self.opened("one\n")
        typed.type("mine\n")
        typed.take(typed.file.changed())
        typed.take(typed.file.receive(.data(id: try #require(typed.lastRead), version: "v9", bytes: over, eof: false, whole: false, digest: Self.digest(over))))
        #expect(typed.file.state == .text && typed.file.note == .tooLarge && typed.text == "mine\n" && typed.file.version == "v1")
        typed.save()
        #expect(typed.writes == 0 && typed.said.last == .saveRefused(OpenFile.tooLargeNow("tide.ts")))
        var opening = Pane(size: 4)
        opening.take(opening.file.receive(.data(id: try #require(opening.lastRead), version: "v9", bytes: over, eof: false, whole: false, digest: Self.digest(over))))
        #expect(opening.file.state == .tooLarge("tide.ts is now over 20 MB. Sail shows text files up to 20 MB.") && !opening.file.canType)
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
        #expect(pane.said.contains { if case .replaced("theirs\n", _, chosen: true) = $0 { true } else { false } }, "Discard Mine was not the person's choice, to undo")
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
        pane.save()
        let write = try #require(pane.lastWrite)
        #expect(write.base == Self.digest(Array("one\n".utf8)) && write.bytes == Array("one\ntwo\n".utf8))
        pane.type("one\ntwo\nthree\n")
        pane.take(pane.file.receive(.written(id: write.id, digest: "s1", version: "w-" + "s1")))
        #expect(!pane.file.clean && pane.file.digest == "s1", "typed during the save, yet clean")
        pane.type("one\ntwo\n")
        #expect(pane.file.clean)
    }

    /// M4: a save answered after more typing, the keys decided by length alone, leaves the pane
    /// unsaved: its answer is not the text the view holds now.
    @Test func aSaveAnsweredAfterMoreTypingLeavesThePaneUnsaved() throws {
        var pane = Self.opened("abc")
        pane.type("abcX")
        pane.save()
        let write = try #require(pane.lastWrite)
        #expect(pane.file.edited(units: 5) == OpenFile.Step())
        pane.take(pane.file.receive(.written(id: write.id, digest: Self.digest(write.bytes), version: "v2")))
        #expect(!pane.file.clean, "the save's answer made clean a pane typed in since")
    }

    /// M27: a stale base writes nothing; the workspace's bytes are read for the sheet; Keep Mine
    /// saves against the version shown, and meets a third change as another conflict.
    @Test func aSaveOverANewerFileWritesNothingAndShowsBoth() throws {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.save()
        let first = try #require(pane.lastWrite)
        pane.take(pane.file.receive(.conflict(id: first.id, digest: "t1")))
        pane.answer(Array("theirs\n".utf8), version: "v2")
        let shown = try #require(pane.shown)
        #expect(shown.text == "theirs\n" && pane.said.last == .conflict(shown, again: false) && pane.text == "mine\n" && !pane.file.clean)
        pane.take(pane.file.keepMine(pane.text, taken: pane.taken, shown: shown))
        let again = try #require(pane.lastWrite)
        #expect(again.base == Self.digest(Array("theirs\n".utf8)) && again.bytes == Array("mine\n".utf8), "Keep Mine did not save against the version shown")
        pane.take(pane.file.receive(.conflict(id: again.id, digest: "t2")))
        pane.answer(Array("third\n".utf8), version: "v3")
        let third = try #require(pane.shown)
        #expect(third.text == "third\n" && pane.said.last == .conflict(third, again: true), "a newer file after Keep Mine was not said as changed again")
        pane.take(pane.file.keepTheirs(third))
        #expect(pane.text == "third\n" && pane.file.clean)
    }

    /// B3: the sheet's version is the one written against and taken, whatever the workspace read
    /// while it was up: Keep Mine over a newer file meets it as a conflict, changed again; Keep Theirs
    /// takes what the sheet showed, and the newer file is followed after.
    @Test func theSheetsChoicesActOnTheVersionItShowed() throws {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.save()
        pane.take(pane.file.receive(.conflict(id: try #require(pane.lastWrite).id, digest: "a")))
        pane.answer(Array("A\n".utf8), version: "v2")
        let shown = try #require(pane.shown)
        pane.take(pane.file.changed())
        pane.answer(Array("B\n".utf8), version: "v3")
        #expect(pane.file.theirs?.text == "B\n" && pane.file.note == .changed)
        var mine = pane
        mine.take(mine.file.keepMine(mine.text, taken: mine.taken, shown: shown))
        let write = try #require(mine.lastWrite)
        #expect(write.base == Self.digest(Array("A\n".utf8)), "Keep Mine wrote against \(write.base), not the version the sheet showed")
        guard case let .write(_, _, _, seen, _)? = mine.asked.last else { return }
        #expect(seen == "v2")
        mine.take(mine.file.receive(.conflict(id: write.id, digest: Self.digest(Array("B\n".utf8)))))
        mine.answer(Array("B\n".utf8), version: "v3")
        #expect(mine.said.last == .conflict(OpenFile.Theirs(bytes: Array("B\n".utf8), digest: Self.digest(Array("B\n".utf8)), version: "v3"), again: true))
        var theirs = pane
        theirs.take(theirs.file.keepTheirs(shown))
        #expect(theirs.text == "A\n" && theirs.file.clean && theirs.file.version == "v2" && theirs.file.digest == shown.digest)
        theirs.take(theirs.file.changed())
        theirs.answer(Array("B\n".utf8), version: "v3")
        #expect(theirs.text == "B\n" && theirs.file.clean, "the newer file was not followed after Keep Theirs")
    }

    /// M1: one save at a time. ⌘S while one is under way is remembered once and, its answer come,
    /// saved against the new base if the text still differs; a conflict drops it; the pane's own
    /// save read back by the watch is not a change on the workspace.
    @Test func oneSaveAtATime() throws {
        var pane = Self.opened("abc")
        pane.type("abcX")
        pane.save()
        let first = try #require(pane.lastWrite)
        pane.type("abcXY")
        pane.save()
        pane.save()
        #expect(pane.writes == 1, "a second write went while the first was under way")
        pane.take(pane.file.changed())
        pane.answer(first.bytes, version: "v2")
        #expect(pane.file.note == .none, "the pane's own save was said as changed on the workspace")
        pane.take(pane.file.receive(.written(id: first.id, digest: Self.digest(first.bytes), version: "v2")))
        let second = try #require(pane.lastWrite)
        #expect(pane.writes == 2 && second.base == Self.digest(first.bytes) && second.bytes == Array("abcXY".utf8))
        var same = Self.opened("abc")
        same.type("abcX")
        same.save()
        same.save()
        same.take(same.file.receive(.written(id: try #require(same.lastWrite).id, digest: Self.digest(Array("abcX".utf8)), version: "v2")))
        #expect(same.writes == 1 && same.file.clean, "a remembered save of the text just saved wrote again")
        var met = Self.opened("abc")
        met.type("abcX")
        met.save()
        met.save()
        met.take(met.file.receive(.conflict(id: try #require(met.lastWrite).id, digest: "t")))
        met.answer(Array("theirs".utf8), version: "v2")
        #expect(met.writes == 1, "a remembered save went after a conflict")
    }

    /// N2: the remembered ⌘S writes the text as it was at that ⌘S, not as it is when the first is
    /// answered: typing on after it stays unsaved, and undoing back past it writes nothing over the
    /// person's own first save.
    @Test func aRememberedSaveWritesTheTextOfItsOwnPress() throws {
        var on = Self.opened("abc")
        on.type("abcX")
        on.save()
        let first = try #require(on.lastWrite)
        on.type("abcXY")
        on.save()
        on.type("abcXYZ")
        on.take(on.file.receive(.written(id: first.id, digest: Self.digest(first.bytes), version: "v2")))
        let second = try #require(on.lastWrite)
        #expect(on.writes == 2 && second.bytes == Array("abcXY".utf8), "the remembered save wrote \(String(decoding: second.bytes, as: UTF8.self))")
        on.take(on.file.receive(.written(id: second.id, digest: Self.digest(second.bytes), version: "v3")))
        #expect(!on.file.clean, "the text typed after the second ⌘S was taken as saved")
        var back = Self.opened("abc")
        back.type("abcX")
        back.save()
        let saved = try #require(back.lastWrite)
        back.save()
        back.type("abc")
        back.take(back.file.receive(.written(id: saved.id, digest: Self.digest(saved.bytes), version: "v2")))
        #expect(back.writes == 1 && !back.file.clean, "undoing past the second ⌘S wrote the original over the person's first save")
    }

    /// Every way a save ends without being written.
    enum SaveEnd: String, CaseIterable {
        case refusal, disconnect, close, givenUp, conflict
    }

    /// A ⌘S remembered while a save is under way is forgotten however that save ends unwritten: a
    /// later save the person makes, once answered, writes nothing remembered from before.
    @Test(arguments: SaveEnd.allCases)
    func aSaveEndedUnwrittenForgetsTheRememberedSave(_ end: SaveEnd) throws {
        var pane = Self.opened("abc")
        pane.type("abcX")
        pane.save()
        let first = try #require(pane.lastWrite)
        pane.type("A")
        pane.save()
        switch end {
        case .refusal:
            pane.take(pane.file.receive(.refused(id: first.id, kind: Open(rawValue: "no_space"), message: "dock's disk is full. Nothing was changed.")))
        case .disconnect:
            pane.file.disconnected()
            pane.take(pane.file.admitted())
            pane.answer(Array("abc".utf8), version: "v1")
        case .close:
            pane.take(pane.file.close())
        case .givenUp:
            pane.type("K")
            pane.take(pane.file.keepMine(pane.text, taken: pane.taken, shown: OpenFile.Theirs(bytes: Array("abc".utf8), digest: Self.digest(Array("abc".utf8)), version: "v1")))
            let replacing = try #require(pane.lastWrite)
            pane.take(pane.file.receive(.written(id: replacing.id, digest: Self.digest(replacing.bytes), version: "v2")))
        case .conflict:
            pane.take(pane.file.receive(.conflict(id: first.id, digest: "t")))
            pane.answer(Array("theirs".utf8), version: "v2")
            pane.take(pane.file.changed())
            pane.answer(Array("abc".utf8), version: "v3")
        }
        let before = pane.writes
        pane.type("B")
        pane.save()
        let later = try #require(pane.lastWrite)
        #expect(pane.writes == before + 1 && later.bytes == Array("B".utf8), "\(end): the later save did not write B")
        pane.take(pane.file.receive(.written(id: later.id, digest: Self.digest(later.bytes), version: "v9")))
        #expect(pane.writes == before + 1 && pane.file.clean, "\(end): something was written after the later save: \(String(decoding: pane.lastWrite?.bytes ?? [], as: UTF8.self))")
        #expect(!pane.asked.contains { if case let .write(_, _, _, _, bytes) = $0 { bytes == Array("A".utf8) } else { false } }, "\(end): the ⌘S remembered as \"A\" was written after its save ended unwritten")
    }

    /// A save after a clean pane followed a change writes against what it followed to: the view's
    /// units are handed over with the text that replaced it.
    @Test func aSaveAfterAFollowWrites() throws {
        var pane = Self.opened("one\n")
        pane.take(pane.file.changed())
        pane.answer(Array("two lines\n".utf8), version: "v2")
        #expect(pane.text == "two lines\n" && pane.file.clean)
        pane.type("two lines!\n")
        pane.save()
        let write = try #require(pane.lastWrite, "a save after a follow wrote nothing: \(pane.said.last.map { "\($0)" } ?? "")")
        #expect(write.bytes == Array("two lines!\n".utf8) && write.base == Self.digest(Array("two lines\n".utf8)))
    }

    /// N3: a conflict forgets the remembered ⌘S: a later save that lands writes nothing remembered
    /// from before it.
    @Test func aConflictForgetsTheRememberedSave() throws {
        var pane = Self.opened("abc")
        pane.type("abcX")
        pane.save()
        let first = try #require(pane.lastWrite)
        pane.type("abcXY")
        pane.save()
        pane.take(pane.file.receive(.conflict(id: first.id, digest: "t")))
        pane.answer(Array("theirs".utf8), version: "v2")
        pane.take(pane.file.changed())
        pane.answer(Array("abc".utf8), version: "v3")
        pane.type("abcXYZ")
        pane.save()
        let third = try #require(pane.lastWrite)
        pane.take(pane.file.receive(.written(id: third.id, digest: Self.digest(third.bytes), version: "v4")))
        #expect(pane.writes == 2 && pane.file.clean, "a ⌘S remembered before the conflict was written after it: \(pane.writes) writes")
    }

    /// M1: a save given up is cancelled on the wire: by a close, and by a write that replaces it,
    /// whose answer alone counts after; a connection lost cancels nothing, the host letting go.
    @Test func aSaveGivenUpIsCancelledOnTheWire() throws {
        var closing = Self.opened("abc")
        closing.type("abcX")
        closing.save()
        let id = try #require(closing.lastWrite).id
        #expect(closing.file.close().asks == [.cancelSave(id: id)])
        var replaced = Self.opened("abc")
        replaced.type("abcX")
        replaced.save()
        let old = try #require(replaced.lastWrite).id
        let shown = OpenFile.Theirs(bytes: Array("t".utf8), digest: Self.digest(Array("t".utf8)), version: "v2")
        let step = replaced.file.keepMine(replaced.text, taken: replaced.taken, shown: shown)
        replaced.take(step)
        let new = try #require(replaced.lastWrite).id
        #expect(step.asks.first == .cancelSave(id: old) && step.asks.count == 2)
        replaced.take(replaced.file.receive(.written(id: old, digest: Self.digest(Array("abcX".utf8)), version: "v2")))
        #expect(!replaced.file.clean && !replaced.said.contains(.saved), "the answer of a save given up was taken")
        replaced.take(replaced.file.receive(.written(id: new, digest: Self.digest(Array("abcX".utf8)), version: "v3")))
        #expect(replaced.file.clean)
        var dropped = Self.opened("abc")
        dropped.type("abcX")
        dropped.save()
        dropped.file.disconnected()
        dropped.take(dropped.file.admitted())
        #expect(!dropped.asked.contains { if case .cancelSave = $0 { true } else { false } })
    }

    /// M32: a save whose answer was lost is decided by the next read: the bytes it sent mean saved;
    /// anything else is the workspace's change, said as such.
    @Test func aLostAnswerIsDecidedByTheNextRead() throws {
        var saved = Self.opened("one\n")
        saved.type("mine\n")
        saved.save()
        saved.file.disconnected()
        saved.take(saved.file.admitted())
        saved.answer(Array("mine\n".utf8), version: "v2")
        #expect(saved.file.clean && saved.file.note == .none && saved.said.suffix(2) == [.saved, .lostSave(applied: true)])
        var lost = Self.opened("one\n")
        lost.type("mine\n")
        lost.save()
        lost.file.disconnected()
        lost.take(lost.file.admitted())
        lost.answer(Array("theirs\n".utf8), version: "v2")
        #expect(!lost.file.clean && lost.file.note == .changed && lost.text == "mine\n" && lost.said.last == .lostSave(applied: false))
        var unchanged = Self.opened("one\n")
        unchanged.type("mine\n")
        unchanged.save()
        unchanged.file.disconnected()
        unchanged.take(unchanged.file.admitted())
        unchanged.answer(Array("one\n".utf8), version: "v1")
        #expect(!unchanged.file.clean && unchanged.file.note == .none && unchanged.said.last == .lostSave(applied: false), "a save lost unapplied was decided quietly")
    }

    /// A save refused as `failed` cannot tell whether it was written: the file is read again and
    /// decided as a lost answer is. Any other refusal is said and the pane stays unsaved (M29, M30).
    @Test func aSaveRefusedIsSaidAndAFailedOneIsReadAgain() throws {
        var full = Self.opened("one\n")
        full.type("mine\n")
        full.save()
        full.take(full.file.receive(.refused(id: try #require(full.lastWrite).id, kind: Open(rawValue: "no_space"), message: "dock's disk is full (12 KB free). Nothing was changed.")))
        #expect(full.said.last == .saveRefused("dock's disk is full (12 KB free). Nothing was changed.") && !full.file.clean)
        var failed = Self.opened("one\n")
        failed.type("mine\n")
        failed.save()
        let reads = failed.asked.count
        failed.take(failed.file.receive(.refused(id: try #require(failed.lastWrite).id, kind: .known(.failed), message: "Sail could not tell whether tide.ts was saved; read it again.")))
        #expect(failed.asked.count == reads + 1, "a save of unknown outcome was not read again")
        failed.answer(Array("mine\n".utf8), version: "v2")
        #expect(failed.file.clean && failed.said.contains(.saved))
    }

    /// A save tells the host the version its base was read at; Keep Mine the version of what the
    /// sheet showed; Save Anyway none; a save's answer makes its digest and version the next base.
    @Test func aSaveCarriesTheVersionItsBaseWasReadAt() throws {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.save()
        guard case let .write(first, _, _, seen, _)? = pane.asked.last else {
            Issue.record("no write")
            return
        }
        #expect(seen == "v1")
        pane.take(pane.file.receive(.conflict(id: first, digest: "t1")))
        pane.answer(Array("theirs\n".utf8), version: "v2")
        pane.take(pane.file.keepMine(pane.text, taken: pane.taken, shown: try #require(pane.shown)))
        guard case let .write(again, _, base, mine, _)? = pane.asked.last else {
            Issue.record("Keep Mine wrote nothing")
            return
        }
        #expect(base == Self.digest(Array("theirs\n".utf8)) && mine == "v2", "Keep Mine said \(mine) for what the sheet showed at v2")
        pane.take(pane.file.receive(.written(id: again, digest: "s1", version: "v3")))
        #expect(pane.file.digest == "s1" && pane.file.version == "v3" && pane.file.clean)
    }

    /// M28: a save meeting a deleted file writes nothing and says so; Save Anyway makes it anew,
    /// never replacing.
    @Test func aSaveOfADeletedFileAsksBeforeMakingItAnew() throws {
        var pane = Self.opened("one\n")
        pane.type("mine\n")
        pane.save()
        pane.take(pane.file.receive(.conflict(id: try #require(pane.lastWrite).id, digest: OpenFile.absent)))
        #expect(pane.file.note == .deletedWhileTyped && pane.said.last == .gone)
        pane.take(pane.file.saveAnyway(pane.text, taken: pane.taken))
        #expect(pane.lastWrite?.base == OpenFile.absent && pane.lastWrite?.bytes == Array("mine\n".utf8))
    }

    /// F13, F15: nothing is written before the file is whole, nor without a save: opening, typing,
    /// following, discarding and reconnecting ask no write.
    @Test func nothingIsWrittenWithoutASave() throws {
        var pane = Pane(size: 300_000)
        let id = try #require(pane.lastRead)
        pane.take(pane.file.receive(.data(id: id, version: "v1", bytes: [UInt8](repeating: 0x61, count: 262_144), eof: false, whole: false, digest: "")))
        pane.save()
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
        let step = pane.file.save(pane.text, taken: pane.taken)
        #expect(step.asks.isEmpty && step.events == [.notConnected] && !pane.file.clean)
    }
}
