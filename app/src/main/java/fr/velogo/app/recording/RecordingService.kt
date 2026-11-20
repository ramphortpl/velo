package fr.velogo.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import fr.velogo.app.MainActivity
import fr.velogo.app.R
import fr.velogo.app.VeloGoApp
import fr.velogo.core.format.Formats
import fr.velogo.core.ride.RideState
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service de premier plan qui enregistre la sortie, écran allumé ou éteint, appli visible ou non.
 *
 * - Type « localisation » : Android le laisse recevoir le GPS en arrière-plan tant que sa notification est là.
 * - Aucun son, aucune prise de focus audio : la musique n'est jamais coupée ni baissée.
 * - Il doit être démarré pendant que l'appli est à l'écran (règle d'Android) : c'est le bouton « Démarrer ».
 */
class RecordingService : LifecycleService() {

    private val container get() = (application as VeloGoApp).container
    private var wakeLock: PowerManager.WakeLock? = null
    private var recordingJob: Job? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> {
                startRecording()
                lifecycleScope.launch { container.recorder.pause(); updateNotification() }
            }
            ACTION_RESUME -> {
                startRecording()
                lifecycleScope.launch { container.recorder.resume(); updateNotification() }
            }
            // ACTION_START, ou redémarrage par le système (intent nul) : on (re)prend l'enregistrement.
            else -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (stopping) return
        if (!promoteToForeground()) {
            stopSelf()
            return
        }
        acquireWakeLock()
        if (recordingJob?.isActive == true) return
        recordingJob = lifecycleScope.launch {
            val recorder = container.recorder
            recorder.startOrRestore(container.settings.settings.first().autoPause)
            updateNotification()

            launch {
                container.gps.fixes.collect { gps ->
                    // Une position restée en mémoire depuis une session précédente n'a rien à faire dans la trace.
                    if (SystemClock.elapsedRealtime() - gps.fix.monoMs <= MAX_FIX_AGE_MS) recorder.onFix(gps.fix)
                }
            }
            launch {
                container.settings.settings.map { it.autoPause }.distinctUntilChanged().collect { recorder.setAutoPause(it) }
            }
            launch {
                var lastState: RideState? = null
                recorder.snapshot.map { it.state }.distinctUntilChanged().collect { state ->
                    if (lastState != null) updateNotification()
                    lastState = state
                }
            }

            var seconds = 0
            var silentSeconds = 0
            while (isActive) {
                delay(1_000)
                recorder.tick()
                seconds++
                if (seconds % NOTIFICATION_PERIOD_S == 0) updateNotification()
                // Chien de garde : si le GPS se tait trop longtemps, on relance l'écoute.
                silentSeconds = if (recorder.snapshot.value.hasSignal) 0 else silentSeconds + 1
                if (silentSeconds >= GPS_WATCHDOG_S) {
                    silentSeconds = 0
                    container.gps.restart()
                }
            }
        }
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        lifecycleScope.launch {
            recordingJob?.cancelAndJoin()
            recordingJob = null
            container.recorder.stop()
            releaseWakeLock()
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    /** Passe au premier plan. Échoue si l'autorisation de localisation manque ou si Android refuse le démarrage. */
    private fun promoteToForeground(): Boolean {
        if (!container.gps.hasPermission()) return false
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Empêche le processeur de s'endormir écran éteint. Android réveille bien l'appli à chaque position GPS,
     * mais sur les téléphones Samsung c'est une ceinture en plus des bretelles ; le coût est négligeable
     * devant celui du GPS lui-même.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VeloGo:enregistrement").apply {
            setReferenceCounted(false)
            acquire(MAX_RIDE_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_channel_recording_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        if (stopping) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val snapshot = container.recorder.snapshot.value
        val paused = snapshot.state == RideState.PAUSED
        val title = when (snapshot.state) {
            RideState.PAUSED -> getString(R.string.notification_paused)
            RideState.AUTO_PAUSED -> getString(R.string.notification_auto_paused)
            else -> getString(R.string.notification_recording)
        }
        val text = getString(
            R.string.notification_summary,
            Formats.distanceKm(snapshot.totals.distanceM),
            Formats.duration(snapshot.totals.movingMs),
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, if (paused) 2 else 1,
            Intent(this, RecordingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_velogo)
            .setColor(ContextCompat.getColor(this, R.color.velogo_emerald))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (paused) R.drawable.ic_play_arrow_filled else R.drawable.ic_pause_filled,
                getString(if (paused) R.string.action_resume else R.string.action_pause),
                toggle,
            )
            .build()
    }

    companion object {
        const val ACTION_START = "fr.velogo.app.action.DEMARRER"
        const val ACTION_PAUSE = "fr.velogo.app.action.PAUSE"
        const val ACTION_RESUME = "fr.velogo.app.action.REPRENDRE"
        const val ACTION_STOP = "fr.velogo.app.action.TERMINER"

        private const val CHANNEL_ID = "enregistrement"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_PERIOD_S = 5
        private const val GPS_WATCHDOG_S = 45
        private const val MAX_FIX_AGE_MS = 5_000L
        private const val MAX_RIDE_MS = 24 * 60 * 60 * 1000L

        /** Démarre (ou reprend) l'enregistrement. À appeler quand l'appli est à l'écran. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }
}
