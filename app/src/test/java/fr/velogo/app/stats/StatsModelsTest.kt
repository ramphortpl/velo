package fr.velogo.app.stats

import fr.velogo.app.data.ImportResult
import fr.velogo.app.data.db.BestEffortEntity
import fr.velogo.app.data.db.RideEntity
import fr.velogo.app.data.db.SegmentEntity
import fr.velogo.app.ui.segments.facts
import fr.velogo.app.ui.segments.gradePct
import fr.velogo.app.ui.segments.rankLabel
import fr.velogo.app.ui.segments.segmentFacts
import fr.velogo.app.ui.segments.suggestedSegmentName
import fr.velogo.app.ui.settings.importMessage
import fr.velogo.app.ui.stats.buildStats
import fr.velogo.app.ui.stats.comparison
import fr.velogo.app.ui.stats.periodLabel
import fr.velogo.app.ui.stats.periodTitle
import fr.velogo.core.stats.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ce que disent les écrans de statistiques et de segments, à partir de sorties connues. */
class StatsModelsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 11, 21)

    private fun ride(id: Long, month: Int, day: Int, km: Double, minutes: Int, ascent: Double = 200.0) = RideEntity(
        id = id, name = "Sortie $id", startTimeMs = ZonedDateTime.of(2026, month, day, 9, 0, 0, 0, paris).toInstant().toEpochMilli(),
        status = RideEntity.STATUS_FINISHED, distanceM = km * 1000, movingMs = minutes * 60_000L, ascentM = ascent, maxSpeedMps = 12.0, maxAltitudeM = 400.0,
    )

    private val rides = listOf(
        ride(1, 11, 21, km = 42.0, minutes = 110, ascent = 650.0),
        ride(2, 11, 17, km = 18.0, minutes = 50),
        ride(3, 11, 10, km = 30.0, minutes = 80),
        ride(4, 9, 5, km = 75.0, minutes = 200, ascent = 1_100.0),
    )

    @Test
    fun `la semaine en cours est choisie par defaut et on peut remonter le temps`() {
        val state = buildStats(rides, emptyList(), Period.WEEK, back = 0, today = today, zone = paris)
        assertTrue(state.loaded)
        // De début septembre à aujourd'hui : douze semaines affichées (le maximum), la dernière est choisie.
        assertEquals(12, state.buckets.size)
        assertEquals(11, state.selected)
        assertEquals(60_000.0, state.current!!.distanceM, 1e-6)
        assertEquals(30_000.0, state.previous!!.distanceM, 1e-6)
        assertEquals("Semaine du 16 au 22 novembre 2026", periodTitle(Period.WEEK, state.current!!.start))
        assertEquals("16 nov.", periodLabel(Period.WEEK, state.current!!.start))
        assertEquals("30,0 km de plus que la semaine précédente", comparison(Period.WEEK, state.current, state.previous))

        val before = buildStats(rides, emptyList(), Period.WEEK, back = 1, today = today, zone = paris)
        assertEquals(10, before.selected)
        assertEquals(30_000.0, before.current!!.distanceM, 1e-6)
        // Reculer plus loin que le graphique ne sort pas de la liste.
        assertEquals(0, buildStats(rides, emptyList(), Period.WEEK, back = 99, today = today, zone = paris).selected)
    }

    @Test
    fun `mois annees et totaux`() {
        val months = buildStats(rides, emptyList(), Period.MONTH, back = 0, today = today, zone = paris)
        assertEquals(6, months.buckets.size)
        assertEquals("Novembre 2026", periodTitle(Period.MONTH, months.current!!.start))
        assertEquals(90_000.0, months.current!!.distanceM, 1e-6)
        assertEquals("90,0 km de plus que le mois précédent", comparison(Period.MONTH, months.current, months.previous))
        val years = buildStats(rides, emptyList(), Period.YEAR, back = 0, today = today, zone = paris)
        assertEquals(1, years.buckets.size)
        assertEquals("Année 2026", periodTitle(Period.YEAR, years.current!!.start))
        assertNull(comparison(Period.YEAR, years.current, years.previous))
        assertEquals(165_000.0, years.allTime!!.distanceM, 1e-6)
        assertEquals(4, years.allTime!!.rides)
        assertEquals("Plus longue sortie", years.records.first().label)
        assertEquals("75,00 km", years.records.first().value)
        assertEquals(4L, years.records.first().rideId)
    }

    @Test
    fun `comparaison avec la periode precedente`() {
        val weeks = buildStats(rides, emptyList(), Period.WEEK, back = 2, today = today, zone = paris)
        // Semaine du 2 novembre : rien, la précédente non plus.
        assertNull(comparison(Period.WEEK, weeks.current, weeks.previous))
        val after = buildStats(rides, emptyList(), Period.WEEK, back = 1, today = today, zone = paris)
        assertEquals("30,0 km de plus que la semaine précédente", comparison(Period.WEEK, after.current, after.previous))
        val less = buildStats(rides.drop(1), emptyList(), Period.WEEK, back = 0, today = today, zone = paris)
        assertEquals("12,0 km de moins que la semaine précédente", comparison(Period.WEEK, less.current, less.previous))
    }

    @Test
    fun `meilleurs temps toutes sorties confondues`() {
        val efforts = listOf(
            BestEffortEntity(1, 10_000, 1_500_000, 1L), BestEffortEntity(2, 10_000, 1_420_000, 2L),
            BestEffortEntity(1, 5_000, 700_000, 3L),
            // Une sortie supprimée depuis : son temps ne compte plus.
            BestEffortEntity(99, 5_000, 100_000, 4L),
        )
        val bests = buildStats(rides, efforts, Period.WEEK, 0, today, paris).bests
        assertEquals(listOf("5 km", "10 km"), bests.map { it.label })
        assertEquals(listOf("11:40", "23:40"), bests.map { it.value })
        assertEquals(listOf(1L, 2L), bests.map { it.rideId })
        assertTrue(bests[1].detail.startsWith("25,4 km/h"))
    }

    @Test
    fun `sans sortie l'ecran le dit`() {
        val state = buildStats(emptyList(), emptyList(), Period.WEEK, 0, today, paris)
        assertTrue(state.loaded && state.isEmpty)
        assertTrue(state.records.isEmpty() && state.bests.isEmpty())
    }

    @Test
    fun `mesures d'un segment et rang d'un passage`() {
        assertEquals("3,5 km · ↗ 212 m · 6,1 %", segmentFacts(3_500.0, 212.0, 6.06))
        // Faux plat ou boucle : ni dénivelé ni pente à annoncer.
        assertEquals("1,8 km", segmentFacts(1_800.0, 3.0, 0.2))
        assertEquals("850 m · ↗ 40 m · −4,7 %", segmentFacts(850.0, 40.0, -4.7))
        val segment = SegmentEntity(
            name = "Côte", createdAtMs = 0, distanceM = 2_000.0, ascentM = 150.0, startAltitudeM = 300.0, endAltitudeM = 440.0,
            points = "", minLat = 0.0, minLon = 0.0, maxLat = 0.0, maxLon = 0.0,
        )
        assertEquals(7.0, segment.gradePct()!!, 1e-9)
        assertEquals("2,0 km · ↗ 150 m · 7,0 %", segment.facts())
        assertNull(segment.copy(startAltitudeM = null).gradePct())
        assertEquals("Record", rankLabel(1))
        assertEquals("3e temps", rankLabel(3))
        // Le nom proposé dit ce qu'est le morceau choisi.
        assertEquals("Côte de 3,5 km", suggestedSegmentName(3_500.0, 6.1))
        assertEquals("Descente de 850 m", suggestedSegmentName(850.0, -4.7))
        assertEquals("Segment de 1,8 km", suggestedSegmentName(1_800.0, 0.4))
        assertEquals("Segment de 12,0 km", suggestedSegmentName(12_000.0, null))
    }

    @Test
    fun `resultat d'un import en une phrase`() {
        assertEquals("3 sorties ajoutées à l'historique.", importMessage(ImportResult(imported = listOf(1, 2, 3))))
        assertEquals("1 sortie ajoutée à l'historique ; 2 y étaient déjà.", importMessage(ImportResult(imported = listOf(1), duplicates = 2)))
        assertEquals("Aucune sortie ajoutée ; 1 y était déjà.", importMessage(ImportResult(duplicates = 1)))
        assertEquals("Aucune sortie ajoutée ; 1 fichier n'était pas une sortie lisible.", importMessage(ImportResult(unreadable = 1)))
    }
}
