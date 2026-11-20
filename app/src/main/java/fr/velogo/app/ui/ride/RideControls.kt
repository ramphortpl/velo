package fr.velogo.app.ui.ride

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import fr.velogo.app.R
import fr.velogo.app.ui.components.HoldButton
import fr.velogo.app.ui.theme.LocalRideColors
import fr.velogo.core.ride.RideState

/** Gros boutons de la sortie : Démarrer, Pause / Reprendre, Terminer (à maintenir). */
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
    val shape = RoundedCornerShape(22.dp)
    val rideColors = LocalRideColors.current
    AnimatedContent(
        targetState = when (state) {
            RideState.IDLE -> 0
            RideState.PAUSED -> 2
            else -> 1
        },
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.94f)) },
        modifier = modifier,
        label = "commandes",
    ) { mode ->
        Row(Modifier.fillMaxWidth().height(CONTROLS_HEIGHT), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (mode) {
                0 -> Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = shape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(painterResource(R.drawable.ic_play_arrow_filled), contentDescription = null, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Démarrer", style = MaterialTheme.typography.headlineMedium)
                }

                else -> {
                    if (mode == 1) {
                        FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1.15f).fillMaxHeight(), shape = shape) {
                            Icon(painterResource(R.drawable.ic_pause_filled), contentDescription = null, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pause", style = MaterialTheme.typography.headlineSmall)
                        }
                    } else {
                        Button(onClick = onResume, modifier = Modifier.weight(1.15f).fillMaxHeight(), shape = shape) {
                            Icon(painterResource(R.drawable.ic_play_arrow_filled), contentDescription = null, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reprendre", style = MaterialTheme.typography.headlineSmall)
                        }
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

val CONTROLS_HEIGHT = 72.dp
