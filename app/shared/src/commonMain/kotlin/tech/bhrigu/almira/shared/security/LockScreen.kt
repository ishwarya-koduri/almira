package tech.bhrigu.almira.shared.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import tech.bhrigu.almira.shared.ui.PrimaryButton

/**
 * What a locked Almira looks like: the name, and nothing else.
 *
 * No totals, no household, no "welcome back, Ishwarya". Whoever is holding the
 * phone has not proved anything yet, and a lock screen that names the family
 * has already leaked the thing worth leaking.
 */
@Composable
fun LockScreen(
    availability: LockAvailability,
    busy: Boolean,
    error: String?,
    onUnlock: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    Box(
        modifier = Modifier.fillMaxSize().background(colors.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).padding(space.x6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(space.x4),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(colors.accent, RoundedCornerShape(AlmiraTheme.radii.lg)),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", style = type.h3, color = colors.accentInk, fontWeight = FontWeight.Bold)
            }

            Text("Almira is locked", style = type.h3, color = colors.ink)

            Text(
                when (availability) {
                    LockAvailability.Biometric ->
                        "Unlock with your fingerprint, or your screen lock."
                    LockAvailability.DeviceCredentialOnly ->
                        "Unlock with your PIN, pattern or password."
                    // Reached only if the lock disappeared between launch and
                    // now — the device lock was removed while the app was open.
                    LockAvailability.None ->
                        "This phone has no screen lock, so there is nothing to unlock with."
                },
                style = type.small,
                color = colors.inkMuted,
                textAlign = TextAlign.Center,
            )

            // Fixed height so the button does not move when a message appears.
            Text(
                text = error.orEmpty(),
                style = type.caption,
                color = colors.caution,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().height(34.dp),
            )

            PrimaryButton(
                label = "Unlock",
                enabled = availability != LockAvailability.None,
                busy = busy,
                onClick = onUnlock,
            )

            // The way out for someone who cannot pass the lock — a borrowed
            // phone, a changed fingerprint — without uninstalling the app.
            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in as someone else", style = type.small, color = colors.accent)
            }
        }
    }
}
