package tech.bhrigu.almira.shared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 4px scale from docs/02 §4. Use the tokens, not raw numbers — a layout
 * built from `13.dp` here and `18.dp` there is how a product stops looking
 * like one thing.
 */
@Immutable
data class AlmiraSpacing(
    val x1: Dp = 4.dp,
    val x2: Dp = 8.dp,
    val x3: Dp = 12.dp,
    val x4: Dp = 16.dp,
    val x5: Dp = 20.dp,
    val x6: Dp = 24.dp,
    val x8: Dp = 32.dp,
    val x10: Dp = 40.dp,
    val x12: Dp = 48.dp,
    val x16: Dp = 64.dp,
    val x24: Dp = 96.dp,
)

/** Radii, by the thing they are for rather than by their size. */
@Immutable
data class AlmiraRadii(
    /** Inputs and chips. */
    val sm: Dp = 8.dp,
    /** Buttons. */
    val md: Dp = 12.dp,
    /** Cards and sheets. */
    val lg: Dp = 16.dp,
    /** The hero. */
    val xl: Dp = 24.dp,
    val full: Dp = 999.dp,
)

/**
 * Motion, in milliseconds. Short enough to feel immediate, long enough to be
 * followed — and one easing curve everywhere, because a product with three is a
 * product where nothing feels deliberate.
 */
object AlmiraMotion {
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 320
}
