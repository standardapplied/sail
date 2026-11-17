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

    static func pane(_ place: Place, runner: (any TicketSource)?) throws -> TerminalPane {
        let shell = try WorkbenchLookTests.shell(.light)
        var bench = Workbench()
        let id = bench.newTab(session: place.newSessionName, place: place)
        return try TerminalPane(
            try #require(bench.panes[id]), start: .open, host: shell.thisMac.host, runner: runner,
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
        #expect(!runner.holdsRunnerSession)
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

    /// A1, A2, A7: the pane lists the person's workspaces from the server's own answers, theirs
    /// first; Make says it is under way; a refusal is the server's sentence and the fix in words.
    @Test func thePaneListsMakesAndRefusesInTheServersWords() async throws {
        let pane = WorkspacesPane(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts))
        pane.person = ("uday", true)
        pane.client = SailClient(transport: RecordedServer([try Exchange("projects"), try Exchange("workspaces"), try Exchange("workspace-create")]), token: "tok")
        await pane.refresh()
        #expect(pane.rows.map(\.name) == ["dock"], "the project's own container is not shown")
        #expect(pane.list.selectedRow == 0 && pane.open.isEnabled, "the first row is chosen, so Return or Open opens it")
        pane.look = .dark
        #expect(pane.list.selectedRow == 0 && pane.open.isEnabled, "a change of look keeps the chosen row")
        #expect(pane.text.contains("dock · running"), "\(pane.text)")
        pane.name.stringValue = "new"
        let before = pane.ended
        pane.makeWorkspace(nil)
        #expect(pane.text.contains("Making new (about 3 s)"))
        #expect(pane.make.title == "Making…" && !pane.make.isEnabled && !pane.open.isEnabled, "a make under way says so and is not pressed twice")
        #expect(await Self.until { pane.ended > before })
        #expect(!pane.text.contains("Making"), "\(pane.text)")
        let viewer = WorkspacesPane(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts))
        viewer.client = SailClient(
            transport: RecordedServer([try Exchange("projects"), try Exchange("workspaces"), try Exchange("refusal-read-only-credential-workspace")]),
            token: "tok"
        )
        await viewer.refresh()
        viewer.name.stringValue = "new"
        let asked = viewer.ended
        viewer.makeWorkspace(nil)
        #expect(await Self.until { viewer.ended > asked })
        #expect(viewer.text.hasSuffix("Your credential is read-only and cannot enter workspaces.\nAsk an admin for a member or admin credential."), "\(viewer.text)")
    }

    /// A pane signed in as `handle`, its projects and workspaces answered by `exchanges`, listed.
    static func listed(
        _ exchanges: [Exchange], as handle: String = "uday", admin: Bool = false, answering502 route: String? = nil
    ) async throws -> WorkspacesPane {
        let pane = WorkspacesPane(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts))
        pane.frame = NSRect(x: 0, y: 0, width: 800, height: 640)
        pane.person = (handle, admin)
        pane.client = SailClient(transport: Proxied(RecordedServer(exchanges), failing: route), token: "tok")
        await pane.refresh()
        pane.layoutSubtreeIfNeeded()
        return pane
    }

    /// A stopped workspace's button says what opening it does; another person's is not offered to
    /// a member; an open under way says so and is not pressed twice.
    @Test func theButtonSaysWhatOpeningDoesAndIsPressedOnce() async throws {
        let pane = try await Self.listed([try Exchange("projects"), try Exchange("workspaces-three")])
        #expect(pane.rows.map(\.name) == ["dock", "pier", "quay"])
        var opened: [String] = []
        pane.onOpen = { _, name in opened.append(name) }
        #expect(pane.open.title == "Open" && pane.open.isEnabled, "dock runs")
        pane.list.selectRowIndexes([1], byExtendingSelection: false)
        #expect(pane.open.title == "Start and Open" && pane.open.isEnabled, "pier is stopped: opening starts it")
        pane.list.selectRowIndexes([2], byExtendingSelection: false)
        #expect(pane.open.title == "Open" && !pane.open.isEnabled, "ana's is not offered to a member")
        pane.list.selectRowIndexes([1], byExtendingSelection: false)
        pane.open.performClick(nil)
        pane.openSelected(nil)
        #expect(opened == ["pier"], "pressed twice, opened once")
        #expect(pane.open.title == "Starting…" && !pane.open.isEnabled)
        pane.shown()
        #expect(await Self.until { !pane.busy }, "asked again")
        #expect(pane.open.title == "Start and Open" && pane.open.isEnabled, "come back to, the row still chosen is offered again")
    }

    /// The body says what is true where the list would be: a project with no workspaces of its own;
    /// no project at all; a list that could not be read, with Try Again, which asks again.
    @Test func theEmptyStatesSayWhatIsTrue() async throws {
        let none = try await Self.listed([try Exchange("projects"), try Exchange("workspaces-none")])
        #expect(none.body == .noWorkspaces(project: "harbor") && none.list.enclosingScrollView?.isHidden == true && none.open.isHidden)
        #expect(none.text.hasSuffix("No workspaces in harbor yet.\nName one below to make it."), "\(none.text)")
        #expect(none.tryAgain.isHidden && !none.name.isHiddenOrHasHiddenAncestor, "the new workspace's name is where it is made")
        let nowhere = try await Self.listed([try Exchange("projects").altered(at: ["projects"], to: [Any]())])
        #expect(nowhere.text == "Workspaces\nNo project on this control plane is open to you yet.\nAn admin adds you to one.", "\(nowhere.text)")
        #expect(nowhere.projects.isHiddenOrHasHiddenAncestor && nowhere.name.isHiddenOrHasHiddenAncestor && nowhere.tryAgain.isHidden)
        let unread = try await Self.listed([try Exchange("projects")], answering502: "GET /v1/projects/harbor/workspaces")
        #expect(unread.text.hasSuffix("The control plane answered with an error (502)."), "\(unread.text)")
        #expect(!unread.text.contains("/v1/"), "no route reaches a person")
        #expect(!unread.tryAgain.isHidden && unread.open.isHidden)
        let before = unread.ended
        unread.tryAgain.performClick(nil)
        #expect(await Self.until { unread.ended > before }, "Try Again asks again")
    }

    /// Look, 1: a chosen row takes the selected fill (half as strong when the list is not what the
    /// keys act on) from 9 pt in, and the accent bar, 2 by 24 pt at its leading edge, in both looks.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func theChosenRowTakesTheFillAndTheBar(_ name: String, _ look: Look) throws {
        for emphasized in [true, false] {
            let row = WorkspaceRowView(look: look)
            row.frame = NSRect(x: 0, y: 0, width: 380, height: Rail.item)
            row.isSelected = true
            row.isEmphasized = emphasized
            let alpha = emphasized ? look.fillSelected.alpha : look.fillSelected.alpha / 2
            let fill = Self.colour(of: Swatch(NSColor(look.fillSelected).withAlphaComponent(alpha), over: NSColor(look.canvas)), at: NSPoint(x: 5, y: 5))
            #expect(Self.colour(of: row, at: NSPoint(x: 1, y: 21)).near(Self.channels(look.accent)), "\(name): the bar")
            #expect(Self.colour(of: row, at: NSPoint(x: 200, y: 21)).near(fill), "\(name) \(emphasized): the fill is \(Self.colour(of: row, at: NSPoint(x: 200, y: 21))), not \(fill)")
            #expect(Self.colour(of: row, at: NSPoint(x: 5, y: 21)).near(Self.channels(look.canvas)), "\(name): nothing between the bar and the fill")
            #expect(Self.colour(of: row, at: NSPoint(x: 1, y: 4)).near(Self.channels(look.canvas)), "\(name): the bar is 24 pt, not the row's height")
            row.isSelected = false
            #expect(Self.colour(of: row, at: NSPoint(x: 200, y: 21)).near(Self.channels(look.canvas)), "\(name): an unchosen row is the canvas")
        }
    }

    /// Look, 2: the pane's groups keep the account pane's rhythm (8 pt within a group, 24 between
    /// groups), the list hugs its one row, and Open sits 12 pt under it.
    @Test func theGroupsKeepTheirRhythmAndTheListHugsItsRows() async throws {
        let pane = try await Self.listed([try Exchange("projects"), try Exchange("workspaces")])
        let scroll = try #require(pane.list.enclosingScrollView)
        let project = try #require(Self.label("PROJECT", in: pane)), new = try #require(Self.label("NEW WORKSPACE", in: pane))
        let making = try #require(pane.name.superview?.superview)
        let rect = { (view: NSView) in pane.convert(view.alignmentRect(forFrame: view.frame), from: view.superview) }
        let gaps = [
            rect(project).minY - rect(pane.projects).maxY, rect(pane.projects).minY - rect(scroll).maxY, rect(scroll).minY - rect(pane.open).maxY,
            rect(pane.open).minY - rect(new).maxY, rect(new).minY - rect(making).maxY,
        ]
        #expect(gaps.map { $0.rounded() } == [8, 24, 12, 24, 8], "gaps \(gaps)")
        #expect(Double(rect(scroll).height).rounded() == Rail.item, "one row, one row's height")
        #expect(rect(making).width.rounded() == WorkspacesPane.width && rect(pane.make).maxX.rounded() == rect(scroll).maxX.rounded(),
                "the name and Make span the list's width: \(rect(making)), \(rect(pane.make)), \(rect(scroll))")
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

    /// Look, the prominent button: enabled, the accent with its label `onAccent` in bold; disabled,
    /// a grey (the strong separator over the canvas), its label secondary, never the accent dimmed.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aDisabledProminentButtonIsGrey(_ name: String, _ look: Look) {
        let button = AccentButton("Open", look: look, target: nil, action: #selector(NSApplication.terminate(_:)))
        button.frame = NSRect(x: 0, y: 0, width: 120, height: 34)
        let backdrop = Backdrop(NSColor(look.canvas), holding: button)
        #expect(Self.colour(of: backdrop, at: NSPoint(x: 4, y: 17)).near(Self.channels(look.accent)), "\(name): enabled is the accent")
        #expect(button.attributedTitle.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor == NSColor(look.onAccent))
        button.isEnabled = false
        let grey = Self.colour(of: Swatch(NSColor(look.separatorStrong), over: NSColor(look.canvas)), at: NSPoint(x: 5, y: 5))
        let drawn = Self.colour(of: backdrop, at: NSPoint(x: 4, y: 17))
        #expect(drawn.near(grey), "\(name): disabled is \(drawn), not \(grey)")
        #expect(drawn.count == 3 && (drawn.max() ?? 0) - (drawn.min() ?? 0) <= 16, "\(name): disabled is not a grey: \(drawn)")
        #expect(button.attributedTitle.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor == NSColor(look.textSecondary))
    }

    /// Look, a row's state: a running workspace's dot is the accent, a stopped one's the secondary
    /// text colour, filled, legible at its size in both looks; an unchosen row under the pointer
    /// takes the hover fill.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func theStateDotIsFilledAndARowUnderThePointerTakesTheHoverFill(_ name: String, _ look: Look) throws {
        for (state, colour) in [(WorkspaceState.running, NSColor(look.accent)), (.stopped, NSColor(look.textSecondary))] {
            let cell = WorkspaceCell(WorkspaceRow(name: "pier", instance: "harbor-pier", state: .known(state), owner: "uday"), for: "uday", look: look)
            cell.frame = NSRect(x: 0, y: 0, width: 380, height: Rail.item)
            let backdrop = Backdrop(NSColor(look.canvas), holding: cell)
            backdrop.layoutSubtreeIfNeeded()
            let dot = try #require(cell.subviews.first { $0 is StateDot })
            let centre = backdrop.convert(NSPoint(x: dot.bounds.midX, y: dot.bounds.midY), from: dot)
            let expected = Self.colour(of: Swatch(colour, over: NSColor(look.canvas)), at: NSPoint(x: 5, y: 5))
            #expect(Self.colour(of: backdrop, at: centre).near(expected), "\(name) \(state): the dot is \(Self.colour(of: backdrop, at: centre)), not \(expected)")
        }
        let row = WorkspaceRowView(look: look)
        row.frame = NSRect(x: 0, y: 0, width: 380, height: Rail.item)
        row.hovered = true
        let hover = Self.colour(of: Swatch(NSColor(look.fillHover), over: NSColor(look.canvas)), at: NSPoint(x: 5, y: 5))
        #expect(Self.colour(of: row, at: NSPoint(x: 200, y: 21)).near(hover), "\(name): the hover fill")
        row.isSelected = true
        row.isEmphasized = true
        let chosen = Self.colour(of: Swatch(NSColor(look.fillSelected), over: NSColor(look.canvas)), at: NSPoint(x: 5, y: 5))
        #expect(Self.colour(of: row, at: NSPoint(x: 200, y: 21)).near(chosen), "\(name): a chosen row keeps its fill under the pointer")
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
