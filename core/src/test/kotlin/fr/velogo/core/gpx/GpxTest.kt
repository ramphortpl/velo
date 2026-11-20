package fr.velogo.core.gpx

import fr.velogo.core.ride.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {

    private fun points(): List<TrackPoint> = listOf(
        TrackPoint(1_800_000_000_000, 45.1234567, 5.7654321, 312.4, 6.5, 4.0, 0),
        TrackPoint(1_800_000_001_000, 45.1235000, 5.7655000, 312.9, 6.6, 4.0, 0),
        TrackPoint(1_800_000_060_000, 45.1300000, 5.7700000, null, 6.0, 5.0, 1),
        TrackPoint(1_800_000_061_500, 45.1300500, 5.7700500, 320.0, 6.1, 5.0, 1),
    )

    @Test
    fun `ecriture puis relecture a l'identique`() {
        val sb = StringBuilder()
        Gpx.write(sb, "Sortie du matin", points())
        val file = Gpx.read(sb.toString().byteInputStream())

        assertEquals("Sortie du matin", file.name)
        assertEquals(1, file.tracks.size)
        val read = file.tracks[0].points
        assertEquals(4, read.size)
        assertFalse(file.tracks[0].isRoute)
        for ((expected, actual) in points().zip(read)) {
            assertEquals(expected.lat, actual.lat, 1e-7)
            assertEquals(expected.lon, actual.lon, 1e-7)
            assertEquals(expected.timeMs, actual.timeMs)
            assertEquals(expected.segment, actual.segment)
            val expectedAltitude = expected.altitudeM
            if (expectedAltitude == null) assertNull(actual.altitudeM) else assertEquals(expectedAltitude, actual.altitudeM!!, 0.05)
        }
    }

    @Test
    fun `les pauses deviennent des troncons separes`() {
        val sb = StringBuilder()
        Gpx.write(sb, "x", points())
        assertEquals(2, Regex("<trkseg>").findAll(sb).count())
        assertEquals(2, Regex("</trkseg>").findAll(sb).count())
    }

    @Test
    fun `les caracteres speciaux du nom sont echappes`() {
        val sb = StringBuilder()
        Gpx.write(sb, "Col <d'Izoard> & \"Casse Déserte\"", points())
        assertTrue(sb.contains("Col &lt;d&apos;Izoard&gt; &amp; &quot;Casse Déserte&quot;"))
        assertEquals("Col <d'Izoard> & \"Casse Déserte\"", Gpx.read(sb.toString().toByteArray(Charsets.UTF_8).inputStream()).name)
    }

    @Test
    fun `lecture d'un GPX d'une autre appli avec itineraire et points nommes`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Autre appli" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <metadata><name>Tour du lac</name></metadata>
              <wpt lat="45.899" lon="6.129"><ele>447</ele><name>Annecy</name></wpt>
              <rte><name>Itinéraire</name>
                <rtept lat="45.90" lon="6.13"><ele>450.5</ele></rtept>
                <rtept lat="45.91" lon="6.14"/>
              </rte>
              <trk><name>Trace</name><trkseg>
                <trkpt lat="45.8990" lon="6.1290"><ele>447.0</ele><time>2026-07-14T08:00:00Z</time>
                  <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                </trkpt>
                <trkpt lat="45.8995" lon="6.1300"><ele>448.2</ele><time>2026-07-14T10:00:05+02:00</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val file = Gpx.read(xml.byteInputStream())
        assertEquals("Tour du lac", file.name)
        assertEquals(1, file.waypoints.size)
        assertEquals("Annecy", file.waypoints[0].name)
        assertEquals(447.0, file.waypoints[0].altitudeM!!, 1e-9)
        assertEquals(2, file.tracks.size)
        val route = file.tracks.first { it.isRoute }
        assertEquals("Itinéraire", route.name)
        assertEquals(2, route.points.size)
        assertEquals(0L, route.points[0].timeMs)
        val track = file.tracks.first { !it.isRoute }
        assertEquals("Trace", track.name)
        // 10:00:05+02:00 = 08:00:05Z : cinq secondes après le premier point.
        assertEquals(5_000L, track.points[1].timeMs - track.points[0].timeMs)
        assertEquals(4, file.allPoints.size)
    }

    @Test
    fun `fichier sans point`() {
        val file = Gpx.read("""<gpx version="1.1"><trk><trkseg></trkseg></trk></gpx>""".byteInputStream())
        assertTrue(file.tracks.isEmpty())
        assertTrue(file.allPoints.isEmpty())
    }

    @Test
    fun `itineraire exporte relisible`() {
        val sb = StringBuilder()
        val pts = points().map { it.copy(timeMs = 0) }
        Gpx.writeRoute(sb, "Vers le col", pts, listOf(GpxWaypoint(45.12, 5.76, "Départ", null)))
        val file = Gpx.read(sb.toString().byteInputStream())
        assertEquals("Vers le col", file.name)
        assertEquals(4, file.allPoints.size)
        assertEquals("Départ", file.waypoints.single().name)
        assertFalse(sb.contains("<time>"))
    }
}
