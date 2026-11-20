package fr.velogo.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.R
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.ui.components.TrackThumbnail
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.util.Dates
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(container: AppContainer) : ViewModel() {
    /** null tant que la liste n'est pas chargée. */
    val rides: StateFlow<List<RideEntity>?> = container.rides.observeRides()
        .map<List<RideEntity>, List<RideEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Liste des sorties, les plus récentes en premier, regroupées par mois. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(onOpenRide: (Long) -> Unit, contentPadding: PaddingValues) {
    val vm = veloViewModel { HistoryViewModel(it) }
    val rides by vm.rides.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Mes sorties") }, scrollBehavior = scrollBehavior) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        val list = rides
        when {
            list == null -> Box(Modifier.fillMaxSize())
            list.isEmpty() -> EmptyHistory(Modifier.padding(inner).padding(contentPadding))
            else -> {
                val groups = remember(list) { list.groupBy { Dates.monthKey(it.startTimeMs) }.toList() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(inner),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for ((key, monthRides) in groups) {
                        stickyHeader(key = "mois-$key") { MonthHeader(monthRides) }
                        items(monthRides, key = { it.id }) { ride ->
                            RideCard(ride, onClick = { onOpenRide(ride.id) }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(rides: List<RideEntity>) {
    val distance = rides.sumOf { it.distanceM }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(Dates.month(rides.first().startTimeMs), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(
            "${rides.size} sortie${if (rides.size > 1) "s" else ""} · ${Formats.distanceAuto(distance)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RideCard(ride: RideEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackThumbnail(
                encodedPreview = ride.preview,
                color = LocalRideColors.current.track,
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                startColor = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ride.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Dates.shortDay(ride.startTimeMs)} · ${Dates.time(ride.startTimeMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SmallStat(Formats.distanceKm(ride.distanceM), "km")
                    SmallStat(Formats.duration(ride.movingMs), "")
                    SmallStat(Formats.meters(ride.ascentM), "m D+")
                }
            }
        }
    }
}

@Composable
private fun SmallStat(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = NumberStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = MaterialTheme.colorScheme.onSurface)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_directions_bike), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Pas encore de sortie", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Lance ta première sortie depuis l'onglet « Sortie » : elle apparaîtra ici avec sa carte, ses chiffres et ses graphiques.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
