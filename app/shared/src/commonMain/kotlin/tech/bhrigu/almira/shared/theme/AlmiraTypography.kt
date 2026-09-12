package tech.bhrigu.almira.shared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * The 1.2 modular scale from docs/02 §3, on a 16sp base.
 *
 * Two families, and the split is the point: **numbers are the hero**, so
 * amounts and headlines take the humanist serif while the interface itself
 * takes a grotesk. Body never goes below 14sp.
 *
 * The real faces are Fraunces and Inter. Bundling them is a step of its own —
 * two variable fonts are about 400 KB and they need licence files shipped with
 * them — so the skeleton uses the platform serif and sans, which keeps the
 * *shape* of the system (which text is serif, at what size, at what weight)
 * correct while the faces are still to come. Swapping them in later changes
 * this file and nothing else.
 */
@Immutable
data class AlmiraTypography(
    val display: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val large: TextStyle,
    val body: TextStyle,
    val small: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    /** Amounts. Tabular figures, so a column of them lines up. */
    val amount: TextStyle,
)

private val Display = FontFamily.Serif
private val Ui = FontFamily.SansSerif

private val evenly = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val AlmiraDefaultTypography = AlmiraTypography(
    display = TextStyle(
        fontFamily = Display, fontSize = 47.sp, lineHeight = 52.sp,
        fontWeight = FontWeight.Medium, lineHeightStyle = evenly,
    ),
    h1 = TextStyle(fontFamily = Display, fontSize = 33.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    h2 = TextStyle(fontFamily = Display, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
    h3 = TextStyle(fontFamily = Display, fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    h4 = TextStyle(fontFamily = Ui, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    large = TextStyle(fontFamily = Ui, fontSize = 18.sp, lineHeight = 26.sp),
    body = TextStyle(fontFamily = Ui, fontSize = 16.sp, lineHeight = 24.sp),
    small = TextStyle(fontFamily = Ui, fontSize = 14.sp, lineHeight = 20.sp),
    caption = TextStyle(fontFamily = Ui, fontSize = 12.5.sp, lineHeight = 18.sp),
    overline = TextStyle(
        fontFamily = Ui, fontSize = 11.5.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
    ),
    amount = TextStyle(
        fontFamily = Display, fontSize = 33.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Medium,
    ),
)
