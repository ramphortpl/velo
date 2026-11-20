package fr.velogo.core.geo

import kotlin.math.roundToLong

/** Encodage compact d'une ligne (algorithme « encoded polyline », précision 1e-5 ≈ 1 m). */
object Polyline {
    private const val PRECISION = 1e5

    fun encode(points: List<LatLon>): String {
        val sb = StringBuilder(points.size * 6)
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.lat * PRECISION).roundToLong()
            val lon = (p.lon * PRECISION).roundToLong()
            encodeValue(lat - lastLat, sb)
            encodeValue(lon - lastLon, sb)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    fun decode(encoded: String): List<LatLon> {
        val out = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            if (index >= encoded.length) break
            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(LatLon(lat / PRECISION, lon / PRECISION))
        }
        return out
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }
}

/** Simplification de lignes (Douglas-Peucker) : garde la forme en réduisant le nombre de points. */
object Simplify {

    /** Renvoie les indices des points conservés pour une tolérance de [toleranceM] mètres. */
    fun indices(points: List<LatLon>, toleranceM: Double): IntArray {
        val n = points.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxDistance = 0.0
            var index = -1
            for (i in first + 1 until last) {
                val d = Geo.distanceToSegmentM(points[i], points[first], points[last]).distanceM
                if (d > maxDistance) { maxDistance = d; index = i }
            }
            if (index >= 0 && maxDistance > toleranceM) {
                keep[index] = true
                stack.addLast(intArrayOf(first, index))
                stack.addLast(intArrayOf(index, last))
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> =
        indices(points, toleranceM).map { points[it] }

    /** Simplifie jusqu'à obtenir au plus [maxPoints] points, en augmentant la tolérance si nécessaire. */
    fun toMaxPoints(points: List<LatLon>, maxPoints: Int, startToleranceM: Double = 2.0): List<LatLon> {
        if (points.size <= maxPoints) return points
        var tolerance = startToleranceM
        var result = simplify(points, tolerance)
        var guard = 0
        while (result.size > maxPoints && guard++ < 24) {
            tolerance *= 1.7
            result = simplify(points, tolerance)
        }
        return result
    }
}
