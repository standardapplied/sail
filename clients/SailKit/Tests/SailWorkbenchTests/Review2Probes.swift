#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation
import SailWorkbench
import Testing

/// The reviewer's second-look probes, taken in as assertions.
@Suite struct Review2Probes {
    static let least = CGSize(width: 110, height: 70)
    static let home = Place.thisMac(folder: "/")

    static func widths(_ bench: Workbench, _ size: CGSize) -> [Double] {
        bench.tabs[0].layout.leaves.compactMap { bench.frames(in: size, scale: 1).panes[$0].map { Double($0.width) } }
    }

    @Test(arguments: [CGSize(width: 420, height: 572), CGSize(width: 1001, height: 601), CGSize(width: 1792, height: 1080), CGSize(width: 2500, height: 1372)])
    func theSqueezeAtEachSize(_ size: CGSize) {
        var bench = Workbench()
        bench.newTab(session: "mac-a", place: Self.home)
        _ = bench.split(.sideBySide, session: "mac-b", place: Self.home, in: size, minimum: Self.least, scale: 1)
        _ = bench.split(.sideBySide, session: "mac-c", place: Self.home, in: size, minimum: Self.least, scale: 1)
        bench.drag([.second], to: 5000, in: size, minimum: Self.least, scale: 1)
        bench.drag([], to: 5000, in: size, minimum: Self.least, scale: 1)
        bench.drag([], to: -5000, in: size, minimum: Self.least, scale: 1)
        let widths = Self.widths(bench, size)
        print("REVIEW2 squeeze at \(size): \(widths)")
        #expect(widths.allSatisfy { $0 >= Self.least.width }, "\(widths)")
    }

    /// A tab made at 2560 pt with a narrow pane, the window then halved: a drag or a key gives the
    /// narrow pane its room back, and none takes more from it.
    @Test func aShrunkWindowStillLetsAMoveGrowTheNarrowPane() {
        let big = CGSize(width: 2560, height: 1400), half = CGSize(width: 1220, height: 1400)
        var bench = Workbench()
        bench.newTab(session: "mac-a", place: Self.home)
        _ = bench.split(.sideBySide, session: "mac-b", place: Self.home, in: big, minimum: Self.least, scale: 1)
        bench.drag([], to: 2400, in: big, minimum: Self.least, scale: 1)
        print("REVIEW2 at 2560: \(Self.widths(bench, big)); halved to 1220: \(Self.widths(bench, half))")
        let moved = bench.drag([], to: 900, in: half, minimum: Self.least, scale: 1)
        let keyed = bench.moveDivider(.left, by: 10, in: half, minimum: Self.least, scale: 1)
        print("REVIEW2 drag that would give the narrow pane room: \(moved); ⌃⌘← \(keyed); after: \(Self.widths(bench, half))")
        #expect(moved, "a drag that only grows the pane under the minimum is refused")
        #expect(keyed, "⌃⌘← that only grows the pane under the minimum is refused")
        var under = Workbench()
        under.newTab(session: "mac-a", place: Self.home)
        _ = under.split(.sideBySide, session: "mac-b", place: Self.home, in: big, minimum: Self.least, scale: 1)
        under.drag([], to: 2400, in: big, minimum: Self.least, scale: 1)
        let squeezed = under.tabs[0].layout
        let narrowed = under.drag([], to: 1180, in: half, minimum: Self.least, scale: 1)
        let keyedNarrower = under.moveDivider(.right, by: 10, in: half, minimum: Self.least, scale: 1)
        #expect(!narrowed && !keyedNarrower, "a drag or ⌃⌘→ narrowed the pane already under the minimum")
        #expect(under.tabs[0].layout == squeezed)
        let grown = under.moveDivider(.left, by: 10, in: half, minimum: Self.least, scale: 1)
        #expect(grown, "⌃⌘← that grows the pane under the minimum, though not to it, is refused")
        #expect(Self.widths(under, half) == [1133, 86])
        let long = under.drag([], to: 50, in: half, minimum: Self.least, scale: 1)
        #expect(long, "a long drag stops where the other pane reaches the minimum")
        #expect(Self.widths(under, half) == [110, 1109])
    }

    /// A stored ratio from a large window restored into the least window.
    @Test func aStoredNarrowRatioRestoredIntoTheLeastWindow() {
        let small = CGSize(width: 420, height: 572)
        for ratio in [0.04, 1e-4, 1e-7] {
            var seed = Workbench()
            seed.newTab(session: "mac-a", place: Self.home)
            var kept = seed.arrangement
            kept.tabs[0].layout = .split(axis: .sideBySide, ratio: ratio,
                first: .pane(session: "mac-a", place: Self.home), second: .split(axis: .sideBySide, ratio: 0.5,
                    first: .pane(session: "mac-b", place: Self.home), second: .split(axis: .sideBySide, ratio: 0.5,
                        first: .pane(session: "mac-c", place: Self.home), second: .pane(session: "mac-d", place: Self.home))))
            var bench = Workbench.restore(kept, live: ["mac-a", "mac-b", "mac-c", "mac-d"], home: "/")
            bench.fit(in: small, minimum: Self.least, scale: 1)
            print("REVIEW2 stored ratio \(ratio), restored at 420 and fit: \(Self.widths(bench, small))")
            #expect(Self.widths(bench, small).allSatisfy { $0 >= Layout.dividerHitWidth }, "a pane laid out with no room to see or grab")
        }
    }

    @Test func equalizeOnMixedAxesWhereEqualDoesNotFit() {
        let size = CGSize(width: 460, height: 400)
        var bench = Workbench()
        bench.newTab(session: "mac-a", place: Self.home)
        _ = bench.split(.sideBySide, session: "mac-b", place: Self.home, in: size, minimum: Self.least, scale: 1)
        _ = bench.split(.stacked, session: "mac-c", place: Self.home, in: size, minimum: Self.least, scale: 1)
        _ = bench.split(.sideBySide, session: "mac-d", place: Self.home, in: size, minimum: Self.least, scale: 1)
        let before = Self.widths(bench, size)
        bench.equalize(in: size, minimum: Self.least, scale: 1)
        print("REVIEW2 equalize mixed in 460: before \(before) after \(Self.widths(bench, size))")
        #expect(bench.frames(in: size, scale: 1).panes.values.allSatisfy { $0.width >= Self.least.width && $0.height >= Self.least.height })
    }
}
