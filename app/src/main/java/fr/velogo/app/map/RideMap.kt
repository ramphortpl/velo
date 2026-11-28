package fr.velogo.app.map

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fr.velogo.app.recording.LiveTrack
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon

/**
 * La carte de l'écran « Rouler », quel que soit le moteur qui dessine le fond choisi : MapLibre pour les cartes de
 * VeloGo, de l'IGN et de CyclOSM, le kit de Mapbox pour les siennes.
 *
 * Un seul des deux moteurs existe à la fois. Ce qu'il faut tracer (trace, itinéraires, étapes, flèche) et les
 * réglages de la vue sont donnés aux deux pilotes, qui les gardent en mémoire : celui qui prend la relève les
 * redessine dès que sa carte est prête. Ce qui commande la caméra à un instant donné (recentrer, cadrer) ne va
 * qu'à celui qui est affiché ; au changement de moteur, il passe sa vue et son suivi à l'autre.
 */
@Stable
class RideMapState internal constructor(
    internal val libre: VeloMapState = VeloMapState(),
    internal val mapbox: MapboxMapState = MapboxMapState(),
) : MapPilot {
    /** Le moteur qui dessine en ce moment. */
    var engine by mutableStateOf(MapEngine.MAPLIBRE)
        private set

    private val active: MapPilot get() = if (engine == MapEngine.MAPBOX) mapbox else libre

    override val isFollowing: Boolean get() = active.isFollowing

    override var onLongPress: ((LatLon) -> Unit)? = null
        set(value) {
            field = value
            libre.onLongPress = value
            mapbox.onLongPress = value
        }

    override var onUserMove: (() -> Unit)? = null
        set(value) {
            field = value
            libre.onUserMove = value
            mapbox.onUserMove = value
        }

    override var onRouteTapped: ((Int) -> Unit)? = null
        set(value) {
            field = value
            libre.onRouteTapped = value
            mapbox.onRouteTapped = value
        }

    /** Appelé quand Mapbox refuse d'envoyer le fond affiché : voir [MapboxMapState.onRefused]. */
    var onMapboxRefused: (() -> Unit)? = null
        set(value) {
            field = value
            mapbox.onRefused = value
        }

    /** Change de moteur : celui qui s'en va passe sa vue et son suivi à celui qui arrive. */
    internal fun switchTo(next: MapEngine) {
        if (next == engine) return
        val camera = if (engine == MapEngine.MAPBOX) mapbox.camera() else libre.camera()
        val following = active.isFollowing
        if (next == MapEngine.MAPBOX) mapbox.adopt(camera, following) else libre.adopt(camera, following)
        engine = next
    }

    override fun setVisible(isVisible: Boolean) {
        libre.setVisible(isVisible)
        mapbox.setVisible(isVisible)
    }

    override fun setLiveTrack(track: LiveTrack) {
        libre.setLiveTrack(track)
        mapbox.setLiveTrack(track)
    }

    override fun setRoutes(options: List<List<LatLon>>, selected: Int) {
        libre.setRoutes(options, selected)
        mapbox.setRoutes(options, selected)
    }

    override fun setRouteProgress(segment: Int, position: LatLon) {
        libre.setRouteProgress(segment, position)
        mapbox.setRouteProgress(segment, position)
    }

    override fun clearRouteProgress() {
        libre.clearRouteProgress()
        mapbox.clearRouteProgress()
    }

    override fun setManeuverArrow(points: List<LatLon>) {
        libre.setManeuverArrow(points)
        mapbox.setManeuverArrow(points)
    }

    override fun setPlanMarkers(markers: List<PlanMarker>) {
        libre.setPlanMarkers(markers)
        mapbox.setPlanMarkers(markers)
    }

    override fun center(): LatLon? = active.center()

    override fun updateLocation(newLocation: Location) {
        libre.updateLocation(newLocation)
        mapbox.updateLocation(newLocation)
    }

    override fun follow() = active.follow()

    override fun setHeadingUp(enabled: Boolean) {
        libre.setHeadingUp(enabled)
        mapbox.setHeadingUp(enabled)
    }

    override fun setNavigation(enabled: Boolean) {
        // Le début d'un guidage ramène la carte sur la position : le moteur au repos doit le savoir aussi, il peut
        // prendre la relève en plein guidage. Son propre suivi est repris de l'autre au changement ([switchTo]).
        libre.setNavigation(enabled)
        mapbox.setNavigation(enabled)
    }

    override fun setNavigationInsets(topPx: Int, bottomPx: Int) {
        libre.setNavigationInsets(topPx, bottomPx)
        mapbox.setNavigationInsets(topPx, bottomPx)
    }

    override fun fit(box: BoundingBox, paddingPx: Int, topPx: Int, bottomPx: Int, endPx: Int, animate: Boolean) =
        active.fit(box, paddingPx, topPx, bottomPx, endPx, animate)

    override fun setTopInset(px: Int) {
        libre.setTopInset(px)
        mapbox.setTopInset(px)
    }

    override fun setOrnamentInsets(startPx: Int, bottomPx: Int) {
        libre.setOrnamentInsets(startPx, bottomPx)
        mapbox.setOrnamentInsets(startPx, bottomPx)
    }
}

@Composable
fun rememberRideMapState(): RideMapState = remember { RideMapState() }

/**
 * La carte de l'écran « Rouler » dans Compose : crée la carte du moteur que demande le fond [spec], et remplace
 * l'une par l'autre quand on passe d'un fond de MapLibre à un fond de Mapbox ou l'inverse.
 */
@Composable
fun RideMap(
    state: RideMapState,
    spec: MapStyleSpec,
    colors: MapColors,
    modifier: Modifier = Modifier,
) {
    val engine = spec.layer.engine
    // Avant de créer la nouvelle carte : elle doit savoir d'où reprendre.
    if (state.engine != engine) state.switchTo(engine)
    key(engine) {
        when (engine) {
            MapEngine.MAPLIBRE -> VeloMap(state.libre, spec, colors, modifier)
            MapEngine.MAPBOX -> MapboxMapView(state.mapbox, spec, colors, modifier)
        }
    }
}
