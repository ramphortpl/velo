package fr.velogo.app.ui.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.R
import fr.velogo.app.ui.components.GlassPill
import fr.velogo.app.ui.components.VeloCard
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.LocalGlass
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.LocalSunlight
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.core.format.Formats
import fr.velogo.core.weather.RainForecast
import kotlin.math.roundToInt

/** Couleur d'une icône météo : ambre pour le soleil, bleu pour ce qui mouille, neutre sinon. */
@Composable
fun weatherTint(code: Int?): Color = when {
    isWetKind(code) -> LocalRideColors.current.rain
    code == 0 || code == 1 || code == 2 -> LocalRideColors.current.sun
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * La météo en un coup d'œil sur la carte : le temps qu'il fait, la température, le vent, et la pluie qui approche.
 * Un appui ouvre le détail.
 */
@Composable
fun WeatherChip(glance: WeatherGlance, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = glance.now
    val glass = LocalGlass.current.regular
    val rideColors = LocalRideColors.current
    val muted = glass.content.copy(alpha = 0.66f)
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassPill(
            modifier = Modifier.semantics { contentDescription = glanceDescription(glance) },
            onClick = onClick,
            horizontalPadding = 13.dp,
            verticalPadding = 7.dp,
        ) {
            // Les lecteurs d'écran lisent la phrase ci-dessus, pas les morceaux un par un.
            Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(weatherIcon(now.code, now.isDay)), contentDescription = null, tint = weatherTint(now.code), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(6.dp))
                Text(Formats.temperature(now.temperatureC), style = NumberStyle.copy(fontSize = 24.sp))
                val wind = now.windKmh
                if (wind != null) {
                    Spacer(Modifier.width(10.dp))
                    Icon(painterResource(R.drawable.ic_air), contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${wind.roundToInt()}", style = NumberStyle.copy(fontSize = 20.sp))
                    Spacer(Modifier.width(3.dp))
                    Text("KM/H", style = LabelCapsStyle.copy(fontSize = 11.sp), color = muted)
                }
            }
        }
        glance.rain?.let { rain ->
            // La pluie qui approche : une pilule bleue, pleine, qui ne se confond avec rien d'autre sur la carte.
            GlassPill(
                modifier = Modifier.clearAndSetSemantics { },
                style = glass.copy(fillTop = rideColors.rainContainer, fillBottom = rideColors.rainContainer, content = rideColors.onRainContainer),
                onClick = onClick,
                horizontalPadding = 13.dp,
                verticalPadding = 7.dp,
            ) {
                Icon(painterResource(R.drawable.ic_umbrella), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(RainForecast.describe(rain), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/**
 * La météo sur l'écran guidon : une seule ligne sous les chiffres, lisible en roulant. La pluie qui approche
 * prend la place du vent, sur fond coloré.
 */
@Composable
fun WeatherStrip(glance: WeatherGlance, onClick: () -> Unit, modifier: Modifier = Modifier) {
    VeloCard(
        modifier = modifier.fillMaxWidth().height(STRIP_HEIGHT).semantics { contentDescription = glanceDescription(glance) },
        onClick = onClick,
        onClickLabel = "Voir la météo",
        corner = 20.dp,
        border = if (LocalSunlight.current) Color.Black else Color.Unspecified,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            StripContent(glance)
        }
    }
}

@Composable
private fun RowScope.StripContent(glance: WeatherGlance) {
    val now = glance.now
    // Les lecteurs d'écran lisent la phrase de la ligne, pas les morceaux un par un.
    Row(Modifier.weight(1f).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(weatherIcon(now.code, now.isDay)), contentDescription = null, tint = weatherTint(now.code), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Text(Formats.temperature(now.temperatureC), style = NumberStyle.copy(fontSize = 30.sp), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        val rain = glance.rain
        if (rain != null) {
            Box(Modifier.weight(1f)) {
                val rideColors = LocalRideColors.current
                Surface(shape = CircleShape, color = rideColors.rainContainer, contentColor = rideColors.onRainContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_umbrella), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            RainForecast.describe(rain).uppercase(),
                            style = LabelCapsStyle.copy(fontSize = 17.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Text(
                (windLine(now, glance.windSide) ?: "").uppercase(),
                style = LabelCapsStyle.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

val STRIP_HEIGHT = 50.dp
