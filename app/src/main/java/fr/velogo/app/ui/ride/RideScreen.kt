package fr.velogo.app.ui.ride

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import fr.velogo.app.ui.COMPACT_WIDTH
import android.content.Intent
import android.location.Location
import android.view.WindowManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import fr.velogo.app.location.GpsSource
import fr.velogo.app.location.LocationAccess
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import fr.velogo.app.data.SavedRouteRepository
import fr.velogo.app.map.PlanMarker
import fr.velogo.app.nav.GuidanceState
import fr.velogo.app.nav.NavigationController
import fr.velogo.app.ui.nav.RouteShareSheet
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RouteShape
import fr.velogo.app.ui.nav.GUIDANCE_BANNER_HEIGHT
import fr.velogo.app.ui.nav.GuidanceBanner
import fr.velogo.app.ui.nav.PlaceSearchDialog
import fr.velogo.app.ui.nav.PlanTarget
import fr.velogo.app.ui.nav.PlannerActions
import fr.velogo.app.ui.nav.PlannerPanel
import fr.velogo.app.ui.nav.PlannerWeather
import fr.velogo.app.ui.nav.SavedRoutesList
import fr.velogo.app.ui.weather.RouteWeatherSection
import fr.velogo.app.ui.weather.WeatherChip
import fr.velogo.app.ui.weather.WeatherContent
import fr.velogo.app.ui.weather.WeatherGlance
import fr.velogo.app.ui.weather.WeatherSheetState
import fr.velogo.app.ui.weather.WeatherStrip
import fr.velogo.app.ui.weather.departureChoices
import fr.velogo.app.ui.weather.departureLabel
import fr.velogo.app.ui.weather.weatherGlance
import fr.velogo.core.weather.RouteWeather
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.gpx.Gpx
import fr.velogo.core.nav.BikeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.velogo.app.R
import fr.velogo.app.map.MapColors
import fr.velogo.app.map.MapLayers
import fr.velogo.app.map.MapStyleSpec
import fr.velogo.app.map.VeloMap
import fr.velogo.app.map.rememberVeloMapState
import fr.velogo.app.recording.RecorderEvent
import fr.velogo.app.recording.RecordingService
import fr.velogo.app.ui.LocalContainer
import fr.velogo.app.ui.components.FittedNumber
import fr.velogo.app.ui.components.GlassControlStack
import fr.velogo.app.ui.components.GlassIconButton
import fr.velogo.app.ui.components.GlassPill
import fr.velogo.app.ui.components.STACK_CELL
import fr.velogo.app.ui.components.Segmented
import fr.velogo.app.ui.components.StackControl
import fr.velogo.app.ui.theme.Corners
import fr.velogo.app.ui.theme.GlassBox
import fr.velogo.app.ui.theme.GlassDefaults
import fr.velogo.app.ui.theme.LocalGlass
import androidx.compose.ui.text.style.TextAlign
import fr.velogo.app.ui.findActivity
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.LocalIsDark
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.ThemeMode
import fr.velogo.app.ui.theme.VeloGoTheme
import fr.velogo.app.ui.util.Dates
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import fr.velogo.core.ride.RideSnapshot
import fr.velogo.core.ride.RideState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import fr.velogo.app.ui.settings.openUrl

enum class RideMode { MAP, DATA }

/**
 * L'écran principal : la carte et, par-dessus, le tableau de bord de la sortie.
 *
 * Il reste composé en permanence (même quand un autre onglet est affiché) pour que la carte ne soit jamais
 * recréée ; [isForeground] indique s'il est réellement visible, afin de couper le rendu et le GPS sinon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideScreen(
    isForeground: Boolean,
    bottomPadding: Dp,
    onRideSaved: (Long) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val container = LocalContainer.current
    val vm = veloViewModel { RideViewModel(it) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val interrupted by vm.interrupted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var mode by rememberSaveable { mutableStateOf(RideMode.MAP) }
    var showLayers by remember { mutableStateOf(false) }
    var showGps by remember { mutableStateOf(false) }
    /** Sens de la marche, tant qu'on roule : sert à dire si le vent est de face ou de dos. */
    var lastBearing by remember { mutableStateOf<Double?>(null) }

    val mapState = rememberVeloMapState()
    val dark = LocalIsDark.current
    val rideColors = LocalRideColors.current
    val mapColors = remember(dark, rideColors) {
        MapColors(
            track = rideColors.track.toArgb(),
            trackCasing = Color.White.toArgb(),
            puck = Color(0xFF1A73E8).toArgb(),
            start = Color(0xFF12A150).toArgb(),
            end = Color(0xFFD32F2F).toArgb(),
            route = rideColors.route.toArgb(),
            routeDone = (if (dark) Color(0xFF596673) else Color(0xFF9AA8B5)).toArgb(),
        )
    }

    // Itinéraires : préparation et guidage vivent dans le conteneur de l'appli, pas dans cet écran.
    val navigation = container.navigation
    val planner by navigation.planner.collectAsStateWithLifecycle()
    val guidance by navigation.guidance.collectAsStateWithLifecycle()
    var searchTarget by remember { mutableStateOf<PlanTarget?>(null) }
    var pressedPoint by remember { mutableStateOf<LatLon?>(null) }
    var showSavedRoutes by remember { mutableStateOf(false) }
    var routeNameDraft by remember { mutableStateOf<String?>(null) }
    /** L'itinéraire dont on prépare le partage (affiche ou fichier GPX), s'il y en a un. */
    var shareTarget by remember { mutableStateOf<ShareTarget?>(null) }
    var proposeRecording by remember { mutableStateOf(false) }
    /** On demande confirmation avant de raccourcir un trajet enregistré (il sera remplacé). */
    var confirmShorten by remember { mutableStateOf(false) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    /** Propositions d'itinéraire : panneau déplié (le détail) ou replié (la carte en grand). */
    var plannerExpanded by rememberSaveable { mutableStateOf(false) }
    /** Augmente à chaque fois que la carte doit se recadrer sur les itinéraires proposés. */
    var fitRequest by remember { mutableIntStateOf(0) }
    // Cartes hors ligne : la carte se recharge quand une région est installée ou supprimée.
    val offlineRevision by container.offline.store.epoch.collectAsStateWithLifecycle()
    val offlineFiles by container.offline.store.installed.collectAsStateWithLifecycle()
    val offlineInstalled = offlineFiles.keys.any { it.startsWith("fond-") }
    val layer = MapLayers.effective(settings.mapLayer, offlineRevision, offlineInstalled)
    val spec = remember(layer, dark, offlineRevision) { MapStyleSpec(layer, dark, if (layer.offline) offlineRevision else 0) }

    val startRide = rememberStartRide(
        batteryHintShown = settings.batteryHintShown,
        onBatteryHintShown = { vm.setBatteryHintShown() },
        onStarted = { mode = RideMode.DATA },
    )

    // Position vers la carte, seulement quand l'écran est réellement affiché : celle du GPS, ou la position
    // approchée du téléphone tant qu'il cherche ses satellites (un simple point, sans sens de marche).
    val lifecycleOwner = LocalLifecycleOwner.current
    val access = startRide.access
    LaunchedEffect(isForeground, access) {
        if (isForeground && access != LocationAccess.NONE) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.gps.positions.collect { gps ->
                    lastBearing = if (gps.speedMps >= MOVING_MPS) gps.courseDeg else null
                    var shown = gps.location
                    val guided = container.navigation.guidance.value
                    if (guided != null && !gps.approximate) {
                        val accuracy = if (shown.hasAccuracy()) shown.accuracy.toDouble() else null
                        val snap = container.navigation.snapToRoute(LatLon(shown.latitude, shown.longitude), accuracy)
                        if (snap != null) {
                            // On suit l'itinéraire : la flèche se pose sur le tracé et en prend la direction, à l'arrêt
                            // comme en roulant, et ce qui est derrière elle s'efface. Le GPS d'un téléphone flotte de
                            // quelques mètres : montré tel quel, il ferait zigzaguer la flèche à côté du trait.
                            shown = Location(shown).apply {
                                latitude = snap.position.lat
                                longitude = snap.position.lon
                                bearing = snap.bearingDeg.toFloat()
                            }
                            mapState.setRouteProgress(snap.segment, snap.position)
                        } else if (!shown.hasBearing()) {
                            // Guidage lancé à l'arrêt, à côté du tracé : la flèche montre où part l'itinéraire.
                            shown = Location(shown).apply { bearing = guided.route.bearingAt(guided.nav.progressM).toFloat() }
                        }
                    }
                    mapState.updateLocation(shown)
                }
            }
        }
    }
    val gpsChip by remember(access) { container.gps.status.map { gpsChip(access, it) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = remember(access) { currentGpsChip(container.gps, access) })
    LaunchedEffect(Unit) { vm.track.collect { mapState.setLiveTrack(it) } }
    LaunchedEffect(settings.headingUp) { mapState.setHeadingUp(settings.headingUp) }

    // --- Météo ----------------------------------------------------------------------------------------------
    val weather = container.weather
    val weatherState by weather.here.collectAsStateWithLifecycle()
    val routeWeather by weather.route.collectAsStateWithLifecycle()
    var showWeather by remember { mutableStateOf(false) }
    /** Heure de départ choisie pour la météo du trajet ; null = maintenant. */
    var departureChoice by rememberSaveable { mutableStateOf<Long?>(null) }
    val nowMs by produceState(System.currentTimeMillis(), isForeground) {
        while (isForeground) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // Là où l'on est, pour la météo : la sortie en cours, sinon la dernière position connue (sans allumer le GPS).
    val weatherPosition = {
        val lat = snapshot.lat
        val lon = snapshot.lon
        if (snapshot.isActive && lat != null && lon != null) LatLon(lat, lon) else container.gps.lastKnown()
    }
    // Tant que l'écran est affiché, la météo est rafraîchie quand elle a vieilli (au plus toutes les dix minutes).
    LaunchedEffect(isForeground, settings.weatherEnabled, access) {
        if (isForeground && settings.weatherEnabled) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val position = weatherPosition()
                    weather.refresh(position)
                    delay(
                        when {
                            // Pas encore de position : on réessaie vite, cela ne coûte rien.
                            position == null -> 5_000L
                            // Pas de réseau : inutile d'insister.
                            weather.here.value.failed -> 120_000L
                            else -> 60_000L
                        }
                    )
                }
            }
        }
    }
    // Une heure de départ dépassée redevient « maintenant ».
    val departureMs = departureChoice?.takeIf { it > nowMs }
    val departureChoices = remember(nowMs / (10 * 60_000L)) { departureChoices(nowMs) }
    val glance = remember(weatherState.report, nowMs, lastBearing, settings.weatherEnabled) {
        if (settings.weatherEnabled) weatherGlance(weatherState.report, nowMs, lastBearing) else null
    }
    LaunchedEffect(planner.options) { departureChoice = null }
    // En guidage, la météo du trajet est (re)demandée quand on ouvre la feuille.
    LaunchedEffect(showWeather, guidance?.route) {
        val current = guidance
        if (showWeather && current != null) weather.requestRoute(current.route, current.nav.progressM)
    }
    val plannerWeather = remember(routeWeather, planner.selectedOption, nowMs, departureMs, settings.weatherEnabled) {
        val selected = planner.selectedOption?.route
        val state = routeWeather?.takeIf { it.route === selected }
        when {
            !settings.weatherEnabled || selected == null -> null
            state?.reports != null -> {
                val summary = RouteWeather.summary(state.rows(departureMs ?: nowMs))
                val headline = RouteWeather.headline(summary) { Dates.time(it) }
                PlannerWeather(
                    text = if (departureMs != null) "Départ ${departureLabel(departureMs, nowMs).lowercase()} : $headline" else headline.replaceFirstChar { it.uppercase() },
                    wet = summary.firstRain != null,
                )
            }
            state == null || state.loading -> PlannerWeather("Recherche des prévisions…")
            else -> PlannerWeather("Indisponible sans réseau")
        }
    }

    // --- Itinéraires sur la carte --------------------------------------------------------------------------
    val screenHeightPx = LocalWindowInfo.current.containerSize.height
    val fitPaddingPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    // À droite, le tracé s'arrête avant la colonne de boutons de la carte : ni le départ ni l'arrivée ne s'y cachent.
    val fitEndPx = with(LocalDensity.current) { MAP_BUTTONS_WIDTH.roundToPx() }
    LaunchedEffect(planner.open, planner.options, planner.selected, guidance?.route) {
        val current = guidance
        when {
            current != null -> mapState.setRoutes(listOf(current.route.points), 0)
            planner.open && planner.options.isNotEmpty() -> mapState.setRoutes(planner.options.map { it.route.points }, planner.selected)
            else -> mapState.setRoutes(emptyList(), 0)
        }
    }
    LaunchedEffect(planner.open, planner.start, planner.vias, planner.end, planner.options, guidance?.route) {
        val current = guidance
        val shown = planner.selectedOption?.route
        mapState.setPlanMarkers(
            when {
                current != null -> waypointMarkers(current.route.waypoints, withStart = false)
                planner.open && planner.isImported && shown != null -> waypointMarkers(shown.waypoints, withStart = true)
                planner.open -> listOfNotNull(planner.start?.let { PlanMarker(it.position, PlanMarker.Kind.START) }) +
                    planner.vias.mapIndexed { index, via -> PlanMarker(via.position, PlanMarker.Kind.VIA, number = index + 1) } +
                    listOfNotNull(planner.end?.let { PlanMarker(it.position, PlanMarker.Kind.END) })
                else -> emptyList()
            }
        )
    }
    val fitDensity = LocalDensity.current
    val fitTopPx = with(fitDensity) { (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_HEIGHT).roundToPx() } + fitPaddingPx
    val bottomPaddingPx = with(fitDensity) { bottomPadding.roundToPx() }
    /** Cadre la carte sur les itinéraires proposés, dans la place que laisse le panneau. */
    val fitPlannedRoutes: (Boolean) -> Unit = { animate ->
        BoundingBox.of(planner.options.flatMap { it.route.points })?.let { box ->
            mapState.fit(
                box, fitPaddingPx, topPx = fitTopPx, bottomPx = panelHeightPx + bottomPaddingPx + fitPaddingPx, endPx = fitEndPx,
                animate = animate,
            )
        }
    }
    // Quand des propositions arrivent (ou qu'on rouvre le panneau sur elles), il se replie et la carte se cadre
    // dessus, en grand.
    LaunchedEffect(planner.options, planner.open) {
        if (planner.open && planner.options.isNotEmpty()) {
            plannerExpanded = false
            // Le temps que le panneau prenne sa nouvelle taille : c'est elle qui dit où cadrer.
            awaitStable { panelHeightPx }
            fitPlannedRoutes(false)
        }
    }
    LaunchedEffect(fitRequest) {
        if (fitRequest > 0 && planner.open && planner.options.isNotEmpty()) {
            awaitStable { panelHeightPx }
            fitPlannedRoutes(true)
        }
    }
    // Un itinéraire qu'on prépare ou qu'on rouvre (depuis l'onglet « Trajets », par exemple) se regarde sur la carte.
    LaunchedEffect(planner.open, planner.options) { if (planner.open) mode = RideMode.MAP }
    // Guidage : la carte passe en vue de guidage (dans le sens de la marche, inclinée) et suit la position.
    LaunchedEffect(guidance != null) {
        mapState.setNavigation(guidance != null)
        if (guidance == null) mapState.clearRouteProgress()
    }
    // La prochaine manœuvre, dessinée en flèche sur le tracé (une trace importée n'a pas de consignes de virage).
    val nextManeuver = guidance?.takeIf { !it.followOnly && !it.nav.arrived }?.nav?.next?.takeIf { it.maneuver != Maneuver.ARRIVE }
    LaunchedEffect(guidance?.route, nextManeuver) {
        val route = guidance?.route
        mapState.setManeuverArrow(if (route != null && nextManeuver != null) route.arrowFor(nextManeuver) else emptyList())
    }
    val navTopPx = with(fitDensity) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_HEIGHT + 12.dp + GUIDANCE_BANNER_HEIGHT + 8.dp +
            (if (snapshot.isActive) MAP_STRIP_HEIGHT + 8.dp else 0.dp)).roundToPx()
    }
    val navBottomPx = with(fitDensity) { (bottomPadding + CONTROLS_HEIGHT + 24.dp).roundToPx() }
    LaunchedEffect(navTopPx, navBottomPx) { mapState.setNavigationInsets(navTopPx, navBottomPx) }
    // Dès qu'on touche à la carte, le panneau déplié se replie pour la laisser voir ; un appui sur un autre
    // itinéraire proposé le choisit.
    DisposableEffect(mapState) {
        mapState.onUserMove = { if (planner.open && planner.options.isNotEmpty() && plannerExpanded) plannerExpanded = false }
        mapState.onRouteTapped = { index -> if (planner.open) navigation.select(index) }
        onDispose {
            mapState.onUserMove = null
            mapState.onRouteTapped = null
        }
    }
    // Un appui long sur la carte place un point d'itinéraire.
    DisposableEffect(mapState, planner.open, planner.end == null, planner.loop, planner.options.isEmpty()) {
        mapState.onLongPress = { point ->
            if (Geo.FRANCE.contains(point.lat, point.lon)) {
                val placing = planner.open && planner.options.isEmpty()
                when {
                    // En boucle, chaque appui long ajoute une étape, dans l'ordre où on les place.
                    placing && planner.loop -> navigation.addVia(point)
                    placing && planner.end == null -> navigation.setEnd(point)
                    else -> pressedPoint = point
                }
            }
        }
        onDispose { mapState.onLongPress = null }
    }
    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }?.removeSuffix(".gpx")?.removeSuffix(".GPX")
                        val file = context.contentResolver.openInputStream(uri)?.use { Gpx.read(it) }
                        (file?.name ?: name) to file?.allPoints.orEmpty()
                    }.getOrNull()
                }
                if (imported == null) snackbar.showSnackbar("Ce fichier n'est pas une trace GPX lisible.")
                else navigation.importTrack(imported.first, imported.second)
            }
        }
    }

    // La carte cesse d'être dessinée dès qu'elle est recouverte (après la fin de l'animation).
    val mapVisible = isForeground && mode == RideMode.MAP
    LaunchedEffect(mapVisible) {
        if (mapVisible) mapState.setVisible(true) else {
            delay(450)
            mapState.setVisible(false)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is RecorderEvent.Saved -> {
                    mode = RideMode.MAP
                    onRideSaved(event.rideId)
                }
                RecorderEvent.TooShort -> {
                    mode = RideMode.MAP
                    snackbar.showSnackbar("Sortie trop courte : elle n'a pas été enregistrée.")
                }
            }
        }
    }

    // Écran toujours allumé et luminosité maximale pendant une sortie, selon les réglages.
    val view = LocalView.current
    val keepScreenOn = isForeground && (snapshot.isActive || guidance != null) && settings.keepScreenOn
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val activity = remember(context) { context.findActivity() }
    val boostBrightness = isForeground && snapshot.isActive && settings.maxBrightness
    DisposableEffect(boostBrightness) {
        val window = activity?.window
        fun apply(value: Float) {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value } }
        }
        apply(if (boostBrightness) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }

    val sunlight = mode == RideMode.DATA && settings.sunlightDashboard
    // Icônes de la barre d'état sombres sur fond clair, claires sur fond sombre.
    if (isForeground) {
        val lightBackground = sunlight || !dark
        DisposableEffect(lightBackground) {
            activity?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = lightBackground }
            onDispose { activity?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark } }
        }
    }

    BackHandler(enabled = isForeground && mode == RideMode.DATA && !snapshot.isActive) { mode = RideMode.MAP }
    // Retour : des propositions vers les points, puis fermeture du panneau d'itinéraire.
    BackHandler(enabled = isForeground && planner.open) {
        when {
            planner.options.isNotEmpty() && plannerExpanded -> {
                plannerExpanded = false
                fitRequest++
            }
            planner.options.isNotEmpty() && !planner.isImported -> navigation.editPoints()
            else -> navigation.closePlanner()
        }
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    LaunchedEffect(topInset) { mapState.setTopInset(with(density) { (topInset + TOP_BAR_HEIGHT + 8.dp).roundToPx() }) }

    val plannerActions = object : PlannerActions {
        override fun close() = navigation.closePlanner()
        override fun setBike(bike: BikeType) = navigation.setBike(bike)
        override fun pick(target: PlanTarget) { searchTarget = target }
        override fun useMyPosition() = navigation.setStart(null)
        override fun removeVia(index: Int) = navigation.removeVia(index)
        override fun moveViaUp(index: Int) = navigation.moveViaUp(index)
        override fun removeEnd() = navigation.removeEnd()
        override fun setLoop(enabled: Boolean) = navigation.setLoop(enabled)
        override fun reverse() = navigation.reverse()
        override fun compute() = navigation.compute()
        override fun stopAtStart() {
            // Un trajet enregistré sera réenregistré raccourci : on demande d'abord. Sinon, rien n'est perdu.
            if (planner.savedId != null) confirmShorten = true else navigation.stopAtStart()
        }
        override fun edit() = navigation.editPoints()
        override fun select(index: Int) = navigation.select(index)
        override fun startGuidance() {
            navigation.startGuidance()
            // Sans enregistrement, le guidage s'arrête quand l'écran s'éteint : on propose d'enregistrer aussi.
            if (!snapshot.isActive) proposeRecording = true
        }
        override fun toggleVoice() { vm.setVoiceGuidance(!settings.voiceGuidance) }
        override fun save() { routeNameDraft = planner.importedName ?: if (planner.loop) "Boucle" else planner.end?.label ?: "Itinéraire" }
        override fun share() {
            val route = planner.selectedOption?.route ?: return
            val destination = planner.end?.label?.takeIf { it != NavigationController.MAP_POINT }
            shareTarget = ShareTarget(
                route = route,
                title = planner.importedName ?: "Sortie vélo",
                subtitle = if (planner.isImported || destination == null) "" else "Vers $destination",
            )
        }
        override fun importGpx() { runCatching { gpxPicker.launch(arrayOf("*/*")) } }
        override fun openSaved() { showSavedRoutes = true }
        override fun openOfflineMaps() {
            navigation.closePlanner()
            onOpenOfflineMaps()
        }
        override fun openWeather() { showWeather = true }
    }
    val panelHeight = with(density) { panelHeightPx.toDp() }

    // Recouvert par un autre onglet, l'habillage de la carte n'a plus à être dessiné : sans cela il le serait, pour
    // rien, à chaque image de la liste qu'on fait défiler par-dessus. On attend la fin du fondu pour le couper.
    var chromeDrawn by remember { mutableStateOf(true) }
    LaunchedEffect(isForeground) {
        if (isForeground) chromeDrawn = true else {
            delay(450)
            chromeDrawn = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        VeloMap(mapState, spec, mapColors, Modifier.fillMaxSize())
      Box(Modifier.fillMaxSize().drawWithContent { if (chromeDrawn) drawContent() }) {
        RideChrome(
            snapshot = snapshot,
            mode = mode,
            onMode = { mode = it },
            themeMode = settings.themeMode,
            sunlight = sunlight,
            attribution = layer.attribution,
            onAttribution = { openUrl(context, layer.attributionUrl) },
            following = mapState.isFollowing,
            headingUp = settings.headingUp,
            hasLocationPermission = access != LocationAccess.NONE,
            gps = gpsChip,
            onGps = { showGps = true },
            topInset = topInset,
            bottomPadding = bottomPadding,
            snackbar = snackbar,
            guidance = guidance,
            plannerOpen = planner.open,
            plannerHeight = if (planner.open) panelHeight else 0.dp,
            routesShown = planner.open && planner.options.isNotEmpty(),
            onFitRoute = {
                val current = guidance
                if (current == null) fitPlannedRoutes(true)
                else BoundingBox.of(current.route.points)?.let { box ->
                    mapState.fit(
                        box, fitPaddingPx, topPx = navTopPx + fitPaddingPx, bottomPx = navBottomPx + fitPaddingPx, endPx = fitEndPx,
                        animate = true,
                    )
                }
            },
            onRoute = { navigation.openPlanner() },
            onStopGuidance = { navigation.stopGuidance() },
            weather = glance,
            onWeather = { showWeather = true },
            onPrivacy = onOpenPrivacy,
            onLayers = { showLayers = true },
            onRecenter = { mapState.follow() },
            onToggleHeading = { vm.setHeadingUp(!settings.headingUp) },
            onRequestPermission = startRide.requestLocationPermission,
            onStart = startRide.start,
            onPause = { RecordingService.pause(context) },
            onResume = { RecordingService.resume(context) },
            onStop = { RecordingService.stop(context) },
            onStopHint = {
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar("Maintiens « Terminer » appuyé pour arrêter la sortie.")
                }
            },
        )

        // --- Panneau d'itinéraire, posé au-dessus de la barre d'onglets -------------------------------
        AnimatedVisibility(
            visible = planner.open && isForeground,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding).widthIn(max = COMPACT_WIDTH + 20.dp),
        ) {
            PlannerPanel(
                state = planner,
                hasRoutingData = offlineFiles.keys.any { it.endsWith(".rd5") },
                voiceEnabled = settings.voiceGuidance,
                actions = plannerActions,
                maxHeight = with(density) { (screenHeightPx * 0.6f).toDp() },
                modifier = Modifier.onSizeChanged { panelHeightPx = it.height },
                weather = plannerWeather,
                expanded = plannerExpanded,
                onExpandedChange = { wanted ->
                    if (plannerExpanded != wanted) {
                        plannerExpanded = wanted
                        fitRequest++
                    }
                },
            )
        }
      }
    }

    searchTarget?.let { target ->
        PlaceSearchDialog(
            title = when (target) {
                PlanTarget.START -> "Point de départ"
                PlanTarget.VIA -> "Passer par…"
                PlanTarget.END -> "Où vas-tu ?"
            },
            offerMyPosition = target == PlanTarget.START,
            offerLoop = target == PlanTarget.END,
            onLoop = {
                navigation.setLoop(true)
                searchTarget = null
            },
            search = { text -> runCatching { navigation.search(text, mapState.center()) }.getOrNull() },
            onPlace = { place ->
                when (target) {
                    PlanTarget.START -> navigation.setStart(place.position, place.title)
                    PlanTarget.VIA -> navigation.addVia(place.position, place.title)
                    PlanTarget.END -> navigation.setEnd(place.position, place.title)
                }
                searchTarget = null
            },
            onMyPosition = {
                navigation.setStart(null)
                searchTarget = null
            },
            onDismiss = { searchTarget = null },
        )
    }

    pressedPoint?.let { point ->
        AlertDialog(
            onDismissRequest = { pressedPoint = null },
            title = { Text("Ce point sur la carte") },
            text = {
                Column {
                    TextButton(onClick = {
                        if (!planner.open) navigation.openPlanner()
                        navigation.setEnd(point)
                        pressedPoint = null
                    }) { Text("Aller ici") }
                    if (planner.open && !planner.isImported) {
                        if (planner.end != null || planner.loop) {
                            TextButton(onClick = {
                                navigation.addVia(point)
                                pressedPoint = null
                            }) { Text("Passer par ici") }
                        }
                        TextButton(onClick = {
                            navigation.setStart(point)
                            pressedPoint = null
                        }) { Text("Partir d'ici") }
                    }
                }
            },
            confirmButton = { },
            dismissButton = { TextButton(onClick = { pressedPoint = null }) { Text("Annuler") } },
        )
    }

    routeNameDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { routeNameDraft = null },
            title = { Text("Enregistrer l'itinéraire") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { routeNameDraft = it.take(60) }, singleLine = true, label = { Text("Nom") })
            },
            confirmButton = {
                Button(onClick = {
                    val route = planner.selectedOption?.route
                    routeNameDraft = null
                    if (route != null) {
                        scope.launch {
                            container.savedRoutes.save(draft, route, planner.bike)
                            snackbar.showSnackbar("Itinéraire enregistré. Tu le retrouves dans l'onglet « Trajets ».")
                        }
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { routeNameDraft = null }) { Text("Annuler") } },
        )
    }

    shareTarget?.let { target ->
        RouteShareSheet(target.route, target.title, target.subtitle, onDismiss = { shareTarget = null })
    }

    // Trajet enregistré qui repasse par son départ puis continue : on le raccourcit, après accord, et la version
    // raccourcie remplace l'ancienne dans les trajets enregistrés.
    if (confirmShorten) {
        val route = planner.selectedOption?.route
        val back = remember(route) { route?.let { RouteShape.returnToStart(it) } }
        val savedId = planner.savedId
        if (route == null || back == null || savedId == null) {
            confirmShorten = false
        } else {
            AlertDialog(
                onDismissRequest = { confirmShorten = false },
                title = { Text("Arrêter ce trajet au départ ?") },
                text = {
                    Text(
                        "« ${planner.importedName} » fera ${Formats.distanceAuto(back.distanceM)} au lieu de ${Formats.distanceAuto(route.distanceM)} : " +
                            "il s'arrêtera à son retour au point de départ. Les ${Formats.distanceAuto(back.remainingM)} retirés " +
                            "sont ceux qui repartaient ensuite vers l'ancienne arrivée. Le trajet enregistré sera remplacé."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        confirmShorten = false
                        val shortened = navigation.stopAtStart()
                        if (shortened != null) {
                            scope.launch {
                                container.savedRoutes.replace(savedId, shortened)
                                fitRequest++
                                snackbar.showSnackbar("Trajet raccourci : ${Formats.distanceAuto(shortened.distanceM)}.")
                            }
                        }
                    }) { Text("Raccourcir") }
                },
                dismissButton = { TextButton(onClick = { confirmShorten = false }) { Text("Annuler") } },
            )
        }
    }

    if (showSavedRoutes) {
        val saved by container.savedRoutes.observe().collectAsStateWithLifecycle(initialValue = emptyList())
        ModalBottomSheet(onDismissRequest = { showSavedRoutes = false }) {
            SavedRoutesList(
                routes = saved,
                onOpen = { id ->
                    showSavedRoutes = false
                    scope.launch {
                        val loaded = container.savedRoutes.load(id)
                        if (loaded == null) snackbar.showSnackbar("Cet itinéraire n'est plus lisible.")
                        else navigation.showSaved(loaded.first.name, loaded.second, BikeType.entries.firstOrNull { it.name == loaded.first.bike } ?: BikeType.HYBRID, savedId = id)
                    }
                },
                onDelete = { id -> scope.launch { container.savedRoutes.delete(id) } },
            )
        }
    }

    if (proposeRecording && !snapshot.isActive && guidance != null) {
        AlertDialog(
            onDismissRequest = { proposeRecording = false },
            title = { Text("Enregistrer aussi la sortie ?") },
            text = {
                Text(
                    "Avec l'enregistrement, le guidage continue quand l'écran s'éteint ou que tu changes d'appli. " +
                        "Sans lui, il s'arrête dès que VeloGo n'est plus affichée."
                )
            },
            confirmButton = {
                Button(onClick = {
                    proposeRecording = false
                    startRide.start()
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { proposeRecording = false }) { Text("Pas maintenant") } },
        )
    }

    if (showWeather) {
        val current = guidance
        val state = routeWeather
        val progressM = current?.nav?.progressM ?: 0.0
        // Les lignes ne sont recalculées que toutes les demi-minutes ou tous les 250 m, pas à chaque position.
        val section = remember(state, current?.route, planner.open, planner.selectedOption, nowMs, (progressM / 250).toInt(), departureMs) {
            when {
                state == null -> null
                current != null && state.route === current.route -> RouteWeatherSection(
                    guiding = true, rows = state.rows(nowMs, progressM), loading = state.loading, failed = state.failed,
                    originM = progressM, totalM = state.route.distanceM,
                )
                current == null && planner.open && state.route === planner.selectedOption?.route -> RouteWeatherSection(
                    guiding = false, rows = state.rows(departureMs ?: nowMs), loading = state.loading, failed = state.failed,
                    originM = 0.0, totalM = state.route.distanceM, departureMs = departureMs, choices = departureChoices,
                )
                else -> null
            }
        }
        val hasPosition = remember(weatherState, snapshot.isActive) { weatherState.position != null || weatherPosition() != null }
        // Ouverte en grand d'emblée : les prévisions heure par heure se lisent sans avoir à tirer la feuille.
        ModalBottomSheet(onDismissRequest = { showWeather = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            WeatherContent(
                state = WeatherSheetState(
                    nowMs = nowMs,
                    report = weatherState.report,
                    loading = weatherState.loading,
                    failed = weatherState.failed,
                    hasPosition = hasPosition,
                    bearingDeg = lastBearing,
                    route = section,
                ),
                onRefresh = {
                    weather.refresh(weatherPosition(), force = true)
                    val route = current?.route ?: planner.selectedOption?.route?.takeIf { planner.open }
                    if (route != null) weather.requestRoute(route, progressM, force = true)
                },
                onDeparture = { departureChoice = it },
                onSource = { openUrl(context, "https://open-meteo.com/") },
            )
        }
    }

    if (showGps) {
        ModalBottomSheet(onDismissRequest = { showGps = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            // L'état est lu ici, dans la feuille : pendant une recherche il change à chaque seconde.
            val status by container.gps.status.collectAsStateWithLifecycle()
            GpsSheetContent(
                model = gpsSheet(access, status),
                onRemedy = { remedy ->
                    when (remedy) {
                        GpsRemedy.LOCATION_SETTINGS -> runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        GpsRemedy.ALLOW_PRECISE -> {
                            showGps = false
                            startRide.requestLocationPermission()
                        }
                        GpsRemedy.RESTART -> container.gps.kick()
                    }
                },
                onReset = { container.gps.kick(reset = true) },
            )
        }
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }) {
            Text("Fond de carte", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            for (candidate in MapLayers.ALL) {
                // La carte hors ligne ne peut être choisie qu'une fois une région téléchargée.
                val needsDownload = candidate.offline && !offlineInstalled
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            showLayers = false
                            if (needsDownload) onOpenOfflineMaps() else vm.setMapLayer(candidate.id)
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (needsDownload) "Télécharge d'abord une région pour rouler sans réseau." else candidate.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (needsDownload) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        RadioButton(selected = candidate.id == layer.id, onClick = null)
                    }
                }
            }
            TextButton(
                onClick = {
                    showLayers = false
                    onOpenOfflineMaps()
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("Gérer les cartes hors ligne") }
            Spacer(Modifier.height(16.dp))
        }
    }

    interrupted?.let { ride ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Sortie interrompue") },
            text = {
                Text(
                    "L'enregistrement commencé ${Dates.relativeDay(ride.startTimeMs)} à ${Dates.time(ride.startTimeMs)} " +
                        "(${Formats.distanceKm(ride.distanceM)} km) s'est arrêté avant la fin. " +
                        "Tu peux le reprendre maintenant ou le garder tel quel dans l'historique."
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.onResumeInterrupted()
                    startRide.start()
                }) { Text("Reprendre") }
            },
            dismissButton = { TextButton(onClick = { vm.finishInterrupted(onRideSaved) }) { Text("Terminer") } },
        )
    }
}

/** Un itinéraire à partager, avec ce que son affiche annonce au départ (l'utilisateur peut le changer). */
private class ShareTarget(val route: Route, val title: String, val subtitle: String)

/** Deux repères à moins de cette distance (m) l'un de l'autre désignent le même endroit. */
private const val SAME_PLACE_M = 40.0

/**
 * Repères d'un itinéraire déjà calculé : étapes numérotées et arrivée, et le départ si on le demande. Une boucle
 * part et arrive au même endroit : un seul repère y suffit (celui du départ quand il est montré).
 */
private fun waypointMarkers(waypoints: List<LatLon>, withStart: Boolean): List<PlanMarker> {
    val loop = waypoints.size > 2 && Geo.distanceM(waypoints.first(), waypoints.last()) <= SAME_PLACE_M
    return waypoints.mapIndexedNotNull { index, point ->
        when {
            index == 0 -> if (withStart) PlanMarker(point, PlanMarker.Kind.START) else null
            index == waypoints.size - 1 -> if (loop && withStart) null else PlanMarker(point, PlanMarker.Kind.END)
            else -> PlanMarker(point, PlanMarker.Kind.VIA, number = index)
        }
    }
}

/**
 * Tout ce qui se pose sur la carte : tableau de bord, barre du haut, boutons flottants, commandes.
 * La carte elle-même n'en fait pas partie, ce qui permet de vérifier cet habillage en image, sans téléphone.
 */
@Composable
fun RideChrome(
    snapshot: RideSnapshot,
    mode: RideMode,
    onMode: (RideMode) -> Unit,
    themeMode: ThemeMode,
    sunlight: Boolean,
    attribution: String,
    following: Boolean,
    headingUp: Boolean,
    /** L'appli a le droit de connaître la position, au moins approximativement. */
    hasLocationPermission: Boolean,
    /** L'état du GPS, affiché en haut à droite ; un appui ([onGps]) ouvre la feuille qui l'explique. */
    gps: GpsChipModel,
    topInset: Dp,
    bottomPadding: Dp,
    snackbar: SnackbarHostState,
    onLayers: () -> Unit,
    onRecenter: () -> Unit,
    onToggleHeading: () -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onStopHint: () -> Unit,
    modifier: Modifier = Modifier,
    /** Guidage en cours, s'il y en a un : son bandeau s'affiche sous la barre du haut. */
    guidance: GuidanceState? = null,
    /** Le panneau d'itinéraire est ouvert : il remplace les boutons de la sortie, sur [plannerHeight]. */
    plannerOpen: Boolean = false,
    plannerHeight: Dp = 0.dp,
    /** Des itinéraires proposés sont dessinés sur la carte : un bouton permet de les revoir en entier. */
    routesShown: Boolean = false,
    onFitRoute: () -> Unit = {},
    onRoute: () -> Unit = {},
    onStopGuidance: () -> Unit = {},
    /** Un appui sur la mention des sources de la carte : ouvre la page de leurs droits. */
    onAttribution: () -> Unit = {},
    /** La météo du moment, si elle est connue : pastille sur la carte, ligne sous les chiffres. */
    weather: WeatherGlance? = null,
    onWeather: () -> Unit = {},
    onGps: () -> Unit = {},
    /** Ouvre la politique de confidentialité (lien sous l'explication qui précède la demande de position). */
    onPrivacy: () -> Unit = {},
) {
    VeloGoTheme(mode = themeMode, sunlight = sunlight) {
        Box(modifier.fillMaxSize()) {
            val bannerSpace = if (guidance != null) GUIDANCE_BANNER_HEIGHT + 8.dp else 0.dp
            // --- Mode carte : éléments flottants ---------------------------------------------------------
            if (mode == RideMode.MAP) {
                MapOverlays(
                    snapshot = snapshot,
                    attribution = attribution,
                    onAttribution = onAttribution,
                    following = following,
                    headingUp = headingUp,
                    hasLocationPermission = hasLocationPermission,
                    topPadding = topInset + TOP_BAR_HEIGHT + 12.dp + bannerSpace,
                    bottomPadding = bottomPadding + (if (plannerOpen) plannerHeight + 12.dp else CONTROLS_HEIGHT + 24.dp),
                    showRouteButton = !plannerOpen,
                    guiding = guidance != null,
                    showFitRoute = routesShown || guidance != null,
                    onFitRoute = onFitRoute,
                    weather = weather,
                    onWeather = onWeather,
                    onLayers = onLayers,
                    onRecenter = onRecenter,
                    onToggleHeading = onToggleHeading,
                    onRequestPermission = onRequestPermission,
                    onRoute = onRoute,
                    onPrivacy = onPrivacy,
                )
            }

            // --- Mode données : le tableau de bord recouvre la carte --------------------------------------
            AnimatedVisibility(
                visible = mode == RideMode.DATA,
                enter = fadeIn() + slideInVertically { it / 10 },
                exit = fadeOut() + slideOutVertically { it / 10 },
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = topInset + TOP_BAR_HEIGHT + 10.dp + bannerSpace, bottom = bottomPadding + CONTROLS_HEIGHT + 22.dp),
                    ) {
                        Dashboard(snapshot = snapshot, modifier = Modifier.fillMaxWidth().weight(1f))
                        if (weather != null) WeatherStrip(weather, onWeather, Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
                    }
                }
            }

            // --- Barre du haut : bascule Carte / Données et état du GPS -----------------------------------
            TopBar(
                mode = mode,
                onMode = onMode,
                gps = gps.takeIf { hasLocationPermission },
                onGps = onGps,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + 6.dp).padding(horizontal = 12.dp),
            )

            // --- Guidage : prochaine manœuvre --------------------------------------------------------------
            if (guidance != null) {
                GuidanceBanner(
                    guidance = guidance,
                    onStop = onStopGuidance,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + TOP_BAR_HEIGHT + 12.dp).padding(horizontal = 12.dp)
                        .widthIn(max = COMPACT_WIDTH),
                )
            }

            // --- Boutons de la sortie ---------------------------------------------------------------------
            // Sur un grand écran, les commandes gardent une largeur de téléphone, au milieu.
            Column(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
                    .padding(bottom = bottomPadding + (if (plannerOpen) plannerHeight else 0.dp) + 10.dp)
                    .widthIn(max = COMPACT_WIDTH)
            ) {
                SnackbarHost(snackbar, Modifier.padding(bottom = 8.dp))
                if (!plannerOpen) {
                    RideControls(
                        state = snapshot.state,
                        onStart = onStart,
                        onPause = onPause,
                        onResume = onResume,
                        onStop = onStop,
                        onStopHint = onStopHint,
                    )
                }
            }
        }
    }
}

private val TOP_BAR_HEIGHT = 48.dp
/** Hauteur du rappel des données posé sur la carte pendant une sortie. */
private val MAP_STRIP_HEIGHT = 78.dp
/** Largeur que prend, à droite de la carte, la colonne de boutons (marge, bouton, un peu d'air). */
private val MAP_BUTTONS_WIDTH = 76.dp

/**
 * Attend qu'une mesure (la hauteur du panneau d'itinéraire, qui s'anime) ne bouge plus : un quart de seconde
 * sans changement, ou une seconde et demie au plus.
 */
private suspend fun awaitStable(value: () -> Int) {
    var last = value()
    var calm = 0
    repeat(25) {
        delay(60)
        val now = value()
        if (now == last) {
            if (++calm >= 4) return
        } else {
            calm = 0
            last = now
        }
    }
}
/** En dessous de cette vitesse, le cap donné par le GPS ne veut rien dire. */
private const val MOVING_MPS = 2.0

@Composable
private fun TopBar(
    mode: RideMode,
    onMode: (RideMode) -> Unit,
    /** La pastille du GPS, ou null tant que la position n'est pas autorisée. */
    gps: GpsChipModel?,
    onGps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(TOP_BAR_HEIGHT)) {
        // Sur un écran étroit, la bascule Carte / Données cède un peu de place à la pastille du GPS.
        val switchWidth = (maxWidth - GPS_CHIP_ROOM).coerceIn(SWITCH_MIN_WIDTH, SWITCH_WIDTH)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Segmented(MODES, mode, onMode, Modifier.width(switchWidth), floating = true, height = 44.dp)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            if (gps != null) GpsChip(gps, onGps)
        }
    }
}

private val MODES = listOf(RideMode.MAP to "Carte", RideMode.DATA to "Données")

/** La pastille du GPS telle qu'elle doit s'afficher à l'instant, avant la première mise à jour reçue. */
private fun currentGpsChip(gps: GpsSource, access: LocationAccess): GpsChipModel = gpsChip(access, gps.status.value)

private val SWITCH_WIDTH = 212.dp
private val SWITCH_MIN_WIDTH = 168.dp
/** Place gardée à droite de la bascule pour la pastille du GPS (« Recherche GPS » est son texte le plus long). */
private val GPS_CHIP_ROOM = 158.dp

@Composable
private fun MapOverlays(
    snapshot: RideSnapshot,
    attribution: String,
    onAttribution: () -> Unit,
    following: Boolean,
    headingUp: Boolean,
    hasLocationPermission: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    showRouteButton: Boolean,
    guiding: Boolean,
    showFitRoute: Boolean,
    onFitRoute: () -> Unit,
    weather: WeatherGlance?,
    onWeather: () -> Unit,
    onLayers: () -> Unit,
    onRecenter: () -> Unit,
    onToggleHeading: () -> Unit,
    onRequestPermission: () -> Unit,
    onRoute: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val glass = LocalGlass.current
    val active = LocalRideColors.current.active
    Box(Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding).padding(horizontal = 12.dp)) {
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (snapshot.isActive) MapStatsStrip(snapshot)
            // La météo se range à droite, sous le rappel des données quand une sortie est en cours. Panneau
            // d'itinéraire ouvert, elle laisse la place aux boutons de la carte : le panneau a sa propre ligne météo.
            if (weather != null && hasLocationPermission && showRouteButton) WeatherChip(weather, onWeather, Modifier.align(Alignment.End))
        }

        // À droite : le bouton d'itinéraire, en vert, puis les réglages de la carte réunis dans une seule pilule.
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showRouteButton) {
                GlassIconButton(R.drawable.ic_route, "Itinéraire", onRoute, style = glass.accent, size = 52.dp, iconSize = 26.dp)
            }
            GlassControlStack(
                controls = buildList {
                    if (showFitRoute) add(StackControl(R.drawable.ic_fit_route, "Voir tout l'itinéraire", onClick = onFitRoute))
                    add(StackControl(R.drawable.ic_layers, "Changer de fond de carte", onClick = onLayers))
                    if (hasLocationPermission) {
                        // En guidage, la carte est toujours dans le sens de la marche : ce réglage ne sert qu'en dehors.
                        if (!guiding) {
                            add(
                                StackControl(
                                    icon = if (headingUp) R.drawable.ic_navigation_filled else R.drawable.ic_north,
                                    description = if (headingUp) "Carte orientée dans le sens de la marche" else "Nord en haut",
                                    active = headingUp,
                                    onClick = onToggleHeading,
                                )
                            )
                        }
                        add(
                            StackControl(
                                icon = if (following) R.drawable.ic_my_location_filled else R.drawable.ic_my_location,
                                description = "Recentrer sur ma position",
                                active = following,
                                onClick = onRecenter,
                            )
                        )
                    }
                },
                activeColor = active,
            )
        }

        // En guidage, dès qu'on a déplacé la carte : un gros bouton pour revenir à la vue de guidage.
        if (guiding && !following && hasLocationPermission) {
            GlassPill(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp),
                style = glass.accent,
                onClick = onRecenter,
                reach = GlassDefaults.Reach,
                horizontalPadding = 18.dp,
                verticalPadding = 12.dp,
            ) {
                Icon(painterResource(R.drawable.ic_navigation_filled), contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Recentrer", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Mention obligatoire des sources de la carte, toujours visible ; un appui ouvre la page de leurs droits.
        Text(
            attribution,
            style = MaterialTheme.typography.labelSmall,
            color = glass.regular.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                // Jamais sous les boutons de la carte, à droite : sur un écran étroit, la mention s'abrège avant.
                .padding(end = STACK_CELL + 12.dp)
                .clip(CircleShape)
                .background(glass.regular.fillBottom)
                .clickable(onClickLabel = "Voir les droits de la carte", role = Role.Button, onClick = onAttribution)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )

        if (!hasLocationPermission) {
            // En haut de la carte et aussi compacte que possible : au milieu, ou plus haute, la fiche recouvrait
            // les boutons de la carte sur les petits écrans.
            GlassBox(
                Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, top = 4.dp).widthIn(max = COMPACT_WIDTH),
                style = glass.panel,
                corner = Corners.Panel,
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, tint = active, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Où es-tu ?", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Dit avant de demander l'autorisation : à quoi sert la position et ce qui en sort du téléphone.
                    for (paragraph in LOCATION_DISCLOSURE) {
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TextButton(onClick = onPrivacy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Confidentialité") }
                        Button(onClick = onRequestPermission, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                            Text("Autoriser la position")
                        }
                    }
                }
            }
        }
    }
}

/** Rappel compact des données essentielles quand on regarde la carte pendant une sortie. */
@Composable
private fun MapStatsStrip(snapshot: RideSnapshot, modifier: Modifier = Modifier) {
    val paused = snapshot.state == RideState.PAUSED || snapshot.state == RideState.AUTO_PAUSED
    val caption = LocalGlass.current.regular.content.copy(alpha = 0.66f)
    GlassBox(modifier, corner = 26.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (paused) "PAUSE" else "KM/H", style = LabelCapsStyle.copy(fontSize = 12.sp), color = if (paused) LocalRideColors.current.paused else caption)
                FittedNumber(Formats.speedKmh(snapshot.speedMps), "88,8", Modifier.width(92.dp).height(46.dp), align = Alignment.Start)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("KM", style = LabelCapsStyle.copy(fontSize = 12.sp), color = caption)
                FittedNumber(Formats.distanceKm(snapshot.totals.distanceM), "88,88", Modifier.width(84.dp).height(34.dp), align = Alignment.Start)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("DURÉE", style = LabelCapsStyle.copy(fontSize = 12.sp), color = caption)
                FittedNumber(Formats.duration(snapshot.totals.movingMs), "8:88:88", Modifier.width(96.dp).height(34.dp), align = Alignment.Start)
            }
        }
    }
}
