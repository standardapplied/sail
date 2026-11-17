import AppKit
import Darwin
import QuartzCore
import SailShell
import SailMeasures
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
        if let kind = step.contextMenu {
            return contextMenu(kind, at: step.at ?? 0, items: step.items, choose: step.choose)
        }
        if step.blink == true {
            return await blinks()
        }
        if let renaming = step.renaming {
            let window = shell.window
            let field = { self.workbench.strip.tabs.compactMap(\.field).first }
            let reached = await waitUntil(2) {
                guard renaming else { return field() == nil }
                return field().map { $0.currentEditor() != nil && window.firstResponder === $0.currentEditor() } ?? false
            }
            let holder = window.firstResponder.map { String(describing: type(of: $0)) } ?? "nothing"
            return (reached, "the field is \(field() == nil ? "closed" : "open"); the keys are with \(holder)")
        }
        if let name = step.shot {
            return await inBothLooks(name) { file in await self.capture(["-o", "-l", "\(self.shell.window.windowNumber)"], to: file) }
        }
        if let name = step.shotMenu {
            return await inBothLooks(name) { file in self.captureWithTheMenuOpen(to: file) }
        }
        if let name = step.fullScreen {
            return await fullScreen(name)
        }
        if let state = step.otherHost {
            return await otherHost(state)
        }
        if let pattern = step.kill {
            let pkill = Process()
            pkill.executableURL = URL(filePath: "/usr/bin/pkill")
            pkill.arguments = ["-f", pattern]
            try? pkill.run()
            pkill.waitUntilExit()
            return (pkill.terminationStatus <= 1, "pkill -f \(pattern) exited \(pkill.terminationStatus)" + (pkill.terminationStatus == 1 ? " (none was running)" : ""))
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

    /// The window's own hit testing: a tab's middle reaches the tab, which never moves the window;
    /// the strip's empty end and the row just after the window's buttons reach no tab, and a view
    /// inside the title bar's own view (`NSTitlebarView`), which takes the drag and performs the
    /// double-click System Settings names. The double-click itself cannot be made in process (a
    /// made event handed to the title bar's view crashes the app; see the spec's report).
    private func stripHits() -> (Bool, String) {
        let window = shell.window
        guard let frame = window.contentView?.superview, let tab = workbench.strip.tabs.first, let middle = tabMiddle(0),
              let zoom = window.standardWindowButton(.zoomButton)
        else { return (false, "no tab to hit") }
        let strip = workbench.strip
        let hit = frame.hitTest(middle)
        let buttons = zoom.convert(zoom.bounds, to: nil)
        let places = [
            ("the strip's empty end", strip.convert(NSPoint(x: strip.bounds.maxX - 4, y: strip.bounds.midY), to: nil)),
            ("just after the window's buttons", NSPoint(x: buttons.maxX + 2, y: buttons.midY)),
        ]
        let said = places.map { name, point -> (Bool, String) in
            let empty = frame.hitTest(point)
            let chain = empty.map { sequence(first: $0, next: \.superview).map { "\(type(of: $0))" } } ?? []
            let onTab = empty.map { view in strip.tabs.contains { view === $0 || view.isDescendant(of: $0) } } ?? false
            let titleBars = !onTab && chain.contains("NSTitlebarView") && empty?.mouseDownCanMoveWindow == true
            return (titleBars, "\(name) hits \(chain.joined(separator: " < "))")
        }
        let onTab = (hit === tab || hit?.isDescendant(of: tab) == true) && !tab.mouseDownCanMoveWindow
        return (onTab && said.allSatisfy(\.0), "a tab's middle hits \(hit.map { "\(type(of: $0))" } ?? "nothing"); " + said.map(\.1).joined(separator: "; "))
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

    /// What each of `panes4`'s three floods writes: ``lines`` lines of 151 bytes each ``period``
    /// seconds, 2,114,000 bytes per second per pane. Twice a heavy build log's 1 MiB/s, and on the
    /// 3-vCPU runner well within the 1.5 CPUs allowed the terminal host and the app together: at
    /// it they took 0.43 (24 % and 19 %); unlimited, the same three floods took them 2.4 (sail#429).
    static let paneLoad = (lines: 280, period: 0.02)
    static var paneBytesPerSecond: Int {
        Int((Double(paneLoad.lines * 151) / paneLoad.period).rounded())
    }

    /// Scenario 32, `panes4`: a tab of four panes in a 2x2 grid. Three flood (lines of 150 `x` at
    /// ``paneLoad``, until the echo is measured, then the marker) while the fourth echoes 200 keys
    /// at one per 50 ms. Budgets: the echo pane's key to echo p95 ≤ 50 ms and output to committed
    /// p95 ≤ 2 refreshes; each flood shows its last bytes, and none of its output waited, nor was
    /// the main thread held, over 100 ms (``Driver/flood(_:)``); the four views' refresh work p95,
    /// summed, ≤ 8 ms; ⌘D to the new pane's first committed frame, ten times, p95 ≤ 100 ms. Each
    /// wait is net of the machine's share of it. Reported: the host's and the app's CPU shares over
    /// the measure's first second; each flood's frames and longest commit gap with what reached
    /// the screen and what was asked for in it; memory; and, after, the same three floods unlimited
    /// for 2.5 s, judged the same way and never gated.
    private func fourPanes() async -> (Bool, String) {
        let before = Self.footprint
        let tabsBefore = workbench.model.tabs.count
        _ = mainStacks
        let files = Self.floodFiles
        let stop = "/tmp/sail-drive-flood-stop"
        try? FileManager.default.removeItem(atPath: stop)
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
        print("panes4 idle, the echo pane focused: \(await wakeups(for: 5).said)")
        panes4["main thread's longest stall making the grid ms"] = mainThread.longest
        let echo = grid[1], floods = [grid[0], grid[2], grid[3]]
        func focus(_ view: TerminalPane) {
            view.window?.makeFirstResponder(view.terminal)
        }
        focus(echo)
        typeText("cat\r")
        func generate(_ block: Int, every period: Double, marker: Int) {
            let command = "clear; perl \(Self.floodPacer) \(files.lines) \(stop) \(block) \(period); cat \(files.tail); echo flood-$((\(marker - 1)+1))\r"
            for view in floods {
                focus(view)
                typeText(command)
            }
            focus(echo)
        }
        let rate = Self.paneBytesPerSecond
        print("panes4 load: each flood pane writes \(rate) bytes per second (\(Self.paneLoad.lines) lines of 151 bytes every \(Int(Self.paneLoad.period * 1000)) ms)")
        panes4["flood rate per pane, bytes per second"] = Double(rate)
        generate(Self.paneLoad.lines * 151, every: Self.paneLoad.period, marker: 2)
        print(String(format: "panes4 phase floods typed %.3f", CACurrentMediaTime()))
        let begun = await waitUntil(10) { floods.allSatisfy { ((try? $0.session?.screen.text()) ?? "").contains("xxxxxxxxxx") } }
        print(String(format: "panes4 phase floods begun %.3f", CACurrentMediaTime()))
        let shares = await cpuShares()
        print("panes4 CPU shares over the measure's first second: \(shares.said)")
        panes4["terminal host CPU share at the measure's start, %"] = shares.host
        panes4["app CPU share at the measure's start, %"] = shares.app
        await Self.processes().forEach { print("panes4 ps: \($0)") }
        floods.forEach { $0.terminal.resetLatency() }
        let framesBefore = floods.map { Self.committed($0.terminal) }
        let (ok, said) = await measure(200, interval: 50, name: "panes4-echo")
        print(String(format: "panes4 phase echo measured %.3f", CACurrentMediaTime()))
        await Self.processes().forEach { print("panes4 ps at the echo's end: \($0)") }
        await Self.touch(stop)
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
                print(String(format: "panes4 phase flood %d shown to its end %.3f", index + 1, CACurrentMediaTime()))
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
        for (index, view) in floods.enumerated() {
            let figures = ran[index] ?? view.terminal.latency
            let judged = flood(figures)
            if let span = figures.longestBetweenCommits {
                print(String(format: "panes4 flood %d longest gap %.3f–%.3f; refresh max %.1f ms", index + 1, span.start, span.end, figures.refresh.max))
                mainStacks.told(span).forEach { print("panes4 flood \(index + 1) in its longest gap: \($0)") }
            }
            budgets["flood \(index + 1): no output waited, nor the main thread held, over 100 ms in its \(frames[index]) frames (\(judged.said))"] =
                frames[index] > 0 && judged.ours <= 100
            measures.append(measurement("panes4-flood-\(index + 1)", keys: 0, figures: figures, budgets: [:]))
            panes4["flood \(index + 1) longest gap between committed frames ms"] = (figures.longestBetweenCommits?.length ?? 0) * 1000
            panes4["flood \(index + 1), ours (the larger of its longest output wait and the main thread's longest hold, less the machine's share), ms"] = judged.ours
            panes4["flood \(index + 1) frames committed"] = Double(frames[index])
            panes4["flood \(index + 1) outputs that reached the screen in its longest commit gap"] = Double(figures.longestGapOutputs)
            panes4["flood \(index + 1) ms a drawable was being asked for in its longest commit gap"] = figures.longestGapFetching
            panes4["flood \(index + 1) longest wait to be shown ms"] = figures.outputToShown.max
            panes4["flood \(index + 1) refresh p95 ms"] = figures.refresh.p95
        }
        for gap in mainThread.stalls.gaps where gap.length > 0.06 {
            print(String(format: "panes4 main thread held %.3f–%.3f (%.1f ms), the drive's own thread %.1f ms of it", gap.start, gap.end, gap.length * 1000,
                         ownThread.stalls.shared(with: gap) * 1000))
            mainStacks.told(gap).forEach { print("panes4 main thread held: \($0)") }
        }
        let work = grid.map { $0.terminal.latency.refreshWork.p95 }.reduce(0, +)
        budgets["the four views' refresh work p95 summed <= 8 ms (CPU time, the main thread's own clock)"] = work <= 8
        panes4["four views' refresh work p95 summed, CPU time, ms"] = work
        panes4["four views' refresh p95 summed, wall time, ms"] = grid.map { $0.terminal.latency.refresh.p95 }.reduce(0, +)
        panes4["echo key to echo p95 ms"] = keyToEcho
        panes4["echo output to committed p95 ms"] = echoed.outputToCommitted.p95
        panes4["peak resident memory at one pane MB"] = before
        panes4["peak resident memory at four panes MB"] = four
        await unlimitedFloods(floods, start: { generate(Self.paneLoad.lines * 151, every: 0, marker: 3) }, stop: stop)
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

    /// The terminal host's and the app's CPU shares, in %, over the next second.
    private func cpuShares() async -> (host: Double, app: Double, said: String) {
        let shares = await Self.cpuShares(host: workbench.host.servingProcess()?.pid, over: 1)
        guard let host = shares.host, let app = shares.app else {
            return (-1, -1, "not measured: ps listed \(shares.host == nil ? "no terminal host" : "the host") and \(shares.app == nil ? "not the app" : "the app")")
        }
        return (host, app, String(format: "terminal host %.0f %%, app %.0f %%, together %.2f CPUs", host, app, (host + app) / 100))
    }

    /// Report-only: the three floods unlimited for 2.5 s, then their marker; each judged as the
    /// gated floods are (``Driver/flood(_:)``), with the CPU shares over its first second.
    private func unlimitedFloods(_ floods: [TerminalPane], start: () -> Void, stop: String) async {
        try? FileManager.default.removeItem(atPath: stop)
        floods.forEach { $0.terminal.resetLatency() }
        mainThread.reset()
        ownThread.reset()
        let began = CACurrentMediaTime()
        start()
        let shares = await cpuShares()
        try? await Task.sleep(for: .seconds(max(0, 2.5 - (CACurrentMediaTime() - began))))
        await Self.touch(stop)
        let marker = "xxxxxxxxxxxxxxxxxxxxflood-3"
        let shown = await waitUntil(60) { floods.allSatisfy { Self.shows($0, marker) } }
        print("panes4 unlimited 2.5 s (reported, not gated): CPU shares over its first second \(shares.said); "
            + (shown ? "every flood shown to its last bytes" : "not every flood shown to its last bytes within 60 s"))
        panes4["unlimited: terminal host CPU share, %"] = shares.host
        panes4["unlimited: app CPU share, %"] = shares.app
        for (index, view) in floods.enumerated() {
            let judged = flood(view.terminal.latency)
            print("panes4 unlimited flood \(index + 1) (reported, not gated): \(judged.said)")
            panes4["unlimited flood \(index + 1), ours, ms"] = judged.ours
        }
    }

    /// A right-click's menu, asked of the view as AppKit asks it before it would show it.
    private func contextMenu(_ kind: String, at index: Int, items: [String]?, choose: String?) -> (Bool, String) {
        shell.window.layoutIfNeeded()
        let target: NSView?
        switch kind {
        case "pane": target = paneView(at: index)?.terminal
        case "tab": target = workbench.strip.tabs.indices.contains(index) ? workbench.strip.tabs[index] : nil
        default: target = nil
        }
        guard let target, let event = NSEvent.mouseEvent(
            with: .rightMouseDown, location: target.convert(NSPoint(x: target.bounds.midX, y: target.bounds.midY), to: nil), modifierFlags: [],
            timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: shell.window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1
        ) else { return (false, "no \(kind) \(index)") }
        guard let menu = target.menu(for: event) else { return (false, "the \(kind) offered no menu") }
        let titles = menu.items.map { $0.isSeparatorItem ? "-" : $0.title }
        let focused = kind != "pane" || shell.window.firstResponder === target
        var said = "the menu holds \(titles)" + (kind == "pane" ? "; the pane \(focused ? "was" : "was not") focused first" : "")
        guard items.map({ $0 == titles }) ?? true, focused else { return (false, said) }
        if let choose {
            guard let item = menu.items.firstIndex(where: { $0.title == choose }) else { return (false, said + "; no \(choose)") }
            menu.performActionForItem(at: item)
            said += "; chose \(choose)" + (workbench.strip.isRenaming ? ", a rename field is open" : "")
        }
        return (true, said)
    }

    /// P2: the focused pane's cursor in two frames a blink apart: they differ only inside the
    /// cursor's cell. Unfocused, its display link goes idle, no blink is due, and the cursor shows.
    /// The blink's wakeups and the frames committed a second, idle, over 5 s, are reported.
    private func blinks() async -> (Bool, String) {
        let view = terminal
        guard await waitUntil(3, { view.cursorOn }), let shown = try? view.snapshot(),
              await waitUntil(3, { !view.cursorOn }), let hidden = try? view.snapshot(),
              let cursor = view.lastFrame?.cursor.position
        else { return (false, "the cursor did not blink: \(view.drawingState)") }
        let metrics = view.metrics, origin = metrics.origin(of: cursor)
        let cell = (minX: origin.x, minY: origin.y, maxX: origin.x + 2 * metrics.width, maxY: origin.y + metrics.height)
        var outside = 0, inside = 0
        for y in 0 ..< min(shown.height, hidden.height) {
            for x in 0 ..< min(shown.width, hidden.width) {
                let at = (y * shown.width + x) * 4
                guard shown.bgra[at ..< at + 4] != hidden.bgra[at ..< at + 4] else { continue }
                if (cell.minX ..< cell.maxX).contains(x), (cell.minY ..< cell.maxY).contains(y) { inside += 1 } else { outside += 1 }
            }
        }
        let rate = await wakeups(for: 5)
        shell.window.makeFirstResponder(nil)
        let idle = await waitUntil(2) { view.drawingState.contains("display link paused") && view.drawingState.contains("not awaiting a drawable") }
        let still = idle && !view.isBlinking && view.cursorOn
        shell.window.makeFirstResponder(view)
        print("blink: \(rate.said) with \(workbench.panes.count) panes")
        let linkSlept = rate.linkFires < Self.idleLinkFires
        return (
            inside > 0 && outside == 0 && still && linkSlept,
            "a blink apart, \(inside) pixels differ inside the cursor's cell and \(outside) outside it; unfocused, "
                + (still ? "its link idle, no blink due, the cursor shown" : "idle \(idle), a blink due \(view.isBlinking), the cursor shown \(view.cursorOn)")
                + (linkSlept ? "; blinking, its display link slept" : "; blinking, its display link fired \(rate.linkFires) a second") + "; \(rate.said)"
        )
    }

    /// A blinking idle pane draws each half-blink on a drawable it asks for at once, so its display
    /// link does not fire; the old way fired it twice a half-blink, 4 a second.
    static let idleLinkFires = 0.5

    /// The idle cost a second over `seconds`, across every pane: the blink's timer wakeups, the
    /// display refreshes and the frames committed; then each pane's frames, the focused one marked,
    /// so a pane drawing for another reason shows; then how the focused pane's refreshes ended, and
    /// how many a second its display link fired (`linkFires`).
    func wakeups(for seconds: Double) async -> (said: String, linkFires: Double) {
        let views = orderedPanes.map(\.terminal)
        let counts = { views.map { ($0.blinkWakeups, $0.refreshes, Self.committed($0)) } }
        let focused = terminal
        let outcomes = { (focused.refreshOutcomes, focused.linkFires) }
        let before = counts(), outcomesBefore = outcomes()
        try? await Task.sleep(for: .seconds(seconds))
        let after = counts(), outcomesAfter = outcomes()
        let by = RefreshOutcome.allCases.map { outcome in
            "\(outcome)" + String(format: " %.1f", Double(outcomesAfter.0[outcome.rawValue] - outcomesBefore.0[outcome.rawValue]) / seconds)
        }.joined(separator: ", ")
        let per = zip(before, after).map { (Double($1.0 - $0.0) / seconds, Double($1.1 - $0.1) / seconds, Double($1.2 - $0.2) / seconds) }
        let total = per.reduce((0.0, 0.0, 0.0)) { ($0.0 + $1.0, $0.1 + $1.1, $0.2 + $1.2) }
        let panes = zip(views, per).map { view, rate in (view === terminal ? "*" : "") + String(format: "%.1f", rate.2) }.joined(separator: " ")
        let fires = Double(outcomesAfter.1 - outcomesBefore.1) / seconds
        let said = String(format: "%.1f blink wakeups, %.1f display refreshes and %.1f frames a second; frames a second by pane (* focused): ", total.0, total.1, total.2) + panes
            + String(format: "; the focused pane's refreshes a second, %.1f asked for by the display link, ending: ", fires) + by
        return (said, fires)
    }

    /// `take` in the light look, then the dark, each once the window has been drawn in it; the
    /// look the system gives comes back after. Fails when a file is missing or empty.
    private func inBothLooks(_ name: String, _ take: (URL) async -> Void) async -> (Bool, String) {
        let given = shell.look
        let folder = out.deletingLastPathComponent().appending(path: "window")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var taken: [String] = []
        for (look, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
            NSApp.appearance = NSAppearance(named: appearance)
            _ = await waitUntil(5) { (self.shell.look == .dark) == (look == "dark") }
            await drawnAndCommitted()
            await onTheCursorsVisiblePhase()
            let file = folder.appending(path: "\(name)-\(look).png")
            await take(file)
            let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
            taken.append("\(file.lastPathComponent) \(size) bytes")
        }
        NSApp.appearance = nil
        _ = await waitUntil(5) { self.shell.look == given }
        await drawnAndCommitted()
        return (taken.allSatisfy { !$0.hasSuffix(" 0 bytes") }, taken.joined(separator: ", "))
    }

    /// Returns once the window has drawn what it owes and that drawing is committed to the window
    /// server (the transaction's completion), at most 2 s: the fact a shot of the screen waits on.
    private func drawnAndCommitted() async {
        shell.window.displayIfNeeded()
        let committed = Mutex(false)
        CATransaction.begin()
        CATransaction.setCompletionBlock { committed.withLock { $0 = true } }
        CATransaction.commit()
        _ = await waitUntil(2) { committed.withLock { $0 } }
    }

    /// Returns just after the focused pane's cursor was drawn shown, at the start of its visible
    /// phase, so a shot shows what a person mostly sees; at once when no terminal shows or its
    /// cursor does not blink.
    private func onTheCursorsVisiblePhase() async {
        guard !workbench.isHiddenOrHasHiddenAncestor, let view = workbench.focused?.terminal, view.isBlinking else { return }
        _ = await waitUntil(2) { !view.cursorOn }
        let hidden = Self.committed(view)
        _ = await waitUntil(2) { view.cursorOn && Self.committed(view) > hidden }
    }

    /// `screencapture -x` with `arguments`, into `file`; its own error, if any, is printed.
    private func capture(_ arguments: [String], to file: URL) async {
        let process = Process()
        let errors = Pipe()
        process.executableURL = URL(filePath: "/usr/sbin/screencapture")
        process.arguments = ["-x"] + arguments + [file.path]
        process.standardError = errors
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            process.terminationHandler = { _ in done.resume() }
            guard (try? process.run()) == nil else { return }
            process.terminationHandler = nil
            print("screencapture did not run")
            done.resume()
        }
        let said = String(decoding: errors.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        if !said.isEmpty { print("screencapture: \(said)") }
    }

    /// The focused pane's context menu, popped up as a right-click shows it, the whole screen taken
    /// a second later (the menu is a window of its own), then the menu closed.
    private func captureWithTheMenuOpen(to file: URL) {
        let view = terminal
        guard let event = NSEvent.mouseEvent(
            with: .rightMouseDown, location: view.convert(NSPoint(x: 80, y: 60), to: nil), modifierFlags: [],
            timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: shell.window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1
        ), let menu = view.menu(for: event) else { return print("the pane offered no menu") }
        let process = Process()
        process.executableURL = URL(filePath: "/usr/sbin/screencapture")
        process.arguments = ["-x", "-T", "1", file.path]
        guard (try? process.run()) != nil else { return print("screencapture did not run") }
        menu.perform(#selector(NSMenu.cancelTracking), with: nil, afterDelay: 2.5, inModes: [.common])
        menu.popUp(positioning: nil, at: NSPoint(x: 80, y: 60), in: view)
        process.waitUntilExit()
    }

    /// The window in full screen once AppKit says the transition ended, filling its screen, the whole
    /// screen taken in both looks, then out of it again once AppKit says so, to the frame it had
    /// before, inside its screen; each frame seen on the way back is said. A window that does not enter full screen fails the step; one that cannot (on no screen,
    /// or not allowing full screen) is skipped, saying why.
    private func fullScreen(_ name: String) async -> (Bool, String) {
        let window = shell.window
        let before = window.frame
        guard let screen = window.screen else { return (true, "skipped: the window is on no screen, so this runner cannot show it in full screen") }
        guard window.styleMask.contains(.resizable), !window.collectionBehavior.contains(.fullScreenNone) else {
            return (true, "skipped: the window does not allow full screen")
        }
        let entered = Mutex(false), exited = Mutex(false)
        let center = NotificationCenter.default
        let watches = [
            center.addObserver(forName: NSWindow.didEnterFullScreenNotification, object: window, queue: nil) { _ in entered.withLock { $0 = true } },
            center.addObserver(forName: NSWindow.didExitFullScreenNotification, object: window, queue: nil) { _ in exited.withLock { $0 = true } },
        ]
        defer { watches.forEach { center.removeObserver($0) } }
        window.toggleFullScreen(nil)
        guard await waitUntil(10, { entered.withLock { $0 } && window.frame == screen.frame }) else {
            return (false, "the window did not finish entering full screen within 10 s: its frame \(NSStringFromRect(window.frame)), the screen's \(NSStringFromRect(screen.frame))")
        }
        let (taken, said) = await inBothLooks(name) { file in await self.capture([], to: file) }
        var passed: [String] = []
        func sample() {
            let frame = NSStringFromRect(window.frame)
            if passed.last != frame { passed.append(frame) }
        }
        window.toggleFullScreen(nil)
        let back = await waitUntil(10) {
            sample()
            return exited.withLock { $0 } && window.frame == before
        }
        let after = window.frame, visible = window.screen?.visibleFrame ?? .zero
        let same = abs(after.width - before.width) < 1 && abs(after.height - before.height) < 1
        let inside = after.width <= visible.width + 1 && after.height <= visible.height + 1
        let frames = "before \(NSStringFromRect(before)), after \(NSStringFromRect(after)), the screen's visible frame \(NSStringFromRect(visible)); "
            + "on the way back: \(passed.joined(separator: " → "))"
        return (taken && back && same && inside, said + (back ? "; " : "; the window did not come back to its frame within 10 s; ") + frames)
    }

    /// P4 and the design note's rule, against another copy's host with a client on it
    /// (`ci/drive`'s third run).
    private func otherHost(_ state: String) async -> (Bool, String) {
        let host = workbench.host
        func serving() -> String { host.servingProcess()?.executable ?? "none" }
        let ours = { serving().contains("/Application Support/Sail/Host/") }
        let other = { serving().contains("/other-build/") }
        let says = { (text: String) in self.workbench.bannerSays?.contains(text) == true }
        if state == "free" {
            let deadline = ContinuousClock.now + .seconds(10)
            var said = "nothing (no answer)"
            while ContinuousClock.now < deadline {
                if let listed = try? await SessionListing.sessions(at: host.address, timeout: 1) {
                    if listed.allSatisfy({ $0.attached == 0 }) { return (true, "the host lists \(listed.count) sessions, none with a client") }
                    said = listed.map { "\($0.name) attached \($0.attached)" }.joined(separator: ", ")
                }
                try? await Task.sleep(for: .milliseconds(100))
            }
            return (false, "after 10 s the host lists: \(said)")
        }
        let reached: Bool
        switch state {
        case "kept": reached = await waitUntil(20) { says(LocalHost.inUse(attached: 1)) && other() }
        case "replaced": reached = await waitUntil(30) { says(LocalHost.closingAnother) && ours() }
        default: return (false, "no other-host state \(state)")
        }
        var hosts = await Self.processes(matching: "_pty-host --local")
        let settled = ContinuousClock.now + .seconds(10)
        while hosts.count != 1, ContinuousClock.now < settled {
            try? await Task.sleep(for: .milliseconds(200))
            hosts = await Self.processes(matching: "_pty-host --local")
        }
        return (
            reached && hosts.count == 1,
            "serving: \(serving()); the banner says: \(workbench.bannerSays ?? "nothing"); hosts: \(hosts)"
        )
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
            ours.append(self.ours(wait).ours)
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
