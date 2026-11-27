package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon

/** Type de vélo : il décide des voies que l'itinéraire accepte ou recherche. */
enum class BikeType {
    /** Vélo de route : bitume, routes roulantes. */
    ROAD,
    /** VTC ou gravel : pistes, voies vertes, petites routes, chemins roulants. */
    HYBRID,
    /** VTT : chemins et sentiers. */
    MTB,
}

/** Ce que l'on privilégie pour un itinéraire. */
enum class RoutePreference {
    /** Le plus rapide. */
    FAST,
    /** Le plus sûr : à l'écart de la circulation. */
    SAFE,
    /** Le moins de dénivelé. */
    FLAT,
}

enum class Maneuver {
    CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT, U_TURN, ROUNDABOUT, ARRIVE,
}

/**
 * Une consigne de guidage : au point [index] de la trace, soit à [distanceM] du départ.
 * Pour un giratoire, [exit] donne le numéro de la sortie à prendre. [street] est le nom de la voie qu'on prend
 * après la manœuvre, quand la carte du téléphone permet de le retrouver (voir [StreetNamer]).
 */
data class Instruction(
    val index: Int,
    val distanceM: Double,
    val maneuver: Maneuver,
    val exit: Int = 0,
    val street: String? = null,
)

/** Répartition d'un itinéraire par nature de voie, en mètres. */
data class WayMix(
    /** Pistes cyclables et voies vertes : séparées de la circulation et prévues pour les vélos. */
    val cyclewayM: Double = 0.0,
    /** Petites routes et rues calmes. */
    val quietM: Double = 0.0,
    /** Routes ordinaires. */
    val roadM: Double = 0.0,
    /** Grands axes (trafic). */
    val busyM: Double = 0.0,
    /** Chemins et sentiers. */
    val pathM: Double = 0.0,
    /** Dont non revêtu (terre, gravier, herbe…), toutes natures confondues. */
    val unpavedM: Double = 0.0,
) {
    val totalM: Double get() = cyclewayM + quietM + roadM + busyM + pathM
}

/**
 * Un itinéraire : sa trace, son profil et ses consignes.
 *
 * [distances] donne la distance cumulée depuis le départ à chaque point de [points] ; [elevations], quand il
 * existe, l'altitude en mètres aux mêmes points.
 */
class Route(
    val points: List<LatLon>,
    val elevations: DoubleArray?,
    val distances: DoubleArray,
    val durationS: Double,
    val ascentM: Double,
    val descentM: Double,
    val instructions: List<Instruction>,
    /** Départ, étapes et arrivée demandés. */
    val waypoints: List<LatLon>,
    val mix: WayMix? = null,
) {
    init {
        require(points.size >= 2) { "un itinéraire a au moins deux points" }
        require(distances.size == points.size) { "une distance par point" }
        require(elevations == null || elevations.size == points.size) { "une altitude par point" }
    }

    val distanceM: Double get() = distances.last()

    /** Même itinéraire avec un autre profil d'altitude (relief plus précis, par exemple). */
    fun withElevations(newElevations: DoubleArray, ascentM: Double, descentM: Double): Route =
        Route(points, newElevations, distances, durationS, ascentM, descentM, instructions, waypoints, mix)

    /** Même itinéraire avec d'autres consignes (les mêmes, complétées du nom des rues, par exemple). */
    fun withInstructions(newInstructions: List<Instruction>): Route =
        Route(points, elevations, distances, durationS, ascentM, descentM, newInstructions, waypoints, mix)

    /**
     * Le début de l'itinéraire, jusqu'au point [index] compris, qui devient l'arrivée. Les consignes rencontrées
     * en chemin sont gardées. La durée est répartie au prorata de la distance et le dénivelé au prorata de ce que
     * le profil conservé monte et descend ; la répartition par nature de voie, qu'on ne sait pas recompter sur un
     * morceau, est abandonnée plutôt qu'inventée.
     */
    fun truncatedAt(index: Int): Route {
        require(index in 1 until points.size) { "coupe hors de l'itinéraire" }
        if (index == points.size - 1) return this
        val count = index + 1
        val keptPoints = points.subList(0, count).toList()
        val keptDistances = distances.copyOf(count)
        val keptElevations = elevations?.copyOf(count)
        val share = if (distanceM > 0.0) keptDistances.last() / distanceM else 1.0
        var ascentShare = share
        var descentShare = share
        if (elevations != null && keptElevations != null) {
            val whole = climb(elevations)
            val part = climb(keptElevations)
            if (whole.first > 0.0) ascentShare = (part.first / whole.first).coerceIn(0.0, 1.0)
            if (whole.second > 0.0) descentShare = (part.second / whole.second).coerceIn(0.0, 1.0)
        }
        val end = keptPoints.last()
        val start = waypoints.first()
        // Les étapes par lesquelles le début de l'itinéraire passe encore, sauf celle qui devient l'arrivée.
        val steps = waypoints.drop(1).dropLast(1).filter { step ->
            Geo.distanceM(step, end) > STEP_MERGE_M && Geo.distanceM(step, start) > STEP_MERGE_M && nearKept(step, keptPoints)
        }
        return Route(
            points = keptPoints,
            elevations = keptElevations,
            distances = keptDistances,
            durationS = durationS * share,
            ascentM = ascentM * ascentShare,
            descentM = descentM * descentShare,
            instructions = instructions.filter { it.maneuver != Maneuver.ARRIVE && it.index < index } +
                Instruction(index, keptDistances.last(), Maneuver.ARRIVE),
            waypoints = listOf(start) + steps + end,
            mix = null,
        )
    }

    private fun nearKept(p: LatLon, kept: List<LatLon>): Boolean {
        for (i in 0 until kept.size - 1) {
            if (Geo.distanceToSegmentM(p, kept[i], kept[i + 1]).distanceM <= STEP_REACH_M) return true
        }
        return false
    }

    /** Ce qu'un profil monte et descend, en ignorant les petites oscillations. */
    private fun climb(profile: DoubleArray): Pair<Double, Double> {
        val accumulator = fr.velogo.core.ride.AscentAccumulator(CLIMB_THRESHOLD_M)
        for (e in profile) accumulator.add(e)
        return accumulator.ascentM to accumulator.descentM
    }

    /** Vrai si les deux itinéraires suivent pratiquement le même tracé (à [toleranceM] près). */
    fun isSameAs(other: Route, toleranceM: Double = 30.0): Boolean {
        if (kotlin.math.abs(distanceM - other.distanceM) > distanceM * 0.02 + 50.0) return false
        // Une vingtaine de points de l'un doivent tous tomber sur l'autre.
        val samples = 24
        var cursor = 0
        for (s in 1 until samples) {
            val target = distanceM * s / samples
            while (cursor < distances.size - 1 && distances[cursor + 1] < target) cursor++
            if (other.distanceTo(points[cursor]) > toleranceM) return false
        }
        return true
    }

    /** Le point de l'itinéraire situé à [progressM] mètres du départ (ramené entre le départ et l'arrivée). */
    fun positionAt(progressM: Double): LatLon {
        val d = progressM.coerceIn(0.0, distanceM)
        // Premier point situé à cette distance ou au-delà.
        var lo = 0
        var hi = distances.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (distances[mid] >= d) hi = mid else lo = mid + 1
        }
        if (lo == 0) return points[0]
        val step = distances[lo] - distances[lo - 1]
        if (step <= 0.0) return points[lo]
        val f = (d - distances[lo - 1]) / step
        val a = points[lo - 1]
        val b = points[lo]
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /**
     * Le morceau de tracé compris entre [fromM] et [toM] mètres du départ (ramenés entre le départ et l'arrivée),
     * extrémités comprises : c'est lui qu'on dessine en flèche sur la carte pour montrer une manœuvre.
     */
    fun slice(fromM: Double, toM: Double): List<LatLon> {
        val from = fromM.coerceIn(0.0, distanceM)
        val to = toM.coerceIn(from, distanceM)
        val out = ArrayList<LatLon>()
        out.add(positionAt(from))
        for (i in points.indices) {
            if (distances[i] > from && distances[i] < to) out.add(points[i])
        }
        val end = positionAt(to)
        if (end != out.last()) out.add(end)
        return out
    }

    /**
     * Le tracé de la manœuvre [instruction], à dessiner en flèche sur la carte : une trentaine de mètres avant le
     * carrefour, autant après. Pour un giratoire, la consigne est à l'entrée de l'anneau : la flèche va plus loin,
     * pour montrer de quel côté on en sort.
     */
    fun arrowFor(instruction: Instruction): List<LatLon> {
        val roundabout = instruction.maneuver == Maneuver.ROUNDABOUT
        val before = if (roundabout) ARROW_BEFORE_ROUNDABOUT_M else ARROW_BEFORE_M
        val after = if (roundabout) ARROW_AFTER_ROUNDABOUT_M else ARROW_AFTER_M
        return slice(instruction.distanceM - before, instruction.distanceM + after)
    }

    /**
     * Direction dans laquelle part l'itinéraire à [progressM] mètres du départ, en degrés (0 = nord, 90 = est).
     * Elle est prise sur les [aheadM] mètres qui suivent, pour ne pas dépendre d'un tout petit bout de tracé ;
     * à l'arrivée, sur les derniers mètres.
     */
    fun bearingAt(progressM: Double, aheadM: Double = 15.0): Double {
        val from = progressM.coerceIn(0.0, (distanceM - aheadM).coerceAtLeast(0.0))
        val a = positionAt(from)
        val b = positionAt(from + aheadM)
        return if (a == b) Geo.bearingDeg(points.first().lat, points.first().lon, points.last().lat, points.last().lon)
        else Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    /** Distance en mètres du point [p] à l'itinéraire (recherche sur toute la trace). */
    fun distanceTo(p: LatLon): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = Geo.distanceToSegmentM(p, points[i], points[i + 1]).distanceM
            if (d < best) best = d
        }
        return best
    }

    companion object {
        private const val CLIMB_THRESHOLD_M = 3.0
        /** Une étape à moins de cette distance (m) du départ ou de l'arrivée se confond avec eux. */
        private const val STEP_MERGE_M = 300.0
        /**
         * Une étape est « sur » l'itinéraire s'il passe à moins de cette distance (m) d'elle : un point posé au
         * doigt à côté d'une route est rattaché par le moteur à la voie la plus proche, parfois à 400 m.
         */
        private const val STEP_REACH_M = 600.0
        private const val ARROW_BEFORE_M = 28.0
        private const val ARROW_AFTER_M = 30.0
        private const val ARROW_BEFORE_ROUNDABOUT_M = 20.0
        private const val ARROW_AFTER_ROUNDABOUT_M = 60.0

        /** Distances cumulées le long d'une ligne brisée. */
        fun cumulativeDistances(points: List<LatLon>): DoubleArray {
            val out = DoubleArray(points.size)
            for (i in 1 until points.size) out[i] = out[i - 1] + Geo.distanceM(points[i - 1], points[i])
            return out
        }
    }
}
