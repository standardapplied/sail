import Foundation
@testable import SailTerminal
import SailTerminalWire
import SailVT
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
}
