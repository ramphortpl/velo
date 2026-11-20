package fr.velogo.app.map

import android.content.Context
import fr.velogo.app.AppContainer
import fr.velogo.app.VeloGoApp
import fr.velogo.app.offline.OfflineRouter
import fr.velogo.app.offline.OfflineTileInterceptor
import fr.velogo.app.offline.OfflineTiles
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import java.util.concurrent.TimeUnit

/** Initialisation unique de MapLibre (faite à la première carte affichée, pas au démarrage de l'appli). */
object MapInit {
    @Volatile
    private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            val app = context.applicationContext
            MapLibre.getInstance(app)
            // Les tuiles sont demandées avec un User-Agent qui identifie VeloGo (exigence des serveurs OSM France),
            // et sans rafale : quelques requêtes simultanées par serveur au plus.
            // Les requêtes de la carte hors ligne sont servies depuis les fichiers du téléphone, sans réseau.
            val offline = (app as? VeloGoApp)?.container?.offline
            val router = OfflineRouter(
                tiles = { offline?.store?.tiles ?: OfflineTiles.NONE },
                asset = { path -> runCatching { app.assets.open("hors-ligne/$path").use { it.readBytes() } }.getOrNull() },
            )
            val client = OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 6 })
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(OfflineTileInterceptor(router))
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", AppContainer.USER_AGENT).build())
                }
                .build()
            HttpRequestUtil.setOkHttpClient(client)
            // Cache des tuiles déjà vues : évite de les redemander et dépanne sans réseau sur les zones récentes.
            OfflineManager.getInstance(app).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, null)
            done = true
        }
    }

    /**
     * Avec la carte hors ligne, MapLibre ne doit pas attendre le retour du réseau pour demander ses tuiles
     * (elles sont sur le téléphone) : on lui dit de se considérer connecté. Avec un fond en ligne, on lui rend
     * la main : il suit l'état réel du réseau et relance les tuiles manquantes dès que la connexion revient.
     */
    fun setOfflineLayer(active: Boolean) {
        if (offlineLayer == active) return
        offlineLayer = active
        runCatching { MapLibre.setConnected(if (active) true else null) }
    }

    private var offlineLayer: Boolean? = null

    private const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024
}
