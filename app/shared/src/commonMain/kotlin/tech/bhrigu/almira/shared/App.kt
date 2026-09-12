package tech.bhrigu.almira.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.ApiException
import tech.bhrigu.almira.shared.api.Household
import tech.bhrigu.almira.shared.capture.CaptureController
import tech.bhrigu.almira.shared.capture.CaptureScreen
import tech.bhrigu.almira.shared.dashboard.DashboardController
import tech.bhrigu.almira.shared.dashboard.DashboardScreen
import tech.bhrigu.almira.shared.api.InMemoryTokenStore
import tech.bhrigu.almira.shared.api.Me
import tech.bhrigu.almira.shared.signin.SignInController
import tech.bhrigu.almira.shared.signin.SignInScreen
import tech.bhrigu.almira.shared.theme.AlmiraTheme

/**
 * The app so far: sign in, and proof that the session works.
 *
 * The screen after sign-in is deliberately thin — it lists the households the
 * session can actually read, which is the smallest honest evidence that an
 * authenticated call succeeded. The dashboard is its own stage, and a
 * hand-drawn imitation of one here would be worth nothing.
 */
@Composable
fun App(apiBaseUrl: String, platformName: String) {
    val scope = rememberCoroutineScope()
    val tokens = remember { InMemoryTokenStore() }
    var signedOutAt by remember { mutableStateOf(0) }

    val api = remember(apiBaseUrl, signedOutAt) {
        AlmiraApi(
            baseUrl = apiBaseUrl,
            tokens = tokens,
            // A refresh that fails for good means the session is gone. Rebuilding
            // the controller sends the user back to the phone step rather than
            // leaving them on a screen whose every request will fail.
            onSessionLost = { signedOutAt += 1 },
        )
    }
    val controller = remember(api) { SignInController(api, scope) }
    val state by controller.state.collectAsState()

    AlmiraTheme {
        Box(Modifier.fillMaxSize().background(AlmiraTheme.colors.canvas)) {
            AnimatedVisibility(
                visible = state.signedIn == null,
                enter = fadeIn(tween(AlmiraMotionBase)),
                exit = fadeOut(tween(AlmiraMotionBase)),
            ) {
                SignInScreen(
                    state = state,
                    onPhoneChanged = controller::onPhoneChanged,
                    onSendCode = controller::sendCode,
                    onCodeChanged = controller::onCodeChanged,
                    onVerify = controller::verify,
                    onResend = controller::resend,
                    onEditPhone = controller::editPhone,
                )
            }

            state.signedIn?.let { me ->
                SignedIn(
                    me = me,
                    api = api,
                    apiBaseUrl = apiBaseUrl,
                    platformName = platformName,
                    onSignOut = {
                        scope.launch {
                            api.signOut()
                            signedOutAt += 1
                        }
                    },
                )
            }
        }
    }
}

private const val AlmiraMotionBase = 200

@Composable
private fun SignedIn(
    me: Me,
    api: AlmiraApi,
    apiBaseUrl: String,
    platformName: String,
    onSignOut: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    var households by remember { mutableStateOf<List<Household>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    // Bumped after a save, so the dashboard is rebuilt and asks the server
    // again rather than showing a total that is one holding out of date.
    var savedAt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        try {
            households = api.households()
        } catch (failure: ApiException) {
            problem = failure.message
        }
    }

    val household = households?.firstOrNull()

    // Capture takes the whole screen while it is open, and its controller lives
    // exactly as long as it does: closing it drops the loaded taxonomy and the
    // half-filled form together, so reopening starts clean.
    if (capturing && household != null) {
        val capture = remember(household.id) {
            CaptureController(
                api = api,
                householdId = household.id,
                scope = scope,
                defaultVisibility = household.defaultVisibility,
            )
        }
        CaptureScreen(
            controller = capture,
            onClose = { capturing = false },
            onSaved = { capturing = false; savedAt += 1 },
        )
        return
    }

    when {
        problem != null -> Message(problem!!, colors.caution)

        households == null -> Box(
            Modifier.fillMaxSize().background(colors.canvas),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = colors.accent) }

        household == null -> Message(
            "No household yet. The web client can create one.",
            colors.inkMuted,
        )

        else -> {
            val dashboard = remember(household.id, savedAt) {
                DashboardController(api = api, householdId = household.id, scope = scope)
            }
            DashboardScreen(
                controller = dashboard,
                householdName = household.name,
                onAdd = { capturing = true },
                onSignOut = onSignOut,
                footnote = "${me.phone?.let(::formatIndianPhone) ?: me.id} · $platformName · $apiBaseUrl",
            )
        }
    }
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AlmiraTheme.colors.canvas)
            .statusBarsPadding()
            .padding(AlmiraTheme.spacing.x6),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AlmiraTheme.typography.small, color = color)
    }
}

/**
 * +919889190735 is how a number is stored; +91 98891 90735 is how it is read.
 * Leaves anything that is not a normalised Indian number exactly as it came.
 */
private fun formatIndianPhone(phone: String): String {
    val digits = phone.removePrefix("+")
    if (!digits.all(Char::isDigit) || !digits.startsWith("91") || digits.length != 12) return phone
    val local = digits.drop(2)
    return "+91 ${local.take(5)} ${local.drop(5)}"
}
