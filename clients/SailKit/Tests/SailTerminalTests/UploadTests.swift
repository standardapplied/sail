import Foundation
import SailAPI
@testable import SailTerminal
import SailTerminalWire
@testable import sail_term_probe
import Synchronization
import Testing

@Suite(.timeLimit(.minutes(1))) struct UploadTests {
    @Test func anUploadSendsItsBytesInOrderEndsWithTheirDigestAndAnswersTheStoredPath() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let bytes = (0 ..< UploadPacing.chunk * 2 + 7).map { UInt8(truncatingIfNeeded: $0 &* 31) }
        let progress = Mutex<[Int64]>([])
        let stored = Task {
            try await session.upload(name: "shot.png", source: .bytes(bytes)) { held in progress.withLock { $0.append(held) } }
        }
        let id = try await begun(peer, name: "shot.png", size: bytes.count)
        try peer.send(.putAck(id: id, received: 0))
        var received: [UInt8] = []
        while received.count < bytes.count {
            guard case let .putChunk(chunkID, offset, chunk) = try await peer.read() else { throw HostError("expected a chunk") }
            #expect(chunkID == id && offset == Int64(received.count) && chunk.count <= UploadPacing.chunk)
            received += chunk
            try peer.send(.putAck(id: id, received: Int64(received.count)))
        }
        #expect(received == bytes)
        #expect(try await peer.read() == .putEnd(id: id, sha256: digest(bytes)))
        try peer.send(.putStored(id: id, path: "/var/lib/sail-paste/inst-1/1-shot.png"))
        #expect(try await stored.value == "/var/lib/sail-paste/inst-1/1-shot.png")
        #expect(progress.withLock { $0 }.last == Int64(bytes.count))
        session.close()
        _ = host
    }

    /// U7: a key typed while chunks wait for the host goes out before the next chunk, which waits
    /// until the host holds all but ``UploadPacing/window`` of those sent.
    @Test func aKeyTypedDuringAnUploadGoesOutAheadOfEveryChunkTheHostHasNotTaken() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let bytes = [UInt8](repeating: 7, count: UploadPacing.chunk * (UploadPacing.window + 3))
        let pacing = Mutex((reads: 0, acknowledged: false, readAhead: false))
        let whole = UploadSource.bytes(bytes)
        let source = UploadSource(size: whole.size) { count in
            pacing.withLock { state in
                if state.reads > UploadPacing.window, !state.acknowledged { state.readAhead = true }
                state.reads += 1
            }
            return try whole.read(count)
        }
        let stored = Task { try await session.upload(name: "a", source: source) }
        let id = try await begun(peer, name: "a", size: bytes.count)
        try peer.send(.putAck(id: id, received: 0))
        for index in 0 ... UploadPacing.window {
            guard case let .putChunk(_, offset, _) = try await peer.read() else { throw HostError("expected a chunk") }
            #expect(offset == Int64(index * UploadPacing.chunk))
        }
        session.paste("k")
        guard case let .input(_, typed) = try await peer.read() else { throw HostError("the key did not go ahead of the chunks") }
        #expect(typed == Array("k".utf8))
        pacing.withLock { $0.acknowledged = true }
        try peer.send(.putAck(id: id, received: Int64(bytes.count)))
        while true {
            if case .putEnd = try await peer.read() { break }
        }
        try peer.send(.putStored(id: id, path: "/var/lib/sail-paste/i/1-a"))
        _ = try await stored.value
        #expect(!pacing.withLock { $0.readAhead }, "no chunk past the window is even read before the host takes one")
        session.close()
        _ = host
    }

    /// Scenario 12, typing stays first, with no wall time: a link of 10 Mbit/s modelled on the bytes
    /// it carries. The host sends no acknowledgement until a key is typed, as a slow link holds what
    /// was sent; the key is typed once the window of two chunks has arrived, and every byte the host
    /// reads ahead of the key is one the link carries first, so the key's delay at that rate is those
    /// bytes over the rate: within 110 ms. The window itself is held by
    /// ``aKeyTypedDuringAnUploadGoesOutAheadOfEveryChunkTheHostHasNotTaken``.
    @Test(.timeLimit(.minutes(1)))
    func aKeyTypedDuringAnUploadWaitsBehindLessThanASlowLinkCarriesIn110Milliseconds() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let total = 12
        let whole = UploadSource.bytes([UInt8](repeating: 5, count: UploadPacing.chunk * total))
        let window = 2
        let stored = Task { try await session.upload(name: "big.bin", source: whole) }
        let id = try await begun(peer, name: "big.bin", size: UploadPacing.chunk * total)
        try peer.send(.putAck(id: id, received: 0))
        let chunkFrame = 21
        let keyFrame = 18
        var ahead = keyFrame
        var received = 0
        var ended = false
        while received < window * UploadPacing.chunk {
            if case let .putChunk(_, _, chunk) = try await peer.read() {
                ahead += chunkFrame + chunk.count
                received += chunk.count
            }
        }
        session.paste("k")
        reading: while true {
            switch try await peer.read() {
            case let .putChunk(_, _, chunk):
                ahead += chunkFrame + chunk.count
                received += chunk.count
            case .putEnd:
                ended = true
            case .input:
                break reading
            default:
                continue
            }
        }
        let linkBytesPerSecond = 10_000_000 / 8
        let delay = Duration.seconds(Double(ahead) / Double(linkBytesPerSecond))
        #expect(delay <= .milliseconds(110), "the key waited \(delay) behind \(ahead) bytes")
        while !ended {
            try peer.send(.putAck(id: id, received: Int64(received)))
            let frame = try await peer.read()
            if case let .putChunk(_, _, chunk) = frame {
                received += chunk.count
            } else if case .putEnd = frame {
                ended = true
            }
        }
        try peer.send(.putStored(id: id, path: "/p"))
        _ = try await stored.value
        session.close()
        _ = host
    }

    @Test func aWatcherAFileTooLargeAndThisMachinesOwnHostAreRefusedBeforeAByteIsSent() async throws {
        let (host, watcher, peer) = try await attachedOnRunner(write: false)
        await #expect(throws: UploadRefusal(kind: .known(.notWriter), limit: 0, file: "a.png", size: 3)) {
            try await watcher.upload(name: "a.png", source: .bytes([1, 2, 3]))
        }
        let large = UploadSource(size: UploadPacing.largest + 1) { _ in [] }
        await #expect(throws: UploadRefusal(kind: .known(.tooLarge), limit: UploadPacing.largest, file: "b.mov", size: UploadPacing.largest + 1)) {
            try await watcher.upload(name: "b.mov", source: large)
        }
        watcher.paste("k")
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("k".utf8)), "no upload frame went before the key")
        watcher.close()
        _ = host

        let (local, mine, _, _) = try await attachedLocally()
        await #expect(throws: UploadRefusal(kind: .known(.noContainer), limit: 0, file: "a.png", size: 3)) {
            try await mine.upload(name: "a.png", source: .bytes([1, 2, 3]))
        }
        mine.close()
        _ = local
    }

    @Test func theHostsRefusalIsThrownWithItsKindAndBound() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let stored = Task { try await session.upload(name: "a.png", source: .bytes([1])) }
        let id = try await begun(peer, name: "a.png", size: 1)
        try peer.send(.putRefused(id: id, kind: .known(.sharedContainer), limit: 0))
        await #expect(throws: UploadRefusal(kind: .known(.sharedContainer), limit: 0, file: "a.png", size: 1)) {
            try await stored.value
        }
        let next = Task { try await session.upload(name: "b", source: .bytes([1])) }
        let nextID = try await begun(peer, name: "b", size: 1)
        #expect(nextID != id, "each upload has an id of its own")
        try peer.send(.putRefused(id: nextID, kind: .unknown("quota"), limit: 9))
        await #expect(throws: UploadRefusal(kind: .unknown("quota"), limit: 9, file: "b", size: 1)) {
            try await next.value
        }
        session.close()
        _ = host
    }

    /// Major 4: a drop before the end is sent loses the upload, and the person is told the
    /// connection dropped, not that the session ended.
    @Test func aConnectionLostBeforeTheEndIsToldAsTheConnectionLost() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let size = UploadPacing.chunk * 4
        let stored = Task { try await session.upload(name: "a", source: .bytes([UInt8](repeating: 1, count: size))) }
        let id = try await begun(peer, name: "a", size: size)
        try peer.send(.putAck(id: id, received: 0))
        _ = try await peer.read()
        peer.close()
        await #expect(throws: UploadRefusal(kind: .known(.connectionLost), limit: 0, file: "a", size: Int64(size))) {
            try await stored.value
        }
        session.close()
        _ = host
    }

    /// Major 4: a drop after the end is sent leaves the outcome to the host, which the next
    /// connection asks; its answer is what the upload comes to, a stored file or not known.
    @Test func aConnectionLostAfterTheEndAsksTheNextConnectionWhatBecameOfTheUpload() async throws {
        for (answer, expected) in [
            (Frame.putStored(id: 0, path: "/var/lib/sail-paste/i/1-a"), "stored"),
            (Frame.putRefused(id: 0, kind: .known(.notKnown), limit: 0), "not known"),
        ] {
            let (host, session, peer) = try await attachedOnRunner()
            let stored = Task { try await session.upload(name: "a", source: .bytes([1])) }
            let id = try await sentWhole(peer, [1])
            peer.close()
            let again = try await host.accept()
            try await again.welcome()
            _ = try await again.acceptAttach()
            #expect(try await again.read() == .putQuery(id: id))
            switch answer {
            case let .putStored(_, path):
                try again.send(.putStored(id: id, path: path))
                #expect(try await stored.value == path, "\(expected)")
            default:
                try again.send(.putRefused(id: id, kind: .known(.notKnown), limit: 0))
                await #expect(throws: UploadRefusal(kind: .known(.notKnown), limit: 0, file: "a", size: 1), "\(expected)") {
                    try await stored.value
                }
            }
            session.close()
            _ = host
        }
    }

    @Test func aSessionThatEndsMidUploadIsToldAsEnded() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let stored = Task { try await session.upload(name: "a", source: .bytes([UInt8](repeating: 1, count: UploadPacing.chunk * 4))) }
        let id = try await begun(peer, name: "a", size: UploadPacing.chunk * 4)
        try peer.send(.putAck(id: id, received: 0))
        _ = try await peer.read()
        try peer.send(.sessionEnded(reason: "exit 0"))
        await #expect(throws: UploadRefusal(kind: .known(.sessionEnded), limit: 0, file: "a", size: Int64(UploadPacing.chunk * 4))) {
            try await stored.value
        }
        session.close()
        _ = host
    }

    @Test func aCancelledUploadTellsTheHostAndThrowsCancellation() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let stored = Task { try await session.upload(name: "a", source: .bytes([UInt8](repeating: 1, count: UploadPacing.chunk * 8))) }
        let id = try await begun(peer, name: "a", size: UploadPacing.chunk * 8)
        try peer.send(.putAck(id: id, received: 0))
        for _ in 0 ... UploadPacing.window {
            _ = try await peer.read()
        }
        stored.cancel()
        #expect(try await peer.read() == .putCancel(id: id))
        await #expect(throws: CancellationError.self) { try await stored.value }
        session.close()
        _ = host
    }

    /// Minor 2: a cancel after the end is sent asks the host to keep nothing, and the host's answer
    /// says what happened: cancelled, or stored when the file had already taken its name.
    @Test func aCancelAfterTheEndIsTheHostsToDecide() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let first = Task { try await session.upload(name: "a", source: .bytes([1])) }
        let id = try await sentWhole(peer, [1])
        first.cancel()
        #expect(try await peer.read() == .putCancel(id: id))
        try peer.send(.putRefused(id: id, kind: .known(.cancelled), limit: 0))
        await #expect(throws: CancellationError.self) { try await first.value }

        let second = Task { try await session.upload(name: "b", source: .bytes([2])) }
        let secondID = try await sentWhole(peer, [2])
        second.cancel()
        #expect(try await peer.read() == .putCancel(id: secondID))
        try peer.send(.putStored(id: secondID, path: "/var/lib/sail-paste/i/1-b"))
        #expect(try await second.value == "/var/lib/sail-paste/i/1-b", "a file stored before the cancel is said to be stored")
        session.close()
        _ = host
    }

    /// Minor 7: an upload cancelled while it waits behind another stops waiting at once.
    @Test func anUploadCancelledWhileItWaitsItsTurnStopsWaiting() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let first = Task { try await session.upload(name: "a", source: .bytes([1])) }
        let id = try await begun(peer, name: "a", size: 1)
        let waiting = Task { try await session.upload(name: "b", source: .bytes([2])) }
        while session.uploads.waiting == 0 {
            await Task.yield()
        }
        waiting.cancel()
        await #expect(throws: CancellationError.self) { try await waiting.value }
        try peer.send(.putAck(id: id, received: 0))
        _ = try await peer.read()
        try peer.send(.putAck(id: id, received: 1))
        _ = try await peer.read()
        try peer.send(.putStored(id: id, path: "/p/a"))
        #expect(try await first.value == "/p/a")
        let next = Task { try await session.upload(name: "c", source: .bytes([3])) }
        _ = try await begun(peer, name: "c", size: 1)
        next.cancel()
        session.close()
        _ = host
    }

    /// Major 4: a file that cannot be read is this machine's failure, thrown as its own error.
    @Test func aSourceThatFailsToReadThrowsItsOwnErrorAndCancelsTheUpload() async throws {
        struct Unreadable: Error, Equatable {}
        let (host, session, peer) = try await attachedOnRunner()
        let source = UploadSource(size: 4) { _ in throw Unreadable() }
        let stored = Task { try await session.upload(name: "a", source: source) }
        let id = try await begun(peer, name: "a", size: 4)
        try peer.send(.putAck(id: id, received: 0))
        #expect(try await peer.read() == .putCancel(id: id))
        await #expect(throws: Unreadable()) { try await stored.value }
        session.close()
        _ = host
    }

    @Test func uploadsBegunTogetherGoOneAtATime() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let first = Task { try await session.upload(name: "a", source: .bytes([1])) }
        let second = Task { try await session.upload(name: "a", source: .bytes([2])) }
        for _ in 0 ..< 2 {
            guard case let .putBegin(id, _, _) = try await peer.read() else { throw HostError("expected a begin") }
            try peer.send(.putAck(id: id, received: 0))
            guard case .putChunk = try await peer.read() else { throw HostError("expected the chunk before another begin") }
            try peer.send(.putAck(id: id, received: 1))
            guard case .putEnd = try await peer.read() else { throw HostError("expected the end before another begin") }
            try peer.send(.putStored(id: id, path: "/p/\(id)"))
        }
        let paths = Set([try await first.value, try await second.value])
        #expect(paths.count == 2)
        session.close()
        _ = host
    }

    @Test func theDigestIsSHA256() {
        func hex(_ bytes: [UInt8]) -> String {
            digest(bytes).map { String(format: "%02x", $0) }.joined()
        }
        #expect(hex([]) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        #expect(hex(Array("abc".utf8)) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        #expect(hex(Array("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8))
            == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
        var pieces = SHA256()
        for _ in 0 ..< 1000 {
            pieces.update([UInt8](repeating: UInt8(ascii: "a"), count: 1000))
        }
        #expect(pieces.finalize().map { String(format: "%02x", $0) }.joined()
            == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }
}

/// The id of the upload `peer` reads the begin of, checking its name and size.
func begun(_ peer: FakeHost.Peer, name: String, size: Int) async throws -> Int32 {
    guard case let .putBegin(id, begunName, begunSize) = try await peer.read(), begunName == name, begunSize == Int64(size) else {
        throw HostError("expected the begin of \(name)")
    }
    return id
}

/// Takes an upload of `bytes`, one chunk, through to its end, leaving the end unanswered: its id.
func sentWhole(_ peer: FakeHost.Peer, _ bytes: [UInt8]) async throws -> Int32 {
    guard case let .putBegin(id, _, _) = try await peer.read() else { throw HostError("expected a begin") }
    try peer.send(.putAck(id: id, received: 0))
    guard case .putChunk = try await peer.read() else { throw HostError("expected the chunk") }
    try peer.send(.putAck(id: id, received: Int64(bytes.count)))
    guard case .putEnd = try await peer.read() else { throw HostError("expected the end") }
    return id
}

func digest(_ bytes: [UInt8]) -> [UInt8] {
    var digest = SHA256()
    digest.update(bytes)
    return digest.finalize()
}

@Suite(.timeLimit(.minutes(1))) struct ProbeUploadTests {
    /// The probe's upload step: the file goes on the session's own connection while keys are typed
    /// and erased, each timed to its echo, and the stored path is pasted after it with a space.
    @Test func theProbeUploadsAFileTimesKeysMeanwhileAndPastesTheStoredPath() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let file = FileManager.default.temporaryDirectory.appending(path: "probe-\(UUID().uuidString).png")
        let bytes = [UInt8](repeating: 3, count: UploadPacing.chunk * 3)
        try Data(bytes).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let script = try JSONDecoder().decode(Script.self, from: Data(#"{"steps": [{"upload": "\#(file.path)", "within": 30}]}"#.utf8))
        let runner = Runner(session: session, host: nil, echoBudget: 10) { _ in }
        let report = Task { await runner.run(script) }
        var held = 0
        var id: Int32 = 0
        var typed = 0
        var pasted: [UInt8] = []
        serving: while true {
            switch try await peer.read() {
            case let .putBegin(begun, _, _):
                id = begun
                try peer.send(.putAck(id: id, received: 0))
            case let .putChunk(_, _, chunk):
                if typed == 0 {
                    guard case let .input(sequence, _) = try await peer.read() else { throw HostError("no key typed during the upload") }
                    typed += 1
                    try peer.send(output("x", applied: sequence))
                }
                held += chunk.count
                try peer.send(.putAck(id: id, received: Int64(held)))
            case let .input(sequence, _):
                typed += 1
                try peer.send(output("", applied: sequence))
            case .putEnd:
                try peer.send(.putStored(id: id, path: "/var/lib/sail-paste/inst-1/1-shot.png"))
                break serving
            default: continue
            }
        }
        while pasted.count < "/var/lib/sail-paste/inst-1/1-shot.png ".utf8.count {
            guard case let .input(sequence, bytes) = try await peer.read() else { continue }
            if bytes.count > 1 {
                pasted = bytes
            } else {
                try peer.send(output("", applied: sequence))
            }
        }
        #expect(String(decoding: pasted, as: UTF8.self) == "/var/lib/sail-paste/inst-1/1-shot.png ")
        let done = await report.value
        let failed = done.steps.filter { !$0.ok }.map(\.step)
        #expect(failed == [])
        let upload = try #require(done.uploads.first)
        #expect(upload.path == "/var/lib/sail-paste/inst-1/1-shot.png" && upload.bytes == bytes.count)
        #expect(upload.sha256 == digest(bytes).map { String(format: "%02x", $0) }.joined())
        #expect(!upload.keyEchoes.isEmpty)
        session.close()
        _ = host
    }
}

/// A session attached to a runner through a fake host, writing unless `write` is false.
func attachedOnRunner(write: Bool = true) async throws -> (FakeHost, TerminalSession, FakeHost.Peer) {
    let host = try FakeHost(tcp: ())
    let door = Door.runner(TicketDesk(address: host.address))
    let session = TerminalSession(attach: "work", write: write, door: door, screen: try screen(), timeout: 5, stableAfter: .zero, sleep: Naps().sleep)
    let watch = Watch(session)
    session.start()
    let peer = try await host.accept()
    try await peer.welcome()
    _ = try await peer.acceptAttach()
    try await watch.until("took the attach's prologue") { watch.seen.contains(.writerChanged("ada")) }
    return (host, session, peer)
}
