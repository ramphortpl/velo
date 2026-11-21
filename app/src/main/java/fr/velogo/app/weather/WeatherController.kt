package fr.velogo.app.weather

import fr.velogo.app.data.Settings
import fr.velogo.app.data.SettingsRepository
import fr.velogo.app.location.GpsSource
import fr.velogo.app.nav.NavigationController
import fr.velogo.app.nav.VoiceGuide
import fr.velogo.app.recording.RideRecorder
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Route
import fr.velogo.core.weather.RainAlerter
import fr.velogo.core.weather.RainEvent
import fr.velogo.core.weather.RainForecast
import fr.velogo.core.weather.RouteSample
import fr.velogo.core.weather.RouteWeather
import fr.velogo.core.weather.RouteWeatherRow
import fr.velogo.core.weather.WeatherReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ce que l'on sait du temps qu'il fait là où l'on est. */
data class WeatherState(
    val report: WeatherReport? = null,
    /** L'endroit pour lequel ces prévisions ont été demandées. */
    val position: LatLon? = null,
    val loading: Boolean = false,
    /** La dernière mise à jour a échoué (pas de réseau) : ce qui est affiché peut dater. */
    val failed: Boolean = false,
)

/** La météo le long d'un itinéraire : celui qu'on prépare, ou celui qu'on suit. */
data class RouteWeatherState(
    val route: Route,
    /** Les endroits de l'itinéraire où la météo est regardée. */
    val samples: List<RouteSample>,
    /** Une prévision par endroit ; null tant que rien n'est arrivé. */
    val reports: List<WeatherReport>? = null,
    val fetchedAtMs: Long = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    /**
     * Ce qu'on rencontrera, endroit par endroit : en partant du début à [departureMs] (préparation), ou en étant
     * maintenant ([departureMs]) à [progressM] du départ (guidage) ; les endroits déjà dépassés sont retirés.
     */
    fun rows(departureMs: Long, progressM: Double = 0.0): List<RouteWeatherRow> {
        val known = reports ?: return emptyList()
        val first = samples.firstOrNull() ?: return emptyList()
        // Temps déjà roulé depuis le premier endroit : l'heure de passage aux suivants se compte à partir d'ici.
        val elapsedS = if (progressM > first.distanceM) {
            RouteWeather.timeAt(route, progressM) - RouteWeather.timeAt(route, first.distanceM)
        } else 0.0
        return RouteWeather.rows(samples, known, departureMs - (elapsedS * 1000).toLong())
            .filter { it.distanceM >= progressM - PASSED_M }
    }

    private companion object {
        const val PASSED_M = 300.0
    }
}

/**
 * La météo de l'appli : celle du lieu où l'on est, celle de l'itinéraire, et l'alerte quand la pluie approche.
 *
 * Elle vit aussi longtemps que l'appli et ne dépend d'aucun écran. Elle n'allume jamais le GPS : elle se sert de
 * la dernière position connue. Hors sortie, c'est l'écran qui demande une mise à jour quand il est affiché ;
 * pendant une sortie ou un guidage, elle se met à jour seule toutes les dix minutes (une requête de quelques
 * kilo-octets) pour pouvoir prévenir de la pluie écran éteint.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class WeatherController(
    private val scope: CoroutineScope,
    private val repository: WeatherRepository,
    settings: SettingsRepository,
    private val gps: GpsSource,
    private val recorder: RideRecorder,
    private val navigation: NavigationController,
    private val voice: VoiceGuide,
    private val notifier: WeatherNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val settingsFlow = settings.settings
    /** Tout ce qui modifie l'état passe par un seul fil d'exécution (les requêtes, elles, n'y bloquent rien). */
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val prefs: StateFlow<Settings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, Settings())

    private val _here = MutableStateFlow(WeatherState())
    val here: StateFlow<WeatherState> = _here

    private val _route = MutableStateFlow<RouteWeatherState?>(null)
    val route: StateFlow<RouteWeatherState?> = _route

    private var refreshing = false

    /** À appeler une fois au démarrage de l'appli. */
    fun start() {
        scope.launch(serial) { watchDuringRides() }
        scope.launch(serial) { followPlanner() }
        scope.launch(serial) {
            // Météo coupée dans les réglages : on n'affiche plus rien.
            settingsEnabled().collect { enabled ->
                if (!enabled) {
                    _here.value = WeatherState()
                    _route.value = null
                    notifier.clear()
                }
            }
        }
    }

    /** Le réglage « Afficher la météo », lu directement dans les réglages enregistrés (pas leur valeur par défaut). */
    private fun settingsEnabled() = settingsFlow.map { it.weatherEnabled }.distinctUntilChanged()

    // --- Météo du lieu ----------------------------------------------------------------------------------------

    /** Met à jour la météo de [position] si elle a vieilli (ou tout de suite avec [force]). */
    fun refresh(position: LatLon?, force: Boolean = false) {
        if (position == null) return
        scope.launch(serial) {
            if (refreshing || !settingsFlow.first().weatherEnabled) return@launch
            refreshing = true
            try {
                load(position, force)
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun load(position: LatLon, force: Boolean) {
        // Des prévisions faites pour un endroit éloigné ne valent rien ici.
        _here.update { state ->
            val known = state.position
            if (known != null && Geo.distanceM(known, position) > FAR_M) WeatherState() else state
        }
        // Des prévisions de moins de dix minutes : on les affiche, sans rien demander au réseau.
        if (!force) {
            repository.fresh(position)?.let { fresh ->
                _here.value = WeatherState(fresh, position)
                return
            }
        }
        // En attendant la réponse, on montre les dernières reçues si elles valent encore quelque chose.
        if (_here.value.report == null) {
            repository.cached(position)?.let { cached -> _here.update { it.copy(report = cached, position = position) } }
        }
        _here.update { it.copy(loading = true) }
        val forecast = try {
            repository.here(position, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // La météo a pu être coupée dans les réglages pendant la requête.
        if (!settingsFlow.first().weatherEnabled) {
            _here.value = WeatherState()
            return
        }
        _here.update { state ->
            if (forecast != null) {
                WeatherState(forecast.report, position, loading = false, failed = forecast.stale)
            } else {
                // Ni réseau ni mémoire récente : on garde ce qui est affiché tant que cela couvre l'heure qu'il est.
                state.copy(report = state.report?.takeIf { it.now(clock()) != null }, loading = false, failed = true)
            }
        }
    }

    /** Dernière position connue : celle de la sortie en cours, sinon celle du GPS si elle est récente. */
    private fun lastPosition(): LatLon? {
        val snapshot = recorder.snapshot.value
        val lat = snapshot.lat
        val lon = snapshot.lon
        if (snapshot.isActive && lat != null && lon != null) return LatLon(lat, lon)
        val fix = gps.last?.fix ?: return null
        return if (clock() - fix.timeMs < MAX_FIX_AGE_MS) LatLon(fix.lat, fix.lon) else null
    }

    // --- Alerte pluie -----------------------------------------------------------------------------------------

    private suspend fun watchDuringRides() {
        combine(
            recorder.snapshot.map { it.isActive }.distinctUntilChanged(),
            navigation.guidance.map { it != null }.distinctUntilChanged(),
            settingsEnabled(),
        ) { recording, guiding, enabled -> enabled && (recording || guiding) }
            .distinctUntilChanged()
            .collectLatest { riding ->
                if (!riding) {
                    notifier.clear()
                    return@collectLatest
                }
                val alerter = RainAlerter(leadMin = RAIN_LEAD_MIN)
                var evaluated = false
                while (true) {
                    lastPosition()?.let { load(it, force = false) }
                    val report = _here.value.report
                    if (report != null) {
                        val now = clock()
                        val due = alerter.update(RainForecast.next(report.quarters, now, horizonMin = RAIN_LEAD_MIN), now)
                        // Une pluie déjà là quand la sortie commence n'est pas une nouvelle : on ne dérange pas pour ça.
                        if (due != null && !(due.isNow && !evaluated) && prefs.value.rainAlert) alert(due)
                        evaluated = true
                    }
                    delay(WATCH_PERIOD_MS)
                }
            }
    }

    private fun alert(event: RainEvent) {
        voice.vibrateRain()
        if (prefs.value.voiceGuidance) voice.speak(RainForecast.spoken(event))
        notifier.rain(RainForecast.describe(event))
    }

    // --- Météo de l'itinéraire --------------------------------------------------------------------------------

    private sealed interface Target {
        data object None : Target
        data object Keep : Target
        class Show(val route: Route) : Target
    }

    /** L'itinéraire choisi dans le panneau de préparation reçoit sa météo sans qu'on la demande. */
    private suspend fun followPlanner() {
        combine(navigation.planner, navigation.guidance, settingsEnabled()) { planner, guidance, enabled ->
            val selected = planner.selectedOption?.route
            when {
                !enabled -> Target.None
                // En guidage, on garde ce qu'on a : l'écran redemande la météo quand on l'ouvre.
                guidance != null -> Target.Keep
                planner.open && selected != null -> Target.Show(selected)
                else -> Target.None
            }
        }
            .distinctUntilChanged { a, b -> a === b || (a is Target.Show && b is Target.Show && a.route === b.route) }
            // On laisse passer les changements rapides de proposition avant d'interroger le réseau.
            .debounce(ROUTE_DEBOUNCE_MS)
            .collect { target ->
                when (target) {
                    Target.None -> _route.value = null
                    Target.Keep -> Unit
                    is Target.Show -> loadRoute(target.route, 0.0, force = false)
                }
            }
    }

    /**
     * Demande la météo le long de [route], à partir de [fromM] du départ. Sans effet si elle est déjà connue et
     * récente pour ce même itinéraire.
     */
    fun requestRoute(route: Route, fromM: Double = 0.0, force: Boolean = false) {
        scope.launch(serial) { loadRoute(route, fromM, force) }
    }

    private suspend fun loadRoute(route: Route, fromM: Double, force: Boolean) {
        if (!settingsFlow.first().weatherEnabled) return
        val current = _route.value
        val same = current != null && current.route === route
        if (same && !force) {
            val fresh = current.reports != null && clock() - current.fetchedAtMs < ROUTE_FRESH_MS
            if (current.loading || fresh) return
        }
        val samples = RouteWeather.samples(route, fromM, MAX_ROUTE_POINTS)
        // Les prévisions déjà affichées pour ce même itinéraire restent à l'écran pendant la mise à jour.
        _route.value = if (same) current.copy(loading = true, failed = false) else RouteWeatherState(route, samples, loading = true)
        // La requête part de son côté : pendant ce temps, on peut changer d'itinéraire ou fermer le panneau.
        scope.launch(serial) {
            val reports = try {
                repository.along(samples.map { it.position })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _route.update { state ->
                when {
                    // Entre-temps, l'itinéraire affiché a changé (ou il n'y en a plus) : cette réponse ne sert plus.
                    state == null || state.route !== route -> state
                    reports != null -> RouteWeatherState(route, samples, reports, clock(), loading = false, failed = false)
                    else -> state.copy(loading = false, failed = true)
                }
            }
        }
    }

    companion object {
        /** On prévient quand la pluie est à moins d'une demi-heure. */
        const val RAIN_LEAD_MIN = 30
        private const val WATCH_PERIOD_MS = 2 * 60_000L
        private const val MAX_FIX_AGE_MS = 5 * 60_000L
        private const val FAR_M = 25_000.0
        private const val ROUTE_FRESH_MS = 30 * 60_000L
        private const val ROUTE_DEBOUNCE_MS = 500L
        const val MAX_ROUTE_POINTS = 8
    }
}
