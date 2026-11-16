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
    }

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
        let main = pthread_main_thread_np()
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
        lock.withLock {
            samples.append(Sample(time: time, runState: gotInfo == KERN_SUCCESS ? info.run_state : -1, cpu: cpu, frames: frames))
            if samples.count > 20000 { samples.removeFirst(10000) }
        }
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
