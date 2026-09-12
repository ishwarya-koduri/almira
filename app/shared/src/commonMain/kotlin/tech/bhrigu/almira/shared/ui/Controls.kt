package tech.bhrigu.almira.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.theme.AlmiraTheme

/**
 * The handful of controls more than one screen needs, in Almira's clothes.
 *
 * Everything here is common code: the same button and the same field shell
 * render on Android and on iOS, which is the point of putting them in `shared`
 * rather than next to whichever screen happened to need them first.
 */

@Composable
fun PrimaryButton(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AlmiraTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        enabled = enabled && !busy,
        shape = RoundedCornerShape(AlmiraTheme.radii.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.accentInk,
            disabledContainerColor = colors.hairline,
            disabledContentColor = colors.inkFaint,
        ),
    ) {
        if (busy) {
            // Same height, no reflow: the button does not change size when it
            // starts working, which is what makes a tap feel answered rather
            // than disruptive.
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.accentInk,
            )
        } else {
            Text(label, style = AlmiraTheme.typography.body)
        }
    }
}

@Composable
fun SecondaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AlmiraTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(AlmiraTheme.radii.md),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.accent,
            disabledContentColor = colors.inkFaint,
        ),
    ) {
        Text(label, style = AlmiraTheme.typography.body)
    }
}

/**
 * A label, a control, and one line underneath that is either help or an error.
 *
 * The line is always there whether or not it has anything to say, so a field
 * that fails validation does not shove everything below it down the screen
 * while the user is still reading — the same reason sign-in's helper line has a
 * fixed height.
 */
@Composable
fun FieldShell(
    label: String,
    required: Boolean = false,
    help: String? = null,
    error: String? = null,
    control: @Composable () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(space.x1),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(space.x1)) {
            Text(label, style = type.small, color = colors.inkMuted, fontWeight = FontWeight.Medium)
            if (required) Text("*", style = type.small, color = colors.caution)
        }
        control()
        val message = error ?: help
        Text(
            text = message.orEmpty(),
            style = type.caption,
            color = if (error != null) colors.caution else colors.inkFaint,
            modifier = Modifier.heightIn(min = 18.dp),
        )
    }
}

/** Outlined fields, coloured from the tokens rather than from Material's defaults. */
@Composable
fun almiraFieldColors(isError: Boolean = false): TextFieldColors {
    val colors = AlmiraTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.ink,
        unfocusedTextColor = colors.ink,
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
        disabledContainerColor = colors.surfaceSunken,
        cursorColor = colors.accent,
        focusedBorderColor = if (isError) colors.caution else colors.accent,
        unfocusedBorderColor = if (isError) colors.caution else colors.hairline,
        focusedPlaceholderColor = colors.inkFaint,
        unfocusedPlaceholderColor = colors.inkFaint,
        focusedPrefixColor = colors.inkMuted,
        unfocusedPrefixColor = colors.inkMuted,
        focusedSuffixColor = colors.inkMuted,
        unfocusedSuffixColor = colors.inkMuted,
    )
}
