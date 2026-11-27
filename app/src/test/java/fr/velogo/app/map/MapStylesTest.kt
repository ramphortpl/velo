package fr.velogo.app.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Les styles de carte embarqués dans l'appli (fabriqués par cartes/style) : ce que MapLibre refuserait au
 * chargement, ou afficherait de travers, se voit ici sans téléphone.
 */
class MapStylesTest {

    /** Le dossier des fichiers embarqués, d'où que les tests soient lancés (racine du projet ou dossier du module). */
    private val assets: File = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun style(family: String, theme: String): JsonObject =
        Json.parseToJsonElement(File(assets, "$family/style-$theme.json").readText()).jsonObject

    private val families = listOf("en-ligne", "hors-ligne")
    private val themes = listOf("clair", "sombre")

    private fun layers(style: JsonObject): List<JsonObject> = style.getValue("layers").jsonArray.map { it.jsonObject }

    /** Les polices qu'une couche nomme en toutes lettres (celles choisies par une expression ne sont pas comptées). */
    private fun fonts(style: JsonObject): Set<String> = layers(style).flatMap { layer ->
        val font = layer["layout"]?.jsonObject?.get("text-font") as? JsonArray ?: return@flatMap emptyList()
        if (font.all { it is JsonPrimitive && it.isString }) font.map { it.jsonPrimitive.content } else emptyList()
    }.toSet()

    @Test
    fun `chaque style est complet - version, sources, couches sans doublon`() {
        for (family in families) for (theme in themes) {
            val style = style(family, theme)
            val name = "$family/$theme"
            assertEquals(name, 8, style.getValue("version").jsonPrimitive.content.toInt())
            val sources = style.getValue("sources").jsonObject.keys
            val layers = layers(style)
            assertTrue("$name : ${layers.size} couches", layers.size > 60)
            assertEquals("$name : couches en double", layers.size, layers.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
            assertEquals("$name : la première couche est le fond", "background", layers.first().getValue("type").jsonPrimitive.content)
            for (layer in layers) {
                val source = layer["source"]?.jsonPrimitive?.content ?: continue
                assertTrue("$name : la couche ${layer["id"]} cite la source inconnue « $source »", source in sources)
            }
            assertTrue("$name : le relief est ombré", layers.any { it.getValue("type").jsonPrimitive.content == "hillshade" })
        }
    }

    @Test
    fun `clair et sombre ont les memes couches`() {
        for (family in families) {
            assertEquals(
                family,
                layers(style(family, "clair")).map { it.getValue("id").jsonPrimitive.content },
                layers(style(family, "sombre")).map { it.getValue("id").jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `la carte en ligne ne s adresse qu a openfreemap et a mapterhorn`() {
        for (theme in themes) {
            val style = style("en-ligne", theme)
            val sources = style.getValue("sources").jsonObject
            assertEquals("https://tiles.openfreemap.org/planet", sources.getValue("openmaptiles").jsonObject.getValue("url").jsonPrimitive.content)
            val relief = sources.getValue("relief").jsonObject
            assertEquals(listOf("https://tiles.mapterhorn.com/{z}/{x}/{y}.webp"), relief.getValue("tiles").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("terrarium", relief.getValue("encoding").jsonPrimitive.content)
            assertEquals(setOf("openmaptiles", "relief"), sources.keys)
            assertEquals("https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf", style.getValue("glyphs").jsonPrimitive.content)
            // Pas de planche d'icônes à aller chercher : les points utiles sont de simples pastilles.
            assertNull(style["sprite"])
            // Les trois polices que sert OpenFreeMap, et pas d'autre : une police absente donne des noms vides.
            assertTrue("${fonts(style)}", setOf("Noto Sans Regular", "Noto Sans Bold", "Noto Sans Italic").containsAll(fonts(style)))
        }
    }

    @Test
    fun `la carte hors ligne ne demande rien au reseau`() {
        for (theme in themes) {
            val text = File(assets, "hors-ligne/style-$theme.json").readText()
            val hosts = Regex("https?://([^/\"]+)").findAll(text).map { it.groupValues[1] }.toSet()
            assertEquals(setOf("hors-ligne.velogo.invalid"), hosts)
            // Polices et icônes sont dans l'appli.
            val style = style("hors-ligne", theme)
            val embedded = File(assets, "hors-ligne/polices").list()!!.toSet()
            for (font in fonts(style)) {
                val folder = font.lowercase().replace(' ', '-')
                assertTrue("police « $font » absente de l'appli", folder in embedded)
            }
            val sprite = style.getValue("sprite").jsonPrimitive.content.substringAfterLast('/')
            assertTrue(File(assets, "hors-ligne/sprites/$sprite.json").isFile)
            assertTrue(File(assets, "hors-ligne/sprites/$sprite@2x.png").isFile)
        }
    }

    @Test
    fun `les fonds vectoriels designent des styles embarques`() {
        for (layer in listOf(MapLayers.OPENFREEMAP, MapLayers.OFFLINE)) {
            for (uri in listOf(layer.lightStyleUri, layer.darkStyleUri)) {
                assertNotNull(uri)
                assertTrue(uri!!, uri.startsWith("asset://"))
                assertTrue("$uri n'est pas dans l'appli", File(assets, uri.removePrefix("asset://")).isFile)
            }
        }
        assertFalse(MapLayers.OPENFREEMAP.offline)
        assertTrue(MapLayers.OFFLINE.offline)
    }

    @Test
    fun `le relief en ligne est credite`() {
        // Les tuiles de relief viennent de Mapterhorn (données de l'IGN pour la France) : mention sur la carte.
        assertTrue("Mapterhorn" in MapLayers.OPENFREEMAP.attribution)
    }
}
