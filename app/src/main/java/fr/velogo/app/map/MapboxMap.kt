package fr.velogo.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import androidx.compose.foundation.layout.Box
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
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.common.Cancelable
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.LayerPosition
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.Style
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.Layer
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconPitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.ProjectionName
import com.mapbox.maps.extension.style.projection.generated.Projection
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.viewport.data.DefaultViewportTransitionOptions
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.maps.plugin.viewport.data.ViewportOptions
import com.mapbox.maps.plugin.viewport.state.FollowPuckViewportState
import com.mapbox.maps.plugin.viewport.viewport
import fr.velogo.app.recording.LiveTrack
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.ln

/**
 * Pilote d'une carte Mapbox : le pendant de [VeloMapState] pour les fonds que seul le kit de Mapbox a le droit
 * d'afficher. Même contrat ([MapPilot]), mêmes tracés (mêmes noms, mêmes couleurs, mêmes épaisseurs), même
 * comportement : l'écran « Rouler » ne sait pas lequel des deux dessine.
 *
 * Ce qui diffère, parce que les deux kits ne s'y prennent pas de la même façon :
 * - la position ne vient jamais de Mapbox : c'est l'appli qui la lui donne ([Provider]), comme à l'autre moteur ;
 * - le suivi de la position est un « état » de la caméra ([FollowPuckViewportState]). Il s'arrête quand on fait
 *   glisser la carte ; zoomer la laisse en suivi, autour de la position ;
 * - le logo de Mapbox et son bouton d'information restent affichés, comme ses conditions le demandent : ce
 *   bouton donne aussi le réglage des statistiques d'utilisation.
 */
@Stable
class MapboxMapState : MapPilot {
    private var mapView: MapView? = null
    private var map: MapboxMap? = null
    private var style: Style? = null
    private var context: Context? = null
    private var spec: MapStyleSpec? = null
    private var colors: MapColors? = null

    /** Les sources de nos tracés dans le fond affiché : elles ne valent que pour lui. */
    private var sources: Map<String, GeoJsonSource> = emptyMap()

    override var isFollowing by mutableStateOf(true)
        private set

    var isStyleLoaded by mutableStateOf(false)
        private set

    private var headingUp = false
    private var visible = true
    private var started = false
    private var rendering = false
    /** Un affichage est en cours pour le fusible de [MapboxSupport] : compté une fois, clos une fois. */
    private var displayOpen = false
    private var location: Location? = null
    /** Le suivi a commencé cadré large, autour d'une position floue : il se resserrera à la première position fine. */
    private var wideFollow = false
    private var locationShown = false
    /** Le repère affiché est la flèche (sens de la marche connu) ou le point ; null tant qu'il n'y en a pas. */
    private var puckIsArrow: Boolean? = null
    private var followState: FollowPuckViewportState? = null
    private var live: LiveTrack = LiveTrack.EMPTY
    private var appliedChunks = -1
    private var routes: List<List<LatLon>> = emptyList()
    private var routeSelected = 0
    private var routeProgress: Pair<Int, LatLon>? = null
    private var appliedDoneChunks = -1
    private var maneuverArrow: List<LatLon> = emptyList()
    private var planMarkers: List<PlanMarker> = emptyList()
    private var pendingFit: Fit? = null

    override var onLongPress: ((LatLon) -> Unit)? = null
    override var onUserMove: (() -> Unit)? = null
    override var onRouteTapped: ((Int) -> Unit)? = null

    /** Vue à reprendre à la prochaine création de la carte (on vient de l'autre moteur), ou null. */
    private var handover: MapCamera? = null
    private var topInsetPx = 0
    private var ornamentStartPx = 0
    private var ornamentBottomPx = 0

    private var navigation = false
    private var navTopPx = 0
    private var navBottomPx = 0

    private var errors: Cancelable? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * La position, telle que l'appli la donne à la carte. Le kit de Mapbox n'écoute donc ni le GPS ni les services
     * de localisation du téléphone : il dessine ce qu'on lui envoie, comme l'autre moteur.
     */
    private inner class Provider : LocationProvider {
        private val consumers = CopyOnWriteArraySet<LocationConsumer>()

        override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
            consumers.add(locationConsumer)
            location?.let { send(locationConsumer, it) }
        }

        override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
            consumers.remove(locationConsumer)
        }

        fun push(l: Location) {
            for (consumer in consumers) send(consumer, l)
        }

        private fun send(consumer: LocationConsumer, l: Location) {
            consumer.onLocationUpdated(Point.fromLngLat(l.longitude, l.latitude))
            if (l.hasBearing()) consumer.onBearingUpdated(l.bearing.toDouble())
            if (l.hasAccuracy()) consumer.onHorizontalAccuracyRadiusUpdated(l.accuracy.toDouble())
        }
    }

    private val provider = Provider()

    private val moveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) {
            onUserMove?.invoke()
            // Faire glisser la carte, c'est vouloir regarder ailleurs : le suivi s'arrête, « Recentrer » y ramène.
            stopFollowing()
        }

        override fun onMove(detector: MoveGestureDetector): Boolean = false

        override fun onMoveEnd(detector: MoveGestureDetector) = Unit
    }

    private val scaleListener = object : OnScaleListener {
        override fun onScaleBegin(detector: StandardScaleGestureDetector) {
            onUserMove?.invoke()
        }

        override fun onScale(detector: StandardScaleGestureDetector) = Unit

        override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
    }

    // --- Cycle de vie (appelé par le composable) ------------------------------------------------------------

    /** Une carte va être créée : à signaler avant, pour qu'un plantage pendant sa création soit compté. */
    internal fun openDisplay(context: Context) {
        if (displayOpen) return
        displayOpen = true
        MapboxSupport.displayStarted(context)
    }

    private fun closeDisplay(context: Context) {
        if (!displayOpen) return
        displayOpen = false
        MapboxSupport.displayEnded(context)
    }

    internal fun bindView(view: MapView, context: Context) {
        val app = context.applicationContext
        mapView = view
        this.context = app
        val map = view.mapboxMap
        this.map = map
        // France uniquement, comme l'autre moteur : on ne peut pas faire glisser la carte ailleurs.
        val f = Geo.FRANCE
        runCatching {
            map.setBounds(
                CameraBoundsOptions.Builder()
                    .bounds(CoordinateBounds(Point.fromLngLat(f.minLon - 0.4, f.minLat - 0.4), Point.fromLngLat(f.maxLon + 0.4, f.maxLat + 0.4)))
                    .minZoom(VeloMapState.MIN_ZOOM)
                    .maxZoom(VeloMapState.MAX_ZOOM)
                    .build()
            )
        }
        handover?.let { camera ->
            runCatching {
                map.setCamera(CameraOptions.Builder().center(camera.center.toPoint()).zoom(camera.zoom).bearing(0.0).pitch(0.0).build())
            }
            handover = null
        }
        val gestures = view.gestures
        gestures.updateSettings { pitchEnabled = false }
        gestures.addOnMapLongClickListener { point ->
            val listener = onLongPress
            listener?.invoke(LatLon(point.latitude(), point.longitude()))
            listener != null
        }
        // Un appui sur un des itinéraires en retrait le choisit. La carte répond plus tard : l'appui n'est pas retenu.
        gestures.addOnMapClickListener { point ->
            if (onRouteTapped != null) {
                val screen = map.pixelForCoordinate(point)
                val reach = VeloMapState.TAP_REACH_DP * view.resources.displayMetrics.density
                val area = ScreenBox(ScreenCoordinate(screen.x - reach, screen.y - reach), ScreenCoordinate(screen.x + reach, screen.y + reach))
                val layers = listOf("${VeloMapState.SRC_ROUTES_OTHER}-trait", "${VeloMapState.SRC_ROUTES_OTHER}-liseré")
                runCatching {
                    map.queryRenderedFeatures(RenderedQueryGeometry(area), RenderedQueryOptions(layers, null)) { found ->
                        val index = found.value?.firstNotNullOfOrNull { it.queriedFeature.feature.getNumberProperty("rang")?.toInt() }
                        if (index != null) main.post { onRouteTapped?.invoke(index) }
                    }
                }
            }
            false
        }
        gestures.addOnMoveListener(moveListener)
        gestures.addOnScaleListener(scaleListener)
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) onResized()
        }
        // Le suivi ne s'arrête que sur notre décision (carte glissée au doigt) : un zoom le laisse en place.
        view.viewport.options = ViewportOptions.Builder().transitionsToIdleUponUserInteraction(false).build()
        view.location.setLocationProvider(provider)
        applyOrnaments()
        errors = map.subscribeMapLoadingError { error ->
            Log.w(TAG, "Carte Mapbox : ${error.type} ${error.message}")
        }
        applyRendering()
        // La création s'est bien passée ; l'affichage ne compte pour le fusible que pendant que la carte est dessinée.
        if (!rendering) closeDisplay(app)
        spec?.let { loadStyle(it) }
    }

    /** La création de la carte a échoué : rien n'est affiché, et le fusible a déjà été réglé par l'appelant. */
    internal fun creationFailed() {
        displayOpen = false
        detach()
    }

    internal fun detach() {
        runCatching { errors?.cancel() }
        errors = null
        context?.let { closeDisplay(it) }
        rendering = false
        followState = null
        sources = emptyMap()
        mapView = null
        map = null
        style = null
        context = null
        isStyleLoaded = false
        locationShown = false
        puckIsArrow = null
        appliedChunks = -1
        appliedDoneChunks = -1
    }

    internal fun onHostStarted(isStarted: Boolean) {
        started = isStarted
        applyRendering()
    }

    override fun setVisible(isVisible: Boolean) {
        if (visible == isVisible) return
        visible = isVisible
        applyRendering()
    }

    // Le kit de Mapbox sait suivre seul le cycle de vie de l'écran, par une brique que cette carte ne charge pas
    // (voir MapboxMapView) : c'est donc bien ici que la carte est démarrée et arrêtée, entre autres pour couper son
    // rendu dès qu'elle est recouverte. Le contrôle de Mapbox qui déconseille ces appels ne s'applique pas.
    @SuppressLint("Lifecycle")
    private fun applyRendering() {
        val view = mapView ?: return
        val shouldRender = started && visible
        if (shouldRender == rendering) return
        rendering = shouldRender
        if (shouldRender) {
            context?.let { openDisplay(it) }
            view.onStart()
            view.onResume()
            // Rattrapage de ce qui a changé pendant que la carte était masquée.
            applyLiveTrack()
            applyRouteProgress()
            location?.let { pushLocation(it) }
        } else {
            view.onStop()
            context?.let { closeDisplay(it) }
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
        val uri = spec.uri ?: return
        style = null
        sources = emptyMap()
        isStyleLoaded = false
        appliedChunks = -1
        appliedDoneChunks = -1
        map.loadStyle(uri) { loaded ->
            // Un autre fond a pu être demandé entre-temps, ou la carte détruite : seul le dernier compte.
            if (this.spec != spec || this.map !== map) return@loadStyle
            style = loaded
            // Carte à plat (les fonds de Mapbox s'ouvrent en globe vus de loin), noms en français.
            runCatching { Projection(ProjectionName.MERCATOR).bindTo(loaded) }
            runCatching { loaded.localizeLabels(Locale.FRENCH) }
            runCatching { installOverlays(loaded) }.onFailure { Log.w(TAG, "Tracés non installés sur la carte Mapbox", it) }
            isStyleLoaded = true
            applyRoutes()
            applyRouteProgress()
            applyManeuverArrow()
            applyPlanMarkers()
            applyLiveTrack()
            // Carte masquée ou à l'arrêt : le repère de position sera posé quand elle sera de nouveau dessinée.
            if (rendering) location?.let { pushLocation(it) }
            pendingFit?.let { fit(it.box, it.paddingPx, it.topPx, it.bottomPx, it.endPx, animate = false) }
        }
    }

    private fun installOverlays(style: Style) {
        val c = colors ?: return
        // Les tracés passent sous les noms du fond : une rue garde son nom lisible par-dessus l'itinéraire.
        val labels = labelsAnchor(style)
        // D'un fond à l'autre, le kit garde ce dont dépend son repère de position : par prudence, on ne pose que
        // ce qui n'est pas déjà là (une source ou une couche en double serait refusée, et tout le reste avec).
        fun addTrace(layer: Layer) {
            if (style.styleLayerExists(layer.layerId)) return
            if (labels != null) style.addLayerBelow(layer, labels) else style.addLayer(layer)
        }
        fun addOnTop(layer: Layer) {
            if (!style.styleLayerExists(layer.layerId)) style.addLayer(layer)
        }
        val added = HashMap<String, GeoJsonSource>()
        for (id in SOURCES) {
            val source = if (style.styleSourceExists(id)) style.getSourceAs<GeoJsonSource>(id)
            else GeoJsonSource.Builder(id).featureCollection(EMPTY).build().also { style.addSource(it) }
            if (source != null) added[id] = source
        }
        sources = added

        // Mêmes couches, mêmes noms et même ordre que sur l'autre moteur (VeloMapState.installOverlays).
        addTrace(line("${VeloMapState.SRC_ROUTES_OTHER}-liseré", VeloMapState.SRC_ROUTES_OTHER, c.trackCasing, routeWidth(2.5, 0.85)))
        addTrace(line("${VeloMapState.SRC_ROUTES_OTHER}-trait", VeloMapState.SRC_ROUTES_OTHER, c.routeOther, routeWidth(0.0, 0.85)))
        addTrace(line("${VeloMapState.SRC_ROUTE}-liseré", VeloMapState.SRC_ROUTE, c.routeCasing, routeWidth(3.0, 1.0)))
        addTrace(line("${VeloMapState.SRC_ROUTE}-trait", VeloMapState.SRC_ROUTE, c.route, routeWidth(0.0, 1.0)))
        for (source in listOf(VeloMapState.SRC_DONE_FROZEN, VeloMapState.SRC_DONE_TAIL)) {
            addTrace(line("$source-trait", source, c.routeDone, routeWidth(0.0, 1.0)))
        }
        val track = VeloMapState.TRACK_WIDTH.toDouble()
        for (source in listOf(VeloMapState.SRC_FROZEN, VeloMapState.SRC_TAIL)) {
            addTrace(line("$source-liseré", source, c.trackCasing, Expression.literal(track + 3.0)))
        }
        for (source in listOf(VeloMapState.SRC_FROZEN, VeloMapState.SRC_TAIL)) {
            addTrace(line("$source-trait", source, c.track, Expression.literal(track)))
        }

        // La prochaine manœuvre, en flèche sur le tracé : par-dessus tout, noms compris.
        val arrowZoom = VeloMapState.ARROW_MIN_ZOOM.toDouble()
        addOnTop(line("${VeloMapState.SRC_ARROW}-liseré", VeloMapState.SRC_ARROW, c.arrowCasing, routeWidth(3.5, 0.72)).minZoom(arrowZoom))
        addOnTop(line("${VeloMapState.SRC_ARROW}-trait", VeloMapState.SRC_ARROW, c.arrow, routeWidth(0.0, 0.72)).minZoom(arrowZoom))
        val ctx = context
        if (ctx != null) {
            style.addImage(VeloMapState.ARROW_IMAGE, arrowHeadImage(ctx, c))
            addOnTop(
                SymbolLayer("${VeloMapState.SRC_ARROW_HEAD}-pointe", VeloMapState.SRC_ARROW_HEAD)
                    .iconImage(VeloMapState.ARROW_IMAGE)
                    .iconRotate(Expression.get(VeloMapState.ARROW_BEARING))
                    .iconRotationAlignment(IconRotationAlignment.MAP)
                    .iconPitchAlignment(IconPitchAlignment.MAP)
                    .iconAllowOverlap(true)
                    .iconIgnorePlacement(true)
                    .iconSize(Expression.fromRaw("""["interpolate",["exponential",1.4],["zoom"],14,0.62,16.5,1,19,1.55]"""))
                    .minZoom(arrowZoom)
            )
        }
        addOnTop(
            CircleLayer("${VeloMapState.SRC_PLAN}-points", VeloMapState.SRC_PLAN)
                .circleRadius(Expression.fromRaw("""["match",["get","kind"],"VIA",11,9]"""))
                .circleColor(Expression.fromRaw("""["match",["get","kind"],"START","${css(c.start)}","VIA","${css(c.via)}","${css(c.end)}"]"""))
                .circleStrokeColor(Color.WHITE)
                .circleStrokeWidth(3.0)
        )
        if (ctx != null) {
            for (number in 1..VeloMapState.MAX_STEP_NUMBER) style.addImage("${VeloMapState.STEP_IMAGE}$number", stepNumberImage(ctx, number))
            addOnTop(
                SymbolLayer("${VeloMapState.SRC_PLAN}-numeros", VeloMapState.SRC_PLAN)
                    .iconImage(Expression.get(VeloMapState.STEP_PROPERTY))
                    .iconAllowOverlap(true)
                    .iconIgnorePlacement(true)
                    .filter(Expression.has(VeloMapState.STEP_PROPERTY))
            )
        }
        // Le repère de position a pu être posé avant nos tracés : il doit rester tout en haut.
        if (style.styleLayerExists(PUCK_LAYER)) runCatching { style.moveStyleLayer(PUCK_LAYER, LayerPosition(null, null, null)) }
    }

    private fun line(id: String, source: String, color: Int, width: Expression): LineLayer =
        LineLayer(id, source).lineColor(color).lineWidth(width).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND)

    /** Épaisseur d'un trait d'itinéraire selon le zoom : la même courbe que sur l'autre moteur. */
    private fun routeWidth(extra: Double, factor: Double): Expression = Expression.fromRaw(
        """["interpolate",["exponential",1.4],["zoom"],9,${4.5 * factor + extra},14,${6.0 * factor + extra},16.5,${9.0 * factor + extra},19,${14.0 * factor + extra}]"""
    )

    private fun css(color: Int): String = "rgba(${Color.red(color)},${Color.green(color)},${Color.blue(color)},${Color.alpha(color) / 255.0})"

    /**
     * La couche sous laquelle glisser les tracés : la première du bloc de noms qui termine le fond. On ne peut pas
     * prendre la première couche de texte venue, comme sur nos propres fonds : ceux de Mapbox en ont au milieu des
     * routes (flèches de sens unique, cotes des courbes de niveau), et l'itinéraire passerait sous la chaussée.
     * Null pour un fond qui ne finit pas par des noms : les tracés vont alors tout en haut.
     */
    private fun labelsAnchor(style: Style): String? = labelsAnchor(style.styleLayers.map { it.id to it.type })

    // --- Ce qu'on dessine -------------------------------------------------------------------------------------

    private fun setData(source: String, data: FeatureCollection) {
        if (style == null) return
        runCatching { sources[source]?.featureCollection(data) }
    }

    override fun setLiveTrack(track: LiveTrack) {
        live = track
        applyLiveTrack()
    }

    private fun applyLiveTrack() {
        if (style == null || !rendering) return
        if (appliedChunks != live.chunks.size) {
            val lines = live.chunks.map { chunk -> LineString.fromLngLats(chunk.points.map { it.toPoint() }) }
            setData(
                VeloMapState.SRC_FROZEN,
                if (lines.isEmpty()) EMPTY else FeatureCollection.fromFeature(Feature.fromGeometry(MultiLineString.fromLineStrings(lines)))
            )
            appliedChunks = live.chunks.size
        }
        setData(
            VeloMapState.SRC_TAIL,
            if (live.tail.size < 2) EMPTY else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(live.tail.map { it.toPoint() })))
        )
    }

    override fun setRoutes(options: List<List<LatLon>>, selected: Int) {
        // Un autre tracé : ce qu'on avait parcouru du précédent ne le concerne plus.
        if (routes.getOrNull(routeSelected) !== options.getOrNull(selected)) {
            routeProgress = null
            appliedDoneChunks = -1
        }
        routes = options
        routeSelected = selected
        applyRoutes()
        applyRouteProgress()
    }

    private fun applyRoutes() {
        if (style == null) return
        val main = routes.getOrNull(routeSelected)?.takeIf { it.size >= 2 }
        // Chaque tracé en retrait porte son rang : un appui dessus le choisit.
        val others = routes.mapIndexedNotNull { i, line ->
            if (i == routeSelected || line.size < 2) null
            else Feature.fromGeometry(LineString.fromLngLats(line.map { it.toPoint() })).apply { addNumberProperty("rang", i) }
        }
        setData(VeloMapState.SRC_ROUTES_OTHER, FeatureCollection.fromFeatures(others))
        setData(
            VeloMapState.SRC_ROUTE,
            if (main == null) EMPTY else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(main.map { it.toPoint() })))
        )
    }

    override fun setRouteProgress(segment: Int, position: LatLon) {
        routeProgress = segment to position
        applyRouteProgress()
    }

    override fun clearRouteProgress() {
        if (routeProgress == null) return
        routeProgress = null
        applyRouteProgress()
    }

    /** Partie parcourue de l'itinéraire, envoyée par morceaux : voir VeloMapState.setRouteProgress. */
    private fun applyRouteProgress() {
        if (style == null || !rendering) return
        val line = routes.getOrNull(routeSelected)
        val progress = routeProgress
        if (line == null || progress == null || progress.first !in 0 until line.size - 1) {
            if (appliedDoneChunks != -1 || progress == null) {
                setData(VeloMapState.SRC_DONE_FROZEN, EMPTY)
                setData(VeloMapState.SRC_DONE_TAIL, EMPTY)
                appliedDoneChunks = -1
            }
            return
        }
        val (segment, position) = progress
        val chunks = segment / VeloMapState.DONE_CHUNK
        val frozenEnd = chunks * VeloMapState.DONE_CHUNK
        if (chunks != appliedDoneChunks) {
            setData(
                VeloMapState.SRC_DONE_FROZEN,
                if (chunks == 0) EMPTY
                else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line.subList(0, frozenEnd + 1).map { it.toPoint() })))
            )
            appliedDoneChunks = chunks
        }
        val tail = ArrayList<Point>(segment - frozenEnd + 2)
        for (i in frozenEnd..segment) tail.add(line[i].toPoint())
        tail.add(position.toPoint())
        setData(VeloMapState.SRC_DONE_TAIL, FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(tail))))
    }

    override fun setManeuverArrow(points: List<LatLon>) {
        if (maneuverArrow == points) return
        maneuverArrow = points
        applyManeuverArrow()
    }

    private fun applyManeuverArrow() {
        if (style == null) return
        val points = maneuverArrow
        // La pointe prend la direction des derniers mètres du tracé.
        val tip = points.lastOrNull()
        val before = points.dropLast(1).lastOrNull { it != tip }
        if (points.size < 2 || tip == null || before == null) {
            setData(VeloMapState.SRC_ARROW, EMPTY)
            setData(VeloMapState.SRC_ARROW_HEAD, EMPTY)
            return
        }
        setData(VeloMapState.SRC_ARROW, FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { it.toPoint() }))))
        val head = Feature.fromGeometry(tip.toPoint()).apply {
            addNumberProperty(VeloMapState.ARROW_BEARING, Geo.bearingDeg(before.lat, before.lon, tip.lat, tip.lon))
        }
        setData(VeloMapState.SRC_ARROW_HEAD, FeatureCollection.fromFeature(head))
    }

    override fun setPlanMarkers(markers: List<PlanMarker>) {
        planMarkers = markers
        applyPlanMarkers()
    }

    private fun applyPlanMarkers() {
        if (style == null) return
        val features = planMarkers.map { m ->
            Feature.fromGeometry(m.position.toPoint()).apply {
                addStringProperty("kind", m.kind.name)
                if (m.kind == PlanMarker.Kind.VIA && m.number in 1..VeloMapState.MAX_STEP_NUMBER) {
                    addStringProperty(VeloMapState.STEP_PROPERTY, "${VeloMapState.STEP_IMAGE}${m.number}")
                }
            }
        }
        setData(VeloMapState.SRC_PLAN, FeatureCollection.fromFeatures(features))
    }

    override fun center(): LatLon? = map?.let { m -> runCatching { m.cameraState.center }.getOrNull() }?.let { LatLon(it.latitude(), it.longitude()) }

    /** Où regarde la carte en ce moment, pour que l'autre moteur reprenne la même vue. */
    internal fun camera(): MapCamera? = map?.let { m -> runCatching { m.cameraState }.getOrNull() }?.let { state ->
        MapCamera(LatLon(state.center.latitude(), state.center.longitude()), state.zoom, state.bearing)
    }

    /** Reprend la vue et le suivi laissés par l'autre moteur, à la prochaine création de cette carte. */
    internal fun adopt(camera: MapCamera?, following: Boolean) {
        handover = camera
        isFollowing = following
    }

    // --- Position et caméra -----------------------------------------------------------------------------------

    override fun updateLocation(newLocation: Location) {
        location = newLocation
        if (rendering) {
            pushLocation(newLocation)
            applyLiveTrack()
        }
    }

    private fun pushLocation(l: Location) {
        val view = mapView ?: return
        if (style == null) return
        val ctx = context ?: return
        val c = colors ?: return
        val arrow = l.hasBearing()
        if (!locationShown) {
            runCatching {
                view.location.updateSettings {
                    enabled = true
                    pulsingEnabled = false
                    showAccuracyRing = true
                    accuracyRingColor = withAlpha(c.puck, 0.16f)
                    accuracyRingBorderColor = withAlpha(c.puck, 0.3f)
                    puckBearingEnabled = true
                    puckBearing = PuckBearing.COURSE
                    locationPuck = puck(ctx, c, arrow)
                }
            }.onFailure {
                Log.w(TAG, "Repère de position non affiché sur la carte Mapbox", it)
                return
            }
            puckIsArrow = arrow
            locationShown = true
            provider.push(l)
            if (isFollowing) {
                val zoom = followZoom(l)
                wideFollow = zoom < VeloMapState.FOLLOW_ZOOM
                startTracking(600L, zoom)
            }
        } else {
            // Sans boussole, le sens de la marche ne vient que du déplacement : une flèche dès qu'il est connu, un
            // simple point tant qu'on n'a pas encore roulé.
            if (puckIsArrow != arrow) {
                runCatching { view.location.updateSettings { locationPuck = puck(ctx, c, arrow) } }
                puckIsArrow = arrow
            }
            provider.push(l)
            // La carte s'était cadrée large autour d'une position floue : à la première position fine, elle se rapproche.
            if (wideFollow && isFollowing && !navigation && followZoom(l) >= VeloMapState.FOLLOW_ZOOM) {
                wideFollow = false
                startTracking(600L, VeloMapState.FOLLOW_ZOOM)
            }
        }
    }

    /**
     * Le repère : le point (cerné de blanc) ou la flèche, aux couleurs fixées, plus gros vu de près.
     *
     * Le kit a trois places d'image (dessus, milieu orienté, ombre) et ne retire jamais une image déjà posée : en
     * passant du point à la flèche, le point resterait dessiné par-dessus. Chaque place reçoit donc toujours une
     * image, vide quand elle ne sert pas.
     */
    private fun puck(ctx: Context, c: MapColors, arrow: Boolean): LocationPuck2D {
        val scale = """["interpolate",["linear"],["zoom"],${VeloMapState.MIN_ZOOM},${VeloMapState.PUCK_SCALE_FAR},${VeloMapState.MAX_ZOOM},${VeloMapState.PUCK_SCALE_NEAR}]"""
        val nothing = ImageHolder.from(emptyImage)
        return LocationPuck2D(
            topImage = if (arrow) nothing else ImageHolder.from(puckDotImage(ctx, c)),
            bearingImage = if (arrow) ImageHolder.from(puckArrowImage(ctx)) else nothing,
            shadowImage = nothing,
            scaleExpression = scale,
        )
    }

    /** Une image d'un point, transparente : pour les places d'image du repère qui ne servent pas. */
    private val emptyImage: Bitmap by lazy { createBitmap(1, 1) }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun followZoom(l: Location): Double {
        val accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else 0.0
        if (accuracyM <= VeloMapState.WIDE_ACCURACY_M) return VeloMapState.FOLLOW_ZOOM
        return (VeloMapState.FOLLOW_ZOOM - ln(accuracyM / VeloMapState.WIDE_ACCURACY_M) / ln(2.0)).coerceAtLeast(VeloMapState.WIDE_MIN_ZOOM)
    }

    /**
     * Met la carte en suivi de la position, dans la vue qui convient : celle du guidage (dans le sens de la
     * marche, inclinée, rapprochée, position vers le bas de l'écran) ou la vue ordinaire, à plat.
     *
     * Le zoom n'est imposé que pour y arriver : une fois la carte en place, on la laisse zoomer au doigt sans
     * quitter le suivi (et tourner, quand elle a le nord en haut).
     */
    private fun startTracking(durationMs: Long, minZoom: Double) {
        val view = mapView ?: return
        val map = map ?: return
        if (!locationShown) return
        val nav = navigation
        val synced = nav || headingUp
        val zoom = if (nav) VeloMapState.NAV_ZOOM else (runCatching { map.cameraState.zoom }.getOrNull() ?: minZoom).coerceAtLeast(minZoom)
        val arrival = FollowPuckViewportStateOptions.Builder()
            .zoom(zoom)
            .pitch(if (nav) VeloMapState.NAV_TILT else 0.0)
            .bearing(if (synced) FollowPuckViewportStateBearing.SyncWithLocationPuck else FollowPuckViewportStateBearing.Constant(0.0))
            .padding(trackingPadding())
            .build()
        runCatching {
            val viewport = view.viewport
            val state = viewport.makeFollowPuckViewportState(arrival)
            followState = state
            val transition = viewport.makeDefaultViewportTransition(DefaultViewportTransitionOptions.Builder().maxDurationMs(durationMs).build())
            viewport.transitionTo(state, transition) { arrived ->
                // Entre-temps on a pu quitter le suivi ou en demander un autre : seul le dernier compte.
                if (followState !== state || !isFollowing) return@transitionTo
                if (arrived) {
                    runCatching { state.options = arrival.toBuilder().zoom(null).apply { if (!synced) bearing(null) }.build() }
                } else {
                    // La carte n'est pas arrivée sur la position (un geste a coupé le mouvement) : elle ne suit donc
                    // pas, et l'écran doit le dire pour que « Recentrer » soit proposé.
                    isFollowing = false
                    followState = null
                    applyFollowGestures()
                }
            }
        }.onFailure { Log.w(TAG, "Suivi de la position non démarré sur la carte Mapbox", it) }
        applyFollowGestures()
    }

    private fun stopFollowing() {
        if (!isFollowing) return
        isFollowing = false
        followState = null
        runCatching { mapView?.viewport?.idle() }
        applyFollowGestures()
    }

    /**
     * Les gestes pendant le suivi : on zoome autour de la position (sinon chaque zoom l'écarterait du centre, et
     * le suivi l'y ramènerait d'un coup), et une carte qui tourne toute seule dans le sens de la marche ne se
     * tourne pas au doigt.
     */
    private fun applyFollowGestures() {
        val view = mapView ?: return
        val following = isFollowing && locationShown
        val synced = navigation || headingUp
        runCatching {
            view.gestures.updateSettings {
                focalPoint = if (following) puckPoint() else null
                rotateEnabled = !(following && synced)
            }
        }
    }

    /** La carte a changé de taille (écran tourné, fenêtre redimensionnée) : marges et point de zoom sont à refaire. */
    private fun onResized() {
        val state = followState
        if (state != null && navigation && isFollowing) {
            runCatching { state.options = state.options.toBuilder().padding(trackingPadding()).build() }
        }
        applyFollowGestures()
    }

    /** Où se trouve la position à l'écran pendant le suivi : au milieu de ce que laissent les marges. */
    private fun puckPoint(): ScreenCoordinate? {
        val view = mapView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val padding = trackingPadding()
        return ScreenCoordinate(view.width / 2.0, padding.top + (view.height - padding.top - padding.bottom) / 2.0)
    }

    /**
     * Marges de la carte pendant le suivi : la position se place au milieu de ce qu'elles laissent libre. En
     * guidage, elle est descendue vers le bas de l'écran pour montrer la route devant.
     */
    private fun trackingPadding(): EdgeInsets {
        val height = mapView?.height ?: 0
        if (!navigation || height <= 0) return EdgeInsets(0.0, 0.0, 0.0, 0.0)
        val free = (height - navTopPx - navBottomPx).coerceAtLeast(0)
        val puckY = navTopPx + free * VeloMapState.NAV_PUCK_POSITION
        val top = (2 * puckY - height + navBottomPx).coerceIn(0.0, (height - navBottomPx).toDouble().coerceAtLeast(0.0))
        return EdgeInsets(top, 0.0, navBottomPx.toDouble(), 0.0)
    }

    override fun follow() {
        isFollowing = true
        val zoom = location?.let { followZoom(it) } ?: VeloMapState.FOLLOW_ZOOM
        wideFollow = zoom < VeloMapState.FOLLOW_ZOOM
        startTracking(500L, zoom)
    }

    override fun setHeadingUp(enabled: Boolean) {
        if (headingUp == enabled) return
        headingUp = enabled
        if (isFollowing && !navigation) startTracking(500L, 0.0)
    }

    override fun setNavigation(enabled: Boolean) {
        if (navigation == enabled) return
        navigation = enabled
        // La boussole de la carte ne sert à rien en guidage (la flèche dit tout) et se glisserait sous le bandeau.
        applyOrnaments()
        when {
            // Le guidage commence : on revient toujours sur la position, dans la vue de guidage.
            enabled -> follow()
            isFollowing -> startTracking(700L, VeloMapState.FOLLOW_ZOOM)
            else -> {
                // La carte était déplacée à la main : on la remet simplement à plat, nord en haut.
                val view = mapView ?: return
                runCatching {
                    view.camera.easeTo(
                        CameraOptions.Builder().pitch(0.0).bearing(0.0).padding(EdgeInsets(0.0, 0.0, 0.0, 0.0)).build(),
                        MapAnimationOptions.Builder().duration(VeloMapState.SETTLE_MS).build(),
                    )
                }
                applyFollowGestures()
            }
        }
    }

    override fun setNavigationInsets(topPx: Int, bottomPx: Int) {
        if (navTopPx == topPx && navBottomPx == bottomPx) return
        navTopPx = topPx
        navBottomPx = bottomPx
        val state = followState ?: return
        if (navigation && isFollowing && locationShown) {
            runCatching { state.options = state.options.toBuilder().padding(trackingPadding()).build() }
            applyFollowGestures()
        }
    }

    override fun fit(box: BoundingBox, paddingPx: Int, topPx: Int, bottomPx: Int, endPx: Int, animate: Boolean) {
        pendingFit = Fit(box, paddingPx, topPx, bottomPx, endPx)
        val view = mapView ?: return
        val map = map ?: return
        if (style == null) return
        isFollowing = false
        followState = null
        runCatching { view.viewport.idle() }
        applyFollowGestures()
        val tiny = (box.maxLat - box.minLat) < 1e-4 && (box.maxLon - box.minLon) < 1e-4
        val corners = listOf(Point.fromLngLat(box.minLon, box.minLat), Point.fromLngLat(box.maxLon, box.maxLat))
        // Vue de dessus, nord en haut, sans marge de carte : celles du guidage resteraient attachées à la caméra.
        val flat = CameraOptions.Builder().bearing(0.0).pitch(0.0).padding(EdgeInsets(0.0, 0.0, 0.0, 0.0)).build()
        fun frame(margins: EdgeInsets, then: (CameraOptions) -> Unit) = map.cameraForCoordinates(corners, flat, margins, if (tiny) 15.0 else null, null, then)
        fun apply(found: CameraOptions) {
            // La carte a pu être détruite, ou le suivi repris, avant que le cadrage soit calculé.
            if (this.map !== map || isFollowing) return
            val target = CameraOptions.Builder().center(found.center).zoom(found.zoom).bearing(0.0).pitch(0.0).padding(EdgeInsets(0.0, 0.0, 0.0, 0.0)).build()
            runCatching {
                if (animate) view.camera.easeTo(target, MapAnimationOptions.Builder().duration(VeloMapState.FIT_MS.toLong()).build())
                else map.setCamera(target)
            }
        }
        runCatching {
            frame(EdgeInsets(topPx.toDouble(), paddingPx.toDouble(), bottomPx.toDouble(), endPx.toDouble())) { found ->
                // Si les marges demandées ne laissent pas de place (très petit écran), on retombe sur un cadrage simple.
                if (found.center != null && found.zoom != null) apply(found)
                else runCatching { frame(EdgeInsets(paddingPx.toDouble(), paddingPx.toDouble(), paddingPx.toDouble(), paddingPx.toDouble())) { simple -> if (simple.center != null) apply(simple) } }
            }
        }
        pendingFit = null
    }

    private class Fit(val box: BoundingBox, val paddingPx: Int, val topPx: Int, val bottomPx: Int, val endPx: Int)

    override fun setTopInset(px: Int) {
        if (topInsetPx == px) return
        topInsetPx = px
        applyOrnaments()
    }

    override fun setOrnamentInsets(startPx: Int, bottomPx: Int) {
        if (ornamentStartPx == startPx && ornamentBottomPx == bottomPx) return
        ornamentStartPx = startPx
        ornamentBottomPx = bottomPx
        applyOrnaments()
    }

    /**
     * La boussole sous la barre du haut ; le logo de Mapbox et son bouton d'information en bas à gauche, là où
     * l'écran écrit la mention des autres fonds. Ces deux-là ne se masquent pas : les conditions de Mapbox les
     * exigent, et le bouton ouvre la liste des droits et le réglage des statistiques d'utilisation.
     */
    private fun applyOrnaments() {
        val view = mapView ?: return
        val density = view.resources.displayMetrics.density
        val margin = 12 * density
        runCatching {
            view.compass.updateSettings {
                enabled = !navigation
                fadeWhenFacingNorth = true
                position = Gravity.TOP or Gravity.END
                marginTop = topInsetPx + margin
                marginRight = margin
            }
            view.logo.updateSettings {
                enabled = true
                position = Gravity.BOTTOM or Gravity.START
                marginLeft = ornamentStartPx.toFloat()
                marginBottom = ornamentBottomPx.toFloat()
            }
            view.attribution.updateSettings {
                enabled = true
                clickable = true
                position = Gravity.BOTTOM or Gravity.START
                marginLeft = ornamentStartPx + LOGO_ROOM_DP * density
                marginBottom = ornamentBottomPx.toFloat()
            }
        }
    }

    private fun LatLon.toPoint(): Point = Point.fromLngLat(lon, lat)

    internal companion object {
        private const val TAG = "VeloGoMapbox"

        /** La couche où le kit de Mapbox dessine le repère de position. */
        private const val PUCK_LAYER = "mapbox-location-indicator-layer"

        /** Place du logo de Mapbox, à gauche de son bouton d'information (88 dp : la largeur que lui donne le kit). */
        private const val LOGO_ROOM_DP = 88f

        private val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

        private val SOURCES = listOf(
            VeloMapState.SRC_ROUTES_OTHER, VeloMapState.SRC_ROUTE, VeloMapState.SRC_DONE_FROZEN, VeloMapState.SRC_DONE_TAIL,
            VeloMapState.SRC_PLAN, VeloMapState.SRC_FROZEN, VeloMapState.SRC_TAIL, VeloMapState.SRC_ARROW, VeloMapState.SRC_ARROW_HEAD,
        )

        /**
         * Parmi les couches d'un fond, de la première dessinée à la dernière (identifiant, genre), celle qui ouvre
         * le bloc de noms final. Le repère de position et nos propres couches, posés par-dessus, ne comptent pas.
         */
        fun labelsAnchor(layers: List<Pair<String, String>>): String? {
            var anchor: String? = null
            for ((id, type) in layers.asReversed()) {
                if (type == "location-indicator" || type == "model" || id.startsWith("velogo-")) continue
                if (type != "symbol") break
                anchor = id
            }
            return anchor
        }
    }
}

/**
 * Une carte Mapbox dans Compose : le pendant de [VeloMap]. Dessinée elle aussi dans une SurfaceView.
 *
 * Elle n'est créée qu'après [MapboxSupport.ensure] ; si sa création échoue, le fusible saute et l'écran revient
 * de lui-même à un fond de MapLibre.
 */
@SuppressLint("Lifecycle")
@Composable
fun MapboxMapView(
    state: MapboxMapState,
    spec: MapStyleSpec,
    colors: MapColors,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        state.openDisplay(context)
        try {
            check(MapboxSupport.ensure(context)) { "kit Mapbox indisponible" }
            val options = MapInitOptions(
                context = context,
                // Rien que ce dont l'appli se sert : ni échelle, ni annotations, ni plans d'intérieur. Le cycle de
                // vie est piloté d'ici (onStart, onStop, onDestroy), pour couper le rendu dès que la carte est recouverte.
                plugins = listOf(
                    Plugin.Mapbox(Plugin.MAPBOX_CAMERA_PLUGIN_ID),
                    Plugin.Mapbox(Plugin.MAPBOX_GESTURES_PLUGIN_ID),
                    Plugin.Mapbox(Plugin.MAPBOX_COMPASS_PLUGIN_ID),
                    Plugin.Mapbox(Plugin.MAPBOX_LOGO_PLUGIN_ID),
                    Plugin.Mapbox(Plugin.MAPBOX_ATTRIBUTION_PLUGIN_ID),
                    Plugin.Mapbox(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID),
                    Plugin.Mapbox(Plugin.MAPBOX_VIEWPORT_PLUGIN_ID),
                ),
                cameraOptions = CameraOptions.Builder().center(Point.fromLngLat(FRANCE_CENTER_LON, FRANCE_CENTER_LAT)).zoom(FRANCE_ZOOM).build(),
                // Aucun fond tant que l'écran n'a pas dit lequel : sinon le kit chargerait d'abord son fond par défaut.
                styleUri = null,
            )
            MapView(context, options).also { view -> state.bindView(view, context) }
        } catch (failure: Throwable) {
            Log.w("VeloGoMapbox", "La carte Mapbox n'a pas pu être créée", failure)
            state.creationFailed()
            MapboxSupport.trip(context)
            null
        }
    }

    if (mapView == null) {
        Box(modifier)
        return
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
            runCatching { mapView.onDestroy() }
        }
    }

    LaunchedEffect(spec, colors) { state.setStyle(spec, colors) }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val FRANCE_CENTER_LAT = 46.6
private const val FRANCE_CENTER_LON = 2.4
private const val FRANCE_ZOOM = 4.8
