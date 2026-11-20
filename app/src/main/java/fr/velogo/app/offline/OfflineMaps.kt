package fr.velogo.app.offline

import android.content.Context
import fr.velogo.app.BuildConfig
import fr.velogo.app.data.SettingsRepository
import fr.velogo.core.format.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Ce qu'on sait de la liste des cartes téléchargeables. */
data class CatalogState(
    val catalog: Catalog? = null,
    val loading: Boolean = false,
    /** La dernière tentative de mise à jour de la liste a échoué (pas de réseau, par exemple). */
    val failed: Boolean = false,
)

/**
 * Les cartes hors ligne vues par le reste de l'appli : la liste de ce qui existe (le catalogue publié avec les
 * cartes), ce qui est installé ([store]) et ce qui est en cours de téléchargement ([downloader]).
 */
class OfflineMaps(
    context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val store = OfflineStore(context, scope)
    val downloader = PackDownloader(context, store)

    private val cache = File(context.filesDir, "catalogue.json")
    private var cacheRead = false
    private var lastRefreshMs = 0L

    private val _catalog = MutableStateFlow(CatalogState())
    val catalog: StateFlow<CatalogState> = _catalog

    /** Au démarrage de l'appli : ouvre les cartes installées et reprend le suivi des téléchargements. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            store.reload()
            runCatching { downloader.sync() }
        }
    }

    /**
     * Récupère la liste des cartes. La copie gardée sur le téléphone est affichée tout de suite ; elle est
     * rafraîchie en ligne si elle date un peu, ou si [force] est vrai.
     */
    suspend fun refreshCatalog(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!cacheRead) {
            cacheRead = true
            val cached = runCatching { Catalog.parse(cache.readText()) }.getOrNull()
            if (cached != null) _catalog.value = _catalog.value.copy(catalog = cached)
        }
        val now = System.currentTimeMillis()
        if (!force && _catalog.value.catalog != null && now - lastRefreshMs < REFRESH_PERIOD_MS) return@withContext
        _catalog.value = _catalog.value.copy(loading = true, failed = false)
        val fresh = runCatching {
            val request = Request.Builder().url(catalogUrl()).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Catalogue : HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                Catalog.parse(text).also { runCatching { cache.writeText(text) } }
            }
        }.getOrNull()
        if (fresh != null) lastRefreshMs = now
        _catalog.value = CatalogState(catalog = fresh ?: _catalog.value.catalog, loading = false, failed = fresh == null)
    }

    /** Fichiers d'une région qu'il faut (re)télécharger : absents, ou dont une version plus récente existe. */
    fun missingFiles(region: RegionPack): List<PackFile> {
        val installed = store.installed.value
        return region.files.filter { installed[it.name]?.sha256 != it.sha256 }
    }

    /**
     * Lance le téléchargement d'une région (avec la vue d'ensemble de la France si elle manque).
     * Renvoie null si tout est parti, sinon l'explication à montrer.
     */
    suspend fun download(regionId: String): String? {
        val catalog = _catalog.value.catalog ?: return "La liste des cartes n'est pas disponible."
        val region = catalog.region(regionId) ?: return "Cette région n'est plus proposée : actualise la liste."
        val current = settings.settings.first()
        val directory = store.directory(current.offlineStorage)
            ?: return "Aucun stockage disponible pour les cartes."
        val installed = store.installed.value
        val wanted = (missingFiles(region) + catalog.france.filter { installed[it.name]?.sha256 != it.sha256 })
            .filterNot { downloader.isActive(it.name) }
        if (wanted.isEmpty()) return null
        val needed = wanted.sumOf { it.size } + SAFETY_MARGIN_BYTES
        if (store.freeBytes(directory) < needed) {
            return "Pas assez de place : il faut ${Formats.fileSize(needed)} libres."
        }
        val names = catalog.regions.associate { it.id to it.name }
        val started = downloader.enqueue(wanted, directory, current.offlineWifiOnly) { file ->
            val place = if (file in region.files) names[region.id] ?: region.id else "France"
            "VeloGo · $place · " + when (file.type) {
                PackFile.BASE -> "fond de carte"
                PackFile.BIKE -> "réseau cyclable"
                PackFile.RELIEF -> "relief"
                else -> "données"
            }
        }
        return if (started) null else "Le téléchargement n'a pas pu démarrer sur ce téléphone."
    }

    suspend fun cancel(regionId: String) {
        val region = _catalog.value.catalog?.region(regionId) ?: return
        downloader.cancel(region.files.map { it.name })
    }

    /** Supprime une région. La vue d'ensemble de la France part avec la dernière région. */
    suspend fun delete(regionId: String) = withContext(Dispatchers.IO) {
        val catalog = _catalog.value.catalog
        val names = catalog?.region(regionId)?.files?.map { it.name }
            ?: listOf(PackFile.BASE, PackFile.BIKE, PackFile.RELIEF).map { "$it-$regionId.pmtiles" }
        downloader.cancel(names)
        store.delete(names)
        val remaining = store.installed.value.keys.filter { it.endsWith(".pmtiles") && !it.endsWith("-$FRANCE.pmtiles") }
        if (remaining.isEmpty()) {
            val overview = store.installed.value.keys.filter { it.endsWith("-$FRANCE.pmtiles") }
            if (overview.isNotEmpty()) {
                downloader.cancel(overview)
                store.delete(overview)
            }
        }
    }

    private fun catalogUrl(): String =
        "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/${PackDownloader.RELEASE_TAG}/catalogue.json"

    companion object {
        const val FRANCE = "france"
        private const val REFRESH_PERIOD_MS = 6 * 60 * 60 * 1000L
        private const val SAFETY_MARGIN_BYTES = 150_000_000L
    }
}
