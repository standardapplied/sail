import AppKit
import SailControls
import SailFiles
import SailLook
import SailTerminalWire

/// The inline name field open in the tree: a new file or folder in `folder`, or a rename of the
/// entry at `renaming` in its folder; `asked` the id of the request sent for its name while
/// unanswered, and `refusal` the sentence under the field.
struct Naming: Equatable {
    enum Kind: Equatable {
        case file, folder
        case rename(FileName)
    }

    let kind: Kind
    let folder: FileName
    var asked: Int32?
    var refusal: String?

    /// The entry a rename renames; nil for a new one.
    var renaming: FileName? {
        if case let .rename(path) = kind { path } else { nil }
    }

    /// Its row's id: a rename's is its entry's own, a new one's a key no path has.
    var id: String {
        renaming?.key ?? "\u{0}new\u{0}" + folder.key
    }

    /// What VoiceOver calls its field.
    var voice: String {
        switch kind {
        case .file: "New file name"
        case .folder: "New folder name"
        case .rename: "New name"
        }
    }
}

/// The inline name field of New File, New Folder and Rename in its row's own place, as the mockup's
/// `.renf`: the box where the name starts, 22 pt in the 24 pt row, the name's 13 pt, and a refusal
/// as its sentence under it. ⏎ commits and Esc cancels; every other key is the field's. VoiceOver
/// calls the field `voice`.
@MainActor
final class NameRow: NSView, NSTextFieldDelegate {
    let field: SailTextField
    private let depth: Int
    /// The row's width for its content, which its sentence wraps in.
    private let room: Double
    var onCommit: @MainActor () -> Void = {}
    var onCancel: @MainActor () -> Void = {}
    var onEdit: @MainActor () -> Void = {}

    init(name: String, voice: String, depth: Int, room: Double, direction: NSUserInterfaceLayoutDirection, look: Look, mono: LabelFont) {
        field = SailTextField(label: nil, placeholder: "", size: .row, look: look, mono: mono)
        field.field.setAccessibilityLabel(voice)
        self.depth = depth
        self.room = room
        super.init(frame: .zero)
        userInterfaceLayoutDirection = direction
        field.userInterfaceLayoutDirection = direction
        field.field.stringValue = name
        field.field.delegate = self
        addSubview(field)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    var text: String {
        field.field.stringValue
    }

    var refusal: String? {
        get { field.error }
        set { if field.error != newValue { field.error = newValue; needsLayout = true } }
    }

    /// Where the box starts: where the row's name does.
    private var start: Double {
        Double(depth) * RowStyle.indent + TreeRowCell.slot + TreeRowCell.gap
    }

    /// Its height: the row's, or the field with its sentence wrapped in the row's width, 1 pt above
    /// it and 3 below.
    var height: Double {
        let width = max(room - start, 0) + 2 * Look.haloRoom
        if Double(field.frame.width) != width { field.setFrameSize(NSSize(width: width, height: Double(field.frame.height))) }
        return max(RowStyle.tree.height, Double(field.intrinsicContentSize.height) + (refusal == nil ? 2 : 4))
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: height)
    }

    var holdsKeys: Bool {
        (window?.firstResponder as? NSTextView)?.delegate === field.field
    }

    /// The keys in the field, its name chosen: up to its extension when it opens on a file's name,
    /// all of it when refused, so the next keys replace it.
    func focus(stem: Bool) {
        guard let window, window.makeFirstResponder(field.field), let editor = field.field.currentEditor() else { return }
        let name = text as NSString, dot = name.range(of: ".", options: .backwards)
        editor.selectedRange = NSRange(location: 0, length: stem && dot.location != NSNotFound && dot.location > 0 ? dot.location : name.length)
    }

    override func layout() {
        super.layout()
        let width = max(Double(bounds.width) - start, 0), x = userInterfaceLayoutDirection == .rightToLeft ? 0 : start
        field.frame = NSRect(x: x - Look.haloRoom, y: 1 - Look.haloRoom, width: width + 2 * Look.haloRoom, height: Double(field.intrinsicContentSize.height) + 2 * Look.haloRoom)
    }

    func control(_: NSControl, textView _: NSTextView, doCommandBy selector: Selector) -> Bool {
        switch selector {
        case #selector(NSResponder.insertNewline(_:)): onCommit()
        case #selector(NSResponder.cancelOperation(_:)): onCancel()
        default: return false
        }
        return true
    }

    func controlTextDidChange(_: Notification) {
        onEdit()
    }
}
