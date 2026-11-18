@preconcurrency import Darwin

/// Has standard output written line by line, so every line the drive prints reaches its output
/// file as printed, and none is lost to a buffer when the drive crashes or is ended. Only Darwin
/// is imported here: C's `stdout` is shared mutable state, which a `@preconcurrency` import of the
/// module declaring it may name.
enum LineBuffered {
    static func standardOutput() {
        setvbuf(stdout, nil, _IOLBF, 0)
    }
}
