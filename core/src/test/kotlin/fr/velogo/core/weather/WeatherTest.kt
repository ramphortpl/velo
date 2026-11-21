package fr.velogo.core.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    private val now = 1_800_000_000_000L
    private val quarter = 15 * 60_000L

    /** Pluie par quart d'heure à partir du quart d'heure en cours (qui a commencé il y a 5 minutes). */
    private fun quarters(vararg mm: Double) = mm.mapIndexed { i, v -> RainStep(now - 5 * 60_000L + i * quarter, v) }

    @Test
    fun `pas de pluie annoncee pas d'alerte`() {
        assertNull(RainForecast.next(quarters(0.0, 0.0, 0.05, 0.0, 0.0, 0.0), now))
        assertNull(RainForecast.next(emptyList(), now))
    }

    @Test
    fun `averse dans vingt-cinq minutes`() {
        val event = RainForecast.next(quarters(0.0, 0.0, 0.3, 0.9, 0.2, 0.0, 0.0), now)!!
        assertEquals(25, event.startsInMin)
        assertEquals(70, event.endsInMin)
        assertEquals(3.6, event.peakMmPerHour, 1e-9)
        assertEquals("modérée", event.intensity)
        assertEquals("Pluie modérée dans 25 min", RainForecast.describe(event))
    }

    @Test
    fun `il pleut deja`() {
        val event = RainForecast.next(quarters(0.2, 0.3, 0.0, 0.0), now)!!
        assertTrue(event.isNow)
        assertEquals(25, event.endsInMin)
        assertEquals("Pluie faible : encore 25 min", RainForecast.describe(event))
        // Pluie qui dure au-delà de ce qu'on connaît : on ne promet pas de fin.
        val long = RainForecast.next(quarters(2.5, 3.0, 2.2), now)!!
        assertNull(long.endsInMin)
        assertEquals("forte", long.intensity)
        assertEquals("Pluie forte en cours", RainForecast.describe(long))
    }

    @Test
    fun `une averse trop lointaine n'est pas signalee`() {
        val far = quarters(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5)
        assertNull(RainForecast.next(far, now, horizonMin = 90))
        assertEquals(115, RainForecast.next(far, now, horizonMin = 180)!!.startsInMin)
        // Les quarts d'heure passés ne comptent plus.
        assertNull(RainForecast.next(listOf(RainStep(now - 40 * 60_000L, 2.0)), now))
    }

    @Test
    fun `vent de face de dos ou de cote`() {
        // Vent du nord (il vient du nord), on roule vers le nord : en pleine face.
        assertEquals(20.0, Wind.headwindKmh(20.0, windFromDeg = 0.0, bearingDeg = 0.0), 1e-9)
        assertEquals(Wind.Side.HEAD, Wind.side(0.0, 10.0))
        // On roule vers le sud : il pousse.
        assertEquals(-20.0, Wind.headwindKmh(20.0, 0.0, 180.0), 1e-9)
        assertEquals(Wind.Side.TAIL, Wind.side(0.0, 170.0))
        // Vent d'ouest, cap au nord : de côté, sans effet dans l'axe.
        assertEquals(0.0, Wind.headwindKmh(20.0, 270.0, 0.0), 1e-9)
        assertEquals(Wind.Side.CROSS, Wind.side(270.0, 0.0))
        assertEquals("de face", Wind.label(Wind.Side.HEAD))
        assertEquals(listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO", "N"), listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0, 350.0).map { Wind.compass(it) })
    }

    @Test
    fun `prevision horaire la plus proche`() {
        val hour = 3_600_000L
        val report = WeatherReport(
            45.9, 6.1, now, current = null,
            hourly = (0 until 6).map { WeatherPoint(now + it * hour, temperatureC = 10.0 + it) },
            quarters = emptyList(),
        )
        assertEquals(12.0, report.at(now + 2 * hour + 20 * 60_000L)!!.temperatureC!!, 1e-9)
        assertEquals(13.0, report.at(now + 2 * hour + 40 * 60_000L)!!.temperatureC!!, 1e-9)
        assertNull(report.at(now + 9 * hour))
        assertEquals(listOf(11.0, 12.0, 13.0), report.hoursFrom(now + hour + 10 * 60_000L, 3).map { it.temperatureC })
    }

    @Test
    fun `codes meteo`() {
        assertEquals("Ciel dégagé", WeatherCodes.label(0))
        assertEquals("Fortes averses", WeatherCodes.label(82))
        assertEquals(WeatherCodes.Kind.THUNDER, WeatherCodes.kind(96))
        assertEquals(WeatherCodes.Kind.SNOW, WeatherCodes.kind(73))
        assertEquals("Temps incertain", WeatherCodes.label(null))
    }

    @Test
    fun `conditions actuelles d'apres des previsions plus ou moins recentes`() {
        val hour = 3_600_000L
        val report = WeatherReport(
            lat = 45.0, lon = 5.0, fetchedAtMs = now,
            current = WeatherPoint(now, temperatureC = 11.5),
            hourly = (0..5).map { WeatherPoint(now + it * hour, temperatureC = 12.0 + it) },
            quarters = emptyList(),
        )
        // Tout de suite : le relevé. Trois heures plus tard : la prévision de l'heure.
        assertEquals(11.5, report.now(now + 10 * 60_000L)!!.temperatureC!!, 1e-9)
        assertEquals(15.0, report.now(now + 3 * hour)!!.temperatureC!!, 1e-9)
        // Au-delà de ce que couvrent les prévisions : rien.
        assertNull(report.now(now + 9 * hour))
        // Sans prévisions horaires, le relevé reste valable une heure et demie.
        val bare = report.copy(hourly = emptyList())
        assertEquals(11.5, bare.now(now + 80 * 60_000L)!!.temperatureC!!, 1e-9)
        assertNull(bare.now(now + 2 * hour))
    }

    @Test
    fun `alerte pluie une seule fois par averse`() {
        val alerter = RainAlerter(leadMin = 30, quietMin = 45)
        val minute = 60_000L
        val far = RainEvent(startsInMin = 50, endsInMin = 80, peakMmPerHour = 1.0)
        val near = RainEvent(startsInMin = 25, endsInMin = 55, peakMmPerHour = 1.0)
        assertNull(alerter.update(null, now))
        assertNull(alerter.update(far, now))
        assertEquals(near, alerter.update(near, now + 20 * minute))
        // La même averse, toujours annoncée : on ne répète pas.
        assertNull(alerter.update(near.copy(startsInMin = 10), now + 35 * minute))
        assertNull(alerter.update(near.copy(startsInMin = 0), now + 45 * minute))
        // Les prévisions hésitent (plus de pluie, puis de nouveau) : pas de nouvelle alerte avant 45 minutes.
        assertNull(alerter.update(null, now + 50 * minute))
        assertNull(alerter.update(near, now + 55 * minute))
        // Une heure après, une nouvelle averse approche : nouvelle alerte.
        assertNull(alerter.update(null, now + 60 * minute))
        assertEquals(near, alerter.update(near, now + 80 * minute))
    }

    @Test
    fun `annonce de la pluie a voix haute`() {
        assertEquals("Pluie faible dans 20 minutes.", RainForecast.spoken(RainEvent(20, 50, 1.0)))
        assertEquals("Pluie modérée, encore 35 minutes.", RainForecast.spoken(RainEvent(0, 35, 4.0)))
        assertEquals("Pluie forte en cours.", RainForecast.spoken(RainEvent(0, null, 12.0)))
        assertEquals("Pluie faible dans une heure 15.", RainForecast.spoken(RainEvent(75, null, 1.0)))
        assertEquals("de sud-ouest", Wind.origin(225.0))
        assertEquals("d'est", Wind.origin(90.0))
        assertEquals("de nord", Wind.origin(350.0))
    }
}
