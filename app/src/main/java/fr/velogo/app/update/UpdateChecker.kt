package fr.velogo.app.update

import fr.velogo.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** Une version de VeloGo publiée dans les Releases du dépôt. */
data class UpdateInfo(
    val versionName: String,
    val buildNumber: Int,
    /** Lien direct vers l'APK. */
    val apkUrl: String,
    /** Page de la version sur GitHub. */
    val pageUrl: String,
    val notes: String,
) {
    val isNewer: Boolean get() = buildNumber > BuildConfig.VERSION_CODE
}

/**
 * Cherche la dernière version de l'appli dans les Releases GitHub du dépôt (API publique, sans compte).
 * L'installation reste manuelle : on ouvre le lien de l'APK, Android demande confirmation.
 */
class UpdateChecker(private val http: OkHttpClient, private val repository: String = BuildConfig.GITHUB_REPO) {

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            parse(response.body?.string().orEmpty())
        }
    }

    companion object {
        private val TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

        /** Garde, parmi les releases « v1.2.3 » accompagnées d'un APK, celle dont le numéro de build est le plus grand. */
        fun parse(json: String): UpdateInfo? {
            val releases = runCatching { JSONArray(json) }.getOrNull() ?: return null
            var best: UpdateInfo? = null
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft")) continue
                val tag = release.optString("tag_name")
                val match = TAG.matchEntire(tag) ?: continue
                val build = match.groupValues[3].toIntOrNull() ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                var apk: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    if (asset.optString("name").endsWith(".apk")) apk = asset.optString("browser_download_url")
                }
                if (apk.isNullOrEmpty()) continue
                if (best == null || build > best.buildNumber) {
                    best = UpdateInfo(tag.removePrefix("v"), build, apk, release.optString("html_url"), release.optString("body"))
                }
            }
            return best
        }
    }
}
