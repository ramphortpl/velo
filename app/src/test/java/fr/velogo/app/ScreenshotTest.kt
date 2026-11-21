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
import androidx.compose.ui.Alignment
import fr.velogo.app.data.db.SavedRouteEntity
import fr.velogo.app.nav.GuidanceState
import fr.velogo.app.nav.NavigationController
import fr.velogo.app.nav.PlanPoint
import fr.velogo.app.nav.PlannerState
import fr.velogo.app.nav.RouteOption
import fr.velogo.app.search.Place
import fr.velogo.app.ui.nav.PlaceSearchContent
import fr.velogo.app.ui.nav.PlanTarget
import fr.velogo.app.ui.nav.PlannerActions
import fr.velogo.app.ui.nav.PlannerPanel
import fr.velogo.app.ui.nav.SavedRoutesList
import fr.velogo.app.ui.nav.SearchResult
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.NavState
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RoutePreference
import fr.velogo.core.nav.WayMix
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
        override fun setVoiceGuidance(value: Boolean) = Unit
        override fun setVibrateGuidance(value: Boolean) = Unit
        override fun setRiderPower(watts: Int) = Unit
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

    // --- Itinéraires et guidage -------------------------------------------------------------------------------

    /** Itinéraire d'exemple : une trace vallonnée d'une quarantaine de kilomètres, avec quelques consignes. */
    private fun sampleRoute(seed: Int = 0, scale: Double = 1.0): Route {
        val base = NavigationController.routeFromTrack(samplePoints(seed))!!
        val n = base.points.size
        val instructions = listOf(
            Instruction(n / 12, base.distances[n / 12], Maneuver.LEFT),
            Instruction(n / 12 + 6, base.distances[n / 12 + 6], Maneuver.SLIGHT_RIGHT),
            Instruction(n / 3, base.distances[n / 3], Maneuver.ROUNDABOUT, 2),
            Instruction(n - 1, base.distanceM, Maneuver.ARRIVE),
        )
        val total = base.distanceM
        return Route(
            base.points, base.elevations, base.distances, durationS = total / 5.4 * scale, ascentM = 520.0 * scale, descentM = 505.0 * scale,
            instructions = instructions, waypoints = listOf(base.points.first(), base.points.last()),
            mix = WayMix(cyclewayM = total * 0.34, quietM = total * 0.38, roadM = total * 0.17, busyM = total * 0.03, pathM = total * 0.08, unpavedM = total * 0.11),
        )
    }

    private object NoPlannerActions : PlannerActions {
        override fun close() = Unit
        override fun setBike(bike: BikeType) = Unit
        override fun pick(target: PlanTarget) = Unit
        override fun useMyPosition() = Unit
        override fun removeVia(index: Int) = Unit
        override fun removeEnd() = Unit
        override fun reverse() = Unit
        override fun compute() = Unit
        override fun edit() = Unit
        override fun select(index: Int) = Unit
        override fun startGuidance() = Unit
        override fun toggleVoice() = Unit
        override fun save() = Unit
        override fun share() = Unit
        override fun importGpx() = Unit
        override fun openSaved() = Unit
        override fun openOfflineMaps() = Unit
    }

    private fun planner(state: PlannerState, hasData: Boolean = true): @Composable () -> Unit = {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize().background(Color(0xFFDDE7DD)), contentAlignment = Alignment.BottomCenter) {
                PlannerPanel(state = state, hasRoutingData = hasData, voiceEnabled = true, actions = NoPlannerActions, maxHeight = 560.dp)
            }
        }
    }

    @Test
    fun itineraire_points() = captureRoboImage(
        "$OUT/itineraire_points.png",
        content = planner(
            PlannerState(
                open = true, bike = BikeType.HYBRID,
                vias = listOf(PlanPoint(LatLon(45.8, 6.2), "Col de Leschaux")),
                end = PlanPoint(LatLon(45.7, 6.3), "Gare d'Albertville"),
            )
        ),
    )

    @Test
    fun itineraire_sans_donnees() = captureRoboImage(
        "$OUT/itineraire_sans_donnees.png",
        content = planner(PlannerState(open = true, bike = BikeType.ROAD), hasData = false),
    )

    @Test
    fun itineraire_propositions() = captureRoboImage(
        "$OUT/itineraire_propositions.png",
        content = planner(
            PlannerState(
                open = true, bike = BikeType.HYBRID, end = PlanPoint(LatLon(45.7, 6.3), "Gare d'Albertville"),
                options = listOf(
                    RouteOption(sampleRoute(), listOf(RoutePreference.FAST)),
                    RouteOption(sampleRoute(1, 1.08), listOf(RoutePreference.SAFE)),
                    RouteOption(sampleRoute(2, 0.8), listOf(RoutePreference.FLAT)),
                ),
                selected = 0,
            )
        ),
    )

    private fun guidance(nav: NavState, rerouting: Boolean = false) =
        GuidanceState(sampleRoute(), nav, BikeType.HYBRID, RoutePreference.FAST, "Gare d'Albertville", rerouting = rerouting)

    private fun guided(mode: RideMode, state: GuidanceState, sunlight: Boolean = false): @Composable () -> Unit = {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize().background(Color(0xFFDDE7DD))) {
                RideChrome(
                    snapshot = recording, mode = mode, onMode = {}, themeMode = ThemeMode.LIGHT, sunlight = sunlight,
                    attribution = "© OpenStreetMap · Protomaps · Mapterhorn (IGN)", following = true, headingUp = true,
                    hasLocationPermission = true, accuracyM = 4.0,
                    topInset = 28.dp, bottomPadding = 80.dp, snackbar = SnackbarHostState(),
                    onLayers = {}, onRecenter = {}, onToggleHeading = {}, onRequestPermission = {},
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onStopHint = {},
                    guidance = state,
                )
            }
        }
    }

    @Test
    fun guidage_carte() {
        val route = sampleRoute()
        val nav = NavState(
            progressM = 2_300.0, remainingM = route.distanceM - 2_300.0, remainingS = 5_400.0, offsetM = 3.0, isOffRoute = false,
            next = route.instructions[0], distanceToNextM = 180.0, then = route.instructions[1], arrived = false,
        )
        captureRoboImage("$OUT/guidage_carte.png", content = guided(RideMode.MAP, guidance(nav)))
    }

    @Test
    fun guidage_guidon() {
        val route = sampleRoute()
        val nav = NavState(
            progressM = 11_000.0, remainingM = route.distanceM - 11_000.0, remainingS = 4_100.0, offsetM = 2.0, isOffRoute = false,
            next = route.instructions[2], distanceToNextM = 1_240.0, then = null, arrived = false,
        )
        captureRoboImage("$OUT/guidage_guidon.png", content = guided(RideMode.DATA, guidance(nav)))
    }

    @Test
    fun guidage_hors_itineraire() {
        val route = sampleRoute()
        val nav = NavState(
            progressM = 6_000.0, remainingM = route.distanceM - 6_000.0, remainingS = 4_900.0, offsetM = 85.0, isOffRoute = true,
            next = route.instructions[2], distanceToNextM = 4_000.0, then = null, arrived = false,
        )
        captureRoboImage("$OUT/guidage_hors_itineraire.png", content = guided(RideMode.MAP, guidance(nav, rerouting = true)))
    }

    @Test
    fun recherche_adresse() = captureRoboImage("$OUT/recherche_adresse.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                PlaceSearchContent(
                    title = "Où vas-tu ?", query = "gare d'alb", onQuery = {},
                    result = SearchResult.Found(
                        listOf(
                            Place("Gare d'Albertville", "Gare voyageurs et fret · 73200 Albertville", LatLon(45.67, 6.38)),
                            Place("Avenue de la Gare", "73200 Albertville", LatLon(45.67, 6.38)),
                            Place("Place de la Gare", "74000 Annecy", LatLon(45.9, 6.12)),
                        )
                    ),
                    offerMyPosition = true, onPlace = {}, onMyPosition = {}, onDismiss = {},
                )
            }
        }
    }

    @Test
    fun mes_itineraires() = captureRoboImage("$OUT/mes_itineraires.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                SavedRoutesList(
                    routes = listOf(
                        SavedRouteEntity(1, "Tour du lac d'Annecy", 1_795_000_000_000, "HYBRID", 41_800.0, 310.0, 7_400.0, ""),
                        SavedRouteEntity(2, "Col de la Forclaz par Talloires", 1_794_000_000_000, "ROAD", 63_200.0, 1_240.0, 12_900.0, ""),
                        SavedRouteEntity(3, "Semnoz par les crêtes", 1_793_000_000_000, "MTB", 28_400.0, 980.0, 9_800.0, ""),
                    ),
                    onOpen = {}, onDelete = {},
                )
            }
        }
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
