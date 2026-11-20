package fr.velogo.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.util.Dates
import fr.velogo.core.gpx.Gpx
import fr.velogo.core.ride.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Export d'une sortie au format GPX, vers un fichier choisi par l'utilisateur ou vers une autre appli. */
object GpxExport {
    const val MIME = "application/gpx+xml"

    fun fileName(ride: RideEntity): String = "VeloGo_${Dates.fileStamp(ride.startTimeMs)}.gpx"

    /** Écrit le GPX dans le dossier de cache partagé et renvoie l'intention de partage correspondante. */
    suspend fun shareIntent(context: Context, ride: RideEntity, points: List<TrackPoint>): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(ride))
        file.bufferedWriter(Charsets.UTF_8).use { Gpx.write(it, ride.name, points) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, ride.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Partager la trace GPX",
        )
    }

    /** Écrit le GPX à l'emplacement choisi dans le sélecteur de fichiers d'Android. */
    suspend fun writeTo(context: Context, target: Uri, ride: RideEntity, points: List<TrackPoint>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { Gpx.write(it, ride.name, points) }
            ?: error("Impossible d'écrire dans ce fichier")
    }
}
