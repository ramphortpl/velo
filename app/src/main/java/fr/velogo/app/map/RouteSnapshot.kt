package fr.velogo.app.map

import android.content.Context
import android.graphics.PointF
import fr.velogo.app.nav.PosterMap
import fr.velogo.app.nav.RoutePoster
import fr.velogo.core.geo.BoundingBox
import fr.velogo.core.geo.LatLon
import fr.velogo.core.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * Photographie le fond de carte autour d'un itinéraire, pour l'affiche à partager.
 *
 * La carte est dessinée à part, hors de l'écran, à la taille de l'affiche : celle qu'on regarde ne bouge pas.
 * Elle passe par le même client réseau que la carte de l'écran, donc la carte hors ligne fonctionne aussi, sans
 * réseau. Seules les tuiles de cette vue sont demandées, comme pour un affichage ordinaire.
 */
object RouteSnapshot {
    /** Deux fois la densité de base : textes et routes de la carte restent lisibles sur l'affiche. */
    private const val PIXEL_RATIO = 2f
    private const val TIMEOUT_MS = 12_000L
    /** Emprise minimale, pour ne pas cadrer à l'excès un itinéraire de quelques mètres (environ 300 m). */
    private const val MIN_SPAN_DEG = 0.003

    /** Le fond [spec] cadré sur [route], ou null s'il n'a pas pu être dessiné à temps (pas de réseau, par exemple). */
    suspend fun capture(context: Context, spec: MapStyleSpec, route: Route): PosterMap? = withContext(Dispatchers.Main.immediate) {
        val box = BoundingBox.of(route.points)?.let(::widened) ?: return@withContext null
        runCatching {
            MapInit.ensure(context)
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<PosterMap?> { continuation ->
                    val options = MapSnapshotter.Options((RoutePoster.MAP_WIDTH / PIXEL_RATIO).toInt(), (RoutePoster.MAP_HEIGHT / PIXEL_RATIO).toInt())
                        .withPixelRatio(PIXEL_RATIO)
                        .withLogo(false)
                        .withAttribution(false)
                        .withRegion(LatLngBounds.from(box.maxLat, box.maxLon, box.minLat, box.minLon))
                        .withPadding(
                            (RoutePoster.MAP_PADDING / PIXEL_RATIO).toInt(), (RoutePoster.MAP_PADDING / PIXEL_RATIO).toInt(),
                            (RoutePoster.MAP_PADDING / PIXEL_RATIO).toInt(), (RoutePoster.MAP_PADDING_BOTTOM / PIXEL_RATIO).toInt(),
                        )
                    val uri = spec.uri
                    if (uri != null) options.withStyle(uri) else options.withStyleJson(spec.json.orEmpty())
                    val snapshotter = MapSnapshotter(context.applicationContext, options)
                    continuation.invokeOnCancellation { runCatching { snapshotter.cancel() } }
                    snapshotter.start(
                        { snapshot -> if (continuation.isActive) continuation.resume(runCatching { picture(snapshot, box) }.getOrNull()) },
                        { if (continuation.isActive) continuation.resume(null) },
                    )
                }
            }
        }.getOrNull()
    }

    /**
     * L'image et sa projection. La carte est vue de dessus, nord en haut : deux points repérés dans l'image
     * suffisent à y placer tous les autres (projection de Mercator), sans plus dépendre de la bibliothèque.
     */
    private fun picture(snapshot: MapSnapshot, box: BoundingBox): PosterMap {
        val southWest = snapshot.pixelForLatLng(LatLng(box.minLat, box.minLon))
        val northEast = snapshot.pixelForLatLng(LatLng(box.maxLat, box.maxLon))
        val kx = (northEast.x - southWest.x) / (box.maxLon - box.minLon)
        val ky = (northEast.y - southWest.y) / (mercator(box.maxLat) - mercator(box.minLat))
        val y0 = mercator(box.minLat)
        return PosterMap(snapshot.bitmap) { p: LatLon ->
            PointF((southWest.x + (p.lon - box.minLon) * kx).toFloat(), (southWest.y + (mercator(p.lat) - y0) * ky).toFloat())
        }
    }

    private fun mercator(latDeg: Double): Double = ln(tan(PI / 4 + Math.toRadians(latDeg) / 2))

    private fun widened(box: BoundingBox): BoundingBox {
        val growLat = ((MIN_SPAN_DEG - (box.maxLat - box.minLat)) / 2).coerceAtLeast(0.0)
        val growLon = ((MIN_SPAN_DEG - (box.maxLon - box.minLon)) / 2).coerceAtLeast(0.0)
        return if (growLat == 0.0 && growLon == 0.0) box
        else BoundingBox(box.minLat - growLat, box.minLon - growLon, box.maxLat + growLat, box.maxLon + growLon)
    }
}
