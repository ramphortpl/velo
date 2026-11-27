package fr.velogo.app.nav

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fr.velogo.app.AppContainer
import fr.velogo.app.ui.nav.StartReturnText
import fr.velogo.app.ui.nav.startReturnText
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.BikeType
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import fr.velogo.core.nav.RouteShape
import fr.velogo.core.nav.StartReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Préparer une boucle, et arrêter au départ un trajet qui y repasse puis continue.
 *
 * Origine (22/11/2026) : un trajet annoncé 40,1 km pour une sortie de 31 km. L'arrivée était restée à Bouffémont
 * et le retour à la maison avait été ajouté comme simple étape : l'itinéraire faisait la boucle, repassait par la
 * maison puis repartait vers Bouffémont par les routes du début, invisibles sur la carte sous le premier passage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlannerLoopTest {
    private val navigation = AppContainer(ApplicationProvider.getApplicationContext<Application>()).navigation
    private val state get() = navigation.planner.value

    private val home = LatLon(48.997, 2.218)
    private val chauvry = LatLon(49.0497, 2.2674)
    private val andilly = LatLon(49.0046, 2.3025)
    private val bouffemont = LatLon(49.0395, 2.2925)

    @Test
    fun `une boucle n a pas d autre arrivee que son depart et il lui faut une etape`() {
        navigation.setEnd(bouffemont, "Bouffémont")
        assertTrue(state.canCompute)

        navigation.setLoop(true)
        assertTrue(state.loop)
        assertNull("l'arrivée choisie avant ne compte plus", state.end)
        assertFalse("une boucle sans étape ne mène nulle part", state.canCompute)

        navigation.addVia(chauvry, "Chauvry")
        assertTrue(state.canCompute)

        // Choisir une arrivée, c'est renoncer à la boucle ; la retirer aussi.
        navigation.setEnd(bouffemont, "Bouffémont")
        assertFalse(state.loop)
        assertEquals("Bouffémont", state.end?.label)
        navigation.setLoop(true)
        navigation.removeEnd()
        assertFalse(state.loop)
        assertFalse(state.canCompute)
    }

    @Test
    fun `les etapes d une boucle se reordonnent et la boucle se parcourt dans l autre sens`() {
        navigation.setLoop(true)
        navigation.addVia(chauvry, "Chauvry")
        navigation.addVia(bouffemont, "Bouffémont")
        navigation.addVia(andilly, "Andilly")
        assertEquals(listOf("Chauvry", "Bouffémont", "Andilly"), state.vias.map { it.label })

        navigation.moveViaUp(2)
        assertEquals(listOf("Chauvry", "Andilly", "Bouffémont"), state.vias.map { it.label })
        // La première étape ne peut pas monter plus haut, et un rang qui n'existe pas ne change rien.
        navigation.moveViaUp(0)
        navigation.moveViaUp(7)
        assertEquals(listOf("Chauvry", "Andilly", "Bouffémont"), state.vias.map { it.label })

        // Départ « ma position » : une boucle s'inverse quand même (un aller simple, lui, ne le peut pas).
        assertNull(state.start)
        navigation.reverse()
        assertEquals(listOf("Bouffémont", "Andilly", "Chauvry"), state.vias.map { it.label })
        assertTrue(state.loop)
    }

    /** Un carré de 2 km de côté autour de la maison, puis de nouveau le premier côté sur [tailM] mètres. */
    private fun loopThenTail(tailM: Int): Route {
        val points = ArrayList<LatLon>()
        points.add(home)
        fun go(bearing: Double, meters: Int) = repeat(meters / 50) { points.add(Geo.offset(points.last().lat, points.last().lon, bearing, 50.0)) }
        go(0.0, 2_000); go(90.0, 2_000); go(180.0, 2_000); go(270.0, 2_000); go(0.0, tailM)
        val distances = Route.cumulativeDistances(points)
        return Route(
            points = points, elevations = null, distances = distances, durationS = distances.last() / 5.0, ascentM = 200.0, descentM = 200.0,
            instructions = listOf(Instruction(40, distances[40], Maneuver.RIGHT), Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)),
            waypoints = listOf(home, points[40], home, points.last()),
        )
    }

    @Test
    fun `un trajet enregistre qui repasse par son depart s arrete a ce retour`() {
        val saved = loopThenTail(3_000)
        runBlocking { navigation.showSaved("Entraînement 1", saved, BikeType.HYBRID, savedId = 12) }
        assertEquals(12L, state.savedId)
        assertEquals(11_000.0, state.selectedOption!!.route.distanceM, 30.0)
        assertNotNull(RouteShape.returnToStart(state.selectedOption!!.route))

        val shortened = navigation.stopAtStart()

        assertNotNull(shortened)
        assertEquals(8_000.0, shortened!!.distanceM, 30.0)
        // Le trajet affiché est le trajet raccourci, toujours sous son nom et rattaché à son enregistrement.
        assertEquals(shortened, state.selectedOption!!.route)
        assertEquals("Entraînement 1", state.importedName)
        assertEquals(12L, state.savedId)
        assertTrue(state.open)
        assertNull(RouteShape.returnToStart(shortened))
        // Une seconde demande n'a plus rien à couper.
        assertNull(navigation.stopAtStart())
        assertEquals(shortened, state.selectedOption!!.route)
    }

    @Test
    fun `un trajet qui ne repasse pas par son depart n est pas touche`() {
        val loop = loopThenTail(0)
        runBlocking { navigation.showSaved("Boucle", loop, BikeType.ROAD, savedId = 3) }
        assertNull(navigation.stopAtStart())
        assertEquals(loop, state.selectedOption!!.route)
    }

    @Test
    fun `preparer autre chose detache le trajet enregistre`() {
        runBlocking { navigation.showSaved("Entraînement 1", loopThenTail(3_000), BikeType.HYBRID, savedId = 12) }
        navigation.setEnd(bouffemont, "Bouffémont")
        assertNull(state.savedId)
        assertNull(state.importedName)
        assertTrue(state.options.isEmpty())
    }

    @Test
    fun `ce que le panneau dit d un trajet qui repasse par son depart`() {
        val back = StartReturn(index = 900, distanceM = 29_300.0, remainingM = 10_800.0)
        assertEquals(
            StartReturnText("Repasse par le départ après 29,3 km, puis continue 10,8 km.", "Arrêter au départ"),
            startReturnText(back, compact = true),
        )
        val full = startReturnText(back, compact = false)
        assertEquals("Arrêter au départ (29,3 km)", full.button)
        assertTrue(full.message, full.message.startsWith("Cet itinéraire revient à son point de départ après 29,3 km, puis continue encore 10,8 km jusqu'à l'arrivée."))
        assertTrue(full.message, full.message.contains("les deux passages se superposent"))
    }
}
