import AppKit
import Darwin
import QuartzCore
import SailLook
import SailRenderer
import SailShell
import SailMeasures
import SailSurface
import SailTerminal
import SailTerminalView
import SailTestPasskey
import SailVT
import SailWorkbench

/// Runs a drive script in the real app against the real local host and a real control plane, the
/// software passkey in the platform's place. Every key, click and drag is an `NSEvent` sent through
/// the app's own `sendEvent`, so no Accessibility or Screen Recording permission is needed; the
/// wheel goes to the view's `scrollWheel`, since a wheel event made by hand carries no window. After
/// each step on Terminals the terminal's last frame is saved as an image, rendered in-process, with
/// the screen's text, and after each step on the account the pane's text; at the end a JSON report.
@MainActor
final class Driver {
    let shell: ShellApp
    let passkey: SoftwarePasskey
    let signed: Bool
    var tokens: Set<String> = []
    /// Every login value a step pasted, which the sweep searches for.
    var pastedLogins: Set<String> = []
    var askedAtStart = 0
    private let steps: [Step]
    private let goesOn: Bool
    /// A script that goes on past a failed step waits at most this long at each later wait, so a
    /// run that failed early prints its summary before the drive's alarm.
    static let afterAFailure = 3.0

    /// A step has failed and the script goes on.
    var failedSoFar: Bool {
        goesOn && results.contains { !$0.ok }
    }
    let out: URL
    var results: [Result] = []
    var measures: [Measure] = []
    private var bench: [String: Double] = [:]
    /// A `resetLatency` step opened a measured window that a `shownWithin` step has not yet judged:
    /// what the drive saves after each step inside it neither draws nor writes on the main thread.
    private var measuring = false
    private var started = false
    var panes4: [String: Double] = [:]
    /// The session `remember` kept.
    var remembered: String?
    /// The top line `markTop` kept.
    var markedTop: String?
    /// The view `hold` kept, and how many refreshes it had made then.
    var held: (view: TerminalView, refreshes: Int)?
    /// How many times the last divider drag held still for the session's resize hold.
    var dragHolds = 0
    /// Where the Workspaces section stood just before the last window step resized the window, and
    /// when the New session panel last opened (`workspacesLayout`).
    var layoutBeforeResize = "", layoutAtOpen = ""
    lazy var standIn: TerminalView = {
        guard let fonts = Bundle.main.resourceURL.flatMap({ try? TerminalFonts(directory: $0.appending(path: "Fonts")) }),
              let view = try? TerminalView(fonts: fonts)
        else { fatalError("the drive's bundle has no fonts for a terminal view") }
        return view
    }()
    let mainThread = Heartbeat.onTheMainThread { CACurrentMediaTime() }
    let mainTurns = Turns.onTheMainRunLoop()
    let ownThread = Heartbeat.onItsOwnThread { CACurrentMediaTime() }
    private var madeStacks: MainStacks?
    /// The main thread's sampler, begun the first time a measure asks for it, and kept.
    var mainStacks: MainStacks {
        if let madeStacks { return madeStacks }
        let host = shell.thisMac.host
        let made = MainStacks(watching: mainThread) { host.servingProcess()?.pid }
        madeStacks = made
        return made
    }
    /// Every stretch of a judged wait excused as the machine's, with its evidence, once each.
    private var excused: [String] = []
    /// Whether the step under way was judged on a machine read starved, and every step that was.
    private var notCleanInStep = false
    private var notClean: [String] = []
    /// Every timing gate's verdict this run, a row each of `figures.tsv`: the figure against its
    /// marks and the machine as read over its step, kept so drift shows across runs.
    private var figureRows: [String] = []

    struct Result: Encodable {
        let index: Int
        let name: String
        let ok: Bool
        let skipped: Bool
        let seconds: Double
        let detail: String
    }

    struct Measure: Encodable {
        let name: String
        let drawables: Int
        let keys: Int
        let figures: LatencyFigures
        let budgets: [String: Bool]
        /// The longest the main thread, and a thread of the drive's own, went without running, in
        /// milliseconds, over the same span as `figures`.
        let mainThreadHeld: Double
        let ownThreadHeld: Double
    }

    struct Report: Encodable {
        let passed: Bool
        let hertz: Int
        let scale: Double
        let steps: [Result]
        let measures: [Measure]
        let bench: [String: Double]
        let panes4: [String: Double]
    }

    init(shell: ShellApp, passkey: SoftwarePasskey, signed: Bool, script: Script, out: URL) {
        self.shell = shell
        self.passkey = passkey
        self.signed = signed
        steps = script.steps
        goesOn = script.goesOn == true
        self.out = out
        askedAtStart = passkey.asked
    }

    /// The focused pane of Terminals, which the terminal steps act on.
    var pane: TerminalPane? {
        shell.thisMac.focused
    }

    /// The focused pane's view; with none, a view that draws nothing, so a step that needs one fails
    /// on what it finds.
    var terminal: TerminalView {
        pane?.terminal ?? standIn
    }

    /// Whether the steps are on Terminals with a session drawn.
    var onTerminal: Bool {
        shell.rail.selected == .sessions && pane?.session != nil
    }

    /// Runs the script once, holding no process activity: the app is measured as a person runs it,
    /// in front, where App Nap does not take an app.
    func start() {
        guard !started else { return }
        started = true
        Task { await run() }
    }

    private func run() async {
        _ = await waitUntil(10) { self.shell.window.isVisible && self.shell.rail.selected != nil }
        for (offset, step) in steps.enumerated() {
            let index = offset + 1
            let name = step.name ?? "step"
            let began = ContinuousClock.now
            if let only = step.signed, only != signed {
                let build = signed ? "signed" : "unsigned"
                results.append(Result(index: index, name: name, ok: true, skipped: true, seconds: 0, detail: "skipped: this build is \(build)"))
                Self.say("drive \(index) \(name): skipped on \(signed ? "a signed" : "an unsigned") build")
                continue
            }
            notCleanInStep = false
            var (ok, detail) = await perform(step, name: name)
            if notCleanInStep { notClean.append("\(index) \(name)") }
            await Task.yield()
            let drew = await waitUntil(Self.drawDeadline) {
                !self.onTerminal || self.measuring || self.terminal.visibleText == self.screenText && self.terminal.isDrawnInFull
            }
            if ok, !drew {
                ok = false
                detail = "the view never drew the screen in full: it shows \(terminal.visibleText.count) characters, the screen holds "
                    + "\(screenText.count), every glyph drawn: \(terminal.isDrawnInFull)"
            }
            if !ok {
                detail += "; " + whereTheWindowIs
                if onTerminal { detail += "; " + terminal.drawingState }
                await Self.captureTheScreen(to: out.appending(path: String(format: "%02d-", index) + name + "-screen.png"))
            }
            let seconds = (ContinuousClock.now - began) / .seconds(1)
            let skipped = ok && detail.hasPrefix("skipped: ")
            results.append(Result(index: index, name: name, ok: ok, skipped: skipped, seconds: seconds, detail: detail))
            save(index: index, name: name)
            Self.say("drive \(index) \(name): \(skipped ? "skipped" : ok ? "ok" : "FAILED") in \(String(format: "%.2f", seconds)) s \(detail)")
            if !ok, !goesOn { break }
        }
        let failed = results.filter { !$0.ok }.map { "\($0.index) \($0.name)" }
        if !failed.isEmpty { Self.say("drive: \(failed.count) step\(failed.count == 1 ? "" : "s") FAILED: " + failed.joined(separator: ", ")) }
        Self.say("drive: \(notClean.count) step\(notClean.count == 1 ? "" : "s") not measured cleanly: machine starved, judged at twice the ceilings"
            + (notClean.isEmpty ? "" : ": " + notClean.joined(separator: ", ")))
        let withinClaim = madeStacks.map { $0.watcherShare <= MainStacks.claim } ?? true
        let lookCheap = madeStacks.map { $0.averageLook <= MainStacks.computation } ?? true
        let stayedRealTime = madeStacks.map { !$0.demoted } ?? true
        let stretches = "drive: \(excused.count) stretch\(excused.count == 1 ? "" : "es") of a judged wait excused as the machine's"
            + (excused.isEmpty ? "" : ": " + excused.joined(separator: "; "))
        let sampler = madeStacks.map { "; the main thread's sampler took \($0.cost)" } ?? "; the main thread's sampler never ran: no step measured a wait"
        let overClaim = withinClaim ? "" : "; FAILED: the watcher took more of a CPU than its claim"
        let overLook = lookCheap ? "" : String(format: "; FAILED: a look took more on average than the watcher's computation of %.0f µs", MainStacks.computation * 1e6)
        let demoted = stayedRealTime ? "" : "; FAILED: the watcher was demoted out of real time"
        Self.say(stretches + sampler + overClaim + overLook + demoted)
        if !figureRows.isEmpty {
            let table = (["figure\tvalue\tbudget\tmark\tceiling\tidle %\tours %\tstarved"] + figureRows).joined(separator: "\n") + "\n"
            try? table.write(to: out.appending(path: "figures.tsv"), atomically: true, encoding: .utf8)
        }
        let passed = results.count == steps.count && results.allSatisfy(\.ok) && withinClaim && lookCheap && stayedRealTime
        let report = Report(
            passed: passed, hertz: shell.window.screen?.maximumFramesPerSecond ?? 0, scale: terminal.metrics.scale, steps: results,
            measures: measures, bench: bench, panes4: panes4
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? encoder.encode(report).write(to: out.appending(path: "report.json"))
        Self.faultOnQuit()
        guard passed else { exit(1) }
        NSApp.terminate(nil)
    }

    /// A fault at quit, for `ci/drive`'s own check that a crash or a hang there fails a run whose
    /// report says it passed: `SAIL_DRIVE_ON_QUIT=crash` aborts, `hang` never returns.
    private static func faultOnQuit() {
        switch ProcessInfo.processInfo.environment["SAIL_DRIVE_ON_QUIT"] {
        case "crash"?:
            abort()
        case "hang"?:
            while true {
                sleep(60)
            }
        default:
            break
        }
    }

    /// Every word `view` shows, a secure field's text left out: a key typed there is no word to say.
    static func words(in view: NSView?) -> [String] {
        guard let view else { return [] }
        let said = view is NSSecureTextField ? nil : (view as? NSTextField).map { [$0.stringValue] }
        return (said ?? []) + view.subviews.flatMap { words(in: $0) }
    }

    /// What a step may do while a sheet an earlier step opened is up over the window: answer it,
    /// read it, shoot it, set what the control plane answers under it, or press the keys and menus
    /// that answer it. Any other step begun under a sheet fails at once, saying which is up, rather
    /// than every step after failing under it.
    static let underASheet: Set<String> = [
        "confirm", "sheet", "asking", "sheetShows", "answeredWhileSheetShows", "sheetClick", "fileSheet", "fileSheetUp", "fileSheetSays", "fileCompare", "fileSave", "fileWrite", "fileTheirs", "fileHolds",
        "filesShot", "filesAct", "filesName", "filesNameSays", "filesDropInto", "filesHover", "filesMenu", "filesAsks", "filesAnswer", "terminalTyped", "shot", "shotScreen", "deletesAsked", "questionsWaiting", "releaseAnswers", "relistAs", "golden", "proxyFails",
        "press", "modifiers", "menuItem", "contextMenu", "choose", "arrangement", "at", "fontPoints", "items", "seconds", "name",
        "pasteLogin", "loginSheetSays", "loginSheetIs", "loginSheetClick", "pasteboardHolds", "setPasteboard", "holdAnswers", "unreachable",
        "projectSheetType", "projectSheetClick", "projectSheetIs", "projectSheetReturnTwice", "goldenRenamed", "createAsked", "projects", "noRows", "templateRead", "window", "leastWindow",
        "accountShot", "accountSays",
    ]

    /// The step's keys that are set.
    static func keys(of step: Step) -> Set<String> {
        Set(Mirror(reflecting: step).children.compactMap { child in
            guard let label = child.label else { return nil }
            let value = Mirror(reflecting: child.value)
            return value.displayStyle == .optional && value.children.isEmpty ? nil : label
        })
    }

    private func perform(_ step: Step, name: String) async -> (Bool, String) {
        if let sheet = shell.window.attachedSheet {
            let stray = Self.keys(of: step).subtracting(Self.underASheet)
            if !stray.isEmpty {
                let words = Self.words(in: sheet.contentView).joined(separator: " ")
                return (false, "a sheet no step answered is up over the window, saying \"\(words)\": this step (\(stray.sorted().joined(separator: ", "))) does not answer it")
            }
        }
        if let account = await performAccount(step) {
            return account
        }
        if let workbench = await performWorkbench(step) {
            return workbench
        }
        if let workspaces = await performWorkspaces(step) {
            return workspaces
        }
        if let kit = await performKit(step) {
            return kit
        }
        if let typed = step.type {
            let text = typed.replacingOccurrences(of: "{private}", with: Self.privateFolder)
            for character in text {
                key(character)
                await Task.yield()
            }
            return (true, "typed \(text.count) keys")
        }
        if let press = step.press {
            guard let key = Keys.named(press, modifiers: step.modifiers ?? []) else { return (false, "no key named \(press)") }
            send(key)
            return (true, "")
        }
        if let marked = step.compose {
            for text in marked {
                terminal.setMarkedText(text, selectedRange: NSRange(location: text.utf16.count, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
            }
            if let commit = step.commit { terminal.insertText(commit, replacementRange: NSRange(location: NSNotFound, length: 0)) }
            return (true, "")
        }
        if let cell = step.click, cell.count == 2 {
            click(cell, clicks: step.clicks ?? 1, shift: step.shift ?? false)
            return (true, "")
        }
        if let cells = step.drag, cells.count == 2, cells.allSatisfy({ $0.count == 2 }) {
            drag(from: cells[0], to: cells[1])
            return (true, "")
        }
        if let lines = step.scroll {
            guard let wheel = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 1, wheel1: Int32(-lines), wheel2: 0, wheel3: 0),
                  let event = NSEvent(cgEvent: wheel)
            else { return (false, "no wheel event could be made") }
            terminal.scrollWheel(with: event)
            return (true, "")
        }
        if let size = step.resize, size.count == 2 {
            shell.window.setContentSize(NSSize(width: size[0], height: size[1]))
            return (true, "")
        }
        if let font = step.font {
            switch font {
            case "bigger": terminal.increaseFontSize(nil)
            case "smaller": terminal.decreaseFontSize(nil)
            default: terminal.resetFontSize(nil)
            }
            return (true, "\(terminal.fontPoints) pt, grid \(terminal.grid.map(\.description) ?? "none")")
        }
        if let points = step.fontPoints {
            return (terminal.fontPoints == points, "\(terminal.fontPoints) pt, grid \(terminal.grid.map(\.description) ?? "none")")
        }
        if step.copy == true {
            NSPasteboard.general.clearContents()
            guard let copy = Keys.named("c", modifiers: ["command"]) else { return (false, "no ⌘C") }
            send(copy)
            let held = NSPasteboard.general.string(forType: .string) ?? ""
            let said = "the pasteboard holds \(held.count) characters" + (held.count <= 40 ? ": '\(held)'" : "")
            if let expected = step.pasteboard { return (held == expected, said) }
            return (!held.isEmpty, said)
        }
        if let text = step.paste {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            guard let paste = Keys.named("v", modifiers: ["command"]) else { return (false, "no ⌘V") }
            send(paste)
            guard let confirm = step.confirm else { return (true, "") }
            let asked = await waitUntil(step.seconds ?? 5) { self.shell.window.attachedSheet != nil }
            guard asked, let sheet = shell.window.attachedSheet, let button = Self.button(confirm ? "Paste" : "Cancel", in: sheet.contentView)
            else { return (false, "no confirmation was asked") }
            button.performClick(nil)
            return (true, confirm ? "pasted after the sheet" : "cancelled at the sheet")
        }
        if let count = step.drawables {
            terminal.drawableCount = count
            return (terminal.drawableCount == count, "\(terminal.drawableCount) drawables")
        }
        if step.bench == true {
            guard let fonts = Bundle.main.resourceURL?.appending(path: "Fonts") else { return (false, "the bundle has no fonts") }
            do {
                let judged = try Bench.run(fonts: fonts)
                bench = judged.figures
                return Bench.verdict(judged)
            } catch {
                return (false, "the bench could not run: \(error)")
            }
        }
        if step.latencyOverlay == true {
            return await latencyOverlay(within: step.seconds ?? 5)
        }
        if step.machineState == true {
            return machineState
        }
        if step.resetLatency == true {
            resetLatency()
            measuring = true
            return (true, "")
        }
        if let wanted = step.line {
            let line = wanted.replacingOccurrences(of: "{home}", with: NSHomeDirectory())
            let found = await waitUntil(step.seconds ?? 10) {
                let lines = self.drawnLines
                return lines.indices.contains { index in
                    lines[index] == line && (step.after.map { above in index > 0 && lines[index - 1] == above } ?? true)
                }
            }
            return (found, found ? "" : "no line '\(line)'\(step.after.map { " under '\($0.prefix(40))'" } ?? "") within \(step.seconds ?? 10) s")
        }
        if let top = step.top {
            let found = await waitUntil(step.seconds ?? 10) { self.drawnLines.first == top }
            return (found, "the top line is '\(drawnLines.first ?? "")'")
        }
        if step.gridColumns == true {
            let columns = terminal.grid.map { "\($0.columns)" } ?? "none"
            let found = await waitUntil(step.seconds ?? 10) { self.lines.contains(columns) }
            return (found, "the grid is \(columns) columns")
        }
        if let limit = step.shownWithin {
            measuring = false
            let figures = terminal.latency
            let judged = flood(figures)
            if let gap = mainThread.stalls.longest() {
                mainStacks.told(gap).forEach { print("main thread in its longest hold of the flood: \($0)") }
            }
            let covered = Self.measuredFromItsStart(figures)
            let ok = covered && judged.ours <= limit
            let measured = measurement(name, keys: 0, figures: figures, budgets: ["every output shown within \(Self.ms(limit)) ms": ok])
            measures.append(measured)
            return (ok, (covered ? "" : "FAILED: the measure missed the flood's start: no key typed after its reset was seen echoed; ")
                + "\(judged.said); the longest refresh took \(Self.ms(figures.refresh.max)) ms, \(figures.lateOutputs) outputs were late; "
                + "the main thread was held at most \(Self.ms(measured.mainThreadHeld)) ms")
        }
        if let text = step.text {
            let found = await waitUntil(step.seconds ?? 10) { self.terminal.visibleText.contains(text) }
            return (found, found ? "" : "the screen never showed '\(text)'")
        }
        if let banner = step.banner {
            let found = await waitUntil(step.seconds ?? 15) { self.pane?.status.says?.contains(banner) == true }
            return (found, "the banner says '\(pane?.status.says ?? "nothing")'")
        }
        if let words = step.workbenchSays {
            let found = await waitUntil(step.seconds ?? 10) { self.workbench.bannerSays?.contains(words) == true }
            return (found, "the workbench says '\(workbench.bannerSays ?? "nothing")'")
        }
        if let gone = step.bannerGone {
            let went = await waitUntil(step.seconds ?? 15) { self.pane?.status.says?.contains(gone) != true }
            return (went, "the banner says '\(pane?.status.says ?? "nothing")'")
        }
        if step.still == true {
            let before = screenText
            let updates = terminal.latency.screenUpdates
            let more = await waitUntil(step.seconds ?? 5) { self.terminal.latency.screenUpdates > updates }
            let stayed = screenText == before
            return (stayed && more, more ? (stayed ? "the view stayed while output came" : "the view moved when output came") : "no output came")
        }
        if let keys = step.measure {
            return await measure(keys, interval: step.interval ?? 50, name: name)
        }
        if step.floodFile == true {
            return (FileManager.default.fileExists(atPath: Self.floodFiles.lines), Self.floodFiles.lines)
        }
        if step.killHost == true {
            guard shell.thisMac.host.startedPID != nil else { return (false, "the app started no host to kill") }
            shell.thisMac.host.crash()
            return (true, "")
        }
        if let pause = step.pauseHost {
            guard let pid = shell.thisMac.host.startedPID else { return (false, "the app started no host to pause") }
            return (Darwin.kill(pid, pause ? SIGSTOP : SIGCONT) == 0, "")
        }
        if let wanted = step.attached {
            let reached = await waitUntil(step.seconds ?? 20) { (self.pane?.session?.state.phase == .attached) == wanted }
            return (reached, "the session is \(pane?.session.map { "\($0.state.phase)" } ?? "none"); \(arrangementSaid)")
        }
        return (false, "the step names no action")
    }

    /// `keys` presses of "x" one each `interval` ms, the line cleared after, and the latency
    /// against the spec's budgets: key to bytes written p95 ≤ 2 ms; output to frame committed p50 ≤ 1
    /// refresh and p95 ≤ 2; and, on frames the display reported a presentation time for (the CI
    /// display reports few or none), output to presented p50 ≤ 1 refresh and p95 ≤ 2, and key to echo
    /// on the display p95 ≤ 50 ms. Estimated presentation times are reported, never gated.
    func measure(_ keys: Int, interval: Int, name: String) async -> (Bool, String) {
        resetLatency()
        let start = ContinuousClock.now
        for index in 1 ... max(1, keys) {
            key("x")
            try? await Task.sleep(until: start + .milliseconds(interval * index), clock: .continuous)
        }
        _ = await waitUntil(5) {
            let figures = self.terminal.latency
            return figures.keyToEcho.count + figures.keyToEchoEstimated.count >= keys
        }
        let figures = terminal.latency
        if let clear = Keys.named("u", modifiers: ["control"]) { send(clear) }
        let refresh = 1000 / Double(max(1, shell.window.screen?.maximumFramesPerSecond ?? 60))
        let measured = figures.outputToPresented.count > 0
        let stale = figures.unpresented.filter { $0.ageMs > 6 * refresh }
        let written = Percentiles(MachineShare.net(figures.keyWrites, own: ownThread.stalls).map { Duration.seconds($0) })
        let budgets = [
            "every committed frame told presented within 6 refreshes (reported presents only)": figures.reportedPresents == 0 || stale.isEmpty,
            "key to bytes written p95 <= 2 ms, each net of the drive's own thread's stall over it "
                + "(net \(Self.ms(written.p95)) ms, raw \(Self.ms(figures.keyToWritten.p95)) ms)": written.p95 <= 2,
            "output to committed p50 <= 1 refresh": figures.outputToCommitted.p50 <= refresh,
            "output to committed p95 <= 2 refreshes": figures.outputToCommitted.p95 <= 2 * refresh,
            "output to presented p50 <= 1 refresh (reported presents only)": !measured || figures.outputToPresented.p50 <= refresh,
            "output to presented p95 <= 2 refreshes (reported presents only)": !measured || figures.outputToPresented.p95 <= 2 * refresh,
            "key to echo presented p95 <= 50 ms (reported presents only)": figures.keyToEcho.count == 0 || figures.keyToEcho.p95 <= 50,
            "every echo measured": figures.keyToEcho.count + figures.keyToEchoEstimated.count >= keys * 9 / 10,
        ]
        measures.append(measurement(name, keys: keys, figures: figures, budgets: budgets))
        let missed = budgets.filter { !$0.value }.keys.sorted()
        let presents = figures.reportedPresents > 0
            ? "\(stale.count) committed frames not told presented within 6 refreshes" : "the display tells no presentation time, so a frame never presented is not caught here"
        let keys = "key to bytes written p95 \(Self.ms(written.p95)) ms net of the drive's own thread's stall, \(Self.ms(figures.keyToWritten.p95)) ms raw"
        return (missed.isEmpty, (missed.isEmpty ? "within every budget" : "missed: \(missed.joined(separator: "; "))") + "; " + keys + "; " + presents)
    }

    /// A flood judged as the app's, in ms (``FloodVerdict``): the larger of its longest output's
    /// wait and the main thread's longest hold over it, each less the machine's share; with the
    /// words that say what each waited for and whose it was.
    func flood(_ figures: LatencyFigures) -> (ours: Double, said: String) {
        let verdict = FloodVerdict.ours(figures, mainHold: mainThread.stalls.longest(), tick: Heartbeat.interval, own: ownThread.stalls, main: mainStacks.looks)
        let gap = figures.longestBetweenCommits?.length ?? 0
        return (max(verdict.shown, verdict.held) * 1000, "the longest any output waited to be shown: \(ours(figures.longestToShown).said); "
            + "the main thread's longest hold: \(mainHeld().said); its longest gap between commits \(Self.ms(gap * 1000)) ms, "
            + "\(figures.longestGapOutputs) outputs reached the screen in it, a drawable was being asked for \(Self.ms(figures.longestGapFetching)) ms of it; "
            + "the main thread took the wake for an output at most \(Self.ms((figures.longestToTold?.length ?? 0) * 1000)) ms after the screen took it"
            + Self.posting(figures) + (figures.longestToTold.map { "; across that wake's wait, " + mainStacks.readers(across: $0) } ?? "")
            + "; the reader runs at quality of service " + Self.quality(pane?.session?.readerQuality) + ", "
            + "\(figures.wakes) wakes for \(figures.changes) screen changes")
    }

    /// Where the longest wake's wait went: the session's thread handing it to the main queue after
    /// the screen took the output, and the main thread taking it after it was handed over. The
    /// hand-off is stamped on both sides, so each is said as a range; a stamp the main thread
    /// overtook is said unknown, never stood in for.
    static func posting(_ figures: LatencyFigures) -> String {
        guard let hop = figures.longestToTold, let handing = figures.longestToToldHanding else { return "" }
        let began = ms((handing - hop.start) * 1000), takenBy = ms((hop.end - handing) * 1000)
        guard let handed = figures.longestToToldHanded else {
            return " (posted between \(began) ms and an unknown time after the screen took it, taken between an unknown time and \(takenBy) ms after it was posted: "
                + "the main thread took it before the hand-off's return was stamped)"
        }
        return " (posted between \(began) and \(ms((handed - hop.start) * 1000)) ms after the screen took it, taken between \(ms((hop.end - handed) * 1000)) "
            + "and \(takenBy) ms after it was posted)"
    }

    /// Whether a flood's measure covers it from its first output: the flood's command is typed
    /// up to its Return, the measure reset, then the Return typed, so the Return's echo, the
    /// flood's first output, is a key seen echoed inside the measure. A measure reset after the
    /// flood began has no key in it.
    static func measuredFromItsStart(_ figures: LatencyFigures) -> Bool {
        figures.keyToEcho.count + figures.keyToEchoEstimated.count > 0
    }

    /// The machine's CPU ticks across a judged wait, between its first and last looks that read
    /// them (a runnable main thread's), beside what its CPUs make over that time: whether the
    /// machine ran at all meanwhile. Idle and busy ticks both still across a long wait is what a
    /// runner paused by its host shows, which nothing inside it can prove otherwise.
    static func machineTicks(across looks: [ThreadLook]) -> String {
        let read = looks.filter { $0.idleTicks != nil && $0.busyTicks != nil }
        guard let first = read.first, let last = read.last, let idleFrom = first.idleTicks, let idleTo = last.idleTicks,
              let busyFrom = first.busyTicks, let busyTo = last.busyTicks
        else { return "the machine's ticks unread in it (the main thread never runnable at a look)" }
        let cpus = ProcessInfo.processInfo.activeProcessorCount, span = last.time - first.time
        return String(format: "the machine's idle ticks +%u and busy ticks +%u over %.1f ms between looks that read them (%ld CPUs make %.1f)",
                      idleTo &- idleFrom, busyTo &- busyFrom, span * 1000, cpus, Double(cpus) * span / ThreadLook.tick)
    }

    /// A quality of service as the flood's line names it.
    static func quality(_ quality: QualityOfService?) -> String {
        guard let quality else { return "unknown (no reader yet)" }
        switch quality {
        case .userInteractive: return "user-interactive"
        case .userInitiated: return "user-initiated"
        case .utility: return "utility"
        case .background: return "background"
        case .default: return "default"
        @unknown default: return "unknown"
        }
    }

    func measurement(_ name: String, keys: Int, figures: LatencyFigures, budgets: [String: Bool]) -> Measure {
        Measure(
            name: name, drawables: terminal.drawableCount, keys: keys, figures: figures, budgets: budgets,
            mainThreadHeld: mainThread.longest, ownThreadHeld: ownThread.longest
        )
    }

    func resetLatency() {
        _ = mainStacks
        terminal.resetLatency()
        mainThread.reset()
        ownThread.reset()
    }

    /// A wait timed on a hosted runner, judged as the app's, in ms: less the machine's share of
    /// that same wait (sail#417), the larger of the drive's own thread's stall over it and the time
    /// the main thread was proven runnable without CPU while the machine had none idle
    /// (``MachineShare/starvation(in:looks:)``); what is left is ours, against the same budget as
    /// before. Each excused stretch is said with its looks, and kept for the drive's last line.
    func ours(_ wait: Span?, less tick: Double = 0) -> (ours: Double, said: String) {
        let wait = wait ?? Span(0, 0)
        let own = ownThread.stalls, looks = mainStacks.looks
        let proven = MachineShare.starvation(in: wait, looks: looks)
        let ownHeld = own.shared(with: wait), starved = MachineShare.starved(in: wait, looks: looks)
        let inconclusive = MachineShare.inconclusive(wait, main: looks)
        let ours = max(0, wait.length - tick - MachineShare.of(wait, own: own, main: looks)) * 1000
        let inWait = looks.filter { $0.time >= wait.start && $0.time <= wait.end }
        let seen = inWait.count, cpus = ProcessInfo.processInfo.activeProcessorCount
        let told = proven.map { $0.told(cpus: cpus) }
        if !inconclusive { told.filter { !excused.contains($0) }.forEach { excused.append($0) } }
        let evidence = (proven.isEmpty
            ? "no run of \(MachineShare.fewestLooks) looks proved it starved (\(seen) looks in the wait)"
            : told.joined(separator: "; ")) + "; the main run loop's mode, " + mainStacks.modes(in: wait) + "; " + Self.machineTicks(across: inWait)
        let ceiling = inconclusive
            ? String(format: "; INCONCLUSIVE: the main thread's starvation is more than %.0f%% of the wait, so none of it is excused", MachineShare.mostExcused * 100)
            : ""
        return (ours, "waited \(Self.ms((wait.length - tick) * 1000)) ms, the drive's own thread held \(Self.ms(ownHeld * 1000)) ms of it, "
            + "the main thread runnable without CPU on a machine with none idle \(Self.ms(starved * 1000)) ms of it (\(evidence))\(ceiling), ours \(Self.ms(ours)) ms")
    }

    /// One try's figure for a gate, in ms, and the span it was taken over.
    typealias Taken = (value: Double, span: Span)

    /// `count` tries, each (`attempt`) giving its gates' figures and spans, by gate. Nil when a try
    /// could not be taken (`attempt` gave nil, its failure its own to say).
    func tried(_ count: Int, _ attempt: () async -> [String: Taken]?) async -> [String: [Taken]]? {
        _ = mainStacks
        var tries: [String: [Taken]] = [:]
        for _ in 0 ..< count {
            guard let figures = await attempt() else { return nil }
            figures.forEach { tries[$0.key, default: []].append($0.value) }
        }
        return tries
    }

    /// A step's verdicts by ``TimingGate``'s rule: the machine read once over every try of the
    /// step (``MachineReading``), each gate then judged on its tries; a step read starved is kept
    /// for the run's summary.
    func judged(_ gates: [TimingGate], _ tries: [String: [Taken]]) -> (verdicts: [TimingGate.Verdict], machine: MachineReading) {
        let spans = gates.flatMap { tries[$0.name]?.map(\.span) ?? [] }, looks = mainStacks.looks
        let whole = (spans.map(\.start).min() ?? .infinity) >= (looks.first?.time ?? -.infinity)
        let machine = MachineReading(MachineShare.idle(in: spans, looks: looks), whole: whole)
        if machine.starved { notCleanInStep = true }
        let verdicts = gates.map { $0.judge(tries[$0.name]?.map(\.value) ?? [], machine: machine) }
        let (idle, ours) = if case let .read(idle, ours, _) = machine { (String(format: "%.1f", idle), String(format: "%.1f", ours)) } else { ("", "") }
        for (gate, verdict) in zip(gates, verdicts) {
            figureRows.append([gate.name, String(format: "%.1f", verdict.value), String(format: "%g", gate.budget), gate.mark.map { String(format: "%g", $0) } ?? "",
                               String(format: "%g", gate.ceiling), idle, ours, machine.starved ? "yes" : "no"].joined(separator: "\t"))
        }
        return (verdicts, machine)
    }

    /// A step's line from its gates' verdicts: what missed, then every figure, then how the
    /// machine read over the step.
    static func line(_ judged: (verdicts: [TimingGate.Verdict], machine: MachineReading), missed more: [String] = []) -> (ok: Bool, said: String) {
        let missed = judged.verdicts.compactMap(\.missed) + more
        let figures = judged.verdicts.map(\.said) + [judged.machine.said]
        return (missed.isEmpty, (missed.isEmpty ? "within every ceiling; " : "missed: \(missed.joined(separator: "; ")); ") + figures.joined(separator: "; "))
    }

    /// The main thread's longest hold, its heartbeat's longest gap (in `span` when given) less one
    /// tick, judged as ``ours(_:less:)`` judges a wait.
    func mainHeld(in span: Span? = nil) -> (ours: Double, said: String) {
        ours(mainThread.stalls.longest(in: span), less: Heartbeat.interval)
    }

    /// The run's private folder, which ci/drive makes for each run and removes after, handed in
    /// `SAIL_DRIVE_PRIVATE` (main.swift refuses to start without it): every file the drive makes
    /// for its steps goes there, so two runs on one Mac never share a path.
    nonisolated static let privateFolder = ProcessInfo.processInfo.environment["SAIL_DRIVE_PRIVATE"] ?? ""

    nonisolated static func privateFile(_ name: String) -> String {
        privateFolder + "/" + name
    }

    /// The bytes the spec's flood prints, made once so that one `cat` gives them (`head -c 20971520
    /// /dev/zero | tr '\0' x | fold -w 150` printed nine processes' worth of CPU beside the
    /// terminal it measured): 20 MiB of `x` folded at 150 columns in `lines`, its last 20 bytes
    /// unended in `tail`. Printed again and again, `lines` keeps the same shape.
    static let floodFiles: (lines: String, tail: String) = {
        let lines = 20_971_520 / 150
        let line = [UInt8](repeating: UInt8(ascii: "x"), count: 150) + [UInt8(ascii: "\n")]
        var bytes = [UInt8]()
        bytes.reserveCapacity(lines * 151)
        for _ in 0 ..< lines { bytes += line }
        let files = (lines: privateFile("flood-lines"), tail: privateFile("flood-tail"))
        FileManager.default.createFile(atPath: files.lines, contents: Data(bytes))
        FileManager.default.createFile(atPath: files.tail, contents: Data(repeating: UInt8(ascii: "x"), count: 20_971_520 - lines * 150))
        return files
    }()

    /// A program asking the terminal for its colours, run by the system's perl in a pane. `ask`
    /// asks the background (OSC 11), palette colour 5 (OSC 4) and the colour scheme (996) and
    /// prints `asked bg <rgb> c5 <rgb> <light|dark|none>`, short enough for a narrow pane; `set` first sets
    /// colour 5 to `rgb:12/34/56` as a program's own. `watch` subscribes to colour scheme reports
    /// (mode 2031), prints `subscribed`, and once a report comes prints `scheme report <scheme>`.
    static let colourAsker: String = {
        let path = privateFile("colours.pl")
        let script = #"""
            use strict;
            use IO::Select;
            my ($mode) = @ARGV;
            $| = 1;
            system('stty -icanon -echo min 1');
            my $in = IO::Select->new(\*STDIN);
            sub replies {
                my $got = '';
                while ($got !~ /997;\dn/ && $in->can_read(5)) {
                    sysread(STDIN, my $chunk, 256) or last;
                    $got .= $chunk;
                }
                return $got;
            }
            sub scheme {
                my ($digit) = @_;
                return 'none' unless defined $digit;
                return $digit eq '1' ? 'dark' : $digit eq '2' ? 'light' : 'none';
            }
            if ($mode eq 'watch') {
                print "\e[?2031hsubscribed\n";
                my ($digit) = replies() =~ /997;(\d)n/;
                print "\e[?2031l";
                system('stty sane');
                print "scheme report ", scheme($digit), "\n";
                exit;
            }
            print "\e]4;5;rgb:12/34/56\e\\" if $mode eq 'set';
            print "\e]11;?\e\\\e]4;5;?\e\\\e[?996n";
            my $got = replies();
            system('stty sane');
            my ($background) = $got =~ /\]11;(rgb:[0-9a-f\/]+)/;
            my ($own) = $got =~ /\]4;5;(rgb:[0-9a-f\/]+)/;
            my ($digit) = $got =~ /997;(\d)n/;
            print "asked bg ", $background // 'none', " c5 ", $own // 'none', " ", scheme($digit), "\n";
            """#
        FileManager.default.createFile(atPath: path, contents: Data(script.utf8))
        return path
    }()

    /// A pane's generator, run by the system's perl: it writes the prepared lines (its first
    /// argument) `block` bytes at a time, one block each `period` seconds, until `stop` exists. A
    /// block the pty took late is not made up for, so the rate is a ceiling; with a period of zero
    /// it writes as fast as the pty takes them. Blocks are whole lines, so the tail after it ends
    /// starts a line.
    static let floodPacer: String = {
        let path = privateFile("flood-pace.pl")
        let script = """
            use strict;
            use Time::HiRes qw(time sleep);
            my ($file, $stop, $block, $period) = @ARGV;
            open(my $in, '<:raw', $file) or die "$file: $!";
            my $bytes = do { local $/; <$in> };
            close $in;
            binmode STDOUT;
            my ($at, $due) = (0, time);
            until (-e $stop) {
                $at = 0 if $at + $block > length $bytes;
                my $written = 0;
                while ($written < $block) {
                    my $count = syswrite(STDOUT, $bytes, $block - $written, $at + $written);
                    die "write: $!" unless defined $count;
                    $written += $count;
                }
                $at += $block;
                $due = time if $due < time;
                $due += $period;
                my $wait = $due - time;
                sleep($wait) if $wait > 0;
            }
            """
        FileManager.default.createFile(atPath: path, contents: Data(script.utf8))
        return path
    }()

    /// Every process whose command line holds `text`, as `ps` lists them.
    static func processes(matching text: String) async -> [String] {
        await ps(["-axo", "pid=,command="]).split(separator: "\n").map(String.init).filter { $0.contains(text) && !$0.contains("ps -axo") }
    }

    /// What the floods' processes and their neighbours cost the machine now, as `ps` says.
    static func processes() async -> [String] {
        await ps(["-axo", "pid,pri,%cpu,comm", "-r"]).split(separator: "\n").prefix(16).map(String.init)
    }

    /// The share of a CPU, in %, the terminal host (the process serving at the app's socket) and
    /// the app each used over the next `seconds`, from their CPU times; nil for one `ps` did not list.
    static func cpuShares(host: pid_t?, over seconds: Double) async -> (host: Double?, app: Double?) {
        let before = await cpuTimes(host: host), started = CACurrentMediaTime()
        try? await Task.sleep(for: .seconds(seconds))
        let after = await cpuTimes(host: host), elapsed = CACurrentMediaTime() - started
        func share(_ was: Double?, _ now: Double?) -> Double? {
            guard let was, let now else { return nil }
            return (now - was) / elapsed * 100
        }
        return (share(before.host, after.host), share(before.app, after.app))
    }

    /// The terminal host's and the app's CPU time so far in seconds, from `ps`'s `[[hh:]mm:]ss.ss`.
    static func cpuTimes(host: pid_t?) async -> (host: Double?, app: Double?) {
        let app = getpid()
        var times: (host: Double?, app: Double?) = (nil, nil)
        for line in await ps(["-axo", "pid=,time="]).split(separator: "\n") {
            let fields = line.split(separator: " ")
            guard fields.count == 2, let pid = pid_t(fields[0]) else { continue }
            let time = fields[1].split(separator: ":").compactMap { Double($0) }.reduce(0) { $0 * 60 + $1 }
            if pid == app { times.app = time }
            if pid == host { times.host = time }
        }
        return times
    }

    /// `ps`'s answer, run off the main thread: a wait for it never holds the frames a step judges.
    private static func ps(_ arguments: [String]) async -> String {
        await Task.detached { psNow(arguments) }.value
    }

    /// Makes the file at `path`, off the main thread: a signal a step sends never holds its frames.
    static func touch(_ path: String) async {
        await Task.detached { _ = FileManager.default.createFile(atPath: path, contents: nil) }.value
    }

    nonisolated private static func psNow(_ arguments: [String]) -> String {
        let ps = Process()
        ps.executableURL = URL(filePath: "/bin/ps")
        ps.arguments = arguments
        let pipe = Pipe()
        ps.standardOutput = pipe
        guard (try? ps.run()) != nil else { return "ps did not run" }
        let listed = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        ps.waitUntilExit()
        return listed
    }

    static func ms(_ milliseconds: Double) -> String {
        String(format: "%.1f", milliseconds)
    }

    /// Whether the app is active, its window on screen and key, and which app is in front: what a
    /// failed step's frames depended on.
    var whereTheWindowIs: String {
        let window = shell.window
        return "the app is \(NSApp.isActive ? "" : "not ")active, its window is \(window.occlusionState.contains(.visible) ? "" : "not ")"
            + "visible and \(window.isKeyWindow ? "" : "not ")key, the app in front is "
            + (NSWorkspace.shared.frontmostApplication?.bundleIdentifier ?? "none")
    }

    /// The whole screen as it is, so whatever covered the window is seen.
    private static func captureTheScreen(to file: URL) async {
        await Shot.begin(.screen, to: file)?.taken()
    }

    /// `line` on standard output at once, unbuffered, so what the drive printed is in its output
    /// file when the sweep reads it.
    static func say(_ line: String) {
        FileHandle.standardOutput.write(Data((line + "\n").utf8))
    }

    /// How long a step's screen may take to be drawn by the view before the step fails.
    static let drawDeadline = 5.0

    static func button(_ title: String, in view: NSView?) -> NSButton? {
        guard let view else { return nil }
        if let button = view as? NSButton, button.title == title { return button }
        return view.subviews.lazy.compactMap { button(title, in: $0) }.first
    }

    var screenText: String {
        (try? pane?.session?.screen.text()) ?? ""
    }

    /// The rows the focused view last drew, trimmed: what a `line`, `top` or `text` wait reads, so it
    /// costs the main thread no refresh of the screen under its lock while a flood feeds it.
    var drawnLines: [String] {
        terminal.visibleText.split(separator: "\n", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    var lines: [String] {
        screenText.split(separator: "\n", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// ⌥⌘L after a burst of typed keys: the overlay shows key to echo from the echoes counted. It
    /// waits up to `seconds` for 90% of the keys written to be counted, then passes on at least one
    /// counted and shown, every line of the overlay there. Not on a fraction: a burst's echoes pair
    /// with its keys only in part (7 to 18 of 20 counted after the wait in healthy runs), and every
    /// echo is measured where keys come one at a time (`measure`, at least 90%).
    private func latencyOverlay(within seconds: Double) async -> (Bool, String) {
        let began = ContinuousClock.now
        let written = terminal.latency.keyToWritten.count
        _ = await waitUntil(seconds) {
            let figures = self.terminal.latency
            return written > 0 && (figures.keyToEcho.count + figures.keyToEchoEstimated.count) * 10 >= written * 9
        }
        let waited = (ContinuousClock.now - began) / .milliseconds(1)
        guard let toggle = Keys.named("l", modifiers: ["command", "option"]) else { return (false, "no ⌥⌘L") }
        send(toggle)
        let shown = pane?.latencyShown ?? ""
        let lines = shown.split(separator: "\n")
        let keys = shown.components(separatedBy: "(").dropFirst().compactMap { part in Int(part.prefix(while: \.isNumber)) }.reduce(0, +)
        let figures = terminal.latency
        let counted = figures.keyToEcho.count + figures.keyToEchoEstimated.count
        let ok = counted > 0 && keys > 0 && lines.count <= TerminalPane.latencyLines && shown.contains("key → echo on glass")
        let measured = "counted \(counted) of \(figures.keyToWritten.count) keys written (on glass \(figures.keyToEcho.count), at completion "
            + "\(figures.keyToEchoEstimated.count)), presents reported \(figures.reportedPresents) and not \(figures.unreportedPresents), "
            + "told before their commit \(figures.presentedBeforeCommit), waited \(String(format: "%.0f", waited)) ms"
        return (ok, "\(measured); \(lines.count) lines of \(TerminalPane.latencyLines): \(lines.joined(separator: " | "))")
    }

    func waitUntil(_ seconds: Double, _ condition: @MainActor () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(failedSoFar ? min(seconds, Self.afterAFailure) : seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return true
    }

    func key(_ character: Character) {
        if let key = Keys.typing(character) { send(key) }
    }

    func send(_ key: Keys.Key) {
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            guard let event = NSEvent.keyEvent(
                with: type, location: .zero, modifierFlags: key.flags, timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: (NSApp.keyWindow ?? shell.window).windowNumber, context: nil, characters: key.characters,
                charactersIgnoringModifiers: key.ignoring, isARepeat: false, keyCode: key.code
            ) else { continue }
            NSApp.sendEvent(event)
        }
    }

    private func click(_ cell: [Int], clicks: Int, shift: Bool) {
        for count in 1 ... max(1, clicks) {
            mouse(.leftMouseDown, at: cell, clicks: count, shift: shift)
            mouse(.leftMouseUp, at: cell, clicks: count, shift: shift)
        }
    }

    private func drag(from start: [Int], to end: [Int]) {
        mouse(.leftMouseDown, at: start, clicks: 1, shift: false)
        mouse(.leftMouseDragged, at: [(start[0] + end[0]) / 2, (start[1] + end[1]) / 2], clicks: 1, shift: false)
        mouse(.leftMouseDragged, at: end, clicks: 1, shift: false)
        mouse(.leftMouseUp, at: end, clicks: 1, shift: false)
    }

    private func mouse(_ type: NSEvent.EventType, at cell: [Int], clicks: Int, shift: Bool) {
        let metrics = terminal.metrics
        let origin = metrics.origin(of: CellPoint(x: cell[0], y: cell[1]))
        let point = NSPoint(
            x: (Double(origin.x) + Double(metrics.width) / 2) / metrics.scale, y: (Double(origin.y) + Double(metrics.height) / 2) / metrics.scale
        )
        guard let event = NSEvent.mouseEvent(
            with: type, location: clickPoint(point, in: terminal), modifierFlags: shift ? .shift : [],
            timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: shell.window.windowNumber, context: nil, eventNumber: 0,
            clickCount: clicks, pressure: 1
        ) else { return }
        NSApp.sendEvent(event)
    }

    private func save(index: Int, name: String) {
        let stem = String(format: "%02d-", index) + name
        guard onTerminal else {
            try? (shell.flow.standing == nil ? shell.welcome.text : shell.account.text).write(to: out.appending(path: stem + ".txt"), atomically: true, encoding: .utf8)
            try? windowImage()?.write(to: out.appending(path: stem + ".png"))
            return
        }
        let text = screenText, file = out.appending(path: stem + ".txt")
        guard !measuring else {
            Task.detached { try? text.write(to: file, atomically: true, encoding: .utf8) }
            return
        }
        if let image = try? terminal.snapshot(), let png = image.png() {
            try? png.write(to: out.appending(path: stem + ".png"))
        }
        try? text.write(to: file, atomically: true, encoding: .utf8)
    }
}

extension Turns {
    /// Told by the main run loop in every mode: a turn's start before any other observer of its
    /// wake or iteration, its commit just before Core Animation's observer of its sleep, and its
    /// end after every other observer of it.
    /// The order Core Animation's commit observes the main run loop's sleep at.
    static let coreAnimationCommit: CFIndex = 2_000_000

    @MainActor static func onTheMainRunLoop() -> Turns {
        let turns = Turns { CACurrentMediaTime() }
        let starts = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.afterWaiting.rawValue | CFRunLoopActivity.beforeTimers.rawValue, true, CFIndex.min) { _, activity in
            activity == .afterWaiting ? turns.woke() : turns.iterated()
        }
        let ends = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.beforeWaiting.rawValue, true, CFIndex.max) { _, _ in turns.slept() }
        let commit = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.beforeWaiting.rawValue, true, Self.coreAnimationCommit - 1) { _, _ in turns.committing() }
        CFRunLoopAddObserver(CFRunLoopGetMain(), starts, .commonModes)
        CFRunLoopAddObserver(CFRunLoopGetMain(), commit, .commonModes)
        CFRunLoopAddObserver(CFRunLoopGetMain(), ends, .commonModes)
        return turns
    }
}

/// `point` in `view` as its window's point, the window laid out first, as it is before anything
/// is drawn: a row a fresh answer has just added is then where a person would see and click it,
/// not where the rows before it left room. Every point the drive sends the pointer to is taken so
/// (main's drive on af09e04fd clicked lighthouse's place outside the projects panel, which had not
/// yet grown to the nine projects just listed).
@MainActor
func clickPoint(_ point: NSPoint, in view: NSView) -> NSPoint {
    view.window?.layoutIfNeeded()
    return view.convert(point, to: nil)
}
