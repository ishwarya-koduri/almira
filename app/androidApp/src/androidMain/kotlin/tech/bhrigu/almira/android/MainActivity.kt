package tech.bhrigu.almira.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import tech.bhrigu.almira.shared.App
import tech.bhrigu.almira.shared.platformName
import tech.bhrigu.almira.shared.security.LockState
import tech.bhrigu.almira.shared.security.PlatformHost

/**
 * The whole of the Android app. Everything it shows comes from `:shared`, which
 * is the arrangement the rest of the app will keep: platform modules exist to
 * hand Compose a window and to answer the questions only they can answer.
 *
 * A `FragmentActivity` rather than a `ComponentActivity` for exactly one
 * reason: `BiometricPrompt` attaches itself as a fragment and will not accept
 * anything less. It is still a `ComponentActivity` underneath, so `setContent`
 * and edge-to-edge are unchanged.
 */
class MainActivity : FragmentActivity() {

    private val lockState = LockState()

    // Built once. Handing `setContent` a fresh host on every recomposition
    // would key the remembered token store to a new object each time, and a
    // rebuilt store is a store that has forgotten how to read the session.
    private val host by lazy { PlatformHost(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Keeps a family's net worth out of the recents thumbnail and out of
        // screenshots. Off in debug because it also blacks out `adb screencap`,
        // and a stage that cannot be photographed cannot be reviewed — see
        // docs/known-issues.md.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        setContent {
            App(
                apiBaseUrl = BuildConfig.API_BASE_URL,
                platformName = platformName(),
                host = host,
                lockState = lockState,
            )
        }
    }

    /**
     * Resumed, and therefore able to host a dialog. The prompt waits for this
     * rather than for the first composition, which happens too early.
     */
    override fun onResume() {
        super.onResume()
        lockState.foregrounded()
    }

    /**
     * Leaving the foreground re-locks.
     *
     * `onStop` rather than `onPause`: a permission dialog or the biometric
     * prompt itself pauses the activity, and re-locking there would mean the
     * prompt locks the app it was opening. `onStop` is the honest "this app is
     * no longer on screen".
     */
    override fun onStop() {
        super.onStop()
        lockState.backgrounded()
    }
}
