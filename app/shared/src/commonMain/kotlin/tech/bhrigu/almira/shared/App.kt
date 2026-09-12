package tech.bhrigu.almira.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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

    LaunchedEffect(api) {
        try {
            households = api.households()
        } catch (failure: ApiException) {
            problem = failure.message
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(space.x6),
            verticalArrangement = Arrangement.spacedBy(space.x4),
        ) {
            Spacer(Modifier.height(space.x8))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.x3),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).background(colors.accentSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // A name's initial when there is a name. A phone-only
                    // account has none, and the first character of a normalised
                    // number is "+", which identifies nobody — so the last two
                    // digits stand in, which is how people recognise their own
                    // number in a list anyway.
                    Text(
                        me.fullName?.firstOrNull { it.isLetter() }?.uppercase()
                            ?: me.phone?.takeLast(2)
                            ?: "?",
                        style = type.h4,
                        color = colors.accent,
                    )
                }
                Column {
                    Text("You're signed in", style = type.h3, color = colors.ink)
                    Text(
                        me.fullName ?: me.phone?.let(::formatIndianPhone) ?: me.id,
                        style = type.small,
                        color = colors.inkMuted,
                    )
                }
            }

            Text("YOUR HOUSEHOLDS", style = type.overline, color = colors.inkFaint)

            when {
                problem != null -> Text(problem!!, style = type.small, color = colors.caution)

                households == null -> Text(
                    "Loading…",
                    style = type.small,
                    color = colors.inkFaint,
                )

                households!!.isEmpty() -> Text(
                    "None yet. The web client can create one.",
                    style = type.small,
                    color = colors.inkMuted,
                )

                else -> households!!.forEach { household ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(AlmiraTheme.radii.lg))
                            .padding(space.x4),
                        verticalArrangement = Arrangement.spacedBy(space.x1),
                    ) {
                        Text(household.name, style = type.h4, color = colors.ink)
                        Text(
                            "${household.myRole} · ${household.memberCount} people · ${household.baseCurrency}",
                            style = type.caption,
                            color = colors.inkMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(space.x2))

            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out", style = type.body, color = colors.accent)
            }

            Text(
                "$platformName · $apiBaseUrl",
                style = type.caption,
                color = colors.inkFaint,
            )
        }
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
