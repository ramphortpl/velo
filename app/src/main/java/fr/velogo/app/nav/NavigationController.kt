package fr.velogo.app.nav

import fr.velogo.app.data.Settings
import fr.velogo.app.data.SettingsRepository
import fr.velogo.app.location.GpsSource
import fr.velogo.app.search.IgnGeocoder
import fr.velogo.app.search.Place
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Simplify
import fr.velogo.core.nav.Announcement
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.GuidanceAnnouncer
import fr.velogo.core.nav.GuidancePhrases
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.NavState
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RouteFollower
import fr.velogo.core.nav.RoutePreference
import fr.velogo.core.nav.RouteShape
import fr.velogo.core.ride.AscentAccumulator
import fr.velogo.core.ride.TrackPoint
import fr.velogo.routing.PlanFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Un point choisi pour l'itinéraire (adresse trouvée, ou endroit touché sur la carte). */
data class PlanPoint(val position: LatLon, val label: String)

/** L'itinéraire en préparation : ses points, les propositions calculées, celle qui est choisie. */
data class PlannerState(
    val open: Boolean = false,
    val bike: BikeType = BikeType.HYBRID,
    /** Point de départ ; null signifie « ma position ». */
    val start: PlanPoint? = null,
    val vias: List<PlanPoint> = emptyList(),
    val end: PlanPoint? = null,
    /** Boucle : l'itinéraire revient à son point de départ, qui est donc aussi l'arrivée ([end] est alors vide). */
    val loop: Boolean = false,
    val computing: Boolean = false,
    val options: List<RouteOption> = emptyList(),
    val selected: Int = 0,
    val error: String? = null,
    /** L'échec vient de données d'itinéraire absentes : l'écran propose d'aller les télécharger. */
    val needsData: Boolean = false,
    /** La proposition affichée est une trace importée (GPX) ou un trajet enregistré, et non un calcul. */
    val importedName: String? = null,
    /** Le trajet affiché vient des trajets enregistrés : son identifiant, pour pouvoir y ranger une correction. */
    val savedId: Long? = null,
) {
    val selectedOption: RouteOption? get() = options.getOrNull(selected)
    val isImported: Boolean get() = importedName != null

    /** Il y a de quoi calculer : une arrivée, ou une boucle avec au moins une étape par laquelle passer. */
    val canCompute: Boolean get() = if (loop) vias.isNotEmpty() else end != null
}

/** Le guidage en cours. */
data class GuidanceState(
    val route: Route,
    val nav: NavState,
    val bike: BikeType,
    val preference: RoutePreference,
    val destination: String,
    /** Un nouvel itinéraire est en cours de calcul (on s'est écarté du précédent). */
    val rerouting: Boolean = false,
    /** Trace importée : on la suit à la ligne, sans consignes de virage ni recalcul. */
    val followOnly: Boolean = false,
)

/**
 * Chef d'orchestre des itinéraires : préparation, calcul, puis guidage le long de l'itinéraire choisi.
 *
 * Il vit aussi longtemps que l'appli et ne dépend d'aucun écran : on peut préparer ou modifier un itinéraire
 * pendant un enregistrement sans y toucher, et le guidage continue écran éteint tant que l'enregistrement tourne
 * (c'est lui qui maintient l'appli éveillée).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationController(
    private val scope: CoroutineScope,
    private val gps: GpsSource,
    private val routes: RouteService,
    private val geocoder: IgnGeocoder,
    private val settings: SettingsRepository,
    private val voice: VoiceGuide,
) {
    /** Tout ce qui touche au suivi passe par un seul fil d'exécution. */
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val prefs: StateFlow<Settings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, Settings())

    private val _planner = MutableStateFlow(PlannerState())
    val planner: StateFlow<PlannerState> = _planner

    private val _guidance = MutableStateFlow<GuidanceState?>(null)
    val guidance: StateFlow<GuidanceState?> = _guidance

    private var computeJob: Job? = null
    private var guidanceJob: Job? = null
    private var follower: RouteFollower? = null
    private var announcer = GuidanceAnnouncer()

    /** Vrai si des données d'itinéraire sont installées. */
    val hasRoutingData: Boolean get() = routes.hasData

    // --- Préparation ------------------------------------------------------------------------------------------

    fun openPlanner() {
        scope.launch {
            val bike = BikeType.entries.firstOrNull { it.name == settings.settings.first().bikeType } ?: BikeType.HYBRID
            _planner.update { it.copy(open = true, bike = bike) }
        }
    }

    /**
     * Ferme le panneau. Un itinéraire en préparation est gardé pour y revenir ; un trajet enregistré ou importé,
     * qui n'a rien à modifier, est rangé : le bouton « Itinéraire » rouvre alors un panneau vierge.
     */
    fun closePlanner() {
        computeJob?.cancel()
        _planner.update { if (it.isImported) PlannerState(bike = it.bike) else it.copy(open = false, computing = false) }
    }

    /** Oublie l'itinéraire en préparation. */
    fun clearPlan() {
        computeJob?.cancel()
        _planner.update { PlannerState(open = it.open, bike = it.bike) }
    }

    fun setBike(bike: BikeType) {
        scope.launch { settings.setBikeType(bike.name) }
        val hadOptions = _planner.value.options.isNotEmpty() && !_planner.value.isImported
        _planner.update { it.copy(bike = bike) }
        if (hadOptions) compute() else invalidate()
    }

    /** Départ choisi ; null pour repartir de sa position. */
    fun setStart(position: LatLon?, label: String? = null) {
        _planner.update { it.copy(start = position?.let { p -> PlanPoint(p, label ?: MAP_POINT) }) }
        invalidate()
        if (position != null && label == null) nameLater(position)
    }

    fun setEnd(position: LatLon, label: String? = null) {
        _planner.update { it.copy(end = PlanPoint(position, label ?: MAP_POINT), loop = false) }
        invalidate()
        if (label == null) nameLater(position)
    }

    /**
     * Boucle : l'itinéraire revient à son point de départ. Les étapes disent par où passer, dans l'ordre où on
     * les ajoute ; il n'y a plus d'autre arrivée à choisir.
     */
    fun setLoop(enabled: Boolean) {
        _planner.update { it.copy(loop = enabled, end = if (enabled) null else it.end) }
        invalidate()
    }

    fun addVia(position: LatLon, label: String? = null) {
        if (_planner.value.vias.size >= MAX_VIAS) return
        _planner.update { it.copy(vias = it.vias + PlanPoint(position, label ?: MAP_POINT)) }
        invalidate()
        if (label == null) nameLater(position)
    }

    fun removeVia(index: Int) {
        _planner.update { it.copy(vias = it.vias.filterIndexed { i, _ -> i != index }) }
        invalidate()
    }

    /** Avance une étape d'un rang : l'itinéraire y passera avant celle qui la précédait. */
    fun moveViaUp(index: Int) {
        if (index !in 1 until _planner.value.vias.size) return
        _planner.update { it.copy(vias = it.vias.toMutableList().apply { add(index - 1, removeAt(index)) }) }
        invalidate()
    }

    fun removeEnd() {
        _planner.update { it.copy(end = null, loop = false) }
        invalidate()
    }

    /**
     * Inverse le sens de l'itinéraire. Une boucle se parcourt dans l'autre sens (mêmes étapes, ordre inverse) ;
     * un aller simple échange départ et arrivée, ce qui est sans effet si le départ est « ma position ».
     */
    fun reverse() {
        val state = _planner.value
        if (state.loop) {
            _planner.update { it.copy(vias = it.vias.reversed()) }
        } else {
            val start = state.start ?: return
            val end = state.end ?: return
            _planner.update { it.copy(start = end, end = start, vias = it.vias.reversed()) }
        }
        invalidate()
    }

    /** Revient des propositions aux points de l'itinéraire, pour les modifier. */
    fun editPoints() = invalidate()

    fun select(index: Int) {
        _planner.update { if (index in it.options.indices) it.copy(selected = index) else it }
    }

    suspend fun search(text: String, near: LatLon?): List<Place> = geocoder.search(text, near)

    /** Calcule les itinéraires entre les points choisis. */
    fun compute() {
        val state = _planner.value
        if (!state.canCompute) return
        computeJob?.cancel()
        _planner.update {
            it.copy(computing = true, error = null, needsData = false, options = emptyList(), selected = 0, importedName = null, savedId = null)
        }
        computeJob = scope.launch {
            val start = state.start?.position ?: currentPosition()
            if (start == null) {
                _planner.update { it.copy(computing = false, error = "Position inconnue : attends le GPS ou choisis un point de départ.") }
                return@launch
            }
            // Une boucle revient là d'où elle part ; sinon l'arrivée est le point choisi.
            val end = if (state.loop) start else state.end?.position ?: return@launch
            val waypoints = listOf(start) + state.vias.map { it.position } + end
            when (val result = routes.options(waypoints, state.bike, prefs.value.riderPowerW)) {
                is RouteOptions.Found -> _planner.update { it.copy(computing = false, options = result.options, selected = 0) }
                is RouteOptions.Failed -> _planner.update {
                    it.copy(computing = false, error = result.message, needsData = result.reason == PlanFailure.MISSING_DATA)
                }
            }
        }
    }

    /** Propose de suivre une trace importée (GPX) : elle devient l'unique proposition affichée. */
    fun importTrack(name: String?, points: List<TrackPoint>) {
        val route = routeFromTrack(points)
        if (route == null) {
            _planner.update { it.copy(open = true, error = "Ce fichier ne contient pas de trace exploitable.", options = emptyList(), importedName = null) }
            return
        }
        computeJob?.cancel()
        _planner.update {
            it.copy(
                open = true, computing = false, error = null, needsData = false,
                options = listOf(RouteOption(route, emptyList())), selected = 0,
                importedName = name?.takeIf { n -> n.isNotBlank() } ?: "Trace importée",
            )
        }
    }

    /**
     * Reprend un itinéraire enregistré : il redevient la proposition affichée, prête à être suivie. [savedId] est
     * son identifiant dans les trajets enregistrés.
     */
    fun showSaved(name: String, route: Route, bike: BikeType, savedId: Long? = null) {
        computeJob?.cancel()
        _planner.update {
            PlannerState(open = true, bike = bike, options = listOf(RouteOption(route, emptyList())), importedName = name, savedId = savedId)
        }
    }

    /**
     * L'itinéraire choisi repasse par son point de départ puis continue (voir [RouteShape.returnToStart]) : on
     * l'arrête à ce retour.
     *
     * Un itinéraire en préparation devient une boucle, avec les étapes d'avant le retour, et se recalcule. Un
     * trajet enregistré ou importé, qui ne se recalcule jamais, est coupé tel quel : la fonction le renvoie alors,
     * pour qu'il puisse être réenregistré.
     */
    fun stopAtStart(): Route? {
        val state = _planner.value
        val option = state.selectedOption ?: return null
        val back = RouteShape.returnToStart(option.route) ?: return null
        if (!state.isImported) {
            // Les étapes atteintes avant le retour au départ : l'itinéraire y passe dans l'ordre, on les retrouve
            // donc l'une après l'autre le long du tracé. Celle qui ramenait au départ, et les suivantes, s'en vont.
            val before = ArrayList<PlanPoint>()
            var cursor = 0
            for (via in state.vias) {
                cursor = nearestIndex(option.route, via.position, cursor)
                if (option.route.distances[cursor] >= back.distanceM - RouteShape.NEAR_M) break
                before.add(via)
            }
            if (before.isNotEmpty()) {
                _planner.update { it.copy(loop = true, end = null, vias = before) }
                compute()
                return null
            }
        }
        val shortened = option.route.truncatedAt(back.index)
        _planner.update { it.copy(options = listOf(option.copy(route = shortened)), selected = 0) }
        return shortened
    }

    private fun invalidate() {
        computeJob?.cancel()
        _planner.update {
            it.copy(computing = false, options = emptyList(), selected = 0, error = null, needsData = false, importedName = null, savedId = null)
        }
    }

    /** Remplace « Point sur la carte » par l'adresse la plus proche, si le réseau répond. */
    private fun nameLater(position: LatLon) {
        scope.launch {
            val name = runCatching { geocoder.reverse(position) }.getOrNull() ?: return@launch
            fun PlanPoint.named() = if (this.position == position && label == MAP_POINT) copy(label = name) else this
            _planner.update { it.copy(start = it.start?.named(), end = it.end?.named(), vias = it.vias.map { v -> v.named() }) }
        }
    }

    /**
     * D'où part un itinéraire « depuis ma position » : la position du GPS de préférence. S'il cherche encore ses
     * satellites (on prépare souvent sa sortie en intérieur), la position approchée du téléphone fait l'affaire
     * quand elle est assez fine : au départ réel, le guidage se recale de toute façon sur le GPS.
     */
    private suspend fun currentPosition(): LatLon? {
        val known = withTimeoutOrNull(POSITION_TIMEOUT_MS) { gps.positions.first() }
        val fix = when {
            known == null -> null
            !known.approximate -> known
            // Le GPS n'a pas encore répondu : on lui laisse quelques secondes avant de se contenter de l'approché.
            else -> withTimeoutOrNull(PRECISE_WAIT_MS) { gps.fixes.first() }
                ?: gps.last?.takeIf { (it.fix.accuracyM ?: Double.MAX_VALUE) <= START_ACCURACY_M }
        }
        return fix?.let { LatLon(it.fix.lat, it.fix.lon) }
    }

    // --- Guidage ----------------------------------------------------------------------------------------------

    /** Démarre le guidage sur la proposition choisie. */
    fun startGuidance() {
        val state = _planner.value
        val option = state.selectedOption ?: return
        val destination = state.importedName ?: if (state.loop) LOOP_DESTINATION else state.end?.label ?: "Arrivée"
        begin(option.route, state.bike, option.preferences.firstOrNull() ?: RoutePreference.FAST, destination, followOnly = state.isImported)
        _planner.update { it.copy(open = false) }
    }

    fun stopGuidance() {
        guidanceJob?.cancel()
        guidanceJob = null
        follower = null
        _guidance.value = null
        voice.release()
    }

    private fun begin(route: Route, bike: BikeType, preference: RoutePreference, destination: String, followOnly: Boolean) {
        guidanceJob?.cancel()
        val first = RouteFollower(route)
        follower = first
        announcer = GuidanceAnnouncer()
        _guidance.value = GuidanceState(route, first.state, bike, preference, destination, followOnly = followOnly)
        if (prefs.value.voiceGuidance) voice.prepare()
        guidanceJob = scope.launch(serial) {
            var lastProgress: Double? = null
            var lastMonoMs = 0L
            var speed: Double? = null
            var lastRerouteMs = 0L
            var arrivedAtMs: Long? = null
            gps.fixes.collect { gpsFix ->
                val fix = gpsFix.fix
                val current = _guidance.value ?: return@collect
                val f = follower ?: return@collect
                val nav = f.update(LatLon(fix.lat, fix.lon), fix.accuracyM, fix.monoMs)
                // Vitesse le long de l'itinéraire : celle du GPS si elle existe, sinon déduite de la progression.
                val previous = lastProgress
                speed = fix.speedMps ?: if (previous != null && fix.monoMs > lastMonoMs) {
                    ((nav.progressM - previous) / ((fix.monoMs - lastMonoMs) / 1000.0)).coerceIn(0.0, 20.0)
                } else speed
                lastProgress = nav.progressM
                lastMonoMs = fix.monoMs
                _guidance.value = current.copy(nav = nav)

                for (announcement in announcer.update(nav, speed)) announce(announcement)

                if (nav.arrived) {
                    val since = arrivedAtMs ?: fix.monoMs.also { arrivedAtMs = it }
                    if (fix.monoMs - since > ARRIVAL_LINGER_MS) stopGuidance()
                } else if (nav.isOffRoute && !current.followOnly && !current.rerouting && fix.monoMs - lastRerouteMs > REROUTE_PERIOD_MS) {
                    lastRerouteMs = fix.monoMs
                    reroute(current.route, LatLon(fix.lat, fix.lon), nav.progressM)
                }
            }
        }
    }

    private fun announce(announcement: Announcement) {
        val current = prefs.value
        if (current.voiceGuidance) {
            voice.prepare()
            voice.speak(announcement.text)
        }
        if (current.vibrateGuidance) voice.vibrate(announcement.kind)
    }

    /** On s'est écarté : nouvel itinéraire depuis la position, par les étapes pas encore atteintes. */
    private fun reroute(route: Route, position: LatLon, progressM: Double) {
        _guidance.update { it?.copy(rerouting = true) }
        scope.launch(serial) {
            val state = _guidance.value ?: return@launch
            val remainingVias = route.waypoints.drop(1).dropLast(1).filter { progressOf(route, it) > progressM + VIA_PASSED_M }
            val waypoints = listOf(position) + remainingVias + route.waypoints.last()
            val fresh = runCatching { routes.single(waypoints, state.bike, state.preference, prefs.value.riderPowerW) }.getOrNull()
            val now = _guidance.value ?: return@launch
            // Le guidage a pu être arrêté ou remplacé pendant le calcul.
            if (now.route !== route) return@launch
            if (fresh == null) {
                _guidance.value = now.copy(rerouting = false)
                return@launch
            }
            // Le nouvel itinéraire garde les étapes d'origine comme repères.
            val kept = Route(
                fresh.points, fresh.elevations, fresh.distances, fresh.durationS, fresh.ascentM, fresh.descentM,
                fresh.instructions, waypoints, fresh.mix,
            )
            val next = RouteFollower(kept)
            follower = next
            announcer = GuidanceAnnouncer()
            _guidance.value = now.copy(route = kept, nav = next.state, rerouting = false)
            if (prefs.value.voiceGuidance) voice.speak(GuidancePhrases.REROUTED)
        }
    }

    /** Rang du point de la trace le plus proche de [target], cherché à partir du rang [from] (le premier s'il y en a plusieurs). */
    private fun nearestIndex(route: Route, target: LatLon, from: Int): Int {
        var best = from
        var bestM = Double.MAX_VALUE
        for (i in from until route.points.size) {
            // Calcul local, léger : il s'agit seulement de comparer des distances entre elles.
            val d = Geo.distanceToSegmentM(target, route.points[i], route.points[i]).distanceM
            if (d < bestM) {
                bestM = d
                best = i
            }
        }
        return best
    }

    /** Distance, le long de l'itinéraire, du point de la trace le plus proche de [target]. */
    private fun progressOf(route: Route, target: LatLon): Double {
        var best = Double.MAX_VALUE
        var along = 0.0
        for (i in 0 until route.points.size - 1) {
            val projection = Geo.distanceToSegmentM(target, route.points[i], route.points[i + 1])
            if (projection.distanceM < best) {
                best = projection.distanceM
                along = route.distances[i] + projection.fraction * (route.distances[i + 1] - route.distances[i])
            }
        }
        return along
    }

    companion object {
        const val MAP_POINT = "Point sur la carte"
        /** Ce que le guidage annonce comme destination d'une boucle. */
        const val LOOP_DESTINATION = "Retour au départ"
        const val MAX_VIAS = 8
        private const val POSITION_TIMEOUT_MS = 8_000L
        private const val PRECISE_WAIT_MS = 3_000L
        /** Une position approchée plus floue que cela ne peut pas servir de point de départ. */
        private const val START_ACCURACY_M = 200.0
        private const val REROUTE_PERIOD_MS = 20_000L
        private const val ARRIVAL_LINGER_MS = 45_000L
        private const val VIA_PASSED_M = 80.0
        private const val MAX_IMPORT_POINTS = 6_000
        private const val IMPORT_ASCENT_THRESHOLD_M = 5.0
        /** Allure forfaitaire pour estimer la durée d'une trace importée (18 km/h). */
        private const val IMPORT_SPEED_MPS = 5.0

        /** Transforme une trace enregistrée ou importée en itinéraire à suivre, ou null si elle est trop courte. */
        fun routeFromTrack(points: List<TrackPoint>): Route? {
            val distinct = ArrayList<TrackPoint>(points.size)
            for (p in points) {
                val last = distinct.lastOrNull()
                if (last == null || last.lat != p.lat || last.lon != p.lon) distinct.add(p)
            }
            if (distinct.size < 2) return null
            var kept: List<TrackPoint> = distinct
            // Une trace très dense (un point par seconde pendant des heures) est allégée sans changer son dessin.
            var tolerance = 1.0
            while (kept.size > MAX_IMPORT_POINTS) {
                val indices = Simplify.indices(distinct.map { LatLon(it.lat, it.lon) }, tolerance)
                kept = indices.map { distinct[it] }
                tolerance *= 2
            }
            val line = kept.map { LatLon(it.lat, it.lon) }
            val distances = Route.cumulativeDistances(line)
            if (distances.last() < 50.0) return null
            val elevations = if (kept.all { it.altitudeM != null }) DoubleArray(kept.size) { kept[it].altitudeM!! } else null
            val accumulator = AscentAccumulator(IMPORT_ASCENT_THRESHOLD_M)
            elevations?.forEach { accumulator.add(it) }
            return Route(
                points = line,
                elevations = elevations,
                distances = distances,
                durationS = distances.last() / IMPORT_SPEED_MPS,
                ascentM = accumulator.ascentM,
                descentM = accumulator.descentM,
                instructions = listOf(Instruction(line.size - 1, distances.last(), Maneuver.ARRIVE)),
                waypoints = listOf(line.first(), line.last()),
            )
        }
    }
}
