import SailAPI

/// An upload refused, by the host or before it was sent: what kind of refusal, the bound it met,
/// and the file it refused. The host sends the kind and the bound alone; ``sentence(box:project:)``
/// is the one place their words are written, for the app and the probe alike.
public struct UploadRefusal: Error, Equatable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case tooLarge = "too_large"
        case tooMany = "too_many"
        case sessionFull = "session_full"
        case notWriter = "not_writer"
        case noContainer = "no_container"
        case sharedContainer = "shared_container"
        case sessionEnded = "session_ended"
        case noSpace = "no_space"
        case failed
        case cancelled
        /// The host has no record of the upload asked after: it cannot say whether it stored it.
        case notKnown = "not_known"
        /// The workspace did not take a chunk within the host's deadline: its disk stalled.
        case stalled
        /// As many uploads as the session or the host keeps under way at once already are.
        case busy
        /// Said by this client alone: the connection dropped before the upload's end was sent, so
        /// the host, which stores a file only once its end arrives, kept nothing.
        case connectionLost = "connection_lost"
    }

    public var kind: Open<Kind>
    /// The bound the upload met, in files or bytes as `kind` says; zero when it met none.
    public var limit: Int64
    /// The file's name as the person knows it.
    public var file: String
    public var size: Int64

    public init(kind: Open<Kind>, limit: Int64, file: String, size: Int64) {
        self.kind = kind
        self.limit = limit
        self.file = file
        self.size = size
    }

    /// What the app tells the person, for a session on `box` in `project`.
    public func sentence(box: String, project: String) -> String {
        switch kind {
        case .known(.tooLarge):
            "\(file) is \(Self.megabytes(size, roundingUp: true)); files up to \(Self.megabytes(limit, roundingUp: false)) are uploaded."
        case .known(.tooMany):
            "This session already holds \(limit) uploaded files, the most it keeps; open a new terminal to upload more."
        case .known(.sessionFull):
            "\(file) would take this session past \(Self.megabytes(limit, roundingUp: false)) of uploads, the most it keeps; open a new terminal to upload more."
        case .known(.notWriter):
            "You are watching this session; take the write token to paste files."
        case .known(.noContainer):
            "This terminal runs on \(box) itself, not in a workspace, so files are not uploaded to it."
        case .known(.sharedContainer):
            "This terminal runs in \(project)'s shared container, which every member's terminal reads, so files are not pasted here; paste them into a terminal in your own workspace of \(project)."
        case .known(.sessionEnded):
            "The session ended before \(file) was uploaded; nothing was saved."
        case .known(.noSpace):
            "The workspace on \(box) is out of disk space, so \(file) was not saved; free some space in it, then paste the file again."
        case .known(.cancelled):
            "\(file) was cancelled; nothing was saved."
        case .known(.notKnown):
            "The connection to \(box) dropped after \(file) was sent, and whether it was saved is not known; paste it again if it is not in your workspace."
        case .known(.stalled):
            "The workspace on \(box) stopped taking \(file), so it was not saved; paste it again."
        case .known(.busy):
            "\(limit) files are already uploading, the most at once; paste \(file) again when one has finished."
        case .known(.connectionLost):
            "The connection to \(box) dropped while \(file) was uploading, before it was sent whole, so nothing was saved; paste it again."
        case .known(.failed), .unknown:
            "\(file) could not be saved on \(box); nothing was pasted. Paste it again."
        }
    }

    /// `bytes` in the units a person reads, `MB` and `GB` of 1024: a size over a bound rounds up,
    /// so it never reads as the bound itself.
    static func megabytes(_ bytes: Int64, roundingUp: Bool) -> String {
        let mebibyte: Int64 = 1 << 20
        let whole = roundingUp ? (bytes + mebibyte - 1) / mebibyte : bytes / mebibyte
        return whole >= 1024 && whole % 1024 == 0 ? "\(whole / 1024) GB" : "\(whole) MB"
    }
}
