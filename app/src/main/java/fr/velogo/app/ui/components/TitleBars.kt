package fr.velogo.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.velogo.app.ui.theme.ScreenTitleStyle

/**
 * La barre d'un onglet, avec son grand titre à la manière d'iOS : gros tant que la liste est en haut, il se range
 * en petit dans la barre dès qu'elle défile. À relier à la liste par
 * `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleBar(title: String, scrollBehavior: TopAppBarScrollBehavior, actions: @Composable RowScope.() -> Unit = {}) {
    val typography = MaterialTheme.typography
    // La barre de Material écrit son grand titre en « headlineMedium » : on le remplace ici, et ici seulement.
    MaterialTheme(typography = typography.copy(headlineMedium = ScreenTitleStyle)) {
        LargeTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = { MaterialTheme(typography = typography) { actions() } },
            scrollBehavior = scrollBehavior,
            collapsedHeight = 56.dp,
            expandedHeight = 104.dp,
            colors = flatBarColors(),
        )
    }
}

/** Une barre du même fond que l'écran, qu'elle soit en haut de la liste ou que celle-ci ait défilé dessous. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun flatBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surface,
)
