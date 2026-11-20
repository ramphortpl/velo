package fr.velogo.core.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Altitude en direct tirée du relief hors ligne (le téléphone n'a pas de baromètre). */
class TerrainAltitudeTest {

    private fun kmh(v: Double) = v / 3.6

    /** Roule seconde par seconde en joignant à chaque position l'altitude du sol donnée par [terrain]. */
    private fun rideWithTerrain(
        sim: RideSimulator, engine: RideEngine, seconds: Int, speedMps: Double, slopePct: Double,
        altitudeNoiseM: Double = 5.0, altitudeDriftM: Double = 6.0, terrain: (RideSimulator) -> Double? = { it.altitudeM },
        recorded: MutableList<TrackPoint> = ArrayList(),
    ): List<TrackPoint> {
        repeat(seconds) {
            sim.ride(1, speedMps, slopePct = { slopePct }, altitudeNoiseM = altitudeNoiseM, altitudeDriftM = altitudeDriftM)
            val fix = sim.fixes.last().copy(terrainAltitudeM = terrain(sim))
            engine.onFix(fix)?.let { recorded.add(it) }
            engine.onTick(fix.monoMs)
        }
        return recorded
    }

    @Test
    fun `avec le relief le denivele et la pente sont justes malgre un GPS bruite`() {
        val sim = RideSimulator(altitudeM = 450.0)
        val engine = RideEngine().apply { start(sim.monoMs, sim.timeMs) }
        // 20 minutes de montée régulière à 6 % puis 10 minutes de plat.
        rideWithTerrain(sim, engine, 1200, kmh(14.0), 6.0)
        val slopeInClimb = engine.snapshot().slopePct
        rideWithTerrain(sim, engine, 600, kmh(26.0), 0.0)
        val totals = engine.totals()

        assertTrue(engine.usesTerrain)
        assertEquals(sim.trueAscentM, totals.ascentM, sim.trueAscentM * 0.02)
        assertEquals(0.0, totals.descentM, 2.0)
        assertNotNull(slopeInClimb)
        assertEquals(6.0, slopeInClimb!!, 0.3)
        assertEquals(0.0, engine.snapshot().slopePct!!, 0.3)
        assertEquals(sim.altitudeM, engine.snapshot().altitudeM!!, 1.0)
    }

    @Test
    fun `la trace garde l'altitude du GPS pour le recalage de fin de sortie`() {
        val sim = RideSimulator(altitudeM = 200.0)
        val engine = RideEngine().apply { start(sim.monoMs, sim.timeMs) }
        // Le GPS est décalé de 12 m par rapport au relief : c'est sa valeur (lissée) qui doit être enregistrée.
        val recorded = rideWithTerrain(sim, engine, 300, kmh(20.0), 0.0, altitudeNoiseM = 0.0, altitudeDriftM = 0.0, terrain = { it.altitudeM - 12.0 })
        assertTrue(recorded.size > 250)
        assertEquals(200.0, recorded.last().altitudeM!!, 0.5)
        assertEquals(188.0, engine.snapshot().altitudeM!!, 0.5)
    }

    @Test
    fun `sans altitude GPS la trace prend celle du relief`() {
        val sim = RideSimulator(altitudeM = 320.0)
        val engine = RideEngine().apply { start(sim.monoMs, sim.timeMs) }
        val recorded = ArrayList<TrackPoint>()
        repeat(200) {
            sim.ride(1, kmh(18.0), slopePct = { 3.0 })
            val fix = sim.fixes.last().copy(altitudeM = null, terrainAltitudeM = sim.altitudeM)
            engine.onFix(fix)?.let { recorded.add(it) }
        }
        assertEquals(sim.altitudeM, recorded.last().altitudeM!!, 1.0)
        assertEquals(sim.trueAscentM, engine.totals().ascentM, 3.0)
    }

    @Test
    fun `quitter la zone couverte par le relief ne cree pas de faux denivele`() {
        val sim = RideSimulator(altitudeM = 100.0)
        val engine = RideEngine().apply { start(sim.monoMs, sim.timeMs) }
        // Plat parfait. Le GPS lit 15 m trop haut ; le relief n'est disponible que pendant les 10 premières minutes.
        var second = 0
        val terrain: (RideSimulator) -> Double? = { if (second++ < 600) it.altitudeM - 15.0 else null }
        rideWithTerrain(sim, engine, 1200, kmh(22.0), 0.0, altitudeNoiseM = 0.0, altitudeDriftM = 0.0, terrain = terrain)
        val totals = engine.totals()

        assertFalse(engine.usesTerrain)
        assertTrue("faux dénivelé : +${totals.ascentM} / -${totals.descentM}", totals.ascentM < 1.0 && totals.descentM < 1.0)
        assertTrue(abs(engine.snapshot().altitudeM!! - 100.0) < 1.0)
    }

    @Test
    fun `entrer dans une zone couverte en cours de sortie garde le denivele deja compte`() {
        val sim = RideSimulator(altitudeM = 600.0)
        val engine = RideEngine().apply { start(sim.monoMs, sim.timeMs) }
        // 100 m de montée au GPS seul (approximatif), puis 100 m avec le relief.
        rideWithTerrain(sim, engine, 600, kmh(12.0), 5.0, altitudeNoiseM = 3.0, altitudeDriftM = 4.0, terrain = { null })
        val beforeM = engine.totals().ascentM
        rideWithTerrain(sim, engine, 600, kmh(12.0), 5.0, altitudeNoiseM = 3.0, altitudeDriftM = 4.0)
        val afterM = engine.totals().ascentM

        assertTrue("dénivelé GPS seul : $beforeM", beforeM in 70.0..125.0)
        assertEquals(100.0, afterM - beforeM, 4.0)
    }
}
