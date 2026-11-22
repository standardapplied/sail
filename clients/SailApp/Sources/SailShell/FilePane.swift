import AppKit
import SailControls
import SailFiles
import SailLook
import SailTerminalWire

/// A workspace's file, open beside its terminals: typed in at once once whole (no mode), written
/// only by ⌘S, following the file while clean and never replacing typed text by itself (the spec
/// `sail-app-reads-a-file-and-opens-it-at-a-line-from-the-terminal`). Its title is the path, the
/// folder quiet, with a 6 pt dot while unsaved; under it the one line when the file changed or went
/// underneath; then the text, or the sentence saying why there is none.
@MainActor
final class FilePane: NSView, NSTextViewDelegate, NSTextStorageDelegate {
    static let titleHeight = 30.0, lineHeight = 30.0, points = 12.0, barFor = 10.0, updatedFor = 3.0

    /// Its model, read and acted on only here: outside the pane what it holds is asked by the
    /// accessors below, whether it is unsaved only through ``unsaved``, which decides an edit no
    /// delegate heard first.
    private var file: OpenFile
    let workspace: String
    var look: Look {
        didSet { if look != oldValue { paint() } }
    }

    /// Sends frames on the files connection.
    var send: @MainActor ([Frame]) -> Void = { _ in }
    /// The pane asks to close, the person having answered for its unsaved text.
    var onClose: @MainActor () -> Void = {}
    /// Its text is unsaved, or clean again: the file's tree row carries the dot.
    var onUnsaved: @MainActor (Bool) -> Void = { _ in }
    /// The conflict sheet: the workspace's bytes it shows beside the pane's text, nil for a deleted
    /// file; `again` when Keep Mine met a file newer than the one the last sheet showed.
    var onCompare: @MainActor (_ theirs: OpenFile.Theirs?, _ again: Bool) -> Void = { _, _ in }
    /// Show Terminal, where the pane covers the terminals in a narrow tab: they come forward.
    var onShowTerminal: @MainActor () -> Void = {}
    /// Whether it covers the terminals (a tab too narrow for both): Show Terminal is in its title.
    var covering = false {
        didSet { if covering != oldValue { showTerminal.isHidden = !covering; needsLayout = true } }
    }

    /// The version the watch is to hold the file at moved.
    var onWatched: @MainActor (WatchedPath?) -> Void = { _ in }
    private var shownWatch: WatchedPath?

    let editor: PaneTextView
    var lineNumbers: LineNumbers {
        numbers
    }
    /// Whether the keys were last in its text: so they stay counted while nothing in the window holds
    /// them, until a terminal or the tree takes them.
    private(set) var heldKeysLast = false
    let scroll = NSScrollView()
    private let mono: LabelFont
    private let titleBar = NSView()
    private let folderLabel = NSTextField(labelWithString: "")
    private let nameLabel = NSTextField(labelWithString: "")
    private let dot = NSView()
    private let status = NSTextField(labelWithString: "")
    private let close: SailButton
    private let showTerminal: SailButton
    private let note = NSTextField(labelWithString: "")
    private let compare: SailButton
    private let discard: SailButton
    private let sentence = NSTextField(wrappingLabelWithString: "")
    private let copyPath: SailButton
    private let numbers: LineNumbers
    /// Lines of the text that a reload changed, barred until their time is up.
    private(set) var barred: (lines: IndexSet, until: Double)?
    private(set) var updatedAt: Double?
    /// When the workspace's bytes held for Compare were read: said on the sheet beside them.
    private(set) var theirsRead = Date()
    private var shownTheirs = ""
    /// What a save said (a refusal, not connected): kept until a save or a newer word on the file.
    private var said: String?
    /// The UTF-16 units the view took of the text the model gave it, the keys apart: what every
    /// write is checked against.
    private var taken = 0
    /// The slices made of the arriving text, those dealt to the view and those it took; and one to
    /// lose, as a broken slicing would: tests break the slicing so, to prove a view missing text is
    /// never typed in or saved.
    private var slicesMade = 0, slicesDealt = 0, slicesTaken = 0
    var losesSlice: Int?
    private var shownNote = OpenFile.Note.none
    private var applying = false
    var clock: @MainActor () -> Double = { CACurrentMediaTime() }

    init(path: FileName, entry: FileEntry, workspace: String, look: Look, mono: LabelFont) {
        file = OpenFile(path: path, entry: entry)
        self.workspace = workspace
        self.look = look
        self.mono = mono
        editor = PaneTextView(usingTextLayoutManager: true)
        close = SailButton(icon: .close, label: "Close", size: .small, look: look, target: nil, action: nil)
        showTerminal = SailButton("Show Terminal", kind: .quiet, size: .small, look: look, target: nil, action: nil)
        compare = SailButton("Compare", kind: .quiet, size: .small, look: look, target: nil, action: nil)
        discard = SailButton("Discard Mine", kind: .quiet, size: .small, look: look, target: nil, action: nil)
        copyPath = SailButton("Copy Path", kind: .secondary, size: .small, look: look, target: nil, action: nil)
        numbers = LineNumbers(scrollView: scroll, orientation: .verticalRuler)
        super.init(frame: .zero)
        build()
        paint()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// Mirrored right to left, its title and line laid out again the way they now read.
    override var userInterfaceLayoutDirection: NSUserInterfaceLayoutDirection {
        didSet { if userInterfaceLayoutDirection != oldValue { needsLayout = true } }
    }

    private func build() {
        folderLabel.lineBreakMode = .byTruncatingHead
        nameLabel.lineBreakMode = .byTruncatingMiddle
        let path = file.path.shown, cut = path.lastIndex(of: "/").map { path.index(after: $0) } ?? path.startIndex
        folderLabel.stringValue = String(path[..<cut]).replacingOccurrences(of: "/home/dev/workspace/", with: "")
        nameLabel.stringValue = file.name
        dot.wantsLayer = true
        dot.layer?.cornerRadius = 3
        dot.isHidden = true
        dot.setAccessibilityElement(true)
        dot.setAccessibilityLabel("Unsaved")
        for view in [folderLabel, nameLabel, dot, status, showTerminal, close] as [NSView] { titleBar.addSubview(view) }
        showTerminal.isHidden = true
        showTerminal.target = self
        showTerminal.action = #selector(showTerminalPressed)
        close.target = self
        close.action = #selector(closePressed)
        compare.target = self
        compare.action = #selector(comparePressed)
        discard.target = self
        discard.action = #selector(discardPressed)
        copyPath.target = self
        copyPath.action = #selector(copyPathPressed)
        editor.isRichText = false
        editor.allowsUndo = true
        editor.isEditable = false
        editor.usesFindBar = true
        editor.isIncrementalSearchingEnabled = true
        editor.isAutomaticQuoteSubstitutionEnabled = false
        editor.isAutomaticDashSubstitutionEnabled = false
        editor.isAutomaticTextReplacementEnabled = false
        editor.isAutomaticSpellingCorrectionEnabled = false
        editor.isContinuousSpellCheckingEnabled = false
        editor.smartInsertDeleteEnabled = false
        editor.font = mono.font(Self.points, .regular) as NSFont
        editor.textContainerInset = NSSize(width: 4, height: 6)
        editor.isHorizontallyResizable = false
        editor.autoresizingMask = [.width]
        editor.delegate = self
        editor.textStorage?.delegate = self
        editor.onKeys = { [weak self] in self?.heldKeysLast = true }
        scroll.documentView = editor
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        scroll.verticalRulerView = numbers
        scroll.hasVerticalRuler = true
        scroll.rulersVisible = true
        numbers.editor = editor
        numbers.font = mono.font(11, .regular) as NSFont
        for view in [titleBar, note, compare, discard, scroll, sentence, copyPath] as [NSView] { addSubview(view) }
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
        setAccessibilityLabel(file.name)
        show()
    }

    // MARK: The connection

    /// Begins reading the file.
    func open() {
        act { $0.open() }
    }

    /// The one door every answer and action passes to the model by: an edit the text view told no
    /// delegate of (its own undo and redo) is decided first, so nothing reads "clean" before it.
    private func act(_ step: (inout OpenFile) -> OpenFile.Step) {
        settleEdits()
        apply(step(&file))
    }

    /// An edit no `textDidChange` reported, decided now.
    private func settleEdits() {
        if editedUnseen { decideEdit() }
    }

    /// The file it holds, as the tree names it.
    var path: FileName {
        file.path
    }

    var name: String {
        file.name
    }

    /// The workspace's bytes held for Compare, read while the text was unsaved and found different.
    var theirs: OpenFile.Theirs? {
        file.theirs
    }

    /// Whether its text is unsaved, any edit not yet decided decided first.
    var unsaved: Bool {
        settleEdits()
        return !file.clean
    }

    /// The frames this pane's requests are answered by, the watch's word on its file and its ticks:
    /// taken here, answering whether the frame was its own alone.
    func takes(_ frame: Frame) -> Bool {
        switch frame {
        case let .filesChanged(paths):
            if paths.contains(file.path) { act { $0.changed() } }
            return false
        case .filesTick:
            act { $0.tick() }
            return false
        case let .putAck(id, received) where id == upload?.id:
            send(upload?.acked(received) ?? [])
            return true
        default:
            guard let answer = Self.answer(frame) else { return false }
            if case let .written(id, _, _) = answer, id == upload?.id { upload = nil }
            if case let .conflict(id, _) = answer, id == upload?.id { upload = nil }
            if case let .refused(id, _, _) = answer, id == upload?.id { upload = nil }
            act { file in PanePhases.timed("model: an answer taken") { file.receive(answer) } }
            return true
        }
    }

    func admitted() {
        act { $0.admitted() }
    }

    /// The connection ended: the host lets go of an upload with it, and a close question's Save
    /// waiting on an answer is told it will not come.
    func disconnected() {
        settleEdits()
        file.disconnected()
        upload = nil
        abandonLeaving()
        show()
    }

    /// The watch's entry for the file, at the version the pane holds.
    var watched: WatchedPath? {
        file.version.isEmpty ? nil : WatchedPath(path: file.path, version: file.version)
    }

    /// The save on the wire, while its bytes are being sent.
    private var upload: FileUpload?

    /// What the model asks, as frames: a write becomes its upload's first frames; a save given up
    /// ends its upload on the wire.
    private func frames(_ asks: [OpenFile.Ask]) -> [Frame] {
        asks.flatMap { ask -> [Frame] in
            switch ask {
            case let .read(id, path): return [.filesRead(id: id, path: path, offset: 0, length: OpenFile.limit + 1)]
            case let .cancel(id): return [.filesCancel(id: id)]
            case let .cancelSave(id):
                if upload?.id == id { upload = nil }
                return [.putCancel(id: id)]
            case let .write(id, path, base, seen, bytes):
                var upload = FileUpload(id: id, path: path, base: base, seen: seen, bytes: bytes)
                let first = upload.start()
                self.upload = upload
                return first
            }
        }
    }

    static func answer(_ frame: Frame) -> OpenFile.Answer? {
        switch frame {
        case let .filesData(id, version, bytes, eof, whole, digest): .data(id: id, version: version, bytes: bytes, eof: eof, whole: whole, digest: digest)
        case let .filesRefused(id, kind, message): .refused(id: id, kind: kind, message: message)
        case let .filesWritten(id, digest, version): .written(id: id, digest: digest, version: version)
        case let .filesConflict(id, digest): .conflict(id: id, digest: digest)
        default: nil
        }
    }

    // MARK: What the person does

    /// ⌘S: the checked save; not connected, it says so and writes nothing; under way, it is saved
    /// again once that one is answered.
    @objc func saveDocument(_: Any?) {
        guard let text = textToWrite() else { return }
        act { [taken] in $0.save(text, taken: taken) }
        if file.clean { finishLeaving() }
    }

    /// The view's text to write, or nil with the reason said: half of a character (a lone surrogate,
    /// from a malformed paste) would be written as U+FFFD, other bytes than the person sees.
    private func textToWrite() -> String? {
        guard let held = editor.textStorage?.mutableString, let line = Self.loneSurrogateLine(held) else { return editor.string }
        said = "Line \(line) holds half of a character, which can't be saved as text. Nothing was saved."
        abandonLeaving()
        show()
        return nil
    }

    /// The 1-based line of the first UTF-16 surrogate in `text` without its other half, if any.
    static func loneSurrogateLine(_ text: NSString) -> Int? {
        let length = text.length, block = 65536
        var buffer = [unichar](repeating: 0, count: block), line = 1, at = 0, high = false
        while at < length {
            let count = min(block, length - at)
            text.getCharacters(&buffer, range: NSRange(location: at, length: count))
            for unit in buffer[..<count] {
                if high {
                    high = false
                    if (0xDC00 ... 0xDFFF).contains(unit) { continue }
                    return line
                }
                if (0xD800 ... 0xDBFF).contains(unit) {
                    high = true
                } else if (0xDC00 ... 0xDFFF).contains(unit) {
                    return line
                } else if unit == 10 {
                    line += 1
                }
            }
            at += count
        }
        return high ? line : nil
    }

    /// Closing: an unsaved pane asks Save, Discard, Cancel first.
    @objc func closePressed() {
        askToLeave(then: { [weak self] in self?.finishClosing() })
    }

    /// Before the pane's text is left (closed, another file opened into it, its tab closed, the app
    /// quit): an unsaved pane asks Save, Discard, Cancel, Save being the checked save. `leave` runs
    /// on Discard, or once the text is saved; `otherwise` on Cancel, and on a save that does not
    /// land (a conflict, a refusal, no connection, the connection lost), which cancels what was
    /// asked (M52).
    func askToLeave(then leave: @escaping @MainActor () -> Void, otherwise: @escaping @MainActor () -> Void = {}) {
        guard unsaved, let window else { return leave() }
        let alert = NSAlert()
        alert.messageText = "Save the changes to \(file.name)?"
        alert.informativeText = "If you don't, what you typed is lost."
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Discard")
        alert.addButton(withTitle: "Cancel")
        alert.buttons[0].keyEquivalent = ""
        alert.beginSheetModal(for: window) { [weak self] answer in
            MainActor.assumeIsolated {
                guard let self else { return }
                switch answer {
                case .alertFirstButtonReturn:
                    self.leaving = (leave, otherwise)
                    self.saveDocument(nil)
                case .alertSecondButtonReturn: leave()
                default: otherwise()
                }
            }
        }
    }

    /// What waits on the save a close question began: left once it is saved, told otherwise if not.
    private var leaving: (leave: @MainActor () -> Void, otherwise: @MainActor () -> Void)?

    private func finishLeaving() {
        guard let leaving else { return }
        self.leaving = nil
        leaving.leave()
    }

    private func abandonLeaving() {
        guard let leaving else { return }
        self.leaving = nil
        leaving.otherwise()
    }

    func finishClosing() {
        send(frames(file.close().asks))
        onClose()
    }

    @objc private func showTerminalPressed() {
        onShowTerminal()
    }

    @objc private func comparePressed() {
        guard let theirs = file.theirs else { return }
        readTime(of: theirs)
        onCompare(theirs, false)
    }

    @objc func discardPressed() {
        act { $0.discardMine() }
    }

    /// Keep Theirs: the workspace's bytes the sheet showed.
    func keepTheirs(_ shown: OpenFile.Theirs) {
        act { $0.keepTheirs(shown) }
    }

    /// Keep Mine: the pane's text, against the version the sheet showed.
    func keepMine(against shown: OpenFile.Theirs) {
        guard let text = textToWrite() else { return }
        act { [taken] in $0.keepMine(text, taken: taken, shown: shown) }
    }

    func saveAnyway() {
        guard let text = textToWrite() else { return }
        act { [taken] in $0.saveAnyway(text, taken: taken) }
    }

    @objc private func copyPathPressed() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(file.path.shown, forType: .string)
    }

    /// The one line's words, nil while none shows.
    var noteWords: String? {
        note.isHidden ? nil : note.stringValue
    }

    /// The sentence in place of the text, nil while the text shows.
    var sentenceWords: String? {
        sentence.isHidden ? nil : sentence.stringValue
    }

    /// The line a ⌘-click went to, carrying the accent bar; nil for none.
    var atLine: Int? {
        numbers.at
    }

    /// The 1-based line the caret is on, counted by the text's own line ranges: walking a large
    /// file's characters through Swift took seconds.
    var caretLine: Int {
        let text = editor.textStorage?.mutableString ?? NSMutableString(), at = min(editor.selectedRange().location, text.length)
        var line = 1, index = 0
        while index < text.length {
            let next = NSMaxRange(text.lineRange(for: NSRange(location: index, length: 0)))
            guard next <= at, next > index, next < text.length || at == text.length && next == at else { break }
            line += 1
            index = next
        }
        return line
    }

    /// The title's status words, empty while there are none.
    var statusWords: String {
        status.stringValue
    }

    /// A terminal or the tree took the keys: the file no longer counts as holding them.
    func keysTakenElsewhere() {
        heldKeysLast = false
    }

    /// Goes to `line` (1-based), as a ⌘-click on a terminal path asks: the caret at its start, in
    /// view, the line carrying the accent bar; once the whole file is in, when it is not yet.
    func go(toLine line: Int) {
        guard editor.isEditable else {
            pendingLine = line
            return
        }
        pendingLine = nil
        show(line: line)
        numbers.at = line
    }

    /// The line a ⌘-click asked for, gone to once the whole file is in.
    private var pendingLine: Int?

    /// Opens at `line` (1-based): the caret at its start, the line scrolled into view.
    func show(line: Int) {
        let text = editor.string as NSString
        var location = 0
        for _ in 1 ..< max(line, 1) {
            let next = text.lineRange(for: NSRange(location: location, length: 0))
            guard NSMaxRange(next) < text.length else { break }
            location = NSMaxRange(next)
        }
        editor.setSelectedRange(NSRange(location: location, length: 0))
        editor.scrollRangeToVisible(NSRange(location: location, length: 0))
    }

    /// A key: decided at once.
    func textDidChange(_: Notification) {
        guard !applying else { return }
        decideEdit()
    }

    /// An edit of the text decided by its length, the text read only when its length is back to the
    /// base's.
    private func decideEdit() {
        editedUnseen = false
        numbers.at = nil
        let step = PanePhases.timed("model: a key decided") { file.edited(units: editor.textStorage?.length ?? 0) ?? file.edited(editor.string) }
        apply(step)
    }

    /// An edit of the text that no `textDidChange` follows, as the text view's own undo and redo:
    /// decided after the turn, so the dot and what a save writes follow it too.
    private var editedUnseen = false

    /// The ruler forgets its line count from the edited place on; an edit not the pane's own is
    /// decided, after the turn if no key's `textDidChange` decides it first.
    nonisolated func textStorage(_: NSTextStorage, didProcessEditing mask: NSTextStorageEditActions, range: NSRange, changeInLength _: Int) {
        guard mask.contains(.editedCharacters) else { return }
        let location = range.location
        MainActor.assumeIsolated {
            numbers.forget(from: location)
            guard !applying, !editedUnseen else { return }
            editedUnseen = true
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, self.editedUnseen else { return }
                    self.decideEdit()
                }
            }
        }
    }

    // MARK: What it shows

    private func apply(_ step: OpenFile.Step) {
        send(frames(step.asks))
        for event in step.events {
            switch event {
            case let .arrived(piece): append(piece)
            case .allArrived:
                allArrived = true
                editableWhenIn()
            case .whole:
                editor.isEditable = true
                numbers.invalidate()
                if let line = pendingLine { go(toLine: line) }
            case let .replaced(text, units, chosen): replace(with: text, units: units, undoable: chosen)
            case .restarted: restart()
            case .saved:
                said = nil
                if !file.clean { apply(file.edited(editor.string)) }
                finishLeaving()
            case let .conflict(theirs, again):
                abandonLeaving()
                readTime(of: theirs)
                onCompare(theirs, again)
            case .gone:
                abandonLeaving()
                onCompare(nil, false)
            case let .saveRefused(words):
                abandonLeaving()
                said = words
            case .notConnected:
                abandonLeaving()
                said = "Not connected to \(workspace). Your changes are kept here."
            case let .lostSave(applied):
                said = applied
                    ? "Saved: your save reached \(workspace) before the connection dropped."
                    : "Not saved: the connection to \(workspace) dropped before your save reached it. ⌘S to save again."
            }
        }
        show()
    }

    /// Text arrived but not yet in the view, in slices: one slice a turn, so a 256 KiB chunk never
    /// holds the main thread for its whole append; the first goes in at once, for the first screen.
    private var slices: [String] = []
    private var allArrived = false
    private static let slice = 32 * 1024

    private func append(_ piece: String) {
        let text = piece as NSString
        var at = 0
        while at < text.length {
            let range = text.rangeOfComposedCharacterSequences(for: NSRange(location: at, length: min(Self.slice, text.length - at)))
            slices.append(text.substring(with: range))
            slicesMade += 1
            at = NSMaxRange(range)
        }
        if editor.textStorage?.length == 0 { appendSlice() }
        scheduleSlice()
    }

    private func scheduleSlice() {
        guard !slices.isEmpty else { return editableWhenIn() }
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated {
                self?.appendSlice()
                self?.scheduleSlice()
            }
        }
    }

    private func appendSlice() {
        guard !slices.isEmpty else { return }
        applying = true
        defer { applying = false }
        let slice = slices.removeFirst()
        slicesDealt += 1
        guard slicesDealt != losesSlice else { return }
        PanePhases.timed("storage: a slice appended") { editor.textStorage?.append(NSAttributedString(string: slice, attributes: textAttributes)) }
        slicesTaken += 1
        taken += (slice as NSString).length
    }

    /// The view holds every slice: the model is told the units it took, and keys are taken once
    /// they are the whole file's (F15). Every slice made must have been taken, and the storage must
    /// hold exactly the units put in it, or the model is told of none, so a slice lost and another
    /// doubled cannot cancel out.
    private func editableWhenIn() {
        guard allArrived, slices.isEmpty, !file.whole else { return }
        let whole = slicesTaken == slicesMade && taken == editor.textStorage?.length ?? 0
        act { [taken] in $0.inView(whole ? taken : -1) }
    }

    /// The first read starts again: the view drops what it took of it.
    private func restart() {
        slices = []
        allArrived = false
        taken = 0
        (slicesMade, slicesDealt, slicesTaken) = (0, 0, 0)
        applying = true
        editor.textStorage?.setAttributedString(NSAttributedString())
        applying = false
        editor.isEditable = false
    }

    /// The workspace's text replaces the pane's, `units` UTF-16 units long: queued slices dropped,
    /// the first visible line and the selection kept where their lines survive, the lines that
    /// differ barred for ten seconds, "Updated just now" for three. One the person chose can be undone.
    private func replace(with text: String, units: Int, undoable: Bool) {
        slices = []
        numbers.at = nil
        (slicesMade, slicesDealt, slicesTaken) = (0, 0, 0)
        let old = editor.string, top = firstVisibleLine, selection = editor.selectedRange()
        let storage = editor.textStorage, all = NSRange(location: 0, length: storage?.length ?? 0), new = NSAttributedString(string: text, attributes: textAttributes)
        applying = true
        if undoable, editor.isEditable, editor.shouldChangeText(in: all, replacementString: text) {
            storage?.replaceCharacters(in: all, with: new)
            editor.didChangeText()
        } else {
            storage?.setAttributedString(new)
            editor.undoManager?.removeAllActions()
        }
        applying = false
        taken = storage?.length == units ? units : -1
        let length = (text as NSString).length
        editor.setSelectedRange(NSRange(location: min(selection.location, length), length: min(selection.length, max(length - selection.location, 0))))
        show(line: top)
        if !old.isEmpty {
            barred = (Self.differing(old, text).other, clock() + Self.barFor)
            updatedAt = clock()
            for after in [Self.updatedFor, Self.barFor] {
                DispatchQueue.main.asyncAfter(deadline: .now() + after) { [weak self] in
                    MainActor.assumeIsolated { self?.show() }
                }
            }
        }
        numbers.invalidate()
    }

    /// The lines that differ on each side, by what the two share at their start and end: the bars
    /// of a reload in the pane and of both sides of the sheet.
    static func differing(_ one: String, _ other: String) -> (one: IndexSet, other: IndexSet) {
        let was = one.components(separatedBy: "\n"), now = other.components(separatedBy: "\n")
        var head = 0
        while head < min(was.count, now.count), was[head] == now[head] {
            head += 1
        }
        var tail = 0
        while tail < min(was.count, now.count) - head, was[was.count - 1 - tail] == now[now.count - 1 - tail] {
            tail += 1
        }
        func lines(_ count: Int) -> IndexSet {
            head < count - tail ? IndexSet(integersIn: (head + 1) ... (count - tail)) : IndexSet()
        }
        return (lines(was.count), lines(now.count))
    }

    /// When the workspace's bytes now held for Compare were read: said on the sheet beside them.
    private func readTime(of theirs: OpenFile.Theirs) {
        guard theirs.digest != shownTheirs else { return }
        shownTheirs = theirs.digest
        theirsRead = Date()
    }

    private var firstVisibleLine: Int {
        let text = editor.string as NSString
        guard let manager = editor.textLayoutManager, let fragment = manager.textLayoutFragment(for: CGPoint(x: 0, y: scroll.contentView.bounds.minY)),
              let content = manager.textContentManager else { return 1 }
        let offset = content.offset(from: content.documentRange.location, to: fragment.rangeInElement.location)
        return text.substring(to: min(offset, text.length)).reduce(1) { $1 == "\n" ? $0 + 1 : $0 }
    }

    private var textAttributes: [NSAttributedString.Key: Any] {
        [.font: mono.font(Self.points, .regular) as NSFont, .foregroundColor: NSColor(look.text)]
    }

    /// The title's dot and status, the one line, and the text or the sentence, as the file says now.
    private func show() {
        if file.note != shownNote {
            shownNote = file.note
            if file.note != .none { said = nil }
        }
        let unsaved = !file.clean
        if dot.isHidden == unsaved {
            dot.isHidden = !unsaved
            onUnsaved(unsaved)
        }
        if file.clean, let at = updatedAt, clock() - at < Self.updatedFor {
            status.stringValue = "Updated just now"
        } else {
            status.stringValue = ""
        }
        let words: String? = said ?? {
            switch file.note {
            case .none: nil
            case .changed: "Changed on the workspace while you typed."
            case .deleted: "\(file.name) was deleted on the workspace."
            case .deletedWhileTyped: "\(file.name) was deleted on the workspace while you typed."
            case .tooLarge: OpenFile.tooLargeNow(file.name)
            case .notShownWhole: OpenFile.notShownWhole(file.name)
            }
        }()
        note.stringValue = words ?? ""
        note.isHidden = words == nil
        compare.isHidden = file.note != .changed || said != nil
        discard.isHidden = !(file.note == .changed || file.note == .deletedWhileTyped) || said != nil
        let sentenceWords: String? = switch file.state {
        case .reading, .text: nil
        case let .notText(words), let .tooLarge(words), let .notAFile(words), let .refused(words): words
        }
        sentence.stringValue = sentenceWords ?? ""
        sentence.isHidden = sentenceWords == nil
        copyPath.isHidden = sentenceWords == nil
        scroll.isHidden = sentenceWords != nil
        scroll.alphaValue = file.note == .deleted ? 0.5 : 1
        numbers.barred = barred.flatMap { clock() < $0.until ? $0.lines : nil } ?? IndexSet()
        if let theirs = file.theirs { readTime(of: theirs) }
        if watched != shownWatch {
            shownWatch = watched
            onWatched(watched)
        }
        needsLayout = true
    }

    private func paint() {
        layer?.backgroundColor = NSColor(Tint(look.surface)).cgColor
        folderLabel.textColor = NSColor(look.textTertiary)
        nameLabel.textColor = NSColor(look.text)
        status.textColor = NSColor(look.textTertiary)
        note.textColor = NSColor(look.text)
        sentence.textColor = NSColor(look.text)
        dot.layer?.backgroundColor = NSColor(look.text).cgColor
        for button in [close, showTerminal, compare, discard, copyPath] { button.look = look }
        editor.textColor = NSColor(look.text)
        editor.insertionPointColor = NSColor(look.text)
        editor.backgroundColor = NSColor(Tint(look.surface))
        numbers.look = look
        for view in [folderLabel, nameLabel, status, note] { view.font = .systemFont(ofSize: Look.Step.callout.points) }
    }

    /// A label's words as drawn, measured from them: its intrinsic size lags a change of font.
    private static func measured(_ label: NSTextField) -> NSSize {
        let size = label.attributedStringValue.size()
        return NSSize(width: label.stringValue.isEmpty ? 0 : (Double(size.width) + 4).rounded(.up), height: (Double(size.height)).rounded(.up))
    }

    override func layout() {
        super.layout()
        let width = Double(bounds.width), height = Double(bounds.height), pad = 12.0
        titleBar.frame = NSRect(x: 0, y: 0, width: width, height: Self.titleHeight)
        let closeSize = close.intrinsicContentSize
        close.frame = NSRect(x: width - pad - Double(closeSize.width), y: (Self.titleHeight - Double(closeSize.height)) / 2, width: Double(closeSize.width), height: Double(closeSize.height))
        var end = Double(close.frame.minX)
        if !showTerminal.isHidden {
            let size = showTerminal.intrinsicContentSize
            showTerminal.frame = NSRect(x: end - 8 - Double(size.width), y: (Self.titleHeight - Double(size.height)) / 2, width: Double(size.width), height: Double(size.height))
            end = Double(showTerminal.frame.minX)
        }
        let folder = Self.measured(folderLabel), name = Self.measured(nameLabel), room = end - pad - 8
        let folderWidth = min(Double(folder.width), max(room - Double(name.width) - 14, 0))
        folderLabel.frame = NSRect(x: pad, y: (Self.titleHeight - Double(folder.height)) / 2, width: folderWidth, height: Double(folder.height))
        let nameWidth = min(Double(name.width), room - folderWidth - 14)
        nameLabel.frame = NSRect(x: pad + folderWidth, y: (Self.titleHeight - Double(name.height)) / 2, width: max(nameWidth, 0), height: Double(name.height))
        dot.frame = NSRect(x: Double(nameLabel.frame.maxX) + 6, y: Self.titleHeight / 2 - 3, width: 6, height: 6)
        let statusSize = status.intrinsicContentSize
        status.frame = NSRect(x: Double(dot.frame.maxX) + 8, y: (Self.titleHeight - Double(statusSize.height)) / 2, width: max(end - Double(dot.frame.maxX) - 16, 0), height: Double(statusSize.height))
        var top = Self.titleHeight
        if !note.isHidden {
            let buttons = [discard, compare].filter { !$0.isHidden }
            let buttonsWidth = buttons.reduce(0.0) { $0 + Double($1.intrinsicContentSize.width) + 8 }
            let size = Self.measured(note)
            let split = OneLine.actionsUnder(words: Double(size.width), actions: buttonsWidth, room: width - 2 * pad)
            note.frame = NSRect(x: pad, y: top + (Self.lineHeight - Double(size.height)) / 2, width: split ? width - 2 * pad : max(width - pad - buttonsWidth - pad, 0), height: Double(size.height))
            if split { top += Self.lineHeight }
            var end = width - pad
            for button in buttons {
                let size = button.intrinsicContentSize
                button.frame = NSRect(x: end - Double(size.width), y: top + (Self.lineHeight - Double(size.height)) / 2, width: Double(size.width), height: Double(size.height))
                end = Double(button.frame.minX) - 8
            }
            top += Self.lineHeight
        }
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        for label in [folderLabel, nameLabel, status, note] { label.alignment = rightToLeft ? .right : .left }
        if rightToLeft {
            for view in [folderLabel, nameLabel, dot, status, showTerminal, close, note, compare, discard] as [NSView] { view.frame.origin.x = width - view.frame.maxX }
        }
        scroll.frame = NSRect(x: 0, y: top, width: width, height: max(height - top, 0))
        let sentenceSize = sentence.cell?.cellSize(forBounds: NSRect(x: 0, y: 0, width: max(width - 2 * pad, 0), height: .greatestFiniteMagnitude)) ?? .zero
        sentence.frame = NSRect(x: pad, y: top + pad, width: max(width - 2 * pad, 0), height: Double(sentenceSize.height))
        let copySize = copyPath.intrinsicContentSize
        copyPath.frame = NSRect(x: pad, y: Double(sentence.frame.maxY) + 10, width: Double(copySize.width), height: Double(copySize.height))
    }
}

/// The text's line numbers, in the ruler beside it, and a 2 pt bar beside each line a reload
/// changed: numbered by the paragraphs the layout shows, counted once from the text's start up to
/// the first shown and cached until the text changes.
@MainActor
final class LineNumbers: NSRulerView {
    weak var editor: NSTextView?
    var font = NSFont.monospacedDigitSystemFont(ofSize: 11, weight: .regular)
    var look = Look.light {
        didSet { needsDisplay = true }
    }

    var barred = IndexSet() {
        didSet { if barred != oldValue { needsDisplay = true } }
    }

    /// The line a ⌘-click went to: a 2 pt accent bar at its leading edge, until the text changes.
    var at: Int? {
        didSet { if at != oldValue { needsDisplay = true } }
    }

    private var starts: [Int] = [0]
    private var counted = 0

    override init(scrollView: NSScrollView?, orientation: NSRulerView.Orientation) {
        super.init(scrollView: scrollView, orientation: orientation)
        ruleThickness = 44
        clipsToBounds = true
    }

    @available(*, unavailable)
    required init(coder _: NSCoder) {
        fatalError("not made from a coder")
    }

    /// The text changed: the line count starts again.
    func invalidate() {
        starts = [0]
        counted = 0
        needsDisplay = true
    }

    /// The text changed at `location`: the lines counted before it stand, the rest are counted again.
    func forget(from location: Int) {
        while starts.count > 1, let last = starts.last, last > location {
            starts.removeLast()
        }
        counted = min(counted, starts.last ?? 0)
        needsDisplay = true
    }

    /// The 1-based line holding character `offset`, counting only as far as it.
    private func line(of offset: Int, in text: NSString) -> Int {
        while counted < offset, counted < text.length {
            let range = text.lineRange(for: NSRange(location: counted, length: 0))
            counted = NSMaxRange(range)
            if counted < text.length || text.length > 0 && text.character(at: text.length - 1) == 10 { starts.append(counted) }
        }
        var low = 0, high = starts.count - 1
        while low < high {
            let middle = (low + high + 1) / 2
            if starts[middle] <= offset { low = middle } else { high = middle - 1 }
        }
        return low + 1
    }

    /// One shown line of the text: its number, where its rows start in the ruler and how tall
    /// they are together (a wrapped line's every row), and whether it is barred.
    struct Row: Equatable {
        let number: Int
        let y: Double
        let height: Double
        let barred: Bool
        /// The line a ⌘-click went to.
        var at = false
    }

    /// The lines shown now, each with its rows' extent: what the ruler draws.
    var rows: [Row] {
        guard let editor, let manager = editor.textLayoutManager, let content = manager.textContentManager, let scroll = scrollView else { return [] }
        let text = editor.string as NSString, visible = scroll.contentView.bounds, inset = Double(editor.textContainerInset.height)
        guard let first = manager.textLayoutFragment(for: CGPoint(x: 0, y: Double(visible.minY) - inset)) else { return [] }
        var rows: [Row] = []
        manager.enumerateTextLayoutFragments(from: first.rangeInElement.location, options: [.ensuresLayout]) { fragment in
            let frame = fragment.layoutFragmentFrame
            guard Double(frame.minY) + inset <= Double(visible.maxY) else { return false }
            let offset = content.offset(from: content.documentRange.location, to: fragment.rangeInElement.location)
            let number = line(of: offset, in: text)
            rows.append(Row(number: number, y: Double(frame.minY) + inset - Double(visible.minY), height: Double(frame.height), barred: barred.contains(number), at: number == at))
            return true
        }
        return rows
    }

    override func drawHashMarksAndLabels(in rect: NSRect) {
        PanePhases.timed("ruler: drawn") { drawRows(in: rect) }
    }

    private func drawRows(in _: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        bounds.fill()
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor(look.textTertiary)]
        for row in rows {
            if row.barred {
                NSColor(look.info).setFill()
                NSRect(x: Double(ruleThickness) - 2, y: row.y, width: 2, height: row.height).fill()
            }
            if row.at {
                NSColor(look.accent).setFill()
                NSRect(x: 0, y: row.y, width: 2, height: row.height).fill()
            }
            let label = "\(row.number)" as NSString, size = label.size(withAttributes: attributes)
            label.draw(at: NSPoint(x: Double(ruleThickness) - 8 - Double(size.width), y: row.y + 1), withAttributes: attributes)
        }
    }
}

/// The pane's text view, telling the pane when it takes the keys.
@MainActor
final class PaneTextView: NSTextView {
    var onKeys: @MainActor () -> Void = {}

    override func becomeFirstResponder() -> Bool {
        let taken = super.becomeFirstResponder()
        if taken { onKeys() }
        return taken
    }
}

/// Where the main thread's time goes in a file pane, by phase, while the drive measures: each
/// phase's total and its longest single span, in seconds.
@MainActor
package enum PanePhases {
    package static var measuring = false
    package private(set) static var phases: [String: (total: Double, longest: Double)] = [:]

    package static func resetPhases() {
        phases = [:]
    }

    /// Runs `work` as phase `name`, timed only while the drive measures.
    package static func timed<Result>(_ name: String, _ work: () -> Result) -> Result {
        guard measuring else { return work() }
        let began = CACurrentMediaTime()
        defer {
            let spent = CACurrentMediaTime() - began, held = phases[name] ?? (0, 0)
            phases[name] = (held.total + spent, max(held.longest, spent))
        }
        return work()
    }
}
