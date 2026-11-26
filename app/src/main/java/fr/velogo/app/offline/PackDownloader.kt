package fr.velogo.app.offline

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
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

/** Le réseau du téléphone, réduit à ce qui décide si Android lance un téléchargement. */
internal data class NetworkState(val connected: Boolean, val metered: Boolean = false, val roaming: Boolean = false) {
    companion object {
        /** Quand on ne sait pas : on ne prétend rien, le téléchargement « va démarrer ». */
        val UNKNOWN = NetworkState(connected = true)
    }
}

/**
 * Pourquoi un téléchargement demandé ne démarre pas, d'après le réseau du téléphone ; null quand rien ne le
 * retient (il va démarrer).
 *
 * Android ne le dit pas lui-même : tant que la condition demandée (Wi-Fi, pas d'itinérance) n'est pas remplie, il
 * annonce simplement le téléchargement « en attente », et l'écran restait indéfiniment sur « Le téléchargement
 * va démarrer… » (vu sur l'émulateur un jour où il n'avait que le réseau mobile, fabrication n° 30).
 */
internal fun pendingReason(wifiOnly: Boolean, network: NetworkState): String? = when {
    !network.connected -> WAITING_FOR_NETWORK
    wifiOnly && network.metered -> WAITING_FOR_WIFI
    network.roaming -> WAITING_ROAMING
    else -> null
}

internal const val WAITING_FOR_NETWORK = "En attente du réseau"
internal const val WAITING_FOR_WIFI =
    "En attente du Wi-Fi. Pour télécharger avec ton forfait, désactive « Wi-Fi uniquement » en bas de la page."
internal const val WAITING_ROAMING = "En attente : rien n'est téléchargé à l'étranger (itinérance)."

/**
 * Télécharge les fichiers de cartes avec le gestionnaire de téléchargements d'Android.
 *
 * C'est le système qui fait le travail : il reprend tout seul après une coupure de réseau ou un redémarrage,
 * attend le Wi-Fi si on le lui demande et affiche sa propre notification, sans que VeloGo reste ouverte.
 * À l'arrivée, on vérifie l'empreinte SHA-256 du fichier avant de l'installer.
 */
class PackDownloader(private val context: Context, private val store: OfflineStore) {

    /** [wifiOnly] et [title] : ce qui a été demandé à Android, pour pouvoir le redemander autrement. */
    private class Tracked(val id: Long, val file: PackFile, val directory: String, val wifiOnly: Boolean, val title: String)

    private val manager: DownloadManager? = context.getSystemService(DownloadManager::class.java)
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
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
                    val label = title(file)
                    val id = request(dm, file, directory.absolutePath, wifiOnly, label)
                    if (id == null) {
                        failures[file.name] = REFUSED
                        continue
                    }
                    list.add(Tracked(id, file, directory.absolutePath, wifiOnly, label))
                }
                save(list)
                publish(list, query(dm, list))
                true
            }
        }

    /**
     * Le réglage « Wi-Fi uniquement » vient de changer. Android ne permet pas de modifier un téléchargement déjà
     * demandé : ceux qui n'ont encore rien reçu sont donc redemandés avec la nouvelle règle (sans cela, celui qui
     * attend le Wi-Fi l'attendrait toujours après qu'on a autorisé le forfait). Ceux qui ont commencé gardent la
     * leur : les redemander ferait perdre ce qui est déjà arrivé.
     */
    suspend fun applyWifiOnly(wifiOnly: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dm = manager ?: return@withContext
            val list = load()
            val rows = query(dm, list)
            var changed = false
            for (index in list.indices.reversed()) {
                val t = list[index]
                val row = rows[t.id] ?: continue
                if (t.wifiOnly == wifiOnly || !row.notStarted) continue
                // L'ancien d'abord : en le retirant, Android efface aussi son fichier, qui porte le même nom.
                runCatching { dm.remove(t.id) }
                val id = request(dm, t.file, t.directory, wifiOnly, t.title)
                if (id == null) {
                    failures[t.file.name] = REFUSED
                    list.removeAt(index)
                } else {
                    list[index] = Tracked(id, t.file, t.directory, wifiOnly, t.title)
                }
                changed = true
            }
            if (changed) {
                save(list)
                publish(list, query(dm, list))
            }
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

    /** Demande un fichier à Android. Renvoie le numéro du téléchargement, ou null si Android le refuse. */
    private fun request(dm: DownloadManager, file: PackFile, directory: String, wifiOnly: Boolean, title: String): Long? {
        val target = File(directory, file.name + OfflineStore.PARTIAL)
        target.delete()
        val request = DownloadManager.Request(url(file).toUri())
            .setTitle(title)
            .setDescription("Carte hors ligne VeloGo")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .addRequestHeader("User-Agent", AppContainer.USER_AGENT)
            .setDestinationUri(Uri.fromFile(target))
        return runCatching { dm.enqueue(request) }.getOrNull()
    }

    private class Row(val status: Int, val reason: Int, val downloaded: Long, val total: Long, val localFile: File?) {
        /** Demandé à Android, mais rien n'est encore arrivé. */
        val notStarted: Boolean
            get() = downloaded <= 0 && (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED)
    }

    /** Le réseau du moment, pour dire pourquoi un téléchargement demandé ne démarre pas. */
    private fun networkState(): NetworkState {
        val cm = connectivity ?: return NetworkState.UNKNOWN
        return runCatching {
            val active = cm.activeNetwork ?: return NetworkState(connected = false)
            // Avant Android 9, rien ne dit proprement qu'un réseau est en itinérance : on ne l'annonce pas.
            val roaming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false
            NetworkState(connected = true, metered = cm.isActiveNetworkMetered, roaming = roaming)
        }.getOrDefault(NetworkState.UNKNOWN)
    }

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
        val network = if (list.isEmpty()) NetworkState.UNKNOWN else networkState()
        for (t in list) {
            val row = rows[t.id]
            val total = t.file.size
            out[t.file.name] = when (row?.status) {
                DownloadManager.STATUS_RUNNING -> DownloadProgress(t.file.name, DownloadProgress.Status.RUNNING, row.downloaded.coerceIn(0, total), total)
                DownloadManager.STATUS_PAUSED -> DownloadProgress(
                    t.file.name, DownloadProgress.Status.WAITING, row.downloaded.coerceIn(0, total), total,
                    when (row.reason) {
                        // Rien n'est encore arrivé : désactiver « Wi-Fi uniquement » relancera ce téléchargement.
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> if (row.notStarted) WAITING_FOR_WIFI else "En attente du Wi-Fi"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> WAITING_FOR_NETWORK
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Nouvelle tentative dans un instant"
                        else -> "En pause"
                    },
                )
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress(t.file.name, DownloadProgress.Status.VERIFYING, total, total)
                else -> {
                    // « En attente » pour Android : s'il y a une raison que le réseau explique, on la dit.
                    val reason = pendingReason(t.wifiOnly, network)
                    if (reason == null) DownloadProgress(t.file.name, DownloadProgress.Status.QUEUED, 0, total)
                    else DownloadProgress(t.file.name, DownloadProgress.Status.WAITING, 0, total, reason)
                }
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
                            // Absents des téléchargements lancés par une version précédente de l'appli.
                            o.optBoolean("wifi", true),
                            o.optString("titre", o.getString("nom")),
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
                        .put("wifi", t.wifiOnly).put("titre", t.title)
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
        private const val REFUSED = "Android a refusé le téléchargement."
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
