/// A stretch of time on the host clock, in seconds.
public struct Span: Equatable, Sendable, Codable {
    public var start: Double
    public var end: Double

    public init(_ start: Double, _ end: Double) {
        self.start = start
        self.end = end
    }

    public var length: Double {
        max(0, end - start)
    }

    func overlap(_ other: Span) -> Double {
        Span(max(start, other.start), min(end, other.end)).length
    }
}

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

    /// How much of `wait` this thread was held too, in seconds: of each gap that overlaps it, the
    /// gap less one tick, never below zero and never more than the overlap. A wait the machine
    /// held every thread through is the machine's; a gap at another moment says nothing of it.
    public func shared(with wait: Span) -> Double {
        gaps.reduce(0) { held, gap in held + min(gap.overlap(wait), max(0, gap.length - tick)) }
    }

    public func net(of wait: Span) -> Double {
        max(0, wait.length - shared(with: wait))
    }
}
