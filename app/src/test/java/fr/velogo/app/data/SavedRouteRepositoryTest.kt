package fr.velogo.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import fr.velogo.app.data.db.SavedRouteEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.app.ui.routes.savedRouteRows
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Polyline
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RouteShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Les trajets enregistrés, de bout en bout sur une vraie base : ce que l'onglet « Trajets » montre et permet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedRouteRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: VeloGoDatabase
    private lateinit var routes: SavedRouteRepository

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder<VeloGoDatabase>(context).setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        routes = SavedRouteRepository(database)
    }

    @After
    fun close() = database.close()

    /** Un itinéraire sinueux de [count] points, un tous les 40 m, en montée régulière. */
    private fun route(count: Int = 1_500, start: LatLon = LatLon(42.305, 9.150)): Route {
        val points = ArrayList<LatLon>(count)
        var p = start
        repeat(count) { i ->
            points.add(p)
            p = Geo.offset(p.lat, p.lon, 20.0 + 70.0 * kotlin.math.sin(i / 60.0), 40.0)
        }
        val distances = Route.cumulativeDistances(points)
        return Route(
            points = points,
            elevations = DoubleArray(count) { 400.0 + it * 0.2 },
            distances = distances,
            durationS = distances.last() / 5.5, ascentM = (count - 1) * 0.2, descentM = 0.0,
            instructions = listOf(Instruction(count / 2, distances[count / 2], Maneuver.LEFT), Instruction(count - 1, distances.last(), Maneuver.ARRIVE)),
            waypoints = listOf(points.first(), points.last()),
        )
    }

    /**
     * Le cas vécu : un trajet qui fait une boucle de 8 km, repasse par son départ, puis repart sur 3 km par les
     * routes du début. Raccourci, il garde son nom, sa date et son rang ; seul son tracé change.
     */
    @Test
    fun `un trajet raccourci remplace l ancien sous le meme nom`() = runBlocking<Unit> {
        val home = LatLon(48.997, 2.218)
        val points = ArrayList<LatLon>()
        points.add(home)
        fun go(bearing: Double, meters: Int) = repeat(meters / 50) { points.add(Geo.offset(points.last().lat, points.last().lon, bearing, 50.0)) }
        go(0.0, 2_000); go(90.0, 2_000); go(180.0, 2_000); go(270.0, 2_000); go(0.0, 3_000)
        val distances = Route.cumulativeDistances(points)
        val whole = Route(
            points = points, elevations = DoubleArray(points.size) { 60.0 + (it % 40) }, distances = distances,
            durationS = distances.last() / 5.0, ascentM = 300.0, descentM = 280.0,
            instructions = listOf(Instruction(40, distances[40], Maneuver.RIGHT), Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)),
            waypoints = listOf(home, points[40], home, points.last()),
        )
        val id = routes.save("Entraînement 1", whole, BikeType.HYBRID)
        val before = routes.observe().first().single()
        assertEquals(11_000.0, before.distanceM, 30.0)

        val back = RouteShape.returnToStart(routes.load(id)!!.second)!!
        assertEquals(8_000.0, back.distanceM, 30.0)
        assertEquals(3_000.0, back.remainingM, 30.0)
        routes.replace(id, whole.truncatedAt(back.index))

        val after = routes.observe().first().single()
        assertEquals(id, after.id)
        assertEquals("Entraînement 1", after.name)
        assertEquals(before.createdAtMs, after.createdAtMs)
        assertEquals(8_000.0, after.distanceM, 30.0)
        assertEquals(whole.durationS * 8.0 / 11.0, after.durationS, 5.0)
        val reread = routes.load(id)!!.second
        assertEquals(8_000.0, reread.distanceM, 30.0)
        assertTrue(RouteShape.isLoop(reread))
        assertNull(RouteShape.returnToStart(reread))
        assertEquals(Maneuver.ARRIVE, reread.instructions.last().maneuver)
        assertEquals(reread.points.size - 1, reread.instructions.last().index)
    }

    @Test
    fun `un trajet enregistre se retrouve dans la liste et se relit en entier`() = runBlocking<Unit> {
        val original = route()
        val id = routes.save("  Boucle de la Restonica ", original, BikeType.ROAD)

        val listed = routes.observe().first().single()
        assertEquals(id, listed.id)
        assertEquals("Boucle de la Restonica", listed.name)
        assertEquals("ROAD", listed.bike)
        assertEquals(original.distanceM, listed.distanceM, 0.001)
        assertEquals("la liste ne transporte pas les tracés", "", listed.data)

        val (entity, reread) = routes.load(id)!!
        assertEquals("Boucle de la Restonica", entity.name)
        assertEquals(original.points.size, reread.points.size)
        // La longueur relue est celle de la liste : le trajet rouvert annonce la même distance, au mètre près.
        assertEquals(listed.distanceM, reread.distanceM, 0.5)
        assertEquals(original.instructions.map { it.maneuver }, reread.instructions.map { it.maneuver })
    }

    @Test
    fun `renommer change le nom et rien d'autre`() = runBlocking<Unit> {
        val original = route()
        val id = routes.save("Sortie du dimanche", original, BikeType.HYBRID)
        val other = routes.save("Autre trajet", route(300, LatLon(42.2, 9.1)), BikeType.MTB)
        val before = routes.observe().first().first { it.id == id }

        routes.rename(id, "  Col de Vizzavona  ")

        val after = routes.observe().first().first { it.id == id }
        assertEquals("Col de Vizzavona", after.name)
        assertEquals(before.copy(name = "Col de Vizzavona"), after)
        assertEquals("Autre trajet", routes.observe().first().first { it.id == other }.name)
        assertEquals("le tracé n'a pas bougé", original.points.size, routes.load(id)!!.second.points.size)
    }

    @Test
    fun `un nom vide devient un nom par defaut`() = runBlocking<Unit> {
        val id = routes.save("Trajet", route(200), BikeType.HYBRID)
        routes.rename(id, "   ")
        assertEquals("Itinéraire", routes.observe().first().single().name)
    }

    @Test
    fun `la vignette suit le trace en quelques dizaines de points`() = runBlocking<Unit> {
        val original = route()
        val id = routes.save("Trajet", original, BikeType.HYBRID)

        val preview = Polyline.decode(routes.preview(id)!!)

        assertTrue("allégée : ${preview.size} points", preview.size in 2..90)
        assertTrue("part du départ", Geo.distanceM(preview.first(), original.points.first()) < 2.0)
        assertTrue("finit à l'arrivée", Geo.distanceM(preview.last(), original.points.last()) < 2.0)
        // Aucun point de la vignette ne s'éloigne du tracé : c'est bien son dessin, simplifié.
        for (p in preview) {
            val nearest = original.points.minOf { Geo.distanceM(p, it) }
            assertTrue("point de vignette à ${nearest.toInt()} m du tracé", nearest < 2.0)
        }
    }

    @Test
    fun `un trajet court garde tous ses points dans la vignette`() = runBlocking<Unit> {
        val original = route(40)
        val id = routes.save("Court", original, BikeType.HYBRID)
        assertEquals(40, Polyline.decode(routes.preview(id)!!).size)
    }

    @Test
    fun `un trajet supprime ou illisible n'a ni vignette ni contenu`() = runBlocking<Unit> {
        val id = routes.save("Trajet", route(200), BikeType.HYBRID)
        routes.delete(id)
        assertTrue(routes.observe().first().isEmpty())
        assertNull(routes.load(id))
        assertNull(routes.preview(id))

        val broken = database.savedRoutes().insert(SavedRouteEntity(name = "Abîmé", createdAtMs = 1L, bike = "HYBRID", distanceM = 1_000.0, ascentM = 0.0, durationS = 100.0, data = "{pas du json"))
        assertNotNull(database.savedRoutes().get(broken))
        assertNull(routes.load(broken))
        assertNull(routes.preview(broken))
    }

    @Test
    fun `les lignes de l'onglet disent l'essentiel, les plus recents d'abord`() = runBlocking<Unit> {
        val zone = ZoneId.systemDefault()
        fun at(date: LocalDate) = date.atTime(10, 30).atZone(zone).toInstant().toEpochMilli()
        val today = LocalDate.of(2026, 11, 21)
        val dao = database.savedRoutes()
        fun entity(name: String, date: LocalDate, bike: String, distanceM: Double, ascentM: Double, durationS: Double) =
            SavedRouteEntity(name = name, createdAtMs = at(date), bike = bike, distanceM = distanceM, ascentM = ascentM, durationS = durationS, data = "")
        val old = dao.insert(entity("Tour du Cap Corse", LocalDate.of(2025, 7, 14), "ROAD", 112_400.0, 1_840.0, 19_500.0))
        val recent = dao.insert(entity("Gare de Corte", today, "HYBRID", 2_310.0, 46.0, 540.0))
        val yesterday = dao.insert(entity("Sentier des bergeries", today.minusDays(1), "MTB", 18_700.0, 720.0, 6_300.0))
        val month = dao.insert(entity("Vallée du Tavignano", LocalDate.of(2026, 11, 3), "inconnu", 850.0, 12.0, 200.0))

        val rows = savedRouteRows(routes.observe().first(), previews = mapOf(recent to "abc"), today = today)

        assertEquals(listOf(recent, yesterday, month, old), rows.map { it.id })
        assertEquals("Gare de Corte", rows[0].name)
        assertEquals("2,3 km", rows[0].distance)
        assertEquals("9 min · ↗ 46 m", rows[0].facts)
        assertEquals("Enregistré aujourd'hui · VTC", rows[0].detail)
        assertEquals("abc", rows[0].preview)

        assertEquals("18,7 km", rows[1].distance)
        assertEquals("1 h 45 · ↗ 720 m", rows[1].facts)
        assertEquals("Enregistré hier · VTT", rows[1].detail)
        assertNull("vignette pas encore prête", rows[1].preview)

        assertEquals("850 m", rows[2].distance)
        assertEquals("un vélo inconnu est pris pour un VTC", "Enregistré le 3 novembre · VTC", rows[2].detail)

        val newYear = savedRouteRows(routes.observe().first(), emptyMap(), today = LocalDate.of(2026, 11, 22).plusYears(1).withDayOfYear(1))
        assertEquals("une autre année, la date est en chiffres", "Enregistré le 21/11/2026 · VTC", newYear[0].detail)
        val eve = dao.insert(entity("Réveillon", LocalDate.of(2026, 12, 31), "ROAD", 5_000.0, 10.0, 900.0))
        val firstOfJanuary = savedRouteRows(routes.observe().first(), emptyMap(), today = LocalDate.of(2027, 1, 1))
        assertEquals(eve, firstOfJanuary[0].id)
        assertEquals("Enregistré hier · Route", firstOfJanuary[0].detail)

        assertEquals("112 km", rows[3].distance)
        assertEquals("5 h 25 · ↗ 1840 m", rows[3].facts)
        assertEquals("Enregistré le 14/07/2025 · Route", rows[3].detail)
    }
}
