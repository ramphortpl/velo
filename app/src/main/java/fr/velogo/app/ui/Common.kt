package fr.velogo.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.velogo.app.AppContainer
import fr.velogo.app.VeloGoApp

/** Accès aux objets partagés de l'appli depuis n'importe quel écran. */
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer non fourni") }

/** Crée (ou retrouve) un ViewModel construit à partir du conteneur de l'appli. */
@Composable
inline fun <reified VM : ViewModel> veloViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM =
    viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create((this[APPLICATION_KEY] as VeloGoApp).container) }
        },
    )

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
