import Foundation
import SailAPI
import SailKitTestSupport
import Synchronization
import Testing

/// A new project's page shows its template building (`buildCard` of the first-impressions page,
/// section 6): the card drawn from the server's answer, its groups found by step name and its bar by
/// the server's step of its steps; the page reads the template every 2 s while it builds and stops
/// when the page is left, the project changes or the build ends, one read at a time; a failure says
/// why in words with Build Again for those who may build, which asks once however often it is
/// pressed; a refused build is said on the card.
@Suite(.timeLimit(.minutes(1))) struct BuildCardTests {
    static let dana = WorkspacesModel.Person(handle: "dana", admin: true, confirmed: true, controlPlane: "https://sail.example.com", creates: true, builds: true)
    static let ana = WorkspacesModel.Person(handle: "ana", admin: false, confirmed: true, controlPlane: "https://sail.example.com")

    /// The golden `name`'s template answer with its build's `step`, and `changes` made to it.
    static func status(_ name: String, step: Int? = nil, _ changes: [([any JSONPathStep], Any?)] = []) throws -> TemplateStatus {
        var exchange = try Exchange(name)
        if let step { exchange = try exchange.altered(at: ["build", "step"], to: step) }
        for (path, value) in changes {
            exchange = try exchange.altered(at: path, to: value)
        }
        return try Endpoint.template(project: "lighthouse").decode(response(exchange))
    }

    /// `person`'s model with lighthouse listed as `state` and chosen, its workspaces none.
    static func model(_ person: WorkspacesModel.Person = Self.dana, _ state: TemplateState = .building) throws -> WorkspacesModel {
        var model = WorkspacesModel(person: person)
        model.timeZone = try #require(TimeZone(identifier: "UTC"))
        let listed = ProjectList(projects: ["harbor", "lighthouse"].map { ProjectSummary(name: $0, containerStatus: .known(.notCreated), template: .known($0 == "harbor" ? .ready : state)) }, total: 2)
        model.projectsListed(.success(listed))
        model.listed(.success(WorkspaceList(project: "lighthouse", workspaces: [])), request: model.choose("lighthouse"), at: WorkspacesModelTests.at)
        return model
    }

    static func card(_ model: WorkspacesModel) -> WorkspacesModel.Build? {
        guard case let .build(card)? = WorkspacesModelTests.page(model)?.body else { return nil }
        return card
    }

    /// The template route answers its state, its steps by name and its build, which the card is
    /// drawn from; a template with no build answers none.
    @Test func theTemplateIsReadAsTheServerSaysIt() throws {
        let building = try Self.status("template-2e-building")
        #expect(building.project == "lighthouse" && building.state == .known(.building) && building.steps == 20 && building.stepNames.count == 20)
        let build = try #require(building.build)
        #expect(build.by == "dana" && build.step == 9 && build.startedAt.milliseconds == 1_794_387_350_656 && build.endedAt == nil && build.reason == nil)
        let failed = try #require(try Self.status("template-2h-failed").build)
        #expect(failed.step == 13 && failed.endedAt != nil && failed.reason?.hasPrefix("github.com did not give lighthouse's repo lamp") == true && failed.remedy?.count == 3)
        #expect(try Self.status("template-2c-missing").build == nil)
    }

    /// The card's lines over the server's steps: "Project saved", then each group found by the name
    /// of its first step, done, running or to come by the step the build is on.
    @Test(arguments: [(1, 1), (4, 1), (5, 2), (12, 2), (13, 3), (14, 3), (15, 4), (18, 4), (19, 5), (20, 5)])
    func theGroupsAreFoundByStepName(_ step: Int, _ running: Int) throws {
        let card = try #require(Self.card(Self.read(try Self.status("template-2e-building", step: step))))
        let names = ["Project saved", "Container created", "Tools installed", "Repositories cloned", "Agents installed", "Ready for workspaces"]
        #expect(card.groups.map(\.name) == names)
        let marks: [WorkspacesModel.Build.Mark] = (0 ..< 6).map { $0 < running ? .done : $0 == running ? .running : .coming }
        #expect(card.groups.map(\.mark) == marks, "step \(step)")
        #expect(card.lines.contains("\(names[running]) · running"))
    }

    /// A step the app does not know joins the group before it, so the groups stay five and no group
    /// is found by a step's position: with one more step among the tools, the build's step 13 is still
    /// Maven at step 14, and the repositories still come; one before the first known step joins the first
    /// group.
    @Test func aStepTheAppDoesNotKnowJoinsTheGroupBeforeIt() throws {
        var names = try Self.status("template-2e-building").stepNames
        names.insert("gpu_drivers_installed", at: try #require(names.firstIndex(of: "jdk_installed")) + 1)
        names.insert("image_pulled", at: 0)
        let card = try #require(Self.card(Self.read(try Self.status("template-2e-building", step: 14, [(["step_names"], names), (["steps"], 22), (["build", "steps"], 22)]))))
        #expect(card.groups.map(\.name) == ["Project saved", "Container created", "Tools installed", "Repositories cloned", "Agents installed", "Ready for workspaces"])
        #expect(card.groups.map(\.mark) == [.done, .done, .running, .coming, .coming, .coming], "\(card.groups)")
        let first = try #require(Self.card(Self.read(try Self.status("template-2e-building", step: 1, [(["step_names"], names), (["steps"], 22)]))))
        #expect(first.groups.map(\.mark) == [.done, .running, .coming, .coming, .coming, .coming], "\(first.groups)")
    }

    /// The bar is filled by the server's step of the server's steps, and says so: never a count of
    /// the app's own.
    @Test func theBarIsTheServersStepOfItsSteps() throws {
        let card = try #require(Self.card(Self.read(try Self.status("template-2e-building", step: 10))))
        #expect(card.at == "step 10 of 20" && card.done == 0.5 && card.stage == .building)
        var names = try Self.status("template-2e-building").stepNames
        names += ["cache_warmed", "smoke_tested", "image_published"]
        let more = try #require(Self.card(Self.read(try Self.status("template-2e-building", step: 10, [(["step_names"], names), (["steps"], 23)]))))
        #expect(more.at == "step 10 of 23" && more.done == 10.0 / 23.0)
        let early = try #require(Self.card(Self.read(try Self.status("template-2e-building", step: 0))))
        #expect(early.at == "step 1 of 20", "a build before its first step said \(early.at ?? "")")
    }

    /// Who started it and when, at the person's clock: "you" for the person, else their handle; the
    /// elapsed time from its start, past an hour with the hours.
    @Test func theCardSaysWhoStartedItAndWhen() throws {
        let mine = try #require(Self.card(Self.read(try Self.status("template-2e-building"))))
        #expect(mine.title == "Creating lighthouse" && mine.started == "Started by you at 08:55")
        #expect(mine.foot == "You can close this; the build keeps going on the runner." && !mine.buildsAgain && mine.reason.isEmpty)
        let theirs = try #require(Self.card(Self.read(try Self.status("template-2e-building"), as: Self.ana)))
        #expect(theirs.started == "Started by dana at 08:55" && theirs.title == "Creating lighthouse")
        let start = try #require(mine.startedAt)
        #expect(mine.endedAt == nil)
        #expect(WorkspacesModel.Build.elapsed(from: start, to: start.addingTimeInterval(258)) == "4:18")
        #expect(WorkspacesModel.Build.elapsed(from: start, to: start.addingTimeInterval(3858)) == "1:04:18")
        #expect(WorkspacesModel.Build.elapsed(from: start, to: start.addingTimeInterval(-5)) == "0:00")
    }

    /// A failure: which step stopped it, the failed group's cross, the server's reason and its remedy
    /// in words (never a command), Build Again for those who may build with what it keeps; others are
    /// told who can. An interrupted build's remedy is the server's own words.
    @Test func aFailureSaysWhyInWords() throws {
        let failed = try #require(Self.card(Self.read(try Self.status("template-2h-failed"), state: .failed)))
        #expect(failed.stage == .failed && failed.title == "lighthouse’s template did not finish" && failed.at == "stopped at step 13 of 20")
        #expect(failed.groups.map(\.mark) == [.done, .done, .done, .failed, .coming, .coming])
        #expect(failed.reason == [
            "github.com did not give lighthouse's repo lamp to the token stored for it: the token may not read the repo, or the repo may not exist.", "An admin stores it on the runner for now.",
        ])
        #expect(failed.buildsAgain && failed.foot == "Build Again picks up at step 13; the 12 before it are kept." && failed.endedAt != nil)
        let theirs = try #require(Self.card(Self.read(try Self.status("template-2h-failed"), state: .failed, as: Self.ana)))
        #expect(!theirs.buildsAgain && theirs.foot == "Ask a member or an admin to build it again." && theirs.started == "Started by dana at 08:55")
        let cut = try #require(Self.card(Self.read(try Self.status("template-2h-interrupted"), state: .failed)))
        #expect(cut.reason == ["The build stopped before it finished: the runner restarted, or its process ended.", "Build it again: it picks up at the step that did not finish."])
        #expect(cut.foot == "Build Again picks up at step 9; the 8 before it are kept.")
        let second = try #require(Self.card(Self.read(try Self.status("template-2h-failed", step: 2), state: .failed)))
        #expect(second.foot == "Build Again picks up at step 2; the step before it is kept.")
    }

    /// The card follows each read: a read with no answer keeps it as it was, saying so quietly; built,
    /// it gives way to the page of a project with a template, New Workspace offered with no refresh.
    @Test func theCardFollowsEachRead() throws {
        var model = try Self.model()
        #expect(WorkspacesModelTests.page(model)?.body == .loading, "a card drawn before the template was read")
        #expect(WorkspacesModelTests.page(model)?.lede == "Workspaces open once the template is ready.")
        model.templateRead("lighthouse", .success(try Self.status("template-2e-building", step: 10)))
        let card = try #require(Self.card(model))
        model.templateRead("lighthouse", .failure(.unreachable("lost")))
        let kept = try #require(Self.card(model))
        #expect(kept.missed == "No answer just now; asking again" && kept.at == card.at && kept.groups == card.groups)
        #expect(kept.lines.contains("step 10 of 20 · No answer just now; asking again"))
        model.templateRead("lighthouse", .success(try Self.status("template-2e-building", step: 11)))
        #expect(Self.card(model)?.missed == nil && Self.card(model)?.at == "step 11 of 20")
        model.templateRead("lighthouse", .success(try Self.status("template-2g-built", [(["project"], "lighthouse")])))
        let page = try #require(WorkspacesModelTests.page(model))
        guard case let .empty(empty) = page.body else { Issue.record("still \(page.body)"); return }
        #expect(empty.title == "No workspaces in lighthouse yet" && page.canCreate && page.createWhy == nil)
        guard case let .shown(panel, _) = model.screen else { return }
        #expect(panel.projects.first { $0.name == "lighthouse" }?.mark == nil, "the pill still says the template is not current")
        model.templateRead("lighthouse", .success(try Self.status("template-2h-failed")))
        #expect(Self.card(model)?.stage == .failed)
        model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "lighthouse", containerStatus: .known(.notCreated), template: .known(.building))], total: 1)))
        #expect(WorkspacesModelTests.page(model)?.body == .loading, "a failed build drawn while the projects listed say it builds")
    }

    /// The template is read while its page shows a build under way, and stops being read when the page
    /// is left for This Mac or another project, or the build ends; a failed build is read once each
    /// time its page is shown.
    @Test func theReadsStopWhenThePageIsLeftOrTheBuildEnds() throws {
        var model = try Self.model()
        #expect(model.templateShown == "lighthouse" && model.readsAgain("lighthouse"))
        model.chooseThisMac()
        #expect(model.templateShown == nil && !model.readsAgain("lighthouse"))
        _ = model.choose("lighthouse")
        #expect(model.readsAgain("lighthouse"))
        _ = model.choose("harbor")
        #expect(model.templateShown == nil && !model.readsAgain("lighthouse"))
        _ = model.choose("lighthouse")
        model.templateRead("lighthouse", .success(try Self.status("template-2h-failed")))
        #expect(model.templateShown == "lighthouse" && !model.readsAgain("lighthouse"))
        model.templateRead("lighthouse", .success(try Self.status("template-2e-building")))
        model.templateRead("lighthouse", .success(try Self.status("template-2g-built", [(["project"], "lighthouse")])))
        #expect(model.templateShown == nil && !model.readsAgain("lighthouse"))
    }

    /// Reads one at a time, `SailClient.templateReadsEvery` after each answer, through the injected
    /// sleep, until the model says the build ended; a read with no answer keeps reading.
    @MainActor @Test func theTemplateIsReadEveryTwoSecondsUntilTheBuildEnds() async throws {
        let reads = Reads([
            .success(try Exchange("template-2e-building")), .failure(.unreachable("lost")), .success(try Exchange("template-2e-building").altered(at: ["build", "step"], to: 12)),
            .success(try Exchange("template-2g-built").altered(at: ["project"], to: "lighthouse")),
        ])
        var model = try Self.model()
        let slept = Mutex<[Duration]>([])
        var seen: [String] = []
        await SailClient(transport: reads, token: "tok").readTemplate("lighthouse", sleep: { duration in slept.withLock { $0.append(duration) } }) { answer in
            model.templateRead("lighthouse", answer)
            seen.append(Self.card(model)?.at ?? "no card")
            return model.readsAgain("lighthouse")
        }
        #expect(seen == ["step 9 of 20", "step 9 of 20", "step 12 of 20", "no card"])
        #expect(slept.withLock { $0 } == [.seconds(2), .seconds(2), .seconds(2)])
        let asked = await reads.asked, most = await reads.most
        #expect(asked == 4 && most == 1)
    }

    /// The reads stop once the page is left between two of them, once the task is cancelled while it
    /// waits, and once a pause fails: nothing more is read or handed on.
    @MainActor @Test func theReadsStopWhenLeftOrCancelled() async throws {
        let building = try Exchange("template-2e-building")
        let left = Reads([.success(building), .success(building), .success(building)])
        var model = try Self.model()
        var answers = 0
        await SailClient(transport: left, token: "tok").readTemplate("lighthouse", sleep: { _ in }) { answer in
            answers += 1
            model.templateRead("lighthouse", answer)
            _ = model.choose("harbor")
            return model.readsAgain("lighthouse")
        }
        let leftAsked = await left.asked
        #expect(answers == 1 && leftAsked == 1)
        let cancelled = Reads([.success(building), .success(building), .success(building)])
        var handed = 0
        let reading = Task {
            await SailClient(transport: cancelled, token: "tok").readTemplate("lighthouse", sleep: { _ in
                withUnsafeCurrentTask { $0?.cancel() }
                try Task.checkCancellation()
            }) { _ in
                handed += 1
                return true
            }
        }
        await reading.value
        let cancelledAsked = await cancelled.asked
        #expect(handed == 1 && cancelledAsked == 1)
        let failing = Reads([.success(building), .success(building), .success(building)])
        var told = 0
        await SailClient(transport: failing, token: "tok").readTemplate("lighthouse", sleep: { _ in throw CancellationError() }) { _ in
            told += 1
            return told < 3
        }
        let failingAsked = await failing.asked
        #expect(told == 1 && failingAsked == 1, "a pause that failed was taken as passed")
    }

    /// Build Again asks the build once however often it is pressed while it is asked; its answer
    /// lets it be asked again.
    @Test func buildAgainAsksOnce() throws {
        var model = try Self.model(Self.dana, .failed)
        model.templateRead("lighthouse", .success(try Self.status("template-2h-failed")))
        let presses = (0 ..< 3).map { _ in model.asksBuild("lighthouse") }
        #expect(presses == [true, false, false])
        #expect(Self.card(model)?.asking == true)
        model.built("lighthouse", WorkspacesModelTests.answer(.buildTemplate(project: "lighthouse"), "refusal-template-read-only"))
        let refused = try #require(Self.card(model))
        #expect(!refused.asking && refused.stage == .failed && refused.refusal == ["Your credential is read-only and cannot build templates.", "Ask an admin for a member or admin credential."])
        model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "lighthouse", containerStatus: .known(.notCreated), template: .known(.failed))], total: 1)))
        #expect(Self.card(model)?.refusal == refused.refusal, "a list showing it failed took the refusal away")
        let again = model.asksBuild("lighthouse")
        #expect(again, "Build Again could not be asked once answered")
        model.built("lighthouse", WorkspacesModelTests.answer(.buildTemplate(project: "lighthouse"), "template-2f-joined"))
        let building = try #require(Self.card(model))
        #expect(building.stage == .building && building.refusal.isEmpty && model.readsAgain("lighthouse"))
    }

    /// A build of a project with no template asked and refused, or not answered, stands on its card
    /// in the server's words and what to do (never a command), with Build Again; until a build is
    /// asked again, or the template is listed or read as being built.
    @Test func aRefusedBuildIsSaidOnTheCard() throws {
        var model = try Self.model(Self.dana, .missing)
        guard case .empty = WorkspacesModelTests.page(model)?.body else { Issue.record("\(String(describing: WorkspacesModelTests.page(model)?.body))"); return }
        _ = model.asksBuild("lighthouse")
        model.built("lighthouse", WorkspacesModelTests.answer(.buildTemplate(project: "lighthouse"), "refusal-template-2d-login-not-stored"))
        let refused = try #require(Self.card(model))
        #expect(refused.stage == .unstarted && refused.title == "lighthouse’s template did not start" && refused.buildsAgain && refused.groups.isEmpty)
        #expect(refused.refusal == ["github.com asks for credentials to fetch lighthouse's repo lamp, and dana keeps no token for it.", "An admin stores it on the runner for now."])
        #expect(WorkspacesModelTests.page(model)?.banner == nil, "the refusal stands over the page too")
        model.built("lighthouse", .failure(.unreachable("The request timed out.")))
        #expect(Self.card(model)?.refusal == ["Sail cannot reach the control plane at https://sail.example.com."])
        model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "lighthouse", containerStatus: .known(.notCreated), template: .known(.missing))], total: 1)))
        #expect(Self.card(model)?.refusal.isEmpty == false, "a list showing no build took the refusal away")
        model.templateRead("lighthouse", .success(try Self.status("template-2e-building")))
        #expect(Self.card(model)?.stage == .building && Self.card(model)?.refusal.isEmpty == true)
    }

    /// `status` read on `person`'s model with lighthouse listed as `state`.
    static func read(_ status: TemplateStatus, state: TemplateState = .building, as person: WorkspacesModel.Person = Self.dana) throws -> WorkspacesModel {
        var model = try Self.model(person, state)
        model.templateRead("lighthouse", .success(status))
        return model
    }
}

/// A server answering the template's reads in turn, one at a time as asked, counting how many were
/// asked and the most under way at once.
actor Reads: Transport {
    private var answers: [Result<Exchange, SailError>]
    private(set) var asked = 0, most = 0
    private var underWay = 0

    init(_ answers: [Result<Exchange, SailError>]) {
        self.answers = answers
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        asked += 1
        underWay += 1
        most = max(most, underWay)
        await Task.yield()
        underWay -= 1
        guard !answers.isEmpty else { throw .unreachable("no read left for \(request.route)") }
        let exchange = try answers.removeFirst().get()
        return HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
    }

    func open(_ request: HTTPRequest) throws(SailError) -> HTTPStream {
        throw .unreachable("no stream \(request.route)")
    }
}
