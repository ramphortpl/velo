package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTest {

    /** 500 m vers l'est, puis 400 m vers le nord ; un point tous les 20 m. */
    private fun route(): Route {
        val points = ArrayList<LatLon>()
        var p = LatLon(45.0, 5.0)
        points.add(p)
        fun leg(bearing: Double, meters: Int) {
            repeat(meters / 20) {
                p = Geo.offset(p.lat, p.lon, bearing, 20.0)
                points.add(p)
            }
        }
        leg(90.0, 500); leg(0.0, 400)
        val distances = Route.cumulativeDistances(points)
        return Route(
            points, null, distances, durationS = 180.0, ascentM = 0.0, descentM = 0.0,
            instructions = listOf(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)),
            waypoints = listOf(points.first(), points.last()),
        )
    }

    @Test
    fun `position a une distance du depart`() {
        val route = route()
        assertEquals(route.points.first(), route.positionAt(0.0))
        assertEquals(route.points.first(), route.positionAt(-30.0))
        assertEquals(route.points.last(), route.positionAt(route.distanceM))
        assertEquals(route.points.last(), route.positionAt(route.distanceM + 500.0))

        // Entre deux points du tracé : à 130 m du départ, sur la première ligne droite.
        val p = route.positionAt(130.0)
        assertEquals(130.0, Geo.distanceM(route.points.first(), p), 0.05)
        assertEquals(0.0, route.distanceTo(p), 0.05)
        // Pile sur un point du tracé.
        assertEquals(route.points[7], route.positionAt(route.distances[7]))
    }

    @Test
    fun `direction de l'itineraire`() {
        val route = route()
        val corner = route.distances[25]
        assertEquals(90.0, route.bearingAt(0.0), 0.5)
        assertEquals(90.0, route.bearingAt(200.0), 0.5)
        // Juste après le virage, puis jusqu'à l'arrivée : plein nord (0° ou tout près de 360°).
        fun north(bearing: Double) = minOf(bearing, 360.0 - bearing)
        assertEquals(0.0, north(route.bearingAt(corner + 5.0)), 0.5)
        assertEquals(0.0, north(route.bearingAt(route.distanceM)), 0.5)
        assertEquals(0.0, north(route.bearingAt(route.distanceM + 100.0)), 0.5)
        // Avant le départ : la direction du départ.
        assertEquals(90.0, route.bearingAt(-50.0), 0.5)
    }

    @Test
    fun `direction d'un itineraire plus court que le recul`() {
        val a = LatLon(45.0, 5.0)
        val b = Geo.offset(a.lat, a.lon, 135.0, 6.0)
        val route = Route(
            listOf(a, b), null, Route.cumulativeDistances(listOf(a, b)), 2.0, 0.0, 0.0,
            listOf(Instruction(1, 6.0, Maneuver.ARRIVE)), listOf(a, b),
        )
        assertEquals(135.0, route.bearingAt(0.0), 0.5)
        assertEquals(135.0, route.bearingAt(3.0), 0.5)
    }
}
