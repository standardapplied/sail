import Foundation
import SailAPI
@testable import SailFiles
import SailTerminalWire
import Testing

/// The data-loss rule over random interleavings of what a file pane meets: pieces arriving and the
/// view taking them a slice at a time, keys, the agent's writes and the reads that follow them,
/// saves and their answers, conflicts and the sheet's choices, connections lost and found. After
/// every step, whatever was written is exactly the text the person had on screen at that moment,
/// against a version they were shown (a whole view of it, or a sheet), or made anew where they were
/// shown the file was gone; the view's text is replaced, but by the person's choice, only while it
/// is the base's; and keys are taken only while the view holds every slice.
@Suite struct OpenFilePropertyTests {
    static let path: FileName = "/home/dev/workspace/tide.ts"

    /// One run: a played workspace, the model, and a view that takes text a slice at a time.
    struct World {
        var random: SplitMix
        var file: OpenFile
        var disk: [UInt8]?
        var versions = 1
        var connected = true
        /// Requests the workspace holds: a read's chunks still to send, or a write to decide.
        var reads: [Int32: [OpenFile.Answer]] = [:]
        var writes: [Int32: (base: String, bytes: [UInt8])] = [:]
        var view = ""
        var loaded = ""
        var slices: [String] = []
        var allArrived = false
        var sheet: OpenFile.Theirs?
        var goneShown = false
        /// Digests the person was shown: every whole view, every sheet.
        var shown: Set<String> = []
        var acting = false
        /// The model's base as the act under way began: what the view must hold to be replaced.
        var baseBefore = ""
        var broken: [String] = []
        var log: [String] = []

        init(seed: UInt64) {
            random = SplitMix(seed: seed)
            let first = random.text()
            disk = Array(first.utf8)
            file = OpenFile(path: OpenFilePropertyTests.path, entry: FileEntry(name: "tide.ts", kind: .known(.file), target: .known(.none), size: Int64(first.utf8.count), mtimeNanos: 0, mode: 0o100644))
            noteBase()
            take(file.open())
        }

        var version: String {
            "v\(versions)"
        }

        var taken: OpenFile.TextAccount {
            OpenFile.TextAccount(units: loaded.utf16.count, digest: OpenFileTests.digest(Array(loaded.utf8)))
        }

        /// Before an act on the model: its base as the act begins.
        mutating func noteBase() {
            baseBefore = file.digest
        }

        mutating func take(_ step: OpenFile.Step) {
            for ask in step.asks {
                switch ask {
                case let .read(id, _): read(id)
                case let .cancel(id): reads[id] = nil
                case let .write(id, _, base, _, bytes):
                    if !acting { broken.append("a write went with no save asked") }
                    if bytes != Array(view.utf8) { broken.append("wrote \(String(decoding: bytes, as: UTF8.self).debugDescription), the view held \(view.debugDescription)") }
                    if !(shown.contains(base) || base == OpenFile.absent && goneShown) { broken.append("wrote against \(base), a version never shown") }
                    if connected { writes[id] = (base, bytes) }
                case let .cancelSave(id): writes[id] = nil
                }
            }
            for event in step.events {
                switch event {
                case let .arrived(piece):
                    var rest = Substring(piece)
                    while !rest.isEmpty {
                        let cut = rest.index(rest.startIndex, offsetBy: min(1 + random.below(4), rest.count))
                        slices.append(String(rest[..<cut]))
                        rest = rest[cut...]
                    }
                case .allArrived:
                    allArrived = true
                    if slices.isEmpty {
                        noteBase()
                        take(file.inView(taken))
                    }
                case let .replaced(text, _, chosen):
                    if !chosen, OpenFileTests.digest(Array(view.utf8)) != baseBefore {
                        broken.append("the view's \(view.debugDescription) was replaced by \(text.debugDescription), not by the person's choice, though it was not the base")
                    }
                    slices = []
                    view = text
                    loaded = text
                case .restarted:
                    slices = []
                    view = ""
                    loaded = ""
                    allArrived = false
                case .saved:
                    if !file.clean {
                        noteBase()
                        take(file.edited(view))
                    }
                case .saveAgain:
                    person { $0.file.save($0.view, taken: $0.taken) }
                case let .conflict(theirs, _):
                    sheet = theirs
                    shown.insert(theirs.digest)
                case .gone:
                    goneShown = true
                default: break
                }
            }
        }

        /// An act of the person's: a write may follow it.
        mutating func person(_ act: (inout World) -> OpenFile.Step) {
            acting = true
            baseBefore = file.digest
            let step = act(&self)
            take(step)
            acting = false
        }

        mutating func read(_ id: Int32) {
            guard connected else { return }
            guard let bytes = disk else {
                reads[id] = [.refused(id: id, kind: .known(.notFound), message: "No tide.ts.")]
                return
            }
            if random.below(20) == 0 {
                reads[id] = [.refused(id: id, kind: .known(.changed), message: "tide.ts changed while it was read.")]
                return
            }
            var answers: [OpenFile.Answer] = [], at = 0
            repeat {
                let end = min(at + 1 + random.below(5), bytes.count), last = end == bytes.count
                answers.append(.data(id: id, version: version, bytes: Array(bytes[at ..< end]), eof: last, whole: last, digest: last ? OpenFileTests.digest(bytes) : ""))
                at = end
            } while at < bytes.count
            reads[id] = answers
        }

        /// The workspace changed by the agent; the watch names it.
        mutating func agentWrites() {
            disk = random.below(8) == 0 ? nil : Array(random.text().utf8)
            versions += 1
            if connected {
                noteBase()
                take(file.changed())
            }
        }

        /// One request answered, a read a chunk at a time.
        mutating func answerOne() {
            let pending = reads.keys.sorted().map { (true, $0) } + writes.keys.sorted().map { (false, $0) }
            guard !pending.isEmpty else { return }
            let (isRead, id) = pending[random.below(pending.count)]
            if isRead {
                var answers = reads[id] ?? []
                let answer = answers.removeFirst()
                reads[id] = answers.isEmpty ? nil : answers
                noteBase()
                take(file.receive(answer))
                return
            }
            guard let write = writes.removeValue(forKey: id) else { return }
            let present = disk.map(OpenFileTests.digest) ?? OpenFile.absent
            guard present == write.base else {
                noteBase()
                return take(file.receive(.conflict(id: id, digest: present)))
            }
            disk = write.bytes
            versions += 1
            if random.below(2) == 0 {
                noteBase()
                take(file.changed())
            }
            noteBase()
            take(file.receive(.written(id: id, digest: OpenFileTests.digest(write.bytes), version: version)))
        }

        mutating func placeSlice() {
            guard !slices.isEmpty else { return }
            let slice = slices.removeFirst()
            view += slice
            loaded += slice
            if slices.isEmpty, allArrived {
                noteBase()
                take(file.inView(taken))
            }
        }

        mutating func key() {
            guard file.canType else { return }
            var characters = Array(view)
            if !characters.isEmpty, random.below(3) == 0 {
                characters.remove(at: random.below(characters.count))
            } else {
                characters.insert(random.character(), at: random.below(characters.count + 1))
            }
            view = String(characters)
            noteBase()
            take(file.edited(units: view.utf16.count) ?? file.edited(view))
        }

        mutating func step() {
            if file.canType, !slices.isEmpty { broken.append("keys taken while the view still took slices") }
            switch random.below(16) {
            case 0, 1: answerOne()
            case 2, 3: placeSlice()
            case 4, 5: key()
            case 6:
                log.append("save")
                person { $0.file.save($0.view, taken: $0.taken) }
            case 7: agentWrites()
            case 8:
                if let shown = file.theirs {
                    sheet = shown
                    self.shown.insert(shown.digest)
                }
            case 9:
                guard let shown = sheet else { break }
                sheet = nil
                log.append("keep mine")
                person { $0.file.keepMine($0.view, taken: $0.taken, shown: shown) }
            case 10:
                guard let shown = sheet else { break }
                sheet = nil
                noteBase()
                take(file.keepTheirs(shown))
            case 11:
                guard goneShown else { break }
                log.append("save anyway")
                person { $0.file.saveAnyway($0.view, taken: $0.taken) }
                goneShown = false
            case 12:
                noteBase()
                take(file.discardMine())
            case 13:
                guard connected else { break }
                connected = false
                reads = [:]
                writes = [:]
                file.disconnected()
            case 14:
                guard !connected else { break }
                connected = true
                noteBase()
                take(file.admitted())
            default:
                if connected {
                    noteBase()
                    take(file.tick())
                }
            }
            if slices.isEmpty, file.whole { shown.insert(OpenFileTests.digest(Array(view.utf8))) }
        }
    }

    @Test func whatIsWrittenIsWhatThePersonSawAgainstAVersionTheyWereShown() {
        var writes = 0
        for seed in UInt64(1) ... 400 {
            var world = World(seed: seed)
            for at in 0 ..< 200 {
                world.step()
                if !world.broken.isEmpty {
                    Issue.record("seed \(seed), step \(at), after \(world.log.suffix(4)): \(world.broken.joined(separator: "; "))")
                    break
                }
            }
            writes += world.log.count
        }
        #expect(writes > 2000, "only \(writes) saves were asked over the runs")
    }
}

/// A seeded generator, so a run that breaks the rule is played again by its seed.
struct SplitMix {
    var seed: UInt64

    mutating func next() -> UInt64 {
        seed = seed &+ 0x9E37_79B9_7F4A_7C15
        var z = seed
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    mutating func below(_ count: Int) -> Int {
        Int(next() % UInt64(max(count, 1)))
    }

    mutating func character() -> Character {
        ["a", "b", "é", "⚓", "\n", "\r\n", " ", "🧭"][below(8)]
    }

    mutating func text() -> String {
        String((0 ..< below(12)).map { _ in character() })
    }
}
