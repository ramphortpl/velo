package fr.velogo.app.weather

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.weather.RouteWeather
import fr.velogo.core.weather.WeatherPoint
import fr.velogo.core.weather.WeatherReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La météo du trajet vue depuis le panneau de préparation, puis en cours de guidage. */
class RouteWeatherStateTest {
    private val t0 = 1_800_000_000_000L
    private val hour = 3_600_000L

    /** 40 km plein nord en deux heures, sans relief : dix kilomètres par demi-heure. */
    private val route: Route = run {
        val points = ArrayList<LatLon>()
        var p = LatLon(45.0, 5.0)
        points.add(p)
        repeat(80) {
            p = Geo.offset(p.lat, p.lon, 0.0, 500.0)
            points.add(p)
        }
        val distances = Route.cumulativeDistances(points)
        Route(points, null, distances, 7200.0, 0.0, 0.0, listOf(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)), listOf(points.first(), points.last()))
    }

    /** Il fait 10° la première heure, 11° la deuxième, etc. : la température dit quelle heure a été retenue. */
    private fun report() = WeatherReport(
        lat = 45.0, lon = 5.0, fetchedAtMs = t0, current = null,
        hourly = (0..8).map { WeatherPoint(t0 + it * hour, temperatureC = 10.0 + it, windKmh = 10.0, windFromDeg = 0.0) },
        quarters = emptyList(),
    )

    private val samples = RouteWeather.samples(route)
    private val state = RouteWeatherState(route, samples, samples.map { report() }, fetchedAtMs = t0)

    @Test
    fun `en preparation tout le trajet a partir de l'heure de depart`() {
        val rows = state.rows(departureMs = t0)
        assertEquals(samples.size, rows.size)
        assertEquals(t0, rows.first().timeMs)
        assertEquals(t0 + 2 * hour, rows.last().timeMs)
        assertEquals(12.0, rows.last().weather!!.temperatureC!!, 1e-9)

        // En partant trois heures plus tard, tout est décalé d'autant.
        val later = state.rows(departureMs = t0 + 3 * hour)
        assertEquals(13.0, later.first().weather!!.temperatureC!!, 1e-9)
        assertEquals(15.0, later.last().weather!!.temperatureC!!, 1e-9)
    }

    @Test
    fun `en guidage seule la suite du trajet compte a partir de maintenant`() {
        // À mi-parcours, avec une heure de retard sur l'horaire prévu.
        val now = t0 + 2 * hour
        val progress = route.distanceM / 2
        val rows = state.rows(departureMs = now, progressM = progress)
        assertTrue(rows.isNotEmpty() && rows.size < samples.size)
        assertTrue(rows.all { it.distanceM >= progress - 300.0 })
        // L'arrivée est à une heure d'ici : c'est la prévision de la quatrième heure qui s'applique.
        assertEquals((now + hour).toDouble(), rows.last().timeMs.toDouble(), 2_000.0)
        assertEquals(13.0, rows.last().weather!!.temperatureC!!, 1e-9)
        // Chaque endroit restant est atteint dans le temps qu'il faut pour y aller depuis ici.
        for (row in rows) {
            val expected = now + ((row.distanceM - progress) / route.distanceM * 7200.0 * 1000).toLong()
            assertEquals(expected.toDouble(), row.timeMs.toDouble(), 2_000.0)
        }
    }

    @Test
    fun `tant que rien n'est arrive il n'y a rien a afficher`() {
        assertTrue(RouteWeatherState(route, samples, reports = null, loading = true).rows(t0).isEmpty())
    }
}
