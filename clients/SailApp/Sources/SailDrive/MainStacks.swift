import Darwin
import Foundation
import QuartzCore
import SailMeasures
import SailSurface

/// What the main thread is doing: a thread of its own looks at it every ``interval``, always, and
/// keeps its run state, its CPU time so far, this process's CPU time and the machine's CPU ticks
/// (``looks``, the evidence ``MachineShare`` judges a wait on: twenty looks in any 100 ms the
/// watcher itself is not held from); and once the main heartbeat has not ticked for 30 ms it also
/// records the main thread's stack (frame pointers walked while it is suspended for a few
/// microseconds, into a buffer made beforehand, since nothing may allocate then: the main thread
/// could hold the allocator's lock) and every thread's CPU. Symbols are looked up only when a
/// report asks.
final class MainStacks: @unchecked Sendable {
    struct Sample: Sendable {
        let time: Double
        /// `TH_STATE_RUNNING` (1), `STOPPED` (2), `WAITING` (3), `UNINTERRUPTIBLE` (4), `HALTED` (5).
        let runState: Int32
        let cpu: Double
        let frames: [UInt]
        /// The machine's CPU ticks so far: user, system, idle, nice.
        let ticks: [UInt32]
        let threads: [ThreadSample]
    }

    /// One of the process's threads: its CPU time so far, current priority and run state.
    struct ThreadSample: Sendable {
        let id: UInt64
        let name: String
        let cpu: Double
        let priority: Int32
        let state: Int32
    }

    private static let host = mach_host_self()
    /// How often the watcher looks at the main thread.
    static let interval = 0.005
    /// The most looks kept: two minutes of them.
    static let kept = 24000
    /// The watcher's real-time claim (`THREAD_TIME_CONSTRAINT_POLICY`), in seconds: in every
    /// ``interval`` it may run up to `computation`, done within `constraint` of waking, preemptible.
    /// Apple's guide sets a real-time thread's values in these proportions (period HZ/160,
    /// computation HZ/3300, constraint HZ/2200); XNU takes a computation of at least half the
    /// constraint, and a thread that keeps running past its quantum is demoted by its fail-safe.
    /// A look costs about 36 µs, so 250 µs leaves room for the late samples' stack walk.
    static let computation = 0.000_25
    static let constraint = 0.000_5
    /// The most of one CPU the watcher may take: its computation in each period, 5 %.
    static var claim: Double { computation / interval }

    static let depth = 48
    private let thread: thread_act_t
    private let low: UInt
    private let high: UInt
    private let buffer = UnsafeMutablePointer<UInt>.allocate(capacity: MainStacks.depth)
    private let lock = NSLock()
    private var samples: [Sample] = []
    private var dense: [ThreadLook] = []
    private var spent: (looks: Int, total: Double, longest: Double) = (0, 0, 0)
    /// Whether the kernel took the watcher's real-time policy, when it began, and its own CPU time.
    private var watcher: (realTime: Bool, since: Double, cpu: Double) = (false, 0, 0)

    /// Made on the main thread, which it then watches through `beat`.
    @MainActor init(watching beat: Heartbeat) {
        thread = mach_thread_self()
        let main = pthread_self()
        high = UInt(bitPattern: pthread_get_stackaddr_np(main))
        low = high - UInt(pthread_get_stacksize_np(main))
        let watcher = Thread { [self] in
            let me = mach_thread_self()
            let realTime = MainStacks.becomeRealTime(me)
            lock.withLock { self.watcher = (realTime, CACurrentMediaTime(), MainStacks.cpu(of: me) ?? 0) }
            while true {
                Thread.sleep(forTimeInterval: MainStacks.interval)
                let now = CACurrentMediaTime()
                look(at: now)
                if now - beat.lastTick > 0.03 { sample(at: now) }
                if let used = MainStacks.cpu(of: me) { lock.withLock { self.watcher.cpu = used } }
            }
        }
        watcher.qualityOfService = .userInteractive
        watcher.start()
    }

    /// One look at the main thread, and what it cost the watcher. A look whose `thread_info`
    /// fails is not kept: a missing look is missing evidence, never a runnable one.
    private func look(at time: Double) {
        var info = thread_basic_info()
        var infoCount = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let gotInfo = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &infoCount) }
        }
        guard gotInfo == KERN_SUCCESS else { return }
        var usage = rusage()
        let process = getrusage(RUSAGE_SELF, &usage) == 0
            ? Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1e6 : 0
        let ticks = Self.hostTicks()
        let look = ThreadLook(
            time: time, runnable: info.run_state == TH_STATE_RUNNING,
            cpu: Double(info.user_time.seconds + info.system_time.seconds) + Double(info.user_time.microseconds + info.system_time.microseconds) / 1e6,
            processCPU: process, idleTicks: ticks.count == 4 ? ticks[2] : nil,
            busyTicks: ticks.count == 4 ? ticks[0] &+ ticks[1] &+ ticks[3] : nil,
            mode: CFRunLoopCopyCurrentMode(CFRunLoopGetMain()).map { $0.rawValue as String }
        )
        let cost = CACurrentMediaTime() - time
        lock.withLock {
            dense.append(look)
            if dense.count > Self.kept { dense.removeFirst(Self.kept / 2) }
            spent = (spent.looks + 1, spent.total + cost, max(spent.longest, cost))
        }
    }

    /// The watcher made a real-time thread (``computation``, ``constraint`` in every ``interval``),
    /// so it keeps its looks while every core is busy; whether the kernel took the policy.
    private static func becomeRealTime(_ thread: thread_act_t) -> Bool {
        var timebase = mach_timebase_info_data_t()
        _ = mach_timebase_info(&timebase)
        func ticks(_ seconds: Double) -> UInt32 {
            UInt32(seconds * 1e9 * Double(timebase.denom) / Double(timebase.numer))
        }
        var policy = thread_time_constraint_policy(
            period: ticks(interval), computation: ticks(computation), constraint: ticks(constraint), preemptible: 1
        )
        let count = mach_msg_type_number_t(MemoryLayout<thread_time_constraint_policy>.size / MemoryLayout<integer_t>.size)
        let set = withUnsafeMutablePointer(to: &policy) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                thread_policy_set(thread, thread_policy_flavor_t(THREAD_TIME_CONSTRAINT_POLICY), $0, count)
            }
        }
        return set == KERN_SUCCESS
    }

    /// `thread`'s CPU time so far, in seconds, or nil when it cannot be read.
    private static func cpu(of thread: thread_act_t) -> Double? {
        var info = thread_basic_info()
        var infoCount = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let got = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &infoCount) }
        }
        guard got == KERN_SUCCESS else { return nil }
        return Double(info.user_time.seconds + info.system_time.seconds) + Double(info.user_time.microseconds + info.system_time.microseconds) / 1e6
    }

    /// The share of one CPU the watcher has used since it began, which must stay within ``claim``.
    var watcherShare: Double {
        let watcher = lock.withLock { self.watcher }
        let elapsed = CACurrentMediaTime() - watcher.since
        return elapsed > 0 ? watcher.cpu / elapsed : 0
    }

    /// How many looks were taken and what one cost the watcher, and what the watcher took of a CPU.
    var cost: String {
        let spent = lock.withLock { self.spent }
        let watcher = lock.withLock { self.watcher }
        return String(format: "%ld looks every %.0f ms, each %.1f µs on average and %.1f µs at most; the watcher %@ (period %.0f ms, computation %.2f ms, constraint %.2f ms) used %.1f ms of CPU, %.2f%% of one CPU against its claim of %.0f%%",
                      spent.looks, Self.interval * 1000, spent.looks > 0 ? spent.total / Double(spent.looks) * 1e6 : 0, spent.longest * 1e6,
                      watcher.realTime ? "ran real-time" : "was REFUSED its real-time policy and ran at its QoS", Self.interval * 1000,
                      Self.computation * 1000, Self.constraint * 1000, watcher.cpu * 1000, watcherShare * 100, Self.claim * 100)
    }

    private func sample(at time: Double) {
        var info = thread_basic_info()
        var infoCount = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let gotInfo = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &infoCount) }
        }
        let cpu = gotInfo == KERN_SUCCESS
            ? Double(info.user_time.seconds + info.system_time.seconds) + Double(info.user_time.microseconds + info.system_time.microseconds) / 1e6 : -1
        let count = walk()
        let frames = Array(UnsafeBufferPointer(start: buffer, count: count))
        let ticks = Self.hostTicks(), threads = Self.threads()
        lock.withLock {
            samples.append(Sample(time: time, runState: gotInfo == KERN_SUCCESS ? info.run_state : -1, cpu: cpu, frames: frames, ticks: ticks, threads: threads))
            if samples.count > 20000 { samples.removeFirst(10000) }
        }
    }

    /// The machine's CPU ticks so far, summed over its CPUs: user, system, idle, nice. Read per CPU
    /// (`host_processor_info`): `host_statistics` answers an unprivileged process asking more often
    /// than its rate limit allows with its last answer, so looks 5 ms apart read the same ticks, and
    /// a later one a second of them at once.
    private static func hostTicks() -> [UInt32] {
        var cpus = natural_t(0)
        var info: processor_info_array_t?
        var count = mach_msg_type_number_t(0)
        guard host_processor_info(host, PROCESSOR_CPU_LOAD_INFO, &cpus, &info, &count) == KERN_SUCCESS, let info else { return [] }
        defer { vm_deallocate(mach_task_self_, vm_address_t(UInt(bitPattern: info)), vm_size_t(Int(count) * MemoryLayout<integer_t>.stride)) }
        let states = Int(CPU_STATE_MAX)
        var ticks = [UInt32](repeating: 0, count: states)
        for cpu in 0 ..< Int(cpus) {
            for state in 0 ..< states {
                ticks[state] &+= UInt32(bitPattern: info[cpu * states + state])
            }
        }
        return [ticks[Int(CPU_STATE_USER)], ticks[Int(CPU_STATE_SYSTEM)], ticks[Int(CPU_STATE_IDLE)], ticks[Int(CPU_STATE_NICE)]]
    }

    private static func threads() -> [ThreadSample] {
        var list: thread_act_array_t?
        var count = mach_msg_type_number_t(0)
        guard task_threads(mach_task_self_, &list, &count) == KERN_SUCCESS, let list else { return [] }
        defer { vm_deallocate(mach_task_self_, vm_address_t(UInt(bitPattern: list)), vm_size_t(Int(count) * MemoryLayout<thread_act_t>.stride)) }
        var found: [ThreadSample] = []
        for index in 0 ..< Int(count) {
            let thread = list[index]
            defer { mach_port_deallocate(mach_task_self_, thread) }
            var info = thread_extended_info()
            var infoCount = mach_msg_type_number_t(MemoryLayout<thread_extended_info>.size / MemoryLayout<integer_t>.size)
            var ident = thread_identifier_info()
            var identCount = mach_msg_type_number_t(MemoryLayout<thread_identifier_info>.size / MemoryLayout<integer_t>.size)
            let gotInfo = withUnsafeMutablePointer(to: &info) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) { thread_info(thread, thread_flavor_t(THREAD_EXTENDED_INFO), $0, &infoCount) }
            }
            let gotIdent = withUnsafeMutablePointer(to: &ident) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(identCount)) { thread_info(thread, thread_flavor_t(THREAD_IDENTIFIER_INFO), $0, &identCount) }
            }
            guard gotInfo == KERN_SUCCESS, gotIdent == KERN_SUCCESS else { continue }
            let name = withUnsafeBytes(of: info.pth_name) { bytes in String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self) }
            found.append(ThreadSample(
                id: ident.thread_id, name: name, cpu: Double(info.pth_user_time + info.pth_system_time) / 1e9, priority: info.pth_curpri,
                state: info.pth_run_state
            ))
        }
        return found
    }

    /// Over `span`: how idle the machine's CPUs were, which of the process's threads used the CPU
    /// (by name, the main thread first), and the main thread's priority.
    func summary(of span: Span) -> String {
        let taken = lock.withLock { samples.filter { $0.time >= span.start && $0.time <= span.end && !$0.ticks.isEmpty } }
        guard let first = taken.first, let last = taken.last, taken.count > 1 else { return "fewer than two samples in the span" }
        let delta = zip(last.ticks, first.ticks).map { Double($0 &- $1) }
        let total = delta.reduce(0, +)
        let idle = total > 0 ? delta[2] / total * 100 : -1
        let before = Dictionary(first.threads.map { ($0.id, $0) }, uniquingKeysWith: { one, _ in one })
        let used = last.threads.compactMap { thread in before[thread.id].map { (thread, (thread.cpu - $0.cpu) * 1000) } }.sorted { $0.1 > $1.1 }
        let busiest = used.prefix(8).map { String(format: "%@ %.1f ms (pri %d, state %d)", $0.0.name.isEmpty ? "#\($0.0.id)" : $0.0.name, $0.1, $0.0.priority, $0.0.state) }
        return String(format: "over %.0f ms between samples: the machine's CPUs %.0f%% idle (ticks user %.0f, system %.0f, idle %.0f); the process's threads by CPU used: ",
                      (last.time - first.time) * 1000, idle, delta[0], delta[1], delta[2]) + busiest.joined(separator: ", ")
    }

    /// The main thread's return addresses, innermost first, into `buffer`; nothing allocated
    /// while it is suspended.
    private func walk() -> Int {
        #if arch(arm64)
        guard thread_suspend(thread) == KERN_SUCCESS else { return 0 }
        defer { thread_resume(thread) }
        var state = (UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0),
                     UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0),
                     UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0), UInt64(0),
                     UInt64(0), UInt64(0), UInt64(0), UInt64(0))
        var stateCount = mach_msg_type_number_t(68)
        let got = withUnsafeMutablePointer(to: &state) {
            $0.withMemoryRebound(to: natural_t.self, capacity: 68) { thread_get_state(thread, thread_state_flavor_t(ARM_THREAD_STATE64), $0, &stateCount) }
        }
        guard got == KERN_SUCCESS else { return 0 }
        let mask: UInt = 0x0000_7FFF_FFFF_FFFF
        buffer[0] = UInt(state.32) & mask
        buffer[1] = UInt(state.30) & mask
        var count = 2
        var fp = UInt(state.29)
        while count < Self.depth, fp >= low, fp + 16 <= high, fp % 8 == 0 {
            guard let frame = UnsafePointer<UInt>(bitPattern: fp) else { break }
            let next = frame[0], ret = frame[1] & mask
            guard ret != 0 else { break }
            buffer[count] = ret
            count += 1
            guard next > fp else { break }
            fp = next
        }
        return count
        #else
        return 0
        #endif
    }

    /// Every look at the main thread, with this process's CPU (every thread of the app, its session
    /// readers included), for ``MachineShare``. The terminal host and its session shims are other
    /// processes; counting them as ours would need their CPU time (`proc_pid_rusage` on each pid
    /// the host started), which the sampler does not read.
    var looks: [ThreadLook] {
        lock.withLock { dense }
    }

    /// The samples taken in `span`, each as its run state, the main thread's CPU time since the
    /// first of them, and its innermost symbols.
    /// The summary of `span` and three of its samples: the first, the middle and the last.
    func told(_ span: Span) -> [String] {
        let lines = described(in: span)
        let picked = lines.count > 3 ? [lines[0], lines[lines.count / 2], lines[lines.count - 1]] : lines
        return [summary(of: span)] + picked
    }

    func described(in span: Span, frames shown: Int = 14) -> [String] {
        let taken = lock.withLock { samples.filter { $0.time >= span.start && $0.time <= span.end } }
        guard let first = taken.first else { return ["no sample of the main thread in \(String(format: "%.3f", span.start))–\(String(format: "%.3f", span.end))"] }
        return taken.map { sample in
            let names = sample.frames.prefix(shown).map(Self.symbol).joined(separator: " < ")
            return String(format: "+%.0f ms state %d cpu +%.1f ms: ", (sample.time - span.start) * 1000, sample.runState, (sample.cpu - first.cpu) * 1000)
                + names
        }
    }

    private static func symbol(_ address: UInt) -> String {
        var info = Dl_info()
        guard let pointer = UnsafeRawPointer(bitPattern: address), dladdr(pointer, &info) != 0 else { return String(format: "0x%lx", address) }
        let image = info.dli_fname.map { String(cString: $0).split(separator: "/").last.map(String.init) ?? "" } ?? "?"
        let name = info.dli_sname.map { String(cString: $0) } ?? "?"
        return "\(image)`\(name.prefix(90))"
    }
}
