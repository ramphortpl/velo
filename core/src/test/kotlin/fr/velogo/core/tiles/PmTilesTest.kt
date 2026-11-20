package fr.velogo.core.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Le fichier d'essai (quelques tuiles du plateau des Glières) a été découpé avec l'outil officiel `pmtiles extract`.
 * Les tailles et empreintes attendues viennent de `pmtiles tile`, une implémentation indépendante de la nôtre.
 */
class PmTilesTest {

    private class Expected(val z: Int, val x: Int, val y: Int, val size: Int, val sha: String)

    private val expected = listOf(
        Expected(12, 2119, 1457, 37392, "cf9f1e478c335e56"),
        Expected(12, 2119, 1458, 38064, "5b37803cb9cca069"),
        Expected(12, 2120, 1457, 35047, "90c6e752d00edc58"),
        Expected(12, 2120, 1458, 39343, "3cc1eb2456c72308"),
        Expected(13, 4239, 2915, 15113, "d8175173fe7e31af"),
        Expected(13, 4239, 2916, 12407, "6d5e653684336460"),
        Expected(13, 4240, 2915, 11578, "fcca0a892ac22ee9"),
        Expected(13, 4240, 2916, 14005, "8a3874aeab5df24e"),
        Expected(14, 8478, 5830, 9160, "38f79be4e9f9af5a"),
        Expected(14, 8478, 5831, 4872, "f43701b9c12957e1"),
        Expected(14, 8478, 5832, 3242, "e7b2bb679681f796"),
        Expected(14, 8479, 5830, 3059, "66e0094a6a1f84ec"),
        Expected(14, 8479, 5831, 7351, "1db18dc31d9118d1"),
        Expected(14, 8479, 5832, 5338, "2b1f40e15e260189"),
        Expected(14, 8480, 5830, 5048, "58ebb8a58ab60cc8"),
        Expected(14, 8480, 5831, 5839, "e64c9dc4b877c3d3"),
        Expected(14, 8480, 5832, 6779, "f2a774dec7e1778a"),
        Expected(14, 8481, 5830, 4681, "eaefe86842124c9a"),
        Expected(14, 8481, 5831, 4302, "acef8e46c12f9563"),
        Expected(14, 8481, 5832, 4521, "864274496833bd12"),
    )

    private fun fixture(): File = File(javaClass.getResource("/essai.pmtiles")!!.toURI())

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

    @Test
    fun `numerotation des tuiles conforme a la specification`() {
        assertEquals(0L, PmTilesReader.zxyToTileId(0, 0, 0))
        assertEquals(1L, PmTilesReader.zxyToTileId(1, 0, 0))
        assertEquals(2L, PmTilesReader.zxyToTileId(1, 0, 1))
        assertEquals(3L, PmTilesReader.zxyToTileId(1, 1, 1))
        assertEquals(4L, PmTilesReader.zxyToTileId(1, 1, 0))
        assertEquals(5L, PmTilesReader.zxyToTileId(2, 0, 0))
        // Dernière tuile du zoom 2 : 1 + 4 + 16 - 1.
        assertEquals(20L, PmTilesReader.zxyToTileId(2, 3, 0))
        // Chaque zoom occupe une plage continue de numéros, sans doublon.
        val seen = HashSet<Long>()
        for (x in 0 until 16) for (y in 0 until 16) assertTrue(seen.add(PmTilesReader.zxyToTileId(4, x, y)))
        assertEquals(85L, seen.min())
        assertEquals(340L, seen.max())
    }

    @Test
    fun `en-tete du fichier`() {
        PmTilesReader.open(fixture()).use { reader ->
            assertEquals(12, reader.header.minZoom)
            assertEquals(14, reader.header.maxZoom)
            assertEquals(PmTilesReader.TYPE_MVT, reader.header.tileType)
            assertTrue(reader.header.isGzippedTiles)
            assertEquals(6.30, reader.header.minLon, 1e-6)
            assertEquals(45.98, reader.header.maxLat, 1e-6)
            assertTrue(reader.metadata().contains("vector_layers"))
        }
    }

    @Test
    fun `chaque tuile est identique a celle de l'outil officiel`() {
        PmTilesReader.open(fixture()).use { reader ->
            for (e in expected) {
                val tile = reader.tile(e.z, e.x, e.y)
                assertNotNull("tuile ${e.z}/${e.x}/${e.y}", tile)
                assertEquals("taille ${e.z}/${e.x}/${e.y}", e.size, tile!!.size)
                assertEquals("contenu ${e.z}/${e.x}/${e.y}", e.sha, sha(tile))
            }
        }
    }

    @Test
    fun `les tuiles absentes renvoient null`() {
        PmTilesReader.open(fixture()).use { reader ->
            assertNull(reader.tile(12, 2118, 1456))
            assertNull(reader.tile(12, 2119, 1459))
            assertNull(reader.tile(14, 8482, 5830))
            assertNull(reader.tile(11, 1059, 728)) // zoom non présent
            assertNull(reader.tile(15, 16958, 11662))
            assertNull(reader.tile(14, 0, 0))
        }
    }

    @Test
    fun `tuiles brutes compressees et test d'emprise`() {
        PmTilesReader.open(fixture()).use { reader ->
            val raw = reader.rawTile(14, 8479, 5831)!!
            assertEquals(0x1f.toByte(), raw[0])
            assertEquals(0x8b.toByte(), raw[1])
            assertEquals(7351, PmTilesReader.gunzip(raw).size)
            assertTrue(reader.mayContain(14, 8479, 5831))
            assertFalse(reader.mayContain(14, 8000, 5831)) // 10° plus à l'ouest
            assertFalse(reader.mayContain(10, 529, 364)) // zoom absent
        }
    }

    @Test
    fun `lectures simultanees`() {
        PmTilesReader.open(fixture()).use { reader ->
            val errors = java.util.concurrent.atomic.AtomicInteger()
            val threads = (0 until 8).map { t ->
                Thread {
                    repeat(40) { i ->
                        val e = expected[(t * 7 + i) % expected.size]
                        val tile = reader.tile(e.z, e.x, e.y)
                        if (tile == null || sha(tile) != e.sha) errors.incrementAndGet()
                    }
                }.also { it.start() }
            }
            threads.forEach { it.join() }
            assertEquals(0, errors.get())
        }
    }

    @Test(expected = java.io.IOException::class)
    fun `un fichier quelconque est refuse`() {
        val file = File.createTempFile("faux", ".pmtiles")
        try {
            file.writeBytes(ByteArray(300) { 7 })
            PmTilesReader.open(file)
        } finally {
            file.delete()
        }
    }
}
