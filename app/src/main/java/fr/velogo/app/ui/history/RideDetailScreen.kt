package fr.velogo.app.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.velogo.app.R
import fr.velogo.app.data.GpxExport
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.map.MapColors
import fr.velogo.app.map.MapLayers
import fr.velogo.app.map.MapStyleSpec
import fr.velogo.app.map.VeloMap
import fr.velogo.app.map.rememberVeloMapState
import fr.velogo.app.ui.LocalContainer
import fr.velogo.app.ui.components.ProfileChart
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.LocalIsDark
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.util.Dates
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import fr.velogo.core.geo.LatLon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Détail d'une sortie : carte, chiffres, graphiques d'altitude et de vitesse, export GPX. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(rideId: Long, onBack: () -> Unit) {
    val vm = veloViewModel(key = "sortie-$rideId") { RideDetailViewModel(it, rideId) }
    val ride by vm.ride.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GpxExport.MIME)) { uri ->
        val current = ride
        if (uri != null && current != null) scope.launch { runCatching { GpxExport.writeTo(context, uri, current, vm.points()) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ride?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = {
                        val current = ride ?: return@IconButton
                        scope.launch { runCatching { context.startActivity(GpxExport.shareIntent(context, current, vm.points())) } }
                    }) { Icon(painterResource(R.drawable.ic_share), contentDescription = "Partager la trace GPX") }
                    IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Plus d'actions") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Renommer") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Enregistrer le GPX…") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_file_save), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                ride?.let { saveLauncher.launch(GpxExport.fileName(it)) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; deleting = true },
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        val data = track
        val marker = selected?.let { i -> data?.profile?.getOrNull(i) }?.let { LatLon(it.lat, it.lon) }
        RideDetailBody(
            ride = ride,
            data = data,
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.padding(inner),
        ) { modifier -> RideMap(data, marker, modifier) }
    }

    if (renaming) {
        var name by remember { mutableStateOf(ride?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renommer la sortie") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Nom") }) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(name)
                    renaming = false
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annuler") } },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Supprimer cette sortie ?") },
            text = { Text("La trace et ses statistiques seront effacées du téléphone. C'est définitif.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleting = false
                        vm.delete(onBack)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annuler") } },
        )
    }
}

/**
 * Corps de l'écran : la carte (fournie par [map]) et, en dessous ou à côté, les chiffres et les graphiques.
 * Séparé de l'accès aux données pour pouvoir être vérifié en image.
 */
@Composable
fun RideDetailBody(
    ride: RideEntity?,
    data: RideTrackData?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    map: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val details: @Composable (Modifier) -> Unit = { detailsModifier ->
            Column(
                detailsModifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (ride != null) {
                    Header(ride)
                    StatsGrid(ride)
                }
                if (data != null && data.profile.size >= 2) {
                    Readout(data, selected)
                    ChartCard("Altitude", "m") {
                        ProfileChart(
                            series = data.altitude,
                            lineColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            selected = selected,
                            onSelect = onSelect,
                            formatY = { Formats.meters(it.toDouble()) },
                            formatX = { Formats.decimal(it.toDouble(), 1) + " km" },
                        )
                    }
                    ChartCard("Vitesse", "km/h") {
                        ProfileChart(
                            series = data.speed,
                            lineColor = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            selected = selected,
                            onSelect = onSelect,
                            formatY = { Formats.decimal(it.toDouble(), 0) },
                            formatX = { Formats.decimal(it.toDouble(), 1) + " km" },
                            zeroBased = true,
                        )
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxHeight())
                details(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                map(Modifier.fillMaxWidth().weight(0.4f))
                details(Modifier.fillMaxWidth().weight(0.6f))
            }
        }
    }
}

@Composable
private fun RideMap(data: RideTrackData?, marker: LatLon?, modifier: Modifier) {
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
        )
    }
    val padding = with(LocalDensity.current) { 40.dp.roundToPx() }
    LaunchedEffect(data) {
        if (data != null) {
            mapState.setRide(data.segments)
            data.bounds?.let { mapState.fit(it, padding) }
        }
    }
    LaunchedEffect(marker) { mapState.setMarker(marker) }
    Box(modifier) {
        VeloMap(mapState, MapStyleSpec(layer, dark, if (layer.offline) offlineRevision else 0), colors, Modifier.fillMaxSize())
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

@Composable
private fun Header(ride: RideEntity) {
    Column {
        Text(Dates.fullDay(ride.startTimeMs), style = MaterialTheme.typography.titleLarge)
        val end = ride.endTimeMs
        Text(
            buildString {
                append("Départ à ${Dates.time(ride.startTimeMs)}")
                if (end != null) append(" · arrivée à ${Dates.time(end)}")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsGrid(ride: RideEntity) {
    val avg = if (ride.movingMs >= 1_000) ride.distanceM / (ride.movingMs / 1000.0) else 0.0
    val altitudeNote = when (ride.altitudeSource) {
        RideEntity.ALTITUDE_IGN -> "Altitude recalée sur le relief IGN"
        RideEntity.ALTITUDE_RELIEF -> "Altitude issue du relief hors ligne"
        else -> "Altitude mesurée au GPS seul : dénivelé approximatif"
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row {
                Stat("DISTANCE", Formats.distanceKm(ride.distanceM), "km", Modifier.weight(1f))
                Stat("DURÉE", Formats.duration(ride.movingMs), "", Modifier.weight(1f))
            }
            Row {
                Stat("VITESSE MOYENNE", Formats.speedKmh(avg), "km/h", Modifier.weight(1f))
                Stat("VITESSE MAX", Formats.speedKmh(ride.maxSpeedMps), "km/h", Modifier.weight(1f))
            }
            Row {
                Stat("DÉNIVELÉ +", Formats.meters(ride.ascentM), "m", Modifier.weight(1f))
                Stat("DÉNIVELÉ −", Formats.meters(ride.descentM), "m", Modifier.weight(1f))
            }
            Row {
                Stat("ALTITUDE MIN / MAX", "${Formats.meters(ride.minAltitudeM)} / ${Formats.meters(ride.maxAltitudeM)}", "m", Modifier.weight(1f))
                Stat("TEMPS TOTAL", Formats.duration(ride.elapsedMs), "", Modifier.weight(1f))
            }
            Text(altitudeNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = LabelCapsStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle.copy(fontSize = 30.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.padding(start = 4.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** Valeurs au point survolé sur un graphique. */
@Composable
private fun Readout(data: RideTrackData, selected: Int?) {
    val sample = selected?.let { data.profile.getOrNull(it) }
    Text(
        if (sample == null) "Glisse le doigt sur un graphique pour lire les valeurs et voir le point sur la carte."
        else "${Formats.decimal(sample.distanceM / 1000.0, 2)} km · ${Formats.meters(sample.altitudeM)} m · ${Formats.speedKmh(sample.speedMps)} km/h",
        style = if (sample == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
        color = if (sample == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ChartCard(title: String, unit: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(" ($unit)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}
