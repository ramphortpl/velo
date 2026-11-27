package fr.velogo.app.map

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.startup.AppInitializer
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.loader.MapboxMapsInitializer
import com.mapbox.maps.module.MapTelemetry
import com.mapbox.maps.module.telemetry.MapTelemetryImpl
import fr.velogo.app.BuildConfig

/**
 * Le kit de cartes de Mapbox, tenu à l'écart tant qu'on ne s'en sert pas.
 *
 * Ses conditions n'admettent ses cartes que dans son propre moteur, qui envoie à Mapbox des données d'utilisation
 * (et compte chaque appareil qui affiche une de ses cartes dans le mois). Il ne démarre donc pas avec l'appli :
 * seulement quand un fond Mapbox est affiché ([ensure]), après l'accord de l'utilisateur. Les autres fonds restent
 * dessinés par MapLibre.
 */
object MapboxSupport {
    /**
     * Jeton public du compte Mapbox de l'auteur, glissé dans l'appli à la fabrication depuis le secret GitHub
     * `MAPBOX_ACCESS_TOKEN` (voir app/build.gradle.kts). Vide quand la fabrication n'en avait pas.
     */
    val token: String get() = BuildConfig.MAPBOX_TOKEN

    @Volatile
    private var ready = false

    /** Vrai si les fonds Mapbox peuvent être proposés sur cet appareil : un jeton dans l'appli, et OpenGL ES 3. */
    fun isAvailable(context: Context): Boolean {
        val glEs = glEsVersion(context)
        val available = isAvailable(token, glEs)
        // Une ligne dans le journal du téléphone, pour savoir pourquoi les fonds Mapbox manquent quand ils manquent
        // (elle dit seulement si un jeton est là, jamais lequel).
        Log.i(
            TAG,
            "Cartes Mapbox ${if (available) "proposées" else "non proposées"} : jeton ${if (token.startsWith("pk.")) "présent" else "absent"}, " +
                "OpenGL ES ${glEs shr 16}.${glEs and 0xFFFF}",
        )
        return available
    }

    /** La même règle, sans téléphone : [glEs] est la version d'OpenGL ES de l'appareil (0x00030000 pour 3.0). */
    internal fun isAvailable(token: String, glEs: Int): Boolean = token.startsWith("pk.") && glEs >= GL_ES_3

    /**
     * Démarre le kit s'il ne l'est pas (chargement de ses bibliothèques, jeton). À appeler avant de créer une carte
     * Mapbox. Renvoie faux s'il n'a pas pu démarrer : le fusible saute, et l'appli revient à ses propres cartes.
     */
    fun ensure(context: Context): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            return try {
                AppInitializer.getInstance(context.applicationContext).initializeComponent(MapboxMapsInitializer::class.java)
                MapboxOptions.accessToken = token
                ready = true
                true
            } catch (failure: Throwable) {
                // Y compris une bibliothèque native introuvable ou refusée par l'appareil (une « Error », pas une exception).
                Log.w(TAG, "Le kit Mapbox n'a pas pu démarrer", failure)
                trip(context)
                false
            }
        }
    }

    /**
     * Accepte ou refuse l'envoi à Mapbox de statistiques d'utilisation (elles comprennent des positions, le modèle
     * du téléphone, l'opérateur, le niveau de batterie). Le kit retient lui-même ce choix d'un lancement à l'autre ;
     * on peut le changer ensuite par le bouton d'information que Mapbox pose sur sa carte. Les demandes de carte et
     * le décompte des appareils, eux, ne se refusent pas.
     *
     * [done] est appelé une fois le choix pris en compte par le kit, avec vrai s'il l'est bien : c'est seulement
     * alors qu'une carte Mapbox peut être affichée. Faux si le kit n'a pas démarré ou n'a pas enregistré le choix.
     */
    fun setStatistics(context: Context, enabled: Boolean, done: (applied: Boolean) -> Unit) {
        if (!ensure(context)) {
            done(false)
            return
        }
        // Le même réglage que celui du bouton d'information de Mapbox (c'est sa propre brique qui le range), donné
        // ici sans carte : il doit être en place avant qu'aucune ne soit créée.
        val telemetry: MapTelemetry = try {
            MapTelemetryImpl(context.applicationContext).also { it.setUserTelemetryRequestState(enabled) }
        } catch (failure: Throwable) {
            Log.w(TAG, "Le choix des statistiques Mapbox n'a pas pu être donné au kit", failure)
            done(false)
            return
        }
        // Le kit range ce choix en tâche de fond : on attend de le relire avant de répondre.
        val main = Handler(Looper.getMainLooper())
        var waited = 0L
        fun check() {
            val applied = runCatching { telemetry.userTelemetryRequestState == enabled }.getOrDefault(false)
            if (applied || waited >= STATISTICS_TIMEOUT_MS) {
                if (!applied) Log.w(TAG, "Le kit Mapbox n'a pas enregistré le choix des statistiques")
                done(applied)
            } else {
                waited += STATISTICS_POLL_MS
                main.postDelayed({ check() }, STATISTICS_POLL_MS)
            }
        }
        check()
    }

    // --- Fusible ----------------------------------------------------------------------------------------------

    private var fuse: MapboxFuse? = null

    private fun fuse(context: Context): MapboxFuse = fuse ?: synchronized(this) {
        fuse ?: MapboxFuse(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { fuse = it }
    }

    /** Vrai quand les fonds Mapbox sont mis de côté pour ce lancement : voir [MapboxFuse]. */
    fun isBlown(context: Context): Boolean = fuse(context).blown

    /** Une carte Mapbox commence à s'afficher. */
    fun displayStarted(context: Context) = fuse(context).displayStarted()

    /** L'affichage s'est terminé normalement (appli en arrière-plan, autre fond choisi). */
    fun displayEnded(context: Context) = fuse(context).displayEnded()

    /** Fait sauter le fusible tout de suite : le kit n'a pas démarré, ou sa carte n'a pas pu être créée. */
    fun trip(context: Context) = fuse(context).trip()

    /** L'utilisateur rechoisit un fond Mapbox : on réessaie. */
    fun rearm(context: Context) = fuse(context).rearm()

    private fun glEsVersion(context: Context): Int =
        runCatching { context.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion }.getOrNull() ?: 0

    private const val TAG = "VeloGoMapbox"
    private const val GL_ES_3 = 0x00030000
    private const val PREFS = "mapbox"
    private const val STATISTICS_TIMEOUT_MS = 3_000L
    private const val STATISTICS_POLL_MS = 100L
}

/**
 * Le fusible des cartes Mapbox.
 *
 * Le moteur de Mapbox est du code natif : s'il plantait à l'affichage sur un téléphone donné, l'appli se fermerait
 * à chaque ouverture, puisqu'elle rouvre le dernier fond choisi, et il ne resterait qu'à effacer ses données,
 * sorties comprises. On compte donc les affichages commencés et jamais terminés normalement (ni mise en
 * arrière-plan, ni changement de fond) : au deuxième d'affilée, les fonds Mapbox sont mis de côté et l'appli revient
 * à son plan. Rechoisir un fond Mapbox réarme le fusible.
 *
 * Un objet par lancement de l'appli : [blown] dit comment se sont finis les lancements d'avant, et ne change
 * ensuite que par [trip] et [rearm].
 */
internal class MapboxFuse(private val prefs: SharedPreferences) {
    var blown by mutableStateOf(prefs.getInt(KEY_UNFINISHED, 0) >= LIMIT)
        private set

    /** Une carte Mapbox commence à s'afficher. Écrit tout de suite : un plantage peut suivre. */
    fun displayStarted() {
        prefs.edit(commit = true) { putInt(KEY_UNFINISHED, prefs.getInt(KEY_UNFINISHED, 0) + 1) }
    }

    /** L'affichage s'est terminé normalement. Sans effet une fois le fusible sauté : il le reste jusqu'à [rearm]. */
    fun displayEnded() {
        if (blown) return
        prefs.edit { putInt(KEY_UNFINISHED, 0) }
    }

    fun trip() {
        prefs.edit(commit = true) { putInt(KEY_UNFINISHED, LIMIT) }
        blown = true
    }

    fun rearm() {
        prefs.edit(commit = true) { putInt(KEY_UNFINISHED, 0) }
        blown = false
    }

    internal companion object {
        const val KEY_UNFINISHED = "affichages_inacheves"
        const val LIMIT = 2
    }
}
