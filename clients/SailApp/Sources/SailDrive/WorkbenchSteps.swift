import AppKit
import Darwin
import SailShell
import SailTerminal
import SailTerminalView
import SailWorkbench

/// The drive's steps on the workbench. Shortcuts are `press` steps, key events through the app's
/// `sendEvent`, so they reach the menu as a person's do; clicks and drags are mouse events at window
/// points through the same door. What a step asserts it reads from the model and from the views
/// the model drew.
extension Driver {
    var workbench: WorkbenchView {
        shell.thisMac
    }

    func performWorkbench(_ step: Step) async -> (Bool, String)? {
        if let expected = step.arrangement {
            let found = await waitUntil(step.seconds ?? 5) { self.arrangementMisses(expected).isEmpty }
            return (found, found ? arrangementSaid : arrangementMisses(expected).joined(separator: "; ") + "; " + arrangementSaid)
        }
        if let index = step.clickPane {
            guard let view = paneView(at: index) else { return (false, "no pane \(index) in the selected tab") }
            let middle = view.convert(NSPoint(x: view.bounds.midX, y: view.bounds.midY), to: nil)
            mouse(.leftMouseDown, at: middle, clicks: 1)
            mouse(.leftMouseUp, at: middle, clicks: 1)
            return (true, "")
        }
        if let index = step.dragDivider {
            return await dragDivider(index, by: step.by ?? 0, moves: step.moves ?? 1, interval: step.interval ?? 10)
        }
        if let tabs = step.dragTab, tabs.count == 2 {
            return dragTab(from: tabs[0], to: tabs[1])
        }
        if let index = step.renameTab {
            return rename(index, to: step.rename ?? "", with: step.renameWith ?? "enter")
        }
        if let answer = step.sheet {
            return await sheet(answer, asking: step.asking, within: step.seconds ?? 5)
        }
        if step.remember == true {
            remembered = pane?.name
            return (remembered != nil, "kept \(remembered ?? "no session")")
        }
        if let live = step.listed {
            guard let remembered else { return (false, "no session was kept") }
            let deadline = ContinuousClock.now + .seconds(step.seconds ?? 10)
            var listed = await listedLive()
            while listed[remembered] ?? false != live, ContinuousClock.now < deadline {
                try? await Task.sleep(for: .milliseconds(100))
                listed = await listedLive()
            }
            return (listed[remembered] ?? false == live, "the host lists \(remembered) \(listed[remembered].map { $0 ? "live" : "ended" } ?? "not at all")")
        }
        if step.stripHits == true {
            return stripHits()
        }
        if step.panes4 == true {
            return await fourPanes()
        }
        if step.onceResized == true {
            let holds = dragHolds
            let resized = await waitUntil(step.seconds ?? 5) { self.orderedPanes.allSatisfy { Self.resizedPerHold($0, holds: holds) } }
            let said = orderedPanes.map { view in
                "\(Self.winches(view)) for a grid of \(view.terminal.grid.map { "\($0.columns)" } ?? "none") columns"
            }
            return (resized, "at most \(holds + 1) sizes allowed (the drag held \(holds) times); " + said.joined(separator: "; "))
        }
        if let text = step.occurs {
            let times = step.times ?? 1
            let found = await waitUntil(step.seconds ?? 5) { self.screenText.components(separatedBy: text).count - 1 == times }
            return (found, "the screen holds it \(screenText.components(separatedBy: text).count - 1) times")
        }
        if step.markTop == true {
            markedTop = lines.first
            return (markedTop != nil, "the top line is '\(markedTop ?? "")'")
        }
        if step.sameTop == true {
            return (lines.first == markedTop, "the top line is '\(lines.first ?? "")', it was '\(markedTop ?? "")'")
        }
        if let seconds = step.wait {
            try? await Task.sleep(for: .seconds(seconds))
            return (true, "")
        }
        if let text = step.absent {
            return (!screenText.contains(text), screenText.contains(text) ? "the screen holds '\(text)'" : "")
        }
        return nil
    }

    /// The sessions the host lists, each live or not.
    private func listedLive() async -> [String: Bool] {
        let sessions = (try? await SessionListing.sessions(at: workbench.host.address, timeout: 2)) ?? []
        return Dictionary(sessions.map { ($0.name, $0.live) }) { first, _ in first }
    }

    /// The selected tab's pane views in reading order.
    private var orderedPanes: [TerminalPane] {
        (workbench.model.selectedTab?.layout.leaves ?? []).compactMap { workbench.panes[$0] }
    }

    private func paneView(at index: Int) -> TerminalPane? {
        orderedPanes.indices.contains(index) ? orderedPanes[index] : nil
    }

    /// The workbench in words: each tab's panes and title, the selected one, the focused pane, the
    /// frames of the selected tab's panes as its views have them.
    var arrangementSaid: String {
        let model = workbench.model
        let tabs = model.tabs.map { "\($0.layout.leaves.count) '\(model.title(of: $0.id))'\($0.id == model.selected ? " selected" : "")" }
        let frames = orderedPanes.map { view in
            view.isHidden ? "hidden" : String(format: "(%.1f, %.1f, %.1f, %.1f)", view.frame.minX, view.frame.minY, view.frame.width, view.frame.height)
        }
        let focus = model.selectedTab.flatMap { $0.layout.leaves.firstIndex(of: $0.focused) }.map(String.init) ?? "none"
        return "tabs [\(tabs.joined(separator: ", "))], focused pane \(focus), frames \(frames.joined(separator: " ")), zoomed "
            + "\(model.selectedTab?.zoomed != nil)"
    }

    /// What differs from `expected`. Frames are fractions of the tab's room, within 1 pt; the views'
    /// own frames must be the model's to the point, and every pane's view the first responder when
    /// it is focused.
    private func arrangementMisses(_ expected: Step.Expected) -> [String] {
        let model = workbench.model
        var misses: [String] = []
        if let tabs = expected.tabs, tabs != model.tabs.map({ $0.layout.leaves.count }) { misses.append("tabs are not \(tabs)") }
        if let selected = expected.selected, model.tabs.firstIndex(where: { $0.id == model.selected }) != selected {
            misses.append("tab \(selected) is not selected")
        }
        if let titles = expected.titles, titles != model.tabs.map({ model.title(of: $0.id) }) { misses.append("titles are not \(titles)") }
        if let zoomed = expected.zoomed, zoomed != (model.selectedTab?.zoomed != nil) { misses.append(zoomed ? "nothing is zoomed" : "a pane is zoomed") }
        if let empty = expected.empty, empty != workbench.saysNoTerminal { misses.append(empty ? "'No terminal is open' is not shown" : "'No terminal is open' shows") }
        guard let tab = model.selectedTab else { return misses + (expected.tabs == [] ? [] : ["no tab"]) }
        if let focus = expected.focus, tab.layout.leaves.firstIndex(of: tab.focused) != focus { misses.append("pane \(focus) is not focused") }
        if let dimmed = expected.dimmed, dimmed != orderedPanes.map(\.isDimmed) { misses.append("the dimmed panes are \(orderedPanes.map(\.isDimmed)), not \(dimmed)") }
        if let focused = workbench.focused, shell.window.isKeyWindow, shell.window.firstResponder !== focused.terminal {
            misses.append("the focused pane is not the first responder")
        }
        let room = workbench.bounds.size
        let (width, height) = (Double(room.width), Double(room.height))
        let frames = model.frames(in: room, scale: Double(shell.window.backingScaleFactor)).panes
        for id in tab.layout.leaves {
            guard let view = workbench.panes[id] else { misses.append("\(id) has no view"); continue }
            if view.isHidden != (frames[id] == nil) { misses.append("\(id)'s view is \(view.isHidden ? "hidden" : "shown") against the model") }
            if let frame = frames[id], view.frame != frame { misses.append("\(id)'s view is at \(view.frame), the model's \(frame)") }
        }
        if let fractions = expected.frames {
            let shown = tab.layout.leaves.compactMap { frames[$0] }
            if shown.count != fractions.count { misses.append("\(shown.count) panes shown, not \(fractions.count)") }
            for (frame, fraction) in zip(shown, fractions) where fraction.count == 4 || fraction.count == 8 {
                let points: [Double] = fraction.count == 8 ? Array(fraction[4...]) : [0, 0, 0, 0]
                let scaled: [Double] = [fraction[0] * width, fraction[1] * height, fraction[2] * width, fraction[3] * height]
                let wanted = zip(scaled, points).map { $0 + $1 }
                let found: [Double] = [Double(frame.minX), Double(frame.minY), Double(frame.width), Double(frame.height)]
                if zip(wanted, found).contains(where: { abs($0 - $1) > 1 }) { misses.append("a pane is at \(frame), not near \(wanted)") }
            }
        }
        return misses
    }

    func mouse(_ type: NSEvent.EventType, at point: NSPoint, clicks: Int) {
        guard let event = NSEvent.mouseEvent(
            with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
            windowNumber: shell.window.windowNumber, context: nil, eventNumber: 0, clickCount: clicks, pressure: 1
        ) else { return }
        NSApp.sendEvent(event)
    }

    /// Scenario 17: the divider pressed at its middle and moved `by` points in `moves` moves,
    /// `interval` ms apart; the panes must follow each move.
    private func dragDivider(_ index: Int, by distance: Double, moves: Int, interval: Int) async -> (Bool, String) {
        let dividers = workbench.model.frames(in: workbench.bounds.size, scale: Double(shell.window.backingScaleFactor)).dividers
        guard dividers.indices.contains(index), let tab = workbench.model.selectedTab else { return (false, "no divider \(index)") }
        let divider = dividers[index]
        let first = tab.layout.leaves.first.flatMap { workbench.panes[$0] }
        let before = first?.frame ?? .zero
        let start = (x: Double(divider.line.midX), y: Double(divider.line.midY))
        let along = divider.axis == .sideBySide
        func windowPoint(_ offset: Double) -> NSPoint {
            workbench.convert(NSPoint(x: start.x + (along ? offset : 0), y: start.y + (along ? 0 : offset)), to: nil)
        }
        mouse(.leftMouseDown, at: windowPoint(0), clicks: 1)
        var followed = 0
        var handled: [Double] = []
        var sentAt: [ContinuousClock.Instant] = []
        let views = tab.layout.leaves.compactMap { workbench.panes[$0] }
        views.forEach { $0.terminal.resetLatency() }
        mainThread.reset()
        ownThread.reset()
        let began = ContinuousClock.now
        for move in 1 ... max(1, moves) {
            let wanted = distance * Double(move) / Double(max(1, moves))
            let sent = ContinuousClock.now
            sentAt.append(sent)
            mouse(.leftMouseDragged, at: windowPoint(wanted), clicks: 1)
            handled.append((ContinuousClock.now - sent) / .milliseconds(1))
            let now = first?.frame ?? .zero
            let edge = along ? Double(now.maxX - before.maxX) : Double(now.maxY - before.maxY)
            if abs(edge - wanted) <= 1 { followed += 1 }
            try? await Task.sleep(for: .milliseconds(interval))
        }
        mouse(.leftMouseUp, at: windowPoint(distance), clicks: 1)
        let spent = (ContinuousClock.now - began) / .milliseconds(1)
        dragHolds = zip(sentAt, sentAt.dropFirst()).filter { $1 - $0 >= TerminalSession.resizeHold }.count
        let timing = String(format: "a move handled in p50 %.1f ms, at most %.1f ms; ", handled.sorted()[handled.count / 2], handled.max() ?? 0)
            + "\(handled.count) moves in \(Int(spent)) ms, \(dragHolds) of the gaps between them \(Int(TerminalSession.resizeHold / .milliseconds(1))) ms or more; the main thread held at most \(Int(mainThread.longest)) ms, a thread of the drive's own "
            + "\(Int(ownThread.longest)) ms; the panes' longest refresh " + views.map { String(format: "%.1f ms", $0.terminal.latency.refresh.max) }.joined(separator: ", ")
        return (followed == max(1, moves), "the panes followed \(followed) of \(max(1, moves)) moves; \(timing); \(arrangementSaid)")
    }

    /// The middle of the tab at `index` in window points, the window laid out first as it is
    /// before it is drawn: a tab is where a person sees it.
    private func tabMiddle(_ index: Int) -> NSPoint? {
        shell.window.layoutIfNeeded()
        let tabs = workbench.strip.tabs
        guard tabs.indices.contains(index) else { return nil }
        return tabs[index].convert(NSPoint(x: tabs[index].bounds.midX, y: tabs[index].bounds.midY), to: nil)
    }

    /// Scenario 28: the tab at `from` pressed, dragged to the middle of the tab at `to`, let go.
    private func dragTab(from: Int, to: Int) -> (Bool, String) {
        guard let start = tabMiddle(from), let end = tabMiddle(to) else { return (false, "no tab \(from) or \(to)") }
        press("\(to + 1)", ["command"])
        shell.window.layoutIfNeeded()
        let tab = workbench.strip.tabs[from]
        let before = tab.frame.minX
        mouse(.leftMouseDown, at: start, clicks: 1)
        let pressed = workbench.model.tabs.firstIndex { $0.id == workbench.model.selected } == from
        for step in 1 ... 10 {
            mouse(.leftMouseDragged, at: NSPoint(x: start.x + (end.x - start.x) * Double(step) / 10, y: start.y), clicks: 1)
        }
        let moved = tab.frame.minX
        mouse(.leftMouseUp, at: end, clicks: 1)
        return (true, "the press \(pressed ? "selected" : "did not select") the tab; it went from x \(Int(before)) to \(Int(moved)) before the release; "
            + arrangementSaid)
    }

    /// Scenario 29: a double-click on the tab, `text` typed into its field, then Return or Escape.
    private func rename(_ index: Int, to text: String, with ending: String) -> (Bool, String) {
        guard let middle = tabMiddle(index) else { return (false, "no tab \(index)") }
        mouse(.leftMouseDown, at: middle, clicks: 1)
        mouse(.leftMouseUp, at: middle, clicks: 1)
        mouse(.leftMouseDown, at: middle, clicks: 2)
        mouse(.leftMouseUp, at: middle, clicks: 2)
        guard workbench.strip.isRenaming else { return (false, "the double-click opened no field") }
        if let all = Keys.named("a", modifiers: ["command"]) { send(all) }
        for character in text { key(character) }
        guard let end = Keys.named(ending, modifiers: []) else { return (false, "no key named \(ending)") }
        send(end)
        return (!workbench.strip.isRenaming, "the field is \(workbench.strip.isRenaming ? "still open" : "closed"); \(arrangementSaid)")
    }

    /// Answers the sheet the window shows: its button named `answer`; or, for "none", no sheet comes.
    private func sheet(_ answer: String, asking: String?, within seconds: Double) async -> (Bool, String) {
        let asked = await waitUntil(answer == "none" ? 1 : seconds) { self.shell.window.attachedSheet != nil }
        if answer == "none" { return (!asked, asked ? "a sheet asked" : "no sheet asked") }
        guard asked, let sheet = shell.window.attachedSheet else { return (false, "no sheet asked") }
        let words = Self.texts(sheet.contentView).joined(separator: " ")
        if let asking, !words.contains(asking) { return (false, "the sheet says '\(words)', not '\(asking)'") }
        guard let button = Self.button(answer, in: sheet.contentView) else { return (false, "the sheet has no \(answer): '\(words)'") }
        button.performClick(nil)
        let gone = await waitUntil(5) { self.shell.window.attachedSheet == nil }
        return (gone, "the sheet said '\(words)'; \(answer)")
    }

    static func texts(_ view: NSView?) -> [String] {
        guard let view else { return [] }
        return ((view as? NSTextField).map { [$0.stringValue] } ?? []) + view.subviews.flatMap(texts)
    }

    /// The window's own hit testing at a tab's middle reaches the tab, and at the strip's empty end
    /// it reaches no tab (the title bar's, or the strip's own plain view).
    private func stripHits() -> (Bool, String) {
        guard let frame = shell.window.contentView?.superview, let tab = workbench.strip.tabs.first, let middle = tabMiddle(0) else {
            return (false, "no tab to hit")
        }
        let hit = frame.hitTest(middle)
        let strip = workbench.strip
        let end = strip.convert(NSPoint(x: strip.bounds.maxX - 4, y: strip.bounds.midY), to: nil)
        let empty = frame.hitTest(end)
        let onTab = hit === tab || hit?.isDescendant(of: tab) == true
        let emptyOnTab = empty.map { view in strip.tabs.contains { view === $0 || view.isDescendant(of: $0) } } ?? false
        let emptyMoves = empty?.mouseDownCanMoveWindow == true
        let tabStays = onTab && !tab.mouseDownCanMoveWindow
        return (
            tabStays && !emptyOnTab && emptyMoves,
            "a tab's middle hits \(hit.map { "\(type(of: $0))" } ?? "nothing"), the strip's empty end \(empty.map { "\(type(of: $0))" } ?? "nothing")"
                + " (it moves the window: \(empty?.mouseDownCanMoveWindow == true))"
        )
    }

    /// The most memory the process has held resident so far, in MB.
    private static var footprint: Double {
        var usage = rusage()
        return getrusage(RUSAGE_SELF, &usage) == 0 ? Double(usage.ru_maxrss) / 1_048_576 : -1
    }

    private func press(_ name: String, _ modifiers: [String]) {
        if let key = Keys.named(name, modifiers: modifiers) { send(key) }
    }

    private func typeText(_ text: String) {
        for character in text { key(character) }
    }

    /// Scenario 32, `panes4`: a tab of four panes in a 2x2 grid; three flood 20 MB each while the
    /// fourth echoes 200 keys at one per 50 ms. Budgets: the echo pane's key to echo p95 ≤ 50 ms
    /// (presented when the display reports it, else the view's estimate, said which) and output to
    /// committed p95 ≤ 2 refreshes; each flooding pane shows its last bytes and no output waited
    /// over 100 ms to be shown; the four views' refresh work p95, summed, ≤ 8 ms; ⌘D to the new
    /// pane's first committed frame, ten times, p95 ≤ 100 ms. Reported only: memory at one pane and
    /// at four, and the atlas bytes of each. The tabs it made are closed after.
    private func fourPanes() async -> (Bool, String) {
        let before = Self.footprint
        let tabsBefore = workbench.model.tabs.count
        mainThread.reset()
        press("t", ["command"])
        guard await waitUntil(10, { self.workbench.model.tabs.count == tabsBefore + 1 && self.pane?.session?.state.phase == .attached }) else {
            return (false, "⌘T made no attached tab: \(arrangementSaid)")
        }
        press("d", ["command"])
        press("d", ["command", "shift"])
        press("arrowLeft", ["command", "option"])
        press("d", ["command", "shift"])
        let grid = orderedPanes
        guard grid.count == 4, await waitUntil(20, { grid.allSatisfy { $0.session?.state.phase == .attached } }) else {
            return (false, "the grid did not attach four panes: \(arrangementSaid)")
        }
        let four = Self.footprint
        panes4["main thread's longest stall making the grid ms"] = mainThread.longest
        let echo = grid[1], floods = [grid[0], grid[2], grid[3]]
        func focus(_ view: TerminalPane) {
            view.window?.makeFirstResponder(view.terminal)
        }
        focus(echo)
        typeText("cat\r")
        let flood = "clear; head -c 20971520 /dev/zero | tr '\\0' x | fold -w 150; echo flood-$((1+1))\r"
        for view in floods {
            focus(view)
            typeText(flood)
        }
        focus(echo)
        grid.forEach { $0.terminal.resetLatency() }
        let (ok, said) = await measure(200, interval: 50, name: "panes4-echo")
        panes4["main thread's longest stall while four ran ms"] = mainThread.longest
        let refresh = 1000 / Double(max(1, shell.window.screen?.maximumFramesPerSecond ?? 60))
        let echoed = echo.terminal.latency
        let presented = echoed.keyToEcho.count > 0
        let keyToEcho = presented ? echoed.keyToEcho.p95 : echoed.keyToEchoEstimated.p95
        let marker = "xxxxxxxxxxxxxxxxxxxxflood-2"
        let shown = await waitUntil(180) { floods.allSatisfy { Self.shows($0, marker) } }
        var budgets = [
            "echo key to echo p95 <= 50 ms (\(presented ? "presented" : "estimated"))": keyToEcho <= 50,
            "echo output to committed p95 <= 2 refreshes": echoed.outputToCommitted.p95 <= 2 * refresh,
            "every flood shown to its last bytes": shown,
        ]
        for (index, view) in floods.enumerated() {
            let figures = view.terminal.latency
            budgets["flood \(index + 1): no output waited over 100 ms to be shown"] = figures.outputToShown.max <= 100
            measures.append(measurement("panes4-flood-\(index + 1)", keys: 0, figures: figures, budgets: [:]))
            panes4["flood \(index + 1) longest wait to be shown ms"] = figures.outputToShown.max
            panes4["flood \(index + 1) refresh p95 ms"] = figures.refresh.p95
        }
        let work = grid.map { $0.terminal.latency.refresh.p95 }.reduce(0, +)
        budgets["the four views' refresh work p95 summed <= 8 ms"] = work <= 8
        panes4["four views' refresh p95 summed ms"] = work
        panes4["echo key to echo p95 ms"] = keyToEcho
        panes4["echo output to committed p95 ms"] = echoed.outputToCommitted.p95
        panes4["peak resident memory at one pane MB"] = before
        panes4["peak resident memory at four panes MB"] = four
        for (index, view) in grid.enumerated() {
            panes4["atlas bytes, pane \(index + 1)"] = Double(view.terminal.atlasBytes)
        }
        press("w", ["command", "option"])
        _ = await sheet("Close", asking: "Its 4 terminals will end.", within: 5)
        let splits = await timeSplits(10)
        budgets["⌘D to the new pane's first committed frame p95 <= 100 ms"] = splits.total <= 100
        panes4["⌘D to first committed frame p95 ms"] = splits.total
        panes4["of which ⌘D handled (pane, view, session made) p95 ms"] = splits.handled
        panes4["main thread's longest stall within a split p95 ms"] = splits.held

        let missed = budgets.filter { !$0.value }.keys.sorted()
        let numbers = panes4.sorted { $0.key < $1.key }.map { "\($0.key) \(String(format: "%.1f", $0.value))" }.joined(separator: ", ")
        return (ok && missed.isEmpty, (missed.isEmpty ? "within every budget" : "missed: \(missed.joined(separator: "; "))") + "; echo: \(said); \(numbers)")
    }

    /// The lines `view`'s shell printed at each size change, `winch-<columns>`.
    static func winches(_ view: TerminalPane) -> [String] {
        let text = (try? view.session?.screen.text()) ?? ""
        return text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.hasPrefix("winch-") }
    }

    /// A size reached `view`'s program only when the drag held (`holds` times) and once at its
    /// end, and the last was the view's grid.
    static func resizedPerHold(_ view: TerminalPane, holds: Int) -> Bool {
        guard let columns = view.terminal.grid?.columns else { return false }
        let sizes = winches(view)
        return (1 ... holds + 1).contains(sizes.count) && sizes.last == "winch-\(columns)"
    }

    /// Whether a line of `view`'s screen, trimmed, is `line`.
    static func shows(_ view: TerminalPane, _ line: String) -> Bool {
        let text = (try? view.session?.screen.text()) ?? ""
        return text.split(separator: "\n").contains { $0.trimmingCharacters(in: .whitespaces) == line }
    }

    /// ⌘D in a new tab, `count` times: each from the key to the new pane's first committed frame,
    /// in ms, the p95; beside it, of the same splits, the p95 of the key's own handling (the pane
    /// and its view made, its session opened) and of the main thread's longest stall. Each tab is
    /// closed after.
    private func timeSplits(_ count: Int) async -> (total: Double, handled: Double, held: Double) {
        var totals: [Double] = [], handled: [Double] = [], held: [Double] = []
        for _ in 0 ..< count {
            press("t", ["command"])
            _ = await waitUntil(10) { self.pane?.terminal.framesCommitted ?? 0 > 0 }
            mainThread.reset()
            let began = ContinuousClock.now
            press("d", ["command"])
            handled.append((ContinuousClock.now - began) / .milliseconds(1))
            let made = pane
            while (made?.terminal.framesCommitted ?? 0) == 0, ContinuousClock.now - began < .seconds(5) {
                try? await Task.sleep(for: .milliseconds(1))
            }
            totals.append((ContinuousClock.now - began) / .milliseconds(1))
            held.append(mainThread.longest)
            press("w", ["command", "option"])
            _ = await sheet("Close", asking: nil, within: 5)
        }
        return (Self.p95(totals), Self.p95(handled), Self.p95(held))
    }

    static func p95(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        return sorted.isEmpty ? .infinity : sorted[min(sorted.count - 1, Int((Double(sorted.count) * 0.95).rounded(.up)) - 1)]
    }
}
