package fr.velogo.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import fr.velogo.core.geo.LatLon
import fr.velogo.core.geo.Polyline
import kotlin.math.cos
import kotlin.math.max

/**
 * Vignette d'une trace : la forme du parcours dessinée directement, sans fond de carte.
 * Instantané et sans réseau, donc parfait pour une liste qui défile.
 */
@Composable
fun TrackThumbnail(
    encodedPreview: String?,
    color: Color,
    modifier: Modifier = Modifier,
    startColor: Color = color,
) {
    val points = remember(encodedPreview) { encodedPreview?.let { runCatching { Polyline.decode(it) }.getOrNull() }.orEmpty() }
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val projected = project(points, size.width, size.height, 6.dp.toPx())
        val path = Path()
        projected.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(startColor, radius = 4.dp.toPx(), center = projected.first())
        drawCircle(Color.White, radius = 1.8.dp.toPx(), center = projected.first())
    }
}

/** Projette des points géographiques dans un rectangle en conservant les proportions du terrain. */
internal fun project(points: List<LatLon>, width: Float, height: Float, padding: Float): List<Offset> {
    var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
    for (p in points) {
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
    }
    val k = cos(Math.toRadians((minLat + maxLat) / 2))
    val spanX = max((maxLon - minLon) * k, 1e-9)
    val spanY = max(maxLat - minLat, 1e-9)
    val scale = minOf((width - 2 * padding) / spanX, (height - 2 * padding) / spanY).toFloat()
    val offsetX = (width - (spanX * scale).toFloat()) / 2f
    val offsetY = (height - (spanY * scale).toFloat()) / 2f
    return points.map { p ->
        Offset(
            x = offsetX + ((p.lon - minLon) * k * scale).toFloat(),
            y = offsetY + ((maxLat - p.lat) * scale).toFloat(),
        )
    }
}
