package tech.bhrigu.almira.shared.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The design system from docs/02, as one theme for both platforms.
 *
 * Two things are provided at once, deliberately:
 *
 * 1. **Almira's own tokens** — colours, spacing, radii and a type scale in the
 *    product's vocabulary, reached through [AlmiraTheme.colors] and friends.
 *    This is what product code should use.
 * 2. **A Material 3 scheme derived from them**, so a stock `Button`, `Card` or
 *    `TextField` already looks like Almira instead of like Material's purple.
 *    Without this second half, every Material component would have to be
 *    restyled at the call site, which is how a design system quietly dies.
 */
object AlmiraTheme {
    val colors: AlmiraColors
        @Composable get() = LocalAlmiraColors.current

    val typography: AlmiraTypography
        @Composable get() = LocalAlmiraTypography.current

    val spacing: AlmiraSpacing
        @Composable get() = LocalAlmiraSpacing.current

    val radii: AlmiraRadii
        @Composable get() = LocalAlmiraRadii.current
}

val LocalAlmiraColors: ProvidableCompositionLocal<AlmiraColors> =
    staticCompositionLocalOf { AlmiraLightColors }
val LocalAlmiraTypography: ProvidableCompositionLocal<AlmiraTypography> =
    staticCompositionLocalOf { AlmiraDefaultTypography }
val LocalAlmiraSpacing: ProvidableCompositionLocal<AlmiraSpacing> =
    staticCompositionLocalOf { AlmiraSpacing() }
val LocalAlmiraRadii: ProvidableCompositionLocal<AlmiraRadii> =
    staticCompositionLocalOf { AlmiraRadii() }

@Composable
fun AlmiraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) AlmiraDarkColors else AlmiraLightColors
    val radii = AlmiraRadii()

    // Material's slots, filled from ours. `background` is the canvas rather
    // than the surface, because the app sits on paper and cards sit on the app.
    //
    // The `surfaceContainer*` family matters more than it looks: menus, sheets
    // and the date picker dialog take their background from it rather than from
    // `surface`, so leaving it unset means a lavender Material dialog opening
    // on top of an Almira screen. Filling it is what keeps every borrowed
    // component inside the design system.
    val material = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.ink,
            secondary = colors.gold,
            onSecondary = colors.canvas,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.hairline,
            outlineVariant = colors.hairline,
            error = colors.caution,
            onError = colors.canvas,
            errorContainer = colors.cautionSoft,
            surfaceContainerLowest = colors.canvas,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            surfaceContainerHighest = colors.surfaceSunken,
            surfaceBright = colors.surface,
            surfaceDim = colors.canvas,
            surfaceTint = colors.accent,
            inverseSurface = colors.ink,
            inverseOnSurface = colors.canvas,
            inversePrimary = colors.accentSoft,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.ink,
            secondary = colors.gold,
            onSecondary = colors.accentInk,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.hairline,
            outlineVariant = colors.hairline,
            error = colors.caution,
            onError = colors.accentInk,
            errorContainer = colors.cautionSoft,
            surfaceContainerLowest = colors.surface,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            surfaceContainerHighest = colors.surfaceSunken,
            surfaceBright = colors.surface,
            surfaceDim = colors.surfaceSunken,
            surfaceTint = colors.accent,
            inverseSurface = colors.ink,
            inverseOnSurface = colors.canvas,
            inversePrimary = colors.accentSoft,
        )
    }

    CompositionLocalProvider(
        LocalAlmiraColors provides colors,
        LocalAlmiraTypography provides rememberAlmiraTypography(),
        LocalAlmiraSpacing provides AlmiraSpacing(),
        LocalAlmiraRadii provides radii,
    ) {
        MaterialTheme(
            colorScheme = material,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(radii.sm),
                small = RoundedCornerShape(radii.sm),
                medium = RoundedCornerShape(radii.md),
                large = RoundedCornerShape(radii.lg),
                extraLarge = RoundedCornerShape(radii.xl),
            ),
            content = content,
        )
    }
}
