package fr.velogo.app

import android.app.Application
import fr.velogo.app.data.RideAnalysis
import fr.velogo.app.data.RideArchive
import fr.velogo.app.data.RideRepository
import fr.velogo.app.data.SavedRouteRepository
import fr.velogo.app.data.SegmentRepository
import fr.velogo.app.data.SettingsRepository
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.app.elevation.ElevationCorrector
import fr.velogo.app.elevation.IgnAltimetry
import fr.velogo.app.elevation.ReliefSource
import fr.velogo.app.location.GpsSource
import fr.velogo.app.nav.NavigationController
import fr.velogo.app.nav.RouteService
import fr.velogo.app.nav.VoiceGuide
import fr.velogo.app.offline.OfflineMaps
import fr.velogo.app.recording.RecorderEvent
import fr.velogo.app.recording.RideRecorder
import fr.velogo.app.search.IgnGeocoder
import fr.velogo.app.update.UpdateChecker
import fr.velogo.app.update.UpdateInfo
import fr.velogo.app.weather.OpenMeteo
import fr.velogo.app.weather.WeatherController
import fr.velogo.app.weather.WeatherNotifier
import fr.velogo.app.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
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
    val savedRoutes: SavedRouteRepository by lazy { SavedRouteRepository(database) }
    val segments: SegmentRepository by lazy { SegmentRepository(database, rides) }

    /** Meilleurs temps et passages sur les segments, calculés une fois par sortie. */
    val analysis: RideAnalysis by lazy { RideAnalysis(database, rides) }

    /** Sauvegarde de toutes les sorties dans une archive, et import de sorties depuis un fichier. */
    val archive: RideArchive by lazy { RideArchive(app, rides) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val gps: GpsSource by lazy { GpsSource(app, appScope) }
    val recorder: RideRecorder by lazy { RideRecorder(rides) { lat, lon -> relief.altitude(lat, lon) } }

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

    /** Cartes hors ligne : catalogue, fichiers installés, téléchargements. */
    val offline: OfflineMaps by lazy { OfflineMaps(app, http, settings, appScope) }

    /** Altitude du sol d'après les packs de relief installés. */
    val relief: ReliefSource by lazy { ReliefSource { offline.store.tiles } }

    val elevationCorrector: ElevationCorrector by lazy { ElevationCorrector(rides, IgnAltimetry(http), relief) }

    /** Itinéraires : calcul hors ligne, recherche d'adresses, guidage. */
    val routeService: RouteService by lazy { RouteService(app, offline.store, relief) }
    val geocoder: IgnGeocoder by lazy { IgnGeocoder(http) }
    /** Voix et vibrations : consignes du guidage et alerte pluie. */
    val voice: VoiceGuide by lazy { VoiceGuide(app) }
    val navigation: NavigationController by lazy {
        NavigationController(appScope, gps, routeService, geocoder, settings, voice)
    }

    /** Météo : celle du lieu, celle des itinéraires, alerte pluie. */
    val weather: WeatherController by lazy {
        WeatherController(
            appScope, WeatherRepository(OpenMeteo(http), File(app.cacheDir, "meteo")), settings,
            gps, recorder, navigation, voice, WeatherNotifier(app),
        )
    }
    val updateChecker: UpdateChecker by lazy { UpdateChecker(http) }

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    /** Version plus récente disponible, s'il y en a une. */
    val update: StateFlow<UpdateInfo?> = _update

    /** Tâches de fond lancées une fois pour toutes au démarrage de l'appli. */
    fun start() {
        offline.start()
        weather.start()
        // Dès qu'une sortie est enregistrée, on recale son altitude sur le relief : celui de l'IGN en ligne si le
        // réglage est actif, sinon (ou sans réseau) celui des cartes hors ligne.
        appScope.launch {
            recorder.events.collect { event ->
                if (event is RecorderEvent.Saved) {
                    val online = settings.settings.first().ignAltitudeCorrection
                    launch {
                        runCatching { elevationCorrector.correct(event.rideId, online) }
                        // Une fois l'altitude recalée : meilleurs temps de la sortie et passages sur les segments.
                        runCatching { analysis.analyze(event.rideId) }
                    }
                }
            }
        }
        // Les sorties récentes qui n'avaient pas pu être recalées (pas de réseau ce jour-là) sont retentées.
        appScope.launch {
            delay(STARTUP_RETRY_DELAY_MS)
            if (!recorder.isActive) {
                runCatching { elevationCorrector.retryPending(online = settings.settings.first().ignAltitudeCorrection) }
                // Sorties jamais analysées (enregistrées avant cette version, ou importées).
                runCatching { analysis.analyzePending() }
            }
        }
        // Vérification discrète des mises à jour, au plus deux fois par jour. La version distribuée par Google Play
        // ne s'en occupe pas : c'est le magasin qui la met à jour, et lui seul en a le droit.
        if (BuildConfig.SELF_UPDATE) {
            appScope.launch {
                val current = settings.settings.first()
                val now = System.currentTimeMillis()
                if (!BuildConfig.DEBUG && current.checkUpdates && now - current.lastUpdateCheckMs > UPDATE_CHECK_PERIOD_MS) {
                    checkForUpdate()
                }
            }
        }
    }

    /** Interroge les Releases du dépôt. Renvoie la version trouvée (même si elle n'est pas plus récente), ou null en cas d'échec. */
    suspend fun checkForUpdate(): UpdateInfo? {
        if (!BuildConfig.SELF_UPDATE) return null
        val found = runCatching { updateChecker.latest() }.getOrNull()
        if (found != null) {
            settings.setLastUpdateCheck(System.currentTimeMillis())
            _update.value = found.takeIf { it.isNewer }
        }
        return found
    }

    companion object {
        private const val UPDATE_CHECK_PERIOD_MS = 12 * 60 * 60 * 1000L
        private const val STARTUP_RETRY_DELAY_MS = 20_000L
        val USER_AGENT = "VeloGo/${BuildConfig.VERSION_NAME} (Android ; application gratuite, non commerciale)"
    }
}
