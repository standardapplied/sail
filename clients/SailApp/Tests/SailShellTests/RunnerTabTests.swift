import AppKit
import Foundation
import SailAPI
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailTerminalWire
import SailTestPasskey
import SailVT
import SailWorkbench
import Synchronization
import Testing

/// Tickets as a control plane that refuses or cannot be reached hands them out: every request kept.
final class RecordingTickets: TicketSource {
    private let asked = Mutex<[TicketRequest]>([])
    private let answer: SailError

    init(answering answer: SailError) {
        self.answer = answer
    }

    var requests: [TicketRequest] {
        asked.withLock { $0 }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        asked.withLock { $0.append(request) }
        throw answer
    }
}

/// Tickets as the API hands them out for a runner's door: one the door issues for each request,
/// every request kept; an attach ticket for a session the door no longer holds is refused as not
/// found, in the API's words, as the API refuses it.
final class DoorTickets: TicketSource {
    let door: FakeRunnerDoor
    private let asked = Mutex<[TicketRequest]>([])
    private let silent = Mutex<(on: Bool, held: [CheckedContinuation<Void, Never>])>((false, []))

    init(_ door: FakeRunnerDoor) {
        self.door = door
    }

    var requests: [TicketRequest] {
        asked.withLock { $0 }
    }

    /// The control plane answers no ticket from now on, as one behind a dead network does, until
    /// ``answerAgain()``.
    func silence() {
        silent.withLock { $0.on = true }
    }

    /// The tickets held go out, and the next are answered at once.
    func answerAgain() {
        let held = silent.withLock { silent in
            silent.on = false
            defer { silent.held = [] }
            return silent.held
        }
        held.forEach { $0.resume() }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        asked.withLock { $0.append(request) }
        await withCheckedContinuation { (held: CheckedContinuation<Void, Never>) in
            let now = silent.withLock { silent -> Bool in
                guard silent.on else { return true }
                silent.held.append(held)
                return false
            }
            if now { held.resume() }
        }
        if case let .attach(session) = request, !door.sessions.contains(session) {
            throw .refused(Refusal(code: .known(.notFound), message: "There is no session '\(session)' on this box that you may attach."))
        }
        return door.issue(for: request)
    }

    func sessions(in _: String, workspace _: String) async throws(SailError) -> RunnerSessions {
        door.listing
    }
}

/// A control plane behind a proxy that answers `failing` with a 502 of its own, as a proxy does
/// while sail-api restarts: no refusal of the server's, so the client has only the status.
actor Proxied: Transport {
    private let server: RecordedServer
    private let failing: String?

    init(_ server: RecordedServer, failing: String?) {
        self.server = server
        self.failing = failing
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        guard request.route != failing else { throw .unexpected(route: request.route, status: 502) }
        return try await server.send(request)
    }

    func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        try await server.open(request)
    }
}

/// What a quit was told to do, in order.
@MainActor final class Replies {
    var given: [Bool] = []
}

/// A terminal in a workspace: a tab like any other whose session's ticket names the tab's
/// workspace, at the first open and at Open Again; refused, it says the server's words and what
/// the person can do; closed or quit, its session is ended at the runner, and what could not be
/// ended is said.
@MainActor @Suite struct RunnerTabTests {
    init() {
        ExitWatch.install()
    }

    static let notYours = Refusal(
        code: .known(.forbiddenNotAssignee), message: "Workspace 'dock' belongs to 'uday', not you.",
        action: "Ask uday to enter it, or have an admin do it."
    )

    static func until(_ seconds: Double = 5, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in DispatchQueue.main.async { done.resume() } }
        }
        return true
    }

    static func pane(
        _ place: Place, runner: (any TicketSource)?, start: TerminalPane.Start = .open, session: String? = nil, person: String = ""
    ) throws -> TerminalPane {
        let shell = try WorkbenchLookTests.shell(.light)
        var bench = Workbench()
        let id = bench.newTab(session: session ?? place.newSessionName, place: place)
        return try TerminalPane(
            try #require(bench.panes[id]), start: start, host: shell.thisMac.host, runner: runner, person: person,
            fonts: TerminalFonts(directory: LookTests.fonts), points: 15, look: .light
        )
    }

    /// A3, A5, A13: the session asks a ticket naming the tab's project and workspace; refused, the
    /// banner says the server's sentence and the fix in words; Open Again asks a new ticket for
    /// the same workspace.
    @Test func aRunnerTabsTicketNamesItsWorkspaceAtEveryOpen() async throws {
        let tickets = RecordingTickets(answering: .refused(Self.notYours))
        let pane = try Self.pane(.workspace(project: "acme", name: "try"), runner: tickets)
        #expect(pane.status.says == nil)
        pane.begin()
        #expect(await Self.until { pane.status.says == "Workspace 'dock' belongs to 'uday', not you. Ask uday to enter it, or have an admin do it." },
                "the banner says \(pane.status.says ?? "nothing")")
        pane.openAgain()
        #expect(await Self.until { tickets.requests.count == 2 })
        #expect(tickets.requests == [.open(project: "acme", workspace: "try"), .open(project: "acme", workspace: "try")])
    }

    /// Q2: while a runner tab's session keeps trying, the banner names where it is going back to and
    /// still offers Reconnect Now, which asks a ticket at once.
    @Test func aRunnerTabSaysWhereItIsReconnectingAndOffersReconnectNow() async throws {
        let tickets = RecordingTickets(answering: .unreachable("the runner does not answer"))
        let pane = try Self.pane(.workspace(project: "harbor", name: "dock"), runner: tickets)
        pane.begin()
        #expect(await Self.until { pane.status.says == "Reconnecting to harbor · dock…" }, "the banner says \(pane.status.says ?? "nothing")")
        #expect(pane.status.offers == ["Reconnect Now"])
        pane.detach()
    }

    /// A tab in a workspace is made only for a person signed in: its tickets are theirs.
    @Test func aWorkspaceTabNeedsSomeoneSignedIn() throws {
        #expect(throws: ShellError.host("Sign in to open a terminal in a workspace.")) {
            try Self.pane(.workspace(project: "acme", name: "try"), runner: nil)
        }
    }

    /// N2: a runner tab whose session was never made (its ticket never came) leaves nothing at the
    /// runner: its end is gone at once, asking no ticket to end what does not exist, and the quit
    /// does not count it.
    @Test func aRunnerTabWhoseSessionWasNeverMadeEndsAsGone() async throws {
        let tickets = RecordingTickets(answering: .unreachable("the runner does not answer"))
        let runner = try Self.pane(.workspace(project: "acme", name: "try"), runner: tickets)
        runner.begin()
        #expect(await Self.until { !tickets.requests.isEmpty })
        let ending = try #require(runner.end(within: .seconds(2)))
        #expect(await ending.value == .gone)
        #expect(!tickets.requests.contains(.attach(session: runner.name)), "\(tickets.requests)")
    }

    /// The Workspaces item is on the rail only while someone is signed in.
    @Test func theWorkspacesItemIsOfferedOnlySignedIn() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        #expect(shell.rail.button(.workspaces)?.isHidden == true)
        shell.rail.offer(.workspaces, true)
        #expect(shell.rail.button(.workspaces)?.isHidden == false)
    }

    /// Look, the shared field: content corners (the canvas shows at the corner), the surface inside,
    /// a 1 pt ring in the strong separator, in both looks.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aFieldIsRoundedOnTheSurfaceWithItsRing(_ name: String, _ look: Look) {
        let frame = FieldFrame(NSTextField(), look: look)
        frame.frame = NSRect(x: 0, y: 0, width: 200, height: FieldFrame.height)
        let backdrop = Backdrop(NSColor(look.canvas), holding: frame)
        let ring = Self.colour(of: Swatch(NSColor(look.separatorStrong), over: NSColor(look.surface)), at: NSPoint(x: 5, y: 5))
        #expect(Self.colour(of: backdrop, at: NSPoint(x: 1, y: 1)).near(Self.channels(look.canvas)), "\(name): the corner is not rounded")
        #expect(Self.colour(of: backdrop, at: NSPoint(x: 150, y: 14)).near(Self.channels(look.surface)), "\(name): the inside is not the surface")
        #expect(Self.colour(of: backdrop, at: NSPoint(x: 150, y: 0.25)).near(ring), "\(name): the ring is \(Self.colour(of: backdrop, at: NSPoint(x: 150, y: 0.25))), not \(ring)")
        #expect(frame.ring.width == 1 && frame.ring.colour == NSColor(look.separatorStrong))
    }

    /// The text field reading `text`, wherever it is in `view`.
    static func label(_ text: String, in view: NSView) -> NSTextField? {
        if let field = view as? NSTextField, field.stringValue == text { return field }
        return view.subviews.lazy.compactMap { label(text, in: $0) }.first
    }

    static func channels(_ rgb: RGB) -> [Double] {
        [Double(rgb.red), Double(rgb.green), Double(rgb.blue)]
    }

    /// The colour `view` draws at `point` (points from its bottom left), as 0–255 sRGB channels.
    static func colour(of view: NSView, at point: NSPoint) -> [Double] {
        guard let image = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return [] }
        view.cacheDisplay(in: view.bounds, to: image)
        let scale = Double(image.pixelsWide) / view.bounds.width
        let y = view.isFlipped ? point.y : view.bounds.height - point.y
        guard let colour = image.colorAt(x: Int(point.x * scale), y: Int(y * scale))?.usingColorSpace(.sRGB) else { return [] }
        return [colour.redComponent, colour.greenComponent, colour.blueComponent].map { ($0 * 255).rounded() }
    }
}

/// `colour` composed over `base` as AppKit composes a view's drawing, the reference a drawn
/// translucent fill is read against: a translucent colour is blended in the drawing's colour space,
/// not as sRGB numbers.
final class Swatch: NSView {
    private let colour: NSColor
    private let base: NSColor

    init(_ colour: NSColor, over base: NSColor) {
        self.colour = colour
        self.base = base
        super.init(frame: NSRect(x: 0, y: 0, width: 10, height: 10))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func draw(_: NSRect) {
        base.setFill()
        bounds.fill()
        colour.setFill()
        NSBezierPath(rect: bounds).fill()
    }
}

/// `view` drawn over `base`, as it is drawn in its pane: what a translucent part of it shows.
final class Backdrop: NSView {
    private let base: NSColor

    init(_ base: NSColor, holding view: NSView) {
        self.base = base
        super.init(frame: NSRect(origin: .zero, size: view.frame.size))
        view.setFrameOrigin(.zero)
        addSubview(view)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func draw(_: NSRect) {
        base.setFill()
        bounds.fill()
    }
}

extension [Double] {
    /// Within 3 of `other` in every channel: a colour drawn and read back.
    func near(_ other: [Double]) -> Bool {
        count == other.count && zip(self, other).allSatisfy { abs($0 - $1) <= 3 }
    }
}
