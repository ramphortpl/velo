package fr.velogo.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

/** Une sortie : ses totaux et un aperçu de sa trace. Les points sont dans [TrackPointEntity]. */
@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    /** [STATUS_RECORDING] tant que la sortie est en cours, [STATUS_FINISHED] ensuite. */
    val status: Int = STATUS_RECORDING,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val elapsedMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val minAltitudeM: Double? = null,
    val maxAltitudeM: Double? = null,
    val pointCount: Int = 0,
    /** Dernier numéro de tronçon utilisé (pour reprendre une sortie interrompue). */
    val lastSegment: Int = 0,
    /** Trace simplifiée, encodée (« encoded polyline ») : sert aux vignettes de la liste. */
    val preview: String? = null,
    val minLat: Double? = null,
    val minLon: Double? = null,
    val maxLat: Double? = null,
    val maxLon: Double? = null,
    /** Origine de l'altitude : « GNSS » (GPS seul), « IGN » (recalée en ligne) ou « RELIEF » (pack hors ligne). */
    val altitudeSource: String = ALTITUDE_GNSS,
    /** Dernière sauvegarde des totaux pendant l'enregistrement. */
    val updatedAtMs: Long = 0,
) {
    companion object {
        const val STATUS_RECORDING = 0
        const val STATUS_FINISHED = 1
        const val ALTITUDE_GNSS = "GNSS"
        const val ALTITUDE_IGN = "IGN"
        const val ALTITUDE_RELIEF = "RELIEF"
    }
}

/** Un point de la trace d'une sortie. */
@Entity(
    tableName = "track_points",
    primaryKeys = ["rideId", "seq"],
    foreignKeys = [
        ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TrackPointEntity(
    val rideId: Long,
    /** Rang du point dans la sortie. */
    val seq: Int,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Float?,
    val speedMps: Float?,
    val accuracyM: Float?,
    val segment: Int,
)
