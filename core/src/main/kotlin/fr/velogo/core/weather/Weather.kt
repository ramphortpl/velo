package fr.velogo.core.weather

import fr.velogo.core.geo.Geo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/** Le temps qu'il fait (ou qu'il fera) à un instant donné. Une valeur absente est null. */
data class WeatherPoint(
    val timeMs: Long,
    val temperatureC: Double? = null,
    /** Température ressentie (vent et humidité compris). */
    val apparentC: Double? = null,
    /** Précipitations sur la période (une heure pour les prévisions horaires), en millimètres. */
    val precipitationMm: Double? = null,
    /** Code météo de l'OMM (voir [WeatherCodes]). */
    val code: Int? = null,
    val windKmh: Double? = null,
    /** Direction d'où vient le vent, en degrés (0 = nord, 90 = est). */
    val windFromDeg: Double? = null,
    val gustKmh: Double? = null,
    val isDay: Boolean = true,
)

/** Précipitations prévues sur un quart d'heure. */
data class RainStep(val timeMs: Long, val mm: Double)

/** Prévisions pour un lieu : conditions actuelles, heure par heure, et pluie par quart d'heure sur les prochaines heures. */
data class WeatherReport(
    val lat: Double,
    val lon: Double,
    /** Moment où ces prévisions ont été récupérées. */
    val fetchedAtMs: Long,
    val current: WeatherPoint?,
    val hourly: List<WeatherPoint>,
    val quarters: List<RainStep>,
) {
    /** Prévision horaire la plus proche de [timeMs], ou null si elle sort de la période couverte. */
    fun at(timeMs: Long): WeatherPoint? {
        val nearest = hourly.minByOrNull { abs(it.timeMs - timeMs) } ?: return null
        return nearest.takeIf { abs(it.timeMs - timeMs) <= MAX_GAP_MS }
    }

    /** Prévisions horaires à partir de [fromMs] (l'heure en cours comprise). */
    fun hoursFrom(fromMs: Long, count: Int): List<WeatherPoint> =
        hourly.filter { it.timeMs > fromMs - HOUR_MS }.take(count)

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val MAX_GAP_MS = 90 * 60_000L
    }
}

/** Une averse annoncée. */
data class RainEvent(
    /** Minutes avant le début (0 s'il pleut déjà). */
    val startsInMin: Int,
    /** Minutes avant la fin, ou null si la pluie dure au-delà de la période connue. */
    val endsInMin: Int?,
    /** Intensité maximale, en millimètres par heure. */
    val peakMmPerHour: Double,
) {
    val isNow: Boolean get() = startsInMin <= 0

    /** « faible », « modérée » ou « forte », selon les seuils habituels des météorologues. */
    val intensity: String
        get() = when {
            peakMmPerHour < 2.5 -> "faible"
            peakMmPerHour < 7.6 -> "modérée"
            else -> "forte"
        }
}

/** Lecture des prévisions de pluie à très courte échéance (pas de 15 minutes). */
object RainForecast {
    /** En dessous de cette quantité par quart d'heure, on considère qu'il ne pleut pas (quelques gouttes). */
    const val THRESHOLD_MM = 0.1

    /** Prochaine averse dans les [horizonMin] minutes qui suivent [nowMs], ou null s'il ne pleuvra pas d'ici là. */
    fun next(quarters: List<RainStep>, nowMs: Long, horizonMin: Int = 90): RainEvent? {
        // Le quart d'heure en cours compte : un pas qui a commencé il y a moins de 15 minutes est encore valable.
        val upcoming = quarters.filter { it.timeMs > nowMs - QUARTER_MS }.sortedBy { it.timeMs }
        val first = upcoming.indexOfFirst { it.mm >= THRESHOLD_MM }
        if (first < 0) return null
        val startMs = upcoming[first].timeMs
        val startsIn = ((startMs - nowMs) / 60_000.0).roundToInt().coerceAtLeast(0)
        if (startsIn > horizonMin) return null
        var last = first
        while (last + 1 < upcoming.size && upcoming[last + 1].mm >= THRESHOLD_MM) last++
        val peak = (first..last).maxOf { upcoming[it].mm } * 4.0
        val endsIn = if (last == upcoming.size - 1) null else ((upcoming[last].timeMs + QUARTER_MS - nowMs) / 60_000.0).roundToInt()
        return RainEvent(startsIn, endsIn, peak)
    }

    /** Phrase courte pour l'alerte : « Pluie faible dans 20 min », « Il pleut encore 35 min »… */
    fun describe(event: RainEvent): String = when {
        event.isNow && event.endsInMin != null -> "Pluie ${event.intensity} : encore ${duration(event.endsInMin)}"
        event.isNow -> "Pluie ${event.intensity} en cours"
        else -> "Pluie ${event.intensity} dans ${duration(event.startsInMin)}"
    }

    private fun duration(minutes: Int): String =
        if (minutes < 60) "$minutes min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"

    private const val QUARTER_MS = 15 * 60_000L
}

/** Le vent par rapport au sens de la marche. */
object Wind {
    enum class Side { HEAD, TAIL, CROSS }

    /**
     * Composante du vent dans l'axe de déplacement, en km/h : positive = vent de face (il freine),
     * négative = vent dans le dos.
     */
    fun headwindKmh(windKmh: Double, windFromDeg: Double, bearingDeg: Double): Double =
        windKmh * cos(Math.toRadians(Geo.angleDeltaDeg(bearingDeg, windFromDeg)))

    fun side(windFromDeg: Double, bearingDeg: Double): Side {
        val angle = abs(Geo.angleDeltaDeg(bearingDeg, windFromDeg))
        return when {
            angle <= 50.0 -> Side.HEAD
            angle >= 130.0 -> Side.TAIL
            else -> Side.CROSS
        }
    }

    fun label(side: Side): String = when (side) {
        Side.HEAD -> "de face"
        Side.TAIL -> "de dos"
        Side.CROSS -> "de côté"
    }

    /** Point cardinal d'où vient le vent : « N », « NE », « E »… */
    fun compass(fromDeg: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return names[(((fromDeg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
    }
}

/** Codes météo de l'Organisation météorologique mondiale, tels que les renvoie Open-Meteo. */
object WeatherCodes {
    enum class Kind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SHOWERS, SNOW, THUNDER }

    fun kind(code: Int?): Kind = when (code) {
        0, 1 -> Kind.CLEAR
        2 -> Kind.PARTLY_CLOUDY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57 -> Kind.DRIZZLE
        61, 63, 65, 66, 67 -> Kind.RAIN
        80, 81, 82 -> Kind.SHOWERS
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.THUNDER
        else -> Kind.CLOUDY
    }

    fun label(code: Int?): String = when (code) {
        0 -> "Ciel dégagé"
        1 -> "Peu nuageux"
        2 -> "Éclaircies"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51 -> "Bruine légère"
        53, 55 -> "Bruine"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        66, 67 -> "Pluie verglaçante"
        71 -> "Neige faible"
        73 -> "Neige"
        75 -> "Forte neige"
        77 -> "Grains de neige"
        80 -> "Averses"
        81 -> "Averses"
        82 -> "Fortes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage avec grêle"
        else -> "Temps incertain"
    }
}
