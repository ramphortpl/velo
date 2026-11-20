package fr.velogo.app.offline

import fr.velogo.app.elevation.ReliefSource
import fr.velogo.core.tiles.PmTilesReader
import fr.velogo.core.tiles.TileSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Altitude lue dans un vrai morceau de pack de relief (une tuile Mapterhorn autour de Corte, en Corse).
 * Les valeurs attendues sont celles du service altimétrique de l'IGN aux mêmes points (relevées le 20/11/2026) :
 * le relief hors ligne doit s'en écarter de moins d'un mètre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReliefSourceTest {

    private val fixture = File(javaClass.getResource("/essai-relief.pmtiles")!!.toURI())

    @Test
    fun `l'altitude hors ligne colle a celle de l'IGN`() {
        PmTilesReader.open(fixture).use { reader ->
            val tiles = OfflineTiles(TileSet.EMPTY, TileSet.EMPTY, TileSet(listOf(reader)), 1)
            val relief = ReliefSource { tiles }
            assertTrue(relief.isAvailable)
            val ign = listOf(
                Triple(42.3060, 9.1500, 437.78),
                Triple(42.3200, 9.1800, 467.57),
                Triple(42.3400, 9.2000, 664.26),
                Triple(42.2990, 9.1450, 529.07),
                Triple(42.3500, 9.2200, 1102.85),
                Triple(42.3300, 9.1600, 700.99),
            )
            for ((lat, lon, expected) in ign) {
                assertEquals("altitude en $lat, $lon", expected, relief.altitude(lat, lon)!!, 1.0)
            }
            // Hors de la zone couverte (Annecy) : pas d'altitude plutôt qu'une valeur fausse.
            assertNull(relief.altitude(45.8992, 6.1294))
        }
    }

    @Test
    fun `sans pack de relief il n'y a pas d'altitude`() {
        val relief = ReliefSource { OfflineTiles.NONE }
        assertTrue(!relief.isAvailable)
        assertNull(relief.altitude(42.3060, 9.1500))
    }
}
