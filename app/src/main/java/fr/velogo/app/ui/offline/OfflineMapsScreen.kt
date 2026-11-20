package fr.velogo.app.ui.offline

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.velogo.app.R
import fr.velogo.app.ui.ride.brandSegmentColors
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats

/** Gestion des cartes hors ligne : télécharger, mettre à jour ou supprimer des régions. */
@Composable
fun OfflineMapsScreen(onBack: () -> Unit) {
    val vm = veloViewModel { OfflineMapsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // L'avancement n'est suivi que lorsque l'écran est réellement visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watchDownloads() }
    }

    OfflineMapsContent(state = state, actions = vm, snackbar = snackbar, onBack = onBack)
}

/** Le contenu de l'écran, sans accès aux données : c'est lui qu'on vérifie en image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsContent(
    state: OfflineMapsUiState,
    actions: OfflineMapsActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var confirmDelete by remember { mutableStateOf<RegionRow?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Cartes hors ligne") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = actions::refresh, enabled = !state.loading) {
                        Icon(painterResource(R.drawable.ic_replay), contentDescription = "Actualiser la liste")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Télécharge tes régions en Wi-Fi, puis roule sans réseau : carte vélo détaillée (pistes, revêtements, " +
                    "véloroutes, cols, points d'eau), relief, altitude et pente précises.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            if (state.anyInstalled && !state.offlineLayerActive) {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("La carte hors ligne est prête", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "Choisis-la quand tu veux avec le bouton des fonds de carte, ou tout de suite :",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = actions::useOfflineLayer) { Text("Utiliser la carte hors ligne") }
                    }
                }
            }

            Panel("Régions", state.catalogDate?.let { "Données OpenStreetMap du $it" }) {
                when {
                    state.regions.isNotEmpty() -> state.regions.forEachIndexed { index, region ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        RegionItem(region, actions, onDelete = { confirmDelete = region })
                    }
                    state.loading -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Liste des cartes indisponible", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Il faut du réseau pour la récupérer une première fois. Vérifie ta connexion puis réessaie.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = actions::refresh) { Text("Réessayer") }
                    }
                }
            }

            Panel("Téléchargement") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) { actions.setWifiOnly(!state.wifiOnly) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi uniquement", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Une région pèse plusieurs centaines de Mo : mieux vaut épargner ton forfait.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = state.wifiOnly, onCheckedChange = null)
                }
                if (state.storages.size > 1) {
                    Text("Emplacement des prochains téléchargements", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        state.storages.forEachIndexed { index, storage ->
                            SegmentedButton(
                                selected = state.selectedStorage == storage.index,
                                onClick = { actions.setStorage(storage.index) },
                                shape = SegmentedButtonDefaults.itemShape(index, state.storages.size),
                                colors = brandSegmentColors(),
                            ) { Text(storage.label) }
                        }
                    }
                }
                val place = state.storages.firstOrNull { it.index == state.selectedStorage }
                Text(
                    buildString {
                        append("Cartes installées : ${Formats.fileSize(state.usedBytes)}")
                        if (place != null) append(" · Place libre : ${Formats.fileSize(place.freeBytes)}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Text(
                "Données © les contributeurs d'OpenStreetMap (ODbL). Fond de carte Protomaps, relief Mapterhorn " +
                    "(LiDAR HD de l'IGN, Licence Ouverte). Les cartes sont régénérées chaque mois.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    confirmDelete?.let { region ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer « ${region.name} » ?") },
            text = { Text("La carte de cette région sera effacée du téléphone. Tu pourras la retélécharger quand tu veux.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.delete(region.id)
                    confirmDelete = null
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun RegionItem(region: RegionRow, actions: OfflineMapsActions, onDelete: () -> Unit) {
    val busy = region.status in setOf(RegionStatus.QUEUED, RegionStatus.DOWNLOADING, RegionStatus.WAITING, RegionStatus.VERIFYING)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (region.status == RegionStatus.INSTALLED) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(region.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (region.status) {
                        RegionStatus.AVAILABLE -> Formats.fileSize(region.sizeBytes)
                        RegionStatus.QUEUED -> "Le téléchargement va démarrer…"
                        RegionStatus.DOWNLOADING -> "${Formats.fileSize(region.downloadedBytes)} sur ${Formats.fileSize(region.sizeBytes)}"
                        RegionStatus.WAITING -> region.message ?: "En pause"
                        RegionStatus.VERIFYING -> "Vérification du fichier…"
                        RegionStatus.INSTALLED -> "Installée · ${Formats.fileSize(region.sizeBytes)}"
                        RegionStatus.UPDATE_AVAILABLE -> "Mise à jour disponible · ${Formats.fileSize(region.updateBytes)}"
                        RegionStatus.FAILED -> region.message ?: "Le téléchargement a échoué."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (region.status == RegionStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (region.status) {
                RegionStatus.AVAILABLE -> FilledTonalButton(
                    onClick = { actions.download(region.id) },
                    modifier = Modifier.semantics { contentDescription = "Télécharger ${region.name}" },
                ) { Text("Télécharger") }
                RegionStatus.FAILED -> FilledTonalButton(onClick = { actions.download(region.id) }) { Text("Réessayer") }
                RegionStatus.UPDATE_AVAILABLE -> {
                    FilledTonalButton(onClick = { actions.download(region.id) }) { Text("Mettre à jour") }
                    DeleteButton(region, onDelete)
                }
                RegionStatus.INSTALLED -> DeleteButton(region, onDelete)
                RegionStatus.VERIFYING -> Unit
                else -> OutlinedButton(
                    onClick = { actions.cancel(region.id) },
                    modifier = Modifier.semantics { contentDescription = "Annuler ${region.name}" },
                ) { Text("Annuler") }
            }
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            // En cours ou en pause : la part déjà reçue. Sinon (démarrage, vérification) : barre d'attente animée.
            val measurable = region.status == RegionStatus.DOWNLOADING || region.status == RegionStatus.WAITING
            if (measurable && region.sizeBytes > 0) {
                LinearProgressIndicator(
                    progress = { (region.downloadedBytes.toFloat() / region.sizeBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DeleteButton(region: RegionRow, onDelete: () -> Unit) {
    IconButton(onClick = onDelete) {
        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Supprimer ${region.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Panel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { content() }
        }
    }
}
