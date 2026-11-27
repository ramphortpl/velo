package fr.velogo.app.map

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import fr.velogo.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ce qui décide si les cartes de Mapbox sont proposées, et le fusible qui les met de côté quand elles font
 * planter l'appli. Le kit de Mapbox lui-même ne tourne pas ici : il lui faut un téléphone et un jeton.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapboxSupportTest {

    private val gl2 = 0x00020000
    private val gl3 = 0x00030000
    private val gl32 = 0x00030002

    private fun prefs(name: String): SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun SharedPreferences.unfinished() = getInt(MapboxFuse.KEY_UNFINISHED, 0)

    @Test
    fun `sans jeton dans l'appli, pas de carte mapbox`() {
        assertFalse(MapboxSupport.isAvailable("", gl3))
        assertFalse(MapboxSupport.isAvailable("   ", gl3))
    }

    @Test
    fun `seul un jeton public est accepte`() {
        // Des jetons d'exemple, volontairement sans la forme des vrais (seul leur début compte ici).
        assertTrue(MapboxSupport.isAvailable("pk.jeton-public-d-exemple", gl3))
        // Un jeton secret ne doit jamais se retrouver dans une appli : même présent, il ne sert pas.
        assertFalse(MapboxSupport.isAvailable("sk.jeton-secret-d-exemple", gl3))
        assertFalse(MapboxSupport.isAvailable("jeton-sans-prefixe", gl3))
    }

    @Test
    fun `il faut opengl es 3`() {
        assertFalse(MapboxSupport.isAvailable("pk.abc", 0))
        assertFalse(MapboxSupport.isAvailable("pk.abc", gl2))
        assertTrue(MapboxSupport.isAvailable("pk.abc", gl3))
        assertTrue(MapboxSupport.isAvailable("pk.abc", gl32))
    }

    @Test
    fun `les tests tournent sans jeton`() {
        // Le jeton ne vient que du secret de la fabrication : s'il apparaissait ici, c'est qu'il serait dans le dépôt.
        assertEquals("", BuildConfig.MAPBOX_TOKEN)
        assertFalse(MapboxSupport.isAvailable(ApplicationProvider.getApplicationContext<Context>()))
    }

    @Test
    fun `un affichage qui se termine normalement ne laisse pas de trace`() {
        val prefs = prefs("fusible-normal")
        val fuse = MapboxFuse(prefs)
        assertFalse(fuse.blown)
        fuse.displayStarted()
        assertEquals(1, prefs.unfinished())
        fuse.displayEnded()
        assertEquals(0, prefs.unfinished())
        assertFalse(MapboxFuse(prefs).blown)
    }

    @Test
    fun `un seul plantage ne fait pas sauter le fusible`() {
        val prefs = prefs("fusible-un-plantage")
        // Premier lancement : la carte s'affiche, puis l'appli s'arrête sans prévenir (arrêt forcé, batterie vide…).
        MapboxFuse(prefs).displayStarted()
        // Deuxième lancement : on réessaie, et cette fois tout se passe bien.
        val second = MapboxFuse(prefs)
        assertFalse(second.blown)
        second.displayStarted()
        second.displayEnded()
        assertFalse(MapboxFuse(prefs).blown)
    }

    @Test
    fun `deux plantages d'affilee mettent mapbox de cote, jusqu'a ce qu'on le rechoisisse`() {
        val prefs = prefs("fusible-deux-plantages")
        MapboxFuse(prefs).displayStarted()
        val second = MapboxFuse(prefs)
        assertFalse(second.blown)
        second.displayStarted()
        // Troisième lancement : l'appli ne retente pas.
        val third = MapboxFuse(prefs)
        assertTrue(third.blown)
        // Rien de ce qui suit ne le réarme par accident : ni un lancement de plus, ni une fin d'affichage.
        third.displayEnded()
        assertTrue(MapboxFuse(prefs).blown)
        // L'utilisateur rechoisit un fond Mapbox.
        val fourth = MapboxFuse(prefs)
        fourth.rearm()
        assertFalse(fourth.blown)
        assertFalse(MapboxFuse(prefs).blown)
    }

    @Test
    fun `le fusible ne saute pas pendant le lancement qui a vu le premier plantage`() {
        val prefs = prefs("fusible-meme-lancement")
        MapboxFuse(prefs).displayStarted()
        val second = MapboxFuse(prefs)
        // La carte s'affiche, est masquée, s'affiche de nouveau : toujours le même lancement, toujours permis.
        second.displayStarted()
        assertFalse(second.blown)
        second.displayEnded()
        second.displayStarted()
        assertFalse(second.blown)
        assertEquals(1, prefs.unfinished())
    }

    @Test
    fun `une carte qui ne peut pas etre creee fait sauter le fusible tout de suite`() {
        val prefs = prefs("fusible-creation")
        val fuse = MapboxFuse(prefs)
        fuse.displayStarted()
        fuse.trip()
        assertTrue(fuse.blown)
        assertTrue(MapboxFuse(prefs).blown)
    }
}
