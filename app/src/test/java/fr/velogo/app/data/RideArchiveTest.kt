package fr.velogo.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.core.geo.Geo
import fr.velogo.core.gpx.Gpx
import fr.velogo.core.ride.AltitudeSource
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Sauvegarde des sorties dans une archive de fichiers GPX, puis reprise sur un téléphone « neuf ». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RideArchiveTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databases = ArrayList<VeloGoDatabase>()

    private fun database(): VeloGoDatabase =
        Room.inMemoryDatabaseBuilder<VeloGoDatabase>(context).setDriver(AndroidSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build().also { databases.add(it) }

    private lateinit var rides: RideRepository
    private lateinit var archive: RideArchive

    @Before
    fun open() {
        rides = RideRepository(database())
        archive = RideArchive(context, rides)
    }

    @After
    fun close() = databases.forEach { it.close() }

    private val t0 = 1_800_000_000_000L
    private val day = 86_400_000L

    /** Une sortie vallonnée : deux tronçons séparés par une pause, une position par seconde. */
    private fun track(startMs: Long, speedMps: Double): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var lat = 45.0
        var time = startMs
        for (i in 0 until 1_200) {
            if (i == 700) time += 600_000
            lat = Geo.offset(lat, 5.0, 0.0, speedMps).lat
            time += 1_000
            out.add(TrackPoint(time, lat, 5.0 + i * 1e-5, altitudeM = 300.0 + 40.0 * kotlin.math.sin(i / 90.0), speedMps = speedMps, accuracyM = 4.0, segment = if (i < 700) 0 else 1))
        }
        return out
    }

    private suspend fun add(name: String, startMs: Long, speedMps: Double, source: String): Long {
        val points = track(startMs, speedMps)
        val totals = RideAnalyzer.summarize(points, if (source == RideEntity.ALTITUDE_GNSS) AltitudeSource.GNSS else AltitudeSource.TERRAIN)
        return rides.importRide(name, points, totals, source)
    }

    @Test
    fun `sauvegarde puis reprise sur un telephone neuf`() = runBlocking<Unit> {
        add("Tour du lac / été", t0, 6.0, RideEntity.ALTITUDE_IGN)
        add("Sortie du soir", t0 + day, 7.5, RideEntity.ALTITUDE_GNSS)
        val before = rides.finishedRides().sortedBy { it.startTimeMs }

        val bytes = ByteArrayOutputStream()
        var progress = 0 to 0
        assertEquals(2, archive.exportTo(bytes) { done, total -> progress = done to total })
        assertEquals(2 to 2, progress)

        // L'archive : un fichier GPX par sortie, nommé d'après sa date et son nom (sans caractères gênants).
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) names.add((zip.nextEntry ?: break).name)
        }
        assertEquals(2, names.size)
        assertTrue(names.all { it.endsWith(".gpx") && !it.contains('/') && !it.contains(' ') })
        assertTrue(names[0].endsWith("_Tour-du-lac-été.gpx"))

        // Téléphone neuf : base vide.
        val fresh = RideRepository(database())
        val restore = RideArchive(context, fresh)
        val result = restore.importFrom(ByteArrayInputStream(bytes.toByteArray()), "VeloGo_sorties.zip")
        assertEquals(2, result.imported.size)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.unreadable)

        val after = fresh.finishedRides().sortedBy { it.startTimeMs }
        assertEquals(before.map { it.name }, after.map { it.name })
        for ((old, new) in before.zip(after)) {
            assertEquals(old.startTimeMs, new.startTimeMs)
            assertEquals(old.distanceM, new.distanceM, 1.0)
            assertEquals(old.movingMs, new.movingMs)
            assertEquals(old.elapsedMs, new.elapsedMs)
            assertEquals(old.ascentM, new.ascentM, 2.0)
            assertEquals(old.altitudeSource, new.altitudeSource)
            assertEquals(old.pointCount, new.pointCount)
            assertEquals(RideEntity.STATUS_FINISHED, new.status)
            assertTrue(new.preview != null && new.minLat != null)
        }
        // Les vitesses, absentes du GPX, ont été recalculées : le graphique de vitesse existe.
        val points = fresh.points(after[0].id)
        assertEquals(6.0, points[300].speedMps!!, 0.1)
        assertEquals(setOf(0, 1), points.map { it.segment }.toSet())

        // Importer deux fois la même archive n'ajoute rien.
        val again = restore.importFrom(ByteArrayInputStream(bytes.toByteArray()), "VeloGo_sorties.zip")
        assertEquals(0, again.imported.size)
        assertEquals(2, again.duplicates)
        assertEquals(2, fresh.finishedRides().size)
    }

    @Test
    fun `un fichier gpx seul venu d'une autre appli`() = runBlocking<Unit> {
        val gpx = StringBuilder().also { Gpx.write(it, "Sortie Strava", track(t0, 8.0).map { p -> p.copy(speedMps = null) }, creator = "Autre appli") }.toString()
        val result = archive.importFrom(ByteArrayInputStream(gpx.toByteArray()), "export.gpx")
        assertEquals(1, result.imported.size)
        val ride = rides.ride(result.imported.single())!!
        assertEquals("Sortie Strava", ride.name)
        // Pas d'indication sur l'altitude : traitée comme celle d'un GPS.
        assertEquals(RideEntity.ALTITUDE_GNSS, ride.altitudeSource)
        assertEquals(1_200 * 8.0, ride.distanceM, 30.0)
    }

    @Test
    fun `ce qui n'est pas une sortie est signale sans rien casser`() = runBlocking<Unit> {
        // Un itinéraire (sans heures), puis un fichier qui n'est pas du GPX.
        val route = StringBuilder().also { Gpx.writeRoute(it, "Itinéraire", track(t0, 6.0)) }.toString()
        assertEquals(ImportResult(unreadable = 1), archive.importFrom(ByteArrayInputStream(route.toByteArray()), "itineraire.gpx"))
        assertEquals(ImportResult(unreadable = 1), archive.importFrom(ByteArrayInputStream("ceci n'est pas un fichier GPX".toByteArray()), "notes.txt"))
        assertTrue(rides.finishedRides().isEmpty())
    }
}
