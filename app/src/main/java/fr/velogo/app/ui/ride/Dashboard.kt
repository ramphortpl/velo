package fr.velogo.app.ui.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.ui.components.FittedNumber
import fr.velogo.app.ui.components.MetricCell
import fr.velogo.app.ui.components.continuousBackground
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.LocalSunlight
import fr.velogo.core.format.Formats
import fr.velogo.core.ride.RideSnapshot
import fr.velogo.core.ride.RideState
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * L'écran « guidon » : la vitesse en très grand, puis les sept autres données, chacune sur sa tuile.
 * Tout l'espace va aux chiffres, pour être lus d'un coup d'œil en roulant ; sous le thème « plein soleil », les
 * tuiles blanches sont cernées de noir.
 */
@Composable
fun Dashboard(snapshot: RideSnapshot, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                Tile(Modifier.weight(1f).fillMaxHeight()) { SpeedHero(snapshot, Modifier.fillMaxSize()) }
                MetricGrid(snapshot, Modifier.weight(1.25f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                Tile(Modifier.weight(3.1f).fillMaxWidth()) { SpeedHero(snapshot, Modifier.fillMaxSize()) }
                MetricGrid(snapshot, Modifier.weight(6.4f).fillMaxWidth())
            }
        }
    }
}

private val TILE_GAP = 8.dp

/** Le fond d'une donnée : une tuile aux coins continus, cernée de noir en plein soleil. */
@Composable
private fun Tile(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.continuousBackground(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            corner = 24.dp,
            border = if (LocalSunlight.current) Color.Black else Color.Unspecified,
        ),
        propagateMinConstraints = true,
    ) { content() }
}

@Composable
private fun SpeedHero(snapshot: RideSnapshot, modifier: Modifier) {
    val paused = snapshot.state == RideState.PAUSED || snapshot.state == RideState.AUTO_PAUSED
    Column(modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("VITESSE", style = LabelCapsStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f))
            Text(
                when (snapshot.state) {
                    RideState.PAUSED -> "EN PAUSE"
                    RideState.AUTO_PAUSED -> "À L'ARRÊT"
                    else -> "km/h"
                },
                style = LabelCapsStyle.copy(fontSize = 17.sp),
                color = if (paused) LocalRideColors.current.paused else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FittedNumber(
            text = Formats.speedKmh(snapshot.speedMps),
            template = "88,8",
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MetricGrid(snapshot: RideSnapshot, modifier: Modifier) {
    val t = snapshot.totals
    val clock by produceState(initialValue = clockText()) {
        while (true) {
            value = clockText()
            delay(5_000)
        }
    }
    val rideColors = LocalRideColors.current
    val slope = snapshot.slopePct
    val slopeColor = when {
        slope == null -> MaterialTheme.colorScheme.onSurface
        slope >= 4.0 -> rideColors.climb
        slope <= -4.0 -> rideColors.descent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        GridRow {
            Metric("DISTANCE", Formats.distanceKm(t.distanceM), "km", "88,88")
            Metric("DURÉE", Formats.duration(t.movingMs), "", "8:88:88")
        }
        GridRow {
            Metric("MOYENNE", Formats.speedKmh(t.avgSpeedMps), "km/h", "88,8")
            Metric("MAX", Formats.speedKmh(t.maxSpeedMps), "km/h", "88,8")
        }
        GridRow {
            Metric("PENTE", Formats.slope(slope), "%", "+88,8", valueColor = slopeColor)
            Metric("DÉNIVELÉ +", Formats.meters(t.ascentM), "m", "8888")
        }
        GridRow {
            Metric("ALTITUDE", Formats.meters(snapshot.altitudeM), "m", "8888")
            Metric("HEURE", clock, "", "88:88")
        }
    }
}

@Composable
private fun ColumnScope.GridRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP), content = content)
}

@Composable
private fun RowScope.Metric(label: String, value: String, unit: String, template: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Tile(Modifier.weight(1f).fillMaxHeight()) {
        MetricCell(label, value, unit, template, Modifier.fillMaxSize(), valueColor = valueColor)
    }
}

private fun clockText(): String {
    val now = LocalTime.now()
    return "${now.hour}:${now.minute.toString().padStart(2, '0')}"
}
