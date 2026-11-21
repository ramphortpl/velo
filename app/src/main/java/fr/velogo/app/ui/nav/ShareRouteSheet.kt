package fr.velogo.app.ui.nav

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.graphics.Bitmap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.velogo.app.data.SavedRouteRepository
import fr.velogo.app.data.Settings
import fr.velogo.app.map.MapLayers
import fr.velogo.app.map.MapStyleSpec
import fr.velogo.app.map.RouteSnapshot
import fr.velogo.app.nav.PosterMap
import fr.velogo.app.nav.PosterText
import fr.velogo.app.nav.RouteShare
import fr.velogo.app.ui.LocalContainer
import fr.velogo.core.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.velogo.app.R
import fr.velogo.app.nav.RoutePoster

/**
 * La feuille « Partager l'itinéraire », complète : elle photographie le fond de carte (version claire du fond
 * choisi par l'utilisateur), dessine l'affiche, la redessine quand on change son titre, et l'envoie.
 * En attendant le fond de carte, et sans réseau, l'affiche se contente du tracé.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteShareSheet(route: Route, initialTitle: String, initialSubtitle: String, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val offlineRevision by container.offline.store.epoch.collectAsStateWithLifecycle()
    val offlineFiles by container.offline.store.installed.collectAsStateWithLifecycle()
    val layer = MapLayers.effective(settings.mapLayer, offlineRevision, offlineFiles.keys.any { it.startsWith("fond-") })

    var title by rememberSaveable(route) { mutableStateOf(initialTitle) }
    var subtitle by rememberSaveable(route) { mutableStateOf(initialSubtitle) }
    var map by remember(route) { mutableStateOf<PosterMap?>(null) }
    var mapPending by remember(route) { mutableStateOf(true) }
    LaunchedEffect(route, layer.id) {
        mapPending = true
        map = RouteSnapshot.capture(context, MapStyleSpec(layer, dark = false, revision = if (layer.offline) offlineRevision else 0), route)
        mapPending = false
    }
    val text = PosterText(title, subtitle, if (map != null) layer.attribution else "")
    val poster by produceState<Bitmap?>(null, route, map, text) {
        // Pendant qu'on écrit le titre, l'affiche attend une pause avant de se redessiner.
        if (value != null) delay(300)
        value = withContext(Dispatchers.Default) { RoutePoster.render(context, route, text, map) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ShareRouteContent(
            poster = remember(poster) { poster?.asImageBitmap() },
            mapPending = mapPending,
            withMap = map != null,
            title = title,
            subtitle = subtitle,
            onTitle = { title = it },
            onSubtitle = { subtitle = it },
            onSharePoster = {
                val image = poster
                if (image != null) scope.launch { runCatching { context.startActivity(RouteShare.posterIntent(context, image, text, route)) } }
            },
            onShareGpx = {
                scope.launch { runCatching { context.startActivity(SavedRouteRepository.shareIntent(context, title.ifBlank { "Itinéraire VeloGo" }, route)) } }
            },
        )
    }
}

/**
 * Contenu de la feuille « Partager l'itinéraire » : l'aperçu de l'affiche, son titre et sa deuxième ligne
 * (modifiables), et les deux façons d'envoyer : l'affiche, pour que tout le monde voie le parcours, ou le fichier
 * GPX, pour ceux qui veulent le charger dans leur GPS.
 */
@Composable
fun ShareRouteContent(
    /** L'affiche telle qu'elle partira, ou null tant qu'elle n'est pas dessinée. */
    poster: ImageBitmap?,
    /** Le fond de carte est encore en train d'arriver : l'affiche montrée s'en passe pour l'instant. */
    mapPending: Boolean,
    /** L'affiche a son fond de carte. Faux une fois l'attente finie : pas de réseau, on fera sans. */
    withMap: Boolean,
    title: String,
    subtitle: String,
    onTitle: (String) -> Unit,
    onSubtitle: (String) -> Unit,
    onSharePoster: () -> Unit,
    onShareGpx: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Partager l'itinéraire", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))

        val shape = RoundedCornerShape(18.dp)
        val frame = Modifier
            .height(300.dp)
            .aspectRatio(RoutePoster.WIDTH / RoutePoster.HEIGHT.toFloat())
            .shadow(6.dp, shape)
            .clip(shape)
        if (poster != null) {
            Image(poster, contentDescription = "Aperçu de l'affiche", modifier = frame)
        } else {
            Box(frame.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Text(
                    "Préparation de l'affiche…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                mapPending -> "Le fond de carte arrive…"
                withMap -> "Voilà ce que tes amis recevront."
                else -> "Fond de carte indisponible sans réseau : l'affiche montre le tracé seul."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { onTitle(it.take(70)) },
            singleLine = true,
            label = { Text("Titre de l'affiche") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = subtitle,
            onValueChange = { onSubtitle(it.take(90)) },
            singleLine = true,
            label = { Text("Deuxième ligne (facultatif)") },
            placeholder = { Text("Dimanche 9 h, devant la mairie") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Button(onClick = onSharePoster, enabled = poster != null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Partager l'affiche", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = onShareGpx) {
            Icon(painterResource(R.drawable.ic_file_save), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Envoyer plutôt le fichier GPX")
        }
    }
}
