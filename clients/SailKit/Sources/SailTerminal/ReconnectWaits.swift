/// How a connection to a runner is tried again after a drop: the waits before each attempt, and
/// how long the attempts may go on in all before they stop and wait to be asked. A terminal session
/// and a workspace's files connection ride out the same drops the same way.
public struct ReconnectWaits: Equatable, Sendable {
    /// Half a second, then doubling to 8 s, for up to a minute in all: a network gone for well under a
    /// minute, as on a train or a moved laptop, is ridden out.
    public static let standard = ReconnectWaits(waits: [.milliseconds(500), .seconds(1), .seconds(2), .seconds(4), .seconds(8)], patience: .seconds(60))

    /// The waits before each attempt after a drop; past the last, its cap again.
    public let waits: [Duration]
    /// How long the waits and attempts may add up to before the connection stops trying.
    public let patience: Duration

    public init(waits: [Duration], patience: Duration) {
        precondition(!waits.isEmpty, "a reconnect needs at least one wait")
        self.waits = waits
        self.patience = patience
    }

    /// The wait before the attempt after `failures` failures in a row.
    public func wait(after failures: Int) -> Duration {
        waits[min(max(failures, 0), waits.count - 1)]
    }

    /// Whether, after `failures` failures in a row and `waited` spent on them, the attempts stop.
    public func parks(after failures: Int, waited: Duration) -> Bool {
        failures >= waits.count && waited >= patience
    }
}

/// Where a session reads how long things took (an attempt, an attachment, a ticket's age): the
/// continuous clock, or a test's, which moves only when the test moves it, so what a session decides
/// from elapsed time is the test's to set and never the runner's speed.
package struct SessionClock: Sendable {
    package static let continuous = SessionClock { ContinuousClock.now }
    private let read: @Sendable () -> ContinuousClock.Instant

    package init(_ read: @escaping @Sendable () -> ContinuousClock.Instant) {
        self.read = read
    }

    package var now: ContinuousClock.Instant {
        read()
    }
}
