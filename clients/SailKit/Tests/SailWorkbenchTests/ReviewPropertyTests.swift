#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation
import SailWorkbench
import Testing

/// The review's generator: one window size, one minimum and one scale per sequence (as the app
/// has), deep trees by biasing to splits, every public command including the selects and the
/// folder, and restore against a random subset of live sessions plus strays and foreign sessions.
@Suite struct ReviewPropertyTests {
    struct XorShift: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state ^= state << 13
            state ^= state >> 7
            state ^= state << 17
            return state
        }
    }

    static func panesUnder(_ bench: Workbench, _ minimum: CGSize, size: CGSize, scale: Double, slack: Double) -> [String] {
        guard let tab = bench.selectedTab, tab.zoomed == nil else { return [] }
        let frames = bench.frames(in: size, scale: scale).panes
        return frames.compactMap { id, frame in
            frame.width + slack < minimum.width || frame.height + slack < minimum.height ? "\(id) is \(frame.size)" : nil
        }
    }

    static func whole(_ bench: Workbench) -> [String] {
        var said: [String] = []
        func walk(_ layout: Layout) {
            guard case let .split(_, ratio, first, second) = layout else { return }
            if !(ratio > 0 && ratio < 1) { said.append("ratio \(ratio)") }
            walk(first)
            walk(second)
        }
        var seen: [PaneID] = []
        for tab in bench.tabs {
            walk(tab.layout)
            let leaves = tab.layout.leaves
            seen += leaves
            if !leaves.contains(tab.focused) { said.append("focused not in tree") }
            if let zoomed = tab.zoomed, !leaves.contains(zoomed) { said.append("zoomed not in tree") }
            if Set(tab.history) != Set(leaves) || tab.history.count != leaves.count || tab.history.last != tab.focused { said.append("history \(tab.history)") }
        }
        if Set(seen) != Set(bench.panes.keys) || Set(seen).count != seen.count { said.append("panes and trees differ") }
        if bench.tabs.isEmpty != (bench.selected == nil) || (bench.selected != nil && !bench.tabs.contains { $0.id == bench.selected }) { said.append("selected") }
        let sessions = bench.panes.values.map(\.session)
        if Set(sessions).count != sessions.count { said.append("a session twice") }
        return said
    }

    struct Counts: Sendable {
        var sequences = 0
        var breaches: [String] = []
        var under: [String] = []
        var underStrict: [String] = []
        var zoomKept: [String] = []
        var restoreWrong: [String] = []
        var ops: [String: Int] = [:]
    }

    static func generate() -> Counts {
        var random = XorShift(state: 0xC0FFEE_1234_5678)
        var counts = Counts()
        let minimums = [CGSize(width: 10, height: 10), CGSize(width: 73.3, height: 41.7), CGSize(width: 110, height: 70)]
        let sizes = [CGSize(width: 1180, height: 760), CGSize(width: 640, height: 612), CGSize(width: 2560, height: 1400), CGSize(width: 401, height: 333)]
        for sequence in 0 ..< 20000 {
            counts.sequences += 1
            var bench = Workbench()
            var made = 0
            let size = sizes.randomElement(using: &random)!
            let minimum = minimums.randomElement(using: &random)!
            let scale = Bool.random(using: &random) ? 1.0 : 2.0
            func session() -> String { made += 1; return "mac-\(sequence)-\(made)" }
            bench.newTab(session: session(), place: .thisMac(folder: "/"))
            var trail: [String] = []
            for _ in 0 ..< Int.random(in: 5 ... 60, using: &random) {
                let underBefore = Self.panesUnder(bench, minimum, size: size, scale: scale, slack: 1).isEmpty
                let strictBefore = Self.panesUnder(bench, minimum, size: size, scale: scale, slack: 0).isEmpty
                let tabBefore = bench.selected
                let roll = Int.random(in: 0 ..< 100, using: &random)
                var op = ""
                var mustUnzoom = false
                switch roll {
                case 0 ..< 30:
                    let axis: Axis = Bool.random(using: &random) ? .sideBySide : .stacked
                    let did = bench.split(axis, session: session(), place: .thisMac(folder: "/"), in: size, minimum: minimum, scale: scale)
                    op = "split \(axis)"; mustUnzoom = did != nil
                case 30 ..< 40:
                    let dividers = bench.frames(in: size, scale: scale).dividers
                    if let d = dividers.randomElement(using: &random) {
                        let extent = d.axis == .sideBySide ? Double(size.width) : Double(size.height)
                        bench.drag(d.path, to: Double.random(in: -100 ... extent + 100, using: &random), in: size, minimum: minimum, scale: scale)
                    }
                    op = "drag"
                case 40 ..< 50:
                    let dir = Direction.allCases.randomElement(using: &random)!
                    bench.moveDivider(dir, by: Double([10, 50, 400].randomElement(using: &random)!), in: size, minimum: minimum, scale: scale)
                    op = "divider \(dir)"
                case 50 ..< 56:
                    if let p = bench.focusedPane { bench.close(p.id); mustUnzoom = true }
                    op = "close"
                case 56 ..< 58: if let t = bench.selected { bench.closeTab(t) }; op = "closeTab"
                case 58 ..< 63:
                    let dir = Direction.allCases.randomElement(using: &random)!
                    mustUnzoom = bench.moveFocus(dir, in: size, scale: scale); op = "focus \(dir)"
                case 63 ..< 66: bench.focusNeighbour(next: Bool.random(using: &random)); op = "neighbour"
                case 66 ..< 70: bench.equalize(in: size, minimum: minimum, scale: scale); op = "equalize"
                case 70 ..< 75: bench.toggleZoom(); op = "zoom"
                case 75 ..< 79: bench.newTab(session: session(), place: .thisMac(folder: "/")); op = "newTab"
                case 79 ..< 81: if let t = bench.tabs.randomElement(using: &random) { bench.select(t.id) }; op = "select"
                case 81 ..< 83: bench.selectTab(at: Int.random(in: -2 ... 10, using: &random)); op = "selectAt"
                case 83: bench.selectLastTab(); op = "last"
                case 84 ..< 86: bench.selectNeighbourTab(next: Bool.random(using: &random)); op = "neighbourTab"
                case 86 ..< 88: if let t = bench.tabs.randomElement(using: &random) { bench.moveTab(t.id, to: Int.random(in: -5 ... 15, using: &random)) }; op = "moveTab"
                case 88 ..< 90: if let t = bench.tabs.randomElement(using: &random) { bench.rename(t.id, to: ["", " x ", "\n", "api"].randomElement(using: &random)!) }; op = "rename"
                case 90 ..< 92: if let p = bench.panes.keys.randomElement(using: &random) { bench.setFolder("file:///tmp", of: p) }; op = "folder"
                case 92 ..< 95: if let p = bench.panes.keys.randomElement(using: &random) { bench.focus(p) }; op = "click"
                default: if let p = bench.panes.keys.randomElement(using: &random) { bench.setTitle("t", of: p) }; op = "title"
                }
                counts.ops[op.components(separatedBy: " ")[0], default: 0] += 1
                trail.append(op)
                let breaches = Self.whole(bench)
                if !breaches.isEmpty, counts.breaches.count < 5 { counts.breaches.append("seq \(sequence) \(trail): \(breaches)") }
                if bench.selected == tabBefore, underBefore, !Self.panesUnder(bench, minimum, size: size, scale: scale, slack: 1).isEmpty, counts.under.count < 5 {
                    counts.under.append("seq \(sequence) size \(size) min \(minimum) scale \(scale) after \(op): \(Self.panesUnder(bench, minimum, size: size, scale: scale, slack: 1)) trail \(trail.suffix(8))")
                }
                if bench.selected == tabBefore, strictBefore, !Self.panesUnder(bench, minimum, size: size, scale: scale, slack: 0).isEmpty, counts.underStrict.count < 3 {
                    counts.underStrict.append("seq \(sequence) after \(op): \(Self.panesUnder(bench, minimum, size: size, scale: scale, slack: 0))")
                }
                if mustUnzoom, let same = bench.tabs.first(where: { $0.id == tabBefore }), same.zoomed != nil, counts.zoomKept.count < 5 { counts.zoomKept.append("seq \(sequence) after \(op)") }
            }
            let all = bench.panes.values.map(\.session)
            let live = all.filter { _ in Bool.random(using: &random) } + ["mac-stray-\(sequence)", "runner-\(sequence)"]
            let restored = Workbench.restore(bench.arrangement, live: live.shuffled(using: &random), home: "/h")
            let got = restored.panes.values.map(\.session)
            let wanted = Set(live.filter { all.contains($0) || $0.hasPrefix("mac-") })
            if Set(got) != wanted || got.count != wanted.count || !Self.whole(restored).isEmpty, counts.restoreWrong.count < 5 {
                counts.restoreWrong.append("seq \(sequence): got \(got.sorted()) wanted \(wanted.sorted()) \(Self.whole(restored))")
            }
        }
        return counts
    }

    /// The generator runs on a thread of its own, so it holds none of the threads every other
    /// test shares while it computes.
    @Test func reviewersGenerator() async {
        let counts = await onOwnThread(Self.generate)
        print("ReviewPropertyTests ran \(counts.sequences) sequences")
        #expect(counts.sequences == 20000)
        #expect(counts.breaches.isEmpty, "\(counts.breaches)")
        #expect(counts.under.isEmpty, "\(counts.under)")
        #expect(counts.underStrict.isEmpty, "\(counts.underStrict)")
        #expect(counts.zoomKept.isEmpty, "\(counts.zoomKept)")
        #expect(counts.restoreWrong.isEmpty, "\(counts.restoreWrong)")
    }

    /// A drag of an outer divider shrinks a nested split on the same axis proportionally, so its
    /// smaller side goes under the minimum while the outer clamp counts both nested minimums.
    @Test func outerDragSqueezesANestedSplitUnderTheMinimum() throws {
        var bench = Workbench()
        let size = CGSize(width: 1001, height: 601), least = CGSize(width: 110, height: 70)
        bench.newTab(session: "mac-a", place: .thisMac(folder: "/"))
        let bMade = bench.split(.sideBySide, session: "mac-b", place: .thisMac(folder: "/"), in: size, minimum: least, scale: 1)
        let b = try #require(bMade)
        let cMade = bench.split(.sideBySide, session: "mac-c", place: .thisMac(folder: "/"), in: size, minimum: least, scale: 1)
        let c = try #require(cMade)
        _ = b
        bench.drag([.second], to: 2000, in: size, minimum: least, scale: 1)
        bench.drag([], to: 2000, in: size, minimum: least, scale: 1)
        let frames = bench.frames(in: size, scale: 1).panes
        #expect((frames[c]?.width ?? 0) >= least.width, "the nested second pane is \(frames[c]?.width ?? 0) pt wide, under \(least.width)")
    }

    @Test func equalizeOnNestedMixedAxes() throws {
        var bench = Workbench()
        let size = CGSize(width: 1202, height: 801), least = CGSize(width: 10, height: 10)
        bench.newTab(session: "mac-a", place: .thisMac(folder: "/"))
        let a = try #require(bench.focusedPane?.id)
        let bMade = bench.split(.sideBySide, session: "mac-b", place: .thisMac(folder: "/"), in: size, minimum: least, scale: 1)
        let b = try #require(bMade)
        let cMade = bench.split(.stacked, session: "mac-c", place: .thisMac(folder: "/"), in: size, minimum: least, scale: 1)
        let c = try #require(cMade)
        let dMade = bench.split(.sideBySide, session: "mac-d", place: .thisMac(folder: "/"), in: size, minimum: least, scale: 1)
        let d = try #require(dMade)
        bench.drag([], to: 100, in: size, minimum: least, scale: 1)
        bench.equalize(in: size, minimum: least, scale: 1)
        let frames = bench.frames(in: size, scale: 1).panes
        let said = "\([a, b, c, d].map { frames[$0].map { "\($0)" } ?? "-" })"
        #expect(frames[a]?.width == 400, "a pane beside a row of two takes a third: \(said)")
        #expect(frames.values.allSatisfy { $0.width >= least.width && $0.height >= least.height }, "\(said)")
    }

    @Test func zeroMinimumLetsADragMakeARatioOfZero() {
        var bench = Workbench()
        let size = CGSize(width: 1001, height: 601)
        bench.newTab(session: "mac-a", place: .thisMac(folder: "/"))
        _ = bench.split(.sideBySide, session: "mac-b", place: .thisMac(folder: "/"), in: size, minimum: .zero, scale: 1)
        bench.drag([], to: -50, in: size, minimum: .zero, scale: 1)
        guard case let .split(_, ratio, _, _) = bench.tabs[0].layout else { return }
        #expect(ratio > 0 && ratio < 1, "ratio \(ratio)")
    }
}
