package fr.velogo.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.velogo.app.R

/** Barlow Condensed (licence OFL) : une police étroite et très lisible, inspirée de la signalisation routière. */
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

private val base = Typography()

val VeloGoTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
)

/**
 * Style des chiffres : chiffres « tabulaires » (tous de même largeur) pour que l'affichage ne tremble pas
 * quand la vitesse change, et interligne serré pour occuper toute la hauteur disponible.
 */
val NumberStyle = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.sp,
    lineHeight = 1.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** Petites étiquettes en capitales au-dessus des chiffres (« VITESSE », « DISTANCE »…). */
val LabelCapsStyle = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    letterSpacing = 0.08.em,
)
