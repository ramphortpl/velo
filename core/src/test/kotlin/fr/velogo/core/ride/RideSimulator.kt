package fr.velogo.core.ride

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Fabrique des sorties simulées (un point GPS par seconde) pour tester le moteur :
 * on connaît exactement la distance, le dénivelé et les temps attendus.
 */
class RideSimulator(
    var lat: Double = 45.0,
    var lon: Double = 5.0,
    var altitudeM: Double = 300.0,
    startTimeMs: Long = 1_800_000_000_000L,
    seed: Int = 42,
) {
    var timeMs: Long = startTimeMs
        private set
    var monoMs: Long = 10_000L
        private set
    /** Distance réellement parcourue (m). */
    var trueDistanceM = 0.0
        private set
    /** Dénivelé positif réel (m). */
    var trueAscentM = 0.0
        private set

    private val random = Random(seed)
    private var drift = 0.0

    val fixes = ArrayList<Fix>()

    /**
     * Roule [seconds] secondes à [speedMps] m/s dans la direction [bearingDeg].
     * [slopePct] peut dépendre de la distance parcourue depuis le début du tronçon.
     */
    fun ride(
        seconds: Int,
        speedMps: Double,
        bearingDeg: Double = 60.0,
        slopePct: (Double) -> Double = { 0.0 },
        accuracyM: Double = 4.0,
        positionNoiseM: Double = 0.0,
        altitudeNoiseM: Double = 0.0,
        altitudeDriftM: Double = 0.0,
        withSpeed: Boolean = true,
        emit: Boolean = true,
    ): RideSimulator {
        var done = 0.0
        repeat(seconds) {
            step(speedMps, bearingDeg)
            val dz = speedMps * slopePct(done) / 100.0
            altitudeM += dz
            if (dz > 0) trueAscentM += dz
            done += speedMps
            trueDistanceM += speedMps
            timeMs += 1000
            monoMs += 1000
            if (emit) fixes.add(fix(speedMps, bearingDeg, accuracyM, positionNoiseM, altitudeNoiseM, altitudeDriftM, withSpeed))
        }
        return this
    }

    /** Reste immobile [seconds] secondes, avec le léger flottement habituel du GPS. */
    fun stand(seconds: Int, jitterM: Double = 1.5, accuracyM: Double = 5.0, emit: Boolean = true): RideSimulator {
        repeat(seconds) {
            timeMs += 1000
            monoMs += 1000
            if (emit) fixes.add(fix(random.nextDouble() * 0.3, null, accuracyM, jitterM, 0.0, 0.0, true))
        }
        return this
    }

    private fun fix(
        speedMps: Double, bearingDeg: Double?, accuracyM: Double,
        positionNoiseM: Double, altitudeNoiseM: Double, altitudeDriftM: Double, withSpeed: Boolean,
    ): Fix {
        var fLat = lat
        var fLon = lon
        if (positionNoiseM > 0) {
            val (dLat, dLon) = metersToDegrees(gaussian() * positionNoiseM, gaussian() * positionNoiseM)
            fLat += dLat; fLon += dLon
        }
        // Bruit d'altitude réaliste : un bruit blanc plus une dérive lente (processus autorégressif, ~60 s).
        if (altitudeDriftM > 0) drift = 0.983 * drift + sqrt(1 - 0.983 * 0.983) * altitudeDriftM * gaussian()
        val alt = altitudeM + drift + if (altitudeNoiseM > 0) gaussian() * altitudeNoiseM else 0.0
        return Fix(
            timeMs = timeMs, monoMs = monoMs, lat = fLat, lon = fLon, altitudeM = alt,
            speedMps = if (withSpeed) speedMps else null, accuracyM = accuracyM,
            verticalAccuracyM = null, bearingDeg = bearingDeg,
        )
    }

    private fun step(distanceM: Double, bearingDeg: Double) {
        val b = Math.toRadians(bearingDeg)
        val (dLat, dLon) = metersToDegrees(distanceM * cos(b), distanceM * sin(b))
        lat += dLat; lon += dLon
    }

    /** Déplace la position sans émettre de point (téléportation : sert à simuler une pause manuelle ou un tunnel). */
    fun teleport(distanceM: Double, bearingDeg: Double, seconds: Int) {
        step(distanceM, bearingDeg)
        timeMs += seconds * 1000L
        monoMs += seconds * 1000L
    }

    /** Conversion mètres -> degrés avec les rayons de courbure de l'ellipsoïde WGS84 au point courant. */
    private fun metersToDegrees(northM: Double, eastM: Double): Pair<Double, Double> {
        val a = 6_378_137.0
        val f = 1.0 / 298.257223563
        val e2 = f * (2 - f)
        val phi = Math.toRadians(lat)
        val w2 = 1 - e2 * sin(phi) * sin(phi)
        val m = a * (1 - e2) / (w2 * sqrt(w2))
        val n = a / sqrt(w2)
        return Math.toDegrees(northM / m) to Math.toDegrees(eastM / (n * cos(phi)))
    }

    private fun gaussian(): Double {
        // Box-Muller.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }
}

/** Fait tourner le moteur sur une liste de points et renvoie les points enregistrés. */
fun RideEngine.feed(fixes: List<Fix>): List<TrackPoint> {
    val recorded = ArrayList<TrackPoint>()
    for (f in fixes) {
        onFix(f)?.let { recorded.add(it) }
        onTick(f.monoMs)
    }
    return recorded
}
