import GhosttyVt

/// What feeding a screen produced besides the screen itself.
public struct Effects: Equatable, Sendable {
    /// Bytes the terminal owes the program, a device status or size report say: the session sends
    /// them to the host as input.
    public var reply: [UInt8]
    public var events: [ScreenEvent]

    public init(reply: [UInt8] = [], events: [ScreenEvent] = []) {
        self.reply = reply
        self.events = events
    }

    public var isEmpty: Bool {
        reply.isEmpty && events.isEmpty
    }
}

/// Something the program asked of the terminal that the app may show.
public enum ScreenEvent: Equatable, Sendable {
    case bell
    case title(String)
    case workingDirectory(String)
    /// The program wrote the clipboard (OSC 52 and its kin) and the policy let it: the text it wrote.
    case clipboardWrite(String)
    case notification(title: String, body: String)
}

/// Whether a program may write the person's clipboard.
public enum ClipboardWritePolicy: Equatable, Sendable {
    case allow
    case deny
}

/// What the terminal's callbacks collect during one call into it. It lives inside the screen's lock
/// and the callbacks run within the call that holds it, so it is never shared.
final class EffectSink {
    var reply: [UInt8] = []
    var events: [ScreenEvent] = []
    var titleChanged = false
    var workingDirectoryChanged = false
    /// Between a replay's begin and end the stream is history: a replayed query is not answered
    /// again, nor a bell rung or a clipboard written for it.
    var replaying = false
    var clipboardPolicy = ClipboardWritePolicy.allow
    var size = GhosttySizeReportSize()
    let isDark: Bool

    init(isDark: Bool) {
        self.isDark = isDark
    }

    func take() -> (reply: [UInt8], events: [ScreenEvent], title: Bool, workingDirectory: Bool) {
        defer {
            reply = []
            events = []
            titleChanged = false
            workingDirectoryChanged = false
        }
        return (reply, events, titleChanged, workingDirectoryChanged)
    }

    /// Installs every callback on `terminal`, each reaching this sink through the userdata.
    func install(on terminal: GhosttyTerminal) throws(ScreenError) {
        let me = Unmanaged.passUnretained(self).toOpaque()
        try set(terminal, GHOSTTY_TERMINAL_OPT_USERDATA, me)
        let writePty: GhosttyTerminalWritePtyFn = { _, userdata, data, length in
            guard let sink = EffectSink.from(userdata), let data, !sink.replaying else { return }
            sink.reply.append(contentsOf: UnsafeBufferPointer(start: data, count: length))
        }
        let bell: GhosttyTerminalBellFn = { _, userdata in
            guard let sink = EffectSink.from(userdata), !sink.replaying else { return }
            sink.events.append(.bell)
        }
        let title: GhosttyTerminalTitleChangedFn = { _, userdata in
            EffectSink.from(userdata)?.titleChanged = true
        }
        let workingDirectory: GhosttyTerminalPwdChangedFn = { _, userdata in
            EffectSink.from(userdata)?.workingDirectoryChanged = true
        }
        let size: GhosttyTerminalSizeFn = { _, userdata, out in
            guard let sink = EffectSink.from(userdata), let out else { return false }
            out.pointee = sink.size
            return true
        }
        let scheme: GhosttyTerminalColorSchemeFn = { _, userdata, out in
            guard let sink = EffectSink.from(userdata), let out else { return false }
            out.pointee = sink.isDark ? GHOSTTY_COLOR_SCHEME_DARK : GHOSTTY_COLOR_SCHEME_LIGHT
            return true
        }
        let clipboardWrite: GhosttyTerminalClipboardWriteFn = { _, userdata, write in
            guard let write else { return }
            let sink = EffectSink.from(userdata)
            let allowed = sink.map { !$0.replaying && $0.clipboardPolicy == .allow } ?? false
            var reply = GhosttyClipboardWriteReply()
            reply.size = MemoryLayout<GhosttyClipboardWriteReply>.size
            reply.result = allowed ? GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS : GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED
            if allowed, let text = EffectSink.text(of: write.pointee) {
                sink?.events.append(.clipboardWrite(text))
            }
            write.pointee.reply?(write, &reply)
        }
        let clipboardRead: GhosttyTerminalClipboardReadFn = { _, _, read in
            guard let read else { return }
            var reply = GhosttyClipboardReadReply()
            reply.size = MemoryLayout<GhosttyClipboardReadReply>.size
            reply.result = GHOSTTY_CLIPBOARD_READ_RESULT_DENIED
            read.pointee.reply?(read, &reply)
        }
        let notification: GhosttyTerminalDesktopNotificationFn = { _, userdata, note in
            guard let sink = EffectSink.from(userdata), let note, !sink.replaying else { return }
            sink.events.append(.notification(title: string(note.pointee.title), body: string(note.pointee.body)))
        }
        try set(terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, unsafeBitCast(writePty, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_BELL, unsafeBitCast(bell, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, unsafeBitCast(title, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_PWD_CHANGED, unsafeBitCast(workingDirectory, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_SIZE, unsafeBitCast(size, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_COLOR_SCHEME, unsafeBitCast(scheme, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, unsafeBitCast(clipboardWrite, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ, unsafeBitCast(clipboardRead, to: UnsafeRawPointer.self))
        try set(terminal, GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION, unsafeBitCast(notification, to: UnsafeRawPointer.self))
    }

    private static func from(_ userdata: UnsafeMutableRawPointer?) -> EffectSink? {
        userdata.map { Unmanaged<EffectSink>.fromOpaque($0).takeUnretainedValue() }
    }

    /// The text a clipboard write carries: its first `text/…` representation.
    private static func text(of write: GhosttyClipboardWrite) -> String? {
        guard let contents = write.contents else { return nil }
        return UnsafeBufferPointer(start: contents, count: write.contents_len)
            .first { string($0.mime).hasPrefix("text/") }
            .map { string($0.data) }
    }
}

/// The text a borrowed libghostty-vt string holds, invalid UTF-8 repaired.
func string(_ value: GhosttyString) -> String {
    guard let pointer = value.ptr else { return "" }
    return String(decoding: UnsafeBufferPointer(start: pointer, count: value.len), as: UTF8.self)
}

func set(_ terminal: GhosttyTerminal, _ option: GhosttyTerminalOption, _ value: UnsafeRawPointer?) throws(ScreenError) {
    let result = ghostty_terminal_set(terminal, option, value)
    guard result == GHOSTTY_SUCCESS else { throw .refused(call: "ghostty_terminal_set(\(option.rawValue))", result: Int32(result.rawValue)) }
}
