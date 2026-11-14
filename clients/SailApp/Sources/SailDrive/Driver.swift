import AppKit
import SailPreview
import SailRenderer
import SailSurface
import SailTerminalView
import SailVT

/// Runs a drive script in the real preview against the real local host. Every key, click and
/// drag is an `NSEvent` sent through the app's own `sendEvent`, so no Accessibility or Screen
/// Recording permission is needed; the wheel goes to the view's `scrollWheel`, since a wheel event
/// made by hand carries no window. After each step the terminal's last frame is saved as an
/// image, rendered in-process, with the screen's text; at the end a JSON report.
@MainActor
final class Driver {
    private let preview: PreviewApp
    private let steps: [Step]
    private let out: URL
    private var results: [Result] = []
    private var measures: [Measure] = []
    private var started = false

    struct Result: Encodable {
        let index: Int
        let name: String
        let ok: Bool
        let seconds: Double
        let detail: String
    }

    struct Measure: Encodable {
        let name: String
        let drawables: Int
        let keys: Int
        let figures: LatencyFigures
        let budgets: [String: Bool]
    }

    struct Report: Encodable {
        let passed: Bool
        let hertz: Int
        let scale: Double
        let steps: [Result]
        let measures: [Measure]
    }

    init(preview: PreviewApp, script: Script, out: URL) {
        self.preview = preview
        steps = script.steps
        self.out = out
    }

    var terminal: TerminalView {
        preview.terminal
    }

    func start() {
        guard !started else { return }
        started = true
        Task { await run() }
    }

    private func run() async {
        for (offset, step) in steps.enumerated() {
            let index = offset + 1
            let name = step.name ?? "step"
            let began = ContinuousClock.now
            let (ok, detail) = await perform(step, name: name)
            let seconds = (ContinuousClock.now - began).seconds
            results.append(Result(index: index, name: name, ok: ok, seconds: seconds, detail: detail))
            save(index: index, name: name)
            print("drive \(index) \(name): \(ok ? "ok" : "FAILED") in \(String(format: "%.2f", seconds)) s \(detail)")
            if !ok { break }
        }
        let passed = results.count == steps.count && results.allSatisfy(\.ok)
        let report = Report(
            passed: passed, hertz: preview.window.screen?.maximumFramesPerSecond ?? 0, scale: terminal.metrics.scale, steps: results,
            measures: measures
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? encoder.encode(report).write(to: out.appending(path: "report.json"))
        exit(passed ? 0 : 1)
    }

    private func perform(_ step: Step, name: String) async -> (Bool, String) {
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
            preview.window.setContentSize(NSSize(width: size[0], height: size[1]))
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
            if let expected = step.pasteboard { return (held == expected, "the pasteboard holds \(held.count) characters") }
            return (!held.isEmpty, "the pasteboard holds \(held.count) characters")
        }
        if let text = step.paste {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            guard let paste = Keys.named("v", modifiers: ["command"]) else { return (false, "no ⌘V") }
            send(paste)
            guard let confirm = step.confirm else { return (true, "") }
            let asked = await waitUntil(step.seconds ?? 5) { self.preview.window.attachedSheet != nil }
            guard asked, let answer = Keys.named(confirm ? "enter" : "escape", modifiers: []) else { return (false, "no confirmation was asked") }
            send(answer)
            return (true, confirm ? "pasted after the sheet" : "cancelled at the sheet")
        }
        if let count = step.drawables {
            terminal.drawableCount = count
            return (terminal.drawableCount == count, "\(terminal.drawableCount) drawables")
        }
        if step.resetLatency == true {
            terminal.resetLatency()
            return (true, "")
        }
        if let line = step.line {
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
        if let limit = step.gapUnder {
            let gap = terminal.latency.largestPresentGap
            return (gap <= limit, "the longest gap between frames drawing output was \(String(format: "%.1f", gap)) ms")
        }
        if let text = step.text {
            let found = await waitUntil(step.seconds ?? 10) { self.screenText.contains(text) }
            return (found, found ? "" : "the screen never showed '\(text)'")
        }
        if let banner = step.banner {
            let found = await waitUntil(step.seconds ?? 15) { self.preview.status?.contains(banner) == true }
            return (found, "the banner says '\(preview.status ?? "nothing")'")
        }
        if step.still == true {
            let before = screenText
            let outputs = terminal.latency.outputToCommitted.count + terminal.latency.outputToPresented.count
            try? await Task.sleep(for: .seconds(step.seconds ?? 3))
            let more = terminal.latency.outputToCommitted.count + terminal.latency.outputToPresented.count > outputs
            let stayed = screenText == before
            return (stayed && more, more ? (stayed ? "the view stayed while output came" : "the view moved when output came") : "no output came")
        }
        if let keys = step.measure {
            return await measure(keys, interval: step.interval ?? 50, name: name)
        }
        if step.killHost == true {
            guard preview.host.startedPID != nil else { return (false, "the preview started no host to kill") }
            preview.host.crash()
            return (true, "")
        }
        if step.newSession == true {
            await preview.open(fresh: true)
            return (true, "")
        }
        return (false, "the step names no action")
    }

    /// `keys` presses of "x" one each `interval` ms, the line cleared after, and the latency
    /// against the budgets: key to bytes written p95 ≤ 2 ms; key to echo on glass p95 ≤ 50 ms;
    /// output to frame committed p50 ≤ 1 refresh and p95 ≤ 2; output to glass p50 ≤ 2 and p95 ≤ 3.
    private func measure(_ keys: Int, interval: Int, name: String) async -> (Bool, String) {
        terminal.resetLatency()
        for _ in 0 ..< keys {
            key("x")
            try? await Task.sleep(for: .milliseconds(interval))
        }
        try? await Task.sleep(for: .milliseconds(500))
        let figures = terminal.latency
        if let clear = Keys.named("u", modifiers: ["control"]) { send(clear) }
        let refresh = 1000 / Double(max(1, preview.window.screen?.maximumFramesPerSecond ?? 60))
        let budgets = [
            "key to bytes written p95 <= 2 ms": figures.keyToWritten.p95 <= 2,
            "key to echo on glass p95 <= 50 ms": figures.keyToEcho.p95 <= 50,
            "output to committed p50 <= 1 refresh": figures.outputToCommitted.p50 <= refresh,
            "output to committed p95 <= 2 refreshes": figures.outputToCommitted.p95 <= 2 * refresh,
            "output to glass p50 <= 2 refreshes": figures.outputToPresented.p50 <= 2 * refresh,
            "output to glass p95 <= 3 refreshes": figures.outputToPresented.p95 <= 3 * refresh,
            "every echo measured": figures.keyToEcho.count >= keys * 9 / 10,
        ]
        measures.append(Measure(name: name, drawables: terminal.drawableCount, keys: keys, figures: figures, budgets: budgets))
        let missed = budgets.filter { !$0.value }.keys.sorted()
        return (missed.isEmpty, missed.isEmpty ? "within every budget" : "missed: \(missed.joined(separator: "; "))")
    }

    private var screenText: String {
        (try? preview.session?.screen.text()) ?? ""
    }

    private var lines: [String] {
        screenText.split(separator: "\n", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private func waitUntil(_ seconds: Double, _ condition: @MainActor () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return true
    }

    private func key(_ character: Character) {
        if let key = Keys.typing(character) { send(key) }
    }

    private func send(_ key: Keys.Key) {
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            guard let event = NSEvent.keyEvent(
                with: type, location: .zero, modifierFlags: key.flags, timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: (NSApp.keyWindow ?? preview.window).windowNumber, context: nil, characters: key.characters,
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
            timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: preview.window.windowNumber, context: nil, eventNumber: 0,
            clickCount: clicks, pressure: 1
        ) else { return }
        NSApp.sendEvent(event)
    }

    private func save(index: Int, name: String) {
        let stem = String(format: "%02d-", index) + name
        if let image = try? terminal.snapshot(), let png = image.png() {
            try? png.write(to: out.appending(path: stem + ".png"))
        }
        try? screenText.write(to: out.appending(path: stem + ".txt"), atomically: true, encoding: .utf8)
    }
}

extension Duration {
    var seconds: Double {
        let (whole, fraction) = components
        return Double(whole) + Double(fraction) / 1e18
    }
}
