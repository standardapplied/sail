import SailAPI

/// The one mark a tab's end shows for its chats, least urgent first: none, ended, working, an
/// unseen turn, error, needs you.
public enum TabMark: Int, Comparable, CaseIterable, Sendable {
    case none, ended, working, unseenTurn, error, needsYou

    public static func < (one: TabMark, other: TabMark) -> Bool {
        one.rawValue < other.rawValue
    }
}

extension Pane {
    /// What a chat pane's state marks: its turn's end only while unseen; a terminal, a new chat,
    /// an idle chat and a state this app does not know, none.
    public var mark: TabMark {
        guard form == .chat, case let .known(state)? = chatState else { return .none }
        return switch state {
        case .needsYou: .needsYou
        case .error: .error
        case .yourTurn: unseen ? .unseenTurn : .none
        case .working: .working
        case .ended: .ended
        case .idle: .none
        }
    }
}

extension Workbench {
    /// The most urgent mark of `tab`'s panes.
    public func mark(of tab: TabID) -> TabMark {
        tabs.first { $0.id == tab }?.layout.leaves.compactMap { panes[$0]?.mark }.max() ?? .none
    }

    /// Chat `pane` stands at `state` now. A turn that ends here, the person's turn after another
    /// state, while its tab is not shown, is unseen until it is; any other state ends an unseen turn.
    public mutating func setChatState(_ state: Open<ChatState>, of pane: PaneID) {
        guard let was = panes[pane], was.form == .chat else { return }
        let yours = state == .known(.yourTurn)
        let ended = yours && was.chatState != nil && was.chatState != state && !shows(pane)
        panes[pane]?.chatState = state
        panes[pane]?.unseen = yours && (was.unseen || ended)
    }

    /// The window is key, or not; once it is, the tab in front is seen.
    public mutating func setWindowKey(_ key: Bool) {
        windowKey = key
        seeFront()
    }

    private func shows(_ pane: PaneID) -> Bool {
        windowKey && selectedTab?.layout.contains(pane) == true
    }

    /// Every pane of the tab in front seen, while the window is key.
    mutating func seeFront() {
        guard windowKey, let tab = selectedTab else { return }
        for id in tab.layout.leaves where panes[id]?.unseen == true {
            panes[id]?.unseen = false
        }
    }
}
