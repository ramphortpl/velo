package fr.velogo.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Série à tracer : abscisses croissantes [xs], ordonnées [ys] (NaN = valeur manquante). */
class ChartSeries(val xs: FloatArray, val ys: FloatArray) {
    init { require(xs.size == ys.size) }
    val size: Int get() = xs.size
    val isDrawable: Boolean get() = size >= 2 && ys.any { !it.isNaN() }
}

/**
 * Graphique d'un profil (altitude ou vitesse en fonction de la distance).
 * On peut y promener le doigt : [onSelect] reçoit l'indice du point survolé, puis null quand on relâche.
 */
@Composable
fun ProfileChart(
    series: ChartSeries,
    lineColor: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
    formatY: (Float) -> String = { it.toInt().toString() },
    formatX: (Float) -> String = { it.toInt().toString() },
    /** Force l'axe vertical à partir de zéro (vitesse) plutôt que de serrer sur les valeurs (altitude). */
    zeroBased: Boolean = false,
) {
    val measurer = rememberTextMeasurer(cacheSize = 12)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val markerColor = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val select = rememberUpdatedState(onSelect)

    Spacer(
        modifier
            .pointerInput(series) {
                if (!series.isDrawable) return@pointerInput
                val left = LEFT_PAD.dp.toPx()
                fun indexAt(x: Float): Int {
                    val w = size.width - left - RIGHT_PAD.dp.toPx()
                    val fraction = ((x - left) / w).coerceIn(0f, 1f)
                    val target = series.xs.first() + fraction * (series.xs.last() - series.xs.first())
                    var lo = 0
                    var hi = series.size - 1
                    while (lo < hi) {
                        val mid = (lo + hi) / 2
                        if (series.xs[mid] < target) lo = mid + 1 else hi = mid
                    }
                    return if (lo > 0 && abs(series.xs[lo - 1] - target) < abs(series.xs[lo] - target)) lo - 1 else lo
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    select.value(indexAt(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                            select.value(indexAt(change.position.x))
                        }
                    }
                    select.value(null)
                }
            }
            .drawWithCache {
                if (!series.isDrawable) return@drawWithCache onDrawBehind { }
                val left = LEFT_PAD.dp.toPx()
                val right = RIGHT_PAD.dp.toPx()
                val top = TOP_PAD.dp.toPx()
                val bottom = BOTTOM_PAD.dp.toPx()
                val plot = Size(size.width - left - right, size.height - top - bottom)

                var minY = Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (y in series.ys) if (!y.isNaN()) { if (y < minY) minY = y; if (y > maxY) maxY = y }
                if (zeroBased) minY = 0f
                if (maxY - minY < 1f) { maxY += 0.5f; minY -= 0.5f }
                val margin = (maxY - minY) * 0.08f
                val y0 = if (zeroBased) 0f else minY - margin
                val y1 = maxY + margin
                val x0 = series.xs.first()
                val x1 = series.xs.last().let { if (it > x0) it else x0 + 1f }

                fun px(i: Int) = left + (series.xs[i] - x0) / (x1 - x0) * plot.width
                fun py(v: Float) = top + (1f - (v - y0) / (y1 - y0)) * plot.height

                val line = Path()
                val area = Path()
                var started = false
                var lastX = 0f
                for (i in 0 until series.size) {
                    val v = series.ys[i]
                    if (v.isNaN()) continue
                    val x = px(i)
                    val y = py(v)
                    if (!started) {
                        line.moveTo(x, y); area.moveTo(x, top + plot.height); area.lineTo(x, y); started = true
                    } else {
                        line.lineTo(x, y); area.lineTo(x, y)
                    }
                    lastX = x
                }
                area.lineTo(lastX, top + plot.height)
                area.close()

                val fill = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.38f), lineColor.copy(alpha = 0.04f)), startY = top, endY = top + plot.height)
                val maxLabel = measurer.measure(formatY(maxY), labelStyle)
                val minLabel = measurer.measure(formatY(minY), labelStyle)
                val startLabel = measurer.measure(formatX(x0), labelStyle)
                val endLabel = measurer.measure(formatX(x1), labelStyle)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))

                onDrawBehind {
                    drawLine(axisColor, Offset(left, py(maxY)), Offset(left + plot.width, py(maxY)), strokeWidth = 1f, pathEffect = dash)
                    drawLine(axisColor, Offset(left, top + plot.height), Offset(left + plot.width, top + plot.height), strokeWidth = 1.5f)
                    if (filled) drawPath(area, fill)
                    drawPath(line, lineColor, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawText(maxLabel, topLeft = Offset(left - maxLabel.size.width - 6f, py(maxY) - maxLabel.size.height / 2f))
                    drawText(minLabel, topLeft = Offset(left - minLabel.size.width - 6f, py(minY) - minLabel.size.height / 2f))
                    drawText(startLabel, topLeft = Offset(left, top + plot.height + 4f))
                    drawText(endLabel, topLeft = Offset(left + plot.width - endLabel.size.width, top + plot.height + 4f))
                    val s = selected
                    if (s != null && s in 0 until series.size && !series.ys[s].isNaN()) {
                        val x = px(s)
                        val y = py(series.ys[s])
                        drawLine(markerColor.copy(alpha = 0.55f), Offset(x, top), Offset(x, top + plot.height), strokeWidth = 1.5f)
                        drawCircle(surface, radius = 6.dp.toPx(), center = Offset(x, y))
                        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
                    }
                }
            }
    )
}

private const val LEFT_PAD = 44
private const val RIGHT_PAD = 10
private const val TOP_PAD = 10
private const val BOTTOM_PAD = 20
