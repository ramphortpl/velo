package fr.velogo.core.ride

import fr.velogo.core.geo.Geo
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Moteur d'une sortie : transforme le flux de positions GPS en données affichables
 * (distance, vitesses, temps, altitude, pente, dénivelé) et décide quels points enregistrer.
 *
 * Il ne dépend pas d'Android : on lui passe les positions ([onFix]) et l'heure ([onTick]),
 * ce qui permet de le tester avec des sorties simulées.
 */
class RideEngine(config: RideConfig = RideConfig()) {

    var config: RideConfig = config
        private set

    var state: RideState = RideState.IDLE
        private set

    private var startTimeMs: Long? = null
    private var startMonoMs = 0L
    private var elapsedBaseMs = 0L
    private var nowMonoMs = 0L
    private var finalElapsedMs = 0L

    private var distanceM = 0.0
    private var movingMs = 0L
    private var maxSpeedMps = 0.0
    private var minAltitudeM: Double? = null
    private var maxAltitudeM: Double? = null

    private var smoothedSpeed: Double? = null
    private var lastFix: Fix? = null
    /** Dernier point compté dans la distance. */
    private var anchor: Fix? = null
    /** Instant jusqu'auquel le temps en mouvement a été compté. */
    private var countedUntilMonoMs: Long? = null
    /** Vrai quand le vélo est à l'arrêt (détecté même si la pause automatique est désactivée). */
    private var stopped = false
    private var slowSinceMonoMs: Long? = null
    private var fastCount = 0
    private var jumpCount = 0
    private var segment = 0
    private var slopePct: Double? = null
    private var lastAccuracyM: Double? = null

    /** Altitude mesurée (GPS) lissée : c'est elle qu'on enregistre, elle sert ensuite à repérer viaducs et tunnels. */
    private var altitudeFilter = AltitudeFilter(config.altitudeSource.accelNoise)
    /** Altitude du relief hors ligne, quand il couvre l'endroit où l'on roule. */
    private var terrainFilter = AltitudeFilter(AltitudeSource.TERRAIN.accelNoise)
    /** Vrai tant que l'affichage (altitude, pente, dénivelé) s'appuie sur le relief plutôt que sur le GPS. */
    private var onTerrain = false
    private var liveAltitudeM: Double? = null
    private var ascent = AscentAccumulator(config.altitudeSource.ascentThresholdM)
    private var slope = SlopeEstimator(config.altitudeSource.slopeWindowM)

    /** Démarre une nouvelle sortie. */
    fun start(nowMonoMs: Long, nowTimeMs: Long) {
        resetAll()
        state = RideState.RECORDING
        startTimeMs = nowTimeMs
        startMonoMs = nowMonoMs
        this.nowMonoMs = nowMonoMs
    }

    /** Reprend une sortie interrompue (appli tuée par le système) à partir de ses totaux sauvegardés. */
    fun restore(startTimeMs: Long, totals: RideTotals, nextSegment: Int, nowMonoMs: Long) {
        resetAll()
        state = RideState.RECORDING
        this.startTimeMs = startTimeMs
        startMonoMs = nowMonoMs
        this.nowMonoMs = nowMonoMs
        elapsedBaseMs = totals.elapsedMs
        distanceM = totals.distanceM
        movingMs = totals.movingMs
        maxSpeedMps = totals.maxSpeedMps
        minAltitudeM = totals.minAltitudeM
        maxAltitudeM = totals.maxAltitudeM
        ascent.restore(totals.ascentM, totals.descentM)
        segment = nextSegment
    }

    fun pause(nowMonoMs: Long) {
        onTick(nowMonoMs)
        if (state == RideState.RECORDING || state == RideState.AUTO_PAUSED) {
            state = RideState.PAUSED
            anchor = null
            countedUntilMonoMs = null
            stopped = false
            slowSinceMonoMs = null
            fastCount = 0
        }
    }

    fun resume(nowMonoMs: Long) {
        onTick(nowMonoMs)
        if (state == RideState.PAUSED) {
            state = RideState.RECORDING
            segment++
            anchor = null
            countedUntilMonoMs = null
            slope.reset()
            slopePct = null
        }
    }

    /** Termine la sortie et renvoie ses totaux. */
    fun stop(nowMonoMs: Long): RideTotals {
        onTick(nowMonoMs)
        val totals = totals()
        finalElapsedMs = totals.elapsedMs
        state = RideState.IDLE
        return totals
    }

    fun setAutoPause(enabled: Boolean) {
        config = config.copy(autoPause = enabled)
        if (state == RideState.RECORDING || state == RideState.AUTO_PAUSED) {
            state = if (stopped && enabled) RideState.AUTO_PAUSED else RideState.RECORDING
            if (!enabled) countedUntilMonoMs = lastFix?.monoMs
        }
    }

    /** Change la nature de l'altitude mesurée (GPS seul, ou capteur précis comme un baromètre). */
    fun setAltitudeSource(source: AltitudeSource) {
        if (source == config.altitudeSource) return
        config = config.copy(altitudeSource = source)
        altitudeFilter = AltitudeFilter(source.accelNoise)
        if (!onTerrain) rebaseLiveAltitude(source)
    }

    /**
     * Bascule l'affichage entre l'altitude mesurée et celle du relief (entrée ou sortie d'une zone couverte par
     * un pack hors ligne). Les deux altitudes diffèrent de quelques mètres : on repart donc d'une nouvelle
     * référence, sans compter cet écart comme du dénivelé.
     */
    private fun useTerrain(enabled: Boolean) {
        if (enabled == onTerrain) return
        onTerrain = enabled
        if (enabled) terrainFilter = AltitudeFilter(AltitudeSource.TERRAIN.accelNoise)
        rebaseLiveAltitude(if (enabled) AltitudeSource.TERRAIN else config.altitudeSource)
    }

    private fun rebaseLiveAltitude(source: AltitudeSource) {
        val a = ascent.ascentM
        val d = ascent.descentM
        ascent = AscentAccumulator(source.ascentThresholdM).also { it.restore(a, d) }
        slope = SlopeEstimator(source.slopeWindowM)
        slopePct = null
    }

    /** À appeler régulièrement (chaque seconde) pour faire avancer le temps écoulé et détecter la perte de signal. */
    fun onTick(monoMs: Long) {
        nowMonoMs = max(nowMonoMs, monoMs)
    }

    /**
     * Traite une position. Renvoie le point à enregistrer dans la trace, ou null si la position est
     * rejetée (trop imprécise, parasite) ou si l'on est en pause.
     */
    fun onFix(fix: Fix): TrackPoint? {
        if (state == RideState.IDLE) return null
        nowMonoMs = max(nowMonoMs, fix.monoMs)
        lastAccuracyM = fix.accuracyM
        val previous = lastFix
        if (previous != null && fix.monoMs <= previous.monoMs) return null
        if (fix.accuracyM != null && fix.accuracyM > config.maxAccuracyM) return null

        val dtS = if (previous != null) (fix.monoMs - previous.monoMs) / 1000.0 else 0.0
        val stepM = if (previous != null) Geo.distanceM(previous.lat, previous.lon, fix.lat, fix.lon) else 0.0
        val derivedSpeed = if (previous != null && dtS > 0.0) stepM / dtS else null

        // Saut de position invraisemblable (rebond du signal) : on l'ignore, sauf s'il se confirme.
        if (derivedSpeed != null && dtS <= JUMP_CHECK_MAX_DT_S) {
            val reference = fix.speedMps ?: smoothedSpeed ?: 0.0
            val limit = max(2.5 * reference + 8.0, 12.0)
            if ((derivedSpeed > limit || derivedSpeed > config.maxPlausibleSpeedMps) && jumpCount < MAX_JUMPS) {
                jumpCount++
                return null
            }
        }
        jumpCount = 0

        var rawSpeed = fix.speedMps ?: derivedSpeed ?: 0.0
        if (rawSpeed > config.maxPlausibleSpeedMps) rawSpeed = min(derivedSpeed ?: 0.0, config.maxPlausibleSpeedMps)

        val before = smoothedSpeed
        val speed = if (before == null || dtS <= 0.0 || dtS > JUMP_CHECK_MAX_DT_S) rawSpeed
        else before + (1 - exp(-dtS / SPEED_TAU_S)) * (rawSpeed - before)
        smoothedSpeed = speed

        val source = config.altitudeSource
        val measuredAltitude = if (fix.altitudeM != null) {
            val sigma = max(fix.verticalAccuracyM ?: source.defaultSigmaM, source.minSigmaM)
            altitudeFilter.update(fix.monoMs, fix.altitudeM, sigma)
        } else altitudeFilter.altitudeM
        // Avec un pack de relief, l'affichage suit le terrain (précis au mètre) ; sinon, l'altitude mesurée.
        useTerrain(fix.terrainAltitudeM != null)
        val altitude = if (fix.terrainAltitudeM != null) {
            terrainFilter.update(fix.monoMs, fix.terrainAltitudeM, AltitudeSource.TERRAIN.defaultSigmaM)
        } else measuredAltitude
        liveAltitudeM = altitude

        var recorded: TrackPoint? = null
        if (state == RideState.RECORDING || state == RideState.AUTO_PAUSED) {
            updateStopDetection(fix, rawSpeed)
            state = if (stopped && config.autoPause) RideState.AUTO_PAUSED else RideState.RECORDING
            if (!stopped) {
                val a = anchor
                val counted = countedUntilMonoMs
                if (a != null) distanceM += Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon)
                if (counted != null) {
                    val gapMs = fix.monoMs - counted
                    val gapSpeed = if (a != null && gapMs > 0) Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon) / (gapMs / 1000.0) else 0.0
                    // Trou de signal (tunnel) : le temps ne compte comme déplacement que si l'on a vraiment avancé.
                    if (gapMs <= MAX_CONTINUOUS_GAP_MS || gapSpeed >= config.autoPauseSpeedMps) movingMs += gapMs
                }
                anchor = fix
                countedUntilMonoMs = fix.monoMs
                if (altitude != null) {
                    ascent.add(altitude)
                    slopePct = slope.add(distanceM, altitude)
                    minAltitudeM = min(minAltitudeM ?: altitude, altitude)
                    maxAltitudeM = max(maxAltitudeM ?: altitude, altitude)
                }
                val accurate = fix.accuracyM == null || fix.accuracyM <= MAX_SPEED_ACCURACY_M
                if (accurate && speed > maxSpeedMps && speed <= config.maxPlausibleSpeedMps) maxSpeedMps = speed
                // La trace garde l'altitude mesurée quand il y en a une : le recalage de fin de sortie en a besoin.
                recorded = TrackPoint(fix.timeMs, fix.lat, fix.lon, measuredAltitude ?: altitude, speed, fix.accuracyM, segment)
            } else if (!config.autoPause) {
                // Pause automatique désactivée : le chrono continue à l'arrêt, mais la distance ne bouge pas.
                val counted = countedUntilMonoMs
                if (counted != null && fix.monoMs - counted <= MAX_CONTINUOUS_GAP_MS) movingMs += fix.monoMs - counted
                countedUntilMonoMs = fix.monoMs
            }
        }
        lastFix = fix
        return recorded
    }

    /** Détecte l'arrêt et le redémarrage du vélo, avec hystérésis pour ne pas osciller. */
    private fun updateStopDetection(fix: Fix, rawSpeed: Double) {
        if (!stopped) {
            if (rawSpeed < config.autoPauseSpeedMps) {
                val since = slowSinceMonoMs ?: fix.monoMs.also { slowSinceMonoMs = it }
                if (fix.monoMs - since >= config.autoPauseDelayMs) {
                    stopped = true
                    fastCount = 0
                    if (config.autoPause) {
                        // Les secondes passées à attendre la confirmation de l'arrêt n'étaient pas du déplacement.
                        val counted = (countedUntilMonoMs ?: since) - since
                        if (counted > 0) movingMs = max(0L, movingMs - counted)
                        countedUntilMonoMs = null
                    }
                }
            } else {
                slowSinceMonoMs = null
            }
        } else {
            val a = anchor
            val drift = if (a != null) Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon) else 0.0
            val moved = drift > RESUME_DISTANCE_M + (fix.accuracyM ?: 0.0)
            if (rawSpeed >= config.autoResumeSpeedMps) fastCount++ else fastCount = 0
            if (fastCount >= RESUME_FIXES || moved) {
                stopped = false
                slowSinceMonoMs = null
                fastCount = 0
                // Vélo déplacé pendant l'arrêt (poussé à pied) : la distance compte, pas le temps.
                if (drift > RESUME_DISTANCE_M) distanceM += drift
                anchor = null
                if (config.autoPause) countedUntilMonoMs = null
            }
        }
    }

    private fun hasSignal(): Boolean {
        val last = lastFix ?: return false
        return nowMonoMs - last.monoMs <= SIGNAL_TIMEOUT_MS
    }

    private fun elapsedMs(): Long =
        if (state == RideState.IDLE) finalElapsedMs else elapsedBaseMs + (nowMonoMs - startMonoMs)

    fun totals(): RideTotals = RideTotals(
        distanceM = distanceM,
        movingMs = movingMs,
        elapsedMs = elapsedMs(),
        // La vitesse max est lissée, la moyenne non : sur quelques dizaines de secondes à allure constante, la
        // moyenne peut la dépasser d'un cheveu. On n'affiche jamais une max inférieure à la moyenne.
        maxSpeedMps = max(maxSpeedMps, if (movingMs >= MIN_AVERAGE_MS) (distanceM / (movingMs / 1000.0)).coerceAtMost(config.maxPlausibleSpeedMps) else 0.0),
        ascentM = ascent.ascentM,
        descentM = ascent.descentM,
        minAltitudeM = minAltitudeM,
        maxAltitudeM = maxAltitudeM,
    )

    /** Numéro du tronçon en cours (utile pour reprendre une sortie interrompue). */
    val currentSegment: Int get() = segment

    fun snapshot(): RideSnapshot {
        val signal = hasSignal()
        val last = lastFix
        val displayedSpeed = when {
            !signal -> null
            state == RideState.AUTO_PAUSED -> 0.0
            else -> smoothedSpeed?.let { if (it < MIN_DISPLAY_SPEED_MPS) 0.0 else it }
        }
        return RideSnapshot(
            state = state,
            startTimeMs = startTimeMs,
            totals = totals(),
            speedMps = displayedSpeed,
            altitudeM = liveAltitudeM,
            slopePct = if (state == RideState.RECORDING && signal) slopePct else null,
            accuracyM = if (signal) lastAccuracyM else null,
            hasSignal = signal,
            lat = last?.lat,
            lon = last?.lon,
            bearingDeg = last?.bearingDeg,
        )
    }

    private fun resetAll() {
        startTimeMs = null; startMonoMs = 0; elapsedBaseMs = 0; nowMonoMs = 0; finalElapsedMs = 0
        distanceM = 0.0; movingMs = 0; maxSpeedMps = 0.0; minAltitudeM = null; maxAltitudeM = null
        smoothedSpeed = null; lastFix = null; anchor = null; countedUntilMonoMs = null; stopped = false; slowSinceMonoMs = null
        fastCount = 0; jumpCount = 0; segment = 0; slopePct = null; lastAccuracyM = null
        altitudeFilter = AltitudeFilter(config.altitudeSource.accelNoise)
        terrainFilter = AltitudeFilter(AltitudeSource.TERRAIN.accelNoise)
        onTerrain = false; liveAltitudeM = null
        ascent = AscentAccumulator(config.altitudeSource.ascentThresholdM)
        slope = SlopeEstimator(config.altitudeSource.slopeWindowM)
    }

    /** Vrai si l'altitude affichée vient du relief hors ligne (et non du GPS). */
    val usesTerrain: Boolean get() = onTerrain

    private companion object {
        /** Constante de temps du lissage de la vitesse affichée. */
        const val SPEED_TAU_S = 1.2
        const val JUMP_CHECK_MAX_DT_S = 5.0
        const val MAX_JUMPS = 3
        const val MAX_CONTINUOUS_GAP_MS = 10_000L
        const val MAX_SPEED_ACCURACY_M = 20.0
        const val RESUME_DISTANCE_M = 15.0
        const val RESUME_FIXES = 2
        const val SIGNAL_TIMEOUT_MS = 6_000L
        const val MIN_DISPLAY_SPEED_MPS = 0.3
        const val MIN_AVERAGE_MS = 10_000L
    }
}
