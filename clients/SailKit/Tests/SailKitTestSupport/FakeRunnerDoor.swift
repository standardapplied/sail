#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif
import Foundation
import SailAPI
import SailTerminal
import SailTerminalWire
import Synchronization

/// A runner's terminal door played by a test or the drive, on a loopback port, refusing what the
/// real door refuses, in its words: it welcomes a ticket it issued, once, and nothing else; a
/// connection does only what its ticket covers (an open ticket makes one session in its workspace
/// and attaches to it, an attach ticket attaches to its session), and the door hangs up on any
/// other frame; it ends a session only for the connection attached to it as its writer. A refused
/// attach is answered and the connection goes on. A test can have someone else hold a session's
/// write token, and hold a `Create`'s answer while the host makes it. Its live sessions are its
/// session list.
///
/// Made for a `person`, it says who holds a session's keyboard as the real host does: the attach's
/// own `WriterChanged` naming them when they took it, someone else's name when that person holds it,
/// and a `WriterChanged` to everyone attached when ``takeKeyboard(_:by:)`` or a `TakeWrite` moves it.
/// It lists what it holds and what it ended for that person, as the API does (``listing``).
///
/// Made `thisMac`, it plays this Mac's own host instead, as the real one admits its person: a
/// blank token is welcomed, a connection does anything, a Kill ends the session it names or is
/// refused as no session, and ListSessions lists the live ones. A test can have it refuse or
/// hold its Kills.
public final class FakeRunnerDoor: Sendable {
    public let port: UInt16
    private let listener: Int32
    private let thisMac: Bool
    private let person: String?
    private let state = Mutex(State())
    private let creates = Mutex(Creates())
    private let killing = Mutex(Killing())
    private let files = Mutex(Files())
    /// One frame written at a time on a connection, whichever thread writes it: a watch's tick never
    /// lands inside a listing.
    private let writing = Mutex<[Int32: NSLock]>([:])

    private struct State {
        var issued = 0
        var unused: [String: TicketRequest] = [:]
        var used: Set<String> = []
        var live: [String] = []
        /// Each live session's writer: the connection's number, or 0 for a client not this door's.
        var writers: [String: Int] = [:]
        /// Who the client not this door's is, while it holds a session's keyboard.
        var holders: [String: String] = [:]
        /// A key typed on a free keyboard takes it, as the opener's does; false models someone else.
        var typingTakes = true
        var connections = 0
        var kills: [String] = []
        /// Every session it made, oldest first, and the reasons of those it ended on its own.
        var made: [String] = []
        var ended: [String: String] = [:]
        /// The connections attached to each session.
        var attached: [String: [Int32]] = [:]
    }

    /// The `Create`s the door holds before it makes their sessions.
    private struct Creates {
        var holding = false
        var losing = false
        var received = 0
        var held: [DispatchSemaphore] = []
    }

    /// A files ticket's workspace, played: its folders by path, each with its entries and version, and
    /// what each files connection watches.
    private struct Files {
        var folders: [FileName: Folder] = [:]
        /// Each file's bytes and version.
        var contents: [FileName: (bytes: [UInt8], version: String)] = [:]
        /// Every frame a files connection sent, in order: what proves nothing was written unasked.
        var received: [Frame] = []
        /// Each save under way: its file, base, and the bytes come so far.
        var saves: [Int32: (path: FileName, base: String, bytes: [UInt8])] = [:]
        /// The next save is refused so.
        var refuseSave: (kind: FileRefusal, message: String)?
        var refusals: [FileName: (kind: FileRefusal, message: String)] = [:]
        var versions = 0
        var watched: [Int32: [WatchedPath]] = [:]
        var holding = false
        var held: [DispatchSemaphore] = []
        var ticking = true
        var listed: [String] = []
        var connections: [Int32] = []
        var admitted = 0
    }

    struct Folder {
        var entries: [FileEntry]
        var version: String
        var truncated: Bool
    }

    /// What this Mac's host does with the Kills it receives.
    private struct Killing {
        var refusal: String?
        var holding = false
        var held: [DispatchSemaphore] = []
    }

    public init(thisMac: Bool = false, person: String? = nil) throws {
        self.thisMac = thisMac
        self.person = person
        let socket = Self.socket()
        guard socket >= 0 else { throw DoorError("no socket for the door") }
        var address = sockaddr_in()
        #if canImport(Darwin)
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        #endif
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socket, $0, size) } }
        guard bound == 0, listen(socket, 16) == 0 else {
            close(socket)
            throw DoorError("the door could not listen")
        }
        var named = size
        _ = withUnsafeMutablePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socket, $0, &named) } }
        port = UInt16(bigEndian: address.sin_port)
        listener = socket
        Thread { [self] in serve() }.start()
    }

    /// Where the door listens, as a ticket names it.
    public var address: String {
        "127.0.0.1:\(port)"
    }

    /// A ticket for one connection doing `request`, as the API hands one out for the real door.
    public func issue(for request: TicketRequest) -> TerminalTicket {
        let ticket = state.withLock { state in
            state.issued += 1
            let ticket = "tkt_door_\(state.issued)"
            state.unused[ticket] = request
            return ticket
        }
        return TerminalTicket(ticket: ticket, address: address, expiresAt: Date().addingTimeInterval(30))
    }

    /// Session `session` prints `blocks` blocks of `lines` lines of `width` `x`s, one block each
    /// `period` seconds, then `marker` on a line of its own, to every connection attached to it: an
    /// agent's build flooding a workspace's terminal, paced by a timer as the panes4 pacer paces it.
    public func flood(_ session: String, lines: Int, width: Int, every period: Double, blocks: Int, then marker: String) {
        let block = Array((String(repeating: "x", count: width) + "\r\n").utf8).repeated(lines)
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "door.flood"))
        let sent = Mutex(0)
        timer.schedule(deadline: .now(), repeating: period)
        timer.setEventHandler { [self] in
            let count = sent.withLock { sent in
                defer { sent += 1 }
                return sent
            }
            let connections = state.withLock { $0.attached[session] ?? [] }
            let bytes = count < blocks ? block : Array("\(marker)\r\n".utf8)
            connections.forEach { answer(.output(lastInputSequence: 0, bytes: bytes), on: $0) }
            if count >= blocks { timer.cancel() }
        }
        timer.resume()
    }

    /// Session `session` prints `text` to every connection attached to it, once: a compiler's line
    /// in a workspace's terminal.
    public func print(_ session: String, _ text: String) {
        let connections = state.withLock { $0.attached[session] ?? [] }
        connections.forEach { answer(.output(lastInputSequence: 0, bytes: Array(text.utf8)), on: $0) }
    }

    // MARK: Files

    /// Folder `path` of a files ticket's workspace now holds `entries` (more than 50,000 when
    /// `truncated`), at a new version: each files connection watching it is told it changed.
    public func setFolder(_ path: String, _ entries: [FileEntry], truncated: Bool = false) {
        files.withLock { files in
            files.versions += 1
            files.folders[FileName(path)] = Folder(entries: entries, version: "v\(files.versions)", truncated: truncated)
            files.refusals[FileName(path)] = nil
            tellWatchers(of: FileName(path), in: files)
        }
    }

    /// File `path` now holds `bytes`, at a new version: each files connection watching it is told.
    public func setFile(_ path: String, _ bytes: [UInt8]) {
        files.withLock { files in
            files.versions += 1
            files.contents[FileName(path)] = (bytes, "v\(files.versions)")
            tellWatchers(of: FileName(path), in: files)
        }
    }

    /// File `path` is gone: a read of it is refused as not found, and its watchers are told.
    public func removeFile(_ path: String) {
        files.withLock { files in
            files.contents[FileName(path)] = nil
            tellWatchers(of: FileName(path), in: files)
        }
    }

    /// The next save is refused as `kind`, in `message`, nothing written.
    public func refuseNextSave(_ kind: FileRefusal, saying message: String) {
        files.withLock { $0.refuseSave = (kind, message) }
    }

    /// File `path`'s bytes as the door holds them now.
    public func file(_ path: String) -> [UInt8]? {
        files.withLock { $0.contents[FileName(path)]?.bytes }
    }

    static func hex(_ bytes: [UInt8]) -> String {
        var hash = SHA256()
        hash.update(bytes)
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Every frame the files connections sent, in order.
    public var filesReceived: [Frame] {
        files.withLock { $0.received }
    }

    /// Folder `path` is gone: a listing of it is refused as not found, and its watchers are told.
    public func removeFolder(_ path: String) {
        files.withLock { files in
            files.folders[FileName(path)] = nil
            tellWatchers(of: FileName(path), in: files)
        }
    }

    /// Under the files lock, as a socket leaves the list by.
    private func tellWatchers(of path: FileName, in files: Files) {
        files.watched.filter { $0.value.contains { $0.path == path } }.forEach { answer(.filesChanged(paths: [path]), on: $0.key) }
    }

    /// A listing of folder `path` is refused, in `message`.
    public func refuseFolder(_ path: String, _ kind: FileRefusal, saying message: String) {
        files.withLock { $0.refusals[FileName(path)] = (kind, message) }
    }

    /// From now on a listing waits, unanswered, until ``releaseListings()``.
    public func holdListings() {
        files.withLock { $0.holding = true }
    }

    public func releaseListings() {
        let held = files.withLock { files in
            files.holding = false
            defer { files.held = [] }
            return files.held
        }
        held.forEach { $0.signal() }
    }

    /// Whether each files connection's watch says it is live each second.
    public func tick(_ ticking: Bool) {
        files.withLock { $0.ticking = ticking }
    }

    /// Each files connection's watch says it is live now.
    public func tickFiles() {
        files.withLock { $0.connections.forEach { answer(.filesTick, on: $0) } }
    }

    /// The folders listed so far, in order.
    public var listed: [String] {
        files.withLock { $0.listed }
    }

    /// How many files connections were admitted in all.
    public var filesAdmitted: Int {
        files.withLock { $0.admitted }
    }

    /// How many files connections are open now.
    public var filesConnections: Int {
        files.withLock { $0.connections.count }
    }

    /// What each live files connection watches.
    public var watched: [[WatchedPath]] {
        files.withLock { Array($0.watched.values) }
    }

    /// Ends every files connection as the host ends one: its refusal with `code` (a stop's
    /// `workspace_stopped`, say), then the connection closes.
    public func endFiles(saying message: String, code: String) {
        files.withLock { files in
            files.connections.forEach { connection in
                answer(.refused(HostRefusal(message: message, remedy: [], code: code)), on: connection)
                shutdown(connection, Int32(SHUT_RDWR))
            }
        }
    }

    /// Drops every files connection with no word, as a network gone does. Under the lock a
    /// connection leaves the list by, so a socket closed and its number reused is never reached.
    public func dropFiles() {
        files.withLock { $0.connections.forEach { shutdown($0, Int32(SHUT_RDWR)) } }
    }

    /// One files ticket's connection: its listings and its watch, a tick each second while it lives.
    /// It is a files connection (``dropFiles()`` reaches it) before its welcome, so a test that acts
    /// on the admission finds it.
    private func filesServed(_ connection: Int32, _ ticket: TicketRequest) {
        files.withLock { files in
            files.connections.append(connection)
            files.admitted += 1
        }
        answer(.welcome(hostBootID: "door-1"), on: connection)
        let ticks = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "files.ticks"))
        ticks.schedule(deadline: .now() + 1, repeating: 1)
        ticks.setEventHandler { [self] in
            if files.withLock({ $0.ticking }) { answer(.filesTick, on: connection) }
        }
        let stopped = DispatchSemaphore(value: 0)
        ticks.setCancelHandler { stopped.signal() }
        ticks.resume()
        defer {
            ticks.cancel()
            stopped.wait()
            files.withLock { files in
                files.connections.removeAll { $0 == connection }
                files.watched[connection] = nil
            }
        }
        while let frame = frame(on: connection) {
            if let outside = Self.refusal(of: frame, under: ticket, opened: nil) {
                return answer(.refused(HostRefusal(message: outside, remedy: [])), on: connection)
            }
            files.withLock { $0.received.append(frame) }
            switch frame {
            case let .filesRead(id, path, offset, length):
                guard let file = files.withLock({ $0.contents[path] }) else {
                    answer(.filesRefused(id: id, kind: .known(.notFound), message: "No file at \(path.shown)."), on: connection)
                    break
                }
                let end = min(file.bytes.count, Int(offset) + Int(min(length, Int64(Int32.max))))
                var at = Int(offset), hash = SHA256()
                repeat {
                    let next = min(at + 256 * 1024, end), chunk = Array(file.bytes[min(at, end) ..< next]), last = next >= end
                    hash.update(chunk)
                    let digest = last ? hash.finalize().map { String(format: "%02x", $0) }.joined() : ""
                    answer(.filesData(id: id, version: file.version, bytes: chunk, eof: next >= file.bytes.count, whole: last && offset == 0 && next >= file.bytes.count, digest: digest), on: connection)
                    at = next
                } while at < end
            case let .filesStat(id, path):
                let (file, folder) = files.withLock { ($0.contents[path], $0.folders[path]) }
                let name = FileName(Array(path.bytes.split(separator: 0x2F).last ?? []))
                if let file {
                    let entry = FileEntry(name: name, kind: .known(.file), target: .known(.none), size: Int64(file.bytes.count), mtimeNanos: 0, mode: 0o100644)
                    answer(.filesEntry(id: id, entry: entry, version: file.version), on: connection)
                } else if let folder {
                    let entry = FileEntry(name: name, kind: .known(.folder), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o040755)
                    answer(.filesEntry(id: id, entry: entry, version: folder.version), on: connection)
                } else {
                    answer(.filesRefused(id: id, kind: .known(.notFound), message: "No file at \(path.shown)."), on: connection)
                }
            case let .filesWrite(id, path, base, _, _):
                if let refusal = files.withLock({ files -> (kind: FileRefusal, message: String)? in
                    defer { files.refuseSave = nil }
                    return files.refuseSave
                }) {
                    answer(.filesRefused(id: id, kind: .known(refusal.kind), message: refusal.message), on: connection)
                } else {
                    files.withLock { $0.saves[id] = (path, base, []) }
                }
            case let .putChunk(id, _, bytes):
                guard let received = files.withLock({ files -> Int? in
                    guard files.saves[id] != nil else { return nil }
                    files.saves[id]?.bytes += bytes
                    return files.saves[id]?.bytes.count
                }) else { break }
                answer(.putAck(id: id, received: Int64(received)), on: connection)
            case let .putCancel(id):
                files.withLock { _ = $0.saves.removeValue(forKey: id) }
            case let .putEnd(id, _):
                guard let save = files.withLock({ $0.saves.removeValue(forKey: id) }) else { break }
                let outcome = files.withLock { files -> Frame in
                    let present = files.contents[save.path].map { Self.hex($0.bytes) } ?? "absent"
                    guard present == save.base else { return .filesConflict(id: id, digest: present) }
                    files.versions += 1
                    files.contents[save.path] = (save.bytes, "v\(files.versions)")
                    tellWatchers(of: save.path, in: files)
                    return .filesWritten(id: id, digest: Self.hex(save.bytes), version: "v\(files.versions)")
                }
                answer(outcome, on: connection)
            case let .filesList(id, path):
                let wait = files.withLock { files -> DispatchSemaphore? in
                    files.listed.append(path.shown)
                    guard files.holding else { return nil }
                    let semaphore = DispatchSemaphore(value: 0)
                    files.held.append(semaphore)
                    return semaphore
                }
                wait?.wait()
                let (folder, refusal) = files.withLock { ($0.folders[path], $0.refusals[path]) }
                if let refusal {
                    answer(.filesRefused(id: id, kind: .known(refusal.kind), message: refusal.message), on: connection)
                } else if let folder {
                    let pages = stride(from: 0, to: max(folder.entries.count, 1), by: 5000).map { Array(folder.entries[$0 ..< min($0 + 5000, folder.entries.count)]) }
                    for (index, page) in pages.enumerated() {
                        let last = index == pages.count - 1
                        answer(.filesListing(FileListing(id: id, version: folder.version, entries: page, more: !last, truncated: last && folder.truncated)), on: connection)
                    }
                } else {
                    answer(.filesRefused(id: id, kind: .known(.notFound), message: "No folder at \(path)."), on: connection)
                }
            case let .filesWatch(paths):
                let stale = files.withLock { files -> [FileName] in
                    files.watched[connection] = paths
                    return paths.filter { (files.contents[$0.path]?.version ?? files.folders[$0.path]?.version) != $0.version && !$0.version.isEmpty }.map(\.path)
                }
                if !stale.isEmpty { answer(.filesChanged(paths: stale), on: connection) }
            default:
                break
            }
        }
    }

    /// From now on a `Create` is made and its answer lost: the door hangs up before it answers, as a
    /// connection that drops while the runner starts a workspace.
    public func loseCreateAnswers() {
        creates.withLock { $0.losing = true }
    }

    /// From now on a `Create` waits, unanswered and its session not yet made, until ``releaseCreates()``:
    /// a workspace still starting.
    public func holdCreates() {
        creates.withLock { $0.holding = true }
    }

    /// The `Create`s held go on: each session is made, then answered, as the real host does.
    public func releaseCreates() {
        let held = creates.withLock { creates in
            creates.holding = false
            defer { creates.held = [] }
            return creates.held
        }
        held.forEach { $0.signal() }
    }

    /// How many `Create`s the door has received, held or not.
    public var createsReceived: Int {
        creates.withLock { $0.received }
    }

    /// The sessions it holds now, as the host lists them.
    public var sessions: [String] {
        state.withLock { $0.live }
    }

    /// The sessions it was asked to end, in order, whether or not it did.
    public var kills: [String] {
        state.withLock { $0.kills }
    }

    /// From now on this Mac's host refuses every Kill, in `words`, its session left running; nil
    /// ends them again.
    public func refuseKills(saying words: String?) {
        killing.withLock { $0.refusal = words }
    }

    /// From now on a Kill waits, unanswered and its session running, until ``releaseKills()``.
    public func holdKills() {
        killing.withLock { $0.holding = true }
    }

    /// The Kills held go on: each session is ended, then answered.
    public func releaseKills() {
        let held = killing.withLock { killing in
            killing.holding = false
            defer { killing.held = [] }
            return killing.held
        }
        held.forEach { $0.signal() }
    }

    /// Someone else holds `session`'s write token from now on: an attach asking for it observes.
    public func holdWrite(_ session: String) {
        state.withLock { state in
            state.writers[session] = 0
            state.holders[session] = "someone"
        }
    }

    /// Someone else lets go of `session`'s write token.
    public func release(_ session: String) {
        state.withLock { state in
            if state.writers[session] == 0 { state.writers[session] = nil }
        }
    }

    /// The sessions the API lists for its person, newest first: every one it holds, and every one it
    /// ended on its own, in that end's words.
    public var listing: RunnerSessions {
        state.withLock { state in
            let sessions = state.made.enumerated().reversed().compactMap { index, name -> RunnerSession? in
                let live = state.live.contains(name)
                guard live || state.ended[name] != nil else { return nil }
                return RunnerSession(
                    name: name, live: live, attached: state.attached[name]?.count ?? 0, writer: "", opener: person ?? "",
                    command: [], createdAt: Date(timeIntervalSince1970: Double(index)), endedReason: state.ended[name]
                )
            }
            return RunnerSessions(sessions: sessions)
        }
    }

    /// The runner holds `session` from now on, as one its person opened from another Mac.
    public func hold(_ session: String) {
        state.withLock { state in
            state.live.append(session)
            state.made.append(session)
        }
    }

    /// The runner ends `session` saying `reason`, as a workspace's stop ends it: no longer live,
    /// listed ended in those words.
    public func end(_ session: String, saying reason: String) {
        state.withLock { state in
            state.live.removeAll { $0 == session }
            state.writers[session] = nil
            state.ended[session] = reason
        }
    }

    /// `fde` takes `session`'s keyboard, as its person's other Mac or an admin does: every connection
    /// attached to it hears so, and that it is not theirs.
    public func takeKeyboard(_ session: String, by fde: String) {
        state.withLock { state in
            state.writers[session] = 0
            state.holders[session] = fde
        }
        announce(session, writer: fde, holder: nil)
    }

    /// Whether a key typed on a free keyboard takes it, as the session's opener's does; false for
    /// someone else (an admin), whose keys stay refused until they take it.
    public var typingTakesAFreeKeyboard: Bool {
        get { state.withLock { $0.typingTakes } }
        set { state.withLock { $0.typingTakes = newValue } }
    }

    /// The client not this door's that holds `session`'s keyboard leaves it, as its person quitting
    /// Sail there does: every connection attached hears no one holds it.
    public func leaveKeyboard(_ session: String) {
        state.withLock { state in
            if state.writers[session] == 0 { state.writers[session] = nil }
        }
        announce(session, writer: "", holder: nil)
    }

    /// Every connection attached to `session` hears `writer` holds it, `holder` being the connection
    /// that does, nil for a client not this door's.
    private func announce(_ session: String, writer: String, holder: Int32?) {
        let connections = state.withLock { $0.attached[session] ?? [] }
        connections.forEach { answer(.writerChanged(fde: writer, yours: $0 == holder), on: $0) }
    }

    private static func session(of ticket: TicketRequest) -> String? {
        if case let .attach(session) = ticket { session } else { nil }
    }

    /// The runner ends `session` on its own (its workspace stopped, say): no longer listed.
    public func forget(_ session: String) {
        state.withLock { state in
            state.live.removeAll { $0 == session }
            state.writers[session] = nil
        }
    }

    public func stop() {
        shutdown(listener, Int32(SHUT_RDWR))
        close(listener)
    }

    /// The real door's words for a Kill of its own session from a connection that does not hold
    /// the session's write token.
    public static let notTheWriter = "This connection does not hold the terminal's keyboard, so it was not ended; someone else may hold it."

    private static func socket() -> Int32 {
        #if canImport(Darwin)
        Darwin.socket(AF_INET, SOCK_STREAM, 0)
        #else
        Glibc.socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        #endif
    }

    private func serve() {
        while true {
            let connection = accept(listener, nil, nil)
            guard connection >= 0 else { return }
            #if canImport(Darwin)
            var one: Int32 = 1
            setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            #endif
            let number = state.withLock { state in
                state.connections += 1
                return state.connections
            }
            Thread { [self] in converse(connection, number) }.start()
        }
    }

    private func converse(_ connection: Int32, _ number: Int) {
        defer {
            state.withLock { state in
                for (session, writer) in state.writers where writer == number {
                    state.writers[session] = nil
                }
                for session in state.attached.keys {
                    state.attached[session]?.removeAll { $0 == connection }
                }
            }
            close(connection)
        }
        guard receive(8, on: connection) == Handshake.magic else { return }
        transmit(Handshake.magic, on: connection)
        guard case let .hello(token) = frame(on: connection) else { return }
        if thisMac, token.isEmpty {
            answer(.welcome(hostBootID: "mac-1"), on: connection)
            return served(connection)
        }
        switch admit(token) {
        case let .success(ticket):
            if case .files = ticket { return filesServed(connection, ticket) }
            answer(.welcome(hostBootID: "door-1"), on: connection)
            attached(connection, number, ticket)
        case let .failure(refused):
            answer(.refused(HostRefusal(message: refused.description, remedy: [])), on: connection)
        }
    }

    /// What `token` admits, spent now; or why it is not welcomed.
    private func admit(_ token: String) -> Result<TicketRequest, DoorError> {
        state.withLock { state in
            if let ticket = state.unused.removeValue(forKey: token) {
                state.used.insert(token)
                return .success(ticket)
            }
            return .failure(DoorError(
                state.used.contains(token)
                    ? "This ticket was presented already, and a ticket admits one connection; ask the API for a new one."
                    : "This is not a ticket this host issued; ask the API for a new one."
            ))
        }
    }

    private func attached(_ connection: Int32, _ number: Int, _ ticket: TicketRequest) {
        var opened: String?
        while let frame = frame(on: connection) {
            if let outside = Self.refusal(of: frame, under: ticket, opened: opened) {
                return answer(.refused(HostRefusal(message: outside, remedy: [])), on: connection)
            }
            switch frame {
            case let .create(create):
                made(create.session)
                if creates.withLock({ $0.losing }) { return }
                opened = create.session
                answer(.accepted, on: connection)
            case let .attach(session, write, _, takes):
                let live = state.withLock { state in
                    guard state.live.contains(session) else { return false }
                    let holder = state.writers[session]
                    if write, holder == nil || takes && (holder != 0 || state.holders[session] == person) { state.writers[session] = number }
                    state.attached[session, default: []].append(connection)
                    return true
                }
                answer(live ? .accepted : .refused(HostRefusal(message: "No live session '\(session)' to attach.", remedy: [])), on: connection)
                if live, let person {
                    let (holder, outside) = state.withLock { ($0.writers[session], $0.holders[session]) }
                    answer(.writerChanged(fde: holder == 0 ? outside ?? "someone" : person, yours: holder == number), on: connection)
                }
            case .takeWrite:
                guard let person, let session = opened ?? Self.session(of: ticket) else { continue }
                state.withLock { $0.writers[session] = number }
                announce(session, writer: person, holder: connection)
            case let .kill(session):
                let ended = state.withLock { state in
                    state.kills.append(session)
                    guard state.writers[session] == number else { return false }
                    state.live.removeAll { $0 == session }
                    state.writers[session] = nil
                    return true
                }
                guard ended else {
                    return answer(.refused(HostRefusal(message: Self.notTheWriter, remedy: [])), on: connection)
                }
                answer(.sessionEnded(reason: "ended"), on: connection)
                answer(.accepted, on: connection)
            case .input:
                guard let session = opened ?? Self.session(of: ticket) else { continue }
                let took = state.withLock { state -> Bool? in
                    if state.writers[session] == number { return false }
                    guard state.writers[session] == nil, state.typingTakes else { return nil }
                    state.writers[session] = number
                    return true
                }
                switch took {
                case nil: answer(.refused(HostRefusal(message: "You do not hold the write token.", remedy: [])), on: connection)
                case true?: if let person { announce(session, writer: person, holder: connection) }
                case false?: continue
                }
            default:
                continue
            }
        }
    }

    /// This Mac's host serving its person, who may do anything.
    private func served(_ connection: Int32) {
        while let frame = frame(on: connection) {
            switch frame {
            case let .create(create):
                made(create.session)
                answer(.accepted, on: connection)
            case let .attach(session, _, _, _):
                let live = state.withLock { $0.live.contains(session) }
                answer(live ? .accepted : .refused(HostRefusal(message: "No live session '\(session)' to attach.", remedy: [])), on: connection)
            case let .kill(session):
                answer(killed(session), on: connection)
            case .listSessions:
                let live = state.withLock { $0.live }.map { SessionInfo(name: $0, instanceID: "i-\($0)", live: true, attached: 0, writerFDE: "", room: "", command: []) }
                answer(.sessions(SessionPage(sessions: live, next: "")), on: connection)
            default:
                continue
            }
        }
    }

    /// This Mac's host's answer to a Kill of `session`, once Kills are no longer held.
    private func killed(_ session: String) -> Frame {
        let (held, refusal) = killing.withLock { killing -> (DispatchSemaphore?, String?) in
            guard killing.holding else { return (nil, killing.refusal) }
            let held = DispatchSemaphore(value: 0)
            killing.held.append(held)
            return (held, killing.refusal)
        }
        held?.wait()
        let ended = state.withLock { state in
            state.kills.append(session)
            guard refusal == nil, state.live.contains(session) else { return false }
            state.live.removeAll { $0 == session }
            return true
        }
        if let refusal { return .refused(HostRefusal(message: refusal, remedy: [])) }
        return ended ? .accepted : .refused(HostRefusal(message: "No session '\(session)'.", remedy: []))
    }

    /// Makes `session`, once the `Create`s are no longer held: as the host does, before it answers.
    private func made(_ session: String) {
        let held = creates.withLock { creates -> DispatchSemaphore? in
            creates.received += 1
            guard creates.holding else { return nil }
            let held = DispatchSemaphore(value: 0)
            creates.held.append(held)
            return held
        }
        held?.wait()
        state.withLock { state in
            state.live.append(session)
            state.made.append(session)
        }
    }

    /// The real door's words for a frame its ticket does not cover, nil when it does.
    static func refusal(of frame: Frame, under ticket: TicketRequest, opened: String?) -> String? {
        let own: String? = switch ticket {
        case .open: opened
        case let .attach(session): session
        case .files: nil
        }
        if case .files = ticket {
            switch frame {
            case .filesList, .filesStat, .filesRead, .filesCancel, .filesWatch, .filesWrite, .putChunk, .putEnd, .putCancel, .filesCreate, .filesMove, .filesRemove: return nil
            default: return "This connection's ticket is to read files, and it admits nothing else; ask the API for a ticket for that."
            }
        }
        let covered = switch frame {
        case let .create(create):
            if case let .open(project, workspace) = ticket { opened == nil && create.project == project && create.workspace == workspace } else { false }
        case let .attach(session, _, _, _): session == own
        case let .kill(session): session == own
        default: true
        }
        guard !covered else { return nil }
        let scope = switch ticket {
        case let .open(project, workspace): workspace.isEmpty ? "open a session in project '\(project)'" : "open a session in workspace '\(workspace)' of project '\(project)'"
        case let .attach(session): "attach to session '\(session)'"
        case let .files(project, workspace): "read the files of workspace '\(workspace)' of project '\(project)'"
        }
        return "This connection's ticket is to \(scope), and it admits nothing else; ask the API for a ticket for that."
    }

    private func frame(on connection: Int32) -> Frame? {
        guard let header = receive(4, on: connection) else { return nil }
        let length = header.reduce(0) { $0 << 8 | Int($1) }
        guard length > 0, length <= FrameCodec.maxPayload, let payload = receive(length, on: connection) else { return nil }
        return try? FrameCodec.decode(payload: payload)
    }

    private func receive(_ count: Int, on connection: Int32) -> [UInt8]? {
        var bytes = [UInt8](repeating: 0, count: count)
        var got = 0
        while got < count {
            let read = bytes.withUnsafeMutableBytes { recv(connection, $0.baseAddress! + got, count - got, 0) }
            guard read > 0 else { return nil }
            got += read
        }
        return bytes
    }

    private func answer(_ frame: Frame, on connection: Int32) {
        guard let bytes = try? FrameCodec.encode(frame) else { return }
        transmit(bytes, on: connection)
    }

    private func transmit(_ bytes: [UInt8], on connection: Int32) {
        #if canImport(Darwin)
        let flags: Int32 = 0
        #else
        let flags = Int32(MSG_NOSIGNAL)
        #endif
        let lock = writing.withLock { locks in
            if let lock = locks[connection] { return lock }
            let lock = NSLock()
            locks[connection] = lock
            return lock
        }
        lock.withLock {
            var sent = 0
            while sent < bytes.count {
                let wrote = bytes.withUnsafeBytes { send(connection, $0.baseAddress! + sent, $0.count - sent, flags) }
                guard wrote > 0 else { return }
                sent += wrote
            }
        }
    }
}

public struct DoorError: Error, CustomStringConvertible {
    public let description: String

    public init(_ description: String) {
        self.description = description
    }
}

extension Array {
    /// These elements `times` times over, in order.
    func repeated(_ times: Int) -> [Element] {
        (0 ..< Swift.max(times, 0)).flatMap { _ in self }
    }
}
