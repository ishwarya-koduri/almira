package tech.bhrigu.almira.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.bhrigu.almira.shared.App
import tech.bhrigu.almira.shared.platformName

/**
 * The whole of the Android app. Everything it shows comes from `:shared`, which
 * is the arrangement the rest of the app will keep: platform modules exist to
 * hand Compose a window and to answer the questions only they can answer.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App(apiBaseUrl = BuildConfig.API_BASE_URL, platformName = platformName())
        }
    }
}
