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
