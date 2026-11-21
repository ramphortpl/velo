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
 * Fait entendre et sentir les consignes de guidage et l'alerte pluie.
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

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    /** Vrai tant qu'un guidage se sert de la voix : elle reste alors prête entre deux consignes. */
    private var keep = false
    /** Phrases demandées pendant que la synthèse vocale démarre : elles sont dites dès qu'elle est prête. */
    private val waiting = ArrayList<String>()
    private val pending = AtomicInteger(0)
    private var counter = 0

    /** Vrai si une voix française est disponible sur le téléphone (connu après [prepare]). */
    @Volatile
    var frenchAvailable: Boolean = true
        private set

    /** Prépare la synthèse vocale (quelques dixièmes de seconde) : à appeler au début d'un guidage avec la voix. */
    fun prepare() {
        synchronized(lock) {
            keep = true
            ensureEngine()
        }
    }

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    /** La synthèse vocale a fini de démarrer (ou n'a pas pu) : on dit ce qui attendait. */
    private fun onInit(status: Int) {
        val toSay: List<String> = synchronized(lock) {
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                // Pas de synthèse vocale utilisable sur ce téléphone (ou guidage arrêté entre-temps).
                if (status != TextToSpeech.SUCCESS) frenchAvailable = false
                ready = false
                waiting.clear()
                return@synchronized emptyList()
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
            val said = if (ready) ArrayList(waiting) else emptyList()
            waiting.clear()
            said
        }
        if (toSay.isEmpty()) idle() else toSay.forEach { speak(it) }
    }

    /**
     * Dit une phrase en baissant brièvement la musique. Si la synthèse vocale n'est pas encore prête, la phrase
     * est dite dès qu'elle l'est ; sans voix française sur le téléphone, rien n'est dit.
     */
    fun speak(text: String) {
        val engine = synchronized(lock) {
            ensureEngine()
            if (!ready) {
                // Voix en cours de démarrage : on garde la phrase (les plus anciennes sont abandonnées).
                if (frenchAvailable) {
                    if (waiting.size >= MAX_WAITING) waiting.removeAt(0)
                    waiting.add(text)
                }
                return
            }
            tts ?: return
        }
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

    /** Trois impulsions (deux brèves, une longue) : de la pluie approche. */
    fun vibrateRain() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 110, 90, 110, 90, 420), -1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
            } else {
                v.vibrate(effect)
            }
        }
    }

    /** Fin du guidage : libère la synthèse vocale et rend le son aux autres applis. */
    fun release() {
        synchronized(lock) {
            keep = false
            shutdown()
        }
    }

    private fun shutdown() {
        ready = false
        waiting.clear()
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
            idle()
        }
    }

    /** Plus rien à dire : hors guidage (alerte pluie isolée), la synthèse vocale est refermée aussitôt. */
    private fun idle() {
        synchronized(lock) {
            if (!keep && pending.get() == 0 && waiting.isEmpty()) shutdown()
        }
    }

    private companion object {
        const val MAX_WAITING = 3
    }
}
