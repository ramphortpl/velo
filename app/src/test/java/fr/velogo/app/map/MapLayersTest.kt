package fr.velogo.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les mentions que les fournisseurs de cartes exigent d'une appli publique : visibles sur la carte, et menant à
 * la page de leurs droits.
 */
class MapLayersTest {

    @Test
    fun `chaque fond a sa mention et la page de ses droits`() {
        for (layer in MapLayers.ALL) {
            assertTrue("${layer.id} : mention absente", layer.attribution.startsWith("©"))
            assertTrue("${layer.id} : page des droits absente", layer.attributionUrl.startsWith("https://"))
        }
    }

    @Test
    fun `les fonds tires d'openstreetmap le disent et renvoient a sa page de droits`() {
        for (layer in listOf(MapLayers.OPENFREEMAP, MapLayers.CYCLOSM, MapLayers.OFFLINE)) {
            assertTrue("${layer.id} : « © OpenStreetMap » manque", "© OpenStreetMap" in layer.attribution)
            assertEquals("https://www.openstreetmap.org/copyright", layer.attributionUrl)
        }
    }

    @Test
    fun `le plan en ligne porte la mention qu'openfreemap rend obligatoire`() {
        // « You must add the following attribution: OpenFreeMap © OpenMapTiles Data from OpenStreetMap. You do
        // not need to display the OpenFreeMap part » (openfreemap.org, relevé le 26/11/2026).
        assertTrue("© OpenMapTiles" in MapLayers.OPENFREEMAP.attribution)
    }

    @Test
    fun `les fonds de l'ign le nomment`() {
        for (layer in listOf(MapLayers.PLAN_IGN, MapLayers.ORTHO_IGN)) {
            assertTrue("IGN" in layer.attribution)
            assertEquals("https://cartes.gouv.fr", layer.attributionUrl)
        }
    }

    @Test
    fun `les fonds de mapbox passent par son kit et portent ses mentions`() {
        for (layer in listOf(MapLayers.MAPBOX, MapLayers.MAPBOX_SATELLITE)) {
            assertEquals(MapEngine.MAPBOX, layer.engine)
            // Les conditions de Mapbox : « © Mapbox », « © OpenStreetMap », et un lien vers sa page « about/maps ».
            assertTrue("© Mapbox" in layer.attribution)
            assertTrue("© OpenStreetMap" in layer.attribution)
            assertEquals("https://www.mapbox.com/about/maps/", layer.attributionUrl)
            // Un style hébergé par Mapbox, que seul son kit sait charger ; pas de tuiles prises à part.
            assertTrue(layer.lightStyleUri!!.startsWith("mapbox://styles/mapbox/"))
            assertTrue(layer.rasterTiles.isEmpty())
            assertFalse(layer.offline)
        }
        assertTrue(MapLayers.MAPBOX.darkStyleUri!!.startsWith("mapbox://styles/mapbox/"))
    }

    @Test
    fun `tous les autres fonds restent dessines par maplibre`() {
        val mapbox = MapLayers.ALL.filter { it.engine == MapEngine.MAPBOX }
        assertEquals(listOf(MapLayers.MAPBOX, MapLayers.MAPBOX_SATELLITE), mapbox)
        for (layer in MapLayers.ALL - mapbox.toSet()) {
            for (uri in listOfNotNull(layer.lightStyleUri, layer.darkStyleUri)) assertFalse("$uri : réservé au kit de Mapbox", uri.startsWith("mapbox://"))
        }
    }

    @Test
    fun `les fonds de mapbox ne sont proposes que si l'appli peut les afficher`() {
        assertEquals(MapLayers.ALL, MapLayers.offered(mapbox = true))
        val without = MapLayers.offered(mapbox = false)
        assertTrue(without.none { it.engine == MapEngine.MAPBOX })
        assertEquals(MapLayers.ALL.size - 2, without.size)
        // Le plan de VeloGo reste le premier de la liste, et le fond d'une première installation.
        assertEquals(MapLayers.OPENFREEMAP, without.first())
        assertEquals(MapLayers.OPENFREEMAP, MapLayers.offered(mapbox = true).first())
        assertEquals(MapLayers.OPENFREEMAP, MapLayers.byId(fr.velogo.app.data.Settings().mapLayer))
    }

    @Test
    fun `un fond mapbox choisi mais non affichable laisse la place au plan`() {
        // Sans jeton, sans accord, ou là où l'appli ne dessine qu'avec MapLibre (détail d'une sortie, affiche).
        assertEquals(MapLayers.OPENFREEMAP, MapLayers.effective("mapbox", 1, offlineInstalled = true))
        assertEquals(MapLayers.OPENFREEMAP, MapLayers.effective("mapbox_satellite", 1, offlineInstalled = false, mapbox = false))
        assertEquals(MapLayers.MAPBOX, MapLayers.effective("mapbox", 1, offlineInstalled = false, mapbox = true))
        assertEquals(MapLayers.MAPBOX_SATELLITE, MapLayers.effective("mapbox_satellite", 0, offlineInstalled = false, mapbox = true))
        // Les autres fonds ne sont pas concernés.
        assertEquals(MapLayers.CYCLOSM, MapLayers.effective("cyclosm", 1, offlineInstalled = false, mapbox = true))
        assertEquals(MapLayers.OPENFREEMAP, MapLayers.effective("hors_ligne", 1, offlineInstalled = false, mapbox = true))
        assertEquals(MapLayers.OFFLINE, MapLayers.effective("hors_ligne", 1, offlineInstalled = true))
    }

    @Test
    fun `l'accord pour mapbox n'est pas donne d'avance`() {
        assertFalse(fr.velogo.app.data.Settings().mapboxConsent)
    }
}
