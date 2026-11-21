import AppKit
import SailControls
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
    static let filesRoot = FileTree.root

    /// An entry as a step spells it: a trailing "/" a folder.
    nonisolated static func fileEntry(_ spelled: String) -> FileEntry {
        let folder = spelled.hasSuffix("/")
        return FileEntry(
            name: folder ? String(spelled.dropLast()) : spelled, kind: .known(folder ? .folder : .file), target: .known(.none), size: folder ? 0 : 1,
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

    func performFiles(_ step: Step) async -> (Bool, String)? {
        let asks = [step.filesFolder, step.filesRefuse, step.filesNew, step.filesClick, step.filesButton, step.filesShot].contains { $0 != nil }
            || step.filesRows != nil || step.filesSays != nil || step.filesHold != nil || step.filesTicks != nil || step.filesEnd != nil
            || step.filesDrop != nil || step.filesBudgets != nil || step.filesFlood != nil
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
            let found = await waitUntil(step.seconds ?? 10) { self.filesShown?.list.numberOfRows == rows }
            return (found, "the panel shows \(filesShown.map { "\($0.list.numberOfRows) rows: \($0.rows.prefix(8).map(\.name))" } ?? "nothing"); its line: \(filesShown?.words ?? "none")")
        }
        if let words = step.filesSays {
            let found = await waitUntil(step.seconds ?? 10) {
                guard let shown = self.filesShown else { return false }
                return words.isEmpty ? shown.words == nil : shown.words?.hasPrefix(words) == true
            }
            return (found, "the line says \(filesShown?.words ?? "nothing")")
        }
        if let name = step.filesNew {
            let found = await waitUntil(step.seconds ?? 10) { self.filesShown?.new.contains { $0.hasSuffix("/" + name) } == true }
            return (found, "marked new: \(filesShown?.new.sorted() ?? [])")
        }
        if let name = step.filesClick {
            guard let shown = filesShown, let id = shown.rows.first(where: { $0.name == name && $0.kind != .note })?.path else { return (false, "no row \(name)") }
            guard await clickRow(id, in: shown.list) else { return (false, "the click on \(name) was not sent") }
            let chosen = await waitUntil(5) { self.filesShown?.list.chosen == id }
            return (chosen, "chosen: \(filesShown?.list.chosen ?? "nothing")")
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
        guard await waitUntil(10, { self.filesShown?.list.numberOfRows == names.count }) else { return (false, "the root never showed: \(filesShown?.words ?? "")") }
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
            guard await waitUntil(5, { self.filesShown?.lastDrawn.path == path && self.filesShown?.lastDrawn.new == true }), let heard = filesShown?.changeHeard else {
                return (false, "under the flood, \(name) drew no new row")
            }
            changed.append((filesShown?.lastDrawn.at ?? heard) - heard)
            try? await Task.sleep(for: .milliseconds(200))
        }
        guard await waitUntil(60, { Self.shows(view, marker) }) else { return (false, "the flood never reached its end") }
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
        guard await waitUntil(10, { self.filesShown?.list.numberOfRows == base.count }) else { return (false, "the panel never showed the root: \(filesShown?.words ?? "")") }
        var opened: [Double] = [], listed: [Double] = [], changed: [Double] = []
        for _ in 0 ..< 10 {
            press("s", ["control", "command"])
            guard await waitUntil(5, { self.filesShown == nil }) else { return (false, "⌃⌘S did not put the panel away") }
            let asked = CACurrentMediaTime()
            press("s", ["control", "command"])
            guard await waitUntil(5, { (self.filesShown?.lastDrawn.at ?? 0) > asked && (self.filesShown?.list.numberOfRows ?? 0) > 0 }) else {
                return (false, "⌃⌘S drew no rows: \(filesShown?.words ?? "no panel")")
            }
            opened.append((filesShown?.lastDrawn.at ?? asked) - asked)
        }
        let berths = root + "/berths"
        for _ in 0 ..< 10 {
            guard let list = filesShown?.list, await clickRow(berths, in: list), await waitUntil(5, { self.filesShown?.list.chosen == berths }) else {
                return (false, "berths could not be chosen")
            }
            press("arrowRight", [])
            guard await waitUntil(10, { (self.filesShown?.list.numberOfRows ?? 0) >= 5000 + base.count }),
                  let heard = filesShown?.listingHeard, heard.path == berths,
                  await waitUntil(5, { (self.filesShown?.lastDrawn.at ?? 0) > heard.at })
            else { return (false, "→ on berths drew no 5,000 rows: \(filesShown?.list.numberOfRows ?? 0)") }
            listed.append((filesShown?.lastDrawn.at ?? heard.at) - heard.at)
            press("arrowLeft", [])
            guard await waitUntil(5, { self.filesShown?.list.numberOfRows == base.count }) else { return (false, "← did not shut berths") }
        }
        guard let list = filesShown?.list, await clickRow(berths, in: list) else { return (false, "berths could not be chosen to scroll") }
        press("arrowRight", [])
        guard await waitUntil(10, { (self.filesShown?.list.numberOfRows ?? 0) >= 5000 + base.count }), let scroll = filesShown?.scroll else { return (false, "berths did not open to scroll") }
        mainThread.reset()
        let down = await turnWheel(over: scroll, to: 1), up = await turnWheel(over: scroll, to: 0)
        let scrolled = held()
        press("arrowLeft", [])
        _ = await waitUntil(5) { self.filesShown?.list.numberOfRows == base.count }
        var names = base
        for index in 0 ..< 10 {
            let name = "tide-\(index).ts"
            names.append(name)
            door.setFolder(root, names.map(Self.fileEntry))
            let path = root + "/" + name
            guard await waitUntil(5, { self.filesShown?.lastDrawn.path == path && self.filesShown?.lastDrawn.new == true }), let heard = filesShown?.changeHeard else {
                return (false, "the change to \(name) drew no new row: \(filesShown?.rows.map(\.name) ?? [])")
            }
            changed.append((filesShown?.lastDrawn.at ?? heard) - heard)
        }
        mainThread.reset()
        let burst = DispatchQueue(label: "files.burst")
        let final = names + (0 ..< 2000).map { "burst-\($0).log" }
        burst.async {
            for count in 0 ... 2000 {
                door.setFolder(root, (names + (0 ..< count).map { "burst-\($0).log" }).map(Self.fileEntry))
            }
        }
        let landed = await waitUntil(30) { self.filesShown?.list.numberOfRows == final.count }
        let bursting = held()
        door.setFolder(root, base.map(Self.fileEntry))
        _ = await waitUntil(10) { self.filesShown?.list.numberOfRows == base.count }
        let rows = [
            ("⌃⌘S to the first rows drawn p95", Percentiles(milliseconds: opened.map { $0 * 1000 }).p95, 50.0, 120.0),
            ("a 5,000-entry listing heard to its rows drawn p95", Percentiles(milliseconds: listed.map { $0 * 1000 }).p95, 60, 150),
            ("scrolling the 5,000 rows end to end and back: the main thread's longest hold", scrolled, 8, 16),
            ("a change heard to the new row and its cue drawn p95", Percentiles(milliseconds: changed.map { $0 * 1000 }).p95, 50, 120),
            ("2,000 changes in a burst: the main thread's longest hold", bursting, 8, 16),
        ]
        let missed = rows.filter { $0.1 > $0.3 }.map { "\($0.0) \(Self.ms($0.1)) ms over its \(Int($0.3)) ms ceiling" }
        let over = rows.filter { $0.1 > $0.2 && $0.1 <= $0.3 }.map { "\($0.0) over its \(Int($0.2)) ms budget" }
        let said = rows.map { "\($0.0) \(Self.ms($0.1)) ms (budget \(Int($0.2)), ceiling \(Int($0.3)))" }.joined(separator: "; ")
        print("files figures: \(said); the wheel: \(down.1), \(up.1); the burst landed: \(landed)")
        let ok = missed.isEmpty && down.0 && up.0 && landed
        return (ok, (missed.isEmpty ? "within every ceiling" : "missed: \(missed.joined(separator: "; "))") + (over.isEmpty ? "" : "; \(over.joined(separator: "; "))") + "; " + said)
    }
}
