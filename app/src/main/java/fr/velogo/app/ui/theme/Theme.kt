package fr.velogo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** Choix de thème de l'utilisateur. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalRideColors = staticCompositionLocalOf { LightRideColors }
/** Vrai quand le thème affiché est sombre (sert au choix du fond de carte). */
val LocalIsDark = staticCompositionLocalOf { false }

@Composable
fun VeloGoTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    /** Force le thème « plein soleil » (écran guidon). */
    sunlight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when {
        sunlight -> false
        mode == ThemeMode.SYSTEM -> isSystemInDarkTheme()
        else -> mode == ThemeMode.DARK
    }
    val colors = when {
        sunlight -> SunlightColors
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalRideColors provides if (dark) DarkRideColors else LightRideColors,
        LocalIsDark provides dark,
    ) {
        MaterialTheme(colorScheme = colors, typography = VeloGoTypography, content = content)
    }
}

object VeloGoThemeExtras {
    val rideColors: RideColors
        @Composable @ReadOnlyComposable get() = LocalRideColors.current
}
