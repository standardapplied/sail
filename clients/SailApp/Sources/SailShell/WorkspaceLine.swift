import AppKit
import SailAPI
import SailControls
import SailLook

/// A workspace row's words as the approved list sets them: the name in 14 medium over its instance
/// in mono 11, in a column that takes what is left; the state's pill in a 112 pt column; and a 104 pt
/// column with someone else's initial and handle, or on the person's own how many of their sessions
/// run in it ("2 sessions"). Narrow, as under a 720 pt card, it drops the owner's column, says whose
/// the workspace is in place of its instance, and its sessions after it.
@MainActor
final class WorkspaceLine: NSStackView {
    static let pillColumn = 112.0, ownerColumn = 104.0
    /// Rows' words made, ever: a redraw makes them only for the rows it makes, never for every row.
    private(set) static var made = 0

    let title: NSTextField
    let place: NSTextField
    let pill: Pill
    /// The owner's column; nil when narrow.
    let whose: NSView?
    /// The owner's handle in full, on hover (a tracking area does not keep its owner).
    let ownerTip: Tooltip?
    /// The name and the line under it in full, on hover, while cut.
    let titleTip: Tooltip
    let placeTip: Tooltip

    init(_ line: WorkspacesModel.Line, narrow: Bool, look: Look, mono: LabelFont) {
        Self.made += 1
        title = NSTextField(labelWithAttributedString: NSAttributedString(string: line.name, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.body.points, weight: .medium), .foregroundColor: NSColor(look.text),
        ]).cut(.byTruncatingTail))
        let theirs = narrow ? line.owner.map { Self.whose($0) } : nil
        let below = ([theirs ?? line.instance] + [narrow ? line.sessionsSaid : nil].compactMap { $0 }).joined(separator: " · ")
        place = NSTextField(labelWithAttributedString: NSAttributedString(string: below, attributes: [
            .font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(look.textTertiary),
        ]).cut(theirs == nil ? .byTruncatingMiddle : .byTruncatingTail))
        titleTip = Self.tip(line.name, look: look)
        placeTip = Self.tip(below, look: look)
        pill = Pill(Self.pill(line), look: look, mono: mono)
        let owned = narrow ? nil : line.owner.map { Self.owner($0, look: look) }
        ownerTip = owned?.tip
        let counted = narrow ? nil : line.sessionsSaid.map { Self.secondary($0, look: look) }
        whose = narrow ? nil : Self.column(owned?.view ?? counted, width: Self.ownerColumn)
        super.init(frame: .zero)
        for (label, tip) in [(title, titleTip), (place, placeTip)] {
            label.lineBreakMode = .byTruncatingTail
            label.maximumNumberOfLines = 1
            label.setContentCompressionResistancePriority(Self.cutShort, for: .horizontal)
            tip.attach(to: label, saying: label.stringValue)
        }
        let words = NSStackView(views: [title, place])
        words.orientation = .vertical
        words.alignment = .leading
        words.spacing = 3
        words.setHuggingPriority(.defaultLow, for: .horizontal)
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

    /// The pill a row shows: Creating only for the create under way here; each state the server
    /// sends by its own word, and Unknown for one this app does not know.
    static func pill(_ line: WorkspacesModel.Line) -> PillState {
        guard !line.creating else { return .creating }
        switch line.busy {
        case .stop?: return .stopping
        case .delete?: return .deleting
        case nil: break
        }
        return switch line.state {
        case .known(.running): .running
        case .known(.stopped): .stopped
        case .known(.frozen): .paused
        case .known(.notCreated): .notCreated
        case .known(.incomplete): .incomplete
        case .known(.error): .error
        case .unknown: .unknown
        }
    }

    /// Whose a row is in its owner's 104 pt column ("ana", "inactive", "no one"): an owner whose
    /// account is not active is said in a word that fits it.
    nonisolated static func said(_ owner: WorkspacesModel.Owner) -> String {
        switch owner {
        case let .person(handle): handle
        case .inactive: "inactive"
        case .nobody: "no one"
        }
    }

    /// Whose a row is under its name when narrow, where there is room ("ana's", "an inactive
    /// account's", "no one's").
    nonisolated static func whose(_ owner: WorkspacesModel.Owner) -> String {
        switch owner {
        case let .person(handle): handle + "'s"
        case .inactive: "an inactive account's"
        case .nobody: "no one's"
        }
    }

    private static func tip(_ words: String, look: Look) -> Tooltip {
        let tip = Tooltip(words, look: look, mono: nil)
        tip.onlyWhenCut = true
        return tip
    }

    /// A column `width` wide holding `view` at its start, never past its end.
    private static func column(_ view: NSView?, width: Double) -> NSView {
        let column = NSView()
        column.widthAnchor.constraint(equalToConstant: width).isActive = true
        guard let view else { return column }
        view.translatesAutoresizingMaskIntoConstraints = false
        column.addSubview(view)
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: column.leadingAnchor), view.trailingAnchor.constraint(lessThanOrEqualTo: column.trailingAnchor),
            view.centerYAnchor.constraint(equalTo: column.centerYAnchor), column.heightAnchor.constraint(greaterThanOrEqualTo: view.heightAnchor),
        ])
        return column
    }

    /// Words in the owner's column, as a person's handle is set there.
    private static func secondary(_ words: String, look: Look) -> NSTextField {
        let label = NSTextField(labelWithAttributedString: NSAttributedString(string: words, attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.textSecondary),
        ]))
        label.maximumNumberOfLines = 1
        return label
    }

    /// The owner's column: a person's initial and handle, cut at its end in the column, in full on
    /// hover; an inactive account or no one in the tertiary ink, with no initial.
    private static func owner(_ owner: WorkspacesModel.Owner, look: Look) -> (view: NSView, tip: Tooltip) {
        let person: String? = switch owner {
        case let .person(handle): handle
        case .inactive, .nobody: nil
        }
        let name = NSTextField(labelWithAttributedString: NSAttributedString(string: said(owner), attributes: [
            .font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(person == nil ? look.textTertiary : look.textSecondary),
        ]).cut(.byTruncatingTail))
        name.lineBreakMode = .byTruncatingTail
        name.maximumNumberOfLines = 1
        name.setContentCompressionResistancePriority(cutShort, for: .horizontal)
        let tooltip = Tooltip(said(owner), look: look, mono: nil)
        tooltip.onlyWhenCut = true
        tooltip.attach(to: name, saying: said(owner))
        var views: [NSView] = person.map { [Initial($0, look: look)] } ?? []
        views.append(name)
        let row = NSStackView(views: views)
        row.spacing = 7
        row.alignment = .centerY
        row.setClippingResistancePriority(.defaultLow, for: .horizontal)
        return (row, tooltip)
    }
}
