package fr.velogo.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import fr.velogo.core.geo.CourseEstimator
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlin.math.abs

/**
 * Une position GPS : la version « calcul » ([fix]) et l'objet Android ([location]) pour la carte.
 *
 * [courseDeg] est le sens de la marche (0 = nord, 90 = est) : celui du GPS quand on roule, sinon celui déduit du
 * déplacement, et à l'arrêt le dernier connu. [location] le porte aussi, pour que la flèche de la carte et la
 * carte orientée dans le sens de la marche fonctionnent même si le GPS ne donne pas de cap.
 */
class GpsFix(val fix: Fix, val location: Location, val courseDeg: Double? = null, val speedMps: Double = fix.speedMps ?: 0.0)

/**
 * Le GPS du téléphone, partagé par toute l'appli (carte et enregistrement).
 *
 * Il n'est allumé que tant que quelqu'un écoute [fixes] : dès que plus personne n'écoute (appli en arrière-plan
 * sans enregistrement), il s'éteint pour économiser la batterie. On utilise le GPS « brut » d'Android, sans
 * dépendre des services Google, à une position par seconde.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GpsSource(private val context: Context, scope: CoroutineScope) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("velogo-gps").apply { start() }

    /** Sens de la marche, suivi d'une position à l'autre (toujours depuis le fil du GPS). */
    private val course = CourseEstimator()

    /** Change quand l'autorisation de localisation vient d'être accordée, pour (re)lancer l'écoute. */
    private val permissionEpoch = MutableStateFlow(0)

    /** Dernière position reçue, même si plus personne n'écoute (pour la météo, qui n'allume jamais le GPS). */
    @Volatile
    var last: GpsFix? = null
        private set

    val fixes: SharedFlow<GpsFix> = permissionEpoch
        .flatMapLatest { if (hasPermission()) updates() else emptyFlow() }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 3_000, replayExpirationMillis = 0), replay = 1)

    /**
     * Dernière position connue du téléphone, même approximative et sans allumer le GPS : celle de l'appli si elle
     * en a reçu une, sinon celle qu'Android garde en mémoire (si elle date de moins de six heures). Bien assez
     * pour la météo, qui s'affiche ainsi avant même que le GPS ait trouvé ses satellites.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(): LatLon? {
        last?.let { return LatLon(it.fix.lat, it.fix.lon) }
        val manager = locationManager ?: return null
        if (!hasPermission()) return null
        val best = runCatching {
            manager.getProviders(true).mapNotNull { provider -> manager.getLastKnownLocation(provider) }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        if (abs(System.currentTimeMillis() - best.time) > MAX_LAST_KNOWN_AGE_MS) return null
        return LatLon(best.latitude, best.longitude)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Vrai si la localisation est activée dans les réglages du téléphone. */
    fun isLocationEnabled(): Boolean = locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager)

    /** À appeler quand l'autorisation de localisation vient d'être accordée. */
    fun onPermissionChanged() = restart()

    /** Relance l'écoute du GPS (nouvelle autorisation, ou GPS muet depuis trop longtemps). */
    fun restart() {
        permissionEpoch.value++
    }

    @SuppressLint("MissingPermission")
    private fun updates(): Flow<GpsFix> = callbackFlow {
        val manager = locationManager
        if (manager == null || !manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            awaitClose { }
            return@callbackFlow
        }
        val listener = LocationListenerCompat { location -> trySend(convert(location).also { last = it }) }
        val request = LocationRequestCompat.Builder(INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(manager, LocationManager.GPS_PROVIDER, request, listener, thread.looper)
        } catch (_: SecurityException) {
            // Autorisation retirée entre-temps : on attend qu'elle revienne.
        }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    private fun convert(location: Location): GpsFix {
        val now = System.currentTimeMillis()
        // Quelques puces GPS renvoient une date fantaisiste : dans ce cas on prend l'horloge du téléphone.
        val time = if (abs(location.time - now) > MAX_CLOCK_SKEW_MS) now else location.time
        val fix = Fix(
            timeMs = time,
            monoMs = location.elapsedRealtimeNanos / 1_000_000,
            lat = location.latitude,
            lon = location.longitude,
            altitudeM = seaLevelAltitude(location),
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            verticalAccuracyM = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
        )
        val estimate = course.update(fix.lat, fix.lon, fix.monoMs, fix.bearingDeg, fix.speedMps)
        val shown = Location(location).apply {
            val bearingDeg = estimate.bearingDeg
            if (bearingDeg != null) bearing = bearingDeg.toFloat() else removeBearing()
            if (!hasSpeed()) speed = estimate.speedMps.toFloat()
        }
        return GpsFix(fix, shown, estimate.bearingDeg, estimate.speedMps)
    }

    /**
     * Le GPS donne une hauteur au-dessus d'un ellipsoïde mathématique, pas au-dessus de la mer :
     * en France l'écart est d'environ 45 à 55 m. On convertit avec le modèle de géoïde d'Android.
     */
    private fun seaLevelAltitude(location: Location): Double? {
        if (!location.hasAltitude()) return null
        if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) return location.mslAltitudeMeters
        try {
            AltitudeConverterCompat.addMslAltitudeToLocation(context, location)
            if (LocationCompat.hasMslAltitude(location)) return LocationCompat.getMslAltitudeMeters(location)
        } catch (_: Exception) {
            // Modèle de géoïde indisponible : on retombe sur la correction moyenne ci-dessous.
        }
        return location.altitude - FRANCE_MEAN_GEOID_M
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L
        const val MAX_LAST_KNOWN_AGE_MS = 6 * 60 * 60 * 1000L
        /** Hauteur moyenne du géoïde en France métropolitaine : simple repli si la conversion précise échoue. */
        const val FRANCE_MEAN_GEOID_M = 48.0
    }
}
