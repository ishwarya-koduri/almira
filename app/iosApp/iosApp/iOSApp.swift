import SwiftUI
import Shared

/// The whole of the iOS app, by design.
///
/// Everything on screen comes from the shared Kotlin module; this exists only
/// to give Compose a window and to be the thing Xcode can sign. When a screen
/// needs something only iOS can answer — the Keychain, Face ID, the one-time
/// code from a message — the answer is handed down through an interface the
/// shared module declares, not written up here.
@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    /// Built once and handed down, the way `MainActivity` holds it on Android.
    /// A new one per redraw would be a lock that forgets it was ever locked.
    private let lockState = LockState()

    init() {
        // Before anything can seal or open a field. AES-GCM is the one
        // primitive the shared module cannot reach on its own — CryptoKit is
        // Swift-only — so this hands it down through the seam. There is no
        // default on the other side: a build that forgot this line fails
        // loudly on the first sealed field rather than inventing a cipher.
        Aead_iosKt.installAppleAead(aead: CryptoKitAead())

        #if DEBUG
        // The B4 known-answer vector, checked against the constants the browser
        // produced, at the one moment AES-GCM is actually available. The
        // derived-key and additional-data halves are asserted in `commonTest`
        // and run natively; this is the envelope half, which needs the bridge
        // installed above and therefore cannot live in a test binary.
        print("--- almira zk self-test (iOS) ---")
        print(ZkSelfTest_iosKt.zkSelfTest())
        // The Keychain and LocalAuthentication actuals, exercised rather than
        // assumed: both are C APIs that fail by returning a status, so the
        // only way to know they work is to make them work once.
        print(SecuritySelfTest_iosKt.securitySelfTest())
        print("--- end zk self-test ---")

        // The cross-client round trip, against the live API and the session
        // already in the Keychain. Reached only by this argument, so it never
        // runs for a person using the app — and only in a debug build, so it
        // cannot ship at all.
        if CommandLine.arguments.contains("-almiraZkInterop") {
            let base = Bundle.main.object(forInfoDictionaryKey: "AlmiraApiBaseUrl") as? String
                ?? "http://localhost:18080"
            print("--- almira zk interop (iOS) ---")
            print(ZkInteropRun_iosKt.zkInteropRun(
                apiBaseUrl: base,
                householdId: "58276cae-2448-4d51-8c9d-29fefd3225d4",
                recordId: "167d9136-e238-48cf-b093-0f51d9a43c8d",
                passphrase: "correct horse battery staple "
            ))
            print("--- end zk interop ---")
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView(lockState: lockState)
                .ignoresSafeArea(.all)
        }
        // The iOS half of the lifecycle that drives the lock. `.background` is
        // this platform's `onStop`: the app is off screen, so the session's
        // data key and the zero-knowledge content key are both dropped. And
        // nothing may raise a prompt until `.active` says the window is really
        // in front — the same rule Android needed, for the same reason.
        // The single-argument `onChange` rather than the two-argument one that
        // arrived in iOS 17: this app's deployment target is 16.0, and taking
        // the newer overload would quietly raise the floor to 17 for a callback
        // that does not need the old value.
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: lockState.foregrounded()
            case .background: lockState.backgrounded()
            // `.inactive` is a notification shade or an incoming call, not a
            // departure. Locking here would lock the app every time a banner
            // appeared.
            default: break
            }
        }
    }
}
