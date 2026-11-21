package fr.velogo.app.offline

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import fr.velogo.core.tiles.PmTilesReader
import fr.velogo.core.tiles.TileSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Les cartes installées, ouvertes et prêtes à fournir des tuiles. [epoch] change à chaque installation ou suppression. */
class OfflineTiles(val base: TileSet, val bike: TileSet, val relief: TileSet, val epoch: Int) {
    val isEmpty: Boolean get() = base.isEmpty

    fun close() {
        base.close(); bike.close(); relief.close()
    }

    companion object {
        val NONE = OfflineTiles(TileSet.EMPTY, TileSet.EMPTY, TileSet.EMPTY, 0)
    }
}

/** Un fichier de carte présent et vérifié sur le téléphone. */
data class InstalledFile(val name: String, val file: File, val sha256: String, val size: Long)

/** Un endroit où ranger les cartes : la mémoire du téléphone, ou la carte SD s'il y en a une. */
data class StorageChoice(val index: Int, val label: String, val directory: File, val freeBytes: Long)

/**
 * Les fichiers de cartes hors ligne sur le téléphone.
 *
 * Ils sont rangés dans le dossier privé de l'appli sur le stockage partagé (ou sur la carte SD) : aucune
 * autorisation n'est nécessaire, et ils disparaissent avec l'appli. Chaque fichier est accompagné d'une petite
 * notice (taille, empreinte) écrite une fois son contenu vérifié : un fichier sans notice est ignoré.
 */
class OfflineStore(private val context: Context, private val scope: CoroutineScope) {

    private val lock = Any()

    @Volatile
    var tiles: OfflineTiles = OfflineTiles.NONE
        private set

    private val _installed = MutableStateFlow<Map<String, InstalledFile>>(emptyMap())
    /** Fichiers installés, par nom. */
    val installed: StateFlow<Map<String, InstalledFile>> = _installed

    private val _epoch = MutableStateFlow(0)
    /** Change à chaque fois que les cartes disponibles changent : la carte affichée doit alors être rechargée. */
    val epoch: StateFlow<Int> = _epoch

    /** Emplacements possibles, mémoire du téléphone d'abord. */
    fun storages(): List<StorageChoice> {
        val roots = runCatching { context.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList())
        val out = ArrayList<StorageChoice>()
        for ((index, root) in roots.withIndex()) {
            if (root == null) continue
            if (runCatching { Environment.getExternalStorageState(root) }.getOrNull() != Environment.MEDIA_MOUNTED) continue
            val directory = File(root, FOLDER)
            val removable = runCatching { Environment.isExternalStorageRemovable(root) }.getOrDefault(index > 0)
            out.add(StorageChoice(index, if (removable) "Carte SD" else "Téléphone", directory, freeBytes(root)))
        }
        return out
    }

    /**
     * Place réellement disponible à cet endroit. Android sait libérer ses caches pour faire de la place : on lui
     * demande donc ce qu'il peut « allouer », et on se rabat sur la place libre brute s'il ne sait pas répondre
     * (carte SD, par exemple).
     */
    @SuppressLint("UsableSpace")
    fun freeBytes(directory: File): Long {
        val manager = context.getSystemService(StorageManager::class.java)
        val allocatable = runCatching { manager?.getAllocatableBytes(manager.getUuidForPath(directory)) }.getOrNull()
        return allocatable ?: directory.usableSpace
    }

    /** Dossier des cartes sur l'emplacement choisi (créé au besoin), ou à défaut sur le premier disponible. */
    fun directory(index: Int): File? {
        val all = storages()
        val choice = all.firstOrNull { it.index == index } ?: all.firstOrNull() ?: return null
        choice.directory.mkdirs()
        return choice.directory.takeIf { it.isDirectory }
    }

    /** Relit les dossiers de cartes et rouvre les fichiers. À appeler au démarrage et après chaque changement. */
    fun reload() {
        synchronized(lock) {
            val found = LinkedHashMap<String, InstalledFile>()
            for (storage in storages()) {
                val files = storage.directory.listFiles() ?: continue
                for (file in files.sortedBy { it.name }) {
                    if (!file.isFile || file.name.endsWith(NOTICE) || file.name.endsWith(PARTIAL) || file.name.endsWith(".txt")) continue
                    val notice = readNotice(File(storage.directory, file.name + NOTICE)) ?: continue
                    if (notice.second != file.length()) continue
                    if (!found.containsKey(file.name)) found[file.name] = InstalledFile(file.name, file, notice.first, notice.second)
                }
            }
            val readers = found.values
                .filter { it.name.endsWith(".pmtiles") }
                .mapNotNull { installed -> runCatching { installed.name to PmTilesReader.open(installed.file) }.getOrNull() }
            // Les régions (zooms détaillés) passent avant la vue d'ensemble de la France.
            fun set(type: String) = TileSet(
                readers.filter { it.first.startsWith("$type-") }.sortedByDescending { it.second.header.maxZoom }.map { it.second }
            )
            val previous = tiles
            tiles = OfflineTiles(set(PackFile.BASE), set(PackFile.BIKE), set(PackFile.RELIEF), previous.epoch + 1)
            _installed.value = found
            _epoch.value = tiles.epoch
            // Les lectures en cours sur les anciens fichiers ont le temps de finir avant leur fermeture.
            if (previous !== OfflineTiles.NONE) scope.launch { delay(CLOSE_DELAY_MS); previous.close() }
        }
    }

    /**
     * Met en place un fichier téléchargé dont le contenu vient d'être vérifié.
     * Il faut ensuite appeler [reload] (une seule fois après une série d'installations).
     */
    fun install(downloaded: File, target: PackFile, directory: File): Boolean = synchronized(lock) {
        val destination = File(directory, target.name)
        val notice = File(directory, target.name + NOTICE)
        // Sans notice, le fichier n'est pas considéré comme installé : on la retire le temps du remplacement.
        notice.delete()
        val moved = downloaded.renameTo(destination) || runCatching {
            downloaded.copyTo(destination, overwrite = true)
            downloaded.delete()
            true
        }.getOrDefault(false)
        if (!moved) return false
        runCatching {
            notice.writeText(JSONObject().put("sha256", target.sha256).put("taille", destination.length()).toString())
        }.isSuccess
    }

    /** Supprime des fichiers installés (tous emplacements confondus), puis recharge. */
    fun delete(names: Collection<String>) {
        synchronized(lock) {
            for (storage in storages()) {
                for (name in names) {
                    File(storage.directory, name + NOTICE).delete()
                    File(storage.directory, name).delete()
                }
            }
        }
        reload()
    }

    /**
     * Dossier où le moteur d'itinéraire trouve ses données (fichiers « dalle-*.rd5 »), ou null s'il n'y en a pas.
     * Si elles sont réparties entre le téléphone et la carte SD, on indique au moteur le second dossier comme il
     * le prévoit lui-même (fichier storageconfig.txt).
     */
    fun routingDirectory(): File? {
        val byDirectory = _installed.value.values.filter { it.name.endsWith(".rd5") }.groupBy { it.file.parentFile }
        val main = byDirectory.maxByOrNull { it.value.size }?.key ?: return null
        val other = byDirectory.keys.firstOrNull { it != main }
        val config = File(main, "storageconfig.txt")
        runCatching {
            if (other != null) config.writeText("secondary_segment_dir=${other.absolutePath}\n") else config.delete()
        }
        return main
    }

    /** Vrai si des données d'itinéraire sont installées. */
    val hasRoutingData: Boolean get() = _installed.value.keys.any { it.endsWith(".rd5") }

    /** Place occupée par les cartes installées. */
    fun usedBytes(): Long = _installed.value.values.sumOf { it.size }

    private fun readNotice(file: File): Pair<String, Long>? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            json.getString("sha256") to json.getLong("taille")
        }.getOrNull()
    }

    companion object {
        const val FOLDER = "cartes"
        /** Suffixe de la notice qui accompagne chaque fichier installé. */
        const val NOTICE = ".json"
        /** Suffixe d'un fichier en cours de téléchargement. */
        const val PARTIAL = ".telechargement"
        private const val CLOSE_DELAY_MS = 4_000L
    }
}
