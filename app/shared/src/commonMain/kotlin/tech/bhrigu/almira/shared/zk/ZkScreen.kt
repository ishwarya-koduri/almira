package tech.bhrigu.almira.shared.zk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import tech.bhrigu.almira.shared.ui.FieldShell
import tech.bhrigu.almira.shared.ui.PrimaryButton
import tech.bhrigu.almira.shared.ui.SecondaryButton
import tech.bhrigu.almira.shared.ui.almiraFieldColors

/**
 * Sealed fields: the ones the server holds and cannot read.
 *
 * A locked field says **locked**, never blank. "There is nothing here" and "you
 * cannot see this" are different sentences and only one of them is true.
 */
@Composable
fun ZkScreen(controller: ZkController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.x5),
        verticalArrangement = Arrangement.spacedBy(space.x3),
    ) {
        Spacer(Modifier.height(space.x2))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sealed", style = type.h3, color = colors.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) {
                Text("Back", style = type.small, color = colors.accent)
            }
        }

        when {
            state.loading -> Box(
                Modifier.fillMaxWidth().padding(space.x16),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = colors.accent) }

            !state.enabled -> Text(
                "Zero-knowledge mode hasn't been set up for this household. " +
                    "The web client can do that; the passphrase is never sent anywhere, " +
                    "so it has to be chosen somewhere it can be typed twice.",
                style = type.small,
                color = colors.inkMuted,
            )

            !state.unlocked -> Column(verticalArrangement = Arrangement.spacedBy(space.x3)) {
                Text(
                    "Fields sealed here are unreadable to the server, and to this app " +
                        "until the passphrase is typed. It is never stored on this phone.",
                    style = type.small,
                    color = colors.inkMuted,
                )
                FieldShell(
                    label = "Passphrase",
                    help = "Spaces count, including one at the end.",
                    error = state.message,
                ) {
                    OutlinedTextField(
                        value = state.passphrase,
                        onValueChange = controller::onPassphraseChanged,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = state.message != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        shape = RoundedCornerShape(AlmiraTheme.radii.md),
                        colors = almiraFieldColors(state.message != null),
                        textStyle = type.body,
                    )
                }
                PrimaryButton(
                    label = "Unlock",
                    enabled = state.passphrase.isNotEmpty(),
                    busy = state.busy,
                    onClick = controller::unlock,
                )
                state.caveats.forEach {
                    Text(it, style = type.caption, color = colors.inkFaint)
                }
            }

            else -> Unlocked(state, controller)
        }

        Spacer(Modifier.height(space.x12))
    }
}

@Composable
private fun Unlocked(state: ZkState, controller: ZkController) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Unlocked", style = type.small, color = colors.accent, modifier = Modifier.weight(1f))
        TextButton(onClick = controller::lock) {
            Text("Lock", style = type.small, color = colors.accent)
        }
    }

    Text("WHICH HOLDING", style = type.overline, color = colors.inkFaint)
    state.holdings.forEach { holding ->
        val chosen = state.chosen?.id == holding.id
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (chosen) colors.accentSoft else colors.surface,
                    RoundedCornerShape(AlmiraTheme.radii.md),
                )
                .border(
                    1.dp,
                    if (chosen) colors.accent else colors.hairline,
                    RoundedCornerShape(AlmiraTheme.radii.md),
                )
                .clickable { controller.choose(holding) }
                .padding(space.x3),
        ) {
            Text(holding.title, style = type.small, color = colors.ink)
            Text(holding.typeLabel, style = type.caption, color = colors.inkMuted)
        }
    }

    state.chosen?.let { holding ->
        Spacer(Modifier.height(space.x2))
        Text("SEALED FIELDS", style = type.overline, color = colors.inkFaint)

        if (state.fields.isEmpty()) {
            Text("None on this one yet.", style = type.caption, color = colors.inkFaint)
        }

        state.fields.forEach { field ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(AlmiraTheme.radii.md))
                    .border(1.dp, colors.hairline, RoundedCornerShape(AlmiraTheme.radii.md))
                    .padding(space.x3),
                verticalArrangement = Arrangement.spacedBy(space.x1),
            ) {
                Text(field.fieldKey, style = type.caption, color = colors.inkMuted)
                when (val outcome = field.outcome) {
                    is OpenOutcome.Opened -> Text(
                        // An empty sealed value is a real value, and saying so
                        // is better than showing a blank line that reads as a
                        // rendering bug.
                        outcome.text.ifEmpty { "(empty)" },
                        style = type.body,
                        color = colors.ink,
                        fontWeight = FontWeight.Medium,
                    )
                    OpenOutcome.Locked ->
                        Text("Locked.", style = type.small, color = colors.inkFaint)
                    OpenOutcome.NewerVersion ->
                        Text("Sealed by a newer Almira.", style = type.small, color = colors.caution)
                    OpenOutcome.Unreadable ->
                        Text("This one couldn't be opened.", style = type.small, color = colors.caution)
                }
            }
        }

        Spacer(Modifier.height(space.x2))
        FieldShell(label = "Field name", help = "lowercase_with_underscores") {
            OutlinedTextField(
                value = state.newFieldKey,
                onValueChange = controller::onFieldKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
                colors = almiraFieldColors(),
                textStyle = type.body,
            )
        }
        FieldShell(label = "What to seal", error = state.message) {
            OutlinedTextField(
                value = state.newValue,
                onValueChange = controller::onValueChanged,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
                colors = almiraFieldColors(),
                textStyle = type.body,
            )
        }
        PrimaryButton(
            label = "Seal it",
            enabled = state.newFieldKey.isNotBlank(),
            busy = state.busy,
            onClick = controller::seal,
        )
        SecondaryButton("Refresh", enabled = true, onClick = { controller.choose(holding) })
    }
}
