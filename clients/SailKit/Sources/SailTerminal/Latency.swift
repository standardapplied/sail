/// Where a key's time went, on a monotonic clock: handed in to its bytes written, to the first
/// `Output` that names its input as applied, to the screen updated with that output. Only times
/// and input numbers are kept, never bytes.
public struct LatencyReport: Equatable, Sendable, Codable {
    public var written: Percentiles
    public var echoed: Percentiles
    public var screen: Percentiles

    public init(written: Percentiles, echoed: Percentiles, screen: Percentiles) {
        self.written = written
        self.echoed = echoed
        self.screen = screen
    }
}

/// p50, p95 and the maximum of some samples, in milliseconds; all zero for none.
public struct Percentiles: Equatable, Sendable, Codable {
    public var count: Int
    public var p50: Double
    public var p95: Double
    public var max: Double

    public init(_ samples: [Duration]) {
        let sorted = samples.map(\.milliseconds).sorted()
        count = sorted.count
        p50 = Self.rank(0.50, of: sorted)
        p95 = Self.rank(0.95, of: sorted)
        max = sorted.last ?? 0
    }

    /// The nearest-rank percentile.
    private static func rank(_ fraction: Double, of sorted: [Double]) -> Double {
        guard !sorted.isEmpty else { return 0 }
        let index = Int((fraction * Double(sorted.count)).rounded(.up)) - 1
        return sorted[Swift.max(0, Swift.min(sorted.count - 1, index))]
    }
}

/// The marks of the last ``capacity`` keys, and of at most ``capacity`` keys not yet echoed.
struct LatencyRecorder {
    static let capacity = 4096

    private struct Pending {
        let sequence: Int64
        let handedIn: ContinuousClock.Instant
        let written: ContinuousClock.Instant
        var echoed: ContinuousClock.Instant?
    }

    private var pending: [Pending] = []
    private var written: [Duration] = []
    private var echoed: [Duration] = []
    private var screen: [Duration] = []

    /// A key handed in at `handedIn` went out as input `sequence`, written at `written`.
    mutating func sent(_ sequence: Int64, handedIn: ContinuousClock.Instant, written: ContinuousClock.Instant) {
        if pending.count == Self.capacity { pending.removeFirst() }
        pending.append(Pending(sequence: sequence, handedIn: handedIn, written: written))
    }

    /// Output naming input `applied` as the last the host applied arrived at `now`.
    mutating func echoed(through applied: Int64, at now: ContinuousClock.Instant) {
        for index in pending.indices where pending[index].sequence <= applied && pending[index].echoed == nil {
            pending[index].echoed = now
        }
    }

    /// The screen took that output at `now`: every key it echoed is complete.
    mutating func screenUpdated(at now: ContinuousClock.Instant) {
        for mark in pending {
            guard let echo = mark.echoed else { continue }
            Self.keep(mark.written - mark.handedIn, in: &written)
            Self.keep(echo - mark.handedIn, in: &echoed)
            Self.keep(now - mark.handedIn, in: &screen)
        }
        pending.removeAll { $0.echoed != nil }
    }

    var report: LatencyReport {
        LatencyReport(written: Percentiles(written), echoed: Percentiles(echoed), screen: Percentiles(screen))
    }

    private static func keep(_ sample: Duration, in samples: inout [Duration]) {
        if samples.count == capacity { samples.removeFirst() }
        samples.append(sample)
    }
}

extension Duration {
    package var milliseconds: Double {
        let (seconds, attoseconds) = components
        return Double(seconds) * 1000 + Double(attoseconds) / 1e15
    }
}
