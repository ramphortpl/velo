package fr.velogo.routing

import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.VeloGoBridge
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RoutePreference
import fr.velogo.core.nav.WayMix
import fr.velogo.core.ride.AscentAccumulator
import java.io.File

/** Ce qu'on demande au calcul d'itinéraire. */
data class PlanRequest(
    /** Départ, étapes éventuelles, arrivée. */
    val waypoints: List<LatLon>,
    val bike: BikeType,
    val preference: RoutePreference,
    /** 0 pour le meilleur itinéraire, 1 à 3 pour des variantes qui s'en écartent. */
    val alternative: Int = 0,
    /** Puissance moyenne du cycliste en watts : elle ne change pas le tracé, seulement la durée estimée. */
    val powerW: Int = 140,
    val timeoutS: Int = 60,
)

enum class PlanFailure {
    /** Les données d'itinéraire de la zone ne sont pas sur le téléphone. */
    MISSING_DATA,
    /** Un des points est trop loin de toute voie praticable. */
    POINT_OFF_NETWORK,
    /** Aucun chemin ne relie les points (île, zone fermée…). */
    NO_ROUTE,
    TIMEOUT,
    ERROR,
}

sealed interface PlanOutcome {
    class Found(val route: Route) : PlanOutcome
    class Failed(val reason: PlanFailure, val detail: String) : PlanOutcome
}

/**
 * Calcul d'itinéraires vélo avec le moteur BRouter embarqué : tout se passe sur le téléphone, sans réseau.
 *
 * [profilesDir] contient les profils (voir [RoutingProfiles.install]) ; les données d'itinéraire (fichiers
 * `dalle-*.rd5`) sont dans le dossier passé à [plan]. Un seul calcul tourne à la fois : BRouter est gourmand en
 * mémoire et le téléphone n'en a pas à revendre.
 */
class BRouterPlanner(private val profilesDir: File, private val memoryClassMb: Int = 64) {

    private val lock = Any()

    fun plan(segmentsDir: File, request: PlanRequest): PlanOutcome = synchronized(lock) {
        require(request.waypoints.size >= 2) { "il faut au moins un départ et une arrivée" }
        val (profile, parameters) = RoutingProfiles.choose(request.bike, request.preference)
        val context = RoutingContext().apply {
            localFunction = File(profilesDir, "$profile.brf").absolutePath
            keyValues = HashMap(parameters).apply { put("bikerPower", request.powerW.toString()) }
            // Mode « consignes détaillées » : BRouter repère les changements de direction et les giratoires.
            turnInstructionMode = 2
            memoryclass = memoryClassMb.coerceIn(64, 256)
            setAlternativeIdx(request.alternative.coerceIn(0, 3))
        }
        val nodes = request.waypoints.mapIndexed { i, p ->
            OsmNodeNamed().apply {
                name = when (i) {
                    0 -> "from"
                    request.waypoints.size - 1 -> "to"
                    else -> "via$i"
                }
                ilon = ((p.lon + 180.0) * 1e6 + 0.5).toInt()
                ilat = ((p.lat + 90.0) * 1e6 + 0.5).toInt()
            }
        }.toMutableList()

        val engine = try {
            RoutingEngine(null, null, segmentsDir, nodes, context).apply { quite = true }
        } catch (e: Exception) {
            return PlanOutcome.Failed(PlanFailure.ERROR, e.message ?: e.toString())
        }
        try {
            engine.doRun(request.timeoutS * 1000L)
        } catch (e: Throwable) {
            return PlanOutcome.Failed(if (e is OutOfMemoryError) PlanFailure.TIMEOUT else PlanFailure.ERROR, e.message ?: e.toString())
        }
        val error = engine.errorMessage
        val track = engine.foundTrack
        if (error != null || track == null || track.nodes.size < 2) {
            val message = error ?: "no track found"
            val reason = when {
                message.contains("datafile") && message.contains("not found") -> PlanFailure.MISSING_DATA
                message.contains("not mapped") -> PlanFailure.POINT_OFF_NETWORK
                message.contains("timeout") || message.contains("memory limit") || message.contains("killed") -> PlanFailure.TIMEOUT
                message.contains("island") || message.contains("no track found") -> PlanFailure.NO_ROUTE
                else -> PlanFailure.ERROR
            }
            return PlanOutcome.Failed(reason, message)
        }
        PlanOutcome.Found(toRoute(track, request))
    }

    private fun toRoute(track: OsmTrack, request: PlanRequest): Route {
        val nodes = track.nodes
        val points = nodes.map { LatLon(it.iLat / 1e6 - 90.0, it.iLon / 1e6 - 180.0) }
        val distances = Route.cumulativeDistances(points)

        // Altitude : BRouter marque d'une valeur spéciale les points sans donnée ; on les comble avec leurs voisins.
        val raw = DoubleArray(nodes.size) { if (nodes[it].sElev == Short.MIN_VALUE) Double.NaN else nodes[it].elev }
        val elevations = fillGaps(raw)
        var ascent = 0.0
        var descent = 0.0
        if (elevations != null) {
            val accumulator = AscentAccumulator(ASCENT_THRESHOLD_M)
            for (e in elevations) accumulator.add(e)
            ascent = accumulator.ascentM
            descent = accumulator.descentM
        }

        val instructions = ArrayList<Instruction>()
        for (hint in VeloGoBridge.hints(track)) {
            val maneuver = maneuver(hint.command) ?: continue
            val index = hint.indexInTrack.coerceIn(0, points.size - 1)
            instructions.add(Instruction(index, distances[index], maneuver, if (maneuver == Maneuver.ROUNDABOUT) hint.roundaboutExit else 0))
        }
        instructions.sortBy { it.distanceM }
        instructions.add(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE))

        val seconds = track.totalSeconds.toDouble()
        return Route(
            points = points,
            elevations = elevations,
            distances = distances,
            // Sans modèle de vitesse dans le profil, on retombe sur une allure moyenne forfaitaire.
            durationS = if (seconds > 0) seconds else distances.last() / FALLBACK_SPEED_MPS,
            ascentM = ascent,
            descentM = descent,
            instructions = instructions,
            waypoints = request.waypoints,
            mix = wayMix(track, distances),
        )
    }

    private fun maneuver(command: Int): Maneuver? = when (command) {
        VeloGoBridge.CONTINUE -> Maneuver.CONTINUE
        VeloGoBridge.LEFT -> Maneuver.LEFT
        VeloGoBridge.SLIGHT_LEFT -> Maneuver.SLIGHT_LEFT
        VeloGoBridge.SHARP_LEFT -> Maneuver.SHARP_LEFT
        VeloGoBridge.RIGHT -> Maneuver.RIGHT
        VeloGoBridge.SLIGHT_RIGHT -> Maneuver.SLIGHT_RIGHT
        VeloGoBridge.SHARP_RIGHT -> Maneuver.SHARP_RIGHT
        VeloGoBridge.KEEP_LEFT, VeloGoBridge.EXIT_LEFT -> Maneuver.KEEP_LEFT
        VeloGoBridge.KEEP_RIGHT, VeloGoBridge.EXIT_RIGHT -> Maneuver.KEEP_RIGHT
        VeloGoBridge.U_TURN, VeloGoBridge.U_TURN_LEFT, VeloGoBridge.U_TURN_RIGHT -> Maneuver.U_TURN
        VeloGoBridge.ROUNDABOUT, VeloGoBridge.ROUNDABOUT_LEFT -> Maneuver.ROUNDABOUT
        else -> null
    }

    /** Répartition du parcours par nature de voie, d'après les attributs OpenStreetMap de chaque tronçon. */
    private fun wayMix(track: OsmTrack, distances: DoubleArray): WayMix {
        var cycleway = 0.0
        var quiet = 0.0
        var road = 0.0
        var busy = 0.0
        var path = 0.0
        var unpaved = 0.0
        var lastTags: Map<String, String> = emptyMap()
        for (i in 1 until track.nodes.size) {
            val length = distances[i] - distances[i - 1]
            // BRouter ne répète pas les attributs tant que la voie ne change pas.
            VeloGoBridge.wayTags(track.nodes[i])?.let { lastTags = parseTags(it) }
            when (WayClassifier.kind(lastTags)) {
                WayKind.CYCLEWAY -> cycleway += length
                WayKind.QUIET -> quiet += length
                WayKind.ROAD -> road += length
                WayKind.BUSY -> busy += length
                WayKind.PATH -> path += length
            }
            if (WayClassifier.isUnpaved(lastTags)) unpaved += length
        }
        return WayMix(cycleway, quiet, road, busy, path, unpaved)
    }

    private fun parseTags(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in text.split(' ')) {
            val i = pair.indexOf('=')
            if (i > 0) out[pair.substring(0, i)] = pair.substring(i + 1)
        }
        return out
    }

    private fun fillGaps(values: DoubleArray): DoubleArray? {
        if (values.all { it.isNaN() }) return null
        val out = values.copyOf()
        var last = Double.NaN
        for (i in out.indices) if (out[i].isNaN()) out[i] = last else last = out[i]
        last = Double.NaN
        for (i in out.indices.reversed()) if (out[i].isNaN()) out[i] = last else last = out[i]
        return out
    }

    private companion object {
        /**
         * Seuil du cumul de dénivelé pour les altitudes de BRouter (issues de relevés à 30 m de maille) : en dessous,
         * les oscillations sont du bruit. Le dénivelé est recalculé plus finement quand le relief hors ligne est là.
         */
        const val ASCENT_THRESHOLD_M = 5.0
        const val FALLBACK_SPEED_MPS = 5.0
    }
}

enum class WayKind { CYCLEWAY, QUIET, ROAD, BUSY, PATH }

/** Range une voie OpenStreetMap dans l'une des familles affichées à l'utilisateur. */
object WayClassifier {
    private val CAR_FREE = setOf("path", "footway", "pedestrian", "bridleway", "track", "steps")
    private val SEPARATED = setOf("track", "opposite_track")
    private val UNPAVED = setOf(
        "unpaved", "gravel", "fine_gravel", "compacted", "dirt", "earth", "ground", "grass", "sand", "mud", "pebblestone",
        "woodchips", "rock", "grass_paver",
    )
    private val PAVED = setOf("asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes", "paving_stones", "sett", "cobblestone", "metal", "wood")

    fun kind(tags: Map<String, String>): WayKind {
        val highway = tags["highway"] ?: return WayKind.QUIET
        val separated = listOf("cycleway", "cycleway:left", "cycleway:right", "cycleway:both").any { tags[it] in SEPARATED }
        if (highway == "cycleway" || separated) return WayKind.CYCLEWAY
        if (highway in CAR_FREE) {
            // Une voie sans voitures n'est une « piste » que si elle est faite pour les vélos (voie verte, par exemple).
            return if (tags["bicycle"] == "designated" && highway != "steps") WayKind.CYCLEWAY else WayKind.PATH
        }
        val traffic = tags["estimated_traffic_class"]?.toIntOrNull()
        return when (highway.removeSuffix("_link")) {
            "motorway", "trunk" -> WayKind.BUSY
            "primary" -> if (traffic != null && traffic <= 2) WayKind.ROAD else WayKind.BUSY
            "secondary" -> if (traffic != null && traffic >= 5) WayKind.BUSY else WayKind.ROAD
            "tertiary" -> WayKind.ROAD
            else -> WayKind.QUIET
        }
    }

    fun isUnpaved(tags: Map<String, String>): Boolean {
        val surface = tags["surface"]
        if (surface != null) return surface in UNPAVED || (surface !in PAVED && surface.contains("gravel"))
        val highway = tags["highway"]
        // Sans indication de revêtement : un chemin est supposé non revêtu, sauf s'il est noté comme carrossable.
        return (highway == "track" && tags["tracktype"] != "grade1") || highway == "path" || highway == "bridleway"
    }
}
