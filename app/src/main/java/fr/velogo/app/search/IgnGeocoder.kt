package fr.velogo.app.search

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Un lieu trouvé : une adresse ou un endroit nommé (gare, col, lac…). */
data class Place(val title: String, val subtitle: String, val position: LatLon)

/**
 * Recherche d'adresses et de lieux avec le service de géocodage de la Géoplateforme IGN : gratuit, sans clé,
 * limité à la France. Il faut du réseau ; hors ligne, on place ses points directement sur la carte.
 */
class IgnGeocoder(private val http: OkHttpClient) {

    /** Lieux correspondant au texte tapé, les plus proches de [near] d'abord quand c'est précisé. */
    suspend fun search(text: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        val query = text.trim()
        if (query.length < MIN_QUERY) return@withContext emptyList()
        val url = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(MAX_QUERY))
            .addQueryParameter("limit", "8")
            .addQueryParameter("autocomplete", "1")
            .addQueryParameter("index", "address,poi")
            .apply {
                if (near != null) {
                    addQueryParameter("lat", String.format(Locale.ROOT, "%.4f", near.lat))
                    addQueryParameter("lon", String.format(Locale.ROOT, "%.4f", near.lon))
                }
            }
            .build()
        features(Request.Builder().url(url).build()).mapNotNull { place(it) }.distinctBy { it.title to it.subtitle }
    }

    /** Adresse la plus proche d'un point (pour nommer un point choisi sur la carte), ou null. */
    suspend fun reverse(position: LatLon): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", String.format(Locale.ROOT, "%.6f", position.lat))
            .addQueryParameter("lon", String.format(Locale.ROOT, "%.6f", position.lon))
            .addQueryParameter("limit", "1")
            .addQueryParameter("index", "address")
            .build()
        val feature = runCatching { features(Request.Builder().url(url).build()).firstOrNull() }.getOrNull() ?: return@withContext null
        val properties = feature.optJSONObject("properties") ?: return@withContext null
        // Au-delà de quelques centaines de mètres, l'adresse la plus proche ne décrit plus l'endroit.
        if (properties.optDouble("distance", 0.0) > MAX_REVERSE_DISTANCE_M) return@withContext null
        text(properties, "label") ?: text(properties, "name")
    }

    private fun features(request: Request): List<JSONObject> =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val array = JSONObject(response.body?.string().orEmpty()).optJSONArray("features") ?: return emptyList()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }

    private fun place(feature: JSONObject): Place? {
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val lon = coordinates.optDouble(0, Double.NaN)
        val lat = coordinates.optDouble(1, Double.NaN)
        if (lat.isNaN() || lon.isNaN() || !Geo.FRANCE.contains(lat, lon)) return null
        val p = feature.optJSONObject("properties") ?: return null
        val city = listOfNotNull(text(p, "postcode"), text(p, "city")).joinToString(" ")
        return if (p.optString("_type") == "poi") {
            val title = text(p, "toponym") ?: text(p, "name") ?: return null
            val category = text(p, "category")?.replaceFirstChar { it.uppercase() }
            Place(title, listOfNotNull(category, city.ifEmpty { null }).joinToString(" · "), LatLon(lat, lon))
        } else {
            val title = text(p, "name") ?: text(p, "label") ?: return null
            Place(title, city.ifEmpty { text(p, "context").orEmpty() }, LatLon(lat, lon))
        }
    }

    /** Le service renvoie tantôt un texte, tantôt une liste de textes : on prend le premier. */
    private fun text(properties: JSONObject, key: String): String? {
        val value = properties.opt(key) ?: return null
        val first = if (value is JSONArray) value.opt(0) else value
        return (first as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val BASE = "https://data.geopf.fr/geocodage"
        const val MIN_QUERY = 3
        const val MAX_QUERY = 120
        const val MAX_REVERSE_DISTANCE_M = 300.0
    }
}
