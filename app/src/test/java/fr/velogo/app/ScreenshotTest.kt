package fr.velogo.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import android.graphics.BitmapFactory
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.widthIn
import fr.velogo.app.ui.BottomFade
import fr.velogo.app.ui.READING_WIDTH
import fr.velogo.app.ui.FloatingTabBar
import fr.velogo.app.ui.Tab
import androidx.compose.ui.res.painterResource
import fr.velogo.app.nav.PosterText
import fr.velogo.app.nav.RoutePoster
import fr.velogo.app.ui.nav.ShareRouteContent
import org.robolectric.RuntimeEnvironment
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
import fr.velogo.app.location.GpsStatus
import fr.velogo.app.location.LocationAccess
import fr.velogo.app.ui.ride.GpsChipModel
import fr.velogo.app.ui.ride.GpsSheetContent
import fr.velogo.app.ui.ride.GpsTone
import fr.velogo.app.ui.ride.RideChrome
import fr.velogo.app.ui.ride.RideMode
import fr.velogo.app.ui.ride.gpsChip
import fr.velogo.app.ui.ride.gpsSheet
import fr.velogo.app.ui.settings.AboutScreen
import fr.velogo.app.ui.settings.PrivacyContent
import fr.velogo.app.ui.settings.parsePolicy
import fr.velogo.app.ui.settings.ArchiveState
import fr.velogo.app.ui.settings.SettingsActions
import fr.velogo.app.ui.settings.SettingsContent
import fr.velogo.app.ui.settings.UpdateCheck
import fr.velogo.app.ui.theme.ThemeMode
import fr.velogo.app.ui.theme.VeloGoTheme
import fr.velogo.app.update.UpdateInfo
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.RouteShape
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
import fr.velogo.app.ui.nav.PlannerWeather
import fr.velogo.app.ui.weather.RouteWeatherSection
import fr.velogo.app.ui.weather.WeatherContent
import fr.velogo.app.ui.weather.WeatherGlance
import fr.velogo.app.ui.weather.WeatherSheetState
import fr.velogo.app.ui.weather.departureChoices
import fr.velogo.app.ui.weather.weatherGlance
import fr.velogo.core.weather.RainStep
import fr.velogo.core.weather.RouteWeather
import fr.velogo.core.weather.WeatherPoint
import fr.velogo.core.weather.WeatherReport
import fr.velogo.app.ui.nav.SavedRoutesList
import fr.velogo.app.ui.routes.SavedRoutesContent
import fr.velogo.app.ui.routes.savedRouteRows
import fr.velogo.core.geo.Simplify
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
import fr.velogo.app.data.db.BestEffortEntity
import fr.velogo.app.data.db.SegmentEffortEntity
import fr.velogo.app.data.db.SegmentEntity
import fr.velogo.app.ui.segments.RideSegmentRow
import fr.velogo.app.ui.segments.SegmentDetailBody
import fr.velogo.app.ui.segments.SegmentEditor
import fr.velogo.app.ui.segments.SegmentRow
import fr.velogo.app.ui.segments.SegmentsContent
import fr.velogo.app.ui.segments.effortRows
import fr.velogo.app.ui.stats.StatsContent
import fr.velogo.app.ui.stats.StatsUiState
import fr.velogo.app.ui.stats.buildStats
import fr.velogo.core.stats.Period
import java.time.LocalDate
import java.time.ZoneId
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

    /** La pastille du GPS quand tout va bien, et les états que le téléphone peut annoncer quand il cherche. */
    private val gpsOk = GpsChipModel(GpsTone.GOOD, "GPS ±4 m")
    private val gpsSearching = GpsStatus(listening = true, satellitesSeen = 3, satellitesUsed = 0, searchingS = 48)
    private val gpsApproximate = gpsSearching.copy(hasApproximate = true, approximateAccuracyM = 38.0)

    private fun chrome(
        snapshot: RideSnapshot,
        mode: RideMode,
        theme: ThemeMode = ThemeMode.LIGHT,
        sunlight: Boolean = false,
        permission: Boolean = true,
        weather: WeatherGlance? = null,
        gps: GpsChipModel = gpsOk,
    ): @Composable () -> Unit = {
        VeloGoTheme(mode = theme) {
            Box(Modifier.fillMaxSize()) {
                MapBackdrop(dark = theme == ThemeMode.DARK)
                RideChrome(
                    snapshot = snapshot, mode = mode, onMode = {}, themeMode = theme, sunlight = sunlight,
                    attribution = "© OpenStreetMap · OpenFreeMap", following = true, headingUp = false,
                    hasLocationPermission = permission, gps = gps,
                    topInset = 28.dp, bottomPadding = 80.dp, snackbar = SnackbarHostState(),
                    onLayers = {}, onRecenter = {}, onToggleHeading = {}, onRequestPermission = {},
                    onStart = {}, onPause = {}, onResume = {}, onStop = {}, onStopHint = {},
                    weather = weather,
                )
                FloatingTabBar(Tab.RIDE, badge = null, onSelect = {}, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    /**
     * Un vrai morceau de carte (OpenFreeMap, Corte) derrière l'habillage : c'est sur lui qu'on juge les surfaces
     * translucides. Sur l'émulateur, la carte est dessinée par MapLibre ; ici, c'est une image.
     */
    @Composable
    private fun MapBackdrop(dark: Boolean) {
        val bitmap = remember(dark) {
            javaClass.getResourceAsStream(if (dark) "/fond_carte_sombre.jpg" else "/fond_carte.jpg")!!.use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        }
        Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }

    /** Un écran d'onglet comme dans l'appli : son contenu, le fondu du bas et la barre d'onglets flottante. */
    private fun tabScreen(tab: Tab, theme: ThemeMode = ThemeMode.LIGHT, badge: Tab? = null, content: @Composable () -> Unit): @Composable () -> Unit = {
        VeloGoTheme(mode = theme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    BottomFade(80.dp, Modifier.align(Alignment.BottomCenter))
                    FloatingTabBar(tab, badge = badge, onSelect = {}, modifier = Modifier.align(Alignment.BottomCenter))
                }
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
        content = chrome(
            RideSnapshot(state = RideState.RECORDING, startTimeMs = 1_800_000_000_000), RideMode.DATA,
            gps = gpsChip(LocationAccess.PRECISE, gpsSearching),
        ),
    )

    // --- État du GPS : pastille en haut de l'écran, feuille d'explication ------------------------------------

    /** Le GPS cherche encore, la carte montre déjà la position déduite du Wi-Fi et du réseau : pastille orange. */
    @Test
    fun carte_position_approchee() = captureRoboImage(
        "$OUT/carte_position_approchee.png",
        content = chrome(RideSnapshot(), RideMode.MAP, gps = gpsChip(LocationAccess.PRECISE, gpsApproximate)),
    )

    /** Écran étroit (360 dp) et texte le plus long de la pastille : la bascule Carte / Données lui cède la place. */
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-420dpi")
    fun carte_recherche_gps_ecran_etroit() = captureRoboImage(
        "$OUT/carte_recherche_gps_ecran_etroit.png",
        content = chrome(RideSnapshot(), RideMode.MAP, gps = gpsChip(LocationAccess.PRECISE, gpsSearching)),
    )

    private fun gpsSheetImage(name: String, access: LocationAccess, status: GpsStatus, theme: ThemeMode = ThemeMode.LIGHT) =
        captureRoboImage("$OUT/$name.png") {
            VeloGoTheme(mode = theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(top = 28.dp)) {
                        GpsSheetContent(gpsSheet(access, status), onRemedy = {}, onReset = {})
                    }
                }
            }
        }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h520dp-420dpi")
    fun gps_feuille_recherche() = gpsSheetImage("gps_feuille_recherche", LocationAccess.PRECISE, gpsSearching)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h520dp-420dpi")
    fun gps_feuille_position_approchee() = gpsSheetImage("gps_feuille_position_approchee", LocationAccess.PRECISE, gpsApproximate)

    /** Recherche qui dure : la réinitialisation du GPS est proposée en plus. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h640dp-420dpi")
    fun gps_feuille_recherche_longue() = gpsSheetImage(
        "gps_feuille_recherche_longue", LocationAccess.PRECISE,
        gpsSearching.copy(satellitesSeen = 0, searchingS = 150), theme = ThemeMode.DARK,
    )

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h520dp-420dpi")
    fun gps_feuille_bon_signal() = gpsSheetImage(
        "gps_feuille_bon_signal", LocationAccess.PRECISE,
        GpsStatus(listening = true, hasFix = true, accuracyM = 4.0, satellitesSeen = 19, satellitesUsed = 12),
    )

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h520dp-420dpi")
    fun gps_feuille_signal_faible() = gpsSheetImage(
        "gps_feuille_signal_faible", LocationAccess.PRECISE,
        GpsStatus(listening = true, hasFix = true, accuracyM = 48.0, satellitesSeen = 6, satellitesUsed = 4),
    )

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h520dp-420dpi")
    fun gps_feuille_gps_coupe() = gpsSheetImage(
        "gps_feuille_gps_coupe", LocationAccess.PRECISE,
        GpsStatus(listening = true, gpsEnabled = false, hasApproximate = true, approximateAccuracyM = 25.0),
    )

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h520dp-420dpi")
    fun gps_feuille_position_approximative() = gpsSheetImage(
        "gps_feuille_position_approximative", LocationAccess.APPROXIMATE,
        GpsStatus(listening = true, hasApproximate = true, approximateAccuracyM = 2_000.0),
    )

    // --- Grand écran -------------------------------------------------------------------------------------------
    // Sur une tablette ou un téléphone déplié (largeur d'au moins 600 dp), Android ne tient plus compte du
    // verrouillage en portrait : l'appli doit s'y présenter correctement, couchée comme debout.

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-240dpi")
    fun tablette_guidon() = captureRoboImage("$OUT/tablette_guidon.png", content = chrome(recording, RideMode.DATA))

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-240dpi")
    fun tablette_carte() = captureRoboImage("$OUT/tablette_carte.png", content = chrome(recording, RideMode.MAP))

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-240dpi")
    fun tablette_historique() = captureRoboImage(
        "$OUT/tablette_historique.png",
        content = tabScreen(Tab.HISTORY) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = READING_WIDTH)) {
                    HistoryContent(sampleRides(), onOpenRide = {}, contentPadding = PaddingValues(bottom = 112.dp), today = sampleToday)
                }
            }
        },
    )

    @Test
    fun carte_au_repos() = captureRoboImage("$OUT/carte_repos.png", content = chrome(RideSnapshot(), RideMode.MAP))

    @Test
    fun carte_en_sortie() = captureRoboImage("$OUT/carte_sortie.png", content = chrome(recording, RideMode.MAP))

    @Test
    fun carte_sans_autorisation() = captureRoboImage("$OUT/carte_sans_autorisation.png", content = chrome(RideSnapshot(), RideMode.MAP, permission = false))

    /** La même explication sur un petit écran (360 × 740 dp) : elle ne doit pas recouvrir les boutons de la carte. */
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-420dpi")
    fun carte_sans_autorisation_petit_ecran() = captureRoboImage(
        "$OUT/carte_sans_autorisation_petit_ecran.png",
        content = chrome(RideSnapshot(), RideMode.MAP, permission = false),
    )

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
    fun carte_sans_autorisation_sombre() = captureRoboImage(
        "$OUT/carte_sans_autorisation_sombre.png",
        content = chrome(RideSnapshot(), RideMode.MAP, ThemeMode.DARK, permission = false),
    )

    @Test
    fun carte_sombre() = captureRoboImage("$OUT/carte_sombre.png", content = chrome(recording, RideMode.MAP, ThemeMode.DARK))

    @Test
    fun historique() = captureRoboImage(
        "$OUT/historique.png",
        content = tabScreen(Tab.HISTORY) { HistoryContent(sampleRides(), onOpenRide = {}, contentPadding = PaddingValues(bottom = 112.dp), today = sampleToday) },
    )

    @Test
    fun historique_sombre() = captureRoboImage(
        "$OUT/historique_sombre.png",
        content = tabScreen(Tab.HISTORY, ThemeMode.DARK) { HistoryContent(sampleRides(), onOpenRide = {}, contentPadding = PaddingValues(bottom = 112.dp), today = sampleToday) },
    )

    @Test
    fun historique_vide() = captureRoboImage("$OUT/historique_vide.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) { HistoryContent(emptyList(), onOpenRide = {}, contentPadding = PaddingValues(bottom = 112.dp)) }
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

    /** Une sortie avec un record sur 10 km et deux passages sur des segments. */
    @Test
    fun detail_sortie_records() = captureRoboImage("$OUT/detail_sortie_records.png") {
        val ride = sampleRides().first()
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) {
                RideDetailBody(
                    ride = ride, data = sampleTrack(), selected = null, onSelect = {},
                    segments = listOf(
                        RideSegmentRow(1, "Montée du Semnoz", 1_421_000, ride.startTimeMs + 900_000, rank = 1, total = 4),
                        RideSegmentRow(2, "Ligne droite de Sévrier", 312_000, ride.startTimeMs + 3_000_000, rank = 3, total = 7),
                    ),
                    bests = listOf("10 km" to "21:34"),
                ) { modifier -> Box(modifier.background(Color(0xFFDDE7DD))) }
            }
        }
    }

    // --- Statistiques et segments -----------------------------------------------------------------------------

    /** Le vendredi de la semaine des deux sorties les plus récentes de l'exemple. */
    private val sampleToday = LocalDate.of(2026, 11, 20)

    private fun sampleStats(period: Period, back: Int = 0): StatsUiState {
        val rides = sampleRides()
        val efforts = rides.flatMap { ride ->
            listOf(5_000 to 640_000L, 10_000 to 1_340_000L, 20_000 to 2_790_000L).map { (distance, duration) ->
                BestEffortEntity(ride.id, distance, duration + ride.id * 23_000L, ride.startTimeMs + 600_000)
            }
        }
        return buildStats(rides, efforts, period, back, today = sampleToday, zone = ZoneId.of("UTC"))
    }

    private fun stats(state: StatsUiState, theme: ThemeMode = ThemeMode.LIGHT): @Composable () -> Unit = {
        VeloGoTheme(mode = theme) {
            Surface(Modifier.fillMaxSize()) { StatsContent(state, onBack = {}, onPeriod = {}, onSelect = {}, onOpenRide = {}) }
        }
    }

    @Test
    fun statistiques() = captureRoboImage("$OUT/statistiques.png", content = stats(sampleStats(Period.WEEK)))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1700dp-420dpi")
    fun statistiques_mois_complet() = captureRoboImage("$OUT/statistiques_mois.png", content = stats(sampleStats(Period.MONTH, back = 1), ThemeMode.DARK))

    @Test
    fun statistiques_vides() = captureRoboImage("$OUT/statistiques_vides.png", content = stats(buildStats(emptyList(), emptyList(), Period.WEEK, 0, sampleToday)))

    private fun sampleSegment(): SegmentEntity {
        val points = samplePoints().subList(300, 520)
        val line = points.map { LatLon(it.lat, it.lon) }
        val box = BoundingBox.of(line)!!
        return SegmentEntity(
            id = 1, name = "Montée du Semnoz", createdAtMs = 1_795_000_000_000L, distanceM = Geo.lengthM(line), ascentM = 212.0,
            startAltitudeM = 512.0, endAltitudeM = 724.0, points = Polyline.encode(line),
            minLat = box.minLat, minLon = box.minLon, maxLat = box.maxLat, maxLon = box.maxLon,
        )
    }

    @Test
    fun segments() = captureRoboImage("$OUT/segments.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) {
                SegmentsContent(
                    rows = listOf(
                        SegmentRow(1, "Montée du Semnoz", "3,5 km · ↗ 212 m · 6,0 %", efforts = 4, bestMs = 1_421_000),
                        SegmentRow(2, "Ligne droite de Sévrier", "1,8 km", efforts = 7, bestMs = 198_000),
                        SegmentRow(3, "Tour du lac par la voie verte", "38,4 km · ↗ 190 m", efforts = 1, bestMs = 5_940_000),
                        SegmentRow(4, "Côte de Talloires", "900 m · ↗ 64 m · 7,1 %", efforts = 0, bestMs = null),
                    ),
                    onBack = {}, onOpenSegment = {},
                )
            }
        }
    }

    @Test
    fun segments_vide() = captureRoboImage("$OUT/segments_vide.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) { SegmentsContent(rows = emptyList(), onBack = {}, onOpenSegment = {}) }
        }
    }

    @Test
    fun segment_detail() = captureRoboImage("$OUT/segment_detail.png") {
        val rides = sampleRides()
        val segment = sampleSegment()
        val efforts = listOf(1_421_000L, 1_468_000L, 1_533_000L, 1_610_000L).mapIndexed { i, elapsed ->
            SegmentEffortEntity(id = i + 1L, segmentId = 1, rideId = rides[i].id, startTimeMs = rides[i].startTimeMs + 900_000, elapsedMs = elapsed)
        }
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) {
                SegmentDetailBody(segment, effortRows(segment, efforts, rides), searching = false, onOpenRide = {}) { modifier ->
                    Box(modifier.background(Color(0xFFDDE7DD)))
                }
            }
        }
    }

    /** Création d'un segment : le morceau choisi sur le profil, entre les kilomètres 6 et 9,5. */
    @Test
    fun segment_creation() = captureRoboImage("$OUT/segment_creation.png") {
        val data = sampleTrack()
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) {
                RideDetailBody(
                    ride = sampleRides().first(), data = data, selected = null, onSelect = {},
                    editor = { modifier ->
                        SegmentEditor(
                            altitude = data.altitude, range = 6f..9.5f, totalKm = data.altitude.xs.last(), name = "Montée du Semnoz",
                            facts = "3,5 km · ↗ 212 m · 6,0 %", error = null,
                            onRange = {}, onName = {}, onCancel = {}, onCreate = {}, modifier = modifier,
                        )
                    },
                ) { modifier -> Box(modifier.background(Color(0xFFDDE7DD))) }
            }
        }
    }

    @Test
    fun reglages() = captureRoboImage(
        "$OUT/reglages.png",
        content = tabScreen(Tab.SETTINGS, badge = Tab.SETTINGS) {
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
                contentPadding = PaddingValues(bottom = 112.dp),
            )
        },
    )

    /**
     * La version distribuée par Google Play : pas de mise à jour par GitHub (ni bandeau ni bouton « Vérifier »), et
     * l'économie de batterie se règle sur la fiche de l'appli.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h3300dp-420dpi")
    fun reglages_version_play() = captureRoboImage("$OUT/reglages_version_play.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                SettingsContent(
                    settings = Settings(),
                    update = UpdateInfo("9.9.99", 99, "https://exemple.invalid/a.apk", "https://exemple.invalid", ""),
                    check = UpdateCheck.IDLE,
                    batteryOk = false,
                    notificationsOk = true,
                    isSamsung = true,
                    actions = NoActions,
                    onOpenAbout = {},
                    onOpenOfflineMaps = {},
                    contentPadding = PaddingValues(bottom = 112.dp),
                    selfUpdate = false,
                    directBatteryExemption = false,
                )
            }
        }
    }

    /** La politique de confidentialité, telle que l'appli l'affiche : le texte est celui du dépôt. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h4200dp-420dpi")
    fun confidentialite() = captureRoboImage("$OUT/confidentialite.png") {
        val file = listOf(File("docs/CONFIDENTIALITE.md"), File("../docs/CONFIDENTIALITE.md")).first { it.isFile }
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                PrivacyContent(parsePolicy(file.readText()), onBack = {})
            }
        }
    }

    /** La page des réglages en entier (écran fictif très haut), sauvegarde des sorties en cours. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h3300dp-420dpi")
    fun reglages_page_entiere() = captureRoboImage("$OUT/reglages_page_entiere.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                SettingsContent(
                    settings = Settings(voiceGuidance = true),
                    update = null,
                    check = UpdateCheck.UP_TO_DATE,
                    batteryOk = true,
                    notificationsOk = true,
                    isSamsung = true,
                    actions = NoActions,
                    onOpenAbout = {},
                    onOpenOfflineMaps = {},
                    contentPadding = PaddingValues(bottom = 112.dp),
                    archive = ArchiveState.Done("12 sorties sauvegardées."),
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
        override fun setWeatherEnabled(value: Boolean) = Unit
        override fun setRainAlert(value: Boolean) = Unit
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

    /** « Sources et licences » : le logo en tête, sur sa plaque blanche, en clair comme en sombre. */
    @Test
    fun sources_et_licences() = captureRoboImage("$OUT/sources_et_licences.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) { AboutScreen(onBack = {}) }
    }

    @Test
    fun sources_et_licences_sombre() = captureRoboImage("$OUT/sources_et_licences_sombre.png") {
        VeloGoTheme(mode = ThemeMode.DARK) { AboutScreen(onBack = {}) }
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
        override fun moveViaUp(index: Int) = Unit
        override fun removeEnd() = Unit
        override fun setLoop(enabled: Boolean) = Unit
        override fun reverse() = Unit
        override fun compute() = Unit
        override fun stopAtStart() = Unit
        override fun edit() = Unit
        override fun select(index: Int) = Unit
        override fun startGuidance() = Unit
        override fun toggleVoice() = Unit
        override fun save() = Unit
        override fun share() = Unit
        override fun importGpx() = Unit
        override fun openSaved() = Unit
        override fun openOfflineMaps() = Unit
        override fun openWeather() = Unit
    }

    private fun planner(state: PlannerState, hasData: Boolean = true, weather: PlannerWeather? = null, expanded: Boolean = true): @Composable () -> Unit = {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            // La barre d'onglets du bas de l'écran est figurée par une bande : le panneau se pose juste au-dessus.
            Box(Modifier.fillMaxSize().background(Color(0xFFDDE7DD)).padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
                PlannerPanel(
                    state = state, hasRoutingData = hasData, voiceEnabled = true, actions = NoPlannerActions, maxHeight = 548.dp,
                    weather = weather, expanded = expanded,
                )
            }
        }
    }

    private fun proposals() = PlannerState(
        open = true, bike = BikeType.HYBRID, end = PlanPoint(LatLon(45.7, 6.3), "Gare d'Albertville"),
        options = listOf(
            RouteOption(sampleRoute(), listOf(RoutePreference.FAST)),
            RouteOption(sampleRoute(1, 1.08), listOf(RoutePreference.SAFE)),
            RouteOption(sampleRoute(2, 0.8), listOf(RoutePreference.FLAT)),
        ),
        selected = 0,
    )

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

    // --- Boucles ------------------------------------------------------------------------------------------------

    /** Une boucle en préparation : étapes numérotées et réordonnables, arrivée « Retour au départ ». */
    @Test
    fun itineraire_boucle_points() = captureRoboImage(
        "$OUT/itineraire_boucle_points.png",
        content = planner(
            PlannerState(
                open = true, bike = BikeType.ROAD, loop = true,
                vias = listOf(
                    PlanPoint(LatLon(49.05, 2.267), "Chauvry"),
                    PlanPoint(LatLon(49.04, 2.293), "Bouffémont"),
                    PlanPoint(LatLon(49.005, 2.302), "Andilly"),
                ),
            )
        ),
    )

    /** Boucle demandée, pas encore d'étape : le panneau dit quoi faire et le calcul attend. */
    @Test
    fun itineraire_boucle_sans_etape() = captureRoboImage(
        "$OUT/itineraire_boucle_sans_etape.png",
        content = planner(PlannerState(open = true, bike = BikeType.ROAD, loop = true)),
    )

    /**
     * Le trajet du 22/11/2026 : une boucle de 29 km qui repasse par le départ, puis 11 km de plus vers l'arrivée
     * par les routes du début. Sur la carte on ne voit que la boucle : le panneau le dit et propose de s'y arrêter.
     */
    private fun loopThenTail(): PlannerState {
        val home = LatLon(48.997, 2.218)
        val points = ArrayList<LatLon>()
        points.add(home)
        fun go(bearing: Double, meters: Int) = repeat(meters / 100) { points.add(Geo.offset(points.last().lat, points.last().lon, bearing, 100.0)) }
        // Un losange qui se referme sur la maison (29,2 km), puis de nouveau ses premiers côtés (10,9 km).
        go(30.0, 7_300); go(110.0, 7_300); go(210.0, 7_300); go(290.0, 7_300); go(30.0, 7_300); go(110.0, 3_600)
        val distances = Route.cumulativeDistances(points)
        val route = Route(
            points = points, elevations = DoubleArray(points.size) { 70.0 + 60.0 * kotlin.math.sin(it / 35.0) + it * 0.05 }, distances = distances,
            durationS = 123 * 60.0, ascentM = 505.0, descentM = 470.0,
            instructions = listOf(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)),
            waypoints = listOf(home, points[73], points[146], home, points.last()),
        )
        checkNotNull(RouteShape.returnToStart(route)) { "l'itinéraire d'exemple doit repasser par son départ" }
        return PlannerState(open = true, bike = BikeType.HYBRID, options = listOf(RouteOption(route, emptyList())), importedName = "Entraînement 1", savedId = 1)
    }

    @Test
    fun itineraire_repasse_par_le_depart() = captureRoboImage(
        "$OUT/itineraire_repasse_par_le_depart.png",
        content = planner(loopThenTail(), expanded = false),
    )

    @Test
    fun itineraire_repasse_par_le_depart_details() = captureRoboImage(
        "$OUT/itineraire_repasse_par_le_depart_details.png",
        content = planner(loopThenTail(), expanded = true),
    )

    @Test
    fun itineraire_sans_donnees() = captureRoboImage(
        "$OUT/itineraire_sans_donnees.png",
        content = planner(PlannerState(open = true, bike = BikeType.ROAD), hasData = false),
    )

    /** Les propositions telles qu'elles arrivent : panneau replié, la carte garde presque tout l'écran. */
    @Test
    fun itineraire_propositions() = captureRoboImage(
        "$OUT/itineraire_propositions.png",
        content = planner(proposals(), weather = PlannerWeather("11 à 14° · vent de face 15 km/h · pluie vers 15:30 (km 24)", wet = true), expanded = false),
    )

    /** Le même panneau déplié : profil, nature des voies, météo du trajet. */
    @Test
    fun itineraire_details() = captureRoboImage(
        "$OUT/itineraire_details.png",
        content = planner(proposals(), weather = PlannerWeather("11 à 14° · vent de face 15 km/h · pluie vers 15:30 (km 24)", wet = true), expanded = true),
    )

    @Test
    fun itineraire_partage() {
        val route = sampleRoute()
        val poster = RoutePoster.render(RuntimeEnvironment.getApplication(), route, PosterText("Sortie vélo", "Vers Gare d'Albertville"), map = null)
        captureRoboImage("$OUT/itineraire_partage.png") {
            VeloGoTheme(mode = ThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ShareRouteContent(
                        poster = poster.asImageBitmap(), mapPending = false, withMap = false,
                        title = "Sortie vélo", subtitle = "Vers Gare d'Albertville",
                        onTitle = {}, onSubtitle = {}, onSharePoster = {}, onShareGpx = {},
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }

    /** Les deux repères de position de la carte, agrandis : le point à l'arrêt, la flèche quand on roule. */
    @Test
    fun repere_position() = captureRoboImage("$OUT/repere_position.png") {
        Row(Modifier.background(Color(0xFFDDE7DD)).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.map_puck_dot), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(32.dp))
            Image(painterResource(R.drawable.map_puck_arrow), contentDescription = null, modifier = Modifier.size(184.dp))
            Spacer(Modifier.width(32.dp))
            Image(painterResource(R.drawable.map_puck_arrow), contentDescription = null, modifier = Modifier.size(46.dp))
        }
    }

    private fun guidance(nav: NavState, rerouting: Boolean = false) =
        GuidanceState(sampleRoute(), nav, BikeType.HYBRID, RoutePreference.FAST, "Gare d'Albertville", rerouting = rerouting)

    private fun guided(mode: RideMode, state: GuidanceState, sunlight: Boolean = false, following: Boolean = true): @Composable () -> Unit = {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize().background(Color(0xFFDDE7DD))) {
                RideChrome(
                    snapshot = recording, mode = mode, onMode = {}, themeMode = ThemeMode.LIGHT, sunlight = sunlight,
                    attribution = "© OpenStreetMap · Protomaps · Mapterhorn (IGN)", following = following, headingUp = true,
                    hasLocationPermission = true, gps = gpsOk,
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
        // Carte déplacée à la main pendant le guidage : le bouton « Recentrer » ramène à la vue de guidage.
        captureRoboImage("$OUT/guidage_recentrer.png", content = guided(RideMode.MAP, guidance(nav), following = false))
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

    /** Choix de l'arrivée : avant même de taper une adresse, « Revenir au départ » fait de l'itinéraire une boucle. */
    @Test
    fun recherche_arrivee_boucle() = captureRoboImage("$OUT/recherche_arrivee_boucle.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                PlaceSearchContent(
                    title = "Où vas-tu ?", query = "", onQuery = {}, result = SearchResult.Idle,
                    offerMyPosition = false, onPlace = {}, onMyPosition = {}, onDismiss = {},
                    offerLoop = true, onLoop = {},
                )
            }
        }
    }

    /** Le service d'adresses ne répond pas, même à la seconde tentative : l'appli le dit et propose de réessayer. */
    @Test
    fun recherche_sans_reseau() = captureRoboImage("$OUT/recherche_sans_reseau.png") {
        VeloGoTheme(mode = ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                PlaceSearchContent(
                    title = "Où vas-tu ?", query = "gare de corte", onQuery = {}, result = SearchResult.Unavailable,
                    offerMyPosition = false, onPlace = {}, onMyPosition = {}, onDismiss = {},
                    offerLoop = true, onLoop = {},
                )
            }
        }
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

    /** L'onglet « Trajets » : les mêmes itinéraires enregistrés, avec le dessin de leur tracé. */
    private fun savedRoutes(theme: ThemeMode, routes: List<SavedRouteEntity>?): @Composable () -> Unit = {
        val previews = routes.orEmpty().associate { it.id to Polyline.encode(Simplify.toMaxPoints(sampleRoute(it.id.toInt()).points, 90)) }
        tabScreen(Tab.ROUTES, theme) {
            SavedRoutesContent(
                // La troisième vignette n'est pas encore prête : la ligne doit rester en place.
                rows = routes?.let { savedRouteRows(it, previews - 3L, today = sampleToday) },
                onOpen = {}, onRename = { _, _ -> }, onShare = {}, onDelete = {}, onNew = {},
                contentPadding = PaddingValues(bottom = 112.dp),
            )
        }()
    }

    private val sampleSavedRoutes = listOf(
        SavedRouteEntity(1, "Tour du lac d'Annecy", 1_795_190_000_000, "HYBRID", 41_800.0, 310.0, 7_400.0, ""),
        SavedRouteEntity(2, "Col de la Forclaz par Talloires et le bout du lac", 1_795_100_000_000, "ROAD", 63_200.0, 1_240.0, 12_900.0, ""),
        SavedRouteEntity(3, "Semnoz par les crêtes", 1_793_000_000_000, "MTB", 28_400.0, 980.0, 9_800.0, ""),
        SavedRouteEntity(4, "Boulangerie", 1_760_000_000_000, "HYBRID", 870.0, 4.0, 190.0, ""),
    )

    @Test
    fun trajets() = captureRoboImage("$OUT/trajets.png", content = savedRoutes(ThemeMode.LIGHT, sampleSavedRoutes))

    @Test
    fun trajets_sombre() = captureRoboImage("$OUT/trajets_sombre.png", content = savedRoutes(ThemeMode.DARK, sampleSavedRoutes))

    @Test
    fun trajets_vide() = captureRoboImage("$OUT/trajets_vide.png", content = savedRoutes(ThemeMode.LIGHT, emptyList()))

    // --- Météo ------------------------------------------------------------------------------------------------

    /** Un mardi de novembre à 14 h 10 (heure de Paris) : éclaircies, vent d'ouest, averse dans vingt minutes. */
    private val weatherNow = 1_795_525_800_000L

    private fun sampleReport(rain: Boolean = true): WeatherReport {
        val hour = 3_600_000L
        val firstHour = weatherNow - weatherNow % hour
        val codes = listOf(2, 61, 63, 3, 2, 1, 0, 0, 0, 1, 2, 3, 3, 45, 45, 3, 2, 1, 0, 0, 1, 2, 2, 3, 61, 61, 3, 2)
        val hourly = codes.mapIndexed { i, code ->
            WeatherPoint(
                timeMs = firstHour + i * hour,
                temperatureC = 11.5 - 4.5 * sin((i - 1) * PI / 12.0) - (if (i in 1..2) 1.5 else 0.0),
                apparentC = 9.0 - 4.5 * sin((i - 1) * PI / 12.0),
                precipitationMm = when {
                    !rain -> 0.0
                    code == 61 -> 0.6
                    code == 63 -> 2.4
                    else -> 0.0
                },
                code = if (rain || code !in listOf(61, 63)) code else 3,
                windKmh = 14.0 + 8.0 * sin(i * PI / 9.0), windFromDeg = 265.0 + i * 4.0, gustKmh = 32.0 + 10.0 * sin(i * PI / 9.0),
                isDay = ((firstHour + i * hour) / hour + 1) % 24 in 8..17,
            )
        }
        val quarterStart = weatherNow - weatherNow % (15 * 60_000L)
        val amounts = if (rain) listOf(0.0, 0.0, 0.2, 0.5, 0.9, 0.6, 0.2, 0.0, 0.0, 0.0, 0.1, 0.0) else List(12) { 0.0 }
        return WeatherReport(
            lat = 45.9, lon = 6.1, fetchedAtMs = weatherNow - 4 * 60_000L,
            current = WeatherPoint(
                timeMs = weatherNow - 4 * 60_000L, temperatureC = 12.3, apparentC = 9.6, precipitationMm = 0.0, code = 2,
                windKmh = 17.0, windFromDeg = 268.0, gustKmh = 36.0, isDay = true,
            ),
            hourly = hourly,
            quarters = amounts.mapIndexed { i, mm -> RainStep(quarterStart + i * 15 * 60_000L, mm) },
        )
    }

    /** On roule vers l'ouest, vent d'ouest : il est de face. */
    private fun sampleGlance(rain: Boolean = true): WeatherGlance = weatherGlance(sampleReport(rain), weatherNow, bearingDeg = 280.0)!!

    @Test
    fun carte_meteo() = captureRoboImage("$OUT/carte_meteo.png", content = chrome(recording, RideMode.MAP, weather = sampleGlance()))

    @Test
    fun carte_meteo_au_repos() = captureRoboImage(
        "$OUT/carte_meteo_repos.png",
        content = chrome(RideSnapshot(), RideMode.MAP, ThemeMode.DARK, weather = weatherGlance(sampleReport(rain = false), weatherNow, null)),
    )

    @Test
    fun guidon_meteo() = captureRoboImage("$OUT/guidon_meteo.png", content = chrome(recording, RideMode.DATA, weather = sampleGlance(rain = false)))

    @Test
    fun guidon_meteo_pluie() = captureRoboImage(
        "$OUT/guidon_meteo_pluie.png",
        content = chrome(recording, RideMode.DATA, sunlight = true, weather = sampleGlance()),
    )

    private fun weatherSheet(state: WeatherSheetState, theme: ThemeMode = ThemeMode.LIGHT): @Composable () -> Unit = {
        VeloGoTheme(mode = theme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                WeatherContent(state = state, onRefresh = {}, onDeparture = {})
            }
        }
    }

    @Test
    fun meteo() = captureRoboImage(
        "$OUT/meteo.png",
        content = weatherSheet(WeatherSheetState(nowMs = weatherNow, report = sampleReport(), bearingDeg = 280.0)),
    )

    /** Une phrase de vent longue (« de sud-ouest », avec des rafales) : elle passe à la ligne après la virgule. */
    @Test
    fun meteo_vent_long() {
        val report = sampleReport(rain = false)
        val windy = report.copy(current = report.current!!.copy(windKmh = 8.0, windFromDeg = 225.0, gustKmh = 24.0))
        captureRoboImage("$OUT/meteo_vent_long.png", content = weatherSheet(WeatherSheetState(nowMs = weatherNow, report = windy)))
    }

    @Test
    fun meteo_sombre_sans_pluie() = captureRoboImage(
        "$OUT/meteo_sombre.png",
        content = weatherSheet(WeatherSheetState(nowMs = weatherNow, report = sampleReport(rain = false)), ThemeMode.DARK),
    )

    /** Météo le long d'un itinéraire, en partant à la prochaine heure ronde. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1500dp-420dpi")
    fun meteo_itineraire() {
        val route = sampleRoute()
        val samples = RouteWeather.samples(route)
        val reports = samples.indices.map { i ->
            val base = sampleReport()
            // Un peu plus frais et plus venté à mesure qu'on avance, comme en montant.
            base.copy(hourly = base.hourly.map { it.copy(temperatureC = it.temperatureC!! - i * 0.7, windKmh = it.windKmh!! + i * 1.5) })
        }
        val choices = departureChoices(weatherNow)
        val rows = RouteWeather.rows(samples, reports, choices[0])
        captureRoboImage(
            "$OUT/meteo_itineraire.png",
            content = weatherSheet(
                WeatherSheetState(
                    nowMs = weatherNow, report = sampleReport(),
                    route = RouteWeatherSection(
                        guiding = false, rows = rows, loading = false, failed = false, originM = 0.0, totalM = route.distanceM,
                        departureMs = choices[0], choices = choices,
                    ),
                )
            ),
        )
    }

    @Test
    fun meteo_sans_reseau() = captureRoboImage(
        "$OUT/meteo_sans_reseau.png",
        content = weatherSheet(WeatherSheetState(nowMs = weatherNow, report = null, failed = true)),
    )

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
