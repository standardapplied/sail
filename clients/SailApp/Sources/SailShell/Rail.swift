import AppKit
import SailLook
import SailTerminalView

/// The icon rail, 60 pt wide on the window's chrome under the band: "This Mac" on top, "Workspaces"
/// under it while signed in, the account at the bottom, as Mast's `.rail` (10 pt in at the top, 12
/// at the bottom, 42 pt items). The selected item takes the accent fill and a 2 pt bar at its
/// leading edge.
@MainActor
public final class Rail: NSView {
    public static let width = 60.0
    /// Mast's `.rail` measures: 10 pt in at the top, 12 at the bottom, 42 pt items, a 2 pt bar.
    static let top = 10.0, bottom = 12.0, item = 42.0, bar = 2.0, barHeight = 24.0, gap = 4.0

    public enum Item: String, CaseIterable, Sendable {
        case thisMac, workspaces, account

        var title: String {
            switch self {
            case .thisMac: "This Mac"
            case .workspaces: "Workspaces"
            case .account: "Account"
            }
        }

        var symbol: String {
            switch self {
            case .thisMac: "terminal"
            case .workspaces: "shippingbox"
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
    private var bars: [Item: NSView] = [:]

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        wantsLayer = true
        for item in Item.allCases {
            let button = NSButton(image: Self.image(item, colour: look.textSecondary), target: self, action: #selector(pick(_:)))
            button.isBordered = false
            // A rounded push button keeps 3 pt alignment insets above and below even unbordered, so
            // its frame, and the chosen item's wash, would be 42 by 48: the square style has none.
            button.bezelStyle = .regularSquare
            button.wantsLayer = true
            button.layer?.cornerRadius = Look.contentRadius
            button.toolTip = item.title
            button.setAccessibilityLabel(item.title)
            button.identifier = NSUserInterfaceItemIdentifier(item.rawValue)
            button.translatesAutoresizingMaskIntoConstraints = false
            let bar = NSView()
            bar.wantsLayer = true
            bar.translatesAutoresizingMaskIntoConstraints = false
            addSubview(button)
            addSubview(bar)
            buttons[item] = button
            bars[item] = bar
            let placed: NSLayoutConstraint = switch item {
            case .thisMac: button.topAnchor.constraint(equalTo: topAnchor, constant: Self.top)
            case .workspaces: button.topAnchor.constraint(equalTo: buttons[.thisMac]?.bottomAnchor ?? topAnchor, constant: Self.gap)
            case .account: button.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Self.bottom)
            }
            NSLayoutConstraint.activate([
                button.centerXAnchor.constraint(equalTo: centerXAnchor), button.widthAnchor.constraint(equalToConstant: Self.item),
                button.heightAnchor.constraint(equalToConstant: Self.item), bar.leadingAnchor.constraint(equalTo: leadingAnchor),
                bar.widthAnchor.constraint(equalToConstant: Self.bar), bar.centerYAnchor.constraint(equalTo: button.centerYAnchor),
                bar.heightAnchor.constraint(equalToConstant: Self.barHeight), placed,
            ])
        }
        widthAnchor.constraint(equalToConstant: Self.width).isActive = true
        offer(.workspaces, false)
        show(nil)
    }

    /// Shows `item` or takes it off the rail.
    func offer(_ item: Item, _ shown: Bool) {
        buttons[item]?.isHidden = !shown
        if !shown { bars[item]?.isHidden = true }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
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
            button.layer?.backgroundColor = chosen ? chosenInk.fill.map { NSColor($0).cgColor } ?? NSColor.clear.cgColor : NSColor.clear.cgColor
            button.image = Self.image(each, colour: chosen ? chosenInk.icon : look.textSecondary)
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
    var chosenWash: (size: NSSize, colour: CGColor?)? {
        guard let selected, let button = buttons[selected] else { return nil }
        return (button.bounds.size, button.layer?.backgroundColor)
    }
}
