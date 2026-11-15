import AppKit
import AuthenticationServices
import SailAPI

/// The Mac's own passkeys: the server's options become a platform registration or assertion
/// request, shown as the system's sheet on the window `anchor` names, and the answer or the error
/// comes back as `async`. Every `ASAuthorizationError` code ends in one `PasskeyFailure`.
public final class PlatformPasskeys: PasskeyAuthenticator {
    private let anchor: @MainActor @Sendable () -> NSWindow?

    public init(anchor: @escaping @MainActor @Sendable () -> NSWindow?) {
        self.anchor = anchor
    }

    public func register(_ request: PasskeyRegistrationRequest) async throws(PasskeyFailure) -> PasskeyRegistration {
        let answer = await Sheet.run(on: anchor) {
            let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: request.relyingParty)
            let asking = provider.createCredentialRegistrationRequest(challenge: request.challenge, name: request.userName, userID: request.userId)
            asking.displayName = request.displayName
            asking.userVerificationPreference = .required
            asking.attestationPreference = .none
            asking.excludedCredentials = request.excluded.map { ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: $0) }
            return asking
        }
        guard case let .registered(made) = answer else { throw answer.failure }
        return made
    }

    public func assert(_ request: PasskeyAssertionRequest) async throws(PasskeyFailure) -> PasskeyAssertion {
        let answer = await Sheet.run(on: anchor) {
            let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: request.relyingParty)
            let asking = provider.createCredentialAssertionRequest(challenge: request.challenge)
            asking.userVerificationPreference = .required
            return asking
        }
        guard case let .asserted(signed) = answer else { throw answer.failure }
        return signed
    }

    /// What `error` from the authorization controller means. A failure whose words name the
    /// webcredentials association is that, whatever its code; cancelling and finding no passkey are
    /// one answer, as the platform gives them.
    public static func failure(_ error: any Error) -> PasskeyFailure {
        let system = String(describing: error as NSError)
        if system.localizedCaseInsensitiveContains("webcredentials") {
            return .associationNotConfirmed(system)
        }
        guard let code = (error as? ASAuthorizationError)?.code else { return .failed(system) }
        if #available(macOS 26, *), code == .deviceNotConfiguredForPasskeyCreation {
            return .cannotCreate(system)
        }
        switch code {
        case .canceled: return .cancelled
        case .matchedExcludedCredential: return .alreadyHeld
        default: return .failed(system)
        }
    }
}

enum SheetAnswer: Sendable {
    case registered(PasskeyRegistration)
    case asserted(PasskeyAssertion)
    case failed(PasskeyFailure)

    var failure: PasskeyFailure {
        switch self {
        case let .failed(failure): failure
        case .registered, .asserted: .failed("macOS answered with a passkey of the other kind")
        }
    }
}

/// One authorization controller's run, kept alive until it answers once.
@MainActor
final class Sheet: NSObject, @preconcurrency ASAuthorizationControllerDelegate, @preconcurrency ASAuthorizationControllerPresentationContextProviding {
    private static var running: Set<Sheet> = []
    private let window: NSWindow
    private var answer: CheckedContinuation<SheetAnswer, Never>?

    private init(window: NSWindow) {
        self.window = window
    }

    static func run(on anchor: @MainActor () -> NSWindow?, _ request: @MainActor () -> ASAuthorizationRequest) async -> SheetAnswer {
        guard let window = anchor() else { return .failed(.failed("Sail has no window to show the passkey sheet on.")) }
        let sheet = Sheet(window: window)
        let controller = ASAuthorizationController(authorizationRequests: [request()])
        return await withCheckedContinuation { continuation in
            sheet.answer = continuation
            running.insert(sheet)
            controller.delegate = sheet
            controller.presentationContextProvider = sheet
            controller.performRequests()
        }
    }

    func authorizationController(controller _: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        switch authorization.credential {
        case let made as ASAuthorizationPlatformPublicKeyCredentialRegistration:
            guard let attestation = made.rawAttestationObject else {
                return finish(.failed(.failed("macOS made a passkey and gave no attestation object")))
            }
            finish(.registered(PasskeyRegistration(credentialId: made.credentialID, clientDataJSON: made.rawClientDataJSON, attestationObject: attestation)))
        case let signed as ASAuthorizationPlatformPublicKeyCredentialAssertion:
            finish(.asserted(PasskeyAssertion(
                credentialId: signed.credentialID, clientDataJSON: signed.rawClientDataJSON, authenticatorData: signed.rawAuthenticatorData,
                signature: signed.signature, userHandle: signed.userID
            )))
        default:
            finish(.failed(.failed("macOS answered with a credential that is not a passkey")))
        }
    }

    func authorizationController(controller _: ASAuthorizationController, didCompleteWithError error: any Error) {
        finish(.failed(PlatformPasskeys.failure(error)))
    }

    func presentationAnchor(for _: ASAuthorizationController) -> ASPresentationAnchor {
        window
    }

    private func finish(_ result: SheetAnswer) {
        answer?.resume(returning: result)
        answer = nil
        Self.running.remove(self)
    }
}
