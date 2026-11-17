import Foundation
import SailMeasures
import SailSurface
@testable import sail_term_probe
import Testing

/// What the bench's report keeps of its gated `frame()` series: each frame, so a run over its
/// limit says where its time went (the wait for the lock, the slices it waited behind, the probe's
/// own stall over it), and the reference thread's stalls beside them.
@Suite struct BenchReportTests {
    @Test func aSeriesKeepsEachFrameWithItsWaitItsSlicesAndTheStallOverIt() {
        var own = Stalls(tick: 0.001)
        own.record(Span(0.100, 0.110))
        let series = Bench.series(
            "frame() at 120 Hz, 1 MiB feeds back to back",
            frames: [.microseconds(600), .microseconds(4600)],
            waits: [Span(0.050, 0.0501), Span(0.104, 0.108)],
            slices: [0, 1],
            own: own
        )

        #expect(series.name == "frame() at 120 Hz, 1 MiB feeds back to back")
        #expect(series.frames.map(\.at) == [0.050, 0.104])
        #expect(series.frames.map(\.milliseconds) == [0.6, 4.6])
        #expect(series.frames.map(\.slicesWaited) == [0, 1])
        #expect(abs(series.frames[0].waitMilliseconds - 0.1) < 1e-9)
        #expect(abs(series.frames[1].waitMilliseconds - 4) < 1e-9)
        #expect(series.frames[0].heldMilliseconds == 0, "no stall over the first wait")
        #expect(abs(series.frames[1].heldMilliseconds - 4) < 1e-9, "the stall's late part covers the second wait")
        #expect(series.stalls == [Span(0.100, 0.110)])
    }

    @Test func aReportKeepsItsSamplesAndOneWithoutThemSaysNothingOfThem() throws {
        let series = BenchSeries(
            name: "frame()", frames: [FrameSample(at: 1, milliseconds: 2, waitMilliseconds: 0.5, slicesWaited: 1, heldMilliseconds: 0)],
            stalls: [Span(1, 1.5)]
        )
        let with = try JSONSerialization.jsonObject(
            with: JSONEncoder().encode(Report(steps: [], latency: nil, droppedInputs: nil, budgets: [], samples: [series]))
        ) as? [String: Any]
        let samples = try #require(with?["samples"] as? [[String: Any]])
        let frame = try #require((samples.first?["frames"] as? [[String: Any]])?.first)
        #expect(frame["slicesWaited"] as? Int == 1)
        #expect(frame["waitMilliseconds"] as? Double == 0.5)
        #expect((samples.first?["stalls"] as? [[String: Any]])?.count == 1)

        let without = try JSONSerialization.jsonObject(
            with: JSONEncoder().encode(Report(steps: [], latency: nil, droppedInputs: nil, budgets: []))
        ) as? [String: Any]
        #expect(without?["samples"] == nil)
    }
}
