import AppKit
import Foundation
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Synchronization
import Testing

/// A vault holding one session as `fde`, for any control plane; a session kept is not held.
struct OneSession: TokenVault {
    let fde: String

    func session(for _: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        PasskeySession(sessionToken: "tok-\(fde)", fde: fde, expiresAt: Date(timeIntervalSinceNow: 3600))
    }

    func keep(_: PasskeySession, for _: ControlPlane) throws(VaultFailure) {}

    func forget(_: ControlPlane) throws(VaultFailure) {}
}

/// Shut until opened: whatever waits on it meanwhile goes on once it is.
final class Gate: Sendable {
    private let state = Mutex<(waiting: CheckedContinuation<Void, Never>?, opened: Bool)>((nil, false))

    var isWaiting: Bool {
        state.withLock { $0.waiting != nil }
    }

    func wait() async {
        await withCheckedContinuation { (wait: CheckedContinuation<Void, Never>) in
            let go = state.withLock { state -> Bool in
                if state.opened { return true }
                state.waiting = wait
                return false
            }
            if go { wait.resume() }
        }
    }

    func open() {
        let waiting = state.withLock { state -> CheckedContinuation<Void, Never>? in
            state.opened = true
            defer { state.waiting = nil }
            return state.waiting
        }
        waiting?.resume()
    }
}

/// A passkey that makes and signs whatever it is asked, its sheet up until `sheet` opens when one
/// is given.
struct Signs: PasskeyAuthenticator {
    var sheet: Gate?

    func register(_: PasskeyRegistrationRequest) async throws(PasskeyFailure) -> PasskeyRegistration {
        await sheet?.wait()
        return PasskeyRegistration(credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.create\"}".utf8), attestationObject: Data([0xA3]))
    }

    func assert(_: PasskeyAssertionRequest) async throws(PasskeyFailure) -> PasskeyAssertion {
        await sheet?.wait()
        return PasskeyAssertion(
            credentialId: Data([1, 2, 3]), clientDataJSON: Data("{\"type\":\"webauthn.get\"}".utf8), authenticatorData: Data([5]),
            signature: Data([7]), userHandle: Data("uday".utf8)
        )
    }
}

/// The recorded server, but the request whose route holds `holding`, after `skipped` such, is held
/// until released, then answered by the server, or by `answer` when given; every route asked, in
/// order. A row's sessions listing, asked beside each list of workspaces, is never the one held or
/// counted: what a test holds is a person's action or a list, and a listing that matched took the
/// place of the request a test meant, silently: one test held nothing it meant to and failed, and
/// another passed while holding nothing at all.
final class Held: Transport {
    private let server: RecordedServer
    private let holding: String
    private let skipped: Int
    private let answer: Exchange?
    private let gate = Gate()
    private let state = Mutex<(routes: [String], seen: Int)>(([], 0))

    init(_ server: RecordedServer, holding: String, after skipped: Int = 0, then answer: Exchange? = nil) {
        self.server = server
        self.holding = holding
        self.skipped = skipped
        self.answer = answer
    }

    var routes: [String] {
        state.withLock { $0.routes }
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        let held = state.withLock { state -> Bool in
            state.routes.append(request.route)
            guard request.route.contains(holding), !request.route.hasSuffix("/sessions") else { return false }
            state.seen += 1
            return state.seen == skipped + 1
        }
        guard held else { return try await server.send(request) }
        await gate.wait()
        guard let answer else { return try await server.send(request) }
        return HTTPResponse(status: answer.status, headers: answer.headers, body: answer.body)
    }

    func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        throw .unexpected(route: request.route, status: 502)
    }

    func release() {
        gate.open()
    }
}

/// The account pane: a check of who is signed in (the app becoming active) holds no button, so a
/// person's action is never swallowed by it; an answer that comes during a person's action waits
/// for it to end, and is then shown unless who is signed in was set since the check began, said
/// after what the action said.
@MainActor @Suite struct AccountPaneTests {
    init() {
        ExitWatch.install()
    }

    static let ticket = "enr_0123456789abcdef0123456789abcdef"

    static func pane(remembering fde: String, _ transport: Held, sheet: Gate? = nil) throws -> AccountPane {
        let defaults = try #require(UserDefaults(suiteName: "sail-account-\(UUID().uuidString)"))
        defaults.set("http://127.0.0.1:7979", forKey: AccountPane.addressKey)
        let account = Account(authenticator: Signs(sheet: sheet), vault: OneSession(fde: fde)) { _ in transport }
        return AccountPane(
            account: account, look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts),
            labels: try LabelFont(directory: LookTests.fonts), defaults: defaults
        )
    }

    /// The pane in a window `width` wide, laid out.
    static func shown(_ pane: AccountPane, width: Double = 900, look: Look = .light) -> NSWindow {
        pane.look = look
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 700), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = pane
        pane.layoutSubtreeIfNeeded()
        return window
    }

    static func eyebrows(_ pane: AccountPane) -> [String] {
        WorkspacesViewTests.found(NSTextField.self, in: pane).filter { !$0.isHiddenOrHasHiddenAncestor && pane.eyebrows.contains($0) }.map(\.stringValue)
    }

    /// Signed in, the pane speaks the menu's language: the person's name as the display heading,
    /// mono eyebrows over the facts (SIGNED IN AS, CONTROL PLANE), `@handle · Role`, the control
    /// plane as the menu's line (its address, state square and word), the session's end in quiet
    /// text, and Sign Out the secondary button.
    @Test(arguments: [Look.light, .dark])
    func signedInItIsTheMenusLanguage(_ look: Look) async throws {
        let pane = try Self.pane(remembering: "uday", Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held"))
        let window = Self.shown(pane, look: look)
        defer { window.orderOut(nil) }
        await pane.refresh()
        pane.layoutSubtreeIfNeeded()
        #expect(Self.eyebrows(pane) == ["SIGNED IN AS", "CONTROL PLANE"], "the eyebrows are \(Self.eyebrows(pane))")
        #expect(pane.eyebrows.allSatisfy { $0.textColor == NSColor(look.textSecondary) && $0.stringValue == $0.stringValue.uppercased() })
        #expect(pane.handle.stringValue == "@uday · Admin" && pane.planeRow.said == "127.0.0.1:7979" && pane.planeRow.plane == .connected)
        #expect(pane.until.stringValue.hasPrefix("Signed in until ") && pane.until.textColor == NSColor(look.textTertiary))
        #expect(pane.signOut.kind == .secondary && !pane.signOut.isHiddenOrHasHiddenAncestor && pane.signIn.isHiddenOrHasHiddenAncestor)
        let lines = pane.text.split(separator: "\n").map(String.init)
        #expect(Array(lines.prefix(3)) == ["Uday", "@uday · Admin", "127.0.0.1:7979 · Connected"] && lines.count == 4, "the pane says \(lines)")
    }

    /// Signed out: "Sign in to Sail", the eyebrows over the two fields, one primary (Sign In with
    /// Passkey) and the rest secondary.
    @Test(arguments: [Look.light, .dark])
    func signedOutItHasOnePrimary(_ look: Look) throws {
        let pane = try Self.pane(remembering: "uday", Held(RecordedServer([]), holding: "nothing is held"))
        let window = Self.shown(pane, look: look)
        defer { window.orderOut(nil) }
        #expect(pane.text == "Sign in to Sail" && Self.eyebrows(pane) == ["CONTROL PLANE", "ENROLLMENT TICKET"], "\(pane.text), \(Self.eyebrows(pane))")
        #expect(pane.signIn.kind == .primary && pane.enroll.kind == .secondary && pane.signOut.isHiddenOrHasHiddenAncestor)
    }

    /// A refusal is said under the field it is about, its sentence in crimson and the rest in the
    /// secondary ink, never as a tinted row: a sign-in's under the control plane's field, above Sign
    /// In; an enrollment's under the ticket's, above Enroll.
    @Test(arguments: [Look.light, .dark])
    func aRefusalIsCrimsonUnderItsField(_ look: Look) async throws {
        let pane = try Self.pane(remembering: "uday", Held(RecordedServer([]), holding: "nothing is held"))
        let window = Self.shown(pane, look: look)
        defer { window.orderOut(nil) }
        func between(_ above: NSView, _ below: NSView) -> Bool {
            pane.layoutSubtreeIfNeeded()
            let said = pane.convert(pane.said.bounds, from: pane.said), top = pane.convert(above.bounds, from: above), bottom = pane.convert(below.bounds, from: below)
            return said.maxY <= top.minY + 0.5 && said.minY >= bottom.maxY - 0.5
        }
        pane.signIn.performClick(nil)
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the sign-in's refusal was never said")
        let words = pane.said.attributedStringValue
        #expect((words.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor) == NSColor(look.error), "the sentence is not crimson")
        #expect(between(pane.address, pane.signIn), "the sign-in's refusal is not under the control plane's field")
        pane.ticket.stringValue = Self.ticket
        pane.enroll.performClick(nil)
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment's refusal was never said")
        #expect(between(pane.ticket, pane.enroll), "the enrollment's refusal is not under the ticket's field")
        pane.look = look == .light ? .dark : .light
        #expect(between(pane.ticket, pane.enroll) && pane.ticketFrame.failed && !pane.addressFrame.failed, "a change of look moved the enrollment's refusal, or the field drawn refused")
    }

    /// The control plane's line is live as the menu's is: out of reach once a check found it so,
    /// reconnecting while one asks again, then its answer.
    @Test func theControlPlanesLineIsLive() async throws {
        let transport = OutOfReach()
        let defaults = try #require(UserDefaults(suiteName: "sail-account-\(UUID().uuidString)"))
        defaults.set("http://127.0.0.1:7979", forKey: AccountPane.addressKey)
        let pane = AccountPane(
            account: Account(authenticator: Signs(sheet: nil), vault: OneSession(fde: "uday")) { _ in transport }, look: .light,
            title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts), labels: try LabelFont(directory: LookTests.fonts),
            defaults: defaults
        )
        let window = Self.shown(pane)
        defer { window.orderOut(nil) }
        let check = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.asked == 1 })
        transport.answer()
        await check.value
        #expect(pane.planeRow.plane == .unreachable && pane.planeRow.said == "127.0.0.1:7979", "after the check the line says \(String(describing: pane.planeRow.plane))")
        let again = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.asked == 2 })
        #expect(pane.planeRow.plane == .reconnecting, "asking again the line says \(String(describing: pane.planeRow.plane))")
        transport.answer()
        await again.value
        #expect(pane.planeRow.plane == .unreachable)
    }

    /// A recorded `whoami` answering for `fde`, named `name`.
    static func whoami(_ fde: String, name: String) -> Exchange {
        let body = #"{"capabilities": ["read"], "display_name": "\#(name)", "email": "e@example.com", "fde": "\#(fde)", "name": "\#(fde)", "role": "member", "schema_version": 1}"#
        return Exchange(method: "GET", path: "/v1/whoami", status: 200, body: Data(body.utf8))
    }

    static let longHandle = String(repeating: "k", count: 63)
    static let longName = "Uday Chandra-Bhattacharyya of the Standard Applied Mac app team"

    /// M1: the pane and the menu say one thing, from one source: the heading, `@handle · Role` and
    /// the control plane's line are the menu content's name, detail, address and state, confirmed,
    /// during a check and out of reach.
    @Test func thePaneAndTheMenuCannotDisagree() async throws {
        func agree(_ pane: AccountPane, _ when: String) {
            let content = pane.menuContent
            #expect(pane.heading.stringValue == content.name && pane.handle.stringValue == (content.detail ?? ""), "\(when): \(pane.heading.stringValue) / \(pane.handle.stringValue) against \(content)")
            #expect(pane.planeRow.said == (content.address ?? "") && pane.planeRow.plane == content.plane, "\(when): the line says \(pane.planeRow.said), \(String(describing: pane.planeRow.plane)) against \(content)")
        }
        let confirmed = try Self.pane(remembering: "uday", Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held"))
        await confirmed.refresh()
        agree(confirmed, "confirmed")
        let transport = OutOfReach()
        let defaults = try #require(UserDefaults(suiteName: "sail-account-\(UUID().uuidString)"))
        defaults.set("http://127.0.0.1:7979", forKey: AccountPane.addressKey)
        let away = AccountPane(
            account: Account(authenticator: Signs(sheet: nil), vault: OneSession(fde: "ana")) { _ in transport }, look: .light,
            title: try TitleFont(directory: LookTests.fonts), mono: try TerminalFonts(directory: LookTests.fonts), labels: try LabelFont(directory: LookTests.fonts),
            defaults: defaults
        )
        let check = Task { await away.refresh() }
        #expect(await RunnerTabTests.until { transport.asked == 1 })
        transport.answer()
        await check.value
        agree(away, "out of reach")
        let again = Task { await away.refresh() }
        #expect(await RunnerTabTests.until { transport.asked == 2 })
        agree(away, "asking again")
        transport.answer()
        await again.value
    }

    /// M2: a long name and a long handle are cut at their end and said in full on hover; the session's
    /// end, confirmed or not, wraps.
    @Test func longWordsAreCutAndSaidInFull() async throws {
        let pane = try Self.pane(remembering: Self.longHandle, Held(RecordedServer([Self.whoami(Self.longHandle, name: Self.longName)]), holding: "nothing is held"))
        let window = Self.shown(pane, width: Double(ShellApp.leastFrame.width) - Rail.width - 4)
        defer { window.orderOut(nil) }
        await pane.refresh()
        pane.layoutSubtreeIfNeeded()
        for (label, tip) in [(pane.heading, pane.headingTip), (pane.handle, pane.handleTip)] {
            let style = label.attributedStringValue.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle
            #expect(style?.lineBreakMode == .byTruncatingTail && tip.shows && tip.text == label.stringValue, "\(label.stringValue.prefix(12))… is not cut at its end and said in full")
        }
        #expect(pane.until.maximumNumberOfLines != 1, "the session's end does not wrap")
    }

    /// Minor 1: a refused field is drawn refused, a crimson edge with the error's mark before the
    /// sentence, until the field is edited again.
    @Test func aRefusedFieldIsDrawnRefused() async throws {
        let pane = try Self.pane(remembering: "uday", Held(RecordedServer([]), holding: "nothing is held"))
        let window = Self.shown(pane)
        defer { window.orderOut(nil) }
        pane.signIn.performClick(nil)
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty })
        #expect(pane.addressFrame.failed && pane.addressFrame.ring.colour == NSColor(pane.look.error) && !pane.ticketFrame.failed, "the refused field is not drawn refused")
        #expect(pane.said.attributedStringValue.containsAttachments, "the sentence has no error mark")
        pane.address.stringValue = "https://sail.example.com"
        pane.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: pane.address))
        #expect(!pane.addressFrame.failed, "edited again, the field is still drawn refused")
    }

    /// A field the person corrected stays corrected when the pane says the same lines again (a
    /// change of look); one not touched stays drawn refused.
    @Test(arguments: [Look.light, .dark])
    func aCorrectedFieldStaysCorrectedAcrossALookChange(_ look: Look) async throws {
        let pane = try Self.pane(remembering: "uday", Held(RecordedServer([]), holding: "nothing is held"))
        let window = Self.shown(pane, look: look)
        defer { window.orderOut(nil) }
        pane.signIn.performClick(nil)
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty })
        pane.look = look == .light ? .dark : .light
        #expect(pane.addressFrame.failed, "untouched, the refused field is no longer drawn refused after a change of look")
        pane.address.stringValue = "https://sail.example.com"
        pane.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: pane.address))
        pane.look = look
        #expect(!pane.addressFrame.failed, "corrected, the field turned crimson again at a change of look")
    }

    /// Minor 2: which field a refusal is about ends with the action: after an enrollment's sentence
    /// under the ticket, a check that finds the session ended says so under the control plane's field.
    @Test func aLaterRefusalIsNotUnderTheEnrollmentsField() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami", after: 1, then: try Exchange("refusal-invalid-bearer-token"))
        let pane = try Self.pane(remembering: "uday", transport)
        let window = Self.shown(pane)
        defer { window.orderOut(nil) }
        await pane.refresh()
        pane.ticket.stringValue = Self.ticket
        pane.enroll.performClick(nil)
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment said nothing")
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.filter { $0.contains("whoami") }.count == 2 })
        transport.release()
        await checking.value
        pane.layoutSubtreeIfNeeded()
        #expect(pane.standing == nil && pane.lines.contains { $0.hasSuffix("has ended.") }, "\(pane.lines)")
        let said = pane.convert(pane.said.bounds, from: pane.said), field = pane.convert(pane.address.bounds, from: pane.address)
        let ticket = pane.convert(pane.ticket.bounds, from: pane.ticket)
        #expect(said.maxY <= field.minY + 0.5 && said.minY >= ticket.maxY, "the session's end is said at \(said), not under the control plane's field \(field)")
    }

    /// Nothing the pane shows is out of reach, signed out, refused and signed in, at every window
    /// size from the least frame up; signed in with the longest handle and a long name, confirmed and
    /// not yet confirmed.
    @Test func nothingInThePaneIsOutOfReach() async throws {
        var faults: [String] = []
        for size in UserMenuTests.sizes {
            let width = Double(size.width) - Rail.width - 4
            let out = try Self.pane(remembering: "uday", Held(RecordedServer([]), holding: "nothing is held"))
            let window = Self.shown(out, width: width)
            faults += WorkspacesReachTests.outOfReach(in: out).map { "\(width) pt, signed out: \($0)" }
            out.signIn.performClick(nil)
            _ = await RunnerTabTests.until { !out.isActing && !out.lines.isEmpty }
            faults += WorkspacesReachTests.outOfReach(in: out).map { "\(width) pt, refused: \($0)" }
            window.orderOut(nil)
            let signedIn = try Self.pane(remembering: "uday", Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held"))
            let other = Self.shown(signedIn, width: width)
            await signedIn.refresh()
            faults += WorkspacesReachTests.outOfReach(in: signedIn).map { "\(width) pt, signed in: \($0)" }
            other.orderOut(nil)
            let long = try Self.pane(remembering: Self.longHandle, Held(RecordedServer([Self.whoami(Self.longHandle, name: Self.longName)]), holding: "nothing is held"))
            let longWindow = Self.shown(long, width: width)
            await long.refresh()
            faults += WorkspacesReachTests.outOfReach(in: long).map { "\(width) pt, long and confirmed: \($0)" }
            longWindow.orderOut(nil)
            let failing = Held(RecordedServer([]), holding: "whoami", then: Exchange(method: "GET", path: "/v1/whoami", status: 502, body: Data("{}".utf8)))
            let unconfirmed = try Self.pane(remembering: Self.longHandle, failing)
            let unconfirmedWindow = Self.shown(unconfirmed, width: width)
            let checking = Task { await unconfirmed.refresh() }
            _ = await RunnerTabTests.until { failing.routes.contains { $0.contains("whoami") } }
            failing.release()
            await checking.value
            unconfirmed.layoutSubtreeIfNeeded()
            #expect(unconfirmed.until.stringValue.hasPrefix("Not yet confirmed"), "\(unconfirmed.until.stringValue)")
            faults += WorkspacesReachTests.outOfReach(in: unconfirmed).map { "\(width) pt, long and not yet confirmed: \($0)" }
            unconfirmedWindow.orderOut(nil)
        }
        #expect(faults.isEmpty, "\(faults.count) faults: \(faults.prefix(20).joined(separator: "\n"))")
    }

    /// A sign-in the server refuses, clicked while a check that will find a session is under
    /// way: the click starts it, its refusal is said, and the check's answer is still shown
    /// (the refusal set no one) with the refusal kept.
    @Test func aRefusedSignInDuringACheckLeavesTheCheckSignedInAndItsRefusalSaid() async throws {
        let transport = Held(RecordedServer([try Exchange("whoami")]), holding: "whoami")
        let pane = try Self.pane(remembering: "uday", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } }, "the check asked nothing: \(transport.routes)")
        #expect(pane.signIn.isEnabled, "the check holds Sign In")

        pane.signIn.performClick(nil)

        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("login") } }, "the click started no sign-in: \(transport.routes)")
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the sign-in's refusal was never said")
        let refused = pane.lines
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == "uday", "the check's answer was dropped though the refusal set no one")
        #expect(pane.lines == refused, "the refusal is no longer said: \(pane.lines)")
        #expect(!pane.busy)
    }

    /// The same, the check finding a remembered session the control plane does not confirm: the
    /// refusal is said, then why it is not confirmed, and the rows say it is not yet confirmed.
    @Test func aRefusedSignInDuringACheckThatCannotConfirmSaysBoth() async throws {
        let failing = Exchange(method: "GET", path: "/v1/whoami", status: 502, body: Data("{}".utf8))
        let transport = Held(RecordedServer([]), holding: "whoami", then: failing)
        let pane = try Self.pane(remembering: "uday", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })

        pane.signIn.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the sign-in's refusal was never said")
        let refused = pane.lines
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == "uday")
        #expect(pane.lines.starts(with: refused) && pane.lines.count > refused.count, "why it is not confirmed was not said: \(pane.lines)")
        #expect(pane.text.contains("Not yet confirmed: remembered on this Mac until "), "\(pane.text)")
        #expect(!pane.text.contains("Signed in until"), "\(pane.text)")
    }

    /// An enrollment while a check that will find a session is under way: the check's answer is
    /// shown, and the enrollment's sentence kept, but never "Sign in with it." beside the
    /// signed-in rows.
    @Test func anEnrollmentDuringACheckKeepsItsSentenceAndTheCheckSignedIn() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami")
        let pane = try Self.pane(remembering: "uday", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })
        pane.ticket.stringValue = Self.ticket

        pane.enroll.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment said nothing: \(transport.routes)")
        #expect(pane.lines == ["A passkey for @uday is on this Mac.", "Sign in with it."], "\(pane.lines)")
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == "uday", "the check's answer was dropped though the enrollment set no one")
        #expect(pane.lines == ["A passkey for @uday is on this Mac."], "\(pane.lines)")
    }

    /// A check that answers while the enrollment's passkey sheet is up: the pane does not change
    /// under the sheet; once the enrollment ends the check's answer is shown, never beside "Sign
    /// in with it.".
    @Test func aCheckAnsweringDuringAnEnrollmentWaitsForItToEnd() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami")
        let sheet = Gate()
        let pane = try Self.pane(remembering: "uday", transport, sheet: sheet)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })
        pane.ticket.stringValue = Self.ticket
        pane.enroll.performClick(nil)
        #expect(await RunnerTabTests.until { sheet.isWaiting }, "the passkey sheet never came up: \(transport.routes)")

        transport.release()
        await checking.value

        #expect(pane.standing == nil && pane.isActing, "the pane changed under the passkey sheet")
        sheet.open()
        #expect(await RunnerTabTests.until { !pane.isActing }, "the enrollment never ended")
        #expect(pane.standing?.fde == "uday", "the check's answer was never shown")
        #expect(pane.lines == ["A passkey for @uday is on this Mac."], "\(pane.lines)")
    }

    /// A check of an older session that answers while the sign-in's passkey sheet is up: the pane
    /// does not flip under the sheet, and the sign-in, once it ends, is not undone by it.
    @Test func aCheckAnsweringDuringASignInNeitherFlipsThePaneNorUndoesIt() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-login-start"), try Exchange("passkey-login-finish")])
        let transport = Held(server, holding: "whoami")
        let sheet = Gate()
        let pane = try Self.pane(remembering: "someone-before", transport, sheet: sheet)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })
        pane.signIn.performClick(nil)
        #expect(await RunnerTabTests.until { sheet.isWaiting }, "the passkey sheet never came up: \(transport.routes)")

        transport.release()
        await checking.value

        #expect(pane.standing == nil, "the pane flipped to \(pane.standing?.fde ?? "") under the passkey sheet")
        sheet.open()
        #expect(await RunnerTabTests.until { !pane.isActing }, "the sign-in never ended")
        #expect(pane.standing?.fde == "uday", "the check's late answer undid the sign-in: \(pane.standing?.fde ?? "no one")")
    }

    /// A sign-in that succeeds while a check of an older session is under way: the check's late
    /// answer, the older session, would undo it, so it is not shown.
    @Test func aSignInDuringACheckIsNotUndoneByTheChecksLateAnswer() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-login-start"), try Exchange("passkey-login-finish")])
        let transport = Held(server, holding: "whoami")
        let pane = try Self.pane(remembering: "someone-before", transport)
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.contains("whoami") } })

        pane.signIn.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && pane.standing != nil }, "the sign-in did not sign in: \(pane.lines)")
        let signedIn = pane.standing?.fde
        #expect(signedIn != nil && signedIn != "someone-before")
        transport.release()
        await checking.value
        #expect(pane.standing?.fde == signedIn, "the check's late answer undid the sign-in: \(pane.standing?.fde ?? "no one")")
    }

    /// An enrollment while a check finds the session ended: the enrollment set no one, so the
    /// check's answer, no one signed in, is shown, and said after the enrollment's sentence.
    @Test func anEnrollmentDuringACheckThatFindsTheSessionEndedShowsNoOneAndSaysBoth() async throws {
        let server = RecordedServer([try Exchange("whoami"), try Exchange("passkey-register-start"), try Exchange("passkey-register-finish")])
        let transport = Held(server, holding: "whoami", after: 1, then: try Exchange("refusal-invalid-bearer-token"))
        let pane = try Self.pane(remembering: "uday", transport)
        await pane.refresh()
        #expect(pane.standing?.fde == "uday", "the first check found no one: \(pane.lines)")
        let checking = Task { await pane.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.filter { $0.contains("whoami") }.count == 2 })
        pane.ticket.stringValue = Self.ticket

        pane.enroll.performClick(nil)

        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the enrollment said nothing: \(transport.routes)")
        let enrolled = pane.lines
        transport.release()
        await checking.value
        #expect(pane.standing == nil, "the session the server ended is still shown")
        #expect(pane.lines.starts(with: enrolled) && pane.lines.contains { $0.hasSuffix("has ended.") }, "\(pane.lines)")
    }

    /// Sign Out signs out at once and asks nothing, as one action of the pane's; the menu item
    /// after it does nothing more.
    @Test func signOutSignsOutAtOnceAskingNothing() async throws {
        let transport = Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held")
        let pane = try Self.pane(remembering: "uday", transport)
        await pane.refresh()
        #expect(pane.standing?.fde == "uday")
        let before = pane.actions

        pane.signOut.performClick(nil)
        pane.signOutHere(nil)

        #expect(pane.actions == before + 1, "one action, the second asking nothing")
        #expect(pane.standing == nil && !pane.isActing && pane.signIn.isEnabled)
    }
}
