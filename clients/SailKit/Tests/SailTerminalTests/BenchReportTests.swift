import Foundation
import SailMeasures
import SailSurface
@testable import sail_term_probe
import Testing

/// What the bench's report keeps of its gated `frame()` series: each frame, so a run over its
/// limit says where its time went (the wait for the lock, the slices it waited behind, the probe's
/// own stall over it, its thread's CPU and what of it was proven starved), and the reference
/// thread's stalls and the looks at the frame thread beside them.
@Suite struct BenchReportTests {
    /// Looks every 5 ms across `span` at a thread `runnable`, its CPU rising by `cpuPerLook`, the
    /// machine's idle ticks still (none idle) and its busy ticks moving, our process using none.
    static func looks(across span: Span, runnable: Bool = true, cpuPerLook: Double = 0) -> [ThreadLook] {
        stride(from: span.start, through: span.end, by: 0.005).enumerated().map { index, time in
            ThreadLook(
                time: time, runnable: runnable, cpu: 0.5 + Double(index) * cpuPerLook, processCPU: 1,
                idleTicks: 100, busyTicks: 1000 + UInt32(index)
            )
        }
    }

    @Test func aFrameIsExcusedOnlyTheTimeItsThreadWasProvenStarved() {
        let frame = Span(1.000, 1.024)
        let starved = Self.looks(across: Span(1.002, 1.022))
        #expect(abs(Bench.excused(frame, cpu: 0.0006, looks: starved) - 0.020) < 1e-9, "20 ms runnable with no CPU and none idle")
        #expect(Bench.excused(frame, cpu: 0.0006, looks: Self.looks(across: Span(1.002, 1.022), cpuPerLook: 0.005)) == 0,
                "a thread on a core the whole time used the frame itself")
        #expect(Bench.excused(frame, cpu: 0.0006, looks: Self.looks(across: Span(1.002, 1.022), runnable: false)) == 0,
                "a thread waiting on something of ours was not starved")
        #expect(Bench.excused(frame, cpu: 0.0006, looks: Self.looks(across: Span(1.002, 1.011))) == 0, "three looks prove nothing")
        #expect(abs(Bench.excused(Span(1.000, 1.021), cpu: 0.008, looks: starved) - 0.013) < 1e-9, "15 ms proven, but never the frame's own CPU time")
    }

    @Test func aSeriesSaysEachFrameOverTheLimitAndCountsItsExcuses() {
        let measured = Bench.Measured(
            frames: [.microseconds(600), .milliseconds(24), .microseconds(7500)],
            waits: [Span(0.900, 0.9001), Span(1.000, 1.0001), Span(2.000, 2.0001)],
            slices: [1, 0, 1], cpus: [0.0005, 0.0006, 0.0074],
            looks: Self.looks(across: Span(1.002, 1.022)) + Self.looks(across: Span(2.001, 2.006), cpuPerLook: 0.005), realTime: true
        )
        let series = Bench.series("frame()", measured, own: Stalls(tick: 0.01))
        #expect(series.frames.map(\.excusedMilliseconds).map { ($0 * 1000).rounded() / 1000 } == [0, 20, 0])
        #expect(series.frames.map(\.cpuMilliseconds).map { ($0 * 1000).rounded() / 1000 } == [0.5, 0.6, 7.4])
        let said = Bench.told(series, realTime: true)
        #expect(said.first == "frame(): 3 frames, 1 excused, 20.0 ms in all; 7 looks at the frame thread, the looker real-time", "\(said)")
        #expect(said.count == 3, "each frame over 4 ms said: \(said)")
        #expect(said[1].hasPrefix("  frame 1 at 1.000 s: 24.00 ms, its thread's CPU 0.60 ms, its wait for the lock 0.10 ms; 20.0 ms excused: 20.0 ms excused at 1.002–1.022 s"), "\(said[1])")
        #expect(said[2] == "  frame 2 at 2.000 s: 7.50 ms, its thread's CPU 7.40 ms, its wait for the lock 0.10 ms; nothing excused: 2 looks in it, runnable at 2", "\(said[2])")
    }


    @Test func aSeriesKeepsEachFrameWithItsWaitItsSlicesAndTheStallOverIt() {
        var own = Stalls(tick: 0.001)
        own.record(Span(0.100, 0.110))
        let series = Bench.series(
            "frame() at 120 Hz, 1 MiB feeds back to back",
            Bench.Measured(
                frames: [.microseconds(600), .microseconds(4600)], waits: [Span(0.050, 0.0501), Span(0.104, 0.108)], slices: [0, 1],
                cpus: [0.0005, 0.0005], looks: [], realTime: nil
            ),
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
