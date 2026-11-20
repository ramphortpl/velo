package fr.velogo.core.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class AltitudeTest {

    private fun kmh(v: Double) = v / 3.6

    // --- Cumul de dénivelé -------------------------------------------------------------------------------

    @Test
    fun `montee reguliere comptee entierement`() {
        val acc = AscentAccumulator(thresholdM = 3.0)
        for (i in 0..1000) acc.add(200.0 + i * 0.5)
        assertEquals(500.0, acc.ascentM, 1e-9)
        assertEquals(0.0, acc.descentM, 1e-9)
    }

    @Test
    fun `les petites oscillations sous le seuil ne comptent pas`() {
        val acc = AscentAccumulator(thresholdM = 3.0)
        repeat(500) { acc.add(100.0 + if (it % 2 == 0) 1.2 else -1.2) }
        assertEquals(0.0, acc.ascentM, 1e-9)
        assertEquals(0.0, acc.descentM, 1e-9)
    }

    @Test
    fun `col aller-retour`() {
        val acc = AscentAccumulator(thresholdM = 2.0)
        for (i in 0..800) acc.add(500.0 + i)          // +800
        for (i in 800 downTo 300) acc.add(500.0 + i)  // -500
        for (i in 300..450) acc.add(500.0 + i)        // +150
        assertEquals(950.0, acc.ascentM, 1e-9)
        assertEquals(500.0, acc.descentM, 1e-9)
    }

    @Test
    fun `reprise des cumuls`() {
        val acc = AscentAccumulator(thresholdM = 2.0)
        acc.restore(120.0, 80.0)
        for (i in 0..10) acc.add(300.0 + i)
        assertEquals(130.0, acc.ascentM, 1e-9)
        assertEquals(80.0, acc.descentM, 1e-9)
    }

    // --- Pente ---------------------------------------------------------------------------------------------

    @Test
    fun `pente constante de 6 pour cent`() {
        val slope = SlopeEstimator(windowM = 40.0)
        var result: Double? = null
        for (i in 0..200) result = slope.add(i * 5.0, 100.0 + i * 5.0 * 0.06)
        assertEquals(6.0, result!!, 0.01)
    }

    @Test
    fun `pas de pente tant que la distance est insuffisante`() {
        val slope = SlopeEstimator(windowM = 80.0)
        assertNull(slope.add(0.0, 100.0))
        assertNull(slope.add(5.0, 100.2))
        assertNull(slope.add(10.0, 100.4))
    }

    @Test
    fun `changement de pente suivi en quelques dizaines de metres`() {
        val slope = SlopeEstimator(windowM = 40.0)
        var alt = 100.0
        var result: Double? = null
        for (i in 1..100) { alt += 5.0 * 0.02; result = slope.add(i * 5.0, alt) }
        assertEquals(2.0, result!!, 0.05)
        for (i in 101..130) { alt += 5.0 * 0.09; result = slope.add(i * 5.0, alt) }
        // 150 m après le changement, la nouvelle pente est affichée.
        assertEquals(9.0, result!!, 0.3)
    }

    @Test
    fun `descente negative`() {
        val slope = SlopeEstimator(windowM = 40.0)
        var result: Double? = null
        for (i in 0..100) result = slope.add(i * 8.0, 900.0 - i * 8.0 * 0.075)
        assertEquals(-7.5, result!!, 0.01)
    }

    // --- Filtre d'altitude ---------------------------------------------------------------------------------

    @Test
    fun `le filtre reduit nettement le bruit du GPS`() {
        val filter = AltitudeFilter(AltitudeSource.GNSS.accelNoise)
        val random = java.util.Random(7)
        var sumSq = 0.0
        var n = 0
        for (t in 0 until 1200) {
            val truth = 400.0 + t * 0.3 // montée régulière de 0,3 m/s
            val out = filter.update(t * 1000L, truth + random.nextGaussian() * 5.0, 5.0)
            if (t > 120) { sumSq += (out - truth) * (out - truth); n++ }
        }
        val rms = sqrt(sumSq / n)
        assertTrue("écart résiduel $rms m", rms < 1.6)
    }

    @Test
    fun `une mesure aberrante isolee est ignoree`() {
        val filter = AltitudeFilter(AltitudeSource.GNSS.accelNoise)
        for (t in 0 until 120) filter.update(t * 1000L, 250.0, 5.0)
        val out = filter.update(120_000L, 400.0, 5.0)
        assertEquals(250.0, out, 1.0)
    }

    @Test
    fun `apres un long trou le filtre repart de la mesure`() {
        val filter = AltitudeFilter(AltitudeSource.GNSS.accelNoise)
        for (t in 0 until 60) filter.update(t * 1000L, 250.0, 5.0)
        val out = filter.update(300_000L, 410.0, 5.0)
        assertEquals(410.0, out, 1e-9)
    }

    // --- Moteur complet : pente et dénivelé sur des sorties simulées -----------------------------------------

    @Test
    fun `montee a 5 pour cent - pente et denivele avec une altitude propre`() {
        val sim = RideSimulator(altitudeM = 600.0)
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.TERRAIN)).apply { start(sim.monoMs, sim.timeMs) }
        sim.ride(seconds = 600, speedMps = kmh(15.0), slopePct = { 5.0 })
        engine.feed(sim.fixes)
        assertEquals(125.0, sim.trueAscentM, 0.01)
        assertEquals(5.0, engine.snapshot().slopePct!!, 0.2)
        assertEquals(sim.trueAscentM, engine.totals().ascentM, sim.trueAscentM * 0.03)
        assertEquals(725.0, engine.snapshot().altitudeM!!, 1.5)
        assertEquals(600.0, engine.totals().minAltitudeM!!, 2.0)
    }

    @Test
    fun `parcours vallonne - denivele a 3 pour cent pres avec le relief`() {
        val sim = RideSimulator(altitudeM = 400.0)
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.TERRAIN)).apply { start(sim.monoMs, sim.timeMs) }
        // Bosses de 100 m tous les 5 km sur 30 km : 600 m de dénivelé positif.
        val speed = kmh(24.0)
        sim.ride(seconds = (30_000 / speed).toInt(), speedMps = speed, slopePct = { d -> 100.0 * 50.0 * (2 * PI / 5000.0) * kotlin.math.sin(2 * PI * d / 5000.0) })
        engine.feed(sim.fixes)
        val error = abs(engine.totals().ascentM - sim.trueAscentM) / sim.trueAscentM
        assertTrue("dénivelé ${engine.totals().ascentM} m pour ${sim.trueAscentM} m", error < 0.03)
        assertEquals(engine.totals().ascentM, engine.totals().descentM, 12.0)
    }

    @Test
    fun `GPS seul sur un parcours vallonne - denivele a 10 pour cent pres malgre le bruit`() {
        val sim = RideSimulator(altitudeM = 400.0, seed = 3)
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.GNSS)).apply { start(sim.monoMs, sim.timeMs) }
        val speed = kmh(24.0)
        sim.ride(
            seconds = (30_000 / speed).toInt(), speedMps = speed,
            slopePct = { d -> 100.0 * 50.0 * (2 * PI / 5000.0) * kotlin.math.sin(2 * PI * d / 5000.0) },
            altitudeNoiseM = 2.5, altitudeDriftM = 2.0,
        )
        engine.feed(sim.fixes)
        val error = abs(engine.totals().ascentM - sim.trueAscentM) / sim.trueAscentM
        assertTrue("dénivelé ${engine.totals().ascentM} m pour ${sim.trueAscentM} m", error < 0.10)
    }

    @Test
    fun `GPS seul sur du plat - le bruit ne cree presque pas de denivele`() {
        val sim = RideSimulator(altitudeM = 50.0, seed = 11)
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.GNSS)).apply { start(sim.monoMs, sim.timeMs) }
        sim.ride(seconds = 3600, speedMps = kmh(27.0), altitudeNoiseM = 2.5, altitudeDriftM = 2.0)
        engine.feed(sim.fixes)
        // 27 km de plat : moins de 40 m de faux dénivelé en une heure.
        assertTrue("faux dénivelé ${engine.totals().ascentM} m", engine.totals().ascentM < 40.0)
    }

    @Test
    fun `GPS seul - la pente affichee reste stable sur une pente constante`() {
        val sim = RideSimulator(altitudeM = 300.0, seed = 5)
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.GNSS)).apply { start(sim.monoMs, sim.timeMs) }
        sim.ride(seconds = 900, speedMps = kmh(14.0), slopePct = { 7.0 }, altitudeNoiseM = 2.5)
        val slopes = ArrayList<Double>()
        for ((i, f) in sim.fixes.withIndex()) {
            engine.onFix(f); engine.onTick(f.monoMs)
            if (i > 120) engine.snapshot().slopePct?.let { slopes.add(it) }
        }
        val mean = slopes.average()
        val std = sqrt(slopes.sumOf { (it - mean) * (it - mean) } / slopes.size)
        assertEquals(7.0, mean, 0.5)
        assertTrue("écart-type de la pente $std %", std < 1.0)
    }

    @Test
    fun `pente masquee a l'arret et en pause`() {
        val sim = RideSimulator()
        val engine = RideEngine(RideConfig(altitudeSource = AltitudeSource.TERRAIN)).apply { start(sim.monoMs, sim.timeMs) }
        sim.ride(seconds = 120, speedMps = 5.0, slopePct = { 4.0 })
        engine.feed(sim.fixes)
        assertEquals(4.0, engine.snapshot().slopePct!!, 0.3)
        val start = sim.fixes.size
        sim.stand(seconds = 30)
        engine.feed(sim.fixes.subList(start, sim.fixes.size))
        assertNull(engine.snapshot().slopePct)
    }

    @Test
    fun `changer de source d'altitude conserve le denivele deja acquis`() {
        val sim = RideSimulator()
        val engine = RideEngine().apply { start(sim.monoMs, sim.timeMs) }
        sim.ride(seconds = 400, speedMps = 5.0, slopePct = { 5.0 })
        engine.feed(sim.fixes)
        val before = engine.totals().ascentM
        assertTrue(before > 80.0)
        engine.setAltitudeSource(AltitudeSource.TERRAIN)
        assertEquals(before, engine.totals().ascentM, 1e-9)
        assertEquals(AltitudeSource.TERRAIN, engine.config.altitudeSource)
    }
}
