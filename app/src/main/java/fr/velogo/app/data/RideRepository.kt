package fr.velogo.app.data

import androidx.room3.withWriteTransaction
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.TrackPointEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Polyline
import fr.velogo.core.geo.Simplify
import fr.velogo.core.ride.RideTotals
import fr.velogo.core.ride.TrackPoint
import kotlinx.coroutines.flow.Flow

/** Accès aux sorties enregistrées. Seul point de passage vers la base pour tout ce qui touche aux sorties. */
class RideRepository(private val db: VeloGoDatabase) {
    private val rides = db.rides()
    private val points = db.trackPoints()

    fun observeRides(): Flow<List<RideEntity>> = rides.observeFinished()

    fun observeRide(id: Long): Flow<RideEntity?> = rides.observe(id)

    suspend fun ride(id: Long): RideEntity? = rides.get(id)

    suspend fun finishedRides(): List<RideEntity> = rides.finished()

    /** Sortie restée « en cours » : l'appli a été arrêtée pendant un enregistrement. */
    suspend fun unfinishedRide(): RideEntity? = rides.unfinished()

    suspend fun createRide(startTimeMs: Long, name: String): Long =
        rides.insert(RideEntity(name = name, startTimeMs = startTimeMs, updatedAtMs = startTimeMs))

    /** Ajoute des points à la trace et met à jour les totaux, en une seule transaction. */
    suspend fun appendPoints(
        rideId: Long,
        firstSeq: Int,
        newPoints: List<TrackPoint>,
        totals: RideTotals,
        lastSegment: Int,
        nowMs: Long,
    ) {
        db.withWriteTransaction {
            if (newPoints.isNotEmpty()) {
                points.insertAll(newPoints.mapIndexed { i, p -> p.toEntity(rideId, firstSeq + i) })
            }
            val current = rides.get(rideId) ?: return@withWriteTransaction
            rides.update(
                current.withTotals(totals).copy(
                    pointCount = firstSeq + newPoints.size,
                    lastSegment = lastSegment,
                    updatedAtMs = nowMs,
                )
            )
        }
    }

    /** Clôt une sortie : totaux définitifs, aperçu de la trace et emprise. */
    suspend fun finishRide(rideId: Long, totals: RideTotals, endTimeMs: Long) {
        val track = points(rideId)
        val current = rides.get(rideId) ?: return
        val line = track.map { LatLon(it.lat, it.lon) }
        val box = BoundingBox.of(line)
        rides.update(
            current.withTotals(totals).copy(
                status = RideEntity.STATUS_FINISHED,
                endTimeMs = endTimeMs,
                pointCount = track.size,
                preview = if (line.size >= 2) Polyline.encode(Simplify.toMaxPoints(line, PREVIEW_POINTS)) else null,
                minLat = box?.minLat, minLon = box?.minLon, maxLat = box?.maxLat, maxLon = box?.maxLon,
                updatedAtMs = endTimeMs,
            )
        )
    }

    /**
     * Ajoute à l'historique une sortie déjà terminée (lue dans un fichier), d'un seul bloc : si l'appli est
     * arrêtée en plein import, il ne reste pas de sortie à moitié écrite.
     */
    suspend fun importRide(name: String, track: List<TrackPoint>, totals: RideTotals, altitudeSource: String): Long {
        val line = track.map { LatLon(it.lat, it.lon) }
        val box = BoundingBox.of(line)
        val start = track.first().timeMs
        val end = track.last().timeMs
        return db.withWriteTransaction {
            val id = rides.insert(
                RideEntity(name = name.trim().ifEmpty { "Sortie" }, startTimeMs = start, endTimeMs = end, status = RideEntity.STATUS_FINISHED)
                    .withTotals(totals)
                    .copy(
                        pointCount = track.size,
                        lastSegment = track.maxOf { it.segment },
                        preview = if (line.size >= 2) Polyline.encode(Simplify.toMaxPoints(line, PREVIEW_POINTS)) else null,
                        minLat = box?.minLat, minLon = box?.minLon, maxLat = box?.maxLat, maxLon = box?.maxLon,
                        altitudeSource = altitudeSource,
                        updatedAtMs = end,
                    )
            )
            track.withIndex().chunked(CHUNK).forEach { chunk -> points.insertAll(chunk.map { (i, p) -> p.toEntity(id, i) }) }
            id
        }
    }

    suspend fun points(rideId: Long): List<TrackPoint> = points.forRide(rideId).map { it.toModel() }

    suspend fun pointCount(rideId: Long): Int = points.count(rideId)

    /** Remplace les altitudes d'une sortie (recalage sur le relief) et les totaux qui en dépendent. */
    suspend fun replaceAltitudes(rideId: Long, corrected: List<TrackPoint>, totals: RideTotals, source: String) {
        db.withWriteTransaction {
            corrected.withIndex().chunked(CHUNK).forEach { chunk ->
                points.insertAll(chunk.map { (i, p) -> p.toEntity(rideId, i) })
            }
            val current = rides.get(rideId) ?: return@withWriteTransaction
            rides.update(
                current.copy(
                    ascentM = totals.ascentM,
                    descentM = totals.descentM,
                    minAltitudeM = totals.minAltitudeM,
                    maxAltitudeM = totals.maxAltitudeM,
                    altitudeSource = source,
                )
            )
        }
    }

    suspend fun rename(id: Long, name: String) = rides.rename(id, name.trim().ifEmpty { "Sortie" })

    suspend fun delete(id: Long) = rides.delete(id)

    private fun RideEntity.withTotals(t: RideTotals) = copy(
        distanceM = t.distanceM,
        movingMs = t.movingMs,
        elapsedMs = t.elapsedMs,
        maxSpeedMps = t.maxSpeedMps,
        ascentM = t.ascentM,
        descentM = t.descentM,
        minAltitudeM = t.minAltitudeM,
        maxAltitudeM = t.maxAltitudeM,
    )

    private fun TrackPoint.toEntity(rideId: Long, seq: Int) = TrackPointEntity(
        rideId = rideId, seq = seq, timeMs = timeMs, lat = lat, lon = lon,
        altitudeM = altitudeM?.toFloat(), speedMps = speedMps?.toFloat(), accuracyM = accuracyM?.toFloat(),
        segment = segment,
    )

    private fun TrackPointEntity.toModel() = TrackPoint(
        timeMs = timeMs, lat = lat, lon = lon,
        altitudeM = altitudeM?.toDouble(), speedMps = speedMps?.toDouble(), accuracyM = accuracyM?.toDouble(),
        segment = segment,
    )

    private companion object {
        const val PREVIEW_POINTS = 160
        const val CHUNK = 400
    }
}

/** Totaux d'une sortie enregistrée, au format du moteur. */
fun RideEntity.totals() = RideTotals(
    distanceM = distanceM,
    movingMs = movingMs,
    elapsedMs = elapsedMs,
    maxSpeedMps = maxSpeedMps,
    ascentM = ascentM,
    descentM = descentM,
    minAltitudeM = minAltitudeM,
    maxAltitudeM = maxAltitudeM,
)
