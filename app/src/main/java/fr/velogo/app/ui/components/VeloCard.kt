package fr.velogo.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.velogo.app.ui.theme.Corners
import fr.velogo.app.ui.theme.continuousRoundRect

/**
 * Dessine un fond à coins continus (voir [fr.velogo.app.ui.theme.ContinuousShape]) derrière le contenu, avec un
 * contour si [border] est précisé. Rien n'est rogné : le tracé est calculé une fois par taille puis simplement
 * rempli.
 */
fun Modifier.continuousBackground(color: Color, corner: Dp = Corners.Card, border: Color = Color.Unspecified, borderWidth: Dp = 1.5.dp): Modifier =
    drawWithCache {
        val path = continuousRoundRect(size, corner.toPx())
        val stroke = if (border.isSpecified) Stroke(borderWidth.toPx()) else null
        onDrawBehind {
            drawPath(path, color)
            if (stroke != null) drawPath(path, border, style = stroke)
        }
    }

/**
 * La carte de VeloGo : un fond uni aux coins continus, sans ombre, comme les groupes d'une liste d'iOS.
 * Avec [onClick], elle fonce légèrement sous le doigt (pas d'onde : il faudrait la rogner aux coins).
 *
 * Le contenu est placé comme dans une `Surface` de Material : il reçoit les contraintes minimales de la carte.
 */
@Composable
fun VeloCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = contentColorFor(color),
    corner: Dp = Corners.Card,
    /** Contour, pour une carte choisie parmi d'autres ou pour le thème « plein soleil ». */
    border: Color = Color.Unspecified,
    borderWidth: Dp = 1.5.dp,
    content: @Composable () -> Unit,
) {
    var box = modifier
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        // Vite foncée, lentement relâchée : l'appui se voit même s'il est bref.
        val shade = animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 70 else 260), label = "appui")
        val press = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        box = box
            .drawWithCache {
                val path = continuousRoundRect(size, corner.toPx())
                val stroke = if (border.isSpecified) Stroke(borderWidth.toPx()) else null
                onDrawBehind {
                    drawPath(path, color)
                    val amount = shade.value
                    if (amount > 0f) drawPath(path, press.copy(alpha = press.alpha * amount))
                    if (stroke != null) drawPath(path, border, style = stroke)
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        box = box.continuousBackground(color, corner, border, borderWidth)
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(box, propagateMinConstraints = true) { content() }
    }
}
