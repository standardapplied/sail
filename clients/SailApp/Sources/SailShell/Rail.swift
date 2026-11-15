import AppKit
import SailLook
import SailTerminalView

/// The icon rail, 60 pt wide on the window's chrome: "This Mac" on top, the account at the bottom.
/// The selected item takes the accent fill and a bar at its leading edge.
@MainActor
public final class Rail: NSView {
    public static let width = 60.0

    public enum Item: String, CaseIterable, Sendable {
        case thisMac, account

        var title: String {
            switch self {
            case .thisMac: "This Mac"
            case .account: "Account"
            }
        }

        var symbol: String {
            switch self {
            case .thisMac: "terminal"
            case .account: "person.crop.circle"
            }
        }
    }

    public private(set) var selected: Item?
    var onSelect: @MainActor (Item) -> Void = { _ in }

    private let look: Look
    private var buttons: [Item: NSButton] = [:]
    private var bars: [Item: NSView] = [:]

    init(look: Look) {
        self.look = look
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor(look.chrome).cgColor
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
            bar.layer?.backgroundColor = NSColor(look.accent).cgColor
            bar.translatesAutoresizingMaskIntoConstraints = false
            addSubview(button)
            addSubview(bar)
            buttons[item] = button
            bars[item] = bar
            NSLayoutConstraint.activate([
                button.centerXAnchor.constraint(equalTo: centerXAnchor), button.widthAnchor.constraint(equalToConstant: 44),
                button.heightAnchor.constraint(equalToConstant: 44), bar.leadingAnchor.constraint(equalTo: leadingAnchor),
                bar.widthAnchor.constraint(equalToConstant: 3), bar.centerYAnchor.constraint(equalTo: button.centerYAnchor),
                bar.heightAnchor.constraint(equalToConstant: 24),
                item == .thisMac
                    ? button.topAnchor.constraint(equalTo: topAnchor, constant: Look.Space.x3l + Look.Space.s)
                    : button.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Look.Space.m),
            ])
        }
        widthAnchor.constraint(equalToConstant: Self.width).isActive = true
        show(nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func show(_ item: Item?) {
        selected = item
        for (each, button) in buttons {
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
