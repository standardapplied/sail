import AppKit
import SailAPI
import SailControls
import SailLook

/// The signed-out Workspaces card (section 1 of the first impressions page): a centred 380 pt
/// column asking one question a screen (``Welcome``) with the app's version at the card's foot.
/// Welcome: Sail's still mark, the Newsreader title, the CONTROL PLANE field (holding the keys),
/// Continue and a quiet line whose link opens Terminals. Enroll: the mark, the reached control plane
/// as a chip with Change, the ENROLLMENT TICKET field and Create Passkey. Welcome back: the title by
/// name, Sign In with Passkey, the chip centred under it. Under way, the primary says its "-ing"
/// word with its spinner and never narrows, the field dims and takes no input (Continue's address
/// still takes keys, and an edit stops the check), and a note over the primary says why the
/// system's sheet is up. A refusal stands under the field it is about (on Welcome back, under the
/// primary, and VoiceOver hears it): its sentence in crimson with the error's mark, its remedy in
/// the secondary ink. Return presses Welcome back's primary. Each screen's own link leads to the
/// other screen.
@MainActor
public final class WelcomeView: NSView, NSTextFieldDelegate {
    static let columnWidth = 380.0

    public private(set) var welcome: Welcome
    public let flow: AccountFlow
    /// Terminals, from the welcome's quiet line.
    var onTerminals: @MainActor () -> Void = {}

    /// The look it is drawn in; a new one repaints it, once it is shown.
    var look: Look {
        didSet { painter.lookChanged(hidden: isHidden) }
    }

    private lazy var painter = PaintWhenShown { [unowned self] in self.paint() }
    /// How many times it was painted: none while it is hidden at a change of look.
    package private(set) var lookPaints = 0

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        painter.shown()
    }

    let mark: BrandMark
    /// The control plane over Enroll's title, and under Welcome back's primary, centred.
    package let chip: PlaneChip
    package let chipUnder: PlaneChip
    let heading = NSTextField(wrappingLabelWithString: "")
    let lede = NSTextField(wrappingLabelWithString: "")
    package let addressField: SailTextField
    package let ticketField: SailTextField
    package let proceed: SailButton
    package let create: SailButton
    package let signIn: SailButton
    let note: TouchNote
    let said = NSTextField(wrappingLabelWithString: "")
    package let toTerminals: LinkButton
    package let toSignIn: LinkButton
    package let toEnroll: LinkButton
    let version: NSTextField
    private(set) var banner: Banner?
    /// The refusal VoiceOver was last told on Welcome back, nil since none stands.
    package private(set) var announced: String?

    private let titleFont: TitleFont
    private let mono: LabelFont
    /// The centred column every screen stands in.
    let column = NSStackView()
    /// The quiet line under the primary: words, the screen's link, words after it.
    let quietLine = WordLine(inset: nil)
    private let before = NSTextField(labelWithString: "")
    private let after = NSTextField(labelWithString: "")
    private lazy var chipRow = Self.holding(chip, centred: false)
    private lazy var chipUnderRow = Self.holding(chipUnder, centred: true)

    init(flow: AccountFlow, look: Look, title: TitleFont, mono: LabelFont, version: String?) {
        self.flow = flow
        self.look = look
        titleFont = title
        self.mono = mono
        welcome = flow.memory.welcome
        mark = BrandMark(.still, look: look, mono: mono)
        chip = PlaneChip(look: look, mono: mono)
        chipUnder = PlaneChip(look: look, mono: mono)
        let address = Welcome.addressField, ticket = Welcome.ticketField
        addressField = SailTextField(label: address.label, placeholder: address.placeholder, size: .large, look: look, mono: mono)
        ticketField = SailTextField(label: ticket.label, placeholder: ticket.placeholder, size: .large, secure: ticket.secure, look: look, mono: mono)
        proceed = SailButton(Welcome.primary(.address), kind: .primary, size: .large, look: look, target: nil, action: nil)
        create = SailButton(Welcome.primary(.ticket), kind: .primary, size: .large, icon: .fingerprint, look: look, target: nil, action: nil)
        signIn = SailButton(Welcome.primary(.back), kind: .primary, size: .large, icon: .fingerprint, look: look, target: nil, action: nil)
        note = TouchNote(look: look)
        toTerminals = LinkButton(Welcome.other(.address).link, look: look, target: nil, action: nil)
        toSignIn = LinkButton(Welcome.other(.ticket).link, look: look, target: nil, action: nil)
        toEnroll = LinkButton(Welcome.other(.back).link, look: look, target: nil, action: nil)
        self.version = NSTextField(labelWithString: Welcome.version(version))
        super.init(frame: .zero)
        compose()
        render()
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    /// Everything the welcome says now, a line each, as the drive reads it: the title, the
    /// control plane when its chip shows, the banner, the note and the refusal.
    public var text: String {
        let host = welcome.screen == .address ? nil : welcome.host
        return ([welcome.title, host, welcome.banner, welcome.note?.words] + welcome.said.map(Optional.some)).compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// The primary shown now.
    public var primary: SailButton {
        switch welcome.screen {
        case .address: proceed
        case .ticket: create
        case .back: signIn
        }
    }

    /// The chip shown now, nil on Welcome.
    package var shownChip: PlaneChip? {
        switch welcome.screen {
        case .address: nil
        case .ticket: chip
        case .back: chipUnder
        }
    }

    /// The field shown now, nil on Welcome back.
    public var field: SailTextField? {
        switch welcome.screen {
        case .address: addressField
        case .ticket: ticketField
        case .back: nil
        }
    }

    /// The link of the quiet line shown now.
    public var link: LinkButton {
        switch welcome.screen {
        case .address: toTerminals
        case .ticket: toSignIn
        case .back: toEnroll
        }
    }

    /// Signed out again: the screen this Mac's memory chooses, the field holding the keys.
    package func reset() {
        welcome = flow.memory.welcome
        render(claim: true)
    }

    /// A check's answer that left no one: an ended session's banner on Welcome back, or its refusal
    /// under the primary there.
    func checked(_ answer: Result<SignedIn?, AccountRefusal>, endedOn: Date?) {
        guard case let .failure(refusal) = answer, flow.standing == nil else { return }
        welcome = flow.memory.welcome
        if let endedOn {
            welcome.ended(on: endedOn)
        } else {
            welcome.signInInstead()
            welcome.refused(refusal)
        }
        render()
    }

    /// The drive's frames: any state, drawn as the flow would leave it.
    package func show(_ state: Welcome) {
        welcome = state
        render()
    }

    /// Continue: the control plane typed, reached.
    @objc func reach(_: Any?) {
        guard !flow.isActing else { return }
        let typed = addressField.field.stringValue
        welcome.began(.connecting)
        render()
        flow.reach(typed) { [weak self] result in
            guard let self else { return }
            switch result {
            case let .success(plane): welcome.reached(plane, person: flow.memory.signIn(at: plane))
            case let .failure(refusal): welcome.refused(refusal)
            }
            render(claim: true)
        }
    }

    /// Create Passkey: the ticket is read and cleared from the field at once.
    @objc func createPasskey(_: Any?) {
        guard !flow.isActing, let plane = welcome.plane else { return }
        let pasted = ticketField.field.stringValue
        ticketField.field.stringValue = ""
        welcome.began(.creating)
        render()
        flow.enroll(ticket: pasted, at: plane) { [weak self] handle in
            self?.welcome.began(.signingAfterEnrolling(handle: handle))
            self?.render()
        } refused: { [weak self] refusal in
            self?.welcome.refused(refusal)
            self?.render(claim: true)
        }
    }

    /// Sign In with Passkey, the primary of Welcome back or the link of Enroll: the sign-in runs at
    /// once, on Welcome back.
    @objc func signInWithPasskey(_: Any?) {
        guard !flow.isActing, let plane = welcome.plane else { return }
        if welcome.screen != .back { welcome.signInInstead() }
        welcome.began(.signingIn)
        render()
        flow.signIn(at: plane) { [weak self] refusal in
            self?.welcome.refused(refusal)
            self?.render()
        }
    }

    @objc func enrollInstead(_: Any?) {
        guard !flow.isActing else { return }
        welcome.enrollInstead()
        render(claim: true)
    }

    /// Change: Welcome, the field holding the control plane, selected.
    @objc func change(_: Any?) {
        guard !flow.isActing else { return }
        let typed = welcome.typed
        welcome.change()
        render()
        addressField.field.stringValue = typed
        if window?.makeFirstResponder(addressField.field) == true { addressField.field.selectText(nil) }
    }

    @objc func openTerminals(_: Any?) {
        onTerminals()
    }

    /// The address edited while Continue's check is under way: the check stops, and the welcome
    /// asks again.
    public func controlTextDidChange(_ note: Notification) {
        guard note.object as? NSTextField === addressField.field, welcome.stage == .connecting else { return }
        flow.stopReaching()
        welcome.change()
        render()
    }

    /// Draws `welcome`; `claim`, its field takes the keys when the window is key.
    private func render(claim: Bool = false) {
        let screen = welcome.screen, busy = welcome.stage != nil
        mark.isHidden = !welcome.showsMark
        chipRow.isHidden = screen != .ticket
        chipUnderRow.isHidden = screen != .back
        for each in [chip, chipUnder] {
            each.show(welcome.host, answers: welcome.answers)
            each.change.isEnabled = !busy
        }
        heading.attributedStringValue = Self.words(welcome.title, titleFont.font(.display) as NSFont, look.text, lineHeight: 1.1, tracking: -0.012)
        lede.attributedStringValue = Self.words(welcome.lede, .systemFont(ofSize: Look.Step.callout.points), look.textSecondary, lineHeight: 1.5)
        for (field, shown) in [(addressField, screen == .address), (ticketField, screen == .ticket)] {
            field.isHidden = !shown
            guard shown, let words = welcome.field else { continue }
            field.help = words.help
            field.error = welcome.said.first
            field.remedy = welcome.said.dropFirst().first
            field.isEnabled = !busy || welcome.stage == .connecting
            field.waits = busy
        }
        for button in [proceed, create, signIn] {
            button.isHidden = button !== primary
            button.fly(button === primary ? welcome.busyWord : nil)
        }
        signIn.isDefault = screen == .back
        note.isHidden = welcome.note == nil
        note.show(welcome.note)
        showBanner(welcome.banner)
        said.isHidden = screen != .back || welcome.said.isEmpty
        said.attributedStringValue = refusalWords(welcome.said, look: look, scale: Double(window?.backingScaleFactor ?? 2))
        announce(said.isHidden ? [] : welcome.said)
        for link in [toTerminals, toSignIn, toEnroll] {
            link.isHidden = link !== self.link
            link.isEnabled = !busy
        }
        before.stringValue = welcome.other.before
        after.stringValue = welcome.other.after ?? ""
        after.isHidden = welcome.other.after == nil
        quietLine.wordsChanged()
        guard claim, let field, window?.isKeyWindow == true else { return }
        window?.makeFirstResponder(field.field)
    }

    /// Tells VoiceOver a refusal that came to stand under Welcome back's primary, once.
    private func announce(_ lines: [String]) {
        let words = lines.joined(separator: " ")
        defer { announced = words.isEmpty ? nil : words }
        guard !words.isEmpty, words != announced else { return }
        NSAccessibility.post(element: said, notification: .announcementRequested, userInfo: [.announcement: words, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    /// Takes the keys into the field shown, as the welcome opens.
    func claimKeys() {
        guard let field else { return }
        window?.makeFirstResponder(field.field)
    }

    /// The session-ended banner, over Welcome back's primary, after the lede.
    private func showBanner(_ words: String?) {
        guard words != banner?.accessibilityLabel() else { return }
        banner?.removeFromSuperview()
        banner = words.map { Banner(.info, title: $0, bold: false, look: look) }
        guard let banner, let at = column.arrangedSubviews.firstIndex(of: lede) else { return }
        column.insertArrangedSubview(banner, at: at + 1)
        fill(banner)
    }

    /// `chip` in a row as wide as the column, at its leading edge or centred.
    private static func holding(_ chip: PlaneChip, centred: Bool) -> NSView {
        let row = NSView()
        chip.translatesAutoresizingMaskIntoConstraints = false
        row.addSubview(chip)
        NSLayoutConstraint.activate([
            chip.topAnchor.constraint(equalTo: row.topAnchor), chip.bottomAnchor.constraint(equalTo: row.bottomAnchor),
            centred ? chip.centerXAnchor.constraint(equalTo: row.centerXAnchor) : chip.leadingAnchor.constraint(equalTo: row.leadingAnchor),
            chip.leadingAnchor.constraint(greaterThanOrEqualTo: row.leadingAnchor), chip.trailingAnchor.constraint(lessThanOrEqualTo: row.trailingAnchor),
        ])
        return row
    }

    private func paint() {
        lookPaints += 1
        layer?.backgroundColor = NSColor(look.canvas).cgColor
        mark.look = look
        chip.look = look
        chipUnder.look = look
        note.look = look
        banner?.look = look
        for field in [addressField, ticketField] {
            field.look = look
        }
        for button in [proceed, create, signIn] {
            button.look = look
        }
        for link in [toTerminals, toSignIn, toEnroll] {
            link.look = look
        }
        let words = NSFont.systemFont(ofSize: Look.Step.footnote.points)
        for label in [before, after] {
            label.font = words
            label.textColor = NSColor(look.textTertiary)
        }
        version.font = mono.font(Look.Step.micro.points, .regular) as NSFont
        version.textColor = NSColor(look.textTertiary)
        render()
    }

    static func words(_ text: String, _ font: NSFont, _ ink: Tint, lineHeight: Double, tracking: Double = 0) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.alignment = .natural
        style.minimumLineHeight = (lineHeight * Double(font.pointSize)).rounded()
        style.maximumLineHeight = style.minimumLineHeight
        return NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style, .kern: tracking * Double(font.pointSize)])
    }

    /// `view` as wide as the column.
    private func fill(_ view: NSView) {
        view.widthAnchor.constraint(equalTo: column.widthAnchor).isActive = true
    }

    private func compose() {
        wantsLayer = true
        proceed.target = self
        proceed.action = #selector(reach(_:))
        create.target = self
        create.action = #selector(createPasskey(_:))
        signIn.target = self
        signIn.action = #selector(signInWithPasskey(_:))
        addressField.field.target = self
        addressField.field.action = #selector(reach(_:))
        addressField.field.delegate = self
        ticketField.field.target = self
        ticketField.field.action = #selector(createPasskey(_:))
        toTerminals.target = self
        toTerminals.action = #selector(openTerminals(_:))
        toSignIn.target = self
        toSignIn.action = #selector(signInWithPasskey(_:))
        toEnroll.target = self
        toEnroll.action = #selector(enrollInstead(_:))
        for each in [chip, chipUnder] {
            each.change.target = self
            each.change.action = #selector(change(_:))
        }
        said.isSelectable = true
        for label in [heading, lede, said] {
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        let space = Double((" " as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: Look.Step.footnote.points)]).width)
        quietLine.pieces = [WordLine.Piece(before)] + [toTerminals, toSignIn, toEnroll, after].map { WordLine.Piece($0, gap: space) }
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = Look.Space.xxl
        column.setViews([mark, chipRow, heading, lede, addressField, ticketField, note, proceed, create, signIn, said, chipUnderRow, quietLine], in: .top)
        column.setCustomSpacing(Look.Space.s, after: heading)
        column.translatesAutoresizingMaskIntoConstraints = false
        version.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        addSubview(version)
        for view in [chipRow, heading, lede, addressField, ticketField, note, proceed, create, signIn, said, chipUnderRow, quietLine] as [NSView] {
            fill(view)
        }
        let wide = column.widthAnchor.constraint(equalToConstant: Self.columnWidth)
        wide.priority = .defaultHigh
        let centred = column.centerYAnchor.constraint(equalTo: centerYAnchor)
        centred.priority = .defaultHigh
        NSLayoutConstraint.activate([
            wide, centred, column.centerXAnchor.constraint(equalTo: centerXAnchor),
            column.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -2 * Look.Space.l),
            column.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: Look.Space.l),
            column.bottomAnchor.constraint(lessThanOrEqualTo: version.topAnchor, constant: -Look.Space.l),
            version.centerXAnchor.constraint(equalTo: centerXAnchor), version.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Look.Space.l),
        ])
        paint()
    }
}

/// A refusal's lines as they stand under what they are about: the sentence in crimson after the
/// error's 14 pt mark, the rest in the secondary ink.
@MainActor
func refusalWords(_ lines: [String], look: Look, scale: Double) -> NSAttributedString {
    let words = NSMutableAttributedString(), font = NSFont.systemFont(ofSize: Look.Step.footnote.points)
    for (index, line) in lines.enumerated() {
        let ink = NSColor(index == 0 ? Tint(look.error) : look.textSecondary)
        if index == 0 {
            let side = FieldSize.mark
            let mark = NSTextAttachment()
            mark.image = MarkImage.image(.error, side: side, ink: Tint(look.error), scale: scale)
            mark.bounds = NSRect(x: 0, y: (Double(font.capHeight) - side) / 2, width: side, height: side)
            words.append(NSAttributedString(attachment: mark))
            words.append(NSAttributedString(string: " "))
        }
        words.append(NSAttributedString(string: (index == 0 ? "" : "\n") + line, attributes: [.foregroundColor: ink, .font: font]))
    }
    words.addAttribute(.font, value: font, range: NSRange(location: 0, length: words.length))
    return words
}

/// The control plane reached, as one chip: the cloud, the host in mono, a 6 pt square in its
/// state's colour (green once it answered, crimson when it did not, quiet before it was asked),
/// and Change. Mirrored right to left.
@MainActor
package final class PlaneChip: NSView {
    var look: Look {
        didSet { paint() }
    }

    package let change: LinkButton
    /// The chip's pieces, its words `Look.Space.s` in from either end.
    let row = WordLine(inset: Look.Space.s)
    private let cloud = NSImageView()
    private let host = NSTextField(labelWithString: "")
    private let square = NSView()
    private let mono: LabelFont
    private(set) var answers: Bool?

    init(look: Look, mono: LabelFont) {
        self.look = look
        self.mono = mono
        change = LinkButton(Welcome.changeLink, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        layer?.borderWidth = Look.hairline
        square.wantsLayer = true
        row.pieces = [
            WordLine.Piece(cloud, size: NSSize(width: FieldSize.mark, height: FieldSize.mark)), WordLine.Piece(host, gap: Look.Space.s, shrinks: true),
            WordLine.Piece(square, gap: Look.Space.s, size: NSSize(width: ControlPlaneRow.square, height: ControlPlaneRow.square)),
            WordLine.Piece(change, gap: Look.Space.m),
        ]
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor), row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            heightAnchor.constraint(equalToConstant: FieldSize.small.height),
        ])
        host.lineBreakMode = .byTruncatingMiddle
        host.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        row.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
        paint()
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    package var said: String {
        host.stringValue
    }

    func show(_ words: String, answers: Bool?) {
        host.stringValue = words
        row.wordsChanged()
        self.answers = answers
        setAccessibilityLabel(words)
        paint()
    }

    /// The square's ink: green once the control plane answered, crimson when it did not.
    var ink: Tint {
        switch answers {
        case true?: Tint(look.success)
        case false?: Tint(look.error)
        case nil: look.textTertiary
        }
    }

    override package func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        paint()
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.surface).cgColor
        layer?.borderColor = NSColor(look.separator).cgColor
        square.layer?.backgroundColor = NSColor(ink).cgColor
        cloud.image = MarkImage.image(.cloud, side: FieldSize.mark, ink: look.textSecondary, scale: Double(window?.backingScaleFactor ?? 2))
        host.font = mono.font(Look.Step.footnote.points, .medium) as NSFont
        host.textColor = NSColor(look.text)
        change.look = look
        row.wordsChanged()
    }
}

/// Views in one line laid out by what they draw, mirrored right to left: each piece's words (an
/// image or a square, its whole frame) stand exactly its `gap` after the words before them, the
/// line centred, at least `inset` in from both ends. AppKit's cells keep room around their words that they
/// never draw into (a label 2 pt each side, a link's cell more after its words), so a stack of them
/// spaced the words unevenly; this places the words themselves. A piece that `shrinks` gives up its
/// room, cut, where the line has too little.
@MainActor
final class WordLine: NSView {
    struct Piece {
        let view: NSView
        let gap: Double
        let size: NSSize?
        let shrinks: Bool

        init(_ view: NSView, gap: Double = 0, size: NSSize? = nil, shrinks: Bool = false) {
            self.view = view
            self.gap = gap
            self.size = size
            self.shrinks = shrinks
        }
    }

    private let inset: Double?
    var pieces: [Piece] = [] {
        didSet {
            oldValue.forEach { $0.view.removeFromSuperview() }
            pieces.forEach { addSubview($0.view) }
            wordsChanged()
        }
    }

    init(inset: Double?) {
        self.inset = inset
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    /// The words of a piece changed, or which are shown: called outside a layout pass.
    func wordsChanged() {
        invalidateIntrinsicContentSize()
        needsLayout = true
    }

    override var isFlipped: Bool {
        true
    }

    /// What a piece draws, in its frame: how far in its words start, how wide they are, and how wide
    /// and tall the frame is.
    private static func drawn(_ piece: Piece) -> (lead: Double, words: Double, frame: NSSize) {
        if let size = piece.size { return (0, Double(size.width), size) }
        let insets = piece.view.alignmentRectInsets
        let words: Double = if let button = piece.view as? NSButton {
            Double(button.attributedTitle.size().width)
        } else {
            Double((piece.view as? NSTextField)?.attributedStringValue.size().width ?? 0)
        }
        let room = piece.view is NSButton ? Double(piece.view.intrinsicContentSize.width) : words + Double(insets.left + insets.right)
        return (Double(insets.left), words, NSSize(width: room, height: piece.view.intrinsicContentSize.height))
    }

    private var shown: [Piece] {
        pieces.filter { !$0.view.isHidden }
    }

    /// The words' width end to end, the gaps between them included.
    private var span: Double {
        shown.enumerated().reduce(0) { sum, item in sum + (item.offset == 0 ? 0 : item.element.gap) + Self.drawn(item.element).words }
    }

    /// Its words and insets, up to the next whole point: Auto Layout puts a view's edges on pixels, and
    /// a width with a fraction it rounded down left the line short of its words by that fraction,
    /// which cut the piece that shrinks though the line had its room.
    override var intrinsicContentSize: NSSize {
        let height = shown.map { Double(Self.drawn($0).frame.height) }.max() ?? 0
        guard let inset else { return NSSize(width: NSView.noIntrinsicMetric, height: height) }
        return NSSize(width: (span + 2 * inset).rounded(.up), height: height)
    }

    override func layout() {
        super.layout()
        let width = Double(bounds.width), height = Double(bounds.height), rightToLeft = userInterfaceLayoutDirection == .rightToLeft
        let short = max(0, span - (width - 2 * (inset ?? 0)))
        var x = max(inset ?? 0, (width - span) / 2)
        for (index, piece) in shown.enumerated() {
            var (lead, words, size) = Self.drawn(piece)
            if piece.shrinks, short > 0 {
                words = max(0, words - short)
                size.width = max(0, Double(size.width) - short)
            }
            x += index == 0 ? 0 : piece.gap
            let left = x - lead, y = (height - Double(size.height)) / 2
            piece.view.frame = NSRect(x: rightToLeft ? width - left - Double(size.width) : left, y: y, width: Double(size.width), height: Double(size.height))
            x += words
        }
    }
}

/// The one-line note over the primary while a system sheet is up: its icon (a fingerprint while
/// Touch ID asks, a check once the passkey is made) and its words, in a surface box with a
/// separator edge.
@MainActor
final class TouchNote: NSView {
    var look: Look {
        didSet { paint() }
    }

    private let icon = NSImageView()
    private let words = NSTextField(wrappingLabelWithString: "")
    private var shown: Welcome.Note?

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = Look.contentRadius
        layer?.borderWidth = Look.hairline
        let row = NSStackView(views: [icon, words])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.spacing = Look.Space.s
        row.edgeInsets = NSEdgeInsets(top: Look.Space.s, left: Look.Space.m, bottom: Look.Space.s, right: Look.Space.m)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor), row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            icon.widthAnchor.constraint(equalToConstant: Look.Step.title3.points), icon.heightAnchor.constraint(equalToConstant: Look.Step.title3.points),
        ])
        words.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        paint()
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        nil
    }

    func show(_ note: Welcome.Note?) {
        shown = note
        words.stringValue = note?.words ?? ""
        setAccessibilityLabel(note?.words)
        paint()
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        paint()
    }

    private func paint() {
        layer?.backgroundColor = NSColor(look.surface).cgColor
        layer?.borderColor = NSColor(look.separator).cgColor
        words.font = .systemFont(ofSize: Look.Step.footnote.points)
        words.textColor = NSColor(look.textSecondary)
        let side = Look.Step.title3.points, scale = Double(window?.backingScaleFactor ?? 2)
        icon.image = MarkImage.image(shown?.created == true ? .check : .fingerprint, side: side, ink: look.text, scale: scale)
    }
}
