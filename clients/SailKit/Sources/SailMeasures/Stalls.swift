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

    /// The longest gap, or the longest that overlaps `span`.
    public func longest(in span: Span? = nil) -> Span? {
        gaps.filter { gap in span.map { gap.overlap($0) > 0 } ?? true }.max { $0.length < $1.length }
    }

    public func net(of wait: Span) -> Double {
        max(0, wait.length - shared(with: wait))
    }
}

/// A look at a thread, taken by a sampler: whether it was runnable, its CPU time so far, its
/// whole process's CPU time so far, and the machine's CPU ticks so far, idle and busy (each tick
/// 1/100 s of one CPU; nil where they could not be read).
public struct ThreadLook: Sendable, Equatable {
    public static let tick = 0.01

    public var time: Double
    public var runnable: Bool
    public var cpu: Double
    public var processCPU: Double
    public var idleTicks: UInt32?
    public var busyTicks: UInt32?

    public init(time: Double, runnable: Bool, cpu: Double, processCPU: Double, idleTicks: UInt32?, busyTicks: UInt32?) {
        self.time = time
        self.runnable = runnable
        self.cpu = cpu
        self.processCPU = processCPU
        self.idleTicks = idleTicks
        self.busyTicks = busyTicks
    }
}

/// How much of a wait was the machine's rather than ours.
public enum MachineShare {
    /// The time in `wait` the looked-at thread was runnable and got no CPU while the machine had
    /// none idle, in seconds: each interval between two looks inside the wait in which the thread
    /// was runnable at both, used under a tenth of the interval's CPU, and the machine's idle
    /// ticks did not move, provided its busy ticks moved over the wait (the counters live). A
    /// thread waiting on something of ours (a lock, a drawable) is not runnable, so never counts;
    /// and of the busy CPU that kept it waiting, the share our own process used is ours.
    public static func starved(in wait: Span, looks: [ThreadLook]) -> Double {
        let inside = looks.filter { $0.time >= wait.start && $0.time <= wait.end }.sorted { $0.time < $1.time }
        guard let firstLook = inside.first, let lastLook = inside.last, let first = firstLook.busyTicks, let last = lastLook.busyTicks,
              last &- first > 0
        else { return 0 }
        let busy = Double(last &- first) * ThreadLook.tick
        let ours = min(1, max(0, lastLook.processCPU - firstLook.processCPU) / busy)
        let starved = zip(inside, inside.dropFirst()).reduce(0.0) { held, pair in
            let (from, to) = pair, interval = to.time - from.time
            let idle = from.idleTicks != nil && from.idleTicks == to.idleTicks
            return from.runnable && to.runnable && to.cpu - from.cpu < interval / 10 && idle ? held + interval : held
        }
        return min(starved * (1 - ours), wait.length)
    }

    /// The machine's share of `wait`, in seconds: the larger of the stall of the drive's own
    /// thread over it and the time the main thread was starved in it, never more than the wait.
    public static func of(_ wait: Span, own: Stalls, main looks: [ThreadLook]) -> Double {
        min(wait.length, max(own.shared(with: wait), starved(in: wait, looks: looks)))
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
