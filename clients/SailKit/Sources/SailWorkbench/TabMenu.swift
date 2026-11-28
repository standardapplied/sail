/// Every tab in the strip's order as the strip's count and the Window menu list it: its title as
/// the strip names it, and the digit ⌘ selects it by.
public struct TabMenu: Equatable, Sendable {
    public struct Entry: Equatable, Sendable {
        public let id: TabID
        public let title: String
        /// "1" to "8" for the first eight tabs, "9" for the last of nine or more, nil for the rest.
        public let key: String?
        public let selected: Bool
    }

    public let entries: [Entry]

    /// The digit that selects the last tab: its item's key from nine tabs on; with eight or fewer
    /// the last tab's item has its own number, and this key still selects it.
    public static let lastKey = "9"

    public init(_ bench: Workbench) {
        let tabs = bench.tabs
        entries = tabs.indices.map { index in
            let id = tabs[index].id
            return Entry(id: id, title: bench.title(of: id), key: Self.key(at: index, of: tabs.count), selected: id == bench.selected)
        }
    }

    /// The digit for the tab at `index` of `count`, as the browsers number theirs.
    static func key(at index: Int, of count: Int) -> String? {
        if index < 8 { return String(index + 1) }
        return index == count - 1 ? lastKey : nil
    }
}
