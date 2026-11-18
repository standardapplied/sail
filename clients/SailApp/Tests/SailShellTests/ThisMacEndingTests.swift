import AppKit
import Foundation
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailWorkbench
import Testing

/// Closing a This Mac pane attached to its host ends its session as closing any other pane does:
/// a Kill on a connection of its own, the host's answer awaited within the end's limit. A Kill
/// that cannot be sent, is refused, or is answered late leaves the pane, marked not ended, never
/// the session running with no pane. Against ``FakeRunnerDoor`` playing this Mac's host.
@MainActor @Suite(.timeLimit(.minutes(1))) struct ThisMacEndingTests {
    static func attached(at door: FakeRunnerDoor) async throws -> TerminalPane {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-mac-end-\(UUID().uuidString.prefix(8))")
        let address = HostAddress.tcp(host: "127.0.0.1", port: door.port)
        let host = LocalHost(binary: nil, address: address, log: folder.appending(path: "host.log").path, copies: folder)
        var bench = Workbench()
        let place = Place.thisMac(folder: "/")
        let id = bench.newTab(session: place.newSessionName, place: place)
        let pane = try TerminalPane(
            try #require(bench.panes[id]), start: .open, host: host, runner: nil,
            fonts: TerminalFonts(directory: LookTests.fonts), points: 15, look: .light
        )
        pane.begin()
        #expect(await RunnerTabTests.until(10) { pane.attachedOnce && pane.status.says == nil }, "attached: \(pane.status.says ?? "nothing said")")
        return pane
    }

    /// The pane is kept, saying the session may still run on this Mac, with End Again.
    static func keptUnended(_ pane: TerminalPane, _ outcome: EndOutcome) {
        guard case let .unconfirmed(why) = outcome else {
            Issue.record("the end was \(outcome)")
            return
        }
        #expect(pane.unended, "the pane says its session may still be running")
        pane.showUnended(why, endAgain: {}, closeAnyway: {})
        #expect(pane.status.says == EndOutcome.mayStillRun("This terminal", why, onThisMac: true) + " Close Anyway leaves it there.")
        #expect(pane.status.offers == ["End Again", "Close Anyway"])
    }

    @Test func anAttachedPanesEndIsTheHostsAnswer() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let pane = try await Self.attached(at: door)
        let ending = try #require(pane.end(), "an attached pane's end awaits the host's answer")
        #expect(await ending.value == .ended)
        #expect(door.sessions.isEmpty && !pane.unended)
    }

    @Test func anAttachedPaneWhoseKillCannotBeSentIsKeptNotEnded() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        let pane = try await Self.attached(at: door)
        door.stop()
        let ending = try #require(pane.end(within: .seconds(5)), "an attached pane's end awaits the host's answer")
        Self.keptUnended(pane, await ending.value)
        #expect(door.sessions == [pane.name], "the session still runs, and its pane is kept")
    }

    @Test func anAttachedPaneWhoseKillIsRefusedIsKeptNotEnded() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let pane = try await Self.attached(at: door)
        let words = "Session '\(pane.name)' is not yours."
        door.refuseKills(saying: words)
        let ending = try #require(pane.end(), "an attached pane's end awaits the host's answer")
        let outcome = await ending.value
        #expect(outcome == .unconfirmed([words]))
        Self.keptUnended(pane, outcome)
        #expect(door.sessions == [pane.name], "the session still runs, and its pane is kept")
    }

    /// Answered after the limit, the end is not confirmed; End Again, once the host answers, finds
    /// the session gone by the host's own list.
    @Test func anAttachedPaneWhoseKillIsAnsweredLateIsKeptNotEndedThenEndsAgain() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        let pane = try await Self.attached(at: door)
        door.holdKills()
        let ending = try #require(pane.end(within: .seconds(1)), "an attached pane's end awaits the host's answer")
        let outcome = await ending.value
        #expect(outcome == .unconfirmed(["This Mac's terminal host did not answer within 1 s."]))
        Self.keptUnended(pane, outcome)
        #expect(door.sessions == [pane.name], "the session still runs, and its pane is kept")
        door.releaseKills()
        #expect(await RunnerTabTests.until(10) { door.sessions.isEmpty }, "the late Kill ends it at the host")
        let again = try #require(pane.end(), "End Again")
        #expect(await again.value == .gone && !pane.unended)
    }
}
