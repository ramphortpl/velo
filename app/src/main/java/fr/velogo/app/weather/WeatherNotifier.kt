package fr.velogo.app.weather

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.velogo.app.MainActivity
import fr.velogo.app.R

/**
 * La notification d'alerte pluie : elle dit pourquoi le téléphone vient de vibrer quand il est dans la poche.
 * Elle est silencieuse (la vibration est faite à part), pour ne jamais toucher à la musique.
 */
class WeatherNotifier(private val context: Context) {

    fun rain(title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel()
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_velogo)
            .setColor(ContextCompat.getColor(context, R.color.velogo_emerald))
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_rain_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Une alerte pluie n'a plus d'intérêt une fois l'averse passée.
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_weather), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notification_channel_weather_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "meteo"
        const val NOTIFICATION_ID = 2
        const val TIMEOUT_MS = 45 * 60_000L
    }
}
