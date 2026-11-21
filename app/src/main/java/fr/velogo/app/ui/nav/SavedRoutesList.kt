package fr.velogo.app.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.velogo.app.R
import fr.velogo.app.data.db.SavedRouteEntity
import fr.velogo.app.ui.util.Dates
import fr.velogo.core.format.Formats
import fr.velogo.core.nav.BikeType

/** Liste des itinéraires enregistrés : on touche une ligne pour la reprendre. */
@Composable
fun SavedRoutesList(routes: List<SavedRouteEntity>, onOpen: (Long) -> Unit, onDelete: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Mes itinéraires", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (routes.isEmpty()) {
            Text(
                "Aucun itinéraire enregistré pour l'instant. Calcule un itinéraire puis touche « Enregistrer » : " +
                    "tu pourras le reprendre plus tard, même sans réseau.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        for (route in routes) {
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(route.id) }.padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_route), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Formats.distanceAuto(route.distanceM)} · ↗ ${Formats.meters(route.ascentM)} m · ${bikeLabel(route.bike)} · ${Dates.shortDay(route.createdAtMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDelete(route.id) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Supprimer ${route.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun bikeLabel(name: String): String = when (name) {
    BikeType.ROAD.name -> "route"
    BikeType.MTB.name -> "VTT"
    else -> "VTC"
}
