import Foundation
@testable import SailAccount
import SailAPI
import Testing

/// What a kept item holds: a session this build wrote reads back; an item of the earlier shape
/// (`token`, `fde`, `expiresAt`, written before the vault kept the server's own session) does not,
/// which the vault then removes and says as unreadable (`AccountTests` in SailKit, the drive's
/// `keychain-item-of-the-old-shape` on signed runs).
@Suite struct KeychainItemTests {
    @Test func aSessionThisBuildKeptReadsBack() throws {
        let kept = PasskeySession(sessionToken: "sess_item", fde: "uday", expiresAt: Date(timeIntervalSince1970: 1_800_000_000))
        #expect(KeychainVault.session(in: try JSONEncoder().encode(kept)) == kept)
    }

    @Test func anItemOfTheEarlierShapeIsNotASession() {
        let earlier = Data(#"{"token":"sess_item","fde":"uday","expiresAt":815000000}"#.utf8)
        #expect(KeychainVault.session(in: earlier) == nil)
    }
}
