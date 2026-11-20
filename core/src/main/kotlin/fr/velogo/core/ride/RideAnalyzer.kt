package fr.velogo.core.ride

import fr.velogo.core.geo.Geo
import kotlin.math.max
import kotlin.math.min

/** Un échantillon de profil : sert aux graphiques vitesse / altitude en fonction de la distance. */
data class ProfileSample(
    val distanceM: Double,
    val timeMs: Long,
    val altitudeM: Double?,
    val speedMps: Double?,
    val lat: Double,
    val lon: Double,
)

/** Recalcule les totaux et les profils d'une trace enregistrée ou importée. */
object RideAnalyzer {

    /** Distances cumulées (m) de chaque point depuis le départ ; les sauts entre tronçons ne comptent pas. */
    fun cumulativeDistances(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            out[i] = out[i - 1] + if (a.segment == b.segment) Geo.distanceM(a.lat, a.lon, b.lat, b.lon) else 0.0
        }
        return out
    }

    /**
     * Altitudes lissées par moyenne glissante sur [windowM] mètres de parcours.
     * Renvoie null aux points sans altitude.
     */
    fun smoothedAltitudes(points: List<TrackPoint>, distances: DoubleArray, windowM: Double): Array<Double?> {
        val n = points.size
        val out = arrayOfNulls<Double>(n)
        var lo = 0
        var hi = 0
        var sum = 0.0
        var count = 0
        val half = windowM / 2
        for (i in 0 until n) {
            while (hi < n && distances[hi] <= distances[i] + half) {
                points[hi].altitudeM?.let { sum += it; count++ }
                hi++
            }
            while (lo < i && distances[lo] < distances[i] - half) {
                points[lo].altitudeM?.let { sum -= it; count-- }
                lo++
            }
            out[i] = if (points[i].altitudeM != null && count > 0) sum / count else null
        }
        return out
    }

    /** Totaux d'une trace : distance, temps en mouvement, vitesses, dénivelés. */
    fun summarize(
        points: List<TrackPoint>,
        source: AltitudeSource = AltitudeSource.GNSS,
        stoppedSpeedMps: Double = 1.0,
    ): RideTotals {
        if (points.size < 2) return RideTotals()
        val distances = cumulativeDistances(points)
        var movingMs = 0L
        var maxSpeed = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.segment != b.segment) continue
            val dtMs = b.timeMs - a.timeMs
            if (dtMs <= 0) continue
            val d = distances[i] - distances[i - 1]
            val speed = d / (dtMs / 1000.0)
            // Pendant une pause aucun point n'est enregistré : l'intervalle est long et la « vitesse » quasi nulle.
            if (speed >= stoppedSpeedMps && speed <= MAX_PLAUSIBLE_SPEED) movingMs += dtMs
            val s = b.speedMps ?: speed
            if (s in 0.0..MAX_PLAUSIBLE_SPEED) maxSpeed = max(maxSpeed, s)
        }
        val smoothed = smoothedAltitudes(points, distances, source.slopeWindowM)
        val accumulator = AscentAccumulator(source.ascentThresholdM)
        var minAlt: Double? = null
        var maxAlt: Double? = null
        for (alt in smoothed) {
            if (alt == null) continue
            accumulator.add(alt)
            minAlt = min(minAlt ?: alt, alt)
            maxAlt = max(maxAlt ?: alt, alt)
        }
        return RideTotals(
            distanceM = distances.last(),
            movingMs = movingMs,
            elapsedMs = max(0L, points.last().timeMs - points.first().timeMs),
            maxSpeedMps = maxSpeed,
            ascentM = accumulator.ascentM,
            descentM = accumulator.descentM,
            minAltitudeM = minAlt,
            maxAltitudeM = maxAlt,
        )
    }

    /**
     * Profil régulièrement échantillonné en distance (au plus [maxSamples] points) pour les graphiques.
     * La vitesse est moyennée sur chaque intervalle, l'altitude est lissée.
     */
    fun profile(
        points: List<TrackPoint>,
        maxSamples: Int = 400,
        source: AltitudeSource = AltitudeSource.GNSS,
    ): List<ProfileSample> {
        if (points.isEmpty()) return emptyList()
        val distances = cumulativeDistances(points)
        val total = distances.last()
        val smoothed = smoothedAltitudes(points, distances, source.slopeWindowM)
        if (points.size <= maxSamples || total <= 0.0) {
            return points.indices.map { i ->
                ProfileSample(distances[i], points[i].timeMs, smoothed[i], points[i].speedMps, points[i].lat, points[i].lon)
            }
        }
        val step = total / (maxSamples - 1)
        val out = ArrayList<ProfileSample>(maxSamples)
        var start = 0
        for (k in 0 until maxSamples) {
            val target = k * step
            var end = start
            while (end < points.size - 1 && distances[end] < target) end++
            var speedSum = 0.0
            var speedCount = 0
            for (j in start..end) points[j].speedMps?.let { speedSum += it; speedCount++ }
            out.add(
                ProfileSample(
                    distanceM = distances[end],
                    timeMs = points[end].timeMs,
                    altitudeM = smoothed[end],
                    speedMps = if (speedCount > 0) speedSum / speedCount else null,
                    lat = points[end].lat,
                    lon = points[end].lon,
                )
            )
            start = end
        }
        return out
    }

    private const val MAX_PLAUSIBLE_SPEED = 33.0
}
