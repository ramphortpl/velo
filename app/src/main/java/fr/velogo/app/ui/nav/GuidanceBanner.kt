package fr.velogo.app.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.R
import fr.velogo.app.nav.GuidanceState
import fr.velogo.app.nav.RouteOption
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.core.format.Formats
import fr.velogo.core.nav.GuidancePhrases
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.RoutePreference

/** Hauteur du bandeau de guidage : les autres éléments de l'écran se décalent d'autant. */
val GUIDANCE_BANNER_HEIGHT: Dp = 78.dp

private val OFF_ROUTE_COLOR = Color(0xFFFFB300)

/** Icône d'une manœuvre. */
fun maneuverIcon(maneuver: Maneuver): Int = when (maneuver) {
    Maneuver.CONTINUE -> R.drawable.ic_straight
    Maneuver.SLIGHT_LEFT -> R.drawable.ic_turn_slight_left
    Maneuver.LEFT -> R.drawable.ic_turn_left
    Maneuver.SHARP_LEFT -> R.drawable.ic_turn_sharp_left
    Maneuver.SLIGHT_RIGHT -> R.drawable.ic_turn_slight_right
    Maneuver.RIGHT -> R.drawable.ic_turn_right
    Maneuver.SHARP_RIGHT -> R.drawable.ic_turn_sharp_right
    Maneuver.KEEP_LEFT -> R.drawable.ic_fork_left
    Maneuver.KEEP_RIGHT -> R.drawable.ic_fork_right
    Maneuver.U_TURN -> R.drawable.ic_u_turn_left
    Maneuver.ROUNDABOUT -> R.drawable.ic_roundabout_right
    Maneuver.ARRIVE -> R.drawable.ic_sports_score
}

/** Ce qui distingue une proposition d'itinéraire : « Le plus rapide », « Rapide et sûr », « Variante »… */
fun optionTitle(option: RouteOption): String {
    val p = option.preferences.toSet()
    return when {
        option.isVariant -> "Variante"
        p.size == 3 -> "Le meilleur sur tous les plans"
        p == setOf(RoutePreference.FAST, RoutePreference.SAFE) -> "Le plus rapide et le plus sûr"
        p == setOf(RoutePreference.FAST, RoutePreference.FLAT) -> "Le plus rapide, peu de dénivelé"
        p == setOf(RoutePreference.SAFE, RoutePreference.FLAT) -> "Le plus sûr, peu de dénivelé"
        RoutePreference.FAST in p -> "Le plus rapide"
        RoutePreference.SAFE in p -> "Le plus sûr"
        RoutePreference.FLAT in p -> "Le moins de dénivelé"
        else -> "Itinéraire"
    }
}

/** Distance restante avant une manœuvre : « 40 m », « 350 m », « 1,2 km ». */
fun maneuverDistance(meters: Double): String = when {
    meters < 95 -> "${(meters / 10).toInt().coerceAtLeast(1) * 10} m"
    meters < 950 -> "${((meters + 25) / 50).toInt() * 50} m"
    else -> "${Formats.decimal(meters / 1000.0, 1)} km"
}

/**
 * Bandeau de guidage : la prochaine manœuvre en gros, ce qu'il reste à parcourir, et le bouton pour arrêter.
 * Il change de couleur quand on s'écarte de l'itinéraire.
 */
@Composable
fun GuidanceBanner(guidance: GuidanceState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val nav = guidance.nav
    val offRoute = nav.isOffRoute
    val container: Color
    val content: Color
    when {
        // Ambre vif et texte noir : l'alerte doit sauter aux yeux, en plein soleil comme de nuit.
        offRoute -> { container = OFF_ROUTE_COLOR; content = Color.Black }
        else -> { container = MaterialTheme.colorScheme.primary; content = MaterialTheme.colorScheme.onPrimary }
    }
    val next = nav.next
    val icon: Int
    val headline: String
    val detail: String
    when {
        nav.arrived -> {
            icon = R.drawable.ic_sports_score
            headline = "Arrivée"
            detail = guidance.destination
        }
        offRoute -> {
            icon = R.drawable.ic_alt_route
            headline = "Hors itinéraire"
            detail = when {
                // Des phrases courtes : sur une seule ligne, les plus longues étaient coupées au guidon.
                guidance.rerouting -> "Calcul d'un nouvel itinéraire…"
                guidance.followOnly -> "Rejoins le tracé (à ${maneuverDistance(nav.offsetM)})"
                else -> "Je recalcule si tu continues"
            }
        }
        next != null && next.maneuver != Maneuver.ARRIVE -> {
            icon = maneuverIcon(next.maneuver)
            headline = maneuverDistance(nav.distanceToNextM)
            val then = nav.then?.takeIf { it.maneuver != Maneuver.ARRIVE }
            detail = GuidancePhrases.label(next) + if (then != null) ", puis ${GuidancePhrases.label(then).lowercase()}" else ""
        }
        else -> {
            icon = R.drawable.ic_straight
            headline = Formats.distanceAuto(nav.remainingM)
            detail = if (guidance.followOnly) "Suis le tracé" else "Continue jusqu'à l'arrivée"
        }
    }
    val remaining = "${Formats.distanceAuto(nav.remainingM)} · ${Formats.durationWords((nav.remainingS * 1000).toLong())}"

    Surface(
        modifier = modifier.fillMaxWidth().height(GUIDANCE_BANNER_HEIGHT).semantics { contentDescription = "Guidage : $headline, $detail" },
        shape = RoundedCornerShape(22.dp),
        color = container,
        contentColor = content,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(46.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(headline, style = NumberStyle.copy(fontSize = 32.sp), maxLines = 1)
                    if (!nav.arrived && !offRoute) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            remaining,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                }
                Text(detail, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onStop) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Arrêter le guidage")
            }
        }
    }
}
