package fr.velogo.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.VeloGoDatabase
import fr.velogo.app.ui.segments.effortRows
import fr.velogo.app.ui.segments.rideBestRows
import fr.velogo.app.ui.segments.rideSegmentRows
import fr.velogo.app.ui.segments.segmentRows
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.RideAnalyzer
import fr.velogo.core.ride.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Segments et meilleurs temps de bout en bout, sur une vraie base (en mémoire) : sorties enregistrées, segment
 * créé à partir de l'une d'elles, passages retrouvés dans les autres, classement, suppression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RideAnalysisTest {
    private lateinit var db: VeloGoDatabase
    private lateinit var rides: RideRepository
    private lateinit var segments: SegmentRepository
    private lateinit var analysis: RideAnalysis

    @Before
    fun open() {
        // Sur l'ordinateur, c'est le SQLite d'Android simulé par Robolectric qui sert : celui que l'appli embarque
        // (BundledSQLiteDriver) n'existe que pour les téléphones. Les requêtes et le schéma sont les mêmes.
        db = Room.inMemoryDatabaseBuilder<VeloGoDatabase>(ApplicationProvider.getApplicationContext<Application>())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        rides = RideRepository(db)
        segments = SegmentRepository(db, rides)
        analysis = RideAnalysis(db, rides)
    }

    @After
    fun close() = db.close()

    /** Une route de 6,5 km avec deux virages, un point tous les 10 m. */
    private fun road(start: LatLon = LatLon(45.2, 5.7)): List<LatLon> {
        val out = ArrayList<LatLon>()
        var p = start
        out.add(p)
        for ((bearing, length) in listOf(40.0 to 2_500.0, 110.0 to 2_000.0, 20.0 to 2_000.0)) {
            repeat((length / 10).toInt()) {
                p = Geo.offset(p.lat, p.lon, bearing, 10.0)
                out.add(p)
            }
        }
        return out
    }

    /** Enregistre une sortie sur [path] à vitesse constante (une position par seconde), en montée régulière. */
    private suspend fun record(path: List<LatLon>, speedMps: Double, startMs: Long, name: String): Long {
        val cumulative = DoubleArray(path.size).also { for (i in 1 until path.size) it[i] = it[i - 1] + Geo.distanceM(path[i - 1], path[i]) }
        val points = ArrayList<TrackPoint>()
        var second = 0
        var cursor = 0
        while (true) {
            val d = second * speedMps
            if (d > cumulative.last()) break
            while (cursor < path.size - 2 && cumulative[cursor + 1] < d) cursor++
            val span = cumulative[cursor + 1] - cumulative[cursor]
            val f = if (span > 0) (d - cumulative[cursor]) / span else 0.0
            points.add(
                TrackPoint(
                    timeMs = startMs + second * 1000L,
                    lat = path[cursor].lat + (path[cursor + 1].lat - path[cursor].lat) * f,
                    lon = path[cursor].lon + (path[cursor + 1].lon - path[cursor].lon) * f,
                    altitudeM = 300.0 + d * 0.04, speedMps = speedMps, accuracyM = 4.0,
                )
            )
            second++
        }
        val id = rides.createRide(startMs, name)
        val totals = RideAnalyzer.summarize(points)
        rides.appendPoints(id, 0, points, totals, lastSegment = 0, nowMs = points.last().timeMs)
        rides.finishRide(id, totals, points.last().timeMs)
        return id
    }

    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L

    @Test
    fun `un segment cree depuis une sortie est retrouve dans les autres et classe`() = runBlocking<Unit> {
        val first = record(road(), speedMps = 6.0, startMs = t0, name = "Sortie du matin")
        val faster = record(road(), speedMps = 8.0, startMs = t0 + day, name = "Sortie rapide")
        val elsewhere = record(road(LatLon(43.6, 1.44)), speedMps = 7.0, startMs = t0 + 2 * day, name = "Ailleurs")

        // Le segment : du kilomètre 1 au kilomètre 3 de la première sortie.
        val segmentId = segments.create("  Côte du test ", first, fromM = 1_000.0, toM = 3_000.0)
        assertNotNull(segmentId)
        val segment = segments.segment(segmentId!!)!!
        assertEquals("Côte du test", segment.name)
        assertEquals(2_000.0, segment.distanceM, 15.0)
        // Montée régulière à 4 % : 80 m de dénivelé sur 2 km.
        assertEquals(80.0, segment.ascentM, 8.0)
        assertEquals(4.0, (segment.endAltitudeM!! - segment.startAltitudeM!!) / segment.distanceM * 100, 0.4)

        analysis.matchSegment(segmentId)
        val allRides = rides.finishedRides()
        var efforts = segments.observeEffortsOfSegment(segmentId).first()
        assertEquals(setOf(first, faster), efforts.map { it.rideId }.toSet())
        val rows = effortRows(segment, efforts, allRides)
        assertEquals(listOf("Sortie rapide", "Sortie du matin"), rows.map { it.rideName })
        assertEquals(listOf(1, 2), rows.map { it.rank })
        assertEquals(2_000.0 / 8.0, rows[0].elapsedMs / 1000.0, 2.5)
        assertEquals(2_000.0 / 6.0, rows[1].elapsedMs / 1000.0, 2.5)
        assertEquals(8.0, rows[0].speedMps, 0.15)

        // Liste des segments et détail d'une sortie.
        val list = segmentRows(segments.observeSegments().first(), segments.observeEfforts().first()).single()
        assertEquals(2, list.efforts)
        assertEquals(rows[0].elapsedMs, list.bestMs)
        val ids = allRides.map { it.id }.toSet()
        val inFirst = rideSegmentRows(first, listOf(segment), efforts, ids).single()
        assertEquals(2, inFirst.rank)
        assertEquals(2, inFirst.total)
        assertTrue(rideSegmentRows(elsewhere, listOf(segment), efforts, ids).isEmpty())

        // Une nouvelle sortie, plus lente, analysée à l'enregistrement : troisième temps.
        val slower = record(road(), speedMps = 5.0, startMs = t0 + 3 * day, name = "Balade")
        analysis.analyze(slower)
        efforts = segments.observeEffortsOfSegment(segmentId).first()
        assertEquals(3, efforts.size)
        assertEquals(slower, efforts.maxBy { it.elapsedMs }.rideId)
        // Analyser deux fois la même sortie ne compte pas deux passages.
        analysis.analyze(slower)
        assertEquals(3, segments.observeEffortsOfSegment(segmentId).first().size)

        // Supprimer une sortie retire ses temps ; supprimer le segment retire le reste, sans toucher aux sorties.
        rides.delete(faster)
        assertEquals(setOf(first, slower), segments.observeEffortsOfSegment(segmentId).first().map { it.rideId }.toSet())
        segments.delete(segmentId)
        assertTrue(segments.observeEfforts().first().isEmpty())
        assertEquals(3, rides.finishedRides().size)
    }

    @Test
    fun `meilleurs temps calcules une fois par sortie`() = runBlocking<Unit> {
        val slow = record(road(), speedMps = 6.0, startMs = t0, name = "Lente")
        val fast = record(road(), speedMps = 8.0, startMs = t0 + day, name = "Rapide")
        // Aucune des deux n'a encore été analysée (comme des sorties enregistrées avant cette version).
        assertEquals(0, rides.ride(slow)!!.analysisVersion)
        analysis.analyzePending()
        assertEquals(RideAnalysis.VERSION, rides.ride(slow)!!.analysisVersion)
        assertEquals(RideAnalysis.VERSION, rides.ride(fast)!!.analysisVersion)
        assertTrue(db.rides().needingAnalysis(RideAnalysis.VERSION).isEmpty())

        // 6,5 km de sortie : un meilleur temps sur 5 km chacune, rien sur 10 km.
        val efforts = db.bestEfforts().observeAll().first()
        assertEquals(listOf(5_000, 5_000), efforts.map { it.distanceM })
        assertEquals(listOf(fast, slow), efforts.map { it.rideId })
        assertEquals(5_000.0 / 8.0, efforts[0].durationMs / 1000.0, 1.5)

        val ids = setOf(slow, fast)
        assertEquals(listOf("5 km" to "10:25"), rideBestRows(fast, efforts, ids))
        assertTrue(rideBestRows(slow, efforts, ids).isEmpty())
        // Seule sortie sur la distance : pas de « record » à fêter.
        assertTrue(rideBestRows(fast, efforts, setOf(fast)).isEmpty())

        // Une sortie en cours n'est pas analysée.
        val running = rides.createRide(t0 + 5 * day, "En cours")
        analysis.analyze(running)
        assertEquals(0, rides.ride(running)!!.analysisVersion)
        assertEquals(RideEntity.STATUS_RECORDING, rides.ride(running)!!.status)
    }

    @Test
    fun `un morceau trop court ne fait pas un segment`() = runBlocking<Unit> {
        val ride = record(road(), speedMps = 6.0, startMs = t0, name = "Sortie")
        assertNull(segments.create("Trop court", ride, fromM = 1_000.0, toM = 1_120.0))
        assertNull(segments.create("À l'envers", ride, fromM = 3_000.0, toM = 1_000.0))
        assertNull(segments.create("Sortie inconnue", 999, fromM = 0.0, toM = 2_000.0))
        assertTrue(segments.observeSegments().first().isEmpty())
    }
}
