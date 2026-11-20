package fr.velogo.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.velogo.app.ui.theme.LabelCapsStyle
import fr.velogo.app.ui.theme.NumberStyle

/**
 * Un nombre dessiné aussi grand que possible dans la place disponible.
 *
 * La taille est calculée sur [template] (par exemple « 88,8 ») et non sur la valeur affichée : le nombre ne
 * change donc pas de taille quand il passe de 9,9 à 10,0. Les chiffres ont tous la même largeur.
 */
@Composable
fun FittedNumber(
    text: String,
    template: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    align: Alignment.Horizontal = Alignment.CenterHorizontally,
    style: TextStyle = NumberStyle,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Spacer(
        modifier
            .semantics { contentDescription = text }
            .drawWithCache {
                val reference = measurer.measure(template, style.copy(fontSize = REFERENCE_PX.toSp()), softWrap = false, maxLines = 1)
                val widthPerPx = reference.size.width / REFERENCE_PX
                // Hauteur utile d'une ligne de chiffres : hauteur des capitales + la place de la virgule.
                val fontPx = minOf(size.height / LINE_EM, if (widthPerPx > 0f) size.width / widthPerPx else size.height).coerceAtLeast(1f)
                val layout = measurer.measure(text, style.copy(fontSize = fontPx.toSp()), softWrap = false, maxLines = 1)
                val x = when (align) {
                    Alignment.Start -> 0f
                    Alignment.End -> size.width - layout.size.width
                    else -> (size.width - layout.size.width) / 2f
                }
                val baseline = (size.height - LINE_EM * fontPx) / 2f + (TOP_EM + CAP_EM) * fontPx
                val y = baseline - layout.firstBaseline
                onDrawBehind { drawText(layout, color = color, topLeft = Offset(x, y)) }
            }
    )
}

// Proportions de la police Barlow Condensed, en fraction du corps (em).
private const val CAP_EM = 0.70f
private const val TOP_EM = 0.04f
private const val DESCENT_EM = 0.11f
private const val LINE_EM = TOP_EM + CAP_EM + DESCENT_EM
private const val REFERENCE_PX = 200f

/** Une donnée de l'écran guidon : étiquette en capitales, grand nombre, unité. */
@Composable
fun MetricCell(
    label: String,
    value: String,
    unit: String,
    template: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, style = LabelCapsStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Bottom) {
            FittedNumber(
                text = value,
                template = template,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = valueColor,
                align = Alignment.Start,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = LabelCapsStyle.copy(fontSize = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                    maxLines = 1,
                )
            }
        }
    }
}
