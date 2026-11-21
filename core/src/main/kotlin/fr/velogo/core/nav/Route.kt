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
 * Pour un giratoire, [exit] donne le numéro de la sortie à prendre.
 */
data class Instruction(val index: Int, val distanceM: Double, val maneuver: Maneuver, val exit: Int = 0)

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
        /** Distances cumulées le long d'une ligne brisée. */
        fun cumulativeDistances(points: List<LatLon>): DoubleArray {
            val out = DoubleArray(points.size)
            for (i in 1 until points.size) out[i] = out[i - 1] + Geo.distanceM(points[i - 1], points[i])
            return out
        }
    }
}
