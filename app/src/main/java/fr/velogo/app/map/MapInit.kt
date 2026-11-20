package fr.velogo.app.map

import android.content.Context
import fr.velogo.app.AppContainer
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
            val client = OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 6 })
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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

    private const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024
}
