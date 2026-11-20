package fr.velogo.core.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RideAnalyzerTest {

    private fun kmh(v: Double) = v / 3.6

    @Test
    fun `les totaux recalcules depuis la trace collent a ceux du direct`() {
        val sim = RideSimulator(altitudeM = 500.0)
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.TERRAIN)).apply { start(sim.monoMs, sim.timeMs) }
        sim.ride(seconds = 900, speedMps = kmh(22.0), slopePct = { 4.0 })
        sim.stand(seconds = 120)
        sim.ride(seconds = 900, speedMps = kmh(35.0), bearingDeg = 200.0, slopePct = { -4.0 })
        val recorded = engine.feed(sim.fixes)
        val live = engine.totals()
        val recomputed = RideAnalyzer.summarize(recorded, AltitudeSource.TERRAIN)

        assertEquals(live.distanceM, recomputed.distanceM, live.distanceM * 0.002)
        assertEquals(live.movingMs.toDouble(), recomputed.movingMs.toDouble(), 8_000.0)
        assertEquals(live.ascentM, recomputed.ascentM, live.ascentM * 0.04)
        assertEquals(live.descentM, recomputed.descentM, live.descentM * 0.04)
        assertEquals(35.0, recomputed.maxSpeedMps * 3.6, 0.5)
        assertEquals(1_920_000.0, recomputed.elapsedMs.toDouble(), 6_000.0)
    }

    @Test
    fun `trace importee sans vitesse ni pause`() {
        val sim = RideSimulator()
        sim.ride(seconds = 600, speedMps = 5.0)
        val points = sim.fixes.map { TrackPoint(it.timeMs, it.lat, it.lon, it.altitudeM, null, null, 0) }
        val totals = RideAnalyzer.summarize(points)
        assertEquals(2_995.0, totals.distanceM, 3.0)
        assertEquals(599_000L, totals.movingMs)
        assertEquals(5.0, totals.avgSpeedMps, 0.01)
        assertEquals(5.0, totals.maxSpeedMps, 0.05)
    }

    @Test
    fun `les sauts entre troncons ne comptent pas dans la distance`() {
        val a = (0 until 100).map { TrackPoint(it * 1000L, 45.0 + it * 0.00005, 5.0, 100.0, 5.5, 4.0, 0) }
        val b = (0 until 100).map { TrackPoint(200_000L + it * 1000L, 45.2 + it * 0.00005, 5.0, 100.0, 5.5, 4.0, 1) }
        val totals = RideAnalyzer.summarize(a + b)
        // Deux tronçons d'environ 550 m ; les 22 km qui les séparent sont ignorés.
        assertEquals(1_100.0, totals.distanceM, 10.0)
    }

    @Test
    fun `trace vide ou a un point`() {
        assertEquals(RideTotals(), RideAnalyzer.summarize(emptyList()))
        assertEquals(RideTotals(), RideAnalyzer.summarize(listOf(TrackPoint(0, 45.0, 5.0))))
        assertTrue(RideAnalyzer.profile(emptyList()).isEmpty())
    }

    @Test
    fun `profil echantillonne pour les graphiques`() {
        val sim = RideSimulator(altitudeM = 200.0)
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.TERRAIN)).apply { start(sim.monoMs, sim.timeMs) }
        sim.ride(seconds = 3000, speedMps = 6.0, slopePct = { 3.0 })
        val recorded = engine.feed(sim.fixes)
        val profile = RideAnalyzer.profile(recorded, maxSamples = 300, source = AltitudeSource.TERRAIN)

        assertEquals(300, profile.size)
        assertEquals(0.0, profile.first().distanceM, 1e-9)
        assertEquals(RideAnalyzer.cumulativeDistances(recorded).last(), profile.last().distanceM, 10.0)
        for (i in 1 until profile.size) assertTrue(profile[i].distanceM >= profile[i - 1].distanceM)
        // 18 km à 3 % : 540 m de montée, lisible sur le profil.
        val gain = profile.last().altitudeM!! - profile.first().altitudeM!!
        assertTrue("gain $gain", abs(gain - 540.0) < 15.0)
        assertEquals(6.0, profile[150].speedMps!!, 0.1)
    }

    @Test
    fun `lissage d'altitude par fenetre glissante`() {
        val points = (0 until 200).map { TrackPoint(it * 1000L, 45.0 + it * 0.00009, 5.0, 100.0 + if (it % 2 == 0) 3.0 else -3.0, 5.0, 4.0, 0) }
        val distances = RideAnalyzer.cumulativeDistances(points)
        val smoothed = RideAnalyzer.smoothedAltitudes(points, distances, windowM = 80.0)
        for (i in 10 until 190) assertEquals(100.0, smoothed[i]!!, 0.8)
    }
}
