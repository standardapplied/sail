import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailWorkbench
import Testing

/// Store a Login (`login-store-mockup.html`): the sheet over the New Session panel, from the
/// server's recorded answers. Every stage laid out whole at its width and the least window's, in both
/// looks and right to left, nothing out of reach, one accent primary and no grey; VoiceOver's names;
/// the keys; and through the section, a key pasted and stored leaves the panel ready with Start
/// holding the keys, a refusal and no answer said in place with the field cleared, Escape sends
/// nothing, the name asked only where the person has a login of the harness, and the clipboard
/// cleared only while it still holds the key. The value is an obvious fake held by no property,
/// default or word of the app.
///
/// One test at a time: each waits on the one main actor every AppKit test shares for the recorded
/// answers, within a bound of the clock, while the sweeps beside it (every stage at both widths, both
/// looks, both directions) hold that actor in long stretches, as the New Session panel's suite found.
@MainActor @Suite(.serialized) struct LoginSheetTests {
    init() {
        ExitWatch.install()
    }

    static let pasted = "sk-ant-api03-login-sheet-tests-fake"
    static let least = 480.0 - 2 * LoginSheet.inset

    /// The model for Claude Code in dock, the person's logins as `logins` recorded.
    static func model(logins: String = "logins", agents: String = "workspace-agents-dock-no-login") throws -> StoreLogin {
        let offered = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: try Exchange(agents).body)).offered
        let listed = try Endpoint.logins.decode(HTTPResponse(status: 200, headers: [:], body: try Exchange(logins).body)).logins
        let claude = try #require(offered.first)
        return try #require(StoreLogin(claude, logins: listed, address: "http://127.0.0.1:7979"))
    }

    /// The model at each stage the page draws, by name.
    static func stages() throws -> [(name: String, model: StoreLogin)] {
        let clipboard = StoreLogin.Clipboard(count: 1, text: nil)
        var filled = try model()
        filled.typed(holdsSomething: true)
        var storing = filled
        _ = storing.store(pasted, clipboard: clipboard)
        var named = try model(logins: "logins-no-default", agents: "workspace-agents-dock-no-default")
        named.typed(holdsSomething: true)
        var naming = named
        naming.changeName()
        var refusedName = named
        _ = refusedName.store(pasted, clipboard: clipboard)
        refusedName.answered(try answer("refusal-invalid-request-login-name"), clipboard: 1)
        var shown: [(String, StoreLogin)] = [("empty", try model()), ("filled", filled), ("storing", storing), ("the name asked", named), ("naming", naming), ("the name refused", refusedName)]
        for golden in ["refusal-invalid-request-login-lines", "refusal-invalid-request-login-characters", "refusal-invalid-request-login-long", "refusal-invalid-request-login-empty"] {
            var refused = storing
            refused.answered(try answer(golden), clipboard: 1)
            shown.append((golden, refused))
        }
        var unreached = storing
        unreached.answered(.failure(.unreachable("Could not connect to the server.")), clipboard: 1)
        shown.append(("unreached", unreached))
        return shown
    }

    static func answer(_ golden: String) throws -> Result<LoginStored, SailError> {
        let exchange = try Exchange(golden)
        do throws(SailError) {
            return .success(try Endpoint.storeLogin(pasted, kind: "api-key", harness: "claude-code", name: "default").decode(HTTPResponse(status: exchange.status, headers: [:], body: exchange.body)))
        } catch {
            return .failure(error)
        }
    }

    /// `model`'s sheet `width` wide in `look`, shown on screen, not attached.
    static func sheet(_ model: StoreLogin, width: Double = LoginSheet.width, look: Look = .light, rightToLeft: Bool = false) throws -> LoginSheet {
        let sheet = LoginSheet(
            model, width: width, direction: rightToLeft ? .rightToLeft : .leftToRight, look: look, titleFont: try TitleFont(directory: LookTests.fonts),
            mono: try LabelFont(directory: LookTests.fonts), pasteboard: NSPasteboard(name: NSPasteboard.Name("sail.tests.\(UUID().uuidString)"))
        )
        sheet.orderFront(nil)
        return sheet
    }

    // MARK: The sheet

    /// Each stage at 420 pt and at the least window's width, left to right and right to left: as
    /// wide as asked, nothing out of reach, every word whole; one accent fill, Store, on or off;
    /// no tint, no grey; the error ink only on a refusal; a refusal under the field it is about; the
    /// key's field and an editable name never in the sheet together.
    @Test(arguments: [Look.light, .dark])
    func eachStageIsWholeInReachAndCalm(_ look: Look) throws {
        var faults: [String] = []
        for (name, model) in try Self.stages() {
            for width in [LoginSheet.width, Self.least] {
                for rightToLeft in [false, true] {
                    let sheet = try Self.sheet(model, width: width, look: look, rightToLeft: rightToLeft)
                    defer { sheet.orderOut(nil) }
                    let scene = "\(name) at \(width) pt\(rightToLeft ? " right to left" : "")"
                    let content = try #require(sheet.contentView)
                    if !near(content.frame.width, width) { faults.append("\(scene): \(content.frame.width) pt wide") }
                    faults += WorkspacesReachTests.outOfReach(in: content).map { "\(scene): \($0)" }
                    let count = CalmTests.Count(marks(in: content))
                    let refused: String? = switch model.stage {
                    case let .refused(said): said
                    case let .naming(said): said
                    default: nil
                    }
                    if count.fill != 1 || count.tint != 0 || count.neutral != 0 || (count.error > 0) != (refused != nil) {
                        faults.append("\(scene): \(count)")
                    }
                    if let said = refused {
                        let shown = sheet.field.superview == nil ? sheet.name?.error : sheet.field.error
                        if shown != said { faults.append("\(scene): the refusal is not under its field") }
                    }
                    if sheet.field.superview != nil, sheet.name?.superview != nil { faults.append("\(scene): the key and an editable name are shown together") }
                    let naming: Bool = if case .naming = model.stage { true } else { false }
                    if naming != (sheet.field.superview == nil) { faults.append("\(scene): the key's field is \(naming ? "shown while naming" : "missing")") }
                    let primary = naming ? sheet.done : sheet.store
                    let start = Double(primary.alignmentRect(forFrame: primary.frame).minX), end = Double(primary.alignmentRect(forFrame: primary.frame).maxX)
                    let wanted = rightToLeft ? start : width - end
                    if abs(wanted - LoginSheet.footer.sides) > 0.5 { faults.append("\(scene): \(primary.title) is \(wanted) pt from the trailing edge") }
                }
            }
        }
        #expect(faults.isEmpty, "\(faults.count) faults, the first: \(faults.prefix(20).joined(separator: "\n"))")
    }

    /// VoiceOver names the sheet by its title, the field as the harness's key with the help as its
    /// description, Store as storing the harness's login and Storing while it runs, and a refusal as
    /// the field's error.
    @Test func voiceOverNamesWhatThePageSays() throws {
        let stages = try Self.stages()
        let empty = try Self.sheet(stages[0].model)
        defer { empty.orderOut(nil) }
        #expect(empty.title == "Store a Claude Code login")
        #expect(empty.field.field.accessibilityLabel() == "Claude Code API key" && empty.field.field.accessibilityHelp() == "Create an API key in the Claude Console.")
        #expect(empty.field.field is NSSecureTextField, "the key is typed into a field that shows it")
        #expect(empty.store.accessibilityLabel() == "Store Claude Code login" && !empty.store.isEnabled)
        let storing = try Self.sheet(stages[2].model)
        defer { storing.orderOut(nil) }
        #expect(storing.store.accessibilityLabel() == "Storing" && !storing.store.isEnabled && !storing.field.isEnabled)
        let refused = try Self.sheet(try #require(stages.first { $0.name == "refusal-invalid-request-login-lines" }).model)
        defer { refused.orderOut(nil) }
        #expect(refused.field.field.accessibilityHelp() == "A key is one line: paste only the key.")
    }

    /// What the sheet shows takes a click in the turn it is made, before any display pass.
    @Test func whatTheSheetShowsTakesAClickAtOnce() throws {
        var faults: [String] = []
        for (name, model) in try Self.stages() {
            let sheet = try Self.sheet(model)
            defer { sheet.orderOut(nil) }
            let controls: [NSView] = ([sheet.cancel, sheet.store, sheet.done, sheet.field.field, sheet.name?.field, sheet.change] as [NSView?]).compactMap(\.self).filter { $0.window != nil && !$0.isHiddenOrHasHiddenAncestor }
            faults += controls.compactMap { WorkspacesReachTests.missesAClick($0).map { "\(name): \($0)" } }
        }
        #expect(faults.isEmpty, "\(faults.joined(separator: "\n"))")
    }

    /// The keys start in the field; Tab goes field, Change, Cancel, Store and round; Change puts the
    /// key away, its value with it, Tab then going name, Cancel, Done, Return in the name is Done
    /// and the key comes back holding the keys; Return in the key stores, Store's key is Return,
    /// Escape cancels.
    @Test func theKeysGoAsThePageSays() throws {
        let sheet = try Self.sheet(try Self.model(logins: "logins-no-default", agents: "workspace-agents-dock-no-default"))
        defer { sheet.orderOut(nil) }
        let name = try #require(sheet.name), change = try #require(sheet.change)
        #expect(sheet.initialFirstResponder === sheet.field.field && name.superview == nil)
        #expect(sheet.field.field.nextKeyView === change && change.nextKeyView === sheet.cancel && sheet.cancel.nextKeyView === sheet.store && sheet.store.nextKeyView === sheet.field.field)
        #expect(sheet.store.keyEquivalent == "\r" && sheet.cancel.keyEquivalent.isEmpty && change.accessibilityLabel() == "Change name")
        sheet.send = { _ in .failure(.unreachable("not here")) }
        let editor = NSTextView()
        Self.paste(into: sheet)
        try NewSessionPanelTests.press(change)
        #expect(sheet.field.superview == nil && Self.cleared(sheet) && name.superview != nil && sheet.model.stage == .naming(refused: nil), "Change left the key in the sheet")
        #expect(name.field.nextKeyView === sheet.cancel && sheet.cancel.nextKeyView === sheet.done && sheet.done.nextKeyView === name.field)
        #expect(sheet.done.keyEquivalent == "\r" && sheet.store.keyEquivalent.isEmpty && sheet.store.isHidden && !sheet.done.isHidden)
        #expect(sheet.control(name.field, textView: editor, doCommandBy: #selector(NSResponder.insertNewline(_:))))
        #expect(sheet.field.superview != nil && name.superview == nil && sheet.model.stage == .empty, "Return in the name was not Done")
        #expect(sheet.field.help == "Paste the key again.", "the key Change put away is not asked again")
        Self.paste(into: sheet)
        #expect(sheet.control(sheet.field.field, textView: editor, doCommandBy: #selector(NSResponder.insertNewline(_:))))
        #expect(Self.cleared(sheet) && sheet.model.stage == .storing, "Return in the key did not store")
        var ended = 0
        sheet.onEnd = { ended += 1 }
        #expect(sheet.control(sheet.field.field, textView: editor, doCommandBy: #selector(NSResponder.cancelOperation(_:))))
        sheet.cancelOperation(nil)
        #expect(ended == 1, "Escape ended the sheet \(ended) times")
        #expect(!sheet.control(sheet.field.field, textView: editor, doCommandBy: #selector(NSResponder.moveLeft(_:))))
    }

    // MARK: Through the section

    /// harbor listed for uday, dock's agents recorded as `agents`, the logins as `logins`, a store
    /// answered as `stored`; the panel open on dock and Store a Login pressed, once the sheet is up.
    static func asked(
        agents: String = "workspace-agents-dock-no-login", logins: String = "logins", stored: [Exchange] = [], look: Look = .light
    ) async throws -> (view: WorkspacesView, window: KeyWindow, server: RecordedServer, sheet: LoginSheet) {
        let (view, window, server) = try await NewSessionPanelTests.listed([agents, logins], also: stored, look: look)
        view.pasteboard = NSPasteboard(name: NSPasteboard.Name("sail.tests.\(UUID().uuidString)"))
        let panel = try await NewSessionPanelTests.open("dock", in: view)
        try NewSessionPanelTests.press(panel.view.storeLogin)
        try #require(await RunnerTabTests.until { window.attachedSheet is LoginSheet }, "Store a Login opened no sheet: \(panel.view.words)")
        let sheet = try #require(window.attachedSheet as? LoginSheet)
        return (view, window, server, sheet)
    }

    /// The sheet's field holds nothing: said as a fact, so no failure prints what it holds.
    static func cleared(_ sheet: LoginSheet) -> Bool {
        sheet.field.field.stringValue.isEmpty
    }

    /// The key pasted into the sheet's field, as a paste changes it.
    static func paste(_ value: String = pasted, into sheet: LoginSheet) {
        sheet.field.field.stringValue = value
        sheet.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: sheet.field.field))
    }

    /// The usual case: the panel stays open behind the sheet, which asks no name; Store is off
    /// until the key is pasted. Stored, the field is empty at once, the one request held the key in
    /// its body; the sheet goes, the panel shows dock's agents asked again, Claude Code ready with
    /// its login, the store said in one line, Start (no longer Start and Sign In There) holding the
    /// keys, and Return starts it signed in; the server's note on the login it replaced is said. The
    /// key is in no default and no word the app shows.
    @Test func aKeyStoredLeavesThePanelReadyWithStartHoldingTheKeys() async throws {
        let (view, window, server, sheet) = try await Self.asked(stored: [try Exchange("login-replaced")])
        defer { window.orderOut(nil) }
        let panel = view.newSession
        #expect(panel.isOpen && panel.popover.stays && sheet.name == nil && !sheet.store.isEnabled)
        #expect(window.childWindows?.contains(panel.popover.panel) == true)
        Self.paste(into: sheet)
        let underSheet = CalmTests.Count(marks(in: window))
        #expect(underSheet.fill == 1 && underSheet.tint == 0 && underSheet.neutral == 0, "the window with the sheet up draws \(underSheet): one primary on screen, the sheet's Store")
        #expect(panel.view.primary?.kind == .secondary && panel.view.veiled, "the panel behind the sheet keeps its primary or is not under the sheet's veil")
        Self.paste(into: sheet)
        #expect(sheet.store.isEnabled)
        view.client = SailClient(transport: RecordedServer([try Exchange("workspace-agents-dock")]), token: "tok")
        try NewSessionPanelTests.press(sheet.store)
        #expect(Self.cleared(sheet), "the field kept the key while it was sent")
        try #require(await RunnerTabTests.until { window.attachedSheet == nil && panel.stored != nil && !view.busy }, "the panel never said the store: \(panel.view.words)")
        let puts = await server.requests.filter { $0.method == .put }
        #expect(puts.count == 1 && puts.first?.path == "/v1/logins/claude-code/default")
        let body = try #require(puts.first?.body)
        let sent = try JSONSerialization.jsonObject(with: body) as? [String: String] == ["kind": "api-key", "value": Self.pasted]
        #expect(sent, "the request's body is not the kind and the key")
        #expect(panel.isOpen && !panel.popover.stays && panel.loginSheet == nil && !panel.view.veiled)
        #expect(panel.view.rows.first?.line.stringValue == "login “default” · API key" && panel.view.rows.first?.chosen == true)
        #expect(panel.view.words.contains("Stored Claude Code login “default”. An agent session already running keeps the login it started with until it ends; no workspace keeps any of it.") && !panel.view.words.contains { $0.hasPrefix("No Claude Code login stored") }, "the store, and the server's note on the login it replaced, are not said: \(panel.view.words)")
        let start = try #require(panel.view.primary)
        #expect(start.title == "Start" && start.isEnabled && start.holdsKeys)
        var started: [AgentStart] = []
        view.onStart = { start, _, _ in started.append(start) }
        try NewSessionPanelTests.press(try NewSessionPanelTests.returnKey(), in: window)
        #expect(started == [AgentStart(harness: "claude-code", display: "Claude Code", mode: "default")], "Return started \(started)")
        let inDefaults = String(describing: UserDefaults.standard.dictionaryRepresentation()).contains(Self.pasted)
        let said = view.text.contains(Self.pasted) || Driverless.words(window.contentView).contains(Self.pasted)
        #expect(!inDefaults && !said, "the key is in the defaults or in what the app says")
    }

    /// The clipboard still holding exactly the key once stored is cleared, and the line says so;
    /// one that changed meanwhile is left alone, and the line says nothing of it.
    @Test(arguments: [true, false])
    func theClipboardIsClearedOnlyWhileItStillHoldsTheKey(_ unchanged: Bool) async throws {
        let (view, window, _, sheet) = try await Self.asked(stored: [try Exchange("login-stored")])
        defer { window.orderOut(nil) }
        view.pasteboard.clearContents()
        view.pasteboard.setString(Self.pasted, forType: .string)
        Self.paste(into: sheet)
        view.client = SailClient(transport: RecordedServer([try Exchange("workspace-agents-dock")]), token: "tok")
        let stored = try Exchange("login-stored"), pasteboard = view.pasteboard
        sheet.send = { endpoint in
            if !unchanged {
                pasteboard.clearContents()
                pasteboard.setString(Self.pasted, forType: .string)
            }
            do throws(SailError) {
                return .success(try endpoint.decode(HTTPResponse(status: stored.status, headers: [:], body: stored.body)))
            } catch {
                return .failure(error)
            }
        }
        try NewSessionPanelTests.press(sheet.store)
        try #require(await RunnerTabTests.until { view.newSession.stored != nil && !view.busy })
        let line = "Stored Claude Code login “default”." + (unchanged ? " Removed the key from the clipboard." : "")
        #expect(view.newSession.stored == line && view.newSession.view.words.contains(line))
        let left = view.pasteboard.string(forType: .string) == (unchanged ? nil : Self.pasted)
        #expect(left, unchanged ? "the clipboard still holds the key" : "the clipboard changed meanwhile was cleared")
    }
}

extension LoginSheetTests {
    /// A refusal is the server's sentence under the field, the field cleared and holding the keys,
    /// Store off and the sheet up; the panel stays as it was.
    @Test func aRefusalIsSaidUnderTheFieldWhichIsClearedAndHoldsTheKeys() async throws {
        let (view, window, _, sheet) = try await Self.asked(stored: [try Exchange("refusal-invalid-request-login-lines")])
        defer { window.orderOut(nil) }
        Self.paste(Self.pasted + "\n" + Self.pasted, into: sheet)
        try NewSessionPanelTests.press(sheet.store)
        try #require(await RunnerTabTests.until { sheet.field.error != nil }, "no refusal was said")
        #expect(sheet.field.error == "A key is one line: paste only the key." && Self.cleared(sheet))
        #expect(sheet.firstResponder === sheet.field.field || (sheet.firstResponder as? NSText)?.delegate === sheet.field.field, "the keys are not in the field: \(String(describing: sheet.firstResponder))")
        #expect(!sheet.store.isEnabled && window.attachedSheet === sheet && view.newSession.isOpen && view.newSession.stored == nil)
        Self.paste(into: sheet)
        #expect(sheet.field.error == nil && sheet.store.isEnabled, "pasting again kept the refusal")
        sheet.withdraw()
    }

    /// No answer: nothing was stored, said with the control plane's name, the field cleared.
    @Test func noAnswerSaysNothingWasStored() async throws {
        let (_, window, _, sheet) = try await Self.asked()
        defer { window.orderOut(nil) }
        Self.paste(into: sheet)
        try NewSessionPanelTests.press(sheet.store)
        try #require(await RunnerTabTests.until { sheet.field.error != nil })
        #expect(sheet.field.error == "Can't reach 127.0.0.1. Nothing was stored." && Self.cleared(sheet))
        sheet.withdraw()
    }

    /// Escape, and Cancel, send nothing and clear the field; the panel stays open behind, as it was.
    @Test(arguments: [true, false])
    func escapeAndCancelSendNothing(_ escape: Bool) async throws {
        let (view, window, server, sheet) = try await Self.asked(stored: [try Exchange("login-stored")])
        defer { window.orderOut(nil) }
        Self.paste(into: sheet)
        if escape { sheet.cancelOperation(nil) } else { try NewSessionPanelTests.press(sheet.cancel) }
        #expect(window.attachedSheet == nil && Self.cleared(sheet))
        #expect(await server.requests.filter { $0.method == .put }.isEmpty, "a cancelled sheet sent the key")
        #expect(view.newSession.view.primary?.kind == .primary && !view.newSession.view.veiled, "the panel's primary did not come back with the sheet gone")
        #expect(view.newSession.isOpen && !view.newSession.popover.stays && view.newSession.view.primary?.title == "Start and Sign In There")
    }

    /// With a Claude Code login that is not the default, `work` is suggested as words beside the one
    /// they have, never in an editable field beside the key; Change names it, Done returns to the
    /// key, and the store names it; a name breaking the rule names it again, the refusal under it,
    /// the key's field put away and empty.
    @Test func theNameIsAskedWhereThePersonHasALoginOfTheHarness() async throws {
        let refused = try Exchange("refusal-invalid-request-login-name")
        let (_, window, server, sheet) = try await Self.asked(agents: "workspace-agents-dock-no-default", logins: "logins-no-default", stored: [refused])
        defer { window.orderOut(nil) }
        let name = try #require(sheet.name, "no name was asked of a person with a Claude Code login")
        let said = Driverless.words(sheet.contentView)
        #expect(name.superview == nil && said.range(of: #"Name\s+work\s"#, options: .regularExpression) != nil && said.contains("you already have “home”"), "\(said)")
        try NewSessionPanelTests.press(sheet.change)
        name.field.stringValue = "Work"
        sheet.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: name.field))
        try NewSessionPanelTests.press(sheet.done)
        #expect(Driverless.words(sheet.contentView).range(of: #"Name\s+Work\s"#, options: .regularExpression) != nil && name.superview == nil)
        Self.paste(into: sheet)
        try NewSessionPanelTests.press(sheet.store)
        try #require(await RunnerTabTests.until { name.error != nil })
        #expect(name.error == refused.said && name.superview != nil && sheet.field.superview == nil && Self.cleared(sheet))
        #expect(!sheet.done.isEnabled, "Done is on with the refused name unchanged")
        name.field.stringValue = "work"
        sheet.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: name.field))
        #expect(sheet.done.isEnabled && name.error == nil, "a new name kept Done off or its refusal")
        #expect(await server.requests.filter { $0.method == .put }.map(\.path) == ["/v1/logins/claude-code/Work"])
        sheet.withdraw()
    }

    /// The panel closing, its section hidden, ends the sheet; a click in the sheet and the sheet
    /// taking the window's keys leave the panel open, and its controls do nothing under the sheet.
    /// That the sheet is drawn over the panel is the window server's order, which the drive reads.
    @Test func theSheetGoesWithThePanelAndThePanelStaysUnderIt() async throws {
        let (view, window, _, sheet) = try await Self.asked()
        defer { window.orderOut(nil) }
        let panel = view.newSession
        let inside = try #require(NSEvent.mouseEvent(with: .leftMouseDown, location: NSPoint(x: 20, y: 20), modifierFlags: [], timestamp: 0, windowNumber: sheet.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1))
        panel.popover.clickedAnywhere(inside)
        NotificationCenter.default.post(name: panel.popover.keysLeft, object: window)
        #expect(panel.isOpen, "a click in the sheet or the sheet taking the keys closed the panel")
        var started = 0
        view.onStart = { _, _, _ in started += 1 }
        try NewSessionPanelTests.press(panel.view.primary)
        #expect(started == 0 && panel.isOpen && window.attachedSheet === sheet, "the panel's Start acted under its sheet")
        Self.paste(into: sheet)
        view.isHidden = true
        #expect(!panel.isOpen && window.attachedSheet == nil && panel.loginSheet == nil && Self.cleared(sheet))
    }

    /// A change of look while the sheet asks reaches all of it.
    @Test func aChangeOfLookReachesTheSheet() async throws {
        let (view, window, _, sheet) = try await Self.asked()
        defer { window.orderOut(nil) }
        view.look = .dark
        #expect(sheet.look == .dark)
        let kept = WorkspacesViewTests.keptLightAnywhere(view)
        #expect(kept.isEmpty, "kept the light look: \(kept)")
        sheet.withdraw()
    }
}

extension Exchange {
    /// The sentence this recorded refusal says.
    var said: String? {
        try? (JSONSerialization.jsonObject(with: body) as? [String: Any]).flatMap { ($0["error"] as? [String: Any])?["message"] as? String }
    }
}

extension LoginSheetTests {
    /// The person's logins not read: the panel says why, in the server's words where it gives them,
    /// and no sheet opens (a name not asked could replace one they have).
    @Test func loginsNotReadOpenNoSheetAndSayWhy() async throws {
        let (view, window, _) = try await NewSessionPanelTests.listed(["workspace-agents-dock-no-login"])
        defer { window.orderOut(nil) }
        let panel = try await NewSessionPanelTests.open("dock", in: view)
        try NewSessionPanelTests.press(panel.view.storeLogin)
        try #require(await RunnerTabTests.until { !view.busy }, "the ask of the logins never ended")
        #expect(window.attachedSheet == nil && panel.isOpen, "a sheet opened with the logins not read")
        #expect(panel.line == .unread("Can't reach 127.0.0.1. Nothing can be stored now.") && panel.view.words.contains("Can't reach 127.0.0.1. Nothing can be stored now."), "\(panel.view.words)")
        #expect(panel.view.primary?.holdsKeys == false && panel.stored == nil)
        view.client = SailClient(transport: RecordedServer([try Exchange("logins")]), token: "tok")
        try NewSessionPanelTests.press(panel.view.storeLogin)
        try #require(await RunnerTabTests.until { window.attachedSheet is LoginSheet }, "a later listing that succeeded opened no sheet")
        #expect(panel.line == nil, "the line of the failed listing stayed once one succeeded")
        window.attachedSheet?.cancelOperation(nil)
        #expect(window.attachedSheet == nil && panel.isOpen && panel.line == nil && !panel.view.words.contains { $0.hasPrefix("Can't reach") }, "the failed listing's line came back")
        let refused = try Exchange("refusal-forbidden")
        let forbidden = Exchange(method: "GET", path: "/v1/logins", status: refused.status, body: refused.body)
        let (other, otherWindow, _) = try await NewSessionPanelTests.listed(["workspace-agents-dock-no-login"], also: [forbidden])
        defer { otherWindow.orderOut(nil) }
        let again = try await NewSessionPanelTests.open("dock", in: other)
        try NewSessionPanelTests.press(again.view.storeLogin)
        try #require(await RunnerTabTests.until { !other.busy })
        let said = try #require(refused.said)
        #expect(otherWindow.attachedSheet == nil && again.line == .unread(said), "the server's refusal of the listing is not its words: \(String(describing: again.line))")
    }

    /// Cancel while storing, then the store succeeds: with the panel still open it says so, and the
    /// clipboard still holding the key is cleared and said; with the panel gone under the sheet,
    /// nothing says it, so the clipboard is left as it was.
    @Test(arguments: [true, false])
    func aStoreAnsweredAfterTheSheetEndedClearsTheClipboardOnlyWhereItIsSaid(_ panelOpen: Bool) async throws {
        let (view, window, _, sheet) = try await Self.asked()
        defer { window.orderOut(nil) }
        view.pasteboard.clearContents()
        view.pasteboard.setString(Self.pasted, forType: .string)
        Self.paste(into: sheet)
        view.client = SailClient(transport: RecordedServer([try Exchange("workspace-agents-dock")]), token: "tok")
        let stored = try Exchange("login-stored"), gate = Gate()
        sheet.send = { endpoint in
            await gate.wait()
            do throws(SailError) {
                return .success(try endpoint.decode(HTTPResponse(status: stored.status, headers: [:], body: stored.body)))
            } catch {
                return .failure(error)
            }
        }
        try NewSessionPanelTests.press(sheet.store)
        sheet.cancelOperation(nil)
        #expect(window.attachedSheet == nil && sheet.model.stage == .storing)
        if !panelOpen { view.isHidden = true }
        gate.open()
        try #require(await RunnerTabTests.until { if case .stored = sheet.model.stage { true } else { false } }, "the store never answered")
        if panelOpen {
            try #require(await RunnerTabTests.until { view.newSession.stored != nil && !view.busy })
            #expect(view.newSession.stored == "Stored Claude Code login “default”. Removed the key from the clipboard.")
            #expect(view.pasteboard.string(forType: .string) == nil, "the clipboard said cleared still holds the key")
        } else {
            let kept = view.pasteboard.string(forType: .string) == Self.pasted
            #expect(kept && !view.newSession.isOpen, "the clipboard was cleared with nothing to say so")
        }
    }
}
