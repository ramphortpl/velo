package fr.velogo.app

import android.app.Application
import fr.velogo.app.data.RideRepository
import fr.velogo.app.data.SettingsRepository
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.app.elevation.ElevationCorrector
import fr.velogo.app.elevation.IgnAltimetry
import fr.velogo.app.location.GpsSource
import fr.velogo.app.recording.RecorderEvent
import fr.velogo.app.recording.RideRecorder
import fr.velogo.app.update.UpdateChecker
import fr.velogo.app.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    val elevationCorrector: ElevationCorrector by lazy { ElevationCorrector(rides, IgnAltimetry(http)) }
    val updateChecker: UpdateChecker by lazy { UpdateChecker(http) }

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    /** Version plus récente disponible, s'il y en a une. */
    val update: StateFlow<UpdateInfo?> = _update

    /** Tâches de fond lancées une fois pour toutes au démarrage de l'appli. */
    fun start() {
        // Dès qu'une sortie est enregistrée, on recale son altitude sur le relief IGN (si le réglage est actif).
        appScope.launch {
            recorder.events.collect { event ->
                if (event is RecorderEvent.Saved && settings.settings.first().ignAltitudeCorrection) {
                    launch { runCatching { elevationCorrector.correct(event.rideId) } }
                }
            }
        }
        // Vérification discrète des mises à jour, au plus deux fois par jour.
        appScope.launch {
            val current = settings.settings.first()
            val now = System.currentTimeMillis()
            if (!BuildConfig.DEBUG && current.checkUpdates && now - current.lastUpdateCheckMs > UPDATE_CHECK_PERIOD_MS) {
                checkForUpdate()
            }
        }
    }

    /** Interroge les Releases du dépôt. Renvoie la version trouvée (même si elle n'est pas plus récente), ou null en cas d'échec. */
    suspend fun checkForUpdate(): UpdateInfo? {
        val found = runCatching { updateChecker.latest() }.getOrNull()
        if (found != null) {
            settings.setLastUpdateCheck(System.currentTimeMillis())
            _update.value = found.takeIf { it.isNewer }
        }
        return found
    }

    companion object {
        private const val UPDATE_CHECK_PERIOD_MS = 12 * 60 * 60 * 1000L
        val USER_AGENT = "VeloGo/${BuildConfig.VERSION_NAME} (Android ; application personnelle non commerciale)"
    }
}
