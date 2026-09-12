package tech.bhrigu.almira.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The single entry point Swift calls. Everything above this line is shared;
 * everything below it is thirty lines of SwiftUI that exist only to host it.
 */
fun MainViewController(apiBaseUrl: String): UIViewController =
    ComposeUIViewController { App(apiBaseUrl = apiBaseUrl, platformName = platformName()) }
