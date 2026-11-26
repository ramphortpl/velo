package fr.velogo.app.ui.stats

import fr.velogo.app.ui.components.VeloCard
import fr.velogo.app.ui.components.Segmented
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.R
import fr.velogo.app.ui.components.BarChart
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import fr.velogo.core.stats.Period
import fr.velogo.core.stats.PeriodTotals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class StatsViewModel(container: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(Period.WEEK)
    /** De combien de périodes on recule depuis celle d'aujourd'hui. */
    private val back = MutableStateFlow(0)

    val state: StateFlow<StatsUiState> =
        combine(container.rides.observeRides(), container.database.bestEfforts().observeAll(), period, back) { rides, efforts, p, b ->
            buildStats(rides, efforts, p, b)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setPeriod(value: Period) {
        period.value = value
        back.value = 0
    }

    /** Choisit une barre du graphique, par son rang dans la liste affichée. */
    fun select(index: Int) {
        back.value = (state.value.buckets.size - 1 - index).coerceAtLeast(0)
    }
}

/** Totaux par semaine, par mois et par année, records et meilleurs temps. */
@Composable
fun StatsScreen(onBack: () -> Unit, onOpenRide: (Long) -> Unit) {
    val vm = veloViewModel { StatsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    StatsContent(state, onBack, vm::setPeriod, vm::select, onOpenRide)
}

/** Le contenu de l'écran, sans accès aux données : c'est lui qu'on vérifie en image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(
    state: StatsUiState,
    onBack: () -> Unit,
    onPeriod: (Period) -> Unit,
    onSelect: (Int) -> Unit,
    onOpenRide: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistiques") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        when {
            !state.loaded -> Box(Modifier.fillMaxSize())
            state.isEmpty -> EmptyStats(Modifier.padding(inner))
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PeriodSelector(state.period, onPeriod)
                PeriodCard(state, onSelect)
                state.allTime?.let { AllTimeCard(it) }
                if (state.records.isNotEmpty()) RecordList("Records", null, state.records, onOpenRide)
                if (state.bests.isNotEmpty()) {
                    RecordList("Meilleurs temps", "Temps réel entre les deux bouts, arrêts compris.", state.bests, onOpenRide)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(period: Period, onPeriod: (Period) -> Unit) {
    val choices = listOf(Period.WEEK to "Semaine", Period.MONTH to "Mois", Period.YEAR to "Année")
    Segmented(choices, period, onPeriod, Modifier.fillMaxWidth())
}

@Composable
private fun PeriodCard(state: StatsUiState, onSelect: (Int) -> Unit) {
    val current = state.current ?: return
    VeloCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(periodTitle(state.period, current.start), style = MaterialTheme.typography.titleLarge)
            comparison(state.period, current, state.previous)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            BarChart(
                values = state.buckets.map { (it.distanceM / 1000.0).toFloat() },
                labels = state.buckets.map { periodLabel(state.period, it.start) },
                selected = state.selected,
                onSelect = onSelect,
                barColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(170.dp),
                formatValue = { "${Formats.decimal(it.toDouble(), if (it < 100f) 1 else 0)} km" },
                description = "Distance par période. ${periodTitle(state.period, current.start)} : ${Formats.distanceAuto(current.distanceM)}.",
            )
            Spacer(Modifier.height(12.dp))
            TotalsGrid(current)
        }
    }
}

@Composable
private fun TotalsGrid(totals: PeriodTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row {
            Stat("DISTANCE", Formats.distanceKm(totals.distanceM), "km", Modifier.weight(1f))
            Stat("SORTIES", totals.rides.toString(), "", Modifier.weight(1f))
        }
        Row {
            Stat("DURÉE", Formats.durationWords(totals.movingMs), "", Modifier.weight(1f))
            Stat("DÉNIVELÉ +", Formats.meters(totals.ascentM), "m", Modifier.weight(1f))
        }
        Row {
            Stat("VITESSE MOYENNE", if (totals.rides == 0) Formats.NO_VALUE else Formats.speedKmh(totals.avgSpeedMps), "km/h", Modifier.weight(1f))
            Stat("PLUS LONGUE SORTIE", if (totals.rides == 0) Formats.NO_VALUE else Formats.distanceKm(totals.longestM), "km", Modifier.weight(1f))
        }
    }
}

@Composable
private fun AllTimeCard(totals: PeriodTotals) {
    VeloCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Depuis le début", style = MaterialTheme.typography.titleLarge)
            Row {
                Stat("DISTANCE", Formats.decimal(totals.distanceM / 1000.0, if (totals.distanceM < 1_000_000) 1 else 0), "km", Modifier.weight(1f))
                Stat("SORTIES", totals.rides.toString(), "", Modifier.weight(1f))
            }
            Row {
                Stat("DURÉE", Formats.durationWords(totals.movingMs), "", Modifier.weight(1f))
                Stat("DÉNIVELÉ +", Formats.meters(totals.ascentM), "m", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = LabelCapsStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle.copy(fontSize = 30.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (unit.isNotEmpty() && value != Formats.NO_VALUE) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** Une liste de records ; un appui sur une ligne ouvre la sortie où le record a été établi. */
@Composable
private fun RecordList(title: String, note: String?, rows: List<RecordRow>, onOpenRide: (Long) -> Unit) {
    VeloCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(6.dp))
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Voir la sortie") { onOpenRide(row.rideId) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.label, style = MaterialTheme.typography.titleMedium)
                        Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(row.value, style = NumberStyle.copy(fontSize = 26.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyStats(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_bar_chart), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Pas encore de statistiques", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tes totaux par semaine, par mois et par année, tes records et tes meilleurs temps apparaîtront ici après ta première sortie.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
