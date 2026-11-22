package fr.velogo.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionArbiterTest {
    private val s = 1_000L
    private val min = 60 * s

    @Test
    fun `rien avant le debut d une ecoute`() {
        val arbiter = PositionArbiter()
        assertFalse(arbiter.acceptApproximate(0, 20.0))
        assertNull(arbiter.searchingSinceMs())
        assertFalse(arbiter.shouldHelp(10 * min))
    }

    @Test
    fun `au demarrage le GPS a quelques secondes d avance`() {
        val arbiter = PositionArbiter()
        arbiter.start(100 * s)
        // La position approchée est demandée tout de suite, pour être prête si le GPS tarde…
        assertTrue(arbiter.needsAssist(100 * s))
        // … mais elle n'est montrée qu'une fois le délai laissé au GPS écoulé.
        assertFalse(arbiter.acceptApproximate(101 * s, 20.0))
        assertFalse(arbiter.acceptApproximate(103 * s + 999, 20.0))
        assertTrue(arbiter.acceptApproximate(104 * s, 20.0))
    }

    @Test
    fun `GPS pret tout de suite - la position approchee ne s affiche jamais`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        arbiter.onPrecise(2 * s)
        assertFalse(arbiter.needsAssist(2 * s))
        for (t in 3..600) {
            arbiter.onPrecise(t * s)
            assertFalse("à $t s", arbiter.acceptApproximate(t * s + 500, 15.0))
            assertFalse("à $t s", arbiter.needsAssist(t * s + 500))
        }
    }

    @Test
    fun `sans GPS toute position approchee est bonne a prendre meme grossiere`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        assertTrue(arbiter.acceptApproximate(5 * s, 30.0))
        assertTrue(arbiter.acceptApproximate(9 * s, 1_800.0))
        // Mais pas n'importe quoi : précision inconnue, absurde ou au-delà de dix kilomètres.
        assertFalse(arbiter.acceptApproximate(9 * s, null))
        assertFalse(arbiter.acceptApproximate(9 * s, Double.NaN))
        assertFalse(arbiter.acceptApproximate(9 * s, -1.0))
        assertFalse(arbiter.acceptApproximate(9 * s, 25_000.0))
    }

    @Test
    fun `GPS perdu en route - seule une position fine prend le relais et pas tout de suite`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        for (t in 1..120) arbiter.onPrecise(t * s)
        // Passage sous les arbres : dix secondes de silence ne changent rien.
        assertFalse(arbiter.needsAssist(130 * s))
        assertFalse(arbiter.acceptApproximate(130 * s, 20.0))
        // Trente secondes : on écoute la position approchée, et on la montre si elle est fine…
        assertTrue(arbiter.needsAssist(150 * s))
        assertTrue(arbiter.acceptApproximate(150 * s, 40.0))
        // … mais pas celle déduite d'une antenne à un kilomètre, qui ferait sauter la carte.
        assertFalse(arbiter.acceptApproximate(150 * s, 900.0))
        // Le GPS revient : la position approchée est de nouveau ignorée.
        arbiter.onPrecise(160 * s)
        assertFalse(arbiter.needsAssist(161 * s))
        assertFalse(arbiter.acceptApproximate(161 * s, 20.0))
    }

    @Test
    fun `GPS muet depuis longtemps - une position grossiere vaut mieux qu une position perimee`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        arbiter.onPrecise(10 * s)
        assertFalse(arbiter.acceptApproximate(4 * min, 900.0))
        assertTrue(arbiter.acceptApproximate(5 * min + 10 * s, 900.0))
    }

    @Test
    fun `retour dans l appli - le GPS garde son avance a chaque nouvelle ecoute`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        arbiter.onPrecise(5 * s)
        // L'appli passe en arrière-plan une minute, puis revient : nouvelle écoute.
        arbiter.start(65 * s)
        assertTrue(arbiter.needsAssist(65 * s))
        assertFalse(arbiter.acceptApproximate(66 * s, 25.0))
        assertTrue(arbiter.acceptApproximate(69 * s + 1, 25.0))
        // Une position grossière, elle, attend : le GPS a parlé il y a une minute.
        assertFalse(arbiter.acceptApproximate(70 * s, 1_200.0))
    }

    @Test
    fun `depuis quand le GPS cherche`() {
        val arbiter = PositionArbiter()
        arbiter.start(10 * s)
        assertEquals(10 * s, arbiter.searchingSinceMs())
        arbiter.onPrecise(40 * s)
        assertEquals(40 * s, arbiter.searchingSinceMs())
        // Nouvelle écoute plus tard : le compteur repart du début de cette écoute.
        arbiter.start(500 * s)
        assertEquals(500 * s, arbiter.searchingSinceMs())
    }

    @Test
    fun `aide au GPS - une fois apres vingt secondes de recherche puis toutes les dix minutes`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        assertFalse(arbiter.shouldHelp(5 * s))
        assertFalse(arbiter.shouldHelp(19 * s))
        assertTrue(arbiter.shouldHelp(20 * s))
        assertFalse(arbiter.shouldHelp(21 * s))
        assertFalse(arbiter.shouldHelp(9 * min))
        assertTrue(arbiter.shouldHelp(10 * min + 20 * s))
        assertFalse(arbiter.shouldHelp(10 * min + 21 * s))
    }

    @Test
    fun `pas d aide tant que le GPS repond`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        for (t in 1..300) {
            arbiter.onPrecise(t * s)
            assertFalse("à $t s", arbiter.shouldHelp(t * s + 900))
        }
        // Il se tait : l'aide part vingt secondes plus tard.
        assertFalse(arbiter.shouldHelp(310 * s))
        assertTrue(arbiter.shouldHelp(320 * s))
    }

    @Test
    fun `aide demandee a la main - pas redemandee aussitot`() {
        val arbiter = PositionArbiter()
        arbiter.start(0)
        arbiter.onHelped(0)
        assertFalse(arbiter.shouldHelp(30 * s))
        assertFalse(arbiter.shouldHelp(9 * min))
        assertTrue(arbiter.shouldHelp(10 * min))
    }

    @Test
    fun `une horloge qui hesite ne casse rien`() {
        val arbiter = PositionArbiter()
        arbiter.start(100 * s)
        arbiter.onPrecise(110 * s)
        // Un instant antérieur à la dernière position : on fait comme si elle venait d'arriver.
        assertFalse(arbiter.needsAssist(105 * s))
        assertFalse(arbiter.acceptApproximate(105 * s, 20.0))
    }
}
