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
        let answer: Result<PasskeyRegistration, PasskeyFailure> = await Sheet.run(on: anchor) {
            let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: request.relyingParty)
            let asking = provider.createCredentialRegistrationRequest(challenge: request.challenge, name: request.userName, userID: request.userId)
            asking.displayName = request.displayName
            asking.userVerificationPreference = .required
            asking.attestationPreference = .none
            asking.excludedCredentials = request.excluded.map { ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: $0) }
            return asking
        }
        return try answer.get()
    }

    public func assert(_ request: PasskeyAssertionRequest) async throws(PasskeyFailure) -> PasskeyAssertion {
        let answer: Result<PasskeyAssertion, PasskeyFailure> = await Sheet.run(on: anchor) {
            let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: request.relyingParty)
            let asking = provider.createCredentialAssertionRequest(challenge: request.challenge)
            asking.userVerificationPreference = .required
            return asking
        }
        return try answer.get()
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

/// One authorization controller's run, kept alive until it answers once. A run the system has not
/// answered within the server's challenge life (five minutes) is cancelled and said, so the pane is
/// never left waiting.
@MainActor
final class Sheet: NSObject, @preconcurrency ASAuthorizationControllerDelegate, @preconcurrency ASAuthorizationControllerPresentationContextProviding {
    static let deadline = Duration.seconds(300)
    private static var running: Set<Sheet> = []
    private let window: NSWindow
    private var answer: CheckedContinuation<Result<any Sendable, PasskeyFailure>, Never>?

    private init(window: NSWindow) {
        self.window = window
    }

    static func run<Made: Sendable>(
        on anchor: @MainActor () -> NSWindow?, _ request: @MainActor () -> ASAuthorizationRequest
    ) async -> Result<Made, PasskeyFailure> {
        guard let window = anchor() else { return .failure(.failed("Sail has no window to show the passkey sheet on.")) }
        let sheet = Sheet(window: window)
        let controller = ASAuthorizationController(authorizationRequests: [request()])
        let made = await withCheckedContinuation { continuation in
            sheet.answer = continuation
            running.insert(sheet)
            controller.delegate = sheet
            controller.presentationContextProvider = sheet
            controller.performRequests()
            Task { @MainActor in
                try? await Task.sleep(for: deadline)
                guard sheet.answer != nil else { return }
                controller.cancel()
                sheet.finish(.failure(.failed("macOS did not answer the passkey request within five minutes.")))
            }
        }
        return made.flatMap { (made) -> Result<Made, PasskeyFailure> in
            (made as? Made).map { .success($0) } ?? .failure(.failed("macOS answered with a passkey of the other kind"))
        }
    }

    func authorizationController(controller _: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        switch authorization.credential {
        case let made as ASAuthorizationPlatformPublicKeyCredentialRegistration:
            guard let attestation = made.rawAttestationObject else {
                return finish(.failure(.failed("macOS made a passkey and gave no attestation object")))
            }
            finish(.success(PasskeyRegistration(credentialId: made.credentialID, clientDataJSON: made.rawClientDataJSON, attestationObject: attestation)))
        case let signed as ASAuthorizationPlatformPublicKeyCredentialAssertion:
            finish(.success(PasskeyAssertion(
                credentialId: signed.credentialID, clientDataJSON: signed.rawClientDataJSON, authenticatorData: signed.rawAuthenticatorData,
                signature: signed.signature, userHandle: signed.userID
            )))
        default:
            finish(.failure(.failed("macOS answered with a credential that is not a passkey")))
        }
    }

    func authorizationController(controller _: ASAuthorizationController, didCompleteWithError error: any Error) {
        finish(.failure(PlatformPasskeys.failure(error)))
    }

    func presentationAnchor(for _: ASAuthorizationController) -> ASPresentationAnchor {
        window
    }

    private func finish(_ result: Result<any Sendable, PasskeyFailure>) {
        answer?.resume(returning: result)
        answer = nil
        Self.running.remove(self)
    }
}
