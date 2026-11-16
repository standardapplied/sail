import AppKit
import Darwin
import QuartzCore
import SailShell
import SailSurface
import SailTerminal
import SailTerminalView
import SailWorkbench
import Synchronization

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
        if let times = step.titleBurst {
            let writes = await arrangementWrites(within: 2) {
                typeText("for i in $(seq 1 \(times)); do printf '\\e]2;t%s\\a' $i; done\r")
            }
            return (writes == 0, "the program set its title \(times) times; the arrangement was written \(writes) times")
        }
        if let times = step.dividerBurst {
            let writes = await arrangementWrites(within: 1.5) {
                for _ in 0 ..< times { press("arrowRight", ["command", "control"]) }
            }
            return (writes == 1, "⌃⌘→ \(times) times at once; the arrangement was written \(writes) times")
        }
        if step.hold == true {
            guard let view = pane?.terminal else { return (false, "no pane is focused") }
            held = (view, view.latency.refresh.count)
            return (true, "")
        }
        if step.heldPaused == true {
            guard let held else { return (false, "no view was kept") }
            let refreshes = held.view.latency.refresh.count
            try? await Task.sleep(for: .seconds(1))
            let paused = held.view.drawingState.contains("display link paused")
            let still = held.view.latency.refresh.count == refreshes
            return (held.view.isHiddenOrHasHiddenAncestor && paused && still, "hidden \(held.view.isHiddenOrHasHiddenAncestor); \(held.view.drawingState)")
        }
        if step.heldResumed == true {
            guard let held else { return (false, "no view was kept") }
            let drew = await waitUntil(step.seconds ?? 2) { held.view.latency.refresh.count > held.refreshes }
            return (drew && !held.view.isHiddenOrHasHiddenAncestor, held.view.drawingState)
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
        if let empty = expected.empty, empty != saysNoTerminal { misses.append(empty ? "'No terminal is open' is not shown" : "'No terminal is open' shows") }
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
        guard ending != "none" else { return (workbench.strip.isRenaming, "the field is \(workbench.strip.isRenaming ? "open" : "closed")") }
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
    /// committed p95 ≤ 2 refreshes; each flooding pane shows its last bytes, with no gap between its
    /// committed frames over 100 ms from its flood's first lines to its last; the four views'
    /// refresh work p95, summed, ≤ 8 ms; ⌘D to the new pane's first committed frame, ten times, p95
    /// ≤ 100 ms. Each wait is net of the drive's own thread's stall during it. Reported only: memory
    /// at one pane and at four. The tabs it made are closed after.
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
        let begun = await waitUntil(10) { floods.allSatisfy { ((try? $0.session?.screen.text()) ?? "").contains("xxxxxxxxxx") } }
        floods.forEach { $0.terminal.resetLatency() }
        let framesBefore = floods.map { Self.committed($0.terminal) }
        let (ok, said) = await measure(200, interval: 50, name: "panes4-echo")
        panes4["main thread's longest stall while four ran ms"] = mainThread.longest
        panes4["a thread of the drive's own, longest stall while four ran ms"] = ownThread.longest
        let refresh = 1000 / Double(max(1, shell.window.screen?.maximumFramesPerSecond ?? 60))
        let echoed = echo.terminal.latency
        let presented = echoed.keyToEcho.count > 0
        let keyToEcho = presented ? echoed.keyToEcho.p95 : echoed.keyToEchoEstimated.p95
        let marker = "xxxxxxxxxxxxxxxxxxxxflood-2"
        var ran = [LatencyFigures?](repeating: nil, count: floods.count), frames = [Int](repeating: 0, count: floods.count)
        let shown = await waitUntil(180) {
            for (index, view) in floods.enumerated() where ran[index] == nil && Self.shows(view, marker) {
                ran[index] = view.terminal.latency
                frames[index] = Self.committed(view.terminal) - framesBefore[index]
            }
            return !ran.contains(nil)
        }
        var budgets = [
            "echo key to echo p95 <= 50 ms (\(presented ? "presented" : "estimated"))": keyToEcho <= 50,
            "echo output to committed p95 <= 2 refreshes": echoed.outputToCommitted.p95 <= 2 * refresh,
            "every flood begun within 10 s": begun,
            "every flood shown to its last bytes": shown,
        ]
        let own = ownThread.stalls
        for (index, view) in floods.enumerated() {
            let figures = ran[index] ?? view.terminal.latency
            let gap = figures.longestBetweenCommits.map { Self.ours($0, own: own) }
            budgets["flood \(index + 1): no gap between committed frames over 100 ms in its \(frames[index]) frames (\(gap?.said ?? "no frame committed"))"] =
                (gap?.ours ?? .infinity) <= 100
            measures.append(measurement("panes4-flood-\(index + 1)", keys: 0, figures: figures, budgets: [:]))
            panes4["flood \(index + 1) longest gap between committed frames ms"] = (figures.longestBetweenCommits?.length ?? 0) * 1000
            panes4["flood \(index + 1) longest wait to be shown ms"] = figures.outputToShown.max
            panes4["flood \(index + 1) refresh p95 ms"] = figures.refresh.p95
        }
        let work = grid.map { $0.terminal.latency.refreshWork.p95 }.reduce(0, +)
        budgets["the four views' refresh work p95 summed <= 8 ms (CPU time, the main thread's own clock)"] = work <= 8
        panes4["four views' refresh work p95 summed, CPU time, ms"] = work
        panes4["four views' refresh p95 summed, wall time, ms"] = grid.map { $0.terminal.latency.refresh.p95 }.reduce(0, +)
        panes4["echo key to echo p95 ms"] = keyToEcho
        panes4["echo output to committed p95 ms"] = echoed.outputToCommitted.p95
        panes4["peak resident memory at one pane MB"] = before
        panes4["peak resident memory at four panes MB"] = four
        press("w", ["command", "option"])
        _ = await sheet("Close", asking: "Its 4 terminals will end.", within: 5)
        let splits = await timeSplits(10)
        budgets["⌘D to the new pane's first committed frame p95 <= 100 ms (\(splits.said))"] = splits.ours <= 100
        panes4["⌘D to first committed frame p95 ms"] = splits.total
        panes4["⌘D to first committed frame, less the drive's own thread's hold, p95 ms"] = splits.ours
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

    /// How many times the app writes its user defaults while `act` runs and for `seconds` after it
    /// (each write posts `UserDefaults.didChangeNotification` in the process), after a write the
    /// steps before left pending has landed.
    private func arrangementWrites(within seconds: Double, _ act: () -> Void) async -> Int {
        try? await Task.sleep(for: .seconds(1))
        let writes = Mutex(0)
        let watch = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: nil) { _ in
            writes.withLock { $0 += 1 }
        }
        defer { NotificationCenter.default.removeObserver(watch) }
        act()
        try? await Task.sleep(for: .seconds(seconds))
        return writes.withLock { $0 }
    }

    /// How many frames `view` has committed, as its drawing state says.
    static func committed(_ view: TerminalView?) -> Int {
        guard let state = view?.drawingState, let found = state.range(of: "committed ") else { return 0 }
        return Int(state[found.upperBound...].prefix { $0.isNumber }) ?? 0
    }

    /// "No terminal is open" shows: its New Terminal button is on screen.
    var saysNoTerminal: Bool {
        Self.button("New Terminal", in: workbench).map { !$0.isHiddenOrHasHiddenAncestor } ?? false
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
    private func timeSplits(_ count: Int) async -> (total: Double, ours: Double, said: String, handled: Double, held: Double) {
        var totals: [Double] = [], ours: [Double] = [], handled: [Double] = [], held: [Double] = [], ownHeld: [Double] = []
        var samples: [String] = []
        for _ in 0 ..< count {
            press("t", ["command"])
            _ = await waitUntil(10) { Self.committed(self.pane?.terminal) > 0 }
            mainThread.reset()
            ownThread.reset()
            let began = CACurrentMediaTime()
            press("d", ["command"])
            handled.append((CACurrentMediaTime() - began) * 1000)
            let made = pane
            while Self.committed(made?.terminal) == 0, CACurrentMediaTime() - began < 5 {
                try? await Task.sleep(for: .milliseconds(1))
            }
            let wait = Span(began, CACurrentMediaTime())
            totals.append(wait.length * 1000)
            held.append(mainThread.longest)
            ownHeld.append(ownThread.stalls.shared(with: wait) * 1000)
            ours.append(Self.ours(wait, own: ownThread.stalls).ours)
            samples.append(String(format: "handled %.1f, first frame %.1f, own thread %.1f, ours %.1f", handled.last ?? 0, totals.last ?? 0, ownHeld.last ?? 0, ours.last ?? 0))
            _ = await waitUntil(5) { made?.session?.state.phase == .attached }
            press("w", ["command", "option"])
            _ = await sheet("Close", asking: nil, within: 5)
        }
        panes4["a thread of the drive's own, held during a split p95 ms"] = Self.p95(ownHeld)
        let said = "p95 waited \(Self.ms(Self.p95(totals))) ms, the drive's own thread held \(Self.ms(Self.p95(ownHeld))) ms, ours "
            + "\(Self.ms(Self.p95(ours))) ms; each split in ms: " + samples.joined(separator: " | ")
        return (Self.p95(totals), Self.p95(ours), said, Self.p95(handled), Self.p95(held))
    }

    static func p95(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        return sorted.isEmpty ? .infinity : sorted[min(sorted.count - 1, Int((Double(sorted.count) * 0.95).rounded(.up)) - 1)]
    }
}
