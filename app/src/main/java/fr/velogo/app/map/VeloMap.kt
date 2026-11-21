package fr.velogo.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.velogo.app.recording.LiveTrack
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** Couleurs des éléments que VeloGo dessine sur la carte. */
data class MapColors(
    val track: Int,
    val trackCasing: Int,
    val puck: Int,
    val cycleway: Int,
    val start: Int,
    val end: Int,
    /** Itinéraire choisi (ou suivi). */
    val route: Int = 0xFF1565C0.toInt(),
    /** Autres itinéraires proposés. */
    val routeOther: Int = 0xFF78909C.toInt(),
    /** Étapes d'un itinéraire. */
    val via: Int = 0xFFEF6C00.toInt(),
)

/** Un point d'itinéraire à dessiner sur la carte. */
data class PlanMarker(val position: LatLon, val kind: Kind) {
    enum class Kind { START, VIA, END }
}

/**
 * Pilote d'une carte : c'est par lui que les écrans changent le fond, dessinent une trace, déplacent la position.
 *
 * La carte efface tout ce qu'on y a ajouté à chaque changement de fond : cet objet garde donc en mémoire ce qui
 * doit être affiché et le redessine dès que le nouveau fond est chargé.
 */
@Stable
class VeloMapState {
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var context: Context? = null
    private var spec: MapStyleSpec? = null
    private var colors: MapColors? = null

    /** Vrai tant que la carte suit la position ; passe à faux dès qu'on la déplace au doigt. */
    var isFollowing by mutableStateOf(true)
        private set

    var isStyleLoaded by mutableStateOf(false)
        private set

    private var headingUp = false
    private var visible = true
    private var started = false
    private var location: Location? = null
    private var locationShown = false
    private var live: LiveTrack = LiveTrack.EMPTY
    private var appliedChunks = -1
    private var ride: List<List<LatLon>> = emptyList()
    private var marker: LatLon? = null
    private var routes: List<List<LatLon>> = emptyList()
    private var routeSelected = 0
    private var planMarkers: List<PlanMarker> = emptyList()
    private var pendingFit: Fit? = null

    /** Appelé quand on appuie longuement sur la carte (pour y placer un point d'itinéraire). */
    var onLongPress: ((LatLon) -> Unit)? = null
    private var topInsetPx = 0

    private val trackingListener = object : OnCameraTrackingChangedListener {
        override fun onCameraTrackingDismissed() {
            isFollowing = false
        }

        override fun onCameraTrackingChanged(currentMode: Int) = Unit
    }

    // --- Cycle de vie (appelé par le composable) ------------------------------------------------------------

    /** La vue existe (mais la carte n'est pas encore prête) : on peut déjà lui appliquer démarrage et arrêt. */
    internal fun bindView(view: MapView) {
        mapView = view
        applyRendering()
    }

    internal fun attach(map: MapLibreMap, context: Context) {
        this.map = map
        this.context = context.applicationContext
        map.uiSettings.apply {
            isLogoEnabled = false
            // Les mentions obligatoires sont affichées en clair par l'écran, dans un coin de la carte.
            isAttributionEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = true
            setCompassFadeFacingNorth(true)
        }
        applyCompassMargins()
        // France uniquement : on ne peut pas faire glisser la carte ailleurs.
        val f = Geo.FRANCE
        map.setLatLngBoundsForCameraTarget(LatLngBounds.from(f.maxLat + 0.4, f.maxLon + 0.4, f.minLat - 0.4, f.minLon - 0.4))
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
        map.addOnMapLongClickListener { point ->
            val listener = onLongPress
            listener?.invoke(LatLon(point.latitude, point.longitude))
            listener != null
        }
        spec?.let { loadStyle(it) }
    }

    internal fun detach() {
        if (listenerAdded) runCatching { map?.locationComponent?.removeOnCameraTrackingChangedListener(trackingListener) }
        listenerAdded = false
        rendering = false
        mapView = null
        map = null
        style = null
        context = null
        isStyleLoaded = false
        locationShown = false
        appliedChunks = -1
    }

    internal fun onHostStarted(isStarted: Boolean) {
        started = isStarted
        applyRendering()
    }

    /**
     * Carte masquée par un autre panneau (écran des chiffres) : on arrête son rendu pour économiser la batterie,
     * sans la détruire, afin qu'elle réapparaisse instantanément.
     */
    fun setVisible(isVisible: Boolean) {
        if (visible == isVisible) return
        visible = isVisible
        applyRendering()
    }

    private var rendering = false
    private var listenerAdded = false

    private fun applyRendering() {
        val view = mapView ?: return
        val shouldRender = started && visible
        if (shouldRender == rendering) return
        rendering = shouldRender
        if (shouldRender) {
            view.onStart(); view.onResume()
            // Rattrapage de ce qui a changé pendant que la carte était masquée.
            applyLiveTrack()
            location?.let { pushLocation(it) }
        } else {
            view.onPause(); view.onStop()
        }
    }

    // --- Fond de carte ----------------------------------------------------------------------------------------

    fun setStyle(spec: MapStyleSpec, colors: MapColors) {
        val sameStyle = this.spec == spec && this.colors == colors
        this.spec = spec
        this.colors = colors
        if (!sameStyle || style == null) loadStyle(spec)
    }

    private fun loadStyle(spec: MapStyleSpec) {
        val map = map ?: return
        style = null
        isStyleLoaded = false
        locationShown = false
        appliedChunks = -1
        MapInit.setOfflineLayer(spec.layer.offline)
        val uri = spec.uri
        val builder = if (uri != null) Style.Builder().fromUri(uri) else Style.Builder().fromJson(spec.json.orEmpty())
        map.setStyle(builder) { loaded ->
            // Un autre fond a pu être demandé entre-temps : seul le dernier compte.
            if (this.spec != spec) return@setStyle
            style = loaded
            installOverlays(loaded, spec)
            isStyleLoaded = true
            applyRoutes()
            applyPlanMarkers()
            applyRide()
            applyLiveTrack()
            applyMarker()
            location?.let { pushLocation(it) }
            pendingFit?.let { fit(it.box, it.paddingPx, it.topPx, it.bottomPx) }
        }
    }

    private fun installOverlays(style: Style, spec: MapStyleSpec) {
        val c = colors ?: return
        val labels = firstLabelLayerId(style)
        if (spec.layer.highlightCycleways) addCycleways(style, c, labels)

        // Itinéraires : dessinés sous la trace de la sortie, qui doit rester lisible par-dessus.
        style.addSource(GeoJsonSource(SRC_ROUTES_OTHER, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addSource(GeoJsonSource(SRC_ROUTE, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addSource(GeoJsonSource(SRC_PLAN, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addLayer(line("$SRC_ROUTES_OTHER-liseré", SRC_ROUTES_OTHER, c.trackCasing, ROUTE_WIDTH + 2.5f))
        style.addLayer(line("$SRC_ROUTES_OTHER-trait", SRC_ROUTES_OTHER, c.routeOther, ROUTE_WIDTH - 1f))
        style.addLayer(line("$SRC_ROUTE-liseré", SRC_ROUTE, c.trackCasing, ROUTE_WIDTH + 3.5f))
        style.addLayer(line("$SRC_ROUTE-trait", SRC_ROUTE, c.route, ROUTE_WIDTH))

        style.addSource(GeoJsonSource(SRC_RIDE, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addSource(GeoJsonSource(SRC_FROZEN, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addSource(GeoJsonSource(SRC_TAIL, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addSource(GeoJsonSource(SRC_ENDS, FeatureCollection.fromFeatures(emptyList<Feature>())))
        style.addSource(GeoJsonSource(SRC_MARKER, FeatureCollection.fromFeatures(emptyList<Feature>())))

        // Chaque trace est dessinée deux fois : un liseré clair dessous, la couleur dessus (lisible sur tous les fonds).
        for (source in listOf(SRC_RIDE, SRC_FROZEN, SRC_TAIL)) {
            style.addLayer(line("$source-liseré", source, c.trackCasing, TRACK_WIDTH + 3f))
        }
        for (source in listOf(SRC_RIDE, SRC_FROZEN, SRC_TAIL)) {
            style.addLayer(line("$source-trait", source, c.track, TRACK_WIDTH))
        }
        style.addLayer(
            CircleLayer("$SRC_ENDS-points", SRC_ENDS).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(
                    Expression.match(Expression.get("kind"), Expression.literal("start"), Expression.color(c.start), Expression.color(c.end))
                ),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
            )
        )
        style.addLayer(
            CircleLayer("$SRC_PLAN-points", SRC_PLAN).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("kind"),
                        Expression.literal("START"), Expression.color(c.start),
                        Expression.literal("VIA"), Expression.color(c.via),
                        Expression.color(c.end),
                    )
                ),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
        style.addLayer(
            CircleLayer("$SRC_MARKER-point", SRC_MARKER).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeColor(c.track),
                PropertyFactory.circleStrokeWidth(4f),
            )
        )
    }

    private fun line(id: String, source: String, color: Int, width: Float): LineLayer =
        LineLayer(id, source).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )

    /** Surligne les pistes cyclables et voies vertes du fond vectoriel (schéma OpenMapTiles). */
    private fun addCycleways(style: Style, c: MapColors, belowLayerId: String?) {
        if (style.getSource(OPENMAPTILES_SOURCE) == null) return
        val filter = Expression.all(
            Expression.eq(Expression.get("class"), Expression.literal("path")),
            Expression.any(
                Expression.eq(Expression.get("subclass"), Expression.literal("cycleway")),
                Expression.eq(Expression.get("bicycle"), Expression.literal("designated")),
            ),
        )
        // Une expression de zoom doit rester au premier niveau : on écrit donc deux échelles plutôt qu'un produit.
        fun widthAt(factor: Float) = Expression.interpolate(
            Expression.exponential(1.5f), Expression.zoom(),
            Expression.stop(11, 0.8f * factor), Expression.stop(14, 2.2f * factor),
            Expression.stop(17, 4.5f * factor), Expression.stop(20, 10f * factor),
        )
        val casing = LineLayer("velogo-pistes-liseré", OPENMAPTILES_SOURCE).withProperties(
            PropertyFactory.lineColor(Color.WHITE),
            PropertyFactory.lineOpacity(0.75f),
            PropertyFactory.lineWidth(widthAt(1.8f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val fill = LineLayer("velogo-pistes", OPENMAPTILES_SOURCE).withProperties(
            PropertyFactory.lineColor(c.cycleway),
            PropertyFactory.lineWidth(widthAt(1f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        for (layer in listOf(casing, fill)) {
            layer.sourceLayer = "transportation"
            layer.setFilter(filter)
            layer.minZoom = 11f
            if (belowLayerId != null) style.addLayerBelow(layer, belowLayerId) else style.addLayer(layer)
        }
    }

    /** Première couche d'étiquettes du fond : nos surlignages se glissent juste en dessous des noms. */
    private fun firstLabelLayerId(style: Style): String? =
        style.layers.firstOrNull { it is SymbolLayer && !it.textField.isNull }?.id

    // --- Ce qu'on dessine -------------------------------------------------------------------------------------

    /** Trace de la sortie en cours. */
    fun setLiveTrack(track: LiveTrack) {
        live = track
        applyLiveTrack()
    }

    private fun applyLiveTrack() {
        val style = style ?: return
        if (!rendering) return
        if (appliedChunks != live.chunks.size) {
            val lines = live.chunks.map { chunk -> LineString.fromLngLats(chunk.points.map { it.toPoint() }) }
            (style.getSource(SRC_FROZEN) as? GeoJsonSource)?.setGeoJson(
                if (lines.isEmpty()) FeatureCollection.fromFeatures(emptyList<Feature>())
                else FeatureCollection.fromFeature(Feature.fromGeometry(MultiLineString.fromLineStrings(lines)))
            )
            appliedChunks = live.chunks.size
        }
        (style.getSource(SRC_TAIL) as? GeoJsonSource)?.setGeoJson(
            if (live.tail.size < 2) FeatureCollection.fromFeatures(emptyList<Feature>())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(live.tail.map { it.toPoint() })))
        )
    }

    /** Trace d'une sortie terminée (un trait par tronçon), avec ses points de départ et d'arrivée. */
    fun setRide(segments: List<List<LatLon>>) {
        ride = segments
        applyRide()
    }

    private fun applyRide() {
        val style = style ?: return
        val lines = ride.filter { it.size >= 2 }.map { seg -> LineString.fromLngLats(seg.map { it.toPoint() }) }
        (style.getSource(SRC_RIDE) as? GeoJsonSource)?.setGeoJson(
            if (lines.isEmpty()) FeatureCollection.fromFeatures(emptyList<Feature>())
            else FeatureCollection.fromFeature(Feature.fromGeometry(MultiLineString.fromLineStrings(lines)))
        )
        val first = ride.firstOrNull { it.isNotEmpty() }?.first()
        val last = ride.lastOrNull { it.isNotEmpty() }?.last()
        val ends = ArrayList<Feature>()
        if (first != null && last != null) {
            ends.add(Feature.fromGeometry(last.toPoint()).apply { addStringProperty("kind", "end") })
            ends.add(Feature.fromGeometry(first.toPoint()).apply { addStringProperty("kind", "start") })
        }
        (style.getSource(SRC_ENDS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(ends))
    }

    /** Itinéraires proposés : celui de rang [selected] est mis en avant, les autres restent en retrait. */
    fun setRoutes(options: List<List<LatLon>>, selected: Int) {
        routes = options
        routeSelected = selected
        applyRoutes()
    }

    private fun applyRoutes() {
        val style = style ?: return
        fun collection(lines: List<List<LatLon>>) =
            if (lines.isEmpty()) FeatureCollection.fromFeatures(emptyList<Feature>())
            else FeatureCollection.fromFeature(
                Feature.fromGeometry(MultiLineString.fromLineStrings(lines.map { line -> LineString.fromLngLats(line.map { it.toPoint() }) }))
            )
        val main = routes.getOrNull(routeSelected)?.takeIf { it.size >= 2 }
        val others = routes.filterIndexed { i, line -> i != routeSelected && line.size >= 2 }
        (style.getSource(SRC_ROUTES_OTHER) as? GeoJsonSource)?.setGeoJson(collection(others))
        (style.getSource(SRC_ROUTE) as? GeoJsonSource)?.setGeoJson(collection(listOfNotNull(main)))
    }

    /** Départ, étapes et arrivée d'un itinéraire en préparation. */
    fun setPlanMarkers(markers: List<PlanMarker>) {
        planMarkers = markers
        applyPlanMarkers()
    }

    private fun applyPlanMarkers() {
        val style = style ?: return
        val features = planMarkers.map { m -> Feature.fromGeometry(m.position.toPoint()).apply { addStringProperty("kind", m.kind.name) } }
        (style.getSource(SRC_PLAN) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Centre de la carte affichée (pour chercher une adresse « près d'ici »), ou null si elle n'est pas prête. */
    fun center(): LatLon? = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }

    /** Repère mobile (point survolé sur un graphique), ou null pour l'effacer. */
    fun setMarker(position: LatLon?) {
        marker = position
        applyMarker()
    }

    private fun applyMarker() {
        val style = style ?: return
        val m = marker
        (style.getSource(SRC_MARKER) as? GeoJsonSource)?.setGeoJson(
            if (m == null) FeatureCollection.fromFeatures(emptyList<Feature>())
            else FeatureCollection.fromFeature(Feature.fromGeometry(m.toPoint()))
        )
    }

    // --- Position et caméra -----------------------------------------------------------------------------------

    /** Nouvelle position GPS : déplace le repère et, si le suivi est actif, la carte. */
    fun updateLocation(newLocation: Location) {
        location = newLocation
        if (rendering) {
            pushLocation(newLocation)
            applyLiveTrack()
        }
    }

    @SuppressLint("MissingPermission")
    private fun pushLocation(l: Location) {
        val map = map ?: return
        val style = style ?: return
        val ctx = context ?: return
        val c = colors ?: return
        val component = map.locationComponent
        if (!locationShown) {
            val options = LocationComponentOptions.builder(ctx)
                .pulseEnabled(false)
                .accuracyAlpha(0.16f)
                .accuracyColor(c.puck)
                .foregroundTintColor(c.puck)
                .bearingTintColor(c.puck)
                .backgroundTintColor(Color.WHITE)
                // Repère plus gros que d'origine : il doit se voir d'un coup d'œil sur un guidon, en plein jour.
                .minZoomIconScale(PUCK_SCALE_FAR)
                .maxZoomIconScale(PUCK_SCALE_NEAR)
                .trackingGesturesManagement(true)
                .build()
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(ctx, style)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(options)
                    .build()
            )
            component.isLocationComponentEnabled = true
            if (!listenerAdded) {
                component.addOnCameraTrackingChangedListener(trackingListener)
                listenerAdded = true
            }
            // Animation du repère plafonnée : fluide à l'œil, deux à trois fois moins de travail pour le téléphone.
            component.setMaxAnimationFps(PUCK_FPS)
            locationShown = true
            component.forceLocationUpdate(l)
            if (isFollowing) component.setCameraMode(trackingMode(), 600L, FOLLOW_ZOOM, null, null, null)
        } else {
            component.forceLocationUpdate(l)
        }
        // Sans boussole, le sens de marche ne vient que du déplacement : flèche quand on roule, point sinon.
        val moving = l.hasBearing() && l.hasSpeed() && l.speed >= MOVING_SPEED_MPS
        val wanted = if (moving) RenderMode.GPS else RenderMode.NORMAL
        if (component.renderMode != wanted) component.renderMode = wanted
    }

    private fun trackingMode(): Int = if (headingUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING

    /** Recentre la carte sur la position et reprend le suivi. */
    @SuppressLint("MissingPermission")
    fun follow() {
        isFollowing = true
        val map = map ?: return
        if (!locationShown) return
        val zoom = map.cameraPosition.zoom.coerceAtLeast(FOLLOW_ZOOM)
        map.locationComponent.setCameraMode(trackingMode(), 500L, zoom, if (headingUp) null else 0.0, null, null)
    }

    /** Carte orientée dans le sens de la marche (vrai) ou nord en haut (faux). */
    @SuppressLint("MissingPermission")
    fun setHeadingUp(enabled: Boolean) {
        if (headingUp == enabled) return
        headingUp = enabled
        val map = map ?: return
        if (!locationShown || !isFollowing) return
        map.locationComponent.setCameraMode(trackingMode(), 500L, null, if (enabled) null else 0.0, null, null)
    }

    /** Cadre la carte sur une emprise (par exemple toute une sortie). */
    /**
     * Cadre la carte sur une emprise (par exemple toute une sortie). [topPx] et [bottomPx] réservent la place
     * de ce qui recouvre la carte en haut et en bas (bandeau, panneau d'itinéraire).
     */
    fun fit(box: BoundingBox, paddingPx: Int, topPx: Int = paddingPx, bottomPx: Int = paddingPx) {
        pendingFit = Fit(box, paddingPx, topPx, bottomPx)
        val map = map ?: return
        if (style == null) return
        isFollowing = false
        runCatching { map.locationComponent.cameraMode = CameraMode.NONE }
        val tiny = (box.maxLat - box.minLat) < 1e-4 && (box.maxLon - box.minLon) < 1e-4
        if (tiny) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng((box.minLat + box.maxLat) / 2, (box.minLon + box.maxLon) / 2), 15.0))
        } else {
            val bounds = LatLngBounds.from(box.maxLat, box.maxLon, box.minLat, box.minLon)
            // Si les marges demandées ne laissent pas de place (très petit écran), on retombe sur un cadrage simple.
            runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx, topPx, paddingPx, bottomPx)) }
                .onFailure { runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)) } }
        }
        pendingFit = null
    }

    private class Fit(val box: BoundingBox, val paddingPx: Int, val topPx: Int, val bottomPx: Int)

    fun setTopInset(px: Int) {
        if (topInsetPx == px) return
        topInsetPx = px
        applyCompassMargins()
    }

    private fun applyCompassMargins() {
        val map = map ?: return
        val density = context?.resources?.displayMetrics?.density ?: 2f
        val margin = (12 * density).toInt()
        map.uiSettings.setCompassMargins(margin, topInsetPx + margin, margin, margin)
    }

    private fun LatLon.toPoint(): Point = Point.fromLngLat(lon, lat)

    private companion object {
        const val SRC_RIDE = "velogo-sortie"
        const val SRC_FROZEN = "velogo-trace"
        const val SRC_TAIL = "velogo-trace-fin"
        const val SRC_ENDS = "velogo-extremites"
        const val SRC_MARKER = "velogo-repere"
        const val SRC_ROUTES_OTHER = "velogo-itineraires"
        const val SRC_ROUTE = "velogo-itineraire"
        const val SRC_PLAN = "velogo-etapes"
        const val ROUTE_WIDTH = 6f
        const val OPENMAPTILES_SOURCE = "openmaptiles"
        const val TRACK_WIDTH = 4.5f
        const val MIN_ZOOM = 4.4
        const val MAX_ZOOM = 19.5
        const val FOLLOW_ZOOM = 15.5
        const val PUCK_FPS = 30
        const val PUCK_SCALE_FAR = 1.0f
        const val PUCK_SCALE_NEAR = 1.5f
        const val MOVING_SPEED_MPS = 1.2f
    }
}

@Composable
fun rememberVeloMapState(): VeloMapState = remember { VeloMapState() }

/**
 * La carte MapLibre dans Compose.
 *
 * Elle est affichée dans une SurfaceView (le mode le plus fluide et le plus économe) : ne pas l'animer en
 * transparence, mais on peut poser d'autres éléments par-dessus.
 */
@Composable
fun VeloMap(
    state: VeloMapState,
    spec: MapStyleSpec,
    colors: MapColors,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        MapInit.ensure(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(CameraPosition.Builder().target(LatLng(FRANCE_CENTER_LAT, FRANCE_CENTER_LON)).zoom(FRANCE_ZOOM).build())
            .logoEnabled(false)
            .attributionEnabled(false)
            .tiltGesturesEnabled(false)
        MapView(context, options).also { view ->
            view.onCreate(null)
            state.bindView(view)
            view.getMapAsync { map -> state.attach(map, context) }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.onHostStarted(true)
                Lifecycle.Event.ON_STOP -> state.onHostStarted(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.onHostStarted(false)
            state.detach()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(spec, colors) { state.setStyle(spec, colors) }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val FRANCE_CENTER_LAT = 46.6
private const val FRANCE_CENTER_LON = 2.4
private const val FRANCE_ZOOM = 4.8
