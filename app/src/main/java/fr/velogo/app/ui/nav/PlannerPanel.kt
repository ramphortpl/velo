package fr.velogo.app.ui.nav

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.R
import fr.velogo.app.nav.PlannerState
import fr.velogo.app.nav.RouteOption
import fr.velogo.app.ui.components.ChartSeries
import fr.velogo.app.ui.components.ProfileChart
import fr.velogo.app.ui.ride.brandSegmentColors
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.core.format.Formats
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.WayMix

/** Quel point de l'itinéraire on est en train de choisir. */
enum class PlanTarget { START, VIA, END }

/** Ce que le panneau d'itinéraire peut demander. */
interface PlannerActions {
    fun close()
    fun setBike(bike: BikeType)
    /** Ouvre la recherche d'adresse pour ce point. */
    fun pick(target: PlanTarget)
    fun useMyPosition()
    fun removeVia(index: Int)
    fun removeEnd()
    fun reverse()
    fun compute()
    /** Revient des propositions aux points de l'itinéraire. */
    fun edit()
    fun select(index: Int)
    fun startGuidance()
    fun toggleVoice()
    fun save()
    /** Ouvre le partage de l'itinéraire : affiche à envoyer, ou fichier GPX. */
    fun share()
    fun importGpx()
    fun openSaved()
    fun openOfflineMaps()
    /** Ouvre le détail de la météo du trajet. */
    fun openWeather()
}

/** Ce que le panneau dit de la météo le long de l'itinéraire choisi. */
data class PlannerWeather(
    /** « 11 à 14° · vent de face 15 km/h · pluie vers 15:30 (km 24) », ou l'état de la recherche. */
    val text: String,
    /** De la pluie est prévue sur le trajet. */
    val wet: Boolean = false,
)

/**
 * Panneau de préparation d'un itinéraire, posé en bas de la carte : d'abord les points (départ, étapes, arrivée),
 * puis les itinéraires proposés. La carte reste visible et utilisable au-dessus.
 *
 * Une fois les itinéraires proposés, le panneau est replié par défaut ([expanded] faux) : il ne montre que les
 * propositions et le bouton de départ, pour laisser la carte en grand. On le déplie pour le détail (profil,
 * nature des voies, météo) avec sa poignée, en la tirant ou en la touchant, ou avec le bouton « Détails ».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerPanel(
    state: PlannerState,
    hasRoutingData: Boolean,
    voiceEnabled: Boolean,
    actions: PlannerActions,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    /** Météo du trajet choisi ; null si la météo est désactivée. */
    weather: PlannerWeather? = null,
    expanded: Boolean = true,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        // Le bouton principal reste toujours visible en bas ; le reste défile au-dessus s'il manque de place.
        Column(Modifier.animateContentSize(tween(220)).heightIn(max = maxHeight).padding(bottom = 12.dp)) {
            if (state.options.isEmpty()) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    PointsStage(state, hasRoutingData, actions)
                }
                PointsFooter(state, actions)
            } else {
                ResultsHeader(state, expanded, onExpandedChange, actions)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (expanded) ResultsDetails(state, weather, actions) else ResultsSummary(state, actions)
                }
                ResultsFooter(voiceEnabled, actions)
            }
        }
    }
}

// --- Étape 1 : les points -------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointsStage(state: PlannerState, hasRoutingData: Boolean, actions: PlannerActions) {
    Header("Itinéraire", onClose = actions::close) {
        IconButton(onClick = actions::openSaved) { Icon(painterResource(R.drawable.ic_bookmark), contentDescription = "Mes trajets enregistrés") }
        IconButton(onClick = actions::importGpx) { Icon(painterResource(R.drawable.ic_upload_file), contentDescription = "Importer une trace GPX") }
    }
    BikeSelector(state.bike, actions::setBike)

    val rideColors = LocalRideColors.current
    PointRow(
        color = START_COLOR, caption = "DÉPART",
        value = state.start?.label ?: "Ma position",
        placeholder = false,
        onClick = { actions.pick(PlanTarget.START) },
        onClear = if (state.start != null) actions::useMyPosition else null,
        clearLabel = "Repartir de ma position",
    )
    state.vias.forEachIndexed { index, via ->
        PointRow(
            color = VIA_COLOR, caption = "ÉTAPE ${index + 1}", value = via.label, placeholder = false,
            onClick = { }, onClear = { actions.removeVia(index) }, clearLabel = "Retirer l'étape ${index + 1}",
        )
    }
    PointRow(
        color = rideColors.stop, caption = "ARRIVÉE",
        value = state.end?.label ?: "Choisir l'arrivée",
        placeholder = state.end == null,
        onClick = { actions.pick(PlanTarget.END) },
        onClear = if (state.end != null) actions::removeEnd else null,
        clearLabel = "Retirer l'arrivée",
    )

    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { actions.pick(PlanTarget.VIA) }, enabled = state.end != null) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Ajouter une étape")
        }
        if (state.start != null && state.end != null) {
            TextButton(onClick = actions::reverse) {
                Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Inverser")
            }
        }
    }
    Text(
        "Astuce : un appui long sur la carte place un point.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
    )

    state.error?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
    }
    if (!hasRoutingData || state.needsData) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (hasRoutingData) "Il manque les données de cette zone." else "Le calcul d'itinéraire se fait sur le téléphone, sans réseau.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Télécharge (ou mets à jour) la région concernée : ses données d'itinéraire viennent avec la carte.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = actions::openOfflineMaps) { Text("Cartes hors ligne") }
            }
        }
    }
}

@Composable
private fun PointsFooter(state: PlannerState, actions: PlannerActions) {
    Button(
        onClick = actions::compute,
        enabled = state.end != null && !state.computing,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
    ) {
        if (state.computing) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(12.dp))
            Text("Calcul en cours…", style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(painterResource(R.drawable.ic_route), contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Calculer l'itinéraire", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BikeSelector(bike: BikeType, onBike: (BikeType) -> Unit) {
    val choices = listOf(BikeType.ROAD to "Route", BikeType.HYBRID to "VTC · Gravel", BikeType.MTB to "VTT")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        choices.forEachIndexed { index, (type, label) ->
            SegmentedButton(
                selected = bike == type,
                onClick = { onBike(type) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                colors = brandSegmentColors(),
                icon = { },
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun PointRow(
    color: Color, caption: String, value: String, placeholder: Boolean,
    onClick: () -> Unit, onClear: (() -> Unit)?, clearLabel: String,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 8.dp).heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (placeholder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClear != null) {
            IconButton(onClick = onClear) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = clearLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- Étape 2 : les propositions -------------------------------------------------------------------------------

/**
 * Haut du panneau des propositions : la poignée, puis le titre et ses boutons. Toute cette zone se tire vers le
 * haut pour déplier le panneau, vers le bas pour le replier ; un appui sur la poignée fait la même chose.
 */
@Composable
private fun ResultsHeader(state: PlannerState, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, actions: PlannerActions) {
    val title = state.importedName ?: if (state.options.size == 1) "1 itinéraire" else "${state.options.size} itinéraires"
    val change by rememberUpdatedState(onExpandedChange)
    val isExpanded by rememberUpdatedState(expanded)
    val threshold = with(LocalDensity.current) { 28.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta -> dragged += delta }
    Column(
        Modifier.draggable(
            state = drag,
            orientation = Orientation.Vertical,
            onDragStarted = { dragged = 0f },
            onDragStopped = { velocity ->
                when {
                    dragged < -threshold || velocity < -900f -> change(true)
                    dragged > threshold || velocity > 900f -> change(false)
                }
            },
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clickable(onClickLabel = if (expanded) "Replier pour voir la carte en grand" else "Déplier le détail") { change(!isExpanded) }
                .semantics { contentDescription = if (expanded) "Replier le panneau" else "Déplier le panneau" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(40.dp).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Row(Modifier.fillMaxWidth().padding(start = if (state.isImported) 24.dp else 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.isImported) {
                IconButton(onClick = actions::edit) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Modifier les points") }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { change(!isExpanded) }) {
                Text(if (expanded) "Voir la carte" else "Détails", maxLines = 1)
                Spacer(Modifier.width(2.dp))
                Icon(painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = actions::share) { Icon(painterResource(R.drawable.ic_share), contentDescription = "Partager l'itinéraire") }
            IconButton(onClick = actions::close) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Fermer l'itinéraire") }
        }
    }
}

/** Panneau replié : les propositions en une ligne chacune. La carte garde toute la place au-dessus. */
@Composable
private fun ResultsSummary(state: PlannerState, actions: PlannerActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.options.forEachIndexed { index, option ->
            OptionCard(
                title = if (state.isImported) "Trace à suivre" else optionTitle(option),
                option = option,
                selected = index == state.selected,
                compact = true,
                onClick = { actions.select(index) },
            )
        }
    }
}

/** Panneau déplié : les propositions en grand, puis le détail de celle qui est choisie. */
@Composable
private fun ResultsDetails(state: PlannerState, weather: PlannerWeather?, actions: PlannerActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.options.forEachIndexed { index, option ->
            OptionCard(
                title = if (state.isImported) "Trace à suivre" else optionTitle(option),
                option = option,
                selected = index == state.selected,
                compact = false,
                onClick = { actions.select(index) },
            )
        }
    }

    val selected = state.selectedOption
    if (selected != null) {
        RouteProfile(selected.route, Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 10.dp).height(96.dp))
        selected.route.mix?.let { WayMixBar(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
        weather?.let { WeatherLine(it, actions::openWeather) }
        if (state.isImported) {
            // Un trajet enregistré garde les consignes de son calcul ; un fichier GPX n'en contient pas.
            val withTurns = selected.route.instructions.any { it.maneuver != Maneuver.ARRIVE }
            Text(
                if (withTurns) "Trajet suivi tel quel, avec ses consignes de virage. Si tu t'en écartes, l'appli te prévient sans le recalculer."
                else "Trace suivie à la ligne : alerte si tu t'en écartes, sans consignes de virage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        TextButton(onClick = actions::save, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(painterResource(R.drawable.ic_bookmark_add), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Enregistrer cet itinéraire")
        }
    }
}

@Composable
private fun ResultsFooter(voiceEnabled: Boolean, actions: PlannerActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = actions::startGuidance, modifier = Modifier.weight(1f).height(56.dp)) {
            Icon(painterResource(R.drawable.ic_navigation_filled), contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Démarrer le guidage", style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = actions::toggleVoice, modifier = Modifier.size(52.dp)) {
            Icon(
                painterResource(if (voiceEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
                contentDescription = if (voiceEnabled) "Guidage vocal activé" else "Guidage vocal désactivé",
            )
        }
    }
}

/** La météo du trajet en une ligne ; un appui ouvre le détail, endroit par endroit. */
@Composable
private fun WeatherLine(weather: PlannerWeather, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (weather.wet) R.drawable.ic_umbrella else R.drawable.ic_partly_cloudy_day),
                contentDescription = null,
                tint = if (weather.wet) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Météo sur le trajet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(weather.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Une proposition d'itinéraire. En version [compact] (panneau replié), tout tient sur deux lignes ; sinon la
 * distance est écrite en grand pour comparer d'un coup d'œil.
 */
@Composable
private fun OptionCard(title: String, option: RouteOption, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val route = option.route
    val summary = "${Formats.distanceAuto(route.distanceM)}, ${Formats.durationWords((route.durationS * 1000).toLong())}, " +
        "${Formats.meters(route.ascentM)} mètres de dénivelé"
    val titleColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = Modifier.width(if (compact) 214.dp else 196.dp).semantics { contentDescription = "$title : $summary" },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
    ) {
        if (compact) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Formats.distanceAuto(route.distanceM), style = NumberStyle.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Formats.durationWords((route.durationS * 1000).toLong())} · ↗ ${Formats.meters(route.ascentM)} m",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = titleColor, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Formats.distanceAuto(route.distanceM), style = NumberStyle.copy(fontSize = 30.sp), color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${Formats.durationWords((route.durationS * 1000).toLong())} · ↗ ${Formats.meters(route.ascentM)} m",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Profil d'altitude d'un itinéraire en fonction de la distance. */
@Composable
private fun RouteProfile(route: Route, modifier: Modifier = Modifier) {
    val elevations = route.elevations ?: return
    val series = remember(route) {
        // Quelques centaines de points suffisent au dessin, même pour un itinéraire de 200 km.
        val step = (route.points.size / 400).coerceAtLeast(1)
        val indices = (route.points.indices step step).toList().let { if (it.last() != route.points.size - 1) it + (route.points.size - 1) else it }
        ChartSeries(FloatArray(indices.size) { (route.distances[indices[it]] / 1000.0).toFloat() }, FloatArray(indices.size) { elevations[indices[it]].toFloat() })
    }
    ProfileChart(
        series = series,
        lineColor = LocalRideColors.current.route,
        modifier = modifier,
        formatX = { Formats.decimal(it.toDouble(), if (route.distanceM < 20_000) 1 else 0) },
    )
}

/** Nature des voies empruntées : une barre proportionnelle et sa légende. */
@Composable
private fun WayMixBar(mix: WayMix, modifier: Modifier = Modifier) {
    val total = mix.totalM
    if (total <= 0.0) return
    val parts = listOf(
        Triple("Pistes cyclables", mix.cyclewayM, CYCLEWAY_COLOR),
        Triple("Petites routes", mix.quietM, QUIET_COLOR),
        Triple("Routes", mix.roadM, ROAD_COLOR),
        Triple("Grands axes", mix.busyM, BUSY_COLOR),
        Triple("Chemins", mix.pathM, PATH_COLOR),
    ).filter { it.second >= total * 0.01 }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            for ((_, meters, color) in parts) Box(Modifier.weight((meters / total).toFloat()).height(10.dp).background(color))
        }
        Spacer(Modifier.height(6.dp))
        // Deux colonnes de légende au plus par ligne.
        for (line in parts.chunked(2)) {
            Row(Modifier.fillMaxWidth()) {
                for ((label, meters, color) in line) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$label ${Formats.distanceAuto(meters)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (line.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (mix.unpavedM >= total * 0.03) {
            Text(
                "Dont non revêtu : ${Formats.distanceAuto(mix.unpavedM)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(title: String, onClose: () -> Unit, onBack: (() -> Unit)? = null, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 24.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Modifier les points") }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        extra()
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Fermer l'itinéraire") }
    }
}

private val START_COLOR = Color(0xFF12A150)
private val VIA_COLOR = Color(0xFFEF6C00)
private val CYCLEWAY_COLOR = Color(0xFF00875A)
private val QUIET_COLOR = Color(0xFF7CB342)
private val ROAD_COLOR = Color(0xFFF9A825)
private val BUSY_COLOR = Color(0xFFD84315)
private val PATH_COLOR = Color(0xFF8D6E63)
