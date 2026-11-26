package fr.velogo.app.ui.ride

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.velogo.app.R
import fr.velogo.app.ui.components.HoldButton
import fr.velogo.app.ui.theme.GlassBox
import fr.velogo.app.ui.theme.GlassStyle
import fr.velogo.app.ui.theme.LocalGlass
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.app.ui.theme.bleed
import fr.velogo.core.ride.RideState

/** Gros boutons de la sortie, en pilules de verre : Démarrer, Pause / Reprendre, Terminer (à maintenir). */
@Composable
fun RideControls(
    state: RideState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onStopHint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlass.current
    val rideColors = LocalRideColors.current
    AnimatedContent(
        targetState = when (state) {
            RideState.IDLE -> 0
            RideState.PAUSED -> 2
            else -> 1
        },
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.94f)) using SizeTransform(clip = false)
        },
        // Pendant le fondu, ce qui dépasse de la zone animée est rogné : on l'agrandit de quoi loger les ombres.
        modifier = modifier.bleed(SHADOW_ROOM),
        label = "commandes",
    ) { mode ->
        Row(Modifier.padding(SHADOW_ROOM).fillMaxWidth().height(CONTROLS_HEIGHT), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (mode) {
                0 -> ControlButton("Démarrer", R.drawable.ic_play_arrow_filled, glass.accent, onStart, Modifier.weight(1f).fillMaxHeight(), large = true)

                else -> {
                    if (mode == 1) {
                        ControlButton("Pause", R.drawable.ic_pause_filled, glass.regular, onPause, Modifier.weight(1.15f).fillMaxHeight())
                    } else {
                        ControlButton("Reprendre", R.drawable.ic_play_arrow_filled, glass.accent, onResume, Modifier.weight(1.15f).fillMaxHeight())
                    }
                    HoldButton(
                        label = "Terminer",
                        icon = painterResource(R.drawable.ic_stop_filled),
                        container = rideColors.stop,
                        content = rideColors.onStop,
                        onConfirmed = onStop,
                        onShortTap = onStopHint,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(label: String, icon: Int, style: GlassStyle, onClick: () -> Unit, modifier: Modifier, large: Boolean = false) {
    GlassBox(modifier, style = style, onClick = onClick, role = Role.Button) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(if (large) 34.dp else 30.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall, maxLines = 1)
        }
    }
}

val CONTROLS_HEIGHT = 72.dp
private val SHADOW_ROOM = 20.dp
