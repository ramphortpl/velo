package fr.velogo.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import fr.velogo.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Le logo de l'appli, tel qu'Android le charge : l'icône adaptative et ses trois couches, l'icône de notification,
 * le logo détouré. Les images sont rangées avec les captures d'écran, pour les regarder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class LogoTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/outputs/roborazzi").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(drawable: Drawable, size: Int, background: Int = Color.TRANSPARENT): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    private fun Bitmap.alphaAt(x: Float, y: Float) = Color.alpha(getPixel((x * width).toInt(), (y * height).toInt()))

    @Test
    fun `l'icone de lancement est adaptative, avec ses trois couches`() {
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        assertTrue("icône adaptative attendue, reçu ${icon?.javaClass?.simpleName}", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull("couche des icônes « à thème » d'Android 13", icon.monochrome)

        // Le décor couvre tout le carré : quelle que soit la forme découpée par le téléphone, pas de trou.
        val background = render(icon.background, 432)
        for ((x, y) in listOf(0.01f to 0.01f, 0.99f to 0.01f, 0.01f to 0.99f, 0.99f to 0.99f, 0.5f to 0.5f)) {
            assertEquals("décor opaque en ($x, $y)", 255, background.alphaAt(x, y))
        }

        // Le logo tient dans le rond que toutes les formes d'icône laissent voir (66 dp sur 108) ; autour, rien.
        val foreground = render(icon.foreground, 432)
        val safeRadius = 33f / 108f
        var ink = 0
        var outside = 0
        for (y in 0 until foreground.height) for (x in 0 until foreground.width) {
            if (Color.alpha(foreground.getPixel(x, y)) < 128) continue
            ink++
            val dx = (x + 0.5f) / foreground.width - 0.5f
            val dy = (y + 0.5f) / foreground.height - 0.5f
            if (dx * dx + dy * dy > safeRadius * safeRadius) outside++
        }
        // À cette taille, le logo couvre un peu moins de dix mille points.
        assertTrue("le logo est dessiné ($ink points)", ink in 7_000..13_000)
        assertEquals("points du logo hors de la zone toujours visible", 0, outside)

        // La silhouette a la même forme que le logo.
        val monochrome = render(icon.monochrome!!, 432)
        var same = 0
        var different = 0
        for (y in 0 until 432 step 3) for (x in 0 until 432 step 3) {
            val a = Color.alpha(foreground.getPixel(x, y)) >= 128
            val b = Color.alpha(monochrome.getPixel(x, y)) >= 128
            if (a == b) same++ else different++
        }
        assertTrue("silhouette et logo superposables ($different écarts sur ${same + different})", different < (same + different) / 200)

        // Pour l'œil : l'icône telle qu'un Samsung (carré très arrondi) et un Pixel (rond) la découpent.
        val sheet = createBitmap(3 * 320, 320)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFFEFF3F1.toInt())
        val masks = listOf<(Path, Float) -> Unit>(
            { p, s -> p.addRoundRect(0f, 0f, s, s, s * 0.30f, s * 0.30f, Path.Direction.CW) },
            { p, s -> p.addCircle(s / 2, s / 2, s / 2, Path.Direction.CW) },
            { p, s -> p.addRoundRect(0f, 0f, s, s, s * 0.12f, s * 0.12f, Path.Direction.CW) },
        )
        masks.forEachIndexed { i, mask ->
            val size = 288f
            canvas.save()
            canvas.translate(16f + i * 320f, 16f)
            canvas.clipPath(Path().also { mask(it, size) })
            // Le téléphone ne montre que les deux tiers du milieu des couches (72 dp sur 108).
            val layer = (size * 1.5f).toInt()
            val offset = -(layer - size) / 2
            canvas.translate(offset, offset)
            icon.background.setBounds(0, 0, layer, layer); icon.background.draw(canvas)
            icon.foreground.setBounds(0, 0, layer, layer); icon.foreground.draw(canvas)
            canvas.restore()
        }
        save(sheet, "icone_lancement.png")
    }

    @Test
    fun `l'icone de notification est une silhouette blanche`() {
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_stat_velogo)!!
        assertEquals("24 dp", icon.intrinsicWidth, icon.intrinsicHeight)
        val bitmap = render(icon, 72)
        var visible = 0
        for (y in 0 until 72) for (x in 0 until 72) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) < 128) continue
            visible++
            // Android ne garde que l'opacité de cette icône : tout ce qui se voit doit être blanc.
            assertTrue("point de couleur en ($x, $y)", Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
        }
        assertTrue("silhouette trop maigre ou trop pleine : $visible points sur 5184", visible in 500..3200)
        // Les coins sont vides : l'icône ne remplit pas son carré.
        for ((x, y) in listOf(0.02f to 0.02f, 0.98f to 0.02f, 0.02f to 0.98f, 0.98f to 0.98f)) assertEquals(0, bitmap.alphaAt(x, y))
        save(render(icon, 96, 0xFF3C4043.toInt()), "icone_notification.png")
    }

    @Test
    fun `le logo detoure a un fond transparent et ses trois couleurs`() {
        val logo = (ContextCompat.getDrawable(context, R.drawable.logo_velogo) as BitmapDrawable).bitmap
        assertTrue("logo plus large que haut", logo.width > logo.height * 3 / 2)
        for ((x, y) in listOf(0.01f to 0.01f, 0.99f to 0.01f, 0.01f to 0.99f, 0.99f to 0.99f)) assertEquals("coin transparent", 0, logo.alphaAt(x, y))
        var darkGreen = 0
        var brightGreen = 0
        var pink = 0
        for (y in 0 until logo.height step 2) for (x in 0 until logo.width step 2) {
            val p = logo.getPixel(x, y)
            if (Color.alpha(p) < 250) continue
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            when {
                r < 40 && g in 50..110 && b < 90 -> darkGreen++
                r < 40 && g > 170 && b in 110..190 -> brightGreen++
                r > 200 && g < 40 && b in 90..170 -> pink++
            }
        }
        assertTrue("vert foncé des lettres : $darkGreen", darkGreen > 5_000)
        assertTrue("vert vif des roues : $brightGreen", brightGreen > 2_000)
        assertTrue("rose du trait : $pink", pink > 800)
    }
}
