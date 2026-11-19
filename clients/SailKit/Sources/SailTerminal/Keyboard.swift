/// Who holds a runner session's keyboard, as this client reads the host's `WriterChanged`, which
/// says whether the keyboard is this connection's: two clients of one person, or someone else
/// taking it in the same moment as Take Keyboard, are each told truly which of them types.
public struct Keyboard: Equatable, Sendable {
    /// Who holds the keyboard while this client does not; nil while it is here, or no one holds it.
    public private(set) var elsewhere: String?
    /// The person this client is, as the last change was read for.
    private var me = ""
    /// No one holds the keyboard, and it is not here.
    private var free = false
    /// The session's opener, once known: a free keyboard is said only to someone else, since the
    /// opener's typing takes it.
    public var opener: String?

    public init() {}

    /// The host said `writer` holds the keyboard, `yours` when it is this client; `me` is the person
    /// this client is.
    public mutating func writerChanged(to writer: String, yours: Bool, me: String) {
        self.me = me
        elsewhere = yours || writer.isEmpty ? nil : writer
        free = !yours && writer.isEmpty
    }

    /// Who opened the session could not be learned (its listing refused, or not naming it): a free
    /// keyboard is said anyway, since silence is the worse answer, and if this client is the opener
    /// after all its first key takes the keyboard and the words go.
    public mutating func openerNotLearned() {
        opener = ""
    }

    /// The keyboard is free and who opened the session is not known yet: ask it.
    public var needsOpener: Bool {
        free && opener == nil
    }

    /// What the pane says while the keyboard is elsewhere: where it went when this person took it
    /// (the host tells only that it is them, not which window or Mac), and who has it when someone
    /// else did.
    public var said: String? {
        guard let elsewhere else { return free && opener.map { $0 != me } == true ? "No one holds the keyboard." : nil }
        guard elsewhere != me else { return "Typing here is paused: you took the keyboard in another window or on another Mac." }
        return "Typing here is paused: the keyboard is with \(elsewhere) now."
    }

    /// What closing this client's tab, named `tab`, says while the keyboard is elsewhere, the
    /// session going on there: nil while it is here or no one holds it.
    public func stillRunning(_ tab: String) -> String? {
        guard let elsewhere else { return nil }
        return "\(tab) is still running: the keyboard is with \(elsewhere == me ? "your other window or Mac" : elsewhere)."
    }
}
