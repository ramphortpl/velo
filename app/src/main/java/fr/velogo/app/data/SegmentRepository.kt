package fr.velogo.app.data

import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.SegmentEffortEntity
import fr.velogo.app.data.db.SegmentEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Polyline
import fr.velogo.core.geo.Simplify
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.RideAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Les segments personnels et les temps réalisés dessus. */
class SegmentRepository(private val db: VeloGoDatabase, private val rides: RideRepository) {
    private val segments = db.segments()

    fun observeSegments(): Flow<List<SegmentEntity>> = segments.observeAll()

    fun observeSegment(id: Long): Flow<SegmentEntity?> = segments.observe(id)

    /** Tous les passages, du plus rapide au plus lent. */
    fun observeEfforts(): Flow<List<SegmentEffortEntity>> = segments.observeEfforts()

    fun observeEffortsOfSegment(id: Long): Flow<List<SegmentEffortEntity>> = segments.observeEffortsOfSegment(id)

    suspend fun segment(id: Long): SegmentEntity? = segments.get(id)

    /**
     * Crée un segment à partir d'un morceau de sortie, entre les distances [fromM] et [toM] depuis le départ.
     * Renvoie son identifiant, ou null si ce morceau est trop court pour faire un segment.
     */
    suspend fun create(name: String, rideId: Long, fromM: Double, toM: Double, nowMs: Long = System.currentTimeMillis()): Long? {
        val ride = rides.ride(rideId) ?: return null
        val points = rides.points(rideId)
        val built = withContext(Dispatchers.Default) {
            val distances = RideAnalyzer.cumulativeDistances(points)
            val first = distances.indexOfFirst { it >= fromM }.coerceAtLeast(0)
            val last = distances.indexOfLast { it <= toM }
            if (last - first < 1) return@withContext null
            val part = points.subList(first, last + 1)
            val line = Simplify.simplify(part.map { LatLon(it.lat, it.lon) }, SIMPLIFY_M)
            val length = Geo.lengthM(line)
            val box = BoundingBox.of(line)
            if (length < MIN_LENGTH_M || box == null) return@withContext null
            // Le dénivelé du segment est compté comme celui de la sortie dont il vient (même origine d'altitude).
            val source = if (ride.altitudeSource == RideEntity.ALTITUDE_GNSS) AltitudeSource.GNSS else AltitudeSource.TERRAIN
            val smoothed = RideAnalyzer.smoothedAltitudes(part, RideAnalyzer.cumulativeDistances(part), source.slopeWindowM)
            SegmentEntity(
                name = name.trim().ifEmpty { "Segment" },
                createdAtMs = nowMs,
                distanceM = length,
                ascentM = RideAnalyzer.summarize(part, source).ascentM,
                startAltitudeM = smoothed.firstOrNull { it != null },
                endAltitudeM = smoothed.lastOrNull { it != null },
                points = Polyline.encode(line),
                minLat = box.minLat, minLon = box.minLon, maxLat = box.maxLat, maxLon = box.maxLon,
            )
        } ?: return null
        return segments.insert(built)
    }

    suspend fun rename(id: Long, name: String) = segments.rename(id, name.trim().ifEmpty { "Segment" })

    suspend fun delete(id: Long) = segments.delete(id)

    companion object {
        /** En dessous, ce n'est plus un segment : le chronométrage n'aurait pas de sens. */
        const val MIN_LENGTH_M = 200.0
        private const val SIMPLIFY_M = 2.0
    }
}
