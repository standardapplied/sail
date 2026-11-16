import Foundation
import SailWorkbench
import Testing

/// W1 and scenario 11: 10,000 seeded sequences of random commands, the value whole after every
/// one, and what is kept restores as itself when every session lives.
@Suite struct WholenessTests {
    /// SplitMix64: the same sequences on every run and every platform.
    struct Seeded: RandomNumberGenerator {
        var state: UInt64

        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var mixed = state
            mixed = (mixed ^ (mixed >> 30)) &* 0xBF58_476D_1CE4_E5B9
            mixed = (mixed ^ (mixed >> 27)) &* 0x94D0_49BB_1331_11EB
            return mixed ^ (mixed >> 31)
        }
    }

    /// What W1 says, each breach in words.
    static func breaches(_ bench: Workbench) -> [String] {
        var said: [String] = []
        var seen: [PaneID] = []
        func ratios(_ layout: Layout) {
            guard case let .split(_, ratio, first, second) = layout else { return }
            if !(ratio > 0 && ratio < 1) { said.append("a ratio of \(ratio)") }
            ratios(first)
            ratios(second)
        }
        for tab in bench.tabs {
            let leaves = tab.layout.leaves
            seen += leaves
            ratios(tab.layout)
            if !leaves.contains(tab.focused) { said.append("tab \(tab.id.value) focuses \(tab.focused), not in its tree") }
            if let zoomed = tab.zoomed, !leaves.contains(zoomed) { said.append("tab \(tab.id.value) zooms \(zoomed), not in its tree") }
            if Set(tab.history) != Set(leaves) || tab.history.count != leaves.count || tab.history.last != tab.focused {
                said.append("tab \(tab.id.value)'s history \(tab.history) is not its panes once, the focused one last")
            }
        }
        if bench.tabs.isEmpty ? bench.selected != nil : !bench.tabs.contains(where: { $0.id == bench.selected }) {
            said.append("the selected tab \(String(describing: bench.selected)) is not one of the tabs")
        }
        if Set(seen).count != seen.count { said.append("a pane is in two places") }
        if Set(seen) != Set(bench.panes.keys) { said.append("the panes \(bench.panes.keys.sorted()) are not the trees' \(seen.sorted())") }
        let sessions = bench.panes.values.map(\.session)
        if Set(sessions).count != sessions.count { said.append("a session has two panes") }
        if Set(bench.tabs.map(\.id)).count != bench.tabs.count { said.append("two tabs share an id") }
        return said
    }

    static let directions = Direction.allCases

    static func randomStep(_ bench: inout Workbench, _ random: inout Seeded, made: inout Int) -> String {
        let size = CGSize(width: Double.random(in: 200 ... 1600, using: &random).rounded(), height: Double.random(in: 150 ... 1000, using: &random).rounded())
        let least = CGSize(width: 50, height: 40)
        let place = Place.thisMac(folder: "/f\(made % 3)")
        func session() -> String {
            made += 1
            return "mac-\(made)"
        }
        let panes = bench.panes.keys.sorted()
        switch Int.random(in: 0 ..< 18, using: &random) {
        case 0, 17: bench.newTab(session: session(), place: place); return "new tab"
        case 1, 2:
            let axis: Axis = Bool.random(using: &random) ? .sideBySide : .stacked
            _ = bench.split(axis, session: session(), place: place, in: size, minimum: least, scale: 2); return "split \(axis)"
        case 3: if let pane = bench.focusedPane { bench.close(pane.id) }; return "close"
        case 4: if let tab = bench.selected { bench.closeTab(tab) }; return "close tab"
        case 5: if let pane = panes.randomElement(using: &random) { bench.focus(pane) }; return "focus"
        case 6:
            let direction = directions.randomElement(using: &random) ?? .left
            bench.moveFocus(direction, in: size, scale: 2); return "move focus \(direction)"
        case 7: bench.focusNeighbour(next: Bool.random(using: &random)); return "neighbour"
        case 8:
            let direction = directions.randomElement(using: &random) ?? .left
            bench.moveDivider(direction, by: 10, in: size, minimum: least, scale: 2); return "divider \(direction)"
        case 9:
            let dividers = bench.frames(in: size, scale: 2).dividers
            if let divider = dividers.randomElement(using: &random) {
                bench.drag(divider.path, to: Double.random(in: -50 ... 1700, using: &random), in: size, minimum: least, scale: 2)
            }
            return "drag"
        case 10: bench.equalize(); return "equalize"
        case 11: bench.toggleZoom(); return "zoom"
        case 12: bench.selectNeighbourTab(next: Bool.random(using: &random)); return "neighbour tab"
        case 13:
            if let tab = bench.tabs.randomElement(using: &random) { bench.moveTab(tab.id, to: Int.random(in: -1 ... 9, using: &random)) }
            return "move tab"
        case 14:
            if let tab = bench.tabs.randomElement(using: &random) { bench.rename(tab.id, to: ["", "api", " ", "deploy"].randomElement(using: &random) ?? "") }
            return "rename"
        case 15:
            if let pane = panes.randomElement(using: &random) { bench.setTitle("t\(made)", of: pane) }
            return "title"
        default:
            if let pane = panes.randomElement(using: &random) { _ = bench.reopen(pane, session: session()) }
            return "reopen"
        }
    }

    @Test func everyOperationKeepsTheValueWholeAndWhatIsKeptComesBackAsItself() {
        var random = Seeded(state: 0x5A11)
        var stepsRun = 0
        var restoredTabs = 0
        var restoredPanes = 0
        for sequence in 0 ..< 10000 {
            var bench = Workbench()
            var made = 0
            bench.newTab(session: "mac-0", place: .thisMac(folder: "/"))
            var trail: [String] = ["new tab"]
            for _ in 0 ..< Int.random(in: 1 ... 30, using: &random) {
                trail.append(Self.randomStep(&bench, &random, made: &made))
                stepsRun += 1
                let breaches = Self.breaches(bench)
                guard breaches.isEmpty else {
                    Issue.record("sequence \(sequence) after \(trail.joined(separator: ", ")): \(breaches.joined(separator: "; "))")
                    return
                }
            }
            let live = bench.panes.values.map(\.session)
            let restored = Workbench.restore(bench.arrangement, live: live, home: "/home")
            restoredTabs += restored.tabs.count
            restoredPanes += restored.panes.count
            #expect(Self.breaches(restored).isEmpty, "sequence \(sequence): restored \(Self.breaches(restored))")
            #expect(restored.arrangement == bench.arrangement, "sequence \(sequence) after \(trail.joined(separator: ", "))")
            #expect(restored.focusedPane?.session == bench.focusedPane?.session)
            if restored.arrangement != bench.arrangement { return }
        }
        #expect(stepsRun > 100_000 && restoredTabs > 10000 && restoredPanes > restoredTabs + 5000, "\(stepsRun) steps, \(restoredTabs) tabs and \(restoredPanes) panes restored")
    }
}
