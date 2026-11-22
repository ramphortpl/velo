package fr.velogo.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import fr.velogo.core.geo.CourseEstimator
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.PositionArbiter
import fr.velogo.core.ride.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor
import kotlin.math.abs

/** Ce que l'appli a le droit de savoir de la position. */
enum class LocationAccess {
    /** Rien : l'autorisation n'a pas été donnée. */
    NONE,
    /** La position à quelques kilomètres près seulement (« position approximative » d'Android 12 et suivants). */
    APPROXIMATE,
    /** La position exacte : la seule qui permette d'enregistrer une sortie. */
    PRECISE,
}

/**
 * Une position : la version « calcul » ([fix]) et l'objet Android ([location]) pour la carte.
 *
 * [courseDeg] est le sens de la marche (0 = nord, 90 = est) : celui du GPS quand on roule, sinon celui déduit du
 * déplacement, et à l'arrêt le dernier connu. [location] le porte aussi, pour que la flèche de la carte et la
 * carte orientée dans le sens de la marche fonctionnent même si le GPS ne donne pas de cap.
 *
 * [approximate] : la position ne vient pas du GPS mais du Wi-Fi et du réseau, en attendant qu'il trouve ses
 * satellites. Elle sert à situer sur la carte, jamais à enregistrer ni à guider.
 */
class GpsFix(
    val fix: Fix,
    val location: Location,
    val courseDeg: Double? = null,
    val speedMps: Double = fix.speedMps ?: 0.0,
    val approximate: Boolean = false,
)

/** L'état de la localisation, tel que l'appli le constate pendant qu'elle écoute. */
data class GpsStatus(
    /** L'appli est en train d'écouter la position. */
    val listening: Boolean = false,
    /** La localisation est activée dans les réglages du téléphone. */
    val locationEnabled: Boolean = true,
    /** Le téléphone a un GPS. */
    val gpsPresent: Boolean = true,
    /** Le GPS lui-même est en service (certains téléphones le coupent en gardant la localisation « active »). */
    val gpsEnabled: Boolean = true,
    /** Le GPS donne des positions en ce moment. */
    val hasFix: Boolean = false,
    /** Leur précision en mètres (null si le GPS se tait, ou s'il ne la dit pas). */
    val accuracyM: Double? = null,
    /** À défaut de GPS, la carte montre une position approchée (Wi-Fi, réseau mobile). */
    val hasApproximate: Boolean = false,
    /** Sa précision en mètres, quand le téléphone la donne. */
    val approximateAccuracyM: Double? = null,
    /** Satellites dont le téléphone reçoit le signal ; null tant qu'il ne l'a pas dit. */
    val satellitesSeen: Int? = null,
    /** Satellites qui servent au calcul de la position. */
    val satellitesUsed: Int? = null,
    /** Depuis combien de secondes le GPS cherche sans rien donner ; null quand il donne des positions. */
    val searchingS: Int? = null,
)

/**
 * La position du téléphone, partagée par toute l'appli (carte, enregistrement, guidage).
 *
 * Elle n'est écoutée que tant que quelqu'un la demande : dès que plus personne n'écoute (appli en arrière-plan
 * sans enregistrement), tout s'éteint pour économiser la batterie.
 *
 * - [fixes] : les positions du GPS « brut » d'Android, une par seconde. C'est la seule source des sorties
 *   enregistrées et du guidage.
 * - [positions] : les mêmes, plus une position approchée (Wi-Fi, réseau mobile) tant que le GPS n'a pas trouvé
 *   ses satellites. C'est ce que montre la carte : en intérieur, ou pendant un démarrage à froid qui dure des
 *   minutes sur certains téléphones, on voit quand même où l'on est. [PositionArbiter] décide quand s'en servir.
 * - [status] : ce qui se passe (GPS coupé, satellites captés, recherche en cours), pour l'expliquer à l'écran.
 *
 * Aucune bibliothèque Google : tout passe par le `LocationManager` d'Android, avec ses appels les plus anciens
 * et les plus répandus, les mêmes sur toutes les versions et toutes les marques.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SuppressLint("MissingPermission")
class GpsSource(private val context: Context, scope: CoroutineScope) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("velogo-gps").apply { start() }
    private val handler = Handler(thread.looper)

    /** Pour les tests : le fil du GPS, à faire avancer à la main, et les ordres envoyés au GPS du téléphone. */
    @get:VisibleForTesting
    internal val looper: Looper get() = thread.looper
    @VisibleForTesting
    internal var onCommand: ((String) -> Unit)? = null

    /** Sens de la marche, suivi d'une position à l'autre (toujours depuis le fil du GPS). */
    private val course = CourseEstimator()

    /** Échecs de suite du modèle de géoïde d'Android (toujours depuis le fil du GPS). */
    private var geoidFailures = 0

    /** Quand montrer la position approchée, quand aider le GPS (toujours depuis le fil du GPS). */
    private val arbiter = PositionArbiter()

    /** Change quand l'autorisation de localisation vient d'être accordée, pour (re)lancer l'écoute. */
    private val permissionEpoch = MutableStateFlow(0)

    /** La prochaine écoute doit commencer par aider le GPS, et éventuellement par lui faire tout oublier. */
    @Volatile
    private var helpRequested = false
    @Volatile
    private var resetRequested = false

    /** Dernière position reçue, même si plus personne n'écoute (pour la météo, qui n'allume jamais le GPS). */
    @Volatile
    var last: GpsFix? = null
        private set

    private val _status = MutableStateFlow(GpsStatus())
    val status: StateFlow<GpsStatus> = _status.asStateFlow()

    /** Toutes les positions, approchées comprises : pour la carte. */
    val positions: SharedFlow<GpsFix> = permissionEpoch
        .flatMapLatest {
            when (access()) {
                LocationAccess.PRECISE -> updates(precise = true)
                LocationAccess.APPROXIMATE -> updates(precise = false)
                LocationAccess.NONE -> emptyFlow()
            }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 3_000, replayExpirationMillis = 0), replay = 1)

    /** Les positions du GPS seulement : pour l'enregistrement, le guidage et tout ce qui se mesure. */
    val fixes: Flow<GpsFix> = positions.filter { !it.approximate }

    /**
     * Dernière position connue du téléphone, même approximative et sans allumer le GPS : celle de l'appli si elle
     * en a reçu une, sinon celle qu'Android garde en mémoire (si elle date de moins de six heures). Bien assez
     * pour la météo, qui s'affiche ainsi avant même que le GPS ait trouvé ses satellites.
     */
    fun lastKnown(): LatLon? {
        last?.let { return LatLon(it.fix.lat, it.fix.lon) }
        val manager = locationManager ?: return null
        if (access() == LocationAccess.NONE) return null
        val best = runCatching {
            manager.getProviders(true).mapNotNull { provider -> manager.getLastKnownLocation(provider) }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        if (abs(System.currentTimeMillis() - best.time) > MAX_LAST_KNOWN_AGE_MS) return null
        return LatLon(best.latitude, best.longitude)
    }

    /** Vrai si l'appli peut lire la position exacte (indispensable pour enregistrer). */
    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun access(): LocationAccess = when {
        hasPermission() -> LocationAccess.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
        else -> LocationAccess.NONE
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Vrai si la localisation est activée dans les réglages du téléphone. */
    fun isLocationEnabled(): Boolean = locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager)

    /** Vrai si le téléphone a un GPS. */
    fun isGpsPresent(): Boolean =
        runCatching { locationManager?.allProviders?.contains(LocationManager.GPS_PROVIDER) }.getOrNull() ?: false

    /**
     * Vrai si le GPS est en service. Sur d'anciens téléphones, la localisation peut être « activée » sans lui
     * (modes « Économie de batterie », « Réseau uniquement ») : rien ne s'enregistre alors, sans raison apparente.
     */
    fun isGpsEnabled(): Boolean =
        runCatching { locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull() ?: false

    /** À appeler quand l'autorisation de localisation vient de changer. */
    fun onPermissionChanged() = restart()

    /** Relance l'écoute (nouvelle autorisation, ou GPS muet depuis trop longtemps). */
    fun restart() {
        permissionEpoch.value++
    }

    /**
     * Relance l'écoute à la demande de l'utilisateur, en redonnant d'abord au GPS l'heure exacte et la position
     * des satellites. Avec [reset], il oublie aussi tout ce qu'il savait : c'est le remède d'un GPS resté bloqué
     * sur des données périmées, au prix d'une recherche qui repart de zéro.
     */
    fun kick(reset: Boolean = false) {
        helpRequested = true
        if (reset) resetRequested = true
        restart()
    }

    private fun updates(precise: Boolean): Flow<GpsFix> = callbackFlow {
        val manager = locationManager
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }
        val session = Session(manager, precise) { trySend(it) }
        // Tout l'état de l'écoute vit sur le fil du GPS : c'est là qu'elle commence et qu'elle finit, dans l'ordre.
        handler.post { session.start() }
        awaitClose { handler.post { session.stop() } }
    }

    /** Une écoute, du moment où quelqu'un demande la position à celui où plus personne ne la demande. */
    private inner class Session(
        private val manager: LocationManager,
        private val precise: Boolean,
        private val emit: (GpsFix) -> Unit,
    ) {
        private var running = false
        /** Le fournisseur d'appoint écouté en ce moment (position approchée), s'il y en a un. */
        private var assist: String? = null
        /** Position approchée arrivée pendant le délai laissé au GPS : elle sera montrée s'il ne répond pas. */
        private var pending: Location? = null
        private var pendingAtMs = 0L
        private var preciseAtMs: Long? = null
        private var preciseAccuracyM: Double? = null
        private var approximateAtMs: Long? = null
        private var approximateAccuracyM: Double? = null
        private var satellitesSeen: Int? = null
        private var satellitesUsed: Int? = null
        private var ticks = 0
        private var locationEnabled = true
        private var gpsPresent = true
        private var gpsEnabled = true
        /** D'où peut venir la position approchée sur ce téléphone (voir [readProviders]). */
        private var assistSource: String? = null
        /** Le téléphone a accepté notre demande de positions GPS. */
        private var gpsRequested = false
        /** Pas de nouvelle demande de position approchée avant cet instant, après un refus du téléphone. */
        private var assistRetryAtMs = 0L

        private val gpsListener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) = onPrecise(location)
            override fun onProviderEnabled(provider: String) = onProvidersChanged()
            override fun onProviderDisabled(provider: String) = onProvidersChanged()
        }
        private val assistListener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) = onApproximate(location)
            override fun onProviderEnabled(provider: String) = onProvidersChanged()
            override fun onProviderDisabled(provider: String) = onProvidersChanged()
        }
        private val satellites = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var seen = 0
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.getCn0DbHz(i) > 0f) seen++
                    if (status.usedInFix(i)) used++
                }
                if (seen != satellitesSeen || used != satellitesUsed) {
                    satellitesSeen = seen
                    satellitesUsed = used
                    publish()
                }
            }
        }
        private val tick = Runnable { onTick() }

        fun start() {
            running = true
            val now = SystemClock.elapsedRealtime()
            arbiter.start(now)
            readProviders()
            if (precise) {
                if (gpsPresent) {
                    if (resetRequested) command(COMMAND_RESET)
                    if (helpRequested) help(now)
                }
                resetRequested = false
                helpRequested = false
                listenToGps()
            }
            updateAssist(now)
            publish()
            handler.postDelayed(tick, TICK_MS)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
            runCatching { manager.removeUpdates(gpsListener) }
            runCatching { manager.removeUpdates(assistListener) }
            runCatching { manager.unregisterGnssStatusCallback(satellites) }
            assist = null
            pending = null
            _status.update { GpsStatus(locationEnabled = locationEnabled, gpsPresent = gpsPresent, gpsEnabled = gpsEnabled) }
        }

        private fun onPrecise(location: Location) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            arbiter.onPrecise(now)
            pending = null
            val gps = convert(location, now)
            preciseAtMs = now
            preciseAccuracyM = gps.fix.accuracyM
            approximateAtMs = null
            approximateAccuracyM = null
            // Le GPS répond : la position approchée ne sert plus, on cesse de la demander.
            updateAssist(now)
            last = gps
            emit(gps)
            publish()
        }

        private fun onApproximate(location: Location) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            // Une vieille position ressortie de la mémoire du téléphone ne dit pas où l'on est maintenant.
            if (now - location.elapsedRealtimeNanos / 1_000_000 > MAX_APPROXIMATE_AGE_MS) return
            if (!precise || arbiter.acceptApproximate(now, location.accuracyOrNull())) {
                show(location, now)
            } else {
                pending = location
                pendingAtMs = now
            }
        }

        private fun show(location: Location, now: Long) {
            pending = null
            val gps = convertApproximate(location, now)
            approximateAtMs = now
            approximateAccuracyM = gps.fix.accuracyM
            last = gps
            emit(gps)
            publish()
        }

        private fun onTick() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            ticks++
            // Filet de sécurité : les réglages du téléphone ont pu changer sans que l'appli en soit prévenue, et
            // une demande de positions refusée (ou un GPS pas encore prêt) mérite une nouvelle tentative.
            if (ticks % PROVIDER_CHECK_TICKS == 0) {
                readProviders()
                if (precise) listenToGps()
            }
            if (precise) {
                // Le délai laissé au GPS est passé sans réponse : la position approchée reçue entre-temps s'affiche.
                pending?.let { waiting ->
                    if (now - pendingAtMs > PENDING_MAX_AGE_MS) pending = null
                    else if (arbiter.acceptApproximate(now, waiting.accuracyOrNull())) show(waiting, now)
                }
                if (gpsPresent && arbiter.shouldHelp(now)) help(now)
            }
            updateAssist(now)
            publish()
            handler.postDelayed(tick, TICK_MS)
        }

        private fun onProvidersChanged() {
            if (!running) return
            readProviders()
            publish()
        }

        /**
         * Relève ce que le téléphone propose. La position approchée vient du fournisseur « fused » d'Android 12 et
         * suivants (il réunit Wi-Fi, réseau mobile et capteurs), sinon du fournisseur « network ».
         */
        private fun readProviders() {
            locationEnabled = runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(true)
            val all = runCatching { manager.allProviders }.getOrNull().orEmpty()
            gpsPresent = LocationManager.GPS_PROVIDER in all
            gpsEnabled = gpsPresent && isGpsEnabled()
            assistSource = when {
                Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in all -> LocationManager.FUSED_PROVIDER
                LocationManager.NETWORK_PROVIDER in all -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
        }

        /** Demande les positions du GPS, et de quoi dire combien de satellites il capte. */
        private fun listenToGps() {
            if (gpsRequested || !gpsPresent) return
            gpsRequested = request(LocationManager.GPS_PROVIDER, INTERVAL_MS, gpsListener)
            if (gpsRequested) watchSatellites()
        }

        /** Écoute la position approchée du téléphone quand le GPS ne suffit pas, cesse dès qu'il répond. */
        private fun updateAssist(now: Long) {
            val wanted = if (!precise || arbiter.needsAssist(now)) assistSource else null
            if (wanted == assist) return
            if (assist != null) runCatching { manager.removeUpdates(assistListener) }
            assist = null
            if (wanted == null || now < assistRetryAtMs) return
            if (request(wanted, ASSIST_INTERVAL_MS, assistListener)) assist = wanted else assistRetryAtMs = now + RETRY_MS
        }

        /**
         * L'appel le plus ancien d'Android pour demander des positions, identique sur toutes les versions : pour le
         * GPS il vaut « précision maximale », pour les autres fournisseurs « précision moyenne, peu de batterie ».
         */
        private fun request(provider: String, intervalMs: Long, listener: LocationListener): Boolean =
            try {
                manager.requestLocationUpdates(provider, intervalMs, 0f, listener, thread.looper)
                true
            } catch (_: Exception) {
                // Autorisation retirée entre-temps, ou fournisseur disparu : on attend la prochaine écoute.
                false
            }

        private fun watchSatellites() {
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    manager.registerGnssStatusCallback(Executor { handler.post(it) }, satellites)
                } else {
                    @Suppress("DEPRECATION")
                    manager.registerGnssStatusCallback(satellites, handler)
                }
            }
        }

        /**
         * Redonne au GPS l'heure exacte et la position prévue des satellites (téléchargées par le téléphone) : avec
         * elles, il trouve ses satellites en quelques secondes au lieu de plusieurs minutes. Sans effet sans réseau.
         */
        private fun help(now: Long) {
            arbiter.onHelped(now)
            for (name in HELP_COMMANDS) command(name)
        }

        private fun command(name: String) {
            onCommand?.invoke(name)
            runCatching { manager.sendExtraCommand(LocationManager.GPS_PROVIDER, name, null) }
        }

        private fun publish() {
            val now = SystemClock.elapsedRealtime()
            val fresh = preciseAtMs?.let { now - it <= FRESH_MS } == true
            val approximateShown = !fresh && approximateAtMs?.let { now - it <= APPROXIMATE_FRESH_MS } == true
            _status.value = GpsStatus(
                listening = true,
                locationEnabled = locationEnabled,
                gpsPresent = gpsPresent,
                gpsEnabled = gpsEnabled,
                hasFix = fresh,
                accuracyM = if (fresh) preciseAccuracyM else null,
                hasApproximate = approximateShown,
                approximateAccuracyM = if (approximateShown) approximateAccuracyM else null,
                satellitesSeen = satellitesSeen,
                satellitesUsed = satellitesUsed,
                searchingS = if (fresh || !precise) null else arbiter.searchingSinceMs()?.let { ((now - it) / 1000).toInt().coerceAtLeast(0) },
            )
        }
    }

    private fun Location.accuracyOrNull(): Double? = if (hasAccuracy()) accuracy.toDouble() else null

    /** Une position du GPS, prête pour les calculs et pour la carte. */
    private fun convert(location: Location, nowMonoMs: Long): GpsFix {
        val now = System.currentTimeMillis()
        // Quelques puces GPS renvoient une date fantaisiste : dans ce cas on prend l'horloge du téléphone.
        val time = if (abs(location.time - now) > MAX_CLOCK_SKEW_MS) now else location.time
        val fix = Fix(
            timeMs = time,
            monoMs = monotonic(location, nowMonoMs),
            lat = location.latitude,
            lon = location.longitude,
            altitudeM = seaLevelAltitude(location),
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            accuracyM = location.accuracyOrNull(),
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

    /** Une position approchée : un simple point sur la carte, sans vitesse, sans cap ni altitude. */
    private fun convertApproximate(location: Location, nowMonoMs: Long): GpsFix {
        val fix = Fix(
            timeMs = System.currentTimeMillis(),
            monoMs = nowMonoMs,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = location.accuracyOrNull(),
        )
        val shown = Location(location).apply {
            removeBearing()
            removeSpeed()
        }
        return GpsFix(fix, shown, courseDeg = null, speedMps = 0.0, approximate = true)
    }

    /**
     * L'instant de la position sur l'horloge du téléphone qui ne recule jamais. Android l'annonce avec chaque
     * position, mais certaines puces GPS le calculent mal (décalage de plusieurs heures après une veille) : une
     * position toute fraîche paraîtrait alors périmée et serait écartée de l'enregistrement. Quand l'instant
     * annoncé n'est pas plausible, on prend celui de la réception.
     */
    private fun monotonic(location: Location, nowMonoMs: Long): Long {
        val reported = location.elapsedRealtimeNanos / 1_000_000
        return if (reported in (nowMonoMs - MAX_DELIVERY_DELAY_MS)..(nowMonoMs + MAX_DELIVERY_ADVANCE_MS)) reported else nowMonoMs
    }

    /**
     * Le GPS donne une hauteur au-dessus d'un ellipsoïde mathématique, pas au-dessus de la mer :
     * en France l'écart est d'environ 45 à 55 m. On convertit avec le modèle de géoïde d'Android.
     */
    private fun seaLevelAltitude(location: Location): Double? {
        if (!location.hasAltitude()) return null
        if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) return location.mslAltitudeMeters
        if (geoidFailures < MAX_GEOID_FAILURES) {
            try {
                AltitudeConverterCompat.addMslAltitudeToLocation(context, location)
                if (LocationCompat.hasMslAltitude(location)) {
                    geoidFailures = 0
                    return LocationCompat.getMslAltitudeMeters(location)
                }
            } catch (_: Throwable) {
                // Modèle de géoïde indisponible : on retombe sur la correction moyenne ci-dessous. On attrape tout,
                // erreurs de chargement comprises : sur le fil du GPS, la moindre exception arrêterait l'appli.
            }
            // Après quelques échecs de suite, inutile d'insister à chaque seconde : le repli suffit.
            geoidFailures++
        }
        return location.altitude - FRANCE_MEAN_GEOID_M
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
        /** La position approchée change peu : la demander toutes les quatre secondes suffit largement. */
        const val ASSIST_INTERVAL_MS = 4_000L
        const val TICK_MS = 1_000L
        const val PROVIDER_CHECK_TICKS = 5
        const val RETRY_MS = 30_000L
        /** Sans position du GPS depuis ce délai, on ne dit plus qu'il fonctionne (comme le moteur de sortie). */
        const val FRESH_MS = 6_000L
        const val APPROXIMATE_FRESH_MS = 3 * 60_000L
        const val MAX_APPROXIMATE_AGE_MS = 2 * 60_000L
        const val PENDING_MAX_AGE_MS = 15_000L
        const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L
        /** Une position est remise à l'appli moins d'une seconde après sa mesure ; dix secondes, c'est déjà une panne. */
        const val MAX_DELIVERY_DELAY_MS = 10_000L
        const val MAX_DELIVERY_ADVANCE_MS = 1_000L
        const val MAX_LAST_KNOWN_AGE_MS = 6 * 60 * 60 * 1000L
        /** Hauteur moyenne du géoïde en France métropolitaine : simple repli si la conversion précise échoue. */
        const val FRANCE_MEAN_GEOID_M = 48.0
        const val MAX_GEOID_FAILURES = 3
        /**
         * Ordres compris par le GPS d'Android : « donne-moi l'heure », « télécharge la position des satellites »
         * (deux noms selon la version d'Android ; celui qui n'est pas connu est ignoré).
         */
        val HELP_COMMANDS = listOf("force_time_injection", "force_psds_injection", "force_xtra_injection")
        const val COMMAND_RESET = "delete_aiding_data"
    }
}
