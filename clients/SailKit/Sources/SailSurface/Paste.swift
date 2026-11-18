/// Where a pane's session runs: on this Mac, where a pasted file's own path names it, or on another
/// machine, where the file is uploaded first and the path it is stored at is pasted.
public enum PastePlace: Sendable {
    case thisMac
    case remote
}

/// One file a paste or a drop names.
public struct PastedFile: Equatable, Sendable {
    public var path: String
    public var size: Int64
    public var folder: Bool

    public init(path: String, size: Int64, folder: Bool) {
        self.path = path
        self.size = size
        self.folder = folder
    }
}

/// A pasteboard flavour the pane reads, in the order the writer listed it.
public enum PasteFlavor: Equatable, Sendable {
    case text
    case files([PastedFile])
    case image
    case richText
    /// A flavour the pane does not read.
    case other

    /// The flavour a pasteboard type names; its files are the app's to fill in.
    public init(type: String) {
        self =
            switch type {
            case "public.utf8-plain-text", "public.plain-text": .text
            case "public.file-url": .files([])
            case "public.png", "public.tiff", "public.jpeg", "public.heic": .image
            case "public.rtf", "public.html": .richText
            default: .other
            }
    }
}

/// What a paste or a drop does, from the first flavour its writer listed that the pane reads.
public enum PasteContent: Equatable, Sendable {
    /// The text flavour, pasted as today.
    case text
    /// The plain text of a rich text flavour, pasted.
    case plainText
    /// These local paths, escaped and space-joined with a space after, pasted as text.
    case paths(String)
    /// These files, uploaded and their stored paths pasted.
    case upload([PastedFile])
    /// The image, written as a PNG (here) or uploaded as one (remote), and its path pasted.
    case image
    case refused(PasteRefusal)
    case nothing

    /// At most this many files in one paste or drop.
    public static let mostFiles = 32
    /// At most this many bytes in one paste or drop.
    public static let mostBytes: Int64 = 256 << 20

    /// What `flavors`, in the writer's order, do in a pane at `place`.
    public static func classify(types flavors: [PasteFlavor], place: PastePlace) -> PasteContent {
        switch flavors.first(where: { $0 != .other }) {
        case .text: .text
        case .richText: .plainText
        case .image: .image
        case let .files(files): classify(files, place: place)
        case .other, nil: .nothing
        }
    }

    private static func classify(_ files: [PastedFile], place: PastePlace) -> PasteContent {
        if files.isEmpty { return .nothing }
        if files.count > mostFiles { return .refused(.tooMany(count: files.count)) }
        switch place {
        case .thisMac:
            return .paths(files.map { ShellEscape.escape($0.path) }.joined(separator: " ") + " ")
        case .remote:
            if files.contains(where: \.folder) { return .refused(.folders) }
            let bytes = files.reduce(Int64(0)) { $0 + $1.size }
            return bytes > mostBytes ? .refused(.tooLarge(bytes: bytes)) : .upload(files)
        }
    }
}

/// A paste or a drop refused before anything is sent.
public enum PasteRefusal: Equatable, Sendable {
    case tooMany(count: Int)
    case tooLarge(bytes: Int64)
    case folders

    /// What the pane tells the person.
    public var sentence: String {
        switch self {
        case let .tooMany(count):
            "This paste holds \(count) files; at most \(PasteContent.mostFiles) are uploaded at once."
        case let .tooLarge(bytes):
            "This paste holds \((bytes + (1 << 20) - 1) >> 20) MB; at most \(PasteContent.mostBytes >> 20) MB are uploaded at once."
        case .folders:
            "Folders are not uploaded; drop the files."
        }
    }
}

/// A path as a shell reads it back, every special character backslash-escaped: Ghostty's
/// `Shell.escape`, so a dropped file pastes here as it does there.
public enum ShellEscape {
    private static let special = Set("\\ ()[]{}<>\"'`!#$&;|*?\t")

    public static func escape(_ path: String) -> String {
        var escaped = ""
        for character in path {
            if special.contains(character) { escaped.append("\\") }
            escaped.append(character)
        }
        return escaped
    }
}

/// The name a host stores an upload under: every byte outside `[A-Za-z0-9._-]` becomes `-`, at
/// most ``longest`` bytes, and `file` when nothing, `.` or `..` is left. The host's `PtyUploads.named`,
/// mirrored here so the pane can say what a file will be called.
public enum UploadName {
    public static let longest = 96

    public static func reduce(_ name: String) -> String {
        let kept = name.utf8.prefix(longest).map { byte -> UInt8 in
            switch byte {
            case UInt8(ascii: "a") ... UInt8(ascii: "z"), UInt8(ascii: "A") ... UInt8(ascii: "Z"),
                 UInt8(ascii: "0") ... UInt8(ascii: "9"), UInt8(ascii: "."), UInt8(ascii: "_"), UInt8(ascii: "-"):
                byte
            default:
                UInt8(ascii: "-")
            }
        }
        let reduced = String(decoding: kept, as: UTF8.self)
        return reduced.isEmpty || reduced == "." || reduced == ".." ? "file" : reduced
    }
}
