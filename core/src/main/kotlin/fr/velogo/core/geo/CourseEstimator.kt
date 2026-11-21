package fr.velogo.core.geo

/** Le sens de la marche et la vitesse à un instant donné. */
class Course(
    /** Cap en degrés (0 = nord, 90 = est), ou null tant qu'on n'a pas assez roulé pour le connaître. */
    val bearingDeg: Double?,
    val speedMps: Double,
)

/**
 * Suit le sens de la marche d'une position à l'autre.
 *
 * Le téléphone n'a pas de boussole : le sens de la marche ne peut venir que du déplacement. Quand le GPS donne
 * lui-même un cap et une vitesse (mesurés par effet Doppler, très fiables dès qu'on roule), on les prend ; sinon
 * on les déduit des positions successives. À l'arrêt, le dernier cap connu est conservé : la flèche de la carte
 * continue de montrer vers où l'on allait au lieu de tourner sur elle-même au gré du bruit du GPS.
 */
class CourseEstimator(
    /** Recul nécessaire pour déduire un cap des positions : en dessous, le bruit du GPS l'emporte. */
    private val baselineM: Double = 8.0,
    /** Ce recul doit avoir été parcouru en moins de temps que cela, sinon ce n'est qu'une dérive à l'arrêt. */
    private val baselineMaxMs: Long = 8_000,
    /** En dessous de cette vitesse, le cap donné par le GPS ne veut rien dire. */
    private val movingMps: Double = 1.0,
) {
    private class Sample(val lat: Double, val lon: Double, val monoMs: Long)

    private val history = ArrayDeque<Sample>()
    private var held: Double? = null

    /**
     * Nouvelle position, à l'instant [monoMs] d'une horloge qui ne recule jamais. [gpsBearingDeg] et [gpsSpeedMps]
     * sont le cap et la vitesse annoncés par le GPS, quand il les donne.
     */
    fun update(lat: Double, lon: Double, monoMs: Long, gpsBearingDeg: Double? = null, gpsSpeedMps: Double? = null): Course {
        val last = history.lastOrNull()
        // Horloge qui recule ou saut de position invraisemblable : on repart de zéro.
        if (last != null && (monoMs < last.monoMs || Geo.distanceM(last.lat, last.lon, lat, lon) > JUMP_M)) reset()
        history.addLast(Sample(lat, lon, monoMs))
        while (history.size > MAX_SAMPLES || monoMs - history.first().monoMs > HISTORY_MS) history.removeFirst()

        val derivedSpeed = derivedSpeed(lat, lon, monoMs)
        val speed = gpsSpeedMps ?: derivedSpeed
        if (gpsBearingDeg != null && gpsSpeedMps != null) {
            if (gpsSpeedMps >= movingMps) held = normalize(gpsBearingDeg)
        } else if (!isStationary(lat, lon, monoMs)) {
            derivedBearing(lat, lon, monoMs)?.let { held = it }
        }
        return Course(held, speed)
    }

    /** Oublie tout (changement de lieu sans rapport avec ce qui précède). */
    fun reset() {
        history.clear()
        held = null
    }

    /** Cap du point le plus récent situé à [baselineM] au moins derrière nous, s'il n'est pas trop ancien. */
    private fun derivedBearing(lat: Double, lon: Double, monoMs: Long): Double? {
        for (i in history.size - 2 downTo 0) {
            val sample = history[i]
            if (monoMs - sample.monoMs > baselineMaxMs) return null
            if (Geo.distanceM(sample.lat, sample.lon, lat, lon) >= baselineM) return Geo.bearingDeg(sample.lat, sample.lon, lat, lon)
        }
        return null
    }

    /**
     * Vrai si l'on n'a pratiquement pas bougé ces dernières secondes : la position annoncée ne fait que trembler
     * autour du même point, et un cap tiré de ces tremblements ferait tourner la flèche au hasard.
     */
    private fun isStationary(lat: Double, lon: Double, monoMs: Long): Boolean {
        var farthest = 0.0
        for (i in history.size - 2 downTo 0) {
            val sample = history[i]
            if (monoMs - sample.monoMs > STATIONARY_WINDOW_MS) break
            val d = Geo.distanceM(sample.lat, sample.lon, lat, lon)
            if (d > farthest) farthest = d
        }
        return farthest < STATIONARY_SPREAD_M
    }

    /** Vitesse moyenne sur les deux à trois dernières secondes. */
    private fun derivedSpeed(lat: Double, lon: Double, monoMs: Long): Double {
        var reference: Sample? = null
        for (i in history.size - 2 downTo 0) {
            val sample = history[i]
            if (monoMs - sample.monoMs > SPEED_WINDOW_MS) break
            reference = sample
        }
        val from = reference ?: return 0.0
        val seconds = (monoMs - from.monoMs) / 1000.0
        return if (seconds >= 0.5) Geo.distanceM(from.lat, from.lon, lat, lon) / seconds else 0.0
    }

    private fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

    private companion object {
        const val MAX_SAMPLES = 40
        const val HISTORY_MS = 20_000L
        const val SPEED_WINDOW_MS = 3_200L
        /** Moins de six mètres parcourus en quatre secondes (5 km/h) : on est à l'arrêt, ou tout comme. */
        const val STATIONARY_WINDOW_MS = 4_200L
        const val STATIONARY_SPREAD_M = 6.0
        /** Au-delà, ce n'est plus un déplacement à vélo entre deux positions : le GPS (ou le lieu) a sauté. */
        const val JUMP_M = 400.0
    }
}
