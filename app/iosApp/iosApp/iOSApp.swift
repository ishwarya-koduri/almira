import SwiftUI

/// The whole of the iOS app, by design.
///
/// Everything on screen comes from the shared Kotlin module; this exists only
/// to give Compose a window and to be the thing Xcode can sign. When a screen
/// needs something only iOS can answer — the Keychain, Face ID, the one-time
/// code from a message — the answer is handed down through an interface the
/// shared module declares, not written up here.
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
