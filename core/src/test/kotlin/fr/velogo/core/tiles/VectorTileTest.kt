package fr.velogo.core.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture d'une vraie tuile du fond de carte hors ligne : le centre de Corte, au zoom 15 (couche des voies). */
class VectorTileTest {

    private fun corte(): ByteArray = javaClass.getResource("/tuiles/roads-15-17216-12126.mvt")!!.readBytes()

    @Test
    fun `les voies d une vraie tuile sont lues avec leur nom`() {
        val layer = VectorTile.lines(corte(), "roads", setOf("name", "kind"))
        assertNotNull(layer)
        assertEquals(4096, layer!!.extent)
        assertTrue("${layer.lines.size} voies", layer.lines.size > 100)
        val cours = layer.lines.filter { it.attributes["name"] == "Cours Paoli" }
        assertTrue("le cours Paoli traverse la tuile", cours.isNotEmpty())
        assertTrue("sa partie principale est une grande rue", cours.any { it.attributes["kind"] == "major_road" })
        // Seuls les attributs demandés sont gardés.
        assertTrue(layer.lines.all { line -> line.attributes.keys.all { it == "name" || it == "kind" } })
    }

    @Test
    fun `les traces restent dans la tuile ou tout pres`() {
        val layer = VectorTile.lines(corte(), "roads", emptySet())!!
        var points = 0
        for (feature in layer.lines) {
            for (line in feature.lines) {
                assertTrue("une ligne a au moins deux points", line.size >= 4)
                assertEquals(0, line.size % 2)
                for (v in line) assertTrue("coordonnée $v hors de la tuile", v in -400..4496)
                points += line.size / 2
            }
        }
        assertTrue("$points points", points > 500)
    }

    @Test
    fun `une couche absente ou une tuile illisible ne donnent rien`() {
        assertNull(VectorTile.lines(corte(), "buildings", emptySet()))
        assertNull(VectorTile.lines(ByteArray(0), "roads", emptySet()))
        // Une tuile tronquée en plein milieu d'un objet.
        assertNull(VectorTile.lines(corte().copyOf(1500), "roads", emptySet()))
        assertNull(VectorTile.lines(byteArrayOf(0x1A, 0x7F, 1, 2, 3), "roads", emptySet()))
    }
}
