package fr.velogo.routing

import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RoutePreference
import fr.velogo.core.nav.RouteShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Calculs d'itinéraires réels autour de Corte, avec un petit morceau des vraies données de BRouter
 * (src/test/resources/dalles, fabriqué par cartes/outils/dalles.py) et le moteur embarqué.
 */
class BRouterPlannerTest {

    companion object {
        private val segments = File("src/test/resources/dalles")
        private lateinit var planner: BRouterPlanner

        // Corte : la gare, la citadelle, la route du sud (ex-N193) et l'entrée de la vallée de la Restonica.
        val GARE = LatLon(42.3003, 9.1576)
        val CITADELLE = LatLon(42.3056, 9.1492)
        val CENTRE = LatLon(42.3060, 9.1500)
        val SUD = LatLon(42.2750, 9.1650)
        val RESTONICA = LatLon(42.2890, 9.1200)

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val profiles = File("build/profils-essai")
            RoutingProfiles.install(profiles)
            planner = BRouterPlanner(profiles)
        }
    }

    private fun route(from: LatLon, to: LatLon, bike: BikeType, preference: RoutePreference = RoutePreference.FAST, via: List<LatLon> = emptyList(), power: Int = 140): Route {
        val outcome = planner.plan(segments, PlanRequest(listOf(from) + via + to, bike, preference, powerW = power))
        if (outcome is PlanOutcome.Failed) throw AssertionError("échec inattendu : ${outcome.reason} ${outcome.detail}")
        return (outcome as PlanOutcome.Found).route
    }

    @Test
    fun `un itineraire en ville a une trace un profil et des consignes coherents`() {
        val r = route(GARE, CITADELLE, BikeType.ROAD)
        assertEquals(2280.0, r.distanceM, 250.0)
        assertTrue("départ à ${Geo.distanceM(r.points.first(), GARE)} m de la gare", Geo.distanceM(r.points.first(), GARE) < 60)
        assertTrue(Geo.distanceM(r.points.last(), CITADELLE) < 60)
        // Corte monte vers sa citadelle : entre 50 et 120 m de dénivelé sur ce trajet.
        assertNotNull(r.elevations)
        assertTrue("dénivelé ${r.ascentM}", r.ascentM in 50.0..120.0)
        assertTrue("altitude de départ ${r.elevations!!.first()}", r.elevations!!.first() in 380.0..460.0)
        // Entre 6 et 20 minutes à 140 W.
        assertTrue("durée ${r.durationS}", r.durationS in 360.0..1200.0)

        val instructions = r.instructions
        assertEquals(Maneuver.ARRIVE, instructions.last().maneuver)
        assertEquals(r.distanceM, instructions.last().distanceM, 0.001)
        assertTrue("au moins trois consignes", instructions.size >= 4)
        assertTrue("consignes dans l'ordre du parcours", instructions.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
        assertTrue(instructions.all { it.index in r.points.indices && kotlin.math.abs(r.distances[it.index] - it.distanceM) < 0.001 })
        // Le giratoire en sortie de gare est annoncé avec son numéro de sortie.
        val roundabout = instructions.first { it.maneuver == Maneuver.ROUNDABOUT }
        assertTrue(roundabout.exit in 1..5)

        val mix = r.mix!!
        assertEquals(r.distanceM, mix.totalM, r.distanceM * 0.01)
    }

    @Test
    fun `l'itineraire sur evite le grand axe que prend l'itineraire rapide`() {
        val fast = route(CENTRE, SUD, BikeType.HYBRID, RoutePreference.FAST)
        val safe = route(CENTRE, SUD, BikeType.HYBRID, RoutePreference.SAFE)
        assertTrue("le rapide emprunte la grande route (${fast.mix!!.busyM} m)", fast.mix!!.busyM > 1500)
        assertTrue("le sûr l'évite (${safe.mix!!.busyM} m)", safe.mix!!.busyM < 300)
        assertFalse(fast.isSameAs(safe))
        // Le détour reste raisonnable.
        assertTrue(safe.distanceM < fast.distanceM * 1.3)
    }

    @Test
    fun `le VTT passe par les chemins et le velo de route reste sur la route`() {
        val road = route(CENTRE, SUD, BikeType.ROAD)
        val mtb = route(CENTRE, SUD, BikeType.MTB)
        assertTrue("VTT : ${mtb.mix!!.pathM} m de chemins", mtb.mix!!.pathM > mtb.distanceM * 0.5)
        assertTrue("VTT : ${mtb.mix!!.unpavedM} m non revêtus", mtb.mix!!.unpavedM > mtb.distanceM * 0.5)
        assertTrue("route : ${road.mix!!.pathM} m de chemins", road.mix!!.pathM < road.distanceM * 0.15)
        // En VTT, « peu de dénivelé » ramène vers la route : nettement moins de montée.
        val flat = route(CENTRE, SUD, BikeType.MTB, RoutePreference.FLAT)
        assertTrue("${flat.ascentM} m contre ${mtb.ascentM} m", flat.ascentM < mtb.ascentM * 0.75)
    }

    @Test
    fun `une etape oblige l'itineraire a y passer`() {
        val direct = route(GARE, RESTONICA, BikeType.ROAD)
        val via = route(GARE, RESTONICA, BikeType.ROAD, via = listOf(CITADELLE))
        assertTrue(via.distanceTo(CITADELLE) < 60)
        assertTrue(direct.distanceTo(CITADELLE) > 150)
        assertTrue(via.distanceM > direct.distanceM)
        assertEquals(3, via.waypoints.size)
    }

    @Test
    fun `une boucle part et revient au meme endroit en passant par ses etapes`() {
        // Gare → entrée de la Restonica → route du sud → gare : le moteur accepte un départ et une arrivée confondus.
        val loop = route(GARE, GARE, BikeType.ROAD, via = listOf(RESTONICA, SUD))
        assertTrue("départ à ${Geo.distanceM(loop.points.first(), GARE)} m de la gare", Geo.distanceM(loop.points.first(), GARE) < 60)
        assertTrue("arrivée à ${Geo.distanceM(loop.points.last(), GARE)} m de la gare", Geo.distanceM(loop.points.last(), GARE) < 60)
        assertTrue(RouteShape.isLoop(loop))
        assertNull("une boucle qui s'arrête à son retour ne « continue » pas", RouteShape.returnToStart(loop))
        // Les deux étapes sont posées à l'écart des routes, comme un point placé au doigt : le moteur les rattache
        // à la voie la plus proche, et l'itinéraire passe à quelques centaines de mètres d'elles.
        assertTrue("à ${loop.distanceTo(RESTONICA)} m de la Restonica", loop.distanceTo(RESTONICA) < 450)
        assertTrue("à ${loop.distanceTo(SUD)} m du point sud", loop.distanceTo(SUD) < 300)
        // Elle fait au moins l'aller et le retour jusqu'à son étape la plus lointaine.
        val farthest = route(GARE, SUD, BikeType.ROAD)
        assertTrue("${loop.distanceM} m pour ${farthest.distanceM} m à vol de route", loop.distanceM > 1.8 * farthest.distanceM)
        assertEquals(Maneuver.ARRIVE, loop.instructions.last().maneuver)
        assertEquals(4, loop.waypoints.size)
        // L'ordre des étapes est l'ordre du parcours : la Restonica est atteinte avant la route du sud.
        fun reachedAt(target: LatLon) = loop.distances[loop.points.indices.minBy { Geo.distanceM(loop.points[it], target) }]
        assertTrue(reachedAt(RESTONICA) < reachedAt(SUD))
    }

    /**
     * Le piège du 22/11/2026, en petit : l'arrivée (la citadelle) reste en place et le retour à la gare est ajouté
     * comme simple étape. L'itinéraire fait la boucle, repasse par la gare, puis repart vers la citadelle. Coupé au
     * retour à la gare, il redevient la boucle, à quelques mètres près celle qu'on aurait calculée directement.
     */
    @Test
    fun `un itineraire qui repasse par son depart puis continue se coupe en boucle`() {
        val whole = route(GARE, CITADELLE, BikeType.ROAD, via = listOf(RESTONICA, SUD, GARE))
        val loop = route(GARE, GARE, BikeType.ROAD, via = listOf(RESTONICA, SUD))
        val tail = route(GARE, CITADELLE, BikeType.ROAD)
        assertEquals("boucle + trajet vers l'arrivée", loop.distanceM + tail.distanceM, whole.distanceM, 60.0)
        assertFalse(RouteShape.isLoop(whole))

        val back = RouteShape.returnToStart(whole)
        assertNotNull("le retour au départ en cours de route est reconnu", back)
        assertEquals(loop.distanceM, back!!.distanceM, 60.0)
        assertEquals(tail.distanceM, back.remainingM, 60.0)

        val cut = whole.truncatedAt(back.index)
        assertEquals(loop.distanceM, cut.distanceM, 60.0)
        assertTrue(RouteShape.isLoop(cut))
        assertNull(RouteShape.returnToStart(cut))
        assertTrue(cut.isSameAs(loop))
        // Durée et dénivelé de la boucle coupée : ceux de la boucle calculée, à peu de chose près.
        assertEquals(loop.durationS, cut.durationS, loop.durationS * 0.12)
        assertEquals(loop.ascentM, cut.ascentM, 25.0)
        assertEquals(Maneuver.ARRIVE, cut.instructions.last().maneuver)
        assertEquals(cut.points.size - 1, cut.instructions.last().index)
        // Les étapes de la boucle restent des repères ; la gare, devenue l'arrivée, n'en est plus une.
        assertEquals(4, cut.waypoints.size)
    }

    @Test
    fun `la puissance du cycliste change la duree pas le trace`() {
        val calm = route(CENTRE, SUD, BikeType.ROAD, power = 100)
        val strong = route(CENTRE, SUD, BikeType.ROAD, power = 220)
        assertTrue(calm.isSameAs(strong))
        assertTrue("${calm.durationS} s contre ${strong.durationS} s", calm.durationS > strong.durationS * 1.3)
    }

    @Test
    fun `sans donnees pour la zone l'echec dit quel fichier manque`() {
        val outcome = planner.plan(segments, PlanRequest(listOf(CENTRE, LatLon(42.10, 9.30)), BikeType.ROAD, RoutePreference.FAST))
        assertTrue(outcome is PlanOutcome.Failed)
        outcome as PlanOutcome.Failed
        assertEquals(PlanFailure.MISSING_DATA, outcome.reason)
        assertTrue(outcome.detail, outcome.detail.contains("dalle-E9_N41.rd5"))

        val nothing = planner.plan(File("build/dossier-vide").also { it.mkdirs() }, PlanRequest(listOf(GARE, CITADELLE), BikeType.ROAD, RoutePreference.FAST))
        assertEquals(PlanFailure.MISSING_DATA, (nothing as PlanOutcome.Failed).reason)
    }

    @Test
    fun `une variante relie les memes points sans etre plus courte que le meilleur trace`() {
        val best = route(CENTRE, SUD, BikeType.HYBRID)
        val outcome = planner.plan(segments, PlanRequest(listOf(CENTRE, SUD), BikeType.HYBRID, RoutePreference.FAST, alternative = 1))
        val variant = (outcome as PlanOutcome.Found).route
        assertTrue(Geo.distanceM(variant.points.first(), CENTRE) < 60)
        // Le point d'arrivée demandé est à 240 m de la route la plus proche : l'itinéraire s'arrête là où elle passe.
        assertEquals(0.0, Geo.distanceM(variant.points.last(), best.points.last()), 1.0)
        // Dans un réseau aussi clairsemé, la variante peut presque se confondre avec le meilleur tracé :
        // l'appli écarte alors le doublon (Route.isSameAs).
        assertTrue("${variant.distanceM} m contre ${best.distanceM} m", variant.distanceM in best.distanceM * 0.9..best.distanceM * 1.6)
    }

    @Test
    fun `tous les profils calculent un itineraire`() {
        for (bike in BikeType.entries) for (preference in RoutePreference.entries) {
            val r = route(GARE, CITADELLE, bike, preference)
            assertTrue("$bike $preference : ${r.distanceM} m", r.distanceM in 1200.0..4000.0)
        }
    }

    @Test
    fun `les profils embarques s'installent une seule fois`() {
        val target = File("build/profils-installation").also { it.deleteRecursively() }
        RoutingProfiles.install(target)
        val lookups = File(target, "lookups.dat")
        assertTrue(lookups.length() > 10_000)
        val stamp = lookups.lastModified()
        Thread.sleep(20)
        RoutingProfiles.install(target)
        assertEquals(stamp, lookups.lastModified())
        assertTrue(File(target, "fastbike.brf").isFile && File(target, "trekking.brf").isFile && File(target, "mtb.brf").isFile)
    }

    @Test
    fun `les voies sont rangees dans la bonne famille`() {
        fun kind(vararg tags: Pair<String, String>) = WayClassifier.kind(mapOf(*tags))
        assertEquals(WayKind.CYCLEWAY, kind("highway" to "cycleway"))
        assertEquals(WayKind.CYCLEWAY, kind("highway" to "path", "bicycle" to "designated"))
        assertEquals(WayKind.CYCLEWAY, kind("highway" to "secondary", "cycleway:right" to "track"))
        assertEquals(WayKind.PATH, kind("highway" to "path"))
        assertEquals(WayKind.PATH, kind("highway" to "track", "tracktype" to "grade3"))
        assertEquals(WayKind.BUSY, kind("highway" to "primary"))
        assertEquals(WayKind.ROAD, kind("highway" to "primary", "estimated_traffic_class" to "1"))
        assertEquals(WayKind.ROAD, kind("highway" to "tertiary"))
        assertEquals(WayKind.QUIET, kind("highway" to "residential"))
        assertTrue(WayClassifier.isUnpaved(mapOf("highway" to "track")))
        assertFalse(WayClassifier.isUnpaved(mapOf("highway" to "track", "tracktype" to "grade1")))
        assertFalse(WayClassifier.isUnpaved(mapOf("highway" to "path", "surface" to "asphalt")))
        assertTrue(WayClassifier.isUnpaved(mapOf("highway" to "unclassified", "surface" to "gravel")))
    }
}
