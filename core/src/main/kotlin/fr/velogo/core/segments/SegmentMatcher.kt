package fr.velogo.core.segments

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.TrackPoint

/** Un passage d'une sortie sur un segment. */
data class SegmentPass(
    /** Indice du point de la trace juste avant l'entrée dans le segment. */
    val startIndex: Int,
    /** Indice du point de la trace juste avant la sortie du segment. */
    val endIndex: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
) {
    val elapsedMs: Long get() = endTimeMs - startTimeMs
}

/**
 * Retrouve dans une trace les passages sur un segment (un bout de route choisi par l'utilisateur).
 *
 * Un passage, c'est : passer au point de départ du segment, le suivre dans le bon sens sans s'en écarter de plus
 * de [toleranceM], et passer à son point d'arrivée. Les heures d'entrée et de sortie sont interpolées entre les
 * points de la trace, pour que le temps ne dépende pas de l'endroit exact où le GPS a posé ses points.
 */
object SegmentMatcher {
    /** Écart toléré entre la trace et le segment : la précision de deux enregistrements GPS, plus la largeur de la route. */
    const val DEFAULT_TOLERANCE_M = 30.0

    fun passes(track: List<TrackPoint>, segment: List<LatLon>, toleranceM: Double = DEFAULT_TOLERANCE_M): List<SegmentPass> {
        if (track.size < 2 || segment.size < 2) return emptyList()
        val shape = Shape(segment)
        if (shape.length < MIN_LENGTH_M) return emptyList()
        val out = ArrayList<SegmentPass>()
        var i = 0
        while (i < track.size - 1) {
            val entry = entry(track, i, shape.first, toleranceM)
            if (entry == null) {
                i++
                continue
            }
            val pass = follow(track, entry, shape, toleranceM)
            if (pass != null) {
                out.add(pass)
                // Sur une boucle, l'arrivée d'un tour est le départ du suivant : on repart du tronçon d'arrivée.
                i = maxOf(pass.endIndex, entry.index + 1)
            } else {
                i = entry.index + 1
            }
        }
        return out
    }

    /** Un endroit précis de la trace : entre les points [index] et [index] + 1, à la fraction [fraction]. */
    private class Spot(val index: Int, val fraction: Double, val distanceM: Double)

    /**
     * Cherche, à partir du tronçon [from], le passage de la trace au plus près de [target] : le premier tronçon à
     * moins de [toleranceM], puis les suivants tant qu'ils s'en rapprochent encore.
     */
    private fun entry(track: List<TrackPoint>, from: Int, target: LatLon, toleranceM: Double): Spot? {
        var best = spot(track, from, target)
        if (best.distanceM > toleranceM) return null
        var k = from + 1
        var travelled = 0.0
        while (k < track.size - 1 && travelled < LOOKAHEAD_M) {
            val next = spot(track, k, target)
            if (next.distanceM < best.distanceM) best = next
            else if (next.distanceM > best.distanceM + MOVING_AWAY_M) break
            travelled += Geo.distanceM(track[k].lat, track[k].lon, track[k + 1].lat, track[k + 1].lon)
            k++
        }
        return best
    }

    private fun spot(track: List<TrackPoint>, index: Int, target: LatLon): Spot {
        val a = track[index]
        val b = track[index + 1]
        val projection = Geo.distanceToSegmentM(target, LatLon(a.lat, a.lon), LatLon(b.lat, b.lon))
        return Spot(index, projection.fraction, projection.distanceM)
    }

    private fun timeAt(track: List<TrackPoint>, spot: Spot): Long {
        val a = track[spot.index].timeMs
        val b = track[spot.index + 1].timeMs
        return a + (spot.fraction * (b - a)).toLong()
    }

    /** Suit le segment à partir de l'entrée [entry]. Renvoie le passage s'il va jusqu'au bout, null sinon. */
    private fun follow(track: List<TrackPoint>, entry: Spot, shape: Shape, toleranceM: Double): SegmentPass? {
        var progress = 0.0
        var travelled = 0.0
        var off = 0.0
        val a = track[entry.index]
        val b = track[entry.index + 1]
        var previous = LatLon(a.lat + (b.lat - a.lat) * entry.fraction, a.lon + (b.lon - a.lon) * entry.fraction)
        val maxOff = maxOf(MAX_OFF_M, 2 * toleranceM)
        for (k in entry.index + 1 until track.size) {
            val point = LatLon(track[k].lat, track[k].lon)
            val step = Geo.distanceM(previous, point)
            travelled += step
            previous = point
            // On ne cherche que juste devant soi : dans un lacet, la route repasse tout près d'elle-même.
            val projection = shape.project(point, progress - BACK_M, progress + AHEAD_M + 2 * step)
            if (projection.distanceM <= toleranceM) {
                if (projection.progressM > progress) progress = projection.progressM
                off = 0.0
            } else {
                off += step
                if (off > maxOff) return null
            }
            if (travelled > 3 * shape.length + 1_000.0) return null
            if (progress >= shape.length - toleranceM) {
                // Presque au bout : la sortie est le passage au plus près du point d'arrivée, à partir d'ici.
                val exit = entry(track, (k - 1).coerceAtMost(track.size - 2), shape.last, 2 * toleranceM) ?: return null
                if (exit.index < entry.index || (exit.index == entry.index && exit.fraction <= entry.fraction)) return null
                val startMs = timeAt(track, entry)
                val endMs = timeAt(track, exit)
                val elapsed = endMs - startMs
                if (elapsed <= 0) return null
                // On doit avoir roulé à peu près la longueur du segment, à une vitesse possible à vélo.
                val covered = covered(track, entry, exit)
                if (covered < shape.length * 0.7 || covered > shape.length * 1.5 + 100.0) return null
                if (shape.length / (elapsed / 1000.0) > MAX_PLAUSIBLE_MPS) return null
                return SegmentPass(entry.index, exit.index, startMs, endMs)
            }
        }
        return null
    }

    /** Distance parcourue le long de la trace entre deux endroits. */
    private fun covered(track: List<TrackPoint>, from: Spot, to: Spot): Double {
        fun edge(i: Int) = Geo.distanceM(track[i].lat, track[i].lon, track[i + 1].lat, track[i + 1].lon)
        if (from.index == to.index) return edge(from.index) * (to.fraction - from.fraction)
        var total = edge(from.index) * (1 - from.fraction) + edge(to.index) * to.fraction
        for (i in from.index + 1 until to.index) total += edge(i)
        return total
    }

    private class Projection(val distanceM: Double, val progressM: Double)

    /** Le tracé du segment, avec la distance cumulée à chacun de ses points. */
    private class Shape(val points: List<LatLon>) {
        val cumulative = DoubleArray(points.size).also { for (i in 1 until points.size) it[i] = it[i - 1] + Geo.distanceM(points[i - 1], points[i]) }
        val length: Double get() = cumulative.last()
        val first: LatLon get() = points.first()
        val last: LatLon get() = points.last()

        /** Point du segment le plus proche de [p], cherché seulement entre les distances [fromM] et [toM] du départ. */
        fun project(p: LatLon, fromM: Double, toM: Double): Projection {
            var best = Double.MAX_VALUE
            var progress = 0.0
            var i = lowerBound(fromM)
            while (i < points.size - 1 && cumulative[i] <= toM) {
                val projection = Geo.distanceToSegmentM(p, points[i], points[i + 1])
                val at = cumulative[i] + projection.fraction * (cumulative[i + 1] - cumulative[i])
                if (projection.distanceM < best && at >= fromM - 1.0 && at <= toM + 1.0) {
                    best = projection.distanceM
                    progress = at
                }
                i++
            }
            return Projection(best, progress)
        }

        /** Indice du tronçon qui contient la distance [d] (0 si elle est avant le départ). */
        private fun lowerBound(d: Double): Int {
            var low = 0
            var high = points.size - 1
            while (high - low > 1) {
                val middle = (low + high) ushr 1
                if (cumulative[middle] <= d) low = middle else high = middle
            }
            return low
        }
    }

    private const val MIN_LENGTH_M = 100.0
    private const val BACK_M = 30.0
    private const val AHEAD_M = 150.0
    /** Distance qu'on peut rouler hors du segment (tunnel, GPS qui décroche) avant d'abandonner le passage. */
    private const val MAX_OFF_M = 80.0
    private const val LOOKAHEAD_M = 300.0
    private const val MOVING_AWAY_M = 15.0
    private const val MAX_PLAUSIBLE_MPS = 25.0
}
