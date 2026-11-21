package fr.velogo.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.R
import fr.velogo.app.data.db.SavedRouteEntity
import fr.velogo.app.ui.components.TrackThumbnail
import fr.velogo.app.ui.nav.RouteShareSheet
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.util.Dates
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Un trajet enregistré, tel que la liste le montre. */
data class SavedRouteRow(
    val id: Long,
    val name: String,
    /** « 42,5 km » */
    val distance: String,
    /** « 2 h 10 · ↗ 680 m » */
    val facts: String,
    /** « Enregistré le 3 novembre · VTC » */
    val detail: String,
    /** Dessin du tracé (trace encodée), ou null tant qu'il n'est pas prêt. */
    val preview: String?,
)

/** Met en forme la liste des trajets enregistrés, dans l'ordre reçu (les plus récents d'abord). */
fun savedRouteRows(
    routes: List<SavedRouteEntity>,
    previews: Map<Long, String>,
    today: LocalDate = LocalDate.now(),
): List<SavedRouteRow> = routes.map { route ->
    SavedRouteRow(
        id = route.id,
        name = route.name,
        distance = Formats.distanceAuto(route.distanceM),
        facts = "${Formats.durationWords((route.durationS * 1000).toLong())} · ↗ ${Formats.meters(route.ascentM)} m",
        // La date d'abord : si la place manque, c'est le type de vélo, moins utile, qui est coupé.
        detail = "Enregistré ${Dates.relativeDayShort(route.createdAtMs, today)} · ${bikeLabel(route.bike)}",
        preview = previews[route.id],
    )
}

/** Les mêmes mots que dans le choix du vélo, en plus court. */
private fun bikeLabel(name: String): String = when (name) {
    BikeType.ROAD.name -> "Route"
    BikeType.MTB.name -> "VTT"
    else -> "VTC"
}

class SavedRoutesViewModel(private val container: AppContainer) : ViewModel() {
    /** null tant que la liste n'est pas chargée. */
    val routes: StateFlow<List<SavedRouteEntity>?> = container.savedRoutes.observe()
        .map<List<SavedRouteEntity>, List<SavedRouteEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _previews = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** Les dessins des tracés, préparés un à un en tâche de fond (la liste ne charge pas les tracés eux-mêmes). */
    val previews: StateFlow<Map<Long, String>> = _previews

    init {
        viewModelScope.launch {
            container.savedRoutes.observe().collect { list ->
                for (route in list) {
                    if (route.id in _previews.value) continue
                    val preview = runCatching { container.savedRoutes.preview(route.id) }.getOrNull() ?: continue
                    _previews.update { it + (route.id to preview) }
                }
            }
        }
    }

    /** Un guidage est en cours : ouvrir un autre trajet l'arrêterait, on demande donc avant. */
    val guiding: Boolean get() = container.navigation.guidance.value != null

    /**
     * Rouvre un trajet sur la carte, prêt à être suivi ; le guidage en cours, s'il y en a un, s'arrête (la carte
     * ne montre qu'un itinéraire à la fois). [onDone] dit si le trajet a pu être relu.
     */
    fun open(id: Long, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val loaded = runCatching { container.savedRoutes.load(id) }.getOrNull()
            if (loaded != null) {
                val bike = BikeType.entries.firstOrNull { it.name == loaded.first.bike } ?: BikeType.HYBRID
                if (guiding) container.navigation.stopGuidance()
                container.navigation.showSaved(loaded.first.name, loaded.second, bike)
            }
            onDone(loaded != null)
        }
    }

    /** L'itinéraire complet d'un trajet (pour son affiche), ou null s'il n'est plus lisible. */
    suspend fun route(id: Long): Route? = runCatching { container.savedRoutes.load(id) }.getOrNull()?.second

    fun rename(id: Long, name: String) = viewModelScope.launch { container.savedRoutes.rename(id, name) }

    fun delete(id: Long) = viewModelScope.launch { container.savedRoutes.delete(id) }

    fun newRoute() = container.navigation.openPlanner()
}

/**
 * L'onglet « Trajets » : les itinéraires enregistrés, à portée de main. Un appui en rouvre un sur la carte, prêt à
 * être suivi ; le menu de chaque ligne permet de le renommer, d'en partager l'affiche ou de le supprimer.
 */
@Composable
fun SavedRoutesScreen(onShowMap: () -> Unit, contentPadding: PaddingValues) {
    val vm = veloViewModel { SavedRoutesViewModel(it) }
    val routes by vm.routes.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var unreadable by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<Pair<SavedRouteRow, Route>?>(null) }
    var replacingGuidance by remember { mutableStateOf<Long?>(null) }
    val open: (Long) -> Unit = { id -> vm.open(id) { ok -> if (ok) onShowMap() else unreadable = true } }

    SavedRoutesContent(
        rows = routes?.let { remember(it, previews) { savedRouteRows(it, previews) } },
        onOpen = { id -> if (vm.guiding) replacingGuidance = id else open(id) },
        onRename = { id, name -> vm.rename(id, name) },
        onShare = { row ->
            scope.launch {
                val route = vm.route(row.id)
                if (route == null) unreadable = true else sharing = row to route
            }
        },
        onDelete = { id -> vm.delete(id) },
        onNew = {
            vm.newRoute()
            onShowMap()
        },
        contentPadding = contentPadding,
    )

    sharing?.let { (row, route) ->
        RouteShareSheet(route, initialTitle = row.name, initialSubtitle = "", onDismiss = { sharing = null })
    }

    replacingGuidance?.let { id ->
        AlertDialog(
            onDismissRequest = { replacingGuidance = null },
            title = { Text("Un guidage est en cours") },
            text = { Text("Ouvrir ce trajet arrête le guidage en cours. Si une sortie est en cours d'enregistrement, elle continue.") },
            confirmButton = {
                TextButton(onClick = {
                    replacingGuidance = null
                    open(id)
                }) { Text("Ouvrir le trajet") }
            },
            dismissButton = { TextButton(onClick = { replacingGuidance = null }) { Text("Annuler") } },
        )
    }

    if (unreadable) {
        AlertDialog(
            onDismissRequest = { unreadable = false },
            title = { Text("Trajet illisible") },
            text = { Text("Ce trajet n'a pas pu être relu. Tu peux le supprimer et l'enregistrer de nouveau.") },
            confirmButton = { TextButton(onClick = { unreadable = false }) { Text("Compris") } },
        )
    }
}

/** Le contenu de l'onglet, sans accès aux données : c'est lui qu'on vérifie en image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesContent(
    /** null tant que la liste n'est pas chargée. */
    rows: List<SavedRouteRow>?,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onShare: (SavedRouteRow) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
    contentPadding: PaddingValues,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var renaming by remember { mutableStateOf<SavedRouteRow?>(null) }
    var deleting by remember { mutableStateOf<SavedRouteRow?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Mes trajets") },
                actions = {
                    if (!rows.isNullOrEmpty()) {
                        TextButton(onClick = onNew) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Nouveau")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        when {
            rows == null -> Box(Modifier.fillMaxSize())
            rows.isEmpty() -> EmptyRoutes(onNew, Modifier.padding(inner).padding(contentPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "aide") {
                    Text(
                        "Touche un trajet pour le rouvrir sur la carte, prêt à être suivi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                items(rows, key = { it.id }) { row ->
                    RouteCard(
                        row = row,
                        onOpen = { onOpen(row.id) },
                        onRename = { renaming = row },
                        onShare = { onShare(row) },
                        onDelete = { deleting = row },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    renaming?.let { row ->
        var name by remember(row.id) { mutableStateOf(row.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer le trajet") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Nom") }) },
            confirmButton = {
                TextButton(onClick = {
                    onRename(row.id, name)
                    renaming = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } },
        )
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer ce trajet ?") },
            text = { Text("« ${row.name} » sera retiré de tes trajets enregistrés. Tes sorties ne sont pas touchées.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(row.id)
                        deleting = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun RouteCard(
    row: SavedRouteRow,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Rouvrir sur la carte", onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackThumbnail(
                encodedPreview = row.preview,
                color = LocalRideColors.current.route,
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                startColor = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(row.distance, style = NumberStyle.copy(fontSize = 24.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        row.facts,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Text(
                    row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Actions pour ${row.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Partager") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Renommer") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRoutes(onNew: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text("Aucun trajet enregistré", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Calcule un itinéraire, ouvre ses « Détails », puis touche « Enregistrer cet itinéraire » : tu le retrouveras ici, " +
                "prêt à être suivi, même sans réseau.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = onNew) {
            Icon(painterResource(R.drawable.ic_route), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Préparer un itinéraire")
        }
    }
}
