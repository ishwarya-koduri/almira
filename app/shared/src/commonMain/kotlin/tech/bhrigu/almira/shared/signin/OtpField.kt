package tech.bhrigu.almira.shared.signin

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.theme.AlmiraMotion
import tech.bhrigu.almira.shared.theme.AlmiraTheme

/**
 * Six cells that are one text field.
 *
 * The obvious implementation — six separate fields — spends its life fighting
 * focus: backspace on an empty cell, paste of a whole code, the keyboard's own
 * autofill handing over all six digits at once. So this is a single
 * [BasicTextField] whose cells are decoration. There is one piece of state, the
 * caret is never in the wrong place, and pasting or autofilling a code just
 * works.
 *
 * The cell under the caret is drawn with the accent and a thicker border, which
 * is the only cue needed: the keyboard is up, and the next digit lands there.
 */
@Composable
fun OtpField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = SignInState.CODE_LENGTH,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    BasicTextField(
        value = value,
        onValueChange = { if (enabled) onValueChange(it) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = type.h3.copy(color = Color.Transparent),
        // Transparent: the real digits are drawn by the cells below, and a
        // second caret blinking behind them would be a puzzle.
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(space.x2),
            ) {
                repeat(length) { index ->
                    val filled = index < value.length
                    val isNext = index == value.length && enabled

                    val border by animateColorAsState(
                        targetValue = when {
                            isError -> colors.caution
                            isNext -> colors.accent
                            filled -> colors.inkFaint
                            else -> colors.hairline
                        },
                        animationSpec = tween(AlmiraMotion.FAST),
                        label = "otpCellBorder",
                    )
                    val thickness by animateDpAsState(
                        targetValue = if (isNext || isError) 2.dp else 1.dp,
                        animationSpec = tween(AlmiraMotion.FAST),
                        label = "otpCellBorderWidth",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .background(
                                if (filled) colors.surface else colors.surfaceSunken,
                                RoundedCornerShape(AlmiraTheme.radii.sm),
                            )
                            .border(thickness, border, RoundedCornerShape(AlmiraTheme.radii.sm)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (filled) value[index].toString() else "",
                            style = type.h3,
                            color = if (isError) colors.caution else colors.ink,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    )
}
