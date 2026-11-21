import AppKit
import ImageIO
import SailControls
import SailLook
import SailFiles
import SailKitTestSupport
import SailMeasures
import SailShell
import SailTerminal
import SailTerminalWire

/// The drive's steps on a workspace tab's files panel, against the drive's runner door, which plays
/// the workspace's folders (``FakeRunnerDoor/setFolder(_:_:truncated:)``) and holds, silences, ends
/// or drops its files connections as a step says. Keys and clicks are a person's: ⌃⌘S and the
/// arrows by `press`, rows and the line's button by a click sent through the app.
extension Driver {
    static let filesRoot = FileTree.root.shown

    /// An entry as a step spells it: a trailing "/" a folder.
    nonisolated static func fileEntry(_ spelled: String) -> FileEntry {
        let folder = spelled.hasSuffix("/")
        return FileEntry(
            name: FileName(folder ? String(spelled.dropLast()) : spelled), kind: .known(folder ? .folder : .file), target: .known(.none), size: folder ? 0 : 1,
            mtimeNanos: 0, mode: folder ? 0o755 : 0o644
        )
    }

    nonisolated static func berths(_ count: Int) -> [FileEntry] {
        (0 ..< count).map { fileEntry(String(format: "berth-%04d.json", $0)) }
    }

    static func filesPath(_ folder: String) -> String {
        folder.isEmpty ? filesRoot : filesRoot + "/" + folder
    }

    var filesShown: FilesShown? {
        workbench.filesOnScreen
    }

    /// Waits for a state of the files panel, or a figure's moment, for all of `seconds`, a failure
    /// earlier in the run notwithstanding: Still listing comes at 6 s and Can't reach after the
    /// reconnect's minute, so the drive's 3 s after a failure would fail every state behind one, and
    /// one run shows them all.
    private func waitForState(_ seconds: Double, _ condition: @MainActor () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return true
    }

    func performFiles(_ step: Step) async -> (Bool, String)? {
        WorkbenchView.filesMeasuring = true
        let asks = [step.filesFolder, step.filesRefuse, step.filesNew, step.filesClick, step.filesDoubleClick, step.filesChosen, step.filesButton, step.filesShot].contains { $0 != nil }
            || step.filesRows != nil || step.filesSays != nil || step.filesHold != nil || step.filesTicks != nil || step.filesEnd != nil
            || step.filesDrop != nil || step.filesBudgets != nil || step.filesReconnects != nil || step.filesFlood != nil
        guard asks else { return nil }
        guard let door = GoldenDesk.door else { return (false, "the drive's runner door could not listen") }
        if let folder = step.filesFolder {
            let entries = step.filesCount.map(Self.berths) ?? (step.filesEntries ?? []).map(Self.fileEntry)
            door.setFolder(Self.filesPath(folder), entries)
            return (true, "\(Self.filesPath(folder)) holds \(entries.count) entries")
        }
        if let folder = step.filesRefuse {
            door.refuseFolder(Self.filesPath(folder), .notPermitted, saying: "The workspace's user may not open \(folder).")
            return (true, "")
        }
        if let rows = step.filesRows {
            let found = await waitForState(step.seconds ?? 10) { self.filesShown?.list.numberOfRows == rows }
            let keys = shell.window.firstResponder.map { "\(type(of: $0))" } ?? "nothing"
            return (found, "the panel shows \(filesShown.map { "\($0.list.numberOfRows) rows: \($0.rows.prefix(12).map(\.name))" } ?? "nothing"); its line: \(filesShown?.words ?? "none"); chosen \(filesShown?.list.chosen ?? "nothing"); the keys with \(keys)")
        }
        if step.filesReconnects == true {
            let before = door.filesAdmitted
            let again = await waitForState(step.seconds ?? 10) { door.filesAdmitted > before }
            return (again, "files connections admitted \(before) then \(door.filesAdmitted)")
        }
        if let words = step.filesSays {
            let found = await waitForState(step.seconds ?? 10) {
                guard let shown = self.filesShown else { return false }
                if let not = step.filesSaysNot, shown.words?.hasSuffix(not) == true { return false }
                if let ending = step.filesSaysEnding, shown.words?.hasSuffix(ending) != true { return false }
                return words.isEmpty ? shown.words == nil : shown.words?.hasPrefix(words) == true
            }
            return (found, "the line says \(filesShown?.words ?? "nothing")")
        }
        if let name = step.filesNew {
            let found = await waitForState(step.seconds ?? 10) { self.filesShown?.new.contains { $0.hasSuffix("/" + name) } == true }
            return (found, "marked new: \(filesShown?.new.sorted() ?? [])")
        }
        if let name = step.filesClick {
            guard let shown = filesShown, let id = shown.rows.first(where: { $0.name == name && $0.kind != .note })?.path.key else { return (false, "no row \(name)") }
            guard await clickName(id, in: shown) else { return (false, "the click on \(name) was not sent") }
            let chosen = await waitForState(5) { self.filesShown?.list.chosen == id }
            return (chosen, "chosen: \(filesShown?.list.chosen ?? "nothing")")
        }
        if let name = step.filesChosen {
            let found = await waitForState(step.seconds ?? 5) {
                guard let shown = self.filesShown, let chosen = shown.list.chosen, let responder = self.shell.window.firstResponder as? NSView else { return false }
                return chosen.hasSuffix("/" + name) && responder.isDescendant(of: shown.list)
            }
            let keys = shell.window.firstResponder.map { "\(type(of: $0))" } ?? "nothing"
            return (found, "chosen \(filesShown?.list.chosen ?? "nothing"), the keys with \(keys)")
        }
        if let name = step.filesDoubleClick {
            guard let shown = filesShown, let row = shown.rows.first(where: { $0.name == name && $0.kind != .note }) else { return (false, "no row \(name)") }
            let open = row.expanded
            guard await clickName(row.path.key, in: shown, twice: true) else { return (false, "the double-click on \(name) was not sent") }
            let toggled = await waitForState(5) { self.filesShown?.rows.first { $0.path == row.path }?.expanded == !open }
            return (toggled, "\(name) \(filesShown?.rows.first { $0.path == row.path }?.expanded == true ? "open" : "shut") after a double-click on its name")
        }
        if let title = step.filesButton {
            guard let button = filesShown?.button, !button.isHiddenOrHasHiddenAncestor, button.title == title else {
                return (false, "the line offers \(filesShown?.button.title ?? "nothing")")
            }
            return (await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY)), "clicked \(title)")
        }
        if let hold = step.filesHold {
            if hold { door.holdListings() } else { door.releaseListings() }
            return (true, "")
        }
        if let ticks = step.filesTicks {
            door.tick(ticks)
            return (true, "")
        }
        if let end = step.filesEnd {
            guard end.count == 2 else { return (false, "filesEnd takes a record and a code") }
            door.endFiles(saying: end[0], code: end[1])
            return (true, "")
        }
        if step.filesDrop == true {
            door.dropFiles()
            return (true, "")
        }
        if let name = step.filesShot {
            return await filesShot(name)
        }
        if step.filesFlood == true {
            return await filesFlood(door)
        }
        return await filesBudgets(door)
    }

    /// The real use: the workspace tab's terminal flooding with an agent's output at panes4's load
    /// (``paneLoad``, 3 s of it) while the files panel beside it is open and the watch brings ten
    /// entries and lists a folder again. Gated: the flood's ours (its longest output wait and the
    /// main thread's longest hold, less the machine's share) at panes4's 100 ms; its drawable wait in
    /// its longest commit gap at ``drawableWaitBound``; a change heard to its row drawn at the
    /// spec's 120 ms ceiling (budget 50). Said: the main thread's longest hold.
    private func filesFlood(_ door: FakeRunnerDoor) async -> (Bool, String) {
        guard let view = pane, filesShown != nil else { return (false, "no workspace terminal with its files panel open") }
        let root = Self.filesRoot, marker = "flood-files-end"
        var names = ["src/", "README.md", "Package.swift"]
        door.setFolder(root, names.map(Self.fileEntry))
        door.setFolder(root + "/src", ["tide.ts"].map(Self.fileEntry))
        guard await waitForState(10, { self.filesShown?.list.numberOfRows == names.count }) else { return (false, "the root never showed: \(filesShown?.words ?? "")") }
        let before = Self.committed(view.terminal)
        view.terminal.resetLatency()
        mainThread.reset()
        let seconds = 3.0
        door.flood(view.name, lines: Self.paneLoad.lines, width: 150, every: Self.paneLoad.period, blocks: Int(seconds / Self.paneLoad.period), then: marker)
        var changed: [Double] = []
        for index in 0 ..< 10 {
            let name = "artifact-\(index).o"
            names.append(name)
            door.setFolder(root, names.map(Self.fileEntry))
            if index == 5 { door.setFolder(root + "/src", ["tide.ts", "quay.ts"].map(Self.fileEntry)) }
            let path = root + "/" + name
            guard await waitForState(5, { self.filesShown?.newDrawn[path] != nil }), let heard = filesShown?.changeHeard[root], let drawn = filesShown?.newDrawn[path] else {
                return (false, "under the flood, \(name) drew no new row: \(filesShown?.rows.map(\.name) ?? [])")
            }
            changed.append(drawn - heard)
            try? await Task.sleep(for: .milliseconds(200))
        }
        guard await waitForState(60, { Self.shows(view, marker) }) else { return (false, "the flood never reached its end") }
        let figures = view.terminal.latency, judged = flood(figures), frames = Self.committed(view.terminal) - before
        let held = max(mainThread.longest - Heartbeat.interval * 1000, 0)
        let change = Percentiles(milliseconds: changed.map { $0 * 1000 }).p95
        let budgets = [
            "the flood's ours <= 100 ms in its \(frames) frames (\(judged.said))": frames > 0 && judged.ours <= 100,
            "its drawable wait in its longest commit gap <= \(Int(Self.drawableWaitBound)) ms (\(Self.ms(figures.longestGapFetching)) ms)": figures.longestGapFetching <= Self.drawableWaitBound,
            "a change heard to its row drawn p95 <= 120 ms ceiling (\(Self.ms(change)) ms, budget 50)": change <= 120,
        ]
        let missed = budgets.filter { !$0.value }.keys.sorted()
        let said = "ours \(Self.ms(judged.ours)) ms, longest gap between commits \(Self.ms((figures.longestBetweenCommits?.length ?? 0) * 1000)) ms, "
            + "drawable wait \(Self.ms(figures.longestGapFetching)) ms, change to screen p95 \(Self.ms(change)) ms, the main thread's longest hold \(Self.ms(held)) ms"
        print("files under a flood: \(said)")
        door.setFolder(root, ["src/", "README.md", "Package.swift"].map(Self.fileEntry))
        return (missed.isEmpty, (missed.isEmpty ? "within every budget" : "missed: \(missed.joined(separator: "; "))") + "; " + said)
    }

    /// Clicks the tree row of `path` on its name, as a person chooses a row, clear of the folder's
    /// disclosure, which a press in its slot opens instead.
    private func clickName(_ path: String, in shown: FilesShown, twice: Bool = false) async -> Bool {
        let list = shown.list
        list.layoutSubtreeIfNeeded()
        guard let index = shown.rows.firstIndex(where: { $0.path.key == path }) else { return false }
        let rect = list.rect(ofRow: index), depth = Double(shown.rows[index].depth)
        let name = RowStyle.tree.margin + RowStyle.tree.leading + depth * RowStyle.indent + 12 + 4 + 8
        let point = NSPoint(x: Double(rect.minX) + name, y: Double(rect.midY))
        guard twice else { return await click(list, at: point) }
        return await clicks(2, on: list, at: point)
    }

    /// `count` clicks at `point` in `view`, each a mouse-down sent through the app with its mouse-up
    /// queued behind it, numbered as a person's double-click numbers them.
    private func clicks(_ count: Int, on view: NSView, at point: NSPoint) async -> Bool {
        guard let window = view.window else { return false }
        let location = view.convert(point, to: nil)
        for number in 1 ... count {
            func event(_ type: NSEvent.EventType) -> NSEvent? {
                NSEvent.mouseEvent(
                    with: type, location: location, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber,
                    context: nil, eventNumber: 0, clickCount: number, pressure: type == .leftMouseDown ? 1 : 0
                )
            }
            guard let down = event(.leftMouseDown), let up = event(.leftMouseUp) else { return false }
            NSApp.postEvent(up, atStart: false)
            NSApp.sendEvent(down)
            await Task.yield()
        }
        return true
    }

    /// How long the drive leaves the menu bar between two ⌃⌘S: AppKit sleeps before a menu item's key
    /// equivalent repeated sooner (NSMENU_IS_THROTTLING_REPEATED_MENU_ITEM_INVOCATIONS, about 50 ms
    /// on round five's run), which no person pressing it once meets.
    static let menuRest = 0.6

    /// The rows scrolled end to end as a fast flick scrolls them: a wheel event of 20 rows a frame,
    /// each frame given its 16 ms to draw before the next, so the main thread's longest hold is the
    /// app's own drawing, not the drive's wheel events piled into one turn.
    private func flick(_ view: NSScrollView, to fraction: Double) async -> (Bool, String) {
        guard let document = view.documentView else { return (false, "the scroll view holds nothing") }
        let room = Double(document.frame.height) - Double(view.contentSize.height)
        guard room > 0 else { return (false, "nothing to scroll") }
        let wanted = room * min(max(fraction, 0), 1), step = 20 * RowStyle.tree.height
        var frames = 0
        while abs(Double(view.contentView.bounds.minY) - wanted) > 0.5 {
            let left = wanted - Double(view.contentView.bounds.minY), before = view.contentView.bounds.minY
            let delta = Int32((left > 0 ? -min(step, left) : min(step, -left)).rounded(left > 0 ? .down : .up))
            guard let wheel = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 1, wheel1: delta, wheel2: 0, wheel3: 0),
                  let event = NSEvent(cgEvent: wheel) else { return (false, "no scroll-wheel event") }
            view.scrollWheel(with: event)
            guard view.contentView.bounds.minY != before else { return (false, "the wheel moved nothing at \(before) of \(room) pt") }
            frames += 1
            try? await Task.sleep(for: .milliseconds(16))
        }
        return (true, "scrolled to \(Int(view.contentView.bounds.minY)) of \(Int(room)) pt in \(frames) frames")
    }

    /// Window shots taken while the 5,000 rows scroll by the wheel, 20 rows a frame, the scroll going
    /// on until each is taken: a row shown without its name (blank while its layer rasterizes)
    /// leaves a band across the tree with no ink taller than the gap between two names. A sample of
    /// the frames shown, not each one.
    private func blankRowsWhileScrolling(_ scroll: NSScrollView) async -> (Bool, String) {
        let window = shell.window, clip = scroll.contentView.convert(scroll.contentView.bounds, to: nil), row = RowStyle.tree.height
        let directory = out.deletingLastPathComponent().appending(path: "blank-rows")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var down = true, steps = 0, said: [String] = [], bands = 0, shots = 0
        for index in 0 ..< 8 {
            let file = directory.appending(path: "\(index).png")
            try? FileManager.default.removeItem(at: file)
            guard let shot = Shot.begin(.window(window.windowNumber), to: file) else { return (false, "no shot could be begun") }
            while !shot.isTaken, steps < 2000 {
                let room = Double(scroll.documentView?.frame.height ?? 0) - Double(scroll.contentSize.height), at = Double(scroll.contentView.bounds.minY)
                if down, at >= room - 0.5 { down = false }
                if !down, at <= 0.5 { down = true }
                guard let wheel = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 1, wheel1: Int32(down ? -20 * row : 20 * row), wheel2: 0, wheel3: 0),
                      let event = NSEvent(cgEvent: wheel) else { return (false, "no scroll-wheel event") }
                scroll.scrollWheel(with: event)
                steps += 1
                try? await Task.sleep(for: .milliseconds(16))
            }
            await shot.taken()
            guard let source = CGImageSourceCreateWithURL(file as CFURL, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return (false, "shot \(index) could not be read") }
            let scale = Double(image.width) / Double(window.frame.width)
            guard let blank = Self.longestBlankBand(image, in: NSRect(
                x: Double(clip.minX) * scale, y: (Double(window.frame.height) - Double(clip.maxY) + row) * scale,
                width: (Double(clip.width) - 16) * scale, height: (Double(clip.height) - 2 * row) * scale
            )) else { return (false, "shot \(index) held no tree") }
            shots += 1
            bands += Int(Double(clip.height) / row) - 2
            if blank < 4 * scale { return (false, "shot \(index) shows no gap between two names where the tree is: the check is not looking at the tree") }
            if blank > 20 * scale { said.append("shot \(index): a band \(Int(blank / scale)) pt tall with no ink") }
        }
        let summary = "\(shots) shots taken while scrolling (\(steps) wheel steps), about \(bands) rows looked at"
        return (said.isEmpty, said.isEmpty ? "\(summary); every row shown had its name" : "\(summary); a row shown without its name: \(said.joined(separator: "; "))")
    }

    /// The tallest run of pixel rows inside `box` (top-left origin, pixels) with no pixel apart from
    /// the box's commonest colour by more than 48 in a channel; nil when `box` falls outside.
    static func longestBlankBand(_ image: CGImage, in box: NSRect) -> Double? {
        let width = image.width, height = image.height
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let data = context.data?.assumingMemoryBound(to: UInt8.self) else { return nil }
        let x0 = max(Int(box.minX), 0), x1 = min(Int(box.maxX), width), y0 = max(Int(box.minY), 0), y1 = min(Int(box.maxY), height)
        guard x1 - x0 > 8, y1 - y0 > 8 else { return nil }
        func pixel(_ x: Int, _ y: Int) -> (Int, Int, Int) {
            let at = (y * width + x) * 4
            return (Int(data[at]), Int(data[at + 1]), Int(data[at + 2]))
        }
        var counts: [Int: Int] = [:]
        for y in stride(from: y0, to: y1, by: 3) {
            for x in stride(from: x0, to: x1, by: 3) {
                let (r, g, b) = pixel(x, y)
                counts[r << 16 | g << 8 | b, default: 0] += 1
            }
        }
        guard let common = counts.max(by: { $0.value < $1.value })?.key else { return nil }
        let ground = (common >> 16, common >> 8 & 0xFF, common & 0xFF)
        var longest = 0, run = 0
        for y in y0 ..< y1 {
            let inked = (x0 ..< x1).contains { x in
                let (r, g, b) = pixel(x, y)
                return max(abs(r - ground.0), abs(g - ground.1), abs(b - ground.2)) > 48
            }
            run = inked ? 0 : run + 1
            longest = max(longest, run)
        }
        return Double(longest)
    }

    /// What the main thread was doing in its longest hold since the heartbeat's reset, as the
    /// sampler saw it: printed, so a hold over its ceiling names its own cause.
    private func tellLongestHold(_ phase: String) {
        guard let gap = mainThread.stalls.longest() else { return }
        mainStacks.told(gap).forEach { print("files, \(phase), the main thread's longest hold: \($0)") }
    }

    /// The window in both looks, then with the workbench and the panel laid out right to left, as a
    /// right-to-left Mac lays them out, in both again.
    private func filesShot(_ name: String) async -> (Bool, String) {
        let window = shell.window.windowNumber
        let (ok, said) = await inBothLooks(name) { file in await self.capture(.window(window), to: file) }
        let bench = workbench
        func direct(_ direction: NSUserInterfaceLayoutDirection) {
            bench.userInterfaceLayoutDirection = direction
            bench.setFrameSize(bench.frame.size)
            guard let panel = filesShown?.scroll.superview else { return }
            for view in [panel] + Self.views(in: panel) {
                view.userInterfaceLayoutDirection = direction
                view.needsLayout = true
                view.needsDisplay = true
            }
        }
        direct(.rightToLeft)
        let (mirrored, saidMirrored) = await inBothLooks(name + "-rtl") { file in await self.capture(.window(window), to: file) }
        direct(.leftToRight)
        return (ok && mirrored, said + "; " + saidMirrored)
    }

    /// The spec's figures, app side, with the door answering at once, each over ten tries (p95):
    /// ⌃⌘S to the first rows drawn; a 5,000-entry folder's whole listing heard to its rows drawn;
    /// the main thread's longest hold scrolling those rows end to end and back by the wheel; a
    /// change heard to the new row drawn with its cue; the main thread's longest hold while 2,000
    /// changes land in a shown folder. Each ceiling gates; each budget is said.
    private func filesBudgets(_ door: FakeRunnerDoor) async -> (Bool, String) {
        /// The main thread's longest hold since the heartbeat's reset: its longest gap less the
        /// 10 ms the heartbeat waits between ticks.
        func held() -> Double {
            max(mainThread.longest - Heartbeat.interval * 1000, 0)
        }
        let root = Self.filesRoot
        let base = ["berths/", "src/", "README.md", "Package.swift"]
        door.setFolder(root, base.map(Self.fileEntry))
        door.setFolder(root + "/berths", Self.berths(5000))
        guard await waitForState(10, { self.filesShown?.list.numberOfRows == base.count }) else { return (false, "the panel never showed the root: \(filesShown?.words ?? "")") }
        var opened: [Double] = [], listed: [Double] = [], changed: [Double] = []
        var toOpen: [Double] = [], toAdmitted: [Double] = [], toListing: [Double] = []
        var worstOpen = 0.0
        for _ in 0 ..< 10 {
            press("s", ["control", "command"])
            guard await waitForState(5, { self.filesShown == nil }) else { return (false, "⌃⌘S did not put the panel away") }
            _ = await waitForState(Self.menuRest) { false }
            mainThread.reset()
            let asked = CACurrentMediaTime()
            press("s", ["control", "command"])
            guard await waitForState(5, { (self.filesShown?.lastDrawn.at ?? 0) > asked && (self.filesShown?.list.numberOfRows ?? 0) > 0 }) else {
                return (false, "⌃⌘S drew no rows: \(filesShown?.words ?? "no panel")")
            }
            opened.append((filesShown?.lastDrawn.at ?? asked) - asked)
            if let last = opened.last, last > worstOpen, last * 1000 > 50 {
                worstOpen = last
                tellLongestHold("⌃⌘S to the first rows, \(Self.ms(last * 1000)) ms")
            }
            if let shown = filesShown, let open = shown.openedAt, let admitted = shown.admittedAt, let listing = shown.listingHeard?.at {
                toOpen.append(open - asked)
                toAdmitted.append(admitted - open)
                toListing.append(listing - admitted)
            }
        }
        let berths = root + "/berths"
        for _ in 0 ..< 10 {
            guard let shown = filesShown, await clickName(berths, in: shown), await waitForState(5, { self.filesShown?.list.chosen == berths }) else {
                return (false, "berths could not be chosen")
            }
            press("arrowRight", [])
            guard await waitForState(10, { (self.filesShown?.list.numberOfRows ?? 0) >= 5000 + base.count }),
                  let heard = filesShown?.listingHeard, heard.path == berths,
                  await waitForState(5, { (self.filesShown?.lastDrawn.at ?? 0) > heard.at })
            else { return (false, "→ on berths drew no 5,000 rows: \(filesShown?.list.numberOfRows ?? 0)") }
            listed.append((filesShown?.lastDrawn.at ?? heard.at) - heard.at)
            press("arrowLeft", [])
            guard await waitForState(5, { self.filesShown?.list.numberOfRows == base.count }) else { return (false, "← did not shut berths") }
        }
        guard let shown = filesShown, await clickName(berths, in: shown) else { return (false, "berths could not be chosen to scroll") }
        press("arrowRight", [])
        guard await waitForState(10, { (self.filesShown?.list.numberOfRows ?? 0) >= 5000 + base.count }), let scroll = filesShown?.scroll else { return (false, "berths did not open to scroll") }
        _ = mainStacks
        var scrolls: [Double] = [], scrollsCPU: [Double] = [], scrollTurns: [Double] = [], wheel: [String] = [], scrolledWell = true, drawsPerEntered: [Double] = [], namesPerDraw: [Double] = []
        let drawsAtStart = filesShown?.draws ?? 0, cpuAtStart = filesShown?.drawCPU.total ?? 0
        for _ in 0 ..< 3 {
            let drawsBefore = filesShown?.draws ?? 0, namesBefore = filesShown?.namesBuilt ?? 0
            mainThread.reset()
            mainTurns.reset()
            let down = await flick(scroll, to: 1), up = await flick(scroll, to: 0)
            scrolls.append(held())
            scrollsCPU.append(mainTurns.longestCPU)
            scrollTurns.append(mainTurns.longest)
            if mainTurns.longestCPU > 16 { tellLongestHold("scrolling the 5,000 rows") }
            scrolledWell = scrolledWell && down.0 && up.0
            wheel += [down.1, up.1]
            let visible = Double(scroll.contentSize.height) / RowStyle.tree.height
            let entered = 2 * Double(filesShown?.list.numberOfRows ?? 0) + visible
            let draws = Double((filesShown?.draws ?? 0) - drawsBefore), names = Double((filesShown?.namesBuilt ?? 0) - namesBefore)
            drawsPerEntered.append(draws / max(entered, 1))
            namesPerDraw.append(names / max(draws, 1))
        }
        let drawn = (filesShown?.draws ?? 0) - drawsAtStart, drawCPU = filesShown?.drawCPU ?? (total: 0, most: 0)
        let perRow = drawn > 0 ? Double(drawCPU.total - cpuAtStart) / Double(drawn) / 1000 : 0
        let (unblank, blankSaid) = await blankRowsWhileScrolling(scroll)
        press("arrowLeft", [])
        _ = await waitForState(5) { self.filesShown?.list.numberOfRows == base.count }
        var names = base
        for index in 0 ..< 10 {
            let name = "tide-\(index).ts"
            names.append(name)
            door.setFolder(root, names.map(Self.fileEntry))
            let path = root + "/" + name
            guard await waitForState(5, { self.filesShown?.newDrawn[path] != nil }), let heard = filesShown?.changeHeard[root], let drawn = filesShown?.newDrawn[path] else {
                return (false, "the change to \(name) drew no new row: \(filesShown?.rows.map(\.name) ?? [])")
            }
            changed.append(drawn - heard)
        }
        var bursts: [Double] = [], burstsCPU: [Double] = [], burstTurns: [Double] = [], landed = true, burstFills = 0, burstMade = 0, burstAllowed = 0
        for round in 0 ..< 3 {
            door.setFolder(root, names.map(Self.fileEntry))
            _ = await waitForState(10) { self.filesShown?.list.numberOfRows == names.count }
            let list = filesShown?.list, fills = list?.fills ?? 0, made = list?.rowsMade ?? 0, allowed = list?.appliedRows ?? 0
            mainThread.reset()
            mainTurns.reset()
            let final = names + (0 ..< 2000).map { "burst-\(round)-\($0).log" }
            DispatchQueue(label: "files.burst").async {
                for count in 0 ... 2000 {
                    door.setFolder(root, (names + (0 ..< count).map { "burst-\(round)-\($0).log" }).map(Self.fileEntry))
                }
            }
            landed = await waitForState(30) { self.filesShown?.list.numberOfRows == final.count } && landed
            bursts.append(held())
            burstsCPU.append(mainTurns.longestCPU)
            burstTurns.append(mainTurns.longest)
            if mainTurns.longestCPU > 16 { tellLongestHold("2,000 changes in a burst") }
            burstFills += (list?.fills ?? 0) - fills
            burstMade += (list?.rowsMade ?? 0) - made
            burstAllowed += (list?.appliedRows ?? 0) - allowed
        }
        let scrolled = scrollsCPU.min() ?? .infinity, bursting = burstsCPU.min() ?? .infinity
        let screenful = Int((Double(filesShown?.scroll.contentSize.height ?? 0) / RowStyle.tree.height).rounded(.up)) + 1
        let structure = [
            "the bursts made \(burstMade) row views for \(burstAllowed) rows inserted or changed and \(burstFills) fills of a screenful (\(screenful)) each (no more allowed)":
                burstMade <= burstAllowed + burstFills * screenful,
            "each scroll drew at most \(String(format: "%.2f", drawsPerEntered.max() ?? 0)) rows per row that entered view (at most 1.1 allowed)": (drawsPerEntered.max() ?? .infinity) <= 1.1,
            "each scroll built at most \(String(format: "%.2f", namesPerDraw.max() ?? 0)) names per row drawn (at most 1 allowed)": (namesPerDraw.max() ?? .infinity) <= 1,
        ]
        func tries(_ figures: [Double]) -> String {
            "\(figures.map { Self.ms($0) }) ms (least \(Self.ms(figures.min() ?? 0)), worst \(Self.ms(figures.max() ?? 0)))"
        }
        let frame120 = 1000 / 120.0
        let promotion = "a 120 Hz frame is 8.3 ms: the costliest scroll turn takes \(String(format: "%.1f", scrolled / frame120)) of them, the costliest burst turn \(String(format: "%.1f", bursting / frame120))"
        let rowCost = "a tree row's draw on the main thread \(String(format: "%.1f", perRow)) µs on average and \(String(format: "%.1f", Double(drawCPU.most) / 1000)) µs at most, over \(drawn) rows drawn in the scrolls, 20 rows entering a turn"
        let scrolling = "scrolling, the main thread's own CPU in its most costly turn \(tries(scrollsCPU)), its longest turn on the wall clock \(tries(scrollTurns)), its longest hold by the heartbeat \(tries(scrolls))"
        let burst = "bursts, CPU \(tries(burstsCPU)), longest turn \(tries(burstTurns)), hold \(tries(bursts))"
        print("files, three tries each: \(scrolling); \(burst); \(promotion); \(rowCost); \(blankSaid); rows taken again from the list's pool \(filesShown?.list.rowsReused ?? 0); "
            + structure.keys.sorted().joined(separator: "; "))
        door.setFolder(root, base.map(Self.fileEntry))
        _ = await waitForState(10) { self.filesShown?.list.numberOfRows == base.count }
        let rows = [
            ("⌃⌘S to the first rows drawn p95", Percentiles(milliseconds: opened.map { $0 * 1000 }).p95, 50.0, 120.0),
            ("a 5,000-entry listing heard to its rows drawn p95", Percentiles(milliseconds: listed.map { $0 * 1000 }).p95, 60, 150),
            ("a change heard to the new row and its cue drawn p95", Percentiles(milliseconds: changed.map { $0 * 1000 }).p95, 50, 120),
            ("scrolling the 5,000 rows end to end and back, the least of three: the main thread's own CPU in its most costly turn", scrolled, 8, 16),
            ("2,000 changes in a burst, the least of three: the main thread's own CPU in its most costly turn", bursting, 8, 16),
        ]
        let missed = rows.filter { $0.1 > $0.3 }.map { "\($0.0) \(Self.ms($0.1)) ms over its \(Int($0.3)) ms ceiling" } + structure.filter { !$0.value }.map(\.key).sorted() + (unblank ? [] : [blankSaid])
        let over = rows.filter { $0.1 > $0.2 && $0.1 <= $0.3 }.map { "\($0.0) over its \(Int($0.2)) ms budget" }
        let said = rows.map { "\($0.0) \(Self.ms($0.1)) ms (budget \(Int($0.2)), ceiling \(Int($0.3)))" }.joined(separator: "; ")
        func p95(_ samples: [Double]) -> String {
            Self.ms(Percentiles(milliseconds: samples.map { $0 * 1000 }).p95)
        }
        print("files, ⌃⌘S to the first rows split p95: the key to the connection asked \(p95(toOpen)) ms, the ticket and connect to admitted \(p95(toAdmitted)) ms, "
            + "admitted to the root's whole listing heard \(p95(toListing)) ms, of \(toOpen.count) tries")
        print("files figures: \(said); the wheel: \(wheel.joined(separator: ", ")); the bursts landed: \(landed)")
        let ok = missed.isEmpty && scrolledWell && landed && unblank
        return (ok, (missed.isEmpty ? "within every ceiling" : "missed: \(missed.joined(separator: "; "))") + (over.isEmpty ? "" : "; \(over.joined(separator: "; "))") + "; " + said)
    }
}
