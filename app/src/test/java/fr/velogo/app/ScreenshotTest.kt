package fr.velogo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.velogo.app.data.Settings
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.components.ChartSeries
import fr.velogo.app.ui.history.HistoryContent
import fr.velogo.app.ui.history.RideDetailBody
import fr.velogo.app.ui.history.RideTrackData
import fr.velogo.app.ui.ride.RideChrome
import fr.velogo.app.ui.ride.RideMode
import fr.velogo.app.ui.settings.SettingsActions
import fr.velogo.app.ui.settings.SettingsContent
import fr.velogo.app.ui.settings.UpdateCheck
import fr.velogo.app.ui.theme.ThemeMode
import fr.velogo.app.ui.theme.VeloGoTheme
import fr.velogo.app.update.UpdateInfo
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Polyline
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.RideSnapshot
import fr.velogo.core.ride.RideState
import fr.velogo.core.ride.RideTotals
import fr.velogo.core.ride.TrackPoint
import org.junit.Test
import org.junit.runner.RunWith
import fr.velogo.app.offline.StorageChoice
import fr.velogo.app.ui.offline.OfflineMapsActions
import fr.velogo.app.ui.offline.OfflineMapsContent
import fr.velogo.app.ui.offline.OfflineMapsUiState
import fr.velogo.app.ui.offline.RegionRow
import fr.velogo.app.ui.offline.RegionStatus
import java.io.File
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.sin

/**
 * Rend les écrans en image sur l'ordinateur, sans téléphone ni émulateur (Robolectric + Roborazzi).
 * `./gradlew :app:recordRoborazziDebug` écrit les PNG dans app/build/outputs/roborazzi/.
 * Format de l'écran : celui du Galaxy A05s (411 × 914 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class ScreenshotTest {

    private val recording = RideSnapshot(
        state = RideState.RECORDING,
        startTimeMs = 1_800_000_000_000,
        totals = RideTotals(
            distanceM = 42_370.0, movingMs = 6_312_000, elapsedMs = 7_100_000, maxSpeedMps = 58.4 / 3.6,
            ascentM = 687.0, descentM = 512.0, minAltitudeM = 212.0, maxAltitudeM = 904.0,
        ),
        speedMps = 27.3 / 3.6,
        altitudeM = 846.0,
        slopePct = 5.4,
        accuracyM = 4.0,
        hasSignal = true,
        lat = 45.9, lon = 6.1,
    )

    private fun chrome(
        snapshot: RideSnapshot,
        mode: RideMode,
        theme: ThemeMode = ThemeMode.LIGHT,
        sunlight: Boolean = false,
        permission: Boolean = true,
    ): @Composable () -> Unit = {
        VeloGoTheme(mode = theme) {
            Box(Modifier.fillMaxSize().background(if (theme == ThemeMode.DARK) Color(0xFF1B2630) else Color(0xFFDDE7DD))) {
                RideChrome(
                    snapshot = snapshot, mode = mode, onMode = {}, themeMode = theme, sunlight = sunlight,
                    attribution = "© OpenStreetMap · OpenFreeMap", following = true, headingUp = false,
                    hasLocationPermission = permission, accuracyM = snapshot.accuracyM ?: 5.0,
                    topInset = 28.dp, bottomPadding = 80.dp, snackbar = SnackbarHostState(),
                    onLayers = {}, onRecenter = {}, onToggleHeading = {}, onRequestPermission = {},
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onStopHint = {},
                )
            }
        }
    }

    @Test
    fun guidon_clair() = captureRoboImage("$OUT/guidon_clair.png", content = chrome(recording, RideMode.DATA))

    @Test
    fun guidon_sombre() = captureRoboImage("$OUT/guidon_sombre.png", content = chrome(recording, RideMode.DATA, ThemeMode.DARK))

    @Test
    fun guidon_plein_soleil() = captureRoboImage("$OUT/guidon_plein_soleil.png", content = chrome(recording, RideMode.DATA, sunlight = true))

    @Test
    fun guidon_en_pause() = captureRoboImage(
        "$OUT/guidon_pause.png",
        content = chrome(recording.copy(state = RideState.PAUSED, speedMps = 0.0, slopePct = null), RideMode.DATA),
    )

    /** Début de sortie, avant la première position GPS : les valeurs absentes s'affichent en tirets discrets. */
    @Test
    fun guidon_sans_gps() = captureRoboImage(
        "$OUT/guidon_sans_gps.png",
        content = chrome(RideSnapshot(state = RideState.RECORDING, startTimeMs = 1_800_000_000_000), RideMode.DATA),
    )

    @Test
    @Config(sdk = [35], qualifiers = "w914dp-h411dp-420dpi")
    fun guidon_paysage() = captureRoboImage("$OUT/guidon_paysage.png", content = chrome(recording, RideMode.DATA))

    @Test
    fun carte_au_repos() = captureRoboImage("$OUT/carte_repos.png", content = chrome(RideSnapshot(), RideMode.MAP))

    @Test
    fun carte_en_sortie() = captureRoboImage("$OUT/carte_sortie.png", content = chrome(recording, RideMode.MAP))

    @Test
    fun carte_sans_autorisation() = captureRoboImage("$OUT/carte_sans_autorisation.png", content = chrome(RideSnapshot(), RideMode.MAP, permission = false))

    @Test
    fun carte_sombre() = captureRoboImage("$OUT/carte_sombre.png", content = chrome(recording, RideMode.MAP, ThemeMode.DARK))

    @Test
    fun historique() = captureRoboImage("$OUT/historique.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) { HistoryContent(sampleRides(), onOpenRide = {}, contentPadding = PaddingValues(bottom = 80.dp)) }
        }
    }

    @Test
    fun historique_sombre() = captureRoboImage("$OUT/historique_sombre.png") {
        VeloGoTheme(mode = ThemeMode.DARK) {
            Surface(Modifier.fillMaxSize()) { HistoryContent(sampleRides(), onOpenRide = {}, contentPadding = PaddingValues(bottom = 80.dp)) }
        }
    }

    @Test
    fun historique_vide() = captureRoboImage("$OUT/historique_vide.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) { HistoryContent(emptyList(), onOpenRide = {}, contentPadding = PaddingValues(bottom = 80.dp)) }
        }
    }

    @Test
    fun detail_sortie() = captureRoboImage("$OUT/detail_sortie.png") {
        val ride = sampleRides().first()
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) {
                RideDetailBody(ride = ride, data = sampleTrack(), selected = 140, onSelect = {}) { modifier ->
                    Box(modifier.background(Color(0xFFDDE7DD)))
                }
            }
        }
    }

    @Test
    fun reglages() = captureRoboImage("$OUT/reglages.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                SettingsContent(
                    settings = Settings(),
                    update = UpdateInfo("0.2.14", 14, "https://exemple.invalid/a.apk", "https://exemple.invalid", ""),
                    check = UpdateCheck.IDLE,
                    batteryOk = false,
                    notificationsOk = true,
                    isSamsung = true,
                    actions = NoActions,
                    onOpenAbout = {},
                    onOpenOfflineMaps = {},
                    contentPadding = PaddingValues(bottom = 80.dp),
                )
            }
        }
    }

    private object NoActions : SettingsActions {
        override fun setTheme(mode: ThemeMode) = Unit
        override fun setSunlight(value: Boolean) = Unit
        override fun setKeepScreenOn(value: Boolean) = Unit
        override fun setMaxBrightness(value: Boolean) = Unit
        override fun setAutoPause(value: Boolean) = Unit
        override fun setIgnCorrection(value: Boolean) = Unit
        override fun setCheckUpdates(value: Boolean) = Unit
        override fun checkNow() = Unit
    }

    /** Une boucle vallonnée de 40 km environ, pour les vignettes et les graphiques. */
    @Test
    fun cartes_hors_ligne() = captureRoboImage("$OUT/cartes_hors_ligne.png") {
        fun region(name: String, size: Long, status: RegionStatus, done: Long = 0, update: Long = 0, message: String? = null) =
            RegionRow(name.lowercase(), name, size, status, done, update, message)
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                OfflineMapsContent(
                    state = OfflineMapsUiState(
                        regions = listOf(
                            region("Auvergne-Rhône-Alpes", 1_240_000_000, RegionStatus.INSTALLED),
                            region("Bourgogne-Franche-Comté", 710_000_000, RegionStatus.DOWNLOADING, done = 312_000_000),
                            // (Pas d'état « vérification » ici : sa barre animée sans fin bloquerait la capture.)
                            region("Bretagne", 540_000_000, RegionStatus.WAITING, done = 96_000_000, message = "En attente du Wi-Fi"),
                            region("Corse", 81_000_000, RegionStatus.UPDATE_AVAILABLE, update = 47_000_000),
                            region("Grand Est", 890_000_000, RegionStatus.AVAILABLE),
                            region("Occitanie", 1_100_000_000, RegionStatus.FAILED, message = "Plus assez de place sur le téléphone."),
                        ),
                        catalogDate = "20/11/2026",
                        usedBytes = 1_321_000_000,
                        storages = listOf(
                            StorageChoice(0, "Téléphone", File("/"), 41_300_000_000),
                            StorageChoice(1, "Carte SD", File("/"), 12_800_000_000),
                        ),
                        selectedStorage = 0,
                        wifiOnly = true,
                        offlineLayerActive = false,
                    ),
                    actions = NoOfflineActions,
                    snackbar = SnackbarHostState(),
                    onBack = {},
                )
            }
        }
    }

    private object NoOfflineActions : OfflineMapsActions {
        override fun refresh() = Unit
        override fun download(regionId: String) = Unit
        override fun cancel(regionId: String) = Unit
        override fun delete(regionId: String) = Unit
        override fun setWifiOnly(value: Boolean) = Unit
        override fun setStorage(index: Int) = Unit
        override fun useOfflineLayer() = Unit
    }

    private fun samplePoints(seed: Int = 0): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var position = LatLon(45.90 + seed * 0.01, 6.12)
        var time = 1_800_000_000_000L
        val n = 2400
        for (i in 0 until n) {
            val bearing = (i * 360.0 / n + 40 * sin(i / 90.0) + seed * 30) % 360.0
            position = Geo.offset(position.lat, position.lon, bearing, 16.0)
            time += 2_500
            val altitude = 450.0 + 260.0 * sin(2 * PI * i / n) + 60.0 * sin(i / 55.0)
            val speed = (6.5 + 3.5 * sin(i / 40.0 + 1.0) - 2.0 * sin(2 * PI * i / n)).coerceAtLeast(1.5)
            out.add(TrackPoint(time, position.lat, position.lon, altitude, speed, 4.0, 0))
        }
        return out
    }

    private fun sampleTrack(): RideTrackData {
        val points = samplePoints()
        val profile = RideAnalyzer.profile(points, 360, AltitudeSource.TERRAIN)
        val xs = FloatArray(profile.size) { (profile[it].distanceM / 1000.0).toFloat() }
        return RideTrackData(
            segments = listOf(points.map { LatLon(it.lat, it.lon) }),
            bounds = BoundingBox.of(points.map { LatLon(it.lat, it.lon) }),
            profile = profile,
            altitude = ChartSeries(xs, FloatArray(profile.size) { profile[it].altitudeM!!.toFloat() }),
            speed = ChartSeries(xs, FloatArray(profile.size) { (profile[it].speedMps!! * 3.6).toFloat() }),
        )
    }

    private fun sampleRides(): List<RideEntity> {
        val day = 86_400_000L
        val base = 1_795_000_000_000L
        return listOf(
            Triple("Tour du lac d'Annecy", 0L, 0),
            Triple("Sortie du matin", 2 * day, 1),
            Triple("Col de la Forclaz", 9 * day, 2),
            Triple("Sortie du soir", 31 * day, 3),
            Triple("Voie verte jusqu'à Albertville", 38 * day, 4),
        ).mapIndexed { index, (name, age, seed) ->
            val points = samplePoints(seed)
            val totals = RideAnalyzer.summarize(points, AltitudeSource.TERRAIN)
            RideEntity(
                id = index + 1L, name = name, startTimeMs = base - age, endTimeMs = base - age + totals.elapsedMs,
                status = RideEntity.STATUS_FINISHED, distanceM = totals.distanceM * (1 + seed * 0.2), movingMs = totals.movingMs,
                elapsedMs = totals.elapsedMs + 600_000, maxSpeedMps = totals.maxSpeedMps, ascentM = totals.ascentM,
                descentM = totals.descentM, minAltitudeM = totals.minAltitudeM, maxAltitudeM = totals.maxAltitudeM,
                pointCount = points.size,
                preview = Polyline.encode(fr.velogo.core.geo.Simplify.toMaxPoints(points.map { LatLon(it.lat, it.lon) }, 160)),
                altitudeSource = if (seed % 2 == 0) RideEntity.ALTITUDE_IGN else RideEntity.ALTITUDE_GNSS,
            )
        }
    }

    private companion object {
        const val OUT = "build/outputs/roborazzi"
    }
}
