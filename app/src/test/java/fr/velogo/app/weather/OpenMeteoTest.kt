package fr.velogo.app.weather

import fr.velogo.core.weather.RainForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture des réponses d'Open-Meteo, d'après le format décrit dans sa documentation (temps en secondes Unix). */
class OpenMeteoTest {
    private val t0 = 1_795_208_400L // une heure ronde

    private fun place(lat: Double, firstTemperature: Double) = """
        {"latitude": $lat, "longitude": 9.14, "generationtime_ms": 0.21, "utc_offset_seconds": 0, "timezone": "GMT",
         "timezone_abbreviation": "GMT", "elevation": 441.0,
         "current_units": {"time": "unixtime", "interval": "seconds", "temperature_2m": "°C"},
         "current": {"time": ${t0 + 900}, "interval": 900, "temperature_2m": $firstTemperature, "apparent_temperature": 9.4,
                     "precipitation": 0.0, "weather_code": 2, "wind_speed_10m": 14.4, "wind_direction_10m": 245,
                     "wind_gusts_10m": 31.3, "is_day": 1},
         "hourly_units": {"time": "unixtime"},
         "hourly": {"time": [$t0, ${t0 + 3600}, ${t0 + 7200}, ${t0 + 10800}],
                    "temperature_2m": [$firstTemperature, 12.1, 11.0, null],
                    "apparent_temperature": [9.4, 9.9, 8.7, null],
                    "precipitation": [0.0, 0.4, 2.6, null],
                    "weather_code": [2, 61, 63, null],
                    "wind_speed_10m": [14.4, 16.2, 21.0, null],
                    "wind_direction_10m": [245, 250, 262, null],
                    "wind_gusts_10m": [31.3, 36.0, 44.6, null],
                    "is_day": [1, 1, 0, null]},
         "minutely_15": {"time": [$t0, ${t0 + 900}, ${t0 + 1800}, ${t0 + 2700}, ${t0 + 3600}],
                         "precipitation": [0.0, 0.0, 0.2, 0.7, null]}}
    """.trimIndent()

    @Test
    fun `un lieu`() {
        val report = OpenMeteo.parse(place(42.3, 11.5), fetchedAtMs = 42L).single()
        assertEquals(42.3, report.lat, 1e-9)
        assertEquals(42L, report.fetchedAtMs)

        val now = report.current!!
        assertEquals((t0 + 900) * 1000, now.timeMs)
        assertEquals(11.5, now.temperatureC!!, 1e-9)
        assertEquals(9.4, now.apparentC!!, 1e-9)
        assertEquals(2, now.code)
        assertEquals(245.0, now.windFromDeg!!, 1e-9)
        assertEquals(31.3, now.gustKmh!!, 1e-9)
        assertTrue(now.isDay)

        // La dernière heure, sans aucune valeur, est écartée.
        assertEquals(3, report.hourly.size)
        assertEquals(63, report.hourly[2].code)
        assertFalse(report.hourly[2].isDay)
        // La pluie de chaque heure est celle de l'heure qui commence (la valeur suivante d'Open-Meteo).
        assertEquals(0.4, report.hourly[0].precipitationMm!!, 1e-9)
        assertEquals(2.6, report.hourly[1].precipitationMm!!, 1e-9)
        assertNull(report.hourly[2].precipitationMm)
        // À 0 h 40, il fait la température de 1 h mais il tombe la pluie de la première heure.
        val later = report.at((t0 + 2400) * 1000)!!
        assertEquals(12.1, later.temperatureC!!, 1e-9)
        assertEquals(0.4, later.precipitationMm!!, 1e-9)

        // Quarts d'heure : chaque valeur est ramenée au début de son quart d'heure, celles sans valeur écartées.
        assertEquals(4, report.quarters.size)
        assertEquals((t0 - 900) * 1000, report.quarters.first().timeMs)
        // Dix minutes après l'heure ronde, la pluie (valeur datée de + 30 min) commence dans cinq minutes.
        val rain = RainForecast.next(report.quarters, nowMs = (t0 + 600) * 1000)!!
        assertEquals(5, rain.startsInMin)
        assertNull(rain.endsInMin)
    }

    @Test
    fun `plusieurs lieux dans une meme reponse`() {
        val reports = OpenMeteo.parse("[${place(42.3, 11.5)}, ${place(42.4, 8.0)}]", 1L)
        assertEquals(listOf(42.3, 42.4), reports.map { it.lat })
        assertEquals(8.0, reports[1].current!!.temperatureC!!, 1e-9)
    }

    @Test
    fun `quota depasse ou reponse illisible`() {
        val quota = assertThrows(WeatherException::class.java) {
            OpenMeteo.parse("""{"error": true, "reason": "Daily API request limit exceeded. Please try again tomorrow."}""", 1L)
        }
        assertTrue(quota.message!!.contains("limit exceeded"))
        assertThrows(WeatherException::class.java) { OpenMeteo.parse("<html>502</html>", 1L) }
        assertThrows(WeatherException::class.java) { OpenMeteo.parse("[]", 1L) }
    }

    @Test
    fun `une reponse sans conditions actuelles reste utilisable`() {
        val report = OpenMeteo.parse("""{"latitude": 45.9, "longitude": 6.1, "hourly": {"time": [$t0], "temperature_2m": [3.5]}}""", 1L).single()
        assertNull(report.current)
        assertEquals(3.5, report.hourly.single().temperatureC!!, 1e-9)
        assertTrue(report.quarters.isEmpty())
    }
}
