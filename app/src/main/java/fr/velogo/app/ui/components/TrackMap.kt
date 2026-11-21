package fr.velogo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.velogo.app.map.MapColors
import fr.velogo.app.map.MapLayers
import fr.velogo.app.map.MapStyleSpec
import fr.velogo.app.map.PlanMarker
import fr.velogo.app.map.VeloMap
import fr.velogo.app.map.rememberVeloMapState
import fr.velogo.app.ui.LocalContainer
import fr.velogo.app.ui.theme.LocalIsDark
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon
import kotlinx.coroutines.flow.first

/**
 * Une carte qui montre une trace enregistrée (une sortie, un segment), cadrée sur elle, sur le fond de carte
 * choisi dans l'appli.
 *
 * [marker] désigne un point de la trace (celui que le doigt survole sur un graphique) ; [highlight] met un morceau
 * de la trace en avant, avec [planMarkers] pour ses extrémités (création d'un segment).
 */
@Composable
fun TrackMap(
    lines: List<List<LatLon>>?,
    bounds: BoundingBox?,
    modifier: Modifier = Modifier,
    marker: LatLon? = null,
    highlight: List<LatLon> = emptyList(),
    planMarkers: List<PlanMarker> = emptyList(),
) {
    val container = LocalContainer.current
    val mapState = rememberVeloMapState()
    val dark = LocalIsDark.current
    val rideColors = LocalRideColors.current
    var layerId by remember { mutableStateOf(MapLayers.OPENFREEMAP.id) }
    LaunchedEffect(Unit) { layerId = container.settings.settings.first().mapLayer }
    val offlineRevision by container.offline.store.epoch.collectAsStateWithLifecycle()
    val offlineFiles by container.offline.store.installed.collectAsStateWithLifecycle()
    val layer = MapLayers.effective(layerId, offlineRevision, offlineFiles.keys.any { it.startsWith("fond-") })
    val colors = remember(dark, rideColors) {
        MapColors(
            track = rideColors.track.toArgb(),
            trackCasing = Color.White.toArgb(),
            puck = Color(0xFF1A73E8).toArgb(),
            cycleway = (if (dark) Color(0xFF37D39A) else Color(0xFF0B8A5C)).toArgb(),
            start = Color(0xFF12A150).toArgb(),
            end = Color(0xFFD32F2F).toArgb(),
            route = rideColors.route.toArgb(),
        )
    }
    val padding = with(LocalDensity.current) { 40.dp.roundToPx() }
    LaunchedEffect(lines, bounds) {
        if (lines != null) {
            mapState.setRide(lines)
            bounds?.let { mapState.fit(it, padding) }
        }
    }
    LaunchedEffect(marker) { mapState.setMarker(marker) }
    LaunchedEffect(highlight) { mapState.setRoutes(if (highlight.size >= 2) listOf(highlight) else emptyList(), 0) }
    LaunchedEffect(planMarkers) { mapState.setPlanMarkers(planMarkers) }
    Box(modifier) {
        VeloMap(mapState, MapStyleSpec(layer, dark, if (layer.offline) offlineRevision else 0), colors, Modifier.fillMaxSize())
        // Mention obligatoire des sources de la carte.
        Text(
            layer.attribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
