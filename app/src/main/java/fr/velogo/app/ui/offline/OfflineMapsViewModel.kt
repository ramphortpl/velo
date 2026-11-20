package fr.velogo.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.data.Settings
import fr.velogo.app.map.MapLayers
import fr.velogo.app.offline.CatalogState
import fr.velogo.app.offline.DownloadProgress
import fr.velogo.app.offline.InstalledFile
import fr.velogo.app.offline.RegionPack
import fr.velogo.app.offline.StorageChoice
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RegionStatus { AVAILABLE, QUEUED, DOWNLOADING, WAITING, VERIFYING, INSTALLED, UPDATE_AVAILABLE, FAILED }

/** Une région telle que l'écran la montre. */
data class RegionRow(
    val id: String,
    val name: String,
    /** Taille de la région complète. */
    val sizeBytes: Long,
    val status: RegionStatus,
    val downloadedBytes: Long = 0,
    /** Ce qu'il reste à télécharger pour une mise à jour. */
    val updateBytes: Long = 0,
    val message: String? = null,
)

data class OfflineMapsUiState(
    val regions: List<RegionRow> = emptyList(),
    /** Date des données, déjà mise en forme (« 20/11/2026 »). */
    val catalogDate: String? = null,
    val loading: Boolean = false,
    val catalogFailed: Boolean = false,
    val usedBytes: Long = 0,
    val storages: List<StorageChoice> = emptyList(),
    val selectedStorage: Int = 0,
    val wifiOnly: Boolean = true,
    /** La carte hors ligne est le fond de carte actuellement choisi. */
    val offlineLayerActive: Boolean = false,
) {
    val anyInstalled: Boolean get() = regions.any { it.status == RegionStatus.INSTALLED || it.status == RegionStatus.UPDATE_AVAILABLE }
}

/** Ce que l'écran des cartes hors ligne peut demander. */
interface OfflineMapsActions {
    fun refresh()
    fun download(regionId: String)
    fun cancel(regionId: String)
    fun delete(regionId: String)
    fun setWifiOnly(value: Boolean)
    fun setStorage(index: Int)
    fun useOfflineLayer()
}

class OfflineMapsViewModel(private val container: AppContainer) : ViewModel(), OfflineMapsActions {
    private val offline = container.offline
    private val storages = MutableStateFlow(offline.store.storages())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Messages ponctuels à montrer en bas de l'écran. */
    val messages: SharedFlow<String> = _messages

    val state: StateFlow<OfflineMapsUiState> = combine(
        offline.catalog, offline.store.installed, offline.downloader.progress, container.settings.settings, storages,
    ) { catalog, installed, progress, settings, places -> buildState(catalog, installed, progress, settings, places) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineMapsUiState(loading = true))

    init {
        viewModelScope.launch { offline.refreshCatalog() }
    }

    /** Suit les téléchargements tant que l'écran est affiché (appelé par l'écran, annulé quand il disparaît). */
    suspend fun watchDownloads() {
        while (currentCoroutineContext().isActive) {
            val active = runCatching { offline.downloader.sync() }.getOrDefault(false)
            storages.value = offline.store.storages()
            delay(if (active) ACTIVE_PERIOD_MS else IDLE_PERIOD_MS)
        }
    }

    override fun refresh() {
        viewModelScope.launch {
            offline.refreshCatalog(force = true)
            if (offline.catalog.value.failed) _messages.tryEmit("Impossible d'actualiser la liste : pas de réseau ?")
        }
    }

    override fun download(regionId: String) {
        viewModelScope.launch {
            // La liste peut dater : on la rafraîchit pour télécharger la version en ligne, pas une version disparue.
            offline.refreshCatalog(force = true)
            offline.download(regionId)?.let { _messages.tryEmit(it) }
        }
    }

    override fun cancel(regionId: String) {
        viewModelScope.launch { offline.cancel(regionId) }
    }

    override fun delete(regionId: String) {
        viewModelScope.launch { offline.delete(regionId) }
    }

    override fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { container.settings.setOfflineWifiOnly(value) }
    }

    override fun setStorage(index: Int) {
        viewModelScope.launch { container.settings.setOfflineStorage(index) }
    }

    override fun useOfflineLayer() {
        viewModelScope.launch { container.settings.setMapLayer(MapLayers.OFFLINE.id) }
    }

    private companion object {
        const val ACTIVE_PERIOD_MS = 1_000L
        const val IDLE_PERIOD_MS = 4_000L
    }
}

internal fun buildState(
    catalog: CatalogState,
    installed: Map<String, InstalledFile>,
    progress: Map<String, DownloadProgress>,
    settings: Settings,
    storages: List<StorageChoice>,
): OfflineMapsUiState = OfflineMapsUiState(
    regions = catalog.catalog?.regions.orEmpty().map { regionRow(it, installed, progress) },
    catalogDate = catalog.catalog?.date?.let { formatDate(it) },
    loading = catalog.loading,
    catalogFailed = catalog.failed,
    usedBytes = installed.values.sumOf { it.size },
    storages = storages,
    selectedStorage = storages.firstOrNull { it.index == settings.offlineStorage }?.index ?: storages.firstOrNull()?.index ?: 0,
    wifiOnly = settings.offlineWifiOnly,
    offlineLayerActive = settings.mapLayer == MapLayers.OFFLINE.id,
)

/** Résume l'état des fichiers d'une région (installés, en cours, en échec) en une seule ligne d'écran. */
internal fun regionRow(region: RegionPack, installed: Map<String, InstalledFile>, progress: Map<String, DownloadProgress>): RegionRow {
    val files = region.files
    val upToDate = files.filter { installed[it.name]?.sha256 == it.sha256 }
    val present = files.count { installed[it.name] != null }
    val ongoing = files.mapNotNull { progress[it.name] }
    val active = ongoing.filter { it.status != DownloadProgress.Status.FAILED }
    val failed = ongoing.firstOrNull { it.status == DownloadProgress.Status.FAILED }
    val missingBytes = files.filter { installed[it.name]?.sha256 != it.sha256 }.sumOf { it.size }

    fun row(status: RegionStatus, downloaded: Long = 0, message: String? = null) =
        RegionRow(region.id, region.name, region.size, status, downloaded, missingBytes, message)

    return when {
        active.isNotEmpty() -> {
            val downloaded = upToDate.sumOf { it.size } + active.sumOf { it.downloadedBytes }
            val status = when {
                active.any { it.status == DownloadProgress.Status.RUNNING } -> RegionStatus.DOWNLOADING
                active.all { it.status == DownloadProgress.Status.VERIFYING } -> RegionStatus.VERIFYING
                active.any { it.status == DownloadProgress.Status.WAITING } -> RegionStatus.WAITING
                active.any { it.downloadedBytes > 0 } -> RegionStatus.DOWNLOADING
                else -> RegionStatus.QUEUED
            }
            row(status, downloaded, active.firstNotNullOfOrNull { it.message })
        }
        failed != null -> row(RegionStatus.FAILED, message = failed.message)
        upToDate.size == files.size -> row(RegionStatus.INSTALLED)
        present > 0 -> row(RegionStatus.UPDATE_AVAILABLE)
        else -> row(RegionStatus.AVAILABLE)
    }
}

/** « 2026-11-20 » -> « 20/11/2026 ». */
private fun formatDate(iso: String): String {
    val parts = iso.split('-')
    return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else iso
}
