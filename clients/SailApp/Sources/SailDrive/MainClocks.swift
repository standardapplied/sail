import Darwin
import Foundation
import QuartzCore
import SailMeasures
import SailSurface
import Synchronization

/// Three clocks on the main thread, each due every 10 ms by a different wake, side by side: a
/// Foundation `Timer` on the main run loop in its common modes (the heartbeat's kind), a
/// `DispatchQueue.main.asyncAfter` chain (a dispatch timer onto the main queue), and a
/// `DispatchQueue.main.async` posted every 10 ms by a real-time thread of its own (a wake with no
/// timer on the main side, as a view's wake is). Late together, the main thread got no CPU; the
/// timers late and the posted wakes on time, the timers' path was late.
final class MainClocks: @unchecked Sendable {
    static let interval = 0.01
    /// A gap this long, three intervals, is said one by one.
    static let said = 0.03

    enum Clock: CaseIterable {
        case runLoopTimer, asyncAfter, posted

        var name: String {
            switch self {
            case .runLoopTimer: "the run loop's Timer"
            case .asyncAfter: "the main.asyncAfter chain"
            case .posted: "the main.async posted from a real-time thread"
            }
        }
    }

    private let lock = NSLock()
    private var ticks: [Clock: (last: Double, gaps: [Span], count: Int)] = [:]
    private var waits: [Span] = []
    private let running = Atomic<Bool>(true)
    private var timer: Timer?

    @MainActor init() {
        let now = CACurrentMediaTime()
        for clock in Clock.allCases {
            ticks[clock] = (last: now, gaps: [], count: 0)
        }
        let timer = Timer(timeInterval: Self.interval, repeats: true) { [weak self] _ in self?.tick(.runLoopTimer) }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        chain()
        let poster = Thread { [self] in
            let me = mach_thread_self()
            _ = Mach.becomeRealTime(me, period: Self.interval, computation: Looker.computation, constraint: Looker.constraint)
            mach_port_deallocate(mach_task_self_, me)
            while running.load(ordering: .acquiring) {
                Thread.sleep(forTimeInterval: Self.interval)
                let posted = CACurrentMediaTime()
                DispatchQueue.main.async { self.ran(posted: posted) }
            }
        }
        poster.name = "sh.standardapplied.sail.drive.clock"
        poster.start()
    }

    @MainActor func stop() {
        running.store(false, ordering: .releasing)
        timer?.invalidate()
        timer = nil
    }

    private func chain() {
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.interval) { [self] in
            tick(.asyncAfter)
            if running.load(ordering: .acquiring) { chain() }
        }
    }

    private func ran(posted: Double) {
        let time = CACurrentMediaTime()
        lock.withLock { waits.append(Span(posted, time)) }
        tick(.posted, at: time)
    }

    private func tick(_ clock: Clock, at time: Double = CACurrentMediaTime()) {
        lock.withLock {
            guard var seen = ticks[clock] else { return }
            if time - seen.last > Self.interval { seen.gaps.append(Span(seen.last, time)) }
            seen.last = time
            seen.count += 1
            ticks[clock] = seen
        }
    }

    /// Each clock's ticks and longest gap, the posted wakes' wait from posting to running, and
    /// each gap of the run loop's Timer over ``said``: what the other two clocks and the main
    /// thread's looks (state, CPU, the machine's ticks) saw across it.
    func report(looks: [ThreadLook]) -> String {
        let (ticks, waits) = lock.withLock { (self.ticks, self.waits) }
        let clocks = Clock.allCases.map { clock -> String in
            let count = ticks[clock]?.count ?? 0
            let gaps = ticks[clock]?.gaps ?? []
            var longest = "no gap"
            if let gap = gaps.max(by: { $0.length < $1.length }) {
                longest = String(format: "longest gap %.1f ms at %.3f–%.3f", gap.length * 1000, gap.start, gap.end)
            }
            let over = gaps.filter { $0.length > Self.said }.count
            return "\(clock.name): \(count) ticks, \(longest), \(over) gaps over \(Int(Self.said * 1000)) ms"
        }
        let sorted = waits.map(\.length).sorted()
        let posted = sorted.isEmpty ? "no posted wake ran"
            : String(format: "posted wakes ran %.1f ms after posting at p50, %.1f at p95, %.1f at most", sorted[sorted.count / 2] * 1000,
                     sorted[min(sorted.count - 1, sorted.count * 95 / 100)] * 1000, (sorted.last ?? 0) * 1000)
        let late = (ticks[.runLoopTimer]?.gaps ?? []).filter { $0.length > Self.said }.sorted { $0.length > $1.length }.prefix(8)
        let across = late.map { gap in
            let after = (ticks[.asyncAfter]?.gaps ?? []).map { $0.overlap(gap) }.max() ?? 0
            let wait = waits.filter { $0.overlap(gap) > 0 }.map(\.length).max() ?? 0
            return String(format: "the Timer's gap %.3f–%.3f (%.1f ms): the asyncAfter chain's gap over it %.1f ms, the longest posted wake in it %.1f ms; ",
                          gap.start, gap.end, gap.length * 1000, after * 1000, wait * 1000) + Self.mainThread(across: gap, looks: looks)
        }
        return (clocks + [posted] + across).joined(separator: "; ")
    }

    /// The main thread's looks across `span`: at how many it was runnable, its CPU over them, and
    /// the machine's idle and busy ticks between the first and last that read them.
    static func mainThread(across span: Span, looks: [ThreadLook]) -> String {
        let inside = looks.filter { $0.time >= span.start && $0.time <= span.end }
        guard let first = inside.first, let last = inside.last else { return "no look at the main thread in it" }
        let read = inside.filter { $0.idleTicks != nil && $0.busyTicks != nil }
        var machine = "the machine's ticks unread"
        if let idleFrom = read.first?.idleTicks, let idleTo = read.last?.idleTicks, let busyFrom = read.first?.busyTicks, let busyTo = read.last?.busyTicks {
            machine = String(format: "the machine's idle ticks +%u and busy ticks +%u", idleTo &- idleFrom, busyTo &- busyFrom)
        }
        return String(format: "the main thread runnable at %ld of %ld looks, its CPU +%.1f ms; ", inside.filter(\.runnable).count, inside.count,
                      (last.cpu - first.cpu) * 1000) + machine
    }
}
