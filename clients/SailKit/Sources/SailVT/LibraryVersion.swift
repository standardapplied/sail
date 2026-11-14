import GhosttyVt

/// The libghostty-vt this package is built for: the 1.x line from 1.0.1, whose C ABI only grows.
public enum LibraryVersion {
    public static let required = "1.0.1"

    /// The version the loaded library reports.
    public static var loaded: String {
        String(cString: ghostty_version())
    }

    /// Passes for a version of major 1 at least ``required``; any other is refused naming both.
    public static func check(_ reported: String) throws(ScreenError) {
        let parts = reported.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
        guard parts.count == 3, let major = parts[0], let minor = parts[1], let patch = parts[2],
              major == 1, (minor, patch) >= (0, 1)
        else {
            throw .unsupportedLibrary(found: reported, required: required)
        }
    }
}

public enum ScreenError: Error, Equatable, Sendable, CustomStringConvertible {
    /// The loaded libghostty-vt is not one this package can drive.
    case unsupportedLibrary(found: String, required: String)
    /// libghostty-vt refused a call, by name, with its result code.
    case refused(call: String, result: Int32)
    /// A value given to the screen is outside what it accepts.
    case invalid(String)

    public var description: String {
        switch self {
        case let .unsupportedLibrary(found, required):
            "libghostty-vt \(found) is loaded, but this build needs 1.x from \(required)"
        case let .refused(call, result):
            "libghostty-vt refused \(call) (result \(result))"
        case let .invalid(reason):
            reason
        }
    }
}
