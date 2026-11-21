package fr.velogo.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.util.Dates
import fr.velogo.core.gpx.Gpx
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Ce qu'un import a donné. */
data class ImportResult(
    /** Identifiants des sorties ajoutées à l'historique. */
    val imported: List<Long> = emptyList(),
    /** Sorties du fichier déjà présentes dans l'historique (même heure de départ). */
    val duplicates: Int = 0,
    /** Traces inutilisables : pas d'heures (un itinéraire), trop courtes, ou fichier illisible. */
    val unreadable: Int = 0,
)

/**
 * Sauvegarde et reprise des sorties, dans le format que toutes les applis de vélo comprennent : le GPX.
 *
 * L'export réunit toutes les sorties dans une archive ZIP (un fichier GPX par sortie) ; l'import relit une telle
 * archive, ou un simple fichier GPX venu d'une autre appli. Tout se passe sur le téléphone : l'archive est écrite
 * là où l'utilisateur le demande (ses fichiers, sa carte SD, son espace en ligne s'il en a un).
 */
class RideArchive(private val context: Context, private val rides: RideRepository) {

    /** Écrit toutes les sorties dans le fichier choisi. Renvoie leur nombre. */
    suspend fun exportAll(target: Uri, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        GpxExport.openForWriting(context, target).use { exportTo(it, onProgress) }
    }

    /** Ajoute à l'historique les sorties du fichier choisi (une archive ZIP de GPX, ou un GPX seul). */
    suspend fun import(source: Uri, onProgress: (done: Int) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val name = runCatching {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("Impossible de lire ce fichier")
        input.use { importFrom(it, name, onProgress) }
    }

    suspend fun exportTo(output: OutputStream, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val all = rides.finishedRides().sortedBy { it.startTimeMs }
        val used = HashSet<String>()
        ZipOutputStream(output.buffered()).use { zip ->
            all.forEachIndexed { index, ride ->
                val points = rides.points(ride.id)
                zip.putNextEntry(ZipEntry(entryName(ride, used)))
                val writer = zip.writer(Charsets.UTF_8)
                Gpx.write(writer, ride.name, points, source = ride.altitudeSource)
                writer.flush()
                zip.closeEntry()
                onProgress(index + 1, all.size)
            }
        }
        return all.size
    }

    suspend fun importFrom(input: InputStream, fileName: String?, onProgress: (done: Int) -> Unit = {}): ImportResult {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val magic = ByteArray(4)
        val read = buffered.read(magic)
        buffered.reset()
        val isZip = read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        var result = ImportResult()
        val known = rides.finishedRides().map { it.startTimeMs }.toMutableList()
        if (!isZip) return importGpx(buffered, fileName?.substringBeforeLast('.'), known, result)
        ZipInputStream(buffered).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) break
                if (entry.isDirectory || !entry.name.endsWith(".gpx", ignoreCase = true)) continue
                val name = entry.name.substringAfterLast('/').substringBeforeLast('.')
                // Le lecteur XML ne doit pas refermer l'archive ; et un fichier démesuré (archive piégée) est refusé.
                result = importGpx(Guarded(zip, MAX_GPX_BYTES), name, known, result)
                onProgress(result.imported.size)
            }
        }
        return result
    }

    private suspend fun importGpx(input: InputStream, fallbackName: String?, known: MutableList<Long>, before: ImportResult): ImportResult {
        val file = runCatching { Gpx.read(input) }.getOrNull() ?: return before.copy(unreadable = before.unreadable + 1)
        var result = before
        var usable = false
        for (track in file.tracks) {
            val points = TrackImport.prepare(track.points) ?: continue
            usable = true
            val start = points.first().timeMs
            if (known.any { abs(it - start) < SAME_RIDE_MS }) {
                result = result.copy(duplicates = result.duplicates + 1)
                continue
            }
            // VeloGo note dans ses fichiers d'où vient l'altitude ; pour ceux des autres, on la traite comme du GPS.
            val origin = when (track.source) {
                RideEntity.ALTITUDE_IGN -> RideEntity.ALTITUDE_IGN
                RideEntity.ALTITUDE_RELIEF -> RideEntity.ALTITUDE_RELIEF
                else -> RideEntity.ALTITUDE_GNSS
            }
            val totals = RideAnalyzer.summarize(points, if (origin == RideEntity.ALTITUDE_GNSS) AltitudeSource.GNSS else AltitudeSource.TERRAIN)
            val name = track.name ?: file.name ?: fallbackName ?: "Sortie importée"
            val id = rides.importRide(name, points, totals, origin)
            known.add(start)
            result = result.copy(imported = result.imported + id)
        }
        return if (usable) result else result.copy(unreadable = result.unreadable + 1)
    }

    /** Nom du fichier d'une sortie dans l'archive : sa date, puis son nom sans caractères gênants. */
    private fun entryName(ride: RideEntity, used: MutableSet<String>): String {
        val slug = ride.name.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').replace(Regex("-+"), "-").take(40)
        val base = Dates.fileStamp(ride.startTimeMs) + if (slug.isEmpty()) "" else "_$slug"
        var name = "$base.gpx"
        var n = 2
        while (!used.add(name)) name = "${base}_${n++}.gpx"
        return name
    }

    /** Laisse lire un flux sans permettre de le fermer, et s'arrête au-delà d'une taille raisonnable. */
    private class Guarded(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) throw IOException("Fichier trop volumineux")
            return super.read().also { if (it >= 0) remaining-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) throw IOException("Fichier trop volumineux")
            return super.read(b, off, minOf(len.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
        }

        override fun close() = Unit
    }

    companion object {
        const val MIME = "application/zip"
        /** Deux sorties parties à moins d'une minute d'écart sont la même. */
        private const val SAME_RIDE_MS = 60_000L
        private const val MAX_ENTRIES = 20_000
        private const val MAX_GPX_BYTES = 64L * 1024 * 1024

        fun fileName(nowMs: Long = System.currentTimeMillis()): String = "VeloGo_sorties_${Dates.fileStamp(nowMs).substringBefore('_')}.zip"
    }
}
