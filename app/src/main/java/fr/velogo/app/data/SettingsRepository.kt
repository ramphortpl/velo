package fr.velogo.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.velogo.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "reglages")

/** Réglages de l'appli. Les valeurs par défaut sont celles d'une première installation. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Écran guidon en noir sur blanc, pour le plein soleil. */
    val sunlightDashboard: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Luminosité au maximum pendant une sortie. */
    val maxBrightness: Boolean = false,
    val autoPause: Boolean = true,
    /** Identifiant du fond de carte choisi (voir MapLayers). */
    val mapLayer: String = "openfreemap",
    /** Carte orientée dans le sens de la marche (sinon nord en haut). */
    val headingUp: Boolean = false,
    /** Recaler l'altitude sur le relief IGN à la fin d'une sortie (demande du réseau). */
    val ignAltitudeCorrection: Boolean = true,
    /** L'explication sur l'économie de batterie Samsung a déjà été montrée. */
    val batteryHintShown: Boolean = false,
    val checkUpdates: Boolean = true,
    val lastUpdateCheckMs: Long = 0,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<Settings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            val d = Settings()
            Settings(
                themeMode = p[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: d.themeMode,
                sunlightDashboard = p[SUNLIGHT] ?: d.sunlightDashboard,
                keepScreenOn = p[KEEP_SCREEN_ON] ?: d.keepScreenOn,
                maxBrightness = p[MAX_BRIGHTNESS] ?: d.maxBrightness,
                autoPause = p[AUTO_PAUSE] ?: d.autoPause,
                mapLayer = p[MAP_LAYER] ?: d.mapLayer,
                headingUp = p[HEADING_UP] ?: d.headingUp,
                ignAltitudeCorrection = p[IGN_CORRECTION] ?: d.ignAltitudeCorrection,
                batteryHintShown = p[BATTERY_HINT] ?: d.batteryHintShown,
                checkUpdates = p[CHECK_UPDATES] ?: d.checkUpdates,
                lastUpdateCheckMs = p[LAST_UPDATE_CHECK] ?: d.lastUpdateCheckMs,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }.let { }
    suspend fun setSunlightDashboard(value: Boolean) = store.edit { it[SUNLIGHT] = value }.let { }
    suspend fun setKeepScreenOn(value: Boolean) = store.edit { it[KEEP_SCREEN_ON] = value }.let { }
    suspend fun setMaxBrightness(value: Boolean) = store.edit { it[MAX_BRIGHTNESS] = value }.let { }
    suspend fun setAutoPause(value: Boolean) = store.edit { it[AUTO_PAUSE] = value }.let { }
    suspend fun setMapLayer(id: String) = store.edit { it[MAP_LAYER] = id }.let { }
    suspend fun setHeadingUp(value: Boolean) = store.edit { it[HEADING_UP] = value }.let { }
    suspend fun setIgnAltitudeCorrection(value: Boolean) = store.edit { it[IGN_CORRECTION] = value }.let { }
    suspend fun setBatteryHintShown(value: Boolean) = store.edit { it[BATTERY_HINT] = value }.let { }
    suspend fun setCheckUpdates(value: Boolean) = store.edit { it[CHECK_UPDATES] = value }.let { }
    suspend fun setLastUpdateCheck(timeMs: Long) = store.edit { it[LAST_UPDATE_CHECK] = timeMs }.let { }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val SUNLIGHT = booleanPreferencesKey("plein_soleil")
        val KEEP_SCREEN_ON = booleanPreferencesKey("ecran_allume")
        val MAX_BRIGHTNESS = booleanPreferencesKey("luminosite_max")
        val AUTO_PAUSE = booleanPreferencesKey("pause_auto")
        val MAP_LAYER = stringPreferencesKey("fond_de_carte")
        val HEADING_UP = booleanPreferencesKey("cap_en_haut")
        val IGN_CORRECTION = booleanPreferencesKey("recalage_ign")
        val BATTERY_HINT = booleanPreferencesKey("conseil_batterie_vu")
        val CHECK_UPDATES = booleanPreferencesKey("verifier_mises_a_jour")
        val LAST_UPDATE_CHECK = longPreferencesKey("derniere_verification")
    }
}
