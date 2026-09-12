import SwiftUI
import UIKit
import Shared

/// Hosts the shared Compose UI inside SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    let lockState: LockState

    func makeUIViewController(context: Context) -> UIViewController {
        // The simulator reaches the host's loopback directly, so this is the
        // same URL the web client uses. A physical device needs the Mac's LAN
        // address instead — which is why it is read from Info.plist rather than
        // compiled in.
        let base = Bundle.main.object(forInfoDictionaryKey: "AlmiraApiBaseUrl") as? String
            ?? "http://localhost:18080"
        return MainViewControllerKt.MainViewController(apiBaseUrl: base, lockState: lockState)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    let lockState: LockState

    var body: some View {
        ComposeView(lockState: lockState)
    }
}
