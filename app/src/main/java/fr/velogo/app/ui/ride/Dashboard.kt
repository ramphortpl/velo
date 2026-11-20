package fr.velogo.app.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.ui.components.FittedNumber
import fr.velogo.app.ui.components.MetricCell
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.core.format.Formats
import fr.velogo.core.ride.RideSnapshot
import fr.velogo.core.ride.RideState
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * L'écran « guidon » : la vitesse en très grand, puis les sept autres données sur une grille.
 * Aucune décoration : tout l'espace va aux chiffres, pour être lu d'un coup d'œil en roulant.
 */
@Composable
fun Dashboard(snapshot: RideSnapshot, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                SpeedHero(snapshot, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MetricGrid(snapshot, Modifier.weight(1.25f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SpeedHero(snapshot, Modifier.weight(3.1f).fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MetricGrid(snapshot, Modifier.weight(6.4f).fillMaxWidth())
            }
        }
    }
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
    Column(modifier) {
        GridRow {
            MetricCell("DISTANCE", Formats.distanceKm(t.distanceM), "km", "88,88", Modifier.weight(1f).fillMaxHeight())
            CellDivider()
            MetricCell("DURÉE", Formats.duration(t.movingMs), "", "8:88:88", Modifier.weight(1f).fillMaxHeight())
        }
        RowDivider()
        GridRow {
            MetricCell("MOYENNE", Formats.speedKmh(t.avgSpeedMps), "km/h", "88,8", Modifier.weight(1f).fillMaxHeight())
            CellDivider()
            MetricCell("MAX", Formats.speedKmh(t.maxSpeedMps), "km/h", "88,8", Modifier.weight(1f).fillMaxHeight())
        }
        RowDivider()
        GridRow {
            MetricCell("PENTE", Formats.slope(slope), "%", "+88,8", Modifier.weight(1f).fillMaxHeight(), valueColor = slopeColor)
            CellDivider()
            MetricCell("DÉNIVELÉ +", Formats.meters(t.ascentM), "m", "8888", Modifier.weight(1f).fillMaxHeight())
        }
        RowDivider()
        GridRow {
            MetricCell("ALTITUDE", Formats.meters(snapshot.altitudeM), "m", "8888", Modifier.weight(1f).fillMaxHeight())
            CellDivider()
            MetricCell("HEURE", clock, "", "88:88", Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.GridRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().weight(1f), content = content)
}

@Composable
private fun CellDivider() {
    VerticalDivider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun clockText(): String {
    val now = LocalTime.now()
    return "${now.hour}:${now.minute.toString().padStart(2, '0')}"
}
