import Foundation
import SailAPI
import Testing

/// The user menu says who is signed in and where, from what the account knows: the name the server
/// gives, else the handle; `@handle · Role`; the control plane's address and whether it answers;
/// and offers what can be done. Its Appearance is this Mac's, Auto until a person chooses.
@Suite struct UserMenuContentTests {
    static let uday = Whoami(fde: "uday", displayName: "Uday Chandra", role: .known(.admin), capabilities: [])

    @Test func signedInItSaysWhoAndWhereAndOffersAccountLoginsAndSignOut() {
        let menu = UserMenuContent.signedIn(handle: "uday", address: "sail.example.com", whoami: Self.uday, unreachable: false, refreshing: false)
        #expect(menu.name == "Uday Chandra" && menu.initial == "U" && menu.detail == "@uday · Admin")
        #expect(menu.address == "sail.example.com" && menu.plane == .connected)
        #expect(menu.items == [.account, .logins, .signOut])
    }

    @Test func withNoNameFromTheServerTheHandleIsTheName() {
        let ana = Whoami(fde: "ana", role: .known(.member), capabilities: [])
        let menu = UserMenuContent.signedIn(handle: "ana", address: "sail.example.com", whoami: ana, unreachable: false, refreshing: false)
        #expect(menu.name == "ana" && menu.initial == "A" && menu.detail == "@ana · Member")
        let unknown = UserMenuContent.signedIn(handle: "ana", address: "sail.example.com", whoami: nil, unreachable: false, refreshing: false)
        #expect(unknown.name == "ana" && unknown.detail == "@ana")
    }

    @Test func theControlPlanesStateIsWhatItLastAnswered() {
        let reach = { (unreachable: Bool, refreshing: Bool) in
            UserMenuContent.signedIn(handle: "uday", address: "sail.example.com", whoami: Self.uday, unreachable: unreachable, refreshing: refreshing).plane
        }
        #expect(reach(false, false) == .connected && reach(false, true) == .connected)
        #expect(reach(true, false) == .unreachable && reach(true, true) == .reconnecting)
        #expect(UserMenuContent.Plane.connected.said == "Connected" && UserMenuContent.Plane.reconnecting.said == "Reconnecting" && UserMenuContent.Plane.unreachable.said == "Can’t reach")
    }

    @Test func signedOutItOffersSignIn() {
        let menu = UserMenuContent.signedOut
        #expect(menu.name == "Not signed in" && menu.initial == nil && menu.detail == nil && menu.address == nil && menu.plane == nil)
        #expect(menu.items == [.signIn])
    }

    /// Logins is offered and not yet usable: it says so, the server's spec not built.
    @Test func loginsIsShownButNotYetUsable() {
        #expect(!UserMenuContent.Item.logins.enabled && UserMenuContent.Item.logins.why == "Logins come in a later version of Sail.")
        #expect(UserMenuContent.Item.account.enabled && UserMenuContent.Item.account.key == "," && UserMenuContent.Item.signOut.key == nil)
    }

    @Test func appearanceIsAutoUntilChosenAndRemembered() throws {
        let defaults = try #require(UserDefaults(suiteName: "appearance-\(UUID().uuidString)"))
        #expect(Appearance.stored(in: defaults) == .auto)
        Appearance.dark.store(in: defaults)
        #expect(Appearance.stored(in: defaults) == .dark)
        defaults.set("violet", forKey: Appearance.key)
        #expect(Appearance.stored(in: defaults) == .auto, "an unknown value is Auto")
        #expect(Appearance.allCases.map(\.said) == ["Light", "Dark", "Auto"])
    }
}
