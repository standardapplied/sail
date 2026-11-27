import AppKit
import SailAPI
import SailControls
import SailLook

/// The build card on a new project's page (`buildCard` of the first-impressions page, section 6),
/// drawn from ``WorkspacesModel/BuildCard`` inside the page's list frame, which gives it the surface, its
/// edge and a scroller where the window is too short for it. Its head: Sail's mark drawing itself
/// while the build runs (the error mark once it stopped), the title over who started it and when,
/// and the time since at the right; then the bar with the step it is on, the groups of steps each
/// done, running, failed or to come, why it stopped in the server's words, and Build Again with what
/// it keeps, or the line at its foot. Every line wraps where it does not fit, so nothing is cut at the
/// least window, and the whole is mirrored right to left.
@MainActor
final class BuildCard: NSView {
    static let padding = (top: 22.0, sides: 24.0, bottom: 22.0), gap = 16.0, markColumn = 32.0, mark = 28.0, headGap = 14.0
    static let bar = 4.0, barGap = 6.0, row = 30.0, rowGap = 2.0, iconColumn = 18.0, iconGap = 10.0, icon = 15.0, dot = 8.0
    /// The tinted box's insets and type (the page's `.bsaid`), and the least room the line beside Build
    /// Again keeps before it goes under it.
    static let box = (vertical: 10.0, sides: 12.0, points: 12.5), againGap = 10.0, beside = 160.0, refusalMark = 14.0, refusalGap = 6.0
    /// Narrower than this, as at the least window, the card is drawn tighter (rows 24 pt, gaps and its
    /// top and bottom 12 and 16 pt), so a stopped build's Build Again is in view without a scroll.
    static let compact = (below: 420.0, row: 24.0, gap: 12.0, padding: 16.0)

    var look: Look {
        didSet {
            mark.look = look
            spinner.look = look
            again.look = look
            needsDisplay = true
        }
    }

    var onBuildAgain: @MainActor () -> Void = {}
    /// The window is off screen (closed, in the Dock, the app hidden): its time does not tick.
    var away = false {
        didSet { keepTicking() }
    }

    private(set) var build: WorkspacesModel.BuildCard?
    let mark: BrandMark
    let spinner: Spinner
    let again: SailButton
    private let titleFont: TitleFont
    private let mono: LabelFont
    /// Redraws the time since the build started, each second while it runs and is on screen, a
    /// scroll's tracking included.
    private(set) var ticks: Timer? {
        didSet { oldValue?.invalidate() }
    }

    init(look: Look, titleFont: TitleFont, mono: LabelFont) {
        self.look = look
        self.titleFont = titleFont
        self.mono = mono
        mark = BrandMark(.drawing(label: nil), side: Self.mark, look: look, mono: mono)
        spinner = Spinner(look: look)
        again = SailButton("Build Again", kind: .primary, look: look, target: nil, action: nil)
        super.init(frame: .zero)
        again.target = self
        again.action = #selector(buildAgain(_:))
        for view in [mark, spinner, again] as [NSView] {
            addSubview(view)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// Draws `build`: the mark that is drawing keeps drawing, so a read every 2 s never begins its
    /// cycle again.
    func show(_ build: WorkspacesModel.BuildCard) {
        self.build = build
        let running = build.stage == .building
        mark.isHidden = !running
        again.isHidden = !build.buildsAgain
        again.fly(build.asking ? "Building" : nil)
        keepTicking()
        needsLayout = true
        needsDisplay = true
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        keepTicking()
    }

    private func keepTicking() {
        let ticking = window != nil && !away && build?.stage == .building
        guard ticking != (ticks != nil) else { return }
        guard ticking else {
            ticks = nil
            return
        }
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.needsDisplay = true }
        }
        RunLoop.main.add(timer, forMode: .common)
        ticks = timer
    }

    @objc private func buildAgain(_: Any?) {
        onBuildAgain()
    }

    /// How tall it is at `width`, every line whole.
    func height(width: Double) -> Double {
        laid(width: width, now: Date()).height
    }

    /// Where each part stands at a width, in the card's reading direction: the bar's fill runs from
    /// its leading end.
    struct Laid {
        var mark = NSRect.zero
        var texts: [(text: NSAttributedString, rect: NSRect)] = []
        var bar: NSRect?
        var filled: NSRect?
        var icons: [(mark: WorkspacesModel.BuildCard.Mark, rect: NSRect)] = []
        var box: NSRect?
        var again: NSRect?
        var refusalMark: NSRect?
        var height = 0.0

        /// Laid right to left in `width`: each part where it stands measured from the other side.
        func mirrored(_ width: Double) -> Laid {
            let flip = { (rect: NSRect) in NSRect(x: width - Double(rect.maxX), y: Double(rect.minY), width: Double(rect.width), height: Double(rect.height)) }
            var laid = self
            laid.mark = flip(mark)
            laid.texts = texts.map { ($0.text, flip($0.rect)) }
            laid.bar = bar.map(flip)
            laid.filled = filled.map(flip)
            laid.icons = icons.map { ($0.mark, flip($0.rect)) }
            laid.box = box.map(flip)
            laid.again = again.map(flip)
            laid.refusalMark = refusalMark.map(flip)
            return laid
        }
    }

    func laid(width: Double, now: Date) -> Laid {
        let laid = laidLeftToRight(width: width, now: now)
        return userInterfaceLayoutDirection == .rightToLeft ? laid.mirrored(width) : laid
    }

    private func laidLeftToRight(width: Double, now: Date) -> Laid {
        var laid = Laid()
        guard let build else { return laid }
        let x = Self.padding.sides, room = max(width - 2 * Self.padding.sides, 1)
        let tight = width < Self.compact.below, gap = tight ? Self.compact.gap : Self.gap, rowHeight = tight ? Self.compact.row : Self.row
        let ends = tight ? Self.compact.padding : Self.padding.top
        var y = ends
        let elapsed = build.startedAt.map { text(WorkspacesModel.BuildCard.elapsed(from: $0, to: build.endedAt ?? now), mono.font(Look.Step.callout.points) as NSFont, look.textSecondary) }
        let elapsedWidth = elapsed.map { max(Double(ceil($0.size().width)), Double(ceil(text("00:00", mono.font(Look.Step.callout.points) as NSFont, look.textSecondary).size().width))) }
        let wordsX = x + Self.markColumn + Self.headGap, wordsRoom = max(room - Self.markColumn - Self.headGap - (elapsedWidth.map { $0 + Self.headGap } ?? 0), 1)
        let title = text(build.title, CTFontCreateCopyWithAttributes(titleFont.font(.title3), 20, nil, nil) as NSFont, look.text, lineHeight: 1.2, tracking: -0.01)
        let started = build.started.map { text($0, .systemFont(ofSize: Look.Step.callout.points), look.textSecondary, lineHeight: 1.4) }
        let titleHeight = Self.height(title, wordsRoom), startedHeight = started.map { Self.height($0, wordsRoom) } ?? 0
        let head = max(Self.markColumn, titleHeight + startedHeight)
        laid.mark = NSRect(x: x + (Self.markColumn - Self.mark) / 2, y: y + (head - Self.mark) / 2, width: Self.mark, height: Self.mark)
        let wordsY = y + (head - titleHeight - startedHeight) / 2
        laid.texts.append((title, NSRect(x: wordsX, y: wordsY, width: wordsRoom, height: titleHeight)))
        if let started { laid.texts.append((started, NSRect(x: wordsX, y: wordsY + titleHeight, width: wordsRoom, height: startedHeight))) }
        if let elapsed, let elapsedWidth {
            let height = Self.height(elapsed, elapsedWidth)
            laid.texts.append((elapsed, NSRect(x: x + room - elapsedWidth, y: y + (head - height) / 2, width: elapsedWidth, height: height)))
        }
        y += head
        if let at = build.at {
            y += gap
            laid.bar = NSRect(x: x, y: y, width: room, height: Self.bar)
            let filled = room * min(max(build.done, 0), 1)
            if filled > 0 { laid.filled = NSRect(x: x, y: y, width: filled, height: Self.bar) }
            y += Self.bar + Self.barGap
            let small = mono.font(Look.Step.micro.points, .regular) as NSFont
            let said = text(at, small, look.textTertiary), atWidth = Double(ceil(said.size().width))
            let atHeight = Self.height(said, room)
            laid.texts.append((said, NSRect(x: x, y: y, width: min(atWidth, room), height: atHeight)))
            if let missed = build.missed.map({ text($0, small, look.textTertiary) }) {
                let missedWidth = Double(ceil(missed.size().width))
                if atWidth + Self.againGap + missedWidth <= room {
                    laid.texts.append((missed, NSRect(x: x + room - missedWidth, y: y, width: missedWidth, height: atHeight)))
                } else {
                    let height = Self.height(missed, room)
                    laid.texts.append((missed, NSRect(x: x, y: y + atHeight, width: room, height: height)))
                    y += height
                }
            }
            y += atHeight
        }
        if !build.groups.isEmpty {
            y += gap
            let nameX = x + Self.iconColumn + Self.iconGap, nameRoom = max(room - Self.iconColumn - Self.iconGap, 1)
            for (index, group) in build.groups.enumerated() {
                let ink = switch group.mark {
                case .failed: Tint(look.error)
                case .coming: look.textTertiary
                case .done, .running: look.text
                }
                let name = text(group.name, .systemFont(ofSize: Look.Step.callout.points), ink, lineHeight: 1.4)
                let nameHeight = Self.height(name, nameRoom), row = max(rowHeight, nameHeight + 8)
                laid.texts.append((name, NSRect(x: nameX, y: y + (row - nameHeight) / 2, width: nameRoom, height: nameHeight)))
                laid.icons.append((group.mark, NSRect(x: x, y: y + (row - Self.iconColumn) / 2, width: Self.iconColumn, height: Self.iconColumn)))
                y += row + (index == build.groups.count - 1 ? 0 : Self.rowGap)
            }
        }
        if !build.reason.isEmpty {
            y += gap
            let said = reason(build.reason), wordsRoom = max(room - 2 * Self.box.sides, 1), height = Self.height(said, wordsRoom)
            laid.box = NSRect(x: x, y: y, width: room, height: height + 2 * Self.box.vertical)
            laid.texts.append((said, NSRect(x: x + Self.box.sides, y: y + Self.box.vertical, width: wordsRoom, height: height)))
            y += height + 2 * Self.box.vertical
        }
        let foot = build.foot.map { text($0, .systemFont(ofSize: Look.Step.footnote.points), look.textTertiary, lineHeight: 1.45) }
        if build.buildsAgain {
            y += gap
            let size = again.intrinsicContentSize, button = NSRect(x: x, y: y, width: Double(size.width), height: Double(size.height))
            laid.again = button
            let beside = room - Double(size.width) - Self.againGap
            if let foot {
                if beside >= Self.beside {
                    let height = Self.height(foot, beside)
                    laid.texts.append((foot, NSRect(x: button.maxX + Self.againGap, y: y + max((Double(size.height) - height) / 2, 0), width: beside, height: height)))
                    y += max(Double(size.height), height)
                } else {
                    let height = Self.height(foot, room)
                    laid.texts.append((foot, NSRect(x: x, y: button.maxY + Self.refusalGap, width: room, height: height)))
                    y = button.maxY + Self.refusalGap + height
                }
            } else {
                y += Double(size.height)
            }
        } else if let foot {
            y += gap
            let height = Self.height(foot, room)
            laid.texts.append((foot, NSRect(x: x, y: y, width: room, height: height)))
            y += height
        }
        if !build.refusal.isEmpty {
            y += Self.againGap
            let said = refusal(build.refusal), wordsX = x + Self.refusalMark + Self.refusalGap, wordsRoom = max(room - Self.refusalMark - Self.refusalGap, 1)
            let height = Self.height(said, wordsRoom)
            laid.refusalMark = NSRect(x: x, y: y + 2, width: Self.refusalMark, height: Self.refusalMark)
            laid.texts.append((said, NSRect(x: wordsX, y: y, width: wordsRoom, height: height)))
            y += height
        }
        laid.height = (y + ends).rounded(.up)
        return laid
    }

    /// The server's reason in its bold, then what to do, a line each under it.
    private func reason(_ lines: [String]) -> NSAttributedString {
        let said = NSMutableAttributedString(attributedString: text(lines[0], .systemFont(ofSize: Self.box.points, weight: .semibold), look.text, lineHeight: 1.45))
        for line in lines.dropFirst() {
            said.append(text("\n" + line, .systemFont(ofSize: Self.box.points), look.textSecondary, lineHeight: 1.45, before: 3))
        }
        return said
    }

    /// A refusal as one under a field is said: its sentence in the error's ink, then what to do.
    private func refusal(_ lines: [String]) -> NSAttributedString {
        let said = NSMutableAttributedString(attributedString: text(lines[0], .systemFont(ofSize: Look.Step.footnote.points), Tint(look.error), lineHeight: 1.45))
        for line in lines.dropFirst() {
            said.append(text("\n" + line, .systemFont(ofSize: Look.Step.footnote.points), look.textSecondary, lineHeight: 1.45))
        }
        return said
    }

    private func text(_ words: String, _ font: NSFont, _ ink: Tint, lineHeight: Double? = nil, tracking: Double = 0, before: Double = 0) -> NSAttributedString {
        let style = NSMutableParagraphStyle()
        style.alignment = .natural
        style.baseWritingDirection = userInterfaceLayoutDirection == .rightToLeft ? .rightToLeft : .leftToRight
        style.lineBreakMode = .byWordWrapping
        if let lineHeight {
            style.minimumLineHeight = (lineHeight * Double(font.pointSize)).rounded()
            style.maximumLineHeight = style.minimumLineHeight
        }
        style.paragraphSpacingBefore = before
        return NSAttributedString(string: words, attributes: [.font: font, .foregroundColor: NSColor(ink), .paragraphStyle: style, .kern: tracking * Double(font.pointSize)])
    }

    static func height(_ text: NSAttributedString, _ width: Double) -> Double {
        Double(ceil(text.boundingRect(with: NSSize(width: width, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin, .usesFontLeading]).height))
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        needsLayout = true
    }

    override func layout() {
        super.layout()
        let laid = laid(width: Double(bounds.width), now: Date())
        mark.frame = laid.mark
        if let button = laid.again { again.frame = again.frame(forAlignmentRect: button) }
        let running = laid.icons.first { $0.mark == .running }?.rect
        spinner.isHidden = running == nil
        if let running {
            let side = Double(spinner.intrinsicContentSize.width)
            spinner.frame = NSRect(x: running.midX - side / 2, y: running.midY - side / 2, width: side, height: side)
        }
    }

    override func draw(_: NSRect) {
        guard let build else { return }
        let laid = laid(width: Double(bounds.width), now: Date())
        if build.stage != .building { Draw.icon(.error, in: laid.mark, ink: Tint(look.error)) }
        if let bar = laid.bar { Draw.box(bar, radius: Self.bar / 2, fill: look.skeleton, edge: nil) }
        if let filled = laid.filled { Draw.box(filled, radius: Self.bar / 2, fill: Tint(build.stage == .failed ? look.error : look.accent), edge: nil) }
        for (mark, rect) in laid.icons {
            let icon = NSRect(x: rect.midX - Self.icon / 2, y: rect.midY - Self.icon / 2, width: Self.icon, height: Self.icon)
            switch mark {
            case .done: Draw.icon(.check, in: icon, ink: Tint(look.success))
            case .failed: Draw.icon(.close, in: icon, ink: Tint(look.error))
            case .coming:
                let dot = NSBezierPath(ovalIn: NSRect(x: rect.midX - Self.dot / 2, y: rect.midY - Self.dot / 2, width: Self.dot, height: Self.dot).insetBy(dx: 0.5, dy: 0.5))
                NSColor(look.separatorStrong).setStroke()
                dot.lineWidth = 1
                dot.stroke()
            case .running: break
            }
        }
        if let box = laid.box {
            let paint = BannerPaint(.error, look: look)
            Draw.box(box, radius: Look.contentRadius, fill: Tint(paint.fill), edge: paint.edge)
        }
        if let mark = laid.refusalMark { Draw.icon(.error, in: mark, ink: Tint(look.error)) }
        for (text, rect) in laid.texts {
            text.draw(with: rect, options: [.usesLineFragmentOrigin, .usesFontLeading])
        }
    }
}
