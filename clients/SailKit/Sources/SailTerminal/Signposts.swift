#if canImport(os)
import os
#endif

/// The session's signpost on Apple platforms (subsystem `sh.standardapplied.sail`, category
/// `terminal`): output read to screen updated, so Instruments shows it beside the view's. It
/// carries a name and times only, never a byte.
enum Signposts {
    #if canImport(os)
    private static let signposter = OSSignposter(subsystem: "sh.standardapplied.sail", category: "terminal")
    #endif

    static func outputToScreen<Result>(_ feed: () -> Result) -> Result {
        #if canImport(os)
        let interval = signposter.beginInterval("output read to screen updated")
        defer { signposter.endInterval("output read to screen updated", interval) }
        #endif
        return feed()
    }
}
