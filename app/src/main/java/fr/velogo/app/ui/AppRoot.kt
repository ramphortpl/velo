package fr.velogo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.velogo.app.ui.theme.Brand
import fr.velogo.app.ui.theme.LocalGlass
import fr.velogo.app.ui.theme.glass
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import fr.velogo.app.AppContainer
import fr.velogo.app.R
import fr.velogo.app.data.Settings
import fr.velogo.app.ui.history.HistoryScreen
import fr.velogo.app.ui.history.RideDetailScreen
import fr.velogo.app.ui.offline.OfflineMapsScreen
import fr.velogo.app.ui.ride.RideScreen
import fr.velogo.app.ui.routes.SavedRoutesScreen
import fr.velogo.app.ui.segments.SegmentDetailScreen
import fr.velogo.app.ui.segments.SegmentsScreen
import fr.velogo.app.ui.stats.StatsScreen
import fr.velogo.app.ui.settings.AboutScreen
import fr.velogo.app.ui.settings.PrivacyScreen
import fr.velogo.app.ui.settings.SettingsScreen
import fr.velogo.app.ui.theme.LocalIsDark
import fr.velogo.app.ui.theme.VeloGoTheme
import kotlinx.serialization.Serializable

@Serializable
data object SavedRoutesKey : NavKey

@Serializable
data object HistoryKey : NavKey

@Serializable
data class RideDetailKey(val rideId: Long) : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object AboutKey : NavKey

@Serializable
data object OfflineMapsKey : NavKey

@Serializable
data object PrivacyKey : NavKey

@Serializable
data object StatsKey : NavKey

@Serializable
data object SegmentsKey : NavKey

@Serializable
data class SegmentDetailKey(val segmentId: Long) : NavKey

/** Les onglets du bas. « Rouler » est l'écran principal : la carte et l'enregistrement. */
enum class Tab(val label: String, val icon: Int, val selectedIcon: Int) {
    RIDE("Rouler", R.drawable.ic_directions_bike, R.drawable.ic_directions_bike_filled),
    /** Les itinéraires enregistrés, à rouvrir d'un appui. */
    ROUTES("Trajets", R.drawable.ic_bookmark, R.drawable.ic_bookmark_filled),
    HISTORY("Historique", R.drawable.ic_history, R.drawable.ic_history),
    SETTINGS("Réglages", R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

/**
 * Racine de l'interface.
 *
 * L'écran « Rouler » (carte + enregistrement) reste toujours en place, en dessous : les autres écrans viennent
 * se poser par-dessus. La carte n'est donc jamais recréée quand on change d'onglet, et l'enregistrement en cours
 * reste visible en un geste.
 */
@Composable
fun AppRoot(container: AppContainer, settings: Settings) {
    VeloGoTheme(mode = settings.themeMode) {
        CompositionLocalProvider(LocalContainer provides container) {
            var tab by rememberSaveable { mutableStateOf(Tab.RIDE) }
            val backStack = rememberNavBackStack(HistoryKey)
            val update by container.update.collectAsStateWithLifecycle()
            val barVisible = tab == Tab.RIDE || backStack.size <= 1

            SystemBarsAppearance()

            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    // Glissement seul, sans fondu : pendant un fondu, l'ombre de la barre, qui en dépasse, serait rognée.
                    AnimatedVisibility(barVisible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                        FloatingTabBar(
                            selected = tab,
                            badge = Tab.SETTINGS.takeIf { update != null },
                            onSelect = { item ->
                                when (item) {
                                    Tab.RIDE -> Unit
                                    Tab.ROUTES -> backStack.resetTo(SavedRoutesKey)
                                    Tab.HISTORY -> backStack.resetTo(HistoryKey)
                                    Tab.SETTINGS -> backStack.resetTo(SettingsKey)
                                }
                                tab = item
                            },
                        )
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize()) {
                    RideScreen(
                        isForeground = tab == Tab.RIDE,
                        bottomPadding = padding.calculateBottomPadding(),
                        onRideSaved = { rideId ->
                            Snapshot.withMutableSnapshot {
                                backStack.resetTo(HistoryKey)
                                backStack.add(RideDetailKey(rideId))
                            }
                            tab = Tab.HISTORY
                        },
                        onOpenOfflineMaps = {
                            Snapshot.withMutableSnapshot {
                                backStack.resetTo(SettingsKey)
                                backStack.add(OfflineMapsKey)
                            }
                            tab = Tab.SETTINGS
                        },
                        onOpenPrivacy = {
                            Snapshot.withMutableSnapshot {
                                backStack.resetTo(SettingsKey)
                                backStack.add(PrivacyKey)
                            }
                            tab = Tab.SETTINGS
                        },
                    )

                    AnimatedVisibility(visible = tab != Tab.RIDE, enter = fadeIn(), exit = fadeOut()) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            NavDisplay(
                                backStack = backStack,
                                // Sur un grand écran, les listes et les fiches gardent une largeur lisible, au milieu.
                                modifier = Modifier.widthIn(max = READING_WIDTH),
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else tab = Tab.RIDE },
                                entryDecorators = listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberViewModelStoreNavEntryDecorator(),
                                ),
                                entryProvider = entryProvider {
                                    entry<SavedRoutesKey> {
                                        SavedRoutesScreen(onShowMap = { tab = Tab.RIDE }, contentPadding = padding)
                                    }
                                    entry<HistoryKey> {
                                        HistoryScreen(
                                            onOpenRide = { backStack.add(RideDetailKey(it)) },
                                            onOpenStats = { backStack.add(StatsKey) },
                                            onOpenSegments = { backStack.add(SegmentsKey) },
                                            contentPadding = padding,
                                        )
                                    }
                                    entry<RideDetailKey> { key ->
                                        RideDetailScreen(
                                            rideId = key.rideId,
                                            onBack = { backStack.removeLastOrNull() },
                                            onOpenSegment = { backStack.add(SegmentDetailKey(it)) },
                                        )
                                    }
                                    entry<StatsKey> {
                                        StatsScreen(onBack = { backStack.removeLastOrNull() }, onOpenRide = { backStack.add(RideDetailKey(it)) })
                                    }
                                    entry<SegmentsKey> {
                                        SegmentsScreen(onBack = { backStack.removeLastOrNull() }, onOpenSegment = { backStack.add(SegmentDetailKey(it)) })
                                    }
                                    entry<SegmentDetailKey> { key ->
                                        SegmentDetailScreen(
                                            segmentId = key.segmentId,
                                            onBack = { backStack.removeLastOrNull() },
                                            onOpenRide = { backStack.add(RideDetailKey(it)) },
                                        )
                                    }
                                    entry<SettingsKey> {
                                        SettingsScreen(
                                            onOpenAbout = { backStack.add(AboutKey) },
                                            onOpenOfflineMaps = { backStack.add(OfflineMapsKey) },
                                            onOpenPrivacy = { backStack.add(PrivacyKey) },
                                            contentPadding = padding,
                                        )
                                    }
                                    entry<PrivacyKey> {
                                        PrivacyScreen(onBack = { backStack.removeLastOrNull() })
                                    }
                                    entry<OfflineMapsKey> {
                                        OfflineMapsScreen(onBack = { backStack.removeLastOrNull() })
                                    }
                                    entry<AboutKey> {
                                        AboutScreen(onBack = { backStack.removeLastOrNull() })
                                    }
                                },
                            )
                            // Sous la barre d'onglets, les listes s'estompent dans le fond : sans flou possible, des
                            // lignes de texte qui passeraient derrière le verre se mêleraient aux noms des onglets.
                            if (barVisible) BottomFade(padding.calculateBottomPadding() + 22.dp, Modifier.align(Alignment.BottomCenter))
                          }
                        }
                    }
                }
            }

            // Retour depuis un onglet secondaire : on revient à la carte plutôt que de quitter l'appli.
            BackHandler(enabled = tab != Tab.RIDE && backStack.size <= 1) { tab = Tab.RIDE }
        }
    }
}

/** Largeur que gardent, sur un grand écran, les éléments pensés pour un téléphone (barre d'onglets, boutons). */
val COMPACT_WIDTH = 520.dp
/** Largeur que gardent, sur un grand écran, les listes et les fiches. */
val READING_WIDTH = 720.dp

/** Hauteur de la barre d'onglets, et marge qui la sépare des bords de l'écran. */
val TAB_BAR_HEIGHT = 64.dp
private val TAB_BAR_MARGIN = 14.dp

/**
 * La barre d'onglets et les marges qui la font flotter au-dessus du bord de l'écran, sans fond : la carte et les
 * listes passent dessous. Elle occupe 80 dp, plus la barre de navigation du téléphone.
 */
@Composable
fun FloatingTabBar(selected: Tab, badge: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = TAB_BAR_MARGIN, end = TAB_BAR_MARGIN, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Sur une tablette ou un téléphone déplié, la barre garde une largeur de téléphone, au milieu.
        TabBar(selected, badge, onSelect, Modifier.widthIn(max = COMPACT_WIDTH))
    }
}

/**
 * La barre d'onglets : une pilule de verre qui flotte au bas de l'écran. Une pastille verte glisse sous l'onglet
 * choisi ; pendant qu'elle glisse, elle seule est redessinée.
 */
@Composable
private fun TabBar(selected: Tab, badge: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    val tabs = Tab.entries
    val position = animateFloatAsState(selected.ordinal.toFloat(), spring(dampingRatio = 0.78f, stiffness = 500f), label = "onglet")
    val marker = Brand.Fresh.copy(alpha = if (LocalIsDark.current) 0.20f else 0.24f)
    val chosenColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .height(TAB_BAR_HEIGHT)
            .glass(glass.regular)
            .drawBehind {
                val gap = TAB_GAP.toPx()
                val cell = (size.width - 2 * gap) / tabs.size
                drawRoundRect(
                    color = marker,
                    topLeft = Offset(gap + cell * position.value, gap),
                    size = Size(cell, size.height - 2 * gap),
                    cornerRadius = CornerRadius((size.height - 2 * gap) / 2f),
                )
            }
            .padding(TAB_GAP)
            .selectableGroup(),
    ) {
        for (item in tabs) {
            val chosen = item == selected
            val tint = if (chosen) chosenColor else idleColor
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(item) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    Icon(painterResource(if (chosen) item.selectedIcon else item.icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    // Une mise à jour attend : un point rose sur l'onglet des réglages.
                    if (item == badge) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(9.dp).background(Brand.Pink, CircleShape))
                }
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
            }
        }
    }
}

private val TAB_GAP = 5.dp

/** Dégradé du transparent vers le fond de l'écran, posé au bas d'une liste. */
@Composable
fun BottomFade(height: Dp, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surface
    Box(
        modifier.fillMaxWidth().height(height).background(
            Brush.verticalGradient(0f to background.copy(alpha = 0f), 0.42f to background.copy(alpha = 0.86f), 1f to background)
        )
    )
}

/** Remplace toute la pile par un seul écran, sans jamais la laisser vide. */
private fun NavBackStack<NavKey>.resetTo(root: NavKey) {
    Snapshot.withMutableSnapshot {
        if (isEmpty()) add(root) else this[0] = root
        while (size > 1) removeAt(size - 1)
    }
}

/** Icônes des barres système lisibles quel que soit le thème choisi dans l'appli. */
@Composable
private fun SystemBarsAppearance() {
    val dark = LocalIsDark.current
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(dark, activity) {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
}
