import AppKit
import Foundation
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailWorkbench
import Testing

/// A nap a test lets pass when it chooses: the clock an end's banner waits on.
@MainActor final class HeldNaps {
    private var held: [CheckedContinuation<Void, Never>] = []

    var count: Int {
        held.count
    }

    func nap(_: Duration) async {
        await withCheckedContinuation { held.append($0) }
    }

    func pass() {
        let passing = held
        held = []
        passing.forEach { $0.resume() }
    }
}

/// Closing a This Mac pane attached to its host ends its session as closing any other pane does:
/// a Kill on a connection of its own, the host's answer awaited within the end's limit. A Kill
/// that cannot be sent, is refused, or is answered late leaves the pane, marked not ended, never
/// the session running with no pane; one answered at once closes the pane without a word, the
/// focus already on the next. Against ``FakeRunnerDoor`` playing this Mac's host.
@MainActor @Suite(.timeLimit(.minutes(1))) struct ThisMacEndingTests {
    static func host(at door: FakeRunnerDoor) -> LocalHost {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-mac-end-\(UUID().uuidString.prefix(8))")
        let address = HostAddress.tcp(host: "127.0.0.1", port: door.port)
        return LocalHost(binary: nil, address: address, log: folder.appending(path: "host.log").path, copies: folder)
    }

    static func attached(at door: FakeRunnerDoor) async throws -> TerminalPane {
        let host = Self.host(at: door)
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

    /// This Mac's workbench of `tabs` tabs of one pane each, every one attached through the door.
    static func workbench(at door: FakeRunnerDoor, tabs: Int) async throws -> WorkbenchView {
        let bench = try WorkbenchView(fonts: LookTests.fonts, host: Self.host(at: door), look: .light)
        let place = Place.thisMac(folder: "/")
        let ids = (0 ..< tabs).map { _ in bench.model.newTab(session: place.newSessionName, place: place) }
        for id in ids {
            let pane = try #require(bench.model.panes[id])
            _ = try #require(bench.makeView(pane, start: .open, points: 15))
        }
        bench.apply()
        let attached = await RunnerTabTests.until(10) { bench.panes.values.allSatisfy { $0.attachedOnce && $0.status.says == nil } }
        #expect(attached, "every pane attached")
        return bench
    }

    /// A host that answers within ``TerminalPane/endingSaidAfter`` closes the pane without a
    /// word: nothing is said before the answer, at it, or once the delay has passed.
    @Test func anEndTheHostConfirmsWithinTheDelayIsNeverSaid() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let pane = try await Self.attached(at: door)
        let naps = HeldNaps()
        pane.nap = { await naps.nap($0) }
        let ending = try #require(pane.end(), "an attached pane's end awaits the host's answer")
        let said = pane.showEnding()
        #expect(pane.status.says == nil)
        #expect(await ending.value == .ended)
        #expect(pane.status.says == nil)
        #expect(await RunnerTabTests.until(5) { naps.count == 1 }, "the delay began")
        naps.pass()
        await said.value
        #expect(pane.status.says == nil, "answered within the delay: never said")
    }

    /// An end the host has not answered once the delay passes says the pane is ending.
    @Test func anEndStillUnansweredAfterTheDelayIsSaid() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        let pane = try await Self.attached(at: door)
        let naps = HeldNaps()
        pane.nap = { await naps.nap($0) }
        door.holdKills()
        let ending = try #require(pane.end(within: .seconds(10)), "an attached pane's end awaits the host's answer")
        let said = pane.showEnding()
        #expect(await RunnerTabTests.until(5) { naps.count == 1 }, "the delay began")
        #expect(pane.status.says == nil)
        naps.pass()
        await said.value
        #expect(pane.status.says == "Ending this terminal…")
        door.releaseKills()
        #expect(await ending.value == .ended)
    }

    /// The focus leaves a pane as its end begins, so a second ⌘W while the host answers closes
    /// the next pane, not the same one.
    @Test func aSecondCloseDuringAnEndClosesTheNextPane() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        let bench = try await Self.workbench(at: door, tabs: 2)
        let first = try #require(bench.model.tabs.first?.focused)
        let second = try #require(bench.model.focusedPane?.id)
        #expect(first != second)
        door.holdKills()
        bench.closePane(nil)
        #expect(bench.panes[second]?.isEnding == true, "the end is under way")
        #expect(bench.model.focusedPane?.id == first, "the focus moved on as the end began")
        bench.closePane(nil)
        #expect(bench.panes[first]?.isEnding == true, "the second close ends the next pane")
        door.releaseKills()
        #expect(await RunnerTabTests.until(10) { bench.panes.isEmpty && bench.model.tabs.isEmpty })
        #expect(door.sessions.isEmpty)
    }

    /// A pane whose end the host refuses stays in the workbench, shown, saying so; End Again,
    /// once the host allows it, ends it and the pane goes.
    @Test func aPaneWhoseEndIsRefusedStaysInTheWorkbenchUntilEndAgainEndsIt() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let bench = try await Self.workbench(at: door, tabs: 2)
        let pane = try #require(bench.focused)
        let words = "Session '\(pane.name)' is not yours."
        door.refuseKills(saying: words)
        bench.closeTab(nil)
        #expect(await RunnerTabTests.until(10) { pane.status.offers == ["End Again", "Close Anyway"] }, "the pane says \(pane.status.says ?? "nothing")")
        #expect(pane.unended)
        #expect(bench.panes[pane.id] === pane && bench.model.panes[pane.id] != nil, "kept in the workbench")
        #expect(bench.focused === pane, "shown, so what it says is seen")
        #expect(pane.status.says == EndOutcome.mayStillRun("This terminal", [words], onThisMac: true) + " Close Anyway leaves it there.")
        #expect(door.sessions.contains(pane.name))
        door.refuseKills(saying: nil)
        #expect(pane.press("End Again"))
        #expect(await RunnerTabTests.until(10) { bench.panes[pane.id] == nil && bench.model.panes[pane.id] == nil })
        #expect(!door.sessions.contains(pane.name) && door.sessions.count == 1, "only the other tab's session is left")
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
