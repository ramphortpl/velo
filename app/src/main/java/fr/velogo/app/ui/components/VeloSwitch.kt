package fr.velogo.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import fr.velogo.app.ui.theme.Brand

/**
 * L'interrupteur, à la manière d'iOS : une rainure qui passe au vert vif du logo et un bouton blanc qui glisse.
 *
 * Il ne fait que montrer l'état : c'est la ligne entière du réglage qui se touche et qui l'annonce aux lecteurs
 * d'écran (`toggleable`). Pendant le glissement, lui seul est redessiné.
 */
@Composable
fun VeloSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val position = animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = 520f), label = "interrupteur")
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    Spacer(
        modifier.size(width = 52.dp, height = 32.dp).drawBehind {
            val at = position.value
            drawRoundRect(lerp(off, ON, at.coerceIn(0f, 1f)), cornerRadius = CornerRadius(size.height / 2f))
            val gap = 2.5.dp.toPx()
            val diameter = size.height - 2 * gap
            val center = Offset(gap + diameter / 2f + (size.width - 2 * gap - diameter) * at, size.height / 2f)
            // Une ombre à peine visible sous le bouton : il se détache de la rainure éteinte, presque blanche elle aussi.
            drawCircle(Color.Black.copy(alpha = 0.12f), radius = diameter / 2f + 0.75.dp.toPx(), center = center.copy(y = center.y + 1.dp.toPx()))
            drawCircle(Color.White, radius = diameter / 2f, center = center)
        }
    )
}

private val ON = Brand.Fresh
