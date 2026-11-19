import AppKit
import SailAPI
import SailControls
import SailLook

/// A workspace row's words as the approved list sets them: the name in 14 medium over its instance
/// in mono 11, in a column that takes what is left; the state's pill in a 112 pt column; and a 104 pt
/// column with someone else's initial and handle, empty on the person's own. Narrow, as under a 720 pt
/// card, it drops the owner's column and says whose the workspace is in place of its instance.
@MainActor
final class WorkspaceLine: NSStackView {
    static let pillColumn = 112.0, ownerColumn = 104.0

    let title: NSTextField
    let place: NSTextField
    let pill: Pill
    /// The owner's column; nil when narrow.
    let whose: NSView?

    init(_ line: WorkspacesModel.Line, narrow: Bool, look: Look, mono: LabelFont) {
        title = NSTextField(labelWithAttributedString: NSAttributedString(string: line.name, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.body.points, weight: .medium), .foregroundColor: NSColor(look.text),
        ]))
        let below = narrow ? line.owner.map { "\($0)'s" } ?? line.instance : line.instance
        place = NSTextField(labelWithAttributedString: NSAttributedString(string: below, attributes: [
            .font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary),
        ]))
        pill = Pill(Self.pill(line.state), look: look, mono: mono)
        whose = narrow ? nil : Self.column(line.owner.map { Self.owner($0, look: look) }, width: Self.ownerColumn)
        super.init(frame: .zero)
        for label in [title, place] {
            label.lineBreakMode = .byTruncatingTail
            label.setContentCompressionResistancePriority(Self.cutShort, for: .horizontal)
        }
        let words = NSStackView(views: [title, place])
        words.orientation = .vertical
        words.alignment = .leading
        words.spacing = 3
        pill.setContentHuggingPriority(.required, for: .horizontal)
        let shown: [NSView] = narrow ? [words, pill] : [words, Self.column(pill, width: Self.pillColumn), whose].compactMap { $0 }
        distribution = .fill
        setHuggingPriority(.defaultLow, for: .horizontal)
        for view in shown {
            addArrangedSubview(view)
        }
        spacing = narrow ? 8 : 0
        alignment = .centerY
    }

    /// Below the stacks' own priorities, so a long name is cut short inside its column; stacks that
    /// clip instead let their words run under the pill.
    static let cutShort = NSLayoutConstraint.Priority(NSLayoutConstraint.Priority.defaultLow.rawValue - 50)

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The pill a workspace's state shows.
    static func pill(_ state: Open<WorkspaceState>) -> PillState {
        switch state {
        case .known(.running): return .running
        case .known(.stopped): return .stopped
        case .known(.frozen): return .paused
        case .known(.notCreated): return .creating
        default: return .incomplete
        }
    }

    private static func column(_ view: NSView?, width: Double) -> NSView {
        let column = NSView()
        column.widthAnchor.constraint(equalToConstant: width).isActive = true
        guard let view else { return column }
        view.translatesAutoresizingMaskIntoConstraints = false
        column.addSubview(view)
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: column.leadingAnchor),
            view.centerYAnchor.constraint(equalTo: column.centerYAnchor), column.heightAnchor.constraint(greaterThanOrEqualTo: view.heightAnchor),
        ])
        return column
    }

    private static func owner(_ handle: String, look: Look) -> NSView {
        let name = NSTextField(labelWithAttributedString: NSAttributedString(string: handle, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ]))
        let row = NSStackView(views: [Initial(handle, look: look), name])
        row.spacing = 7
        row.alignment = .centerY
        return row
    }
}
