package fr.velogo.core.weather

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteWeatherTest {
    private val t0 = 1_800_000_000_000L
    private val hour = 3_600_000L

    /** Itinéraire rectiligne vers le [bearing] donné, un point tous les 500 m, avec un profil d'altitude facultatif. */
    private fun straight(km: Int, durationS: Double, bearing: Double = 0.0, elevation: ((Double) -> Double)? = null): Route {
        val points = ArrayList<LatLon>()
        var p = LatLon(45.0, 5.0)
        points.add(p)
        repeat(km * 2) {
            p = Geo.offset(p.lat, p.lon, bearing, 500.0)
            points.add(p)
        }
        val distances = Route.cumulativeDistances(points)
        val elevations = elevation?.let { f -> DoubleArray(points.size) { f(distances[it]) } }
        return Route(
            points, elevations, distances, durationS, 0.0, 0.0,
            listOf(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)), listOf(points.first(), points.last()),
        )
    }

    private fun report(temperature: Double, windFrom: Double = 0.0, wind: Double = 20.0, rainAtHour: Int? = null) = WeatherReport(
        lat = 45.0, lon = 5.0, fetchedAtMs = t0, current = null,
        hourly = (0..6).map { h ->
            WeatherPoint(
                timeMs = t0 + h * hour, temperatureC = temperature, precipitationMm = if (h == rainAtHour) 1.2 else 0.0,
                code = if (h == rainAtHour) 61 else 2, windKmh = wind, windFromDeg = windFrom, gustKmh = wind * 1.8,
            )
        },
        quarters = emptyList(),
    )

    @Test
    fun `sans profil d'altitude le temps suit la distance`() {
        val route = straight(km = 30, durationS = 5400.0)
        val times = RouteWeather.passageTimes(route)
        assertEquals(0.0, times.first(), 1e-9)
        assertEquals(5400.0, times.last(), 1e-6)
        assertEquals(2700.0, times[times.size / 2], 2.0)
    }

    @Test
    fun `un col se monte bien plus lentement qu'il ne se descend`() {
        // 15 km de montée à 6 %, puis 15 km de descente.
        val route = straight(km = 30, durationS = 7200.0) { d -> if (d < 15_000) d * 0.06 else (30_000 - d) * 0.06 }
        val times = RouteWeather.passageTimes(route)
        assertEquals(7200.0, times.last(), 1e-6)
        val summit = times[times.size / 2]
        assertTrue("sommet atteint après ${summit.toInt()} s", summit > 7200.0 * 0.78 && summit < 7200.0 * 0.9)
        // Le temps ne recule jamais.
        for (i in 1 until times.size) assertTrue(times[i] >= times[i - 1])
    }

    @Test
    fun `vitesse selon la pente`() {
        assertEquals(26.0, RouteWeather.speedMps(0.0) * 3.6, 2.0)
        assertEquals(10.5, RouteWeather.speedMps(0.05) * 3.6, 1.5)
        assertEquals(5.7, RouteWeather.speedMps(0.10) * 3.6, 1.0)
        // Descente : on ne dépasse pas 45 km/h ; mur : on pousse le vélo.
        assertEquals(45.0, RouteWeather.speedMps(-0.08) * 3.6, 1e-6)
        assertEquals(3.6, RouteWeather.speedMps(0.25) * 3.6, 1e-6)
    }

    @Test
    fun `endroits ou regarder la meteo`() {
        val route = straight(km = 40, durationS = 7200.0)
        val samples = RouteWeather.samples(route)
        // Un point tous les huit kilomètres, départ et arrivée compris.
        assertEquals(6, samples.size)
        assertEquals(0.0, samples.first().distanceM, 1e-6)
        assertEquals(route.distanceM, samples.last().distanceM, 1e-6)
        assertEquals(0.0, samples.first().offsetS, 1e-6)
        assertEquals(7200.0, samples.last().offsetS, 1e-3)
        for (sample in samples) {
            val delta = Geo.angleDeltaDeg(0.0, sample.bearingDeg)
            assertTrue("cap ${sample.bearingDeg}", kotlin.math.abs(delta) < 1.0)
        }
        // Le troisième est bien sur la trace, aux deux cinquièmes du trajet.
        assertTrue(route.distanceTo(samples[2].position) < 1.0)
        assertEquals(route.distanceM * 2 / 5, samples[2].distanceM, 1.0)

        // En cours de route : on repart de là où l'on est, et le temps est compté à partir de maintenant.
        val ahead = RouteWeather.samples(route, fromM = 30_000.0)
        assertEquals(3, ahead.size)
        assertEquals(30_000.0, ahead.first().distanceM, 1e-6)
        assertEquals(0.0, ahead.first().offsetS, 1e-6)
        assertEquals(7200.0 * (route.distanceM - 30_000.0) / route.distanceM, ahead.last().offsetS, 1.0)

        // Un trajet très court donne quand même le départ et l'arrivée ; un très long plafonne.
        assertEquals(2, RouteWeather.samples(straight(km = 2, durationS = 400.0)).size)
        assertEquals(8, RouteWeather.samples(straight(km = 150, durationS = 20_000.0)).size)
    }

    @Test
    fun `meteo a l'heure de passage`() {
        val route = straight(km = 40, durationS = 7200.0)
        val samples = RouteWeather.samples(route)
        // Vent du nord à 20 km/h, on roule plein nord. Il pleut au 5e point pendant la 2e heure seulement.
        val reports = samples.indices.map { i -> report(temperature = 12.0 + i * 0.6, rainAtHour = if (i == 4) 1 else null) }
        val rows = RouteWeather.rows(samples, reports, departureMs = t0)
        assertEquals(6, rows.size)
        assertEquals(t0, rows.first().timeMs)
        assertEquals(t0 + 2 * hour, rows.last().timeMs)
        assertEquals(20.0, rows[3].headwindKmh!!, 0.1)
        assertEquals(Wind.Side.HEAD, rows[3].side)
        assertFalse(rows[3].isWet)
        // Le 5e point est atteint au bout de 96 minutes : en pleine 2e heure, celle où il y pleut.
        assertEquals(t0 + 96 * 60_000L, rows[4].timeMs)
        assertTrue(rows[4].isWet)

        val summary = RouteWeather.summary(rows)
        assertEquals(12.0, summary.minTemperatureC!!, 1e-9)
        assertEquals(15.0, summary.maxTemperatureC!!, 1e-9)
        assertEquals(rows[4], summary.firstRain)
        assertFalse(summary.rainAtStart)
        assertEquals(20.0, summary.meanHeadwindKmh!!, 0.1)
        assertEquals(36.0, summary.maxGustKmh!!, 1e-9)
        assertEquals(
            "12 à 15° · vent de face 20 km/h · pluie vers +96 (km 32)",
            RouteWeather.headline(summary) { "+${(it - t0) / 60_000}" },
        )

        // En partant une heure plus tôt, on passe au 5e point avant la pluie.
        val earlier = RouteWeather.summary(RouteWeather.rows(samples, reports, departureMs = t0 - hour))
        assertNull(earlier.firstRain)
    }

    @Test
    fun `resume selon le vent et la pluie`() {
        val route = straight(km = 16, durationS = 3600.0, bearing = 180.0)
        val samples = RouteWeather.samples(route)
        fun headline(reports: List<WeatherReport>) =
            RouteWeather.headline(RouteWeather.summary(RouteWeather.rows(samples, reports, t0))) { "h" }

        // Vent du nord, on roule vers le sud : il pousse.
        assertEquals("14° · vent de dos 20 km/h · sans pluie", headline(samples.map { report(14.0) }))
        // Vent d'ouest : de côté.
        assertEquals("14° · vent de côté 25 km/h · sans pluie", headline(samples.map { report(14.0, windFrom = 270.0, wind = 25.0) }))
        assertEquals("14° · peu de vent · sans pluie", headline(samples.map { report(14.0, wind = 5.0) }))
        assertEquals("9° · vent de dos 20 km/h · pluie dès le départ", headline(samples.map { report(9.0, rainAtHour = 0) }))

        // Prévisions qui ne couvrent pas l'heure de passage : pas de valeurs, mais pas d'erreur non plus.
        val late = RouteWeather.rows(samples, samples.map { report(14.0) }, departureMs = t0 + 30 * hour)
        assertTrue(late.all { it.weather == null && it.headwindKmh == null })
        assertEquals("sans pluie", RouteWeather.headline(RouteWeather.summary(late)) { "h" })
    }
}
