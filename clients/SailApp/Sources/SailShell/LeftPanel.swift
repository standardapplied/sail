import AppKit
import SailAPI
import SailControls
import SailLook

/// The app's one left panel, as the approved navigation draws it, with the Workspaces section's
/// projects: a 46 pt header with the section's title over a hairline, the projects as the panel's
/// project rows (tile, name, readiness pill) under a "Projects" head, and a foot under a hairline
/// with who is signed in and the control plane in mono 11. It is 236 pt by default; its trailing
/// edge drags it between 200 and 360 pt, remembered on this Mac.
@MainActor
package final class LeftPanel: NSView {
    static let width = (standard: 236.0, least: 200.0, most: 360.0), header = 46.0, edge = 6.0
    static let remembered = "leftPanel.width"

    var onChoose: @MainActor (String) -> Void = { _ in }
    var look: Look {
        didSet { needsDisplay = true }
    }

    let list: SailList
    let heading = NSTextField(labelWithString: "Workspaces")
    let who = NSTextField(labelWithString: "")
    let place = NSTextField(labelWithString: "")
    private let mono: LabelFont
    private(set) var widthConstraint: NSLayoutConstraint!
    private var projects: [WorkspacesModel.Project] = []

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        list = SailList(rowStyle: .sidebar, look: look, mono: mono)
        super.init(frame: .zero)
        heading.font = .systemFont(ofSize: 15, weight: .semibold)
        heading.textColor = NSColor(look.text)
        let foot = NSStackView(views: [who, place])
        foot.orientation = .vertical
        foot.alignment = .leading
        foot.spacing = 0
        for view in [heading, list, foot] as [NSView] {
            view.translatesAutoresizingMaskIntoConstraints = false
            addSubview(view)
        }
        list.translatesAutoresizingMaskIntoConstraints = true
        let width = UserDefaults.standard.object(forKey: Self.remembered) as? Double ?? Self.width.standard
        widthConstraint = widthAnchor.constraint(equalToConstant: Self.clamped(width))
        NSLayoutConstraint.activate([
            widthConstraint, heading.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            heading.centerYAnchor.constraint(equalTo: topAnchor, constant: Self.header / 2),
            foot.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14), foot.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),
        ])
        list.onChoose = { [weak self] name in if let name { self?.onChoose(name) } }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    static func clamped(_ width: Double) -> Double {
        min(max(width, Self.width.least), Self.width.most)
    }

    /// Shows `panel`'s projects, its chosen one chosen, and who is signed in.
    func render(_ panel: WorkspacesModel.Panel) {
        if panel.projects != projects {
            projects = panel.projects
            list.entries = [.head("Projects", count: panel.projects.count)] + panel.projects.map { project in
                .item(row(project))
            }
        }
        if list.chosen != panel.chosen { list.choose(panel.chosen) }
        who.attributedStringValue = Self.foot(panel.who, look.textSecondary, mono)
        place.attributedStringValue = Self.foot(panel.controlPlane, look.textTertiary, mono)
        needsLayout = true
    }

    /// A project's row: its tile, its name and its template's pill; VoiceOver reads its name and pill.
    private func row(_ project: WorkspacesModel.Project) -> SailList.Item {
        let pill = Self.pill(project.mark)
        let voice = pill.map { "\(project.name), \($0.label.lowercased())" } ?? project.name
        return SailList.Item(id: project.name, name: project.name, voice: voice, content: SidebarRowCell(project.name, template: pill, look: look, mono: mono))
    }

    static func pill(_ mark: WorkspacesModel.Mark?) -> PillState? {
        switch mark {
        case .noTemplate: .noTemplate
        case .building: .building
        case .buildFailed: .buildFailed
        case nil: nil
        }
    }

    private static func foot(_ words: String, _ ink: Tint, _ mono: LabelFont) -> NSAttributedString {
        let lines = NSMutableParagraphStyle()
        lines.minimumLineHeight = (Look.Step.micro.points * 1.5).rounded()
        lines.maximumLineHeight = lines.minimumLineHeight
        return NSAttributedString(string: words, attributes: [.font: mono.font(Look.Step.micro.points, .regular) as NSFont, .foregroundColor: NSColor(ink), .paragraphStyle: lines])
    }

    private var footTop: Double {
        Double(bounds.height) - 12 - 2 * (Look.Step.micro.points * 1.5).rounded() - 10
    }

    override public func layout() {
        super.layout()
        let top = Self.header + 4
        let rows = Double(list.rect(ofRow: max(list.numberOfRows - 1, 0)).maxY)
        list.frame = NSRect(x: 0, y: top, width: Double(bounds.width), height: min(rows, max(footTop - top, 0)))
    }

    override public func draw(_: NSRect) {
        NSColor(Tint(look.surface)).setFill()
        bounds.fill()
        NSColor(look.separator).setFill()
        NSRect(x: 0, y: Self.header, width: Double(bounds.width), height: Look.hairline).fill()
        NSRect(x: 0, y: footTop, width: Double(bounds.width), height: Look.hairline).fill()
        NSRect(x: Double(bounds.width) - Look.hairline, y: 0, width: Look.hairline, height: Double(bounds.height)).fill()
    }

    // MARK: The edge

    override public func resetCursorRects() {
        addCursorRect(NSRect(x: Double(bounds.width) - Self.edge, y: 0, width: Self.edge, height: Double(bounds.height)), cursor: .resizeLeftRight)
    }

    override public func mouseDown(with event: NSEvent) {
        let at = convert(event.locationInWindow, from: nil)
        guard Double(at.x) >= Double(bounds.width) - Self.edge else { return super.mouseDown(with: event) }
        let start = Double(event.locationInWindow.x), from = Double(widthConstraint.constant)
        while let next = window?.nextEvent(matching: [.leftMouseDragged, .leftMouseUp]), next.type == .leftMouseDragged {
            resize(to: from + Double(next.locationInWindow.x) - start)
        }
        UserDefaults.standard.set(Double(widthConstraint.constant), forKey: Self.remembered)
    }

    /// The panel `width` wide, kept between its least and most.
    func resize(to width: Double) {
        widthConstraint.constant = Self.clamped(width)
    }
}
