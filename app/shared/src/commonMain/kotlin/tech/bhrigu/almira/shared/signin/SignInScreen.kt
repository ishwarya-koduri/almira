package tech.bhrigu.almira.shared.signin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.theme.AlmiraMotion
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import tech.bhrigu.almira.shared.ui.PrimaryButton

/**
 * Two steps, and nothing else on screen at either of them.
 *
 * Sign-in is the first thing anybody sees and the only thing standing between
 * them and their own records, so it carries no navigation, no marketing and no
 * second call to action. The one line of reassurance at the bottom is there
 * because this app asks for a phone number and people are right to wonder what
 * it does with it.
 */
@Composable
fun SignInScreen(
    state: SignInState,
    onPhoneChanged: (String) -> Unit,
    onSendCode: () -> Unit,
    onCodeChanged: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onEditPhone: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val space = AlmiraTheme.spacing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = space.x6),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(space.x16))
            BrandMark()
            Spacer(Modifier.height(space.x10))

            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = targetState == SignInStep.Code
                    val distance = if (forward) 1 else -1
                    (
                        slideInHorizontally(tween(AlmiraMotion.BASE)) { it / 6 * distance } +
                            fadeIn(tween(AlmiraMotion.BASE))
                        ) togetherWith (
                        slideOutHorizontally(tween(AlmiraMotion.BASE)) { -it / 6 * distance } +
                            fadeOut(tween(AlmiraMotion.FAST))
                        )
                },
                label = "signInStep",
            ) { step ->
                when (step) {
                    SignInStep.Phone -> PhoneStep(
                        state = state,
                        onPhoneChanged = onPhoneChanged,
                        onSubmit = onSendCode,
                    )

                    SignInStep.Code -> CodeStep(
                        state = state,
                        onCodeChanged = onCodeChanged,
                        onVerify = onVerify,
                        onResend = onResend,
                        onEditPhone = onEditPhone,
                    )
                }
            }

            Spacer(Modifier.height(space.x8))
            Text(
                "Encrypted. Almira never asks for a bank password and never moves money.",
                style = AlmiraTheme.typography.caption,
                color = colors.inkFaint,
            )
            Spacer(Modifier.height(space.x8))
        }
    }
}

@Composable
private fun BrandMark() {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AlmiraTheme.spacing.x3),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.accent, RoundedCornerShape(AlmiraTheme.radii.md)),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", style = type.h3, color = colors.accentInk)
        }
        Text("Almira", style = type.h2, color = colors.ink)
    }
}

@Composable
private fun PhoneStep(
    state: SignInState,
    onPhoneChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(space.x4)) {
        Text("Welcome to Almira", style = type.h1, color = colors.ink)
        Text(
            "Everything your family owns and owes, in one calm, private place.",
            style = type.body,
            color = colors.inkMuted,
        )

        Spacer(Modifier.height(space.x1))

        OutlinedTextField(
            value = state.phone,
            onValueChange = onPhoneChanged,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            enabled = !state.busy,
            label = { Text("Your phone number", style = type.small) },
            // The country code is shown rather than typed. Every number this
            // product expects is Indian, and making people type +91 is one more
            // thing to get wrong.
            prefix = { Text("+91  ", style = type.body, color = colors.inkMuted) },
            placeholder = { Text("98765 43210", style = type.body, color = colors.inkFaint) },
            textStyle = type.body,
            singleLine = true,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            shape = RoundedCornerShape(AlmiraTheme.radii.sm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.hairline,
                errorBorderColor = colors.caution,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                errorContainerColor = colors.surface,
            ),
        )

        HelperLine(
            message = state.error,
            fallback = "We'll text you a 6-digit code.",
        )

        PrimaryButton(
            label = "Send code",
            enabled = state.phoneIsPlausible,
            busy = state.busy,
            onClick = onSubmit,
        )
    }
}

@Composable
private fun CodeStep(
    state: SignInState,
    onCodeChanged: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onEditPhone: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(space.x4)) {
        Text("Check your phone", style = type.h1, color = colors.ink)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.x2),
        ) {
            Text(
                "Sent to +91 ${state.phone}",
                style = type.body,
                color = colors.inkMuted,
            )
            // A mistyped number should cost a tap, not a restart.
            TextButton(
                onClick = onEditPhone,
                enabled = !state.busy,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = space.x2,
                    vertical = 0.dp,
                ),
            ) {
                Text("Change", style = type.small, color = colors.accent)
            }
        }

        Spacer(Modifier.height(space.x1))

        OtpField(
            value = state.code,
            onValueChange = onCodeChanged,
            modifier = Modifier.focusRequester(focus),
            isError = state.error != null,
            enabled = !state.busy,
        )

        HelperLine(
            message = state.error,
            fallback = state.challenge?.let {
                "It expires in ${it.expiresInSeconds / 60} minutes."
            } ?: "",
        )

        PrimaryButton(
            label = "Continue",
            enabled = state.codeIsComplete,
            busy = state.busy,
            onClick = onVerify,
        )

        TextButton(
            onClick = onResend,
            enabled = state.resendIn == 0 && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.resendIn > 0) "Resend in ${state.resendIn}s" else "Send a new code",
                style = type.small,
                color = if (state.resendIn > 0) colors.inkFaint else colors.accent,
            )
        }

        // Development only: the server echoes the code when no SMS provider is
        // configured, so the whole flow is usable without an SMS bill or a DLT
        // registration. It is absent in every other environment.
        state.challenge?.developmentCode?.let { code ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentSoft, RoundedCornerShape(AlmiraTheme.radii.md))
                    .padding(space.x4),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(space.x1)) {
                    Text("Development mode", style = type.overline, color = colors.accent)
                    Text(
                        "No SMS provider is configured, so the code is $code.",
                        style = type.small,
                        color = colors.ink,
                    )
                }
            }
        }
    }
}

/**
 * One line that is either the helper text or the error, never both and never
 * neither — so the layout does not jump when something goes wrong.
 */
@Composable
private fun HelperLine(message: String?, fallback: String) {
    val colors = AlmiraTheme.colors
    Text(
        text = message ?: fallback,
        style = AlmiraTheme.typography.caption,
        color = if (message != null) colors.caution else colors.inkMuted,
        modifier = Modifier.height(38.dp),
    )
}
