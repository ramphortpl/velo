package fr.velogo.app.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.velogo.app.R
import fr.velogo.app.search.Place
import kotlinx.coroutines.delay

/** Où en est une recherche d'adresse. */
sealed interface SearchResult {
    data object Idle : SearchResult
    data object Loading : SearchResult
    data object Unavailable : SearchResult
    data class Found(val places: List<Place>) : SearchResult
}

/**
 * Recherche d'une adresse ou d'un lieu, en plein écran. Les propositions arrivent pendant la frappe.
 * [offerMyPosition] ajoute « Ma position » en tête (pour le point de départ) ; [offerLoop] ajoute « Revenir au
 * départ » (pour l'arrivée : l'itinéraire devient une boucle).
 */
@Composable
fun PlaceSearchDialog(
    title: String,
    offerMyPosition: Boolean,
    search: suspend (String) -> List<Place>?,
    onPlace: (Place) -> Unit,
    onMyPosition: () -> Unit,
    onDismiss: () -> Unit,
    offerLoop: Boolean = false,
    onLoop: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult>(SearchResult.Idle) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.trim().length < 3) {
            result = SearchResult.Idle
            return@LaunchedEffect
        }
        // On attend une courte pause dans la frappe avant d'interroger le service.
        delay(350)
        result = SearchResult.Loading
        val places = search(query)
        result = if (places == null) SearchResult.Unavailable else SearchResult.Found(places)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            PlaceSearchContent(
                title = title, query = query, onQuery = { query = it }, result = result, offerMyPosition = offerMyPosition,
                onPlace = onPlace, onMyPosition = onMyPosition, onDismiss = onDismiss,
                fieldModifier = Modifier.focusRequester(focus),
                offerLoop = offerLoop, onLoop = onLoop,
            )
        }
    }
}

/** Le contenu de la recherche, sans accès aux données : c'est lui qu'on vérifie en image. */
@Suppress("ModifierParameter") // [fieldModifier] s'applique au champ de saisie (pour lui donner le focus), pas à l'ensemble.
@Composable
fun PlaceSearchContent(
    title: String,
    query: String,
    onQuery: (String) -> Unit,
    result: SearchResult,
    offerMyPosition: Boolean,
    onPlace: (Place) -> Unit,
    onMyPosition: () -> Unit,
    onDismiss: () -> Unit,
    fieldModifier: Modifier = Modifier,
    offerLoop: Boolean = false,
    onLoop: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") }
            TextField(
                value = query,
                onValueChange = onQuery,
                modifier = fieldModifier.weight(1f),
                placeholder = { Text(title) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Effacer") }
                    }
                },
            )
        }
        if (result is SearchResult.Loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            if (offerMyPosition) {
                item {
                    ResultRow(R.drawable.ic_my_location_filled, "Ma position", "Partir de l'endroit où je suis", onMyPosition)
                }
            }
            if (offerLoop) {
                item {
                    ResultRow(R.drawable.ic_replay, "Revenir au départ", "Faire une boucle : tu choisis ensuite par où passer", onLoop)
                }
            }
            when (result) {
                is SearchResult.Found -> {
                    if (result.places.isEmpty()) item { Hint("Aucun résultat. Essaie avec le nom de la commune, ou place le point par un appui long sur la carte.") }
                    items(result.places) { place -> ResultRow(R.drawable.ic_location_on, place.title, place.subtitle) { onPlace(place) } }
                }
                SearchResult.Unavailable -> item {
                    Hint("Recherche impossible : il faut du réseau. Sans réseau, place ton point par un appui long sur la carte.")
                }
                SearchResult.Idle -> item { Hint("Tape une adresse, une commune ou un lieu (gare, col, lac…). Recherche limitée à la France.") }
                SearchResult.Loading -> Unit
            }
        }
    }
}

@Composable
private fun ResultRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
    )
}
