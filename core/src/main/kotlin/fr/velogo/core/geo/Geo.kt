package fr.velogo.core.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Point géographique WGS84, en degrés décimaux. */
data class LatLon(val lat: Double, val lon: Double)

/** Rectangle englobant, en degrés décimaux. */
data class BoundingBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        fun of(points: Iterable<LatLon>): BoundingBox? {
            var minLat = Double.POSITIVE_INFINITY
            var minLon = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            var any = false
            for (p in points) {
                any = true
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
            }
            return if (any) BoundingBox(minLat, minLon, maxLat, maxLon) else null
        }
    }
}

/** Calculs géodésiques sur l'ellipsoïde WGS84. */
object Geo {
    /** Demi-grand axe WGS84 (m). */
    const val A = 6_378_137.0
    private const val F = 1.0 / 298.257223563
    private const val B = A * (1 - F)
    private const val E2 = F * (2 - F)

    /** France métropolitaine et Corse, avec une marge : sert à borner la carte et les recherches. */
    val FRANCE = BoundingBox(minLat = 41.2, minLon = -5.6, maxLat = 51.3, maxLon = 9.8)

    /**
     * Distance en mètres entre deux points.
     *
     * Pour des points proches (le cas du GPS à 1 Hz), on projette localement sur l'ellipsoïde avec les deux
     * rayons de courbure : l'erreur reste sous le centimètre par kilomètre. Au-delà de 0,1° d'écart, on passe
     * à la formule de Vincenty.
     */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat2 - lat1
        val dLon = normalizeLonDelta(lon2 - lon1)
        if (abs(dLat) > 0.1 || abs(dLon) > 0.1) return vincentyM(lat1, lon1, lat2, lon2)
        val phi = Math.toRadians((lat1 + lat2) / 2)
        val sinPhi = sin(phi)
        val w2 = 1 - E2 * sinPhi * sinPhi
        val w = sqrt(w2)
        val meridianRadius = A * (1 - E2) / (w2 * w)
        val normalRadius = A / w
        val dy = Math.toRadians(dLat) * meridianRadius
        val dx = Math.toRadians(dLon) * normalRadius * cos(phi)
        return sqrt(dx * dx + dy * dy)
    }

    fun distanceM(a: LatLon, b: LatLon): Double = distanceM(a.lat, a.lon, b.lat, b.lon)

    /** Distance géodésique exacte (Vincenty), avec repli sphérique si l'itération ne converge pas. */
    fun vincentyM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == lat2 && lon1 == lon2) return 0.0
        val l = Math.toRadians(normalizeLonDelta(lon2 - lon1))
        val u1 = atan((1 - F) * tan(Math.toRadians(lat1)))
        val u2 = atan((1 - F) * tan(Math.toRadians(lat2)))
        val sinU1 = sin(u1); val cosU1 = cos(u1)
        val sinU2 = sin(u2); val cosU2 = cos(u2)
        var lambda = l
        var iterations = 0
        while (iterations++ < 100) {
            val sinLambda = sin(lambda); val cosLambda = cos(lambda)
            val sinSigma = sqrt(
                (cosU2 * sinLambda) * (cosU2 * sinLambda) +
                    (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda) * (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
            )
            if (sinSigma == 0.0) return 0.0
            val cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            val sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
            val cos2Alpha = 1 - sinAlpha * sinAlpha
            val cos2SigmaM = if (cos2Alpha == 0.0) 0.0 else cosSigma - 2 * sinU1 * sinU2 / cos2Alpha
            val c = F / 16 * cos2Alpha * (4 + F * (4 - 3 * cos2Alpha))
            val previous = lambda
            lambda = l + (1 - c) * F * sinAlpha *
                (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
            if (abs(lambda - previous) < 1e-12) {
                val u2sq = cos2Alpha * (A * A - B * B) / (B * B)
                val bigA = 1 + u2sq / 16384 * (4096 + u2sq * (-768 + u2sq * (320 - 175 * u2sq)))
                val bigB = u2sq / 1024 * (256 + u2sq * (-128 + u2sq * (74 - 47 * u2sq)))
                val deltaSigma = bigB * sinSigma * (
                    cos2SigmaM + bigB / 4 * (
                        cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
                            bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)
                        )
                    )
                return B * bigA * (sigma - deltaSigma)
            }
        }
        return haversineM(lat1, lon1, lat2, lon2)
    }

    /** Distance sur une sphère de rayon moyen : rapide, précise à ~0,3 % près. */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(normalizeLonDelta(lon2 - lon1))
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * MEAN_RADIUS * asin(min(1.0, sqrt(h)))
    }

    /** Cap initial de [lat1, lon1] vers [lat2, lon2], en degrés dans [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(normalizeLonDelta(lon2 - lon1))
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point atteint en partant de [lat, lon] dans la direction [bearingDeg] sur [distanceM] mètres. */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): LatLon {
        val delta = distanceM / MEAN_RADIUS
        val theta = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(delta) + cos(p1) * sin(delta) * cos(theta))
        val l2 = l1 + atan2(sin(theta) * sin(delta) * cos(p1), cos(delta) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), normalizeLon(Math.toDegrees(l2)))
    }

    /** Plus petit écart angulaire signé de [from] vers [to], dans (-180, 180]. */
    fun angleDeltaDeg(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Longueur d'une ligne brisée, en mètres. */
    fun lengthM(points: List<LatLon>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distanceM(points[i - 1], points[i])
        return total
    }

    /**
     * Distance en mètres du point P au segment [A, B], et position du point le plus proche le long du segment
     * (0 = A, 1 = B). Calcul en projection locale : valable pour des segments de quelques kilomètres.
     */
    fun distanceToSegmentM(p: LatLon, a: LatLon, b: LatLon): SegmentProjection {
        val cosLat = cos(Math.toRadians(p.lat))
        val ax = Math.toRadians(normalizeLonDelta(a.lon - p.lon)) * cosLat * MEAN_RADIUS
        val ay = Math.toRadians(a.lat - p.lat) * MEAN_RADIUS
        val bx = Math.toRadians(normalizeLonDelta(b.lon - p.lon)) * cosLat * MEAN_RADIUS
        val by = Math.toRadians(b.lat - p.lat) * MEAN_RADIUS
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val cx = ax + tc * dx
        val cy = ay + tc * dy
        return SegmentProjection(distanceM = sqrt(cx * cx + cy * cy), fraction = tc)
    }

    private fun normalizeLon(lon: Double): Double {
        var l = lon % 360.0
        if (l > 180.0) l -= 360.0
        if (l < -180.0) l += 360.0
        return l
    }

    private fun normalizeLonDelta(d: Double): Double {
        var x = d
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }

    /** Rayon moyen de la Terre (m). */
    const val MEAN_RADIUS = 6_371_008.8
}

data class SegmentProjection(val distanceM: Double, val fraction: Double)
