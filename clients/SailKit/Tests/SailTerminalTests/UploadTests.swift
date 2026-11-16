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
        #expect(try await peer.read() == .putBegin(id: 0, name: "shot.png", size: Int64(bytes.count)))
        try peer.send(.putAck(id: 0, received: 0))
        var received: [UInt8] = []
        while received.count < bytes.count {
            guard case let .putChunk(id, offset, chunk) = try await peer.read() else { throw HostError("expected a chunk") }
            #expect(id == 0 && offset == Int64(received.count) && chunk.count <= UploadPacing.chunk)
            received += chunk
            try peer.send(.putAck(id: 0, received: Int64(received.count)))
        }
        #expect(received == bytes)
        var digest = SHA256()
        digest.update(bytes)
        #expect(try await peer.read() == .putEnd(id: 0, sha256: digest.finalize()))
        try peer.send(.putStored(id: 0, path: "/tmp/sail-paste/inst-1/1-shot.png"))
        #expect(try await stored.value == "/tmp/sail-paste/inst-1/1-shot.png")
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
        #expect(try await peer.read() == .putBegin(id: 0, name: "a", size: Int64(bytes.count)))
        try peer.send(.putAck(id: 0, received: 0))
        for index in 0 ... UploadPacing.window {
            guard case let .putChunk(_, offset, _) = try await peer.read() else { throw HostError("expected a chunk") }
            #expect(offset == Int64(index * UploadPacing.chunk))
        }
        session.paste("k")
        guard case let .input(_, typed) = try await peer.read() else { throw HostError("the key did not go ahead of the chunks") }
        #expect(typed == Array("k".utf8))
        pacing.withLock { $0.acknowledged = true }
        try peer.send(.putAck(id: 0, received: Int64(bytes.count)))
        while true {
            if case .putEnd = try await peer.read() { break }
        }
        try peer.send(.putStored(id: 0, path: "/tmp/sail-paste/i/1-a"))
        _ = try await stored.value
        #expect(!pacing.withLock { $0.readAhead }, "no chunk past the window is even read before the host takes one")
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
        #expect(try await peer.read() == .putBegin(id: 0, name: "a.png", size: 1))
        try peer.send(.putRefused(id: 0, kind: .known(.sharedContainer), limit: 0))
        await #expect(throws: UploadRefusal(kind: .known(.sharedContainer), limit: 0, file: "a.png", size: 1)) {
            try await stored.value
        }
        let next = Task { try await session.upload(name: "b", source: .bytes([1])) }
        #expect(try await peer.read() == .putBegin(id: 1, name: "b", size: 1), "each upload has an id of its own")
        try peer.send(.putRefused(id: 1, kind: .unknown("quota"), limit: 9))
        await #expect(throws: UploadRefusal(kind: .unknown("quota"), limit: 9, file: "b", size: 1)) {
            try await next.value
        }
        session.close()
        _ = host
    }

    @Test func aConnectionLostMidUploadEndsItAsTheSessionEnded() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let stored = Task { try await session.upload(name: "a", source: .bytes([UInt8](repeating: 1, count: UploadPacing.chunk * 4))) }
        _ = try await peer.read()
        try peer.send(.putAck(id: 0, received: 0))
        _ = try await peer.read()
        peer.close()
        await #expect(throws: UploadRefusal(kind: .known(.sessionEnded), limit: 0, file: "a", size: Int64(UploadPacing.chunk * 4))) {
            try await stored.value
        }
        session.close()
        _ = host
    }

    @Test func aCancelledUploadTellsTheHostAndThrowsCancellation() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        let stored = Task { try await session.upload(name: "a", source: .bytes([UInt8](repeating: 1, count: UploadPacing.chunk * 8))) }
        _ = try await peer.read()
        try peer.send(.putAck(id: 0, received: 0))
        for _ in 0 ... UploadPacing.window {
            _ = try await peer.read()
        }
        stored.cancel()
        #expect(try await peer.read() == .putCancel(id: 0))
        await #expect(throws: CancellationError.self) { try await stored.value }
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
        #expect(paths == ["/p/0", "/p/1"])
        session.close()
        _ = host
    }

    @Test func theDigestIsSHA256() {
        func hex(_ bytes: [UInt8]) -> String {
            var digest = SHA256()
            digest.update(bytes)
            return digest.finalize().map { String(format: "%02x", $0) }.joined()
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

    /// Scenario 12: a 4 MiB upload over a link of 10 Mbit/s while a key is typed every 50 ms; each
    /// key reaches the host within 110 ms of its press, and every byte arrives.
    @Test(.timeLimit(.minutes(1))) func typingStaysFirstThroughASlowLink() async throws {
        let (host, session, peer) = try await attachedOnRunner()
        peer.shape(bytesPerSecond: 10_000_000 / 8)
        let bytes = (0 ..< 4 << 20).map { UInt8(truncatingIfNeeded: $0 &* 7) }
        let pressed = Mutex<[ContinuousClock.Instant]>([])
        let arrived = Mutex<[ContinuousClock.Instant]>([])
        let stored = Task { try await session.upload(name: "big.bin", source: .bytes(bytes)) }
        let typing = Task {
            while !Task.isCancelled {
                try await Task.sleep(for: .milliseconds(50))
                pressed.withLock { $0.append(.now) }
                session.paste("k")
            }
        }
        var received = 0
        reading: while true {
            switch try await peer.read() {
            case .putBegin: try peer.send(.putAck(id: 0, received: 0))
            case let .putChunk(_, _, chunk):
                received += chunk.count
                try peer.send(.putAck(id: 0, received: Int64(received)))
            case .input: arrived.withLock { $0.append(.now) }
            case .putEnd:
                try peer.send(.putStored(id: 0, path: "/p"))
                break reading
            default: continue
            }
        }
        _ = try await stored.value
        typing.cancel()
        let delays = zip(pressed.withLock { $0 }, arrived.withLock { $0 }).map { $1 - $0 }.sorted()
        let worst = try #require(delays.last)
        print("upload pacing: chunk \(UploadPacing.chunk), window \(UploadPacing.window), \(delays.count) keys,"
            + " median \(delays[delays.count / 2]), p95 \(delays[delays.count * 95 / 100]), worst \(worst)")
        #expect(received == bytes.count)
        #expect(delays.count >= 40)
        #expect(worst <= .milliseconds(110))
        session.close()
        _ = host
    }
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
        var typed = 0
        var pasted: [UInt8] = []
        serving: while true {
            switch try await peer.read() {
            case .putBegin: try peer.send(.putAck(id: 0, received: 0))
            case let .putChunk(_, _, chunk):
                if typed == 0 {
                    guard case let .input(sequence, _) = try await peer.read() else { throw HostError("no key typed during the upload") }
                    typed += 1
                    try peer.send(output("x", applied: sequence))
                }
                held += chunk.count
                try peer.send(.putAck(id: 0, received: Int64(held)))
            case let .input(sequence, _):
                typed += 1
                try peer.send(output("", applied: sequence))
            case .putEnd:
                try peer.send(.putStored(id: 0, path: "/tmp/sail-paste/inst-1/1-shot.png"))
                break serving
            default: continue
            }
        }
        while pasted.count < "/tmp/sail-paste/inst-1/1-shot.png ".utf8.count {
            if case let .input(_, bytes) = try await peer.read(), bytes.count > 1 { pasted = bytes }
        }
        #expect(String(decoding: pasted, as: UTF8.self) == "/tmp/sail-paste/inst-1/1-shot.png ")
        let done = await report.value
        let failed = done.steps.filter { !$0.ok }.map(\.step)
        #expect(failed == [])
        let upload = try #require(done.uploads.first)
        #expect(upload.path == "/tmp/sail-paste/inst-1/1-shot.png" && upload.bytes == bytes.count)
        var digest = SHA256()
        digest.update(bytes)
        #expect(upload.sha256 == digest.finalize().map { String(format: "%02x", $0) }.joined())
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
