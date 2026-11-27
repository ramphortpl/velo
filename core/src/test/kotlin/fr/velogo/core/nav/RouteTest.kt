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
    fun `morceau de trace entre deux distances`() {
        val route = route()
        // Autour du virage (à 500 m du départ) : trente mètres avant, trente après.
        val piece = route.slice(470.0, 530.0)
        assertEquals(60.0, Route.cumulativeDistances(piece).last(), 0.1)
        assertEquals(0.0, Geo.distanceM(route.positionAt(470.0), piece.first()), 0.01)
        assertEquals(0.0, Geo.distanceM(route.positionAt(530.0), piece.last()), 0.01)
        // Il passe par les points du tracé situés entre les deux, donc par le coin du virage.
        assertEquals(listOf(route.positionAt(470.0), route.points[24], route.points[25], route.points[26], route.positionAt(530.0)), piece)

        // Aux extrémités, le morceau s'arrête au départ et à l'arrivée.
        assertEquals(route.points.first(), route.slice(-50.0, 30.0).first())
        assertEquals(30.0, Route.cumulativeDistances(route.slice(-50.0, 30.0)).last(), 0.1)
        assertEquals(route.points.last(), route.slice(880.0, 2_000.0).last())
        // Un morceau vide se réduit à un point.
        assertEquals(1, route.slice(300.0, 300.0).size)
        assertEquals(1, route.slice(300.0, 100.0).size)
        // Bornes posées exactement sur des points du tracé : pas de point en double.
        assertEquals(listOf(route.points[5], route.points[6], route.points[7]), route.slice(route.distances[5], route.distances[7]))
    }

    @Test
    fun `fleche d une manoeuvre`() {
        val route = route()
        // Virage à gauche au coin (500 m) : la flèche arrive par l'ouest et repart vers le nord.
        val turn = route.arrowFor(Instruction(25, route.distances[25], Maneuver.LEFT))
        assertEquals(58.0, Route.cumulativeDistances(turn).last(), 0.2)
        assertEquals(90.0, Geo.bearingDeg(turn[0].lat, turn[0].lon, turn[1].lat, turn[1].lon), 1.0)
        val tip = turn.last()
        val before = turn[turn.size - 2]
        assertEquals(0.0, Geo.bearingDeg(before.lat, before.lon, tip.lat, tip.lon), 1.0)
        // Giratoire : elle va plus loin après l'entrée, pour montrer la sortie.
        val roundabout = route.arrowFor(Instruction(25, route.distances[25], Maneuver.ROUNDABOUT, 2))
        assertEquals(80.0, Route.cumulativeDistances(roundabout).last(), 0.2)
        // Une manœuvre tout près du départ : la flèche commence au départ.
        assertEquals(route.points.first(), route.arrowFor(Instruction(1, route.distances[1], Maneuver.RIGHT)).first())
    }

    @Test
    fun `les consignes se remplacent sans toucher au reste`() {
        val route = route()
        val named = route.withInstructions(listOf(Instruction(25, route.distances[25], Maneuver.LEFT, street = "Cours Paoli")) + route.instructions)
        assertEquals("Cours Paoli", named.instructions.first().street)
        assertEquals(route.points, named.points)
        assertEquals(route.distanceM, named.distanceM, 0.0)
        assertEquals(route.durationS, named.durationS, 0.0)
        assertEquals(route.waypoints, named.waypoints)
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
