import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Synchronization
import Testing

/// A new project's page shows its build (`buildCard` of the first-impressions page, section 6): the
/// card is laid out whole at the least window and wide, mirrored right to left; the page reads its
/// template every 2 s while it builds, through the section's pause, until it is built or the page
/// is left, signed out of or hidden, and once again each time it is shown; Build Again asks the build
/// once however often it is pressed.
@MainActor @Suite(.serialized, .timeLimit(.minutes(1))) struct BuildCardViewTests {
    init() {
        ExitWatch.install()
    }

    static let dana = WorkspacesModel.Person(handle: "dana", admin: true, confirmed: true, controlPlane: "127.0.0.1", creates: true, builds: true)
    static let long = "acme-payments-reconciliation-service"
    static let read = "GET /v1/projects/lighthouse/template"
    static let build = "POST /v1/projects/lighthouse/template%3Abuild"
    /// The least window's card: the narrowest card less the page's sides and the frame's edge.
    static let least = WorkspacesViewTests.narrowestCard - 2 * ProjectView.padding.sides - 2

    /// The golden `name`'s template answer for project `project`, its build at `step`.
    static func template(_ name: String, project: String = "lighthouse", step: Int? = nil) throws -> Exchange {
        var exchange = try Exchange(name)
        if let step { exchange = try exchange.altered(at: ["build", "step"], to: step) }
        let said = String(decoding: exchange.body, as: UTF8.self).replacingOccurrences(of: "lighthouse", with: project).replacingOccurrences(of: "\"harbor\"", with: "\"\(project)\"")
        return Exchange(method: "GET", path: "/v1/projects/\(project)/template", status: exchange.status, body: Data(said.utf8))
    }

    /// The card project `project`'s page draws for `person` once `golden` is read, and `refused` when
    /// a build asked was refused.
    static func card(_ golden: String, project: String = Self.long, step: Int? = nil, person: WorkspacesModel.Person = Self.dana, refused: String? = nil) throws -> WorkspacesModel.BuildCard {
        let exchange = try template(golden, project: project, step: step)
        let status = try Endpoint.template(project: project).decode(HTTPResponse(status: exchange.status, body: exchange.body))
        var model = WorkspacesModel(person: person)
        model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: project, containerStatus: .known(.notCreated), template: status.state)], total: 1)))
        model.listed(.success(WorkspaceList(project: project, workspaces: [])), request: model.choose(project), at: Date(timeIntervalSince1970: 0))
        model.templateRead(project, .success(status))
        if let refused {
            let refusal = try Exchange(refused), answer: Result<TemplateStatus, SailError>
            do throws(SailError) {
                answer = .success(try Endpoint.buildTemplate(project: project).decode(HTTPResponse(status: refusal.status, body: refusal.body)))
            } catch {
                answer = .failure(error)
            }
            _ = model.asksBuild(project)
            model.built(project, answer)
        }
        guard case let .shown(_, .project(page)) = model.screen, case let .build(card) = page.body else { throw GoldenError(reason: "no card: \(model.screen)") }
        return card
    }

    static func shown(_ build: WorkspacesModel.BuildCard, width: Double, direction: NSUserInterfaceLayoutDirection = .leftToRight) throws -> BuildCard {
        let card = BuildCard(look: .light, titleFont: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        card.userInterfaceLayoutDirection = direction
        card.show(build)
        card.frame = NSRect(x: 0, y: 0, width: width, height: card.height(width: width))
        card.layout()
        return card
    }

    /// Every line of the card is whole at the least window and wide, a long project's name and the
    /// server's long sentences included: each wraps within its own place, none runs past the card's
    /// sides or into another, and the card is as tall as all of them.
    @Test(arguments: [false, true])
    func theCardIsLaidOutWhole(_ wide: Bool) throws {
        let width = wide ? 600 : Self.least
        let builds = [
            try Self.card("template-2e-building", step: 10), try Self.card("template-2h-failed", refused: "refusal-template-read-only"),
            try Self.card("template-2h-failed", person: WorkspacesViewTests.ana),
        ]
        for build in builds {
            for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
                let card = try Self.shown(build, width: width, direction: direction)
                let laid = card.laid(width: width, now: Date(timeIntervalSince1970: 0))
                let boxes = laid.texts.map { $0.rect } + [laid.again].compactMap { $0 }
                for (text, rect) in laid.texts {
                    #expect(rect.minX >= BuildCard.padding.sides - 0.5 && rect.maxX <= width - BuildCard.padding.sides + 0.5, "'\(text.string)' at \(rect) in \(width)")
                    #expect(BuildCard.height(text, Double(rect.width)) <= Double(rect.height) + 0.5, "'\(text.string)' cut in \(rect)")
                    #expect(Double(rect.maxY) + BuildCard.compact.padding <= laid.height + 0.5, "'\(text.string)' below the card")
                }
                for (index, one) in boxes.enumerated() {
                    for other in boxes[(index + 1)...] {
                        #expect(!one.insetBy(dx: 0.5, dy: 0.5).intersects(other.insetBy(dx: 0.5, dy: 0.5)), "\(one) over \(other) at \(width): \(build.title)")
                    }
                }
                if build.buildsAgain {
                    #expect(!card.again.isHidden && card.bounds.contains(card.again.frame), "Build Again at \(card.again.frame) outside \(card.bounds)")
                }
            }
        }
    }

    /// Right to left, the card is mirrored: the mark, the running group's spinner and Build Again
    /// stand at the trailing side, each where the left-to-right card has it, measured from the other
    /// edge; so does every part it draws (each text, the bar and its fill, which runs from the right,
    /// the groups' icons, the tinted box and the refusal's mark).
    @Test func theCardIsMirroredRightToLeft() throws {
        let width = 600.0, now = Date(timeIntervalSince1970: 0)
        let mirror = { (rect: NSRect) in NSRect(x: width - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height)) }
        for build in [try Self.card("template-2e-building", step: 10), try Self.card("template-2h-failed"), try Self.card("template-2h-failed", refused: "refusal-template-read-only")] {
            let ltr = try Self.shown(build, width: width), rtl = try Self.shown(build, width: width, direction: .rightToLeft)
            for (one, other) in [(ltr.mark, rtl.mark), (ltr.spinner, rtl.spinner), (ltr.again, rtl.again)] where !one.isHidden {
                #expect(abs(Double(other.frame.minX) - (width - Double(one.frame.maxX))) < 0.5 && other.frame.minY == one.frame.minY, "\(one.frame) mirrored to \(other.frame)")
                #expect(Double(other.frame.minX) > width / 2, "\(other.frame) stays at the leading side")
            }
            let left = ltr.laid(width: width, now: now), right = rtl.laid(width: width, now: now)
            #expect(right.texts.map { $0.text.string } == left.texts.map { $0.text.string })
            #expect(right.texts.map { $0.rect } == left.texts.map { mirror($0.rect) }, "a text not mirrored: \(right.texts.map { $0.rect })")
            #expect(right.icons.map { $0.rect } == left.icons.map { mirror($0.rect) } && right.icons.map { $0.mark } == left.icons.map { $0.mark }, "an icon not mirrored")
            #expect(right.mark == mirror(left.mark) && right.bar == left.bar.map(mirror) && right.box == left.box.map(mirror), "the mark, the bar or the box not mirrored")
            #expect(right.again == left.again.map(mirror) && right.refusalMark == left.refusalMark.map(mirror), "Build Again or the refusal's mark not mirrored")
            let bar = try #require(right.bar), filled = try #require(right.filled)
            #expect(filled.maxX == bar.maxX && filled.width < bar.width && right.filled == left.filled.map(mirror), "the fill at \(filled) in \(bar) does not run from the right")
        }
    }

    /// While the window is off screen (closed, in the Dock, the app hidden) the reads stop and the
    /// time does not tick, nor while the section is hidden; back on screen, the template is read
    /// again and the time ticks, through a scroll's tracking too.
    @Test func offScreenNothingIsReadAndNothingTicks() async throws {
        let pauses = Pauses()
        let (view, window, desk) = try await Self.section("building", "template-2e-building", pauses)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { view.text.contains("Creating lighthouse") && pauses.count == 1 }, "\(view.text)")
        let card = view.project.buildCard, ticks = try #require(card.ticks)
        #expect(CFRunLoopContainsTimer(CFRunLoopGetMain(), unsafeBitCast(ticks, to: CFRunLoopTimer.self), .commonModes), "the time stops while a scroll tracks")
        view.away = true
        #expect(await RunnerTabTests.until { pauses.cancelled == 1 }, "a read went on off screen")
        #expect(card.ticks == nil, "the time ticked off screen")
        view.away = false
        #expect(await RunnerTabTests.until { pauses.count == 2 }, "not read again back on screen")
        let reads = await Self.reads(desk)
        #expect(card.ticks != nil && reads == 2)
        view.isHidden = true
        #expect(card.ticks == nil, "the time ticked in a hidden section")
        view.isHidden = false
        #expect(card.ticks != nil, "the time stopped in the section shown again")
    }

    /// The shell tells Workspaces when the window goes off screen and comes back, wherever it tells
    /// the files.
    @Test func theShellTellsWorkspacesTheWindow() async throws {
        let desk = try Desk(["whoami"])
        let shell = try WelcomeViewTests.shell(desk, defaults: WelcomeViewTests.reached(), vault: OneSession(fde: "uday", lasts: AccountPaneTests.month))
        defer { shell.window.orderOut(nil) }
        _ = shell.windowShouldClose(shell.window)
        #expect(shell.workspaces.away, "the window closed")
        _ = shell.applicationShouldHandleReopen(NSApplication.shared, hasVisibleWindows: false)
        #expect(!shell.workspaces.away, "the window open again")
        shell.windowDidMiniaturize(Notification(name: NSWindow.didMiniaturizeNotification))
        #expect(shell.workspaces.away, "the window in the Dock")
        shell.windowDidDeminiaturize(Notification(name: NSWindow.didDeminiaturizeNotification))
        #expect(!shell.workspaces.away, "the window back from the Dock")
        shell.applicationDidHide(Notification(name: NSApplication.didHideNotification))
        #expect(shell.workspaces.away, "the app hidden")
        shell.applicationDidUnhide(Notification(name: NSApplication.didUnhideNotification))
        #expect(!shell.workspaces.away, "the app shown again")
        await shell.checking?.value
    }

    /// A read the server refused stops the reads where it is, never said as no answer: a refusal in
    /// the server's words on the card; a project gone lists the projects again, so the page follows
    /// what is true; an ended session says nothing on the card, and the client tells it once.
    @Test(arguments: ["refusal-forbidden", "refusal-project-not-in-catalog-sessions", "refusal-missing-bearer-token"])
    func aRefusedReadStopsTheReads(_ golden: String) async throws {
        let pauses = Pauses()
        let ended = Told()
        let (view, window, desk) = try await Self.section("building", "template-2e-building", pauses, ended: ended)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { view.text.contains("Creating lighthouse") && pauses.count == 1 }, "\(view.text)")
        let refusal = try Exchange(golden)
        await desk.answer(Exchange(method: "GET", path: "/v1/projects/lighthouse/template", status: refusal.status, body: refusal.body))
        await desk.answer(try Exchange("projects").altered(at: ["projects"], to: [["name": "harbor", "container_status": "running", "template": "ready"]]))
        let lists = await desk.asked.filter { $0 == "GET /v1/projects" }.count
        pauses.letGo()
        switch golden {
        case "refusal-forbidden":
            #expect(await RunnerTabTests.until { view.text.contains("Enrollment ticket is invalid or expired.") }, "\(view.text)")
            #expect(view.project.buildCard.ticks == nil, "the time ticked on a card no longer read")
        case "refusal-project-not-in-catalog-sessions":
            #expect(await RunnerTabTests.until { Self.row(view) == nil && !view.text.contains("Creating lighthouse") }, "the page kept a project gone: \(view.text)")
            #expect(await desk.asked.filter { $0 == "GET /v1/projects" }.count == lists + 1)
        default:
            #expect(await RunnerTabTests.until { ended.times == 1 }, "the ended session was not told")
            #expect(view.text.contains("Creating lighthouse") && !view.text.lowercased().contains("bearer"), "\(view.text)")
        }
        let reads = await Self.reads(desk)
        #expect(!view.text.contains("No answer just now") && pauses.count == 1 && reads == 2, "read again after a refusal: \(view.text)")
    }

    /// A first read that finds the session ended says nothing, and the client tells it; the check
    /// finding it holding shows the section again, which reads the template once more, so the page
    /// does not wait on the loading frame for ever.
    @Test func aFirstReadWithAnEndedSessionIsReadOnceTheSectionIsShownAgain() async throws {
        let pauses = Pauses()
        let ended = Told()
        let (view, window, desk) = try await Self.section("building", "refusal-missing-bearer-token", pauses, ended: ended)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { ended.times == 1 }, "the ended session was not told: \(view.text)")
        #expect(!view.text.lowercased().contains("bearer") && !view.text.contains("Creating lighthouse") && pauses.count == 0, "\(view.text)")
        await desk.answer(try Self.template("template-2e-building"))
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("Creating lighthouse") && pauses.count == 1 }, "not read again once shown: \(view.text)")
        #expect(await Self.reads(desk) == 2 && ended.times == 1)
    }

    /// At the least window a stopped build's Build Again stands inside the card's frame, in view with
    /// no scroll: the card is drawn tighter there.
    @Test func atTheLeastWindowBuildAgainIsInView() async throws {
        let pauses = Pauses()
        let (view, window, _) = try await Self.section("failed", "template-2h-failed", pauses)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { view.text.contains("Build Again picks up at step 13") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        let card = view.project.buildCard, frame = view.project.buildFrame
        #expect(Double(frame.frame.height) == card.height(width: Double(frame.frame.width) - 2) + 2, "the frame \(frame.frame) not the card's height at its width")
        window.setContentSize(NSSize(width: WorkspacesViewTests.narrowestCard, height: ShellApp.leastFrame.height - ShellApp.bandHeight - 4))
        view.layoutSubtreeIfNeeded()
        #expect(Double(frame.frame.height) == card.height(width: Double(frame.frame.width) - 2) + 2, "the frame \(frame.frame) kept the card's height at the width before")
        let button = card.convert(card.again.alignmentRect(forFrame: card.again.frame), to: frame)
        #expect(!card.again.isHidden && frame.bounds.insetBy(dx: 1, dy: 1).contains(button), "Build Again at \(button) in a frame \(frame.bounds)")
        let narrow = view.project.narrow
        try #require(!card.needsLayout)
        view.project.setFrameSize(NSSize(width: narrow ? ProjectView.wide + 100 : ProjectView.wide - 100, height: view.project.frame.height))
        #expect(view.project.narrow != narrow && !card.needsLayout, "the page drawn again for its own width laid the same card out again")
    }

    /// Build Again only for those who may build; while it is asked it says so.
    @Test func buildAgainIsOfferedOnlyToThoseWhoBuild() throws {
        #expect(try !Self.shown(try Self.card("template-2h-failed"), width: 600).again.isHidden)
        #expect(try Self.shown(try Self.card("template-2h-failed", person: WorkspacesViewTests.ana), width: 600).again.isHidden)
        #expect(try Self.shown(try Self.card("template-2e-building"), width: 600).again.isHidden)
    }

    /// The section with lighthouse listed as `state` (and harbor, ready), its template read as `golden`,
    /// each pause between reads held by `pauses`.
    static func section(_ state: String, _ golden: String, _ pauses: Pauses, ended: Told = Told()) async throws -> (WorkspacesView, KeyWindow, Desk) {
        let desk = try Desk([])
        await desk.answer(try Exchange("projects").altered(at: ["projects"], to: [["name": "harbor", "container_status": "running", "template": "ready"], ["name": "lighthouse", "container_status": "not_created", "template": state]]))
        let none = try Exchange("workspaces-none")
        for project in ["harbor", "lighthouse"] {
            await desk.answer(Exchange(method: "GET", path: "/v1/projects/\(project)/workspaces", status: none.status, body: none.body))
        }
        await desk.answer(try Self.template(golden))
        let (view, window) = try WorkspacesViewTests.view(desk, chosenLast: "lighthouse", ended: ended)
        view.pause = { try await pauses.pause($0) }
        view.person = Self.dana
        view.shown()
        return (view, window, desk)
    }

    /// Lighthouse's row in the panel as VoiceOver reads it: its name and its pill.
    static func row(_ view: WorkspacesView) -> String? {
        view.panel.list.entries.lazy.compactMap { entry -> String? in
            guard case let .item(item) = entry, item.id == "lighthouse" else { return nil }
            return item.voice
        }.first
    }

    static func reads(_ desk: Desk) async -> Int {
        await desk.asked.filter { $0 == Self.read }.count
    }

    /// A building project's page reads its template, then again 2 s after each answer, the card and
    /// the panel's row following; built, the card gives way to the page of a project with a template,
    /// New Workspace offered with no refresh, and nothing more is read. The reads ask neither the
    /// projects nor the workspaces again.
    @Test func aBuildingPageReadsItsTemplateUntilItIsBuilt() async throws {
        let pauses = Pauses()
        let (view, window, desk) = try await Self.section("building", "template-2e-building", pauses)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { view.text.contains("Creating lighthouse") && pauses.count == 1 }, "\(view.text)")
        #expect(view.project.newWorkspace.isHidden && view.project.newWorkspaceIcon.isHidden && view.project.listAgain.isHidden, "the page's buttons beside the card")
        let lists = await desk.asked.filter { $0 != Self.read }
        #expect(Self.row(view) == "lighthouse, building")
        await desk.answer(try Self.template("template-2e-building", step: 12))
        pauses.letGo()
        #expect(await RunnerTabTests.until { view.text.contains("step 12 of 20") && pauses.count == 2 }, "\(view.text)")
        await desk.answer(try Self.template("template-2g-built"))
        pauses.letGo()
        #expect(await RunnerTabTests.until { view.text.contains("No workspaces in lighthouse yet") }, "\(view.text)")
        #expect(!view.project.newWorkspace.isHidden && view.project.newWorkspace.isEnabled, "New Workspace not offered once built")
        #expect(Self.row(view) == "lighthouse", "the panel's row still says \(Self.row(view) ?? "nothing")")
        #expect(await desk.asked.filter { $0 != Self.read } == lists, "the reads asked for more than the template")
        let reads = await Self.reads(desk)
        #expect(reads == 3 && pauses.asked == [.seconds(2), .seconds(2)])
    }

    /// The reads stop when the page is left for another project, signed out of or hidden; shown
    /// again, it is read once more, never two reads at once.
    @Test func leavingThePageStopsItsReads() async throws {
        let pauses = Pauses()
        let (view, window, desk) = try await Self.section("building", "template-2e-building", pauses)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { pauses.count == 1 }, "\(view.text)")
        view.panel.onChoose("harbor")
        view.panel.onChoose("lighthouse")
        #expect(await RunnerTabTests.until { pauses.count == 2 }, "not read again once shown again: \(view.text)")
        #expect(await Self.reads(desk) == 2, "read after the page was left")
        view.isHidden = true
        view.isHidden = false
        #expect(await RunnerTabTests.until { pauses.count == 3 }, "not read again once the section was shown again")
        #expect(await Self.reads(desk) == 3)
        view.signedOut()
        #expect(await RunnerTabTests.until { pauses.cancelled == 3 }, "a pause outlived its page")
        #expect(await Self.reads(desk) == 3, "read after signing out")
    }

    /// Build Again with a session that has ended says nothing on the card, and the client tells it
    /// once.
    @Test func buildAgainWithAnEndedSessionHandsItOver() async throws {
        let pauses = Pauses()
        let ended = Told()
        let (view, window, desk) = try await Self.section("failed", "template-2h-failed", pauses, ended: ended)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { view.text.contains("lighthouse’s template did not finish") }, "\(view.text)")
        let joined = try Exchange("template-2f-joined"), refusal = try Exchange("refusal-missing-bearer-token")
        await desk.answer(Exchange(method: joined.method, path: joined.path, status: refusal.status, body: refusal.body))
        let again = view.project.buildCard.again
        _ = again.sendAction(again.action, to: again.target)
        #expect(await RunnerTabTests.until { ended.times == 1 && !view.busy }, "the ended session was not told: \(view.text)")
        #expect(!view.text.lowercased().contains("bearer") && view.text.contains("lighthouse’s template did not finish"), "\(view.text)")
    }

    /// Build Again on a failed build asks the build once however often it is pressed while asked;
    /// its answer draws the build running.
    @Test func buildAgainAsksTheBuildOnce() async throws {
        let pauses = Pauses()
        let (view, window, desk) = try await Self.section("failed", "template-2h-failed", pauses)
        defer { window.orderOut(nil) }
        #expect(await RunnerTabTests.until { view.text.contains("lighthouse’s template did not finish") }, "\(view.text)")
        let again = view.project.buildCard.again
        #expect(!again.isHidden && pauses.count == 0, "a failed build read again")
        await desk.answer(try Exchange("template-2f-joined"))
        await desk.answer(try Self.template("template-2e-building", step: 13))
        await desk.hold(Self.build)
        for _ in 0 ..< 3 {
            _ = again.sendAction(again.action, to: again.target)
        }
        await desk.arrived(during: Task { _ = await RunnerTabTests.until { false } })
        _ = again.sendAction(again.action, to: again.target)
        await desk.release()
        #expect(await RunnerTabTests.until { view.text.contains("Creating lighthouse") && pauses.count == 1 }, "\(view.text)")
        #expect(await desk.asked.filter { $0 == Self.build }.count == 1)
    }
}

/// The pauses between a template's reads, each held until let go, or until its task is cancelled.
final class Pauses: Sendable {
    private let held = Mutex<(asked: [Duration], gates: [Gate], cancelled: Int)>(([], [], 0))

    var count: Int {
        held.withLock { $0.asked.count }
    }

    var asked: [Duration] {
        held.withLock { $0.asked }
    }

    /// How many pauses ended by their task's cancellation.
    var cancelled: Int {
        held.withLock { $0.cancelled }
    }

    func pause(_ duration: Duration) async throws {
        let gate = Gate()
        held.withLock {
            $0.asked.append(duration)
            $0.gates.append(gate)
        }
        await gate.wait()
        if Task.isCancelled { held.withLock { $0.cancelled += 1 } }
        try Task.checkCancellation()
    }

    /// Lets the last pause go.
    func letGo() {
        held.withLock { $0.gates.last }?.open()
    }
}
