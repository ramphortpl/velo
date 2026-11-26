package fr.velogo.app.ui.nav

import fr.velogo.app.search.Place
import fr.velogo.core.geo.LatLon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Recherche d'adresse : une demande qui échoue est retentée une fois avant d'annoncer « Recherche impossible ». */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchRetryTest {
    private val gare = listOf(Place("Gare de Corte", "20250 Corte", LatLon(42.3008, 9.1557)))

    @Test
    fun `le service repond du premier coup - une seule demande et pas d attente`() = runTest {
        var calls = 0
        val found = searchWithRetry("gare de corte", { calls++; gare })
        assertEquals(gare, found)
        assertEquals(1, calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `premiere demande en echec - la seconde aboutit apres une courte attente`() = runTest {
        var calls = 0
        val found = searchWithRetry("gare de corte", { if (++calls == 1) null else gare })
        assertEquals(gare, found)
        assertEquals(2, calls)
        assertEquals(1_500L, currentTime)
    }

    @Test
    fun `deux echecs - la recherche est dite impossible et on ne s acharne pas`() = runTest {
        var calls = 0
        assertNull(searchWithRetry("gare de corte", { calls++; null }))
        assertEquals(2, calls)
    }

    @Test
    fun `aucun resultat n est pas un echec - pas de seconde demande`() = runTest {
        var calls = 0
        val found = searchWithRetry("zzzz", { calls++; emptyList() })
        assertEquals(emptyList<Place>(), found)
        assertEquals(1, calls)
    }
}
