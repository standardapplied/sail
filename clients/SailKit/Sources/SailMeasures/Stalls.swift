import SailSurface

/// When a thread that only ticks, once every `tick` seconds, was kept from running: each gap
/// between its ticks held it for the gap less one tick.
public struct Stalls: Sendable {
    public static let capacity = 100_000

    public let tick: Double
    public private(set) var gaps: [Span] = []

    public init(tick: Double) {
        self.tick = tick
    }

    public mutating func record(_ gap: Span) {
        guard gap.length > tick else { return }
        gaps.append(gap)
        if gaps.count > Self.capacity { gaps.removeFirst() }
    }

    /// How much of `wait` this thread was held too, in seconds: the longest stall that overlaps
    /// it, less one tick, never below zero and never more than the overlap. A stall the machine held
    /// every thread through is the machine's; one at another moment says nothing of this wait, and
    /// ticks merely late on a busy machine do not add up to one.
    public func shared(with wait: Span) -> Double {
        gaps.reduce(0) { held, gap in max(held, min(gap.overlap(wait), max(0, gap.length - tick))) }
    }

    /// How much of a short `sample` this thread was held over, in seconds: the longest overlap
    /// with the late part of a gap, after the tick the thread slept for was due. A sleeping thread
    /// asks for no CPU, so only lateness is proof it was held; a tick late by a millisecond takes
    /// a millisecond, not the sub-millisecond samples a tail budget is made of anywhere in its gap.
    public func held(over sample: Span) -> Double {
        gaps.reduce(0) { held, gap in max(held, Span(gap.start + tick, gap.end).overlap(sample)) }
    }

    /// The longest gap, or the longest that overlaps `span`.
    public func longest(in span: Span? = nil) -> Span? {
        gaps.filter { gap in span.map { gap.overlap($0) > 0 } ?? true }.max { $0.length < $1.length }
    }

    public func net(of wait: Span) -> Double {
        max(0, wait.length - shared(with: wait))
    }
}

/// A look at a thread, taken by a sampler: whether it was runnable, its CPU time so far, our
/// processes' CPU time so far (its own, and the terminal host's and its session shims' where the
/// sampler was given them), and the machine's CPU ticks so far, idle and busy (each tick 1/100 s
/// of one CPU). The last three are nil where they were not read: a sampler reads them only on a
/// runnable look.
public struct ThreadLook: Sendable, Equatable, Codable {
    public static let tick = 0.01

    public var time: Double
    public var runnable: Bool
    public var cpu: Double
    public var ourCPU: Double?
    public var idleTicks: UInt32?
    public var busyTicks: UInt32?

    public init(time: Double, runnable: Bool, cpu: Double, ourCPU: Double?, idleTicks: UInt32?, busyTicks: UInt32?) {
        self.time = time
        self.runnable = runnable
        self.cpu = cpu
        self.ourCPU = ourCPU
        self.idleTicks = idleTicks
        self.busyTicks = busyTicks
    }
}

/// How much of a wait was the machine's rather than ours.
public enum MachineShare {
    /// The longest a look may come after the one before for the two to prove anything of the time
    /// between: three intervals of a sampler looking every 5 ms. A longer gap is missing samples,
    /// and missing samples excuse nothing.
    public static let longestGap = 0.015
    /// The fewest looks in a row that prove a thread was starved: a single runnable look is as
    /// likely a thread caught on its way into or out of a wait.
    public static let fewestLooks = 4
    /// The most of an interval's time a thread may have run and still be said to have got no CPU:
    /// a thread on a core is charged all of it.
    public static let cpuAllowed = 0.1

    /// Each stretch of `wait` the machine is answerable for, with the looks that prove it, on
    /// positive evidence only: at least ``fewestLooks`` looks in a row, none more than
    /// ``longestGap`` after the one before, at every one of which the thread was runnable (run
    /// state `TH_STATE_RUNNING`: on a core or on a run queue; a thread waiting on something of ours,
    /// a lock, a drawable, a `mach_msg` with nothing for it, is `TH_STATE_WAITING`), between every
    /// two of which it used under ``cpuAllowed`` of the time (so it was not on a core) while the
    /// machine's idle ticks did not move (so no CPU was idle for it), and over which the machine's
    /// busy ticks did move (the counters live). Of the busy CPU that kept it waiting, the share
    /// our own processes used (the app, its terminal host and the host's session shims) stays ours.
    public static func starvation(in wait: Span, looks: [ThreadLook]) -> [Starved] {
        let inside = looks.filter { $0.time >= wait.start && $0.time <= wait.end }.sorted { $0.time < $1.time }
        var proven: [Starved] = [], run: [ThreadLook] = []
        func close() {
            if run.count >= fewestLooks, let starved = Starved(run) { proven.append(starved) }
            run = []
        }
        for (from, to) in zip(inside, inside.dropFirst()) {
            guard starving(from, to) else {
                close()
                continue
            }
            if run.isEmpty { run = [from] }
            run.append(to)
        }
        close()
        return proven
    }

    /// Whether the thread got no CPU between two looks while it was runnable and none was idle.
    static func starving(_ from: ThreadLook, _ to: ThreadLook) -> Bool {
        let interval = to.time - from.time
        return interval > 0 && interval <= longestGap && from.runnable && to.runnable && to.cpu - from.cpu < interval * cpuAllowed
            && from.idleTicks != nil && from.idleTicks == to.idleTicks
    }

    /// The main run loop's modes as samples read them (nil: it ran none), each with how many
    /// samples saw it, the most seen first: whether the loop was in a mode that runs the main
    /// queue's blocks.
    public static func modes(_ seen: [String?]) -> String {
        guard !seen.isEmpty else { return "no sample" }
        let counted = Dictionary(grouping: seen) { $0 ?? "not running" }.map { (mode: $0.key, count: $0.value.count) }
        return counted.sorted { $0.count != $1.count ? $0.count > $1.count : $0.mode < $1.mode }.map { "\($0.mode) at \($0.count)" }.joined(separator: ", ")
    }

    /// The time in `wait` the machine is answerable for (``starvation(in:looks:)``), in seconds,
    /// never more than the wait.
    public static func starved(in wait: Span, looks: [ThreadLook]) -> Double {
        min(starvation(in: wait, looks: looks).reduce(0) { $0 + $1.machines }, wait.length)
    }

    /// Each sample's length less the time the measuring process's own thread was held over it,
    /// in seconds: a percentile taken of these is the code's, a stall of the whole process left out.
    public static func net(_ samples: [Span], own: Stalls) -> [Double] {
        samples.map { $0.length - own.held(over: $0) }
    }

    /// The most of a judged wait the main thread's proven starvation may excuse: half. Past it the
    /// starvation is inconclusive and none of it is excused, so a wait over its budget fails: a
    /// measurement that excused most of itself on inference measured nothing (the bench's rule, by
    /// time). The drive's own thread's stall is not capped here, though it too is an inference: a
    /// thread that only sleeps 10 ms woke late, which says the scheduler held it, not that our
    /// work did; it does not subtract our processes' CPU.
    public static let mostExcused = 0.5

    /// Whether the main thread's proven starvation is more than ``mostExcused`` of `wait`.
    public static func inconclusive(_ wait: Span, main looks: [ThreadLook]) -> Bool {
        starved(in: wait, looks: looks) > wait.length * mostExcused
    }

    /// The machine's share of `wait`, in seconds: the larger of the stall of the drive's own
    /// thread over it and the time the main thread was starved in it (none of the starvation when
    /// it is inconclusive), never more than the wait.
    public static func of(_ wait: Span, own: Stalls, main looks: [ThreadLook]) -> Double {
        let starvation = inconclusive(wait, main: looks) ? 0 : starved(in: wait, looks: looks)
        return min(wait.length, max(own.shared(with: wait), starvation))
    }
}

/// A stretch in which a thread was runnable and got no CPU while the machine had none idle, the
/// looks that prove it, and the share of the machine's busy CPU our own processes used over it.
public struct Starved: Sendable, Equatable {
    public let looks: [ThreadLook]
    public let ours: Double

    /// The stretch proven by `looks`, nil when the machine's busy ticks did not move over it or
    /// our processes' CPU was not read at its ends.
    init?(_ looks: [ThreadLook]) {
        guard let first = looks.first, let last = looks.last, let from = first.busyTicks, let to = last.busyTicks, to &- from > 0,
              let ourFrom = first.ourCPU, let ourTo = last.ourCPU
        else {
            return nil
        }
        self.looks = looks
        ours = min(1, max(0, ourTo - ourFrom) / (Double(to &- from) * ThreadLook.tick))
    }

    public var span: Span {
        Span(looks.first?.time ?? 0, looks.last?.time ?? 0)
    }

    /// The machine's share of the stretch, in seconds.
    public var machines: Double {
        span.length * (1 - ours)
    }

    /// The stretch and its evidence, so a reader can check it: the busy ticks beside the most
    /// `cpus` CPUs could have made over it, ticks moving in whole steps, so over a stretch this
    /// short the run state at each look is the stronger evidence.
    public func told(cpus: Int) -> String {
        let first = looks[0], last = looks[looks.count - 1]
        let gap = zip(looks, looks.dropFirst()).map { $1.time - $0.time }.max() ?? 0
        return String(
            format: "%.1f ms excused at %.3f–%.3f s: %ld looks at most %.1f ms apart, run state running at each, the thread's CPU +%.2f ms, the machine's idle ticks +0 and busy ticks +%ld (%ld CPUs make %.1f over the stretch), our processes %.0f%% of that busy CPU",
            machines * 1000, first.time, last.time, looks.count, gap * 1000, (last.cpu - first.cpu) * 1000,
            Int((last.busyTicks ?? 0) &- (first.busyTicks ?? 0)), cpus, Double(cpus) * span.length / ThreadLook.tick, ours * 100
        )
    }
}

/// A flood judged as the app's, in seconds: its longest output-to-shown wait and the main
/// thread's longest hold over it (less one tick), each less the machine's share of it. A gap
/// between committed frames in which no output waited is the producer's silence, not a stall of
/// the view, so it is not judged.
public enum FloodVerdict {
    public static func ours(_ figures: LatencyFigures, mainHold: Span?, tick: Double, own: Stalls, main looks: [ThreadLook])
        -> (shown: Double, held: Double)
    {
        let shown = figures.longestToShown ?? Span(0, 0), hold = mainHold ?? Span(0, 0)
        return (
            max(0, shown.length - MachineShare.of(shown, own: own, main: looks)),
            max(0, hold.length - tick - MachineShare.of(hold, own: own, main: looks))
        )
    }
}
