package fr.velogo.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    /** Ne télécharger les cartes hors ligne qu'en Wi-Fi. */
    val offlineWifiOnly: Boolean = true,
    /** Emplacement des cartes hors ligne : 0 = mémoire du téléphone, 1 = carte SD. */
    val offlineStorage: Int = 0,
    /** Consignes de guidage dites à voix haute (la musique baisse un instant, sans s'arrêter). */
    val voiceGuidance: Boolean = false,
    /** Vibration à chaque consigne et en cas de sortie d'itinéraire. */
    val vibrateGuidance: Boolean = true,
    /** Type de vélo choisi pour les itinéraires (nom d'un BikeType). */
    val bikeType: String = "HYBRID",
    /** Puissance moyenne du cycliste en watts : sert à estimer la durée des itinéraires. */
    val riderPowerW: Int = 140,
    /** Afficher la météo (demande du réseau de temps en temps). */
    val weatherEnabled: Boolean = true,
    /** Prévenir (vibration, notification, voix si le guidage vocal est actif) quand la pluie approche pendant une sortie. */
    val rainAlert: Boolean = true,
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
                offlineWifiOnly = p[OFFLINE_WIFI_ONLY] ?: d.offlineWifiOnly,
                offlineStorage = p[OFFLINE_STORAGE] ?: d.offlineStorage,
                voiceGuidance = p[VOICE_GUIDANCE] ?: d.voiceGuidance,
                vibrateGuidance = p[VIBRATE_GUIDANCE] ?: d.vibrateGuidance,
                bikeType = p[BIKE_TYPE] ?: d.bikeType,
                riderPowerW = p[RIDER_POWER] ?: d.riderPowerW,
                weatherEnabled = p[WEATHER] ?: d.weatherEnabled,
                rainAlert = p[RAIN_ALERT] ?: d.rainAlert,
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
    suspend fun setOfflineWifiOnly(value: Boolean) = store.edit { it[OFFLINE_WIFI_ONLY] = value }.let { }
    suspend fun setOfflineStorage(index: Int) = store.edit { it[OFFLINE_STORAGE] = index }.let { }
    suspend fun setVoiceGuidance(value: Boolean) = store.edit { it[VOICE_GUIDANCE] = value }.let { }
    suspend fun setVibrateGuidance(value: Boolean) = store.edit { it[VIBRATE_GUIDANCE] = value }.let { }
    suspend fun setBikeType(name: String) = store.edit { it[BIKE_TYPE] = name }.let { }
    suspend fun setRiderPower(watts: Int) = store.edit { it[RIDER_POWER] = watts }.let { }
    suspend fun setWeatherEnabled(value: Boolean) = store.edit { it[WEATHER] = value }.let { }
    suspend fun setRainAlert(value: Boolean) = store.edit { it[RAIN_ALERT] = value }.let { }

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
        val OFFLINE_WIFI_ONLY = booleanPreferencesKey("cartes_wifi_seulement")
        val OFFLINE_STORAGE = intPreferencesKey("cartes_emplacement")
        val VOICE_GUIDANCE = booleanPreferencesKey("guidage_vocal")
        val VIBRATE_GUIDANCE = booleanPreferencesKey("guidage_vibration")
        val BIKE_TYPE = stringPreferencesKey("type_de_velo")
        val RIDER_POWER = intPreferencesKey("puissance_cycliste")
        val WEATHER = booleanPreferencesKey("meteo")
        val RAIN_ALERT = booleanPreferencesKey("alerte_pluie")
    }
}
