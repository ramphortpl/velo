package fr.velogo.core.segments

import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.AscentAccumulator
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackPoint

/** Les mesures d'un morceau de sortie : celles du segment qu'on en tire. */
class SegmentMeasures(
    val lengthM: Double,
    val ascentM: Double,
    val startAltitudeM: Double?,
    val endAltitudeM: Double?,
) {
    /** Pente moyenne en %, si l'altitude des deux bouts est connue. */
    val gradePct: Double?
        get() {
            val start = startAltitudeM ?: return null
            val end = endAltitudeM ?: return null
            return if (lengthM > 0.0) (end - start) / lengthM * 100.0 else null
        }
}

/**
 * Découpe un morceau d'une sortie entre deux distances depuis le départ.
 *
 * L'écran de création d'un segment et le segment enregistré passent tous deux par ici : les chiffres annoncés
 * avant de créer sont exactement ceux du segment créé. Les deux bouts tombent aux distances demandées, entre
 * deux points de la trace s'il le faut. L'altitude est celle du profil de la sortie (lissée sur toute la trace,
 * pas seulement sur le morceau) et le dénivelé est compté avec le même seuil que celui de la sortie.
 */
class SegmentCutter(private val points: List<TrackPoint>, source: AltitudeSource) {
    private val distances = RideAnalyzer.cumulativeDistances(points)
    private val altitudes = RideAnalyzer.smoothedAltitudes(points, distances, source.slopeWindowM)
    private val thresholdM = source.ascentThresholdM

    /** Longueur de la sortie entière. */
    val totalM: Double get() = if (distances.isEmpty()) 0.0 else distances.last()

    /** Mesures du morceau compris entre [fromM] et [toM] (ramenés dans la sortie), ou null s'il est vide. */
    fun measure(fromM: Double, toM: Double): SegmentMeasures? {
        val span = span(fromM, toM) ?: return null
        val accumulator = AscentAccumulator(thresholdM)
        var first: Double? = null
        var last: Double? = null
        fun add(altitude: Double?) {
            if (altitude == null) return
            accumulator.add(altitude)
            if (first == null) first = altitude
            last = altitude
        }
        add(altitudeAt(span.before, span.from))
        for (i in span.before + 1 until span.after) add(altitudes[i])
        add(altitudeAt(span.after - 1, span.to))
        return SegmentMeasures(span.to - span.from, accumulator.ascentM, first, last)
    }

    /** Tracé du même morceau : vide s'il n'y a rien entre les deux distances. */
    fun line(fromM: Double, toM: Double): List<LatLon> {
        val span = span(fromM, toM) ?: return emptyList()
        val line = ArrayList<LatLon>(span.after - span.before + 1)
        fun add(position: LatLon) {
            if (line.isEmpty() || line.last() != position) line.add(position)
        }
        add(positionAt(span.before, span.from))
        for (i in span.before + 1 until span.after) add(LatLon(points[i].lat, points[i].lon))
        add(positionAt(span.after - 1, span.to))
        return if (line.size >= 2) line else emptyList()
    }

    /**
     * Le morceau demandé, ramené dans la sortie : [before] est le dernier point avant le début (ou dessus),
     * [after] le premier après la fin (ou dessus). Comme la fin est plus loin que le début, [after] > [before].
     */
    private class Span(val from: Double, val to: Double, val before: Int, val after: Int)

    private fun span(fromM: Double, toM: Double): Span? {
        if (points.size < 2 || fromM.isNaN() || toM.isNaN()) return null
        val from = fromM.coerceIn(0.0, totalM)
        val to = toM.coerceIn(0.0, totalM)
        if (to <= from) return null
        return Span(from, to, lastAtOrBefore(from), firstAtOrAfter(to))
    }

    /** Indice du dernier point situé à [distanceM] du départ ou avant. */
    private fun lastAtOrBefore(distanceM: Double): Int {
        var lo = 0
        var hi = distances.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (distances[mid] <= distanceM) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Indice du premier point situé à [distanceM] du départ ou après. */
    private fun firstAtOrAfter(distanceM: Double): Int {
        var lo = 0
        var hi = distances.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (distances[mid] >= distanceM) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Part du chemin déjà faite entre le point [i] et le suivant quand on est à [distanceM] du départ. */
    private fun fraction(i: Int, distanceM: Double): Double {
        if (i >= points.size - 1) return 0.0
        val step = distances[i + 1] - distances[i]
        return if (step > 0.0) ((distanceM - distances[i]) / step).coerceIn(0.0, 1.0) else 0.0
    }

    private fun positionAt(i: Int, distanceM: Double): LatLon {
        val a = points[i]
        val f = fraction(i, distanceM)
        if (f <= 0.0) return LatLon(a.lat, a.lon)
        val b = points[i + 1]
        if (f >= 1.0) return LatLon(b.lat, b.lon)
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    private fun altitudeAt(i: Int, distanceM: Double): Double? {
        val f = fraction(i, distanceM)
        val a = altitudes[i]
        val b = if (i < points.size - 1) altitudes[i + 1] else null
        return when {
            a != null && b != null -> a + (b - a) * f
            f >= 0.5 -> b ?: a
            else -> a ?: b
        }
    }
}
