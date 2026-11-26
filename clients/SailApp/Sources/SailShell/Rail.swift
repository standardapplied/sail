import AppKit
import SailLook
import SailTerminalView

/// The icon rail, 60 pt wide on the window's chrome under the band: Workspaces on top, Terminals
/// under it, the account at the bottom, as Mast's `.rail` (8 pt in at the top under the compact
/// band, 12 at the bottom, 42 pt items). The selected item takes the accent fill and a 2 pt bar at
/// its leading edge.
@MainActor
public final class Rail: NSView {
    public static let width = 60.0
    /// Mast's `.rail` measures: 8 pt in at the top, 12 at the bottom, 42 pt items, a 2 pt bar.
    static let top = 8.0, bottom = 12.0, item = 42.0, bar = 2.0, barHeight = 24.0, gap = 4.0

    public enum Item: String, CaseIterable, Sendable {
        case workspaces, terminals, account

        var title: String {
            switch self {
            case .workspaces: "Workspaces"
            case .terminals: "Terminals"
            case .account: "Account"
            }
        }

        var symbol: String {
            switch self {
            case .workspaces: "shippingbox"
            case .terminals: "terminal"
            case .account: "person.crop.circle"
            }
        }
    }

    public private(set) var selected: Item?
    var onSelect: @MainActor (Item) -> Void = { _ in }

    /// The look it is drawn in; a new one repaints it.
    var look: Look {
        didSet { show(selected) }
    }

    private var buttons: [Item: NSButton] = [:]
    /// The wash under each item, shown under the chosen one: a plain view sized 42 pt square by its
    /// constraints, so its size does not depend on how a button draws its own frame.
    private var washes: [Item: NSView] = [:]
    private var bars: [Item: NSView] = [:]

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        wantsLayer = true
        for item in Item.allCases {
            let button = RailButton(image: Self.image(item, colour: look.textSecondary), target: self, action: #selector(pick(_:)))
            button.isBordered = false
            button.toolTip = item.title
            button.setAccessibilityLabel(item.title)
            if item == .account {
                button.setAccessibilityRole(.menuButton)
                button.setAccessibilityHelp("Opens a menu of who is signed in, the control plane and Appearance.")
            }
            button.identifier = NSUserInterfaceItemIdentifier(item.rawValue)
            button.translatesAutoresizingMaskIntoConstraints = false
            let wash = NSView()
            wash.wantsLayer = true
            wash.layer?.cornerRadius = Look.contentRadius
            wash.translatesAutoresizingMaskIntoConstraints = false
            addSubview(wash, positioned: .below, relativeTo: nil)
            washes[item] = wash
            let bar = NSView()
            bar.wantsLayer = true
            bar.translatesAutoresizingMaskIntoConstraints = false
            addSubview(button)
            addSubview(bar)
            buttons[item] = button
            bars[item] = bar
            let placed: NSLayoutConstraint = switch item {
            case .workspaces: button.topAnchor.constraint(equalTo: topAnchor, constant: Self.top)
            case .terminals: button.topAnchor.constraint(equalTo: buttons[.workspaces]?.bottomAnchor ?? topAnchor, constant: Self.gap)
            case .account: button.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Self.bottom)
            }
            NSLayoutConstraint.activate([
                button.centerXAnchor.constraint(equalTo: centerXAnchor), button.widthAnchor.constraint(equalToConstant: Self.item),
                button.heightAnchor.constraint(equalToConstant: Self.item), bar.leadingAnchor.constraint(equalTo: leadingAnchor),
                bar.widthAnchor.constraint(equalToConstant: Self.bar), bar.centerYAnchor.constraint(equalTo: button.centerYAnchor),
                bar.heightAnchor.constraint(equalToConstant: Self.barHeight), placed,
                wash.centerXAnchor.constraint(equalTo: button.centerXAnchor), wash.centerYAnchor.constraint(equalTo: button.centerYAnchor),
                wash.widthAnchor.constraint(equalToConstant: Self.item), wash.heightAnchor.constraint(equalToConstant: Self.item),
            ])
        }
        widthAnchor.constraint(equalToConstant: Self.width).isActive = true
        show(nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Whether the press being acted on is the pointer's: a click, not the keys or VoiceOver.
    var pressedByPointer: Bool {
        buttons.values.contains { ($0 as? RailButton)?.clicked == true }
    }

    /// The user button while its menu is open: outlined in the look's `railOpen`, its icon in ink.
    package var menuOpen = false {
        didSet { if menuOpen != oldValue { show(selected) } }
    }

    /// The square under `item`'s button: its wash when chosen, its outline while its menu is open.
    func wash(_ item: Item) -> NSView? {
        washes[item]
    }

    /// The bar beside `item`'s button, which marks it chosen.
    func bar(_ item: Item) -> NSView? {
        bars[item]
    }

    func show(_ item: Item?) {
        selected = item
        layer?.backgroundColor = NSColor.clear.cgColor
        for (each, button) in buttons {
            bars[each]?.layer?.backgroundColor = NSColor(chosenInk.bar).cgColor
            let chosen = each == item
            washes[each]?.layer?.backgroundColor = chosen ? chosenInk.fill.map { NSColor($0).cgColor } ?? NSColor.clear.cgColor : NSColor.clear.cgColor
            let open = menuOpen && each == .account
            washes[each]?.layer?.borderWidth = open ? 1 : 0
            washes[each]?.layer?.borderColor = look.railOpen.edge.map { NSColor($0).cgColor }
            button.image = Self.image(each, colour: chosen ? chosenInk.icon : open ? look.railOpen.ink : look.textSecondary)
            bars[each]?.isHidden = !chosen
        }
    }

    public func button(_ item: Item) -> NSButton? {
        buttons[item]
    }

    @objc private func pick(_ sender: NSButton) {
        guard let item = sender.identifier.flatMap({ Item(rawValue: $0.rawValue) }) else { return }
        onSelect(item)
    }

    /// `item`'s symbol in `colour`, made once (``SymbolInk``).
    private static func image(_ item: Item, colour: Tint) -> NSImage {
        SymbolInk.image(item.symbol, points: 20, weight: .regular, colour: colour, accessibility: item.title)
    }
}

extension Rail: Marked {
    /// The chosen item: its ink fill and icon, and the accent bar that says which.
    var chosenInk: (fill: Tint?, icon: Tint, bar: Tint) {
        (look.railChosen.fill, look.railChosen.ink, Tint(look.accent))
    }

    /// Its chosen item's icon and bar; the wash under it is the chrome's, on the chrome, and not
    /// counted as the card's.
    package var drawnMarks: [Mark] {
        guard selected != nil else { return [] }
        let ink = chosenInk
        return look.marks(line: ink.icon) + look.marks(line: ink.bar)
    }

    /// The chosen item's wash: its square and its colour, for the tests.
    var chosenWash: (size: NSSize, colour: CGColor?, button: NSRect, insets: NSEdgeInsets)? {
        guard let selected, let wash = washes[selected], let button = buttons[selected] else { return nil }
        return (wash.bounds.size, wash.layer?.backgroundColor, button.frame, button.alignmentRectInsets)
    }
}

/// A rail item's button, which knows while a click is acting on it: its action runs inside the
/// click's tracking, so the user menu can tell the pointer from the keys and VoiceOver.
@MainActor
final class RailButton: NSButton {
    private(set) var clicked = false

    override func mouseDown(with event: NSEvent) {
        clicked = true
        defer { clicked = false }
        super.mouseDown(with: event)
    }

    /// VoiceOver's press, and its Show Menu on a menu button, act at once, as a press of the keys:
    /// AppKit's own press of this menu-button-role button did not open the menu in the test.
    override func accessibilityPerformPress() -> Bool {
        guard isEnabled else { return false }
        performClick(nil)
        return true
    }

    override func accessibilityPerformShowMenu() -> Bool {
        accessibilityPerformPress()
    }
}
