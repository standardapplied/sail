/// The opening of a terminal connection: each side writes its magic and reads the other's back.
public enum Handshake {
    public static let magic: [UInt8] = Array("SAILPTY5".utf8)

    /// Passes when `peer` is this protocol's magic; any other is a peer this build cannot talk to.
    public static func check(peer: [UInt8]) throws(TerminalWireError) {
        guard peer == magic else { throw .otherProtocol }
    }
}
