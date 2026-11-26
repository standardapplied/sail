import AppKit
import SailAPI
import SailControls
import SailLook
import SailTerminalView

/// The account signed in, in the user menu's language: the person's name as the display heading,
/// `@handle · Role` under SIGNED IN AS, the control plane as the menu's line under CONTROL PLANE
/// (its address, state square and word, live as checks start and end), the session's end in quiet
/// text, and Sign Out (secondary). Why `whoami` could not confirm the session, or why the vault
/// would not forget it, is said under the control plane's line: its sentence in crimson after the
/// error's mark, the rest in the secondary ink. Signed out it shows nothing: the welcome is on
/// Workspaces.
@MainActor
public final class AccountPane: NSView, NSMenuItemValidation {
    public let signOut: SailButton
    public let said = NSTextField(wrappingLabelWithString: "")
    public let flow: AccountFlow

    /// The look it is drawn in; a new one repaints it.
    var look: Look {
        didSet { painter.lookChanged(hidden: isHidden) }
    }

    private lazy var painter = PaintWhenShown { [unowned self] in self.paint() }

    /// How many times the pane was painted, wherever from: none while it is hidden at a change of
    /// look.
    package private(set) var lookPaints = 0

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        painter.shown()
    }

    private let mono: TerminalFonts
    /// What the pane says now under the control plane's line, a line each.
    private(set) var lines: [String] = []
    /// Why the vault would not forget the session at Sign Out, said until who is signed in changes.
    private var signOutLines: [String] = []
    private var shownFor: SignedIn?
    /// The facts' mono eyebrows.
    private(set) var eyebrows: [NSTextField] = []
    /// The display heading: who is signed in; cut at its end, said in full on hover.
    let heading = NSTextField(labelWithString: "")
    /// `@handle · Role`; cut at its end, said in full on hover.
    let handle = NSTextField(labelWithString: "")
    private(set) lazy var headingTip = Tooltip("", look: look, mono: nil)
    private(set) lazy var handleTip = Tooltip("", look: look, mono: nil)
    private let headingFont: NSFont
    /// The control plane as the user menu draws it.
    let planeRow: ControlPlaneRow
    /// Until when the session lasts, or that it is not yet confirmed, in quiet text that wraps.
    let until = NSTextField(wrappingLabelWithString: "")

    init(flow: AccountFlow, look: Look, title: TitleFont, mono: TerminalFonts, labels: LabelFont) {
        self.flow = flow
        self.look = look
        self.mono = mono
        planeRow = ControlPlaneRow(look: look, mono: labels, points: Look.Step.callout.points)
        headingFont = title.font(.title2) as NSFont
        signOut = SailButton("Sign Out", kind: .secondary, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        compose()
        show()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Everything the pane says now, a line each, as the drive reads it.
    public var text: String {
        guard flow.standing != nil else { return "" }
        return ([heading.stringValue, handle.stringValue, "\(planeRow.said) · \(planeRow.plane?.said ?? "")", until.stringValue] + lines).filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// Signs out at once, asking nothing: what the sign-in opened on the runner lives on there,
    /// for the same person's next sign-in.
    @objc public func signOutHere(_: Any?) {
        let refused = flow.signOut()
        signOutLines = refused?.lines ?? []
        shownFor = flow.standing
        show()
    }

    public func validateMenuItem(_ item: NSMenuItem) -> Bool {
        item.action != #selector(signOutHere(_:)) || (flow.standing != nil && !flow.isActing)
    }

    /// Draws what the flow knows now: who is signed in, the control plane's line, the session's
    /// end, and why `whoami` could not confirm it. Sign Out is held while a person's action runs.
    func show() {
        let standing = flow.standing
        if standing != shownFor {
            signOutLines = []
            shownFor = standing
        }
        let whoami = try? standing?.whoami.get()
        let ends = standing?.until.formatted(date: .long, time: .shortened) ?? ""
        until.stringValue = standing == nil ? "" : whoami == nil ? "Not yet confirmed: remembered on this Mac until \(ends)" : "Signed in until \(ends)"
        signOut.isEnabled = !flow.isActing
        paintFacts()
        var now = signOutLines
        if case let .failure(refusal)? = standing?.whoami { now += refusal.lines }
        say(now)
    }

    /// The heading, the handle and the control plane's line, from what the menu says.
    private func paintFacts() {
        let content = flow.menuContent
        let signedIn = flow.standing != nil
        set(heading, to: signedIn ? content.name : "", font: headingFont, ink: look.text, tip: headingTip)
        set(handle, to: signedIn ? content.detail ?? "" : "", font: monoFont(.callout, from: mono), ink: look.textSecondary, tip: handleTip)
        if signedIn, let address = content.address, let plane = content.plane { planeRow.show(address: address, plane: plane) }
    }

    /// `label` saying `words` on one line, cut at its end, with `tip` saying them in full when cut.
    private func set(_ label: NSTextField, to words: String, font: NSFont, ink: Tint, tip: Tooltip) {
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byTruncatingTail
        label.attributedStringValue = NSAttributedString(string: words, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style])
        tip.text = words
    }

    private func paint() {
        lookPaints += 1
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        until.textColor = NSColor(look.textTertiary)
        headingTip.look = look
        handleTip.look = look
        eyebrows.forEach { $0.textColor = NSColor(look.textSecondary) }
        planeRow.look = look
        signOut.look = look
        show()
    }

    /// Says `lines` under the control plane's line, the first a refusal's sentence.
    private func say(_ lines: [String]) {
        self.lines = lines
        said.attributedStringValue = refusalWords(lines, look: look, scale: Double(window?.backingScaleFactor ?? 2))
        said.isHidden = lines.isEmpty
    }

    private func label(_ text: String) -> NSTextField {
        let label = eyebrow(text, mono: mono)
        eyebrows.append(label)
        return label
    }

    private func compose() {
        wantsLayer = true
        until.font = .systemFont(ofSize: Look.Step.footnote.points)
        for (label, tip) in [(heading, headingTip), (handle, handleTip)] {
            label.maximumNumberOfLines = 1
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            tip.onlyWhenCut = true
            tip.attach(to: label, saying: "")
        }
        until.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        signOut.target = self
        signOut.action = #selector(signOutHere(_:))
        said.isSelectable = true
        said.isHidden = true
        let rows = NSStackView(views: [label("Signed in as"), handle, label("Control plane"), planeRow, said, until, signOut])
        rows.orientation = .vertical
        rows.alignment = .leading
        rows.spacing = Look.Space.s
        rows.setCustomSpacing(Look.Space.l, after: handle)
        rows.setCustomSpacing(Look.Space.l, after: until)
        let column = NSStackView(views: [heading, rows])
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = Look.Space.l
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        let wide = planeRow.widthAnchor.constraint(equalToConstant: WelcomeView.columnWidth)
        wide.priority = .defaultHigh
        NSLayoutConstraint.activate([
            column.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.x3l + Look.Space.l),
            column.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Look.Space.x3l),
            column.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -Look.Space.x3l),
            said.widthAnchor.constraint(lessThanOrEqualTo: planeRow.widthAnchor),
            until.widthAnchor.constraint(lessThanOrEqualTo: planeRow.widthAnchor), wide,
        ])
        paint()
    }
}

/// JetBrains Mono at `step`, the face the terminal uses.
@MainActor
func monoFont(_ step: Look.Step, from mono: TerminalFonts) -> NSFont {
    (try? mono.faces(points: step.points, scale: 1).font(.regular)).map { $0 as NSFont }
        ?? .monospacedSystemFont(ofSize: step.points, weight: .regular)
}

/// A group's eyebrow, as "CONTROL PLANE" is: its name upper case in JetBrains Mono at the micro
/// step, which its pane paints in the secondary text colour.
@MainActor
func eyebrow(_ text: String, mono: TerminalFonts) -> NSTextField {
    let label = NSTextField(labelWithString: text.uppercased())
    label.font = monoFont(.micro, from: mono)
    return label
}
