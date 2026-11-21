package fr.velogo.app.ui.segments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.R
import fr.velogo.app.ui.components.ChartSeries
import fr.velogo.app.ui.components.ProfileChart
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.core.format.Formats

/**
 * Création d'un segment à partir d'une sortie : on choisit le début et la fin du morceau sur le profil d'altitude
 * (la carte au-dessus montre le morceau choisi), on lui donne un nom, et c'est tout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentEditor(
    /** Profil d'altitude de la sortie, abscisses en kilomètres. */
    altitude: ChartSeries,
    /** Début et fin choisis, en kilomètres depuis le départ de la sortie. */
    range: ClosedFloatingPointRange<Float>,
    totalKm: Float,
    name: String,
    /** Mesures du morceau choisi : « 3,2 km · ↗ 210 m · 6,5 % ». */
    facts: String,
    error: String?,
    onRange: (ClosedFloatingPointRange<Float>) -> Unit,
    onName: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pas des boutons de réglage fin : 100 m, ou 50 m sur une sortie courte.
    val step = if (totalKm < 5f) 0.05f else 0.1f
    fun bounded(start: Float, end: Float): ClosedFloatingPointRange<Float> {
        val s = start.coerceIn(0f, totalKm)
        val e = end.coerceIn(0f, totalKm)
        return if (e > s) s..e else range
    }
    Column(
        // Clavier ouvert, le contenu remonte au-dessus de lui : le bouton « Créer » reste atteignable.
        modifier.imePadding().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Nouveau segment", style = MaterialTheme.typography.titleLarge)
        Text(
            "Choisis le début et la fin du morceau à chronométrer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (altitude.isDrawable) {
            val first = altitude.xs.indexOfFirst { it >= range.start }.coerceAtLeast(0)
            val last = altitude.xs.indexOfLast { it <= range.endInclusive }.coerceAtLeast(first)
            ProfileChart(
                series = altitude,
                lineColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(110.dp),
                formatY = { Formats.meters(it.toDouble()) },
                formatX = { Formats.decimal(it.toDouble(), 1) + " km" },
                range = first..last,
            )
        }
        RangeSlider(
            value = range,
            onValueChange = { onRange(bounded(it.start, it.endInclusive)) },
            valueRange = 0f..totalKm.coerceAtLeast(0.1f),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Nudge("DÉBUT", range.start, Modifier.weight(1f), "le début",
                onMinus = { onRange(bounded(range.start - step, range.endInclusive)) },
                onPlus = { onRange(bounded(range.start + step, range.endInclusive)) })
            Nudge("FIN", range.endInclusive, Modifier.weight(1f), "la fin",
                onMinus = { onRange(bounded(range.start, range.endInclusive - step)) },
                onPlus = { onRange(bounded(range.start, range.endInclusive + step)) })
        }
        Text(facts, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { onName(it.take(60)) },
            singleLine = true,
            label = { Text("Nom du segment") },
            placeholder = { Text("Côte de la Croix, tour du lac…") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Annuler") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onCreate, enabled = name.isNotBlank()) { Text("Créer le segment") }
        }
    }
}

/** Réglage fin d'un des deux bouts : la position en kilomètres et deux boutons pour l'avancer ou la reculer. */
@Composable
private fun Nudge(label: String, km: Float, modifier: Modifier, what: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(modifier) {
        Text(label, style = LabelCapsStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = "Reculer $what")
            }
            Text(
                "km ${Formats.decimal(km.toDouble(), 2)}",
                style = NumberStyle.copy(fontSize = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
                maxLines = 1,
            )
            FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Avancer $what")
            }
        }
    }
}
