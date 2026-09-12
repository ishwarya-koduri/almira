package tech.bhrigu.almira.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import tech.bhrigu.almira.shared.security.LockState
import tech.bhrigu.almira.shared.security.PlatformHost

/**
 * The single entry point Swift calls. Everything above this line is shared;
 * everything below it is thirty lines of SwiftUI that exist only to host it.
 */
fun MainViewController(apiBaseUrl: String, lockState: LockState): UIViewController =
    ComposeUIViewController {
        App(
            apiBaseUrl = apiBaseUrl,
            platformName = platformName(),
            host = PlatformHost(),
            lockState = lockState,
        )
    }
