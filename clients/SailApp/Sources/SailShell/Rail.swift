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
            let button = NSButton(image: Self.image(item), target: self, action: #selector(pick(_:)))
            button.isBordered = false
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
            bars[each]?.layer?.backgroundColor = NSColor(look.accentText).cgColor
            let chosen = each == item
            button.layer?.backgroundColor = chosen ? NSColor(look.fillSelected).cgColor : NSColor.clear.cgColor
            button.contentTintColor = chosen ? NSColor(look.accentText) : NSColor(look.textSecondary)
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

    private static func image(_ item: Item) -> NSImage {
        let image = NSImage(systemSymbolName: item.symbol, accessibilityDescription: item.title) ?? NSImage()
        return image.withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: 20, weight: .regular)) ?? image
    }
}
