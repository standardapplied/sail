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
@MainActor @Suite(.timeLimit(.minutes(1))) struct WelcomeViewTests {
    init() {
        ExitWatch.install()
    }

    static let plane = AccountFlowTests.plane
    static let uday = RememberedSignIn(handle: "uday", name: "Uday")

    /// A welcome over a flow whose control plane is `transport`, with this Mac's `defaults`.
    static func welcome(
        _ transport: any Transport, defaults: UserDefaults = ReattachTests.defaults(), passkey: Signs = Signs(), reached: Reached = Reached()
    ) throws -> WelcomeView {
        let account = Account(authenticator: passkey, vault: MemoryVaultOf(), reachWithin: .seconds(Patience.answer)) { plane in
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
        #expect(welcome.proceed.busyWord == "Connecting" && welcome.addressField.waits && welcome.addressField.paint.boxOpacity < 1 && !welcome.toTerminals.isEnabled)
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

    /// Cancelled at Touch ID on Welcome back: said under the primary, the chip under it. Return is
    /// the primary's only while it is free.
    @Test func cancelledAtTouchIDIsSaidUnderThePrimary() async throws {
        let (welcome, window) = try Self.shown(RecordedServer([try Exchange("passkey-login-start")]), defaults: Self.signedInBefore(), passkey: Signs(failing: .cancelled))
        defer { window.orderOut(nil) }
        welcome.signInWithPasskey(nil)
        #expect(welcome.signIn.busyWord == "Signing In" && welcome.text.hasSuffix("Confirm with Touch ID."))
        #expect(welcome.signIn.keyEquivalent.isEmpty, "Sign In with Passkey holds Return while signing in")
        await welcome.flow.underWay?.value
        welcome.layoutSubtreeIfNeeded()
        #expect(welcome.signIn.keyEquivalent == "\r", "free again, Sign In with Passkey does not take Return")
        #expect(welcome.text == "Welcome back, Uday\n127.0.0.1:7979\nCancelled.\nIf this Mac holds no passkey for 127.0.0.1:7979, enroll with a ticket from your admin.")
        let primary = welcome.convert(welcome.signIn.bounds, from: welcome.signIn), said = welcome.convert(welcome.said.bounds, from: welcome.said)
        let chip = welcome.convert(welcome.chipUnder.bounds, from: welcome.chipUnder)
        #expect(Self.visible(welcome.said) && said.maxY <= primary.minY + 0.5 && chip.maxY <= said.minY + 0.5, "the refusal is not under the primary, over the chip")
        #expect(welcome.said.attributedStringValue.containsAttachments)
        #expect(welcome.announced == "Cancelled. If this Mac holds no passkey for 127.0.0.1:7979, enroll with a ticket from your admin.", "VoiceOver heard \(welcome.announced ?? "nothing")")
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
        welcome.checked(.failure(AccountRefusal(.signIn(.ended), at: Self.plane, step: .asking)), endedOn: day)
        welcome.layoutSubtreeIfNeeded()
        let banner = try #require(welcome.banner)
        #expect(banner.tone == .info && Self.visible(banner) && welcome.text.contains("Your sign-in ended on 25 November. Your sessions on the runner kept running."))
        let font = banner.words.attribute(.font, at: 0, effectiveRange: nil) as? NSFont
        #expect((font?.fontDescriptor.object(forKey: .traits) as? [NSFontDescriptor.TraitKey: Any])?[.weight] as? CGFloat == NSFont.Weight.regular.rawValue, "the banner's sentence is not regular: \(String(describing: font))")
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

    /// A link among words takes a word's space before its words and after them, the same either
    /// side, and the chip's words stand as far in from either end, measured where AppKit draws each
    /// piece's words, within half a backing pixel: on all three screens, either direction. A link
    /// is as wide as its words.
    @Test(arguments: [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft])
    func aLinkTakesAWordsSpaceEitherSide(_ direction: NSUserInterfaceLayoutDirection) throws {
        let (welcome, window) = try Self.shown(RecordedServer([]))
        defer { window.orderOut(nil) }
        let half = 0.5 / Double(window.backingScaleFactor)
        let space = Double((" " as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: Look.Step.footnote.points)]).width)
        let plane = try ControlPlane(address: "sail.example.com")
        let screens = [Welcome(plane: nil, person: nil), Welcome(plane: plane, person: nil), Welcome(plane: plane, person: RememberedSignIn(handle: "mara", name: "Mara Quill"))]
        for screen in screens {
            welcome.show(screen)
            for line in [welcome.quietLine, welcome.chip.row, welcome.chipUnder.row] {
                line.userInterfaceLayoutDirection = direction
            }
            welcome.layoutSubtreeIfNeeded()
            let spans = Self.spans(in: welcome.quietLine, direction)
            let gaps = zip(spans, spans.dropFirst()).map { $1.lowerBound - $0.upperBound }
            #expect(spans.count >= 2 && gaps.allSatisfy { abs($0 - space) <= half }, "\(screen.title): the gaps are \(gaps), a space is \(space)")
            if let chip = welcome.shownChip {
                chip.layoutSubtreeIfNeeded()
                let drawn = Self.spans(in: chip.row, direction)
                let first = try #require(drawn.first), last = try #require(drawn.last)
                let leading = first.lowerBound, trailing = Double(chip.row.bounds.width) - last.upperBound
                #expect(abs(leading - trailing) <= half && leading >= Look.Space.s - half, "\(screen.title): the chip's words stand \(leading) and \(trailing) in")
            }
        }
        for link in [welcome.toTerminals, welcome.toSignIn, welcome.toEnroll, welcome.chip.change] {
            #expect(Double(link.intrinsicContentSize.width) - Double(link.attributedTitle.size().width) < 0.5, "\(link.title) asks room past its words")
        }
    }

    /// Where each shown piece of `line` draws, left to right: a label's words from its alignment
    /// rect's leading edge (its trailing edge right to left, where its words stand right-aligned), a
    /// link's from its frame's leading edge likewise, any other view whole.
    static func spans(in line: NSView, _ direction: NSUserInterfaceLayoutDirection) -> [ClosedRange<Double>] {
        let rightToLeft = direction == .rightToLeft
        return line.subviews.filter { !$0.isHidden }.map { view -> ClosedRange<Double> in
            let frame = view.frame
            let (words, inner): (Double, NSRect) = if let label = view as? NSTextField {
                (Double(label.attributedStringValue.size().width), label.alignmentRect(forFrame: frame))
            } else if let link = view as? NSButton {
                (Double(link.attributedTitle.size().width), frame)
            } else {
                (Double(frame.width), frame)
            }
            return rightToLeft ? (Double(inner.maxX) - words) ... Double(inner.maxX) : Double(inner.minX) ... (Double(inner.minX) + words)
        }.sorted { $0.lowerBound < $1.lowerBound }
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

    /// A shell over this Mac's `defaults` at a control plane answering from `server`, holding no
    /// session unless `vault` does, landed as a launch lands it.
    static func shell(
        _ server: any Transport, defaults: UserDefaults, passkey: Signs = Signs(), vault: any TokenVault = MemoryVaultOf()
    ) throws -> ShellApp {
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in passkey }, connect: { _ in server }, vault: vault, defaults: defaults
        )
        shell.land()
        return shell
    }

    /// A check that finds no one signed in, when no one was, changes nothing on screen: the app
    /// becoming active after Change keeps the address being typed.
    @Test func aCheckWhileSignedOutKeepsChange() async throws {
        let shell = try Self.shell(RecordedServer([]), defaults: Self.reached())
        let welcome = shell.welcome
        try #require(welcome.welcome.screen == .ticket)
        welcome.change(nil)
        welcome.addressField.field.stringValue = "sail.example.org"
        await shell.flow.refresh()
        #expect(welcome.welcome.screen == .address && welcome.addressField.field.stringValue == "sail.example.org", "the check left \(welcome.text) holding \(welcome.addressField.field.stringValue)")
    }

    /// The app becoming active keeps a refusal the person went elsewhere to act on.
    @Test func aCheckWhileSignedOutKeepsARefusal() async throws {
        let shell = try Self.shell(RecordedServer([try Exchange("refusal-forbidden")]), defaults: Self.reached())
        let welcome = shell.welcome
        welcome.ticketField.field.stringValue = AccountPaneTests.ticket
        welcome.createPasskey(nil)
        await shell.flow.underWay?.value
        let refused = "This ticket is used, expired or not from 127.0.0.1:7979."
        try #require(welcome.ticketField.error == refused)
        await shell.flow.refresh()
        #expect(welcome.welcome.screen == .ticket && welcome.ticketField.error == refused, "the check left \(welcome.text)")
    }

    /// Enrolled, the second Touch ID cancelled: Welcome back for that control plane, saying plainly
    /// that the passkey is made, with no error's mark and no word of a new ticket, since the ticket is
    /// spent and signing in is what is left; a check leaves it as it is.
    @Test func aCheckAfterAnEnrollmentWhoseSignInWasCancelledStaysOnWelcomeBack() async throws {
        let server = RecordedServer([try Exchange("passkey-register-start"), try Exchange("passkey-register-finish"), try Exchange("passkey-login-start")])
        let shell = try Self.shell(server, defaults: Self.reached(), passkey: Signs(failingSignIn: .cancelled))
        let welcome = shell.welcome
        welcome.ticketField.field.stringValue = AccountPaneTests.ticket
        welcome.createPasskey(nil)
        await shell.flow.underWay?.value
        let cancelled = "Welcome back\n127.0.0.1:7979\nPasskey created for @uday. Sign in with it."
        try #require(welcome.text == cancelled, "after the cancel: \(welcome.text)")
        #expect(Self.visible(welcome.said) && !welcome.said.attributedStringValue.containsAttachments && welcome.signIn.isEnabled && welcome.signIn.busyWord == nil)
        #expect(welcome.announced == "Passkey created for @uday. Sign in with it.", "VoiceOver heard \(welcome.announced ?? "nothing")")
        await shell.flow.refresh()
        #expect(welcome.welcome.screen == .back && welcome.text == cancelled, "the check left \(welcome.text)")
    }

    /// Touch ID cancelled as the passkey is made: Enroll as it was, Create Passkey free to try again,
    /// and under the field, in the help's ink and with no error's mark, that nothing changed.
    @Test func aCancelledFirstTouchIDLeavesEnrollAsItWas() async throws {
        let (welcome, window) = try Self.shown(RecordedServer([try Exchange("passkey-register-start")]), defaults: Self.reached(), passkey: Signs(failing: .cancelled))
        defer { window.orderOut(nil) }
        welcome.ticketField.field.stringValue = AccountPaneTests.ticket
        welcome.createPasskey(nil)
        await welcome.flow.underWay?.value
        let line = "Nothing changed. Paste the ticket again to create your passkey."
        #expect(welcome.welcome.screen == .ticket && welcome.text == "Enroll this Mac\n127.0.0.1:7979\n" + line, "after the cancel: \(welcome.text)")
        #expect(welcome.create.isEnabled && welcome.create.busyWord == nil && Self.visible(welcome.create) && welcome.ticketField.isEnabled)
        #expect(welcome.ticketField.help == line && welcome.ticketField.error == nil && !welcome.ticketField.drawnMarks.contains(.errorInk))
        #expect(welcome.announced == line, "VoiceOver heard \(welcome.announced ?? "nothing")")
    }

    /// Enrolled, the sign-in after it refused: Sign In with Passkey signs in with no ticket.
    @Test func aSignInRefusedAfterEnrollingIsSignedInAgainWithNoTicket() async throws {
        let exchanges = [try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")] + (try signInExchanges())
        let transport = Held(RecordedServer(exchanges), holding: "login/finish", then: try Exchange("refusal-authentication-failed"))
        let (welcome, window) = try Self.shown(transport, defaults: Self.reached())
        defer { window.orderOut(nil) }
        welcome.ticketField.field.stringValue = AccountPaneTests.ticket
        welcome.createPasskey(nil)
        await transport.arrived(during: welcome.flow.underWay)
        transport.release()
        await welcome.flow.underWay?.value
        try #require(welcome.welcome.screen == .back && welcome.flow.standing == nil, "after the refusal: \(welcome.text)")
        welcome.signInWithPasskey(nil)
        await welcome.flow.underWay?.value
        #expect(welcome.flow.standing?.fde == "uday" && transport.routes.filter { $0.contains("register") }.count == 2, "\(transport.routes)")
    }

    /// Connecting never traps a person: an edit of the address stops the check, and what it answers
    /// after is neither said nor remembered; Return in the field meanwhile asks nothing again.
    @Test func editingTheAddressStopsConnecting() async throws {
        let transport = Held(RecordedServer([try Exchange("health")]), holding: "health")
        let defaults = ReattachTests.defaults()
        let (welcome, window) = try Self.shown(transport, defaults: defaults)
        defer { window.orderOut(nil) }
        let field = welcome.addressField.field
        field.stringValue = "sail.example.com"
        welcome.reach(nil)
        await transport.arrived(during: welcome.flow.underWay)
        welcome.reach(nil)
        #expect(transport.routes == ["GET /v1/health"], "Return while connecting asked again: \(transport.routes)")
        field.stringValue = "sail.example.org"
        NotificationCenter.default.post(name: NSControl.textDidChangeNotification, object: field)
        #expect(welcome.welcome.stage == nil && welcome.proceed.busyWord == nil, "the edit left it \(welcome.proceed.busyWord ?? "idle")")
        transport.release()
        await welcome.flow.underWay?.value
        #expect(welcome.welcome.screen == .address && field.stringValue == "sail.example.org" && welcome.addressField.error == nil, "after the answer: \(welcome.text)")
        #expect(SignInMemory(defaults).plane == nil && !welcome.flow.isActing, "a check stopped by an edit was remembered")
    }

    /// The label in the chip that names its host.
    static func host(in chip: PlaneChip) -> NSTextField? {
        chip.row.subviews.compactMap { $0 as? NSTextField }.first
    }

    /// The chip shows its host whole wherever it has the room (the drive's own hosts among others,
    /// as Auto Layout sizes the chip); only a host too long for the column is cut, in its middle.
    @Test func theChipShowsItsHostWholeWhereItHasRoom() throws {
        let (welcome, window) = try Self.shown(RecordedServer([]))
        defer { window.orderOut(nil) }
        for address in ["http://127.0.0.1:7979", "http://127.0.0.1:7980", "http://10.0.0.2:7443", "sail.example.com", "sail.example.com:7443", "box.ts.net"] {
            welcome.show(Welcome(plane: try ControlPlane(address: address), person: nil))
            welcome.layoutSubtreeIfNeeded()
            let host = try #require(Self.host(in: welcome.chip))
            let words = Double(host.attributedStringValue.size().width), insets = host.alignmentRectInsets
            let cut = host.cell?.expansionFrame(withFrame: host.bounds, in: host) ?? .zero
            #expect(
                cut == .zero && Double(host.frame.width) >= words + Double(insets.left + insets.right) - 0.001,
                "\(address): the host is \(host.frame.width) wide for \(words) of words and \(insets.left + insets.right) of insets (its cell asks \(host.cell?.cellSize.width ?? 0), its intrinsic \(host.intrinsicContentSize.width)); the chip is \(welcome.chip.frame.width) for its intrinsic \(welcome.chip.intrinsicContentSize.width), its row \(welcome.chip.row.frame.width); cut \(cut); scale \(window.backingScaleFactor)"
            )
        }
        welcome.show(Welcome(plane: try ControlPlane(address: "https://" + String(repeating: "h", count: 60) + ".example.com"), person: nil))
        welcome.layoutSubtreeIfNeeded()
        let long = try #require(Self.host(in: welcome.chip)), column = welcome.convert(welcome.column.bounds, from: welcome.column)
        #expect(long.lineBreakMode == .byTruncatingMiddle && Double(long.frame.width) < Double(long.attributedStringValue.size().width), "a host longer than the column is not cut")
        #expect(abs(Double(column.width) - WelcomeView.columnWidth) < 0.5, "a long host widened the column to \(column.width)")
    }

    /// Welcome back works from the keyboard: Return presses its primary, there alone; elsewhere the
    /// field takes Return.
    @Test func returnPressesWelcomeBacksPrimary() throws {
        let (welcome, window) = try Self.shown(RecordedServer([]), defaults: Self.signedInBefore())
        defer { window.orderOut(nil) }
        #expect(welcome.signIn.keyEquivalent == "\r" && Self.sends(welcome.signIn, #selector(WelcomeView.signInWithPasskey(_:)), to: welcome))
        welcome.enrollInstead(nil)
        #expect([welcome.proceed, welcome.create, welcome.signIn].allSatisfy { $0.keyEquivalent.isEmpty }, "Return presses a button where a field takes it")
    }

    /// Every word on the controls is the one ``Welcome`` holds, so the words tests read the screen.
    @Test func theControlsSayWhatWelcomeHolds() throws {
        let (welcome, window) = try Self.shown(RecordedServer([]))
        defer { window.orderOut(nil) }
        let plane = try ControlPlane(address: "sail.example.com")
        for screen in [Welcome(plane: nil, person: nil), Welcome(plane: plane, person: nil), Welcome(plane: plane, person: Self.uday)] {
            welcome.show(screen)
            #expect(welcome.primary.title == screen.primary && welcome.link.title == screen.other.link, "\(screen.title): \(welcome.primary.title), \(welcome.link.title)")
        }
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
        state("cancelled creating") { $0.reached(plane, person: nil); $0.began(.creating); $0.refused(AccountRefusal(.cancelled, signingIn: false, at: plane)) }
        state("cancelled after enrolling") {
            $0.reached(plane, person: nil)
            $0.began(.signingAfterEnrolling(handle: "mara"))
            $0.refused(AccountRefusal(.cancelled, signingIn: true, at: plane))
        }
        state("ended") { $0.reached(plane, person: mara); $0.ended(on: Date(timeIntervalSince1970: 1_795_608_000)) }
        return all
    }
}

/// The shell lands on Workspaces at every launch and after every sign-in; the rail reads
/// Workspaces, Terminals, Account; the project chosen last is kept per person and control plane.
@MainActor @Suite(.timeLimit(.minutes(1))) struct LandingTests {
    init() {
        ExitWatch.install()
    }

    @Test func aFreshLaunchOpensOnTheWelcomeInWorkspaces() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.land()
        #expect(shell.rail.selected == .workspaces && !shell.welcome.isHidden && shell.workspaces.isHidden && shell.welcome.text == "Welcome to Sail")
    }

    /// With a session held for the control plane last reached, Sail's mark draws itself in
    /// Workspaces, "Connecting to" it, until the check answers. The launch asks it itself, so the
    /// words never stand while nothing is asked, though the app is never activated.
    @Test func aLaunchHoldingASessionWaitsOnTheMarkInWorkspaces() async throws {
        let defaults = ReattachTests.defaults()
        SignInMemory(defaults).reached(AccountFlowTests.plane)
        let transport = Held(RecordedServer([try Exchange("whoami")]), holding: "whoami")
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in Signs() }, connect: { _ in transport }, vault: OneSession(fde: "uday"), defaults: defaults
        )
        shell.land()
        #expect(shell.rail.selected == .workspaces && shell.welcome.isHidden && !shell.workspaces.isHidden)
        #expect(shell.workspaces.text == "Workspaces\nConnecting to 127.0.0.1:7979" && shell.workspaces.card.subviews.first is BrandMark)
        await transport.arrived(during: shell.checking)
        #expect(transport.routes == ["GET /v1/whoami"], "Connecting to 127.0.0.1:7979 stands while nothing is asked: \(transport.routes)")
        transport.release()
        await shell.checking?.value
        #expect(shell.flow.standing?.fde == "uday" && !shell.workspaces.asking, "the check's answer left \(shell.workspaces.text)")
    }

    /// A sign-in made by a person opens Workspaces, wherever the window was.
    @Test func aSignInOpensWorkspaces() async throws {
        let defaults = ReattachTests.defaults()
        SignInMemory(defaults).reached(AccountFlowTests.plane)
        let server = RecordedServer(try signInExchanges())
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in Signs() }, connect: { _ in server }, vault: MemoryVaultOf(), defaults: defaults
        )
        shell.select(.sessions)
        _ = await AccountFlowTests.signIn(shell.flow)
        #expect(shell.flow.standing?.fde == "uday" && shell.rail.selected == .workspaces, "signed in, the window shows \(shell.rail.selected?.rawValue ?? "nothing")")
    }

    /// Account with no one signed in is the welcome on Workspaces.
    @Test func theAccountSignedOutIsTheWelcome() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.select(.account)
        #expect(shell.rail.selected == .workspaces && !shell.welcome.isHidden && shell.account.isHidden)
    }

    /// The rail, top to bottom: Workspaces, Sessions, then Account at the foot; each says its
    /// name as its tooltip and to VoiceOver.
    @Test func theRailReadsWorkspacesSessionsAccount() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.window.contentView?.layoutSubtreeIfNeeded()
        let buttons = try Rail.Item.allCases.map { try #require(shell.rail.button($0)) }
        #expect(buttons.allSatisfy { !$0.isHidden }, "signed out, an item is off the rail")
        let order = buttons.sorted { $0.convert($0.bounds, to: nil).maxY > $1.convert($1.bounds, to: nil).maxY }
        #expect(order.map { $0.toolTip ?? "" } == ["Workspaces", "Sessions", "Account"] && order.map { $0.accessibilityLabel() ?? "" } == ["Workspaces", "Sessions", "Account"])
        #expect(Rail.Item.sessions.rawValue == "sessions")
    }

    /// A relaunch holding no session, after a sign-in, is Welcome back by name: a second shell over
    /// the first one's settings, the closest a unit test comes to the drive's relaunch.
    @Test func aRelaunchIsWelcomeBackByName() async throws {
        let defaults = WelcomeViewTests.reached()
        let first = try WelcomeViewTests.shell(RecordedServer(try signInExchanges()), defaults: defaults)
        _ = await AccountFlowTests.signIn(first.flow)
        try #require(first.flow.standing?.fde == "uday")
        let again = try WelcomeViewTests.shell(RecordedServer([]), defaults: defaults)
        #expect(again.rail.selected == .workspaces && !again.welcome.isHidden && again.welcome.text == "Welcome back, Uday\n127.0.0.1:7979", "the relaunch shows \(again.welcome.text)")
    }

    /// A launch holding a session waits on the drawing mark, then opens Workspaces on the project the
    /// person chose last there, not the first listed.
    @Test func aLaunchHoldingASessionLandsOnTheProjectChosenLast() async throws {
        let defaults = WelcomeViewTests.reached()
        let listed = #"{"schema_version": 1, "total": 2, "projects": [{"name": "harbor", "container_status": "running", "template": "ready"}, {"name": "lighthouse", "container_status": "running", "template": "ready"}]}"#
        let none = #"{"schema_version": 1, "project": "lighthouse", "workspaces": []}"#
        let server = RecordedServer([
            try Exchange("whoami"), Exchange(method: "GET", path: "/v1/projects", status: 200, body: Data(listed.utf8)),
            Exchange(method: "GET", path: "/v1/projects/lighthouse/workspaces", status: 200, body: Data(none.utf8)),
        ])
        let uday = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: AccountFlowTests.plane.description)
        defaults.set("lighthouse", forKey: ShellApp.chosenKey(uday))
        let shell = try WelcomeViewTests.shell(server, defaults: defaults, vault: OneSession(fde: "uday"))
        #expect(shell.workspaces.text == "Workspaces\nConnecting to 127.0.0.1:7979" && shell.welcome.isHidden)
        await shell.checking?.value
        await shell.workspaces.refresh()
        let lines = shell.workspaces.text.split(separator: "\n").map(String.init)
        #expect(shell.rail.selected == .workspaces && lines.dropFirst().first == "lighthouse", "the launch landed on \(lines)")
    }

    /// Signed in on Workspaces, a check that finds the same person, the same session and the same
    /// answer (the app activated again, after the person was elsewhere) asks for the projects again,
    /// so what others added meanwhile shows.
    @Test func aCheckWhileSignedInAsksForTheProjectsAgain() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("projects"), try Exchange("workspaces-three")])
        let held = MemoryVaultOf(PasskeySession(sessionToken: "tok-uday", fde: "uday", expiresAt: Date(timeIntervalSinceNow: 3600)))
        let shell = try ShellApp(
            fonts: LookTests.fonts, helper: nil, look: .light, passkeys: { _ in Signs() }, connect: { _ in server }, vault: held, defaults: WelcomeViewTests.reached()
        )
        shell.select(.workspaces)
        await shell.flow.refresh()
        await shell.workspaces.projectsAsked?.value
        let before = await server.requests.filter { $0.route == "GET /v1/projects" }.count
        try #require(shell.flow.standing?.fde == "uday" && shell.rail.selected == .workspaces && before >= 1)
        await shell.flow.refresh()
        await shell.workspaces.projectsAsked?.value
        let after = await server.requests.filter { $0.route == "GET /v1/projects" }.count
        #expect(after == before + 1, "the check asked for the projects \(after - before) times")
    }

    /// Signed out on Terminals, Return in a terminal starts no sign-in: Welcome back's primary holds
    /// Return only while the welcome is shown, as a key works only while its control is.
    @Test func returnStartsNoSignInWhileTheWelcomeIsNotShown() throws {
        let shell = try WelcomeViewTests.shell(RecordedServer([]), defaults: WelcomeViewTests.signedInBefore())
        try #require(shell.welcome.welcome.screen == .back && shell.welcome.signIn.keyEquivalent == "\r")
        shell.select(.sessions)
        #expect(shell.welcome.signIn.keyEquivalent.isEmpty, "Sign In with Passkey holds Return on Terminals")
        let returnKey = try #require(NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, characters: "\r", charactersIgnoringModifiers: "\r",
            isARepeat: false, keyCode: 36
        ))
        _ = shell.window.contentView?.performKeyEquivalent(with: returnKey)
        #expect(shell.flow.actions == 0, "Return on Terminals started a sign-in")
        shell.select(.workspaces)
        #expect(shell.welcome.signIn.keyEquivalent == "\r", "shown again, Welcome back's primary does not take Return")
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
