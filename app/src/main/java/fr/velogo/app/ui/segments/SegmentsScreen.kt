package fr.velogo.app.ui.segments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.R
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.veloViewModel
import fr.velogo.core.format.Formats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SegmentsViewModel(container: AppContainer) : ViewModel() {
    /** null tant que la liste n'est pas chargée. */
    val rows: StateFlow<List<SegmentRow>?> =
        combine(container.segments.observeSegments(), container.segments.observeEfforts()) { segments, efforts ->
            segmentRows(segments, efforts) as List<SegmentRow>?
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Les segments personnels : des bouts de route où comparer ses temps d'une sortie à l'autre. */
@Composable
fun SegmentsScreen(onBack: () -> Unit, onOpenSegment: (Long) -> Unit) {
    val vm = veloViewModel { SegmentsViewModel(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    SegmentsContent(rows, onBack, onOpenSegment)
}

/** Le contenu de l'écran, sans accès aux données : c'est lui qu'on vérifie en image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentsContent(rows: List<SegmentRow>?, onBack: () -> Unit, onOpenSegment: (Long) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes segments") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        when {
            rows == null -> Box(Modifier.fillMaxSize())
            rows.isEmpty() -> EmptySegments(Modifier.padding(inner))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.id }) { row -> SegmentCard(row, onClick = { onOpenSegment(row.id) }) }
                item(key = "aide") {
                    Text(
                        "Pour ajouter un segment, ouvre une sortie puis choisis « Créer un segment » dans le menu.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentCard(row: SegmentRow, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (row.efforts) {
                        0 -> "Pas encore de passage"
                        1 -> "1 passage"
                        else -> "${row.efforts} passages"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.bestMs != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("RECORD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Formats.duration(row.bestMs), style = NumberStyle.copy(fontSize = 30.sp), color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EmptySegments(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_timer), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Pas encore de segment", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Un segment, c'est un bout de route que tu choisis (une côte, une ligne droite, ton tour habituel) : " +
                "VeloGo y chronomètre chacun de tes passages et garde ton record.\n\n" +
                "Pour en créer un, ouvre une sortie dans l'historique puis choisis « Créer un segment » dans le menu.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
