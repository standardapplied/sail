#if canImport(CoreGraphics)
import CoreGraphics
#endif
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

    /// Tab sizes from the least window's (420 by 572 beside the rail) to a large display's, with
    /// the 10x3 minimum at 15 pt and at a large font, at scale 1 and 2.
    static let windows = [
        Window(size: CGSize(width: 420, height: 572), least: CGSize(width: 110, height: 70), scale: 2),
        Window(size: CGSize(width: 1120, height: 772), least: CGSize(width: 110, height: 70), scale: 1),
        Window(size: CGSize(width: 1120, height: 772), least: CGSize(width: 73.3, height: 41.7), scale: 2),
        Window(size: CGSize(width: 2500, height: 1372), least: CGSize(width: 190, height: 120), scale: 2),
    ]

    /// The size invariant: no pane of any tab under the minimum.
    static func panesUnderTheMinimum(_ bench: Workbench, _ window: Window) -> [String] {
        bench.tabs.flatMap { tab in
            bench.frames(of: tab.id, in: window.size, scale: window.scale).panes.compactMap { id, frame in
                frame.width < window.least.width - 1e-9 || frame.height < window.least.height - 1e-9
                    ? "tab \(tab.id.value)'s \(id) is \(frame.width) by \(frame.height), under \(window.least)" : nil
            }
        }
    }

    /// One window per sequence, as the app has: its tab's size, the panes' minimum and the scale.
    struct Window {
        let size: CGSize
        let least: CGSize
        let scale: Double
    }

    static func randomStep(_ bench: inout Workbench, _ random: inout Seeded, made: inout Int, window: Window) -> String {
        let (size, least, scale) = (window.size, window.least, window.scale)
        let place = Place.thisMac(folder: "/f\(made % 3)")
        func session() -> String {
            made += 1
            return "mac-\(made)"
        }
        let panes = bench.panes.keys.sorted()
        switch Int.random(in: 0 ..< 22, using: &random) {
        case 0, 17: bench.newTab(session: session(), place: place); return "new tab"
        case 1, 2:
            let axis: Axis = Bool.random(using: &random) ? .sideBySide : .stacked
            _ = bench.split(axis, session: session(), place: place, in: size, minimum: least, scale: scale); return "split \(axis)"
        case 3: if let pane = bench.focusedPane { bench.close(pane.id) }; return "close"
        case 4: if let tab = bench.selected { bench.closeTab(tab) }; return "close tab"
        case 5: if let pane = panes.randomElement(using: &random) { bench.focus(pane) }; return "focus"
        case 6:
            let direction = directions.randomElement(using: &random) ?? .left
            bench.moveFocus(direction, in: size, scale: scale); return "move focus \(direction)"
        case 7: bench.focusNeighbour(next: Bool.random(using: &random)); return "neighbour"
        case 8:
            let direction = directions.randomElement(using: &random) ?? .left
            bench.moveDivider(direction, by: 10, in: size, minimum: least, scale: scale); return "divider \(direction)"
        case 9:
            let dividers = bench.frames(in: size, scale: scale).dividers
            if let divider = dividers.randomElement(using: &random) {
                bench.drag(divider.path, to: Double.random(in: -50 ... 1700, using: &random), in: size, minimum: least, scale: scale)
            }
            return "drag"
        case 10: bench.equalize(in: size, minimum: least, scale: scale); return "equalize"
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
        case 16: if let tab = bench.tabs.randomElement(using: &random) { bench.select(tab.id) }; return "select"
        case 18: bench.selectTab(at: Int.random(in: -1 ... 9, using: &random)); return "select at"
        case 19: bench.selectLastTab(); return "last tab"
        case 20: if let pane = panes.randomElement(using: &random) { bench.setFolder("file:///tmp", of: pane) }; return "folder"
        default:
            let direction = directions.randomElement(using: &random) ?? .left
            bench.moveDivider(direction, by: 400, in: size, minimum: least, scale: scale); return "far divider \(direction)"
        }
    }

    /// What the walk found: its first breach, every restore that differed, and how much it ran.
    struct Walked: Sendable {
        var issues: [String] = []
        var sequences = 0
        var stepsRun = 0
        var restoredTabs = 0
        var restoredPanes = 0
    }

    static func walk() -> Walked {
        var random = Seeded(state: 0x5A11)
        var walked = Walked()
        for sequence in 0 ..< 10000 {
            walked.sequences += 1
            var bench = Workbench()
            var made = 0
            let window = Self.windows[sequence % Self.windows.count]
            bench.newTab(session: "mac-0", place: .thisMac(folder: "/"))
            var trail: [String] = ["new tab"]
            for _ in 0 ..< Int.random(in: 1 ... 30, using: &random) {
                trail.append(Self.randomStep(&bench, &random, made: &made, window: window))
                walked.stepsRun += 1
                let breaches = Self.breaches(bench) + Self.panesUnderTheMinimum(bench, window)
                guard breaches.isEmpty else {
                    walked.issues.append("sequence \(sequence) after \(trail.joined(separator: ", ")): \(breaches.joined(separator: "; "))")
                    return walked
                }
            }
            let live = bench.panes.values.map(\.session)
            let restored = Workbench.restore(bench.arrangement, live: live, home: "/home")
            walked.restoredTabs += restored.tabs.count
            walked.restoredPanes += restored.panes.count
            if !Self.breaches(restored).isEmpty { walked.issues.append("sequence \(sequence): restored \(Self.breaches(restored))") }
            if restored.focusedPane?.session != bench.focusedPane?.session { walked.issues.append("sequence \(sequence): the focus did not come back") }
            if restored.arrangement != bench.arrangement {
                walked.issues.append("sequence \(sequence) after \(trail.joined(separator: ", "))")
                return walked
            }
        }
        return walked
    }

    /// The walk runs on a thread of its own, so it holds none of the threads every other test
    /// shares while it computes.
    @Test func everyOperationKeepsTheValueWholeAndWhatIsKeptComesBackAsItself() async {
        let walked = await onOwnThread(Self.walk)
        print("WholenessTests ran \(walked.sequences) sequences, \(walked.stepsRun) steps")
        #expect(walked.issues.isEmpty, "\(walked.issues.prefix(5))")
        #expect(walked.sequences == 10000)
        #expect(
            walked.stepsRun > 100_000 && walked.restoredTabs > 10000 && walked.restoredPanes > walked.restoredTabs + 5000,
            "\(walked.stepsRun) steps, \(walked.restoredTabs) tabs and \(walked.restoredPanes) panes restored"
        )
    }
}

/// `work`, blocking, on a thread of its own, its result awaited.
func onOwnThread<Value: Sendable>(_ work: @escaping @Sendable () -> Value) async -> Value {
    await withCheckedContinuation { result in
        Thread { result.resume(returning: work()) }.start()
    }
}
