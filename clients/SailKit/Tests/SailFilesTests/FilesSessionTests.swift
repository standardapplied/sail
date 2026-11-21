import Foundation
import SailAPI
@testable import SailFiles
import SailKitTestSupport
@testable import SailTerminal
import SailTerminalWire
import Synchronization
import Testing

/// A workspace's files connection against a played runner door: admitted through a files ticket,
/// every frame handed on, ended in the host's words when the workspace stops, tried again after a
/// drop with a new ticket, and never started when the API says the workspace is stopped.
@Suite(.timeLimit(.minutes(1))) struct FilesSessionTests {
    /// Tickets from `door`, or refusals as the API words them.
    final class Desk: TicketSource {
        let door: FakeRunnerDoor
        private let refusing = Mutex<Refusal?>(nil)
        private let asked = Mutex<[TicketRequest]>([])

        init(_ door: FakeRunnerDoor) {
            self.door = door
        }

        func refuse(_ refusal: Refusal?) {
            refusing.withLock { $0 = refusal }
        }

        var requests: [TicketRequest] {
            asked.withLock { $0 }
        }

        func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
            asked.withLock { $0.append(request) }
            if let refusal = refusing.withLock({ $0 }) { throw .refused(refusal) }
            return door.issue(for: request)
        }
    }

    /// The events of `session` read into a list a test waits on.
    final class Heard: Sendable {
        private let events = Mutex<[FilesSession.Event]>([])
        private let changed = Alarm { try await Task.sleep(for: $0) }

        init(_ session: FilesSession) {
            Task { for await event in session.events { self.heard(event) } }
        }

        private func heard(_ event: FilesSession.Event) {
            events.withLock { $0.append(event) }
            changed.ring()
        }

        var phases: [FilesSession.Phase] {
            events.withLock { $0.compactMap { if case let .phase(phase) = $0 { phase } else { nil } } }
        }

        var admissions: Int {
            events.withLock { $0.count { if case .admitted = $0 { true } else { false } } }
        }

        var frames: [Frame] {
            events.withLock { $0.compactMap { if case let .frame(frame) = $0 { frame } else { nil } } }
        }

        /// Whether `condition` holds within 10 s, checked at each event.
        func until(_ condition: @Sendable () -> Bool) async -> Bool {
            let end = ContinuousClock.now + .seconds(10)
            while !condition() {
                let left = end - ContinuousClock.now
                guard left > .zero else { return false }
                await changed.wait(left)
            }
            return true
        }
    }

    static let waits = ReconnectWaits(waits: [.seconds(1), .seconds(2)], patience: .seconds(3))

    /// A session on `desk` whose waits pass at once.
    static func session(_ desk: Desk) -> FilesSession {
        FilesSession(project: "harbor", workspace: "dock", door: .runner(desk), waits: waits, timeout: 5) { _ in try Task.checkCancellation() }
    }

    static func refusal(_ code: ErrorCode, _ message: String) -> Refusal {
        Refusal(code: .known(code), message: message)
    }

    /// The API's refusal of a files ticket as the server wrote it in the shared golden `name`.
    static func refusal(golden name: String) throws -> Refusal {
        let exchange = try Exchange(name)
        do throws(SailError) {
            _ = try Endpoint.terminalTicket(files: "harbor", workspace: "idle").decode(HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body))
        } catch let .refused(refusal) {
            return refusal
        } catch {
            throw error
        }
        throw NoRefusal(golden: name)
    }

    struct NoRefusal: Error {
        let golden: String
    }

    @Test func admittedItHandsOnTheListingsAndTicks() async throws {
        let door = try FakeRunnerDoor()
        door.setFolder(FileTree.root.shown, [FileTreeTests.file("README.md")])
        let desk = Desk(door)
        let session = Self.session(desk)
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.admissions == 1 }, "never admitted: \(heard.phases)")
        #expect(desk.requests == [.files(project: "harbor", workspace: "dock")])
        session.send([.filesList(id: 1, path: FileTree.root)])
        #expect(await heard.until { heard.frames.contains { if case .filesListing = $0 { true } else { false } } }, "no listing: \(heard.frames)")
        door.tickFiles()
        #expect(await heard.until { heard.frames.contains(.filesTick) }, "the tick was not handed on")
        #expect(heard.phases.prefix(2) == [.connecting, .connected])
    }

    /// A listing's pages are gathered and sorted once, off the main thread, by the connection's own
    /// reader: the tree is handed one whole listing in the order it shows.
    @Test func aListingIsHandedOnWholeAndSorted() async throws {
        let door = try FakeRunnerDoor()
        door.setFolder(FileTree.root.shown, (0 ..< 12_000).reversed().map { FileTreeTests.file("f\($0)") } + [FileTreeTests.folder("src")])
        let session = Self.session(Desk(door))
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.admissions == 1 })
        session.send([.filesList(id: 1, path: FileTree.root)])
        #expect(await heard.until { heard.frames.contains { if case .filesListing = $0 { true } else { false } } })
        let listings = heard.frames.compactMap { if case let .filesListing(listing) = $0 { listing } else { nil } }
        #expect(listings.count == 1 && listings[0].more == false && listings[0].entries.count == 12_001, "\(listings.map { ($0.entries.count, $0.more) })")
        #expect(listings.first?.entries.prefix(3).map(\.name) == ["src", "f0", "f1"], "not in the order the tree shows")
    }

    /// M1: a live connection whose watch falls silent is closed after its quiet limit, and the shared
    /// loop truly reconnects; until then, and after, nothing says it is reconnecting that is not.
    @Test func aSilentConnectionIsClosedAndReconnects() async throws {
        let door = try FakeRunnerDoor()
        let desk = Desk(door)
        let session = FilesSession(project: "harbor", workspace: "dock", door: .runner(desk), waits: Self.waits, timeout: 5, quiet: .milliseconds(1500)) { _ in try Task.checkCancellation() }
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.admissions == 1 })
        #expect(await heard.until { heard.frames.count { $0 == .filesTick } >= 3 } && heard.admissions == 1, "a ticking connection was closed")
        door.tick(false)
        #expect(await heard.until { heard.admissions == 2 }, "the silent connection was never closed and reconnected: \(heard.phases)")
        #expect(desk.requests.count == 2 && heard.phases.contains { if case .reconnecting = $0 { true } else { false } })
    }

    /// #510's endings: a sweep that finds the workspace given to another ends it in its words; one
    /// connection over a cap is said with Try Again and not tried on its own; a handover's
    /// `terminal_unavailable` is a drop, tried again at once.
    @Test func eachEndingIsSaidOrTriedAsItDeserves() async throws {
        let door = try FakeRunnerDoor()
        let desk = Desk(door)
        let session = Self.session(desk)
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.admissions == 1 })
        door.endFiles(saying: "The runner's terminal host is restarting, which ends this connection to dock's files; connect again.", code: "terminal_unavailable")
        #expect(await heard.until { heard.admissions == 2 }, "a handover was not tried again: \(heard.phases)")
        door.endFiles(saying: "Workspace dock of harbor is now ana's.", code: "forbidden")
        #expect(await heard.until { heard.phases.last == .refused(["Workspace dock of harbor is now ana's."]) }, "\(heard.phases)")
        desk.refuse(Self.refusal(.rateLimited, "You have 16 files connections open; close one to open another."))
        session.reconnectNow()
        #expect(await heard.until { heard.phases.last == .limited("You have 16 files connections open; close one to open another.") }, "\(heard.phases)")
        #expect(desk.requests.count == 3, "a capped connection was asked for again on its own: \(desk.requests.count)")
    }

    @Test func aStopEndsItInTheHostsWordsAndNothingTriesAgain() async throws {
        let door = try FakeRunnerDoor()
        let desk = Desk(door)
        let session = Self.session(desk)
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.admissions == 1 })
        door.endFiles(saying: "Workspace dock of harbor is being stopped by ana.", code: "workspace_stopped")
        #expect(await heard.until { heard.phases.last == .stopped("Workspace dock of harbor is being stopped by ana.") }, "\(heard.phases)")
        #expect(desk.requests.count == 1 && heard.admissions == 1, "a stopped workspace was tried again")
    }

    @Test func aDropIsTriedAgainWithANewTicket() async throws {
        let door = try FakeRunnerDoor()
        let desk = Desk(door)
        let session = Self.session(desk)
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.admissions == 1 })
        door.dropFiles()
        #expect(await heard.until { heard.admissions == 2 }, "the drop was not ridden out: \(heard.phases)")
        #expect(desk.requests.count == 2 && heard.phases.contains { if case .reconnecting = $0 { true } else { false } })
    }

    @Test func aStoppedWorkspaceIsNeverStartedByLooking() async throws {
        let door = try FakeRunnerDoor()
        let desk = Desk(door)
        let stopped = try Self.refusal(golden: "refusal-workspace-stopped")
        desk.refuse(stopped)
        let session = Self.session(desk)
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.phases.last == .stopped("Workspace idle of harbor is stopped, and reading its files does not start it.") }, "\(heard.phases)")
        #expect(desk.requests.count == 1, "a stopped workspace was asked for again")
        desk.refuse(nil)
        session.reconnectNow()
        #expect(await heard.until { heard.admissions == 1 }, "Start, then trying again, did not connect")
    }

    @Test func pastItsPatienceItStopsTryingUntilAsked() async throws {
        let door = try FakeRunnerDoor()
        let desk = Desk(door)
        desk.refuse(Self.refusal(.terminalUnavailable, "The runner's terminal host is busy."))
        let session = Self.session(desk)
        let heard = Heard(session)
        session.start()
        defer { session.close() }
        #expect(await heard.until { heard.phases.contains { if case .unreachable = $0 { true } else { false } } }, "\(heard.phases)")
        #expect(desk.requests.count == 3, "not one attempt and one per wait before it stopped: \(desk.requests.count)")
        desk.refuse(nil)
        session.reconnectNow()
        #expect(await heard.until { heard.admissions == 1 })
    }
}
