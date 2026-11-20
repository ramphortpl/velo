package fr.velogo.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Bouton à maintenir appuyé pour confirmer (il se remplit pendant l'appui).
 * Sert à « Terminer » : impossible d'arrêter une sortie par un effleurement accidentel sur le guidon.
 */
@Composable
fun HoldButton(
    label: String,
    icon: Painter,
    container: Color,
    content: Color,
    onConfirmed: () -> Unit,
    onShortTap: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1100,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm = rememberUpdatedState(onConfirmed)
    val shortTap = rememberUpdatedState(onShortTap)
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .background(container.copy(alpha = 0.16f))
            .drawBehind { drawRect(container, size = Size(size.width * progress.value, size.height)) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = label) { confirm.value(); true }
            }
            .pointerInput(holdMillis) {
                detectTapGestures(
                    onPress = {
                        val filling = scope.launch {
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirm.value()
                        }
                        val released = tryAwaitRelease()
                        val completed = progress.value >= 1f
                        filling.cancel()
                        if (released && !completed) shortTap.value()
                        progress.animateTo(0f, tween(180))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val foreground = if (progress.value > 0.5f) content else container
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = foreground, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        }
    }
}
