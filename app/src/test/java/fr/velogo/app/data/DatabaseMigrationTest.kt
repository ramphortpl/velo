package fr.velogo.app.data

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.core.geo.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Une mise à jour de l'appli ne doit jamais perdre une sortie : on fabrique une base au format d'une ancienne
 * version (d'après son schéma exporté dans app/schemas), on y range une sortie, puis on l'ouvre avec l'appli
 * d'aujourd'hui.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun schema(version: Int): JSONObject {
        val name = "schemas/fr.velogo.app.data.db.VeloGoDatabase/$version.json"
        val file = listOf(File(name), File("app/$name")).first { it.isFile }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    /** Crée une base vide au format de la version [version], exactement comme Room l'aurait créée à l'époque. */
    private fun createOldDatabase(file: File, version: Int): SQLiteDatabase {
        file.parentFile?.mkdirs()
        file.delete()
        val database = schema(version)
        assertEquals(version, database.getInt("version"))
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices")
            if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = database.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    @Test
    fun `les sorties et les itineraires survivent au passage a la version 3`() = runBlocking<Unit> {
        val file = context.getDatabasePath("ancienne-version.db")
        val t0 = 1_795_000_000_000L
        createOldDatabase(file, version = 2).use { db ->
            db.insertOrThrow("rides", null, ContentValues().apply {
                put("id", 7L); put("name", "Tour du lac"); put("startTimeMs", t0); put("endTimeMs", t0 + 3_600_000L); put("status", 1)
                put("distanceM", 23_456.7); put("movingMs", 3_300_000L); put("elapsedMs", 3_600_000L); put("maxSpeedMps", 14.2)
                put("ascentM", 312.0); put("descentM", 298.0); put("minAltitudeM", 447.0); put("maxAltitudeM", 702.0)
                put("pointCount", 600); put("lastSegment", 0); put("preview", "abc")
                put("minLat", 45.0); put("minLon", 5.0); put("maxLat", 45.1); put("maxLon", 5.1)
                put("altitudeSource", "IGN"); put("updatedAtMs", t0 + 3_600_000L)
            })
            var lat = 45.0
            for (seq in 0 until 600) {
                lat = Geo.offset(lat, 5.0, 0.0, 12.0).lat
                db.insertOrThrow("track_points", null, ContentValues().apply {
                    put("rideId", 7L); put("seq", seq); put("timeMs", t0 + seq * 2_000L); put("lat", lat); put("lon", 5.0)
                    put("altitudeM", 450.0f + seq * 0.3f); put("speedMps", 6.0f); put("accuracyM", 4.0f); put("segment", 0)
                })
            }
            db.insertOrThrow("saved_routes", null, ContentValues().apply {
                put("id", 3L); put("name", "Vers la gare"); put("createdAtMs", t0); put("bike", "HYBRID")
                put("distanceM", 12_000.0); put("ascentM", 150.0); put("durationS", 2_400.0); put("data", "{}")
            })
        }

        // L'appli d'aujourd'hui ouvre cette base : Room la met à niveau toute seule.
        val db = Room.databaseBuilder<VeloGoDatabase>(context, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val rides = RideRepository(db)
            val ride = rides.ride(7)
            assertNotNull(ride)
            assertEquals("Tour du lac", ride!!.name)
            assertEquals(23_456.7, ride.distanceM, 1e-9)
            assertEquals(312.0, ride.ascentM, 1e-9)
            assertEquals(RideEntity.ALTITUDE_IGN, ride.altitudeSource)
            assertEquals(RideEntity.STATUS_FINISHED, ride.status)
            // La nouvelle colonne vaut 0 : la sortie n'a pas encore été analysée.
            assertEquals(0, ride.analysisVersion)
            val points = rides.points(7)
            assertEquals(600, points.size)
            assertEquals(t0 + 599 * 2_000L, points.last().timeMs)
            assertEquals(450.0 + 599 * 0.3, points.last().altitudeM!!, 0.01)
            assertEquals("Vers la gare", db.savedRoutes().get(3)!!.name)

            // Les nouveautés fonctionnent sur la base migrée : analyse de l'ancienne sortie, création d'un segment.
            val analysis = RideAnalysis(db, rides)
            analysis.analyzePending()
            assertEquals(RideAnalysis.VERSION, rides.ride(7)!!.analysisVersion)
            assertEquals(listOf(5_000), db.bestEfforts().observeAll().first().map { it.distanceM })
            val segments = SegmentRepository(db, rides)
            val segmentId = segments.create("Ligne droite", 7, fromM = 1_000.0, toM = 4_000.0)
            assertNotNull(segmentId)
            analysis.matchSegment(segmentId!!)
            assertEquals(listOf(7L), segments.observeEffortsOfSegment(segmentId).first().map { it.rideId })
            assertTrue(rides.finishedRides().size == 1)
        } finally {
            db.close()
            file.delete()
        }
    }
}
