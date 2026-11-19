import Foundation
import SailTerminalWire
import SailWorkbench
import Testing

/// Runner panes outlive the app and come back for their own person: kept with the arrangement, held
/// while no one is signed in, dropped for anyone else.
@Suite struct RunnerReturnTests {
    static let dock = Place.workspace(project: "harbor", name: "dock")

    /// A this-Mac tab and two runner tabs, the second split, kept for ana.
    static func kept() -> Arrangement {
        var bench = Workbench()
        _ = bench.newTab(session: "mac-1", place: .thisMac(folder: "/"))
        _ = bench.newTab(session: "app-a", place: dock)
        _ = bench.newTab(session: "app-b", place: dock)
        _ = bench.split(.sideBySide, session: "app-c", place: dock, in: CGSize(width: 1000, height: 600), minimum: CGSize(width: 100, height: 60), scale: 2)
        return bench.arrangement(runner: "ana", holding: [])
    }

    @Test func runnerPanesComeBackForTheirOwnPersonAlone() {
        let kept = Self.kept()
        #expect(kept.runner == "ana")
        #expect(kept.runnerSessions(for: "ana") == ["app-a", "app-b", "app-c"])
        #expect(kept.runnerSessions(for: "ben").isEmpty)
        #expect(kept.runnerSessions(for: nil).isEmpty)
        let ana = Workbench.restore(kept, live: ["mac-1"] + kept.runnerSessions(for: "ana"), home: "/")
        #expect(ana.tabs.map { $0.layout.leaves.compactMap { ana.panes[$0]?.session } } == [["mac-1"], ["app-a"], ["app-b", "app-c"]])
        let ben = Workbench.restore(kept, live: ["mac-1"] + kept.runnerSessions(for: "ben"), home: "/")
        #expect(ben.panes.values.map(\.session) == ["mac-1"])
    }

    /// Held while no one is signed in, the runner tabs stay in what is kept, after the open ones,
    /// and a sign-in adopts them whole, a session already open never twice.
    @Test func heldRunnerTabsAreKeptAndAdoptedWhole() {
        let kept = Self.kept()
        var bench = Workbench.restore(kept, live: ["mac-1"], home: "/")
        let held = kept.runnerTabs
        #expect(held.count == 2)
        let again = bench.arrangement(runner: kept.runner, holding: held)
        #expect(again.tabs.count == 3 && again.runner == "ana")
        let made = bench.adopt(held)
        #expect(made.count == 3)
        #expect(bench.tabs.map { $0.layout.leaves.compactMap { bench.panes[$0]?.session } } == [["mac-1"], ["app-a"], ["app-b", "app-c"]])
        #expect(bench.adopt(held).isEmpty)
    }
}
