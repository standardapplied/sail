import AppKit
import SailControls
import SailLook

/// The conflict sheet: the workspace's file and the pane's text side by side, read-only, scrolling
/// together, opened at the first line that differs, each differing line barred. Keep Mine (in the
/// error ink: it replaces theirs), Keep Theirs, Cancel; no default, so ⏎ does nothing, and Esc
/// cancels. A deleted file offers Save Anyway and Discard instead. It writes nothing itself.
@MainActor
final class ConflictSheet: NSWindow {
    enum Choice {
        case keepMine, keepTheirs, saveAnyway, discard, cancel
    }

    var onChoice: @MainActor (Choice) -> Void = { _ in }
    let theirsView = NSTextView()
    let yoursView = NSTextView()
    private(set) var buttons: [SailButton] = []
    private var syncing = false

    init(name: String, theirs: String, yours: String, deleted: Bool, look: Look, mono: LabelFont) {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 900, height: 560), styleMask: [.titled], backing: .buffered, defer: true)
        let root = NSView()
        root.wantsLayer = true
        root.layer?.backgroundColor = NSColor(Tint(look.surface)).cgColor
        contentView = root
        let heading = NSTextField(labelWithString: deleted ? "\(name) was deleted on the workspace" : "\(name) changed on the workspace")
        heading.font = .systemFont(ofSize: Look.Step.title3.points, weight: .semibold)
        let detail = NSTextField(labelWithString: deleted ? "Save Anyway makes it anew; Discard drops what you typed." : "Keep one version. The other is lost; nothing is merged.")
        detail.textColor = NSColor(look.textSecondary)
        let lines = ConflictSheet.differing(theirs, yours)
        let theirsSide = side("Theirs", note: deleted ? "deleted" : "on the workspace", text: theirs, in: theirsView, barred: lines.theirs, look: look, mono: mono)
        let yoursSide = side("Yours", note: "unsaved", text: yours, in: yoursView, barred: lines.yours, look: look, mono: mono)
        let sides = NSStackView(views: [theirsSide, yoursSide])
        sides.distribution = .fillEqually
        sides.spacing = Look.Space.m
        let titles: [(String, ButtonKind, Choice)] = deleted
            ? [("Cancel", .secondary, .cancel), ("Discard", .secondary, .discard), ("Save Anyway", .destructive, .saveAnyway)]
            : [("Cancel", .secondary, .cancel), ("Keep Theirs", .secondary, .keepTheirs), ("Keep Mine", .destructive, .keepMine)]
        buttons = titles.map { title, kind, choice in
            let button = SailButton(title, kind: kind, look: look, target: self, action: #selector(chosen(_:)))
            button.tag = [Choice.keepMine, .keepTheirs, .saveAnyway, .discard, .cancel].firstIndex(of: choice) ?? 4
            button.keyEquivalent = ""
            return button
        }
        let actions = NSStackView(views: [buttons[0], NSView(), buttons[1], buttons[2]])
        actions.setHuggingPriority(.defaultLow, for: .horizontal)
        let stack = NSStackView(views: [heading, detail, sides, actions])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = Look.Space.m
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        stack.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: root.leadingAnchor), stack.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            stack.topAnchor.constraint(equalTo: root.topAnchor), stack.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            sides.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -40), actions.widthAnchor.constraint(equalTo: sides.widthAnchor),
        ])
        for view in [theirsView, yoursView] {
            view.enclosingScrollView?.contentView.postsBoundsChangedNotifications = true
            NotificationCenter.default.addObserver(self, selector: #selector(scrolled(_:)), name: NSView.boundsDidChangeNotification, object: view.enclosingScrollView?.contentView)
        }
        let first = min(lines.theirs.first ?? 1, lines.yours.first ?? 1)
        for view in [theirsView, yoursView] { Self.scroll(view, toLine: first) }
    }

    private func side(_ title: String, note: String, text: String, in view: NSTextView, barred: IndexSet, look: Look, mono: LabelFont) -> NSView {
        let head = NSTextField(labelWithString: "\(title)  \(note)")
        head.font = .systemFont(ofSize: Look.Step.callout.points, weight: .semibold)
        view.isEditable = false
        view.isRichText = false
        view.drawsBackground = false
        view.font = mono.font(12, .regular) as NSFont
        view.textColor = NSColor(look.text)
        view.string = text
        let barColour = NSColor(look.info).withAlphaComponent(0.18)
        let source = text as NSString
        var location = 0, number = 1
        while location < source.length {
            let range = source.lineRange(for: NSRange(location: location, length: 0))
            if barred.contains(number) { view.textStorage?.addAttribute(.backgroundColor, value: barColour, range: range) }
            location = NSMaxRange(range)
            number += 1
        }
        let scroll = NSScrollView()
        scroll.documentView = view
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        view.autoresizingMask = [.width]
        let box = NSStackView(views: [head, scroll])
        box.orientation = .vertical
        box.alignment = .leading
        scroll.widthAnchor.constraint(equalTo: box.widthAnchor).isActive = true
        return box
    }

    /// The lines that differ on each side, by what the two share at their start and end.
    static func differing(_ theirs: String, _ yours: String) -> (theirs: IndexSet, yours: IndexSet) {
        let one = theirs.components(separatedBy: "\n"), other = yours.components(separatedBy: "\n")
        var head = 0
        while head < min(one.count, other.count), one[head] == other[head] {
            head += 1
        }
        var tail = 0
        while tail < min(one.count, other.count) - head, one[one.count - 1 - tail] == other[other.count - 1 - tail] {
            tail += 1
        }
        func lines(_ count: Int) -> IndexSet {
            head < count - tail ? IndexSet(integersIn: (head + 1) ... (count - tail)) : IndexSet()
        }
        return (lines(one.count), lines(other.count))
    }

    static func scroll(_ view: NSTextView, toLine line: Int) {
        let text = view.string as NSString
        var location = 0
        for _ in 1 ..< max(line, 1) where location < text.length {
            location = NSMaxRange(text.lineRange(for: NSRange(location: location, length: 0)))
        }
        view.scrollRangeToVisible(NSRange(location: min(location, text.length), length: 0))
    }

    /// One side scrolled: the other follows to the same place.
    @objc private func scrolled(_ note: Notification) {
        guard !syncing, let moved = note.object as? NSClipView else { return }
        syncing = true
        defer { syncing = false }
        for view in [theirsView, yoursView] {
            guard let clip = view.enclosingScrollView?.contentView, clip !== moved else { continue }
            clip.scroll(to: moved.bounds.origin)
            view.enclosingScrollView?.reflectScrolledClipView(clip)
        }
    }

    @objc private func chosen(_ sender: SailButton) {
        let choices: [Choice] = [.keepMine, .keepTheirs, .saveAnyway, .discard, .cancel]
        onChoice(choices.indices.contains(sender.tag) ? choices[sender.tag] : .cancel)
    }

    override func cancelOperation(_: Any?) {
        onChoice(.cancel)
    }

    /// ⏎ chooses nothing: neither version is the default.
    override func keyDown(with event: NSEvent) {
        if event.keyCode == 36 || event.keyCode == 76 { return }
        super.keyDown(with: event)
    }
}
