package fr.velogo.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les distances de référence viennent de GeographicLib (algorithme de Karney sur l'ellipsoïde WGS84),
 * une implémentation indépendante qui fait autorité.
 */
class GeoTest {

    @Test
    fun `longues distances conformes a la reference geodesique`() {
        assertEquals(391_141.1637, Geo.distanceM(48.8530, 2.3499, 45.7623, 4.8226), 0.01) // Paris -> Lyon
        assertEquals(904_003.5483, Geo.distanceM(48.3904, -4.4861, 48.5734, 7.7521), 0.01) // Brest -> Strasbourg
        assertEquals(928_608.8502, Geo.distanceM(51.0344, 2.3768, 42.6887, 2.8948), 0.01) // Dunkerque -> Perpignan
        assertEquals(104_494.7929, Geo.distanceM(41.9192, 8.7386, 42.6973, 9.4509), 0.01) // Ajaccio -> Bastia
    }

    @Test
    fun `courtes distances precises au millimetre`() {
        assertEquals(100.0186, Geo.distanceM(45.0, 5.0, 45.0009, 5.0), 0.001)
        assertEquals(100.1355, Geo.distanceM(45.0, 5.0, 45.0, 5.00127), 0.001)
        assertEquals(6.8607, Geo.distanceM(48.0, -1.5, 48.00004, -1.49993), 0.001)
        assertEquals(1024.4808, Geo.distanceM(43.6, 1.44, 43.6065, 1.4490), 0.005)
        assertEquals(4864.2108, Geo.distanceM(50.6, 3.06, 50.63, 3.11), 0.02)
    }

    @Test
    fun `distance nulle et symetrie`() {
        assertEquals(0.0, Geo.distanceM(45.0, 5.0, 45.0, 5.0), 0.0)
        val ab = Geo.distanceM(44.1, 4.2, 44.3, 4.9)
        val ba = Geo.distanceM(44.3, 4.9, 44.1, 4.2)
        assertEquals(ab, ba, 1e-6)
    }

    @Test
    fun `la formule spherique reste a moins de 0,3 pour cent`() {
        val exact = 391_141.1637
        val approx = Geo.haversineM(48.8530, 2.3499, 45.7623, 4.8226)
        assertTrue(kotlin.math.abs(approx - exact) / exact < 0.003)
    }

    @Test
    fun `caps cardinaux`() {
        assertEquals(0.0, Geo.bearingDeg(45.0, 5.0, 46.0, 5.0), 0.01)
        assertEquals(180.0, Geo.bearingDeg(46.0, 5.0, 45.0, 5.0), 0.01)
        assertEquals(90.0, Geo.bearingDeg(45.0, 5.0, 45.0, 5.001), 0.01)
        assertEquals(270.0, Geo.bearingDeg(45.0, 5.001, 45.0, 5.0), 0.01)
        // Référence géodésique : 150,53° de Paris vers Lyon.
        assertEquals(150.53, Geo.bearingDeg(48.8530, 2.3499, 45.7623, 4.8226), 0.2)
    }

    @Test
    fun `ecart angulaire signe`() {
        assertEquals(20.0, Geo.angleDeltaDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.angleDeltaDeg(10.0, 350.0), 1e-9)
        assertEquals(180.0, Geo.angleDeltaDeg(0.0, 180.0), 1e-9)
        assertEquals(-90.0, Geo.angleDeltaDeg(90.0, 0.0), 1e-9)
    }

    @Test
    fun `aller a un point puis mesurer redonne la distance`() {
        val start = LatLon(45.5, 6.0)
        for (bearing in listOf(0.0, 45.0, 135.0, 270.0)) {
            val end = Geo.offset(start.lat, start.lon, bearing, 2_000.0)
            assertEquals(2_000.0, Geo.distanceM(start, end), 8.0) // sphère contre ellipsoïde : < 0,4 %
            assertEquals(bearing, Geo.bearingDeg(start.lat, start.lon, end.lat, end.lon), 0.5)
        }
    }

    @Test
    fun `distance d'un point a un segment`() {
        val a = LatLon(45.0, 5.0)
        val b = Geo.offset(45.0, 5.0, 90.0, 1_000.0)
        val middle = Geo.offset(45.0, 5.0, 90.0, 500.0)
        val off = Geo.offset(middle.lat, middle.lon, 0.0, 30.0)
        val projection = Geo.distanceToSegmentM(off, a, b)
        assertEquals(30.0, projection.distanceM, 0.3)
        assertEquals(0.5, projection.fraction, 0.01)
        // Au-delà de l'extrémité : la distance est celle à l'extrémité.
        val beyond = Geo.offset(b.lat, b.lon, 90.0, 40.0)
        val p2 = Geo.distanceToSegmentM(beyond, a, b)
        assertEquals(40.0, p2.distanceM, 0.3)
        assertEquals(1.0, p2.fraction, 1e-9)
    }

    @Test
    fun `longueur d'une ligne brisee`() {
        val p0 = LatLon(45.0, 5.0)
        val p1 = Geo.offset(p0.lat, p0.lon, 0.0, 300.0)
        val p2 = Geo.offset(p1.lat, p1.lon, 90.0, 400.0)
        val length = Geo.lengthM(listOf(p0, p1, p2))
        assertEquals(700.0, length, 3.0)
        assertEquals(0.0, Geo.lengthM(listOf(p0)), 0.0)
    }

    @Test
    fun `la France contient ses extremites`() {
        assertTrue(Geo.FRANCE.contains(48.3904, -4.4861)) // Brest
        assertTrue(Geo.FRANCE.contains(51.0344, 2.3768)) // Dunkerque
        assertTrue(Geo.FRANCE.contains(41.3871, 9.1594)) // Bonifacio
        assertTrue(Geo.FRANCE.contains(48.5734, 7.7521)) // Strasbourg
        assertTrue(!Geo.FRANCE.contains(52.52, 13.40)) // Berlin
    }

    @Test
    fun `polyline encodee conforme a l'exemple de reference`() {
        val points = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(points))
        val decoded = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, decoded.size)
        assertEquals(40.7, decoded[1].lat, 1e-5)
        assertEquals(-126.453, decoded[2].lon, 1e-5)
    }

    @Test
    fun `polyline aller-retour sur une trace francaise`() {
        val points = (0 until 500).map { LatLon(45.0 + it * 0.00007, 5.0 - it * 0.00011) }
        val decoded = Polyline.decode(Polyline.encode(points))
        assertEquals(points.size, decoded.size)
        for (i in points.indices) {
            assertEquals(points[i].lat, decoded[i].lat, 6e-6)
            assertEquals(points[i].lon, decoded[i].lon, 6e-6)
        }
        assertEquals(emptyList<LatLon>(), Polyline.decode(""))
    }

    @Test
    fun `simplification garde la forme`() {
        // Une ligne droite de 1 km avec 101 points : il ne doit rester que les extrémités.
        val straight = (0..100).map { Geo.offset(45.0, 5.0, 90.0, it * 10.0) }
        assertEquals(2, Simplify.simplify(straight, 1.0).size)
        // Un angle droit : le coin doit être conservé.
        val corner = (0..50).map { Geo.offset(45.0, 5.0, 90.0, it * 10.0) } +
            (1..50).map { Geo.offset(Geo.offset(45.0, 5.0, 90.0, 500.0).lat, Geo.offset(45.0, 5.0, 90.0, 500.0).lon, 0.0, it * 10.0) }
        val simplified = Simplify.simplify(corner, 2.0)
        assertEquals(3, simplified.size)
        assertEquals(corner[50], simplified[1])
        // Limite du nombre de points.
        val wavy = (0..2000).map { LatLon(45.0 + 0.0002 * kotlin.math.sin(it / 7.0), 5.0 + it * 0.0001) }
        assertTrue(Simplify.toMaxPoints(wavy, 120).size <= 120)
    }

    @Test
    fun `rectangle englobant`() {
        val box = BoundingBox.of(listOf(LatLon(45.0, 5.0), LatLon(44.5, 5.5), LatLon(45.2, 4.8)))!!
        assertEquals(44.5, box.minLat, 0.0)
        assertEquals(45.2, box.maxLat, 0.0)
        assertEquals(4.8, box.minLon, 0.0)
        assertEquals(5.5, box.maxLon, 0.0)
        assertEquals(null, BoundingBox.of(emptyList()))
    }
}
