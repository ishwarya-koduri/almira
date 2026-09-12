package tech.bhrigu.almira.shared.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The palette from docs/02, as the app's own type rather than Material's.
 *
 * Material's ColorScheme has no slot for "hairline", "ink-faint" or a gold used
 * exactly once per screen, and squeezing them into `surfaceVariant` and
 * `tertiary` would lose the meaning that makes them worth having. So these are
 * carried directly and Material's scheme is derived from them (see
 * [AlmiraTheme]) — that way stock Material components still look right, and
 * everything written for this product reads in the product's own vocabulary.
 *
 * Every value here is the same hex as `tokens.css`. When one changes, it
 * changes in both places or the two surfaces drift, which is the one thing a
 * design system is for.
 */
@Immutable
data class AlmiraColors(
    /** The warm off-white "paper" the whole app sits on. Not clinical white. */
    val canvas: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val hairline: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentInk: Color,
    val positive: Color,
    val caution: Color,
    val cautionSoft: Color,
    /** Used exactly once per screen, on the number you came to see. */
    val gold: Color,
    val isDark: Boolean,
)

val AlmiraLightColors = AlmiraColors(
    canvas = Color(0xFFFBF9F5),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFF4F1EA),
    ink = Color(0xFF1C1A17),
    inkMuted = Color(0xFF6B6558),
    inkFaint = Color(0xFF9A9384),
    hairline = Color(0xFFE7E2D8),
    accent = Color(0xFF0F5A57),
    accentSoft = Color(0xFFDCEBE9),
    accentInk = Color(0xFFFFFFFF),
    positive = Color(0xFF1B7A43),
    caution = Color(0xFFB4520A),
    cautionSoft = Color(0xFFFBEFE4),
    gold = Color(0xFFC9A227),
    isDark = false,
)

val AlmiraDarkColors = AlmiraColors(
    canvas = Color(0xFF14130F),
    surface = Color(0xFF1D1B16),
    surfaceSunken = Color(0xFF242019),
    ink = Color(0xFFF2EEE4),
    inkMuted = Color(0xFFB4AD9C),
    inkFaint = Color(0xFF8A8474),
    hairline = Color(0xFF332F27),
    accent = Color(0xFF3E9E99),
    accentSoft = Color(0xFF1E3B39),
    accentInk = Color(0xFF0B1C1B),
    positive = Color(0xFF4FB477),
    caution = Color(0xFFE0873F),
    cautionSoft = Color(0xFF33251A),
    gold = Color(0xFFD8B95A),
    isDark = true,
)

/**
 * Category identity. Dots and thin chips only, never large fills — and never
 * carrying meaning on their own: each is always paired with an icon and a
 * label, which is also what makes the set work for the ~8% of men with
 * colour-vision deficiency (docs/02 §2).
 */
object CategoryColors {
    val gold = Color(0xFFC9A227)
    val deposits = Color(0xFF4E7C59)
    val mutualFunds = Color(0xFF3E6B99)
    val equity = Color(0xFF6A5A99)
    val ipo = Color(0xFFA6555A)
    val bonds = Color(0xFF7A6A55)
    val retirement = Color(0xFF3F8A8A)
    val insurance = Color(0xFF2F7F76)
    val realEstate = Color(0xFFB06B3A)
    val alternatives = Color(0xFF8A7F6A)
    val cash = Color(0xFF7C8A6A)
    val universal = Color(0xFF6B6558)

    /** The server sends a category code; this is the only place that maps it. */
    fun forCode(code: String): Color = when (code) {
        "gold" -> gold
        "deposits" -> deposits
        "mutual_funds" -> mutualFunds
        "equity" -> equity
        "ipo" -> ipo
        "bonds" -> bonds
        "retirement" -> retirement
        "insurance" -> insurance
        "real_estate" -> realEstate
        "alternatives" -> alternatives
        "cash" -> cash
        else -> universal
    }
}
