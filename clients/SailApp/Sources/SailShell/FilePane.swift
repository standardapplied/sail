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
final class FilePane: NSView, NSTextViewDelegate {
    static let titleHeight = 30.0, lineHeight = 30.0, points = 12.0, barFor = 10.0, updatedFor = 3.0

    private(set) var file: OpenFile
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
    /// The conflict sheet: theirs beside the pane's text.
    var onCompare: @MainActor (_ theirs: String) -> Void = { _ in }
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
    private var said: String?
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
        apply(file.open())
    }

    /// The frames this pane's requests are answered by, the watch's word on its file and its ticks:
    /// taken here, answering whether the frame was its own alone.
    func takes(_ frame: Frame) -> Bool {
        switch frame {
        case let .filesChanged(paths):
            if paths.contains(file.path) { apply(file.changed()) }
            return false
        case .filesTick:
            apply(file.tick())
            return false
        default:
            guard let answer = Self.answer(frame) else { return false }
            apply(file.receive(answer))
            return true
        }
    }

    func admitted() {
        apply(file.admitted())
    }

    func disconnected() {
        file.disconnected()
        show()
    }

    /// The watch's entry for the file, at the version the pane holds.
    var watched: WatchedPath? {
        file.version.isEmpty ? nil : WatchedPath(path: file.path, version: file.version)
    }

    static func frames(_ asks: [OpenFile.Ask]) -> [Frame] {
        asks.compactMap { ask in
            switch ask {
            case let .read(id, path): .filesRead(id: id, path: path, offset: 0, length: OpenFile.limit + 1)
            case let .cancel(id): .filesCancel(id: id)
            case .write: nil
            }
        }
    }

    static func answer(_ frame: Frame) -> OpenFile.Answer? {
        switch frame {
        case let .filesData(id, version, bytes, eof, whole, digest): .data(id: id, version: version, bytes: bytes, eof: eof, whole: whole, digest: digest)
        case let .filesRefused(id, kind, message): .refused(id: id, kind: kind, message: message)
        default: nil
        }
    }

    // MARK: What the person does

    /// ⌘S: the checked save; not connected, it says so and writes nothing.
    @objc func saveDocument(_: Any?) {
        apply(file.save())
    }

    /// Closing: an unsaved pane asks Save, Discard, Cancel first.
    @objc func closePressed() {
        askToLeave { [weak self] in self?.finishClosing() }
    }

    /// Before the pane's text is left (closed, another file opened into it, its tab closed, the app
    /// quit): an unsaved pane asks Save, Discard, Cancel, Save being the checked save; a conflict or a
    /// refusal cancels what was asked (M52).
    func askToLeave(then leave: @escaping @MainActor () -> Void) {
        guard !file.clean, let window else { return leave() }
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
                    self.afterSave = leave
                    self.saveDocument(nil)
                case .alertSecondButtonReturn: leave()
                default: break
                }
            }
        }
    }

    /// What waits on the save a close question began: left only once it is saved.
    private var afterSave: (@MainActor () -> Void)?

    func finishClosing() {
        send(Self.frames(file.close().asks))
        onClose()
    }

    @objc private func showTerminalPressed() {
        onShowTerminal()
    }

    @objc private func comparePressed() {
        guard let theirs = file.theirs else { return }
        onCompare(String(decoding: theirs, as: UTF8.self))
    }

    @objc func discardPressed() {
        apply(file.discardMine())
    }

    func keepTheirs() {
        apply(file.keepTheirs())
    }

    func keepMine() {
        apply(file.keepMine())
    }

    func saveAnyway() {
        apply(file.saveAnyway())
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

    /// A terminal or the tree took the keys: the file no longer counts as holding them.
    func keysTakenElsewhere() {
        heldKeysLast = false
    }

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

    func textDidChange(_: Notification) {
        guard !applying else { return }
        apply(file.edited(editor.string))
    }

    // MARK: What it shows

    private func apply(_ step: OpenFile.Step) {
        send(Self.frames(step.asks))
        for event in step.events {
            switch event {
            case let .arrived(piece): append(piece)
            case .whole:
                editor.isEditable = true
                numbers.invalidate()
            case let .replaced(text): replace(with: text)
            case .saved:
                said = nil
                if let leave = afterSave {
                    afterSave = nil
                    leave()
                }
            case let .conflict(theirs):
                afterSave = nil
                onCompare(theirs)
            case .gone: afterSave = nil
            case let .saveRefused(words):
                afterSave = nil
                said = words
            case .notConnected:
                afterSave = nil
                said = "Not connected to \(workspace). Your changes are kept here."
            }
        }
        show()
    }

    private func append(_ piece: String) {
        applying = true
        defer { applying = false }
        editor.textStorage?.append(NSAttributedString(string: piece, attributes: textAttributes))
        numbers.invalidate()
    }

    /// The workspace's text replaces the pane's: the first visible line and the selection kept where
    /// their lines survive, the lines that differ barred for ten seconds, "Updated just now".
    private func replace(with text: String) {
        let old = editor.string, top = firstVisibleLine, selection = editor.selectedRange()
        applying = true
        editor.textStorage?.setAttributedString(NSAttributedString(string: text, attributes: textAttributes))
        editor.undoManager?.removeAllActions()
        applying = false
        let length = (text as NSString).length
        editor.setSelectedRange(NSRange(location: min(selection.location, length), length: min(selection.length, max(length - selection.location, 0))))
        show(line: top)
        if !old.isEmpty {
            barred = (Self.differing(old, text), clock() + Self.barFor)
            updatedAt = clock()
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.barFor) { [weak self] in
                MainActor.assumeIsolated { self?.show() }
            }
        }
        numbers.invalidate()
    }

    /// The new text's lines that differ from the old, by what the two share at their start and end.
    static func differing(_ old: String, _ new: String) -> IndexSet {
        let was = old.components(separatedBy: "\n"), now = new.components(separatedBy: "\n")
        var head = 0
        while head < min(was.count, now.count), was[head] == now[head] {
            head += 1
        }
        var tail = 0
        while tail < min(was.count, now.count) - head, was[was.count - 1 - tail] == now[now.count - 1 - tail] {
            tail += 1
        }
        return head < now.count - tail ? IndexSet(integersIn: (head + 1) ... (now.count - tail)) : IndexSet()
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
        let unsaved = !file.clean
        if dot.isHidden == unsaved {
            dot.isHidden = !unsaved
            onUnsaved(unsaved)
        }
        if let at = updatedAt, clock() - at < Self.updatedFor {
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
        if file.theirsDigest != shownTheirs {
            shownTheirs = file.theirsDigest
            theirsRead = Date()
        }
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
            var end = width - pad
            for button in [discard, compare] where !button.isHidden {
                let size = button.intrinsicContentSize
                button.frame = NSRect(x: end - Double(size.width), y: top + (Self.lineHeight - Double(size.height)) / 2, width: Double(size.width), height: Double(size.height))
                end = Double(button.frame.minX) - 8
            }
            let size = note.intrinsicContentSize
            note.frame = NSRect(x: pad, y: top + (Self.lineHeight - Double(size.height)) / 2, width: max(end - pad - 8, 0), height: Double(size.height))
            top += Self.lineHeight
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
            rows.append(Row(number: number, y: Double(frame.minY) + inset - Double(visible.minY), height: Double(frame.height), barred: barred.contains(number)))
            return true
        }
        return rows
    }

    override func drawHashMarksAndLabels(in _: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        bounds.fill()
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor(look.textTertiary)]
        for row in rows {
            if row.barred {
                NSColor(look.info).setFill()
                NSRect(x: Double(ruleThickness) - 2, y: row.y, width: 2, height: row.height).fill()
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
