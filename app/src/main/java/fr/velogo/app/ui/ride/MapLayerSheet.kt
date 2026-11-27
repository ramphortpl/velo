package fr.velogo.app.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.velogo.app.R
import fr.velogo.app.map.MapLayer
import fr.velogo.app.ui.components.VeloSwitch

/**
 * Le choix du fond de carte : les fonds proposés ([layers]), celui qui est affiché ([selectedId]), et l'accès aux
 * cartes hors ligne. La carte hors ligne ne peut être choisie qu'une fois une région téléchargée : d'ici là, sa
 * ligne y mène.
 */
@Composable
fun MapLayersContent(
    layers: List<MapLayer>,
    selectedId: String,
    offlineInstalled: Boolean,
    onPick: (MapLayer) -> Unit,
    onOfflineMaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text("Fond de carte", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        for (candidate in layers) {
            val needsDownload = candidate.offline && !offlineInstalled
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (needsDownload) onOfflineMaps() else onPick(candidate) }
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
                    RadioButton(selected = candidate.id == selectedId, onClick = null)
                }
            }
        }
        TextButton(onClick = onOfflineMaps, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Gérer les cartes hors ligne") }
        Spacer(Modifier.height(16.dp))
    }
}

/** Ce que l'appli dit de Mapbox avant d'afficher une de ses cartes pour la première fois. */
val MAPBOX_DISCLOSURE = listOf(
    "Ce fond est fourni par Mapbox, une société américaine, et dessiné par son propre moteur de carte.",
    "Tant qu'il est affiché, Mapbox reçoit l'adresse internet du téléphone, la zone de carte regardée et de quoi " +
        "compter les utilisateurs de l'appli.",
    "Mapbox peut aussi recevoir des statistiques d'utilisation, qui contiennent ta position et qu'il dit rendre " +
        "anonymes, pour améliorer ses cartes. C'est toi qui choisis :",
)

/** Rappel sous le choix : ce qui ne change pas, et où changer d'avis. */
const val MAPBOX_DISCLOSURE_NOTE =
    "Les autres fonds de carte n'envoient rien à Mapbox. Tu pourras changer d'avis avec le bouton ⓘ posé sur sa carte."

/**
 * L'accord demandé avant la première carte Mapbox : ce que Mapbox reçoit, et le choix d'y ajouter ou non des
 * statistiques d'utilisation (refusées tant qu'on ne les a pas acceptées). [onAccept] reçoit ce choix.
 */
@Composable
fun MapboxConsentDialog(onAccept: (statistics: Boolean) -> Unit, onDismiss: () -> Unit, onPrivacy: () -> Unit) {
    var statistics by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("La carte Mapbox") },
        text = { MapboxConsentContent(statistics, onStatistics = { statistics = it }, onPrivacy = onPrivacy) },
        confirmButton = { Button(onClick = { onAccept(statistics) }) { Text("Afficher Mapbox") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Le contenu de la fenêtre d'accord, à part pour être vérifié en image. */
@Composable
fun MapboxConsentContent(statistics: Boolean, onStatistics: (Boolean) -> Unit, onPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        for (paragraph in MAPBOX_DISCLOSURE) {
            Text(paragraph, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .toggleable(value = statistics, role = Role.Switch, onValueChange = onStatistics)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Envoyer mes statistiques à Mapbox", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            VeloSwitch(statistics)
        }
        Text(
            MAPBOX_DISCLOSURE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        TextButton(onClick = onPrivacy, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Politique de confidentialité") }
    }
}
