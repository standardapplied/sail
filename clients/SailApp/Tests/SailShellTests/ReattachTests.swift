import AppKit
import Foundation
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailVT
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
    /// Defaults of a test's own, so no suite running beside it reads or overwrites its arrangement.
    static func defaults() -> UserDefaults {
        let name = "sail.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    static let dock = Place.workspace(project: "harbor", name: "dock")
    static let stopped = "Workspace dock of harbor was stopped by ana."

    /// This Mac's workbench over `local`, restored from `kept`, its runner tabs reaching `runner`
    /// as whoever `who` names.
    static func workbench(local: FakeRunnerDoor, runner: FakeRunnerDoor, who: Who, kept: Arrangement? = nil) async throws -> WorkbenchView {
        let defaults = Self.defaults()
        if let kept { defaults.set(try JSONEncoder().encode(kept), forKey: WorkbenchView.arrangementKey) }
        let bench = try WorkbenchView(fonts: LookTests.fonts, host: ThisMacEndingTests.host(at: local), look: .light, defaults: defaults)
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
    /// newest first; with all in tabs, it chooses the newest's tab and starts nothing (minor 8).
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

        let newest = try #require(bench.panes.values.first { $0.name == "app-new" })
        bench.model.focus(try #require(bench.panes.values.first { $0.name == "app-old" }).id)
        bench.openWorkspace(project: "harbor", name: "dock")
        #expect(await RunnerTabTests.until(10) { bench.model.focusedPane?.id == newest.id }, "the newest's tab is chosen")
        #expect(Self.runnerSessions(bench).count == 2 && runner.createsReceived == 0, "nothing started")
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

    /// The ended card and the keyboard card sit inside the pane, in a window as a person sees it,
    /// with every action reached by a click at its middle and 2 pt inside each edge.
    @Test func theCardsSitInsideThePaneAndEveryActionIsReached() async throws {
        let runner = try FakeRunnerDoor(person: "uday")
        defer { runner.stop() }
        runner.hold("app-x")
        runner.end("app-x", saying: Self.stopped)
        let pane = try RunnerTabTests.pane(Self.dock, runner: DoorTickets(runner), start: .attach, session: "app-x", person: "uday")
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 360), styleMask: [.titled], backing: .buffered, defer: false)
        let room = NSView(frame: NSRect(x: 0, y: 0, width: 520, height: 360))
        pane.frame = room.bounds
        room.addSubview(pane)
        window.contentView = room
        window.orderFront(nil)
        pane.begin()
        #expect(await RunnerTabTests.until(10) { pane.status.offers == ["Open a New One", "Close"] }, "\(pane.status)")
        #expect(WorkspacesReachTests.outOfReach(in: room).isEmpty, "\(WorkspacesReachTests.outOfReach(in: room))")

        runner.hold("app-k")
        let held = try RunnerTabTests.pane(Self.dock, runner: DoorTickets(runner), start: .attach, session: "app-k", person: "uday")
        held.frame = room.bounds
        pane.removeFromSuperview()
        room.addSubview(held)
        held.begin()
        #expect(await RunnerTabTests.until(10) { held.attachedOnce })
        runner.takeKeyboard("app-k", by: "ana")
        #expect(await RunnerTabTests.until(10) { held.status.offers == ["Take Keyboard"] }, "\(held.status)")
        #expect(held.status.says == "Typing here is paused: the keyboard is with ana now.", "someone else is named")
        #expect(WorkspacesReachTests.outOfReach(in: room).isEmpty, "\(WorkspacesReachTests.outOfReach(in: room))")
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
        #expect(await RunnerTabTests.until(10) { pane.status.says == "Typing here is paused: you took the keyboard in another window or on another Mac." }, "\(pane.status)")
        #expect(pane.status.offers == ["Take Keyboard"])
        #expect(!pane.statusReturns, "Return belongs to the terminal, never to Take Keyboard")
        var beeps = 0
        pane.beep = { beeps += 1 }
        pane.session?.send(KeyInput(key: .a, text: "a"))
        pane.session?.send(KeyInput(key: .b, text: "b"))
        #expect(await RunnerTabTests.until(10) { beeps == 1 }, "a key that goes nowhere is said, once")
        #expect(pane.press("Take Keyboard"))
        #expect(await RunnerTabTests.until(10) { pane.status.says == nil }, "\(pane.status)")
        #expect(beeps == 1)
    }

    /// M1: closing a tab whose keyboard is on the person's other Mac only detaches it, at a prompt
    /// or with a program running: the session goes on there, said once, and nothing is ended.
    @Test(arguments: [true, false])
    func closingATabWhoseKeyboardIsElsewhereEndsNothing(_ atPrompt: Bool) async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        let bench = try await Self.workbench(local: local, runner: runner, who: Who("uday"))
        bench.openWorkspace(project: "harbor", name: "dock")
        #expect(await RunnerTabTests.until(10) { Self.runnerSessions(bench).count == 1 })
        #expect(await Self.attached(bench))
        let pane = try #require(bench.panes.values.first { $0.place.onRunner })
        if atPrompt { _ = pane.session?.screen.feed(Array("\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}ls\r\n\u{1b}]133;C\u{07}\u{1b}]133;D;0\u{07}\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}".utf8)) }
        #expect(pane.mayEndAProgram == !atPrompt)
        runner.takeKeyboard(pane.name, by: "uday")
        #expect(await RunnerTabTests.until(10) { pane.stillRunning != nil })
        bench.model.focus(pane.id)
        bench.closePane(nil)
        #expect(bench.panes[pane.id] == nil, "the tab went")
        #expect(runner.sessions == [pane.name] && runner.kills.isEmpty, "nothing was ended")
        #expect(bench.bannerSays == "Still running: the keyboard is with your other window or Mac.")
    }

    /// The workbench's own sentence sits 32 pt below the terminals' top, or a small gap below the first
    /// row of the tallest font shown (the band and its tab strip are above the terminals, so it never
    /// reaches them), one card line tall, and never over a pane's card at the foot: in one pane, side by side and stacked,
    /// with the sentence showing, every action of every card is reached (the drive once clicked
    /// Close through "Still running").
    @Test(arguments: [nil, Axis.sideBySide, Axis.stacked])
    func theWorkbenchsSentenceNeverCoversAPanesCardOrAFirstRow(_ split: Axis?) async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        var kept = Workbench()
        _ = kept.newTab(session: "app-x", place: Self.dock)
        if let split { _ = kept.split(split, session: "app-y", place: Self.dock, in: CGSize(width: 960, height: 630), minimum: CGSize(width: 100, height: 60), scale: 2) }
        for session in kept.panes.values.map(\.session) {
            runner.hold(session)
            runner.end(session, saying: Self.stopped)
        }
        let bench = try await Self.workbench(local: local, runner: runner, who: Who("uday"), kept: kept.arrangement(runner: "uday", holding: []))
        let panes = bench.panes.values.filter(\.place.onRunner)
        #expect(panes.count == (split == nil ? 1 : 2))
        #expect(await RunnerTabTests.until(10) { panes.allSatisfy { $0.status.offers == ["Open a New One", "Close"] } }, "\(panes.map(\.status))")
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: 960, height: 630), styleMask: [.titled], backing: .buffered, defer: false)
        bench.frame = NSRect(x: 0, y: 0, width: 960, height: 630)
        window.contentView = bench
        window.orderFront(nil)
        bench.tell("Still running: the keyboard is with your other window or Mac.")
        bench.layoutSubtreeIfNeeded()
        let sentence = try #require(bench.subviews.compactMap { $0 as? Card }.first { !$0.isHidden })
        let place = sentence.frame
        let rows = panes.map { Double($0.terminal.metrics.padding.height + $0.terminal.metrics.height) / $0.terminal.metrics.scale }
        #expect(abs(Double(place.minY) - max(32, (rows.max() ?? 0) + Look.Space.s)) < 0.5 && Double(place.height) <= 40, "the sentence is at \(place), the first rows end at \(rows)")
        for pane in panes {
            let metrics = pane.terminal.metrics
            let paneFrame = bench.convert(pane.bounds, from: pane)
            let firstRow = Double(paneFrame.minY) + Double(metrics.padding.height + metrics.height) / metrics.scale
            if paneFrame.intersects(place) { #expect(Double(place.minY) >= firstRow, "the sentence at \(place) covers \(pane.name)'s first row, which ends at \(firstRow)") }
            let card = try #require(pane.subviews.compactMap { $0 as? Card }.first { !$0.isHidden })
            #expect(!bench.convert(card.bounds, from: card).intersects(place), "the sentence at \(place) lies over \(pane.name)'s card")
            for action in Self.buttons(card) where ["Open a New One", "Close"].contains(action.title) {
                let middle = action.convert(NSPoint(x: action.bounds.midX, y: action.bounds.midY), to: nil)
                let reached = bench.hitTest(try #require(bench.superview).convert(middle, from: nil))
                #expect(reached === action || reached?.isDescendant(of: action) == true, "a click on \(pane.name)'s \(action.title) reaches \(String(describing: reached))")
            }
        }
    }

    /// Two of the workbench's sentences never stack: the newer replaces the older in its one banner,
    /// and the older's time running out leaves the newer said.
    @Test func aNewerSentenceReplacesTheOlder() async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        let bench = try await Self.workbench(local: local, runner: runner, who: Who("uday"))
        bench.tell("Still running: the keyboard is with your other window or Mac.")
        bench.tell("Still running: the keyboard is with ana.")
        #expect(bench.subviews.compactMap { $0 as? Card }.filter { !$0.isHidden }.count == 1)
        #expect(bench.bannerSays == "Still running: the keyboard is with ana.")
    }

    static func buttons(_ view: NSView) -> [NSButton] {
        ((view as? NSButton).map { [$0] } ?? []) + view.subviews.flatMap(buttons)
    }

    /// Minor 3: a listing answered after its person signed out, someone else signing in meanwhile,
    /// opens nothing for the one signed in now.
    @Test func anOpenWhoseListingOutlivesItsSignInOpensNothing() async throws {
        let (local, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer { local.stop(); runner.stop() }
        let who = Who("uday")
        let bench = try await Self.workbench(local: local, runner: runner, who: who)
        let before = bench.model.panes.count
        who.handle = "ben"
        let theirs = RunnerSession(name: "app-a", live: true, attached: 0, writer: "", opener: "uday", command: [], createdAt: Date(), endedReason: nil)
        bench.open(Self.dock, attaching: RunnerSessions(sessions: [theirs]), for: "uday")
        #expect(bench.model.panes.count == before, "nothing opened under another person")
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
