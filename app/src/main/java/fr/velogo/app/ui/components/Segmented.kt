package fr.velogo.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.velogo.app.ui.theme.GlassDefaults
import fr.velogo.app.ui.theme.LocalGlass
import fr.velogo.app.ui.theme.drawGlassBody
import fr.velogo.app.ui.theme.glass

/**
 * Choix entre quelques options, à la manière d'iOS : une rainure en forme de pilule et, sous l'option choisie,
 * une pastille verte qui glisse de l'une à l'autre.
 *
 * Pendant le glissement seule la pastille est redessinée : rien n'est recomposé ni remesuré.
 *
 * [floating] : le sélecteur est posé sur la carte ; sa rainure est alors en verre, avec une ombre.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    height: Dp = 42.dp,
) {
    val glass = LocalGlass.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position = animateFloatAsState(index.toFloat(), spring(dampingRatio = 0.8f, stiffness = 560f), label = "sélecteur")
    val groove = if (floating) Modifier.glass(glass.regular, reach = GlassDefaults.SmallReach)
    else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
    val idle = if (floating) glass.regular.content else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .height(height)
            .then(groove)
            .drawBehind {
                val gap = GAP.toPx()
                val cell = (size.width - 2 * gap) / options.size
                drawGlassBody(
                    style = glass.accent,
                    topLeft = Offset(gap + cell * position.value, gap),
                    size = Size(cell, size.height - 2 * gap),
                    radius = (size.height - 2 * gap) / 2f,
                )
            }
            .padding(GAP)
            .selectableGroup(),
    ) {
        options.forEachIndexed { at, (value, label) ->
            val chosen = at == index
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (chosen) glass.accent.content else idle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

private val GAP = 4.dp
