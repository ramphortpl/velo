package fr.velogo.app.ui.weather

import androidx.annotation.DrawableRes
import fr.velogo.app.R
import fr.velogo.core.format.Formats
import fr.velogo.core.weather.RainEvent
import fr.velogo.core.weather.RainForecast
import fr.velogo.core.weather.RouteWeatherRow
import fr.velogo.core.weather.WeatherCodes
import fr.velogo.core.weather.WeatherPoint
import fr.velogo.core.weather.WeatherReport
import fr.velogo.core.weather.Wind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** L'essentiel de la météo du moment, pour la pastille de la carte et la ligne de l'écran guidon. */
data class WeatherGlance(
    val now: WeatherPoint,
    /** Pluie attendue dans l'heure et demie, s'il y en a. */
    val rain: RainEvent?,
    /** Le vent par rapport au sens de la marche, quand on roule et qu'il souffle assez pour compter. */
    val windSide: Wind.Side?,
)

/** Résume des prévisions pour l'instant [nowMs] ; null si elles ne couvrent plus cet instant. */
fun weatherGlance(report: WeatherReport?, nowMs: Long, bearingDeg: Double?): WeatherGlance? {
    val now = report?.now(nowMs) ?: return null
    return WeatherGlance(now, RainForecast.next(report.quarters, nowMs), windSide(now, bearingDeg))
}

/** De face, de dos ou de côté ; null à l'arrêt ou par vent trop faible pour que cela compte. */
fun windSide(point: WeatherPoint, bearingDeg: Double?): Wind.Side? {
    val wind = point.windKmh ?: return null
    val from = point.windFromDeg ?: return null
    return if (bearingDeg != null && wind >= CALM_KMH) Wind.side(from, bearingDeg) else null
}

/** « Vent de face 14 km/h » quand on roule, « Vent NO 14 km/h » sinon. */
fun windLine(point: WeatherPoint, side: Wind.Side?): String? {
    val wind = point.windKmh ?: return null
    val speed = "${wind.roundToInt()} km/h"
    val from = point.windFromDeg
    return when {
        wind < 1.0 -> "Pas de vent"
        side != null -> "Vent ${Wind.label(side)} $speed"
        from != null -> "Vent ${Wind.compass(from)} $speed"
        else -> "Vent $speed"
    }
}

/** Le vent en toutes lettres : « 14 km/h de nord-ouest, rafales 31 km/h ». */
fun windSentence(point: WeatherPoint): String? {
    val wind = point.windKmh ?: return null
    if (wind < 1.0) return "Pas de vent"
    val from = point.windFromDeg?.let { " " + Wind.origin(it) }.orEmpty()
    val gusts = point.gustKmh?.takeIf { it >= wind + 8 }?.let { ", rafales ${it.roundToInt()} km/h" }.orEmpty()
    return "${wind.roundToInt()} km/h$from$gusts"
}

/** Où se trouve une ligne de la météo du trajet : « Départ », « km 16 », « Arrivée ». */
fun placeLabel(row: RouteWeatherRow, index: Int, originM: Double, totalM: Double, guiding: Boolean): String = when {
    totalM - row.distanceM < 50.0 -> "Arrivée"
    index == 0 && row.distanceM - originM < 500.0 -> if (guiding) "Ici" else "Départ"
    guiding -> "Dans ${((row.distanceM - originM) / 1000).roundToInt()} km"
    else -> "km ${(row.distanceM / 1000).roundToInt()}"
}

/**
 * Heures de départ proposées pour la météo d'un trajet : les trois prochaines heures rondes, puis toutes les deux
 * heures en journée (de 6 h à 20 h), jusqu'au lendemain soir. « Maintenant » est proposé à part.
 */
fun departureChoices(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    // Une heure ronde trop proche (moins de vingt minutes) ne se distingue pas de « maintenant ».
    var next = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
    if (Duration.between(now, next).toMinutes() < 20) next = next.plusHours(1)
    val last = now.toLocalDate().plusDays(1).atTime(20, 0).atZone(zone)
    val out = ArrayList<Long>()
    repeat(3) {
        out.add(next.toInstant().toEpochMilli())
        next = next.plusHours(1)
    }
    while (!next.isAfter(last)) {
        if (next.hour in 6..20 && next.hour % 2 == 0) out.add(next.toInstant().toEpochMilli())
        next = next.plusHours(1)
    }
    return out
}

/** « 14 h », ou « Demain 8 h » si ce n'est plus aujourd'hui. */
fun departureLabel(timeMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(timeMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return if (time.toLocalDate() == today) "${time.hour} h" else "Demain ${time.hour} h"
}

/** Phrase lue par les lecteurs d'écran pour la pastille météo. */
fun glanceDescription(glance: WeatherGlance): String {
    val parts = ArrayList<String>(4)
    parts.add("Météo : ${Formats.temperature(glance.now.temperatureC)}")
    parts.add(WeatherCodes.label(glance.now.code))
    windLine(glance.now, glance.windSide)?.let { parts.add(it) }
    glance.rain?.let { parts.add(RainForecast.describe(it)) }
    return parts.joinToString(", ")
}

@DrawableRes
fun weatherIcon(code: Int?, isDay: Boolean): Int = when (WeatherCodes.kind(code)) {
    WeatherCodes.Kind.CLEAR -> if (isDay) R.drawable.ic_sunny else R.drawable.ic_clear_night
    WeatherCodes.Kind.PARTLY_CLOUDY -> if (isDay) R.drawable.ic_partly_cloudy_day else R.drawable.ic_partly_cloudy_night
    WeatherCodes.Kind.CLOUDY -> R.drawable.ic_cloud
    WeatherCodes.Kind.FOG -> R.drawable.ic_foggy
    WeatherCodes.Kind.DRIZZLE -> R.drawable.ic_rainy_light
    WeatherCodes.Kind.RAIN, WeatherCodes.Kind.SHOWERS -> R.drawable.ic_rainy
    WeatherCodes.Kind.SNOW -> R.drawable.ic_weather_snowy
    WeatherCodes.Kind.THUNDER -> R.drawable.ic_thunderstorm
}

/** Vrai si ce temps mouille (pluie, bruine, neige, orage) : l'icône est alors dessinée en bleu. */
fun isWetKind(code: Int?): Boolean = when (WeatherCodes.kind(code)) {
    WeatherCodes.Kind.DRIZZLE, WeatherCodes.Kind.RAIN, WeatherCodes.Kind.SHOWERS, WeatherCodes.Kind.SNOW, WeatherCodes.Kind.THUNDER -> true
    else -> false
}

/** En dessous, le sens du vent n'a pas d'importance à vélo. */
private const val CALM_KMH = 8.0
