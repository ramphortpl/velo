package fr.velogo.app.map

import org.junit.Assert.assertEquals
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
}
