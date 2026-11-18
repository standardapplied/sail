/// SHA-256 (FIPS 180-4), fed in pieces: the digest an upload ends with, which the host checks
/// against the bytes it received. Written here because the Linux toolchain has no CryptoKit and
/// SailKit takes no dependency for one hash.
public struct SHA256: Sendable {
    public init() {}

    private static let k: [UInt32] = [
        0x428a_2f98, 0x7137_4491, 0xb5c0_fbcf, 0xe9b5_dba5, 0x3956_c25b, 0x59f1_11f1, 0x923f_82a4, 0xab1c_5ed5,
        0xd807_aa98, 0x1283_5b01, 0x2431_85be, 0x550c_7dc3, 0x72be_5d74, 0x80de_b1fe, 0x9bdc_06a7, 0xc19b_f174,
        0xe49b_69c1, 0xefbe_4786, 0x0fc1_9dc6, 0x240c_a1cc, 0x2de9_2c6f, 0x4a74_84aa, 0x5cb0_a9dc, 0x76f9_88da,
        0x983e_5152, 0xa831_c66d, 0xb003_27c8, 0xbf59_7fc7, 0xc6e0_0bf3, 0xd5a7_9147, 0x06ca_6351, 0x1429_2967,
        0x27b7_0a85, 0x2e1b_2138, 0x4d2c_6dfc, 0x5338_0d13, 0x650a_7354, 0x766a_0abb, 0x81c2_c92e, 0x9272_2c85,
        0xa2bf_e8a1, 0xa81a_664b, 0xc24b_8b70, 0xc76c_51a3, 0xd192_e819, 0xd699_0624, 0xf40e_3585, 0x106a_a070,
        0x19a4_c116, 0x1e37_6c08, 0x2748_774c, 0x34b0_bcb5, 0x391c_0cb3, 0x4ed8_aa4a, 0x5b9c_ca4f, 0x682e_6ff3,
        0x748f_82ee, 0x78a5_636f, 0x84c8_7814, 0x8cc7_0208, 0x90be_fffa, 0xa450_6ceb, 0xbef9_a3f7, 0xc671_78f2,
    ]

    private var state: [UInt32] = [
        0x6a09_e667, 0xbb67_ae85, 0x3c6e_f372, 0xa54f_f53a, 0x510e_527f, 0x9b05_688c, 0x1f83_d9ab, 0x5be0_cd19,
    ]
    private var pending: [UInt8] = []
    private var length: UInt64 = 0

    public mutating func update(_ bytes: some Collection<UInt8>) {
        length &+= UInt64(bytes.count)
        pending += bytes
        let whole = pending.count - pending.count % 64
        for start in stride(from: 0, to: whole, by: 64) {
            compress(pending[start ..< start + 64])
        }
        pending.removeFirst(whole)
    }

    /// The 32-byte digest of everything fed so far.
    public consuming func finalize() -> [UInt8] {
        let bits = length &* 8
        var tail = pending + [0x80]
        tail += [UInt8](repeating: 0, count: (120 - tail.count % 64) % 64)
        tail += withUnsafeBytes(of: bits.bigEndian, Array.init)
        for start in stride(from: 0, to: tail.count, by: 64) {
            compress(tail[start ..< start + 64])
        }
        return state.flatMap { word in withUnsafeBytes(of: word.bigEndian, Array.init) }
    }

    private mutating func compress(_ block: ArraySlice<UInt8>) {
        var w = [UInt32](repeating: 0, count: 64)
        for t in 0 ..< 16 {
            let i = block.startIndex + t * 4
            w[t] = UInt32(block[i]) << 24 | UInt32(block[i + 1]) << 16 | UInt32(block[i + 2]) << 8 | UInt32(block[i + 3])
        }
        for t in 16 ..< 64 {
            let s0 = w[t - 15].rotated(7) ^ w[t - 15].rotated(18) ^ (w[t - 15] >> 3)
            let s1 = w[t - 2].rotated(17) ^ w[t - 2].rotated(19) ^ (w[t - 2] >> 10)
            w[t] = w[t - 16] &+ s0 &+ w[t - 7] &+ s1
        }
        var (a, b, c, d, e, f, g, h) = (state[0], state[1], state[2], state[3], state[4], state[5], state[6], state[7])
        for t in 0 ..< 64 {
            let t1 = h &+ (e.rotated(6) ^ e.rotated(11) ^ e.rotated(25)) &+ ((e & f) ^ (~e & g)) &+ Self.k[t] &+ w[t]
            let t2 = (a.rotated(2) ^ a.rotated(13) ^ a.rotated(22)) &+ ((a & b) ^ (a & c) ^ (b & c))
            (h, g, f, e, d, c, b, a) = (g, f, e, d &+ t1, c, b, a, t1 &+ t2)
        }
        for (i, value) in [a, b, c, d, e, f, g, h].enumerated() {
            state[i] &+= value
        }
    }
}

private extension UInt32 {
    func rotated(_ count: UInt32) -> UInt32 {
        self >> count | self << (32 - count)
    }
}
