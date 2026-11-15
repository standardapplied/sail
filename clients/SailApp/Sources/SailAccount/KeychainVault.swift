import Foundation
import SailAPI
import Security

/// The session in the data-protection keychain: one generic-password item per control plane,
/// service `sh.standardapplied.sail.session`, account the control plane's address, readable only
/// while the Mac is unlocked, never synchronized, in the app's default access group.
public struct KeychainVault: TokenVault {
    public static let service = "sh.standardapplied.sail.session"

    public init() {}

    /// The query naming `plane`'s item, as every call asks it.
    public static func item(_ plane: ControlPlane) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: plane.description,
            kSecUseDataProtectionKeychain as String: true, kSecAttrSynchronizable as String: false,
        ]
    }

    public func session(for controlPlane: ControlPlane) throws(VaultFailure) -> PasskeySession? {
        var query = Self.item(controlPlane)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = found as? Data else { throw Self.failure(status) }
        guard let session = try? JSONDecoder().decode(PasskeySession.self, from: data) else {
            throw VaultFailure(status: "the item for \(controlPlane) is not a sign-in Sail kept")
        }
        return session
    }

    public func keep(_ session: PasskeySession, for controlPlane: ControlPlane) throws(VaultFailure) {
        try forget(controlPlane)
        guard let data = try? JSONEncoder().encode(session) else { throw VaultFailure(status: "the sign-in could not be written") }
        var item = Self.item(controlPlane)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        item[kSecAttrLabel as String] = "Sail sign-in to \(controlPlane)"
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw Self.failure(status) }
    }

    public func forget(_ controlPlane: ControlPlane) throws(VaultFailure) {
        let status = SecItemDelete(Self.item(controlPlane) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Self.failure(status) }
    }

    static func failure(_ status: OSStatus) -> VaultFailure {
        let words = SecCopyErrorMessageString(status, nil) as String? ?? "status \(status)"
        guard status == errSecMissingEntitlement else { return VaultFailure(status: "\(words) (\(status))") }
        return VaultFailure(status: "errSecMissingEntitlement (\(status))", remedy: "This build is unsigned. Use a signed build.")
    }
}
