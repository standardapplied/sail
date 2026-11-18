import AppKit
import SailAPI
import SailControls
import SailLook

/// A workspace row's words as the approved list sets them: the name in 14 medium over its instance
/// in mono 11, in a column that takes what is left; the state's pill in a 112 pt column; and, when
/// the workspace is someone else's, their initial and handle in 13 secondary in a 104 pt column.
@MainActor
public final class WorkspaceLine: NSStackView {
    public static let pillColumn = 112.0, ownerColumn = 104.0

    public init(name: String, instance: String, state: PillState, owner: String?, look: Look, mono: LabelFont) {
        super.init(frame: .zero)
        let title = NSTextField(labelWithAttributedString: NSAttributedString(string: name, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.body.points, weight: .medium), .foregroundColor: NSColor(look.text),
        ]))
        let place = NSTextField(labelWithAttributedString: NSAttributedString(string: instance, attributes: [
            .font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary),
        ]))
        for label in [title, place] {
            label.lineBreakMode = .byTruncatingTail
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        let words = NSStackView(views: [title, place])
        words.orientation = .vertical
        words.alignment = .leading
        words.spacing = 3
        let pill = Self.column(Pill(state, look: look, mono: mono), width: Self.pillColumn)
        let whose = Self.column(owner.map { Self.owner($0, look: look) } ?? NSView(), width: Self.ownerColumn)
        setViews([words, pill, whose], in: .leading)
        words.setContentHuggingPriority(.defaultLow, for: .horizontal)
        spacing = 0
        alignment = .centerY
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// The pill a workspace's state shows.
    public static func pill(_ state: Open<WorkspaceState>) -> PillState {
        switch state {
        case .known(.running): return .running
        case .known(.stopped): return .stopped
        case .known(.frozen): return .paused
        case .known(.notCreated): return .creating
        default: return .incomplete
        }
    }

    private static func column(_ view: NSView, width: Double) -> NSView {
        let column = NSView()
        view.translatesAutoresizingMaskIntoConstraints = false
        column.addSubview(view)
        NSLayoutConstraint.activate([
            column.widthAnchor.constraint(equalToConstant: width), view.leadingAnchor.constraint(equalTo: column.leadingAnchor),
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
