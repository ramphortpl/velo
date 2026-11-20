package fr.velogo.app

import android.app.Application
import fr.velogo.app.data.RideRepository
import fr.velogo.app.data.SettingsRepository
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.app.location.GpsSource
import fr.velogo.app.recording.RideRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Les objets partagés par toute l'appli, créés à la demande (injection de dépendances « à la main » :
 * l'appli est assez petite pour s'en passer d'un outil, et le démarrage reste rapide).
 */
class AppContainer(private val app: Application) {
    /** Vit aussi longtemps que l'appli : pour ce qui doit survivre à la fermeture d'un écran. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: VeloGoDatabase by lazy { VeloGoDatabase.create(app) }
    val rides: RideRepository by lazy { RideRepository(database) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val gps: GpsSource by lazy { GpsSource(app, appScope) }
    val recorder: RideRecorder by lazy { RideRecorder(rides) }

    /**
     * Client HTTP de l'appli. Le User-Agent identifie VeloGo, comme l'exigent les serveurs de tuiles
     * d'OpenStreetMap France ; il ne contient aucune information personnelle.
     */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    companion object {
        val USER_AGENT = "VeloGo/${BuildConfig.VERSION_NAME} (Android ; application personnelle non commerciale)"
    }
}
