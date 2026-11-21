package fr.velogo.app.weather

import fr.velogo.app.ui.weather.departureChoices
import fr.velogo.app.ui.weather.departureLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Les heures de départ proposées pour la météo d'un trajet. */
class DepartureChoicesTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, 21, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

    private fun labels(now: Long) = departureChoices(now, paris).map { departureLabel(it, now, paris) }

    @Test
    fun `le matin`() {
        assertEquals(
            listOf("10 h", "11 h", "12 h", "14 h", "16 h", "18 h", "20 h", "Demain 6 h", "Demain 8 h", "Demain 10 h", "Demain 12 h", "Demain 14 h", "Demain 16 h", "Demain 18 h", "Demain 20 h"),
            labels(at(9, 10)),
        )
    }

    @Test
    fun `le soir on prepare surtout le lendemain`() {
        assertEquals(
            listOf("21 h", "22 h", "23 h", "Demain 6 h", "Demain 8 h", "Demain 10 h", "Demain 12 h", "Demain 14 h", "Demain 16 h", "Demain 18 h", "Demain 20 h"),
            labels(at(20, 15)),
        )
    }

    @Test
    fun `une heure ronde trop proche est sautee`() {
        // À 13 h 50, « 14 h » ne se distingue pas de « maintenant » : on commence à 15 h.
        assertEquals(listOf("15 h", "16 h", "17 h", "18 h", "20 h"), labels(at(13, 50)).take(5))
        // À 13 h 40, il reste vingt minutes : 14 h est proposé.
        assertEquals("14 h", labels(at(13, 40)).first())
    }
}
