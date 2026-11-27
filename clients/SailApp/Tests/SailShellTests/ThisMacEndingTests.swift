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
    init() {
        ExitWatch.install()
    }

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
            try #require(bench.panes[id]), start: .open, host: host, runner: nil, person: "",
            fonts: TerminalFonts(directory: LookTests.fonts), points: 15, look: .light
        )
        pane.begin()
        try #require(await RunnerTabTests.until(10) { pane.attachedOnce && pane.status.says == nil }, "attached: \(pane.status.says ?? "nothing said")")
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
        let bench = try WorkbenchView(fonts: LookTests.fonts, host: Self.host(at: door), look: .light, defaults: ReattachTests.defaults())
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
        try #require(bench.strip.tabs.count == 2, "the tabs are \(bench.strip.tabs.count)")
        #expect(bench.strip.tabs.map(\.showsUnended) == [false, true], "its tab is marked")
        #expect(bench.strip.tabs[1].accessibilityLabel()?.hasSuffix("a terminal in it may still be running") == true, "and VoiceOver says so")
        door.refuseKills(saying: nil)
        #expect(pane.press("End Again"))
        #expect(pane.status.says == nil, "the old sentence goes as the new end begins")
        #expect(await RunnerTabTests.until(10) { bench.panes[pane.id] == nil && bench.model.panes[pane.id] == nil })
        #expect(!door.sessions.contains(pane.name) && door.sessions.count == 1, "only the other tab's session is left")
    }

    /// Closed from the workbench, an end the host has not answered once the delay passes says the
    /// pane is ending.
    @Test func theWorkbenchSaysAnEndStillUnansweredAfterTheDelay() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        let bench = try await Self.workbench(at: door, tabs: 2)
        let pane = try #require(bench.focused)
        let naps = HeldNaps()
        pane.nap = { await naps.nap($0) }
        door.holdKills()
        bench.closePane(nil)
        #expect(await RunnerTabTests.until(5) { naps.count == 1 }, "the delay began")
        #expect(pane.status.says == nil)
        naps.pass()
        #expect(await RunnerTabTests.until(5) { pane.status.says == "Ending this terminal…" }, "the pane says \(pane.status.says ?? "nothing")")
        door.releaseKills()
        #expect(await RunnerTabTests.until(10) { bench.panes[pane.id] == nil })
    }

    /// An end the host answers late, once the person has moved on, takes nothing from them: the
    /// focus stays where they put it, and the pane says so in its tab, which is marked.
    @Test func aLateUnconfirmedEndAfterThePersonMovedOnTakesNoFocus() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        let bench = try await Self.workbench(at: door, tabs: 3)
        let ids = bench.model.tabs.map(\.focused)
        let pane = try #require(bench.panes[ids[2]])
        let words = "Session '\(pane.name)' is not yours."
        door.refuseKills(saying: words)
        door.holdKills()
        bench.closePane(nil)
        #expect(bench.model.focusedPane?.id == ids[1], "the focus moved on as the end began")
        bench.model.focus(ids[0])
        bench.apply()
        door.releaseKills()
        #expect(await RunnerTabTests.until(10) { pane.status.offers == ["End Again", "Close Anyway"] }, "the pane says \(pane.status.says ?? "nothing")")
        #expect(bench.model.focusedPane?.id == ids[0] && bench.focused !== pane, "the focus stayed where the person put it")
        #expect(bench.strip.tabs.map(\.showsUnended) == [false, false, true], "its tab is marked")
        #expect(pane.status.says == EndOutcome.mayStillRun("This terminal", [words], onThisMac: true) + " Close Anyway leaves it there.")
    }

    /// Closing a zoomed pane moves the focus to a pane the person sees: the zoom ends as the focus
    /// leaves it, so no key goes to a pane the zoom hides.
    @Test func closingAZoomedPaneFocusesAPaneThatIsSeen() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        let bench = try await Self.workbench(at: door, tabs: 1)
        let place = Place.thisMac(folder: "/")
        let room = CGSize(width: 800, height: 600), least = CGSize(width: 100, height: 100)
        let made = bench.model.split(.sideBySide, session: place.newSessionName, place: place, in: room, minimum: least, scale: 2)
        let split = try #require(made)
        let pane = try #require(bench.model.panes[split])
        _ = try #require(bench.makeView(pane, start: .open, points: 15))
        bench.apply()
        #expect(await RunnerTabTests.until(10) { bench.panes.values.allSatisfy(\.attachedOnce) }, "both attached")
        bench.zoomSplit(nil)
        let zoomed = try #require(bench.focused)
        #expect(bench.model.selectedTab?.zoomed == zoomed.id)
        door.holdKills()
        bench.closePane(nil)
        let focused = try #require(bench.focused)
        #expect(focused !== zoomed && !focused.isHidden, "the keys go to a pane the person sees")
        #expect(bench.model.selectedTab?.zoomed == nil, "the zoom ended as the focus left it")
    }

    /// A person who moved away and back before a late answer still moved: the unended pane takes
    /// no focus.
    @Test func aLateUnconfirmedEndAfterThePersonMovedAwayAndBackTakesNoFocus() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        let bench = try await Self.workbench(at: door, tabs: 3)
        let ids = bench.model.tabs.map(\.focused)
        let pane = try #require(bench.panes[ids[2]])
        door.refuseKills(saying: "Session '\(pane.name)' is not yours.")
        door.holdKills()
        bench.closePane(nil)
        #expect(bench.model.focusedPane?.id == ids[1])
        bench.model.focus(ids[0])
        bench.apply()
        bench.model.focus(ids[1])
        bench.apply()
        door.releaseKills()
        #expect(await RunnerTabTests.until(10) { pane.status.offers == ["End Again", "Close Anyway"] }, "the pane says \(pane.status.says ?? "nothing")")
        #expect(bench.model.focusedPane?.id == ids[1], "back where it was, the person keeps the focus")
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

    /// This Mac's workbench over `door`, never shown.
    static func unshown(at door: FakeRunnerDoor) throws -> WorkbenchView {
        try WorkbenchView(fonts: LookTests.fonts, host: Self.host(at: door), look: .light, defaults: ReattachTests.defaults())
    }

    /// Restored for This Mac's page, Terminals not yet shown, the workbench lists the shells that
    /// live and opens none when none does; the terminals shown first, a restore that finds none
    /// opens one, as it always did.
    @Test func thisMacsPageRestoresWhatLivesAndStartsNothing() async throws {
        let (door, other) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(thisMac: true))
        defer {
            door.stop()
            other.stop()
        }
        let empty = try Self.unshown(at: door)
        empty.restoreOnce()
        #expect(empty.terminals == .listing)
        try #require(await RunnerTabTests.until(10) { empty.restored }, "the restore never ended")
        #expect(empty.model.tabs.isEmpty && door.createsReceived == 0, "the page's restore started a shell nobody asked for")
        #expect(empty.terminals == .listed([]))
        let kept = Place.thisMac(folder: "/").newSessionName
        door.hold(kept)
        let restored = try Self.unshown(at: door)
        restored.restoreOnce()
        try #require(await RunnerTabTests.until(10) { restored.restored }, "the restore never ended")
        #expect(restored.localSessions.map(\.title) == ["This Mac"] && restored.model.panes.values.map(\.session) == [kept], "\(restored.localSessions)")
        let shown = try Self.unshown(at: other)
        shown.shown()
        #expect(shown.model.tabs.isEmpty, "a shell opened before the restore")
        #expect(await RunnerTabTests.until(10) { shown.restored && shown.model.tabs.count == 1 }, "the terminals shown first opened no tab")
    }

    /// Terminals shown first once This Mac's page restored none opens a shell, as a first showing
    /// always did, and only one: a later showing opens none, and New Shell's own showing opens its
    /// shell alone.
    @Test func terminalsShownAfterThePagesEmptyRestoreOpenOneShell() async throws {
        let (door, other) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(thisMac: true))
        defer {
            door.stop()
            other.stop()
        }
        let shown = try Self.unshown(at: door)
        shown.restoreOnce()
        try #require(await RunnerTabTests.until(10) { shown.restored }, "the restore never ended")
        shown.shown()
        #expect(shown.model.tabs.count == 1, "Terminals' first showing opened \(shown.model.tabs.count) shells")
        shown.shown()
        #expect(shown.model.tabs.count == 1, "a second showing opened another shell")
        let asked = try Self.unshown(at: other)
        asked.onShow = { [weak asked] in asked?.shown() }
        asked.restoreOnce()
        try #require(await RunnerTabTests.until(10) { asked.restored }, "the restore never ended")
        asked.newTab(nil)
        #expect(asked.model.tabs.count == 1, "New Shell opened \(asked.model.tabs.count) shells")
    }

    /// A workspace's Open and a New Session's Start show Terminals for their own tab: after This
    /// Mac's page restored none, that showing, Terminals' first, opens no shell beside it; Start's
    /// tab opens at once, Open's once the runner lists the workspace's sessions.
    @Test func openAndStartShowTerminalsForTheirTabAlone() async throws {
        let (door, other, runner) = (try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(thisMac: true), try FakeRunnerDoor(person: "uday"))
        defer {
            door.stop()
            other.stop()
            runner.stop()
        }
        let tickets = DoorTickets(runner)
        for (bench, act) in [(try Self.unshown(at: door), "Start"), (try Self.unshown(at: other), "Open")] {
            bench.signedIn = { ("uday", tickets) }
            var showings = 0
            bench.onShow = { [weak bench] in
                showings += 1
                bench?.shown()
            }
            bench.restoreOnce()
            try #require(await RunnerTabTests.until(10) { bench.restored }, "the restore never ended")
            if act == "Start" { bench.startSession(nil, project: "harbor", name: "dock") } else { bench.openWorkspace(project: "harbor", name: "dock") }
            let titles = { bench.model.tabs.map { bench.model.title(of: $0.id) } }
            #expect(showings == 1 && titles() == (act == "Start" ? ["harbor · dock"] : []), "\(act): shown \(showings) times, the tabs are \(titles())")
            #expect(await RunnerTabTests.until(10) { titles() == ["harbor · dock"] }, "\(act): the tabs are \(titles())")
        }
    }

    /// New Shell pressed while the restore lists what lives opens its shell once the restore is
    /// done, beside the shells it brings back.
    @Test func newShellDuringTheRestoreOpensOneBesideWhatLives() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let kept = Place.thisMac(folder: "/").newSessionName
        door.hold(kept)
        let bench = try Self.unshown(at: door)
        bench.onShow = { [weak bench] in bench?.shown() }
        bench.restoreOnce()
        bench.newTab(nil)
        #expect(bench.model.tabs.isEmpty, "a shell opened before the restore")
        try #require(await RunnerTabTests.until(10) { bench.restored }, "the restore never ended")
        #expect(bench.model.tabs.count == 2 && bench.model.panes.values.contains { $0.session == kept }, "\(bench.model.tabs.count) tabs after the restore")
    }

    /// A restore that cannot reach this Mac's terminal host tells This Mac's page so: its terminals
    /// are unlisted, not listed empty.
    @Test func aRestoreThatCannotListSaysSo() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        let bench = try Self.unshown(at: door)
        door.stop()
        var told: [LocalProjectView.Terminals] = []
        bench.onSessions = { [weak bench] in bench.map { told.append($0.terminals) } }
        bench.restoreOnce()
        #expect(await RunnerTabTests.until(10) { told.last == .unlisted }, "told \(told)")
        #expect(!bench.restored && bench.terminals == .unlisted)
    }

    /// End Session asks by its row's terminal, which Workspaces does not show, never Close's own
    /// "Close this terminal?"; cancelled, the terminal stays.
    @Test func endSessionAsksByTheRowsTerminal() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let bench = try await Self.workbench(at: door, tabs: 2)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        window.contentView = bench
        window.orderFront(nil)
        let second = bench.model.tabs[1].focused
        #expect(bench.panes[second]?.mayEndAProgram == true, "nothing would be asked")
        bench.endSession(second)
        let sheet = try #require(window.attachedSheet, "End Session asked nothing")
        let words = WorkspacesViewTests.found(NSTextField.self, in: try #require(sheet.contentView)).map(\.stringValue)
        #expect(words.contains("Close This Mac 2?"), "End Session asked \(words)")
        window.endSheet(sheet, returnCode: .alertSecondButtonReturn)
        #expect(bench.panes[second]?.isEnding == false, "cancelled, the terminal is ending")
    }

    /// A pane of this Mac kept after its session ended is said Ended on This Mac's page, as its host
    /// said it and when, and the page is told.
    @Test func aKeptPaneThatEndedIsSaidEnded() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let bench = try Self.unshown(at: door)
        var told = 0
        bench.onSessions = { told += 1 }
        let id = bench.model.newTab(session: "mac-gone", place: .thisMac(folder: "/"))
        let pane = try #require(bench.model.panes[id])
        _ = try #require(bench.makeView(pane, start: .attach, points: 15))
        bench.apply()
        let before = told
        try #require(await RunnerTabTests.until(10) { bench.localSessions.first?.ended != nil }, "the refused attach was never said ended")
        let ended = bench.localSessions.first?.ended ?? ""
        #expect(ended.hasPrefix("No live session 'mac-gone' to attach. · ") && told > before, "\(ended)")
    }

    /// An exit is said by its code, any other end as the host said it, each with its time as the
    /// locale tells the time.
    @Test func anEndIsSaidWithItsTime() throws {
        let at = try #require(Calendar.current.date(from: DateComponents(year: 2026, month: 11, day: 26, hour: 11, minute: 2)))
        let british = Locale(identifier: "en_GB")
        #expect(WorkbenchView.said(.ended("exited(3)"), at: at, locale: british) == "exited 3 · 11:02")
        #expect(WorkbenchView.said(.hostRestarted, at: at, locale: british) == "the host restarted, which ends every session it held · 11:02")
        let american = WorkbenchView.said(.ended("exited(3)"), at: at, locale: Locale(identifier: "en_US"))
        #expect(american.hasPrefix("exited 3 · 11:02") && american.hasSuffix("AM"), "\(american)")
    }
}
