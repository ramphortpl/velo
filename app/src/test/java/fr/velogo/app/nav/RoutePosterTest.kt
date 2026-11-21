package fr.velogo.app.nav

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import fr.velogo.core.geo.Geo
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Instruction
import fr.velogo.core.nav.Maneuver
import fr.velogo.core.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * L'affiche d'un itinéraire, dessinée pour de bon (moteur graphique d'Android fourni par Robolectric) : les
 * images sont rangées avec les captures d'écran, pour les regarder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RoutePosterTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /** Une boucle de 43 km autour d'un lac, avec deux bosses. */
    private fun loop(): Route {
        val center = LatLon(45.86, 6.17)
        val points = ArrayList<LatLon>()
        val elevations = ArrayList<Double>()
        val count = 400
        for (i in 0..count) {
            val angle = 2 * PI * i / count
            val radius = 6_200.0 + 1_500.0 * sin(3 * angle) + 700.0 * cos(5 * angle)
            points.add(Geo.offset(center.lat, center.lon, Math.toDegrees(angle), radius))
            elevations.add(450.0 + 260.0 * sin(angle).coerceAtLeast(0.0) * sin(angle) + 120.0 * sin(2.3 * angle + 1.0).coerceAtLeast(0.0))
        }
        val distances = Route.cumulativeDistances(points)
        return Route(
            points, elevations.toDoubleArray(), distances, durationS = 2.0 * 3600 + 10 * 60, ascentM = 684.0, descentM = 684.0,
            instructions = listOf(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)),
            waypoints = listOf(points.first(), points[140], points.last()),
        )
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/outputs/roborazzi").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Nombre de pixels de la couleur du tracé (à peu près) dans une zone. */
    private fun routePixels(bitmap: Bitmap, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom step 3) for (x in 0 until bitmap.width step 3) {
            val c = bitmap.getPixel(x, y)
            if (Color.blue(c) > 170 && Color.red(c) < 60 && Color.green(c) in 70..130) count++
        }
        return count
    }

    @Test
    fun `affiche sans fond de carte`() {
        val poster = RoutePoster.render(context, loop(), PosterText("Tour du lac d'Annecy", "Dimanche 9 h, devant la mairie de Sevrier"), map = null)
        save(poster, "affiche_sans_carte.png")

        assertEquals(RoutePoster.WIDTH, poster.width)
        assertEquals(RoutePoster.HEIGHT, poster.height)
        // Le tracé est dessiné dans la zone carte, et nulle part dans le cartouche du bas.
        assertTrue(routePixels(poster, 0, RoutePoster.MAP_HEIGHT - 60) > 400)
        assertEquals(0, routePixels(poster, RoutePoster.MAP_HEIGHT + 20, RoutePoster.HEIGHT))
    }

    @Test
    fun `affiche avec fond de carte`() {
        val route = loop()
        // Un faux fond de carte : un aplat et un « lac », avec sa propre façon de placer les points.
        val map = Bitmap.createBitmap(RoutePoster.MAP_WIDTH, RoutePoster.MAP_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(map).apply {
            drawColor(0xFFF2EFE9.toInt())
            drawOval(330f, 250f, 760f, 560f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFAAD3DF.toInt() })
        }
        val center = LatLon(45.86, 6.17)
        val pixelsPerDegree = 3_300f
        val project = { p: LatLon ->
            PointF(
                RoutePoster.MAP_WIDTH / 2f + ((p.lon - center.lon) * cos(Math.toRadians(center.lat))).toFloat() * pixelsPerDegree,
                (RoutePoster.MAP_HEIGHT - 40) / 2f - (p.lat - center.lat).toFloat() * pixelsPerDegree,
            )
        }
        val text = PosterText(
            title = "Sortie du club : le grand tour par le col de la Forclaz et Talloires",
            subtitle = "Vers Annecy, quai de la Tournette",
            attribution = "© OpenStreetMap · OpenFreeMap",
        )
        val poster = RoutePoster.render(context, route, text, PosterMap(map, project))
        save(poster, "affiche.png")

        assertTrue(routePixels(poster, 0, RoutePoster.MAP_HEIGHT - 60) > 400)
        // Le fond fourni est bien là : la couleur du lac au centre de la carte.
        val lake = poster.getPixel(540, 400)
        assertTrue(Color.blue(lake) > 200 && Color.red(lake) < 190)
    }

    @Test
    fun `legende qui accompagne l'affiche`() {
        val route = loop()
        assertEquals(
            "Tour du lac — Dimanche 9 h\n46,1 km · ↗ 684 m · environ 2 h 10\nItinéraire préparé avec VeloGo",
            RouteShare.caption(PosterText("  Tour du lac ", " Dimanche 9 h "), route),
        )
        // Sans titre ni deuxième ligne : un nom par défaut, et rien d'autre.
        assertEquals(
            "Sortie vélo\n46,1 km · ↗ 684 m · environ 2 h 10\nItinéraire préparé avec VeloGo",
            RouteShare.caption(PosterText(""), route),
        )
    }

    @Test
    fun `l'affiche partagee est un fichier JPEG du dossier de partage`() = kotlinx.coroutines.runBlocking<Unit> {
        val route = loop()
        val text = PosterText("Tour du lac d'Annecy : la boucle !", "Dimanche 9 h")
        val poster = RoutePoster.render(context, route, text, map = null)
        val chooser = RouteShare.posterIntent(context, poster, text, route)

        val send = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)!!
        assertEquals(android.content.Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertTrue(send.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.startsWith("Tour du lac d'Annecy : la boucle ! — Dimanche 9 h"))
        assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = send.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
        // Le nom du fichier vient du titre, débarrassé de ce qu'un nom de fichier n'accepte pas.
        assertEquals("VeloGo_Tour du lac dAnnecy  la boucle.jpg", uri.lastPathSegment)
        val file = File(File(context.cacheDir, "exports"), uri.lastPathSegment!!)
        assertTrue(file.length() > 20_000)
        // Signature d'un fichier JPEG.
        val head = file.inputStream().use { input -> ByteArray(3).also { input.read(it) } }
        assertEquals(listOf(0xFF, 0xD8, 0xFF), head.map { it.toInt() and 0xFF })
    }

    @Test
    fun `itineraire court et sans altitude`() {
        val a = LatLon(42.3, 9.15)
        val points = (0..40).map { Geo.offset(a.lat, a.lon, 40.0, it * 20.0) }
        val distances = Route.cumulativeDistances(points)
        val route = Route(points, null, distances, 180.0, 0.0, 0.0, listOf(Instruction(points.size - 1, distances.last(), Maneuver.ARRIVE)), listOf(points.first(), points.last()))
        val poster = RoutePoster.render(context, route, PosterText(title = ""), map = null)
        save(poster, "affiche_courte.png")
        assertTrue(routePixels(poster, 0, RoutePoster.MAP_HEIGHT - 60) > 100)
    }
}
