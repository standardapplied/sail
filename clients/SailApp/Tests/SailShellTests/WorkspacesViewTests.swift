import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Testing

/// The Workspaces section drawn from the server's recorded answers: the left panel and a project's
/// list, what a refresh holds (nothing), and the panel hidden under a narrow card.
@MainActor @Suite struct WorkspacesViewTests {
    static let uday = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: "127.0.0.1")

    static func view(_ transport: any Transport, width: Double = 1000) throws -> (WorkspacesView, NSWindow) {
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        view.person = Self.uday
        view.client = SailClient(transport: transport, token: "tok")
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = view
        window.orderFront(nil)
        return (view, window)
    }

    static func listed(_ golden: String = "workspaces-three") async throws -> (WorkspacesView, NSWindow, RecordedServer) {
        let server = RecordedServer([try Exchange("projects"), try Exchange(golden)])
        let (view, window) = try Self.view(server)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "nothing listed: \(view.text)")
        return (view, window, server)
    }

    /// The panel lists the project chosen; the list is yours, then other people's, in words.
    @Test func theListIsSaidAsAPersonReadsIt() async throws {
        let (view, _, _) = try await Self.listed()
        #expect(view.text.contains("harbor\nYour own copies of harbor on the runner. Each one opens as a terminal tab."), "\(view.text)")
        #expect(view.text.contains("dock · running") && view.text.contains("pier · stopped"), "\(view.text)")
        #expect(view.panel.list.chosen == "harbor" && view.project.newWorkspace.isEnabled)
        #expect(!view.text.contains("/v1/") && !view.text.contains("sail "))
    }

    /// A listing under way (the section shown again) holds nothing: only a person's action does (#462).
    @Test func aListingUnderWayHoldsNothing() async throws {
        let exchanges = [try Exchange("projects"), try Exchange("workspaces"), try Exchange("projects"), try Exchange("workspaces")]
        let transport = Held(RecordedServer(exchanges), holding: "projects", after: 1)
        let (view, _) = try Self.view(transport)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        let listing = Task { await view.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.filter { $0.contains("projects") }.count == 2 }, "the listing asked nothing")
        #expect(!view.busy && view.project.newWorkspace.isEnabled, "a listing under way held New Workspace")
        transport.release()
        await listing.value
        #expect(view.project.newWorkspace.isEnabled)
    }

    /// Under a 720 pt card the panel hides and the content takes the width; ⌃⌘S slides it over.
    @Test func aNarrowCardHidesThePanelAndShowSidebarSlidesItOver() async throws {
        let (view, window, _) = try await Self.listed()
        #expect(!view.panel.isHidden)
        window.setContentSize(NSSize(width: 700, height: 640))
        view.layoutSubtreeIfNeeded()
        #expect(view.panel.isHidden && view.project.frame.minX == 0)
        view.toggleSidebar(nil)
        view.layoutSubtreeIfNeeded()
        #expect(!view.panel.isHidden && view.project.frame.minX == 0, "the panel slides over the content")
        view.toggleSidebar(nil)
        window.setContentSize(NSSize(width: 1000, height: 640))
        view.layoutSubtreeIfNeeded()
        #expect(!view.panel.isHidden && view.project.frame.minX == view.panel.frame.maxX)
    }

    /// The panel's edge drags it between 200 and 360 pt.
    @Test func thePanelIsResizedWithinItsBounds() throws {
        let panel = LeftPanel(look: .light, mono: try LabelFont(directory: LookTests.fonts))
        panel.resize(to: 500)
        #expect(panel.widthConstraint.constant == 360)
        panel.resize(to: 120)
        #expect(panel.widthConstraint.constant == 200)
        panel.resize(to: 260)
        #expect(panel.widthConstraint.constant == 260)
    }
}
