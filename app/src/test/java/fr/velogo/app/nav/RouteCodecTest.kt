package fr.velogo.app.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.WayMix
import fr.velogo.core.ride.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCodecTest {

    private fun sample(): Route {
        val points = ArrayList<LatLon>()
        var p = LatLon(42.3, 9.15)
        repeat(200) {
            points.add(p)
            p = Geo.offset(p.lat, p.lon, 30.0 + it, 35.0)
        }
        val distances = Route.cumulativeDistances(points)
        return Route(
            points = points,
            elevations = DoubleArray(points.size) { 400.0 + it * 0.75 },
            distances = distances,
            durationS = 1_830.0, ascentM = 149.0, descentM = 0.0,
            instructions = listOf(
                Instruction(40, distances[40], Maneuver.LEFT),
                Instruction(120, distances[120], Maneuver.ROUNDABOUT, 3),
                Instruction(199, distances[199], Maneuver.ARRIVE),
            ),
            waypoints = listOf(points.first(), points[100], points.last()),
            mix = WayMix(1_000.0, 2_000.0, 3_000.0, 400.0, 500.0, 650.0),
        )
    }

    @Test
    fun `un itineraire enregistre se relit a l'identique`() {
        val route = sample()
        val text = RouteCodec.encode(route)
        val back = RouteCodec.decode(text)!!

        assertEquals(route.points.size, back.points.size)
        // La trace est arrondie au mètre près.
        for (i in route.points.indices) assertTrue(Geo.distanceM(route.points[i], back.points[i]) < 1.2)
        assertEquals(route.distanceM, back.distanceM, route.distanceM * 0.001)
        for (i in route.points.indices) assertEquals(route.elevations!![i], back.elevations!![i], 0.06)
        assertEquals(route.durationS, back.durationS, 0.001)
        assertEquals(route.ascentM, back.ascentM, 0.001)
        assertEquals(route.instructions.map { Triple(it.index, it.maneuver, it.exit) }, back.instructions.map { Triple(it.index, it.maneuver, it.exit) })
        assertEquals(back.distances[120], back.instructions[1].distanceM, 0.001)
        assertEquals(3, back.waypoints.size)
        assertEquals(route.mix, back.mix)
        // Compact : moins de 12 octets par point, altitudes et consignes comprises.
        assertTrue("taille ${text.length}", text.length < route.points.size * 12)
    }

    @Test
    fun `un texte illisible ne fait pas planter`() {
        assertNull(RouteCodec.decode(""))
        assertNull(RouteCodec.decode("{\"trace\":\"\"}"))
        assertNull(RouteCodec.decode("pas du json"))
    }

    @Test
    fun `une trace GPX importee devient un itineraire a suivre`() {
        val track = (0 until 500).map { i ->
            val p = Geo.offset(45.9, 6.1, 80.0, i * 12.0)
            TrackPoint(timeMs = 0, lat = p.lat, lon = p.lon, altitudeM = 450.0 + i * 0.2)
        }
        val route = NavigationController.routeFromTrack(track + track.last())
        assertNotNull(route)
        route!!
        assertEquals(499 * 12.0, route.distanceM, 499 * 12.0 * 0.005)
        assertEquals(listOf(Maneuver.ARRIVE), route.instructions.map { it.maneuver })
        assertEquals(99.8, route.ascentM, 1.0)
        assertEquals(2, route.waypoints.size)
        // Trop court ou vide : rien à suivre.
        assertNull(NavigationController.routeFromTrack(track.take(1)))
        assertNull(NavigationController.routeFromTrack(emptyList()))
    }

    @Test
    fun `une trace tres dense est allegee sans changer sa longueur`() {
        val dense = (0 until 40_000).map { i ->
            val p = Geo.offset(45.9, 6.1, 80.0 + 20.0 * kotlin.math.sin(i / 400.0), i * 2.0)
            TrackPoint(timeMs = 0, lat = p.lat, lon = p.lon)
        }
        val route = NavigationController.routeFromTrack(dense)!!
        assertTrue("points gardés : ${route.points.size}", route.points.size <= 6_000)
        val full = Route.cumulativeDistances(dense.map { LatLon(it.lat, it.lon) }).last()
        assertEquals(full, route.distanceM, full * 0.01)
    }
}
