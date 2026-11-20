package fr.velogo.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Identité VeloGo : vert émeraude sur bleu nuit. (Pas d'orange : on ne ressemble à aucune appli existante.)
val Emerald = Color(0xFF1FD39A)
val Night = Color(0xFF0B2239)

/** Thème clair. */
val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF006B54),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7FF8D2),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF2B5F8A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE5FF),
    onSecondaryContainer = Color(0xFF001D33),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF8),
    onBackground = Color(0xFF151D1A),
    surface = Color(0xFFF7FBF8),
    onSurface = Color(0xFF151D1A),
    surfaceVariant = Color(0xFFDAE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C3),
    inverseSurface = Color(0xFF2A322F),
    inverseOnSurface = Color(0xFFECF2EE),
    inversePrimary = Color(0xFF60DBB7),
    surfaceTint = Color(0xFF006B54),
    surfaceDim = Color(0xFFD7DBD8),
    surfaceBright = Color(0xFFF7FBF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F2),
    surfaceContainer = Color(0xFFEBEFEC),
    surfaceContainerHigh = Color(0xFFE5EAE6),
    surfaceContainerHighest = Color(0xFFDFE4E1),
)

/** Thème sombre : surfaces bleu nuit. */
val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF4FDDB3),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFF7FF8D2),
    secondary = Color(0xFF9CCBFB),
    onSecondary = Color(0xFF003353),
    secondaryContainer = Color(0xFF0E4A72),
    onSecondaryContainer = Color(0xFFCFE5FF),
    tertiary = Color(0xFFF5BF48),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5E4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B131A),
    onBackground = Color(0xFFDEE3E8),
    surface = Color(0xFF0B131A),
    onSurface = Color(0xFFDEE3E8),
    surfaceVariant = Color(0xFF3C4750),
    onSurfaceVariant = Color(0xFFBBC7D1),
    outline = Color(0xFF86919B),
    outlineVariant = Color(0xFF3C4750),
    inverseSurface = Color(0xFFDEE3E8),
    inverseOnSurface = Color(0xFF283038),
    inversePrimary = Color(0xFF006B54),
    surfaceTint = Color(0xFF4FDDB3),
    surfaceDim = Color(0xFF0B131A),
    surfaceBright = Color(0xFF313A44),
    surfaceContainerLowest = Color(0xFF060D13),
    surfaceContainerLow = Color(0xFF131C24),
    surfaceContainer = Color(0xFF17212A),
    surfaceContainerHigh = Color(0xFF212B35),
    surfaceContainerHighest = Color(0xFF2C3640),
)

/**
 * Thème « plein soleil » : noir pur sur blanc pur, sans gris.
 * Sur un écran LCD peu lumineux, c'est ce qui reste lisible sur le guidon en plein été.
 */
val SunlightColors: ColorScheme = LightColors.copy(
    primary = Color(0xFF004D3C),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF000000),
    outline = Color(0xFF000000),
    outlineVariant = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F2F2),
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

/** Couleurs propres au vélo, en plus de celles de Material. */
data class RideColors(
    /** La trace enregistrée sur la carte. */
    val track: Color,
    /** L'itinéraire à suivre. */
    val route: Color,
    val climb: Color,
    val descent: Color,
    /** Bouton d'arrêt. */
    val stop: Color,
    val onStop: Color,
    /** Pause en cours. */
    val paused: Color,
)

val LightRideColors = RideColors(
    track = Color(0xFFE5006D),
    route = Color(0xFF1565C0),
    climb = Color(0xFFC62828),
    descent = Color(0xFF1565C0),
    stop = Color(0xFFB3261E),
    onStop = Color(0xFFFFFFFF),
    paused = Color(0xFF8A5A00),
)

val DarkRideColors = RideColors(
    track = Color(0xFFFF5CA8),
    route = Color(0xFF64B5F6),
    climb = Color(0xFFFF8A80),
    descent = Color(0xFF82B1FF),
    stop = Color(0xFFFFB4AB),
    onStop = Color(0xFF690005),
    paused = Color(0xFFF5BF48),
)
