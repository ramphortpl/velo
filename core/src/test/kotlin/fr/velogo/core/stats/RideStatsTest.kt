package fr.velogo.core.stats

import fr.velogo.core.geo.Geo
import fr.velogo.core.ride.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RideStatsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 11, 21) // un samedi

    private fun at(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

    private fun ride(id: Long, timeMs: Long, km: Double, minutes: Int, ascent: Double = 100.0, maxKmh: Double = 40.0, altitude: Double? = 300.0) =
        RideSummary(id, timeMs, km * 1000, minutes * 60_000L, ascent, maxKmh / 3.6, altitude)

    private val rides = listOf(
        ride(1, at(2026, 11, 21), km = 42.0, minutes = 110, ascent = 650.0),
        ride(2, at(2026, 11, 16, 0, 30), km = 18.0, minutes = 50),            // lundi, juste après minuit
        ride(3, at(2026, 11, 15, 23, 30), km = 25.0, minutes = 70),           // dimanche soir : la semaine d'avant
        ride(4, at(2026, 10, 31), km = 61.5, minutes = 170, ascent = 1_240.0, maxKmh = 71.0, altitude = 1_326.0),
        ride(5, at(2025, 7, 14), km = 102.0, minutes = 300, ascent = 900.0),
        ride(6, at(2026, 11, 3), km = 6.0, minutes = 12, ascent = 5.0, maxKmh = 45.0),  // court et rapide
    )

    @Test
    fun `totaux par semaine du lundi au dimanche`() {
        val weeks = RideStats.buckets(rides, Period.WEEK, today, paris, count = 4)
        assertEquals(listOf("2026-10-26", "2026-11-02", "2026-11-09", "2026-11-16"), weeks.map { it.start.toString() })
        assertEquals(listOf(1, 1, 1, 2), weeks.map { it.rides })
        assertEquals(60_000.0, weeks[3].distanceM, 1e-6)
        assertEquals(160 * 60_000L, weeks[3].movingMs)
        assertEquals(750.0, weeks[3].ascentM, 1e-6)
        assertEquals(42_000.0, weeks[3].longestM, 1e-6)
        assertEquals(60_000.0 / (160 * 60), weeks[3].avgSpeedMps, 1e-9)
        assertEquals(25_000.0, weeks[2].distanceM, 1e-6)
    }

    @Test
    fun `totaux par mois et par annee avec les periodes vides`() {
        val months = RideStats.buckets(rides, Period.MONTH, today, paris, count = 3)
        assertEquals(listOf("2026-09-01", "2026-10-01", "2026-11-01"), months.map { it.start.toString() })
        assertEquals(listOf(0, 1, 4), months.map { it.rides })
        assertEquals(0.0, months[0].distanceM, 0.0)
        assertEquals(0.0, months[0].avgSpeedMps, 0.0)
        assertEquals(91_000.0, months[2].distanceM, 1e-6)

        val years = RideStats.buckets(rides, Period.YEAR, today, paris, count = 2)
        assertEquals(listOf(1, 5), years.map { it.rides })
        assertEquals(102_000.0, years[0].distanceM, 1e-6)
    }

    @Test
    fun `nombre de periodes a montrer`() {
        // De juillet 2025 à novembre 2026 : 17 mois, 2 ans ; les semaines sont plafonnées.
        assertEquals(17, RideStats.span(rides, Period.MONTH, today, paris, min = 6, max = 24))
        assertEquals(2, RideStats.span(rides, Period.YEAR, today, paris, min = 1, max = 10))
        assertEquals(12, RideStats.span(rides, Period.WEEK, today, paris, min = 8, max = 12))
        assertEquals(8, RideStats.span(emptyList(), Period.WEEK, today, paris, min = 8, max = 12))
        assertEquals(6, RideStats.span(rides.take(1), Period.MONTH, today, paris, min = 6, max = 24))
    }

    @Test
    fun `totaux depuis le debut`() {
        val all = RideStats.allTime(rides, paris)
        assertEquals(6, all.rides)
        assertEquals(254_500.0, all.distanceM, 1e-6)
        assertEquals("2025-07-14", all.start.toString())
        assertEquals(0, RideStats.allTime(emptyList(), paris).rides)
    }

    @Test
    fun `records personnels`() {
        val records = RideStats.records(rides).associateBy { it.kind }
        assertEquals(5L, records.getValue(RecordKind.DISTANCE).rideId)
        assertEquals(102_000.0, records.getValue(RecordKind.DISTANCE).value, 1e-6)
        assertEquals(4L, records.getValue(RecordKind.ASCENT).rideId)
        assertEquals(5L, records.getValue(RecordKind.DURATION).rideId)
        assertEquals(4L, records.getValue(RecordKind.MAX_SPEED).rideId)
        assertEquals(1_326.0, records.getValue(RecordKind.ALTITUDE).value, 1e-6)
        // La sortie de 6 km à 30 km/h de moyenne ne compte pas : trop courte. C'est la sortie 1 (22,9 km/h).
        assertEquals(1L, records.getValue(RecordKind.AVG_SPEED).rideId)
        assertEquals(42_000.0 / (110 * 60), records.getValue(RecordKind.AVG_SPEED).value, 1e-9)

        assertTrue(RideStats.records(emptyList()).isEmpty())
        // Sans altitude connue, pas de record d'altitude ; sans sortie assez longue, pas de record de moyenne.
        val small = RideStats.records(listOf(ride(9, at(2026, 1, 1), km = 4.0, minutes = 15, altitude = null))).associateBy { it.kind }
        assertNull(small[RecordKind.ALTITUDE])
        assertNull(small[RecordKind.AVG_SPEED])
        assertEquals(9L, small.getValue(RecordKind.DISTANCE).rideId)
    }

    // --- Meilleurs temps ----------------------------------------------------------------------------------------

    /** Trace rectiligne : une liste de (mètres par seconde, secondes), une position par seconde. */
    private fun track(vararg legs: Pair<Double, Int>, pauseAfterLeg: Int? = null, pauseS: Int = 0): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var lat = 45.0
        val lon = 5.0
        var time = 1_800_000_000_000L
        var segment = 0
        out.add(TrackPoint(time, lat, lon))
        legs.forEachIndexed { index, (speed, seconds) ->
            repeat(seconds) {
                lat = Geo.offset(lat, lon, 0.0, speed).lat
                time += 1_000
                out.add(TrackPoint(time, lat, lon, segment = segment))
            }
            if (index == pauseAfterLeg) {
                time += pauseS * 1_000L
                segment++
            }
        }
        return out
    }

    @Test
    fun `meilleurs temps sur 5 et 10 km`() {
        // 4 km à 6 m/s, puis 5,4 km à 9 m/s, puis 6 km à 6 m/s : 15,4 km en tout.
        val efforts = BestEfforts.compute(track(6.0 to 667, 9.0 to 600, 6.0 to 1000)).associateBy { it.distanceM }
        assertEquals(setOf(5_000, 10_000), efforts.keys)
        // Les 5 km les plus rapides sont entièrement dans la partie à 9 m/s.
        assertEquals(5_000.0 / 9.0, efforts.getValue(5_000).durationMs / 1000.0, 1.5)
        assertTrue(efforts.getValue(5_000).startDistanceM in 3_990.0..4_420.0)
        // Les 10 km les plus rapides : les 5,4 km rapides et 4,6 km à 6 m/s.
        assertEquals(5_400.0 / 9.0 + 4_600.0 / 6.0, efforts.getValue(10_000).durationMs / 1000.0, 2.5)
    }

    @Test
    fun `un arret compte dans le temps`() {
        // 6 km à 6 m/s avec trois minutes d'arrêt au milieu : le meilleur 5 km contient forcément l'arrêt.
        val efforts = BestEfforts.compute(track(6.0 to 500, 6.0 to 500, pauseAfterLeg = 0, pauseS = 180))
        assertEquals(5_000.0 / 6.0 + 180.0, efforts.single().durationMs / 1000.0, 1.5)
    }

    @Test
    fun `pas de temps sur une distance qu'on n'a pas faite ni sur une trace abimee`() {
        assertTrue(BestEfforts.compute(track(6.0 to 600)).isEmpty())
        assertTrue(BestEfforts.compute(emptyList()).isEmpty())
        // Un saut du GPS de 6 km en une seconde : le « record » qui en sortirait n'est pas retenu.
        assertTrue(BestEfforts.compute(track(6.0 to 100, 6_000.0 to 1, 6.0 to 100), intArrayOf(5_000)).isEmpty())
    }
}
