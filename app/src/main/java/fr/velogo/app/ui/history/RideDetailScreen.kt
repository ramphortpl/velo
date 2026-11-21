package fr.velogo.app.ui.history

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.velogo.app.R
import fr.velogo.app.data.GpxExport
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.components.ProfileChart
import fr.velogo.app.ui.components.TrackMap
import fr.velogo.app.map.PlanMarker
import fr.velogo.app.ui.segments.RideSegmentRow
import fr.velogo.app.ui.segments.SegmentEditor
import fr.velogo.app.ui.segments.rankLabel
import fr.velogo.app.ui.segments.segmentFacts
import fr.velogo.app.ui.segments.suggestedSegmentName
import fr.velogo.core.ride.AscentAccumulator
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.util.Dates
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import fr.velogo.core.geo.LatLon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Détail d'une sortie : carte, chiffres, graphiques d'altitude et de vitesse, export GPX. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(rideId: Long, onBack: () -> Unit, onOpenSegment: (Long) -> Unit = {}) {
    val vm = veloViewModel(key = "sortie-$rideId") { RideDetailViewModel(it, rideId) }
    val ride by vm.ride.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val segmentRows by vm.segments.collectAsStateWithLifecycle()
    val bestRows by vm.bests.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }

    // Création d'un segment : le morceau choisi (en kilomètres depuis le départ) et son nom.
    var editing by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(0f..0f) }
    // Tant que l'utilisateur n'a rien tapé, le nom proposé suit le morceau choisi (« Côte de 3,5 km »).
    var typedName by remember { mutableStateOf<String?>(null) }
    var segmentError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }
    val startSegment = {
        // Par défaut, le tiers central de la sortie : on voit tout de suite les deux poignées.
        val totalKm = ((track?.profile?.lastOrNull()?.distanceM ?: 0.0) / 1000.0).toFloat()
        range = (totalKm / 3f)..(totalKm * 2f / 3f)
        typedName = null
        segmentError = null
        editing = true
    }

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
                            text = { Text("Créer un segment…") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_timer), contentDescription = null) },
                            enabled = (track?.profile?.size ?: 0) >= 2,
                            onClick = {
                                menuOpen = false
                                startSegment()
                            },
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
        // Le morceau choisi pour le segment, montré sur la carte avec ses deux bouts. La carte n'est redessinée
        // que lorsque le doigt se pose : la suivre à chaque image pendant le glissement la ferait saccader.
        var portion by remember { mutableStateOf<List<LatLon>>(emptyList()) }
        LaunchedEffect(editing, range, data) {
            if (!editing) {
                portion = emptyList()
            } else {
                delay(MAP_HIGHLIGHT_DELAY_MS)
                portion = vm.portion(range.start * 1000.0, range.endInclusive * 1000.0)
            }
        }
        val ends = remember(portion) {
            if (portion.size >= 2) listOf(PlanMarker(portion.first(), PlanMarker.Kind.START), PlanMarker(portion.last(), PlanMarker.Kind.END)) else emptyList()
        }
        val editor: (@Composable (Modifier) -> Unit)? = if (editing && data != null) { modifier ->
            val measures = remember(range, data) { portionMeasures(data, range) }
            val segmentName = typedName ?: suggestedSegmentName(measures.lengthM, measures.gradePct)
            SegmentEditor(
                altitude = data.altitude,
                range = range,
                totalKm = ((data.profile.lastOrNull()?.distanceM ?: 0.0) / 1000.0).toFloat(),
                name = segmentName,
                facts = segmentFacts(measures.lengthM, measures.ascentM, measures.gradePct),
                error = segmentError,
                onRange = { range = it; segmentError = null },
                onName = { typedName = it },
                onCancel = { editing = false },
                onCreate = {
                    if (!creating) {
                        creating = true
                        vm.createSegment(segmentName, range.start * 1000.0, range.endInclusive * 1000.0) { id ->
                            creating = false
                            if (id == null) {
                                segmentError = "Ce morceau est trop court : un segment fait au moins 200 m."
                            } else {
                                editing = false
                                onOpenSegment(id)
                            }
                        }
                    }
                },
                modifier = modifier,
            )
        } else null
        RideDetailBody(
            ride = ride,
            data = data,
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.padding(inner),
            segments = segmentRows,
            bests = bestRows,
            onOpenSegment = onOpenSegment,
            onCreateSegment = startSegment,
            editor = editor,
        ) { modifier -> TrackMap(data?.segments, data?.bounds, modifier, marker = marker, highlight = portion, planMarkers = ends) }
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
    /** Les passages de cette sortie sur les segments personnels. */
    segments: List<RideSegmentRow> = emptyList(),
    /** Les distances sur lesquelles cette sortie détient le record : (« 10 km », « 21:34 »). */
    bests: List<Pair<String, String>> = emptyList(),
    onOpenSegment: (Long) -> Unit = {},
    /** Ouvre la création d'un segment à partir de cette sortie ; sans lui, le bouton n'est pas proposé. */
    onCreateSegment: (() -> Unit)? = null,
    /** Création d'un segment : quand il est là, il remplace les chiffres et les graphiques sous la carte. */
    editor: (@Composable (Modifier) -> Unit)? = null,
    map: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val details: @Composable (Modifier) -> Unit = editor ?: { detailsModifier ->
            Column(
                detailsModifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (ride != null) {
                    Header(ride)
                    StatsGrid(ride)
                }
                if (segments.isNotEmpty() || bests.isNotEmpty()) Highlights(segments, bests, onOpenSegment)
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
                    if (onCreateSegment != null) {
                        FilledTonalButton(onClick = onCreateSegment, modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(R.drawable.ic_timer), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Créer un segment à partir de cette sortie")
                        }
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
            // Pendant la création d'un segment, la carte cède un peu de place : tous les réglages et le bouton
            // « Créer » tiennent à l'écran sans défiler.
            val mapShare = if (editor != null) 0.3f else 0.4f
            Column(Modifier.fillMaxSize()) {
                map(Modifier.fillMaxWidth().weight(mapShare))
                details(Modifier.fillMaxWidth().weight(1f - mapShare))
            }
        }
    }
}

/** Longueur, dénivelé et pente moyenne d'un morceau de sortie. */
private class PortionMeasures(val lengthM: Double, val ascentM: Double, val gradePct: Double?)

/** Mesures du morceau de sortie choisi pour un segment, d'après le profil affiché. */
private fun portionMeasures(data: RideTrackData, range: ClosedFloatingPointRange<Float>): PortionMeasures {
    val fromM = range.start * 1000.0
    val toM = range.endInclusive * 1000.0
    val altitudes = data.profile.filter { it.distanceM in fromM..toM }.mapNotNull { it.altitudeM }
    val accumulator = AscentAccumulator(PORTION_ASCENT_THRESHOLD_M)
    altitudes.forEach { accumulator.add(it) }
    val length = toM - fromM
    val grade = if (altitudes.size >= 2 && length > 0.0) (altitudes.last() - altitudes.first()) / length * 100.0 else null
    return PortionMeasures(length, accumulator.ascentM, grade)
}

private const val PORTION_ASCENT_THRESHOLD_M = 3.0
private const val MAP_HIGHLIGHT_DELAY_MS = 120L

/** Ce que cette sortie a de remarquable : ses records sur les distances de référence, ses temps sur les segments. */
@Composable
private fun Highlights(segments: List<RideSegmentRow>, bests: List<Pair<String, String>>, onOpenSegment: (Long) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(
                if (segments.isEmpty()) "Records" else if (bests.isEmpty()) "Segments" else "Records et segments",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
            )
            for ((distance, time) in bests) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_emoji_events), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Ton meilleur temps sur $distance", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(time, style = NumberStyle.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            for (row in segments) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Voir le segment") { onOpenSegment(row.segmentId) }.padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (row.rank == 1) R.drawable.ic_emoji_events else R.drawable.ic_timer),
                        contentDescription = null,
                        tint = if (row.rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (row.total > 1) "${rankLabel(row.rank)} sur ${row.total} passages" else "Premier passage",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(Formats.duration(row.elapsedMs), style = NumberStyle.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface)
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
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
