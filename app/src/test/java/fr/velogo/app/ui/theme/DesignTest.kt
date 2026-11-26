package fr.velogo.app.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Les briques du thème : ombre du verre, coins continus, contrastes des couleurs du logo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesignTest {

    @Test
    fun `ombre du verre - les parts decroissent et leur somme vaut un`() {
        for (rings in listOf(1, 4, 10, 16)) {
            val shares = glassShadowShares(rings)
            assertEquals(rings, shares.size)
            assertEquals("somme pour $rings anneaux", 1f, shares.sum(), 1e-4f)
            assertTrue(shares.all { it > 0f })
            // L'anneau le plus proche du bord porte le plus d'opacité, le plus lointain le moins.
            for (i in 1 until rings) assertTrue("anneau $i sur $rings", shares[i] < shares[i - 1])
        }
    }

    @Test
    fun `ombre du verre - l opacite cumulee s eteint doucement`() {
        val shares = glassShadowShares(10)
        // À mi-portée il reste un quart de l'opacité (décroissance au carré), et presque rien au dernier anneau.
        assertEquals(0.25f, shares.drop(5).sum(), 1e-4f)
        assertTrue(shares.last() < 0.02f)
    }

    @Test
    fun `coins continus - le trace tient dans son rectangle et le remplit`() {
        val size = Size(400f, 240f)
        val bounds = continuousRoundRect(size, 40f).getBounds()
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(0f, bounds.top, 0.01f)
        assertEquals(400f, bounds.right, 0.01f)
        assertEquals(240f, bounds.bottom, 0.01f)
    }

    @Test
    fun `coins continus - le rayon est ramene a ce que la taille permet`() {
        val size = Size(300f, 100f)
        assertEquals(100f / 2f / CONTINUOUS_REACH, continuousMaxRadius(size), 1e-3f)
        // Un rayon démesuré ne fait pas déborder le tracé ni ne le retourne.
        val bounds = continuousRoundRect(size, 5_000f).getBounds()
        assertEquals(300f, bounds.width, 0.01f)
        assertEquals(100f, bounds.height, 0.01f)
        // Sans rayon, un simple rectangle.
        val plain = continuousRoundRect(size, 0f).getBounds()
        assertEquals(300f, plain.width, 0.01f)
        assertEquals(100f, plain.height, 0.01f)
    }

    @Test
    fun `coins continus - la courbe commence plus tot que celle d un quart de cercle`() {
        val r = 400f
        val side = 2000
        fun region(path: android.graphics.Path) = android.graphics.Region().apply { setPath(path, android.graphics.Region(0, 0, side, side)) }
        val continuous = region(continuousRoundRect(Size(side.toFloat(), side.toFloat()), r).asAndroidPathCompat())
        val circular = region(
            android.graphics.Path().apply { addRoundRect(0f, 0f, side.toFloat(), side.toFloat(), r, r, android.graphics.Path.Direction.CW) }
        )
        // À 0,9 rayon du sommet le long du bord, le quart de cercle a à peine quitté le bord (2 px) ; le coin
        // continu, qui a commencé à tourner dès 1,53 rayon, en est déjà à 7 px. Un point à 4 px du bord est donc
        // dans le rectangle arrondi classique et hors du nôtre.
        assertTrue(circular.contains(360, 4))
        assertTrue(!continuous.contains(360, 4))
        assertTrue(continuous.contains(360, 10))
        // Au milieu du coin les deux courbes se confondent (à 0,29 rayon de chaque bord) : même rondeur d'ensemble.
        assertTrue(continuous.contains(121, 121) && circular.contains(121, 121))
        assertTrue(!continuous.contains(112, 112) && !circular.contains(112, 112))
        // Et le sommet reste dehors.
        assertTrue(!continuous.contains(2, 2))
    }

    private fun androidx.compose.ui.graphics.Path.asAndroidPathCompat(): android.graphics.Path =
        (this as androidx.compose.ui.graphics.AndroidPath).internalPath

    @Test
    fun `couleurs du logo - les textes restent lisibles sur leurs fonds`() {
        // Vert foncé du logo sur vert vif (bouton « Démarrer », option choisie) : au moins 4,5.
        assertTrue(contrast(LightGlass.accent.content, LightGlass.accent.fillTop) >= 4.5)
        assertTrue(contrast(LightGlass.accent.content, LightGlass.accent.fillBottom) >= 4.5)
        // Blanc sur vert foncé (bandeau de guidage, boutons pleins).
        assertTrue(contrast(Color.White, Brand.Deep) >= 7.0)
        assertTrue(contrast(LightGlass.deep.content, LightGlass.deep.fillTop) >= 4.5)
        // Texte courant sur le fond des écrans et sur les cartes, en clair comme en sombre.
        assertTrue(contrast(LightColors.onSurface, LightColors.surface) >= 7.0)
        assertTrue(contrast(LightColors.onSurfaceVariant, LightColors.surfaceContainerLow) >= 4.5)
        assertTrue(contrast(DarkColors.onSurface, DarkColors.surface) >= 7.0)
        assertTrue(contrast(DarkColors.onSurfaceVariant, DarkColors.surfaceContainerLow) >= 4.5)
        // Rose du logo en gros caractères sur blanc (« Terminer ») : au moins 3, le seuil des grands textes.
        assertTrue(contrast(Brand.Pink, Color.White) >= 3.0)
        // Texte vert foncé des boutons et des liens sur les cartes blanches.
        assertTrue(contrast(LightColors.primary, LightColors.surfaceContainerLow) >= 7.0)
        assertTrue(contrast(DarkColors.primary, DarkColors.surfaceContainerLow) >= 7.0)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `plein soleil - ni transparence ni ombre`() {
        for (style in listOf(SunlightGlass.regular, SunlightGlass.panel, SunlightGlass.accent, SunlightGlass.deep)) {
            assertEquals(1f, style.fillTop.alpha, 0f)
            assertEquals(1f, style.fillBottom.alpha, 0f)
            assertEquals(0f, style.shadow.alpha, 0f)
            assertTrue(abs(contrast(style.content, style.fillTop)) >= 7.0)
        }
    }
}
