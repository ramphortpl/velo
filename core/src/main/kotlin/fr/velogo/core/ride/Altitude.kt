package fr.velogo.core.ride

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lissage de l'altitude par filtre de Kalman (modèle « altitude + vitesse verticale »).
 *
 * L'altitude GPS brute saute de plusieurs mètres d'une seconde à l'autre ; ce filtre la rend exploitable
 * pour la pente et le dénivelé. [accelNoise] règle la nervosité : petit = très lisse (GPS seul),
 * plus grand = suit mieux le terrain (altitude issue d'un modèle de relief ou d'un baromètre).
 */
class AltitudeFilter(private val accelNoise: Double = 0.05) {
    private var h = Double.NaN
    private var v = 0.0
    private var p00 = 0.0
    private var p01 = 0.0
    private var p11 = 0.0
    private var lastMonoMs = 0L
    private var outliers = 0

    val altitudeM: Double? get() = if (h.isNaN()) null else h
    val verticalSpeedMps: Double get() = v

    fun reset() {
        h = Double.NaN; v = 0.0; p00 = 0.0; p01 = 0.0; p11 = 0.0; outliers = 0
    }

    /** Intègre une mesure d'altitude d'écart-type [sigmaM] et renvoie l'altitude lissée. */
    fun update(monoMs: Long, measuredM: Double, sigmaM: Double): Double {
        val r = sigmaM * sigmaM
        if (h.isNaN()) {
            h = measuredM; v = 0.0
            p00 = r; p01 = 0.0; p11 = 0.25
            lastMonoMs = monoMs
            return h
        }
        val dt = ((monoMs - lastMonoMs) / 1000.0).coerceAtLeast(0.0)
        lastMonoMs = monoMs
        if (dt > MAX_GAP_S) {
            // Trop longtemps sans mesure (tunnel, perte de signal) : on repart de la mesure.
            h = measuredM; v = 0.0
            p00 = r; p01 = 0.0; p11 = 0.25
            outliers = 0
            return h
        }
        // Prédiction.
        val q = accelNoise * accelNoise
        val hPred = h + v * dt
        val n00 = p00 + dt * (2 * p01 + dt * p11) + q * dt * dt * dt * dt / 4
        val n01 = p01 + dt * p11 + q * dt * dt * dt / 2
        val n11 = p11 + q * dt * dt
        val s = n00 + r
        val innovation = measuredM - hPred
        // Une mesure aberrante isolée est ignorée ; si elle persiste, c'est le filtre qui avait tort.
        if (abs(innovation) > OUTLIER_SIGMAS * sqrt(s) && outliers < MAX_OUTLIERS) {
            outliers++
            h = hPred; p00 = n00; p01 = n01; p11 = n11
            return h
        }
        outliers = 0
        val k0 = n00 / s
        val k1 = n01 / s
        h = hPred + k0 * innovation
        v += k1 * innovation
        p00 = (1 - k0) * n00
        p01 = (1 - k0) * n01
        p11 = n11 - k1 * n01
        return h
    }

    private companion object {
        const val MAX_GAP_S = 30.0
        const val OUTLIER_SIGMAS = 5.0
        const val MAX_OUTLIERS = 4
    }
}

/**
 * Cumul du dénivelé positif et négatif avec hystérésis.
 *
 * Tant qu'on monte, chaque nouveau point haut est compté ; on ne bascule en « descente » que si l'altitude
 * redescend d'au moins [thresholdM] sous ce point haut (et inversement). Les petites oscillations dues au
 * bruit ne créent donc pas de faux dénivelé.
 */
class AscentAccumulator(private val thresholdM: Double) {
    var ascentM: Double = 0.0
        private set
    var descentM: Double = 0.0
        private set
    private var direction = 0
    private var extreme = Double.NaN

    fun restore(ascentM: Double, descentM: Double) {
        this.ascentM = ascentM; this.descentM = descentM
        direction = 0; extreme = Double.NaN
    }

    fun add(altitudeM: Double) {
        if (extreme.isNaN()) {
            extreme = altitudeM
            return
        }
        when (direction) {
            0 -> {
                val d = altitudeM - extreme
                if (d >= thresholdM) { ascentM += d; direction = 1; extreme = altitudeM }
                else if (d <= -thresholdM) { descentM -= d; direction = -1; extreme = altitudeM }
            }
            1 -> {
                if (altitudeM > extreme) { ascentM += altitudeM - extreme; extreme = altitudeM }
                else if (extreme - altitudeM >= thresholdM) { descentM += extreme - altitudeM; direction = -1; extreme = altitudeM }
            }
            else -> {
                if (altitudeM < extreme) { descentM += extreme - altitudeM; extreme = altitudeM }
                else if (altitudeM - extreme >= thresholdM) { ascentM += altitudeM - extreme; direction = 1; extreme = altitudeM }
            }
        }
    }
}

/**
 * Pente en % par régression linéaire de l'altitude sur les [windowM] derniers mètres parcourus,
 * puis lissage. Renvoie null tant qu'on n'a pas parcouru assez de distance pour une valeur fiable.
 */
class SlopeEstimator(private val windowM: Double, private val smoothingM: Double = windowM / 3) {
    private val distances = ArrayDeque<Double>()
    private val altitudes = ArrayDeque<Double>()
    private var smoothed = Double.NaN
    private var lastDistance = Double.NaN

    fun reset() {
        distances.clear(); altitudes.clear(); smoothed = Double.NaN; lastDistance = Double.NaN
    }

    fun add(cumulativeDistanceM: Double, altitudeM: Double): Double? {
        if (distances.isNotEmpty() && cumulativeDistanceM < distances.last()) reset()
        distances.addLast(cumulativeDistanceM)
        altitudes.addLast(altitudeM)
        while (distances.size > 2 && cumulativeDistanceM - distances[1] >= windowM) {
            distances.removeFirst(); altitudes.removeFirst()
        }
        val n = distances.size
        val span = cumulativeDistanceM - distances.first()
        if (n < 3 || span < windowM * MIN_SPAN_RATIO) return if (smoothed.isNaN()) null else smoothed

        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sx += distances[i]; sy += altitudes[i] }
        val mx = sx / n; val my = sy / n
        var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val dx = distances[i] - mx
            sxx += dx * dx
            sxy += dx * (altitudes[i] - my)
        }
        if (sxx <= 0.0) return if (smoothed.isNaN()) null else smoothed
        val raw = (sxy / sxx * 100.0).coerceIn(-MAX_SLOPE_PCT, MAX_SLOPE_PCT)

        if (smoothed.isNaN()) {
            smoothed = raw
        } else {
            val step = (cumulativeDistanceM - lastDistance).coerceAtLeast(0.0)
            val alpha = (step / smoothingM).coerceIn(0.0, 1.0)
            smoothed += alpha * (raw - smoothed)
        }
        lastDistance = cumulativeDistanceM
        return smoothed
    }

    private companion object {
        const val MIN_SPAN_RATIO = 0.5
        const val MAX_SLOPE_PCT = 40.0
    }
}
