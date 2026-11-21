package fr.velogo.app.data

import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.SegmentEffortEntity
import fr.velogo.app.data.db.SegmentEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.Polyline
import fr.velogo.core.geo.Simplify
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.segments.SegmentCutter
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
     * Renvoie son identifiant, ou null si ce morceau est trop court pour faire un segment. Ses mesures sont
     * celles que [SegmentCutter] annonce pour ce morceau : l'écran de création affiche donc les mêmes.
     */
    suspend fun create(name: String, rideId: Long, fromM: Double, toM: Double, nowMs: Long = System.currentTimeMillis()): Long? {
        val ride = rides.ride(rideId) ?: return null
        val points = rides.points(rideId)
        val built = withContext(Dispatchers.Default) {
            // Altitude et dénivelé comptés comme ceux de la sortie dont le segment vient (même origine d'altitude).
            val source = if (ride.altitudeSource == RideEntity.ALTITUDE_GNSS) AltitudeSource.GNSS else AltitudeSource.TERRAIN
            val cutter = SegmentCutter(points, source)
            val measures = cutter.measure(fromM, toM) ?: return@withContext null
            if (measures.lengthM < MIN_LENGTH_M) return@withContext null
            val line = Simplify.simplify(cutter.line(fromM, toM), SIMPLIFY_M)
            val box = BoundingBox.of(line)
            if (line.size < 2 || box == null) return@withContext null
            SegmentEntity(
                name = name.trim().ifEmpty { "Segment" },
                createdAtMs = nowMs,
                distanceM = measures.lengthM,
                ascentM = measures.ascentM,
                startAltitudeM = measures.startAltitudeM,
                endAltitudeM = measures.endAltitudeM,
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
