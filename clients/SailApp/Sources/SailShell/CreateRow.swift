import AppKit
import SailAPI
import SailControls
import SailLook

/// The create row as the approved frames draw it: the name field (a 250 pt box) with the server's rule
/// under it, or its refusal running the column's width (at most 440 pt), and, when the name is
/// already listed, a line under that: "Show dock or choose another name."; Cancel and Create with ⏎
/// at the row's end, level with the box. While it is under way the field waits and Create says so.
@MainActor
final class CreateRow: NSView {
    /// The design's create row (`f-create`, `f-refused`): 12 pt above the field, at least 76 pt for
    /// the field and its lines, under the row's 1 pt hairline.
    static let column = 440.0, box = 250.0, gap = 16.0, padding = 12.0, below = 7.0, least = 76.0

    let field: SailTextField
    let cancel: SailButton
    let create: SailButton
    let buttons: NSStackView
    /// "Show dock or choose another name.", for a name already listed.
    let showLine: NSStackView?
    /// The field held the keys when a scroll took the row out of the window (the list makes only the
    /// rows on screen): they are given back when it returns, unless someone gave them elsewhere.
    private var keysOwed = false

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        if newWindow == nil, let window, (window.firstResponder as? NSTextView)?.delegate === field.field { keysOwed = true }
        super.viewWillMove(toWindow: newWindow)
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard keysOwed, let window else { return }
        keysOwed = false
        guard window.firstResponder == nil || window.firstResponder === window, window.makeFirstResponder(field.field), let editor = field.field.currentEditor() else { return }
        editor.selectedRange = NSRange(location: (field.field.stringValue as NSString).length, length: 0)
    }

    /// The field's lines: the row's width, at most the 440 pt column; its box stays clear of the buttons.
    private var fieldWidth: NSLayoutConstraint?
    /// The field's width, held against this row rather than on the field, which every create row
    /// shares: the constraint goes when the field moves to the next row.

    init(field: SailTextField, existing: String?, look: Look, mono: LabelFont, on: @escaping @MainActor (ProjectView.Act) -> Void) {
        let runs = Runs(on)
        let cancel = SailButton("Cancel", kind: .quiet, size: .small, look: look, target: runs, action: #selector(Runs.cancel(_:)))
        let create = SailButton("Create", kind: .primary, size: .small, look: look, target: runs, action: #selector(Runs.create(_:)))
        create.hint("\r", through: field.field, mono: mono)
        let buttons = NSStackView(views: [cancel, create])
        buttons.spacing = PageColumn.buttonGap
        self.field = field
        self.cancel = cancel
        self.create = create
        self.buttons = buttons
        showLine = existing.map { name in
            runs.existing = name
            return Self.showLine(name, look: look, target: runs)
        }
        super.init(frame: .zero)
        self.runs = runs
        var views: [NSView] = [field, buttons]
        if let showLine { views.append(showLine) }
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        let fieldWidth = field.widthAnchor.constraint(equalToConstant: Self.column)
        fieldWidth.priority = .defaultHigh
        self.fieldWidth = fieldWidth
        var constraints = [
            field.leadingAnchor.constraint(equalTo: leadingAnchor), field.topAnchor.constraint(equalTo: topAnchor, constant: Self.padding), fieldWidth,
            field.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor),
            buttons.trailingAnchor.constraint(equalTo: trailingAnchor),
            buttons.topAnchor.constraint(equalTo: field.topAnchor, constant: (field.size.height - ButtonSize.small.height) / 2),
        ]
        if let showLine {
            constraints += [
                showLine.leadingAnchor.constraint(equalTo: field.leadingAnchor), showLine.topAnchor.constraint(equalTo: field.bottomAnchor, constant: Self.below),
                showLine.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor),
            ]
        }
        NSLayoutConstraint.activate(constraints)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// The row's height when its content has `room` across, and its column the width that leaves:
    /// 12 pt, then the field with its lines, at least the design's 76 pt; the list draws the row
    /// 1 pt taller, its hairline, as the design's 89 pt row is.
    func height(room: Double) -> Double {
        let lines = max(min(Self.column, room), 1)
        field.boxWidth = max(min(Self.box, room - Self.gap - Double(buttons.fittingSize.width)), 1)
        fieldWidth?.constant = lines
        field.setFrameSize(NSSize(width: lines + 2 * Look.haloRoom, height: Double(field.frame.height)))
        return (Self.padding + max(Self.least, words)).rounded(.up)
    }

    /// The whole row under its hairline, its 12 pt above the field included, so every line it draws
    /// is inside its bounds and takes clicks: one drawn past them is seen and never hit.
    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: Self.padding + max(Self.least, words))
    }

    /// The field with the line under its box, and the show line under that.
    private var words: Double {
        Double(field.intrinsicContentSize.height) + (showLine.map { Self.below + Double($0.fittingSize.height) } ?? 0)
    }

    /// "Show <name> or choose another name." on one line: the link is cut at its end before the
    /// words after it are touched, so a long taken name never squeezes them, and the line never wraps.
    private static func showLine(_ name: String, look: Look, target: Runs) -> NSStackView {
        let show = LinkButton("Show \(name)", look: look, target: target, action: #selector(Runs.show(_:)))
        let rest = NSTextField(labelWithAttributedString: NSAttributedString(string: "or choose another name.", attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.footnote.points), .foregroundColor: NSColor(look.textTertiary),
        ]))
        rest.maximumNumberOfLines = 1
        rest.lineBreakMode = .byClipping
        rest.setContentCompressionResistancePriority(.defaultHigh, for: .horizontal)
        let line = NSStackView(views: [show, rest])
        line.spacing = 4
        line.alignment = .firstBaseline
        line.setClippingResistancePriority(.defaultHigh, for: .horizontal)
        return line
    }

    private var runs: Runs?

    /// The row's buttons' target.
    @MainActor
    final class Runs: NSObject {
        let on: @MainActor (ProjectView.Act) -> Void
        var existing: String?

        init(_ on: @escaping @MainActor (ProjectView.Act) -> Void) {
            self.on = on
        }

        @objc func cancel(_: Any?) {
            on(.cancelCreate)
        }

        @objc func create(_: Any?) {
            on(.create)
        }

        @objc func show(_: Any?) {
            if let existing { on(.show(existing)) }
        }
    }
}
