package fr.velogo.core.weather

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Route
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Un endroit de l'itinéraire où l'on regarde la météo. */
data class RouteSample(
    /** Distance depuis le départ de l'itinéraire, en mètres. */
    val distanceM: Double,
    val position: LatLon,
    /** Direction générale dans laquelle on roule à cet endroit, en degrés. */
    val bearingDeg: Double,
    /** Temps de trajet jusqu'à cet endroit, en secondes depuis le point de départ du calcul. */
    val offsetS: Double,
)

/** La météo prévue à un endroit de l'itinéraire, à l'heure où l'on y passera. */
data class RouteWeatherRow(
    val distanceM: Double,
    /** Heure de passage estimée. */
    val timeMs: Long,
    /** Null si les prévisions ne vont pas jusqu'à cette heure-là. */
    val weather: WeatherPoint?,
    /** Vent dans l'axe de la route, en km/h : positif de face, négatif de dos. */
    val headwindKmh: Double?,
    val side: Wind.Side?,
) {
    /** Vrai s'il est prévu qu'il pleuve (ou neige) à cet endroit quand on y passera. */
    val isWet: Boolean get() = (weather?.precipitationMm ?: 0.0) >= RouteWeather.WET_MM_PER_HOUR
}

/** Ce qu'il faut retenir de la météo d'un trajet. */
data class RouteWeatherSummary(
    val minTemperatureC: Double?,
    val maxTemperatureC: Double?,
    /** Premier endroit où il pleuvra, s'il y en a un. */
    val firstRain: RouteWeatherRow?,
    /** Vrai si ce premier endroit est le point de départ. */
    val rainAtStart: Boolean,
    /** Vent moyen dans l'axe du trajet, en km/h : positif de face, négatif de dos. */
    val meanHeadwindKmh: Double?,
    /** Force moyenne du vent, en km/h. */
    val meanWindKmh: Double?,
    val maxGustKmh: Double?,
)

/**
 * Météo le long d'un itinéraire : où la regarder, à quelle heure on y passera, et ce qu'on en retient.
 */
object RouteWeather {
    /** Précipitations horaires à partir desquelles on annonce de la pluie sur le trajet. */
    const val WET_MM_PER_HOUR = 0.2

    /**
     * Temps de passage estimé à chaque point de l'itinéraire, en secondes depuis le départ.
     *
     * La durée totale est celle de l'itinéraire ; elle est répartie selon le relief, car on met bien plus
     * longtemps à monter un col qu'à le descendre : sans cela, l'heure de passage au sommet serait fausse d'une
     * heure sur une sortie en montagne. Sans profil d'altitude, la répartition suit simplement la distance.
     */
    fun passageTimes(route: Route): DoubleArray {
        val n = route.points.size
        val total = route.distanceM
        val out = DoubleArray(n)
        if (total <= 0.0) return out
        val elevations = route.elevations
        val costs = DoubleArray(n)
        if (elevations != null) {
            var anchor = 0
            var cost = 0.0
            for (i in 1 until n) {
                val d = route.distances[i] - route.distances[anchor]
                if (d < STRETCH_M && i < n - 1) continue
                val pace = if (d > 0.0) 1.0 / speedMps((elevations[i] - elevations[anchor]) / d) else 0.0
                for (j in anchor + 1..i) costs[j] = cost + (route.distances[j] - route.distances[anchor]) * pace
                cost = costs[i]
                anchor = i
            }
        }
        val sum = costs[n - 1]
        for (i in 0 until n) {
            out[i] = if (sum > 0.0) route.durationS * costs[i] / sum else route.durationS * route.distances[i] / total
        }
        return out
    }

    /**
     * Vitesse d'un cycliste « moyen » sur une pente donnée, à puissance constante (frottements, gravité et
     * résistance de l'air). Seul le rapport entre les pentes compte ici : la durée totale vient d'ailleurs.
     */
    internal fun speedMps(slope: Double): Double {
        val resistance = MASS_KG * 9.81 * (slope.coerceIn(-0.25, 0.25) + ROLLING)
        fun excess(v: Double) = v * (resistance + AIR * v * v) - POWER_W
        if (excess(MAX_SPEED_MPS) <= 0.0) return MAX_SPEED_MPS
        if (excess(MIN_SPEED_MPS) >= 0.0) return MIN_SPEED_MPS
        var low = MIN_SPEED_MPS
        var high = MAX_SPEED_MPS
        repeat(30) {
            val middle = (low + high) / 2
            if (excess(middle) > 0.0) high = middle else low = middle
        }
        return (low + high) / 2
    }

    /**
     * Les endroits où regarder la météo entre [fromM] (là où l'on est) et l'arrivée : le départ, l'arrivée, et
     * un point tous les huit kilomètres environ, sans dépasser [maxCount].
     */
    fun samples(route: Route, fromM: Double = 0.0, maxCount: Int = 8): List<RouteSample> {
        val start = fromM.coerceIn(0.0, route.distanceM)
        val remaining = route.distanceM - start
        val count = (ceil(remaining / SPACING_M).toInt() + 1).coerceIn(2, maxCount.coerceAtLeast(2))
        val times = passageTimes(route)
        val startS = interpolate(route.distances, times, start)
        // La direction est prise sur un bon kilomètre : sur une route sinueuse, c'est le cap général qui compte.
        val span = min(HEADING_SPAN_M, remaining / (count - 1) / 2).coerceAtLeast(50.0)
        return (0 until count).map { k ->
            val d = start + remaining * k / (count - 1)
            val position = pointAt(route, d)
            val before = pointAt(route, (d - span).coerceAtLeast(0.0))
            val after = pointAt(route, (d + span).coerceAtMost(route.distanceM))
            RouteSample(
                distanceM = d,
                position = position,
                bearingDeg = Geo.bearingDeg(before.lat, before.lon, after.lat, after.lon),
                offsetS = interpolate(route.distances, times, d) - startS,
            )
        }
    }

    /** Associe à chaque endroit la prévision de l'heure où l'on y sera, en partant à [departureMs]. */
    fun rows(samples: List<RouteSample>, reports: List<WeatherReport>, departureMs: Long): List<RouteWeatherRow> =
        samples.mapIndexedNotNull { index, sample ->
            val report = reports.getOrNull(index) ?: return@mapIndexedNotNull null
            val time = departureMs + (sample.offsetS * 1000).toLong()
            val weather = report.at(time)
            val wind = weather?.windKmh
            val from = weather?.windFromDeg
            RouteWeatherRow(
                distanceM = sample.distanceM,
                timeMs = time,
                weather = weather,
                headwindKmh = if (wind != null && from != null) Wind.headwindKmh(wind, from, sample.bearingDeg) else null,
                side = if (wind != null && from != null && wind >= CALM_KMH) Wind.side(from, sample.bearingDeg) else null,
            )
        }

    fun summary(rows: List<RouteWeatherRow>): RouteWeatherSummary {
        val temperatures = rows.mapNotNull { it.weather?.temperatureC }
        val headwinds = rows.mapNotNull { it.headwindKmh }
        val winds = rows.mapNotNull { it.weather?.windKmh }
        val firstRain = rows.firstOrNull { it.isWet }
        return RouteWeatherSummary(
            minTemperatureC = temperatures.minOrNull(),
            maxTemperatureC = temperatures.maxOrNull(),
            firstRain = firstRain,
            rainAtStart = firstRain != null && firstRain === rows.firstOrNull(),
            meanHeadwindKmh = headwinds.takeIf { it.isNotEmpty() }?.average(),
            meanWindKmh = winds.takeIf { it.isNotEmpty() }?.average(),
            maxGustKmh = rows.mapNotNull { it.weather?.gustKmh }.maxOrNull(),
        )
    }

    /**
     * Le résumé en une ligne : « 11 à 14° · vent de face 15 km/h · pluie vers 15:30 (km 24) ».
     * [formatTime] met une heure en forme (« 15:30 ») dans le fuseau du téléphone.
     */
    fun headline(summary: RouteWeatherSummary, formatTime: (Long) -> String): String {
        val parts = ArrayList<String>(3)
        val low = summary.minTemperatureC?.roundToInt()
        val high = summary.maxTemperatureC?.roundToInt()
        if (low != null && high != null) parts.add(if (low == high) "$low°" else "$low à $high°")
        windPhrase(summary)?.let { parts.add(it) }
        val rain = summary.firstRain
        parts.add(
            when {
                rain == null -> "sans pluie"
                summary.rainAtStart -> "pluie dès le départ"
                else -> "pluie vers ${formatTime(rain.timeMs)} (km ${(rain.distanceM / 1000).roundToInt()})"
            }
        )
        return parts.joinToString(" · ")
    }

    private fun windPhrase(summary: RouteWeatherSummary): String? {
        val head = summary.meanHeadwindKmh ?: return null
        val force = summary.meanWindKmh ?: return null
        return when {
            force < CALM_KMH -> "peu de vent"
            head >= HEAD_KMH -> "vent de face ${abs(head).roundToInt()} km/h"
            head <= -HEAD_KMH -> "vent de dos ${abs(head).roundToInt()} km/h"
            else -> "vent de côté ${force.roundToInt()} km/h"
        }
    }

    /** Temps de trajet estimé, en secondes, entre le départ de l'itinéraire et le point situé à [distanceM]. */
    fun timeAt(route: Route, distanceM: Double): Double =
        interpolate(route.distances, passageTimes(route), distanceM.coerceIn(0.0, route.distanceM))

    /** Point de l'itinéraire situé à [distanceM] du départ. */
    fun pointAt(route: Route, distanceM: Double): LatLon {
        val d = distanceM.coerceIn(0.0, route.distanceM)
        val i = segmentAt(route.distances, d)
        val a = route.points[i]
        val b = route.points[i + 1]
        val length = route.distances[i + 1] - route.distances[i]
        val f = if (length > 0.0) (d - route.distances[i]) / length else 0.0
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    private fun interpolate(distances: DoubleArray, values: DoubleArray, d: Double): Double {
        val i = segmentAt(distances, d)
        val length = distances[i + 1] - distances[i]
        val f = if (length > 0.0) (d - distances[i]) / length else 0.0
        return values[i] + (values[i + 1] - values[i]) * f.coerceIn(0.0, 1.0)
    }

    /** Indice du segment [i, i + 1] qui contient la distance [d] (recherche par dichotomie). */
    private fun segmentAt(distances: DoubleArray, d: Double): Int {
        var low = 0
        var high = distances.size - 1
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (distances[middle] <= d) low = middle else high = middle
        }
        return low
    }

    private const val SPACING_M = 8_000.0
    private const val HEADING_SPAN_M = 1_200.0
    private const val STRETCH_M = 200.0
    /** En dessous, on parle de « peu de vent » et le sens du vent n'a pas d'importance. */
    private const val CALM_KMH = 8.0
    private const val HEAD_KMH = 6.0

    // Cycliste « moyen » : 85 kg avec le vélo, 140 W, position relevée.
    private const val MASS_KG = 85.0
    private const val POWER_W = 140.0
    private const val ROLLING = 0.006
    private const val AIR = 0.27
    private const val MIN_SPEED_MPS = 1.0
    private const val MAX_SPEED_MPS = 12.5
}
