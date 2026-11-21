import AppKit
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
        if let done = await performFile(step) { return done }
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

    /// The file pane's steps: an agent's write, opening from the tree, what it shows, typing, the
    /// one line, Compare and the sheet.
    private func performFile(_ step: Step) async -> (Bool, String)? {
        let pane = { self.workbench.filePaneShown }
        if let write = step.fileWrite, write.count == 2, let door = GoldenDesk.door {
            door.setFile(Self.filesPath(write[0]), Array(write[1].utf8))
            return (true, "\(write[0]) now holds \(write[1].utf8.count) bytes")
        }
        if let name = step.fileOpen {
            guard workbench.openFile(at: Self.filesPath(name)) else { return (false, "no file row \(name) to open") }
            let opened = await waitForState(step.seconds ?? 10) { pane() != nil }
            return (opened, "the file pane: \(pane().map { "\($0.text.utf8.count) bytes" } ?? "none")")
        }
        if let text = step.fileShows {
            let shown = await waitForState(step.seconds ?? 10) { pane()?.text == text && pane()?.editable == true }
            return (shown, "the file pane shows \(pane()?.text.debugDescription ?? "nothing")\(pane()?.editable == true ? "" : ", not yet typed in")")
        }
        if let typed = step.fileType {
            guard let editor = pane()?.editor, editor.isEditable else { return (false, "no file pane to type in") }
            shell.window.makeFirstResponder(editor)
            let end = (editor.string as NSString).length
            editor.insertText(typed, replacementRange: NSRange(location: end, length: 0))
            return (true, "typed \(typed.debugDescription)")
        }
        if let words = step.fileNote {
            let said = await waitForState(step.seconds ?? 10) { (pane()?.note ?? "") == words }
            return (said, "the file pane's line says \(pane()?.note ?? "nothing")")
        }
        if let unsaved = step.fileUnsaved {
            let found = await waitForState(step.seconds ?? 10) { pane()?.clean == !unsaved }
            return (found, "the file pane is \(pane()?.clean == true ? "clean" : "unsaved")")
        }
        if let offers = step.fileCompare {
            guard workbench.compareFile() else { return (false, "no Compare to press: \(pane()?.note ?? "no line")") }
            let up = await waitForState(step.seconds ?? 5) { self.workbench.sheetOffers == offers }
            return (up, "the sheet offers \(workbench.sheetOffers)")
        }
        if let title = step.fileSheet {
            let pressed = workbench.sheetUp
            guard workbench.pressSheet(title) else { return (false, "the sheet offers no \(title): \(workbench.sheetOffers)") }
            let gone = await waitForState(step.seconds ?? 5) { self.workbench.sheetUp !== pressed }
            return (gone, "pressed \(title)")
        }
        if let heading = step.fileSheetSays {
            let says = await waitForState(step.seconds ?? 5) { self.workbench.sheetHeading == heading }
            return (says, "the sheet says \(workbench.sheetHeading ?? "nothing, none is up")")
        }
        if let text = step.fileTheirs {
            let held = await waitForState(step.seconds ?? 10) { pane()?.theirs == text }
            return (held, "the file pane holds for Compare \(pane()?.theirs?.debugDescription ?? "nothing")")
        }
        if let holds = step.fileHolds, holds.count == 2, let door = GoldenDesk.door {
            let path = Self.filesPath(holds[0]), wanted = Array(holds[1].utf8)
            let held = await waitForState(step.seconds ?? 10) { door.file(path) == wanted }
            return (held, "\(holds[0]) holds \(door.file(path).map { String(decoding: $0, as: UTF8.self).debugDescription } ?? "nothing")")
        }
        if step.fileSaveWhileLoading == true, let door = GoldenDesk.door { return await fileSaveWhileLoading(door) }
        if step.fileSave == true {
            guard workbench.saveFile() else { return (false, "no file pane to save") }
            return (true, "⌘S")
        }
        if let offers = step.fileSheetUp {
            let up = await waitForState(step.seconds ?? 5) { self.workbench.sheetOffers == offers }
            return (up, "the sheet offers \(workbench.sheetOffers)")
        }
        if let refusal = step.fileRefuseSave, refusal.count == 2, let kind = FileRefusal(rawValue: refusal[0]), let door = GoldenDesk.door {
            door.refuseNextSave(kind, saying: refusal[1])
            return (true, "the next save is refused \(refusal[0])")
        }
        if let name = step.fileRemove, let door = GoldenDesk.door {
            door.removeFile(Self.filesPath(name))
            return (true, "\(name) deleted on the workspace")
        }
        if step.fileLookSwitch == true { return await fileLookSwitch() }
        if step.fileBudgets == true, let door = GoldenDesk.door { return await fileBudgets(door) }
        if step.fileClose == true {
            guard workbench.closeFile() else { return (false, "no file pane to close") }
            let gone = await waitForState(step.seconds ?? 5) { pane() == nil }
            return (gone, gone ? "the file pane closed" : "the file pane stayed: \(pane()?.clean == false ? "unsaved" : "clean")")
        }
        return nil
    }

    /// B1: a 4 MB file opened, and ⌘S pressed while the view still takes it a slice a turn: the
    /// save refuses in words, nothing goes on the wire and the workspace's file stays whole; once
    /// in, it is typed in, still as it was. The pane closed after, the root as it was.
    private func fileSaveWhileLoading(_ door: FakeRunnerDoor) async -> (Bool, String) {
        let pane = { self.workbench.filePaneShown }, name = "slow.ts", path = Self.filesPath(name)
        let line = "export const tide = \"" + String(repeating: "~", count: 90) + "\";\n"
        let slow = Array(String(repeating: line, count: 4 * 1024 * 1024 / line.utf8.count).utf8)
        door.setFolder(Self.filesRoot, ["slow.ts", "src/", "README.md", "Package.swift"].map(Self.fileEntry))
        door.setFile(path, slow)
        defer { door.setFolder(Self.filesRoot, ["src/", "README.md", "Package.swift"].map(Self.fileEntry)) }
        guard await waitForState(10, { self.filesShown?.rows.contains { $0.name == name } == true }) else { return (false, "the root never listed \(name)") }
        let writes = { door.filesReceived.filter { if case .filesWrite = $0 { true } else { false } }.count }, before = writes()
        guard workbench.openFile(at: path) else { return (false, "no row \(name) to open") }
        guard await waitForState(10, { (pane()?.text.isEmpty == false) && pane()?.editable == false }) else {
            return (false, "\(name) was never seen loading: \(pane().map { "\($0.text.utf8.count) bytes, editable \($0.editable)" } ?? "no pane")")
        }
        let shownWhenSaved = pane()?.text.utf8.count ?? 0
        guard workbench.saveFile() else { return (false, "no file pane to save") }
        let words = "\(name) is still loading. Nothing was saved."
        let refused = pane()?.note == words
        guard await waitForState(10, { pane()?.editable == true }) else { return (false, "\(name) was never whole") }
        let whole = pane()?.text.utf8.count ?? 0
        guard await waitForState(1, { writes() > before }) == false else { return (false, "⌘S while loading put a write on the wire") }
        let kept = door.file(path) == slow
        guard workbench.closeFile(), await waitForState(5, { pane() == nil }) else { return (false, "\(name) did not close") }
        let said = "⌘S with \(shownWhenSaved) of \(slow.count) bytes in the view: the line said \(refused ? words.debugDescription : "something else"); whole after: \(whole) bytes; the workspace's file \(kept ? "unchanged" : "changed")"
        return (refused && kept && whole == slow.count, said)
    }

    /// The look switched to dark and back with the file open beside the terminal: nothing over the
    /// terminal against ``OverTerminals``' rule before or after either switch, and no bitmap given to
    /// the large layers over it meanwhile.
    private func fileLookSwitch() async -> (Bool, String) {
        guard let (terminal, _) = workbench.fileAndTerminal, let root = shell.window.contentView?.superview else { return (false, "no file open beside a terminal") }
        var broken = OverTerminals.misses(over: terminal.terminal, of: terminal, in: root).map { "before: \($0)" }
        let painting = PaintWatch()
        painting.watch(OverTerminals.largeLayers(over: terminal.terminal, of: terminal, in: root))
        let start = NSApp.appearance
        for (name, look) in [("dark", NSAppearance.Name.darkAqua), ("light", .aqua)] {
            NSApp.appearance = NSAppearance(named: look)
            _ = await lookDrawn()
            broken += OverTerminals.misses(over: terminal.terminal, of: terminal, in: root).map { "after \(name): \($0)" }
        }
        NSApp.appearance = start
        painting.stop()
        if painting.paints > 0 { broken.append("\(painting.paints) bitmaps given to what lies over the terminal") }
        return (broken.isEmpty, broken.isEmpty ? "nothing over the terminal, nothing painted over it, through dark and light" : broken.joined(separator: "; "))
    }

    /// The spec's figures for the file pane, app side, the door answering at once: a 5 MB file to its
    /// first screen drawn and to typing possible, the main thread's costliest turn while it loads, a
    /// key to its glyph drawn; a clean pane's change to its new text drawn; ⌘S of 100 KB to the dot
    /// gone. Each over its tries, p95, gated at its ceiling, its budget said.
    private func fileBudgets(_ door: FakeRunnerDoor) async -> (Bool, String) {
        let line = "export const tide = { at: 42, height: 1.25, rounding: \"minutes\" };\n"
        let big = Array(String(repeating: line, count: 5_000_000 / line.utf8.count).utf8)
        let hundred = Array(String(repeating: line, count: 100_000 / line.utf8.count).utf8)
        door.setFolder(Self.filesRoot, ["big.ts", "hundred.ts", "src/", "README.md", "Package.swift"].map(Self.fileEntry))
        door.setFile(Self.filesPath("big.ts"), big)
        door.setFile(Self.filesPath("hundred.ts"), hundred)
        guard await waitForState(10, { self.filesShown?.list.numberOfRows == 5 }) else { return (false, "the root never listed big.ts") }
        let pane = { self.workbench.filePaneShown }
        func closed() async -> Bool {
            if pane() != nil { _ = workbench.closeFile() }
            return await waitForState(5) { pane() == nil }
        }
        var first: [Double] = [], typing: [Double] = [], holds: [Double] = [], keys: [Double] = [], changes: [Double] = [], saves: [Double] = []
        _ = mainStacks
        var loadingPhases = "", keyPhases = ""
        func phases() -> String {
            PanePhases.phases.sorted { $0.value.total > $1.value.total }.map { "\($0.key) \(Self.ms($0.value.total * 1000)) ms in all, \(Self.ms($0.value.longest * 1000)) ms at most" }.joined(separator: ", ")
        }
        for _ in 0 ..< 3 {
            guard await closed() else { return (false, "the file pane would not close: unsaved") }
            mainTurns.reset()
            mainThread.reset()
            PanePhases.resetPhases()
            let opened = CACurrentMediaTime()
            guard workbench.openFile(at: Self.filesPath("big.ts")) else { return (false, "no row big.ts") }
            guard await waitForState(10, { (pane()?.editor.textStorage?.length ?? 0) > 0 }), let editor = pane()?.editor else { return (false, "big.ts showed nothing") }
            editor.displayIfNeeded()
            first.append(CACurrentMediaTime() - opened)
            guard await waitForState(10, { pane()?.editable == true }) else { return (false, "big.ts was never whole") }
            typing.append(CACurrentMediaTime() - opened)
            holds.append(mainTurns.longestCPU / 1000)
            if mainTurns.longestCPU > 16 { tellLongestHold("loading a 5 MB file") }
            loadingPhases = phases()
            shell.window.makeFirstResponder(editor)
            PanePhases.resetPhases()
            mainThread.reset()
            let key = CACurrentMediaTime()
            editor.insertText("x", replacementRange: NSRange(location: 0, length: 0))
            PanePhases.timed("view: displayed after the key") { editor.displayIfNeeded() }
            keys.append(CACurrentMediaTime() - key)
            if (CACurrentMediaTime() - key) * 1000 > 16 { tellLongestHold("a key in a 5 MB file") }
            keyPhases = phases()
            editor.undoManager?.undo()
        }
        guard await closed(), workbench.openFile(at: Self.filesPath("hundred.ts")), await waitForState(10, { pane()?.editable == true }) else { return (false, "hundred.ts never opened") }
        for index in 0 ..< 5 {
            let changed = hundred + Array("// change \(index)\n".utf8)
            let heard = CACurrentMediaTime()
            door.setFile(Self.filesPath("hundred.ts"), changed)
            let wanted = String(decoding: changed, as: UTF8.self)
            guard await waitForState(10, { pane()?.text == wanted }) else { return (false, "a change to a clean pane was not shown") }
            pane()?.editor.displayIfNeeded()
            changes.append(CACurrentMediaTime() - heard)
        }
        for index in 0 ..< 5 {
            guard let editor = pane()?.editor else { break }
            editor.insertText("// saved \(index)\n", replacementRange: NSRange(location: 0, length: 0))
            let saved = CACurrentMediaTime()
            _ = workbench.saveFile()
            guard await waitForState(10, { pane()?.clean == true }) else { return (false, "a save of hundred.ts never cleared the dot: \(pane()?.note ?? "")") }
            saves.append(CACurrentMediaTime() - saved)
        }
        _ = await closed()
        door.setFolder(Self.filesRoot, ["src/", "README.md", "Package.swift"].map(Self.fileEntry))
        func p95(_ seconds: [Double]) -> Double {
            Percentiles(milliseconds: seconds.map { $0 * 1000 }).p95
        }
        let rows: [(String, Double, Double, Double)] = [
            ("a 5 MB file chosen to its first screen drawn p95", p95(first), 60, 150),
            ("the same file to typing possible p95", p95(typing), 400, 1000),
            // The costliest load turn is CoreText shaping glyphs and the layer's draw, not the 32 KiB
            // slice appended (1.5 ms at most): halving the slice did not lower its p95 (#525).
            ("loading it: the main thread's own CPU in its costliest turn, the least of three", (holds.min() ?? .infinity) * 1000, 8, 16),
            ("a key typed in a 5 MB file to its glyph drawn, the least of three", (keys.min() ?? .infinity) * 1000, 8, 16),
            ("a clean pane's change to the new text drawn p95", p95(changes), 80, 200),
            ("⌘S of 100 KB to the dot gone p95", p95(saves), 40, 100),
        ]
        let missed = rows.filter { $0.1 > $0.3 }.map { "\($0.0) \(Self.ms($0.1)) ms over its \(Int($0.3)) ms ceiling" }
        let said = rows.map { "\($0.0) \(Self.ms($0.1)) ms (budget \(Int($0.2)), ceiling \(Int($0.3)))" }.joined(separator: "; ")
        print("file figures: \(said); loading p95 \(Self.ms(p95(holds))) ms and a key p95 \(Self.ms(p95(keys))) ms over three tries each")
        print("file figures, where the last load's time went: \(loadingPhases)")
        print("file figures, where the last key's time went: \(keyPhases)")
        return (missed.isEmpty, (missed.isEmpty ? "within every ceiling; " : "missed: \(missed.joined(separator: "; ")); ") + said)
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
        var scrolls: [Double] = [], scrollsCPU: [Double] = [], scrollTurns: [Double] = [], scrollsBeforeCommit: [Double] = [], wheel: [String] = [], scrolledWell = true, drawsPerEntered: [Double] = [], namesPerDraw: [Double] = []
        let drawsAtStart = filesShown?.draws ?? 0, cpuAtStart = filesShown?.drawCPU.total ?? 0, madeAtStart = filesShown?.list.madeCPU ?? (rows: 0, nanoseconds: 0)
        let cellsAtStart = (made: filesShown?.list.rowsMade ?? 0, reused: filesShown?.list.rowsReused ?? 0)
        for _ in 0 ..< 3 {
            let drawsBefore = filesShown?.draws ?? 0, namesBefore = filesShown?.namesBuilt ?? 0
            mainThread.reset()
            mainTurns.reset()
            let down = await flick(scroll, to: 1), up = await flick(scroll, to: 0)
            scrolls.append(held())
            scrollsCPU.append(mainTurns.longestCPU)
            scrollTurns.append(mainTurns.longest)
            scrollsBeforeCommit.append(mainTurns.costliestBeforeCommit)
            if max(mainTurns.longestCPU, mainTurns.longest) > 24 { tellLongestHold("scrolling the 5,000 rows") }
            scrolledWell = scrolledWell && down.0 && up.0
            wheel += [down.1, up.1]
            let visible = Double(scroll.contentSize.height) / RowStyle.tree.height
            let entered = 2 * Double(filesShown?.list.numberOfRows ?? 0) + visible
            let draws = Double((filesShown?.draws ?? 0) - drawsBefore), names = Double((filesShown?.namesBuilt ?? 0) - namesBefore)
            drawsPerEntered.append(draws / max(entered, 1))
            namesPerDraw.append(names / max(draws, 1))
        }
        let drawn = (filesShown?.draws ?? 0) - drawsAtStart, drawCPU = filesShown?.drawCPU ?? (total: 0, most: 0)
        let madeNew = ((filesShown?.list.rowsMade ?? 0) - cellsAtStart.made) - ((filesShown?.list.rowsReused ?? 0) - cellsAtStart.reused)
        let perRow = drawn > 0 ? Double(drawCPU.total - cpuAtStart) / Double(drawn) / 1000 : 0
        let made = filesShown?.list.madeCPU ?? (rows: 0, nanoseconds: 0), madeRows = made.rows - madeAtStart.rows
        let perMade = madeRows > 0 ? Double(made.nanoseconds - madeAtStart.nanoseconds) / Double(madeRows) / 1000 : 0
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
            if max(mainTurns.longestCPU, mainTurns.longest) > 24 { tellLongestHold("2,000 changes in a burst") }
            burstFills += (list?.fills ?? 0) - fills
            burstMade += (list?.rowsMade ?? 0) - made
            burstAllowed += (list?.appliedRows ?? 0) - allowed
        }
        let scrolled = scrollsCPU.min() ?? .infinity, bursting = burstsCPU.min() ?? .infinity
        let scrolledWall = scrollTurns.min() ?? .infinity, burstingWall = burstTurns.min() ?? .infinity
        let screenful = Int((Double(filesShown?.scroll.contentSize.height ?? 0) / RowStyle.tree.height).rounded(.up)) + 1
        let structure = [
            "the scrolls made \(madeNew) rows new, the rest taken from the list's pool (a screenful, \(screenful), and 4 more allowed)": madeNew <= screenful + 4,
            "the bursts made \(burstMade) row views for \(burstAllowed) rows inserted or changed and \(burstFills) fills of a screenful (\(screenful)) each (no more allowed)":
                burstMade <= burstAllowed + burstFills * screenful,
            "each scroll drew at most \(String(format: "%.2f", drawsPerEntered.max() ?? 0)) rows per row that entered view (at most 1.1 allowed)": (drawsPerEntered.max() ?? .infinity) <= 1.1,
            "each scroll built at most \(String(format: "%.2f", namesPerDraw.max() ?? 0)) names per row drawn (at most 1 allowed)": (namesPerDraw.max() ?? .infinity) <= 1,
        ]
        func tries(_ figures: [Double]) -> String {
            "\(figures.map { Self.ms($0) }) ms (least \(Self.ms(figures.min() ?? 0)), worst \(Self.ms(figures.max() ?? 0)))"
        }
        let frame120 = 1000 / 120.0
        let promotion = "budgets 24 ms, 16 ms and a 120 Hz frame's 8.3 ms: the costliest scroll turn takes \(String(format: "%.1f", scrolled / frame120)) of them, the costliest burst turn \(String(format: "%.1f", bursting / frame120))"
        let rowCost = "a tree row's draw on the main thread \(String(format: "%.1f", perRow)) µs on average and \(String(format: "%.1f", Double(drawCPU.most) / 1000)) µs at most, over \(drawn) rows drawn in the scrolls, 20 rows entering a turn"
        let scrolling = "scrolling, the main thread's own CPU in its most costly turn \(tries(scrollsCPU)), its longest turn on the wall clock \(tries(scrollTurns)), its longest hold by the heartbeat \(tries(scrolls))"
        let burst = "bursts, CPU \(tries(burstsCPU)), longest turn \(tries(burstTurns)), hold \(tries(bursts))"
        let commits = zip(scrollsCPU, scrollsBeforeCommit).map { "\(Self.ms($1)) before its commit and \(Self.ms($0 - $1)) in it" }
        let split = "each try's costliest scroll turn split where Core Animation's commit begins: \(commits.joined(separator: ", ")) ms; "
            + "a row made or made over by the list (its row view and cell, configure included) \(String(format: "%.1f", perMade)) µs on average, over \(madeRows) in the scrolls"
        print("files, three tries each: \(scrolling); \(burst); \(promotion); \(rowCost); \(split); rows taken again from the list's pool \(filesShown?.list.rowsReused ?? 0); "
            + structure.keys.sorted().joined(separator: "; "))
        door.setFolder(root, base.map(Self.fileEntry))
        _ = await waitForState(10) { self.filesShown?.list.numberOfRows == base.count }
        let rows = [
            ("⌃⌘S to the first rows drawn p95", Percentiles(milliseconds: opened.map { $0 * 1000 }).p95, 50.0, 120.0),
            ("a 5,000-entry listing heard to its rows drawn p95", Percentiles(milliseconds: listed.map { $0 * 1000 }).p95, 60, 150),
            ("a change heard to the new row and its cue drawn p95", Percentiles(milliseconds: changed.map { $0 * 1000 }).p95, 50, 120),
            ("scrolling the 5,000 rows end to end and back, the least of three: the main thread's own CPU in its most costly turn", scrolled, 24, 40),
            ("2,000 changes in a burst, the least of three: the main thread's own CPU in its most costly turn", bursting, 24, 40),
            ("scrolling the 5,000 rows end to end and back, the least of three: the main thread's longest turn on the wall clock", scrolledWall, 24, 40),
            ("2,000 changes in a burst, the least of three: the main thread's longest turn on the wall clock", burstingWall, 24, 40),
        ]
        let missed = rows.filter { $0.1 > $0.3 }.map { "\($0.0) \(Self.ms($0.1)) ms over its \(Int($0.3)) ms ceiling" } + structure.filter { !$0.value }.map(\.key).sorted()
        let over = rows.filter { $0.1 > $0.2 && $0.1 <= $0.3 }.map { "\($0.0) over its \(Int($0.2)) ms budget" }
        let said = rows.map { "\($0.0) \(Self.ms($0.1)) ms (budget \(Int($0.2)), ceiling \(Int($0.3)))" }.joined(separator: "; ")
        func p95(_ samples: [Double]) -> String {
            Self.ms(Percentiles(milliseconds: samples.map { $0 * 1000 }).p95)
        }
        print("files, ⌃⌘S to the first rows split p95: the key to the connection asked \(p95(toOpen)) ms, the ticket and connect to admitted \(p95(toAdmitted)) ms, "
            + "admitted to the root's whole listing heard \(p95(toListing)) ms, of \(toOpen.count) tries")
        print("files figures: \(said); the wheel: \(wheel.joined(separator: ", ")); the bursts landed: \(landed)")
        let ok = missed.isEmpty && scrolledWell && landed
        return (ok, (missed.isEmpty ? "within every ceiling" : "missed: \(missed.joined(separator: "; "))") + (over.isEmpty ? "" : "; \(over.joined(separator: "; "))") + "; " + said)
    }
}
