package fr.velogo.app.data

import android.content.Context
import java.io.File

/** Le dossier des fichiers confiés à d'autres applis (traces GPX, affiches) par la feuille de partage d'Android. */
object ShareFiles {
    /**
     * Le dossier de partage. Les fichiers de plus d'un jour y sont effacés au passage ; les plus récents restent,
     * car l'appli qui les a reçus (une messagerie, par exemple) peut encore être en train de les lire.
     */
    fun directory(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val limit = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        return dir
    }

    /** Nom de fichier tiré d'un titre : lettres, chiffres, espaces et tirets seulement. */
    fun safeName(title: String, fallback: String): String =
        title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { fallback }.take(60)

    private const val KEEP_MS = 24 * 60 * 60 * 1000L
}
