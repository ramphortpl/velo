package fr.velogo.app.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {
    @Insert
    suspend fun insert(ride: RideEntity): Long

    @Update
    suspend fun update(ride: RideEntity)

    @Query("SELECT * FROM rides WHERE status = 1 ORDER BY startTimeMs DESC")
    fun observeFinished(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE status = 1 ORDER BY startTimeMs DESC")
    suspend fun finished(): List<RideEntity>

    @Query("SELECT * FROM rides WHERE id = :id")
    fun observe(id: Long): Flow<RideEntity?>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun get(id: Long): RideEntity?

    @Query("SELECT * FROM rides WHERE status = 0 ORDER BY startTimeMs DESC LIMIT 1")
    suspend fun unfinished(): RideEntity?

    @Query("UPDATE rides SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun delete(id: Long)

    /** Sorties terminées dont l'analyse (meilleurs temps, segments) est absente ou d'une version antérieure. */
    @Query("SELECT * FROM rides WHERE status = 1 AND analysisVersion < :version ORDER BY startTimeMs DESC")
    suspend fun needingAnalysis(version: Int): List<RideEntity>

    @Query("UPDATE rides SET analysisVersion = :version WHERE id = :id")
    suspend fun setAnalysisVersion(id: Long, version: Int)
}

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY seq")
    suspend fun forRide(rideId: Long): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_points WHERE rideId = :rideId")
    suspend fun count(rideId: Long): Int
}

@Dao
interface SavedRouteDao {
    @Insert
    suspend fun insert(route: SavedRouteEntity): Long

    /** La liste, sans le contenu des itinéraires (plusieurs centaines de Ko chacun). */
    @Query("SELECT id, name, createdAtMs, bike, distanceM, ascentM, durationS, '' AS data FROM saved_routes ORDER BY createdAtMs DESC")
    fun observeSummaries(): Flow<List<SavedRouteEntity>>

    @Query("SELECT * FROM saved_routes WHERE id = :id")
    suspend fun get(id: Long): SavedRouteEntity?

    @Query("UPDATE saved_routes SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SegmentDao {
    @Insert
    suspend fun insert(segment: SegmentEntity): Long

    @Query("SELECT * FROM segments ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments")
    suspend fun all(): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE id = :id")
    fun observe(id: Long): Flow<SegmentEntity?>

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun get(id: Long): SegmentEntity?

    @Query("UPDATE segments SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertEfforts(efforts: List<SegmentEffortEntity>)

    @Query("DELETE FROM segment_efforts WHERE rideId = :rideId")
    suspend fun deleteEffortsOfRide(rideId: Long)

    @Query("DELETE FROM segment_efforts WHERE segmentId = :segmentId")
    suspend fun deleteEffortsOfSegment(segmentId: Long)

    /** Tous les passages, du plus rapide au plus lent. */
    @Query("SELECT * FROM segment_efforts ORDER BY elapsedMs")
    fun observeEfforts(): Flow<List<SegmentEffortEntity>>

    @Query("SELECT * FROM segment_efforts WHERE segmentId = :segmentId ORDER BY elapsedMs")
    fun observeEffortsOfSegment(segmentId: Long): Flow<List<SegmentEffortEntity>>
}

@Dao
interface BestEffortDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(efforts: List<BestEffortEntity>)

    @Query("DELETE FROM best_efforts WHERE rideId = :rideId")
    suspend fun deleteForRide(rideId: Long)

    /** Tous les meilleurs temps, par distance puis du plus rapide au plus lent. */
    @Query("SELECT * FROM best_efforts ORDER BY distanceM, durationMs")
    fun observeAll(): Flow<List<BestEffortEntity>>
}
