#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import SailKitTestSupport
@testable import SailTerminal
import Synchronization
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
        let made = [
            ("a connection", connection.descriptor), ("an accepted connection", peer.descriptor), ("a unix socket", unix), ("a TCP socket", inet),
            ("a pipe's reading end", pipe.read), ("a pipe's writing end", pipe.write),
        ]
        for (what, descriptor) in made {
            try #require(descriptor >= 0, "\(what) was not made: \(errno)")
            #expect(fcntl(descriptor, F_GETFD) & FD_CLOEXEC != 0, "\(what) is inherited by a process this one starts")
        }
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
        #expect(throws: TerminalError.self, "the stopped listener took a connection") { try Socket.connect(to: host.address, timeout: Patience.answer) }
        try await child.end()
    }

    /// A process being started holds a copy of every descriptor of this one until it closes them
    /// before its exec, so a close in that instant waits for the start to end (``TestSpawn``).
    /// The start is held in that instant by a FIFO it opens as its standard error, which nothing
    /// opens until the close has ended or waits.
    @Test func aListenerClosedWhileAProcessStartsRefusesTheNextConnection() async throws {
        let listener = try HeldListener.loopback()
        let start = try HeldStart()
        defer { start.release() }
        try await start.holding()
        let waits = TestSpawn.waits
        let closing = Closing(listener)
        _ = try await untilCancelled("the close neither ended nor waited for the start") { () -> Bool? in
            guard closing.answer == nil, TestSpawn.waits == waits else { return true }
            sched_yield()
            return nil
        }
        start.release()
        let answer = try await untilCancelled("the close never ended") { () -> Result<Socket, TerminalError>? in
            guard let answer = closing.answer else {
                sched_yield()
                return nil
            }
            return answer
        }
        #expect(throws: TerminalError.self, "the listener took a connection after its close") { try answer.get() }
    }

    /// `listener` closed, then connected to, on a thread of its own.
    final class Closing: Sendable {
        private let answered = Mutex<Result<Socket, TerminalError>?>(nil)

        init(_ listener: HeldListener) {
            Thread { [self] in
                listener.close()
                let answer = Result { () throws(TerminalError) in
                    try Socket.connect(to: .tcp(host: "127.0.0.1", port: listener.port), timeout: Patience.answer)
                }
                answered.withLock { $0 = answer }
            }.start()
        }

        var answer: Result<Socket, TerminalError>? {
            answered.withLock { $0 }
        }
    }

    /// `/bin/sh` held inside `posix_spawn`: its standard output is the FIFO `through`, which this
    /// test reads, and its standard error the FIFO `held`, which nothing opens until ``release()``.
    /// Once it has opened `through` it holds its copy of this process's descriptors, and keeps it
    /// until it has opened `held`.
    final class HeldStart: Sendable {
        private let folder: String
        private let through: Int32
        private let started = DispatchSemaphore(value: 0)
        private let spawned = Mutex<Result<pid_t, SpawnRefused>?>(nil)
        private let released = Mutex(false)

        init() throws {
            var template = Array("/tmp/sailkit-XXXXXX".utf8CString)
            let folder = try #require(mkdtemp(&template).map { String(cString: $0) })
            self.folder = folder
            try #require(mkfifo(folder + "/through", 0o600) == 0 && mkfifo(folder + "/held", 0o600) == 0, "no FIFO: \(errno)")
            through = open(folder + "/through", O_RDONLY | O_NONBLOCK | O_CLOEXEC)
            try #require(through >= 0, "the FIFO cannot be read: \(errno)")
            Thread { [self] in
                let result = Result { () throws(SpawnRefused) in
                    try TestSpawn.process("/bin/sh", ["-c", "exit 0"], output: .file(folder + "/through"), errors: .file(folder + "/held"))
                }
                spawned.withLock { $0 = result }
                started.signal()
            }.start()
        }

        /// Waits until the start has opened its standard output: from then until ``release()`` it
        /// holds a copy of every descriptor of this process.
        func holding() async throws {
            let opened = try await untilCancelled("the start never opened its output") { [self] () -> Bool? in
                var byte: UInt8 = 0
                if read(through, &byte, 1) < 0, errno == EAGAIN { return true }
                if spawned.withLock({ $0 != nil }) { return false }
                sched_yield()
                return nil
            }
            try #require(opened, "the start ended before it opened its output: \(String(describing: spawned.withLock { $0 }))")
        }

        /// Lets the start open `held`, waits for it to end and reaps what it started; once.
        func release() {
            guard released.withLock({ (done: inout Bool) -> Bool in
                defer { done = true }
                return !done
            }) else { return }
            let held = open(folder + "/held", O_RDONLY | O_NONBLOCK | O_CLOEXEC)
            started.wait()
            if case let .success(pid)? = spawned.withLock({ $0 }) {
                var status: Int32 = 0
                while waitpid(pid, &status, 0) < 0, errno == EINTR {}
            }
            [held, through].forEach { close($0) }
            ["/held", "/through", ""].forEach { _ = remove(folder + $0) }
        }
    }

    /// `/bin/sh` waiting on its standard input, a pipe from this test, until the test kills it; it
    /// says it runs on its standard output, another.
    struct Child {
        let pid: pid_t
        private let input: Int32
        private let output: Int32

        /// The child's ends of its two pipes are closed here once it holds them, so the test sees
        /// the end of its output if it exits.
        init() async throws {
            let input = try #require(Descriptors.pipe()), output = try #require(Descriptors.pipe())
            let spawned = Result { () throws(SpawnRefused) in
                try TestSpawn.process("/bin/sh", ["-c", "echo runs; read line"], input: .descriptor(input.read), output: .descriptor(output.write))
            }
            [input.read, output.write].forEach { close($0) }
            guard case let .success(pid) = spawned else {
                [input.write, output.read].forEach { close($0) }
                throw HostError("the child was not started: \(spawned)")
            }
            self.pid = pid
            self.input = input.write
            self.output = output.read
            let said = try await untilCancelled("the child never said it runs") { [output = output.read] () -> Int? in
                guard readable(output) else { return nil }
                var buffer = [UInt8](repeating: 0, count: 16)
                return read(output, &buffer, buffer.count)
            }
            try #require(said > 0, "the child ended before it said it runs")
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
