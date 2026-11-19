/// Who holds a runner session's keyboard, as this client reads the host's `WriterChanged`, which
/// says whether the keyboard is this connection's: two clients of one person, or someone else
/// taking it in the same moment as Take Keyboard, are each told truly which of them types.
public struct Keyboard: Equatable, Sendable {
    /// Who holds the keyboard while this client does not; nil while it is here, or no one holds it.
    public private(set) var elsewhere: String?
    /// The person this client is, as the last change was read for.
    private var me = ""

    public init() {}

    /// The host said `writer` holds the keyboard, `yours` when it is this client; `me` is the person
    /// this client is.
    public mutating func writerChanged(to writer: String, yours: Bool, me: String) {
        self.me = me
        elsewhere = yours || writer.isEmpty ? nil : writer
    }

    /// What the pane says while the keyboard is elsewhere: where it went when this person took it
    /// (the host tells only that it is them, not which window or Mac), and who has it when someone
    /// else did.
    public var said: String? {
        guard let elsewhere else { return nil }
        guard elsewhere != me else { return "Typing here is paused: you took the keyboard in another window or on another Mac." }
        return "Typing here is paused: the keyboard is with \(elsewhere) now."
    }

    /// What closing this client's tab says while the keyboard is elsewhere, the session going on
    /// there: nil while it is here.
    public var stillRunning: String? {
        guard let elsewhere else { return nil }
        return "Still running: the keyboard is with \(elsewhere == me ? "your other window or Mac" : elsewhere)."
    }
}
