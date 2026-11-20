package fr.velogo.core.ride

/** Une position GPS brute, telle que fournie par le téléphone. */
data class Fix(
    /** Heure UTC en millisecondes. */
    val timeMs: Long,
    /** Horloge monotone en millisecondes (ne recule jamais) : sert à mesurer les durées. */
    val monoMs: Long,
    val lat: Double,
    val lon: Double,
    /** Altitude au-dessus du niveau moyen de la mer, en mètres. */
    val altitudeM: Double? = null,
    /** Vitesse mesurée par effet Doppler, en m/s. */
    val speedMps: Double? = null,
    /** Précision horizontale en mètres (rayon à 68 %). */
    val accuracyM: Double? = null,
    /** Précision verticale en mètres (68 %). */
    val verticalAccuracyM: Double? = null,
    /** Cap de déplacement en degrés. */
    val bearingDeg: Double? = null,
    /**
     * Altitude du sol à cet endroit d'après un modèle de relief (pack hors ligne), si l'on en a un.
     * Bien plus stable que l'altitude GPS : quand elle est là, c'est elle qui alimente l'affichage en direct.
     */
    val terrainAltitudeM: Double? = null,
)

/** Un point de trace tel qu'il est enregistré dans l'historique. */
data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val accuracyM: Double? = null,
    /** Numéro de tronçon : change après chaque pause manuelle, pour ne pas tracer de trait pendant la pause. */
    val segment: Int = 0,
)

enum class RideState {
    /** Aucune sortie en cours. */
    IDLE,
    /** Enregistrement actif. */
    RECORDING,
    /** À l'arrêt : l'enregistrement reprendra seul au redémarrage. */
    AUTO_PAUSED,
    /** Pause demandée par l'utilisateur. */
    PAUSED,
}

/** D'où vient l'altitude : conditionne le lissage et le seuil de dénivelé. */
enum class AltitudeSource(
    val accelNoise: Double,
    val defaultSigmaM: Double,
    val minSigmaM: Double,
    val ascentThresholdM: Double,
    val slopeWindowM: Double,
) {
    /**
     * GPS seul : l'altitude dérive de plusieurs mètres en quelques minutes, donc on lisse fort et on ne compte
     * une montée qu'à partir de 8 m (même ordre de grandeur que les applis du marché sans baromètre).
     * Réglé par balayage sur des sorties simulées : pas de faux dénivelé sur le plat, pente stable à ±0,6 %.
     */
    GNSS(accelNoise = 0.02, defaultSigmaM = 8.0, minSigmaM = 8.0, ascentThresholdM = 8.0, slopeWindowM = 120.0),
    /** Modèle de relief ou baromètre : précis, donc peu lissé et réactif. */
    TERRAIN(accelNoise = 0.25, defaultSigmaM = 1.5, minSigmaM = 1.0, ascentThresholdM = 2.0, slopeWindowM = 40.0),
}

data class RideConfig(
    val autoPause: Boolean = true,
    /** En dessous de cette vitesse (m/s) on est considéré à l'arrêt. 1,0 m/s = 3,6 km/h. */
    val autoPauseSpeedMps: Double = 1.0,
    /** Au-dessus de cette vitesse (m/s) on repart. 1,7 m/s ≈ 6 km/h. */
    val autoResumeSpeedMps: Double = 1.7,
    /** Durée sous le seuil avant de passer en pause automatique. */
    val autoPauseDelayMs: Long = 4_000,
    /** Les points moins précis que cela (en mètres) sont ignorés. */
    val maxAccuracyM: Double = 35.0,
    /** Vitesse maximale crédible à vélo (m/s) : au-delà, le point est un parasite. 33 m/s ≈ 120 km/h. */
    val maxPlausibleSpeedMps: Double = 33.0,
    val altitudeSource: AltitudeSource = AltitudeSource.GNSS,
)

/** Totaux d'une sortie : ce qui est sauvegardé au fil de l'eau et à l'arrivée. */
data class RideTotals(
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val elapsedMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val minAltitudeM: Double? = null,
    val maxAltitudeM: Double? = null,
) {
    val avgSpeedMps: Double get() = if (movingMs >= 1_000) distanceM / (movingMs / 1000.0) else 0.0
}

/** Photographie de la sortie à un instant donné : c'est ce que l'écran affiche. */
data class RideSnapshot(
    val state: RideState = RideState.IDLE,
    val startTimeMs: Long? = null,
    val totals: RideTotals = RideTotals(),
    /** Vitesse instantanée lissée, null sans signal GPS. */
    val speedMps: Double? = null,
    val altitudeM: Double? = null,
    val slopePct: Double? = null,
    val accuracyM: Double? = null,
    val hasSignal: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val bearingDeg: Double? = null,
) {
    val isActive: Boolean get() = state != RideState.IDLE
}
