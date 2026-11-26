#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import SailKitTestSupport
@testable import SailTerminal
import Testing

/// A process the client starts holds none of its connections: every descriptor SailKit makes is
/// close-on-exec, and a child is started with only what it is given, so a listener this process
/// stops refuses the next connection however many children live.
@Suite(.timeLimit(.minutes(1))) struct InheritanceTests {
    @Test func everyDescriptorSailKitMakesClosesOnExec() async throws {
        let host = try FakeHost(tcp: ())
        let connection = try Socket.connect(to: host.address, timeout: Patience.answer)
        let peer = try await host.accept()
        let unix = Descriptors.socket(AF_UNIX), inet = Descriptors.socket(AF_INET)
        let pipe = try #require(Descriptors.pipe())
        defer { [unix, inet, pipe.read, pipe.write].forEach { close($0) } }
        for (made, descriptor) in [("an accepted connection", peer.descriptor), ("a unix socket", unix), ("a TCP socket", inet), ("a pipe's reading end", pipe.read), ("a pipe's writing end", pipe.write)] {
            try #require(descriptor >= 0, "\(made) was not made: \(errno)")
            #expect(fcntl(descriptor, F_GETFD) & FD_CLOEXEC != 0, "\(made) is inherited by a process this one starts")
        }
        withExtendedLifetime(connection) {}
    }

    /// The child is looked at from here, through the kernel, once it said it runs: a child that
    /// listed its own descriptors would open one to do it, whose number could be one of ours.
    @Test func aChildHoldsOnlyTheDescriptorsItIsGiven() async throws {
        let host = try FakeHost(tcp: ())
        let connection = try Socket.connect(to: host.address, timeout: Patience.answer)
        let peer = try await host.accept()
        let foreign = open("/dev/null", O_RDONLY)
        defer { close(foreign) }
        try #require(foreign >= 0)
        let child = try await Child()
        let held = try Self.descriptors(of: child.pid)
        try await child.end()
        #expect(held == [0, 1, 2], "the child holds what it was not given: a listener, a connection, or a descriptor opened outside Descriptors")
        withExtendedLifetime((connection, peer)) {}
    }

    @Test func aListenerStoppedWhileAChildLivesRefusesTheNextConnection() async throws {
        let host = try FakeHost(tcp: ())
        let child = try await Child()
        host.stop()
        #expect(throws: TerminalError.self, "a child kept the stopped listener open") {
            try Socket.connect(to: host.address, timeout: Patience.answer)
        }
        try await child.end()
    }

    /// `/bin/sh` waiting on its standard input, a pipe from this test, until the test kills it; it
    /// says it runs on its standard output, another.
    struct Child {
        let pid: pid_t
        private let input: Int32
        private let output: Int32

        init() async throws {
            let input = try #require(Descriptors.pipe()), output = try #require(Descriptors.pipe())
            pid = try Self.spawn(reading: input.read, writing: output.write)
            self.input = input.write
            self.output = output.read
            let said = try await untilCancelled("the child never said it runs") { [output = output.read] () -> Int? in
                guard readable(output) else { return nil }
                var buffer = [UInt8](repeating: 0, count: 16)
                return read(output, &buffer, buffer.count)
            }
            try #require(said > 0, "the child ended before it said it runs")
        }

        /// The child's ends of its two pipes are closed here once it holds them, so the test sees
        /// the end of its output if it exits.
        private static func spawn(reading: Int32, writing: Int32) throws -> pid_t {
            defer {
                close(reading)
                close(writing)
            }
            return try Spawn.process("/bin/sh", ["-c", "echo runs; read line"], input: .descriptor(reading), output: .descriptor(writing))
        }

        /// Kills the child and reaps it: a child that holds a copy of its own input's writing end
        /// would never see that input end.
        func end() async throws {
            kill(pid, SIGKILL)
            let pid = pid
            _ = try await onThread {
                var status: Int32 = 0
                while waitpid(pid, &status, 0) < 0, errno == EINTR {}
            }
            close(input)
            close(output)
        }
    }

    /// The descriptors `pid` holds, as its kernel lists them.
    static func descriptors(of pid: pid_t) throws -> [Int32] {
        #if canImport(Darwin)
        let stride = MemoryLayout<proc_fdinfo>.stride
        let size = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, nil, 0)
        try #require(size > 0, "no descriptors listed for \(pid): \(errno)")
        var listed = [proc_fdinfo](repeating: proc_fdinfo(), count: Int(size) / stride)
        let filled = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, &listed, size)
        try #require(filled > 0, "no descriptors listed for \(pid): \(errno)")
        return listed.prefix(Int(filled) / stride).map(\.proc_fd).sorted()
        #else
        return try FileManager.default.contentsOfDirectory(atPath: "/proc/\(pid)/fd").compactMap { Int32($0) }.sorted()
        #endif
    }
}
