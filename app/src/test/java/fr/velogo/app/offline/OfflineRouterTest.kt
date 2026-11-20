package fr.velogo.app.offline

import fr.velogo.core.tiles.PmTilesReader
import fr.velogo.core.tiles.TileSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Le « serveur » de cartes hors ligne : ce qu'il répond à la carte, sans réseau. */
class OfflineRouterTest {

    // Le fichier d'essai du module core : quelques tuiles vectorielles du plateau des Glières.
    private val fixture = File("../core/src/test/resources/essai.pmtiles")

    private fun router(tiles: OfflineTiles, assets: Map<String, ByteArray> = emptyMap(), asked: MutableList<String> = ArrayList()) =
        OfflineRouter(tiles = { tiles }, asset = { path -> asked.add(path); assets[path] })

    @Test
    fun `les tuiles viennent des packs installes et sont decompressees`() {
        PmTilesReader.open(fixture).use { reader ->
            val tiles = OfflineTiles(TileSet(listOf(reader)), TileSet.EMPTY, TileSet.EMPTY, 1)
            val reply = router(tiles).reply(listOf("fond", "12", "2119", "1457.mvt"))
            assertEquals(37392, reply.body!!.size)
            assertEquals("application/x-protobuf", reply.contentType)
            // Hors de la zone téléchargée, ou dans une couche sans pack : « introuvable », pas d'erreur.
            assertNull(router(tiles).reply(listOf("fond", "12", "10", "10.mvt")).body)
            assertNull(router(tiles).reply(listOf("velo", "12", "2119", "1457.mvt")).body)
            assertNull(router(tiles).reply(listOf("relief", "12", "2119", "1457.webp")).body)
        }
    }

    @Test
    fun `les adresses farfelues sont refusees`() {
        val router = router(OfflineTiles.NONE)
        assertNull(router.reply(emptyList()).body)
        assertNull(router.reply(listOf("fond", "12", "2119")).body)
        assertNull(router.reply(listOf("fond", "douze", "1", "1.mvt")).body)
        assertNull(router.reply(listOf("fond", "-1", "1", "1.mvt")).body)
        assertNull(router.reply(listOf("ailleurs", "1", "2", "3")).body)
        assertNull(router.reply(listOf("sprites", "..", "secret")).body)
    }

    @Test
    fun `les polices embarquees sont servies et une plage absente donne une reponse vide`() {
        val asked = ArrayList<String>()
        val glyphs = byteArrayOf(10, 1, 65)
        val router = router(OfflineTiles.NONE, mapOf("polices/noto-sans-regular/0-255.pbf" to glyphs), asked)

        assertArrayEquals(glyphs, router.reply(listOf("polices", "Noto Sans Regular", "0-255.pbf")).body)
        // Une liste de polices : la première fait foi.
        assertArrayEquals(glyphs, router.reply(listOf("polices", "Noto Sans Regular,Arial Unicode MS", "0-255.pbf")).body)
        // Plage non embarquée (écriture non latine) : réponse valide mais vide, la carte ne reste pas bloquée.
        val missing = router.reply(listOf("polices", "Noto Sans Regular", "1024-1279.pbf"))
        assertNotNull(missing.body)
        assertEquals(0, missing.body!!.size)
        // Chaque fichier n'est lu qu'une fois.
        assertEquals(listOf("polices/noto-sans-regular/0-255.pbf", "polices/noto-sans-regular/1024-1279.pbf"), asked)
        // Un nom de police qui chercherait à sortir du dossier est refusé.
        assertNull(router.reply(listOf("polices", "../../x", "0-255.pbf")).body)
    }

    @Test
    fun `les icones embarquees sont servies avec le bon type`() {
        val router = router(OfflineTiles.NONE, mapOf("sprites/light@2x.png" to byteArrayOf(1), "sprites/light@2x.json" to byteArrayOf(2)))
        assertEquals("image/png", router.reply(listOf("sprites", "light@2x.png")).contentType)
        assertEquals("application/json", router.reply(listOf("sprites", "light@2x.json")).contentType)
        assertNull(router.reply(listOf("sprites", "inconnu.png")).body)
    }

    @Test
    fun `le catalogue des cartes est lu et les noms dangereux sont ecartes`() {
        val text = """
            {"version": 1, "date": "2026-11-20", "champ_inconnu": true,
             "france": [{"type": "fond", "nom": "fond-france.pmtiles", "fichier": "fond-france-0a1b2c3d4e.pmtiles", "taille": 27900000, "sha256": "${"a".repeat(64)}"}],
             "dalles": [{"type": "dalle", "nom": "E5_N45.rd5", "fichier": "E5_N45-1122334455.rd5", "taille": 253664862, "sha256": "${"B".repeat(64)}", "modifie": "x"}],
             "regions": [
               {"id": "corse", "nom": "Corse", "emprise": [8.552, 41.367, 9.56, 43.007], "dalles": ["E5_N40"], "fichiers": [
                 {"type": "fond", "nom": "fond-corse.pmtiles", "fichier": "fond-corse-9b7249d742.pmtiles", "taille": 46900000, "sha256": "${"c".repeat(64)}"},
                 {"type": "velo", "nom": "../../evil", "fichier": "velo-corse-1.pmtiles", "taille": 5, "sha256": "${"d".repeat(64)}"},
                 {"type": "relief", "nom": "relief-corse.pmtiles", "fichier": "relief-corse-d860241668.pmtiles", "taille": 31700000, "sha256": "${"e".repeat(64)}"}
               ]},
               {"id": "vide", "nom": "Sans fichier", "emprise": [0, 0, 1, 1], "fichiers": []}
             ]}
        """.trimIndent()
        val catalog = Catalog.parse(text)

        assertEquals("2026-11-20", catalog.date)
        assertEquals("fond-france-0a1b2c3d4e.pmtiles", catalog.france.single().remoteName)
        assertEquals("b".repeat(64), catalog.routing.single().sha256)
        val corse = catalog.regions.single()
        assertEquals("Corse", corse.name)
        assertEquals(listOf("fond-corse.pmtiles", "relief-corse.pmtiles"), corse.files.map { it.name })
        assertEquals(46_900_000L + 31_700_000L, corse.size)
        assertTrue(corse.bounds.contains(42.3, 9.15))
        assertFalse(corse.bounds.contains(45.9, 6.1))
        assertEquals(listOf("E5_N40"), corse.routingTiles)
    }
}
