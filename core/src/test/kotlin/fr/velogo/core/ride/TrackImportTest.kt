package fr.velogo.core.ride

import fr.velogo.core.geo.Geo
import fr.velogo.core.gpx.Gpx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackImportTest {
    private val t0 = 1_800_000_000_000L

    /** Trace rectiligne vers le nord : une position toutes les [stepS] secondes à [speedMps]. */
    private fun track(count: Int, speedMps: Double = 6.0, stepS: Int = 2, segment: Int = 0, startMs: Long = t0, startLat: Double = 45.0): List<TrackPoint> {
        var lat = startLat
        return (0 until count).map { i ->
            if (i > 0) lat = Geo.offset(lat, 5.0, 0.0, speedMps * stepS).lat
            TrackPoint(timeMs = startMs + i * stepS * 1000L, lat = lat, lon = 5.0, altitudeM = 200.0 + i, segment = segment)
        }
    }

    @Test
    fun `la vitesse est recalculee a chaque point`() {
        val prepared = TrackImport.prepare(track(200, speedMps = 6.0))!!
        assertEquals(200, prepared.size)
        for (p in prepared) assertEquals(6.0, p.speedMps!!, 0.05)
        // Les autres valeurs ne sont pas touchées.
        assertEquals(200.0, prepared.first().altitudeM!!, 0.0)
        assertEquals(t0, prepared.first().timeMs)
    }

    @Test
    fun `une pause ne fausse pas la vitesse`() {
        // Deux tronçons séparés par vingt minutes d'arrêt (pause manuelle), le second plus rapide.
        val first = track(100, speedMps = 5.0)
        val second = track(100, speedMps = 9.0, segment = 1, startMs = first.last().timeMs + 1_200_000, startLat = first.last().lat)
        val prepared = TrackImport.prepare(first + second)!!
        assertEquals(5.0, prepared[98].speedMps!!, 0.05)
        assertEquals(9.0, prepared[101].speedMps!!, 0.05)
        assertEquals(9.0, prepared[150].speedMps!!, 0.05)
    }

    @Test
    fun `une trace sans heures ou trop courte n'est pas une sortie`() {
        // Un itinéraire (pas d'heures), trois points, une trace qui ne bouge pas.
        assertNull(TrackImport.prepare(track(200).map { it.copy(timeMs = 0) }))
        assertNull(TrackImport.prepare(track(3)))
        assertNull(TrackImport.prepare(track(50, speedMps = 0.0)))
        assertNull(TrackImport.prepare(emptyList()))
    }

    @Test
    fun `les points mal dates sont ecartes`() {
        val points = track(60).toMutableList()
        points[30] = points[30].copy(timeMs = t0 - 5_000)   // horloge qui recule
        points[40] = points[40].copy(timeMs = 0)            // point sans heure
        val prepared = TrackImport.prepare(points)!!
        assertEquals(58, prepared.size)
        for (i in 1 until prepared.size) assertTrue(prepared[i].timeMs >= prepared[i - 1].timeMs)
    }

    @Test
    fun `une sortie exportee puis reimportee garde ses totaux`() {
        val first = track(600, speedMps = 6.0)
        val second = track(400, speedMps = 7.5, segment = 1, startMs = first.last().timeMs + 300_000, startLat = first.last().lat)
        val original = first + second
        val gpx = StringBuilder().also { Gpx.write(it, "Sortie <test> & co", original, source = "IGN") }.toString()
        val read = Gpx.read(gpx.byteInputStream()).tracks.single()
        assertEquals("Sortie <test> & co", read.name)
        assertEquals("IGN", read.source)
        val prepared = TrackImport.prepare(read.points)
        assertNotNull(prepared)
        val before = RideAnalyzer.summarize(original)
        val after = RideAnalyzer.summarize(prepared!!)
        assertEquals(before.distanceM, after.distanceM, 0.5)
        assertEquals(before.movingMs, after.movingMs)
        assertEquals(before.elapsedMs, after.elapsedMs)
        assertEquals(before.ascentM, after.ascentM, 1.0)
        // Les deux tronçons (la pause) sont conservés.
        assertEquals(setOf(0, 1), prepared.map { it.segment }.toSet())
        assertEquals(TrackImport.distanceM(original), TrackImport.distanceM(prepared), 0.5)

        // Un fichier d'une autre appli n'a pas de balise <src>.
        val foreign = StringBuilder().also { Gpx.write(it, "Autre", original) }.toString()
        assertNull(Gpx.read(foreign.byteInputStream()).tracks.single().source)
    }
}
