package tech.bhrigu.almira.shared.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import tech.bhrigu.almira.shared.resources.Res
import tech.bhrigu.almira.shared.resources.fraunces_medium
import tech.bhrigu.almira.shared.resources.fraunces_regular
import tech.bhrigu.almira.shared.resources.fraunces_semibold
import tech.bhrigu.almira.shared.resources.inter_medium
import tech.bhrigu.almira.shared.resources.inter_regular
import tech.bhrigu.almira.shared.resources.inter_semibold

/**
 * The two faces from docs/02 §3, bundled rather than borrowed from the system.
 *
 * **Static instances, not the variable fonts.** Both families ship as variable
 * fonts upstream, and one file would have been smaller — but Compose's `Font()`
 * does not expose variation axes, so a variable file renders at a single
 * default weight and the whole type scale quietly flattens. Three weights of
 * each is 1.2 MB and the scale actually works.
 *
 * **What they do not cover.** Neither face has Devanagari or Telugu, and this
 * product is localised into Hindi and Telugu. Text in those scripts falls back
 * to the platform's own Noto faces, which is correct behaviour and looks fine —
 * but it means the type on a Hindi screen is not Inter. Bundling Noto Sans
 * Devanagari and Telugu would add roughly another megabyte, and that is a
 * decision to take when those screens are built and can be looked at, not
 * before.
 *
 * Both are SIL Open Font License 1.1. The licence text is bundled with the app
 * at `files/licenses/`, because the OFL requires it to travel with the fonts.
 */
@Composable
fun almiraDisplayFamily(): FontFamily = FontFamily(
    Font(Res.font.fraunces_regular, FontWeight.Normal),
    Font(Res.font.fraunces_medium, FontWeight.Medium),
    Font(Res.font.fraunces_semibold, FontWeight.SemiBold),
)

@Composable
fun almiraUiFamily(): FontFamily = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_medium, FontWeight.Medium),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
)
