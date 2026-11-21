package fr.velogo.app.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/**
 * Un graphique en barres : une barre par période (semaine, mois, année), la plus récente à droite.
 * Un appui sur une barre la sélectionne ; sa valeur s'affiche au-dessus d'elle.
 */
@Composable
fun BarChart(
    values: List<Float>,
    /** Étiquette de chaque barre (« 16 nov. », « nov. », « 2026 ») ; seules quelques-unes sont écrites, faute de place. */
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    barColor: Color,
    modifier: Modifier = Modifier,
    formatValue: (Float) -> String = { it.toInt().toString() },
    /** Phrase lue par les lecteurs d'écran à la place du dessin. */
    description: String = "",
) {
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val idleColor = barColor.copy(alpha = 0.38f)
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selectedLabelStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val valueStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val select = rememberUpdatedState(onSelect)
    val count = values.size

    Spacer(
        modifier
            .semantics { if (description.isNotEmpty()) contentDescription = description }
            .pointerInput(count) {
                if (count == 0) return@pointerInput
                detectTapGestures { position ->
                    val slot = size.width.toFloat() / count
                    select.value((position.x / slot).toInt().coerceIn(0, count - 1))
                }
            }
            .drawWithCache {
                if (count == 0) return@drawWithCache onDrawBehind { }
                val top = TOP_PAD.dp.toPx()
                val bottom = BOTTOM_PAD.dp.toPx()
                val plotHeight = size.height - top - bottom
                val slot = size.width / count
                val barWidth = (slot * 0.68f).coerceAtMost(40.dp.toPx())
                val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
                val radius = CornerRadius(5.dp.toPx())
                val minBar = 3.dp.toPx()
                // Pas plus de six ou sept étiquettes, toujours celle de droite (la période en cours).
                val step = ceil(count / 6.0).toInt().coerceAtLeast(1)
                val texts = labels.mapIndexed { i, label ->
                    if (i == selected || (count - 1 - i) % step == 0) measurer.measure(label, if (i == selected) selectedLabelStyle else labelStyle, softWrap = false, maxLines = 1) else null
                }
                val selectedValue = values.getOrNull(selected)?.let { measurer.measure(formatValue(it), valueStyle, softWrap = false, maxLines = 1) }
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))

                onDrawBehind {
                    drawLine(axisColor, Offset(0f, top), Offset(size.width, top), strokeWidth = 1f, pathEffect = dash)
                    drawLine(axisColor, Offset(0f, top + plotHeight), Offset(size.width, top + plotHeight), strokeWidth = 1.5f)
                    for (i in 0 until count) {
                        val value = values[i]
                        val center = slot * (i + 0.5f)
                        // Une période sans sortie garde un trait au sol : on voit qu'elle existe.
                        val height = if (value <= 0f) minBar else (value / max * plotHeight).coerceAtLeast(minBar)
                        drawRoundRect(
                            color = if (i == selected) barColor else idleColor,
                            topLeft = Offset(center - barWidth / 2, top + plotHeight - height),
                            size = Size(barWidth, height),
                            cornerRadius = radius,
                        )
                        // L'étiquette de la barre choisie passe devant ses voisines.
                        val text = texts[i] ?: continue
                        val neighbourSelected = selected != i && texts.getOrNull(selected) != null && kotlin.math.abs(selected - i) * slot < text.size.width + 6.dp.toPx()
                        if (neighbourSelected) continue
                        val x = (center - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                        drawText(text, topLeft = Offset(x, top + plotHeight + 5.dp.toPx()))
                    }
                    if (selectedValue != null && selected in 0 until count) {
                        val center = slot * (selected + 0.5f)
                        val height = if (values[selected] <= 0f) minBar else (values[selected] / max * plotHeight).coerceAtLeast(minBar)
                        val x = (center - selectedValue.size.width / 2f).coerceIn(0f, size.width - selectedValue.size.width)
                        drawText(selectedValue, topLeft = Offset(x, (top + plotHeight - height - selectedValue.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
                    }
                }
            }
    )
}

private const val TOP_PAD = 24
private const val BOTTOM_PAD = 24
