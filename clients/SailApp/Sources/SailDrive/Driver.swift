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
/// each step on "This Mac" the terminal's last frame is saved as an image, rendered in-process, with
/// the screen's text, and after each step on the account the pane's text; at the end a JSON report.
@MainActor
final class Driver {
    let shell: ShellApp
    let passkey: SoftwarePasskey
    let signed: Bool
    var tokens: Set<String> = []
    var askedAtStart = 0
    private let steps: [Step]
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
    lazy var standIn: TerminalView = {
        guard let fonts = Bundle.main.resourceURL.flatMap({ try? TerminalFonts(directory: $0.appending(path: "Fonts")) }),
              let view = try? TerminalView(fonts: fonts)
        else { fatalError("the drive's bundle has no fonts for a terminal view") }
        return view
    }()
    let mainThread = Heartbeat.onTheMainThread { CACurrentMediaTime() }
    let ownThread = Heartbeat.onItsOwnThread { CACurrentMediaTime() }
    lazy var mainStacks = MainStacks(watching: mainThread)
    /// Every stretch of a judged wait excused as the machine's, with its evidence, once each.
    private var excused: [String] = []

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
        self.out = out
        askedAtStart = passkey.asked
    }

    /// The focused pane of "This Mac", which the terminal steps act on.
    var pane: TerminalPane? {
        shell.thisMac.focused
    }

    /// The focused pane's view; with none, a view that draws nothing, so a step that needs one fails
    /// on what it finds.
    var terminal: TerminalView {
        pane?.terminal ?? standIn
    }

    /// Whether the steps are on "This Mac" with a session drawn.
    var onTerminal: Bool {
        shell.rail.selected == .thisMac && pane?.session != nil
    }

    /// Runs the script once. App Nap is held off for the run: it slows a window that is not in
    /// front, and the keys must go one each `interval`.
    func start() {
        guard !started else { return }
        started = true
        let activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .latencyCritical], reason: "driving the app")
        Task {
            await run()
            ProcessInfo.processInfo.endActivity(activity)
        }
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
            var (ok, detail) = await perform(step, name: name)
            let drew = await waitUntil(Self.drawDeadline) {
                !self.onTerminal || self.terminal.visibleText == self.screenText && self.terminal.isDrawnInFull
            }
            if ok, !drew {
                ok = false
                detail = "the view never drew the screen in full: it shows \(terminal.visibleText.count) characters, the screen holds "
                    + "\(screenText.count), every glyph drawn: \(terminal.isDrawnInFull)"
            }
            if !ok {
                detail += "; " + whereTheWindowIs
                if onTerminal { detail += "; " + terminal.drawingState }
                Self.captureTheScreen(to: out.appending(path: String(format: "%02d-", index) + name + "-screen.png"))
            }
            let seconds = (ContinuousClock.now - began) / .seconds(1)
            let skipped = ok && detail.hasPrefix("skipped: ")
            results.append(Result(index: index, name: name, ok: ok, skipped: skipped, seconds: seconds, detail: detail))
            save(index: index, name: name)
            Self.say("drive \(index) \(name): \(skipped ? "skipped" : ok ? "ok" : "FAILED") in \(String(format: "%.2f", seconds)) s \(detail)")
            if !ok { break }
        }
        let withinClaim = mainStacks.watcherShare <= MainStacks.claim
        Self.say("drive: \(excused.count) stretch\(excused.count == 1 ? "" : "es") of a judged wait excused as the machine's"
            + (excused.isEmpty ? "" : ": " + excused.joined(separator: "; ")) + "; the main thread's sampler took \(mainStacks.cost)"
            + (withinClaim ? "" : "; FAILED: the watcher took more of a CPU than its claim"))
        let passed = results.count == steps.count && results.allSatisfy(\.ok) && withinClaim
        let report = Report(
            passed: passed, hertz: shell.window.screen?.maximumFramesPerSecond ?? 0, scale: terminal.metrics.scale, steps: results,
            measures: measures, bench: bench, panes4: panes4
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? encoder.encode(report).write(to: out.appending(path: "report.json"))
        guard passed else { exit(1) }
        NSApp.terminate(nil)
    }

    private func perform(_ step: Step, name: String) async -> (Bool, String) {
        if let account = await performAccount(step) {
            return account
        }
        if let workbench = await performWorkbench(step) {
            return workbench
        }
        if let workspaces = await performWorkspaces(step) {
            return workspaces
        }
        if let text = step.type {
            for character in text { key(character) }
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
                bench = try Bench.run(fonts: fonts)
            } catch {
                return (false, "the bench could not run: \(error)")
            }
            let p95 = bench["full redraw 200x60 cpu p95 ms"] ?? .infinity
            return (p95 <= 4, bench.sorted { $0.key < $1.key }.map { "\($0.key) \(String(format: "%.2f", $0.value))" }.joined(separator: ", "))
        }
        if step.latencyOverlay == true {
            return await latencyOverlay(within: step.seconds ?? 5)
        }
        if step.resetLatency == true {
            resetLatency()
            measuring = true
            return (true, "")
        }
        if let wanted = step.line {
            let line = wanted.replacingOccurrences(of: "{home}", with: NSHomeDirectory())
            let found = await waitUntil(step.seconds ?? 10) {
                let lines = self.lines
                return lines.indices.contains { index in
                    lines[index] == line && (step.after.map { above in index > 0 && lines[index - 1] == above } ?? true)
                }
            }
            return (found, found ? "" : "no line '\(line)'\(step.after.map { " under '\($0.prefix(40))'" } ?? "") within \(step.seconds ?? 10) s")
        }
        if let top = step.top {
            let found = await waitUntil(step.seconds ?? 10) { self.lines.first == top }
            return (found, "the top line is '\(lines.first ?? "")'")
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
            let ok = judged.ours <= limit
            let measured = measurement(name, keys: 0, figures: figures, budgets: ["every output shown within \(Self.ms(limit)) ms": ok])
            measures.append(measured)
            return (ok, "\(judged.said); the longest refresh took \(Self.ms(figures.refresh.max)) ms, \(figures.lateOutputs) outputs were late; "
                + "the main thread was held at most \(Self.ms(measured.mainThreadHeld)) ms")
        }
        if let text = step.text {
            let found = await waitUntil(step.seconds ?? 10) { self.screenText.contains(text) }
            return (found, found ? "" : "the screen never showed '\(text)'")
        }
        if let banner = step.banner {
            let found = await waitUntil(step.seconds ?? 15) { self.pane?.status.says?.contains(banner) == true }
            return (found, "the banner says '\(pane?.status.says ?? "nothing")'")
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
            return (reached, "the session is \(pane?.session.map { "\($0.state.phase)" } ?? "none")")
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
        let ours = max(0, wait.length - tick - MachineShare.of(wait, own: own, main: looks)) * 1000
        let seen = looks.filter { $0.time >= wait.start && $0.time <= wait.end }.count
        proven.map(\.told).filter { !excused.contains($0) }.forEach { excused.append($0) }
        let evidence = (proven.isEmpty
            ? "no run of \(MachineShare.fewestLooks) looks proved it starved (\(seen) looks in the wait)"
            : proven.map(\.told).joined(separator: "; ")) + "; the main run loop in " + MachineShare.modes(in: wait, looks: looks)
        return (ours, "waited \(Self.ms((wait.length - tick) * 1000)) ms, the drive's own thread held \(Self.ms(ownHeld * 1000)) ms of it, "
            + "the main thread runnable without CPU on a machine with none idle \(Self.ms(starved * 1000)) ms of it (\(evidence)), ours \(Self.ms(ours)) ms")
    }

    /// The main thread's longest hold, its heartbeat's longest gap (in `span` when given) less one
    /// tick, judged as ``ours(_:less:)`` judges a wait.
    func mainHeld(in span: Span? = nil) -> (ours: Double, said: String) {
        ours(mainThread.stalls.longest(in: span), less: Heartbeat.interval)
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
        FileManager.default.createFile(atPath: "/tmp/sail-drive-flood-lines", contents: Data(bytes))
        FileManager.default.createFile(atPath: "/tmp/sail-drive-flood-tail", contents: Data(repeating: UInt8(ascii: "x"), count: 20_971_520 - lines * 150))
        return ("/tmp/sail-drive-flood-lines", "/tmp/sail-drive-flood-tail")
    }()

    /// A pane's generator, run by the system's perl: it writes the prepared lines (its first
    /// argument) `block` bytes at a time, one block each `period` seconds, until `stop` exists. A
    /// block the pty took late is not made up for, so the rate is a ceiling; with a period of zero
    /// it writes as fast as the pty takes them. Blocks are whole lines, so the tail after it ends
    /// starts a line.
    static let floodPacer: String = {
        let path = "/tmp/sail-drive-flood-pace.pl"
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
    private static func captureTheScreen(to file: URL) {
        let capture = Process()
        capture.executableURL = URL(filePath: "/usr/sbin/screencapture")
        capture.arguments = ["-x", file.path]
        try? capture.run()
        capture.waitUntilExit()
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
        let deadline = ContinuousClock.now + .seconds(seconds)
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
            with: type, location: terminal.convert(point, to: nil), modifierFlags: shift ? .shift : [],
            timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: shell.window.windowNumber, context: nil, eventNumber: 0,
            clickCount: clicks, pressure: 1
        ) else { return }
        NSApp.sendEvent(event)
    }

    private func save(index: Int, name: String) {
        let stem = String(format: "%02d-", index) + name
        guard onTerminal else {
            try? shell.account.text.write(to: out.appending(path: stem + ".txt"), atomically: true, encoding: .utf8)
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
