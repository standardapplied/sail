import GhosttyVt

/// One libghostty-vt object, freed when the last reference to it goes; an object made before a
/// later step of a set-up fails is freed with that failure.
final class Owned {
    let pointer: OpaquePointer
    private let release: (OpaquePointer) -> Void

    private init(pointer: OpaquePointer, release: @escaping (OpaquePointer) -> Void) {
        self.pointer = pointer
        self.release = release
    }

    deinit {
        release(pointer)
    }

    /// The object `create` makes, refused naming `call` when the library refuses it.
    static func make(
        _ call: String, _ create: (UnsafeMutablePointer<OpaquePointer?>) -> GhosttyResult,
        release: @escaping (OpaquePointer) -> Void
    ) throws(ScreenError) -> Owned {
        var made: OpaquePointer?
        let result = create(&made)
        guard result == GHOSTTY_SUCCESS, let made else { throw .refused(call: call, result: Int32(result.rawValue)) }
        return Owned(pointer: made, release: release)
    }
}

/// `call`'s result, refused unless it succeeded; the call is named only for a refusal.
func require(_ call: @autoclosure () -> String, _ result: GhosttyResult) throws(ScreenError) {
    guard result == GHOSTTY_SUCCESS else { throw .refused(call: call(), result: Int32(result.rawValue)) }
}

/// The bytes an encoder writes: tried with a buffer of `guess` bytes, then once more with what the
/// encoder says it needs. Nothing when the encoder refuses or writes nothing.
func encoded(guess: Int = 128, _ encode: (UnsafeMutablePointer<UInt8>?, Int, UnsafeMutablePointer<Int>) -> GhosttyResult) -> [UInt8] {
    var needed = 0
    var bytes = [UInt8](repeating: 0, count: guess)
    var result = bytes.withUnsafeMutableBufferPointer { encode($0.baseAddress, $0.count, &needed) }
    if result == GHOSTTY_OUT_OF_SPACE, needed > guess {
        bytes = [UInt8](repeating: 0, count: needed)
        result = bytes.withUnsafeMutableBufferPointer { encode($0.baseAddress, $0.count, &needed) }
    }
    guard result == GHOSTTY_SUCCESS else { return [] }
    return Array(bytes.prefix(needed))
}
