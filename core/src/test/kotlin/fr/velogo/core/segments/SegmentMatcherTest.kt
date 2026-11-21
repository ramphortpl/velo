package fr.velogo.core.segments

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.ride.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SegmentMatcherTest {
    private val t0 = 1_800_000_000_000L
    private val origin = LatLon(45.2, 5.7)

    /** Une route : des lignes droites mises bout à bout (cap en degrés, longueur en mètres), un point tous les 10 m. */
    private fun road(vararg legs: Pair<Double, Double>, start: LatLon = origin): List<LatLon> {
        val out = ArrayList<LatLon>()
        var p = start
        out.add(p)
        for ((bearing, length) in legs) {
            var done = 0.0
            while (done < length - 1e-6) {
                val step = minOf(10.0, length - done)
                p = Geo.offset(p.lat, p.lon, bearing, step)
                out.add(p)
                done += step
            }
        }
        return out
    }

    /** Roule sur [path] à vitesse constante, une position toutes les [intervalS] secondes, avec un bruit GPS de [noiseM]. */
    private fun ride(path: List<LatLon>, speedMps: Double = 6.0, intervalS: Double = 1.0, noiseM: Double = 0.0, startMs: Long = t0, seed: Int = 1): List<TrackPoint> {
        val random = Random(seed)
        val cumulative = DoubleArray(path.size).also { for (i in 1 until path.size) it[i] = it[i - 1] + Geo.distanceM(path[i - 1], path[i]) }
        val out = ArrayList<TrackPoint>()
        var time = 0.0
        var cursor = 0
        while (true) {
            val d = time * speedMps
            if (d > cumulative.last()) break
            while (cursor < path.size - 2 && cumulative[cursor + 1] < d) cursor++
            val span = cumulative[cursor + 1] - cumulative[cursor]
            val f = if (span > 0) (d - cumulative[cursor]) / span else 0.0
            var p = LatLon(path[cursor].lat + (path[cursor + 1].lat - path[cursor].lat) * f, path[cursor].lon + (path[cursor + 1].lon - path[cursor].lon) * f)
            if (noiseM > 0) p = Geo.offset(p.lat, p.lon, random.nextDouble(360.0), random.nextDouble(noiseM))
            out.add(TrackPoint(timeMs = startMs + (time * 1000).toLong(), lat = p.lat, lon = p.lon))
            time += intervalS
        }
        return out
    }

    /** Le bout de [path] compris entre deux distances depuis son début. */
    private fun part(path: List<LatLon>, fromM: Double, toM: Double): List<LatLon> {
        val out = ArrayList<LatLon>()
        var d = 0.0
        for (i in path.indices) {
            if (i > 0) d += Geo.distanceM(path[i - 1], path[i])
            if (d >= fromM - 1e-6 && d <= toM + 1e-6) out.add(path[i])
        }
        return out
    }

    // Une route de 5 km avec quelques virages ; le segment en couvre les kilomètres 1 à 3.
    private val route = road(40.0 to 1200.0, 95.0 to 900.0, 20.0 to 1400.0, 330.0 to 800.0, 60.0 to 700.0)
    private val segment = part(route, 1_000.0, 3_000.0)

    @Test
    fun `un passage chronometre a la seconde pres`() {
        val passes = SegmentMatcher.passes(ride(route, speedMps = 6.0, noiseM = 4.0), segment)
        assertEquals(1, passes.size)
        val pass = passes.single()
        assertEquals(2_000.0 / 6.0, pass.elapsedMs / 1000.0, 2.5)
        assertEquals(1_000.0 / 6.0, (pass.startTimeMs - t0) / 1000.0, 2.0)
        assertTrue(pass.startIndex < pass.endIndex)
    }

    @Test
    fun `dans l'autre sens ce n'est pas le meme segment`() {
        assertTrue(SegmentMatcher.passes(ride(route.reversed()), segment).isEmpty())
    }

    @Test
    fun `aller retour aller deux passages`() {
        val there = ride(route, speedMps = 6.0)
        val back = ride(route.reversed(), speedMps = 7.0, startMs = there.last().timeMs + 1_000)
        val again = ride(route, speedMps = 8.0, startMs = back.last().timeMs + 1_000)
        val passes = SegmentMatcher.passes(there + back + again, segment)
        assertEquals(2, passes.size)
        assertEquals(2_000.0 / 6.0, passes[0].elapsedMs / 1000.0, 2.0)
        assertEquals(2_000.0 / 8.0, passes[1].elapsedMs / 1000.0, 2.0)
        assertTrue(passes[1].startTimeMs > passes[0].endTimeMs)
    }

    @Test
    fun `un detour au milieu annule le passage`() {
        // Au kilomètre 2, un crochet de 300 m sur une route perpendiculaire, puis retour et suite du parcours.
        val before = part(route, 0.0, 2_000.0)
        val corner = before.last()
        val spur = road(170.0 to 300.0, start = corner)
        val after = part(route, 2_000.0, 5_000.0)
        val track = ride(before + spur.drop(1) + spur.reversed().drop(1) + after.drop(1))
        assertTrue(SegmentMatcher.passes(track, segment).isEmpty())
    }

    @Test
    fun `une trace peu dense reste reconnue`() {
        // Une position toutes les dix secondes : soixante mètres entre deux points.
        val passes = SegmentMatcher.passes(ride(route, speedMps = 6.0, intervalS = 10.0, noiseM = 5.0), segment)
        assertEquals(1, passes.size)
        assertEquals(2_000.0 / 6.0, passes.single().elapsedMs / 1000.0, 4.0)
    }

    @Test
    fun `une route parallele n'est pas le segment`() {
        // La même route, décalée de 70 m sur le côté.
        val parallel = route.map { Geo.offset(it.lat, it.lon, 130.0, 70.0) }
        assertTrue(SegmentMatcher.passes(ride(parallel), segment).isEmpty())
    }

    @Test
    fun `il faut passer par le depart et aller jusqu'au bout`() {
        // Sortie commencée au milieu du segment, puis sortie arrêtée avant la fin.
        assertTrue(SegmentMatcher.passes(ride(part(route, 1_500.0, 5_000.0)), segment).isEmpty())
        assertTrue(SegmentMatcher.passes(ride(part(route, 0.0, 2_600.0)), segment).isEmpty())
    }

    @Test
    fun `des lacets serres`() {
        // Cinq lacets de 250 m, espacés de 25 m seulement : la route repasse tout près d'elle-même.
        val legs = ArrayList<Pair<Double, Double>>()
        repeat(5) { i ->
            legs.add((if (i % 2 == 0) 90.0 else 270.0) to 250.0)
            if (i < 4) legs.add(0.0 to 25.0)
        }
        val climb = road(*legs.toTypedArray())
        val approach = road(90.0 to 300.0, start = Geo.offset(origin.lat, origin.lon, 270.0, 300.0))
        val exit = road(0.0 to 300.0, start = climb.last())
        val track = ride(approach + climb.drop(1) + exit.drop(1), speedMps = 3.0, noiseM = 3.0)
        val passes = SegmentMatcher.passes(track, climb)
        assertEquals(1, passes.size)
        val length = 5 * 250.0 + 4 * 25.0
        assertEquals(length / 3.0, passes.single().elapsedMs / 1000.0, 6.0)
    }

    @Test
    fun `chaque tour d'une boucle compte`() {
        // Un circuit carré de 1,2 km ; on y entre 100 m avant le départ et on fait deux tours et demi.
        val lap = road(0.0 to 300.0, 90.0 to 300.0, 180.0 to 300.0, 270.0 to 300.0)
        val approach = road(0.0 to 100.0, start = Geo.offset(origin.lat, origin.lon, 180.0, 100.0))
        val track = ride(approach + lap.drop(1) + lap.drop(1) + part(lap, 0.0, 600.0).drop(1), speedMps = 8.0, noiseM = 3.0)
        val passes = SegmentMatcher.passes(track, lap)
        assertEquals(2, passes.size)
        for (pass in passes) assertEquals(1_200.0 / 8.0, pass.elapsedMs / 1000.0, 3.0)
        assertTrue(abs(passes[1].startTimeMs - passes[0].endTimeMs) < 3_000)
    }

    @Test
    fun `un arret en plein segment compte dans le temps`() {
        val first = ride(part(route, 0.0, 2_000.0), speedMps = 6.0)
        // Deux minutes d'arrêt (pause automatique : aucun point enregistré), puis on repart.
        val second = ride(part(route, 2_000.0, 5_000.0), speedMps = 6.0, startMs = first.last().timeMs + 120_000)
            .map { it.copy(segment = 1) }
        val passes = SegmentMatcher.passes(first + second, segment)
        assertEquals(1, passes.size)
        // (Les deux morceaux de la trace d'essai ne se raccordent qu'à une dizaine de mètres près.)
        assertEquals(2_000.0 / 6.0 + 120.0, passes.single().elapsedMs / 1000.0, 5.0)
    }

    @Test
    fun `segment trop court ou trace vide`() {
        assertTrue(SegmentMatcher.passes(ride(route), part(route, 1_000.0, 1_050.0)).isEmpty())
        assertTrue(SegmentMatcher.passes(emptyList(), segment).isEmpty())
        assertTrue(SegmentMatcher.passes(ride(route), emptyList()).isEmpty())
    }
}
