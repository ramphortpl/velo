package fr.velogo.app.ui.ride

import android.view.WindowManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import fr.velogo.app.data.SavedRouteRepository
import fr.velogo.app.map.PlanMarker
import fr.velogo.app.nav.GuidanceState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
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
    var lastAccuracy by remember { mutableStateOf<Double?>(null) }
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
            cycleway = (if (dark) Color(0xFF37D39A) else Color(0xFF0B8A5C)).toArgb(),
            start = Color(0xFF12A150).toArgb(),
            end = Color(0xFFD32F2F).toArgb(),
            route = rideColors.route.toArgb(),
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
    var proposeRecording by remember { mutableStateOf(false) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
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

    // Position GPS vers la carte, seulement quand l'écran est réellement affiché.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isForeground, startRide.hasLocationPermission) {
        if (isForeground && startRide.hasLocationPermission) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.gps.fixes.collect { gps ->
                    lastAccuracy = gps.fix.accuracyM
                    val speed = gps.fix.speedMps
                    lastBearing = if (speed != null && speed >= MOVING_MPS) gps.fix.bearingDeg else null
                    mapState.updateLocation(gps.location)
                }
            }
        }
    }
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
    LaunchedEffect(isForeground, settings.weatherEnabled, startRide.hasLocationPermission) {
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
                    planner.vias.map { PlanMarker(it.position, PlanMarker.Kind.VIA) } +
                    listOfNotNull(planner.end?.let { PlanMarker(it.position, PlanMarker.Kind.END) })
                else -> emptyList()
            }
        )
    }
    // Quand des propositions arrivent, la carte se cadre dessus, au-dessus du panneau.
    LaunchedEffect(planner.options) {
        if (planner.open && planner.options.isNotEmpty()) {
            BoundingBox.of(planner.options.flatMap { it.route.points })?.let { box ->
                mapState.fit(box, fitPaddingPx, topPx = fitPaddingPx * 3, bottomPx = (screenHeightPx * 0.55f).toInt())
            }
        }
    }
    LaunchedEffect(planner.open) { if (planner.open) mode = RideMode.MAP }
    LaunchedEffect(guidance != null) { if (guidance != null) mapState.follow() }
    // Un appui long sur la carte place un point d'itinéraire.
    DisposableEffect(mapState, planner.open, planner.end == null, planner.options.isEmpty()) {
        mapState.onLongPress = { point ->
            if (Geo.FRANCE.contains(point.lat, point.lon)) {
                if (planner.open && planner.options.isEmpty() && planner.end == null) navigation.setEnd(point) else pressedPoint = point
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
        if (planner.options.isNotEmpty() && !planner.isImported) navigation.editPoints() else navigation.closePlanner()
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
        override fun removeEnd() = navigation.removeEnd()
        override fun reverse() = navigation.reverse()
        override fun compute() = navigation.compute()
        override fun edit() = navigation.editPoints()
        override fun select(index: Int) = navigation.select(index)
        override fun startGuidance() {
            navigation.startGuidance()
            // Sans enregistrement, le guidage s'arrête quand l'écran s'éteint : on propose d'enregistrer aussi.
            if (!snapshot.isActive) proposeRecording = true
        }
        override fun toggleVoice() { vm.setVoiceGuidance(!settings.voiceGuidance) }
        override fun save() { routeNameDraft = planner.importedName ?: planner.end?.label ?: "Itinéraire" }
        override fun share() {
            val route = planner.selectedOption?.route ?: return
            val name = planner.importedName ?: planner.end?.label?.let { "Vers $it" } ?: "Itinéraire VeloGo"
            scope.launch { runCatching { context.startActivity(SavedRouteRepository.shareIntent(context, name, route)) } }
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

    Box(Modifier.fillMaxSize()) {
        VeloMap(mapState, spec, mapColors, Modifier.fillMaxSize())
        RideChrome(
            snapshot = snapshot,
            mode = mode,
            onMode = { mode = it },
            themeMode = settings.themeMode,
            sunlight = sunlight,
            attribution = layer.attribution,
            following = mapState.isFollowing,
            headingUp = settings.headingUp,
            hasLocationPermission = startRide.hasLocationPermission,
            accuracyM = if (snapshot.isActive) snapshot.accuracyM else lastAccuracy,
            topInset = topInset,
            bottomPadding = bottomPadding,
            snackbar = snackbar,
            guidance = guidance,
            plannerOpen = planner.open,
            plannerHeight = if (planner.open) panelHeight else 0.dp,
            onRoute = { navigation.openPlanner() },
            onStopGuidance = { navigation.stopGuidance() },
            weather = glance,
            onWeather = { showWeather = true },
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding),
        ) {
            PlannerPanel(
                state = planner,
                hasRoutingData = offlineFiles.keys.any { it.endsWith(".rd5") },
                voiceEnabled = settings.voiceGuidance,
                actions = plannerActions,
                maxHeight = with(density) { (screenHeightPx * 0.6f).toDp() },
                modifier = Modifier.onSizeChanged { panelHeightPx = it.height },
                weather = plannerWeather,
            )
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
                        if (planner.end != null) {
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
                            snackbar.showSnackbar("Itinéraire enregistré. Tu le retrouves avec le signet du panneau d'itinéraire.")
                        }
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { routeNameDraft = null }) { Text("Annuler") } },
        )
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
                        else navigation.showSaved(loaded.first.name, loaded.second, BikeType.entries.firstOrNull { it.name == loaded.first.bike } ?: BikeType.HYBRID)
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

/** Repères d'un itinéraire déjà calculé : étapes et arrivée, et le départ si on le demande. */
private fun waypointMarkers(waypoints: List<LatLon>, withStart: Boolean): List<PlanMarker> =
    waypoints.mapIndexedNotNull { index, point ->
        when {
            index == 0 -> if (withStart) PlanMarker(point, PlanMarker.Kind.START) else null
            index == waypoints.size - 1 -> PlanMarker(point, PlanMarker.Kind.END)
            else -> PlanMarker(point, PlanMarker.Kind.VIA)
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
    hasLocationPermission: Boolean,
    accuracyM: Double?,
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
    onRoute: () -> Unit = {},
    onStopGuidance: () -> Unit = {},
    /** La météo du moment, si elle est connue : pastille sur la carte, ligne sous les chiffres. */
    weather: WeatherGlance? = null,
    onWeather: () -> Unit = {},
) {
    VeloGoTheme(mode = themeMode, sunlight = sunlight) {
        Box(modifier.fillMaxSize()) {
            val bannerSpace = if (guidance != null) GUIDANCE_BANNER_HEIGHT + 8.dp else 0.dp
            // --- Mode carte : éléments flottants ---------------------------------------------------------
            if (mode == RideMode.MAP) {
                MapOverlays(
                    snapshot = snapshot,
                    attribution = attribution,
                    following = following,
                    headingUp = headingUp,
                    hasLocationPermission = hasLocationPermission,
                    topPadding = topInset + TOP_BAR_HEIGHT + 12.dp + bannerSpace,
                    bottomPadding = bottomPadding + (if (plannerOpen) plannerHeight + 12.dp else CONTROLS_HEIGHT + 24.dp),
                    showRouteButton = !plannerOpen,
                    weather = weather,
                    onWeather = onWeather,
                    onLayers = onLayers,
                    onRecenter = onRecenter,
                    onToggleHeading = onToggleHeading,
                    onRequestPermission = onRequestPermission,
                    onRoute = onRoute,
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
                            .padding(top = topInset + TOP_BAR_HEIGHT + 6.dp + bannerSpace, bottom = bottomPadding + CONTROLS_HEIGHT + 20.dp),
                    ) {
                        Dashboard(snapshot = snapshot, modifier = Modifier.fillMaxWidth().weight(1f))
                        if (weather != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            WeatherStrip(weather, onWeather)
                        }
                    }
                }
            }

            // --- Barre du haut : bascule Carte / Données et état du GPS -----------------------------------
            TopBar(
                mode = mode,
                onMode = onMode,
                snapshot = snapshot,
                accuracyM = accuracyM,
                hasLocationPermission = hasLocationPermission,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + 6.dp).padding(horizontal = 12.dp),
            )

            // --- Guidage : prochaine manœuvre --------------------------------------------------------------
            if (guidance != null) {
                GuidanceBanner(
                    guidance = guidance,
                    onStop = onStopGuidance,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + TOP_BAR_HEIGHT + 12.dp).padding(horizontal = 12.dp),
                )
            }

            // --- Boutons de la sortie ---------------------------------------------------------------------
            Column(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
                    .padding(bottom = bottomPadding + (if (plannerOpen) plannerHeight else 0.dp) + 10.dp)
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
/** En dessous de cette vitesse, le cap donné par le GPS ne veut rien dire. */
private const val MOVING_MPS = 2.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    mode: RideMode,
    onMode: (RideMode) -> Unit,
    snapshot: RideSnapshot,
    accuracyM: Double?,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(TOP_BAR_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(50), shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(4.dp).width(212.dp)) {
                SegmentedButton(
                    selected = mode == RideMode.MAP,
                    onClick = { onMode(RideMode.MAP) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = brandSegmentColors(),
                    icon = { },
                ) { Text("Carte") }
                SegmentedButton(
                    selected = mode == RideMode.DATA,
                    onClick = { onMode(RideMode.DATA) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = brandSegmentColors(),
                    icon = { },
                ) { Text("Données") }
            }
        }
        Spacer(Modifier.weight(1f))
        if (hasLocationPermission) GpsChip(snapshot, accuracyM)
    }
}

/** Boutons segmentés aux couleurs de VeloGo (émeraude) plutôt qu'au bleu par défaut. */
@Composable
fun brandSegmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    inactiveContainerColor = MaterialTheme.colorScheme.surface,
)

/** État du signal GPS : précision en mètres, ou recherche en cours. */
@Composable
private fun GpsChip(snapshot: RideSnapshot, accuracyM: Double?) {
    val searching = accuracyM == null || (snapshot.isActive && !snapshot.hasSignal)
    val weak = accuracyM != null && !searching && accuracyM > 15.0
    val color = when {
        searching -> MaterialTheme.colorScheme.error
        weak -> LocalRideColors.current.paused
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(50), shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                if (searching) "GPS…" else "GPS ±${Formats.meters(accuracyM)} m",
                style = LabelCapsStyle.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MapOverlays(
    snapshot: RideSnapshot,
    attribution: String,
    following: Boolean,
    headingUp: Boolean,
    hasLocationPermission: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    showRouteButton: Boolean,
    weather: WeatherGlance?,
    onWeather: () -> Unit,
    onLayers: () -> Unit,
    onRecenter: () -> Unit,
    onToggleHeading: () -> Unit,
    onRequestPermission: () -> Unit,
    onRoute: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding).padding(horizontal = 12.dp)) {
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot.isActive) MapStatsStrip(snapshot)
            // La météo se range à droite, sous le rappel des données quand une sortie est en cours. Panneau
            // d'itinéraire ouvert, elle laisse la place aux boutons de la carte : le panneau a sa propre ligne météo.
            if (weather != null && hasLocationPermission && showRouteButton) WeatherChip(weather, onWeather, Modifier.align(Alignment.End))
        }

        Column(Modifier.align(Alignment.BottomEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showRouteButton) {
                SmallFloatingActionButton(
                    onClick = onRoute,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(painterResource(R.drawable.ic_route), contentDescription = "Itinéraire")
                }
            }
            SmallFloatingActionButton(onClick = onLayers, containerColor = MaterialTheme.colorScheme.surface) {
                Icon(painterResource(R.drawable.ic_layers), contentDescription = "Changer de fond de carte")
            }
            if (hasLocationPermission) {
                SmallFloatingActionButton(onClick = onToggleHeading, containerColor = MaterialTheme.colorScheme.surface) {
                    Icon(
                        painterResource(if (headingUp) R.drawable.ic_navigation_filled else R.drawable.ic_north),
                        contentDescription = if (headingUp) "Carte orientée dans le sens de la marche" else "Nord en haut",
                        tint = if (headingUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                SmallFloatingActionButton(onClick = onRecenter, containerColor = MaterialTheme.colorScheme.surface) {
                    Icon(
                        painterResource(if (following) R.drawable.ic_my_location_filled else R.drawable.ic_my_location),
                        contentDescription = "Recentrer sur ma position",
                        tint = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // Mention obligatoire des sources de la carte, toujours visible.
        Text(
            attribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        if (!hasLocationPermission) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Où es-tu ?", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "VeloGo a besoin de ta position pour te situer sur la carte et enregistrer tes sorties. Elle reste sur ton téléphone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRequestPermission) { Text("Autoriser la position") }
                }
            }
        }
    }
}

/** Rappel compact des données essentielles quand on regarde la carte pendant une sortie. */
@Composable
private fun MapStatsStrip(snapshot: RideSnapshot, modifier: Modifier = Modifier) {
    val paused = snapshot.state == RideState.PAUSED || snapshot.state == RideState.AUTO_PAUSED
    Surface(modifier, shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (paused) "PAUSE" else "KM/H", style = LabelCapsStyle.copy(fontSize = 12.sp), color = if (paused) LocalRideColors.current.paused else MaterialTheme.colorScheme.onSurfaceVariant)
                FittedNumber(Formats.speedKmh(snapshot.speedMps), "88,8", Modifier.width(92.dp).height(46.dp), align = Alignment.Start)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("KM", style = LabelCapsStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FittedNumber(Formats.distanceKm(snapshot.totals.distanceM), "88,88", Modifier.width(84.dp).height(34.dp), align = Alignment.Start)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("DURÉE", style = LabelCapsStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FittedNumber(Formats.duration(snapshot.totals.movingMs), "8:88:88", Modifier.width(96.dp).height(34.dp), align = Alignment.Start)
            }
        }
    }
}
