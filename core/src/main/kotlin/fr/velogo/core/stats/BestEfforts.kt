package fr.velogo.core.stats

import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackPoint

/** Le meilleur temps réalisé sur une distance donnée au cours d'une sortie. */
data class BestEffort(
    val distanceM: Int,
    val durationMs: Long,
    /** Heure à laquelle ce passage a commencé. */
    val startTimeMs: Long,
    /** Distance parcourue depuis le départ de la sortie au début de ce passage, en mètres. */
    val startDistanceM: Double,
)

/**
 * Meilleurs temps sur des distances de référence (« mon meilleur 10 km »).
 *
 * Le temps compté est le temps réel écoulé entre les deux bouts du passage, arrêts compris : s'arrêter à un feu
 * fait partie de l'effort, comme dans une course.
 */
object BestEfforts {
    /** Distances de référence, en mètres. */
    val DISTANCES = intArrayOf(5_000, 10_000, 20_000, 30_000, 50_000, 80_000, 100_000)

    fun compute(points: List<TrackPoint>, distances: IntArray = DISTANCES): List<BestEffort> {
        if (points.size < 2) return emptyList()
        val cumulative = RideAnalyzer.cumulativeDistances(points)
        val total = cumulative.last()
        val out = ArrayList<BestEffort>(distances.size)
        for (target in distances) {
            if (total < target) continue
            best(points, cumulative, target)?.let { out.add(it) }
        }
        return out
    }

    /** Fenêtre glissante : pour chaque point d'arrivée, le départ situé exactement [target] mètres avant. */
    private fun best(points: List<TrackPoint>, cumulative: DoubleArray, target: Int): BestEffort? {
        var start = 0
        var bestMs = Long.MAX_VALUE
        var bestStartMs = 0L
        var bestStartM = 0.0
        for (end in 1 until points.size) {
            val from = cumulative[end] - target
            if (from < 0.0) continue
            while (start + 1 < end && cumulative[start + 1] <= from) start++
            // Le départ exact tombe entre deux points : son heure est interpolée.
            val span = cumulative[start + 1] - cumulative[start]
            val fraction = if (span > 0.0) ((from - cumulative[start]) / span).coerceIn(0.0, 1.0) else 0.0
            val startMs = points[start].timeMs + (fraction * (points[start + 1].timeMs - points[start].timeMs)).toLong()
            val duration = points[end].timeMs - startMs
            if (duration in 1 until bestMs) {
                bestMs = duration
                bestStartMs = startMs
                bestStartM = from
            }
        }
        // Une vitesse impossible à vélo trahit une trace abîmée (saut du GPS) : pas de record là-dessus.
        if (bestMs == Long.MAX_VALUE || target / (bestMs / 1000.0) > MAX_PLAUSIBLE_MPS) return null
        return BestEffort(target, bestMs, bestStartMs, bestStartM)
    }

    private const val MAX_PLAUSIBLE_MPS = 25.0
}
