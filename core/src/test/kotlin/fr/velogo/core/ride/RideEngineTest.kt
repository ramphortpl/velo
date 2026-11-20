package fr.velogo.core.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RideEngineTest {

    private fun kmh(v: Double) = v / 3.6

    private fun started(sim: RideSimulator, config: RideConfig = RideConfig()): RideEngine =
        RideEngine(config).apply { start(sim.monoMs, sim.timeMs) }

    @Test
    fun `une heure a 25 km h donne 25 km`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 3600, speedMps = kmh(25.0))
        val recorded = engine.feed(sim.fixes)
        val totals = engine.totals()

        assertEquals(25_000.0, sim.trueDistanceM, 1e-6)
        // Le premier point sert d'origine : il manque au plus un pas d'une seconde.
        assertEquals(sim.trueDistanceM, totals.distanceM, 10.0)
        assertEquals(3_599_000L, totals.movingMs)
        assertEquals(25.0, totals.avgSpeedMps * 3.6, 0.05)
        assertEquals(25.0, totals.maxSpeedMps * 3.6, 0.05)
        assertEquals(3600, recorded.size)
        assertEquals(RideState.RECORDING, engine.state)
    }

    @Test
    fun `distance exacte a 0,1 pour cent pres sur un parcours sinueux`() {
        val sim = RideSimulator(lat = 48.1, lon = -1.7)
        val engine = started(sim)
        var bearing = 10.0
        repeat(120) {
            sim.ride(seconds = 15, speedMps = kmh(22.0), bearingDeg = bearing)
            bearing = (bearing + 37.0) % 360.0
        }
        engine.feed(sim.fixes)
        val error = abs(engine.totals().distanceM - sim.trueDistanceM) / sim.trueDistanceM
        assertTrue("erreur de distance $error", error < 0.001)
    }

    @Test
    fun `sans vitesse Doppler la vitesse est deduite des positions`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 600, speedMps = kmh(30.0), withSpeed = false)
        engine.feed(sim.fixes)
        assertEquals(30.0, engine.snapshot().speedMps!! * 3.6, 0.1)
        assertEquals(30.0, engine.totals().avgSpeedMps * 3.6, 0.1)
    }

    @Test
    fun `pause automatique a l'arret puis reprise`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 120, speedMps = kmh(20.0))
        engine.feed(sim.fixes)
        assertEquals(RideState.RECORDING, engine.state)
        val before = sim.fixes.size

        sim.stand(seconds = 60)
        engine.feed(sim.fixes.subList(before, sim.fixes.size))
        assertEquals(RideState.AUTO_PAUSED, engine.state)
        assertEquals(0.0, engine.snapshot().speedMps!!, 0.0)
        val distanceAtStop = engine.totals().distanceM
        val afterStop = sim.fixes.size

        sim.ride(seconds = 120, speedMps = kmh(20.0))
        engine.feed(sim.fixes.subList(afterStop, sim.fixes.size))
        assertEquals(RideState.RECORDING, engine.state)

        val totals = engine.totals()
        // 240 s de roulage ; l'arrêt d'une minute ne compte pas (tolérance : les quelques secondes de transition).
        assertEquals(240_000.0, totals.movingMs.toDouble(), 6_000.0)
        assertEquals(300_000L, totals.elapsedMs)
        assertEquals(sim.trueDistanceM, totals.distanceM, sim.trueDistanceM * 0.01)
        // Le flottement du GPS à l'arrêt n'a pas ajouté de distance.
        assertTrue(distanceAtStop <= 120 * kmh(20.0) + 6.0)
        assertEquals(20.0, totals.avgSpeedMps * 3.6, 0.5)
    }

    @Test
    fun `a l'arret dix minutes la distance ne derive pas`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 30, speedMps = kmh(18.0))
        sim.stand(seconds = 600, jitterM = 3.0)
        engine.feed(sim.fixes)
        assertTrue(engine.totals().distanceM < 30 * kmh(18.0) + 15.0)
        assertTrue(engine.totals().movingMs < 40_000)
    }

    @Test
    fun `sans pause automatique le chrono continue mais pas la distance`() {
        val sim = RideSimulator()
        val engine = started(sim, RideConfig(autoPause = false))
        sim.ride(seconds = 60, speedMps = kmh(20.0))
        sim.stand(seconds = 120, jitterM = 3.0)
        sim.ride(seconds = 60, speedMps = kmh(20.0))
        engine.feed(sim.fixes)
        val totals = engine.totals()
        assertEquals(RideState.RECORDING, engine.state)
        assertEquals(240_000.0, totals.movingMs.toDouble(), 3_000.0)
        assertEquals(sim.trueDistanceM, totals.distanceM, sim.trueDistanceM * 0.02)
    }

    @Test
    fun `pause manuelle - le trajet fait en pause ne compte pas`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 100, speedMps = 5.0)
        engine.feed(sim.fixes)
        val distanceBefore = engine.totals().distanceM
        engine.pause(sim.monoMs)
        assertEquals(RideState.PAUSED, engine.state)

        // 500 m parcourus pendant la pause (dans le train, par exemple).
        val start = sim.fixes.size
        sim.ride(seconds = 50, speedMps = 10.0)
        val whilePaused = engine.feed(sim.fixes.subList(start, sim.fixes.size))
        assertTrue(whilePaused.isEmpty())
        assertEquals(distanceBefore, engine.totals().distanceM, 1e-9)

        engine.resume(sim.monoMs)
        val start2 = sim.fixes.size
        sim.ride(seconds = 100, speedMps = 5.0)
        val after = engine.feed(sim.fixes.subList(start2, sim.fixes.size))

        val totals = engine.totals()
        assertEquals(1_000.0, totals.distanceM, 12.0)
        assertEquals(198_000.0, totals.movingMs.toDouble(), 2_000.0)
        assertEquals(250_000L, totals.elapsedMs)
        // Nouveau tronçon après la pause : la trace n'est pas reliée par un trait.
        assertEquals(1, after.first().segment)
        assertEquals(1, engine.currentSegment)
    }

    @Test
    fun `les points trop imprecis sont ignores`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 60, speedMps = 6.0, accuracyM = 80.0)
        val recorded = engine.feed(sim.fixes)
        assertTrue(recorded.isEmpty())
        assertEquals(0.0, engine.totals().distanceM, 0.0)
        assertFalse(engine.snapshot().hasSignal)
    }

    @Test
    fun `un rebond du signal ne fausse ni la distance ni la vitesse max`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 300, speedMps = 7.0)
        // Un point parasite à 400 m, vitesse annoncée normale.
        val glitchIndex = 150
        val g = sim.fixes[glitchIndex]
        sim.fixes[glitchIndex] = g.copy(lat = g.lat + 0.0036)
        engine.feed(sim.fixes)
        val totals = engine.totals()
        assertEquals(sim.trueDistanceM, totals.distanceM, sim.trueDistanceM * 0.01)
        assertEquals(7.0, totals.maxSpeedMps, 0.2)
    }

    @Test
    fun `tunnel - la distance et le temps du trou de signal sont comptes`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 60, speedMps = 8.0)
        sim.ride(seconds = 40, speedMps = 8.0, emit = false)
        sim.ride(seconds = 60, speedMps = 8.0)
        engine.feed(sim.fixes)
        val totals = engine.totals()
        assertEquals(sim.trueDistanceM, totals.distanceM, 10.0)
        assertEquals(159_000.0, totals.movingMs.toDouble(), 1_500.0)
    }

    @Test
    fun `perte de signal affichee apres quelques secondes`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 30, speedMps = 6.0)
        engine.feed(sim.fixes)
        assertTrue(engine.snapshot().hasSignal)
        assertNotNull(engine.snapshot().speedMps)
        engine.onTick(sim.monoMs + 10_000)
        assertFalse(engine.snapshot().hasSignal)
        assertNull(engine.snapshot().speedMps)
        // Le temps écoulé continue de courir.
        assertEquals(40_000L, engine.totals().elapsedMs)
    }

    @Test
    fun `vitesse max sur une descente`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 120, speedMps = kmh(25.0))
        sim.ride(seconds = 30, speedMps = kmh(62.0))
        sim.ride(seconds = 120, speedMps = kmh(25.0))
        engine.feed(sim.fixes)
        assertEquals(62.0, engine.totals().maxSpeedMps * 3.6, 0.3)
    }

    @Test
    fun `arret puis sauvegarde - reprise apres une interruption`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 600, speedMps = 6.0, slopePct = { 3.0 })
        engine.feed(sim.fixes)
        val saved = engine.totals()

        // L'appli est tuée 5 minutes plus tard puis relancée : on repart des totaux sauvegardés.
        val later = sim.monoMs + 300_000
        val restored = RideEngine()
        restored.restore(startTimeMs = sim.timeMs - 600_000, totals = saved.copy(elapsedMs = saved.elapsedMs + 300_000), nextSegment = 1, nowMonoMs = later)
        assertEquals(RideState.RECORDING, restored.state)
        assertEquals(saved.distanceM, restored.totals().distanceM, 1e-9)
        assertEquals(saved.ascentM, restored.totals().ascentM, 1e-9)
        restored.onTick(later + 10_000)
        assertEquals(saved.elapsedMs + 310_000, restored.totals().elapsedMs)
        assertEquals(1, restored.currentSegment)
    }

    @Test
    fun `stop renvoie les totaux et remet le moteur au repos`() {
        val sim = RideSimulator()
        val engine = started(sim)
        sim.ride(seconds = 100, speedMps = 5.0)
        engine.feed(sim.fixes)
        val totals = engine.stop(sim.monoMs)
        assertEquals(RideState.IDLE, engine.state)
        assertEquals(495.0, totals.distanceM, 6.0)
        assertEquals(100_000L, totals.elapsedMs)
        assertNull(engine.onFix(sim.fixes.last().copy(monoMs = sim.monoMs + 1000)))
        assertEquals(100_000L, engine.totals().elapsedMs)
    }

    @Test
    fun `les points hors sortie sont ignores`() {
        val engine = RideEngine()
        assertNull(engine.onFix(Fix(0, 0, 45.0, 5.0)))
        assertEquals(RideState.IDLE, engine.snapshot().state)
        assertFalse(engine.snapshot().isActive)
    }
}
