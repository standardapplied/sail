import AppKit
import Foundation
import SailAPI
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailWorkbench
import Testing

/// Ruling 6 and review 1's M3 to M8: runner terminals end with a quit or a sign-out, asked once,
/// all at once under one deadline, only tabs holding a live session counted, each named by its
/// tab's title, and what may still run said as that; a close the runner does not confirm keeps
/// its tab; the window closes without quitting; the Workspaces pane says what it knows of the
/// sign-in. Against ``FakeRunnerDoor``, which refuses what the real door refuses.
@MainActor @Suite(.timeLimit(.minutes(1))) struct RunnerEndingTests {
    /// Every word a sheet shows: its texts and its buttons' titles.
    static func words(in view: NSView?) -> [String] {
        guard let view else { return [] }
        let own: [String] = switch view {
        case let button as NSButton: [button.title]
        case let field as NSTextField: [field.stringValue]
        default: []
        }
        return own + view.subviews.flatMap { words(in: $0) }
    }

    static func said(_ window: NSWindow) -> [String] {
        words(in: window.attachedSheet?.contentView)
    }

    /// Runner tabs in `names`, each attached through the door.
    static func attached(_ names: [String], _ tickets: DoorTickets) async throws -> [TerminalPane] {
        let panes = try names.map { try RunnerTabTests.pane(.workspace(project: "harbor", name: $0), runner: tickets) }
        panes.forEach { $0.begin() }
        #expect(
            await RunnerTabTests.until(10) { panes.allSatisfy { $0.attachedOnce && $0.status.says == nil } },
            "attached: \(panes.map { $0.status.says ?? "nothing said" })"
        )
        return panes
    }

    static let titled: @MainActor (TerminalPane) -> String = { $0.place.label ?? "" }

    @Test func quittingAsksOnceThenEndsEveryLiveRunnerTerminalAtOnceAndQuits() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let tickets = DoorTickets(door)
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attached(["dock", "try"], tickets)
        let never = try RunnerTabTests.pane(.workspace(project: "harbor", name: "pier"), runner: RecordingTickets(answering: .refused(RunnerTabTests.notYours)))
        never.begin()
        #expect(door.sessions.count == 2, "the host's session list before: \(door.sessions)")
        let replies = Replies()
        let answeredLater = RunnerEnds.ask(panes + [never], titled: Self.titled, on: shell.window) { replies.given.append($0) }
        #expect(answeredLater)
        let asked = Self.said(shell.window)
        #expect(asked.contains("Quit and End 2 Runner Terminals"), "only tabs holding a live session count: \(asked)")
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until(15) { replies.given == [true] }, "the app was told \(replies.given)")
        #expect(door.sessions.isEmpty, "the host's session list after: \(door.sessions)")
        #expect(door.kills.sorted() == panes.map(\.name).sorted())
    }

    @Test func cancellingTheQuitEndsNothing() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attached(["dock"], DoorTickets(door))
        let replies = Replies()
        _ = RunnerEnds.ask(panes, titled: Self.titled, on: shell.window) { replies.given.append($0) }
        #expect(Self.said(shell.window).contains("Quit and End 1 Runner Terminal"))
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertSecondButtonReturn)
        #expect(await RunnerTabTests.until { replies.given == [false] })
        #expect(door.kills.isEmpty && door.sessions == panes.map(\.name) && panes.allSatisfy { $0.status.says == nil })
    }

    /// M5: what may still run is named by its tab's title and said as possibly running; a session
    /// the runner ended already is not listed at all, and no session's name is shown.
    @Test func whatMayStillRunIsNamedByItsTabAndWhatEndedAlreadyIsNot() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attached(["dock", "try", "quay"], DoorTickets(door))
        door.holdWrite(panes[1].name)
        door.forget(panes[2].name)
        let replies = Replies()
        _ = RunnerEnds.ask(panes, titled: Self.titled, on: shell.window) { replies.given.append($0) }
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until(15) { Self.said(shell.window).contains("Some terminals on the runner may still be running") })
        let said = Self.said(shell.window).joined(separator: "\n")
        #expect(said.contains(EndOutcome.mayStillRun("harbor · try", [FakeRunnerDoor.notTheWriter])), "\(said)")
        #expect(!said.contains("harbor · quay") && !said.contains("harbor · dock "), "only what may still run: \(said)")
        #expect(!said.contains("in time"), "no timeout ends it, and none is promised: \(said)")
        #expect(!said.contains("app-"), "no session's own name is shown: \(said)")
        #expect(replies.given.isEmpty, "the app waits for the person to read it")
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until { replies.given == [true] })
    }

    /// M4: the ends run at once under one deadline, the tickets included: a control plane that
    /// never answers holds the quit for the deadline, not for its transport's 60 s.
    @Test func aQuitEndsWithinItsDeadlineWhenTheControlPlaneNeverAnswers() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let tickets = DoorTickets(door)
        let panes = try await Self.attached(["dock", "try"], tickets)
        tickets.silence()
        let replies = Replies()
        let started = ContinuousClock.now
        _ = RunnerEnds.ask(panes, titled: Self.titled, on: shell.window, within: .seconds(1)) { replies.given.append($0) }
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until(10) { Self.said(shell.window).contains("Some terminals on the runner may still be running") })
        #expect(ContinuousClock.now - started < .seconds(5), "both ended at once within the deadline")
        let said = Self.said(shell.window).joined(separator: "\n")
        #expect(said.contains(EndOutcome.mayStillRun("harbor · dock", ["The runner did not answer within 1 s."])), "\(said)")
        #expect(said.contains(EndOutcome.mayStillRun("harbor · try", ["The runner did not answer within 1 s."])), "\(said)")
        tickets.answerAgain()
    }

    /// m9 and M8: a quit the system asks for (log out, restart, shut down) asks nothing: the
    /// terminals are ended within the deadline and the system goes on.
    @Test func aQuitTheSystemAsksForEndsWithoutAsking() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attached(["dock"], DoorTickets(door))
        let replies = Replies()
        let asked = RunnerEnds.ask(panes, titled: Self.titled, on: shell.window, systemQuitting: true) { replies.given.append($0) }
        #expect(asked && shell.window.attachedSheet == nil, "nothing is asked")
        #expect(await RunnerTabTests.until(15) { replies.given == [true] })
        #expect(door.sessions.isEmpty)
    }

    /// M8: AppKit shows no sheet on a window that is off screen, so a quit asked while the window
    /// is closed brings it back and asks on it.
    @Test func aQuitAskedWithTheWindowClosedBringsItBack() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attached(["dock"], DoorTickets(door))
        shell.window.orderOut(nil)
        _ = RunnerEnds.ask(panes, titled: Self.titled, on: shell.window) { _ in }
        #expect(shell.window.isVisible && shell.window.attachedSheet != nil)
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertSecondButtonReturn)
    }

    /// M8: closing the window keeps the app and its tabs, this Mac's and the runner's; the Dock
    /// icon brings the window back.
    @Test func closingTheWindowKeepsTheAppAndItsTabs() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.window.orderFront(nil)
        let panes = shell.thisMac.panes.count
        #expect(!shell.applicationShouldTerminateAfterLastWindowClosed(NSApp))
        #expect(!shell.windowShouldClose(shell.window) && !shell.window.isVisible)
        #expect(shell.thisMac.panes.count == panes)
        #expect(shell.applicationShouldHandleReopen(NSApp, hasVisibleWindows: false) && shell.window.isVisible)
        shell.window.orderOut(nil)
    }

    /// M6: signing out with a runner terminal open asks as a quit does, naming the sign-out.
    @Test func signingOutWithRunnerTerminalsOpenAsksAndEndsThem() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attached(["dock"], DoorTickets(door))
        let replies = Replies()
        _ = RunnerEnds.ask(panes, titled: Self.titled, on: shell.window, asking: RunnerEnds.Question.signOut) { replies.given.append($0) }
        #expect(Self.said(shell.window).contains("Sign Out and End 1 Runner Terminal"))
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until(15) { replies.given == [true] })
        #expect(door.sessions.isEmpty)
    }

    /// M3: an end the runner does not confirm keeps the tab, saying it may still be running, with
    /// End Again and Close Anyway; End Again, once the runner allows it, ends it.
    @Test func aCloseTheRunnerDoesNotConfirmKeepsTheTabAndOffersEndAgain() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let panes = try await Self.attached(["dock"], DoorTickets(door))
        let pane = panes[0]
        door.holdWrite(pane.name)
        let refused = try #require(pane.end())
        let outcome = await refused.value
        #expect(outcome == .unconfirmed([FakeRunnerDoor.notTheWriter]) && !outcome.closesTab)
        #expect(pane.unended && pane.holdsRunnerSession, "it may still be running, and a quit still counts it")
        var again = 0
        pane.showUnended([FakeRunnerDoor.notTheWriter], endAgain: { again += 1 }, closeAnyway: {})
        #expect(pane.status.says == EndOutcome.mayStillRun("This terminal", [FakeRunnerDoor.notTheWriter]) + " Close Anyway leaves it there.")
        #expect(pane.status.offers == ["End Again", "Close Anyway"])
        door.release(pane.name)
        let ended = try #require(pane.end(), "End Again ends it again")
        #expect(await ended.value == .ended && door.sessions.isEmpty)
        #expect(again == 0)
    }

    /// N2: a tab whose workspace is still starting (its `Create` unanswered) holds a session coming:
    /// a quit counts it, and its end waits for the runner's answer within the deadline, then ends
    /// what was made, so nothing is left at the runner.
    @Test func aTabStillOpeningIsCountedAndItsEndWaitsForWhatTheRunnerMakes() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        door.holdCreates()
        let pane = try RunnerTabTests.pane(.workspace(project: "harbor", name: "dock"), runner: DoorTickets(door))
        pane.begin()
        #expect(await RunnerTabTests.until(10) { door.createsReceived == 1 })
        #expect(pane.holdsRunnerSession, "a session coming counts")
        let replies = Replies()
        _ = RunnerEnds.ask([pane], titled: Self.titled, on: shell.window) { replies.given.append($0) }
        #expect(Self.said(shell.window).contains("Quit and End 1 Runner Terminal"))
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        door.releaseCreates()
        #expect(await RunnerTabTests.until(15) { replies.given == [true] })
        #expect(door.sessions.isEmpty, "the host's list: nothing left behind")
    }

    /// r3: the panes of a split share their tab's title, and each end is still its own pane's: one
    /// ended after another was not confirmed leaves that one said as its own end came.
    @Test func twoPanesOfOneTitleAreEachSaidAsTheirOwnEndCame() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let slow = DoorTickets(door)
        let first = try await Self.attached(["dock"], slow)[0]
        let second = try await Self.attached(["dock"], DoorTickets(door))[0]
        door.holdWrite(second.name)
        slow.silence()
        let replies = Replies()
        _ = RunnerEnds.ask([first, second], titled: Self.titled, on: shell.window) { replies.given.append($0) }
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until(10) { second.unended }, "the second pane's end came first")
        slow.answerAgain()
        #expect(await RunnerTabTests.until(15) { Self.said(shell.window).contains("Some terminals on the runner may still be running") })
        let said = Self.said(shell.window).joined(separator: "\n")
        #expect(said.contains(EndOutcome.mayStillRun("harbor · dock", [FakeRunnerDoor.notTheWriter])), "\(said)")
        #expect(!said.contains("Sail went on"), "the ended pane's end is not taken for the other's: \(said)")
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until { replies.given == [true] })
    }

    /// n10: going on before the ends answer says which terminals may still run, by title, before
    /// the quit or sign-out goes on.
    @Test func goingOnBeforeTheEndsAnswerSaysWhatMayStillRun() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let shell = try WorkbenchLookTests.shell(.light)
        let tickets = DoorTickets(door)
        let panes = try await Self.attached(["dock"], tickets)
        tickets.silence()
        let replies = Replies()
        _ = RunnerEnds.ask(panes, titled: Self.titled, on: shell.window, asking: RunnerEnds.Question.signOut) { replies.given.append($0) }
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until(5) { Self.said(shell.window).contains("Go On Now") })
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until(5) { Self.said(shell.window).contains("Some terminals on the runner may still be running") })
        let said = Self.said(shell.window).joined(separator: "\n")
        #expect(said.contains(EndOutcome.mayStillRun("harbor · dock", ["Sail went on before the runner answered."])), "\(said)")
        #expect(replies.given.isEmpty, "the sign-out waits for the person to read it")
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await RunnerTabTests.until { replies.given == [true] })
        tickets.answerAgain()
    }

    /// M7: launched onto Workspaces, the pane says it is asking who is signed in, its rail item
    /// shown; signed out, it says so with Sign In, which shows the account; unknown, it says why
    /// with Try Again.
    @Test func theWorkspacesPaneSaysWhatItKnowsOfTheSignIn() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.workspaces.waitForSignIn()
        shell.select(.workspaces)
        #expect(shell.rail.button(.workspaces)?.isHidden == false && shell.rail.selected == .workspaces)
        #expect(shell.workspaces.text == "Workspaces\nAsking the control plane who is signed in…", "\(shell.workspaces.text)")
        shell.account.onStanding(nil)
        #expect(shell.workspaces.text == "Workspaces\nYou are signed out of the control plane.\nSign in to see your workspaces on the runner.")
        #expect(shell.workspaces.tryAgain.title == "Sign In" && !shell.workspaces.tryAgain.isHidden)
        shell.workspaces.tryAgain.performClick(nil)
        #expect(shell.rail.selected == .account)
        #expect(shell.rail.button(.workspaces)?.isHidden == true, "signed out, it is off the rail once left")
        shell.workspaces.signInUnknown(["Sail could not reach the control plane: The request timed out."])
        #expect(shell.workspaces.text.hasSuffix("Sail could not reach the control plane: The request timed out."))
        #expect(shell.workspaces.tryAgain.title == "Try Again")
    }
}
