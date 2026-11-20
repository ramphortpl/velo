package fr.velogo.app.offline

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import fr.velogo.app.AppContainer
import fr.velogo.app.BuildConfig
import fr.velogo.app.VeloGoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Où en est le téléchargement d'un fichier. */
data class DownloadProgress(
    val name: String,
    val status: Status,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Explication en clair quand le téléchargement attend ou a échoué. */
    val message: String? = null,
) {
    enum class Status { QUEUED, RUNNING, WAITING, VERIFYING, FAILED }
}

/**
 * Télécharge les fichiers de cartes avec le gestionnaire de téléchargements d'Android.
 *
 * C'est le système qui fait le travail : il reprend tout seul après une coupure de réseau ou un redémarrage,
 * attend le Wi-Fi si on le lui demande et affiche sa propre notification, sans que VeloGo reste ouverte.
 * À l'arrivée, on vérifie l'empreinte SHA-256 du fichier avant de l'installer.
 */
class PackDownloader(private val context: Context, private val store: OfflineStore) {

    private class Tracked(val id: Long, val file: PackFile, val directory: String)

    private val manager: DownloadManager? = context.getSystemService(DownloadManager::class.java)
    private val ledger = File(context.filesDir, "telechargements.json")
    private val mutex = Mutex()
    private var tracked: MutableList<Tracked>? = null
    private val failures = LinkedHashMap<String, String>()

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    /** Avancement par nom de fichier (seulement ceux qui sont en cours, en attente ou en échec). */
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress

    /** Lance le téléchargement de [files] dans [directory]. Les fichiers déjà en cours ne sont pas relancés. */
    suspend fun enqueue(files: List<PackFile>, directory: File, wifiOnly: Boolean, title: (PackFile) -> String): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val dm = manager ?: return@withContext false
                val list = load()
                directory.mkdirs()
                for (file in files) {
                    if (list.any { it.file.name == file.name }) continue
                    failures.remove(file.name)
                    val target = File(directory, file.name + OfflineStore.PARTIAL)
                    target.delete()
                    val request = DownloadManager.Request(url(file).toUri())
                        .setTitle(title(file))
                        .setDescription("Carte hors ligne VeloGo")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setAllowedOverMetered(!wifiOnly)
                        .setAllowedOverRoaming(false)
                        .addRequestHeader("User-Agent", AppContainer.USER_AGENT)
                        .setDestinationUri(Uri.fromFile(target))
                    val id = runCatching { dm.enqueue(request) }.getOrNull()
                    if (id == null) {
                        failures[file.name] = "Android a refusé le téléchargement."
                        continue
                    }
                    list.add(Tracked(id, file, directory.absolutePath))
                }
                save(list)
                publish(list, query(dm, list))
                true
            }
        }

    /** Annule les téléchargements des fichiers nommés (et oublie leurs échecs). */
    suspend fun cancel(names: Collection<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dm = manager ?: return@withContext
            val list = load()
            val (stopped, kept) = list.partition { it.file.name in names }
            if (stopped.isNotEmpty()) runCatching { dm.remove(*stopped.map { it.id }.toLongArray()) }
            for (t in stopped) File(t.directory, t.file.name + OfflineStore.PARTIAL).delete()
            names.forEach { failures.remove(it) }
            list.clear(); list.addAll(kept)
            save(list)
            publish(list, query(dm, list))
        }
    }

    /**
     * Relit l'avancement auprès d'Android, vérifie et installe ce qui vient de se terminer.
     * Renvoie vrai s'il reste des téléchargements en cours ou en attente.
     */
    suspend fun sync(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dm = manager ?: return@withContext false
            val list = load()
            if (list.isEmpty()) {
                publish(list, emptyMap())
                return@withContext false
            }
            val rows = query(dm, list)
            var installed = false
            for (t in list.toList()) {
                val row = rows[t.id]
                when {
                    // Annulé depuis la notification, ou effacé par le système.
                    row == null -> list.remove(t)
                    row.status == DownloadManager.STATUS_SUCCESSFUL -> {
                        _progress.value = _progress.value + (t.file.name to DownloadProgress(t.file.name, DownloadProgress.Status.VERIFYING, t.file.size, t.file.size))
                        val file = row.localFile ?: File(t.directory, t.file.name + OfflineStore.PARTIAL)
                        val valid = file.isFile && file.length() == t.file.size && sha256(file) == t.file.sha256
                        // À partir d'ici on va au bout, même si l'écran qui a lancé la vérification se ferme.
                        withContext(NonCancellable) {
                            if (valid && store.install(file, t.file, File(t.directory))) {
                                installed = true
                                failures.remove(t.file.name)
                            } else {
                                file.delete()
                                failures[t.file.name] = "Fichier incomplet ou abîmé : relance le téléchargement."
                            }
                            runCatching { dm.remove(t.id) }
                            list.remove(t)
                            save(list)
                        }
                    }
                    row.status == DownloadManager.STATUS_FAILED -> {
                        failures[t.file.name] = failureMessage(row.reason)
                        runCatching { dm.remove(t.id) }
                        File(t.directory, t.file.name + OfflineStore.PARTIAL).delete()
                        list.remove(t)
                    }
                }
            }
            save(list)
            if (installed) store.reload()
            publish(list, rows)
            list.isNotEmpty()
        }
    }

    /** Vrai si un fichier de ce nom est en cours de téléchargement. */
    fun isActive(name: String): Boolean = _progress.value[name]?.let { it.status != DownloadProgress.Status.FAILED } ?: false

    private fun url(file: PackFile): String =
        "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/$RELEASE_TAG/${file.remoteName}"

    // --- Dialogue avec le gestionnaire de téléchargements d'Android -------------------------------------------

    private class Row(val status: Int, val reason: Int, val downloaded: Long, val total: Long, val localFile: File?)

    private fun query(dm: DownloadManager, list: List<Tracked>): Map<Long, Row> {
        if (list.isEmpty()) return emptyMap()
        val out = HashMap<Long, Row>()
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(*list.map { it.id }.toLongArray()))?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val status = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val reason = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val soFar = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uri = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                while (cursor.moveToNext()) {
                    val local = cursor.getString(uri)?.let { runCatching { it.toUri().path }.getOrNull() }?.let { File(it) }
                    out[cursor.getLong(id)] = Row(cursor.getInt(status), cursor.getInt(reason), cursor.getLong(soFar), cursor.getLong(total), local)
                }
            }
        }
        return out
    }

    private fun publish(list: List<Tracked>, rows: Map<Long, Row>) {
        val out = LinkedHashMap<String, DownloadProgress>()
        for (t in list) {
            val row = rows[t.id]
            val total = t.file.size
            out[t.file.name] = when (row?.status) {
                DownloadManager.STATUS_RUNNING -> DownloadProgress(t.file.name, DownloadProgress.Status.RUNNING, row.downloaded.coerceIn(0, total), total)
                DownloadManager.STATUS_PAUSED -> DownloadProgress(
                    t.file.name, DownloadProgress.Status.WAITING, row.downloaded.coerceIn(0, total), total,
                    when (row.reason) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "En attente du Wi-Fi"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "En attente du réseau"
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Nouvelle tentative dans un instant"
                        else -> "En pause"
                    },
                )
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress(t.file.name, DownloadProgress.Status.VERIFYING, total, total)
                else -> DownloadProgress(t.file.name, DownloadProgress.Status.QUEUED, 0, total)
            }
        }
        for ((name, message) in failures) {
            if (name !in out) out[name] = DownloadProgress(name, DownloadProgress.Status.FAILED, message = message)
        }
        _progress.value = out
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Plus assez de place sur le téléphone."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Stockage introuvable (carte SD retirée ?)."
        DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Impossible d'écrire le fichier."
        DownloadManager.ERROR_CANNOT_RESUME -> "Téléchargement interrompu : relance-le."
        404 -> "Cette carte vient d'être mise à jour : actualise la liste puis relance."
        else -> "Téléchargement impossible (code $reason). Vérifie ta connexion puis relance."
    }

    // --- Mémoire des téléchargements lancés -------------------------------------------------------------------

    private fun load(): MutableList<Tracked> {
        tracked?.let { return it }
        val list = ArrayList<Tracked>()
        runCatching {
            if (ledger.isFile) {
                val array = JSONArray(ledger.readText())
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    list.add(
                        Tracked(
                            o.getLong("id"),
                            PackFile(o.getString("type"), o.getString("nom"), o.getString("fichier"), o.getLong("taille"), o.getString("sha256")),
                            o.getString("dossier"),
                        )
                    )
                }
            }
        }
        tracked = list
        return list
    }

    private fun save(list: List<Tracked>) {
        runCatching {
            val array = JSONArray()
            for (t in list) {
                array.put(
                    JSONObject().put("id", t.id).put("type", t.file.type).put("nom", t.file.name).put("fichier", t.file.remoteName)
                        .put("taille", t.file.size).put("sha256", t.file.sha256).put("dossier", t.directory)
                )
            }
            val temporary = File(ledger.parentFile, ledger.name + ".tmp")
            temporary.writeText(array.toString())
            temporary.renameTo(ledger)
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Étiquette de la Release GitHub qui contient les cartes. */
        const val RELEASE_TAG = "cartes"
    }
}

/** Prévenu par Android à la fin d'un téléchargement, même si l'appli est fermée : on vérifie et on installe. */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val container = (context.applicationContext as? VeloGoApp)?.container ?: return
        val pending = goAsync()
        val job = container.appScope.launch { runCatching { container.offline.downloader.sync() } }
        // Android n'accorde que quelques secondes à un récepteur : la vérification d'un gros fichier continue
        // ensuite si l'appli reste en vie, et sera de toute façon refaite à la prochaine ouverture.
        container.appScope.launch {
            withTimeoutOrNull(RECEIVER_BUDGET_MS) { job.join() }
            pending.finish()
        }
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}
