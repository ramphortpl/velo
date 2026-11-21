package fr.velogo.app.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
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
    /**
     * Version de l'analyse déjà faite sur cette sortie (meilleurs temps, passages sur les segments) ; 0 = jamais.
     * Une sortie en retard sur la version courante est réanalysée en tâche de fond.
     */
    @ColumnInfo(defaultValue = "0") val analysisVersion: Int = 0,
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

/** Un itinéraire enregistré pour être repris plus tard (ou hors ligne). */
@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
    /** Type de vélo pour lequel il a été calculé (nom d'un BikeType). */
    val bike: String,
    val distanceM: Double,
    val ascentM: Double,
    val durationS: Double,
    /** L'itinéraire complet (trace, altitudes, consignes), encodé par RouteCodec. */
    val data: String,
)

/** Un segment personnel : un bout de route sur lequel on veut comparer ses temps d'une sortie à l'autre. */
@Entity(tableName = "segments")
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
    val distanceM: Double,
    val ascentM: Double,
    val startAltitudeM: Double? = null,
    val endAltitudeM: Double? = null,
    /** Tracé du segment, encodé (« encoded polyline », précision d'un mètre). */
    val points: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

/** Un passage d'une sortie sur un segment, avec son temps. */
@Entity(
    tableName = "segment_efforts",
    foreignKeys = [
        ForeignKey(entity = SegmentEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("segmentId"), Index("rideId")],
)
data class SegmentEffortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val rideId: Long,
    /** Heure d'entrée dans le segment. */
    val startTimeMs: Long,
    /** Temps mis pour le parcourir, arrêts compris. */
    val elapsedMs: Long,
)

/** Le meilleur temps d'une sortie sur une distance de référence (5 km, 10 km…). */
@Entity(
    tableName = "best_efforts",
    primaryKeys = ["rideId", "distanceM"],
    foreignKeys = [
        ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BestEffortEntity(
    val rideId: Long,
    val distanceM: Int,
    val durationMs: Long,
    /** Heure à laquelle ce passage a commencé. */
    val startTimeMs: Long,
)
