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
        let held = Result { try Self.descriptors(of: child.pid) }
        try await child.end()
        #expect(try held.get() == [0, 1, 2], "the child holds what it was not given: a listener, a connection, or a descriptor opened outside Descriptors")
        withExtendedLifetime((connection, peer)) {}
    }

    /// A child starts with no signal blocked and every signal at its default action, whatever the
    /// thread that starts it blocks and this process ignores: a host that inherited a blocked or
    /// ignored SIGTERM would never end on `stop()`. Here SIGUSR1 is both, for the instant of the
    /// start, on a thread of its own, and the child sends it to itself.
    @Test func aChildStartsWithEverySignalAtItsDefaultActionAndNoneBlocked() async throws {
        let status = try await onThread { () throws(SpawnRefused) -> Int32 in
            var blocked = sigset_t(), before = sigset_t()
            sigemptyset(&blocked)
            sigaddset(&blocked, SIGUSR1)
            pthread_sigmask(SIG_BLOCK, &blocked, &before)
            let handled = signal(SIGUSR1, SIG_IGN)
            defer {
                signal(SIGUSR1, handled)
                pthread_sigmask(SIG_SETMASK, &before, nil)
            }
            let pid = try TestSpawn.process("/bin/sh", ["-c", "kill -USR1 $$; exit 0"])
            var status: Int32 = 0
            while waitpid(pid, &status, 0) < 0, errno == EINTR {}
            return status
        }
        #expect(status & 0x7F == SIGUSR1, "the child did not end of the SIGUSR1 it sent itself: status \(status)")
    }

    /// A child's standard descriptor is never given one of this process's own standard three: an
    /// earlier one may already have taken its place in the child when a later one is copied.
    @Test func aStandardDescriptorIsNeverGivenAsAnother() {
        for descriptor in Int32(0) ... 2 {
            let refused = #expect(throws: SpawnRefused.self) { try TestSpawn.process("/bin/sh", ["-c", "exit 0"], errors: .descriptor(descriptor)) }
            #expect(refused?.description == "descriptor \(descriptor) is one of the child's own standard three")
        }
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
        let answer = try await start.closing(listener)
        #expect(throws: TerminalError.self, "the listener took a connection after its close") { try answer.get() }
    }

    /// `listener` closed, then connected to, on a thread of its own.
    final class Closing: Sendable {
        private let answered = Mutex<Result<Socket, TerminalError>?>(nil)
        private let ended = DispatchSemaphore(value: 0)

        init(_ listener: HeldListener) {
            Thread { [self] in
                listener.close()
                let answer = Result { () throws(TerminalError) in
                    try Socket.connect(to: .tcp(host: "127.0.0.1", port: listener.port), timeout: Patience.answer)
                }
                answered.withLock { $0 = answer }
                ended.signal()
            }.start()
        }

        /// The connect's answer, waited for at most a glance.
        var answer: Result<Socket, TerminalError>? {
            if let answer = answered.withLock({ $0 }) { return answer }
            _ = ended.wait(timeout: .now() + .milliseconds(Int(glance)))
            return answered.withLock { $0 }
        }
    }

    /// `/bin/sh` held inside `posix_spawn`: its standard output is the FIFO `through`, which this
    /// test reads, and its standard error the FIFO `held`, which nothing opens until ``release()``.
    /// Once it has opened `through` it holds its copy of this process's descriptors, and keeps it
    /// until it has opened `held`.
    final class HeldStart: Sendable {
        private let folder: String
        private let through: Int32
        private let ended = DispatchSemaphore(value: 0)
        private let spawned = Mutex<Result<pid_t, SpawnRefused>?>(nil)
        private let released = Mutex(false)
        private let step = Mutex(Step.unstarted)

        /// Before the start; until it has opened its standard output; then while `listener` is
        /// closed and connected to, as long as the close has not ended or waited; then after `held`
        /// was opened.
        private enum Step {
            case unstarted
            case holding
            case closing(Closing, waits: Int)
            case released(Closing)
        }

        init() throws {
            var template = Array("/tmp/sailkit-XXXXXX".utf8CString)
            let folder = try #require(mkdtemp(&template).map { String(cString: $0) })
            self.folder = folder
            let made = mkfifo(folder + "/through", 0o600) == 0 && mkfifo(folder + "/held", 0o600) == 0
            through = made ? open(folder + "/through", O_RDONLY | O_NONBLOCK | O_CLOEXEC) : -1
            guard through >= 0 else {
                let reason = errno
                HeldStart.clear(folder)
                throw HostError("no FIFO to hold the start with: \(reason)")
            }
        }

        /// `listener` closed while the start holds this process's descriptors, then connected to:
        /// the connect's answer. `held` is opened once the close has ended or waits for the start.
        /// Every step, the start first, runs on one thread of its own, never on the shared pool:
        /// tests there that close a listener or start a process wait for the start meanwhile, and a
        /// step that waited for a pool thread could wait for them for ever.
        func closing(_ listener: HeldListener) async throws -> Result<Socket, TerminalError> {
            try await untilCancelled("the start, its close or the connect after never ended") { [self] () -> Result<Socket, TerminalError>? in
                switch step.withLock({ $0 }) {
                case .unstarted:
                    step.withLock { $0 = .holding }
                    start()
                case .holding:
                    var byte: UInt8 = 0
                    if read(through, &byte, 1) < 0, errno == EAGAIN {
                        let waits = TestSpawn.waits
                        let closing = Closing(listener)
                        step.withLock { $0 = .closing(closing, waits: waits) }
                    } else if ended.wait(timeout: .now() + .milliseconds(Int(glance))) == .success {
                        throw HostError("the start ended before it opened its output: \(String(describing: spawned.withLock { $0 }))")
                    }
                case let .closing(closing, waits):
                    guard closing.answer != nil || TestSpawn.waits != waits else { return nil }
                    release()
                    step.withLock { $0 = .released(closing) }
                case let .released(closing):
                    return closing.answer
                }
                return nil
            }
        }

        private func start() {
            Thread { [self] in
                let result = Result { () throws(SpawnRefused) in
                    try TestSpawn.process("/bin/sh", ["-c", "exit 0"], output: .file(folder + "/through"), errors: .file(folder + "/held"))
                }
                spawned.withLock { $0 = result }
                ended.signal()
            }.start()
        }

        /// Lets the start open `held`, waits for it to end and reaps what it started; once. A start
        /// that cannot be let go would hold every later start and close of this process for ever,
        /// so that ends the process at once.
        func release() {
            guard released.withLock({ (done: inout Bool) -> Bool in
                defer { done = true }
                return !done
            }) else { return }
            switch step.withLock({ $0 }) {
            case .unstarted: break
            default: letGo()
            }
            close(through)
            HeldStart.clear(folder)
        }

        private func letGo() {
            let held = open(folder + "/held", O_RDONLY | O_NONBLOCK | O_CLOEXEC)
            precondition(held >= 0, "the held FIFO cannot be opened, so the start it holds never ends: \(errno)")
            if spawned.withLock({ $0 }) == nil { ended.wait() }
            if case let .success(pid)? = spawned.withLock({ $0 }) {
                var status: Int32 = 0
                while waitpid(pid, &status, 0) < 0, errno == EINTR {}
            }
            close(held)
        }

        private static func clear(_ folder: String) {
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
            do {
                let said = try await untilCancelled("the child never said it runs") { [output = output.read] () -> Int? in
                    guard readable(output) else { return nil }
                    var buffer = [UInt8](repeating: 0, count: 16)
                    return read(output, &buffer, buffer.count)
                }
                try #require(said > 0, "the child ended before it said it runs")
            } catch {
                try await end()
                throw error
            }
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
