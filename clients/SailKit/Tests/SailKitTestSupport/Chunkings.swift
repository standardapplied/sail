/// SplitMix64: a small generator whose sequence is fixed by its seed, so a failing chunking replays.
public struct SeededGenerator: RandomNumberGenerator, Sendable {
    private var state: UInt64

    public init(seed: UInt64) {
        state = seed
    }

    public mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var mixed = state
        mixed = (mixed ^ (mixed >> 30)) &* 0xBF58_476D_1CE4_E5B9
        mixed = (mixed ^ (mixed >> 27)) &* 0x94D0_49BB_1331_11EB
        return mixed ^ (mixed >> 31)
    }
}

/// `bytes` cut into chunks the way `seed` picks: empty ones, single bytes and long runs alike, in
/// order, so their concatenation is `bytes` again.
public func chunking(of bytes: [UInt8], seed: UInt64) -> [[UInt8]] {
    var generator = SeededGenerator(seed: seed)
    var chunks: [[UInt8]] = []
    var start = 0
    while start < bytes.count {
        let longest = [1, 3, 16, 256, bytes.count].randomElement(using: &generator) ?? 1
        let end = min(bytes.count, start + Int.random(in: 0 ... longest, using: &generator))
        chunks.append(Array(bytes[start ..< end]))
        start = end
    }
    return chunks
}

/// `bytes` cut in two at `offset`.
public func cut(_ bytes: [UInt8], at offset: Int) -> [[UInt8]] {
    [Array(bytes[..<offset]), Array(bytes[offset...])]
}
