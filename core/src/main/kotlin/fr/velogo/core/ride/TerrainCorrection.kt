package fr.velogo.core.ride

import kotlin.math.abs

/**
 * Recalage de l'altitude d'une trace sur un modèle de relief (IGN en ligne, pack de relief hors ligne).
 *
 * Le relief est bien plus précis que l'altitude GPS, mais il décrit le sol : sous un viaduc il plonge dans la
 * vallée, au-dessus d'un tunnel il grimpe sur la montagne. On s'appuie donc sur l'altitude GPS, grossière mais
 * fidèle à ce que le vélo a réellement fait, pour repérer ces passages et les rectifier.
 */
object TerrainCorrection {

    /** Indices des points à interroger : environ un tous les [stepM] mètres, extrémités comprises. */
    fun sampleIndices(distances: DoubleArray, stepM: Double, maxSamples: Int): IntArray {
        val n = distances.size
        if (n == 0) return IntArray(0)
        val total = distances.last()
        val step = maxOf(stepM, if (maxSamples > 1) total / (maxSamples - 1) else total)
        val out = ArrayList<Int>()
        var next = 0.0
        for (i in 0 until n) {
            if (i == 0 || distances[i] >= next) {
                out.add(i)
                next = distances[i] + step
            }
        }
        if (out.last() != n - 1) out.add(n - 1)
        return out.toIntArray()
    }

    /**
     * Étend à tous les points les altitudes connues aux [indices] (interpolation linéaire selon la distance).
     * Une valeur NaN dans [sampled] signifie « pas de donnée » : elle est ignorée.
     */
    fun interpolate(distances: DoubleArray, indices: IntArray, sampled: DoubleArray): DoubleArray {
        val n = distances.size
        val out = DoubleArray(n) { Double.NaN }
        val known = indices.indices.filter { !sampled[it].isNaN() }
        if (known.isEmpty()) return out
        var k = 0
        for (i in 0 until n) {
            while (k < known.size - 1 && indices[known[k + 1]] <= i) k++
            val a = known[k]
            val ia = indices[a]
            if (i <= ia || k == known.size - 1) {
                // Avant le premier point connu ou après le dernier : on prolonge à plat.
                out[i] = sampled[a]
            } else {
                val b = known[k + 1]
                val ib = indices[b]
                val span = distances[ib] - distances[ia]
                val t = if (span > 0) (distances[i] - distances[ia]) / span else 0.0
                out[i] = sampled[a] + t * (sampled[b] - sampled[a])
            }
        }
        return out
    }

    /**
     * Rectifie les passages où le relief s'écarte franchement de l'altitude GPS (viaduc, tunnel) :
     * sur ces tronçons, on relie en ligne droite les altitudes du relief de part et d'autre.
     */
    fun fuse(
        terrain: DoubleArray,
        gnss: List<Double?>,
        distances: DoubleArray,
        thresholdM: Double = 20.0,
        windowM: Double = 120.0,
        maxRunM: Double = 3_000.0,
    ): DoubleArray {
        val n = terrain.size
        val out = terrain.copyOf()
        // 1. Décalage constant entre GPS et relief (le GPS est souvent faux de quelques mètres, partout pareil).
        val offsets = ArrayList<Double>()
        for (i in 0 until n) {
            val g = gnss[i] ?: continue
            if (!terrain[i].isNaN()) offsets.add(g - terrain[i])
        }
        if (offsets.size < 10) return out
        offsets.sort()
        val bias = offsets[offsets.size / 2]

        // 2. Écart lissé sur une fenêtre glissante, pour ne pas réagir au bruit du GPS.
        val diff = DoubleArray(n) { i ->
            val g = gnss[i]
            if (g == null || terrain[i].isNaN()) Double.NaN else terrain[i] - (g - bias)
        }
        val smooth = DoubleArray(n) { Double.NaN }
        var lo = 0
        var hi = 0
        var sum = 0.0
        var count = 0
        val half = windowM / 2
        for (i in 0 until n) {
            while (hi < n && distances[hi] <= distances[i] + half) { if (!diff[hi].isNaN()) { sum += diff[hi]; count++ }; hi++ }
            while (lo < i && distances[lo] < distances[i] - half) { if (!diff[lo].isNaN()) { sum -= diff[lo]; count-- }; lo++ }
            if (count > 0) smooth[i] = sum / count
        }

        // 3. Chaque tronçon anormal est remplacé par une rampe entre ses deux bords sains.
        var i = 0
        while (i < n) {
            if (smooth[i].isNaN() || abs(smooth[i]) <= thresholdM) { i++; continue }
            var start = i
            var end = i
            while (end + 1 < n && !smooth[end + 1].isNaN() && abs(smooth[end + 1]) > thresholdM * 0.5) end++
            while (start > 0 && !smooth[start - 1].isNaN() && abs(smooth[start - 1]) > thresholdM * 0.5) start--
            val before = start - 1
            val after = end + 1
            if (before >= 0 && after < n && distances[after] - distances[before] <= maxRunM) {
                val za = out[before]
                val zb = out[after]
                val span = distances[after] - distances[before]
                for (j in start..end) {
                    val t = if (span > 0) (distances[j] - distances[before]) / span else 0.0
                    out[j] = za + t * (zb - za)
                }
            }
            i = end + 1
        }
        return out
    }
}
