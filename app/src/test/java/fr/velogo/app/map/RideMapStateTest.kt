package fr.velogo.app.map

import android.location.Location
import fr.velogo.app.recording.LiveTrack
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La carte de l'écran « Rouler » à deux moteurs : ce qui passe de l'un à l'autre au changement de fond, et les
 * couches de Mapbox sous lesquelles glisser nos tracés. Aucune carte n'est créée ici : les deux pilotes gardent
 * ce qu'on leur demande tant qu'ils n'en ont pas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RideMapStateTest {

    private val corte = LatLon(42.3094, 9.1490)

    @Test
    fun `la carte commence avec maplibre et en suivi`() {
        val state = RideMapState()
        assertEquals(MapEngine.MAPLIBRE, state.engine)
        assertTrue(state.isFollowing)
        assertNull(state.center())
    }

    @Test
    fun `changer de moteur garde le suivi`() {
        val state = RideMapState()
        state.switchTo(MapEngine.MAPBOX)
        assertEquals(MapEngine.MAPBOX, state.engine)
        assertTrue(state.isFollowing)
        state.switchTo(MapEngine.MAPLIBRE)
        assertTrue(state.isFollowing)
    }

    @Test
    fun `une carte deplacee le reste en changeant de moteur`() {
        val state = RideMapState()
        // Le moteur affiché a quitté le suivi (carte déplacée au doigt) ; celui qui prend la relève ne recentre pas.
        state.libre.adopt(MapCamera(corte, 13.0), following = false)
        assertFalse(state.isFollowing)
        state.switchTo(MapEngine.MAPBOX)
        assertFalse(state.isFollowing)
        // « Recentrer » ne s'adresse qu'au moteur affiché ; l'autre reprendra son état au prochain changement.
        state.follow()
        assertTrue(state.isFollowing)
        assertTrue(state.mapbox.isFollowing)
        state.switchTo(MapEngine.MAPLIBRE)
        assertTrue(state.isFollowing)
    }

    @Test
    fun `les deux moteurs recoivent ce qu'il faut dessiner, sans carte et sans erreur`() {
        val state = RideMapState()
        var pressed: LatLon? = null
        state.onLongPress = { pressed = it }
        state.libre.onLongPress?.invoke(corte)
        assertEquals(corte, pressed)
        pressed = null
        state.mapbox.onLongPress?.invoke(corte)
        assertEquals(corte, pressed)

        val line = listOf(corte, LatLon(42.31, 9.15), LatLon(42.32, 9.16))
        state.setVisible(true)
        state.setLiveTrack(LiveTrack.EMPTY)
        state.setRoutes(listOf(line), 0)
        state.setRouteProgress(1, LatLon(42.315, 9.155))
        state.setManeuverArrow(line)
        state.setPlanMarkers(listOf(PlanMarker(corte, PlanMarker.Kind.START)))
        state.setHeadingUp(true)
        state.setNavigation(true)
        state.setNavigationInsets(300, 400)
        state.setTopInset(180)
        state.setOrnamentInsets(36, 420)
        state.updateLocation(Location("gps").apply { latitude = corte.lat; longitude = corte.lon; bearing = 45f; accuracy = 6f })
        state.fit(BoundingBox(42.30, 9.14, 42.33, 9.17), 80)
        state.clearRouteProgress()
        state.setNavigation(false)
        state.switchTo(MapEngine.MAPBOX)
        state.fit(BoundingBox(42.30, 9.14, 42.33, 9.17), 80)
        state.follow()
        assertTrue(state.isFollowing)
    }

    @Test
    fun `sur un fond mapbox, les traces passent sous le bloc de noms final`() {
        // La fin de la liste des couches du fond « Outdoors » de Mapbox, dans l'ordre où elles sont dessinées :
        // des flèches de sens unique (du texte, pour la carte) au milieu des routes, puis les limites, puis les noms.
        val layers = listOf(
            "land" to "background", "hillshade" to "fill", "contour-line" to "line", "contour-label" to "symbol",
            "road-primary" to "line", "road-oneway-arrow-blue" to "symbol", "bridge-primary" to "line",
            "admin-0-boundary" to "line",
            "road-label" to "symbol", "poi-label" to "symbol", "settlement-major-label" to "symbol", "country-label" to "symbol",
        )
        assertEquals("road-label", MapboxMapState.labelsAnchor(layers))
    }

    @Test
    fun `le repere de position et nos propres couches ne comptent pas pour trouver les noms`() {
        val layers = listOf(
            "land" to "background", "road-primary" to "line", "road-label" to "symbol", "country-label" to "symbol",
            "velogo-manoeuvre-trait" to "line", "velogo-etapes-numeros" to "symbol",
            "mapbox-location-indicator-layer" to "location-indicator",
        )
        assertEquals("road-label", MapboxMapState.labelsAnchor(layers))
    }

    @Test
    fun `un fond sans noms a la fin recoit les traces tout en haut`() {
        assertNull(MapboxMapState.labelsAnchor(listOf("fond" to "background", "satellite" to "raster")))
        assertNull(MapboxMapState.labelsAnchor(emptyList()))
    }
}
