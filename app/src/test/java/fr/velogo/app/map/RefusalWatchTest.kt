package fr.velogo.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quand conclure que Mapbox refuse d'envoyer une carte : assez vite pour ne pas laisser une carte noire, pas au
 * premier refus passager. Aucune carte ici, seulement le compte des refus et l'heure qu'on lui donne.
 */
class RefusalWatchTest {

    private val grace = RefusalWatch.GRACE_MS

    @Test
    fun `le message de refus du kit est reconnu`() {
        // Relevé tel quel dans le journal de l'émulateur (fabrication n° 39, images satellite d'un compte d'essai).
        assertTrue(RefusalWatch.isRefusal("Failed to load tile: HTTP status code 429. Too Many Requests"))
        assertTrue(RefusalWatch.isRefusal("Failed to load style: HTTP status code 401"))
        assertTrue(RefusalWatch.isRefusal("Failed to load source: HTTP status code 403. Forbidden"))
    }

    @Test
    fun `une panne n'est pas un refus`() {
        assertFalse(RefusalWatch.isRefusal(null))
        assertFalse(RefusalWatch.isRefusal(""))
        assertFalse(RefusalWatch.isRefusal("Failed to load tile: HTTP status code 404"))
        assertFalse(RefusalWatch.isRefusal("Failed to load tile: HTTP status code 500"))
        assertFalse(RefusalWatch.isRefusal("Failed to load tile: HTTP status code 4290"))
        assertFalse(RefusalWatch.isRefusal("Failed to load tile: Unable to resolve host \"api.mapbox.com\""))
        assertFalse(RefusalWatch.isRefusal("Couldn't connect to server"))
    }

    @Test
    fun `quatre refus d'affilee puis le delai de grace`() {
        val watch = RefusalWatch()
        assertFalse(watch.isHopeless("satellite", 0))
        assertFalse(watch.refused("satellite", 1_000))
        assertFalse(watch.refused("satellite", 1_001))
        assertFalse(watch.refused("satellite", 1_002))
        assertFalse(watch.isHopeless("satellite", 60_000))
        // Le quatrième atteint le seuil : c'est le moment de programmer la vérification.
        assertTrue(watch.refused("satellite", 1_003))
        assertFalse(watch.isHopeless("satellite", 1_003))
        assertFalse(watch.isHopeless("satellite", 1_003 + grace - 1))
        assertTrue(watch.isHopeless("satellite", 1_003 + grace))
        // Les refus suivants n'annoncent pas un nouveau seuil et ne repoussent pas l'échéance.
        assertFalse(watch.refused("satellite", 1_500))
        assertTrue(watch.isHopeless("satellite", 1_003 + grace))
    }

    @Test
    fun `une tuile arrivee efface les refus`() {
        val watch = RefusalWatch()
        repeat(3) { watch.refused("composite", 100L + it) }
        watch.served("composite")
        // Le compte repart de zéro : trois nouveaux refus ne suffisent pas.
        assertFalse(watch.refused("composite", 200))
        assertFalse(watch.refused("composite", 201))
        assertFalse(watch.refused("composite", 202))
        assertFalse(watch.isHopeless("composite", 60_000))
    }

    @Test
    fun `une salve de refus suivie de tuiles n'est pas un refus du fond`() {
        val watch = RefusalWatch()
        // Les réponses arrivent dans le désordre : quatre refus d'abord (Mapbox limite le débit), puis des tuiles.
        repeat(3) { watch.refused("composite", 0) }
        assertTrue(watch.refused("composite", 0))
        watch.served("composite")
        assertFalse(watch.isHopeless("composite", grace))
        assertFalse(watch.isHopeless("composite", 10 * grace))
    }

    @Test
    fun `un seuil atteint de nouveau repart de sa propre heure`() {
        val watch = RefusalWatch()
        repeat(4) { watch.refused("composite", 0) }
        watch.served("composite")
        // Nouvelle série, deux secondes plus tard : la vérification programmée pour la première ne doit pas conclure.
        repeat(3) { watch.refused("composite", 2_000) }
        assertTrue(watch.refused("composite", 2_000))
        assertFalse(watch.isHopeless("composite", grace + 50))
        assertTrue(watch.isHopeless("composite", 2_000 + grace))
    }

    @Test
    fun `chaque source a son compte`() {
        val watch = RefusalWatch()
        // Le fond satellite d'un compte d'essai : les images sont refusées, les noms et les routes arrivent.
        repeat(3) {
            watch.refused("satellite", 0)
            watch.served("composite")
        }
        assertTrue(watch.refused("satellite", 0))
        watch.served("composite")
        assertTrue(watch.isHopeless("satellite", grace))
        assertFalse(watch.isHopeless("composite", grace))
    }

    @Test
    fun `la description d'une source refusee suffit`() {
        val watch = RefusalWatch()
        // Sans sa description, aucune tuile de la source ne sera demandée : il n'y aura pas d'autre refus à compter.
        assertTrue(watch.refused("satellite", 500, whole = true))
        assertFalse(watch.isHopeless("satellite", 500))
        assertTrue(watch.isHopeless("satellite", 500 + grace))
        // Si elle finit par arriver et que des tuiles suivent, tout est oublié.
        watch.served("satellite")
        assertFalse(watch.isHopeless("satellite", 60_000))
    }

    @Test
    fun `un autre fond repart de rien`() {
        val watch = RefusalWatch()
        repeat(4) { watch.refused("satellite", 0) }
        watch.reset()
        assertFalse(watch.isHopeless("satellite", 60_000))
        assertFalse(watch.refused("satellite", 60_000))
    }
}
