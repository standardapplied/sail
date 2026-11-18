#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif
import Foundation
import Synchronization

/// Looks at the thread that made it every ``interval``, from a real-time thread of its own, and
/// keeps each look (``ThreadLook``: its run state, its CPU time, this process's CPU time and the
/// machine's CPU ticks), the evidence ``MachineShare`` excuses a wait on, until stopped. Only on
/// Darwin; elsewhere it looks at nothing, so nothing is excused.
public final class Looker: Sendable {
    /// How often it looks.
    public static let interval = 0.005
    /// Its real-time claim (`THREAD_TIME_CONSTRAINT_POLICY`), in seconds: in every ``interval`` it
    /// may run up to `computation`, done within `constraint` of waking, preemptible. Apple's guide
    /// sets a real-time thread's values in these proportions (period HZ/160, computation HZ/3300,
    /// constraint HZ/2200); XNU takes a computation of at least half the constraint, and a thread
    /// that keeps running past its quantum is demoted by its fail-safe. A look costs about 36 µs,
    /// so 250 µs leaves room for the drive's late samples' stack walk.
    public static let computation = 0.000_25
    public static let constraint = 0.000_5

    private let state = Mutex<(looks: [ThreadLook], realTime: Bool?)>(([], nil))
    private let stopped = Atomic<Bool>(false)

    /// Watches the calling thread, its looks timed by `now`.
    public init(now: @escaping @Sendable () -> Double) {
        #if canImport(Darwin)
        let watched = mach_thread_self()
        let looker = Thread { [self] in
            let me = mach_thread_self()
            let realTime = Mach.becomeRealTime(me, period: Self.interval, computation: Self.computation, constraint: Self.constraint)
            mach_port_deallocate(mach_task_self_, me)
            state.withLock { $0.realTime = realTime }
            while !stopped.load(ordering: .acquiring) {
                Thread.sleep(forTimeInterval: Self.interval)
                guard let look = Mach.look(at: watched, time: now()) else { continue }
                state.withLock { $0.looks.append(look) }
            }
            mach_port_deallocate(mach_task_self_, watched)
        }
        looker.qualityOfService = .userInteractive
        looker.start()
        #endif
    }

    public var looks: [ThreadLook] {
        state.withLock { $0.looks }
    }

    /// Whether the kernel took its real-time policy; nil before it started, or where it cannot look.
    public var realTime: Bool? {
        state.withLock { $0.realTime }
    }

    public func stop() {
        stopped.store(true, ordering: .releasing)
    }

    /// The calling thread's CPU time so far, in seconds.
    public static func thisThreadsCPU() -> Double {
        var spent = timespec()
        clock_gettime(CLOCK_THREAD_CPUTIME_ID, &spent)
        return Double(spent.tv_sec) + Double(spent.tv_nsec) / 1e9
    }
}

#if canImport(Darwin)
/// The Mach calls a look is made of.
public enum Mach {
    /// A look at `thread` at `time`; nil when `thread_info` fails: a missing look is missing
    /// evidence, never a runnable one.
    public static func look(at thread: thread_act_t, time: Double) -> ThreadLook? {
        var info = thread_basic_info()
        var infoCount = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let got = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &infoCount) }
        }
        guard got == KERN_SUCCESS else { return nil }
        var usage = rusage()
        let process = getrusage(RUSAGE_SELF, &usage) == 0
            ? Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1e6 : 0
        let ticks = hostTicks()
        return ThreadLook(
            time: time, runnable: info.run_state == TH_STATE_RUNNING, cpu: seconds(info),
            processCPU: process, idleTicks: ticks.count == 4 ? ticks[2] : nil,
            busyTicks: ticks.count == 4 ? ticks[0] &+ ticks[1] &+ ticks[3] : nil
        )
    }

    /// `thread`'s CPU time so far, in seconds, or nil when it cannot be read.
    public static func cpu(of thread: thread_act_t) -> Double? {
        var info = thread_basic_info()
        var infoCount = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let got = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(infoCount)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &infoCount) }
        }
        return got == KERN_SUCCESS ? seconds(info) : nil
    }

    /// The machine's CPU ticks so far: user, system, idle, nice; empty when they cannot be read.
    public static func hostTicks() -> [UInt32] {
        var load = host_cpu_load_info()
        var count = mach_msg_type_number_t(MemoryLayout<host_cpu_load_info>.size / MemoryLayout<integer_t>.size)
        let got = withUnsafeMutablePointer(to: &load) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { host_statistics(host, HOST_CPU_LOAD_INFO, $0, &count) }
        }
        guard got == KERN_SUCCESS else { return [] }
        return [load.cpu_ticks.0, load.cpu_ticks.1, load.cpu_ticks.2, load.cpu_ticks.3]
    }

    /// Makes `thread` real-time (`THREAD_TIME_CONSTRAINT_POLICY`): in every `period` it may run up
    /// to `computation`, done within `constraint` of waking, preemptible, so it keeps its looks
    /// while every core is busy; whether the kernel took the policy.
    public static func becomeRealTime(_ thread: thread_act_t, period: Double, computation: Double, constraint: Double) -> Bool {
        var timebase = mach_timebase_info_data_t()
        _ = mach_timebase_info(&timebase)
        func ticks(_ seconds: Double) -> UInt32 {
            UInt32(seconds * 1e9 * Double(timebase.denom) / Double(timebase.numer))
        }
        var policy = thread_time_constraint_policy(
            period: ticks(period), computation: ticks(computation), constraint: ticks(constraint), preemptible: 1
        )
        let count = mach_msg_type_number_t(MemoryLayout<thread_time_constraint_policy>.size / MemoryLayout<integer_t>.size)
        let set = withUnsafeMutablePointer(to: &policy) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                thread_policy_set(thread, thread_policy_flavor_t(THREAD_TIME_CONSTRAINT_POLICY), $0, count)
            }
        }
        return set == KERN_SUCCESS
    }

    private static let host = mach_host_self()

    private static func seconds(_ info: thread_basic_info) -> Double {
        Double(info.user_time.seconds + info.system_time.seconds) + Double(info.user_time.microseconds + info.system_time.microseconds) / 1e6
    }
}
#endif
