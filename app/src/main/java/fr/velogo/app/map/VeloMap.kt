package fr.velogo.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt
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
import fr.velogo.app.R
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
import org.maplibre.android.location.OnLocationCameraTransitionListener
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
import kotlin.math.ln

/** Couleurs des éléments que VeloGo dessine sur la carte. */
data class MapColors(
    val track: Int,
    val trackCasing: Int,
    val puck: Int,
    val start: Int,
    val end: Int,
    /** Itinéraire choisi (ou suivi), et le liseré plus sombre qui le détache du fond. */
    val route: Int = 0xFF1565C0.toInt(),
    val routeCasing: Int = 0xFF0C3F7E.toInt(),
    /** Partie de l'itinéraire déjà parcourue, pendant le guidage. */
    val routeDone: Int = 0xFF9AA8B5.toInt(),
    /** Autres itinéraires proposés. */
    val routeOther: Int = 0xFF78909C.toInt(),
    /** Flèche de la prochaine manœuvre : claire, cernée de sombre, lisible sur le trait de l'itinéraire. */
    val arrow: Int = 0xFFFFFFFF.toInt(),
    val arrowCasing: Int = 0xFF0A2540.toInt(),
    /** Étapes d'un itinéraire. */
    val via: Int = 0xFFEF6C00.toInt(),
)

/** Un point d'itinéraire à dessiner sur la carte. */
data class PlanMarker(
    val position: LatLon,
    val kind: Kind,
    /** Rang de l'étape (1, 2, 3…), écrit dans son repère pour montrer l'ordre du parcours ; 0 pour ne rien écrire. */
    val number: Int = 0,
) {
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
    /** Le suivi a commencé cadré large, autour d'une position floue : il se resserrera à la première position fine. */
    private var wideFollow = false
    private var locationShown = false
    private var live: LiveTrack = LiveTrack.EMPTY
    private var appliedChunks = -1
    private var ride: List<List<LatLon>> = emptyList()
    private var marker: LatLon? = null
    private var routes: List<List<LatLon>> = emptyList()
    private var routeSelected = 0
    /** Guidage : jusqu'où l'itinéraire affiché est parcouru (rang du segment et point exact), ou null. */
    private var routeProgress: Pair<Int, LatLon>? = null
    private var appliedDoneChunks = -1
    /** Guidage : tracé de la prochaine manœuvre, dessiné en flèche ; vide s'il n'y en a pas. */
    private var maneuverArrow: List<LatLon> = emptyList()
    private var planMarkers: List<PlanMarker> = emptyList()
    private var pendingFit: Fit? = null

    /** Appelé quand on appuie longuement sur la carte (pour y placer un point d'itinéraire). */
    var onLongPress: ((LatLon) -> Unit)? = null

    /** Appelé quand on commence à déplacer, zoomer ou tourner la carte au doigt. */
    var onUserMove: (() -> Unit)? = null

    /** Appelé quand on touche un des itinéraires proposés, avec son rang. */
    var onRouteTapped: ((Int) -> Unit)? = null
    private var topInsetPx = 0

    /** Guidage : carte dans le sens de la marche, inclinée et rapprochée, position dans le bas de l'écran. */
    private var navigation = false
    private var navTopPx = 0
    private var navBottomPx = 0

    /** Vrai pendant qu'on relance soi-même le suivi : la carte annonce alors un arrêt du suivi qui n'en est pas un. */
    private var restartingTracking = false

    private val trackingListener = object : OnCameraTrackingChangedListener {
        override fun onCameraTrackingDismissed() {
            if (!restartingTracking) isFollowing = false
        }

        override fun onCameraTrackingChanged(currentMode: Int) {
            // En guidage, la carte doit rester dans le sens de la marche : si un geste l'en a sortie (rotation au
            // doigt), on le signale comme un suivi interrompu, et « Recentrer » y ramène.
            if (!restartingTracking && navigation && currentMode != CameraMode.TRACKING_GPS) isFollowing = false
        }
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
            isCompassEnabled = !navigation
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
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserMove?.invoke()
        }
        // Un appui sur un des itinéraires en retrait le choisit.
        map.addOnMapClickListener { point ->
            val listener = onRouteTapped ?: return@addOnMapClickListener false
            val screen = map.projection.toScreenLocation(point)
            val reach = TAP_REACH_DP * (context.resources.displayMetrics.density)
            val area = RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach)
            val index = runCatching {
                map.queryRenderedFeatures(area, "$SRC_ROUTES_OTHER-trait", "$SRC_ROUTES_OTHER-liseré")
                    .firstNotNullOfOrNull { feature -> feature.getNumberProperty("rang")?.toInt() }
            }.getOrNull()
            if (index != null) listener(index)
            index != null
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
        appliedDoneChunks = -1
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
            applyRouteProgress()
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
        appliedDoneChunks = -1
        MapInit.setOfflineLayer(spec.layer.offline)
        val uri = spec.uri
        val builder = if (uri != null) Style.Builder().fromUri(uri) else Style.Builder().fromJson(spec.json.orEmpty())
        map.setStyle(builder) { loaded ->
            // Un autre fond a pu être demandé entre-temps : seul le dernier compte.
            if (this.spec != spec) return@setStyle
            style = loaded
            installOverlays(loaded)
            isStyleLoaded = true
            applyRoutes()
            applyRouteProgress()
            applyManeuverArrow()
            applyPlanMarkers()
            applyRide()
            applyLiveTrack()
            applyMarker()
            location?.let { pushLocation(it) }
            pendingFit?.let { fit(it.box, it.paddingPx, it.topPx, it.bottomPx, it.endPx) }
        }
    }

    private fun installOverlays(style: Style) {
        val c = colors ?: return
        // Les tracés passent sous les noms du fond : une rue garde son nom lisible par-dessus l'itinéraire.
        val labels = firstLabelLayerId(style)
        fun addTrace(layer: LineLayer) = if (labels != null) style.addLayerBelow(layer, labels) else style.addLayer(layer)
        fun emptySource(id: String) = style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList<Feature>())))

        // Itinéraires : dessinés sous la trace de la sortie, qui doit rester lisible par-dessus.
        for (source in listOf(SRC_ROUTES_OTHER, SRC_ROUTE, SRC_DONE_FROZEN, SRC_DONE_TAIL, SRC_PLAN)) emptySource(source)
        addTrace(line("$SRC_ROUTES_OTHER-liseré", SRC_ROUTES_OTHER, c.trackCasing, routeWidth(2.5f, 0.85f)))
        addTrace(line("$SRC_ROUTES_OTHER-trait", SRC_ROUTES_OTHER, c.routeOther, routeWidth(0f, 0.85f)))
        addTrace(line("$SRC_ROUTE-liseré", SRC_ROUTE, c.routeCasing, routeWidth(3f, 1f)))
        addTrace(line("$SRC_ROUTE-trait", SRC_ROUTE, c.route, routeWidth(0f, 1f)))
        // Pendant le guidage, ce qui est déjà parcouru s'efface : un trait gris posé sur l'itinéraire.
        for (source in listOf(SRC_DONE_FROZEN, SRC_DONE_TAIL)) addTrace(line("$source-trait", source, c.routeDone, routeWidth(0f, 1f)))

        for (source in listOf(SRC_RIDE, SRC_FROZEN, SRC_TAIL, SRC_ARROW, SRC_ARROW_HEAD, SRC_ENDS, SRC_MARKER)) emptySource(source)

        // Chaque trace est dessinée deux fois : un liseré clair dessous, la couleur dessus (lisible sur tous les fonds).
        for (source in listOf(SRC_RIDE, SRC_FROZEN, SRC_TAIL)) {
            addTrace(line("$source-liseré", source, c.trackCasing, TRACK_WIDTH + 3f))
        }
        for (source in listOf(SRC_RIDE, SRC_FROZEN, SRC_TAIL)) {
            addTrace(line("$source-trait", source, c.track, TRACK_WIDTH))
        }

        // La prochaine manœuvre, en flèche sur le tracé : par-dessus tout, noms compris, elle doit sauter aux yeux.
        style.addLayer(line("$SRC_ARROW-liseré", SRC_ARROW, c.arrowCasing, routeWidth(3.5f, 0.72f)).apply { minZoom = ARROW_MIN_ZOOM })
        style.addLayer(line("$SRC_ARROW-trait", SRC_ARROW, c.arrow, routeWidth(0f, 0.72f)).apply { minZoom = ARROW_MIN_ZOOM })
        context?.let { ctx ->
            style.addImage(ARROW_IMAGE, arrowHeadImage(ctx, c))
            style.addLayer(
                SymbolLayer("$SRC_ARROW_HEAD-pointe", SRC_ARROW_HEAD).withProperties(
                    PropertyFactory.iconImage(ARROW_IMAGE),
                    PropertyFactory.iconRotate(Expression.get(ARROW_BEARING)),
                    // La pointe tourne avec la carte et se couche avec elle : elle reste dans l'axe du tracé.
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    // Elle grossit avec le zoom, comme le trait qu'elle termine.
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.exponential(1.4f), Expression.zoom(),
                            Expression.stop(14, 0.62f), Expression.stop(16.5, 1f), Expression.stop(19, 1.55f),
                        )
                    ),
                ).apply { minZoom = ARROW_MIN_ZOOM }
            )
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
                // Une étape est un peu plus grosse que le départ et l'arrivée : son numéro doit y tenir.
                PropertyFactory.circleRadius(Expression.match(Expression.get("kind"), Expression.literal("VIA"), Expression.literal(11f), Expression.literal(9f))),
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
        // Le numéro de chaque étape, écrit dans son repère : il montre l'ordre du parcours. Dessiné par l'appli
        // (une image par chiffre), car tous les fonds de carte n'apportent pas de police de caractères.
        context?.let { ctx ->
            for (number in 1..MAX_STEP_NUMBER) style.addImage("$STEP_IMAGE$number", stepNumberImage(ctx, number))
            style.addLayer(
                SymbolLayer("$SRC_PLAN-numeros", SRC_PLAN).withProperties(
                    PropertyFactory.iconImage(Expression.get(STEP_PROPERTY)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ).withFilter(Expression.has(STEP_PROPERTY))
            )
        }
        style.addLayer(
            CircleLayer("$SRC_MARKER-point", SRC_MARKER).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeColor(c.track),
                PropertyFactory.circleStrokeWidth(4f),
            )
        )
    }

    /** Le chiffre d'une étape, en blanc sur fond transparent, à poser sur son repère. */
    private fun stepNumberImage(ctx: Context, number: Int): Bitmap {
        val metrics = ctx.resources.displayMetrics
        val size = (STEP_IMAGE_DP * metrics.density).roundToInt().coerceAtLeast(8)
        val image = createBitmap(size, size).apply { density = metrics.densityDpi }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.72f
        }
        Canvas(image).drawText(number.toString(), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return image
    }

    /** La pointe de la flèche de manœuvre : un triangle tourné vers le nord, que la carte oriente ensuite. */
    private fun arrowHeadImage(ctx: Context, c: MapColors): Bitmap {
        val metrics = ctx.resources.displayMetrics
        val size = (ARROW_IMAGE_DP * metrics.density).roundToInt().coerceAtLeast(12)
        val image = createBitmap(size, size).apply { density = metrics.densityDpi }
        val s = size.toFloat()
        val shape = Path().apply {
            moveTo(s * 0.5f, s * 0.08f)
            lineTo(s * 0.9f, s * 0.82f)
            lineTo(s * 0.5f, s * 0.66f)
            lineTo(s * 0.1f, s * 0.82f)
            close()
        }
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.14f
        paint.color = c.arrowCasing
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
        paint.color = c.arrow
        canvas.drawPath(shape, paint)
        return image
    }

    private fun line(id: String, source: String, color: Int, width: Float): LineLayer =
        LineLayer(id, source).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )

    private fun line(id: String, source: String, color: Int, width: Expression): LineLayer =
        LineLayer(id, source).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )

    /**
     * Épaisseur d'un trait d'itinéraire : fin vu de loin (pour comparer des tracés), épais vu de près (pour le
     * suivre du coin de l'œil pendant le guidage). [extra] s'ajoute pour le liseré clair qui l'entoure.
     */
    private fun routeWidth(extra: Float, factor: Float): Expression = Expression.interpolate(
        Expression.exponential(1.4f), Expression.zoom(),
        Expression.stop(9, 4.5f * factor + extra), Expression.stop(14, 6f * factor + extra),
        Expression.stop(16.5, 9f * factor + extra), Expression.stop(19, 14f * factor + extra),
    )

    /**
     * Première couche de noms du fond : les tracés (itinéraire, trace de la sortie) se glissent juste en dessous,
     * pour que les noms de rue et de lieu restent lisibles par-dessus. Null pour un fond en images, qui n'en a pas.
     */
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

    /**
     * Guidage : l'itinéraire affiché est parcouru jusqu'au point [position], situé sur son segment de rang
     * [segment] ; null pour ne plus rien montrer de parcouru. Ce qui est derrière soi est dessiné en gris.
     *
     * Le tracé parcouru est envoyé à la carte par morceaux : les morceaux complets une fois pour toutes, le
     * dernier (quelques centaines de points au plus) à chaque position. Renvoyer tout le tracé chaque seconde
     * ferait travailler le téléphone pour rien sur un long itinéraire.
     */
    fun setRouteProgress(segment: Int, position: LatLon) {
        routeProgress = segment to position
        applyRouteProgress()
    }

    fun clearRouteProgress() {
        if (routeProgress == null) return
        routeProgress = null
        applyRouteProgress()
    }

    private fun applyRouteProgress() {
        val style = style ?: return
        if (!rendering) return
        val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
        val line = routes.getOrNull(routeSelected)
        val progress = routeProgress
        if (line == null || progress == null || progress.first !in 0 until line.size - 1) {
            if (appliedDoneChunks != -1 || progress == null) {
                (style.getSource(SRC_DONE_FROZEN) as? GeoJsonSource)?.setGeoJson(empty)
                (style.getSource(SRC_DONE_TAIL) as? GeoJsonSource)?.setGeoJson(empty)
                appliedDoneChunks = -1
            }
            return
        }
        val (segment, position) = progress
        val chunks = segment / DONE_CHUNK
        val frozenEnd = chunks * DONE_CHUNK
        if (chunks != appliedDoneChunks) {
            (style.getSource(SRC_DONE_FROZEN) as? GeoJsonSource)?.setGeoJson(
                if (chunks == 0) empty
                else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line.subList(0, frozenEnd + 1).map { it.toPoint() })))
            )
            appliedDoneChunks = chunks
        }
        val tail = ArrayList<Point>(segment - frozenEnd + 2)
        for (i in frozenEnd..segment) tail.add(line[i].toPoint())
        tail.add(position.toPoint())
        (style.getSource(SRC_DONE_TAIL) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(tail))))
    }

    /**
     * Guidage : le tracé de la prochaine manœuvre (quelques dizaines de mètres avant et après le carrefour),
     * dessiné en flèche par-dessus l'itinéraire. Une liste vide l'efface.
     */
    fun setManeuverArrow(points: List<LatLon>) {
        if (maneuverArrow == points) return
        maneuverArrow = points
        applyManeuverArrow()
    }

    private fun applyManeuverArrow() {
        val style = style ?: return
        val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
        val points = maneuverArrow
        // La pointe prend la direction des derniers mètres du tracé.
        val tip = points.lastOrNull()
        val before = points.dropLast(1).lastOrNull { it != tip }
        if (points.size < 2 || tip == null || before == null) {
            (style.getSource(SRC_ARROW) as? GeoJsonSource)?.setGeoJson(empty)
            (style.getSource(SRC_ARROW_HEAD) as? GeoJsonSource)?.setGeoJson(empty)
            return
        }
        (style.getSource(SRC_ARROW) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { it.toPoint() })))
        )
        val head = Feature.fromGeometry(tip.toPoint()).apply {
            addNumberProperty(ARROW_BEARING, Geo.bearingDeg(before.lat, before.lon, tip.lat, tip.lon))
        }
        (style.getSource(SRC_ARROW_HEAD) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeature(head))
    }

    private fun applyRoutes() {
        val style = style ?: return
        fun collection(lines: List<List<LatLon>>) =
            if (lines.isEmpty()) FeatureCollection.fromFeatures(emptyList<Feature>())
            else FeatureCollection.fromFeature(
                Feature.fromGeometry(MultiLineString.fromLineStrings(lines.map { line -> LineString.fromLngLats(line.map { it.toPoint() }) }))
            )
        val main = routes.getOrNull(routeSelected)?.takeIf { it.size >= 2 }
        // Chaque tracé en retrait porte son rang : un appui dessus le choisit.
        val others = routes.mapIndexedNotNull { i, line ->
            if (i == routeSelected || line.size < 2) null
            else Feature.fromGeometry(LineString.fromLngLats(line.map { it.toPoint() })).apply { addNumberProperty("rang", i) }
        }
        (style.getSource(SRC_ROUTES_OTHER) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(others))
        (style.getSource(SRC_ROUTE) as? GeoJsonSource)?.setGeoJson(collection(listOfNotNull(main)))
    }

    /** Départ, étapes et arrivée d'un itinéraire en préparation. */
    fun setPlanMarkers(markers: List<PlanMarker>) {
        planMarkers = markers
        applyPlanMarkers()
    }

    private fun applyPlanMarkers() {
        val style = style ?: return
        val features = planMarkers.map { m ->
            Feature.fromGeometry(m.position.toPoint()).apply {
                addStringProperty("kind", m.kind.name)
                if (m.kind == PlanMarker.Kind.VIA && m.number in 1..MAX_STEP_NUMBER) addStringProperty(STEP_PROPERTY, "$STEP_IMAGE${m.number}")
            }
        }
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
                // Point à l'arrêt, flèche dès qu'on connaît le sens de la marche : dessins aux couleurs fixées
                // (une teinte appliquée ici effacerait le contour blanc de la flèche).
                .foregroundDrawable(R.drawable.map_puck_dot)
                .gpsDrawable(R.drawable.map_puck_arrow)
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
            if (isFollowing) {
                val zoom = followZoom(l)
                wideFollow = zoom < FOLLOW_ZOOM
                startTracking(600L, zoom)
            }
        } else {
            component.forceLocationUpdate(l)
            // La carte s'était cadrée large autour d'une position floue (le GPS cherchait encore ses satellites) :
            // à la première position fine, elle se rapproche comme d'habitude.
            if (wideFollow && isFollowing && !navigation && followZoom(l) >= FOLLOW_ZOOM) {
                wideFollow = false
                startTracking(600L, FOLLOW_ZOOM)
            }
        }
        // Sans boussole, le sens de la marche ne vient que du déplacement : une flèche dès qu'il est connu (la
        // position qu'on reçoit le garde à l'arrêt), un simple point tant qu'on n'a pas encore roulé.
        val wanted = if (l.hasBearing()) RenderMode.GPS else RenderMode.NORMAL
        if (component.renderMode != wanted) component.renderMode = wanted
    }

    /**
     * Zoom du suivi pour cette position : celui de tous les jours quand elle est fine, plus large quand elle est
     * floue (position approchée du téléphone), pour que le cercle qui dit son incertitude tienne dans l'écran.
     */
    private fun followZoom(l: Location): Double {
        val accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else 0.0
        if (accuracyM <= WIDE_ACCURACY_M) return FOLLOW_ZOOM
        return (FOLLOW_ZOOM - ln(accuracyM / WIDE_ACCURACY_M) / ln(2.0)).coerceAtLeast(WIDE_MIN_ZOOM)
    }

    private fun trackingMode(): Int = if (navigation || headingUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING

    /**
     * Met la carte en suivi de la position, dans la vue qui convient : celle du guidage (dans le sens de la
     * marche, inclinée, rapprochée, position vers le bas de l'écran) ou la vue ordinaire, à plat.
     * Les réglages fins (inclinaison, marges) ne sont acceptés par la carte qu'une fois la transition finie.
     */
    @SuppressLint("MissingPermission")
    private fun startTracking(durationMs: Long, minZoom: Double) {
        val map = map ?: return
        if (!locationShown) return
        val component = map.locationComponent
        val nav = navigation
        val zoom = if (nav) NAV_ZOOM else map.cameraPosition.zoom.coerceAtLeast(minZoom)
        val bearing = if (nav || headingUp) null else 0.0
        val tilt = if (nav) NAV_TILT else 0.0
        // D'un suivi à un autre, la carte ne ferait que changer de mode en gardant l'orientation et l'inclinaison
        // d'avant. En repartant de « pas de suivi », elle fait une vraie transition vers la vue demandée.
        restartingTracking = true
        runCatching { if (component.cameraMode != CameraMode.NONE) component.cameraMode = CameraMode.NONE }
        restartingTracking = false
        component.setCameraMode(trackingMode(), durationMs, zoom, bearing, tilt, object : OnLocationCameraTransitionListener {
            override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                // Entre-temps on a pu quitter le suivi ou changer de vue : seul le dernier état compte.
                if (!isFollowing || nav != navigation || this@VeloMapState.map !== map) return
                runCatching {
                    component.tiltWhileTracking(tilt, SETTLE_MS)
                    if (nav) component.zoomWhileTracking(NAV_ZOOM, SETTLE_MS)
                    component.paddingWhileTracking(trackingPadding(), SETTLE_MS)
                }
            }

            override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
        })
    }

    /**
     * Marges de la carte pendant le suivi, [gauche, haut, droite, bas] : la position se place au milieu de ce
     * qu'elles laissent libre. En guidage, elle est descendue vers le bas de l'écran pour montrer la route devant.
     */
    private fun trackingPadding(): DoubleArray {
        val height = mapView?.height ?: 0
        if (!navigation || height <= 0) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val free = (height - navTopPx - navBottomPx).coerceAtLeast(0)
        val puckY = navTopPx + free * NAV_PUCK_POSITION
        val top = (2 * puckY - height + navBottomPx).coerceIn(0.0, (height - navBottomPx).toDouble().coerceAtLeast(0.0))
        return doubleArrayOf(0.0, top, 0.0, navBottomPx.toDouble())
    }

    /** Recentre la carte sur la position et reprend le suivi. */
    fun follow() {
        isFollowing = true
        val zoom = location?.let { followZoom(it) } ?: FOLLOW_ZOOM
        wideFollow = zoom < FOLLOW_ZOOM
        startTracking(500L, zoom)
    }

    /** Carte orientée dans le sens de la marche (vrai) ou nord en haut (faux), hors guidage. */
    fun setHeadingUp(enabled: Boolean) {
        if (headingUp == enabled) return
        headingUp = enabled
        if (isFollowing && !navigation) startTracking(500L, 0.0)
    }

    /**
     * Vue de guidage (vrai) ou vue ordinaire (faux). La vue de guidage suit la position dans le sens de la
     * marche, carte inclinée ; on la quitte en déplaçant la carte, on y revient avec [follow].
     */
    fun setNavigation(enabled: Boolean) {
        if (navigation == enabled) return
        navigation = enabled
        // La boussole de la carte ne sert à rien en guidage (la flèche dit tout) et se glisserait sous le bandeau.
        map?.uiSettings?.isCompassEnabled = !enabled
        when {
            // Le guidage commence : on revient toujours sur la position, dans la vue de guidage.
            enabled -> follow()
            isFollowing -> startTracking(700L, FOLLOW_ZOOM)
            else -> {
                // La carte était déplacée à la main : on la remet simplement à plat, nord en haut.
                val map = map ?: return
                runCatching {
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(map.cameraPosition).tilt(0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
                        ),
                        SETTLE_MS.toInt(),
                    )
                }
            }
        }
    }

    /** Ce qui recouvre la carte en haut et en bas pendant le guidage (bandeau, boutons), en pixels. */
    @SuppressLint("MissingPermission")
    fun setNavigationInsets(topPx: Int, bottomPx: Int) {
        if (navTopPx == topPx && navBottomPx == bottomPx) return
        navTopPx = topPx
        navBottomPx = bottomPx
        val map = map ?: return
        if (navigation && isFollowing && locationShown) runCatching { map.locationComponent.paddingWhileTracking(trackingPadding(), SETTLE_MS) }
    }

    /**
     * Cadre la carte sur une emprise (par exemple toute une sortie), vue de dessus et nord en haut. [topPx],
     * [bottomPx] et [endPx] réservent la place de ce qui recouvre la carte en haut, en bas et à droite (bandeau,
     * panneau d'itinéraire, colonne de boutons).
     */
    fun fit(
        box: BoundingBox,
        paddingPx: Int,
        topPx: Int = paddingPx,
        bottomPx: Int = paddingPx,
        endPx: Int = paddingPx,
        animate: Boolean = false,
    ) {
        pendingFit = Fit(box, paddingPx, topPx, bottomPx, endPx)
        val map = map ?: return
        if (style == null) return
        isFollowing = false
        runCatching { map.locationComponent.cameraMode = CameraMode.NONE }
        val tiny = (box.maxLat - box.minLat) < 1e-4 && (box.maxLon - box.minLon) < 1e-4
        val update = if (tiny) {
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng((box.minLat + box.maxLat) / 2, (box.minLon + box.maxLon) / 2))
                    .zoom(15.0).bearing(0.0).tilt(0.0)
                    .padding(paddingPx.toDouble(), topPx.toDouble(), endPx.toDouble(), bottomPx.toDouble())
                    .build()
            )
        } else {
            CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(box.maxLat, box.maxLon, box.minLat, box.minLon), 0.0, 0.0, paddingPx, topPx, endPx, bottomPx)
        }
        fun apply(u: org.maplibre.android.camera.CameraUpdate) = if (animate) map.animateCamera(u, FIT_MS) else map.moveCamera(u)
        // Si les marges demandées ne laissent pas de place (très petit écran), on retombe sur un cadrage simple.
        runCatching { apply(update) }.onFailure {
            runCatching {
                apply(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(box.maxLat, box.maxLon, box.minLat, box.minLon), 0.0, 0.0, paddingPx, paddingPx, paddingPx, paddingPx))
            }
        }
        pendingFit = null
    }

    private class Fit(val box: BoundingBox, val paddingPx: Int, val topPx: Int, val bottomPx: Int, val endPx: Int)

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
        /** Partie parcourue de l'itinéraire suivi : morceaux complets, et le morceau en cours. */
        const val SRC_DONE_FROZEN = "velogo-parcouru"
        const val SRC_DONE_TAIL = "velogo-parcouru-fin"
        const val DONE_CHUNK = 250
        /** Flèche de la prochaine manœuvre : son trait, sa pointe, et la propriété qui oriente la pointe. */
        const val SRC_ARROW = "velogo-manoeuvre"
        const val SRC_ARROW_HEAD = "velogo-manoeuvre-pointe"
        const val ARROW_IMAGE = "velogo-pointe"
        const val ARROW_BEARING = "cap"
        const val ARROW_IMAGE_DP = 30f
        const val ARROW_MIN_ZOOM = 13.5f
        /** Les étapes numérotées : nom de leurs images, propriété qui dit laquelle afficher, taille du chiffre. */
        const val STEP_IMAGE = "velogo-etape-"
        const val STEP_PROPERTY = "numero"
        const val MAX_STEP_NUMBER = 9
        const val STEP_IMAGE_DP = 18f
        const val TRACK_WIDTH = 4.5f
        const val MIN_ZOOM = 4.4
        const val MAX_ZOOM = 19.5
        const val FOLLOW_ZOOM = 15.5
        /** Au zoom du suivi, le cercle d'une position connue à 150 m près occupe les deux tiers de la largeur. */
        const val WIDE_ACCURACY_M = 150.0
        const val WIDE_MIN_ZOOM = 10.0
        const val PUCK_FPS = 30
        const val PUCK_SCALE_FAR = 1.0f
        const val PUCK_SCALE_NEAR = 1.5f
        /** Vue de guidage : assez près pour lire les carrefours, assez inclinée pour voir loin devant. */
        const val NAV_ZOOM = 16.6
        const val NAV_TILT = 50.0
        /** Où se place la position dans la partie libre de l'écran pendant le guidage (0 = en haut, 1 = en bas). */
        const val NAV_PUCK_POSITION = 0.72
        const val SETTLE_MS = 450L
        const val FIT_MS = 450
        /** On choisit un itinéraire en retrait en le touchant, à un doigt près. */
        const val TAP_REACH_DP = 22f
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
