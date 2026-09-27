package tech.almira.shared.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tech.almira.shared.api.Household
import tech.almira.shared.theme.AlmiraTheme
import tech.almira.shared.ui.FieldShell
import tech.almira.shared.ui.PrimaryButton
import tech.almira.shared.ui.almiraFieldColors

/**
 * The first run, on a phone (docs/03 §1; closes known issue 89).
 *
 * The same four questions the web client asks, in the same order and the same
 * words, because a person who starts on one and continues on the other should
 * not meet two different products. Every question has an answer already chosen,
 * so somebody who wants to get on with it can press the button at the bottom
 * straight away and still end up with a private almirah of their own.
 *
 * It scrolls rather than paginating: four questions is short enough to see the
 * shape of, and a wizard would hide how little there is to do.
 */
@Composable
fun FirstRunScreen(
    controller: FirstRunController,
    onCreated: (Household) -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    val state by controller.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(space.x4),
        verticalArrangement = Arrangement.spacedBy(space.x4),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(space.x1)) {
            Text("Let's set things up", style = type.h1, color = colors.ink)
            Text(
                "This takes about a minute. You can change any of it later.",
                style = type.body,
                color = colors.inkMuted,
            )
        }

        Question("Who are you setting this up for?") {
            Choice(
                "For me",
                "Your own things, and your family's if you like.",
                chosen = state.forWhom == SettingUpFor.Me,
            ) { controller.forWhom(SettingUpFor.Me) }
            Choice(
                "For a parent or someone else",
                "You're typing on their behalf. Everything is recorded as theirs, " +
                    "and they can be invited to check it later.",
                chosen = state.forWhom == SettingUpFor.SomeoneElse,
            ) { controller.forWhom(SettingUpFor.SomeoneElse) }

            if (state.forWhom == SettingUpFor.SomeoneElse) {
                FieldShell(
                    label = "What do you call them?",
                    required = true,
                    help = "The name you use at home is fine: \"Amma\", \"Nanna\", \"Pinni\".",
                    error = state.error.takeIf { state.personName.isBlank() },
                ) {
                    OutlinedTextField(
                        value = state.personName,
                        onValueChange = controller::personName,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        placeholder = { Text("Amma", style = type.body, color = colors.inkFaint) },
                        textStyle = type.body,
                        singleLine = true,
                        isError = state.error != null && state.personName.isBlank(),
                        shape = RoundedCornerShape(AlmiraTheme.radii.sm),
                        colors = almiraFieldColors(state.error != null && state.personName.isBlank()),
                    )
                }
                FieldShell(label = "They are your") {
                    Row(horizontalArrangement = Arrangement.spacedBy(space.x2)) {
                        FirstRunController.RELATIONSHIPS.forEach { (value, label) ->
                            Pill(label, chosen = state.relationship == value) { controller.relationship(value) }
                        }
                    }
                }
                Text(
                    "What you add for them starts shared with the household, so you can see it too. " +
                        "They can make any of it private when they join.",
                    style = type.caption,
                    color = colors.inkFaint,
                )
            }
        }

        Question("Who are we tracking for?") {
            Choice(
                "Just me",
                "Your own investments and loans.",
                chosen = state.tracking == Tracking.JustMe,
            ) { controller.tracking(Tracking.JustMe) }
            Choice(
                "Me and my family",
                "Track for a spouse, children or parents. Each person keeps their own privacy.",
                chosen = state.tracking == Tracking.Family,
            ) { controller.tracking(Tracking.Family) }
        }

        Question(
            "What should new entries default to?",
            help = "Private means only the owner can see it, not even a household admin. " +
                "You can share any single entry whenever you want.",
        ) {
            Choice(
                "Private by default",
                "Nothing is shared unless you choose to share it.",
                chosen = state.startsAs == StartsAs.Private,
            ) { controller.startsAs(StartsAs.Private) }
            Choice(
                "Shared by default",
                "New entries are visible to everyone in the household.",
                chosen = state.startsAs == StartsAs.Shared,
            ) { controller.startsAs(StartsAs.Shared) }
        }

        FieldShell(label = "Household name", help = "Optional. We'll call it \"My household\" otherwise.") {
            OutlinedTextField(
                value = state.householdName,
                onValueChange = controller::householdName,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                placeholder = { Text("The Koduri household", style = type.body, color = colors.inkFaint) },
                textStyle = type.body,
                singleLine = true,
                shape = RoundedCornerShape(AlmiraTheme.radii.sm),
                colors = almiraFieldColors(),
            )
        }

        FieldShell(label = "What should we call you?", help = "Shown next to the things you own.") {
            OutlinedTextField(
                value = state.yourName,
                onValueChange = controller::yourName,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                placeholder = { Text("Your name", style = type.body, color = colors.inkFaint) },
                textStyle = type.body,
                singleLine = true,
                shape = RoundedCornerShape(AlmiraTheme.radii.sm),
                colors = almiraFieldColors(),
            )
        }

        // Said here as well as under the field it belongs to, because on a phone
        // the field that failed may be a screen away by the time you press.
        if (state.error != null) {
            Text(state.error.orEmpty(), style = type.small, color = colors.caution)
        }

        PrimaryButton(
            label = "Create my household",
            enabled = !state.busy,
            busy = state.busy,
            onClick = { controller.create(onCreated) },
        )

        Text(
            "Sign out",
            style = type.small,
            color = colors.inkMuted,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !state.busy, onClick = onSignOut)
                .padding(vertical = space.x2),
        )

        Spacer(Modifier.height(space.x4))
    }
}

@Composable
private fun Question(title: String, help: String? = null, content: @Composable () -> Unit) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(space.x2)) {
        Text(title, style = type.h3, color = colors.ink)
        if (help != null) Text(help, style = type.caption, color = colors.inkFaint)
        content()
    }
}

/** One of a pair of cards: the thing chosen carries the accent, the other a hairline. */
@Composable
private fun Choice(title: String, help: String, chosen: Boolean, onChoose: () -> Unit) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (chosen) 2.dp else 1.dp,
                color = if (chosen) colors.accent else colors.hairline,
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
            )
            .background(if (chosen) colors.surface else colors.canvas, RoundedCornerShape(AlmiraTheme.radii.md))
            .clickable(onClick = onChoose)
            .padding(space.x3),
        verticalArrangement = Arrangement.spacedBy(space.x1),
    ) {
        Text(title, style = type.body, color = colors.ink, fontWeight = FontWeight.Medium)
        Text(help, style = type.caption, color = colors.inkMuted)
    }
}

@Composable
private fun Pill(label: String, chosen: Boolean, onChoose: () -> Unit) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    Text(
        label,
        style = type.small,
        color = if (chosen) colors.accentInk else colors.ink,
        modifier = Modifier
            .background(
                if (chosen) colors.accent else colors.surface,
                RoundedCornerShape(AlmiraTheme.radii.lg),
            )
            .border(
                width = 1.dp,
                color = if (chosen) colors.accent else colors.hairline,
                shape = RoundedCornerShape(AlmiraTheme.radii.lg),
            )
            .clickable(onClick = onChoose)
            .padding(horizontal = space.x3, vertical = space.x2),
    )
}
