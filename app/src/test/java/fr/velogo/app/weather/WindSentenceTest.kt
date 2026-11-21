package fr.velogo.app.weather

import fr.velogo.app.ui.weather.windSentence
import fr.velogo.core.weather.WeatherPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La phrase du vent sur l'écran météo : ce qu'elle dit, et où elle a le droit de passer à la ligne. */
class WindSentenceTest {
    private fun point(windKmh: Double?, fromDeg: Double? = 225.0, gustKmh: Double? = null) = WeatherPoint(
        timeMs = 0, temperatureC = 17.0, apparentC = 14.0, precipitationMm = 0.0, code = 0,
        windKmh = windKmh, windFromDeg = fromDeg, gustKmh = gustKmh, isDay = true,
    )

    /** La phrase telle qu'on la lit, sans les caractères qui règlent les coupures. */
    private fun String.visible() = replace(' ', ' ').replace("⁠", "")

    @Test
    fun `vent, origine et rafales`() {
        assertEquals("8 km/h de sud-ouest, rafales 24 km/h", windSentence(point(8.2, 225.0, 24.4))!!.visible())
        assertEquals("17 km/h d'ouest", windSentence(point(17.0, 268.0, gustKmh = 22.0))!!.visible())
        assertEquals("12 km/h", windSentence(point(12.0, fromDeg = null))!!.visible())
        assertEquals("Pas de vent", windSentence(point(0.4)))
        assertNull(windSentence(point(null)))
    }

    @Test
    fun `la phrase ne se coupe ni dans km-h ni entre un nombre et son unite`() {
        // Vu sur l'écran météo : « rafales 24 km/ » en bout de ligne, et « h » seul sur la suivante.
        val sentence = windSentence(point(8.0, 225.0, 24.0))!!
        // Les seuls endroits où la ligne peut se couper : les espaces ordinaires et le trait d'union.
        assertEquals(listOf("8 km/⁠h", "de", "sud-ouest,", "rafales 24 km/⁠h"), sentence.split(' '))
        // Après chaque barre, le caractère qui interdit la coupure.
        assertEquals(2, Regex("/⁠h").findAll(sentence).count())
        assertEquals(2, sentence.count { it == '/' })
    }
}
