package fr.velogo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.velogo.app.data.Settings
import fr.velogo.app.ui.AppRoot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as VeloGoApp).container
        // Les réglages (dont le thème) sont lus avant le premier affichage : l'écran d'accueil reste jusque-là,
        // ce qui évite un éclair de mauvais thème au démarrage.
        val settings = container.settings.settings
            .map<Settings, Settings?> { it }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        splash.setKeepOnScreenCondition { settings.value == null }

        setContent {
            val current by settings.collectAsStateWithLifecycle()
            current?.let { AppRoot(container, it) }
        }
    }
}
