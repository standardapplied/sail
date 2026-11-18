import AppKit
import Darwin
import Foundation

/// What the system reports of this process now, as it reports it, named as Apple's headers name
/// it: the process's BSD flags (`proc_pidinfo(PROC_PIDTBSDINFO)`, the bits `<mach/task_policy.h>`
/// names, among them `PROC_FLAG_SUPPRESSED`, App Nap's suppression) and its parent; the task's role
/// (`TASK_CATEGORY_POLICY`) and base latency and throughput QoS tiers (`TASK_BASE_QOS_POLICY`);
/// and each thread's scheduled, base and greatest priority (`THREAD_EXTENDED_INFO`).
enum MachineState {
    /// `pbi_flags` bits, as `<mach/task_policy.h>` defines them.
    static let flags: [(bit: UInt32, name: String)] = [
        (0x8000, "PROC_FLAG_DARWINBG"), (0x10000, "PROC_FLAG_EXT_DARWINBG"), (0x100000, "PROC_FLAG_ADAPTIVE"),
        (0x200000, "PROC_FLAG_ADAPTIVE_IMPORTANT"), (0x400000, "PROC_FLAG_IMPORTANCE_DONOR"),
        (0x800000, "PROC_FLAG_SUPPRESSED"), (0x1000000, "PROC_FLAG_APPLICATION"),
    ]

    /// `task_role_t`, as `<mach/task_policy.h>` defines it.
    static let roles: [Int32: String] = [
        -1: "TASK_RENICED", 0: "TASK_UNSPECIFIED", 1: "TASK_FOREGROUND_APPLICATION", 2: "TASK_BACKGROUND_APPLICATION",
        3: "TASK_CONTROL_APPLICATION", 4: "TASK_GRAPHICS_SERVER", 5: "TASK_THROTTLE_APPLICATION", 6: "TASK_NONUI_APPLICATION",
        7: "TASK_DEFAULT_APPLICATION", 8: "TASK_DARWINBG_APPLICATION", 9: "TASK_USER_INIT_APPLICATION",
    ]

    /// The process's BSD flags and its parent, by pid and path.
    static func process() -> String {
        var info = proc_bsdinfo()
        let size = Int32(MemoryLayout<proc_bsdinfo>.size)
        guard proc_pidinfo(getpid(), PROC_PIDTBSDINFO, 0, &info, size) == size else { return "the process's BSD info unread" }
        let set = flags.filter { info.pbi_flags & $0.bit != 0 }.map(\.name)
        let parent = pid_t(info.pbi_ppid)
        return "process flags " + (set.isEmpty ? "none of those named" : set.joined(separator: ", "))
            + ", parent pid \(parent) (\(path(of: parent)))"
    }

    /// The task's role and base QoS tiers, each with the result of asking.
    static func task() -> String {
        var role = [integer_t](repeating: 0, count: 1)
        var roleCount = mach_msg_type_number_t(1)
        var roleDefault = boolean_t(0)
        let roleRead = task_policy_get(mach_task_self_, task_policy_flavor_t(TASK_CATEGORY_POLICY), &role, &roleCount, &roleDefault)
        var tiers = [integer_t](repeating: 0, count: 2)
        var tiersCount = mach_msg_type_number_t(2)
        var tiersDefault = boolean_t(0)
        let tiersRead = task_policy_get(mach_task_self_, task_policy_flavor_t(TASK_BASE_QOS_POLICY), &tiers, &tiersCount, &tiersDefault)
        let roleSaid = roleRead == KERN_SUCCESS ? (roles[role[0]] ?? "role \(role[0])") + (roleDefault != 0 ? " (the default)" : "") : "unread (\(roleRead))"
        let tiersSaid = tiersRead == KERN_SUCCESS
            ? "latency \(tier(tiers[0], marker: 0xFF)), throughput \(tier(tiers[1], marker: 0xFE))" + (tiersDefault != 0 ? " (the defaults)" : "")
            : "unread (\(tiersRead))"
        return "task role \(roleSaid); base QoS tiers \(tiersSaid)"
    }

    /// A latency (marker 0xFF) or throughput (0xFE) QoS tier as `<mach/task_policy.h>` encodes it:
    /// 0 unspecified, else the marker in bits 16 and up and the tier plus one below.
    static func tier(_ value: integer_t, marker: Int32) -> String {
        guard value != 0 else { return "unspecified" }
        return value >> 16 == marker ? "tier \((value & 0xFFFF) - 1)" : String(format: "0x%x", value)
    }

    /// Every thread of the process: its name (or id), scheduled, base and greatest priority, run state.
    static func threads() -> String {
        MainStacks.threads().map { thread in
            "\(thread.name.isEmpty ? "#\(thread.id)" : thread.name) \(thread.priority)/\(thread.basePriority)/\(thread.maxPriority) state \(thread.state)"
        }.joined(separator: ", ")
    }

    private static func path(of pid: pid_t) -> String {
        var buffer = [UInt8](repeating: 0, count: 4096)
        guard proc_pidpath(pid, &buffer, UInt32(buffer.count)) > 0 else { return "path unread" }
        return String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
    }
}

extension Driver {
    /// What the system reports of this process now (``MachineState``), the app's place on screen,
    /// the drive's process activity, the main thread's QoS, the heartbeat's timer and which of the
    /// drive's environment names reached it (never their values).
    var machineState: String {
        let qos = DispatchQoS.QoSClass(rawValue: qos_class_self()).map { "\($0)" } ?? "unknown"
        var timer = "no timer"
        if let kept = mainThread.timer {
            timer = String(format: "a Foundation Timer every %.0f ms on the main run loop in its common modes, tolerance %.4f s", kept.timeInterval * 1000, kept.tolerance)
        }
        let names = ["SAIL_DRIVE_GOLDENS", "SAIL_DRIVE_TICKET", "SAIL_DRIVE_PASSKEY", "SAIL_DATA_DIR", "SAIL_SIGNING"]
        let present = names.filter { ProcessInfo.processInfo.environment[$0] != nil }
        let environment = present.isEmpty ? "none of its names" : present.joined(separator: ", ")
        let activity = holdsActivity ? "holds a process activity (userInitiated, latencyCritical)" : "holds no process activity"
        let policy = NSRunningApplication.current.activationPolicy.rawValue
        let threads = "the main thread's QoS \(qos); threads (scheduled/base/greatest priority): \(MachineState.threads())"
        let parts: [String] = [
            MachineState.process(), MachineState.task(), threads, "\(whereTheWindowIs); activation policy \(policy)",
            "the drive \(activity)", "the heartbeat is \(timer)", "the drive's environment has \(environment)",
        ]
        return parts.joined(separator: "; ")
    }

    /// Begins the three main-thread clocks and the sampler's reading of every look ("start"), or
    /// ends them and says what they saw ("report").
    func clocksStep(_ action: String) -> (Bool, String) {
        switch action {
        case "start":
            mainStacks.readEveryLook(true)
            clocks = MainClocks()
            return (true, "")
        case "report":
            guard let clocks else { return (false, "no clocks were started") }
            clocks.stop()
            self.clocks = nil
            mainStacks.readEveryLook(false)
            return (true, clocks.report(looks: mainStacks.looks))
        default:
            return (false, "no clocks action \(action)")
        }
    }
}
