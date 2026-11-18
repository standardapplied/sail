import Darwin
import Foundation
import QuartzCore
import SailMeasures
import SailSurface
import SailTerminal

/// What the main thread is doing: a thread of its own looks at it every ``interval``, always, and
/// keeps its run state, its CPU time so far and, while it is runnable, our processes' CPU time (the
/// app's, the terminal host's and the host's session shims') and the machine's CPU ticks
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
        /// The machine's CPU ticks so far, idle and busy, as the watcher's look at that moment
        /// read them: nil when the main thread was not runnable, as no look then reads them.
        let idleTicks: UInt32?
        let busyTicks: UInt32?
        /// The process's threads and the main run loop's mode (nil: it ran none): the sample's slow
        /// part, read by the helper's thread once the watcher has taken the stack, never by the
        /// real-time watcher. `fill` names that reading, which stands for every sample taken since
        /// the last, and `filledAt` says when it was read.
        var threads: [ThreadSample] = []
        var mode: String?
        var fill: Int?
        var filledAt: Double?
    }

    /// One of the process's threads: its CPU time so far, current priority and run state.
    struct ThreadSample: Sendable {
        let id: UInt64
        let name: String
        let cpu: Double
        let priority: Int32
        let state: Int32
    }

    /// A look at a session's reader thread (``TerminalSession/readerName``): its run state
    /// (`TH_STATE_RUNNING`, running or waiting for a CPU; `TH_STATE_WAITING`), its CPU time so far
    /// and its priority.
    struct ReaderLook: Sendable {
        let time: Double
        let id: UInt64
        let state: Int32
        let cpu: Double
        let priority: Int32
    }

    /// How often the watcher looks at the main thread, and its real-time claim: ``Looker``'s.
    static let interval = Looker.interval
    /// The most looks kept: two minutes of them.
    static let kept = 24000
    static let computation = Looker.computation
    static let constraint = Looker.constraint
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
    private var readerLooks: [ReaderLook] = []
    /// The reader threads the helper found, their ports freed only under ``readerLock`` once it has
    /// swapped in the next search's, so the watcher never reads a port that was freed.
    private var readerPorts: [(port: thread_act_t, id: UInt64)] = []
    /// The terminal host's pid and its session shims', found by the helper once a second, whose
    /// CPU a runnable look counts as ours.
    private var others: [pid_t] = []
    private let readerLock = NSLock()
    /// Signalled by the watcher when it took a stack sample, so the helper reads its slow part.
    private let helperWake = DispatchSemaphore(value: 0)
    /// How many readings the helper has taken for stack samples, each naming its samples.
    private var fills = 0
    private var spent: (looks: Int, total: Double, longest: Double, modeLongest: Double, tooSlow: Int) = (0, 0, 0, 0, 0)
    /// Whether the kernel took the watcher's real-time policy, when it began, its own CPU time, its
    /// port, and when the helper first found it demoted out of real time, at what priority.
    private var watcher: (realTime: Bool, since: Double, cpu: Double, port: thread_act_t?, demoted: (time: Double, priority: Int32)?) = (false, 0, 0, nil, nil)
    /// The lowest priority a thread runs at under a real-time policy (XNU's `BASEPRI_RTQUEUES`).
    static let realTimePriority: Int32 = 97

    /// Made on the main thread, which it then watches through `beat`; `host` answers the terminal
    /// host's pid, nil while none serves.
    @MainActor init(watching beat: Heartbeat, host: @escaping @Sendable () -> pid_t?) {
        thread = mach_thread_self()
        let main = pthread_self()
        high = UInt(bitPattern: pthread_get_stackaddr_np(main))
        low = high - UInt(pthread_get_stacksize_np(main))
        let watcher = Thread { [self] in
            let me = mach_thread_self()
            let realTime = Mach.becomeRealTime(me, period: MainStacks.interval, computation: MainStacks.computation, constraint: MainStacks.constraint)
            lock.withLock { self.watcher = (realTime, CACurrentMediaTime(), Mach.cpu(of: me) ?? 0, me, nil) }
            while true {
                Thread.sleep(forTimeInterval: MainStacks.interval)
                let now = CACurrentMediaTime()
                let seen = look(at: now)
                lookAtReaders(at: now)
                if now - beat.lastTick > 0.03 { sample(at: now, look: seen) }
                if let used = Mach.cpu(of: me) { lock.withLock { self.watcher.cpu = used } }
            }
        }
        watcher.qualityOfService = .userInteractive
        watcher.start()
        let helper = Thread { [self] in
            var scanned = -Double.infinity
            while true {
                _ = helperWake.wait(timeout: .now() + 1)
                fillSamples()
                let now = CACurrentMediaTime()
                if now - scanned >= 1 {
                    let found = host().map { [$0] + Mach.children(of: $0) } ?? []
                    lock.withLock { others = found }
                    findReaders()
                    checkWatcher()
                    scanned = now
                }
            }
        }
        helper.qualityOfService = .userInitiated
        helper.start()
    }

    /// One look at the main thread, and what it cost the watcher. A look whose `thread_info`
    /// fails is not kept: a missing look is missing evidence, never a runnable one. Nor is one
    /// that took longer than ``MachineShare/longestGap``: what it read spans more time than two
    /// looks may be apart and still prove anything, so it is counted and left out of every proof.
    private func look(at time: Double) -> ThreadLook? {
        let pids = lock.withLock { others }
        guard let look = Mach.look(at: thread, time: time, others: pids) else { return nil }
        let cost = CACurrentMediaTime() - time
        let counts = cost <= MachineShare.longestGap
        lock.withLock {
            if counts {
                dense.append(look)
                if dense.count > Self.kept { dense.removeFirst(Self.kept / 2) }
            } else {
                spent.tooSlow += 1
            }
            spent.looks += 1
            spent.total += cost
            spent.longest = max(spent.longest, cost)
        }
        return counts ? look : nil
    }

    /// What a look cost on average, wall time.
    var averageLook: Double {
        let spent = lock.withLock { self.spent }
        return spent.looks > 0 ? spent.total / Double(spent.looks) : 0
    }

    /// Whether the helper found the watcher demoted out of real time.
    var demoted: Bool {
        lock.withLock { watcher.demoted != nil }
    }

    /// The main run loop's current mode, nil while it runs none: read on the helper's thread only,
    /// for a stack sample. `CFRunLoopCopyCurrentMode` takes that run loop's lock (up to 7 ms on a
    /// CI runner), and no public way reads the mode without it (an observer on the main run loop
    /// sees only the modes it was added to, and a mode outside them is what this looks for); so it
    /// is never read from the real-time watcher, nor on every look, and its longest read is said in
    /// the drive's last line.
    private func mainMode() -> String? {
        let began = CACurrentMediaTime()
        let mode = CFRunLoopCopyCurrentMode(CFRunLoopGetMain()).map { $0.rawValue as String }
        let took = CACurrentMediaTime() - began
        lock.withLock { spent.modeLongest = max(spent.modeLongest, took) }
        return mode
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
        let ran = !watcher.realTime ? "was REFUSED its real-time policy and ran at its QoS"
            : watcher.demoted.map { String(format: "ran real-time until it was found demoted at %.3f s (priority %d)", $0.time, $0.priority) }
            ?? "ran real-time, never found demoted"
        return String(format: "%ld looks every %.0f ms, each %.1f µs on average and %.1f µs at most, %ld looks too slow to count (over %.0f ms); the main run loop's mode, read on the helper's thread for a stack sample, took at most %.1f µs to read; the watcher %@ (period %.0f ms, computation %.2f ms, constraint %.2f ms) used %.1f ms of CPU, %.2f%% of one CPU against its claim of %.0f%%",
                      spent.looks, Self.interval * 1000, spent.looks > 0 ? spent.total / Double(spent.looks) * 1e6 : 0, spent.longest * 1e6,
                      spent.tooSlow, MachineShare.longestGap * 1000, spent.modeLongest * 1e6,
                      ran, Self.interval * 1000, Self.computation * 1000, Self.constraint * 1000, watcher.cpu * 1000, watcherShare * 100, Self.claim * 100)
    }

    private func sample(at time: Double, look: ThreadLook?) {
        var info = thread_basic_info()
        var infoCount = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let gotInfo = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &infoCount) }
        }
        let cpu = gotInfo == KERN_SUCCESS
            ? Double(info.user_time.seconds + info.system_time.seconds) + Double(info.user_time.microseconds + info.system_time.microseconds) / 1e6 : -1
        let count = walk()
        let frames = Array(UnsafeBufferPointer(start: buffer, count: count))
        lock.withLock {
            samples.append(Sample(
                time: time, runState: gotInfo == KERN_SUCCESS ? info.run_state : -1, cpu: cpu, frames: frames,
                idleTicks: look?.idleTicks, busyTicks: look?.busyTicks
            ))
            if samples.count > 20000 { samples.removeFirst(10000) }
        }
        helperWake.signal()
    }

    /// On the helper's thread: the slow part of the stack samples the watcher took since the last
    /// time (the process's threads, the main run loop's mode), read once for all of them.
    private func fillSamples() {
        guard lock.withLock({ samples.last.map { $0.fill == nil } ?? false }) else { return }
        let threads = Self.threads(), mode = mainMode(), read = CACurrentMediaTime()
        lock.withLock {
            fills += 1
            var index = samples.count - 1
            while index >= 0, samples[index].fill == nil {
                samples[index].threads = threads
                samples[index].mode = mode
                samples[index].fill = fills
                samples[index].filledAt = read
                index -= 1
            }
        }
    }

    /// The main run loop's modes as the stack samples taken in `span` read them, or that none was
    /// taken there: the watcher samples only while the main thread has stalled over 30 ms.
    /// Each reading counted once, however many samples it stands for, with how long after the
    /// earliest of them it was read: a mode read late may be one the loop entered after the stall.
    func modes(in span: Span) -> String {
        let taken = lock.withLock { samples.filter { $0.fill != nil && $0.time >= span.start && $0.time <= span.end } }
        let readings = Dictionary(grouping: taken) { $0.fill ?? 0 }.values
        guard !readings.isEmpty else { return "no stack sample in it (the main thread did not stall over 30 ms)" }
        let lags = readings.map { reading in reading.map { ($0.filledAt ?? $0.time) - $0.time }.max() ?? 0 }
        return "in " + MachineShare.modes(readings.map { $0.first?.mode }) + String(format: " (%ld reading%@ of %ld samples, read up to %.1f ms after them)",
                                                                                   readings.count, readings.count == 1 ? "" : "s", taken.count, (lags.max() ?? 0) * 1000)
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
            if let sample = Self.sample(of: thread) { found.append(sample) }
        }
        return found
    }

    /// One thread's name, id, CPU time so far, current priority and run state; nil when the thread
    /// cannot be read (it ended).
    private static func sample(of thread: thread_act_t) -> ThreadSample? {
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
        guard gotInfo == KERN_SUCCESS, gotIdent == KERN_SUCCESS else { return nil }
        let name = withUnsafeBytes(of: info.pth_name) { bytes in String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self) }
        return ThreadSample(
            id: ident.thread_id, name: name, cpu: Double(info.pth_user_time + info.pth_system_time) / 1e9, priority: info.pth_curpri,
            state: info.pth_run_state
        )
    }

    /// Each look, on the watcher's thread, the readers the helper found: their state, CPU time and
    /// priority, kept as they are read, with no array made for them.
    private func lookAtReaders(at time: Double) {
        readerLock.withLock {
            for reader in readerPorts {
                guard let found = Self.sample(of: reader.port) else { continue }
                let look = ReaderLook(time: time, id: reader.id, state: found.state, cpu: found.cpu, priority: found.priority)
                lock.withLock {
                    readerLooks.append(look)
                    if readerLooks.count > Self.kept { readerLooks.removeFirst(Self.kept / 2) }
                }
            }
        }
    }

    /// On the helper's thread, once a second: the process's threads named
    /// ``TerminalSession/readerName``, handed to the watcher in place of the last search's.
    private func findReaders() {
        var found: [(port: thread_act_t, id: UInt64)] = []
        var list: thread_act_array_t?
        var count = mach_msg_type_number_t(0)
        if task_threads(mach_task_self_, &list, &count) == KERN_SUCCESS, let list {
            for index in 0 ..< Int(count) {
                let thread = list[index]
                if let sample = Self.sample(of: thread), sample.name == TerminalSession.readerName {
                    found.append((port: thread, id: sample.id))
                } else {
                    mach_port_deallocate(mach_task_self_, thread)
                }
            }
            vm_deallocate(mach_task_self_, vm_address_t(UInt(bitPattern: list)), vm_size_t(Int(count) * MemoryLayout<thread_act_t>.stride))
        }
        let previous = readerLock.withLock {
            defer { readerPorts = found }
            return readerPorts
        }
        previous.forEach { mach_port_deallocate(mach_task_self_, $0.port) }
    }

    /// On the helper's thread, once a second: whether the watcher still runs real-time. The kernel
    /// demotes a real-time thread that overruns its computation, which the grant at start does not
    /// show; the first time it is found below ``realTimePriority`` is kept for the last line.
    private func checkWatcher() {
        let watcher = lock.withLock { self.watcher }
        guard watcher.realTime, watcher.demoted == nil, let port = watcher.port, let sample = Self.sample(of: port),
              sample.priority < Self.realTimePriority
        else { return }
        let now = CACurrentMediaTime()
        lock.withLock { self.watcher.demoted = (now, sample.priority) }
    }

    /// The reader threads across `span`: for each, its priority, at how many looks it was runnable
    /// (running or waiting for a CPU) and at how many waiting, and the CPU it used: a reader
    /// runnable across a wait and given little CPU was kept from it.
    func readers(across span: Span) -> String {
        let looks = lock.withLock { readerLooks.filter { $0.time >= span.start && $0.time <= span.end } }
        guard !looks.isEmpty else { return "no look at a reader thread in it" }
        return Dictionary(grouping: looks) { $0.id }.sorted { $0.key < $1.key }.map { id, seen in
            let runnable = seen.filter { $0.state == TH_STATE_RUNNING }.count, waiting = seen.filter { $0.state == TH_STATE_WAITING }.count
            let cpu = ((seen.last?.cpu ?? 0) - (seen.first?.cpu ?? 0)) * 1000
            return String(format: "reader #%llu (pri %d): runnable at %ld of %ld looks, waiting at %ld, %.1f ms of CPU across them",
                          id, seen.last?.priority ?? 0, runnable, seen.count, waiting, cpu)
        }.joined(separator: "; ")
    }

    /// Over `span`: how idle the machine's CPUs were, which of the process's threads used the CPU
    /// (by name, the main thread first), and the main thread's priority.
    func summary(of span: Span) -> String {
        let taken = lock.withLock { samples.filter { $0.time >= span.start && $0.time <= span.end && $0.idleTicks != nil && $0.busyTicks != nil } }
        guard let first = taken.first, let last = taken.last, taken.count > 1 else { return "fewer than two samples with the machine's ticks in the span" }
        let idleTicks = Double((last.idleTicks ?? 0) &- (first.idleTicks ?? 0)), busyTicks = Double((last.busyTicks ?? 0) &- (first.busyTicks ?? 0))
        let idle = idleTicks + busyTicks > 0 ? idleTicks / (idleTicks + busyTicks) * 100 : -1
        let readings = taken.filter { $0.fill != nil }
        guard let early = readings.first, let late = readings.last, early.fill != late.fill, let from = early.filledAt, let to = late.filledAt else {
            return String(format: "over %.0f ms between samples: the machine's CPUs %.0f%% idle (ticks busy %.0f, idle %.0f); the process's threads read once in it",
                          (last.time - first.time) * 1000, idle, busyTicks, idleTicks)
        }
        let before = Dictionary(early.threads.map { ($0.id, $0) }, uniquingKeysWith: { one, _ in one })
        let used = late.threads.compactMap { thread in before[thread.id].map { (thread, (thread.cpu - $0.cpu) * 1000) } }.sorted { $0.1 > $1.1 }
        let busiest = used.prefix(8).map { String(format: "%@ %.1f ms (pri %d, state %d)", $0.0.name.isEmpty ? "#\($0.0.id)" : $0.0.name, $0.1, $0.0.priority, $0.0.state) }
        return String(format: "over %.0f ms between samples: the machine's CPUs %.0f%% idle (ticks busy %.0f, idle %.0f); the process's threads by CPU used between two readings %.0f ms apart: ",
                      (last.time - first.time) * 1000, idle, busyTicks, idleTicks, (to - from) * 1000) + busiest.joined(separator: ", ")
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

    /// Every look at the main thread, with our processes' CPU on its runnable looks (every thread
    /// of the app, its session readers included, the terminal host and the host's session shims),
    /// for ``MachineShare``.
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
