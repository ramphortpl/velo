package fr.velogo.app.ui.ride

import android.view.WindowManager
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
        )
    }
    val layer = MapLayers.byId(settings.mapLayer)
    val spec = remember(layer, dark) { MapStyleSpec(layer, dark) }

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
                    mapState.updateLocation(gps.location)
                }
            }
        }
    }
    LaunchedEffect(Unit) { vm.track.collect { mapState.setLiveTrack(it) } }
    LaunchedEffect(settings.headingUp) { mapState.setHeadingUp(settings.headingUp) }

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
    val keepScreenOn = isForeground && snapshot.isActive && settings.keepScreenOn
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

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    LaunchedEffect(topInset) { mapState.setTopInset(with(density) { (topInset + TOP_BAR_HEIGHT + 8.dp).roundToPx() }) }

    Box(Modifier.fillMaxSize()) {
        VeloMap(mapState, spec, mapColors, Modifier.fillMaxSize())

        VeloGoTheme(mode = settings.themeMode, sunlight = sunlight) {
            // --- Mode carte : éléments flottants ---------------------------------------------------------
            if (mode == RideMode.MAP) {
                MapOverlays(
                    snapshot = snapshot,
                    attribution = layer.attribution,
                    following = mapState.isFollowing,
                    headingUp = settings.headingUp,
                    hasLocationPermission = startRide.hasLocationPermission,
                    topPadding = topInset + TOP_BAR_HEIGHT + 12.dp,
                    bottomPadding = bottomPadding + CONTROLS_HEIGHT + 24.dp,
                    onLayers = { showLayers = true },
                    onRecenter = { mapState.follow() },
                    onToggleHeading = { vm.setHeadingUp(!settings.headingUp) },
                    onRequestPermission = startRide.requestLocationPermission,
                )
            }

            // --- Mode données : le tableau de bord recouvre la carte --------------------------------------
            AnimatedVisibility(
                visible = mode == RideMode.DATA,
                enter = fadeIn() + slideInVertically { it / 10 },
                exit = fadeOut() + slideOutVertically { it / 10 },
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Dashboard(
                        snapshot = snapshot,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topInset + TOP_BAR_HEIGHT + 6.dp, bottom = bottomPadding + CONTROLS_HEIGHT + 20.dp),
                    )
                }
            }

            // --- Barre du haut : bascule Carte / Données et état du GPS -----------------------------------
            TopBar(
                mode = mode,
                onMode = { mode = it },
                snapshot = snapshot,
                accuracyM = if (snapshot.isActive) snapshot.accuracyM else lastAccuracy,
                hasLocationPermission = startRide.hasLocationPermission,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + 6.dp).padding(horizontal = 12.dp),
            )

            // --- Boutons de la sortie ---------------------------------------------------------------------
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = bottomPadding + 10.dp)) {
                SnackbarHost(snackbar, Modifier.padding(bottom = 8.dp))
                RideControls(
                    state = snapshot.state,
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
            }
        }
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }) {
            Text("Fond de carte", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            for (candidate in MapLayers.ALL) {
                ListItem(
                    headlineContent = { Text(candidate.title) },
                    supportingContent = { Text(candidate.description) },
                    trailingContent = { RadioButton(selected = candidate.id == layer.id, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        vm.setMapLayer(candidate.id)
                        showLayers = false
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
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

private val TOP_BAR_HEIGHT = 48.dp

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
                    icon = { },
                ) { Text("Carte") }
                SegmentedButton(
                    selected = mode == RideMode.DATA,
                    onClick = { onMode(RideMode.DATA) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { },
                ) { Text("Données") }
            }
        }
        Spacer(Modifier.weight(1f))
        if (hasLocationPermission) GpsChip(snapshot, accuracyM)
    }
}

/** État du signal GPS : précision en mètres, ou recherche en cours. */
@Composable
private fun GpsChip(snapshot: RideSnapshot, accuracyM: Double?) {
    val searching = accuracyM == null || (snapshot.isActive && !snapshot.hasSignal)
    val weak = !searching && accuracyM != null && accuracyM > 15.0
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
    onLayers: () -> Unit,
    onRecenter: () -> Unit,
    onToggleHeading: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding).padding(horizontal = 12.dp)) {
        if (snapshot.isActive) MapStatsStrip(snapshot, Modifier.align(Alignment.TopStart))

        Column(Modifier.align(Alignment.BottomEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
