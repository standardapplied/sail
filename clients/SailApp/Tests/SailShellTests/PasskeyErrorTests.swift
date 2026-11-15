import AuthenticationServices
import Foundation
import SailAccount
import SailAPI
import Testing

/// Scenario 13: every `ASAuthorizationError` code, and the association failure's words, end in one
/// `PasskeyFailure`, which SailKit's `RefusalRowTests` turns into rows 7 and 9 to 12.
@Suite struct PasskeyErrorTests {
    func error(_ code: ASAuthorizationError.Code, reason: String? = nil) -> any Error {
        NSError(domain: ASAuthorizationError.errorDomain, code: code.rawValue, userInfo: reason.map { [NSLocalizedFailureReasonErrorKey: $0] } ?? [:])
    }

    @Test func cancellingIsOneAnswerWithFindingNoPasskey() {
        #expect(PlatformPasskeys.failure(error(.canceled)) == .cancelled)
    }

    @Test func aPasskeyTheServerExcludedIsAlreadyHeld() {
        #expect(PlatformPasskeys.failure(error(.matchedExcludedCredential)) == .alreadyHeld)
    }

    @Test(arguments: [ASAuthorizationError.Code.failed, .notHandled, .notInteractive, .unknown, .canceled])
    func aFailureNamingTheWebcredentialsAssociationIsThatWhateverItsCode(_ code: ASAuthorizationError.Code) {
        let reason = "Unable to verify webcredentials association of ABCDE12345.sh.standardapplied.sail with domain sail.standardapplied.com."
        guard case let .associationNotConfirmed(system) = PlatformPasskeys.failure(error(code, reason: reason)) else {
            Issue.record("code \(code.rawValue) with the association's words was not taken for it")
            return
        }
        #expect(system.contains(reason) && system.contains(ASAuthorizationError.errorDomain))
    }

    @Test(arguments: [ASAuthorizationError.Code.failed, .notHandled, .notInteractive, .unknown, .invalidResponse])
    func anyOtherCodeIsTheSystemsOwnWords(_ code: ASAuthorizationError.Code) {
        guard case let .failed(system) = PlatformPasskeys.failure(error(code)) else {
            Issue.record("code \(code.rawValue) was not a plain failure")
            return
        }
        #expect(system.contains("\(code.rawValue)"))
    }

    @Test func aMacNotSetUpForPasskeysCannotCreateOne() {
        guard #available(macOS 26, *) else { return }
        guard case .cannotCreate = PlatformPasskeys.failure(error(.deviceNotConfiguredForPasskeyCreation)) else {
            Issue.record("deviceNotConfiguredForPasskeyCreation was not cannotCreate")
            return
        }
    }

    @Test func anErrorOfAnotherDomainIsAFailureInItsWords() {
        let other = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)
        #expect(PlatformPasskeys.failure(other) == .failed(String(describing: other)))
    }
}
