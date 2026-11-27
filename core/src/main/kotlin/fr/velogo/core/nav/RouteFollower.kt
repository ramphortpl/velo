package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import kotlin.math.max

/** Où l'on en est sur l'itinéraire, à un instant donné. */
data class NavState(
    /** Distance parcourue le long de l'itinéraire, en mètres. */
    val progressM: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Écart entre la position et l'itinéraire, en mètres. */
    val offsetM: Double,
    /** Vrai quand on s'est écarté de l'itinéraire depuis plusieurs secondes. */
    val isOffRoute: Boolean,
    /** Prochaine consigne, ou null s'il n'y en a plus. */
    val next: Instruction?,
    val distanceToNextM: Double,
    /** Consigne qui suit de près la prochaine (« puis à droite »), le cas échéant. */
    val then: Instruction?,
    val arrived: Boolean,
) {
    companion object {
        val NONE = NavState(0.0, 0.0, 0.0, 0.0, false, null, 0.0, null, false)
    }
}

/**
 * La position ramenée sur l'itinéraire : le point du tracé le plus proche ([position]), la direction du tracé à
 * cet endroit ([bearingDeg]), le rang du segment où il se trouve ([segment]) et sa distance au départ.
 */
data class RouteSnap(val position: LatLon, val bearingDeg: Double, val segment: Int, val progressM: Double)

data class FollowerConfig(
    /** Au-delà de cet écart (m), on considère qu'on n'est plus sur l'itinéraire. */
    val offRouteM: Double = 40.0,
    /** L'écart doit durer ce temps avant de le signaler (un GPS qui saute ne doit pas affoler le guidage). */
    val offRouteDelayMs: Long = 8_000,
    /** À cette distance de l'arrivée (m), on est arrivé. */
    val arrivalM: Double = 25.0,
    /** Deux consignes séparées de moins que cela (m) sont annoncées ensemble. */
    val chainM: Double = 120.0,
    /**
     * Tant que la position est à moins de cette distance (m) de l'itinéraire, la carte la montre dessus : le GPS
     * d'un téléphone flotte de quelques mètres, et une flèche qui zigzague à côté du trait ne guide pas.
     */
    val snapM: Double = 25.0,
)

/**
 * Suit la progression le long d'un itinéraire à partir des positions GPS.
 *
 * À chaque position, on cherche le point de la trace le plus proche, d'abord autour de l'endroit où l'on était
 * (un itinéraire peut repasser au même endroit : il ne faut pas sauter d'un passage à l'autre), puis sur toute
 * la trace si l'on s'en est trop éloigné (raccourci, reprise plus loin).
 */
class RouteFollower(val route: Route, private val config: FollowerConfig = FollowerConfig()) {
    private val points = route.points
    private val distances = route.distances
    // Écrits par le fil du guidage, lus aussi par celui de l'écran ([snap]) : d'où « Volatile ».
    @Volatile private var segment = 0
    @Volatile private var progress = 0.0
    @Volatile private var lastMonoMs = 0L
    @Volatile private var located = false
    private var offSinceMs: Long? = null
    @Volatile private var offRoute = false
    private var arrived = false

    var state: NavState = NavState.NONE.copy(remainingM = route.distanceM, remainingS = route.durationS)
        private set

    fun update(position: LatLon, accuracyM: Double?, monoMs: Long): NavState {
        var best = if (located) nearest(position, localRange(monoMs), preferForward = true) else null
        if (best == null || best.distanceM > RELOCATE_M) {
            val anywhere = nearest(position, 0 until points.size - 1, preferEarliest = !located)
            // On ne quitte le passage en cours pour un autre endroit de la trace que s'il est nettement plus proche.
            if (best == null || anywhere.distanceM < best.distanceM * 0.5) best = anywhere
        }
        located = true
        lastMonoMs = monoMs
        segment = best.segment
        val progress = distances[segment] + best.fraction * (distances[segment + 1] - distances[segment])
        this.progress = progress

        val threshold = max(config.offRouteM, 1.5 * (accuracyM ?: 0.0))
        if (best.distanceM > threshold) {
            val since = offSinceMs ?: monoMs.also { offSinceMs = it }
            if (monoMs - since >= config.offRouteDelayMs) offRoute = true
        } else if (best.distanceM < threshold * 0.6) {
            offSinceMs = null
            offRoute = false
        }

        val remaining = (route.distanceM - progress).coerceAtLeast(0.0)
        if (remaining <= config.arrivalM && best.distanceM <= threshold) arrived = true

        val nextIndex = route.instructions.indexOfFirst { it.distanceM > progress - PASSED_M }
        val next = route.instructions.getOrNull(nextIndex)
        val following = route.instructions.getOrNull(nextIndex + 1)
        state = NavState(
            progressM = progress,
            remainingM = remaining,
            remainingS = if (route.distanceM > 0) route.durationS * remaining / route.distanceM else 0.0,
            offsetM = best.distanceM,
            isOffRoute = offRoute && !arrived,
            next = next,
            distanceToNextM = if (next != null) (next.distanceM - progress).coerceAtLeast(0.0) else remaining,
            then = if (next != null && following != null && following.distanceM - next.distanceM <= config.chainM) following else null,
            arrived = arrived,
        )
        return state
    }

    /**
     * Où dessiner [position] sur l'itinéraire, sans rien changer au suivi : le point du tracé le plus proche, autour
     * de l'endroit où l'on en est (comme [update], donc sur le bon passage d'un aller-retour). Null si l'on n'a pas
     * encore été situé, si l'on est sorti de l'itinéraire ou si la position en est trop loin : la carte montre alors
     * la position telle que le GPS la donne, et l'écart se voit.
     */
    fun snap(position: LatLon, accuracyM: Double?): RouteSnap? {
        if (!located || offRoute) return null
        val match = nearest(position, localRange(lastMonoMs + SNAP_ELAPSED_MS), preferForward = true)
        val tolerance = max(config.snapM, 1.5 * (accuracyM ?: 0.0)).coerceAtMost(config.offRouteM)
        if (match.distanceM > tolerance) return null
        val a = points[match.segment]
        val b = points[match.segment + 1]
        val along = distances[match.segment] + match.fraction * (distances[match.segment + 1] - distances[match.segment])
        return RouteSnap(
            position = LatLon(a.lat + (b.lat - a.lat) * match.fraction, a.lon + (b.lon - a.lon) * match.fraction),
            bearingDeg = route.bearingAt(along),
            segment = match.segment,
            progressM = along,
        )
    }

    private class Match(val segment: Int, val fraction: Double, val distanceM: Double)

    /**
     * Segments à examiner autour de la position connue : un peu en arrière, et en avant ce qu'on a pu parcourir
     * depuis la dernière position. Sur un aller-retour, le chemin du retour n'entre ainsi en jeu qu'une fois
     * arrivé au demi-tour.
     */
    private fun localRange(monoMs: Long): IntRange {
        val elapsedS = ((monoMs - lastMonoMs) / 1000.0).coerceIn(0.0, 60.0)
        val ahead = (LOOK_AHEAD_BASE_M + MAX_SPEED_MPS * elapsedS).coerceAtMost(LOOK_AHEAD_MAX_M)
        var first = segment
        while (first > 0 && distances[segment] - distances[first] < LOOK_BACK_M) first--
        var last = segment
        while (last < points.size - 2 && distances[last + 1] - progress < ahead) last++
        return first..last
    }

    private fun nearest(position: LatLon, range: IntRange, preferEarliest: Boolean = false, preferForward: Boolean = false): Match {
        var best = Match(range.first, 0.0, Double.MAX_VALUE)
        var bestAlong = 0.0
        for (i in range) {
            val projection = Geo.distanceToSegmentM(position, points[i], points[i + 1])
            val along = distances[i] + projection.fraction * (distances[i + 1] - distances[i])
            val better = when {
                // Au tout début, entre deux passages équivalents (boucle), on prend le premier dans l'ordre du parcours.
                preferEarliest -> projection.distanceM < best.distanceM - EQUIVALENT_M
                // Deux passages superposés (demi-tour) : à égalité, on avance plutôt que de reculer.
                preferForward && kotlin.math.abs(projection.distanceM - best.distanceM) < TIE_M ->
                    bestAlong < progress && along >= progress
                else -> projection.distanceM < best.distanceM
            }
            if (better) {
                best = Match(i, projection.fraction, projection.distanceM)
                bestAlong = along
            }
        }
        return best
    }

    private companion object {
        const val LOOK_BACK_M = 60.0
        const val LOOK_AHEAD_BASE_M = 50.0
        const val LOOK_AHEAD_MAX_M = 400.0
        /** Vitesse maximale crédible, pour borner la recherche en avant (20 m/s = 72 km/h). */
        const val MAX_SPEED_MPS = 20.0
        /** Écart de distance (m) en dessous duquel deux passages sont jugés aussi proches l'un que l'autre. */
        const val TIE_M = 3.0
        /** Au-delà de cet écart au passage en cours, on regarde si l'on n'est pas ailleurs sur la trace. */
        const val RELOCATE_M = 70.0
        /** Une consigne est considérée comme passée quand on l'a dépassée de cette distance. */
        const val PASSED_M = 12.0
        const val EQUIVALENT_M = 15.0
        /** Temps supposé écoulé depuis la dernière position suivie, pour borner la recherche de [snap] en avant. */
        const val SNAP_ELAPSED_MS = 2_000L
    }
}
