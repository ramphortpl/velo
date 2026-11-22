package fr.velogo.core.nav

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boucles, retours au départ en cours de route, et coupe d'un itinéraire à l'un de ses points. */
class RouteShapeTest {
    private val home = LatLon(48.997, 2.218)

    /** Avance de [meters] mètres dans la direction [bearing], un point tous les 50 m. */
    private fun MutableList<LatLon>.go(bearing: Double, meters: Int) {
        repeat(meters / 50) {
            val last = last()
            add(Geo.offset(last.lat, last.lon, bearing, 50.0))
        }
    }

    private fun route(points: List<LatLon>, waypoints: List<LatLon> = listOf(points.first(), points.last()), elevations: DoubleArray? = null): Route {
        val distances = Route.cumulativeDistances(points)
        return Route(
            points = points, elevations = elevations, distances = distances, durationS = distances.last() / 5.0,
            ascentM = 400.0, descentM = 380.0,
            instructions = listOf(
                Instruction(40, distances[40], Maneuver.RIGHT),
                Instruction(80, distances[80], Maneuver.LEFT),
                Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE),
            ),
            waypoints = waypoints,
            mix = WayMix(roadM = distances.last()),
        )
    }

    /** Un carré de 2 km de côté qui revient au départ : 8 km. */
    private fun square(): MutableList<LatLon> = mutableListOf(home).apply {
        go(0.0, 2_000)
        go(90.0, 2_000)
        go(180.0, 2_000)
        go(270.0, 2_000)
    }

    @Test
    fun `une boucle qui s arrete a son retour`() {
        val loop = route(square())
        assertEquals(8_000.0, loop.distanceM, 20.0)
        assertTrue(RouteShape.isLoop(loop))
        assertNull(RouteShape.returnToStart(loop))
    }

    @Test
    fun `un trajet ordinaire n est ni une boucle ni un retour au depart`() {
        val straight = route(mutableListOf(home).apply { go(45.0, 6_000) })
        assertFalse(RouteShape.isLoop(straight))
        assertNull(RouteShape.returnToStart(straight))
    }

    @Test
    fun `la boucle repasse par le depart puis repart par les routes du debut`() {
        // Le cas vécu : la boucle, puis de nouveau le premier côté (superposé au début) jusqu'à l'arrivée.
        val points = square().apply { go(0.0, 1_500) }
        val r = route(points)
        assertFalse(RouteShape.isLoop(r))
        val back = RouteShape.returnToStart(r)
        assertNotNull(back)
        assertEquals(160, back!!.index)
        assertEquals(8_000.0, back.distanceM, 20.0)
        assertEquals(1_500.0, back.remainingM, 10.0)
        assertTrue(Geo.distanceM(points[back.index], home) < 5.0)
    }

    @Test
    fun `quelques centaines de metres apres le retour ne comptent pas`() {
        // On revient chez soi et l'arrivée est au bout de la rue : ce n'est pas un trajet « qui continue ».
        val r = route(square().apply { go(0.0, 600) })
        assertNull(RouteShape.returnToStart(r))
    }

    @Test
    fun `tourner autour du depart avant de partir n est pas un retour`() {
        // Un tour de pâté de maisons (300 m de côté) avant de partir pour de bon.
        val points = mutableListOf(home).apply {
            go(0.0, 300); go(90.0, 300); go(180.0, 300); go(270.0, 300)
            go(45.0, 5_000)
        }
        assertNull(RouteShape.returnToStart(route(points)))
    }

    @Test
    fun `le passage par le depart est vu meme entre deux points eloignes`() {
        // Aller-retour de 3 km, puis on dépasse le départ : la trace n'a de points que tous les 400 m autour de lui.
        val out = mutableListOf(home).apply { go(0.0, 3_000) }
        val backWay = listOf(
            Geo.offset(home.lat, home.lon, 0.0, 2_000.0), Geo.offset(home.lat, home.lon, 0.0, 1_000.0),
            Geo.offset(home.lat, home.lon, 0.0, 250.0), Geo.offset(home.lat, home.lon, 180.0, 150.0),
        )
        val tail = mutableListOf(backWay.last()).apply { go(180.0, 2_000) }
        val r = route(out + backWay + tail.drop(1))
        val back = RouteShape.returnToStart(r)
        assertNotNull(back)
        // Le point retenu est le plus proche du départ : celui qui est 150 m après lui.
        assertEquals(150.0, Geo.distanceM(r.points[back!!.index], home), 5.0)
        assertEquals(2_000.0, back.remainingM, 10.0)
    }

    @Test
    fun `une boucle en huit repasse par son depart sans que ce soit un piege`() {
        // Un tour au nord, retour à la maison, un tour au sud, retour à la maison : c'est voulu, et ça finit au départ.
        val points = mutableListOf(home).apply {
            go(0.0, 2_000); go(90.0, 1_000); go(180.0, 2_000); go(270.0, 1_000)
            go(180.0, 2_000); go(270.0, 1_000); go(0.0, 2_000); go(90.0, 1_000)
        }
        val eight = route(points)
        assertTrue(RouteShape.isLoop(eight))
        assertNull(RouteShape.returnToStart(eight))
    }

    @Test
    fun `plusieurs passages par le depart - c est le dernier qui compte`() {
        // Le même huit, puis de nouveau le premier côté sur 1,5 km : la coupe garde le huit entier.
        val points = mutableListOf(home).apply {
            go(0.0, 2_000); go(90.0, 1_000); go(180.0, 2_000); go(270.0, 1_000)
            go(180.0, 2_000); go(270.0, 1_000); go(0.0, 2_000); go(90.0, 1_000)
            go(0.0, 1_500)
        }
        val r = route(points)
        val back = RouteShape.returnToStart(r)
        assertNotNull(back)
        assertEquals(12_000.0, back!!.distanceM, 30.0)
        assertEquals(1_500.0, back.remainingM, 10.0)
    }

    @Test
    fun `aller-retour simple - une boucle sans suite`() {
        val points = mutableListOf(home).apply { go(30.0, 4_000); go(210.0, 4_000) }
        val r = route(points)
        assertTrue(RouteShape.isLoop(r))
        assertNull(RouteShape.returnToStart(r))
    }

    // --- Coupe ----------------------------------------------------------------------------------------------

    @Test
    fun `couper au retour au depart donne la boucle seule`() {
        val points = square().apply { go(0.0, 1_500) }
        val north = Geo.offset(home.lat, home.lon, 0.0, 2_000.0)
        val east = Geo.offset(north.lat, north.lon, 90.0, 2_000.0)
        // Altitude : on monte de 100 m sur le premier côté, on redescend sur le troisième, et la suite remonte de 75 m.
        val elevations = DoubleArray(points.size) { i ->
            when {
                i <= 40 -> 50.0 + 2.5 * i
                i <= 80 -> 150.0
                i <= 120 -> 150.0 - 2.5 * (i - 80)
                i <= 160 -> 50.0
                else -> 50.0 + 2.5 * (i - 160)
            }
        }
        // Étapes demandées : le coin nord, le coin est, la maison (c'est elle qui ramène au départ), puis l'arrivée.
        val whole = route(points, waypoints = listOf(home, north, east, home, points.last()), elevations = elevations)
        val back = RouteShape.returnToStart(whole)!!
        val loop = whole.truncatedAt(back.index)

        assertEquals(161, loop.points.size)
        assertEquals(8_000.0, loop.distanceM, 20.0)
        assertEquals(loop.points.size, loop.distances.size)
        assertEquals(loop.points.size, loop.elevations!!.size)
        assertTrue(RouteShape.isLoop(loop))
        assertNull(RouteShape.returnToStart(loop))
        // Durée au prorata de la distance.
        assertEquals(whole.durationS * 8_000.0 / 9_500.0, loop.durationS, 2.0)
        // Dénivelé : la boucle garde les 100 m de montée sur 175 et toute la descente.
        assertEquals(400.0 * 100.0 / 175.0, loop.ascentM, 2.0)
        assertEquals(380.0, loop.descentM, 2.0)
        // Consignes : celles du chemin, puis l'arrivée au point de coupe.
        assertEquals(listOf(Maneuver.RIGHT, Maneuver.LEFT, Maneuver.ARRIVE), loop.instructions.map { it.maneuver })
        assertEquals(160, loop.instructions.last().index)
        assertEquals(loop.distanceM, loop.instructions.last().distanceM, 1e-6)
        // Étapes : les deux coins restent, la maison devient l'arrivée (et n'est plus une étape).
        assertEquals(4, loop.waypoints.size)
        assertEquals(home, loop.waypoints.first())
        assertTrue(Geo.distanceM(loop.waypoints.last(), home) < 5.0)
        assertEquals(listOf(north, east), loop.waypoints.subList(1, 3))
        // La répartition par nature de voie n'est pas inventée.
        assertNull(loop.mix)
    }

    @Test
    fun `sans profil d altitude le denivele suit la distance`() {
        val whole = route(square().apply { go(0.0, 2_000) })
        val loop = whole.truncatedAt(160)
        assertEquals(400.0 * 0.8, loop.ascentM, 2.0)
        assertEquals(380.0 * 0.8, loop.descentM, 2.0)
        assertNull(loop.elevations)
    }

    @Test
    fun `les consignes situees apres la coupe disparaissent`() {
        val whole = route(square())
        val cut = whole.truncatedAt(60)
        assertEquals(listOf(Maneuver.RIGHT, Maneuver.ARRIVE), cut.instructions.map { it.maneuver })
        assertEquals(3_000.0, cut.distanceM, 10.0)
    }

    @Test
    fun `couper au dernier point ne change rien et couper ailleurs que sur la trace est refuse`() {
        val whole = route(square())
        assertSame(whole, whole.truncatedAt(whole.points.size - 1))
        for (bad in listOf(0, -1, whole.points.size)) {
            val refused = runCatching { whole.truncatedAt(bad) }.exceptionOrNull()
            assertTrue("indice $bad", refused is IllegalArgumentException)
        }
    }
}
