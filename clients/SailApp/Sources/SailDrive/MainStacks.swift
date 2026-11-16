import Darwin
import Foundation
import QuartzCore
import SailSurface

/// What the main thread is doing while its heartbeat is late: a thread of its own looks every
/// 5 ms, and once the main heartbeat has not ticked for 30 ms it records the main thread's run
/// state, its CPU time so far and its stack (frame pointers walked while it is suspended for a
/// few microseconds, into a buffer made beforehand, since nothing may allocate then: the main
/// thread could hold the allocator's lock). Symbols are looked up only when a report asks.
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

    static let depth = 48
    private let thread: thread_act_t
    private let low: UInt
    private let high: UInt
    private let buffer = UnsafeMutablePointer<UInt>.allocate(capacity: MainStacks.depth)
    private let lock = NSLock()
    private var samples: [Sample] = []

    /// Made on the main thread, which it then watches through `beat`.
    @MainActor init(watching beat: Heartbeat) {
        thread = mach_thread_self()
        let main = pthread_self()
        high = UInt(bitPattern: pthread_get_stackaddr_np(main))
        low = high - UInt(pthread_get_stacksize_np(main))
        let watcher = Thread { [self] in
            while true {
                Thread.sleep(forTimeInterval: 0.005)
                let now = CACurrentMediaTime()
                if now - beat.lastTick > 0.03 { sample(at: now) }
            }
        }
        watcher.qualityOfService = .userInteractive
        watcher.start()
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

    private static func hostTicks() -> [UInt32] {
        var load = host_cpu_load_info()
        var count = mach_msg_type_number_t(MemoryLayout<host_cpu_load_info>.size / MemoryLayout<integer_t>.size)
        let got = withUnsafeMutablePointer(to: &load) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { host_statistics(host, HOST_CPU_LOAD_INFO, $0, &count) }
        }
        guard got == KERN_SUCCESS else { return [] }
        return [load.cpu_ticks.0, load.cpu_ticks.1, load.cpu_ticks.2, load.cpu_ticks.3]
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
