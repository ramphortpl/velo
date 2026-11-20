package fr.velogo.app.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Base locale de VeloGo. Tout reste sur le téléphone.
 * À chaque changement de schéma : incrémenter [version] et fournir une migration, pour ne jamais perdre de sortie.
 */
@Database(
    entities = [RideEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VeloGoDatabase : RoomDatabase() {
    abstract fun rides(): RideDao
    abstract fun trackPoints(): TrackPointDao

    companion object {
        const val FILE_NAME = "velogo.db"

        fun create(context: Context): VeloGoDatabase =
            Room.databaseBuilder<VeloGoDatabase>(context.applicationContext, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
