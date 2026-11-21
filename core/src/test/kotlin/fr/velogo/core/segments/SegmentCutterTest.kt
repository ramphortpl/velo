package fr.velogo.core.segments

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCutterTest {
    private val t0 = 1_800_000_000_000L
    private val origin = LatLon(42.3, 9.15)

    /**
     * Une sortie en ligne droite vers le nord-est : un point tous les [stepM] mètres, un par seconde, sur une
     * pente régulière de [slopePct] % (ou sans altitude du tout).
     */
    private fun straight(lengthM: Double, stepM: Double = 6.5, slopePct: Double? = 5.0, baseAltitudeM: Double = 400.0): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var p = origin
        var done = 0.0
        var index = 0
        while (true) {
            out.add(TrackPoint(timeMs = t0 + index * 1000L, lat = p.lat, lon = p.lon, altitudeM = slopePct?.let { baseAltitudeM + done * it / 100.0 }))
            if (done >= lengthM) break
            p = Geo.offset(p.lat, p.lon, 40.0, stepM)
            done += stepM
            index++
        }
        return out
    }

    private fun position(point: TrackPoint) = LatLon(point.lat, point.lon)

    @Test
    fun `les deux bouts tombent aux distances demandees`() {
        val track = straight(900.0)
        val cutter = SegmentCutter(track, AltitudeSource.TERRAIN)
        val distances = RideAnalyzer.cumulativeDistances(track)

        val measures = cutter.measure(310.0, 620.0)!!
        val line = cutter.line(310.0, 620.0)

        assertEquals(310.0, measures.lengthM, 1e-9)
        assertEquals(310.0, Geo.lengthM(line), 0.01)
        // Le début est à 310 m du départ, entre deux points de la trace ; la fin à 620 m.
        assertEquals(310.0, Geo.distanceM(position(track.first()), line.first()), 0.01)
        assertEquals(620.0, Geo.distanceM(position(track.first()), line.last()), 0.01)
        // Entre les deux : les points de la trace, tous, dans l'ordre.
        val inside = track.indices.filter { distances[it] > 310.0 && distances[it] < 620.0 }.map { position(track[it]) }
        assertEquals(inside, line.subList(1, line.size - 1))
    }

    @Test
    fun `altitude, denivele et pente sont ceux du profil de la sortie`() {
        val track = straight(900.0, slopePct = 5.0)
        val measures = SegmentCutter(track, AltitudeSource.TERRAIN).measure(310.0, 620.0)!!

        assertEquals(400.0 + 310.0 * 0.05, measures.startAltitudeM!!, 0.05)
        assertEquals(400.0 + 620.0 * 0.05, measures.endAltitudeM!!, 0.05)
        assertEquals(15.5, measures.ascentM, 0.1)
        assertEquals(5.0, measures.gradePct!!, 0.02)
    }

    @Test
    fun `une descente ne compte aucun denivele positif`() {
        val track = straight(900.0, slopePct = -4.0)
        val measures = SegmentCutter(track, AltitudeSource.TERRAIN).measure(200.0, 700.0)!!

        assertEquals(0.0, measures.ascentM, 1e-9)
        assertEquals(-4.0, measures.gradePct!!, 0.02)
    }

    @Test
    fun `la sortie entiere, et les distances hors de la sortie`() {
        val track = straight(500.0)
        val cutter = SegmentCutter(track, AltitudeSource.TERRAIN)
        val total = cutter.totalM
        assertEquals(RideAnalyzer.cumulativeDistances(track).last(), total, 1e-9)

        val whole = cutter.line(0.0, total)
        assertEquals(track.map(::position), whole)
        assertEquals(total, cutter.measure(0.0, total)!!.lengthM, 1e-9)

        // Des distances qui débordent sont ramenées dans la sortie.
        assertEquals(whole, cutter.line(-50.0, total + 1_000.0))
        assertEquals(total, cutter.measure(-50.0, total + 1_000.0)!!.lengthM, 1e-9)
        assertEquals(total - 120.0, cutter.measure(120.0, 1e9)!!.lengthM, 1e-9)
    }

    @Test
    fun `rien entre deux distances identiques ou inversees`() {
        val cutter = SegmentCutter(straight(500.0), AltitudeSource.TERRAIN)

        assertNull(cutter.measure(200.0, 200.0))
        assertNull(cutter.measure(300.0, 100.0))
        assertNull(cutter.measure(Double.NaN, 100.0))
        assertTrue(cutter.line(200.0, 200.0).isEmpty())
        assertTrue(cutter.line(300.0, 100.0).isEmpty())
        // Au-delà de la fin de la sortie, il ne reste rien.
        assertNull(cutter.measure(900.0, 1_200.0))
    }

    @Test
    fun `trop peu de points`() {
        assertNull(SegmentCutter(emptyList(), AltitudeSource.GNSS).measure(0.0, 100.0))
        assertNull(SegmentCutter(straight(0.0), AltitudeSource.GNSS).measure(0.0, 100.0))
        assertTrue(SegmentCutter(straight(0.0), AltitudeSource.GNSS).line(0.0, 100.0).isEmpty())
        assertEquals(0.0, SegmentCutter(emptyList(), AltitudeSource.GNSS).totalM, 0.0)
    }

    @Test
    fun `les deux bouts entre les deux memes points`() {
        // Un point tous les 50 m : le morceau de 110 à 130 m tient entre le 3e et le 4e.
        val track = straight(400.0, stepM = 50.0)
        val cutter = SegmentCutter(track, AltitudeSource.TERRAIN)

        val line = cutter.line(110.0, 130.0)
        assertEquals(2, line.size)
        assertEquals(20.0, Geo.lengthM(line), 0.01)
        assertEquals(110.0, Geo.distanceM(position(track.first()), line.first()), 0.01)
        assertEquals(20.0, cutter.measure(110.0, 130.0)!!.lengthM, 1e-9)
    }

    @Test
    fun `un bout pile sur un point de la trace`() {
        val track = straight(400.0, stepM = 50.0)
        val distances = RideAnalyzer.cumulativeDistances(track)
        val cutter = SegmentCutter(track, AltitudeSource.TERRAIN)

        val line = cutter.line(distances[2], distances[5])
        assertEquals(track.subList(2, 6).map(::position), line)
    }

    @Test
    fun `sortie sans altitude`() {
        val measures = SegmentCutter(straight(600.0, slopePct = null), AltitudeSource.GNSS).measure(100.0, 500.0)!!

        assertEquals(400.0, measures.lengthM, 1e-9)
        assertEquals(0.0, measures.ascentM, 0.0)
        assertNull(measures.startAltitudeM)
        assertNull(measures.endAltitudeM)
        assertNull(measures.gradePct)
    }

    @Test
    fun `altitude absente au debut du morceau`() {
        // Les 40 premiers points n'ont pas d'altitude (le GPS n'en donnait pas encore).
        val track = straight(900.0).mapIndexed { i, p -> if (i < 40) p.copy(altitudeM = null) else p }
        val distances = RideAnalyzer.cumulativeDistances(track)
        val measures = SegmentCutter(track, AltitudeSource.TERRAIN).measure(100.0, 700.0)!!

        // La première altitude connue du morceau sert de départ.
        assertEquals(400.0 + distances[40] * 0.05, measures.startAltitudeM!!, 1.0)
        assertEquals(400.0 + 700.0 * 0.05, measures.endAltitudeM!!, 0.05)
    }

    @Test
    fun `une pause avec deplacement ne compte pas dans les distances`() {
        // Deux tronçons : le second commence 300 m plus loin (pause, vélo porté). Ce saut ne fait pas de distance.
        val first = straight(300.0, stepM = 10.0)
        val jump = Geo.offset(first.last().lat, first.last().lon, 40.0, 300.0)
        val second = (0..30).map { i ->
            val p = Geo.offset(jump.lat, jump.lon, 40.0, i * 10.0)
            TrackPoint(timeMs = first.last().timeMs + 600_000L + i * 1000L, lat = p.lat, lon = p.lon, altitudeM = 430.0, segment = 1)
        }
        val track = first + second
        val cutter = SegmentCutter(track, AltitudeSource.TERRAIN)
        val beforePause = RideAnalyzer.cumulativeDistances(first).last()
        val afterPause = RideAnalyzer.cumulativeDistances(second).last()
        assertEquals(beforePause + afterPause, cutter.totalM, 1e-6)

        // Un morceau pris après la pause est tout entier sur le second tronçon.
        val line = cutter.line(beforePause + 50.0, beforePause + 250.0)
        assertEquals(200.0, Geo.lengthM(line), 0.01)
        assertEquals(50.0, Geo.distanceM(jump, line.first()), 0.01)
        // Un morceau qui commence pile à la pause part du second tronçon, pas de la fin du premier.
        assertEquals(jump, cutter.line(beforePause, beforePause + 200.0).first())
    }

    @Test
    fun `la sortie est reconnue sur le morceau qu'on en tire`() {
        val track = straight(1_500.0)
        val cutter = SegmentCutter(track, AltitudeSource.TERRAIN)

        val passes = SegmentMatcher.passes(track, cutter.line(310.0, 1_120.0))

        assertEquals(1, passes.size)
        // 810 m à 6,5 m/s : un peu plus de deux minutes, à une seconde près (le pas de la trace).
        assertEquals(810.0 / 6.5 * 1000.0, passes.single().elapsedMs.toDouble(), 1_100.0)
        assertNotNull(cutter.measure(310.0, 1_120.0))
    }
}
