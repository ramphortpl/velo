package fr.velogo.app.ui.segments

import fr.velogo.app.ui.components.VeloCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.R
import fr.velogo.app.data.db.SegmentEntity
import fr.velogo.app.ui.components.TrackMap
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.util.Dates
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Polyline
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SegmentDetailViewModel(private val container: AppContainer, private val segmentId: Long) : ViewModel() {
    val segment: StateFlow<SegmentEntity?> =
        container.segments.observeSegment(segmentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Les passages classés ; null tant qu'ils ne sont pas chargés. */
    val efforts: StateFlow<List<EffortRow>?> =
        combine(container.segments.observeSegment(segmentId), container.segments.observeEffortsOfSegment(segmentId), container.rides.observeRides()) { segment, efforts, rides ->
            if (segment == null) emptyList() else effortRows(segment, efforts, rides)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Vrai pendant que le segment est recherché dans les sorties déjà enregistrées. */
    val searching: StateFlow<Boolean> =
        container.analysis.matching.map { segmentId in it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun rename(name: String) = viewModelScope.launch { container.segments.rename(segmentId, name) }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        container.segments.delete(segmentId)
        onDeleted()
    }
}

/** Un segment : son tracé sur la carte, ses mesures et tous les passages, du plus rapide au plus lent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentDetailScreen(segmentId: Long, onBack: () -> Unit, onOpenRide: (Long) -> Unit) {
    val vm = veloViewModel(key = "segment-$segmentId") { SegmentDetailViewModel(it, segmentId) }
    val segment by vm.segment.collectAsStateWithLifecycle()
    val efforts by vm.efforts.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(segment?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Plus d'actions") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Renommer") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                            onClick = { menuOpen = false; renaming = true },
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
        val current = segment
        // Le tracé n'est décodé qu'une fois : la carte ne se recadre pas à chaque passage qui s'ajoute à la liste.
        val line = remember(current?.points) { current?.let { Polyline.decode(it.points) } }
        val lines = remember(line) { line?.let { listOf(it) } }
        val bounds = remember(line) { line?.let { BoundingBox.of(it) } }
        SegmentDetailBody(current, efforts, searching, onOpenRide, Modifier.padding(inner)) { modifier ->
            TrackMap(lines, bounds, modifier)
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(segment?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renommer le segment") },
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
            title = { Text("Supprimer ce segment ?") },
            text = { Text("Le segment et ses temps seront effacés. Tes sorties, elles, ne sont pas touchées.") },
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

/** Corps de l'écran : la carte (fournie par [map]) puis les mesures et les passages. Vérifiable en image. */
@Composable
fun SegmentDetailBody(
    segment: SegmentEntity?,
    efforts: List<EffortRow>?,
    searching: Boolean,
    onOpenRide: (Long) -> Unit,
    modifier: Modifier = Modifier,
    map: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        map(Modifier.fillMaxWidth().weight(0.36f))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.64f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (segment != null) {
                Text(segment.facts(), style = MaterialTheme.typography.titleLarge)
                if (efforts != null) {
                    Summary(efforts)
                    EffortList(efforts, searching, onOpenRide)
                }
            }
        }
    }
}

@Composable
private fun Summary(efforts: List<EffortRow>) {
    val best = efforts.firstOrNull()
    VeloCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("RECORD", style = LabelCapsStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(best?.let { Formats.duration(it.elapsedMs) } ?: Formats.NO_VALUE, style = NumberStyle.copy(fontSize = 38.sp), color = MaterialTheme.colorScheme.primary)
                if (best != null) {
                    Text("${Formats.speedKmh(best.speedMps)} km/h", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("PASSAGES", style = LabelCapsStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(efforts.size.toString(), style = NumberStyle.copy(fontSize = 38.sp), color = MaterialTheme.colorScheme.onSurface)
                // Le dernier passage en date, pour se situer par rapport au record.
                efforts.maxByOrNull { it.startTimeMs }?.takeIf { efforts.size > 1 }?.let { last ->
                    Text("Dernier : ${Formats.duration(last.elapsedMs)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EffortList(efforts: List<EffortRow>, searching: Boolean, onOpenRide: (Long) -> Unit) {
    if (efforts.isEmpty()) {
        Text(
            if (searching) "Recherche de tes passages dans les sorties déjà enregistrées…"
            else "Aucun passage dans tes sorties pour l'instant. Le segment sera chronométré à chaque sortie qui l'emprunte en entier, dans ce sens.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    VeloCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            efforts.forEachIndexed { index, effort ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Voir la sortie") { onOpenRide(effort.rideId) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Rank(effort.rank)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(effort.rideName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Dates.longDay(effort.startTimeMs)} · ${Formats.speedKmh(effort.speedMps)} km/h",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(Formats.duration(effort.elapsedMs), style = NumberStyle.copy(fontSize = 26.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
        }
    }
    if (searching) {
        Text("Recherche dans les autres sorties…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Le rang d'un passage : une coupe pour le record, un numéro pour les autres. */
@Composable
private fun Rank(rank: Int) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(if (rank == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (rank == 1) {
            Icon(painterResource(R.drawable.ic_emoji_events), contentDescription = "Record", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        } else {
            Text(rank.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
