package fr.velogo.app.ui.ride

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.velogo.app.location.LocationAccess
import fr.velogo.app.recording.RecordingService
import fr.velogo.app.ui.LocalContainer
import fr.velogo.app.ui.findActivity

/** Ce que l'écran peut demander au « parcours de démarrage ». */
class StartRide(
    /** Ce que l'appli a le droit de savoir de la position : rien, à peu près, ou exactement. */
    val access: LocationAccess,
    /** Demande seulement l'autorisation de position exacte (pour afficher la carte). */
    val requestLocationPermission: () -> Unit,
    /** Lance une sortie, en demandant au passage ce qui manque. */
    val start: () -> Unit,
)

private enum class StartDialog { LOCATION_DENIED, LOCATION_OFF, GPS_OFF, BATTERY }

/**
 * Enchaîne tout ce qu'il faut avant de démarrer une sortie, sans jamais bloquer inutilement :
 * 1. autorisation de position exacte (obligatoire) ; 2. localisation activée (obligatoire) ; 3. GPS en service
 * (on prévient, sans bloquer) ; 4. autorisation de notification (conseillée) ; 5. dispense d'économie de batterie
 * (conseillée, une seule fois).
 */
@Composable
fun rememberStartRide(
    batteryHintShown: Boolean,
    onBatteryHintShown: () -> Unit,
    onStarted: () -> Unit,
): StartRide {
    val context = LocalContext.current
    val gps = LocalContainer.current.gps

    val activity = remember(context) { context.findActivity() }
    var access by remember { mutableStateOf(gps.access()) }
    var dialog by remember { mutableStateOf<StartDialog?>(null) }
    var wantsStart by remember { mutableStateOf(false) }
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    var batteryAsked by rememberSaveable { mutableStateOf(false) }
    /** On a déjà prévenu, pour cette sortie, que le GPS du téléphone est coupé. */
    var gpsWarned by remember { mutableStateOf(false) }
    val proceed = remember { mutableStateOf({ }) }

    /** L'autorisation a pu changer (fenêtre d'Android, retour des réglages) : l'écoute de la position suit. */
    fun refreshAccess(): LocationAccess {
        val now = gps.access()
        if (now != access) {
            access = now
            gps.onPermissionChanged()
        }
        return now
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = refreshAccess() == LocationAccess.PRECISE
        when {
            granted -> if (wantsStart) proceed.value()
            wantsStart -> {
                wantsStart = false
                dialog = StartDialog.LOCATION_DENIED
            }
            // Après deux refus, Android ne montre plus sa fenêtre : sans cette explication, le bouton
            // « Autoriser » semblerait ne rien faire. Seuls les réglages de l'appli permettent alors d'autoriser.
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION) ->
                dialog = StartDialog.LOCATION_DENIED
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (wantsStart) proceed.value()
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (wantsStart) proceed.value()
    }

    proceed.value = {
        when {
            !gps.hasPermission() ->
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

            !gps.isLocationEnabled() -> {
                wantsStart = false
                dialog = StartDialog.LOCATION_OFF
            }

            // Localisation « active » mais GPS coupé (mode économe de certains téléphones) : rien ne s'enregistrerait.
            !gpsWarned && !gps.isGpsEnabled() -> {
                wantsStart = false
                dialog = StartDialog.GPS_OFF
            }

            Build.VERSION.SDK_INT >= 33 && !notificationAsked &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                notificationAsked = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            !batteryHintShown && !batteryAsked && !isIgnoringBatteryOptimizations(context) -> {
                batteryAsked = true
                dialog = StartDialog.BATTERY
            }

            else -> {
                wantsStart = false
                gpsWarned = false
                RecordingService.start(context)
                onStarted()
            }
        }
    }

    // Au retour des réglages du téléphone, l'autorisation a pu changer.
    LifecycleResumeEffect(Unit) {
        refreshAccess()
        onPauseOrDispose { }
    }

    when (dialog) {
        StartDialog.LOCATION_DENIED -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Position nécessaire") },
            text = { Text("Sans la position précise, VeloGo ne peut ni afficher où tu es ni enregistrer ta sortie. Tu peux l'autoriser dans les réglages de l'appli : Autorisations > Position > « Lorsque l'appli est en cours d'utilisation », avec la position exacte.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Ouvrir les réglages") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Plus tard") } },
        )

        StartDialog.LOCATION_OFF -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Localisation désactivée") },
            text = { Text("La localisation du téléphone est coupée. Active-la pour démarrer la sortie.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }) { Text("Activer") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annuler") } },
        )

        StartDialog.GPS_OFF -> {
            val present = remember { gps.isGpsPresent() }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (present) "GPS coupé" else "Pas de GPS") },
                text = {
                    Text(
                        if (present) "La localisation du téléphone est active, mais sans le GPS : il est réglé sur un mode économe (« Économie de batterie » ou « Réseau uniquement », par exemple). Sans le GPS, la sortie ne s'enregistre pas. Dans les réglages de localisation, choisis le mode « Haute précision »."
                        else "Cet appareil n'a pas de GPS : la sortie ne pourra pas s'enregistrer."
                    )
                },
                confirmButton = {
                    if (present) {
                        TextButton(onClick = {
                            dialog = null
                            runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        }) { Text("Ouvrir les réglages") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog = null
                        gpsWarned = true
                        wantsStart = true
                        proceed.value()
                    }) { Text("Démarrer quand même") }
                },
            )
        }

        StartDialog.BATTERY -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Enregistrer écran éteint") },
            text = { Text("Pour que l'enregistrement ne s'arrête pas quand l'écran est éteint, autorise VeloGo à ne pas être mis en veille par l'économie de batterie. L'appli ne consomme que pendant une sortie.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    onBatteryHintShown()
                    wantsStart = true
                    val launched = runCatching { batteryLauncher.launch(batteryExemptionIntent(context)) }.isSuccess
                    if (!launched) proceed.value()
                }) { Text("Autoriser") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = null
                    onBatteryHintShown()
                    wantsStart = true
                    proceed.value()
                }) { Text("Plus tard") }
            },
        )

        null -> Unit
    }

    return StartRide(
        access = access,
        requestLocationPermission = {
            wantsStart = false
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        start = {
            wantsStart = true
            proceed.value()
        },
    )
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

/** Fenêtre système « Autoriser l'appli à toujours s'exécuter en arrière-plan ? ». */
@SuppressLint("BatteryLife")
fun batteryExemptionIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
