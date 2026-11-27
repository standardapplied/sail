import AppKit
import SailAPI
import SailControls
import SailLook

/// New project (`createSheet` of `first-impressions-mockup.html`, section 6): a sheet on the
/// window, 600 pt or, on a narrow window, the window less 12 pt each side. NAME; REPOSITORIES, a row
/// each of the URL and the folder it is cloned to under `~/workspace/`, in mono and left to right
/// as paths read in any language, with × to remove it, and Add Repository; AGENTS, a check for each harness the server knows; SIZE, Small to
/// Custom, Custom's three fields under it; then the foot with what comes next, Cancel and Create
/// Project ⏎, the one primary. Where the window is too short, everything over the foot scrolls.
/// It draws ``NewProject`` and sends the person's edits there; Create sends the definition once
/// (``send``), and a refusal stands under the field it names or over the foot. Return creates and
/// Escape cancels. Built whole by ``show()``, every frame set there; mirrored right to left.
@MainActor
package final class CreateProjectSheet: NSPanel, NSTextFieldDelegate {
    static let width = 600.0, inset = 12.0
    static let padding = (top: 22.0, sides: 24.0, bottom: 18.0), gap = 14.0, inner = 7.0, rowGap = 8.0
    static let footer = (vertical: 14.0, sides: 16.0), buttonGap = 8.0
    /// A column narrower than this puts each path under its URL.
    static let sideBySide = 480.0

    /// Sends the create and gives its answer: the host's client.
    var send: @MainActor (ProjectDefinition) async -> Result<ProjectCreated, SailError> = { _ in .failure(.unreachable("nothing sends it")) }
    /// Told the project's name once it is created, the sheet ended.
    var onCreated: @MainActor (String) -> Void = { _ in }
    /// Told when it ends, created, cancelled or withdrawn.
    var onEnd: @MainActor () -> Void = {}

    var look: Look {
        didSet {
            guard look != oldValue else { return }
            for view in body.subviews + content.subviews {
                (view as? SailTextField)?.look = look
                (view as? SailButton)?.look = look
                (view as? LinkButton)?.look = look
                (view as? AgentCheck)?.look = look
            }
            sizes.look = look
            scroll.look = look
            content.look = look
            show()
        }
    }

    package private(set) var model: NewProject
    package let name: SailTextField
    package private(set) var urls: [SailTextField] = []
    package private(set) var paths: [SailTextField] = []
    package private(set) var removes: [SailButton] = []
    package let addRepo: LinkButton
    package let checks: [AgentCheck]
    package let sizes: SizeSegments
    package let custom: [(part: NewProject.Part, field: SailTextField)]
    package let cancel: SailButton
    package let create: SailButton
    /// The refusal over the foot, while one stands.
    package private(set) var refusal: Banner?
    private let heading = NSTextField(wrappingLabelWithString: "")
    private let reposLabel = NSTextField(labelWithString: "")
    private let agentsLabel = NSTextField(labelWithString: "")
    private let agentsHelp = NSTextField(wrappingLabelWithString: "")
    private let sizeLabel = NSTextField(labelWithString: "")
    private let sizeHelp = NSTextField(wrappingLabelWithString: "")
    private let footHelp = NSTextField(wrappingLabelWithString: "")
    private let body = SheetBody()
    private let scroll: QuietScrollView
    private let content: LoginSheetContent
    private let titleFont: TitleFont
    private let mono: LabelFont
    private let direction: NSUserInterfaceLayoutDirection
    private let address: String
    private var sheetWidth: Double
    private var room: Double
    private var ended = false

    /// The sheet for `model`, `width` wide and at most `room` tall, laid out in `direction`; a
    /// failure to reach the server is said at `address`.
    init(_ model: NewProject, width: Double, room: Double, direction: NSUserInterfaceLayoutDirection, address: String, look: Look, titleFont: TitleFont, mono: LabelFont) {
        self.model = model
        self.look = look
        self.titleFont = titleFont
        self.mono = mono
        self.direction = direction
        self.address = address
        sheetWidth = width
        self.room = room
        name = SailTextField(label: "Name", placeholder: "", help: NewProject.nameHelp, look: look, mono: mono)
        addRepo = LinkButton("Add Repository", look: look, target: nil, action: nil)
        checks = model.agents.map { AgentCheck($0.display, look: look) }
        sizes = SizeSegments(look: look)
        custom = [(NewProject.Part.cores, "Cores"), (.memory, "Memory (GB)"), (.disk, "Disk (GB)")].map { part, label in
            (part: part, field: SailTextField(label: label, placeholder: "", look: look, mono: mono))
        }
        cancel = SailButton("Cancel", kind: .secondary, look: look, target: nil, action: nil)
        create = SailButton("Create Project", kind: .primary, look: look, target: nil, action: nil)
        scroll = QuietScrollView(look: look)
        content = LoginSheetContent(look: look)
        super.init(contentRect: NSRect(x: 0, y: 0, width: width, height: 400), styleMask: [.titled], backing: .buffered, defer: true)
        title = "New project"
        contentView = content
        scroll.documentView = body
        content.addSubview(scroll)
        wire()
        let fields: [NSView] = [heading, name, reposLabel, addRepo, agentsLabel, agentsHelp, sizeLabel, sizes, sizeHelp] + checks + custom.map(\.field)
        for view in fields {
            body.addSubview(view)
        }
        for view in [footHelp, cancel, create] as [NSView] {
            content.addSubview(view)
        }
        for view in [content, scroll, body] as [NSView] + fields + [footHelp, cancel, create] {
            view.userInterfaceLayoutDirection = direction
        }
        initialFirstResponder = name.field
        show()
    }

    private func wire() {
        name.field.delegate = self
        addRepo.target = self
        addRepo.action = #selector(addRepository(_:))
        for (at, check) in checks.enumerated() {
            check.onToggle = { [weak self] checked in
                guard let self else { return }
                model.check(model.agents[at].name, checked)
                show()
            }
        }
        sizes.onChoose = { [weak self] size in
            self?.model.choose(size)
            self?.show()
        }
        for (part, field) in custom {
            field.field.delegate = self
            field.field.stringValue = model.custom[part] ?? ""
        }
        cancel.target = self
        cancel.action = #selector(cancelled(_:))
        create.target = self
        create.action = #selector(pressedCreate(_:))
        create.shortcut("\r", mono: mono)
    }

    /// The sheet fitted again to a window `width` and `room` now allow.
    func fit(width: Double, room: Double) {
        guard width != sheetWidth || room != self.room else { return }
        sheetWidth = width
        self.room = room
        show()
    }

    /// Draws what the model says, every frame set here.
    func show() {
        let column = sheetWidth - 2 * Self.padding.sides, x = Self.padding.sides
        heading.attributedStringValue = titled("New project")
        var y = place(heading, x: x, y: Self.padding.top, width: column) + Self.gap
        name.error = model.nameRefused?.said
        name.remedy = model.nameRefused?.remedy
        y = place(name, x: x, y: y, width: column) + Self.gap
        y = repositories(x: x, y: y, width: column) + Self.gap
        y = agents(x: x, y: y, width: column) + Self.gap
        y = size(x: x, y: y, width: column)
        y = footRefusal(x: x, y: y, width: column) + Self.padding.bottom
        for field in [name] + urls + paths + custom.map(\.field) {
            field.isEnabled = !model.underWay
        }
        let foot = footer()
        let shown = min(y, max(room - foot, 0))
        body.frame = NSRect(x: 0, y: 0, width: sheetWidth, height: y)
        scroll.frame = NSRect(x: 0, y: 0, width: sheetWidth, height: shown)
        placeFooter(top: shown)
        content.footerTop = shown
        let height = shown + foot
        setContentSize(NSSize(width: sheetWidth, height: height))
        content.frame = NSRect(x: 0, y: 0, width: sheetWidth, height: height)
        content.needsDisplay = true
        chainKeys()
    }

    /// REPOSITORIES: a row each, the URL and its folder side by side where the column is wide
    /// enough and the folder under the URL where it is not, × at the row's end; then Add Repository.
    private func repositories(x: Double, y: Double, width: Double) -> Double {
        reposLabel.attributedStringValue = eyebrow("Repositories")
        var y = place(reposLabel, x: x, y: y, width: width) + Self.inner
        syncRows()
        let remove = Double(removes.first?.intrinsicContentSize.width ?? 0), lead = width - remove - Self.rowGap
        let wide = width >= Self.sideBySide, urlWidth = wide ? ((lead - Self.rowGap) * 1.35 / 2.35).rounded() : lead
        for (at, repo) in model.repos.enumerated() {
            if urls[at].field.stringValue != repo.url { urls[at].field.stringValue = repo.url }
            if paths[at].field.stringValue != repo.path { paths[at].field.stringValue = repo.path }
            let top = y
            y = place(urls[at], x: x, y: y, width: urlWidth)
            let pathX = wide ? x + urlWidth + Self.rowGap : x, pathTop = wide ? top : y + Self.rowGap
            y = max(y, place(paths[at], x: pathX, y: pathTop, width: wide ? lead - urlWidth - Self.rowGap : lead))
            let size = removes[at].intrinsicContentSize
            let box = NSRect(x: x + width - Double(size.width), y: top + (FieldSize.regular.height - Double(size.height)) / 2, width: Double(size.width), height: Double(size.height))
            removes[at].frame = mirrored(box).insetBy(dx: -Look.haloRoom, dy: -Look.haloRoom)
            y += wide ? Self.rowGap : Self.gap
        }
        let link = addRepo.intrinsicContentSize
        addRepo.frame = mirrored(NSRect(x: x, y: y, width: Double(link.width), height: Double(link.height)))
        return y + Double(link.height)
    }

    /// A URL field, a path field and × for each repository, made or dropped as the rows change.
    private func syncRows() {
        while urls.count < model.repos.count {
            let url = SailTextField(label: nil, placeholder: "github.com/acme/app", look: look, mono: mono)
            let path = SailTextField(label: nil, placeholder: "", look: look, mono: mono)
            path.prefix = NewProject.folder
            let remove = SailButton(icon: .close, label: "Remove Repository", kind: .quiet, size: .small, look: look, target: self, action: #selector(removeRepository(_:)))
            for field in [url, path] {
                field.userInterfaceLayoutDirection = .leftToRight
                field.field.font = mono.font(Look.Step.callout.points) as NSFont
                field.field.delegate = self
            }
            remove.userInterfaceLayoutDirection = direction
            for view in [url, path, remove] as [NSView] {
                body.addSubview(view)
            }
            urls.append(url)
            paths.append(path)
            removes.append(remove)
        }
        while urls.count > model.repos.count {
            for view in [urls.removeLast(), paths.removeLast(), removes.removeLast()] as [NSView] {
                view.removeFromSuperview()
            }
        }
    }

    /// AGENTS: a check for each harness, two to a line, and the help; or why none could be listed.
    private func agents(x: Double, y: Double, width: Double) -> Double {
        agentsLabel.attributedStringValue = eyebrow("Agents")
        var y = place(agentsLabel, x: x, y: y, width: width) + Self.inner
        let half = ((width - Self.rowGap) / 2).rounded(.down)
        for (at, check) in checks.enumerated() {
            check.checked = model.agents[at].checked
            check.isEnabled = !model.underWay
            let column = Double(at % 2), top = y + Double(at / 2) * (AgentCheck.height + Self.rowGap)
            check.frame = mirrored(NSRect(x: x + column * (half + Self.rowGap), y: top, width: half, height: AgentCheck.height))
        }
        let lines = (checks.count + 1) / 2
        y += Double(lines) * AgentCheck.height + Double(max(lines - 1, 0)) * Self.rowGap + (lines > 0 ? Self.inner : 0)
        let unlisted = model.agentsUnlisted
        agentsHelp.attributedStringValue = text(unlisted ?? NewProject.agentsHelp, Look.Step.footnote, unlisted == nil ? look.textTertiary : Tint(look.error), wraps: true)
        return place(agentsHelp, x: x, y: y, width: width)
    }

    /// SIZE: the four sizes, Custom's three fields when it is chosen, and the help naming the size.
    private func size(x: Double, y: Double, width: Double) -> Double {
        sizeLabel.attributedStringValue = eyebrow("Size")
        var y = place(sizeLabel, x: x, y: y, width: width) + Self.inner
        sizes.chosen = model.size
        sizes.frame = mirrored(NSRect(x: x, y: y, width: width, height: SizeSegments.height))
        y += SizeSegments.height + Self.inner
        let customised = model.size == .custom, third = ((width - 2 * Self.rowGap) / 3).rounded(.down)
        var bottom = y
        for (at, (part, field)) in custom.enumerated() {
            field.isHidden = !customised
            guard customised else { continue }
            if field.field.stringValue != model.custom[part] { field.field.stringValue = model.custom[part] ?? "" }
            bottom = max(bottom, place(field, x: x + Double(at) * (third + Self.rowGap), y: y, width: third))
        }
        y = customised ? bottom + Self.inner : y
        sizeHelp.attributedStringValue = text(model.sizeHelp, Look.Step.footnote, look.textTertiary, wraps: true)
        return place(sizeHelp, x: x, y: y, width: width)
    }

    /// A refusal that names no field, over the foot; where it stands, the fields scroll to it.
    private func footRefusal(x: Double, y: Double, width: Double) -> Double {
        refusal?.removeFromSuperview()
        refusal = nil
        guard let said = model.refused.first else { return y }
        let banner = Banner(.error, title: said, line: model.refused.count > 1 ? model.refused.dropFirst().joined(separator: " ") : nil, look: look)
        banner.userInterfaceLayoutDirection = direction
        body.addSubview(banner)
        refusal = banner
        return place(banner, x: x, y: y + Self.gap, width: width)
    }

    /// The foot's height: the help taking the room the buttons leave, wrapped there.
    private func footer() -> Double {
        let buttons = Double(cancel.intrinsicContentSize.width + create.intrinsicContentSize.width) + Self.buttonGap
        let room = max(sheetWidth - 2 * Self.footer.sides - buttons - Self.footer.sides, 1)
        footHelp.attributedStringValue = text(NewProject.footHelp, Look.Step.footnote, look.textTertiary, wraps: true)
        let help = Self.height(footHelp, room), button = Double(create.intrinsicContentSize.height)
        return max(help, button) + 2 * Self.footer.vertical
    }

    private func placeFooter(top: Double) {
        let middle = top + (footer() / 2).rounded()
        create.fly(model.underWay ? "Creating" : nil)
        create.isEnabled = model.creates || model.underWay
        cancel.isEnabled = !model.underWay
        var trailing = sheetWidth - Self.footer.sides
        for button in [create, cancel] {
            let size = button.intrinsicContentSize
            let box = NSRect(x: trailing - Double(size.width), y: middle - Double(size.height) / 2, width: Double(size.width), height: Double(size.height))
            button.frame = mirrored(box).insetBy(dx: -Look.haloRoom, dy: -Look.haloRoom)
            trailing = Double(box.minX) - Self.buttonGap
        }
        let room = max(trailing - Self.footer.sides - Self.footer.sides + Self.buttonGap, 1), help = Self.height(footHelp, room)
        footHelp.frame = mirrored(NSRect(x: Self.footer.sides, y: middle - help / 2, width: room, height: help))
    }

    /// Tab: the name, each repository's URL and path, Add Repository, Custom's fields while shown,
    /// Cancel, Create Project.
    private func chainKeys() {
        let repos = zip(urls, paths).flatMap { [$0.field, $1.field] as [NSView] }
        let shownCustom: [NSView] = model.size == .custom ? custom.map { $0.field.field } : []
        let chain: [NSView] = [name.field] + repos + [addRepo] + shownCustom + [cancel, create]
        for (at, view) in chain.enumerated() { view.nextKeyView = chain[(at + 1) % chain.count] }
    }

    package func controlTextDidChange(_ note: Notification) {
        guard let changed = note.object as? NSTextField else { return }
        if changed === name.field {
            model.named(changed.stringValue)
        } else if let row = urls.firstIndex(where: { $0.field === changed }) {
            model.typeURL(changed.stringValue, row: row)
        } else if let row = paths.firstIndex(where: { $0.field === changed }) {
            model.typePath(changed.stringValue, row: row)
        } else if let part = custom.first(where: { $0.field.field === changed })?.part {
            let kept = model.typeCustom(part, changed.stringValue)
            if changed.stringValue != kept { changed.stringValue = kept }
        }
        show()
    }

    /// Return creates; Escape cancels, as Cancel does.
    package func control(_: NSControl, textView _: NSTextView, doCommandBy selector: Selector) -> Bool {
        switch selector {
        case #selector(NSResponder.insertNewline(_:)): createNow()
        case #selector(NSResponder.cancelOperation(_:)): end()
        default: return false
        }
        return true
    }

    package override func cancelOperation(_: Any?) {
        end()
    }

    @objc private func addRepository(_: Any?) {
        model.addRepo()
        show()
        if let url = urls.last { makeFirstResponder(url.field) }
    }

    @objc private func removeRepository(_ sender: Any?) {
        guard let button = sender as? SailButton, let row = removes.firstIndex(where: { $0 === button }) else { return }
        model.removeRepo(row)
        show()
    }

    @objc private func cancelled(_: Any?) {
        end()
    }

    @objc private func pressedCreate(_: Any?) {
        createNow()
    }

    /// The create sent last, its answer taken: what a test awaits.
    package private(set) var sending: Task<Void, Never>?

    /// The definition sent once; the answer taken when it comes.
    private func createNow() {
        guard let definition = model.send() else { return }
        show()
        let send = self.send
        sending = Task {
            let answer = await send(definition)
            answered(answer)
        }
    }

    private func answered(_ answer: Result<ProjectCreated, SailError>) {
        switch answer {
        case let .success(created):
            end()
            onCreated(created.name)
        case let .failure(error):
            model.refuse(error, at: address)
            show()
            if model.nameRefused != nil {
                makeFirstResponder(name.field)
            } else if let refusal {
                body.scrollToVisible(refusal.frame)
            }
        }
    }

    /// Ends it as Cancel does, when what it was opened over has gone; nothing once it ended.
    func withdraw() {
        end()
    }

    private func end() {
        guard !ended else { return }
        ended = true
        sheetParent?.endSheet(self)
        onEnd()
    }

    private func titled(_ words: String) -> NSAttributedString {
        let style = paragraph(wraps: true)
        style.minimumLineHeight = (Look.Step.title3.points * 1.2).rounded()
        style.maximumLineHeight = style.minimumLineHeight
        return NSAttributedString(string: words, attributes: [
            .font: titleFont.font(.title3) as NSFont, .foregroundColor: NSColor(look.text), .paragraphStyle: style, .kern: -0.012 * Look.Step.title3.points,
        ])
    }

    /// A group's mono eyebrow, set to the leading edge.
    private func eyebrow(_ words: String) -> NSAttributedString {
        NSAttributedString(string: words.uppercased(), attributes: [
            .font: mono.font(Look.Step.caption.points) as NSFont, .foregroundColor: NSColor(look.textTertiary), .kern: Look.Tracking.eyebrow * Look.Step.caption.points,
            .paragraphStyle: paragraph(wraps: false),
        ])
    }

    private func text(_ words: String, _ step: Look.Step, _ ink: Tint, wraps: Bool) -> NSAttributedString {
        NSAttributedString(string: words, attributes: [.font: NSFont.systemFont(ofSize: step.points), .foregroundColor: NSColor(ink), .paragraphStyle: paragraph(wraps: wraps)])
    }

    private func paragraph(wraps: Bool) -> NSMutableParagraphStyle {
        let style = NSMutableParagraphStyle()
        style.alignment = direction == .rightToLeft ? .right : .left
        style.baseWritingDirection = .natural
        style.lineBreakMode = wraps ? .byWordWrapping : .byTruncatingTail
        return style
    }

    /// `view` at `x`, `y`, `width` wide, at its own height for that width; where it ends. A field's
    /// or a button's frame holds the halo's room around its box.
    private func place(_ view: NSView, x: Double, y: Double, width: Double) -> Double {
        let room = view is SailTextField ? Look.haloRoom : 0
        view.frame = mirrored(NSRect(x: x - room, y: y - room, width: width + 2 * room, height: 10))
        let height = (view as? NSTextField).map { Self.height($0, width) } ?? Double(ceil(view.intrinsicContentSize.height))
        view.frame = mirrored(NSRect(x: x - room, y: y - room, width: width + 2 * room, height: height + 2 * room))
        return y + height
    }

    /// A label's height as its cell draws it at `width`.
    private static func height(_ label: NSTextField, _ width: Double) -> Double {
        Double(ceil(label.cell?.cellSize(forBounds: NSRect(x: 0, y: 0, width: width, height: 10000)).height ?? 0))
    }

    /// `rect`, laid out left to right, turned about the sheet's middle right to left.
    private func mirrored(_ rect: NSRect) -> NSRect {
        guard direction == .rightToLeft else { return rect }
        return NSRect(x: sheetWidth - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height))
    }
}

/// The fields the sheet scrolls, top to bottom.
@MainActor
final class SheetBody: NSView {
    override var isFlipped: Bool {
        true
    }
}

/// An agent's check (`.chk`): a 34 pt tile on the surface in a 1 pt edge, the box (the accent with
/// its check while chosen, an empty ring while not), then the harness's name. A click turns it.
@MainActor
package final class AgentCheck: NSView {
    static let height = 34.0, box = 14.0, padding = 10.0, boxRadius = 3.0

    var look: Look {
        didSet { needsDisplay = true }
    }

    package var checked = true {
        didSet { if checked != oldValue { needsDisplay = true } }
    }

    var isEnabled = true {
        didSet { if isEnabled != oldValue { needsDisplay = true } }
    }

    var onToggle: @MainActor (Bool) -> Void = { _ in }
    package let label: String

    init(_ label: String, look: Look) {
        self.label = label
        self.look = look
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    package override func draw(_: NSRect) {
        let leftToRight = userInterfaceLayoutDirection != .rightToLeft
        Draw.box(bounds, radius: Look.contentRadius, fill: Tint(look.surface), edge: look.separatorStrong)
        let x = leftToRight ? Self.padding : Double(bounds.width) - Self.padding - Self.box
        let box = NSRect(x: x, y: (Double(bounds.height) - Self.box) / 2, width: Self.box, height: Self.box)
        if checked {
            Draw.box(box, radius: Self.boxRadius, fill: Tint(look.accent), edge: nil)
            Draw.icon(.check, in: box.insetBy(dx: 1.5, dy: 1.5), ink: Tint(look.onAccent))
        } else {
            Draw.box(box, radius: Self.boxRadius, fill: nil, edge: look.separatorStrong)
        }
        let words = NSAttributedString(string: label, attributes: [.font: NSFont.systemFont(ofSize: Look.Step.callout.points), .foregroundColor: NSColor(look.text)])
        let size = words.size(), room = Double(bounds.width) - 3 * Self.padding - Self.box
        let textX = leftToRight ? Self.padding * 2 + Self.box : Double(bounds.width) - Self.padding * 2 - Self.box - min(Double(size.width), room)
        words.draw(with: NSRect(x: textX, y: (Double(bounds.height) - Double(size.height)) / 2, width: room, height: Double(size.height)), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine])
    }

    package override func mouseDown(with _: NSEvent) {}

    package override func mouseUp(with event: NSEvent) {
        guard isEnabled, bounds.contains(convert(event.locationInWindow, from: nil)) else { return }
        checked.toggle()
        onToggle(checked)
    }
}

/// SIZE's segmented control (`.seg`): a 2 pt inset track on the canvas in a 1 pt edge, four equal
/// segments in SF 12 medium, the chosen one on a surface thumb in its own edge. A click chooses.
@MainActor
package final class SizeSegments: NSView {
    static let height = 30.0, pad = 2.0

    var look: Look {
        didSet { needsDisplay = true }
    }

    package var chosen = NewProject.Size.medium {
        didSet { if chosen != oldValue { needsDisplay = true } }
    }

    var onChoose: @MainActor (NewProject.Size) -> Void = { _ in }

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    package required init?(coder: NSCoder) {
        nil
    }

    package override var isFlipped: Bool {
        true
    }

    /// Each size's segment, Small at the leading end.
    package var rects: [NSRect] {
        let sizes = NewProject.Size.allCases, width = (Double(bounds.width) - 2 * Self.pad) / Double(sizes.count)
        let laid = sizes.indices.map { NSRect(x: Self.pad + Double($0) * width, y: Self.pad, width: width, height: Double(bounds.height) - 2 * Self.pad) }
        guard userInterfaceLayoutDirection == .rightToLeft else { return laid }
        return laid.map { NSRect(x: Double(bounds.width) - Double($0.maxX), y: Double($0.minY), width: Double($0.width), height: Double($0.height)) }
    }

    package override func draw(_: NSRect) {
        Draw.box(bounds, radius: Look.contentRadius, fill: Tint(look.canvas), edge: look.separatorStrong)
        for (size, rect) in zip(NewProject.Size.allCases, rects) {
            let on = size == chosen
            if on { Draw.box(rect, radius: Look.innerRadius, fill: Tint(look.surface), edge: look.separatorStrong) }
            let words = NSAttributedString(string: size.rawValue, attributes: [
                .font: NSFont.systemFont(ofSize: Look.Step.footnote.points, weight: .medium), .foregroundColor: NSColor(on ? look.text : look.textSecondary),
            ])
            let measure = words.size()
            words.draw(at: NSPoint(x: Double(rect.midX) - Double(measure.width) / 2, y: Double(rect.midY) - Double(measure.height) / 2))
        }
    }

    package override func mouseDown(with _: NSEvent) {}

    package override func mouseUp(with event: NSEvent) {
        let at = convert(event.locationInWindow, from: nil)
        guard let index = rects.firstIndex(where: { $0.contains(at) }) else { return }
        let size = NewProject.Size.allCases[index]
        guard size != chosen else { return }
        chosen = size
        onChoose(size)
    }
}
