package fr.velogo.app.nav

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import fr.velogo.app.data.ShareFiles
import fr.velogo.core.format.Formats
import fr.velogo.core.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Envoi de l'affiche d'un itinéraire à une autre appli (messagerie, galerie…). */
object RouteShare {
    const val POSTER_MIME = "image/jpeg"

    /** Le texte qui accompagne l'affiche : le nom de la sortie et ses chiffres, lisibles même sans ouvrir l'image. */
    fun caption(text: PosterText, route: Route): String = buildString {
        append(text.title.trim().ifEmpty { "Sortie vélo" })
        if (text.subtitle.isNotBlank()) append(" — ").append(text.subtitle.trim())
        append('\n')
        append(Formats.distanceAuto(route.distanceM))
        append(" · ↗ ").append(Formats.meters(route.ascentM)).append(" m")
        append(" · environ ").append(Formats.durationWords((route.durationS * 1000).toLong()))
        append('\n')
        append("Itinéraire préparé avec VeloGo")
    }

    /** Écrit l'affiche dans le dossier de partage et renvoie l'intention « Partager » (image et légende). */
    suspend fun posterIntent(context: Context, poster: Bitmap, text: PosterText, route: Route): Intent = withContext(Dispatchers.IO) {
        val name = text.title.trim().ifEmpty { "Sortie vélo" }
        val file = File(ShareFiles.directory(context), "VeloGo_${ShareFiles.safeName(name, "itineraire")}.jpg")
        file.outputStream().use { poster.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(POSTER_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, caption(text, route))
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Sans cela, l'aperçu de la feuille de partage d'Android n'a pas le droit de lire l'image.
        send.clipData = ClipData.newUri(context.contentResolver, name, uri)
        Intent.createChooser(send, "Partager l'affiche").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
