package fr.velogo.core.tiles

import org.junit.Assert.assertEquals
import org.junit.Test

class ReliefTest {

    @Test
    fun `encodage terrarium`() {
        // Valeurs de référence de la documentation du format : 0 m = (128, 0, 0).
        assertEquals(0.0, ElevationTile.terrarium(0xFF800000.toInt()), 1e-9)
        assertEquals(1.0, ElevationTile.terrarium(0xFF800100.toInt()), 1e-9)
        assertEquals(256.0, ElevationTile.terrarium(0xFF810000.toInt()), 1e-9)
        assertEquals(0.5, ElevationTile.terrarium(0xFF800080.toInt()), 1e-9)
        assertEquals(-32768.0, ElevationTile.terrarium(0xFF000000.toInt()), 1e-9)
        // Le Mont Blanc et un point sous le niveau de la mer survivent à l'aller-retour.
        for (m in listOf(4805.59, 448.2, 0.0, -3.5, 2642.0, 12.25)) {
            assertEquals(m, ElevationTile.terrarium(ElevationTile.encode(m)), 1.0 / 256)
        }
    }

    @Test
    fun `position d'un point dans les tuiles`() {
        // Annecy au zoom 12 : tuile 2117/1458 (vérifié avec les extraits de carte du projet).
        val p = TileMath.position(45.899, 6.129, 12)
        assertEquals(2117, p.x)
        assertEquals(1458, p.y)
        // Le coin nord-ouest du monde.
        val corner = TileMath.position(85.0, -180.0, 3)
        assertEquals(0, corner.x)
        assertEquals(0, corner.y)
        // Le centre du monde est au coin commun de quatre tuiles.
        val center = TileMath.position(0.0, 0.0, 1)
        assertEquals(1, center.x)
        assertEquals(1, center.y)
        assertEquals(0.0, center.fx, 1e-9)
        assertEquals(0.0, center.fy, 1e-9)
    }

    @Test
    fun `interpolation sur un plan incline`() {
        // Un plan : l'altitude vaut 100 + 2 m par pixel vers l'est + 3 m par pixel vers le sud.
        val size = 16
        val pixels = IntArray(size * size) { i -> ElevationTile.encode(100.0 + 2.0 * (i % size) + 3.0 * (i / size)) }
        val tile = ElevationTile.fromArgb(size, pixels)
        // Au centre d'un pixel, on retrouve sa valeur.
        assertEquals(100.0 + 2.0 * 5 + 3.0 * 7, tile.sample(5.5 / size, 7.5 / size), 0.02)
        // Entre deux pixels, la moyenne.
        assertEquals(100.0 + 2.0 * 5.5 + 3.0 * 7, tile.sample(6.0 / size, 7.5 / size), 0.02)
        assertEquals(100.0 + 2.0 * 5 + 3.0 * 7.25, tile.sample(5.5 / size, 7.75 / size), 0.02)
        // Sur les bords, pas d'extrapolation hasardeuse : on reste sur le dernier pixel.
        assertEquals(100.0, tile.sample(0.0, 0.0), 0.02)
        assertEquals(100.0 + 2.0 * 15 + 3.0 * 15, tile.sample(1.0, 1.0), 0.02)
    }
}
