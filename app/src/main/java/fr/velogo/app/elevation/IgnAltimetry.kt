package fr.velogo.app.elevation

import fr.velogo.core.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Service de calcul altimétrique de la Géoplateforme IGN (gratuit, sans clé, données RGE ALTI).
 * Limites officielles respectées : 5 000 points par requête, 5 requêtes par seconde.
 */
class IgnAltimetry(private val http: OkHttpClient) {

    /** Altitude du sol (m) en chaque point ; NaN là où l'IGN n'a pas de donnée. */
    suspend fun elevations(points: List<LatLon>): DoubleArray = withContext(Dispatchers.IO) {
        val out = DoubleArray(points.size) { Double.NaN }
        var start = 0
        while (start < points.size) {
            val end = minOf(start + BATCH, points.size)
            val batch = points.subList(start, end)
            val body = JSONObject()
                .put("lon", batch.joinToString("|") { String.format(Locale.ROOT, "%.6f", it.lon) })
                .put("lat", batch.joinToString("|") { String.format(Locale.ROOT, "%.6f", it.lat) })
                .put("resource", RESOURCE)
                .put("delimiter", "|")
                .put("indent", "false")
                .put("measures", "false")
                .put("zonly", "true")
                .toString()
            val request = Request.Builder().url(URL).post(body.toRequestBody(JSON)).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Altimétrie IGN : HTTP ${response.code}")
                val values = JSONObject(response.body?.string().orEmpty()).getJSONArray("elevations")
                for (i in 0 until minOf(values.length(), batch.size)) {
                    val z = values.optDouble(i, Double.NaN)
                    if (!z.isNaN() && z > NO_DATA) out[start + i] = z
                }
            }
            start = end
            if (start < points.size) delay(PAUSE_MS)
        }
        out
    }

    private companion object {
        const val URL = "https://data.geopf.fr/altimetrie/1.0/calcul/alti/rest/elevation.json"
        const val RESOURCE = "ign_rge_alti_wld"
        const val BATCH = 4_000
        const val PAUSE_MS = 400L
        /** L'IGN renvoie -99999 hors couverture. */
        const val NO_DATA = -9_000.0
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
