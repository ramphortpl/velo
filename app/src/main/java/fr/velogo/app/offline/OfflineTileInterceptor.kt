package fr.velogo.app.offline

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/** Ce que le « serveur » de cartes hors ligne répond à une requête. Un corps null vaut « introuvable ». */
class OfflineReply(val body: ByteArray?, val contentType: String)

/**
 * Le serveur de cartes hors ligne… sans serveur.
 *
 * Le style de la carte vélo hors ligne demande ses tuiles, ses polices et ses icônes à une adresse fictive
 * (https://hors-ligne.velogo.invalid/…). Ces requêtes ne partent jamais sur le réseau : elles sont interceptées
 * ici et servies depuis les fichiers du téléphone. La carte ne voit aucune différence avec un fond en ligne, ce
 * qui permet de réunir plusieurs régions dans une seule carte et de tout piloter depuis notre code.
 *
 * Adresses reconnues :
 *  - /fond/{z}/{x}/{y}.mvt, /velo/{z}/{x}/{y}.mvt : tuiles vectorielles des packs installés ;
 *  - /relief/{z}/{x}/{y}.webp : tuiles de relief ;
 *  - /polices/{police}/{plage}.pbf : polices embarquées dans l'appli (réponse vide pour une plage absente :
 *    les quelques caractères concernés ne sont pas dessinés, mais le reste des noms s'affiche) ;
 *  - /sprites/{nom} : icônes embarquées.
 */
class OfflineRouter(
    private val tiles: () -> OfflineTiles,
    /** Lit un fichier embarqué dans l'appli (chemin relatif au dossier « hors-ligne »), ou null s'il n'existe pas. */
    private val asset: (String) -> ByteArray?,
) {
    private val glyphs = ConcurrentHashMap<String, ByteArray>()

    fun reply(segments: List<String>): OfflineReply {
        when (segments.firstOrNull()) {
            "fond", "velo", "relief" -> {
                if (segments.size != 4) return NOT_FOUND
                val z = segments[1].toIntOrNull()
                val x = segments[2].toIntOrNull()
                val y = segments[3].substringBefore('.').toIntOrNull()
                if (z == null || x == null || y == null || z !in 0..22 || x < 0 || y < 0) return NOT_FOUND
                val current = tiles()
                return when (segments[0]) {
                    "fond" -> OfflineReply(current.base.tile(z, x, y), VECTOR)
                    "velo" -> OfflineReply(current.bike.tile(z, x, y), VECTOR)
                    else -> OfflineReply(current.relief.tile(z, x, y), WEBP)
                }
            }
            "polices" -> {
                if (segments.size != 3) return NOT_FOUND
                // « Noto Sans Regular » -> dossier « noto-sans-regular ». Une liste de polices : on prend la première.
                val font = segments[1].substringBefore(',').trim().lowercase().replace(' ', '-')
                val range = segments[2]
                if (!SAFE_NAME.matches(font) || !SAFE_NAME.matches(range)) return NOT_FOUND
                val bytes = glyphs.getOrPut("$font/$range") { asset("polices/$font/$range") ?: EMPTY }
                return OfflineReply(bytes, PROTOBUF)
            }
            "sprites" -> {
                if (segments.size != 2 || !SAFE_NAME.matches(segments[1])) return NOT_FOUND
                val name = segments[1]
                return OfflineReply(asset("sprites/$name"), if (name.endsWith(".png")) "image/png" else "application/json")
            }
            else -> return NOT_FOUND
        }
    }

    companion object {
        const val HOST = "hors-ligne.velogo.invalid"
        private const val VECTOR = "application/x-protobuf"
        private const val PROTOBUF = "application/x-protobuf"
        private const val WEBP = "image/webp"
        private val EMPTY = ByteArray(0)
        private val NOT_FOUND = OfflineReply(null, "text/plain")
        private val SAFE_NAME = Regex("[A-Za-z0-9@._-]+")
    }
}

/** Branche [OfflineRouter] sur le client HTTP de la carte : tout ce qui vise l'adresse fictive est servi sur place. */
class OfflineTileInterceptor(private val router: OfflineRouter) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != OfflineRouter.HOST) return chain.proceed(request)
        val reply = try {
            router.reply(request.url.pathSegments)
        } catch (_: Exception) {
            OfflineReply(null, "text/plain")
        }
        return respond(request, reply)
    }

    private fun respond(request: Request, reply: OfflineReply): Response {
        val body = reply.body
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            // 404 : pour la carte, « cette tuile n'existe pas » (zone non téléchargée), et non une panne à réessayer.
            .code(if (body != null) 200 else 404)
            .message(if (body != null) "OK" else "Not Found")
            .body((body ?: ByteArray(0)).toResponseBody(reply.contentType.toMediaType()))
            .build()
    }
}
