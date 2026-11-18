import Foundation
@testable import SailTerminal
import SailTerminalWire
import SailVT
import Synchronization
@testable import sail_term_probe
import Testing

/// A session takes the other look while it runs, and a program that subscribed to scheme reports
/// (mode 2031) hears of it from the writer only: at the change, and once again at every attach
/// as the writer, in case the scheme changed while the client was away. An observer's look never
/// reaches the program.
@Suite(.timeLimit(.minutes(1))) struct LiveLookSessionTests {
    static let light = Array("\u{1b}[?997;2n".utf8)
    static let dark = Array("\u{1b}[?997;1n".utf8)

    /// What the host reads next as input bytes.
    static func input(_ peer: FakeHost.Peer) async throws -> [UInt8] {
        let frame = try await peer.read()
        guard case let .input(_, bytes) = frame else { throw HostError("expected input, got \(frame)") }
        return bytes
    }

    /// Two people attached with different looks: the program hears the writer's, never the
    /// observer's, until the observer takes the write and its look becomes the program's.
    @Test func aSubscribedProgramHearsTheWritersLookAndNeverTheObservers() async throws {
        let (host, writer, peer, watch) = try await attachedLocally()
        let observer = TerminalSession(
            attach: "work", write: false, door: .local(host.address), screen: try screen(), timeout: 5, sleep: Naps().sleep
        )
        let watched = Watch(observer)
        observer.start()
        let seen = try await host.accept()
        try await seen.welcome()
        #expect(try await seen.read() == .attach(session: "work", write: false))
        try seen.send([.accepted, .replayBegin(safe: true), output("\u{1b}[?2031hready"), .replayEnd, .writerChanged(fde: "ada")])
        try peer.send(output("\u{1b}[?2031hready"))
        try await watch.text("ready")
        try await watched.until("took the prologue") { watched.seen.contains(.writerChanged("ada")) }

        try observer.setPalette(.light)
        observer.send(KeyInput(key: .o, text: "o"))
        #expect(try await Self.input(seen) == Array("o".utf8), "the observer's look is not told")
        try writer.setPalette(.dark)
        writer.send(KeyInput(key: .w, text: "w"))
        #expect(try await Self.input(peer) == Array("w".utf8), "the writer's look is unchanged: nothing new to tell")
        try writer.setPalette(.light)
        #expect(try await Self.input(peer) == Self.light, "the writer's look reaches the program")

        try writer.setPalette(.dark)
        #expect(try await Self.input(peer) == Self.dark)
        observer.takeWrite()
        #expect(try await seen.read() == .takeWrite)
        try seen.send(.writerChanged(fde: "bob"))
        #expect(try await Self.input(seen) == Self.light, "the new writer's look becomes the program's")
        observer.close()
        writer.close()
    }

    @Test func aProgramThatDidNotSubscribeIsToldNothing() async throws {
        let (_, session, peer, watch) = try await attachedLocally()
        try peer.send(output("ready"))
        try await watch.text("ready")
        try session.setPalette(.light)
        session.send(KeyInput(key: .k, text: "k"))
        #expect(try await Self.input(peer) == Array("k".utf8), "the first input is the key, no report before it")
        session.close()
    }

    /// The ruling on a reattach: a subscriber is told the scheme once the replay is over, so a
    /// look changed while the client was away is not left unsaid; one that is not subscribed
    /// after the replay is told nothing.
    @Test(arguments: [true, false])
    func aWritersReattachTellsASubscriberTheSchemeOnce(_ subscribed: Bool) async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(output("ready"))
        try await watch.text("ready")
        peer.close()
        try session.setPalette(.light)
        let again = try await host.accept()
        try await again.welcome()
        #expect(try await again.read() == .attach(session: "work", write: true))
        try again.send([.accepted, .replayBegin(safe: true), output((subscribed ? "\u{1b}[?2031h" : "") + "history"), .replayEnd, output(" live")])
        try await watch.text("history live")
        session.send(KeyInput(key: .k, text: "k"))
        if subscribed {
            #expect(try await Self.input(again) == Self.light, "the scheme of now, told once the replay is over")
        }
        #expect(try await Self.input(again) == Array("k".utf8))
        session.close()
    }

    /// What the drive's colour steps leave behind (steps 41 to 52: the asker's queries and its
    /// subscription, the looks switched around them), then a flood: no render hold is open or
    /// armed, and every output of the flood is told the moment the screen takes it.
    @Test func theColourStepsLeaveNoHoldAndAFloodAfterThemIsToldAtOnce() async throws {
        let hands = Hands()
        let timers = ManualTimers()
        let host = try FakeHost()
        let create = SessionCreate(session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 80, rows: 24))
        let screen = try Screen(size: GridSize(columns: 80, rows: 24), scrollbackBytes: 1 << 20, palette: .light, now: { hands.now })
        let session = TerminalSession(
            open: create, door: .local(host.address), screen: screen, timeout: 5, stableAfter: .zero, sleep: Naps().sleep, after: timers.after
        )
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.acceptAttach(columns: 80, rows: 24)
        try await watch.until("took the attach's prologue") { watch.seen.contains(.writerChanged("ada")) }
        let told = Mutex(0)
        let (tells, sink) = AsyncStream.makeStream(of: Void.self)
        session.onScreenChange { _ in
            told.withLock { $0 += 1 }
            sink.yield()
        }
        var tell = tells.makeAsyncIterator()
        var expected = 0
        let prompt = "\u{1b}[?2004h$ "
        let steps = [
            prompt + "perl /tmp/sail-drive-colours.pl set\r\n\u{1b}[?2004l\r",
            "\u{1b}]4;5;rgb:12/34/56\u{1b}\\\u{1b}]11;?\u{1b}\\\u{1b}]4;5;?\u{1b}\\\u{1b}[?996n",
            "asked bg rgb:f8f8/fafa/fcfc c5 rgb:1212/3434/5656 light\r\n" + prompt + "perl /tmp/sail-drive-colours.pl watch\r\n\u{1b}[?2004l\r",
            "\u{1b}[?2031hsubscribed\r\n",
        ]
        for step in steps {
            try peer.send(output(step))
        }
        expected += steps.count
        while told.withLock({ $0 }) < expected {
            _ = await tell.next()
        }
        #expect(try screen.text().contains("subscribed"), "the asker's output is held")
        try session.setPalette(.dark)
        expected += 1
        var reports = 0
        for _ in 0 ..< 4 {
            guard case let .input(_, bytes) = try await peer.read() else { continue }
            if bytes == Self.dark { reports += 1; break }
        }
        #expect(reports == 1, "the subscriber was not told dark once")
        try peer.send(output("\u{1b}[?2031lscheme report dark\r\n" + prompt + "perl /tmp/sail-drive-colours.pl ask\r\n\u{1b}[?2004l\r"))
        try peer.send(output("\u{1b}]11;?\u{1b}\\\u{1b}]4;5;?\u{1b}\\\u{1b}[?996n"))
        try peer.send(output("asked bg rgb:0a0a/0e0e/1111 c5 rgb:1212/3434/5656 dark\r\n" + prompt + "ready-for-the-flood"))
        expected += 3
        while told.withLock({ $0 }) < expected {
            _ = await tell.next()
        }
        #expect(try screen.text().contains("ready-for-the-flood"), "the asker's output is held")
        for palette in [Palette.light, .dark, .light, .light] {
            try session.setPalette(palette)
        }
        expected += 4
        #expect(screen.holdRemaining == nil, "the colour steps left a render hold open")
        #expect(timers.live.isEmpty, "a hold's tick is armed: \(timers.live)")
        let line = String(repeating: "x", count: 150) + "\r\n"
        let frames = 200
        for index in 0 ..< frames {
            try peer.send(output(String(repeating: line, count: 20) + (index == frames - 1 ? "flood-2" : "")))
        }
        expected += frames
        while told.withLock({ $0 }) < expected {
            _ = await tell.next()
        }
        #expect(try screen.text().contains("flood-2"), "the flood's last output is held")
        #expect(screen.holdRemaining == nil && timers.live.isEmpty, "the flood left a hold open or armed")
        hands.advance(.seconds(5))
        #expect(timers.live.isEmpty)
        session.send(KeyInput(key: .z, text: "z"))
        var sent: [[UInt8]] = []
        while sent.last != Array("z".utf8) {
            guard case let .input(_, bytes) = try await peer.read() else { continue }
            sent.append(bytes)
        }
        #expect(!sent.contains(Self.dark) && !sent.contains(Self.light), "a scheme report went out after the program unsubscribed: \(sent.count) inputs")
        session.close()
    }

}
