package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteFollowerTest {

    /** Itinéraire en baïonnette : 500 m vers l'est, 400 m vers le nord, 300 m vers l'est. Un point tous les 20 m. */
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
        leg(90.0, 500); leg(0.0, 400); leg(90.0, 300)
        val distances = Route.cumulativeDistances(points)
        val instructions = listOf(
            Instruction(25, distances[25], Maneuver.LEFT),
            Instruction(45, distances[45], Maneuver.RIGHT),
            Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE),
        )
        return Route(points, null, distances, durationS = 240.0, ascentM = 0.0, descentM = 0.0, instructions = instructions, waypoints = listOf(points.first(), points.last()))
    }

    /** Position à [meters] du départ le long de l'itinéraire, décalée de [side] mètres sur le côté. */
    private fun at(route: Route, meters: Double, side: Double = 0.0): LatLon {
        val d = route.distances
        var i = 0
        while (i < d.size - 2 && d[i + 1] < meters) i++
        val a = route.points[i]
        val b = route.points[i + 1]
        val t = ((meters - d[i]) / (d[i + 1] - d[i])).coerceIn(0.0, 1.0)
        val on = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        if (side == 0.0) return on
        val bearing = Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
        return Geo.offset(on.lat, on.lon, bearing + 90.0, side)
    }

    @Test
    fun `la progression et la prochaine consigne suivent la position`() {
        val route = route()
        val follower = RouteFollower(route)
        var state = follower.update(at(route, 100.0, side = 4.0), 5.0, 0)
        assertEquals(100.0, state.progressM, 1.0)
        assertEquals(route.distanceM - 100.0, state.remainingM, 1.5)
        assertEquals(4.0, state.offsetM, 0.5)
        assertEquals(Maneuver.LEFT, state.next!!.maneuver)
        assertEquals(400.0, state.distanceToNextM, 1.5)
        assertNull("le virage suivant est à 400 m : pas d'annonce groupée", state.then)
        assertEquals(240.0 * (route.distanceM - 100.0) / route.distanceM, state.remainingS, 1.0)

        state = follower.update(at(route, 620.0), 5.0, 60_000)
        assertEquals(Maneuver.RIGHT, state.next!!.maneuver)
        assertEquals(280.0, state.distanceToNextM, 1.5)
        assertFalse(state.isOffRoute)
        assertFalse(state.arrived)

        state = follower.update(at(route, 1190.0), 5.0, 200_000)
        assertTrue(state.arrived)
        assertEquals(Maneuver.ARRIVE, state.next!!.maneuver)
    }

    @Test
    fun `un ecart bref est tolere et un ecart durable declenche l'alerte`() {
        val route = route()
        val follower = RouteFollower(route)
        follower.update(at(route, 200.0), 5.0, 0)
        // Le GPS saute de 80 m pendant 3 secondes : pas d'alerte.
        for (s in 1..3) assertFalse(follower.update(at(route, 200.0 + s * 6, side = 80.0), 5.0, s * 1000L).isOffRoute)
        assertFalse(follower.update(at(route, 230.0), 5.0, 4_000).isOffRoute)
        // On part vraiment de travers : alerte au bout de 8 secondes, pas avant.
        var state = follower.state
        for (s in 5..16) {
            state = follower.update(at(route, 230.0 + (s - 4) * 6, side = 90.0), 5.0, s * 1000L)
            if (s < 12) assertFalse("alerte trop tôt à ${s}s", state.isOffRoute)
        }
        assertTrue(state.isOffRoute)
        assertTrue(state.offsetM > 80)
        // De retour sur l'itinéraire : l'alerte tombe.
        assertFalse(follower.update(at(route, 320.0, side = 5.0), 5.0, 30_000).isOffRoute)
    }

    @Test
    fun `une mauvaise precision du GPS elargit la tolerance`() {
        val route = route()
        val follower = RouteFollower(route)
        var state = follower.update(at(route, 100.0), 5.0, 0)
        for (s in 1..20) state = follower.update(at(route, 100.0 + s * 5, side = 55.0), accuracyM = 50.0, monoMs = s * 1000L)
        assertFalse(state.isOffRoute)
    }

    @Test
    fun `reprendre l'itineraire plus loin recale la progression`() {
        val route = route()
        val follower = RouteFollower(route)
        follower.update(at(route, 50.0), 5.0, 0)
        // On coupe à travers champs et on rejoint le troisième tronçon.
        val state = follower.update(at(route, 1000.0, side = 3.0), 5.0, 120_000)
        assertEquals(1000.0, state.progressM, 2.0)
        assertEquals(Maneuver.ARRIVE, state.next!!.maneuver)
    }

    @Test
    fun `sur un aller-retour on ne saute pas du retour a l'aller`() {
        // 600 m vers l'est puis retour par le même chemin.
        val points = ArrayList<LatLon>()
        var p = LatLon(45.0, 5.0)
        points.add(p)
        repeat(30) { p = Geo.offset(p.lat, p.lon, 90.0, 20.0); points.add(p) }
        repeat(30) { p = Geo.offset(p.lat, p.lon, 270.0, 20.0); points.add(p) }
        val distances = Route.cumulativeDistances(points)
        val route = Route(points, null, distances, 300.0, 0.0, 0.0, listOf(Instruction(30, distances[30], Maneuver.U_TURN)), listOf(points.first(), points.last()))
        val follower = RouteFollower(route)
        // Au départ, entre l'aller et le retour (superposés), c'est l'aller qui est retenu.
        assertEquals(40.0, follower.update(Geo.offset(45.0, 5.0, 90.0, 40.0), 5.0, 0).progressM, 2.0)
        // Aller complet, demi-tour, puis retour : la progression continue de croître.
        var last = 0.0
        for (s in 1..55) {
            val along = 40.0 + s * 20.0
            val x = if (along <= 600.0) along else 1200.0 - along
            val state = follower.update(Geo.offset(45.0, 5.0, 90.0, x), 5.0, s * 3000L)
            assertTrue("recul à $along m : ${state.progressM} < $last", state.progressM >= last - 1.0)
            last = state.progressM
        }
        // Il reste 60 m avant de retrouver le point de départ.
        assertEquals(route.distanceM - 60.0, last, 3.0)
    }

    @Test
    fun `chaque consigne est annoncee a l'approche puis au moment de tourner`() {
        val route = route()
        val follower = RouteFollower(route)
        val announcer = GuidanceAnnouncer()
        val said = ArrayList<Announcement>()
        // 18 km/h : 5 m par seconde.
        var meters = 0.0
        var second = 0L
        while (meters <= 1200.0) {
            val state = follower.update(at(route, meters), 5.0, second * 1000)
            said += announcer.update(state, 5.0)
            meters += 5.0
            second++
        }
        assertEquals(
            listOf(Announcement.Kind.PREPARE, Announcement.Kind.NOW, Announcement.Kind.PREPARE, Announcement.Kind.NOW, Announcement.Kind.ARRIVED),
            said.map { it.kind },
        )
        assertEquals("Dans 100 mètres, tourne à gauche.", said[0].text)
        assertEquals("Tourne à gauche.", said[1].text)
        assertEquals("Dans 100 mètres, tourne à droite.", said[2].text)
        assertEquals("Tu es à destination.", said[4].text)
    }

    @Test
    fun `la sortie d'itineraire n'est annoncee qu'une fois par ecart`() {
        val route = route()
        val follower = RouteFollower(route)
        val announcer = GuidanceAnnouncer()
        val said = ArrayList<Announcement>()
        follower.update(at(route, 150.0), 5.0, 0)
        for (s in 1..40) said += announcer.update(follower.update(at(route, 150.0 + s * 3, side = 120.0), 5.0, s * 1000L), 3.0)
        assertEquals(listOf(Announcement.Kind.OFF_ROUTE), said.map { it.kind })
        assertNotNull(said.single().text)
    }

    @Test
    fun `les phrases du guidage`() {
        assertEquals("Rond-point, 2e sortie", GuidancePhrases.label(Instruction(0, 0.0, Maneuver.ROUNDABOUT, 2)))
        assertEquals("Rond-point, 1re sortie", GuidancePhrases.label(Instruction(0, 0.0, Maneuver.ROUNDABOUT, 1)))
        assertEquals("Dans 250 mètres, au rond-point, prends la troisième sortie.", GuidancePhrases.prepare(Instruction(0, 0.0, Maneuver.ROUNDABOUT, 3), 262.0))
        assertEquals("Tourne à droite, puis tourne à gauche.", GuidancePhrases.now(Instruction(0, 0.0, Maneuver.RIGHT), Instruction(1, 60.0, Maneuver.LEFT)))
        assertEquals("Fais demi-tour.", GuidancePhrases.now(Instruction(0, 0.0, Maneuver.U_TURN), null))
        assertEquals("40 mètres", GuidancePhrases.spokenDistance(38.0))
        assertEquals("150 mètres", GuidancePhrases.spokenDistance(160.0))
        assertEquals("1 kilomètre", GuidancePhrases.spokenDistance(1020.0))
        assertEquals("1,2 kilomètre", GuidancePhrases.spokenDistance(1240.0))
        assertEquals("3,5 kilomètres", GuidancePhrases.spokenDistance(3460.0))
    }

    @Test
    fun `deux itineraires au meme trace sont reconnus comme identiques`() {
        val a = route()
        val b = route()
        assertTrue(a.isSameAs(b))
        // Même départ et même arrivée, mais par un autre chemin.
        val start = a.points.first()
        val end = a.points.last()
        val other = listOf(start, Geo.offset(start.lat, start.lon, 0.0, 400.0), end)
        val c = Route(other, null, Route.cumulativeDistances(other), 240.0, 0.0, 0.0, emptyList(), listOf(start, end))
        assertFalse(a.isSameAs(c))
    }
}
