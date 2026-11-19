import AppKit
import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailShell
import SailTerminal
import SailWorkbench
import Synchronization
import Testing

/// Who is signed in, as the shell would say it to the workbench.
@MainActor final class Who {
    var handle: String?

    init(_ handle: String?) {
        self.handle = handle
    }
}

/// A session on the runner outlives the app, the sign-out and the tab's own client: the app comes
/// back to it as the person it is, and says when it cannot.
@MainActor @Suite(.serialized) struct ReattachTests {
    static let dock = Place.workspace(project: "harbor", name: "dock")
    static let stopped = "Workspace dock of harbor is being stopped by ana, which ends this terminal."

    /// This Mac's workbench over `local`, restored from `kept`, its runner tabs reaching `runner`
    /// as whoever `who` names.
    static func workbench(local: FakeRunnerDoor, runner: FakeRunnerDoor, who: Who, kept: Arrangement? = nil) async throws -> WorkbenchView {
        if let kept {
            UserDefaults.standard.set(try JSONEncoder().encode(kept), forKey: WorkbenchView.arrangementKey)
        } else {
            UserDefaults.standard.removeObject(forKey: WorkbenchView.arrangementKey)
        }
        let bench = try WorkbenchView(fonts: LookTests.fonts, host: ThisMacEndingTests.host(at: local), look: .light)
        let tickets = DoorTickets(runner)
        bench.signedIn = { who.handle.map { ($0, tickets) } }
        bench.shown()
        #expect(await RunnerTabTests.until(10) { bench.restored }, "the workbench restored")
        return bench
    }

    static func runnerSessions(_ bench: WorkbenchView) -> [String] {
        bench.model.tabs.flatMap(\.layout.leaves).compactMap { bench.model.panes[$0] }.filter(\.place.onRunner).map(\.session)
    }

    static func attached(_ bench: WorkbenchView) async -> Bool {
        await RunnerTabTests.until(10) { bench.panes.values.filter(\.place.onRunner).allSatisfy(\.attachedOnce) }
    }

    /// R2: at launch, signed in as the person they were kept for, the runner tabs come back attached.
    @Test func launchReattachesTheRunnerTabsKeptForThePersonSignedIn() async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        runner.hold("app-r")
        var kept = Workbench()
        _ = kept.newTab(session: "app-r", place: Self.dock)
        let bench = try await Self.workbench(local: local, runner: runner, who: Who("uday"), kept: kept.arrangement(runner: "uday", holding: []))
        #expect(Self.runnerSessions(bench) == ["app-r"])
        #expect(await Self.attached(bench), "the runner tab attached")
        #expect(runner.sessions == ["app-r"] && runner.kills.isEmpty)
    }

    /// R10: signing out takes the runner tabs away and ends nothing; the same person's sign-in
    /// brings them back attached; another person's drops them.
    @Test func signingOutEndsNothingAndOnlyTheSamePersonGetsTheTabsBack() async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        let who = Who("uday")
        let bench = try await Self.workbench(local: local, runner: runner, who: who)
        bench.openWorkspace(project: "harbor", name: "dock")
        #expect(await RunnerTabTests.until(10) { Self.runnerSessions(bench).count == 1 })
        #expect(await Self.attached(bench))
        let session = try #require(Self.runnerSessions(bench).first)

        who.handle = nil
        bench.standingChanged()
        #expect(Self.runnerSessions(bench).isEmpty)
        #expect(runner.sessions == [session] && runner.kills.isEmpty, "the sign-out ended nothing")

        who.handle = "uday"
        bench.standingChanged()
        #expect(Self.runnerSessions(bench) == [session])
        #expect(await Self.attached(bench), "back attached")

        who.handle = nil
        bench.standingChanged()
        who.handle = "ana"
        bench.standingChanged()
        #expect(Self.runnerSessions(bench).isEmpty, "another person's sign-in dropped them")
        #expect(runner.sessions == [session] && runner.kills.isEmpty)
    }

    /// R7: Open on a workspace attaches a tab to each of the person's live sessions no tab holds,
    /// newest first; with none left, it opens a new one.
    @Test func openingAWorkspaceReattachesItsLiveSessionsNewestFirst() async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        runner.hold("app-old")
        runner.hold("app-new")
        runner.hold("app-ended")
        runner.end("app-ended", saying: Self.stopped)
        let bench = try await Self.workbench(local: local, runner: runner, who: Who("uday"))
        bench.openWorkspace(project: "harbor", name: "dock")
        #expect(await RunnerTabTests.until(10) { Self.runnerSessions(bench).count == 2 })
        #expect(Self.runnerSessions(bench) == ["app-new", "app-old"])
        #expect(await Self.attached(bench))
        #expect(runner.createsReceived == 0)

        bench.openWorkspace(project: "harbor", name: "dock")
        #expect(await RunnerTabTests.until(10) { Self.runnerSessions(bench).count == 3 })
        #expect(await RunnerTabTests.until(10) { runner.createsReceived == 1 }, "a new one is opened")
    }

    /// R8, R9: a reattach the runner refuses says, from the workspace's listing, that the terminal
    /// ended in the host's words, or that it is no longer on the runner; it offers Open a New One,
    /// which opens one there, and Close.
    @Test func aRefusedReattachSaysEndedOrGoneWithOpenANewOneAndClose() async throws {
        let runner = try FakeRunnerDoor(person: "uday")
        defer { runner.stop() }
        runner.hold("app-x")
        runner.end("app-x", saying: Self.stopped)
        let ended = try RunnerTabTests.pane(Self.dock, runner: DoorTickets(runner), start: .attach, session: "app-x", person: "uday")
        ended.begin()
        #expect(await RunnerTabTests.until(10) { ended.status.says == "This terminal ended: \(Self.stopped)" }, "\(ended.status)")
        #expect(ended.status.offers == ["Open a New One", "Close"])

        let gone = try RunnerTabTests.pane(Self.dock, runner: DoorTickets(runner), start: .attach, session: "app-gone", person: "uday")
        var closed = false
        gone.onClose = { _ in closed = true }
        gone.begin()
        #expect(await RunnerTabTests.until(10) { gone.status.says == RunnerReturn.gone }, "\(gone.status)")
        #expect(gone.press("Close") && closed)

        #expect(ended.press("Open a New One"))
        #expect(await RunnerTabTests.until(10) { runner.createsReceived == 1 && ended.attachedOnce }, "a new one opened in the workspace")
    }

    /// The workspace's listing not found (deleted, or on another runner) says so, offering Close
    /// alone; the runner's terminal host not answering is said in its words, with Try Again, which
    /// attaches the same session again once it answers.
    @Test func aReattachWhoseWorkspaceIsNotHereOrWhoseHostIsBusySaysSo() async throws {
        let runner = try FakeRunnerDoor(person: "uday")
        defer { runner.stop() }
        let elsewhere = try RunnerTabTests.pane(
            Self.dock, runner: Answering(runner, .refused(Refusal(code: .known(.notFound), message: "Project 'harbor' has no workspace 'dock' on this box."))),
            start: .attach, session: "app-x", person: "uday"
        )
        elsewhere.begin()
        #expect(await RunnerTabTests.until(10) { elsewhere.status.says == RunnerReturn.elsewhere }, "\(elsewhere.status)")
        #expect(elsewhere.status.offers == ["Close"])

        let busyAnswer = Answering(runner, .refused(Refusal(code: .known(.terminalUnavailable), message: "The pty host is shutting down; reconnect and try again.")))
        let busy = try RunnerTabTests.pane(Self.dock, runner: busyAnswer, start: .attach, session: "app-y", person: "uday")
        busy.begin()
        #expect(await RunnerTabTests.until(10) { busy.status.says == "The pty host is shutting down; reconnect and try again." }, "\(busy.status)")
        #expect(busy.status.offers == ["Try Again", "Close"])
        runner.hold("app-y")
        #expect(busy.press("Try Again"))
        #expect(await RunnerTabTests.until(10) { busy.attachedOnce }, "attached again once the host answers")
    }

    /// The door's tickets, its listing answered with `refusal`.
    final class Answering: TicketSource {
        let tickets: DoorTickets
        let refusal: SailError

        init(_ door: FakeRunnerDoor, _ refusal: SailError) {
            tickets = DoorTickets(door)
            self.refusal = refusal
        }

        func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
            try await tickets.ticket(for: request)
        }

        func sessions(in _: String, workspace _: String) async throws(SailError) -> RunnerSessions {
            throw refusal
        }
    }

    /// R11: the keyboard taken by this person's other Mac is said, with Take Keyboard, which takes
    /// it back and the line goes.
    @Test func theKeyboardTakenElsewhereIsSaidAndTakeKeyboardTakesItBack() async throws {
        let runner = try FakeRunnerDoor(person: "uday")
        defer { runner.stop() }
        runner.hold("app-k")
        let pane = try RunnerTabTests.pane(Self.dock, runner: DoorTickets(runner), start: .attach, session: "app-k", person: "uday")
        pane.begin()
        #expect(await RunnerTabTests.until(10) { pane.attachedOnce && pane.session?.state.writer == "uday" })
        #expect(pane.status.says == nil, "the keyboard came here")

        runner.takeKeyboard("app-k", by: "uday")
        #expect(await RunnerTabTests.until(10) { pane.status.says == "Typing here is paused: the keyboard is with uday now." }, "\(pane.status)")
        #expect(pane.status.offers == ["Take Keyboard"])
        #expect(pane.press("Take Keyboard"))
        #expect(await RunnerTabTests.until(10) { pane.status.says == nil }, "\(pane.status)")
    }

    /// R1: quitting detaches the runner's terminals and asks nothing; the runner keeps them.
    @Test func quittingEndsNothingOnTheRunner() async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        let bench = try await Self.workbench(local: local, runner: runner, who: Who("uday"))
        bench.openWorkspace(project: "harbor", name: "dock")
        #expect(await RunnerTabTests.until(10) { Self.runnerSessions(bench).count == 1 })
        #expect(await Self.attached(bench))
        #expect(!ShellApp.instancesRespond(to: #selector(NSApplicationDelegate.applicationShouldTerminate(_:))), "a quit asks nothing")
        bench.detachAll()
        #expect(runner.sessions == Self.runnerSessions(bench) && runner.kills.isEmpty)
    }
}
