import Foundation
import SailAPI
import SailTerminalWire

/// What a person changes of a workspace's names: a file or a folder made, one moved (a rename is a
/// move within its folder), one removed. Each is one request, answered done or refused in a true
/// sentence; none ever replaces a name already there (F11), which the host decides in the one call
/// that makes the name. A folder moved into itself or under itself is refused here, before any frame
/// (M22). The tree is not touched: a row appears, moves or goes only by the watch.
///
/// Nothing here speaks to a connection: each act answers the frame to send, and the caller sends it,
/// so the whole of it is tested on its own.
public struct FileActions: Sendable {
    public enum Action: Equatable, Sendable {
        case create(FileName, folder: Bool)
        case move(from: FileName, to: FileName)
        case remove(FileName)
    }

    /// What an ask comes to: the frame to send, or its refusal before any frame.
    public enum Asked: Equatable, Sendable {
        case send(Frame)
        case refused(String)
    }

    public enum Answer: Equatable, Sendable {
        case done(Action)
        case refused(Action, String)
    }

    public static let intoItself = "A folder cannot be moved into itself."
    public static let notAName = "A name cannot be . or .. or hold a /."

    private let workspace: String
    private var asked: [Int32: Action] = [:]
    private var nextID: Int32

    /// Requests numbered from `firstID`, apart from the tree's, a lookup's and a pane's.
    public init(workspace: String, firstID: Int32 = 1 << 28) {
        self.workspace = workspace
        nextID = firstID
    }

    /// Whether an action is under way: asked, and neither answered nor ended with its connection.
    public var underWay: Bool {
        !asked.isEmpty
    }

    /// Asks `action`: its frame, or its refusal when it would move a folder into itself.
    public mutating func ask(_ action: Action) -> Asked {
        if case let .move(from, to) = action, to == from || to.isUnder(from) { return .refused(Self.intoItself) }
        let id = nextID
        nextID += 1
        asked[id] = action
        return switch action {
        case let .create(path, folder): .send(.filesCreate(id: id, path: path, folder: folder))
        case let .move(from, to): .send(.filesMove(id: id, from: from, to: to))
        case let .remove(path): .send(.filesRemove(id: id, path: path))
        }
    }

    /// The answer `frame` gives to an action of these, nil for any other frame.
    public mutating func receive(_ frame: Frame) -> Answer? {
        switch frame {
        case let .filesDone(id):
            return asked.removeValue(forKey: id).map { .done($0) }
        case let .filesRefused(id, kind, message):
            return asked.removeValue(forKey: id).map { .refused($0, Self.sentence(kind, message, $0)) }
        default:
            return nil
        }
    }

    /// The connection ended: every action under way is said so, its outcome left to the tree.
    public mutating func disconnected() -> [Answer] {
        defer { asked = [:] }
        return asked.sorted { $0.key < $1.key }.map {
            .refused($0.value, "The connection to \(workspace) dropped before it answered; the tree shows what is there.")
        }
    }

    /// A name typed for a new entry or a rename is one entry's name: no slash, and not `.` or `..`.
    public static func isName(_ typed: String) -> Bool {
        !typed.isEmpty && typed != "." && typed != ".." && !typed.contains("/")
    }

    /// A taken name and a folder partly removed in the contract's words, naming what the person
    /// typed or chose; every other refusal in the host's own.
    private static func sentence(_ kind: Open<FileRefusal>, _ message: String, _ action: Action) -> String {
        switch (kind, action) {
        case let (.known(.exists), .create(path, _)), let (.known(.exists), .move(_, path)): "\(path.leaf.shown) is already there."
        case let (.known(.partlyRemoved), .remove(path)): "Some of \(path.leaf.shown) could not be deleted."
        default: message
        }
    }
}

extension FileName {
    /// Its last name, the folder's own for a folder.
    public var leaf: FileName {
        FileName(Array(bytes.split(separator: 0x2F).last ?? []))
    }

    /// The path as a shell reads it back, pasted at a prompt: as it is when every byte is plain,
    /// else in single quotes, a quote in it closed, escaped and opened again; a path that is not
    /// UTF-8 in ANSI-C quotes, each byte that is not plain written `\xHH`.
    public var shellQuoted: String {
        let plain = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/._-+,:@%".utf8)
        if !bytes.isEmpty, bytes.allSatisfy(plain.contains) { return shown }
        guard let text = String(validating: bytes, as: UTF8.self) else {
            return "$'" + bytes.map { (0x20 ..< 0x7F).contains($0) && $0 != 0x27 && $0 != 0x5C ? String(UnicodeScalar($0)) : String(format: "\\x%02x", $0) }.joined() + "'"
        }
        return "'" + text.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
