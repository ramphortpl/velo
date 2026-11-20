package fr.velogo.core.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TerrainCorrectionTest {

    private fun distances(n: Int, step: Double) = DoubleArray(n) { it * step }

    @Test
    fun `echantillonnage regulier avec les deux extremites`() {
        val d = distances(1001, 5.0) // 5 km, un point tous les 5 m
        val idx = TerrainCorrection.sampleIndices(d, stepM = 12.0, maxSamples = 10_000)
        assertEquals(0, idx.first())
        assertEquals(1000, idx.last())
        for (k in 1 until idx.size - 1) assertTrue(d[idx[k]] - d[idx[k - 1]] >= 12.0)
        assertTrue(idx.size in 300..420)
    }

    @Test
    fun `le nombre de points demandes est plafonne`() {
        val d = distances(20_001, 5.0) // 100 km
        val idx = TerrainCorrection.sampleIndices(d, stepM = 12.0, maxSamples = 2_000)
        assertTrue("${idx.size} points", idx.size <= 2_001)
    }

    @Test
    fun `interpolation lineaire entre les points connus`() {
        val d = distances(11, 10.0)
        val idx = intArrayOf(0, 5, 10)
        val z = TerrainCorrection.interpolate(d, idx, doubleArrayOf(100.0, 150.0, 130.0))
        assertEquals(100.0, z[0], 1e-9)
        assertEquals(120.0, z[2], 1e-9)
        assertEquals(150.0, z[5], 1e-9)
        assertEquals(138.0, z[8], 1e-9)
        assertEquals(130.0, z[10], 1e-9)
    }

    @Test
    fun `les trous de donnees sont enjambes`() {
        val d = distances(11, 10.0)
        val idx = intArrayOf(0, 5, 10)
        val z = TerrainCorrection.interpolate(d, idx, doubleArrayOf(100.0, Double.NaN, 200.0))
        assertEquals(150.0, z[5], 1e-9)
        val none = TerrainCorrection.interpolate(d, idx, doubleArrayOf(Double.NaN, Double.NaN, Double.NaN))
        assertTrue(none.all { it.isNaN() })
    }

    @Test
    fun `viaduc - le plongeon du relief dans la vallee est gomme`() {
        val n = 2_000
        val d = distances(n, 5.0) // 10 km
        val random = java.util.Random(4)
        // Le vélo roule à plat à 300 m ; le GPS le voit 9 m trop haut, avec du bruit.
        val gnss = List<Double?>(n) { 309.0 + random.nextGaussian() * 2.5 }
        // Le relief suit la route, sauf sur 400 m où il descend de 45 m sous le viaduc.
        val terrain = DoubleArray(n) { i -> if (d[i] in 4_000.0..4_400.0) 255.0 else 300.0 }
        val fused = TerrainCorrection.fuse(terrain, gnss, d)
        for (i in 0 until n) assertEquals("à ${d[i]} m", 300.0, fused[i], 1.0)
    }

    @Test
    fun `tunnel - la bosse du relief au-dessus est gommee`() {
        val n = 1_500
        val d = distances(n, 6.0)
        val gnss = List<Double?>(n) { 500.0 + d[it] * 0.01 } // légère montée régulière
        val terrain = DoubleArray(n) { i -> 500.0 + d[i] * 0.01 + if (d[i] in 3_000.0..3_900.0) 120.0 else 0.0 }
        val fused = TerrainCorrection.fuse(terrain, gnss, d)
        for (i in 0 until n) assertTrue(abs(fused[i] - (500.0 + d[i] * 0.01)) < 1.5)
    }

    @Test
    fun `un vrai col n'est pas touche`() {
        val n = 3_000
        val d = distances(n, 6.0)
        val random = java.util.Random(9)
        val profile = DoubleArray(n) { i -> 400.0 + 600.0 * kotlin.math.sin(Math.PI * i / n) }
        val gnss = List<Double?>(n) { profile[it] - 6.0 + random.nextGaussian() * 3.0 }
        val fused = TerrainCorrection.fuse(profile, gnss, d)
        for (i in 0 until n) assertEquals(profile[i], fused[i], 1e-9)
    }

    @Test
    fun `sans altitude GPS le relief est garde tel quel`() {
        val n = 200
        val d = distances(n, 5.0)
        val terrain = DoubleArray(n) { 100.0 + it }
        val fused = TerrainCorrection.fuse(terrain, List<Double?>(n) { null }, d)
        assertTrue(terrain.contentEquals(fused))
    }
}
