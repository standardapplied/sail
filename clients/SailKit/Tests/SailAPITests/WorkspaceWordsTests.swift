import SailAPI
import SailKitTestSupport
import Testing

/// What a person reads of a workspace refusal and of the list: the server's sentence, then what
/// they can do in the app, never a command; the list theirs first, in words.
@Suite struct WorkspaceWordsTests {
    static func refusal(_ message: String, action: String? = nil, _ remedy: [RemedyPart] = []) -> Refusal {
        Refusal(code: .known(.conflict), message: message, action: action, remedy: remedy)
    }

    /// The refusal the golden `name` recorded, as this client reads it.
    static func recorded(_ name: String) throws -> Refusal? {
        do {
            _ = try Endpoint.whoami.decode(response(try Exchange(name)))
            return nil
        } catch let SailError.refused(refusal) {
            return refusal
        }
    }

    static func sail(_ verb: String, _ args: String...) -> RemedyPart {
        .command(run: .known(.sail), verb: verb, args: args)
    }

    /// The decided table: each command the server names becomes who acts, in words; the list's own
    /// command says nothing, as the list is on screen; one the app does not know, the message alone.
    @Test func aTypedFixIsSaidAsWhoActsNeverAsTheCommand() {
        let cases: [(RemedyPart, String?)] = [
            (Self.sail("project template build", "acme"), "An admin builds acme's template on the runner first."),
            (Self.sail("git login", "github.com"), "An admin stores it on the runner for now."),
            (Self.sail("fde update", "ada", "--email", "<email>"), "An admin sets it on main for now."),
            (Self.sail("project workspace list", "acme"), nil),
            (Self.sail("project workspace delete", "acme", "try", "--force"), "Ask an admin to delete it."),
            (Self.sail("agent stop", "acme/fix"), nil),
        ]
        for (part, said) in cases {
            let refused = Self.refusal("Refused.", action: "Do: sail it.", [.words("Do: "), part, .words(".")])
            #expect(refused.lines == ["Refused."] + (said.map { [$0] } ?? []))
            #expect(!refused.lines.joined().contains("sail "), "a command reached the person: \(refused.lines)")
        }
    }

    /// A fix with no command is shown as the server wrote it, so a bad one is seen and mended at the
    /// source; none, the message alone.
    @Test func anUntypedFixIsShownAsWrittenAndNoneIsTheMessageAlone() {
        #expect(Self.refusal("Your credential is read-only and cannot enter workspaces.", action: "Ask an admin for a member or admin credential.").lines
            == ["Your credential is read-only and cannot enter workspaces.", "Ask an admin for a member or admin credential."])
        #expect(Self.refusal("This runner is creating another workspace, which takes about a minute.").lines
            == ["This runner is creating another workspace, which takes about a minute."])
    }

    /// The server's own refusals, as it writes them (`Workspaces.java:1447`, `WorkspaceDoors.java:93-106`,
    /// `WorkspaceDoors.java:119`, `ApiRouter.terminalTicket`), read as a person reads them (A9 to A11, A16).
    @Test func theRunnersRefusalsReadAsThePersonReadsThem() {
        let untemplated = Refusal(code: .known(.conflict), message: "acme has no template yet:", action: "project template build acme.",
                                  remedy: [Self.sail("project template build", "acme"), .words(".")])
        #expect(untemplated.lines == ["acme has no template yet:", "An admin builds acme's template on the runner first."])
        let busy = Refusal(code: .known(.runnerBusy), message: "This runner is creating another workspace, which takes about a minute.")
        #expect(busy.lines == ["This runner is creating another workspace, which takes about a minute."])
        let node = Refusal(
            code: .known(.readOnlyCredential),
            message: "This box is client's node, where only client creates or enters a workspace; another person's workspaces live on main.",
            action: "Workspaces are created on main: create yours there."
        )
        #expect(node.lines.last == "Workspaces are created on main: create yours there.")
        let offered = Refusal(code: .known(.terminalNotOffered), message: "This runner offers no terminal over the network; ask an admin.")
        #expect(offered.lines == ["This runner offers no terminal over the network; ask an admin."])
    }

    /// The server-written refusals of the goldens, read as a person reads them.
    @Test func theRecordedRefusalsReadInWords() throws {
        for name in ["refusal-read-only-credential-workspace", "refusal-conflict-workspace-exists", "refusal-forbidden-not-assignee", "refusal-workspace-holds-work"] {
            let refusal = try #require(try Self.recorded(name), "\(name) is no refusal")
            #expect(refusal.lines.first == refusal.message)
            #expect(!refusal.lines.joined().contains("/v1/") && !refusal.lines.joined().contains("sail "), "\(name): \(refusal.lines)")
        }
    }

    /// A1: yours first, running before the rest, each by name; the project's own container left out;
    /// why Open is not offered: whose it is, no one's, or a former member's.
    @Test func theListIsYoursFirstInWords() {
        let list = WorkspaceList(project: "acme", workspaces: [
            WorkspaceRow(name: "main", instance: "acme", state: .known(.running), owner: nil),
            WorkspaceRow(name: "dock", instance: "acme-dock", state: .known(.running), owner: "uday"),
            WorkspaceRow(name: "fix", instance: "acme-fix", state: .known(.stopped), owner: "ana"),
            WorkspaceRow(name: "try", instance: "acme-try", state: .known(.running), owner: "ana"),
            WorkspaceRow(name: "old", instance: "acme-old", state: .known(.frozen), owner: nil),
            WorkspaceRow(name: "half", instance: "acme-half", state: .known(.incomplete), owner: "ana"),
        ])
        let rows = list.rows(for: "ana")
        #expect(rows.map(\.name) == ["try", "fix", "half", "dock", "old"])
        #expect(rows.map { $0.whyNotOpened(for: "ana", admin: false) } == [nil, nil, nil, "Only uday or an admin opens it", "No one owns it: only an admin opens it"])
        #expect(rows.allSatisfy { $0.whyNotOpened(for: "ana", admin: true) == nil })
        #expect(rows.map { $0.opens(for: "ana", admin: false) } == [true, true, true, false, false])
        #expect(rows.allSatisfy { $0.opens(for: "uday", admin: true) })
        let left = WorkspaceRow(name: "x", instance: "acme-x", state: .known(.stopped), owner: WorkspaceRow.removedOwner)
        #expect(left.whyNotOpened(for: "ana", admin: false) == "Its owner’s account is not active: only an admin opens it", "the server's marker read as a handle")
    }

    /// A call that ended in anything but an answer is said in words by its kind, never as the enum's
    /// name nor its route: a runner tab that cannot ask its ticket parks saying this.
    @Test func everyFailureOfACallIsSaidInWords() {
        let said = [
            SailError.refused(Self.refusal("Workspace 'try' exists already.")),
            .undecodable(Undecodable(route: "GET /v1/projects", field: "projects", reason: "not a list")),
            .unexpected(route: "GET /v1/projects", status: 502),
            .notAnEventStream(route: "GET /v1/events", contentType: nil),
            .unsendable("a body that does not encode"),
            .unreachable("The network connection was lost."),
        ].map(\.description)
        #expect(said == [
            "Workspace 'try' exists already.",
            "The control plane sent an answer this app cannot read.",
            "The control plane answered with an error (502).",
            "The control plane's proxy answered in place of its event stream.",
            "a body that does not encode",
            "Sail could not reach the control plane: The network connection was lost.",
        ])
        #expect(!said.joined().contains("/v1/"))
    }

    /// Where the control plane's address is known, a failure names it in Sail's words in place of the
    /// transport's text; a refusal stays the server's sentence; with no address, the words above.
    @Test func aFailureNamesTheControlPlanesAddress() {
        let failures = [
            SailError.unreachable("The network connection was lost."), .unexpected(route: "GET /v1/projects", status: 502),
            .undecodable(Undecodable(route: "GET /v1/projects", field: "projects", reason: "not a list")), .refused(Self.refusal("Workspace 'try' exists already.")),
        ]
        #expect(failures.map { $0.said(at: "main.acme.dev") } == [
            "Sail cannot reach the control plane at main.acme.dev.", "The control plane at main.acme.dev answered with an error (502).",
            "The control plane at main.acme.dev sent an answer this app cannot read.", "Workspace 'try' exists already.",
        ])
        #expect(failures.map { $0.said(at: "") } == failures.map(\.description))
    }

    /// M9: each project's latest request wins: an answer overtaken by a later request for its project
    /// is dropped, while another project's request overtakes none; the rows stay paired with the
    /// project they were listed for, and nil asks for nothing anyone takes.
    @Test func eachProjectsLatestListingWinsAndKeepsItsProject() {
        let dock = WorkspaceRow(name: "dock", instance: "acme-dock", state: .known(.running), owner: "uday")
        let pier = WorkspaceRow(name: "pier", instance: "beta-pier", state: .known(.stopped), owner: "uday")
        var listing = WorkspaceListing()
        let first = listing.ask("acme")
        let second = listing.ask("beta")
        #expect(listing.take([dock], request: first) == "acme", "acme's answer, arriving after beta was asked")
        #expect(listing.take([pier], request: second) == "beta")
        #expect(listing.rows == ["acme": [dock], "beta": [pier]])
        let third = listing.ask("beta")
        #expect(listing.take([dock], request: second) == nil, "overtaken by a later request for the same project")
        #expect(listing.project(of: third) == "beta" && listing.project(of: second) == nil && listing.project(of: first) == "acme")
        #expect(listing.rows["beta"] == [pier], "an overtaken answer replaced the rows")
        listing.settle("pier", in: "beta", as: .running)
        listing.settle("dock", in: "acme", as: nil)
        #expect(listing.rows["beta"]?.map(\.state) == [.known(.running)] && listing.rows["acme"] == [])
        let nowhere = listing.ask(nil)
        #expect(listing.project(of: nowhere) == nil && listing.take([dock], request: nowhere) == nil && listing.asked("acme") && !listing.asked("gamma"))
        listing.clear()
        #expect(listing.rows.isEmpty && listing.project(of: third) == nil && !listing.asked("acme"))
    }
}
