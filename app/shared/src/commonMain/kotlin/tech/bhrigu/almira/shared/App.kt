package tech.bhrigu.almira.shared

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
import tech.bhrigu.almira.shared.api.Me
import tech.bhrigu.almira.shared.security.LockAvailability
import tech.bhrigu.almira.shared.security.LockScreen
import tech.bhrigu.almira.shared.security.LockState
import tech.bhrigu.almira.shared.security.PlatformHost
import tech.bhrigu.almira.shared.security.UnlockResult
import tech.bhrigu.almira.shared.security.createAppLock
import tech.bhrigu.almira.shared.security.createTokenStore
import tech.bhrigu.almira.shared.signin.SignInController
import tech.bhrigu.almira.shared.signin.createOtpAutofill
import tech.bhrigu.almira.shared.zk.ZkController
import tech.bhrigu.almira.shared.zk.ZkScreen
import tech.bhrigu.almira.shared.zk.ZkVault
import tech.bhrigu.almira.shared.signin.SignInScreen
import tech.bhrigu.almira.shared.theme.AlmiraTheme

/**
 * The app: a lock, a sign-in, and everything behind them.
 *
 * Three things arrive from the platform because only the platform can make
 * them — the host that owns the Keystore and the biometric prompt, and the
 * lock flag its lifecycle drives. Everything else is common.
 */
@Composable
fun App(
    apiBaseUrl: String,
    platformName: String,
    host: PlatformHost,
    lockState: LockState,
) {
    val scope = rememberCoroutineScope()
    val tokens = remember(host) { createTokenStore(host) }
    val appLock = remember(host) { createAppLock(host) }
    val autofill = remember(host) { createOtpAutofill(host) }

    var signedOutAt by remember { mutableStateOf(0) }
    // Bumped on every unlock so the client is rebuilt and reads the token it
    // can now decrypt, rather than living with the null it loaded while locked.
    var unlockedAt by remember { mutableStateOf(0) }

    // null while we are still finding out. Neither screen is right to show yet,
    // and guessing would mean a flash of sign-in in front of someone who is
    // already signed in.
    var haveSession by remember(signedOutAt) { mutableStateOf<Boolean?>(null) }
    var unlocking by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }

    val locked by lockState.locked.collectAsState()
    val inForeground by lockState.inForeground.collectAsState()
    val availability = remember(host) { appLock.availability() }

    val api = remember(apiBaseUrl, signedOutAt, unlockedAt) {
        AlmiraApi(
            baseUrl = apiBaseUrl,
            tokens = tokens,
            // A refresh that fails for good means the session is gone. Rebuilding
            // the controller sends the user back to the phone step rather than
            // leaving them on a screen whose every request will fail.
            onSessionLost = { signedOutAt += 1 },
        )
    }
    val controller = remember(api) { SignInController(api, scope, autofill) }
    val vault = remember(api) { ZkVault(api) }
    val state by controller.state.collectAsState()

    LaunchedEffect(signedOutAt) {
        val stored = tokens.hasSession()
        haveSession = stored
        // Nothing stored, or nothing to lock with: there is no question to ask.
        if (!stored || availability == LockAvailability.None) lockState.unlocked()
    }

    // Locking has to reach the store, not just the screen. Dropping the
    // in-memory data key is what makes the lock a lock: after this, reading the
    // session needs the device's own authentication again.
    //
    // The zero-knowledge content key goes at the same moment, and for a
    // stronger reason: it is never written anywhere, so leaving the foreground
    // really does mean the passphrase has to be typed again. A biometric brings
    // back the session; only the passphrase brings back the sealed fields, and
    // that is the whole difference between the two secrets (B9).
    LaunchedEffect(locked) {
        if (locked) {
            tokens.forget()
            vault.forget()
        }
    }

    val showLock = haveSession == true && locked && availability != LockAvailability.None

    suspend fun attemptUnlock() {
        if (unlocking) return
        unlocking = true
        lockError = null
        try {
            when (val result = appLock.unlock("Unlock Almira", "Your family's records are behind this.")) {
                UnlockResult.Unlocked -> {
                    lockState.unlocked()
                    unlockedAt += 1
                }
                UnlockResult.Cancelled -> Unit
                UnlockResult.Unavailable -> lockState.unlocked()
                is UnlockResult.Failed -> lockError = result.message
            }
        } finally {
            // Also runs when backgrounding cancels the attempt, which is the
            // difference between a button that recovers and one that spins for
            // the rest of the session.
            unlocking = false
        }
    }

    // Ask as soon as the lock appears, rather than making someone tap Unlock to
    // be asked to unlock. The button stays for a second try.
    //
    // Gated on actually being in the foreground, not only on being locked:
    // leaving the app re-locks during `onStop`, and a prompt raised then is
    // silently dropped — no dialog, no callback, a button busy for ever. It has
    // to wait for the resume.
    LaunchedEffect(showLock, inForeground) {
        if (showLock && inForeground) attemptUnlock()
    }

    // Once past the lock with a stored session, bring it back. `me()` is a real
    // authenticated call, so reaching the dashboard is itself the proof that the
    // token survived — nothing here trusts the store's say-so.
    LaunchedEffect(haveSession, showLock, unlockedAt) {
        if (haveSession == true && !showLock && state.signedIn == null) {
            if (!controller.resume()) {
                tokens.clear()
                haveSession = false
            }
        }
    }

    AlmiraTheme {
        Box(Modifier.fillMaxSize().background(AlmiraTheme.colors.canvas)) {
            when {
                showLock -> LockScreen(
                    availability = availability,
                    busy = unlocking,
                    error = lockError,
                    onUnlock = { scope.launch { attemptUnlock() } },
                    onSignOut = {
                        scope.launch {
                            tokens.clear()
                            haveSession = false
                            lockState.unlocked()
                            signedOutAt += 1
                        }
                    },
                )

                // Still deciding, or resuming a stored session.
                haveSession == null || (haveSession == true && state.signedIn == null) ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AlmiraTheme.colors.accent)
                    }

                state.signedIn == null -> SignInScreen(
                    state = state,
                    onPhoneChanged = controller::onPhoneChanged,
                    onSendCode = controller::sendCode,
                    onCodeChanged = controller::onCodeChanged,
                    onVerify = controller::verify,
                    onResend = controller::resend,
                    onEditPhone = controller::editPhone,
                    smsSignature = controller.smsSignature(),
                )

                else -> SignedIn(
                    me = state.signedIn!!,
                    api = api,
                    vault = vault,
                    apiBaseUrl = apiBaseUrl,
                    platformName = platformName,
                    onSignOut = {
                        scope.launch {
                            api.signOut()
                            haveSession = false
                            signedOutAt += 1
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SignedIn(
    me: Me,
    api: AlmiraApi,
    vault: ZkVault,
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
    var sealing by remember { mutableStateOf(false) }
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
    if (sealing && household != null) {
        val zk = remember(household.id, api) { ZkController(api, household.id, vault, scope) }
        ZkScreen(controller = zk, onBack = { sealing = false })
        return
    }

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
                onSealed = { sealing = true },
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
