package fr.velogo.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Les trois couleurs du logo (`docs/logo`) : toute l'appli en découle.
 * Vert foncé pour le texte fort et les boutons, vert vif pour ce qui est choisi ou en marche, rose pour la trace
 * et pour arrêter.
 */
object Brand {
    val Deep = Color(0xFF014A38)
    val Fresh = Color(0xFF01CD95)
    val Pink = Color(0xFFE70180)
}

/**
 * Thème clair, à la manière des listes groupées d'iOS : un fond gris très légèrement vert, et dessus des cartes
 * blanches. `surface` est le fond des écrans, `surfaceContainerLow` celui des cartes et des feuilles.
 */
val LightColors: ColorScheme = lightColorScheme(
    primary = Brand.Deep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6F6E5),
    onPrimaryContainer = Color(0xFF013527),
    secondary = Color(0xFF00795C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDF3EA),
    onSecondaryContainer = Color(0xFF01382B),
    tertiary = Color(0xFFC8006E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E7),
    onTertiaryContainer = Color(0xFF3E0020),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFEEF3F0),
    onBackground = Color(0xFF0E1A16),
    surface = Color(0xFFEEF3F0),
    onSurface = Color(0xFF0E1A16),
    surfaceVariant = Color(0xFFDCE7E1),
    onSurfaceVariant = Color(0xFF45534D),
    outline = Color(0xFF72807A),
    outlineVariant = Color(0xFFD3DDD8),
    inverseSurface = Color(0xFF1F2B27),
    inverseOnSurface = Color(0xFFEAF2EE),
    inversePrimary = Color(0xFF4FE0B6),
    surfaceTint = Brand.Deep,
    surfaceDim = Color(0xFFD5DDD9),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFAFCFB),
    surfaceContainerHigh = Color(0xFFF7FAF8),
    surfaceContainerHighest = Color(0xFFE5EDE9),
)

/** Thème sombre : un noir à peine vert, des cartes un ton au-dessus, et le vert vif du logo pour ce qui compte. */
val DarkColors: ColorScheme = darkColorScheme(
    primary = Brand.Fresh,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Brand.Deep,
    onPrimaryContainer = Color(0xFFA6F5DB),
    secondary = Color(0xFF8FD6BD),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF173A2F),
    onSecondaryContainer = Color(0xFFC4EFDF),
    tertiary = Color(0xFFFF6CB5),
    onTertiary = Color(0xFF5E0034),
    tertiaryContainer = Color(0xFF7A0045),
    onTertiaryContainer = Color(0xFFFFD9E7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF06100D),
    onBackground = Color(0xFFE1EBE6),
    surface = Color(0xFF06100D),
    onSurface = Color(0xFFE1EBE6),
    surfaceVariant = Color(0xFF2F3D37),
    onSurfaceVariant = Color(0xFFB4C4BC),
    outline = Color(0xFF7F8F87),
    outlineVariant = Color(0xFF2A3731),
    inverseSurface = Color(0xFFE1EBE6),
    inverseOnSurface = Color(0xFF1F2B27),
    inversePrimary = Brand.Deep,
    surfaceTint = Brand.Fresh,
    surfaceDim = Color(0xFF06100D),
    surfaceBright = Color(0xFF2A3833),
    surfaceContainerLowest = Color(0xFF020806),
    surfaceContainerLow = Color(0xFF101C18),
    surfaceContainer = Color(0xFF14211C),
    surfaceContainerHigh = Color(0xFF1B2A24),
    surfaceContainerHighest = Color(0xFF24342E),
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
    /** La trace enregistrée sur la carte : le rose du logo. */
    val track: Color,
    /** L'itinéraire à suivre : bleu, la seule couleur qui tranche sur tous les fonds de carte. */
    val route: Color,
    val climb: Color,
    val descent: Color,
    /** Bouton d'arrêt. */
    val stop: Color,
    val onStop: Color,
    /** Pause en cours, signal moyen : ce qui demande un peu d'attention. */
    val paused: Color,
    /** La pluie, et le fond des messages qui l'annoncent. */
    val rain: Color,
    val rainContainer: Color,
    val onRainContainer: Color,
    /** Le soleil. */
    val sun: Color,
    /** Ce qui est en marche (carte qui suit la position, bon signal) : le vert vif du logo, lisible sur fond clair. */
    val active: Color,
)

val LightRideColors = RideColors(
    track = Brand.Pink,
    route = Color(0xFF1565C0),
    climb = Color(0xFFD1006F),
    descent = Color(0xFF00795C),
    stop = Brand.Pink,
    onStop = Color(0xFFFFFFFF),
    paused = Color(0xFF8A5A00),
    rain = Color(0xFF1A66C9),
    rainContainer = Color(0xFFD9E8FF),
    onRainContainer = Color(0xFF062B5C),
    sun = Color(0xFFC27A00),
    active = Color(0xFF00A57B),
)

val DarkRideColors = RideColors(
    track = Color(0xFFFF4FA3),
    route = Color(0xFF64B5F6),
    climb = Color(0xFFFF6CB5),
    descent = Color(0xFF4FE0B6),
    stop = Color(0xFFFF4FA3),
    onStop = Color(0xFF3E0020),
    paused = Color(0xFFF5BF48),
    rain = Color(0xFF8CBBFF),
    rainContainer = Color(0xFF123A6B),
    onRainContainer = Color(0xFFD9E8FF),
    sun = Color(0xFFF5BF48),
    active = Brand.Fresh,
)
