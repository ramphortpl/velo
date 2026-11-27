package fr.velogo.app.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.R
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.NumberStyle
import fr.velogo.app.ui.util.Dates
import fr.velogo.core.format.Formats
import fr.velogo.core.weather.RainForecast
import fr.velogo.core.weather.RouteWeather
import fr.velogo.core.weather.RouteWeatherRow
import fr.velogo.core.weather.WeatherCodes
import fr.velogo.core.weather.WeatherPoint
import fr.velogo.core.weather.WeatherReport
import fr.velogo.core.weather.Wind
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration

/** Ce que la feuille météo affiche. */
data class WeatherSheetState(
    val nowMs: Long,
    val report: WeatherReport?,
    val loading: Boolean = false,
    /** La dernière mise à jour a échoué : pas de réseau. */
    val failed: Boolean = false,
    /** Faux tant que le téléphone ne sait pas où il est. */
    val hasPosition: Boolean = true,
    /** Sens de la marche, quand on roule : le vent est alors dit « de face » ou « de dos ». */
    val bearingDeg: Double? = null,
    val route: RouteWeatherSection? = null,
)

/** La météo le long de l'itinéraire préparé ou suivi. */
data class RouteWeatherSection(
    /** Vrai en guidage : on est déjà parti, il n'y a plus d'heure de départ à choisir. */
    val guiding: Boolean,
    val rows: List<RouteWeatherRow>,
    val loading: Boolean,
    val failed: Boolean,
    /** Là où l'on est sur l'itinéraire (0 en préparation). */
    val originM: Double,
    val totalM: Double,
    /** Heure de départ choisie (préparation seulement) ; null = maintenant. */
    val departureMs: Long? = null,
    /** Heures de départ proposées, en plus de « Maintenant ». */
    val choices: List<Long> = emptyList(),
)

/** Le contenu de la feuille météo, sans accès aux données : c'est lui qu'on vérifie en image. */
@Composable
fun WeatherContent(
    state: WeatherSheetState,
    onRefresh: () -> Unit,
    /** Choix d'une heure de départ pour la météo du trajet ; null = maintenant. */
    onDeparture: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    /** Un appui sur la mention de la source : ouvre le site d'Open-Meteo. */
    onSource: () -> Unit = {},
) {
    val report = state.report
    val now = report?.now(state.nowMs)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Météo", style = MaterialTheme.typography.titleLarge)
                Text(
                    statusLine(state, report.takeIf { now != null }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.failed && !state.loading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Mettre à jour la météo")
            }
        }

        if (report == null || now == null) {
            EmptyWeather(state, onRefresh)
        } else {
            CurrentBlock(now, state.bearingDeg)
            RainBlock(report, state.nowMs)
            HourlyBlock(report, state.nowMs)
        }

        state.route?.let { RouteBlock(it, state.nowMs, onDeparture) }

        // Open-Meteo demande un lien à côté de ses données, pas seulement son nom.
        Text(
            "Données météo : Open-Meteo.com (CC BY 4.0), modèles AROME et ARPEGE de Météo-France.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .clickable(onClickLabel = "Ouvrir le site d'Open-Meteo", role = Role.Button, onClick = onSource)
                .padding(8.dp),
        )
    }
}

private fun statusLine(state: WeatherSheetState, report: WeatherReport?): String = when {
    state.loading -> "Mise à jour…"
    report != null && state.failed -> "Pas de réseau · prévisions reçues à ${Dates.time(report.fetchedAtMs)}"
    report != null -> "Prévisions Météo-France · reçues à ${Dates.time(report.fetchedAtMs)}"
    !state.hasPosition -> "Position inconnue"
    state.failed -> "Pas de réseau"
    else -> "Prévisions Météo-France"
}

@Composable
private fun EmptyWeather(state: WeatherSheetState, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(
            when {
                state.loading -> "Recherche des prévisions…"
                !state.hasPosition -> "La météo s'affichera dès que le téléphone saura où tu es."
                else -> "Les prévisions ont besoin du réseau. Les dernières reçues restent affichées quelques heures, même sans connexion."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!state.loading && state.hasPosition) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onRefresh) { Text("Réessayer") }
        }
    }
}

// --- Maintenant -----------------------------------------------------------------------------------------------

@Composable
private fun CurrentBlock(now: WeatherPoint, bearingDeg: Double?) {
    val side = windSide(now, bearingDeg)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(weatherIcon(now.code, now.isDay)), contentDescription = null, tint = weatherTint(now.code), modifier = Modifier.size(60.dp))
        Spacer(Modifier.width(14.dp))
        Text(Formats.temperature(now.temperatureC), style = NumberStyle.copy(fontSize = 64.sp), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(WeatherCodes.label(now.code), style = MaterialTheme.typography.titleMedium)
            now.apparentC?.let {
                Text("Ressenti ${Formats.temperature(it)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    windSentence(now)?.let { sentence ->
        InfoRow(
            R.drawable.ic_air, "Vent", sentence,
            detail = side?.let { "Tu l'as ${Wind.label(it)}" }, detailColor = side?.let { sideColor(it) },
        )
    }
}

@Composable
private fun sideColor(side: Wind.Side): Color = when (side) {
    Wind.Side.HEAD -> LocalRideColors.current.climb
    Wind.Side.TAIL -> MaterialTheme.colorScheme.primary
    Wind.Side.CROSS -> MaterialTheme.colorScheme.onSurface
}

/** Une ligne « Vent » ou « Pluie » ; [detail] ajoute une précision en couleur sous le texte. */
@Composable
private fun InfoRow(icon: Int, label: String, text: String, detail: String? = null, detailColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.titleSmall, color = detailColor ?: MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Le code météo décrit l'instant de l'heure ronde, la pluie l'heure entière : quand il doit pleuvoir dans l'heure
 * sous un ciel annoncé simplement couvert, on montre quand même de la pluie.
 */
private fun shownCode(code: Int?, rainMm: Double): Int? = when {
    rainMm < RouteWeather.WET_MM_PER_HOUR || isWetKind(code) -> code
    rainMm < 1.0 -> 51
    else -> 61
}

// --- Pluie dans les deux heures -------------------------------------------------------------------------------

@Composable
private fun RainBlock(report: WeatherReport, nowMs: Long) {
    // Les huit quarts d'heure à venir, celui en cours compris.
    val steps = remember(report, nowMs / QUARTER_MS) {
        report.quarters.filter { it.timeMs > nowMs - QUARTER_MS }.sortedBy { it.timeMs }.take(8)
    }
    val event = RainForecast.next(report.quarters, nowMs, horizonMin = 120)
    val sentence = when {
        steps.isEmpty() -> null
        event == null -> "Pas de pluie dans les 2 heures"
        else -> RainForecast.describe(event)
    }
    if (sentence != null) InfoRow(R.drawable.ic_umbrella, "Pluie", sentence)
    if (event == null || steps.size < 4) return

    val bar = LocalRideColors.current.rain
    val idle = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().padding(start = 60.dp, end = 24.dp, top = 4.dp).clearAndSetSemantics { }) {
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            for (step in steps) {
                val perHour = step.mm * 4
                // Échelle en racine : une pluie faible reste visible à côté d'une averse.
                val fraction = if (step.mm < RainForecast.THRESHOLD_MM) 0f else sqrt(perHour / 12.0).coerceIn(0.12, 1.0).toFloat()
                if (fraction == 0f) {
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(idle))
                } else {
                    Box(Modifier.weight(1f).fillMaxHeight(fraction).clip(RoundedCornerShape(4.dp)).background(bar))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            for (label in listOf("maintenant", "+ 30 min", "+ 1 h", "+ 1 h 30")) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
    }
}

// --- Heure par heure ------------------------------------------------------------------------------------------

@Composable
private fun HourlyBlock(report: WeatherReport, nowMs: Long) {
    val hours = remember(report, nowMs / HOUR_MS) { report.hoursFrom(nowMs, 24) }
    if (hours.size < 2) return
    SectionTitle("Heure par heure")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        var previousDay: Int? = null
        for ((index, hour) in hours.withIndex()) {
            val day = Dates.dayOfYear(hour.timeMs)
            // Passage au lendemain : on le dit, pour ne pas confondre 8 h ce matin et 8 h demain.
            if (previousDay != null && day != previousDay) DayMark(Dates.weekdayShort(hour.timeMs))
            previousDay = day
            HourColumn(hour, first = index == 0)
        }
    }
    Text(
        "Pluie en millimètres par heure, vent en km/h ; la flèche montre vers où il souffle (nord en haut).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp).padding(top = 6.dp),
    )
}

@Composable
private fun DayMark(day: String) {
    Column(Modifier.width(34.dp).height(HOUR_COLUMN_HEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(day, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(1.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun HourColumn(hour: WeatherPoint, first: Boolean) {
    val rain = hour.precipitationMm ?: 0.0
    val wind = hour.windKmh
    val description = buildString {
        append(Dates.hour(hour.timeMs)).append(" : ").append(Formats.temperature(hour.temperatureC)).append(", ").append(WeatherCodes.label(hour.code))
        if (rain >= 0.1) append(", pluie ${Formats.rainMm(rain)}")
        if (wind != null) append(", vent ${wind.roundToInt()} km/h")
    }
    Column(
        Modifier.width(54.dp).height(HOUR_COLUMN_HEIGHT).clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (first) "Maint." else Dates.hour(hour.timeMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        val code = shownCode(hour.code, rain)
        Icon(painterResource(weatherIcon(code, hour.isDay)), contentDescription = null, tint = weatherTint(code), modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(2.dp))
        Text(Formats.temperature(hour.temperatureC), style = NumberStyle.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            if (rain >= 0.1) Formats.decimal(rain, 1) else "",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = LocalRideColors.current.rain,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (wind != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                hour.windFromDeg?.let { from ->
                    Icon(
                        painterResource(R.drawable.ic_navigation_filled),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        // L'icône pointe vers le haut : on la tourne vers là où va le vent.
                        modifier = Modifier.size(12.dp).rotate((from + 180.0).toFloat()),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text("${wind.roundToInt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

// --- Le long de l'itinéraire ----------------------------------------------------------------------------------

@Composable
private fun RouteBlock(section: RouteWeatherSection, nowMs: Long, onDeparture: (Long?) -> Unit) {
    HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    SectionTitle(if (section.guiding) "Sur la suite du trajet" else "Sur l'itinéraire")
    if (!section.guiding) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Départ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(selected = section.departureMs == null, onClick = { onDeparture(null) }, label = { Text("Maintenant") })
            for (choice in section.choices) {
                FilterChip(
                    selected = section.departureMs == choice,
                    onClick = { onDeparture(choice) },
                    label = { Text(departureLabel(choice, nowMs)) },
                )
            }
        }
    }
    when {
        section.rows.isEmpty() && section.loading -> RouteNote("Recherche de la météo du trajet…")
        section.rows.isEmpty() -> RouteNote("Météo du trajet indisponible : pas de réseau.")
        else -> {
            val summary = remember(section.rows) { RouteWeather.summary(section.rows) }
            Text(
                RouteWeather.headline(summary) { Dates.time(it) }.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            section.rows.forEachIndexed { index, row -> RouteRow(row, placeLabel(row, index, section.originM, section.totalM, section.guiding)) }
            if (section.failed) RouteNote("Pas de réseau : ces prévisions n'ont pas pu être mises à jour.")
            RouteNote("Heures de passage estimées d'après ton allure et le relief.")
        }
    }
}

@Composable
private fun RouteNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Composable
private fun RouteRow(row: RouteWeatherRow, place: String) {
    val weather = row.weather
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 5.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(92.dp)) {
            Text(place, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(Dates.time(row.timeMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (weather == null) {
            Text("Au-delà des prévisions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Row
        }
        val code = shownCode(weather.code, weather.precipitationMm ?: 0.0)
        Icon(
            painterResource(weatherIcon(code, weather.isDay)),
            contentDescription = WeatherCodes.label(code),
            tint = weatherTint(code),
            modifier = Modifier.size(26.dp),
        )
        Text(
            Formats.temperature(weather.temperatureC),
            style = NumberStyle.copy(fontSize = 22.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(46.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val head = row.headwindKmh
            val side = row.side
            val wind = weather.windKmh
            Text(
                when {
                    wind == null -> ""
                    side == null || head == null -> "Peu de vent"
                    side == Wind.Side.CROSS -> "Vent de côté ${wind.roundToInt()} km/h"
                    else -> "Vent ${Wind.label(side)} ${abs(head).roundToInt()} km/h"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = side?.let { sideColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (row.isWet) {
                Text(
                    "Pluie ${Formats.rainMm(weather.precipitationMm ?: 0.0)} dans l'heure",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = LocalRideColors.current.rain,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 8.dp))
}

private const val QUARTER_MS = 15 * 60_000L
private const val HOUR_MS = 3_600_000L
private val HOUR_COLUMN_HEIGHT = 118.dp
