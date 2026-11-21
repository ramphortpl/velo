package fr.velogo.app.weather

import fr.velogo.core.geo.LatLon
import fr.velogo.core.weather.RainStep
import fr.velogo.core.weather.WeatherPoint
import fr.velogo.core.weather.WeatherReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/** Le service météo a répondu, mais pas avec des prévisions (quota dépassé, paramètre refusé…). */
class WeatherException(message: String) : IOException(message)

/**
 * Prévisions d'Open-Meteo (gratuit, sans clé ni compte, licence CC BY 4.0), avec les modèles de Météo-France :
 * AROME à 1,5 km sur la France, relayé par ARPEGE au-delà de deux jours. La pluie par quart d'heure vient de la
 * prévision immédiate d'AROME.
 */
class OpenMeteo(private val http: OkHttpClient) {

    /**
     * Demande les prévisions pour un ou plusieurs lieux, en une seule requête, et renvoie la réponse telle quelle
     * (à lire avec [parse]). Lève une [IOException] sans réseau.
     */
    suspend fun fetch(points: List<LatLon>, hours: Int = FORECAST_HOURS): String = withContext(Dispatchers.IO) {
        require(points.isNotEmpty() && points.size <= MAX_POINTS)
        val url = BASE.toHttpUrl().newBuilder()
            // Deux décimales (environ un kilomètre) : bien assez pour la météo, et rien de précis ne sort du téléphone.
            .addQueryParameter("latitude", points.joinToString(",") { coordinate(it.lat) })
            .addQueryParameter("longitude", points.joinToString(",") { coordinate(it.lon) })
            .addQueryParameter("models", "meteofrance_seamless")
            .addQueryParameter("current", VARIABLES)
            .addQueryParameter("hourly", VARIABLES)
            .addQueryParameter("minutely_15", "precipitation")
            .addQueryParameter("forecast_hours", hours.toString())
            .addQueryParameter("forecast_minutely_15", "16")
            .addQueryParameter("timeformat", "unixtime")
            .addQueryParameter("timezone", "GMT")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // En cas de refus (quota, paramètre), Open-Meteo explique pourquoi dans un objet JSON : on le laisse lire.
            if (!response.isSuccessful && !body.trimStart().startsWith("{")) throw IOException("Météo : HTTP ${response.code}")
            body
        }
    }

    companion object {
        private const val BASE = "https://api.open-meteo.com/v1/forecast"
        const val MAX_POINTS = 12
        /** Deux jours de prévisions : de quoi préparer la sortie du lendemain. */
        const val FORECAST_HOURS = 48

        /** Coordonnée telle qu'elle est envoyée : deux décimales. */
        fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
        private const val VARIABLES =
            "temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day"
        private const val QUARTER_S = 900L
        private val json = Json { ignoreUnknownKeys = true }

        /** Lit la réponse d'Open-Meteo : un objet pour un lieu, un tableau pour plusieurs. */
        fun parse(text: String, fetchedAtMs: Long): List<WeatherReport> {
            val root = try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw WeatherException("Réponse météo illisible")
            }
            if (root is JsonObject && (root["error"] as? JsonPrimitive)?.booleanOrNull == true) {
                throw WeatherException((root["reason"] as? JsonPrimitive)?.content ?: "Service météo indisponible")
            }
            val places = when (root) {
                is JsonArray -> root.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(root)
                else -> emptyList()
            }
            if (places.isEmpty()) throw WeatherException("Réponse météo vide")
            return places.map { place(it, fetchedAtMs) }
        }

        private fun place(o: JsonObject, fetchedAtMs: Long): WeatherReport {
            val current = (o["current"] as? JsonObject)?.let { c ->
                val time = c.long("time") ?: return@let null
                WeatherPoint(
                    timeMs = time * 1000,
                    temperatureC = c.double("temperature_2m"), apparentC = c.double("apparent_temperature"),
                    precipitationMm = c.double("precipitation"), code = c.int("weather_code"),
                    windKmh = c.double("wind_speed_10m"), windFromDeg = c.double("wind_direction_10m"), gustKmh = c.double("wind_gusts_10m"),
                    isDay = c.int("is_day") != 0,
                )
            }
            val hourly = (o["hourly"] as? JsonObject)?.let { h ->
                val times = h.array("time")
                times.indices.mapNotNull { i ->
                    val time = times[i].long() ?: return@mapNotNull null
                    WeatherPoint(
                        timeMs = time * 1000,
                        temperatureC = h.at("temperature_2m", i), apparentC = h.at("apparent_temperature", i),
                        // Open-Meteo donne à chaque heure la pluie de l'heure qui la précède : celle de l'heure qui
                        // commence est donc dans la valeur suivante.
                        precipitationMm = h.at("precipitation", i + 1), code = h.at("weather_code", i)?.toInt(),
                        windKmh = h.at("wind_speed_10m", i), windFromDeg = h.at("wind_direction_10m", i), gustKmh = h.at("wind_gusts_10m", i),
                        isDay = h.at("is_day", i)?.toInt() != 0,
                    )
                    // Une heure sans aucune valeur (au-delà de la portée du modèle) n'apporte rien.
                }.filter { it.temperatureC != null || it.precipitationMm != null }
            }.orEmpty()
            val quarters = (o["minutely_15"] as? JsonObject)?.let { m ->
                val times = m.array("time")
                times.indices.mapNotNull { i ->
                    val time = times[i].long() ?: return@mapNotNull null
                    val mm = m.at("precipitation", i) ?: return@mapNotNull null
                    // Même convention : la valeur datée de 10 h 15 est la pluie tombée entre 10 h et 10 h 15.
                    RainStep((time - QUARTER_S) * 1000, mm)
                }
            }.orEmpty()
            return WeatherReport(
                lat = o.double("latitude") ?: 0.0, lon = o.double("longitude") ?: 0.0,
                fetchedAtMs = fetchedAtMs, current = current, hourly = hourly, quarters = quarters,
            )
        }

        private fun JsonElement.long(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
        private fun JsonObject.long(key: String): Long? = this[key]?.long()
        private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
        private fun JsonObject.at(key: String, index: Int): Double? =
            ((this[key] as? JsonArray)?.getOrNull(index) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    }
}
