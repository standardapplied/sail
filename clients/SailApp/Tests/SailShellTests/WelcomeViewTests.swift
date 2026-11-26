import AppKit
import Foundation
import SailAPI
@testable import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Synchronization
import Testing

/// The control planes a flow reached for, in order.
final class Reached: Sendable {
    private let planes = Mutex<[String]>([])

    var asked: [String] {
        planes.withLock { $0 }
    }

    func note(_ plane: ControlPlane) {
        planes.withLock { $0.append(plane.description) }
    }
}

/// The signed-out Workspaces card (section 1 of the first impressions page): one question a
/// screen, chosen from what this Mac remembers; Continue, Create Passkey and Sign In with Passkey
/// through the flow; busy, refused and ended as drawn.
@MainActor @Suite struct WelcomeViewTests {
    init() {
        ExitWatch.install()
    }

    static let plane = AccountFlowTests.plane
    static let uday = RememberedSignIn(handle: "uday", name: "Uday")

    /// A welcome over a flow whose control plane is `transport`, with this Mac's `defaults`.
    static func welcome(
        _ transport: any Transport, defaults: UserDefaults = ReattachTests.defaults(), passkey: Signs = Signs(), reached: Reached = Reached()
    ) throws -> WelcomeView {
        let account = Account(authenticator: passkey, vault: MemoryVaultOf()) { plane in
            reached.note(plane)
            return transport
        }
        return WelcomeView(
            flow: AccountFlow(account: account, memory: SignInMemory(defaults)), look: .light, title: try TitleFont(directory: LookTests.fonts),
            mono: try LabelFont(directory: LookTests.fonts), version: "0.76.0"
        )
    }

    /// The welcome in a window the size of the design's card (1024 × 684, less the rail and band),
    /// laid out.
    static func shown(
        _ transport: any Transport, look: Look = .light, defaults: UserDefaults = ReattachTests.defaults(), passkey: Signs = Signs(),
        size: NSSize = NSSize(width: 1024 - Rail.width - 4, height: 684 - ShellApp.bandHeight - 2), reached: Reached = Reached()
    ) throws -> (WelcomeView, NSWindow) {
        let welcome = try welcome(transport, defaults: defaults, passkey: passkey, reached: reached)
        welcome.look = look
        let window = KeyWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = welcome
        window.orderFront(nil)
        welcome.layoutSubtreeIfNeeded()
        return (welcome, window)
    }

    static func reached(_ defaults: UserDefaults = ReattachTests.defaults()) -> UserDefaults {
        SignInMemory(defaults).reached(plane)
        return defaults
    }

    static func signedInBefore(_ defaults: UserDefaults = ReattachTests.defaults()) -> UserDefaults {
        SignInMemory(defaults).signedIn(uday, at: plane)
        return defaults
    }

    /// Whether `control` sends `action` to `target`.
    static func sends(_ control: NSControl, _ action: Selector, to target: AnyObject) -> Bool {
        control.action == action && control.target === target
    }

    static func visible(_ view: NSView) -> Bool {
        !view.isHiddenOrHasHiddenAncestor
    }

    /// Which screen shows is decided by this Mac alone: no control plane known, the welcome with
    /// Sail's mark and the field; one reached, Enroll with its chip; one signed in to before, Welcome
    /// back by name, no mark, the chip centred under the primary.
    @Test func theScreenIsChosenFromWhatThisMacRemembers() throws {
        let (first, firstWindow) = try Self.shown(RecordedServer([]))
        defer { firstWindow.orderOut(nil) }
        #expect(first.text == "Welcome to Sail" && Self.visible(first.mark) && Self.visible(first.addressField) && !Self.visible(first.ticketField))
        #expect(first.primary === first.proceed && Self.visible(first.proceed) && !Self.visible(first.chip) && first.link === first.toTerminals)
        let (enroll, enrollWindow) = try Self.shown(RecordedServer([]), defaults: Self.reached())
        defer { enrollWindow.orderOut(nil) }
        #expect(enroll.text == "Enroll this Mac\n127.0.0.1:7979" && Self.visible(enroll.chip) && Self.visible(enroll.ticketField) && enroll.primary === enroll.create)
        let (back, backWindow) = try Self.shown(RecordedServer([]), defaults: Self.signedInBefore())
        defer { backWindow.orderOut(nil) }
        #expect(back.text == "Welcome back, Uday\n127.0.0.1:7979" && !Self.visible(back.mark) && Self.visible(back.chipUnder) && !Self.visible(back.chip))
        #expect(back.primary === back.signIn && back.field == nil && back.link === back.toEnroll)
        let primary = back.convert(back.signIn.bounds, from: back.signIn), chip = back.convert(back.chipUnder.bounds, from: back.chipUnder)
        #expect(abs(chip.midX - primary.midX) < 0.5 && chip.maxY <= primary.minY, "the chip \(chip) is not centred under the primary \(primary)")
    }

    /// Continue sends one health check to the address typed, a bare host taken as https; while it
    /// asks, Continue says Connecting with its spinner at its width, the field dims and takes no
    /// input, and the address is not yet remembered; once Sail's health answered, Enroll.
    @Test func continueReachesTheControlPlaneThenEnroll() async throws {
        let transport = Held(RecordedServer([try Exchange("health")]), holding: "health")
        let defaults = ReattachTests.defaults(), reached = Reached()
        let (welcome, window) = try Self.shown(transport, defaults: defaults, reached: reached)
        defer { window.orderOut(nil) }
        let width = welcome.proceed.frame.width
        welcome.addressField.field.stringValue = "sail.example.com"

        welcome.reach(nil)

        await transport.arrived(during: welcome.flow.underWay)
        welcome.layoutSubtreeIfNeeded()
        #expect(welcome.proceed.busyWord == "Connecting" && !welcome.addressField.isEnabled && !welcome.toTerminals.isEnabled)
        #expect(welcome.proceed.frame.width >= width && SignInMemory(defaults).plane == nil, "Connecting narrowed Continue, or the address was remembered before it answered")
        transport.release()
        await welcome.flow.underWay?.value
        #expect(reached.asked == ["https://sail.example.com"] && transport.routes == ["GET /v1/health"])
        #expect(welcome.welcome.screen == .ticket && welcome.text == "Enroll this Mac\nsail.example.com" && welcome.proceed.busyWord == nil)
        #expect(SignInMemory(defaults).plane?.description == "https://sail.example.com" && welcome.chip.answers == true)
    }

    /// A control plane that does not answer is said under the field, its sentence crimson after the
    /// error's mark and its remedy under it, and nothing is remembered.
    @Test func anUnreachableControlPlaneIsSaidUnderTheField() async throws {
        let defaults = ReattachTests.defaults()
        let (welcome, window) = try Self.shown(RecordedServer([]), defaults: defaults)
        defer { window.orderOut(nil) }
        welcome.addressField.field.stringValue = "sail.example.com"
        welcome.reach(nil)
        await welcome.flow.underWay?.value
        #expect(welcome.addressField.error == "Sail cannot reach https://sail.example.com." && welcome.addressField.remedy == "Check that this Mac is on its network and the address is right.")
        #expect(welcome.addressField.isEnabled && welcome.welcome.screen == .address && SignInMemory(defaults).plane == nil)
        #expect(welcome.addressField.drawnMarks.contains(.errorInk))
    }

    /// Enrolling signs in: the ticket is cleared from the field as it is read; the passkey made,
    /// the note says so and the primary says Signing In for the second Touch ID; then someone is
    /// signed in and remembered for the next welcome there.
    @Test func enrollingSignsInWithASecondTouchID() async throws {
        let exchanges = [try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")] + (try signInExchanges())
        let transport = Held(RecordedServer(exchanges), holding: "login/finish")
        let defaults = Self.reached()
        let (welcome, window) = try Self.shown(transport, defaults: defaults)
        defer { window.orderOut(nil) }
        welcome.ticketField.field.stringValue = AccountPaneTests.ticket

        welcome.createPasskey(nil)

        #expect(welcome.ticketField.field.stringValue.isEmpty && welcome.create.busyWord == "Creating Passkey", "the ticket was not cleared as it was read")
        #expect(welcome.text == "Enroll this Mac\n127.0.0.1:7979\nConfirm with Touch ID to create your passkey.")
        await transport.arrived(during: welcome.flow.underWay)
        #expect(welcome.welcome.stage == .signingAfterEnrolling(handle: "uday") && welcome.create.busyWord == "Signing In")
        #expect(welcome.text == "Enroll this Mac\n127.0.0.1:7979\nPasskey created for @uday. Confirm once more to sign in." && Self.visible(welcome.note))
        transport.release()
        await welcome.flow.underWay?.value
        #expect(welcome.flow.standing?.fde == "uday", "enrolling stopped before signing in")
        #expect(SignInMemory(defaults).welcome.title == "Welcome back, Uday")
    }

    /// A ticket the server refuses leaves the field empty with its placeholder, and the refusal
    /// under it in words.
    @Test func aRefusedTicketLeavesTheFieldEmptyWithItsPlaceholder() async throws {
        let (welcome, window) = try Self.shown(RecordedServer([try Exchange("refusal-forbidden")]), defaults: Self.reached())
        defer { window.orderOut(nil) }
        welcome.ticketField.field.stringValue = AccountPaneTests.ticket
        welcome.createPasskey(nil)
        await welcome.flow.underWay?.value
        #expect(welcome.ticketField.field.stringValue.isEmpty && welcome.ticketField.field.placeholderAttributedString?.string == "Paste the ticket or its link")
        #expect(welcome.ticketField.error == "This ticket is used, expired or not from 127.0.0.1:7979.")
        #expect(welcome.ticketField.remedy == "A ticket lasts 15 minutes and enrolls one passkey. Ask your admin for a new one.")
    }

    /// Cancelled at Touch ID on Welcome back: said under the primary, the chip under it.
    @Test func cancelledAtTouchIDIsSaidUnderThePrimary() async throws {
        let (welcome, window) = try Self.shown(RecordedServer([try Exchange("passkey-login-start")]), defaults: Self.signedInBefore(), passkey: Signs(failing: .cancelled))
        defer { window.orderOut(nil) }
        welcome.signInWithPasskey(nil)
        #expect(welcome.signIn.busyWord == "Signing In" && welcome.text.hasSuffix("Confirm with Touch ID."))
        await welcome.flow.underWay?.value
        welcome.layoutSubtreeIfNeeded()
        #expect(welcome.text == "Welcome back, Uday\n127.0.0.1:7979\nCancelled.\nIf this Mac holds no passkey for 127.0.0.1:7979, enroll with a ticket from your admin.")
        let primary = welcome.convert(welcome.signIn.bounds, from: welcome.signIn), said = welcome.convert(welcome.said.bounds, from: welcome.said)
        let chip = welcome.convert(welcome.chipUnder.bounds, from: welcome.chipUnder)
        #expect(Self.visible(welcome.said) && said.maxY <= primary.minY + 0.5 && chip.maxY <= said.minY + 0.5, "the refusal is not under the primary, over the chip")
        #expect(welcome.said.attributedStringValue.containsAttachments)
    }

    /// Each screen leads to the other: Enroll's Sign In with Passkey signs in at once, on Welcome
    /// back; Welcome back's Enroll with a Ticket shows Enroll; Change shows the welcome with the
    /// control plane in the field, selected; Open Terminals opens Terminals. Each link is wired to
    /// its action, which the test sends itself: no AppKit click runs in a unit test.
    @Test func aWrongGuessCostsOneClick() async throws {
        let transport = Held(RecordedServer([]), holding: "login/start")
        let (welcome, window) = try Self.shown(transport, defaults: Self.signedInBefore())
        defer { window.orderOut(nil) }
        #expect(Self.sends(welcome.toEnroll, #selector(WelcomeView.enrollInstead(_:)), to: welcome) && Self.sends(welcome.toSignIn, #selector(WelcomeView.signInWithPasskey(_:)), to: welcome))
        #expect(Self.sends(welcome.chipUnder.change, #selector(WelcomeView.change(_:)), to: welcome) && Self.sends(welcome.toTerminals, #selector(WelcomeView.openTerminals(_:)), to: welcome))
        welcome.enrollInstead(nil)
        #expect(welcome.welcome.screen == .ticket && Self.visible(welcome.ticketField))
        welcome.signInWithPasskey(nil)
        #expect(welcome.welcome.screen == .back && welcome.flow.isActing && welcome.signIn.busyWord == "Signing In", "the sign-in did not run at once")
        transport.release()
        await welcome.flow.underWay?.value
        welcome.change(nil)
        let field = welcome.addressField.field, editor = field.currentEditor() as? NSTextView
        #expect(welcome.welcome.screen == .address && field.stringValue == "http://127.0.0.1:7979")
        #expect(editor?.selectedRange() == NSRange(location: 0, length: 21), "the address is not selected: \(String(describing: editor?.selectedRange()))")
        var opened = false
        welcome.onTerminals = { opened = true }
        welcome.openTerminals(nil)
        #expect(opened)
    }

    /// A check that found the session ended shows Welcome back with the banner over the primary.
    @Test func anEndedSessionIsABannerOverThePrimary() throws {
        let (welcome, window) = try Self.shown(RecordedServer([]), defaults: Self.signedInBefore())
        defer { window.orderOut(nil) }
        let day = try #require(Calendar(identifier: .gregorian).date(from: DateComponents(timeZone: .current, year: 2026, month: 11, day: 25, hour: 12)))
        welcome.checked(.failure(AccountRefusal(.refused(Refusal(code: .known(.invalidBearerToken), message: "Session expired.")), at: Self.plane, step: .asking)), endedOn: day)
        welcome.layoutSubtreeIfNeeded()
        let banner = try #require(welcome.banner)
        #expect(banner.tone == .info && Self.visible(banner) && welcome.text.contains("Your sign-in ended on 25 November. Your sessions on the runner kept running."))
        let primary = welcome.convert(welcome.signIn.bounds, from: welcome.signIn), over = welcome.convert(banner.bounds, from: banner)
        #expect(over.minY >= primary.maxY - 0.5, "the banner stands at \(over), not over the primary \(primary)")
    }

    /// The column is 380 pt, centred in the card on what is drawn; the version line is the app's,
    /// centred at its foot.
    @Test func theColumnIsCentredAndTheVersionAtTheFoot() throws {
        let (welcome, window) = try Self.shown(RecordedServer([]))
        defer { window.orderOut(nil) }
        let column = welcome.convert(welcome.column.bounds, from: welcome.column), version = welcome.convert(welcome.version.bounds, from: welcome.version)
        let half = 0.5 / Double(window.backingScaleFactor)
        #expect(abs(column.width - WelcomeView.columnWidth) < half && abs(column.midX - welcome.bounds.midX) <= half, "the column \(column) in \(welcome.bounds)")
        #expect(welcome.version.stringValue == "Sail 0.76.0" && abs(version.midX - welcome.bounds.midX) <= half && version.maxY < column.minY, "the version \(version)")
    }

    /// Every state, at the least window's card: nothing out of reach.
    @Test func nothingIsOutOfReachAtTheLeastWindow() throws {
        let least = NSSize(width: Double(ShellApp.leastFrame.width) - Rail.width - 4, height: Double(ShellApp.leastFrame.height) - ShellApp.bandHeight - 2)
        let (welcome, window) = try Self.shown(RecordedServer([]), size: least)
        defer { window.orderOut(nil) }
        var faults: [String] = []
        for (name, state) in Self.states() {
            welcome.show(state)
            welcome.layoutSubtreeIfNeeded()
            faults += WorkspacesReachTests.outOfReach(in: welcome).map { "\(name): \($0)" }
        }
        #expect(faults.isEmpty, "\(faults.prefix(20).joined(separator: "\n"))")
    }

    /// The states the page draws, as the drive's frames make them.
    static func states() -> [(String, Welcome)] {
        let plane = try! ControlPlane(address: "sail.example.com"), mara = RememberedSignIn(handle: "mara", name: "Mara Quill")
        var all: [(String, Welcome)] = []
        func state(_ name: String, _ change: (inout Welcome) -> Void) {
            var welcome = Welcome(plane: nil, person: nil)
            change(&welcome)
            all.append((name, welcome))
        }
        state("welcome") { _ in }
        state("connecting") { $0.began(.connecting) }
        state("unreachable") { $0.refused(AccountRefusal(.unreachable("Could not connect to the server."), at: plane, step: .reaching)) }
        state("enroll") { $0.reached(plane, person: nil) }
        state("creating") { $0.reached(plane, person: nil); $0.began(.creating) }
        state("signing after enrolling") { $0.reached(plane, person: nil); $0.began(.signingAfterEnrolling(handle: "mara")) }
        state("ticket refused") {
            $0.reached(plane, person: nil)
            $0.refused(AccountRefusal(.refused(Refusal(code: .known(.forbidden), message: "Enrollment ticket is invalid or expired.")), at: plane, step: .enrolling))
        }
        state("back") { $0.reached(plane, person: mara) }
        state("signing in") { $0.reached(plane, person: mara); $0.began(.signingIn) }
        state("cancelled") { $0.reached(plane, person: mara); $0.refused(AccountRefusal(.cancelled, signingIn: true, at: plane)) }
        state("ended") { $0.reached(plane, person: mara); $0.ended(on: Date(timeIntervalSince1970: 1_795_608_000)) }
        return all
    }
}

/// The shell lands on Workspaces at every launch and after every sign-in; the rail reads
/// Workspaces, Terminals, Account; the project chosen last is kept per person and control plane.
@MainActor @Suite struct LandingTests {
    init() {
        ExitWatch.install()
    }

    @Test func aFreshLaunchOpensOnTheWelcomeInWorkspaces() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.land()
        #expect(shell.rail.selected == .workspaces && !shell.welcome.isHidden && shell.workspaces.isHidden && shell.welcome.text == "Welcome to Sail")
    }

    /// With a session held for the control plane last reached, Sail's mark draws itself in
    /// Workspaces, "Connecting to" it, until the check answers.
    @Test func aLaunchHoldingASessionWaitsOnTheMarkInWorkspaces() throws {
        let defaults = ReattachTests.defaults()
        SignInMemory(defaults).reached(AccountFlowTests.plane)
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in Signs() }, connect: { _ in OutOfReach() }, vault: OneSession(fde: "uday"), defaults: defaults
        )
        shell.land()
        #expect(shell.rail.selected == .workspaces && shell.welcome.isHidden && !shell.workspaces.isHidden)
        #expect(shell.workspaces.text == "Workspaces\nConnecting to 127.0.0.1:7979" && shell.workspaces.card.subviews.first is BrandMark)
    }

    /// A sign-in made by a person opens Workspaces, wherever the window was.
    @Test func aSignInOpensWorkspaces() async throws {
        let defaults = ReattachTests.defaults()
        SignInMemory(defaults).reached(AccountFlowTests.plane)
        let server = RecordedServer(try signInExchanges())
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in Signs() }, connect: { _ in server }, vault: MemoryVaultOf(), defaults: defaults
        )
        shell.select(.terminals)
        _ = await AccountFlowTests.signIn(shell.flow)
        #expect(shell.flow.standing?.fde == "uday" && shell.rail.selected == .workspaces, "signed in, the window shows \(shell.rail.selected?.rawValue ?? "nothing")")
    }

    /// Account with no one signed in is the welcome on Workspaces.
    @Test func theAccountSignedOutIsTheWelcome() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.select(.account)
        #expect(shell.rail.selected == .workspaces && !shell.welcome.isHidden && shell.account.isHidden)
    }

    /// The rail, top to bottom: Workspaces, Terminals, then Account at the foot; each says its
    /// name as its tooltip and to VoiceOver.
    @Test func theRailReadsWorkspacesTerminalsAccount() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.window.contentView?.layoutSubtreeIfNeeded()
        let buttons = try Rail.Item.allCases.map { try #require(shell.rail.button($0)) }
        #expect(buttons.allSatisfy { !$0.isHidden }, "signed out, an item is off the rail")
        let order = buttons.sorted { $0.convert($0.bounds, to: nil).maxY > $1.convert($1.bounds, to: nil).maxY }
        #expect(order.map { $0.toolTip ?? "" } == ["Workspaces", "Terminals", "Account"] && order.map { $0.accessibilityLabel() ?? "" } == ["Workspaces", "Terminals", "Account"])
        #expect(Rail.Item.terminals.rawValue == "terminals")
    }

    /// The project chosen last is kept for one person on one control plane.
    @Test func theProjectChosenLastIsKeptPerPersonAndControlPlane() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        let mara = WorkspacesModel.Person(handle: "mara", admin: false, confirmed: true, controlPlane: "sail.example.com")
        let other = WorkspacesModel.Person(handle: "mara", admin: false, confirmed: true, controlPlane: "other.example.com")
        shell.workspaces.keepChosen("lighthouse", mara)
        #expect(shell.workspaces.lastChosen(mara) == "lighthouse" && shell.workspaces.lastChosen(other) == nil)
    }
}
