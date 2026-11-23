import AppKit
import Darwin
import Foundation

/// What the system reports of this process now, as it reports it, named as Apple's headers name
/// it: the process's BSD flags (`proc_pidinfo(PROC_PIDTBSDINFO)`, the bits `<mach/task_policy.h>`
/// names, among them `PROC_FLAG_SUPPRESSED`, App Nap's suppression) and its parent; the task's role
/// (`TASK_CATEGORY_POLICY`) and base latency and throughput QoS tiers (`TASK_BASE_QOS_POLICY`; the
/// effective tiers a clamp sets are not readable without privilege); the main thread's QoS; and
/// each thread's scheduled, base and greatest priority (`THREAD_EXTENDED_INFO`).
struct MachineState {
    /// `pbi_flags` bits, as `<mach/task_policy.h>` defines them.
    static let flagNames: [(bit: UInt32, name: String)] = [
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
    static let foregroundApplication: Int32 = 1
    static let launchd = "/sbin/launchd"

    let flags: [String]
    let parent: String
    let role: Int32?
    let tiers: String
    let mainQoS: DispatchQoS.QoSClass?

    /// Read now, on the main thread.
    @MainActor static func read() -> MachineState {
        var info = proc_bsdinfo()
        let size = Int32(MemoryLayout<proc_bsdinfo>.size)
        let gotInfo = proc_pidinfo(getpid(), PROC_PIDTBSDINFO, 0, &info, size) == size
        var role = [integer_t](repeating: 0, count: 1)
        var roleCount = mach_msg_type_number_t(1)
        var roleDefault = boolean_t(0)
        let roleResult = task_policy_get(mach_task_self_, task_policy_flavor_t(TASK_CATEGORY_POLICY), &role, &roleCount, &roleDefault)
        var tiers = [integer_t](repeating: 0, count: 2)
        var tiersCount = mach_msg_type_number_t(2)
        var tiersDefault = boolean_t(0)
        let tiersRead = task_policy_get(mach_task_self_, task_policy_flavor_t(TASK_BASE_QOS_POLICY), &tiers, &tiersCount, &tiersDefault)
        let flags: [String] = gotInfo ? flagNames.filter { info.pbi_flags & $0.bit != 0 }.map(\.name) : ["unread"]
        let parent: String = gotInfo ? path(of: pid_t(info.pbi_ppid)) : "unread"
        let roleRead: Int32? = roleResult == KERN_SUCCESS ? role[0] : nil
        var tiersSaid = "unread (\(tiersRead))"
        if tiersRead == KERN_SUCCESS {
            tiersSaid = "latency \(tier(tiers[0], marker: 0xFF)), throughput \(tier(tiers[1], marker: 0xFE))"
        }
        return MachineState(flags: flags, parent: parent, role: roleRead, tiers: tiersSaid, mainQoS: DispatchQoS.QoSClass(rawValue: qos_class_self()))
    }

    /// What is not as a person's launch of Sail makes it, each said; empty when all is.
    var departures: [String] {
        var said: [String] = []
        if role != Self.foregroundApplication { said.append("its task role is not TASK_FOREGROUND_APPLICATION") }
        if parent != Self.launchd { said.append("its parent is not \(Self.launchd)") }
        if mainQoS != .userInteractive { said.append("its main thread is not user-interactive") }
        return said
    }

    var told: String {
        let roleSaid = role.map { Self.roles[$0] ?? "role \($0)" } ?? "unread"
        let qos = mainQoS.map { "\($0)" } ?? "unknown"
        return "process flags \(flags.isEmpty ? "none of those named" : flags.joined(separator: ", ")), parent \(parent); "
            + "task role \(roleSaid); base QoS tiers \(tiers); the main thread's QoS \(qos); "
            + "threads (scheduled/base/greatest priority): \(Self.threads())"
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
        guard proc_pidpath(pid, &buffer, UInt32(buffer.count)) > 0 else { return "pid \(pid), path unread" }
        return String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
    }
}

extension Driver {
    /// What the system reports of this process now (``MachineState``), the app's place on screen and
    /// which of the drive's environment names reached it (never their values); failing unless the
    /// app was launched as a person's Sail is, so the drive can never slip back to measuring an app
    /// exec'd from a shell, which no person runs.
    var machineState: (Bool, String) {
        let state = MachineState.read()
        let names = ["SAIL_DRIVE_GOLDENS", "SAIL_DRIVE_TICKET", "SAIL_DRIVE_TICKET_FILE", "SAIL_DRIVE_PASSKEY", "SAIL_DRIVE_PRIVATE", "SAIL_DATA_DIR", "SAIL_SIGNING"]
        let present = names.filter { ProcessInfo.processInfo.environment[$0] != nil }
        let environment = present.isEmpty ? "none of its names" : present.joined(separator: ", ")
        let departures = state.departures
        let verdict = departures.isEmpty ? "" : "FAILED: not launched as a person's Sail is: \(departures.joined(separator: ", ")); "
        return (departures.isEmpty, "\(verdict)\(state.told); \(whereTheWindowIs); the drive's environment has \(environment)")
    }
}
