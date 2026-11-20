package fr.velogo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import fr.velogo.app.ui.ride.RideScreen
import fr.velogo.app.ui.settings.AboutScreen
import fr.velogo.app.ui.settings.SettingsScreen
import fr.velogo.app.ui.theme.LocalIsDark
import fr.velogo.app.ui.theme.VeloGoTheme
import kotlinx.serialization.Serializable

@Serializable
data object HistoryKey : NavKey

@Serializable
data class RideDetailKey(val rideId: Long) : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object AboutKey : NavKey

/** Les onglets du bas. « Rouler » est l'écran principal : la carte et l'enregistrement. */
enum class Tab(val label: String, val icon: Int, val selectedIcon: Int) {
    RIDE("Rouler", R.drawable.ic_directions_bike, R.drawable.ic_directions_bike_filled),
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
                    AnimatedVisibility(barVisible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                        NavigationBar {
                            for (item in Tab.entries) {
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = {
                                        when (item) {
                                            Tab.RIDE -> Unit
                                            Tab.HISTORY -> backStack.resetTo(HistoryKey)
                                            Tab.SETTINGS -> backStack.resetTo(SettingsKey)
                                        }
                                        tab = item
                                    },
                                    icon = {
                                        BadgedBox(badge = { if (item == Tab.SETTINGS && update != null) Badge() }) {
                                            Icon(painterResource(if (tab == item) item.selectedIcon else item.icon), contentDescription = null)
                                        }
                                    },
                                    label = { Text(item.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
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
                    )

                    AnimatedVisibility(visible = tab != Tab.RIDE, enter = fadeIn(), exit = fadeOut()) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            NavDisplay(
                                backStack = backStack,
                                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else tab = Tab.RIDE },
                                entryDecorators = listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberViewModelStoreNavEntryDecorator(),
                                ),
                                entryProvider = entryProvider {
                                    entry<HistoryKey> {
                                        HistoryScreen(onOpenRide = { backStack.add(RideDetailKey(it)) }, contentPadding = padding)
                                    }
                                    entry<RideDetailKey> { key ->
                                        RideDetailScreen(rideId = key.rideId, onBack = { backStack.removeLastOrNull() })
                                    }
                                    entry<SettingsKey> {
                                        SettingsScreen(onOpenAbout = { backStack.add(AboutKey) }, contentPadding = padding)
                                    }
                                    entry<AboutKey> {
                                        AboutScreen(onBack = { backStack.removeLastOrNull() })
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Retour depuis un onglet secondaire : on revient à la carte plutôt que de quitter l'appli.
            BackHandler(enabled = tab != Tab.RIDE && backStack.size <= 1) { tab = Tab.RIDE }
        }
    }
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
