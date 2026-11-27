import AppKit
import SailAPI
import SailControls
import SailLook

/// The control plane as the user menu's line: a 6 pt square in its state's colour (green connected,
/// teal reconnecting, crimson out of reach), the address in mono in the secondary ink cut in its
/// middle, and the state's word at the line's end in the tertiary ink (crimson when out of reach), at
/// the micro step. Mirrored right to left.
@MainActor
final class ControlPlaneRow: NSView {
    static let square = 6.0, gap = 8.0

    var look: Look {
        didSet { repaint() }
    }

    private let mono: LabelFont
    private let points = Look.Step.micro.points
    private let address = NSTextField(labelWithString: "")
    private let state = NSTextField(labelWithString: "")
    private(set) var plane: UserMenuContent.Plane?
    private(set) var said = ""

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        super.init(frame: .zero)
        for label in [address, state] {
            label.lineBreakMode = .byTruncatingMiddle
            label.maximumNumberOfLines = 1
            addSubview(label)
        }
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// Says `address` and its state.
    func show(address words: String, plane: UserMenuContent.Plane) {
        said = words
        self.plane = plane
        setAccessibilityLabel("\(words), \(plane.said)")
        repaint()
    }

    /// The height a line of it takes.
    var height: Double {
        ceil(points * 1.45)
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: height)
    }

    /// The square's ink: green connected, teal reconnecting, crimson out of reach.
    var ink: Tint? {
        switch plane {
        case .connected?: Tint(look.success)
        case .reconnecting?: Tint(look.info)
        case .unreachable?: Tint(look.error)
        case nil: nil
        }
    }

    /// Where the square is drawn, in its own bounds.
    var squareRect: NSRect {
        mirrored(NSRect(x: 0, y: (Double(bounds.height) - Self.square) / 2, width: Self.square, height: Self.square))
    }

    private func mirrored(_ rect: NSRect) -> NSRect {
        guard userInterfaceLayoutDirection == .rightToLeft else { return rect }
        return NSRect(x: Double(bounds.width) - rect.maxX, y: rect.minY, width: rect.width, height: rect.height)
    }

    override func layout() {
        super.layout()
        let width = Double(bounds.width), height = Double(bounds.height)
        // The words' own width and the label cell's 2 pt either side: an intrinsic width cut
        // "Connected" to "Connect…" in the menu's frames.
        let stateWidth = ceil(Double(state.attributedStringValue.size().width)) + 4
        let line = ceil(Double(address.attributedStringValue.size().height)) + 1
        let top = (height - line) / 2
        state.frame = mirrored(NSRect(x: width - stateWidth, y: top, width: stateWidth, height: line))
        let start = Self.square + Self.gap
        address.frame = mirrored(NSRect(x: start, y: top, width: max(width - start - stateWidth - Self.gap, 0), height: line))
    }

    private func repaint() {
        let rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        func words(_ text: String, _ font: NSFont, _ ink: Tint, trailing: Bool, cut: NSLineBreakMode) -> NSAttributedString {
            let style = NSMutableParagraphStyle()
            style.lineBreakMode = cut
            style.alignment = rightToLeft != trailing ? .right : .left
            return NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style])
        }
        address.attributedStringValue = words(said, mono.font(points, .regular) as NSFont, look.textSecondary, trailing: false, cut: .byTruncatingMiddle)
        let stateInk = plane == .unreachable ? Tint(look.error) : look.textTertiary
        state.attributedStringValue = words(plane?.said ?? "", .systemFont(ofSize: points), stateInk, trailing: true, cut: .byTruncatingTail)
        needsLayout = true
        needsDisplay = true
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        repaint()
    }

    override var userInterfaceLayoutDirection: NSUserInterfaceLayoutDirection {
        didSet { repaint() }
    }

    override func draw(_: NSRect) {
        guard let ink else { return }
        NSColor(ink).setFill()
        squareRect.fill()
    }
}

extension ControlPlaneRow: Marked {
    package var drawnMarks: [Mark] {
        look.marks(fill: ink, area: Self.square * Self.square)
    }
}

extension ControlPlane {
    /// The control plane as the menu and the Account view name it: its host, and its port when it
    /// has one.
    var named: String {
        url.host().map { host in url.port.map { "\(host):\($0)" } ?? host } ?? url.absoluteString
    }
}
