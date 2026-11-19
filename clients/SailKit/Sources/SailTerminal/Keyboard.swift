/// Who holds a runner session's keyboard, as this client reads the host's `WriterChanged`. The first
/// after an attach answers it: this person's attach to write takes the keyboard from their own other
/// client, never from someone else, so a writer other than them means it is theirs. One after Take
/// Keyboard answers that. Any other says someone took the keyboard, this person's other Mac included.
public struct Keyboard: Equatable, Sendable {
    /// Who holds the keyboard while this client does not; nil while it is here.
    public private(set) var elsewhere: String?
    private var answering = Answer.attach

    private enum Answer: Equatable, Sendable {
        case attach, take, nothing
    }

    public init() {}

    /// This client attached again: the next `WriterChanged` answers the attach.
    public mutating func attached() {
        answering = .attach
    }

    /// This client asked for the keyboard: the next `WriterChanged` answers that.
    public mutating func taking() {
        answering = .take
    }

    /// The host said `writer` holds the keyboard; `me` is the person this client is.
    public mutating func writerChanged(to writer: String, me: String) {
        switch answering {
        case .attach: elsewhere = writer == me || writer.isEmpty ? nil : writer
        case .take: elsewhere = nil
        case .nothing: elsewhere = writer.isEmpty ? nil : writer
        }
        answering = .nothing
    }

    /// What the pane says while the keyboard is elsewhere.
    public var said: String? {
        elsewhere.map { "Typing here is paused: the keyboard is with \($0) now." }
    }
}
