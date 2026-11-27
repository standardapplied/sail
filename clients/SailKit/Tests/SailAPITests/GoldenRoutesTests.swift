import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The call this client makes for each recorded exchange, by the golden's name.
let calls: [String: @Sendable (SailClient) async throws -> Void] = [
    "whoami": { _ = try await $0.send(.whoami) },
    "projects": { _ = try await $0.send(.projects) },
    "projects-none": { _ = try await $0.send(.projects) },
    "project-created": { _ = try await $0.send(.createProject(marina)) },
    "project-definition": { _ = try await $0.send(.projectDefinition("marina")) },
    "template-2f-joined": { _ = try await $0.send(.buildTemplate(project: "lighthouse")) },
    "template-2g-current": { _ = try await $0.send(.buildTemplate(project: "lighthouse")) },
    "rooms": { _ = try await $0.send(.rooms(project: "harbor")) },
    "room": { _ = try await $0.send(.room("harbor-design")) },
    "room-bare": { _ = try await $0.send(.room("harbor-quay")) },
    "messages": { _ = try await $0.send(.messages(room: "harbor-design", limit: 50)) },
    "message-posted": { _ = try await $0.send(.post(question, room: "harbor-design")) },
    "message-reply": { _ = try await $0.send(.post(reply, room: "harbor-design")) },
    "specs": { _ = try await $0.send(.specs(SpecFilter(project: "harbor"))) },
    "spec": { _ = try await $0.send(.spec("harbor-docks")) },
    "spec-bare": { _ = try await $0.send(.spec("harbor-buoys")) },
    "refusal-read-only-credential": { _ = try await $0.send(.post(MessagePost(body: "hello"), room: "harbor-design")) },
    "refusal-room-not-found": { _ = try await $0.send(.room("nowhere")) },
    "refusal-spec-not-found": { _ = try await $0.send(.spec("nothing")) },
    "refusal-missing-bearer-token": { _ = try await $0.send(.whoami) },
    "refusal-invalid-bearer-token": { _ = try await $0.send(.whoami) },
    "refusal-bad-request": { _ = try await $0.send(.post(MessagePost(body: ""), room: "harbor-design")) },
    "refusal-not-found": { _ = try await $0.send(Endpoint<Whoami>(.get, ["v1", "nowhere"])) },
    "terminal-ticket": { _ = try await $0.send(.terminalTicket(open: "harbor")) },
    "terminal-ticket-workspace": { _ = try await $0.send(.terminalTicket(open: "harbor", workspace: "dock")) },
    "terminal-ticket-files": { _ = try await $0.send(.terminalTicket(files: "harbor", workspace: "dock")) },
    "refusal-workspace-stopped": { _ = try await $0.send(.terminalTicket(files: "harbor", workspace: "idle")) },
    "workspace-create": { _ = try await $0.send(.createWorkspace("new", project: "harbor")) },
    "workspace-start": { _ = try await $0.send(.startWorkspace("dock", project: "harbor")) },
    "workspace-stop": { _ = try await $0.send(.stopWorkspace("dock", project: "harbor")) },
    "workspace-delete": { _ = try await $0.send(.deleteWorkspace("dock", project: "harbor", force: true)) },
    "refusal-workspace-holds-work": { _ = try await $0.send(.deleteWorkspace("dock", project: "harbor")) },
    "workspaces": { _ = try await $0.send(.workspaces(project: "harbor")) },
    "workspaces-three": { _ = try await $0.send(.workspaces(project: "harbor")) },
    "workspaces-none": { _ = try await $0.send(.workspaces(project: "harbor")) },
    "workspaces-made": { _ = try await $0.send(.workspaces(project: "harbor")) },
    "refusal-read-only-credential-workspace": { _ = try await $0.send(.createWorkspace("new", project: "harbor")) },
    "refusal-conflict-workspace-exists": { _ = try await $0.send(.createWorkspace("dock", project: "harbor")) },
    "refusal-forbidden-not-assignee": { _ = try await $0.send(.startWorkspace("dock", project: "harbor")) },
    "workspace-sessions": { _ = try await $0.send(.sessions(workspace: "dock", project: "harbor")) },
    "refusal-not-found-session": { _ = try await $0.send(.terminalTicket(attach: "app-gone")) },
    "workspace-sessions-not-theirs": { _ = try await $0.send(.sessions(workspace: "dock", project: "harbor")) },
    "refusal-not-found-workspace": { _ = try await $0.send(.sessions(workspace: "gone", project: "harbor")) },
    "refusal-project-not-in-catalog-sessions": { _ = try await $0.send(.sessions(workspace: "dock", project: "harbor")) },
    "workspace-agents": { _ = try await $0.send(.agents(workspace: "berth", project: "harbor")) },
    "workspace-agents-stopped-no-login": { _ = try await $0.send(.agents(workspace: "quay", project: "harbor")) },
    "workspace-agents-not-yours": { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) },
    "workspace-agents-owner-gone": { _ = try await $0.send(.agents(workspace: "wreck", project: "harbor")) },
    "workspace-agents-dock": { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) },
    "workspace-agents-dock-signed-in": { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) },
    "workspace-agents-dock-signed-out": { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) },
    "workspace-agents-pier": { _ = try await $0.send(.agents(workspace: "pier", project: "harbor")) },
    "workspace-agents-quay": { _ = try await $0.send(.agents(workspace: "quay", project: "harbor")) },
    "workspace-agents-dock-no-login": { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) },
    "workspace-agents-dock-not-installed": { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) },
    "workspace-agents-pier-owner-gone": { _ = try await $0.send(.agents(workspace: "pier", project: "harbor")) },
    "refusal-forbidden-not-assignee-agents": { _ = try await $0.send(.agents(workspace: "dock", project: "harbor")) },
    "logins": { _ = try await $0.send(.logins) },
    "logins-no-default": { _ = try await $0.send(.logins) },
    "logins-with-a-default": { _ = try await $0.send(.logins) },
    "login-stored": { _ = try await $0.send(.storeLogin(goldenKey, kind: "api-key", harness: "claude-code", name: "default")) },
    "login-replaced": { _ = try await $0.send(.storeLogin(goldenKey, kind: "api-key", harness: "claude-code", name: "default")) },
    "login-stored-beside-the-default": { _ = try await $0.send(.storeLogin(goldenKey, kind: "api-key", harness: "claude-code", name: "work")) },
    "login-used": { _ = try await $0.send(.useLogin(harness: "claude-code", name: "work")) },
    "refusal-not-found-login": { _ = try await $0.send(.useLogin(harness: "claude-code", name: "work")) },
    "refusal-invalid-request-login-empty": { _ = try await $0.send(.storeLogin("   ", kind: "api-key", harness: "claude-code", name: "default")) },
    "refusal-invalid-request-login-lines": { _ = try await $0.send(.storeLogin(goldenKey + "\n" + goldenKey, kind: "api-key", harness: "claude-code", name: "default")) },
    "refusal-invalid-request-login-long": { _ = try await $0.send(.storeLogin(String(repeating: "k", count: 16 * 1024 + 1), kind: "api-key", harness: "claude-code", name: "default")) },
    "refusal-invalid-request-login-characters": { _ = try await $0.send(.storeLogin("sk-ant-api03 golden fake key", kind: "api-key", harness: "claude-code", name: "default")) },
    "refusal-invalid-request-login-name": { _ = try await $0.send(.storeLogin(goldenKey, kind: "api-key", harness: "claude-code", name: "Work")) },
    "login-removed": { _ = try await $0.send(.removeLogin(harness: "claude-code", name: "default")) },
    "agents": { _ = try await $0.send(.knownAgents) },
    "passkeys": { _ = try await $0.send(.passkeys) },
    "passkey-removed": { _ = try await $0.send(.removePasskey("Z29sZGVuIGNyZWRlbnRpYWxfaWQgMjQ")) },
    "refusal-conflict-passkey-current": { _ = try await $0.send(.removePasskey("Z29sZGVuIGNyZWRlbnRpYWxfaWQgNg")) },
    "refusal-conflict-passkey-last": { _ = try await $0.send(.removePasskey("Z29sZGVuIGNyZWRlbnRpYWxfaWQgNg")) },
    "refusal-forbidden-passkey-no-passkey-session": { _ = try await $0.send(.removePasskey("Z29sZGVuIGNyZWRlbnRpYWxfaWQgNg")) },
    "refusal-not-found-passkey-not-yours": { _ = try await $0.send(.removePasskey("Z29sZGVuIGNyZWRlbnRpYWxfaWQgMTU")) },
    "attention": { _ = try await $0.send(.attention(conversation: "harbor-docks")) },
    "changes": { _ = try await $0.send(.changes(since: "golden.0.0")) },
    "board-specs": { _ = try await $0.send(.specs(SpecFilter(project: "harbor"))) },
    "board-rooms": { _ = try await $0.send(.rooms(project: "harbor")) },
    "refusal-resync-required": { _ = try await $0.send(.changes(since: "999999999")) },
]

/// The project the server's golden `project-created` creates, whole.
let marina = ProjectDefinition(
    name: "marina",
    repos: [ProjectDefinition.Repo(url: "https://github.com/harbor/app.git", path: "app"), ProjectDefinition.Repo(url: "https://github.com/harbor/docs.git", path: "docs")],
    agent: ProjectDefinition.Agent(install: ["claude-code", "codex"]), resources: ProjectDefinition.Resources(cpu: 4, memory: "8GB", disk: "50GB")
)

/// The obvious fake the server's goldens store as a login's value.
let goldenKey = "sk-ant-api03-golden-fake-key"

let question = MessagePost(body: "Which berth takes the ferry — north or south? ⚓", asksQuestion: true)
let reply = MessagePost(body: "South: it has the deeper draft. 🚢", replyTo: "00000000-0000-7000-8000-000000000001")

/// What `call` answers when the server is the one that recorded `name`, and the request it sent.
func answer<Response>(
    _ name: String, _ call: (SailClient) async throws -> Response
) async throws -> (Result<Response, SailError>, HTTPRequest?) {
    let server = RecordedServer([try Exchange(name)])
    let client = SailClient(transport: server, token: "tok")
    do {
        let response = try await call(client)
        return (.success(response), await server.requests.first)
    } catch let error as SailError {
        return (.failure(error), await server.requests.first)
    }
}

@Suite struct GoldenRoutesTests {
    @Test func everyRecordedExchangeIsOneThisClientMakes() throws {
        #expect(Set(calls.keys).union(refusalsOfRoutesNotYetCalled).union(answersOfRoutesNotYetCalled).union(passkeyExchanges).union(reachExchanges)
            == Set(try Goldens.names(in: "http")))
    }

    @Test(arguments: calls.keys.sorted())
    func theClientSendsTheRecordedRequestAndReadsItsAnswer(name: String) async throws {
        let exchange = try Exchange(name)
        let (result, sent) = try await answer(name) { client in try await calls[name]?(client) }
        let request = try #require(sent)
        #expect(request.method.rawValue == exchange.method)
        #expect(request.path == exchange.path)
        #expect(request.headers["Authorization"] == "Bearer tok")
        #expect(try json(request.body) == json(exchange.requestBody))
        switch result {
        case .success:
            #expect((200 ..< 300).contains(exchange.status))
        case let .failure(.refused(refusal)):
            #expect(exchange.status >= 400)
            let named = "refusal-" + refusal.code.rawValue.replacingOccurrences(of: "_", with: "-")
            #expect(name == named || name.hasPrefix(named + "-"), "\(name) is named for another code than \(named)")
        case let .failure(error):
            Issue.record("\(name): \(error)")
        }
    }

    @Test func whoamiReadsTheCallersHandleRoleAndCapabilities() async throws {
        let whoami = try Endpoint.whoami.decode(response(try Exchange("whoami")))
        #expect(whoami.fde == "uday")
        #expect(whoami.name == "uday")
        #expect(whoami.displayName == "Uday")
        #expect(whoami.email == "uday@example.com")
        #expect(whoami.role == .known(.admin))
        #expect(whoami.capabilities == [.known(.read), .known(.write), .known(.admin), .known(.build)])
    }

    @Test func aTerminalTicketReadsTheTicketWhereToPresentItAndWhenItLapses() throws {
        let ticket = try Endpoint.terminalTicket(open: "harbor").decode(response(try Exchange("terminal-ticket")))
        #expect(ticket.ticket == "tkt_harbor")
        #expect(ticket.address == "100.64.0.7:7072")
        #expect(ticket.expiresAt.milliseconds == Goldens.pinnedMilliseconds + 30_000)
    }

    @Test func aWorkspaceTicketNamesItsWorkspace() throws {
        let ticket = try Endpoint.terminalTicket(open: "harbor", workspace: "dock")
            .decode(response(try Exchange("terminal-ticket-workspace")))
        #expect(ticket.ticket == "tkt_harbor")
    }

    @Test func aFilesTicketReadsAsATicketForItsWorkspace() throws {
        let ticket = try Endpoint.terminalTicket(files: "harbor", workspace: "dock")
            .decode(response(try Exchange("terminal-ticket-files")))
        #expect(ticket.ticket == "tkt_harbor")
        #expect(ticket.address == "100.64.0.7:7072")
    }

    /// A files ticket for a stopped workspace is refused by its code, its remedy the start command,
    /// typed, never an HTTP call: nothing is started by looking.
    @Test func aFilesTicketForAStoppedWorkspaceIsRefusedWithTheStartAsACommand() async throws {
        let (result, _) = try await answer("refusal-workspace-stopped") { client in
            try await client.send(.terminalTicket(files: "harbor", workspace: "idle"))
        }
        guard case let .failure(.refused(refusal)) = result else { Issue.record("not refused: \(result)"); return }
        #expect(refusal.code == .known(.workspaceStopped))
        #expect(refusal.message == "Workspace idle of harbor is stopped, and reading its files does not start it.")
        #expect(refusal.remedy.contains(.command(run: .known(.sail), verb: "project workspace start", args: ["harbor", "idle"])))
    }

    /// The project's workspaces as the server lists them: its own container as `main`, with no
    /// owner, and each workspace its owner's while that person is active.
    @Test func theWorkspacesOfAProjectAreReadAsListed() throws {
        let listed = try Endpoint.workspaces(project: "harbor").decode(response(try Exchange("workspaces")))
        #expect(listed == WorkspaceList(project: "harbor", workspaces: [
            WorkspaceRow(name: "main", instance: "harbor", state: .known(.notCreated), owner: nil),
            WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.running), owner: "uday"),
        ]))
    }

    /// A workspace's terminal sessions as the runner's host lists them for the caller, newest first:
    /// one live with its keyboard held, one its workspace's stop ended, in the stop's words; another
    /// person lists nothing of the owner's.
    @Test func aWorkspacesSessionsAreReadAsListed() throws {
        let sessions = Endpoint.sessions(workspace: "dock", project: "harbor")
        let listed = try sessions.decode(response(try Exchange("workspace-sessions")))
        #expect(listed == RunnerSessions(sessions: [
            RunnerSession(
                name: "app-1f2e3d4c", live: true, attached: 1, writer: "uday", opener: "uday", command: ["claude"],
                agent: RunnerAgent(harness: "claude-code", display: "Claude Code", mode: "default", login: "stored"),
                createdAt: Date(timeIntervalSince1970: 1_763_460_000), endedReason: nil
            ),
            RunnerSession(
                name: "app-5a6b7c8d", live: false, attached: 0, writer: "", opener: "uday", command: [],
                createdAt: Date(timeIntervalSince1970: 1_763_456_400),
                endedReason: "Workspace dock of harbor was stopped by ana."
            ),
        ]))
        #expect(try sessions.decode(response(try Exchange("workspace-sessions-not-theirs"))) == RunnerSessions(sessions: []))
    }

    /// What can start in a workspace, each case as the server answers it: its owner signed in, with
    /// a harness the project does not install refused; no login stored, then none the default, each
    /// with the remedy as data and a start inside still offered; another person's, and one whose
    /// person is gone, refused whole with nothing offered.
    @Test func whatCanStartInAWorkspaceIsReadAsAnswered() throws {
        func agents(_ name: String, _ workspace: String) throws -> WorkspaceAgents {
            try Endpoint.agents(workspace: workspace, project: "harbor").decode(response(try Exchange(name)))
        }
        let owned = try agents("workspace-agents", "berth")
        #expect(owned.owner == "dana" && owned.running && owned.refusal == nil)
        let claude = try #require(owned.offered.first)
        #expect(claude.login?.name == "work" && claude.login?.kind == "api-key")
        #expect(claude.modeDefault == claude.modes.first?.mode)
        #expect(claude.modes.count == 7 && claude.refusal == nil && claude.inside)
        #expect(claude.signIn?.name == "Your Claude account" && claude.signIn?.signedIn == false && claude.signIn?.said?.hasPrefix("Claude Code asks you to sign in") == true)
        #expect(claude.kinds.map(\.label) == ["API key"], "its one key")
        let codex = try #require(owned.offered.last)
        #expect(codex.refusal?.code == .known(.harnessNotInstalled) && !codex.inside && codex.login == nil)
        #expect(codex.note?.hasPrefix("Sail starts Codex CLI in a workspace with full access") == true && codex.modes.count == 1)

        let stopped = try agents("workspace-agents-stopped-no-login", "quay")
        #expect(!stopped.running && stopped.refusal == nil)
        let none = try #require(stopped.offered.first)
        #expect(none.login == nil && none.inside && none.signIn?.signedIn == nil, "a stopped workspace is not read for its sign-in")

        let theirs = try agents("workspace-agents-not-yours", "dock")
        #expect(theirs.refusal?.code == .known(.notYourWorkspace) && theirs.offered.isEmpty && theirs.owner == "uday")
        let gone = try agents("workspace-agents-owner-gone", "wreck")
        #expect(gone.refusal?.code == .known(.ownerGone) && gone.offered.isEmpty && gone.owner == nil)
    }

    /// A running workspace, a stopped one and another person's, as the server lists them and as
    /// the pane orders them for their owner: theirs first, running first; a project with none of
    /// its own lists only its container.
    @Test func threeWorkspacesAndNoneAreReadAsListed() throws {
        let three = try Endpoint.workspaces(project: "harbor").decode(response(try Exchange("workspaces-three")))
        let rows = three.rows(for: "uday")
        #expect(rows.map(\.name) == ["dock", "pier", "quay"] && rows.map(\.owner) == ["uday", "uday", "ana"])
        #expect(rows.map(\.state) == [.known(.running), .known(.stopped), .known(.running)])
        let none = try Endpoint.workspaces(project: "harbor").decode(response(try Exchange("workspaces-none")))
        #expect(none.workspaces.map(\.name) == ["main"] && none.rows(for: "uday").isEmpty)
    }

    /// A forced delete's fix is a command, typed, never an HTTP call.
    @Test func aDeleteThatHoldsWorkNamesTheForcedDeleteAsACommand() async throws {
        let (result, _) = try await answer("refusal-workspace-holds-work") { client in
            try await client.send(.deleteWorkspace("dock", project: "harbor"))
        }
        guard case let .failure(.refused(refusal)) = result else { Issue.record("not refused: \(result)"); return }
        #expect(refusal.remedy.contains(.command(run: .known(.sail), verb: "project workspace delete", args: ["harbor", "dock", "--force"])))
        #expect(!(refusal.action ?? "").contains("/v1/"))
    }

    @Test func aWorkspaceDoorReadsWhatItMadeOrChanged() throws {
        let created = try Endpoint.createWorkspace("new", project: "harbor")
            .decode(response(try Exchange("workspace-create")))
        #expect(created == WorkspaceCreated(
            project: "harbor", workspace: "new", instance: "harbor-new", owner: "dana", template: "v1", stale: false,
            timings: WorkspaceTimings(
                cloneMs: 0, startMs: 0, readyMs: 0, totalMs: 0, readySteps: ["files": 0, "machinery": 0, "mark": 0]
            )
        ))
        let started = try Endpoint.startWorkspace("dock", project: "harbor")
            .decode(response(try Exchange("workspace-start")))
        #expect(started == WorkspaceChanged(
            project: "harbor", workspace: "dock", instance: "harbor-dock", change: .known(.alreadyRunning), timings: nil
        ))
        let stopped = try Endpoint.stopWorkspace("dock", project: "harbor")
            .decode(response(try Exchange("workspace-stop")))
        #expect(stopped.change == .known(.stopped))
        let deleted = try Endpoint.deleteWorkspace("dock", project: "harbor", force: true)
            .decode(response(try Exchange("workspace-delete")))
        #expect(deleted.change == .known(.deleted))
    }

    @Test func aHeartbeatReadsTheConversationTheServersMinuteAndWhetherItWasNew() throws {
        let minute = try Endpoint<AttentionMinute>.attention(conversation: "harbor-docks")
            .decode(response(try Exchange("attention")))
        #expect(minute.conversation == "harbor-docks")
        #expect(minute.recorded)
        #expect(Int64(minute.minute.timeIntervalSince1970 * 1000) == Goldens.pinnedMilliseconds / 60_000 * 60_000)
    }

    @Test func projectsReadEachProjectAndItsContainer() throws {
        let list = try Endpoint.projects.decode(response(try Exchange("projects")))
        #expect(list.total == 1)
        #expect(list.projects.map(\.name) == ["harbor"])
        #expect(list.projects.map(\.containerStatus) == [.known(.notCreated)])
        #expect(list.projects.map(\.id) == ["harbor"])
    }

    @Test func aRoomReadsEveryFieldTheServerSends() throws {
        let room = try Endpoint.room("harbor-design").decode(response(try Exchange("room")))
        let listed = try Endpoint.rooms().decode(response(try Exchange("rooms")))
        #expect(listed.count == 3)
        #expect(listed.rooms.map(\.id) == ["harbor-buoys", "harbor-design", "harbor-quay"])
        #expect(listed.rooms[1] == room)
        #expect(room.id == "harbor-design")
        #expect(room.project == "harbor")
        #expect(room.title == "Docks")
        #expect(room.assignee == "uday")
        #expect(room.wake == .known(.mention))
        #expect(room.effectiveWake == .known(.mention))
        #expect(room.members.count == 1)
        expectSeated(room.members.first)
        #expect(room.specIDs == ["harbor-docks"])
        #expect(room.createdBy == "uday")
        #expect(room.createdAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(room.updatedAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(room.updatedBy == "sail")
        #expect(room.lastActivityAt?.milliseconds == Goldens.pinnedMilliseconds)
        #expect(room.needsReply)
        #expect(room.questionMessageID == "00000000-0000-7000-8000-000000000003")
    }

    @Test func aRoomWithNothingOptionalReadsAsNothingSeatedFiledOrAsked() throws {
        let room = try Endpoint.room("harbor-quay").decode(response(try Exchange("room-bare")))
        #expect(room.id == "harbor-quay")
        #expect(room.assignee == "uday")
        #expect(room.wake == nil)
        #expect(room.effectiveWake == .known(.on))
        #expect(room.members.isEmpty)
        #expect(room.specIDs.isEmpty)
        #expect(room.lastActivityAt == nil)
        #expect(!room.needsReply)
        #expect(room.questionMessageID == nil)
    }

    @Test func messagesReadEveryFieldWithAReplyAndAQuestion() throws {
        let list = try Endpoint.messages(room: "harbor-design").decode(response(try Exchange("messages")))
        #expect(list.specID == "harbor-design")
        #expect(list.total == 4)
        let (asked, answered, failed, agent) = (list.messages[0], list.messages[1], list.messages[2], list.messages[3])
        #expect(asked.id == "00000000-0000-7000-8000-000000000001")
        #expect(asked.specID == "harbor-design")
        #expect(asked.author == "uday")
        #expect(asked.body == question.body)
        #expect(asked.replyTo == nil)
        #expect(asked.createdAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(asked.isQuestion)
        #expect(!asked.isError)
        #expect(answered.body == reply.body)
        #expect(answered.replyTo == asked.id)
        #expect(!answered.isQuestion)
        #expect(failed.author == "claude/run-1")
        #expect(failed.body == "The harness exited before it answered.")
        #expect(failed.isError)
        #expect(!failed.isQuestion)
        #expect(agent.author == "claude/run-1")
        #expect(agent.isQuestion)
        #expect(!agent.isError)
        let posted = try Endpoint.post(question, room: "harbor-design").decode(response(try Exchange("message-posted")))
        #expect(posted.message == asked)
        let replied = try Endpoint.post(reply, room: "harbor-design").decode(response(try Exchange("message-reply")))
        #expect(replied.message == answered)
    }

    @Test func aSpecReadsEveryFieldTheServerSends() throws {
        let detail = try Endpoint.spec("harbor-docks").decode(response(try Exchange("spec")))
        let list = try Endpoint.specs().decode(response(try Exchange("specs")))
        #expect(list.total == 2)
        #expect(list.specs.map(\.id) == ["harbor-docks", "harbor-buoys"])
        let listed = try #require(list.specs.first)
        #expect(detail.body == "The south berth takes the ferry.")
        #expect(detail.plan == "")
        #expect(detail.openFindings == 1)
        let run = try #require(detail.latestRun)
        #expect(run.id == "run-1")
        #expect(run.node == "uday")
        #expect(run.status == .known(.running))
        #expect(run.exitCode == nil)
        #expect(run.principal == "claude/run-1")
        #expect(run.owner == "uday")
        #expect(run.sessionID == nil)
        #expect(run.sessionSource == nil)
        #expect(run.lastActivityAt?.milliseconds == Goldens.pinnedMilliseconds)
        #expect(run.presence == .known(.working))
        let spec = detail.spec
        #expect(spec.id == "harbor-docks")
        #expect(spec.project == "harbor")
        #expect(spec.title == "Docks take the ferry")
        #expect(spec.status == .known(.pending))
        #expect(spec.assignee == "uday")
        #expect(spec.agent == "claude-code")
        #expect(spec.model == "opus")
        #expect(spec.reasoningEffort == "high")
        #expect(spec.branch == "agent/harbor-docks")
        #expect(spec.priority == 2)
        #expect(spec.dependsOn == ["harbor-berths"])
        #expect(spec.repos == ["harbor-api"])
        #expect(spec.wake == .known(.mention))
        expectSeated(spec.engagement)
        #expect(spec.createdBy == "uday")
        #expect(spec.createdAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(spec.updatedAt.milliseconds == Goldens.pinnedMilliseconds)
        #expect(spec.updatedBy == "uday")
        #expect(spec.roomID == "harbor-design")
        #expect(spec.lastActivityAt == nil)
        #expect(listed.lastActivityAt?.milliseconds == Goldens.pinnedMilliseconds)
        #expect(spec.needsReply)
        #expect(spec.questionMessageID == "00000000-0000-7000-8000-000000000003")
    }

    @Test func aSpecWithNothingOptionalReadsAsUnassignedUnfiledAndUnasked() throws {
        let detail = try Endpoint.spec("harbor-buoys").decode(response(try Exchange("spec-bare")))
        #expect(detail.body == "")
        #expect(detail.openFindings == 0)
        #expect(detail.latestRun == nil)
        let spec = detail.spec
        #expect(spec.id == "harbor-buoys")
        #expect(spec.status == .known(.pending))
        #expect(spec.assignee == nil)
        #expect(spec.agent == nil)
        #expect(spec.model == nil)
        #expect(spec.reasoningEffort == nil)
        #expect(spec.branch == nil)
        #expect(spec.priority == 0)
        #expect(spec.dependsOn.isEmpty)
        #expect(spec.repos.isEmpty)
        #expect(spec.wake == nil)
        #expect(spec.engagement == nil)
        #expect(spec.roomID == "harbor-buoys")
        #expect(spec.updatedAt.milliseconds == Goldens.pinnedMilliseconds + 1)
        #expect(spec.lastActivityAt == nil)
        #expect(!spec.needsReply)
        #expect(spec.questionMessageID == nil)
    }

    @Test func aFieldTheClientDoesNotKnowIsIgnored() throws {
        let exchange = try Exchange("room")
        let grown = try exchange.altered(at: ["ferry_schedule"], to: ["hourly", "nightly"])
        let member = try exchange.altered(at: ["members", 0, "berth"], to: 4)
        let room = try Endpoint.room("harbor-design").decode(response(exchange))
        #expect(try Endpoint.room("harbor-design").decode(response(grown)) == room)
        #expect(try Endpoint.room("harbor-design").decode(response(member)) == room)
    }

    @Test func aWordAClientDoesNotKnowIsKeptAsUnknown() throws {
        let room = try Exchange("room").altered(at: ["members", 0, "mode"], to: "pilot")
        #expect(try Endpoint.room("harbor-design").decode(response(room)).members.first?.mode == .unknown("pilot"))
        let spec = try Exchange("spec").altered(at: ["spec", "status"], to: "drydock")
        #expect(try Endpoint.spec("harbor-docks").decode(response(spec)).spec.status == .unknown("drydock"))
        let whoami = try Exchange("whoami").altered(at: ["capabilities", 1], to: "steer")
        #expect(try Endpoint.whoami.decode(response(whoami)).capabilities[1] == .unknown("steer"))
        let role = try Exchange("whoami").altered(at: ["role"], to: "harbormaster")
        #expect(try Endpoint.whoami.decode(response(role)).role == .unknown("harbormaster"))
    }

    @Test func aMissingRequiredFieldNamesTheRouteAndTheField() throws {
        let rooms = try Exchange("rooms").altered(at: ["rooms", 0, "effective_wake"], to: nil)
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/rooms?project=harbor", field: "rooms[0].effective_wake", reason: "it is missing"
        ))) { try Endpoint.rooms(project: "harbor").decode(response(rooms)) }
        let spec = try Exchange("spec").altered(at: ["spec", "engagement", "engaged_at"], to: nil)
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/specs/harbor-docks", field: "spec.engagement.engaged_at", reason: "it is missing"
        ))) { try Endpoint.spec("harbor-docks").decode(response(spec)) }
    }

    @Test func aFieldOfTheWrongShapeNamesTheRouteAndTheField() throws {
        let messages = try Exchange("messages").altered(at: ["messages", 1, "created_at"], to: "yesterday")
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/rooms/harbor-design/messages", field: "messages[1].created_at",
            reason: "'yesterday' is not an ISO-8601 instant"
        ))) { try Endpoint.messages(room: "harbor-design").decode(response(messages)) }
        let whoami = try Exchange("whoami").altered(at: ["capabilities"], to: "all")
        expectUndecodable(route: "GET /v1/whoami", field: "capabilities") {
            try Endpoint.whoami.decode(response(whoami))
        }
        let page = try Exchange("messages").altered(at: ["total"], to: NSNull())
        #expect(throws: SailError.undecodable(Undecodable(
            route: "GET /v1/rooms/harbor-design/messages", field: "total", reason: "it is null"
        ))) { try Endpoint.messages(room: "harbor-design").decode(response(page)) }
    }

    @Test func anAnswerThatIsNotJSONIsUndecodableAsAWhole() {
        expectUndecodable(route: "GET /v1/whoami", field: "(body)") {
            try Endpoint.whoami.decode(HTTPResponse(status: 200, body: Data("<html>".utf8)))
        }
    }

    @Test func anAnswerItsModelRefusesForItsOwnReasonIsUndecodableAsAWhole() {
        struct Unwilling: Decodable {
            init(from decoder: any Decoder) throws {
                throw CancellationError()
            }
        }
        #expect(throws: SailError.undecodable(Undecodable(route: "GET /v1/whoami", field: "(body)", reason: "CancellationError()"))) {
            try SailJSON.decode(Unwilling.self, from: Data("{}".utf8), route: "GET /v1/whoami")
        }
    }

    @Test func aRefusalThatNamesNoFieldErrorsKeepsItsWords() throws {
        let bare = try Exchange("refusal-room-not-found").altered(at: ["error", "field_errors"], to: nil)
        #expect(throws: SailError.refused(Refusal(code: .known(.roomNotFound), message: "Room 'nowhere' was not found."))) {
            try Endpoint.room("nowhere").decode(response(bare))
        }
    }

    @Test func aRefusalKeepsItsCodeMessageAndFix() async throws {
        let (result, _) = try await answer("refusal-read-only-credential") { client in
            try await client.send(.post(MessagePost(body: "hello"), room: "harbor-design"))
        }
        #expect(throws: SailError.refused(Refusal(
            code: .known(.readOnlyCredential),
            message: "Your credential is read-only and cannot post in rooms.",
            action: "Ask an admin for a member or admin credential.",
            remedy: []
        ))) { try result.get() }
    }

    @Test func aRefusalWhoseFixNamesACommandKeepsItAsWordsAndAsTheCommand() throws {
        #expect(throws: SailError.refused(Refusal(
            code: .known(.projectNotCreated),
            message: "Project 'harbor' has no container on this box.",
            action: "Create it: project apply harbor",
            remedy: [.words("Create it: "), .command(run: .known(.sail), verb: "project apply", args: ["harbor"])]
        ))) { try Endpoint.whoami.decode(response(try Exchange("refusal-project-not-created"))) }
        #expect(throws: SailError.refused(Refusal(
            code: .known(.notFound),
            message: "Project 'harbor' was not found.",
            action: "Ask an admin on main for the project: fde grant <handle> <project>.",
            remedy: [
                .words("Ask an admin on main for the project: "),
                .command(run: .known(.sail), verb: "fde grant", args: ["<handle>", "<project>"]),
                .words("."),
            ]
        ))) { try Endpoint.whoami.decode(response(try Exchange("refusal-out-of-scope"))) }
    }

    @Test func aRefusalKeepsWhatItIsAboutFieldByField() throws {
        #expect(throws: SailError.refused(Refusal(
            code: .known(.runOnOtherNode),
            message: "Run run-2 executed on ada; its logs live there, not on this box.",
            fieldErrors: [
                FieldError(field: "node", message: "ada"), FieldError(field: "spec", message: "harbor-docks"),
                FieldError(field: "project", message: "harbor"),
            ]
        ))) { try Endpoint.whoami.decode(response(try Exchange("refusal-run-on-other-node"))) }
    }

    @Test func aRefusalWithAnUnknownCodeOrRunnerKeepsTheRawWord() throws {
        let refused = try Exchange("refusal-project-not-created")
            .altered(at: ["error", "code"], to: "berth_taken")
            .altered(at: ["error", "remedy", 1, "run"], to: "harbormaster")
        #expect(throws: SailError.refused(Refusal(
            code: .unknown("berth_taken"),
            message: "Project 'harbor' has no container on this box.",
            action: "Create it: project apply harbor",
            remedy: [.words("Create it: "), .command(run: .unknown("harbormaster"), verb: "project apply", args: ["harbor"])]
        ))) { try Endpoint.whoami.decode(response(refused)) }
    }

    @Test func aTransportThatCannotReachTheServerSaysSo() async throws {
        let (result, _) = try await answer("whoami") { client in try await client.send(.room("harbor-design")) }
        #expect(throws: SailError.unreachable("no golden answers GET /v1/rooms/harbor-design")) { try result.get() }
    }

    @Test func aFailedAnswerWithNoRefusalKeepsItsStatus() throws {
        #expect(throws: SailError.unexpected(route: "GET /v1/whoami", status: 503)) {
            try Endpoint.whoami.decode(HTTPResponse(status: 503, body: Data()))
        }
    }
}

/// Refusals the server writes for routes this client does not call yet; each still decodes.
let refusalsOfRoutesNotYetCalled: Set<String> = [
    "refusal-out-of-scope", "refusal-project-not-created", "refusal-run-on-other-node",
    "refusal-template-admin-only", "refusal-template-read-only", "refusal-template-node",
    "refusal-template-2d-login-not-stored", "refusal-template-2d-no-resources",
    "refusal-template-2d-runner-not-ready", "refusal-project-name-taken",
    "refusal-project-name-invalid", "refusal-project-repos-same-path",
    "refusal-project-section-repos-same-path",
    "refusal-chat-not-your-workspace", "refusal-chat-no-chat-form", "refusal-chat-mode-not-offered",
    "refusal-chat-inside-invalid", "refusal-chat-not-found", "refusal-chat-invalid-id",
    "refusal-chat-after-invalid", "refusal-chat-ended",
]

/// The answers of routes this client does not call yet, recorded for the apps that will.
let answersOfRoutesNotYetCalled: Set<String> = [
    "spec-measures", "specs-measures",
    "template-2c-missing", "template-2d-missing", "template-2e-building", "template-2f-building",
    "template-2g-built", "template-2h-failed", "template-2h-interrupted", "template-2h-cut-pending",
    "chat-opened", "chat-message-sent", "chat-items", "chat-message-again", "chat-message-queued",
    "chat-items-after", "chat-ended", "chat-items-ended",
]

/// The member the golden writer seats in the room.
func expectSeated(_ member: RoomMember?, sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(member?.agent == "claude-code", sourceLocation: sourceLocation)
    #expect(member?.agentName == "Claude Code", sourceLocation: sourceLocation)
    #expect(member?.mode == .known(.readOnly), sourceLocation: sourceLocation)
    #expect(member?.model == "opus", sourceLocation: sourceLocation)
    #expect(member?.engagedAt.milliseconds == Goldens.pinnedMilliseconds, sourceLocation: sourceLocation)
}

/// That `decode` fails naming `route` and `field`, in whatever words the platform's decoder gives.
func expectUndecodable(
    route: String, field: String, sourceLocation: SourceLocation = #_sourceLocation,
    _ decode: () throws -> some Any
) {
    do {
        let decoded = try decode()
        Issue.record("decoded \(decoded)", sourceLocation: sourceLocation)
    } catch let SailError.undecodable(undecodable) {
        #expect(undecodable.route == route, sourceLocation: sourceLocation)
        #expect(undecodable.field == field, sourceLocation: sourceLocation)
    } catch {
        Issue.record("\(error)", sourceLocation: sourceLocation)
    }
}

func response(_ exchange: Exchange) -> HTTPResponse {
    HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
}

func json(_ data: Data?) throws -> NSDictionary? {
    try data.map { try JSONSerialization.jsonObject(with: $0) as? NSDictionary } ?? nil
}
