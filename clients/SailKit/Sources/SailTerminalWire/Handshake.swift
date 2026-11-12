/// The opening of a terminal connection: each side writes its magic and reads the other's back.
public enum Handshake {
    public static let magic: [UInt8] = Array("SAILPTY3".utf8)

    private static let older: [[UInt8]] = [Array("SAILPTY1".utf8), Array("SAILPTY2".utf8)]

    /// Passes when `peer` is this protocol's magic, and otherwise names which side is behind: an
    /// older magic is a host that predates this client, anything else one that has moved past it.
    public static func check(peer: [UInt8]) throws(TerminalWireError) {
        guard peer != magic else { return }
        throw .skew(older.contains(peer) ? .hostOlder : .clientOlder)
    }
}
