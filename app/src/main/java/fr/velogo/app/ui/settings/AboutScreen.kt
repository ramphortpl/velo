package fr.velogo.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import fr.velogo.app.BuildConfig
import fr.velogo.app.R

private class Credit(val title: String, val text: String, val url: String)

private val DATA = listOf(
    Credit(
        "OpenStreetMap",
        "Données cartographiques © les contributeurs d'OpenStreetMap, sous licence ODbL. Toutes les cartes de VeloGo en dérivent, sauf les fonds de l'IGN.",
        "https://www.openstreetmap.org/copyright",
    ),
    Credit(
        "OpenFreeMap",
        "Fond « Plan » : tuiles vectorielles OpenFreeMap © OpenMapTiles, données OpenStreetMap.",
        "https://openfreemap.org",
    ),
    Credit(
        "CyclOSM",
        "Fond « CyclOSM » : rendu cycliste d'OpenStreetMap, tuiles servies par OpenStreetMap France. Consultation en ligne uniquement, sans téléchargement en masse.",
        "https://www.cyclosm.org",
    ),
    Credit(
        "Protomaps",
        "Carte « Vélo hors ligne » : fond de carte Protomaps (données OpenStreetMap), style et icônes sous licence BSD. La surcouche vélo est produite par VeloGo à partir d'OpenStreetMap.",
        "https://protomaps.com",
    ),
    Credit(
        "Mapterhorn",
        "Relief de la carte hors ligne, altitude et pente sans réseau : MNT LiDAR HD et RGE ALTI® de l'IGN (Licence Ouverte 2.0), assemblés par Mapterhorn.",
        "https://mapterhorn.com/attribution",
    ),
    Credit(
        "IGN – Géoplateforme",
        "Plan IGN, photos aériennes, recherche d'adresses et de lieux, altitudes (RGE ALTI®) : Institut national de l'information géographique et forestière, Licence Ouverte 2.0.",
        "https://cartes.gouv.fr",
    ),
    Credit(
        "Open-Meteo et Météo-France",
        "Météo : prévisions fournies par Open-Meteo.com (licence CC BY 4.0) d'après les modèles AROME et ARPEGE de Météo-France (Licence Ouverte 2.0). Seule ta position arrondie au kilomètre est envoyée, sans compte ni identifiant.",
        "https://open-meteo.com",
    ),
)

private val SOFTWARE = listOf(
    Credit("MapLibre Native", "Moteur d'affichage des cartes. Licence BSD à 2 clauses.", "https://maplibre.org"),
    Credit("Android Jetpack et Kotlin", "Jetpack Compose, Room, DataStore, Navigation ; langage Kotlin. Licence Apache 2.0.", "https://developer.android.com/jetpack"),
    Credit("OkHttp", "Client HTTP de Square. Licence Apache 2.0.", "https://square.github.io/okhttp/"),
    Credit("BRouter", "Calcul des itinéraires vélo, embarqué dans l'appli : il fonctionne sans réseau à partir des données OpenStreetMap des régions téléchargées. Licence MIT.", "https://brouter.de"),
    Credit("Planetiler", "Fabrique la surcouche vélo des cartes hors ligne (sur les serveurs de GitHub, pas dans l'appli). Licence Apache 2.0.", "https://github.com/onthegomap/planetiler"),
    Credit("Barlow Condensed", "Police des chiffres, par Jeremy Tribby. SIL Open Font License 1.1.", "https://github.com/jpt/barlow"),
    Credit("Noto Sans", "Police des noms sur la carte hors ligne. SIL Open Font License 1.1.", "https://fonts.google.com/noto"),
    Credit("Material Symbols", "Icônes de Google. Licence Apache 2.0.", "https://fonts.google.com/icons"),
)

/** Sources de données et logiciels utilisés : les mentions obligatoires, en clair. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources et licences") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Retour") } },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Le logo, toujours sur une plaque blanche : son vert foncé ne se lirait pas sur le thème sombre.
            Surface(shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Image(
                    painterResource(R.drawable.logo_velogo),
                    contentDescription = "Logo de VeloGo",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp).height(104.dp),
                )
            }
            Text(
                "VeloGo ${BuildConfig.VERSION_NAME} — appli personnelle, gratuite et sans compte. Tes sorties restent sur ton téléphone.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Cartes et données", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            DATA.forEach { CreditCard(it) { openUrl(context, it.url) } }
            Text("Logiciels", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            SOFTWARE.forEach { CreditCard(it) { openUrl(context, it.url) } }
        }
    }
}

@Composable
private fun CreditCard(credit: Credit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(credit.title, style = MaterialTheme.typography.titleMedium)
            Text(credit.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(credit.url.removePrefix("https://").removePrefix("www."), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
