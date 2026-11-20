package fr.velogo.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fr.velogo.app.AppContainer
import fr.velogo.app.BuildConfig
import fr.velogo.app.R
import fr.velogo.app.data.Settings
import fr.velogo.app.ui.ride.batteryExemptionIntent
import fr.velogo.app.ui.ride.brandSegmentColors
import fr.velogo.app.ui.ride.isIgnoringBatteryOptimizations
import fr.velogo.app.ui.theme.ThemeMode
import fr.velogo.app.update.UpdateInfo
import fr.velogo.app.ui.veloViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class UpdateCheck { IDLE, CHECKING, UP_TO_DATE, FAILED }

/** Ce que l'écran des réglages peut demander. */
interface SettingsActions {
    fun setTheme(mode: ThemeMode)
    fun setSunlight(value: Boolean)
    fun setKeepScreenOn(value: Boolean)
    fun setMaxBrightness(value: Boolean)
    fun setAutoPause(value: Boolean)
    fun setIgnCorrection(value: Boolean)
    fun setCheckUpdates(value: Boolean)
    fun checkNow()
}

class SettingsViewModel(private val container: AppContainer) : ViewModel(), SettingsActions {
    val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val update = container.update

    private val _check = MutableStateFlow(UpdateCheck.IDLE)
    val check: StateFlow<UpdateCheck> = _check

    override fun setTheme(mode: ThemeMode) { viewModelScope.launch { container.settings.setThemeMode(mode) } }
    override fun setSunlight(value: Boolean) { viewModelScope.launch { container.settings.setSunlightDashboard(value) } }
    override fun setKeepScreenOn(value: Boolean) { viewModelScope.launch { container.settings.setKeepScreenOn(value) } }
    override fun setMaxBrightness(value: Boolean) { viewModelScope.launch { container.settings.setMaxBrightness(value) } }
    override fun setAutoPause(value: Boolean) { viewModelScope.launch { container.settings.setAutoPause(value) } }
    override fun setIgnCorrection(value: Boolean) { viewModelScope.launch { container.settings.setIgnAltitudeCorrection(value) } }
    override fun setCheckUpdates(value: Boolean) { viewModelScope.launch { container.settings.setCheckUpdates(value) } }

    override fun checkNow() {
        viewModelScope.launch {
            _check.value = UpdateCheck.CHECKING
            val found = container.checkForUpdate()
            _check.value = when {
                found == null -> UpdateCheck.FAILED
                found.isNewer -> UpdateCheck.IDLE
                else -> UpdateCheck.UP_TO_DATE
            }
        }
    }
}

@Composable
fun SettingsScreen(onOpenAbout: () -> Unit, onOpenOfflineMaps: () -> Unit, contentPadding: PaddingValues) {
    val vm = veloViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // L'état des autorisations est relu à chaque retour sur l'écran (on revient souvent des réglages du téléphone).
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var notificationsOk by remember { mutableStateOf(notificationsGranted(context)) }
    LifecycleResumeEffect(Unit) {
        batteryOk = isIgnoringBatteryOptimizations(context)
        notificationsOk = notificationsGranted(context)
        onPauseOrDispose { }
    }

    SettingsContent(
        settings = settings,
        update = update,
        check = check,
        batteryOk = batteryOk,
        notificationsOk = notificationsOk,
        isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        actions = vm,
        onOpenAbout = onOpenAbout,
        onOpenOfflineMaps = onOpenOfflineMaps,
        contentPadding = contentPadding,
    )
}

/** Le contenu de l'écran, sans accès aux données : c'est lui qu'on vérifie en image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: Settings,
    update: UpdateInfo?,
    check: UpdateCheck,
    batteryOk: Boolean,
    notificationsOk: Boolean,
    isSamsung: Boolean,
    actions: SettingsActions,
    onOpenAbout: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val vm = actions

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Réglages") }, scrollBehavior = scrollBehavior) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            update?.let { info ->
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("VeloGo ${info.versionName} est disponible", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "Télécharge le fichier puis ouvre-le : la mise à jour s'installe par-dessus, sans rien effacer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { openUrl(context, info.apkUrl) }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Télécharger")
                        }
                    }
                }
            }

            Section("Pendant la sortie") {
                SwitchRow("Pause automatique", "Le chrono s'arrête quand tu es à l'arrêt et repart tout seul.", settings.autoPause, vm::setAutoPause)
                SwitchRow("Écran toujours allumé", "L'écran ne s'éteint pas pendant une sortie tant que VeloGo est affiché.", settings.keepScreenOn, vm::setKeepScreenOn)
                SwitchRow("Luminosité au maximum", "Pour mieux lire au soleil. Consomme plus de batterie.", settings.maxBrightness, vm::setMaxBrightness)
                SwitchRow("Écran guidon « plein soleil »", "Chiffres noirs sur fond blanc pur, le plus lisible en extérieur.", settings.sunlightDashboard, vm::setSunlight)
            }

            Section("Cartes") {
                ActionRow(
                    "Cartes hors ligne",
                    "Télécharge tes régions pour rouler sans réseau : carte vélo, relief, altitude et pente précises.",
                    "Ouvrir",
                    onOpenOfflineMaps,
                )
            }

            Section("Affichage") {
                Text("Thème", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val options = listOf(ThemeMode.SYSTEM to "Automatique", ThemeMode.LIGHT to "Clair", ThemeMode.DARK to "Sombre")
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { vm.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            colors = brandSegmentColors(),
                        ) { Text(label) }
                    }
                }
            }

            Section("Altitude et dénivelé") {
                SwitchRow(
                    "Recaler sur le relief IGN en ligne",
                    "À la fin d'une sortie, l'altitude est corrigée avec le relief le plus fin de l'IGN (quelques secondes de réseau). Sans réseau, c'est le relief des cartes hors ligne qui sert. Sans l'un ni l'autre, le dénivelé vient du GPS seul et reste approximatif : ton téléphone n'a pas de baromètre.",
                    settings.ignAltitudeCorrection, vm::setIgnCorrection,
                )
            }

            Section("Fiabilité de l'enregistrement") {
                StatusRow(
                    title = "Économie de batterie",
                    ok = batteryOk,
                    okText = "VeloGo n'est pas restreinte : l'enregistrement continue écran éteint.",
                    koText = "Android peut endormir VeloGo écran éteint et couper l'enregistrement.",
                    action = "Autoriser",
                    onAction = { runCatching { context.startActivity(batteryExemptionIntent(context)) } },
                )
                if (isSamsung) {
                    ActionRow(
                        title = "Samsung : jamais en veille",
                        description = "Dans Batterie > Limites d'utilisation en arrière-plan, ajoute VeloGo aux applis jamais mises en veille.",
                        action = "Ouvrir",
                        onAction = { openSamsungNeverSleeping(context) },
                    )
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    StatusRow(
                        title = "Notification",
                        ok = notificationsOk,
                        okText = "La notification d'enregistrement est autorisée (elle est silencieuse).",
                        koText = "Sans notification, tu ne vois pas que l'enregistrement tourne et tu ne peux pas le mettre en pause depuis l'écran verrouillé.",
                        action = "Autoriser",
                        onAction = { openNotificationSettings(context) },
                    )
                }
            }

            Section("VeloGo") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (check) {
                                UpdateCheck.CHECKING -> "Recherche d'une mise à jour…"
                                UpdateCheck.UP_TO_DATE -> "Tu as la dernière version."
                                UpdateCheck.FAILED -> "Impossible de vérifier (pas de réseau ?)."
                                UpdateCheck.IDLE -> if (update != null) "Une version plus récente est disponible." else "Les mises à jour sont publiées sur GitHub."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { vm.checkNow() }, enabled = check != UpdateCheck.CHECKING) { Text("Vérifier") }
                }
                SwitchRow("Vérifier automatiquement", "Au lancement, au plus deux fois par jour.", settings.checkUpdates, vm::setCheckUpdates)
                ActionRow("Sources et licences", "Cartes, données et logiciels utilisés par VeloGo.", "Voir", onOpenAbout)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) { content() }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ActionRow(title: String, description: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun StatusRow(title: String, ok: Boolean, okText: String, koText: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning),
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (ok) okText else koText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) {
            Spacer(Modifier.width(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
}

/** Ouvre la liste Samsung des « applis jamais mises en veille », ou à défaut la fiche de l'appli. */
private fun openSamsungNeverSleeping(context: Context) {
    val samsung = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
        .setPackage("com.samsung.android.lool")
        .putExtra("activity_type", 2)
    val opened = runCatching { context.startActivity(samsung) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
}
