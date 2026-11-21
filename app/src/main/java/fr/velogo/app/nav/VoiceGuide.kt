package fr.velogo.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fr.velogo.core.nav.Announcement
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fait entendre et sentir les consignes de guidage.
 *
 * Musique : c'est le seul endroit de l'appli qui touche au son. Pendant une consigne (deux à trois secondes), on
 * demande à Android de **baisser** le volume des autres applis (« ducking »), jamais de les mettre en pause ;
 * le volume remonte dès que la phrase est finie. Si la voix est désactivée, rien n'est demandé du tout.
 */
class VoiceGuide(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    private val pending = AtomicInteger(0)
    private var counter = 0

    /** Vrai si une voix française est disponible sur le téléphone (connu après [prepare]). */
    @Volatile
    var frenchAvailable: Boolean = true
        private set

    /** Prépare la synthèse vocale (quelques dixièmes de seconde) : à appeler au début d'un guidage avec la voix. */
    fun prepare() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                frenchAvailable = false
                return@TextToSpeech
            }
            val result = engine.setLanguage(Locale.FRANCE)
            frenchAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finished()

                @Deprecated("Ancienne signature, encore appelée par certains moteurs")
                override fun onError(utteranceId: String?) = finished()
                override fun onError(utteranceId: String?, errorCode: Int) = finished()
            })
            ready = frenchAvailable
        }
    }

    /** Dit une consigne en baissant brièvement la musique. Sans effet tant que la voix n'est pas prête. */
    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready) return
        if (pending.getAndIncrement() == 0) audio?.requestAudioFocus(focusRequest)
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, "velogo-${counter++}")
        if (result != TextToSpeech.SUCCESS) finished()
    }

    /** Signale une consigne par une vibration, reconnaissable sans regarder l'écran. */
    fun vibrate(kind: Announcement.Kind) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (kind) {
            Announcement.Kind.PREPARE -> VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            Announcement.Kind.NOW -> VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 220), -1)
            Announcement.Kind.OFF_ROUTE -> VibrationEffect.createWaveform(longArrayOf(0, 140, 90, 140, 90, 140, 90, 140), -1)
            Announcement.Kind.ARRIVED -> VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
            } else {
                v.vibrate(effect)
            }
        }
    }

    /** Libère la synthèse vocale et rend le son aux autres applis. */
    fun release() {
        ready = false
        tts?.let { engine ->
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
        tts = null
        pending.set(0)
        audio?.abandonAudioFocusRequest(focusRequest)
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio?.abandonAudioFocusRequest(focusRequest)
        }
    }
}
